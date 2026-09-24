package com.panapods.ble

import android.bluetooth.*
import android.content.Context
import android.os.Build
import com.panapods.utils.PanaLog
import com.panapods.protocol.RacePacket
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Airoha BLE GATT 客户端
 *
  * 负责与松下/Technics EAH-AZ 系列耳机建立 BLE GATT 连接，
  * 管理 Race 协议的收发。
 *
  * 参考原始类:
 * - com.airoha.android.lib.physical.ble.AirohaBleController
 * - com.airoha.liblinker.AirohaLinker
 *
 * 写队列已抽到 [GattWriteQueue]，连接重试退避抽到 [ConnectRetryPolicy]。
 */
class AirohaBleClient(
    private val context: Context,
    initialListener: Listener
) {
    companion object {
        private const val TAG = "PanaPods/BleClient"
        // Write watchdog timeout: if onCharacteristicWrite is not called within this window
        // (write dropped by BT stack or callback lost), force-reset isWriting to avoid a
        // permanently stalled write queue (root cause of intermittent ANC no-response).
        private const val WRITE_TIMEOUT_MS = 2000L
        // connectGatt 后一直收不到 STATE_CONNECTED 的回调时（HyperOS 常见）强制收尾并重试，
        // 避免 Service 永远停留在 connecting 状态。
        private const val CONNECT_ESTABLISH_TIMEOUT_MS = 15_000L
        // v176：CCC 被拒走“假定通知仍活跃”路径时的兑现校验窗口（代码注释承诺过
        // “如 3s 内收不到任何数据再报错”，此前从未实现）。假定错误 → 连接看似已建立
        // 却永不来数据，Service 重连看门狗查到 GATT 仍连着也不会重连 → 永久僵死。
        private const val CCC_ASSUMED_NO_DATA_TIMEOUT_MS = 3_000L
    }

    interface Listener {
        fun onConnected()
        fun onDisconnected()
        fun onRacePacketReceived(packet: RacePacket)
        fun onError(message: String)
    }

    // 可置空监听器：旧连接在 detach() 后不再向 Service 回调，防止
    // 异步的 GATT 回调（如延迟到达的 STATE_DISCONNECTED）污染新连接的状态。
    @Volatile
    private var listener: Listener? = initialListener
    @Volatile
    private var detached = false

    private var bluetoothGatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private var readChar: BluetoothGattCharacteristic? = null
    private var negotiatedMtu = AirohaUuid.MIN_MTU
    private val isConnected = AtomicBoolean(false)
    private var isNotifying = false
        // 幂等守卫：LE Audio 栈会对同一连接回调两次 onMtuChanged，导致 discoverServices 跑两遍。
        // 两个 CCC 描述符写并发 → 第二个写会拿 status=13。保证每次连接只发现一次服务、只写一次描述符。
    private var mtuHandled = false
    private var servicesHandled = false
        // 持久 ACL 会话（划掉后只断开重连时 ACL 未释放）上，onMtuChanged 会在 t=0 自发触发，
        // 立刻带起 discoverServices —— 早于 refresh 生效，拿到失效句柄 → CCC 写 status=13。
        // 用此门闩：只处理 refresh 之后我们主动请求的那次 MTU 回调，忽略自发回调。
    private var mtuRequested = false
    private var lastAddress: String? = null
    private val retryHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val retryPolicy = ConnectRetryPolicy(retryHandler)
    private val writeQueue = GattWriteQueue(
        handler = retryHandler,
        writeTimeoutMs = WRITE_TIMEOUT_MS,
        tag = TAG,
        canWrite = { isNotifying && writeChar != null && bluetoothGatt != null },
        write = { data ->
            val gatt = bluetoothGatt
            val char = writeChar
            if (gatt == null || char == null) {
                false
            } else {
                char.value = data
                gatt.writeCharacteristic(char)
            }
        }
    )
        // 断开后重连控制：disconnect() 是异步的，必须等 STATE_DISCONNECTED 回调后才 close，
        // 否则 BT 栈内部状态不一致，重连拿到残留缓存 → status=13。
    private var pendingRetryAfterDisconnect = false
    private var userDisconnecting = false
    // v176：CCC 被拒、假定通知仍活跃后是否还欠一次“窗口内零数据”校验
    //（见 [cccAssumedNoDataWatchdog]，兑现 onDescriptorWrite 里承诺的 3s 兜底）。
    @Volatile private var cccAssumedCheckPending = false
    private val cccAssumedNoDataWatchdog = Runnable {
        if (detached || !isConnected.get() || !cccAssumedCheckPending) return@Runnable
        cccAssumedCheckPending = false
        PanaLog.e(
            TAG,
            "CCC assumed but no notification data within ${CCC_ASSUMED_NO_DATA_TIMEOUT_MS}ms, tearing down for retry"
        )
        // 假定失败：拆链交给 Service 侧按退避重连，而不是永远停在“GATT 已连但永不来数据”。
        failGatt("notifications assumed but no data in ${CCC_ASSUMED_NO_DATA_TIMEOUT_MS}ms")
    }

    // Connect watchdog: connectGatt succeeded but the BT stack never delivered
    // STATE_CONNECTED. Close the half-open GATT and either auto-retry or report failure
    // so the service can fall back to its own watchdog.
    private val connectWatchdog = Runnable {
        if (detached || isConnected.get()) return@Runnable
        PanaLog.w(TAG, "connectGatt establishment timeout, force closing half-open GATT")
        val gatt = bluetoothGatt
        bluetoothGatt = null
        writeChar = null
        readChar = null
        writeQueue.clear()
        if (gatt != null) {
            runCatching { gatt.disconnect() }
            runCatching { gatt.close() }
        }
        if (detached) return@Runnable
        val delay = retryPolicy.scheduleRetry {
            lastAddress?.let { addr -> connectInternal(addr, resetRetry = false) }
        }
        if (delay >= 0) {
            PanaLog.i(TAG, "connect establishment failed (no callback), auto-retry ${retryPolicy.count}/${retryPolicy.maxRetries} in ${delay}ms")
        } else {
            PanaLog.e(TAG, "connect establishment failed (no callback), max retries reached, giving up")
            listener?.onDisconnected()
        }
    }

    /**
     * 回调参数是否属于「已被替换/强制关闭的旧 GATT」。
     *
     * [connectWatchdog]、[disconnect] 的 2s 强制收尾、以及 [connectInternal] 重连前的
     * close，都会先把 [bluetoothGatt] 置空/换新，但 BT 栈仍会把旧 gatt 的 STATE_* 回调
     * 迟到投递过来。这类回调必须整体忽略，否则它会 removeCallbacksAndMessages(null)
     * 杀掉新连接刚排下的 MTU 请求与连接看门狗、清空新 gatt 的特征引用，并让新 gatt
     * 脱管（close 的是旧对象）→ GATT 客户端泄漏。
     *
     * 注：[bluetoothGatt] 在 connectGatt 返回后同一线程立即赋值，而回调经主线程投递，
     * 故「当前 gatt 的回调」不可能早于该赋值到达，`!==` 判断不会误伤正常流程。
     */
    private fun isStaleGatt(gatt: BluetoothGatt): Boolean = gatt !== bluetoothGatt

    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (isStaleGatt(gatt)) {
                PanaLog.d(TAG, "stale onConnectionStateChange ignored (status=$status newState=$newState)")
                return
            }
            PanaLog.d(TAG, "onConnectionStateChange: status=$status newState=$newState")
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    PanaLog.i(TAG, "GATT Connected, refreshing cache...")
                    retryHandler.removeCallbacks(connectWatchdog)
                                        // v91: 先上报清缓存的尝试，但失败也不一定是致命错误
                    refreshDeviceCache(gatt)
                                        // refresh() 异步清缓存：忽略自发的 onMtuChanged，延迟 700ms 后主动请求 MTU，
                                        // 仅在那条回调上才发起服务发现，确保 refresh 已生效、拿到新句柄。
                    retryHandler.postDelayed({
                        val g = bluetoothGatt ?: return@postDelayed
                        mtuRequested = true
                        PanaLog.i(TAG, "requesting MTU ${AirohaUuid.TARGET_MTU} (post-refresh)")
                        g.requestMtu(AirohaUuid.TARGET_MTU)
                                                // 兜底：部分栈 MTU 变化时不回调 onMtuChanged，500ms 后仍未发现则直接发现服务
                        retryHandler.postDelayed({
                            val g2 = bluetoothGatt ?: return@postDelayed
                            if (!mtuHandled) {
                                mtuHandled = true
                                PanaLog.i(TAG, "MTU callback timeout, discovering services directly")
                                g2.discoverServices()
                            }
                        }, 500)
                    }, 700)
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    PanaLog.i(TAG, "GATT Disconnected (status=$status)")
                    // Capture whether we ever fully connected (CCC enabled) before resetting state.
                    // Used to distinguish a genuine drop from a failed connection establishment.
                    val wasConnected = isConnected.get()
                                        // v91: 感知 status 错误码，帮助定位问题
                    when (status) {
                        GattStatus.SUCCESS -> PanaLog.i(TAG, "  reason: normal disconnection")
                        GattStatus.INVALID_ATTRIBUTE_LENGTH -> PanaLog.w(TAG, "  reason: GATT_INVALID_ATTRIBUTE_LENGTH (refresh/CCC failure)")
                        GattStatus.CONN_TERMINATE_PEER_USER -> PanaLog.w(TAG, "  reason: GATT_CONN_TERMINATE_PEER_USER (device hung up)")
                        GattStatus.CONN_TERMINATE_LOCAL_HOST -> PanaLog.w(TAG, "  reason: GATT_CONN_TERMINATE_LOCAL_HOST (local hung up)")
                        GattStatus.CONN_FAIL_ESTABLISH -> PanaLog.w(TAG, "  reason: GATT_CONN_FAIL_ESTABLISH (unable to establish GATT connection)")
                        GattStatus.CONN_UNKNOWN -> PanaLog.e(TAG, "  reason: GATT_CONN_UNKNOWN (HyperOS 4 compatibility or permission issue)")
                        else -> PanaLog.w(TAG, "  reason: unknown status=$status")
                    }
                    retryHandler.removeCallbacksAndMessages(null)
                    isConnected.set(false)
                    isNotifying = false
                    mtuHandled = false
                    mtuRequested = false
                    servicesHandled = false
                    writeChar = null
                    readChar = null
                    // Reset write state and drop stale queued packets: if the drop happened
                    // mid-write (isWriting=true), a reconnect would otherwise see isWriting
                    // stuck true and writeNext() would return early forever (queue deadlock).
                    writeQueue.clear()
                                        // 断开后 refresh 清缓存（此时 ACL 已断，refresh 能真正生效）
                    refreshDeviceCache(gatt)
                    gatt.close()
                    bluetoothGatt = null
        
                    if (pendingRetryAfterDisconnect) {
                                                // CCC 写失败触发的重连：等 2s 让 BT 栈彻底释放旧会话再重连
                        pendingRetryAfterDisconnect = false
                        PanaLog.i(TAG, "Disconnect complete, will retry in 2s...")
                        retryHandler.postDelayed({
                            lastAddress?.let { addr ->
                                PanaLog.i(TAG, "Retry connect to $addr (post-disconnect)")
                                connectInternal(addr, resetRetry = false)
                            }
                        }, 2000)
                    } else if (userDisconnecting) {
                        userDisconnecting = false
                        listener?.onDisconnected()
                    } else if (!wasConnected && status != GattStatus.SUCCESS && retryPolicy.canRetry()) {
                        // Connection establishment failed (e.g. status=133 GATT_CONN_FAIL_ESTABLISH,
                        // 147, 22). This is the common intermittent "slow to connect" cause on Android.
                        // Auto-retry with backoff instead of giving up; keep the service in the
                        // connecting state (do NOT call onDisconnected) so the UI stays "connecting".
                        val delay = retryPolicy.scheduleRetry {
                            lastAddress?.let { addr -> connectInternal(addr, resetRetry = false) }
                        }
                        if (delay >= 0) {
                            PanaLog.i(TAG, "Connect establish failed (status=$status), auto-retry ${retryPolicy.count}/${retryPolicy.maxRetries} in ${delay}ms")
                        }
                    } else {
                        listener?.onDisconnected()
                    }
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            if (isStaleGatt(gatt)) {
                PanaLog.d(TAG, "stale onMtuChanged ignored: mtu=$mtu status=$status")
                return
            }
            // status 失败时部分栈会回调 mtu=0：无条件赋值会让 sendPacket 算出
            // maxPayload = 0-3 = -3 → copyOfRange(0, -3) 抛 IAE（调用方在主线程直接崩）。
            if (status == BluetoothGatt.GATT_SUCCESS && mtu >= AirohaUuid.MIN_MTU) {
                negotiatedMtu = mtu
            } else {
                PanaLog.w(TAG, "onMtuChanged failed (status=$status mtu=$mtu), keeping MTU=$negotiatedMtu")
            }
                        // 忽略持久会话中 t=0 自发的 MTU 回调（早于 refresh 生效），只认我们主动请求的那次
            if (!mtuRequested) {
                PanaLog.d(TAG, "spontaneous onMtuChanged ignored: mtu=$mtu")
                return
            }
            if (mtuHandled) {
                PanaLog.d(TAG, "onMtuChanged ignored (already handled): mtu=$mtu")
                return
            }
            mtuHandled = true
            PanaLog.i(TAG, "MTU negotiated: $mtu, status: $status")
            gatt.discoverServices()
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (isStaleGatt(gatt)) {
                PanaLog.d(TAG, "stale onServicesDiscovered ignored")
                return
            }
            if (status != BluetoothGatt.GATT_SUCCESS) {
                failGatt("Service discovery failed: $status")
                return
            }
            if (servicesHandled) {
                PanaLog.d(TAG, "onServicesDiscovered ignored (already handled)")
                return
            }
            servicesHandled = true

            // 查找 Airoha MMI Service
            val service = gatt.getService(UUID.fromString(AirohaUuid.SERVICE_MMI))
            if (service == null) {
                failGatt("Airoha MMI Service not found")
                return
            }

            // 获取 Read (RX/Notify) 和 Write (TX) 特征
            readChar = service.getCharacteristic(UUID.fromString(AirohaUuid.CHAR_MMI_READ))
            writeChar = service.getCharacteristic(UUID.fromString(AirohaUuid.CHAR_MMI_WRITE))

            if (readChar == null || writeChar == null) {
                failGatt("Airoha MMI Characteristics not found")
                return
            }

                        // 启用通知：必须等 onDescriptorWrite 成功后才能认为链路可用
            gatt.setCharacteristicNotification(readChar, true)
            val descriptor = readChar!!.getDescriptor(
                UUID.fromString(AirohaUuid.DESCRIPTOR_CCC)
            )
            if (descriptor != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                } else {
                    @Suppress("DEPRECATION")
                    descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    @Suppress("DEPRECATION")
                    gatt.writeDescriptor(descriptor)
                }
                PanaLog.i(TAG, "Writing CCC descriptor to enable notification...")
            } else {
                failGatt("CCC descriptor not found, notification cannot be enabled")
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            if (isStaleGatt(gatt)) {
                PanaLog.d(TAG, "stale onCharacteristicChanged ignored")
                return
            }
            // v176：任何通知数据（含解析失败的）都证明通知链路是活的 → 兑现 CCC 假定校验。
            if (cccAssumedCheckPending) {
                cccAssumedCheckPending = false
                retryHandler.removeCallbacks(cccAssumedNoDataWatchdog)
                PanaLog.i(TAG, "CCC assumption verified by incoming notification data")
            }
                        // 收到耳机通知数据，解析为 RacePacket
            val packet = RacePacket.fromBytes(value)
            if (packet != null) {
                if (PanaLog.enabled) {
                    val hex = value.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }
                    PanaLog.d(TAG, "RX: $hex")
                    PanaLog.d(TAG, "Received: $packet")
                }
                listener?.onRacePacketReceived(packet)
            } else {
                val hex = value.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }
                PanaLog.w(TAG, "Failed to parse packet: $hex")
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (isStaleGatt(gatt)) {
                PanaLog.d(TAG, "stale onCharacteristicWrite ignored (status=$status)")
                return
            }
            PanaLog.d(TAG, "Write status: $status")
            // Write finished: cancel watchdog and send the next queued chunk.
            // status != SUCCESS 时不视为正常完成，交给队列按失败重试/丢弃处理，
            // 否则写失败（0x85 等）与成功同等对待，调用方无从察觉命令其实没发出去。
            writeQueue.onWriteCompleted(status == BluetoothGatt.GATT_SUCCESS)
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            if (isStaleGatt(gatt)) {
                PanaLog.d(TAG, "stale onDescriptorWrite ignored (status=$status)")
                return
            }
            PanaLog.d(TAG, "Descriptor write status: $status")
            if (status != BluetoothGatt.GATT_SUCCESS) {
                                // status=13 (GATT_INVALID_ATTRIBUTE_LENGTH) 在此设备上的真正含义：
                                // 系统 LE Audio 维持 ACL 链路不断，远端设备仍保有上一会话的 CCC 订阅，
                                // 重新写 ENABLE_NOTIFICATION_VALUE 被拒。但通知实际上仍在流通。
                                // 策略：假定通知已激活，直接尝试正常通信。如 3s 内收不到任何数据再报错。
                PanaLog.w(TAG, "CCC write rejected ($status), assuming notifications still active from previous session")
                retryPolicy.reset()
                isNotifying = true
                isConnected.set(true)
                PanaLog.i(TAG, "Airoha BLE connected (CCC assumed), MTU=$negotiatedMtu")
                listener?.onConnected()
                // v176：兑现上面“如 3s 内收不到任何数据再报错”的承诺——排一次性校验：
                // 窗口内一条通知都没收到就拆链重试，否则假定错误时连接看似建立却永不来数据。
                cccAssumedCheckPending = true
                retryHandler.removeCallbacks(cccAssumedNoDataWatchdog)
                retryHandler.postDelayed(cccAssumedNoDataWatchdog, CCC_ASSUMED_NO_DATA_TIMEOUT_MS)
                writeQueue.flush()
                return
            }
                        // 成功 → 重置重试计数
            retryPolicy.reset()
            if (isNotifying) return
            isNotifying = true
            isConnected.set(true)
            PanaLog.i(TAG, "Airoha BLE connected, notification enabled, MTU=$negotiatedMtu")
            listener?.onConnected()
                        // 通知已启用，冲刷连接建立期积压的写队列
            writeQueue.flush()
        }
    }

    /**
          * 连接到指定蓝牙地址的耳机
     */
    fun connect(address: String): Boolean = connectInternal(address, resetRetry = true)

    /**
     * 与 Service 解除绑定：清空监听器并停止内部重试/超时任务。
     * 调用后本客户端不再产生任何对 Service 的回调，异步到达的 GATT 回调全部安全忽略。
     * 用于快速重连时让旧连接安静退出，避免旧连接的 STATE_DISCONNECTED 覆盖新连接状态。
     */
    fun detach() {
        detached = true
        listener = null
        retryHandler.removeCallbacksAndMessages(null)
    }

    private fun connectInternal(address: String, resetRetry: Boolean): Boolean {
        if (detached) {
            PanaLog.w(TAG, "connectInternal: client detached, refusing to reconnect")
            return false
        }
        // v174：蓝牙关闭 / 服务不可用时 getSystemService 可能返回 null 或类型不符，
        // 旧写法强转 + 非空断言会直接抛 CCE/NPE 崩掉连接流程，这里改为安全取值。
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = runCatching { manager?.adapter }.getOrNull()
        if (adapter == null || !adapter.isEnabled) {
            listener?.onError("BluetoothAdapter not available")
            return false
        }

        val device = adapter.getRemoteDevice(address)
        if (bluetoothGatt != null) {
            PanaLog.w(TAG, "connectInternal: previous GATT exists, closing before reconnect")
            runCatching { bluetoothGatt?.close() }
            bluetoothGatt = null
            writeChar = null
            readChar = null
        }

        // Drop any stale queued packets from a previous session; a reconnect must
        // never inherit a half-written queue or a stuck isWriting flag.
        writeQueue.clear()
        isNotifying = false
        isConnected.set(false)
        mtuHandled = false
        mtuRequested = false
        servicesHandled = false
        lastAddress = address
        if (resetRetry) retryPolicy.reset()
        
                // v92: HyperOS 4 兼容性修复 —— 尝试不使用 TRANSPORT_LE 参数
                // 验证方案：查看是否是 TRANSPORT_LE 导致 connectGatt 回调不来
        val gatt = try {
            PanaLog.d(TAG, "connectGatt without explicit TRANSPORT parameter (SDK ${Build.VERSION.SDK_INT})")
                        // v92: 移除 TRANSPORT_LE，使用系统默认
            device.connectGatt(context, false, gattCallback)
        } catch (e: Throwable) {
            PanaLog.e(TAG, "connectGatt() threw exception: ${e.message}", e)
            listener?.onError("connectGatt failed: ${e.javaClass.simpleName} ${e.message}")
            return false
        }
        
        if (gatt == null) {
            PanaLog.e(TAG, "connectGatt returned null - HyperOS 4 compatibility issue or Bluetooth disabled")
            listener?.onError("connectGatt returned null (Bluetooth may be disabled)")
            return false
        }
        
        bluetoothGatt = gatt
        retryHandler.removeCallbacks(connectWatchdog)
        // v176：新会话开始，作废上一会话遗留的 CCC 假定校验——直连路径（上一会话
        // 异常未走 STATE_DISCONNECTED 回调）不会触发 removeCallbacksAndMessages。
        retryHandler.removeCallbacks(cccAssumedNoDataWatchdog)
        cccAssumedCheckPending = false
        retryHandler.postDelayed(connectWatchdog, CONNECT_ESTABLISH_TIMEOUT_MS)
        PanaLog.i(TAG, "Connecting to $address...")
        return true
    }

    /**
     * 服务发现/特征获取失败时主动断开 GATT，避免 PanaBleService 长期停在 connecting。
     */
    private fun failGatt(reason: String) {
        PanaLog.e(TAG, reason)
        listener?.onError(reason)
        if (bluetoothGatt != null) {
            disconnect()
        }
    }

    /**
          * 通过反射清空 GATT 属性缓存 (BluetoothGatt.refresh 为隐藏方法)。
          * 进程异常退出后系统仍缓存上一会话的属性句柄，重连写描述符会命中失效句柄 (status=13)。
          * v91: HyperOS 4 可能禁用或改变此操作，改为可选且非关键操作。
     */
    private fun refreshDeviceCache(gatt: BluetoothGatt): Boolean {
        return try {
            val refresh = gatt.javaClass.getMethod("refresh")
            val result = refresh.invoke(gatt) as? Boolean ?: false
            PanaLog.d(TAG, "GATT cache refresh result=$result")
            result
        } catch (e: NoSuchMethodException) {
            PanaLog.w(TAG, "GATT refresh method not found (expected on HyperOS 4)")
            false
        } catch (e: SecurityException) {
            PanaLog.w(TAG, "GATT refresh blocked by system (HyperOS 4 restriction): ${e.message}")
            false
        } catch (e: Exception) {
            PanaLog.w(TAG, "GATT refresh failed: ${e.message}")
            false
        }
    }

    /**
          * 断开连接
     *
          * 正确流程：disconnect() → 等 STATE_DISCONNECTED 回调 → refresh() → close()。
          * 如果直接 disconnect()+close() 不等回调，BT 栈内部断开未完成就释放了 GATT 客户端，
          * 重连时 ACL 链路上残留旧会话状态（缓存句柄/CCC 状态）→ 写 CCC status=13。
     */
    fun disconnect() {
        retryHandler.removeCallbacksAndMessages(null)
        retryPolicy.reset()
        pendingRetryAfterDisconnect = false
        cccAssumedCheckPending = false
        isNotifying = false
        isConnected.set(false)
        // Always drop the write queue and reset the write state on disconnect,
        // even when bluetoothGatt is already null, so a later reconnect starts clean.
        writeQueue.clear()
        val gatt = bluetoothGatt
        if (gatt != null) {
            userDisconnecting = true
            gatt.disconnect()
            // 安全超时：STATE_DISCONNECTED 不回调时 2s 后强制 close
            retryHandler.postDelayed({
                if (userDisconnecting) {
                    userDisconnecting = false
                    PanaLog.w(TAG, "Disconnect timeout, force close")
                    bluetoothGatt?.close()
                    bluetoothGatt = null
                    writeChar = null
                    readChar = null
                    listener?.onDisconnected()
                }
            }, 2000)
        } else {
            writeChar = null
            readChar = null
        }
    }

    /**
          * 发送 RacePacket 到耳机
     */
    fun sendPacket(packet: RacePacket): Boolean {
        val data = packet.toBytes()
        if (PanaLog.enabled) {
            val hex = data.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }
            PanaLog.d(TAG, "TX: $hex")
        }

                // 分包: 如果数据超过 (MTU - 3)，需要分片发送
        // 下限 20：MTU 协商失败/被拒时 negotiatedMtu 可能仍是 MIN_MTU 之外的异常值，
        // maxPayload <= 0 会让下面的 copyOfRange(offset, 负数) 抛 IllegalArgumentException。
        val maxPayload = maxOf(negotiatedMtu - 3, 20)
        if (data.size <= maxPayload) {
            return enqueueWrite(data)
        }

        // 分包逻辑：所有分片都成功入队才返回 true，避免调用方误判并登记 pending。
        var offset = 0
        var allQueued = true
        while (offset < data.size) {
            val end = minOf(offset + maxPayload, data.size)
            val chunk = data.copyOfRange(offset, end)
            if (!enqueueWrite(chunk)) allQueued = false
            offset = end
        }
        return allQueued
    }

    /**
     * 直接发送原始字节到写特征（协议调试页使用）。
     */
    fun sendRaw(data: ByteArray): Boolean {
        if (data.isEmpty()) return false
        return enqueueWrite(data)
    }

    private fun enqueueWrite(data: ByteArray): Boolean {
        if (bluetoothGatt == null) return false
        return writeQueue.enqueue(data)
    }

    /**
          * 当前是否已连接
     */
    fun isConnected(): Boolean = isConnected.get()
}
