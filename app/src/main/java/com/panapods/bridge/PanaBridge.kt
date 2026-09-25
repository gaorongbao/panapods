package com.panapods.bridge

import com.panapods.headphones.AncMode

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import com.panapods.utils.PanaLog

/**
 * App ↔ Hook 状态桥接器
 *
 * 通过 Broadcast 在 App 进程 (BLE Service) 和 Xposed Hook 进程
 * (com.android.bluetooth / com.android.settings) 之间同步耳机状态。
 *
 * App 侧: publishState() 广播状态；
 * Hook 侧: registerStateReceiver() 接收广播并缓存 → 供 Hook 读取
 */
object PanaBridge {

    private const val TAG = "PanaBridge"

    // ============ 广播 Action ============
    const val ACTION_STATE_UPDATED = "com.panapods.bridge.STATE_UPDATED"
    const val ACTION_COMMAND = "com.panapods.bridge.COMMAND"
    // v113：渲染进程（:ui）→ 数据进程（:core）的卡片渲染信号。
    // :ui 里 ProfileContext.listener 通常为 null（nudge 静默失败），
    // 由 :ui 在卡片渲染时发此广播，让 :core 触发本地 nudge 刷新卡片 ANC 区块。
    const val ACTION_CARD_ASSEMBLED = "com.panapods.bridge.CARD_ASSEMBLED"

    // ============ 通用 Extra ============
    const val EXTRA_LEFT_BATTERY = "left_battery"
    const val EXTRA_RIGHT_BATTERY = "right_battery"
    const val EXTRA_CRADLE_BATTERY = "cradle_battery"
    const val EXTRA_ANC_MODE = "anc_mode"
    const val EXTRA_DEVICE_NAME = "device_name"
    const val EXTRA_IS_CONNECTED = "is_connected"
    const val EXTRA_MAC_ADDRESS = "mac_address"
    // v127：LC3/LE-Audio 副地址（用于 Hook 进程识别 LC3 副地址卡 → 跳过 patch 避免双卡）。
    const val EXTRA_LC3_MAC_ADDRESS = "lc3_mac_address"
    const val EXTRA_STATE_TOKEN = "state_token"
    const val STATE_TOKEN = "panapods-state-v1"

    // ============ 命令 Extra ============
    const val EXTRA_COMMAND = "command"
    const val EXTRA_CMD_ANC_MODE = "cmd_anc_mode"
    const val EXTRA_CMD_ANC_LEVEL = "cmd_anc_level"
    const val EXTRA_CMD_AMBIENT_LEVEL = "cmd_ambient_level"

    // ============ 命令鉴权 ============
    // 命令接收器 exported，第三方 App 也可能发送广播。Hook 进程与 App 同包同 APK，
    // 共享此 token；第三方 App 无法拿到，接收端校验可拒绝未授权命令。
    const val EXTRA_COMMAND_TOKEN = "cmd_token"
    const val COMMAND_TOKEN = "panapods-bridge-cmd-v1"

    /** 校验命令广播是否来自本模块（App 进程或注入了本模块的 Hook 进程）。 */
    fun isAuthorizedCommand(intent: Intent?): Boolean {
        return intent?.getStringExtra(EXTRA_COMMAND_TOKEN) == COMMAND_TOKEN
    }

    // ============ 命令类型 ============
    const val COMMAND_SET_ANC_MODE = "set_anc_mode"
    const val COMMAND_SYNC_ANC_MODE = "sync_anc_mode"

    // ============ Unified constants (avoid magic values scattered in hooks) ============
    const val PACKAGE_NAME = "com.panapods"
    const val COMMAND_RECEIVER_CLASS = "com.panapods.bridge.PanaCommandReceiver"

    /** Xiaomi TWS device ID used for spoofing (Settings.apk has full image resources + ANC UI) */
    const val MIUI_DEVICE_ID = "01010600"

    /** checkSupport string: bit-16 set to 1 means ANC is supported */
    const val MIUI_HEADSET_SUPPORT = "01010600,000000000000000010000000"

    /** 松下/Technics 耳机在系统卡片中的回退显示名（优先使用桥接缓存的真实设备名） */
    const val PANA_DISPLAY_NAME = "Technics EAH-AZ"

    // ============ Hook 侧缓存 (线程安全) ============
    @Volatile private var leftBattery: Int = -1
    @Volatile private var rightBattery: Int = -1
    @Volatile private var cradleBattery: Int = -1
    @Volatile private var ancMode: Int = -1
    @Volatile private var deviceName: String? = null
    @Volatile private var isConnected: Boolean = false
    @Volatile private var macAddress: String? = null
    @Volatile private var lc3MacAddress: String? = null
    @Volatile private var panaOuiPrefix: String? = null  // v127b：Pana OUI 前 3 字节，由 macAddress 自动推算
    @Volatile private var receiverRegistered: Boolean = false

    // ============ L1 缓存：设备地址识别（避免 device.name Binder IPC）============
    // 三级缓存结构，提升 Hook 性能：
    // - panaAddresses: 确认为 Pana 的地址集合（秒级查询）
    // - nonPanaAddresses: 确认不是 Pana 的地址集合
    private val panaAddresses = java.util.Collections.newSetFromMap(
        java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    )
    private val nonPanaAddresses = java.util.Collections.newSetFromMap(
        java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    )
    // ============ L2 缓存：活动设备与 ANC 本地状态 ============
    @Volatile private var classicPanaDevice: android.bluetooth.BluetoothDevice? = null
    @Volatile private var currentAncMode: Int = -1
    @Volatile private var lastAncClickAt: Long = 0L

    // ============ App 侧: 广播状态 ============

    /**
     * 广播最新状态 (BLE Service 侧调用)
     *
     * 这是隐式广播：Hook 进程（com.android.bluetooth / com.android.settings 等）
     * 中动态注册的 RECEIVER_EXPORTED 接收器依赖它接收状态。不能用
     * setPackage/setClassName 改成显式广播，否则 Hook 进程收不到。
     * Android 14 对跨应用隐式广播的限制由 Provider 轮询兜底。
     *
     * v177：只更新非 -1 的电量字段，避免 300ms 防抖窗口内中间态 -1(显示为 255) 覆盖
     * 旧有效值。anc/name/addr/connected 全量更新。
     */
    fun publishState(
        context: Context,
        left: Int, right: Int, cradle: Int,
        anc: Int, name: String?, addr: String?, connected: Boolean,
        lc3Addr: String? = null  // v127：副地址，可选（只有 LC3/LE-Audio 才有意义）
    ) {
        PanaLog.d(TAG, "publishState: L=$left R=$right C=$cradle anc=$anc addr=$addr lc3=$lc3Addr connected=$connected")
        val intent = Intent(ACTION_STATE_UPDATED).apply {
            putExtra(EXTRA_LEFT_BATTERY, normalizeBattery(left))
            putExtra(EXTRA_RIGHT_BATTERY, normalizeBattery(right))
            putExtra(EXTRA_CRADLE_BATTERY, normalizeBattery(cradle))
            putExtra(EXTRA_ANC_MODE, anc)
            putExtra(EXTRA_DEVICE_NAME, name)
            putExtra(EXTRA_MAC_ADDRESS, addr)
            putExtra(EXTRA_IS_CONNECTED, connected)
            putExtra(EXTRA_STATE_TOKEN, STATE_TOKEN)
            if (lc3Addr != null) putExtra(EXTRA_LC3_MAC_ADDRESS, lc3Addr)
        }
        runCatching {
            // 发送普通广播，使所有进程都能接收到（包括 Hook 进程）
            context.sendBroadcast(intent)
            PanaLog.d(TAG, "publishState broadcast sent from ${context.packageName}")
        }.onFailure { e ->
            PanaLog.e(TAG, "publishState broadcast failed", e)
        }
        // 同步更新本进程缓存：只覆盖非 -1 的电量，防止防抖窗口内中间态外泄
        if (left != -1) leftBattery = left
        if (right != -1) rightBattery = right
        if (cradle != -1) cradleBattery = cradle
        ancMode = anc
        deviceName = name
        macAddress = addr
        isConnected = connected
        if (!lc3Addr.isNullOrBlank() && !lc3Addr.equals(addr, ignoreCase = true)) {
            lc3MacAddress = lc3Addr
        }
        if (!addr.isNullOrBlank() && addr.length >= 8) {
            panaOuiPrefix = addr.substring(0, 8)
        }
        PanaLog.d(TAG, "Bridge cache updated: L=$leftBattery R=$rightBattery C=$cradleBattery anc=$ancMode connected=$isConnected")
    }

    /**
     * Unified "set ANC mode to app" channel used by all hooks:
     * 1) ContentProvider.call (bypasses Android 14 cross-app broadcast limits), then
     * 2) explicit broadcast to PanaCommandReceiver as fallback.
     *
     * @return true if the Provider.call path succeeded
     */
    fun sendAncModeToApp(context: Context?, mode: Int): Boolean {
        if (context == null || !AncMode.isValid(mode)) return false
        // Provider.call 成功时应返回非 null Bundle；权限不足/Provider 不可用时会返回 null，
        // 此时再走显式广播兜底，避免"命令实际未执行但误以为成功"。
        val providerBundle = runCatching {
            val extras = Bundle().apply { putInt(PanaPodsProvider.EXTRA_MODE, mode) }
            context.contentResolver.call(
                PanaPodsProvider.CONTENT_URI,
                PanaPodsProvider.METHOD_SET_ANC_MODE,
                mode.toString(),
                extras
            )
        }.getOrNull()
        if (providerBundle != null) {
            PanaLog.d(TAG, "sendAncModeToApp($mode) via Provider.call OK")
            return true
        }
        runCatching {
            val intent = Intent(ACTION_COMMAND).apply {
                setClassName(PACKAGE_NAME, COMMAND_RECEIVER_CLASS)
                putExtra(EXTRA_COMMAND, COMMAND_SET_ANC_MODE)
                putExtra(EXTRA_CMD_ANC_MODE, mode)
                putExtra(EXTRA_COMMAND_TOKEN, COMMAND_TOKEN)
            }
            context.sendBroadcast(intent)
            PanaLog.d(TAG, "sendAncModeToApp($mode) via broadcast OK")
        }.onFailure { e ->
            PanaLog.e(TAG, "sendAncModeToApp broadcast failed", e)
        }
        return false
    }

    // ============ Hook 侧: 接收并缓存 ============

    /**
     * 注册状态广播接收器 (供 Hook install 时调用, 每个进程只注册一次)
     */
    fun registerStateReceiver(context: Context?) {
        if (receiverRegistered || context == null) return
        try {
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context?, intent: Intent?) {
                    updateCacheFromIntent(intent)
                }
            }
            context.registerReceiver(receiver, IntentFilter(ACTION_STATE_UPDATED),
                Context.RECEIVER_EXPORTED)
            receiverRegistered = true
            PanaLog.i(TAG, "State receiver registered in ${context.packageName}")
        } catch (e: Exception) {
            PanaLog.e(TAG, "Failed to register state receiver", e)
        }
    }

    /**
     * 从 Intent 更新本进程缓存（供 PanaStateReceiver 和各 Hook 调用）。
     *
     * 返回 true 表示校验通过并已更新；false 表示未携带 state token，拒绝更新。
     * 防止第三方 App 伪造 STATE_UPDATED 广播污染 Hook 侧缓存。
     */
    fun updateCacheFromIntent(intent: Intent?): Boolean {
        if (intent == null) return false
        if (intent.getStringExtra(EXTRA_STATE_TOKEN) != STATE_TOKEN) {
            PanaLog.w(TAG, "Rejected state broadcast without valid token")
            return false
        }
        leftBattery = normalizeBattery(intent.getIntExtra(EXTRA_LEFT_BATTERY, -1))
        rightBattery = normalizeBattery(intent.getIntExtra(EXTRA_RIGHT_BATTERY, -1))
        cradleBattery = normalizeBattery(intent.getIntExtra(EXTRA_CRADLE_BATTERY, -1))
        ancMode = intent.getIntExtra(EXTRA_ANC_MODE, -1)
        deviceName = intent.getStringExtra(EXTRA_DEVICE_NAME)
        macAddress = intent.getStringExtra(EXTRA_MAC_ADDRESS)
        val addr = macAddress  // v127b：局部别名便于下面读
        isConnected = intent.getBooleanExtra(EXTRA_IS_CONNECTED, false)
        // v127：同步 LC3 副地址到本进程缓存（仅当广播携带时才更新，避免被空字符串覆盖）
        val newLc3 = intent.getStringExtra(EXTRA_LC3_MAC_ADDRESS)
        if (!newLc3.isNullOrBlank()) {
            // 与 macAddress 相同则不认为是副地址（避免经典/LC3 同步时互盖）
            if (!newLc3.equals(macAddress, ignoreCase = true)) {
                lc3MacAddress = newLc3
            }
        }
        // v175：断开时清掉 LC3 副地址。原先只增不清，换一副耳机后旧副地址仍参与
        // isSameDeviceAddress 匹配，可能把新设备误判成同一副（或命中旧耳机）。
        if (!isConnected) {
            lc3MacAddress = null
        }
        // v127b：自动从 macAddress 推算 OUI 前缀（"AC:DE:48,AB" → "AC:DE:48"）
        if (!addr.isNullOrBlank() && addr.length >= 8) {
            panaOuiPrefix = addr.substring(0, 8)
        }
        PanaLog.d(TAG, "Bridge updated: L=$leftBattery R=$rightBattery C=$cradleBattery connected=$isConnected")
        return true
    }

    /**
     * 直接更新本进程缓存（供 HyperOSHeadsetHook 从 ContentProvider 查询后使用）
     */
    fun publishStateToCache(
        left: Int, right: Int, cradle: Int,
        anc: Int, name: String?, addr: String?, connected: Boolean,
        lc3Addr: String? = null  // v127：可选副地址
    ) {
        leftBattery = normalizeBattery(left)
        rightBattery = normalizeBattery(right)
        cradleBattery = normalizeBattery(cradle)
        ancMode = anc
        deviceName = name
        macAddress = addr
        isConnected = connected
        if (!lc3Addr.isNullOrBlank() && !lc3Addr.equals(addr, ignoreCase = true)) {
            lc3MacAddress = lc3Addr
        }
        if (!addr.isNullOrBlank() && addr.length >= 8) {
            panaOuiPrefix = addr.substring(0, 8)
        }
        PanaLog.d(TAG, "Bridge cache set: L=$leftBattery R=$rightBattery C=$cradleBattery anc=$anc connected=$connected")
    }

    // ============ Hook 侧 读取 ============

    fun getLeftBattery(): Int = leftBattery
    fun getRightBattery(): Int = rightBattery
    fun getCradleBattery(): Int = cradleBattery
    fun getAncMode(): Int = ancMode
    fun getDeviceName(): String? = deviceName
    fun getMacAddress(): String? = macAddress
    fun getLc3MacAddress(): String? = lc3MacAddress
    fun getPanaOuiPrefix(): String? = panaOuiPrefix
    fun isConnected(): Boolean = isConnected

    /**
     * 记录同一副耳机在 LC3/LE-Audio 模式下的地址。
     * 该地址通常与经典蓝牙地址只有最后一个字节不同。
     */
    fun setLc3MacAddress(address: String?) {
        if (address.isNullOrBlank()) return
        if (macAddress.equals(address, ignoreCase = true)) return
        lc3MacAddress = address
        PanaLog.d(TAG, "LC3 address recorded: $address (classic=$macAddress)")
    }

    /**
     * 取左右耳平均电量 (用于系统 BluetoothDevice.getBatteryLevel())
     * 系统只能显示一个百分比, 取左右平均值最合理
     */
    fun getAverageBattery(): Int {
        // 只统计 0..100 的有效电量；单耳连接时缺失的那只耳（-1 或 255）
        // 不能参与平均，否则会把平均值拉成无效值（>100）导致系统不显示。
        val l = leftBattery.takeIf { it in 0..100 }
        val r = rightBattery.takeIf { it in 0..100 }
        return when {
            l != null && r != null -> (l + r) / 2
            l != null -> l
            r != null -> r
            else -> -1
        }
    }

    /** 统一归一化：只接受 0..100 的有效电量，其余（含 255=断开、-1=未知）一律 -1 */
    fun normalizeBattery(level: Int): Int = if (level in 0..100) level else -1

    /** 统一归一化：只接受 0..100 的有效电量，其余返回 null（供 UI/状态层使用）。 */
    fun normalizeBatteryOrNull(level: Int): Int? = level.takeIf { it in 0..100 }

    // ============ 设备识别 ============

    private val PANA_KEYWORDS = listOf("Technics", "EAH-AZ", "AZ100", "Panasonic")

    /**
     * 判断地址是否为 Pana 设备（优先使用 L1 缓存，三次达成一次 Hook 多次调用的优化）
     *
     * 流程：
     * 1. 地址 in panaAddresses → true（已检验，秒级响应）
     * 2. 地址 in nonPanaAddresses →false
     * 3. 设备名称查询 → 不会每次都调 device.name
     * 4. Bridge 缓存的上一会话名称，此次是同一设备 → true
     * 5. 返回 false 并缓存
     */

    /**
     * 记录 Pana 的经典 DUAL 地址（控制中心主卡片）
     */
    fun setClassicPanaDevice(device: android.bluetooth.BluetoothDevice?) {
        classicPanaDevice = device
        if (device != null) {
            panaAddresses.add(device.address.uppercase())
            PanaLog.d(TAG, "ClassicPanaDevice recorded: ${device.address}")
        }
    }

    /**
     * 公开 API: 获取 pana 地址缓存集合
     */
    fun isPanaByAddress(address: String?): Boolean {
        if (address == null) return false
        val addr = address.uppercase()
        return panaAddresses.contains(addr)
    }

    /**
     * 公开 API: 检查地址是否在正数据集中
     */
    fun isNonPanaByAddress(address: String?): Boolean {
        if (address == null) return false
        val addr = address.uppercase()
        return nonPanaAddresses.contains(addr)
    }

    /**
     * 公开 API: 注册 Pana 地址
     */
    fun addPanaAddress(address: String) {
        panaAddresses.add(address.uppercase())
    }

    /**
     * 公开 API: 注册非 Pana 地址
     */
    fun addNonPanaAddress(address: String) {
        nonPanaAddresses.add(address.uppercase())
    }

    /**
     * 上报 ANC 本地预判位置（用户点击后立即生效）
     */
    fun setCurrentAncMode(mode: Int) {
        currentAncMode = mode
        lastAncClickAt = System.currentTimeMillis()
        PanaLog.d(TAG, "Local ANC mode set: $mode")
    }

    /**
     * 获取最近的 ANC 本地状态（经过预判，未待耳机回复）
     */
    fun getCurrentAncMode(): Int = currentAncMode

    /**
     * 获取地址缓存信息（诊断用）
     */
    fun getCacheStats(): Map<String, Any> {
        return mapOf(
            "panaAddresses" to panaAddresses.size,
            "nonPanaAddresses" to nonPanaAddresses.size,
            "classicDevice" to (classicPanaDevice?.address ?: "null"),
            "currentAncMode" to currentAncMode
        )
    }

    /**
     * 通过 MAC 地址判断是否为当前耳机。
     * LC3/LE-Audio 模式下系统可能使用不同的地址（通常仅最后一个字节不同），
     * 因此同时匹配经典地址、LC3 地址以及前 5 段相同的地址变体。
     */
    fun isCurrentDevice(address: String?): Boolean {
        if (address == null) return false
        return isSameDeviceAddress(address, macAddress) ||
                isSameDeviceAddress(address, lc3MacAddress)
    }

    /**
     * 判断两个 MAC 是否属于同一副耳机：
     * 1. 完全相同；或
     * 2. 前 5 段相同（旧规则，仅末段不同）；或
     * 3. 前 4 段相同（经典/LE 双模地址，末两段都可能不同）。
     */
    fun isSameDeviceAddress(base: String?, candidate: String?): Boolean {
        if (base.isNullOrBlank() || candidate.isNullOrBlank()) return false
        if (base.equals(candidate, ignoreCase = true)) return true
        val baseParts = base.split(":")
        val candParts = candidate.split(":")
        if (baseParts.size != 6 || candParts.size != 6) return false
        return (0..3).all { baseParts[it].equals(candParts[it], ignoreCase = true) }
    }

    /**
     * 判断两个 MAC 地址是否为同一设备的地址变体（LE-Audio 常见场景）。
     * 规则：标准 6 段 MAC，前 5 段完全相同，仅最后一段不同——v176：该规则对本项目失效（坑 #3：实测两地址第 5 段即不同），恒 false 使 pushStatusTo 的兄弟地址兜底永不命中，已改为委托 [isSameDeviceAddress]（前 4 段匹配）。
     */
    /**
     * Check whether two MAC addresses are siblings of the same device
     * (common in LE-Audio); delegates to [isSameDeviceAddress] since v176
     * (first 4 segments match; project addresses differ at segment 5).
     */
    fun isAddressSibling(base: String, candidate: String): Boolean =
        isSameDeviceAddress(base, candidate)

    /**
     * 判断设备名是否为松下/Technics 耳机（原方法保留）
     */
    fun isPana(name: String?): Boolean {
        name ?: return false
        return PANA_KEYWORDS.any { name.contains(it, ignoreCase = true) }
    }

    /**
     * 判断设备名是否为松下/Technics 耳机（别名，兼容性方法）
     */
    fun isPanaDevice(name: String?): Boolean {
        return isPana(name)
    }
}