package com.panapods.ble

import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import com.panapods.utils.PanaLog
import com.panapods.utils.RootKeepAlive
import com.panapods.R
import com.panapods.bridge.PanaBridge
import com.panapods.config.ConfigManager
import com.panapods.headphones.AncMode
import com.panapods.headphones.AmbientMode
import com.panapods.headphones.HeadphoneState
import com.panapods.headphones.HeadphoneText
import com.panapods.protocol.*
import com.panapods.receivers.KeepAliveScheduler
import com.panapods.utils.Async

/**
 * BLE 前台服务
 *
  * 管理与松下/Technics EAH-AZ 系列耳机的 BLE 连接生命周期，
  * 协调 AirohaBleClient + PanaProtocolEngine。
 *
 * 已抽出：连接健康看门狗 → [ConnectionCoordinator]；
 * 电量/在位映射 → [BatteryStateTracker]；通知 → [NotificationController]。
 */
class PanaBleService : Service(), AirohaBleClient.Listener, PanaProtocolEngine.ResponseListener {

    companion object {
        private const val TAG = "PanaBleService"
        const val ACTION_CONNECT = "com.panapods.action.CONNECT"
        const val ACTION_DISCONNECT = "com.panapods.action.DISCONNECT"
        const val ACTION_SYNC_ANC = "com.panapods.action.SYNC_ANC"
        const val EXTRA_ADDRESS = "extra_address"

        /**
         * 系统里当前真实在线的本耳机地址（A2DP / HEADSET / LE_AUDIO 任一）。
         *
         * 一副 Technics 在系统中有两个配对地址（经典 DUAL 主地址 + LE 副地址），
         * 单耳使用时只有「在盒外那只」的地址在线，另一只的地址会消失。App 若把连接
         * 目标钉在已消失的地址上，就会无限 20s 超时重连——表现为 App 一直「正在连接」、
         * 融合中心/TWS 的电量与照片全部失效。重连前先解析在线地址即可自动跟随。
         *
         * 设备识别用「名字是 Pana」或「地址与已知的本耳机地址精确相同」；
         * 不用兄弟匹配，避免把已回盒的那只也算成在线。
         *
         * @param refs 已知的本耳机地址（当前/上次/桥接缓存），用于精确匹配
         */
        fun findConnectedPanaAddress(context: Context, refs: List<String?>): String? {
            val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
                ?: return null
            val known = refs.filterNotNull()
            var nameMatch: String? = null
            for (profile in intArrayOf(
                BluetoothProfile.A2DP, BluetoothProfile.HEADSET, BluetoothProfile.LE_AUDIO
            )) {
                val devices = try { btManager.getConnectedDevices(profile) } catch (_: Throwable) { null }
                    ?: continue
                for (d in devices) {
                    val addr = try { d.address } catch (_: Throwable) { null } ?: continue
                    // 精确命中已知的本耳机地址优先返回；名字匹配只作兜底，
                    // 避免同时连着另一副同名/同品牌耳机时选错设备。
                    if (known.any { it.equals(addr, ignoreCase = true) }) return addr
                    if (nameMatch == null &&
                        PanaBridge.isPanaDevice(try { d.name } catch (_: Throwable) { null })
                    ) {
                        nameMatch = addr
                    }
                }
            }
            // v173 来源二：AudioManager 输出设备（本 ROM 的 LE_AUDIO 查询对三方 App 盲，
            // 但音频子系统始终看得见已连接的 BLE 耳机）。
            findAudioOutputPanaAddress(context, refs)?.let { return it }
            // v173 来源三：profile 代理 connectedDevices（不走 BluetoothManager 快捷查询）。
            for (profile in intArrayOf(
                BluetoothProfile.A2DP, BluetoothProfile.HEADSET, BluetoothProfile.LE_AUDIO
            )) {
                val devices = profileProxyDevices(profile) ?: continue
                for (d in devices) {
                    val addr = try { d.address } catch (_: Throwable) { null } ?: continue
                    if (known.any { it.equals(addr, ignoreCase = true) }) return addr
                    if (isObservedPanaAddress(context, addr, refs)) return addr
                }
            }
            // v173 来源四：广播维护的观测地址集（ACL/LE-Audio 连接事件）。
            for (addr in systemConnectedAddresses.toList()) {
                if (known.any { it.equals(addr, ignoreCase = true) }) return addr
                if (isObservedPanaAddress(context, addr, refs)) return addr
            }
            return nameMatch
        }

        // 已连接状态下周期性刷新电量/左右在位：覆盖“先戴一只，过一会再戴另一只”的场景
        private const val BATTERY_REFRESH_INTERVAL_MS = 2_000L

        // ===================== v173：系统层连接观测 =====================
        // 背景：本机 ROM 上第三方 App 调 BluetoothManager.getConnectedDevices(LE_AUDIO)
        // 恒返回空列表（实测实证），而单耳使用时耳机恰恰只通过 LE Audio 在线。
        // 结果 livePanaAddress() 恒 null → v167 的“跟随在线兄弟地址”永不触发 →
        // GATT 钉死在已消失的地址上 20s 一轮无限超时，电量/照片/TWS 全部失效。
        // 修复：新增三个不依赖该 API 的来源——
        //   (a) AudioManager 输出设备（TYPE_BLE_HEADSET / TYPE_BLUETOOTH_A2DP）；
        //   (b) profile 代理（A2DP/HEADSET/LE_AUDIO）的 connectedDevices；
        //   (c) ACL / LE-Audio 连接广播实时维护的地址集（广播必达）。

        /** 本进程观测到的系统层已连接地址（仅收录“确定属于本耳机”的地址）。 */
        private val systemConnectedAddresses = java.util.Collections.newSetFromMap(
            java.util.concurrent.ConcurrentHashMap<String, Boolean>()
        )

        private val profileProxyLock = Any()
        private val profileProxyMap = HashMap<Int, BluetoothProfile>()
        @Volatile private var profileProxiesRequested = false

        private val profileProxyListener = object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile?) {
                if (proxy != null) {
                    synchronized(profileProxyLock) { profileProxyMap[profile] = proxy }
                }
            }
            override fun onServiceDisconnected(profile: Int) {
                synchronized(profileProxyLock) { profileProxyMap.remove(profile) }
            }
        }

        /** 每进程只申请一次 profile 代理（A2DP/HEADSET/LE_AUDIO），失败不打扰。 */
        private fun requestProfileProxiesOnce(context: Context) {
            if (profileProxiesRequested) return
            profileProxiesRequested = true
            try {
                val adapter = BluetoothAdapter.getDefaultAdapter() ?: return
                for (p in intArrayOf(
                    BluetoothProfile.A2DP, BluetoothProfile.HEADSET, BluetoothProfile.LE_AUDIO
                )) {
                    runCatching { adapter.getProfileProxy(context.applicationContext, profileProxyListener, p) }
                }
            } catch (_: Throwable) {}
        }

        private fun profileProxyDevices(profile: Int): List<BluetoothDevice>? {
            val proxy = synchronized(profileProxyLock) { profileProxyMap[profile] } ?: return null
            return try { proxy.connectedDevices } catch (_: Throwable) { null }
        }

        /**
         * 该地址是否值得收录为本耳机的系统连接观测：
         * 与已知地址同设备（前 4 段）、或 OUI 前缀命中、或已配对且名字是 Pana。
         */
        private fun isObservedPanaAddress(context: Context, addr: String?, refs: List<String?>): Boolean {
            if (addr.isNullOrBlank()) return false
            val known = refs.filterNotNull()
            if (known.any { PanaBridge.isSameDeviceAddress(addr, it) }) return true
            PanaBridge.getPanaOuiPrefix()?.let { if (addr.startsWith(it, true)) return true }
            // 既不像已知地址也不是已知 OUI：只有已配对且名字像 Pana 才收
            try {
                val dev = BluetoothAdapter.getDefaultAdapter()?.getRemoteDevice(addr)
                if (dev != null && dev.bondState == BluetoothDevice.BOND_BONDED &&
                    PanaBridge.isPanaDevice(dev.name)
                ) return true
            } catch (_: Throwable) {}
            return false
        }

        /** 从 AudioManager 输出设备里找本耳机当前在线的地址（LE Audio/A2DP 均可见）。 */
        private fun findAudioOutputPanaAddress(context: Context, refs: List<String?>): String? {
            return try {
                val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return null
                val known = refs.filterNotNull()
                for (info in am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
                    val t = try { info.type } catch (_: Throwable) { -1 }
                    if (t != 26 /* TYPE_BLE_HEADSET */ && t != 22 /* TYPE_BLUETOOTH_A2DP */) continue
                    val addr = try { info.address } catch (_: Throwable) { "" } ?: ""
                    val name = try { info.productName?.toString() } catch (_: Throwable) { null }
                    if (known.any { it.equals(addr, ignoreCase = true) }) return addr
                    if (addr.isNotBlank() && isObservedPanaAddress(context, addr, refs)) return addr
                    if (addr.isNotBlank() && name != null && PanaBridge.isPanaDevice(name)) return addr
                }
                null
            } catch (_: Throwable) { null }
        }
        // 媒体播放中放慢刷新：GATT 查询与 LE Audio/A2DP 共用蓝牙链路，
        // 频繁写入会与音乐抢时隙导致卡顿；播放时降低到 15s 一次并精简查询。
        private const val BATTERY_REFRESH_INTERVAL_MUSIC_MS = 15_000L
        // 无状态变化超过该时长后，未播放时的轮询从 2s 退避到 6s，降低 GATT 开销。
        private const val BATTERY_REFRESH_INTERVAL_IDLE_MS = 6_000L
        private const val BATTERY_IDLE_AFTER_MS = 10_000L
        // 发出 PARTNER 电量查询后，超过该时间没收到副耳电量应答就记为“本轮未应答”
        private const val PARTNER_BATTERY_TIMEOUT_MS = 2_000L
        // v168：副耳电量连续多少轮完全无应答才判定“副耳已入盒/关闭”并清空该侧。
        // relay 是异步两跳（discoverPartnerDst → relayGetBattery → 应答），单轮偶发
        // 迟到/丢失属常态；阈值 3（约 6s）可与 2s 刷新周期解耦，避免右耳周期性闪 "-"。
        private const val PARTNER_BATTERY_MISS_THRESHOLD = 3

                // v95 新增：SharedPreferences 持久化 ANC 模式
                // 与 ConfigManager / PanaLog 统一使用 panapods_config，避免配置分散到两份文件。
        private const val PREF_NAME = "panapods_config"
        private const val KEY_ANC_MODE = "last_anc_mode"

        @Volatile private var instance: PanaBleService? = null
                // -1 表示尚未从耳机读取；0 才表示耳机真实处于关闭模式
        @Volatile private var lastAncMode: Int = -1
                // v95.3: SET 命令发出后的期望模式和保护截止时间
                //        在保护窗口内，来自耳机的旧值响应会被忽略，避免 UI 闪烁
        @Volatile private var pendingAncMode: Int = -1
        @Volatile private var pendingAncUntil: Long = 0L

        /**
                  * 供 PanaPodsProvider 跨进程查询当前耳机状态。
                  * 如果服务未运行或尚未连接，返回最近一次缓存状态。
         */
        fun peekState(): HeadphoneState? {
            return instance?.currentState
        }

        /**
         * For PanaPodsProvider: whether the BLE service is running AND currently
         * connected to the earphone. Must not be derived from peekState() != null,
         * because the service always holds a HeadphoneState instance.
         */
        fun isBleConnected(): Boolean = instance?.isConnected() ?: false

        /**
                  * 供 Xposed Hook / Provider / Receiver 直接设置 ANC 模式，
                  * 避免依赖广播或 Service 启动。
         */
        fun setAncModeFromProvider(mode: Int) {
            val svc = instance
            if (svc == null) {
                PanaLog.w(TAG, "setAncModeFromProvider: service not running, mode=$mode ignored")
                return
            }
            PanaLog.d(TAG, "setAncModeFromProvider mode=$mode")
            // Provider.call runs on a binder thread; hop to the main thread so
            // HeadphoneState is only mutated from one thread.
            svc.mainHandler.post { svc.setAncMode(mode) }
        }

                // v95 新增：获取最后一次保存的 ANC 模式
        fun getLastAncMode(): Int = lastAncMode

        private fun persistAncMode(mode: Int) {
            val ctx = instance ?: return
            ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .edit().putInt(KEY_ANC_MODE, mode).apply()
        }
    }

    // v178：记录上一次 LE Audio 连接状态，用于检测连接建立瞬间并触发 TWS 同步。
    @Volatile private var lastLeAudioConnected = false
    private val binder = LocalBinder()
    private var bleClient: AirohaBleClient? = null
    private var protocolEngine: PanaProtocolEngine? = null
    // ConfigManager 无状态，缓存复用，避免看门狗/重连等热路径反复创建。
    private val config by lazy { ConfigManager(this) }
    // AudioManager 同理，电量轮询每 2s 调用一次 isMusicActive()。
    private val audioManager by lazy { getSystemService(Context.AUDIO_SERVICE) as? AudioManager }
    // Read by PanaPodsProvider.query() from a binder thread, written on the
    // main thread; @Volatile guarantees cross-thread visibility.
    @Volatile
    private var currentState = HeadphoneState()
    private var stateListener: StateListener? = null
    private var isConnected = false
    private var isConnecting = false
    private val mainHandler = Handler(Looper.getMainLooper())
    // 用户手动断开后，抑制周期看门狗自动重连；等下次显式 connect/ACL_CONNECTED 才恢复
    @Volatile
    private var suppressAutoReconnect = false

    // v171：连接风暴抑制。策略与背景见 [ConnectBackoff] 注释。
    private val connectBackoff = ConnectBackoff()
    // v171：音乐播放中的轮询序号，用于把「我们发起的 GATT 轮询」与耳机侧掉线时间对齐。
    private var pollTraceTick = 0

    /** 记一次连接失败并安排下一次允许尝试的时间（指数退避）。 */
    private fun noteConnectFailure(why: String) {
        val delay = connectBackoff.noteFailure(SystemClock.elapsedRealtime())
        PanaLog.i(
            TAG,
            "CONN-TRACE: connect failure ($why) streak=${connectBackoff.streak}, next attempt in ${delay}ms"
        )
    }

    /** 连接成功 / 设备真实在线 / 用户手动操作后复位退避。 */
    private fun resetConnectBackoff() {
        if (connectBackoff.streak != 0) {
            PanaLog.i(TAG, "CONN-TRACE: backoff reset (streak was ${connectBackoff.streak})")
        }
        connectBackoff.reset()
    }

    private val batteryTracker = BatteryStateTracker()
    // 必须懒加载：Service 字段初始化发生在 attachBaseContext 之前，
    // 此时 getSystemService/applicationContext 会因 mBase == null 抛 NPE。
    private val notificationController by lazy { NotificationController(this) }
    private val connectionCoordinator = ConnectionCoordinator(object : ConnectionCoordinator.Callback {
        override fun isConnected(): Boolean = this@PanaBleService.isConnected()
        override fun isConnecting(): Boolean = this@PanaBleService.isConnecting
        override fun isSuppressAutoReconnect(): Boolean = suppressAutoReconnect
        override fun currentAddress(): String? = currentState.macAddress
        override fun savedAddress(): String? = config.lastBtAddress
        override fun autoConnectEnabled(): Boolean = config.autoConnect
        override fun isBluetoothOn(): Boolean = this@PanaBleService.isBluetoothOn()
        override fun isBonded(address: String): Boolean = this@PanaBleService.isBonded(address)
        override fun isGattActuallyConnected(address: String): Boolean =
            this@PanaBleService.isActuallyGattConnected(address)
        override fun isLeAudioConnected(): Boolean = this@PanaBleService.isLeAudioConnected()
        override fun connect(address: String) = this@PanaBleService.connect(address)
        override fun teardownClient(notifyDisconnected: Boolean) =
            this@PanaBleService.teardownClient(notifyDisconnected)
        override fun notifyGiveUpReconnect() {
            stateListener?.onStateChanged(currentState)
            stateListener?.onDisconnected()
            notificationController.update(getString(R.string.service_not_connected))
        }
        override fun log(message: String) {
            stateListener?.onLogReceived(message)
        }
        override fun noteConnectTimeoutFailure() {
            noteConnectFailure("connect timeout")
        }
    })

    // 最近一次状态变化时间（主线程读写），电量轮询据此做空闲退避。
    private var lastStateChangeAt = 0L

    // v168：最近一次 discoverPartnerDst 观测到的副耳 dst（type/id）。
    // 副耳电量必须走 relay 查询，而 relay 需要 dst；discovery 偶尔返回 ACK/超时
    // （此时本轮不会发出 relay），缓存上次成功的 dst 可在漏轮时立刻补发一次 relay，
    // 显著提高副耳电量的读取成功率（此前只能靠下一轮重新 discovery）。
    @Volatile private var partnerDstType: Int = -1
    @Volatile private var partnerDstId: Int = -1

    // 已连接时周期性刷新电量/左右在位；mainHandler 在断开时会被清空，重连后由 handleConnected 重新调度
    private val batteryRefreshRunnable = object : Runnable {
        override fun run() {
            val musicActive = isMusicActive()
            // v171：放音中每轮打一条轮询标记（直连 Log，不受 PanaLog 开关限制），
            // 用于把「我们自己发起的 GATT 轮询」与耳机侧「单侧掉线」的时间对齐。
            if (musicActive) {
                pollTraceTick += 1
                PanaLog.i(
                    TAG,
                    "POLL-TRACE: tick=$pollTraceTick light=true leAudio=${isLeAudioConnected()} connected=$isConnected"
                )
            }
            
            // v178：检测 LE Audio 连接状态变化，若刚建立则立即触发 TWS 同步，
            // 确保副耳通过 relay 链路能收到音频转发（防止单耳无声）。
            val leAudioNow = isLeAudioConnected()
            if (leAudioNow && !lastLeAudioConnected) {
                PanaLog.i(TAG, "LE Audio connected detected, triggering immediate TWS sync")
                triggerTwSync()
            }
            lastLeAudioConnected = leAudioNow
            
            refreshBattery(light = musicActive)
            if (isConnected) {
                // 防重入：避免 handleConnected 与本 runnable 同时调度造成双循环
                mainHandler.removeCallbacks(this)
                val now = SystemClock.elapsedRealtime()
                val delay = when {
                    musicActive -> BATTERY_REFRESH_INTERVAL_MUSIC_MS
                    // 长时间无任何状态变化（耳机静止）时退避，降低 GATT 轮询开销
                    now - lastStateChangeAt > BATTERY_IDLE_AFTER_MS -> BATTERY_REFRESH_INTERVAL_IDLE_MS
                    else -> BATTERY_REFRESH_INTERVAL_MS
                }
                mainHandler.postDelayed(this, delay)
            }
        }
    }

    // 每轮刷新后检查副耳电量应答：连续 PARTNER_BATTERY_MISS_THRESHOLD 轮都没应答
    // 才判定副耳已关闭/入盒并清空该侧。
    //
    // v168：旧逻辑单轮未应答就清空。但副耳电量是异步两跳 relay（discover → relay →
    // 应答），且看门狗 2s 与刷新周期 2s 相同、每轮又重置 responded 标记，任何一轮 relay
    // 迟到/丢（discovery 返回 ACK、链路抖动）都会把仍在位的副耳电量擦掉，通知与融合中心
    // 表现为“一个耳朵有电量、一个没有”。改为累计连续未应答轮数，慢一轮不再误清。
    //
    // v177：修复 partnerBattery == null 时提前 return 导致 streak 永不增、
    // 重连后补发 relay 永不触发的 bug。现在：只有 partnerBattery != null 且本轮无应答
    // 才累计 streak；partnerBattery 为 null（新会话/已清空）时不增 streak、也不清空。
    private val partnerBatteryTimeoutRunnable = Runnable {
        if (!isConnected) return@Runnable
        if (batteryTracker.partnerBatteryResponded) {
            batteryTracker.resetPartnerMissStreak()
            return@Runnable
        }
        // 仅当已有缓存电量且本轮无应答时才累计 streak；partnerBattery==null 不计入
        val streak = batteryTracker.onPartnerMissIfCached()
        if (streak >= PARTNER_BATTERY_MISS_THRESHOLD) {
            PanaLog.d(TAG, "Partner battery unanswered for $streak rounds, clearing")
            batteryTracker.clearPartnerBattery()
            recomputeBatteryState()
        } else if (streak > 0) {
            PanaLog.d(TAG, "Partner battery unanswered this round (streak=$streak), keeping last value")
        }
    }

    /**
          * 状态变更监听器 (Activity 侧注册)
     */
    interface StateListener {
        fun onStateChanged(state: HeadphoneState)
        fun onConnecting(address: String)
        fun onConnected(address: String)
        fun onDisconnected()
        fun onLogReceived(message: String)
    }

    inner class LocalBinder : Binder() {
        fun getService(): PanaBleService = this@PanaBleService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        instance = this
                // 从 SharedPreferences 恢复上次保存的 ANC 模式
        val sp = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        // 迁移旧版 prefs：老版本 ANC 模式存在 pana_pods_config 里。
        val old = getSharedPreferences("pana_pods_config", Context.MODE_PRIVATE)
        if (!sp.contains(KEY_ANC_MODE) && old.contains(KEY_ANC_MODE)) {
            sp.edit().putInt(KEY_ANC_MODE, old.getInt(KEY_ANC_MODE, -1)).apply()
        }
        val saved = sp.getInt(KEY_ANC_MODE, -1)
        if (AncMode.isValid(saved)) {
            lastAncMode = saved
            // v175：把上次已知模式种进当前状态，与 Provider 的 lastAncMode 口径一致，
            // 否则服务刚起来时 UI 会显示"未知/关闭"直到第一次 getOutsideCtrl 回包。
            currentState = currentState.copy(outsideCtrl = saved)
        }
        startForeground(
            NotificationController.NOTIFICATION_ID,
            notificationController.buildNotification(getString(R.string.service_notification_running))
        )
        // v173：注册系统层连接观测（广播 + profile 代理），修复本 ROM 上
        // BluetoothManager.getConnectedDevices(LE_AUDIO) 对三方 App 返回空的盲区，
        // 使单耳（尤其仅左耳、走 LE Audio）场景下 live 地址可解析、自动跟随不失效。
        registerSystemConnectionObservers()
        connectionCoordinator.start()
        // Root keep-alive: apply battery whitelist + schedule periodic restart alarm
        // once per service start (enabled by user in settings, default off).
        if (config.rootKeepAlive) {
            KeepAliveScheduler.schedule(this)
            Async.run("root-keepalive") {
                runCatching { RootKeepAlive.apply(this@PanaBleService) }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT -> {
                val address = intent.getStringExtra(EXTRA_ADDRESS)
                if (address != null) {
                    // 显式连接请求（含 ACL_CONNECTED 广播触发）恢复自动重连能力
                    suppressAutoReconnect = false
                    connect(address)
                }
            }
            ACTION_DISCONNECT -> disconnect()
            ACTION_SYNC_ANC -> {
                if (isConnected && protocolEngine != null) {
                    PanaLog.i(TAG, "ACTION_SYNC_ANC: querying real earphone mode")
                    protocolEngine?.getOutsideCtrl()
                } else if (!isConnecting) {
                    PanaLog.i(TAG, "ACTION_SYNC_ANC: service not connected, reconnecting first")
                    tryReconnectFromSaved()
                }
            }
            PanaBridge.ACTION_COMMAND -> handleBridgeCommand(intent)
            else -> {
                                // 进程被杀后系统按 START_STICKY 重启服务时 intent 往往为 null，
                                // 此处用保存的地址自动重连，避免“被杀一次后再也连不上”。
                if (!isConnected && !isConnecting) tryReconnectFromSaved()
            }
        }
        return START_STICKY
    }

    /**
          * 处理来自 Xposed Hook 的反向控制命令
     */
    private fun handleBridgeCommand(intent: Intent) {
        when (intent.getStringExtra(PanaBridge.EXTRA_COMMAND)) {
            PanaBridge.COMMAND_SET_ANC_MODE -> {
                val mode = intent.getIntExtra(PanaBridge.EXTRA_CMD_ANC_MODE, -1)
                if (!AncMode.isValid(mode)) {
                    PanaLog.w(TAG, "Bridge command: invalid ANC mode=$mode ignored")
                    return
                }
                val ncLevel = intent.getIntExtra(PanaBridge.EXTRA_CMD_ANC_LEVEL, currentState.ncLevel)
                val ambientLevel = intent.getIntExtra(PanaBridge.EXTRA_CMD_AMBIENT_LEVEL, currentState.ambientLevel)
                PanaLog.i(TAG, "Bridge command: setOutsideCtrl mode=$mode nc=$ncLevel ambient=$ambientLevel")
                // Reuse the full local-cache + pending-window pipeline instead of
                // bypassing straight to the engine.
                setAncMode(mode, ncLevel, ambientLevel)
            }
        }
    }

    override fun onDestroy() {
        connectionCoordinator.stop()
        unregisterSystemConnectionObservers()
        disconnect()
        instance = null
        super.onDestroy()
    }

        // ============ 公开 API ============

    fun setStateListener(listener: StateListener?) {
        this.stateListener = listener
                // 注册时立即同步当前状态，防止连接事件在监听器注册前触发
        if (listener != null) {
            listener.onStateChanged(currentState)
            if (isConnected) {
                listener.onConnected(currentState.macAddress ?: "")
            } else if (isConnecting) {
                listener.onConnecting(currentState.macAddress ?: "")
            }
        }
    }

    fun connect(address: String) {
        if (address.isBlank()) {
            PanaLog.w(TAG, "connect: blank address ignored")
            return
        }
        // 显式连接请求恢复自动重连能力（用户手动点击连接 / ACL_CONNECTED / Activity 自动连接）
        suppressAutoReconnect = false

        // 单耳使用时会换耳：若「目标地址」在系统里已不在线（那只耳回了充电盒/关机），
        // 而同一副耳机的兄弟地址仍在线，就跟随到在线的那只。
        // 否则连接目标会被永久钉在已消失的地址上，每 20s 超时重连一次永不停歇
        // —— 表现为 App 一直「正在连接」、融合中心与 TWS 的电量/照片全部失效。
        val live = livePanaAddress()
        val target = if (!isAddressConnected(address) && live != null &&
            !live.equals(address, ignoreCase = true) &&
            PanaBridge.isSameDeviceAddress(live, address)
        ) {
            PanaLog.i(TAG, "connect: $address not connected, following live sibling $live")
            stateListener?.onLogReceived("RETARGET: $address -> $live")
            PanaLog.i(TAG, "CONN-TRACE: retarget $address -> $live (sibling is live)")
            live
        } else {
            address
        }

        // v171：连接风暴抑制 —— 详见 ConnectBackoff 注释。
        // 设备在系统层面真实在线时立即复位退避（拿出耳机秒连）；否则对连续失败的
        // 重复尝试按指数退避限流，避免每 5~6s 拉起又拆掉一条 ACL 干扰 LE Audio。
        val now = SystemClock.elapsedRealtime()
        val leAudio = isLeAudioConnected()
        val presentAtSystem = isAddressConnected(target) ||
            (currentState.macAddress?.let { isAddressConnected(it) } ?: false) ||
            leAudio
        if (connectBackoff.shouldThrottle(now, presentAtSystem)) {
            PanaLog.i(
                TAG,
                "CONN-TRACE: throttled addr=$target streak=${connectBackoff.streak} " +
                    "wait=${connectBackoff.waitMs(now)}ms present=false leAudio=$leAudio"
            )
            return
        }
        PanaLog.i(
            TAG,
            "CONN-TRACE: connect req addr=$target present=$presentAtSystem streak=${connectBackoff.streak} " +
                "leAudio=$leAudio connected=$isConnected connecting=$isConnecting"
        )

        val currentAddr = currentState.macAddress
        if (isConnecting || isConnected) {
            val sameAddress = currentAddr.equals(target, ignoreCase = true)
            // LE Audio/LC3 模式下，ACL_CONNECTED 可能带来与 GATT 地址只差最后一段的
            // 兄弟地址（同一副耳机在系统里的经典/LE 两个地址）。必须视为同一设备，
            // 否则会误断一条还活着的 LE 链路，造成音频卡顿/单耳掉线。
            val sameDevice = PanaBridge.isSameDeviceAddress(currentAddr, target)
            if (!sameAddress) {
                PanaBridge.setLc3MacAddress(target)
                PanaLog.i(TAG, "connect: recorded LC3/sibling address $target (current=$currentAddr)")
            }
            // 关键修复：不能只凭 isConnected 标志就忽略重连请求。耳机放回充电盒再取出时，
            // GATT 断开回调可能丢失，服务仍认为连着；若此时忽略 ACL_CONNECTED 触发的新请求，
            // 就会出现“重新拿出耳机不自动连接”。这里查询系统真实的 GATT 连接状态，
            // 只有真连着才忽略，否则强制重建连接。
            val actuallyConnected = sameDevice && (
                (currentAddr != null && isActuallyGattConnected(currentAddr)) ||
                isActuallyGattConnected(target)
            )
            if (actuallyConnected) {
                PanaLog.d(TAG, "connect: same device already connected (current=$currentAddr acl=$target), ignore")
                stateListener?.onLogReceived("WARN: same device already connected via $currentAddr, ignore ACL $target")
                return
            }
            PanaLog.w(TAG, "connect: stale connection state (isConnected=$isConnected isConnecting=$isConnecting sameAddress=$sameAddress sameDevice=$sameDevice), forcing reconnect to $target")
            stateListener?.onLogReceived("WARN: stale connection state, force reconnect to $target")
            teardownClient(notifyDisconnected = false)
        }

        if (bleClient != null) {
            teardownClient(notifyDisconnected = false)
        }

        isConnecting = true
        // 多机型适配：从系统蓝牙栈读取真实设备名（如 EAH-AZ40/AZ60/AZ80/AZ100），
        // 未知 modelId 时 UI 与 Hook 侧显示名都回退到它。
        val remoteName = try {
            val adapter = (getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
            adapter?.getRemoteDevice(target)?.name?.takeIf { it.isNotBlank() }
        } catch (_: Throwable) { null }
        currentState = currentState.copy(
            macAddress = target,
            deviceName = remoteName ?: currentState.deviceName,
            // 多机型适配：连接新设备时先重置型号 ID，等 GET_MODEL_ID 响应再确认，
            // 避免从 AZ100 切换到 AZ40/AZ60/AZ80 时短暂显示上一个型号。
            modelId = -1
        )
        stateListener?.onConnecting(target)
        stateListener?.onLogReceived("CONNECT: $target")
        bleClient = AirohaBleClient(this, this)
        protocolEngine = PanaProtocolEngine { packet ->
            bleClient?.sendPacket(packet) ?: false
        }
        protocolEngine?.setResponseListener(this)

        val ok = bleClient?.connect(target) ?: false
        if (!ok) {
            isConnecting = false
            stateListener?.onLogReceived("CONNECT failed: bleClient.connect returned false")
            noteConnectFailure("bleClient.connect returned false")
            // Do not leave half-initialized client/engine behind.
            bleClient?.detach()
            bleClient = null
            protocolEngine?.release()
            protocolEngine = null
            connectionCoordinator.cancelConnectTimeout()
            // v175：同步失败原本只 log，既不回调也不改通知 —— 蓝牙刚关闭/`connectGatt`
            // 抛异常时，UI 进度条与前台通知会**永远**停在"正在连接"，而自动连接关闭时
            // 看门狗也不会来纠正。这里走统一的断开收尾，把状态与通知拉回"未连接"。
            stateListener?.onDisconnected()
            notificationController.update(getString(R.string.service_disconnected))
        } else {
            connectionCoordinator.armConnectTimeout()
            notificationController.update(getString(R.string.service_connecting))
        }
    }

    fun disconnect() {
        suppressAutoReconnect = true
        mainHandler.removeCallbacksAndMessages(null)
        // 清理 LC3 模式下排队的延迟重连，避免用户断开后又被自动连上。
        connectionCoordinator.cancelAutoReconnect()
        // v171：用户显式断开是「新意图」，复位连接退避，避免下次手动连接被限流。
        resetConnectBackoff()
        teardownClient(notifyDisconnected = true)
        stateListener?.onLogReceived("DISCONNECT requested")
    }

    fun isConnected(): Boolean = isConnected

        // ============ 耳机控制 API ============

    fun setAncMode(mode: Int) {
        setAncMode(mode, currentState.ncLevel, currentState.ambientLevel)
    }

    fun setAncMode(mode: Int, ncLevel: Int, ambientLevel: Int) {
        if (!AncMode.isValid(mode)) {
            PanaLog.w(TAG, "setAncMode: invalid mode=$mode ignored")
            return
        }
        setOutsideCtrlOptimistic(mode, ncLevel, ambientLevel)
    }

    fun setNcLevel(level: Int) {
        setOutsideCtrlOptimistic(currentState.outsideCtrl, level, currentState.ambientLevel)
    }

    fun setAmbientMode(mode: Int) {
        if (mode != AmbientMode.TRANSPARENT && mode != AmbientMode.ATTENTION) {
            PanaLog.w(TAG, "setAmbientMode: invalid mode=$mode ignored")
            return
        }
        val engine = protocolEngine
        if (engine == null) {
            PanaLog.w(TAG, "setAmbientMode: protocolEngine is null, cannot set mode=$mode")
            return
        }
        PanaLog.i(TAG, "setAmbientMode: mode=$mode")

        // 与 setAncMode 一致：先乐观更新本地缓存让 UI 立即响应，再发命令并回查。
        currentState = currentState.copy(ambientMode = mode)
        notifyStateChange()
        mainHandler.postDelayed({
            engine.setAmbientMode(mode)
            mainHandler.postDelayed({
                PanaLog.d(TAG, "Querying ambientMode after setAmbientMode($mode)")
                protocolEngine?.getAmbientMode()
            }, 400)
        }, 50)
    }

    /**
     * ANC 模式 / NC 增益 / 环境声等级的统一设置管线：
     * 乐观更新本地缓存 → 通知 UI → 延迟发送命令 → 延时回查真实状态。
     * SET_OUTSIDE_CTRL 同时携带这三个值，因此三个入口共用同一套逻辑，
     * 避免 setNcLevel / setAmbientLevel 只发命令不更新缓存导致 UI 弹回。
     */
    private fun setOutsideCtrlOptimistic(mode: Int, ncLevel: Int, ambientLevel: Int) {
        // v175：outsideCtrl 未知时为 -1（如 setNcLevel 在状态未读到时调用），
        // 直接下发会把非法模式写进耳机，必须拦下。
        if (!AncMode.isValid(mode)) {
            PanaLog.w(TAG, "setOutsideCtrl: invalid mode=$mode ignored")
            return
        }
        val engine = protocolEngine
        if (engine == null) {
            PanaLog.w(TAG, "setAncMode: protocolEngine is null, cannot set mode=$mode")
            return
        }
        PanaLog.i(TAG, "setOutsideCtrl: mode=$mode nc=$ncLevel ambient=$ambientLevel")

        // 先更新本地缓存，让 App UI / Provider 查询立即反应用户操作
        currentState = currentState.copy(
            outsideCtrl = mode,
            ncLevel = ncLevel,
            ambientLevel = ambientLevel
        )
        lastAncMode = mode
        persistAncMode(mode)
        // v95.3: 设置保护窗口 1500ms，屏蔽耳机返回旧值导致的 UI 闪烁
        // v176：窗口计时改单调时钟，与下方 onOutsideCtrlReceived 的读取同一时基，
        // 防墙上时钟回拨把 1500ms 的屏蔽窗口拉成无限期。
        pendingAncMode = mode
        pendingAncUntil = SystemClock.elapsedRealtime() + 1500L
        notifyStateChange()

        // 延迟 50ms 后发送命令，给系统应用充足时间处理状态更新
        mainHandler.postDelayed({
            engine.setOutsideCtrl(mode, ncLevel, ambientLevel)

            // 延时查询一次耳机实际状态，确认命令是否生效；如未生效会回退 UI
            mainHandler.postDelayed({
                PanaLog.d(TAG, "Querying outsideCtrl after setOutsideCtrl($mode)")
                protocolEngine?.getOutsideCtrl()
            }, 400)
        }, 50)
    }

    /**
     * 协议调试：解析 HEX 字符串并直接发送原始报文。
     * 格式示例："05 5A 02 00 00 00"（可用空格/换行分隔，每字节两位 hex）。
     */
    fun sendRawHex(hex: String) {
        val bytes = try {
            hex.trim()
                .split(Regex("\\s+"))
                .filter { it.isNotBlank() }
                .map { it.toInt(16).toByte() }
                .toByteArray()
        } catch (e: Exception) {
            PanaLog.w(TAG, "sendRawHex: invalid hex input: $hex", e)
            stateListener?.onLogReceived("ERROR: invalid hex input")
            return
        }
        if (bytes.isEmpty()) return
        val ok = bleClient?.sendRaw(bytes) ?: false
        val pretty = bytes.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }
        stateListener?.onLogReceived(if (ok) "TX RAW: $pretty" else "ERROR: raw send failed")
    }

    // ============ AirohaBleClient.Listener ============

    override fun onConnected() {
        // GATT callbacks arrive on a binder thread; state + UI must only change on main.
        if (Looper.myLooper() == Looper.getMainLooper()) {
            handleConnected()
        } else {
            mainHandler.post { handleConnected() }
        }
    }

    private fun handleConnected() {
        PanaLog.i(TAG, "BLE connected")
        isConnected = true
        isConnecting = false
        resetConnectBackoff()
        PanaLog.i(TAG, "CONN-TRACE: connected addr=${currentState.macAddress}")
        connectionCoordinator.onConnected()
        stateListener?.onConnected(currentState.macAddress ?: "")
        stateListener?.onLogReceived("CONNECTED")
                // v89：移除连接成功弹窗
                // toast("PanaPods 已连接")
        notificationController.update(getString(R.string.service_connected))
        publishBridgeState()
        
                // 记录设备地址到 Bridge 中，让 Hook 层能正常识别
        currentState.macAddress?.let { address ->
            try {
                val device = BluetoothAdapter.getDefaultAdapter()?.getRemoteDevice(address)
                if (device != null) {
                    PanaBridge.setClassicPanaDevice(device)
                    PanaLog.i(TAG, "Registered device to bridge: $address")
                }
            } catch (e: Throwable) {
                PanaLog.e(TAG, "Failed to register device to bridge", e)
            }
        }

                // 等 notification 调用稳定后再发命令
        mainHandler.postDelayed({
            val engine = protocolEngine
            if (engine == null || !isConnected) {
                PanaLog.d(TAG, "initSession skipped: engine=${if (engine == null) "NULL" else "ok"} connected=$isConnected")
                return@postDelayed
            }
            PanaLog.d(TAG, "Sending initSession + battery queries")
            PanaLog.d("BLE_DIAGNOSTIC", ">>> BATTERY QUERIES ABOUT TO SEND <<<")
            stateListener?.onLogReceived("SEND: initSession")
            engine.initSession()
                        // 查询耳机真实 ANC 模式，使重连/手机重启后控制中心按钮反映实际状态
                        // （耳机硬件保留降噪模式，但 app 缓存默认 0，不查询会导致按钮错误显示「关闭」）
            stateListener?.onLogReceived("SEND: getOutsideCtrl")
            engine.getOutsideCtrl()
            // 首次电量/左右探测
            refreshBattery()
            // 周期刷新：覆盖“先戴一只，过一会再戴另一只”时副耳电量不出现的场景
            mainHandler.removeCallbacks(batteryRefreshRunnable)
            mainHandler.postDelayed(batteryRefreshRunnable, BATTERY_REFRESH_INTERVAL_MS)
            PanaLog.d("BLE_DIAGNOSTIC", ">>> BATTERY QUERIES SENT <<<")
        }, 300)
    }

    override fun onDisconnected() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            handleDisconnected()
        } else {
            mainHandler.post { handleDisconnected() }
        }
    }

    private fun handleDisconnected() {
        PanaLog.i(TAG, "BLE disconnected")
        // v171：区分「连上后掉线」与「压根没连上的失败」——后者要计入连接退避，
        // 否则耳机不在场时会陷入每 5~6s 一次的重连风暴。
        val wasConnected = isConnected
        PanaLog.i(
            TAG,
            "CONN-TRACE: disconnected addr=${currentState.macAddress} wasConnected=$wasConnected leAudio=${isLeAudioConnected()}"
        )
        isConnected = false
        isConnecting = false
        if (!wasConnected) noteConnectFailure("disconnected before establishment")
        connectionCoordinator.onDisconnected()
        mainHandler.removeCallbacksAndMessages(null)
        bridgePublishPending = false
        // Clear per-session sensor data so the UI does not keep stale values.
        resetTransientState()
        stateListener?.onStateChanged(currentState)
        stateListener?.onLogReceived("DISCONNECTED")
        stateListener?.onDisconnected()
                // v89：移除断开连接弹窗
                // toast("PanaPods 已断开")
        notificationController.update(getString(R.string.service_disconnected))
        publishBridgeState()
    }

    override fun onRacePacketReceived(packet: RacePacket) {
        if (PanaLog.enabled) {
            PanaLog.d("BLE_DIAGNOSTIC", ">>> RACE PACKET RECEIVED: raceId=${packet.raceId} type=${packet.type} payload_size=${packet.payload?.size ?: 0}")
        }
        // Parse + dispatch on main so every ResponseListener callback and every
        // HeadphoneState mutation happens on the main thread.
        mainHandler.post {
            protocolEngine?.handlePacket(packet)
            if (PanaLog.enabled) {
                stateListener?.onLogReceived("RX: $packet")
            }
        }
    }

    override fun onError(message: String) {
        PanaLog.e(TAG, "BLE Error: $message")
        mainHandler.post {
            stateListener?.onLogReceived("ERROR: $message")
        }
                // v89：移除错误弹窗
                // toast("BLE 错误: $message")
    }

    /**
          * START_STICKY 重启后的自动重连：若开启自动连接且蓝牙已开、上次设备仍已配对，
          * 则重新建立 GATT。覆盖“耳机仍连着但 app 被 LMK/划掉杀死”的场景（无
          * ACL_CONNECTED 广播触发）。
     */
    private fun tryReconnectFromSaved() {
        try {
            if (suppressAutoReconnect) {
                PanaLog.d(TAG, "tryReconnectFromSaved: suppressed (user disconnected)")
                return
            }
            if (!config.autoConnect) return
            val address = config.lastBtAddress ?: return
            if (!isBluetoothOn()) return
            val bonded = isBonded(address)
            PanaLog.i(TAG, "Sticky restart reconnect: addr=$address bonded=$bonded")
            if (bonded) connect(address)
        } catch (e: Throwable) {
            PanaLog.w(TAG, "tryReconnectFromSaved failed: ${e.message}")
        }
    }

    // ============ PanaProtocolEngine.ResponseListener ============

    override fun onBatteryReceived(target: Int, level: Int) {
        PanaLog.d("BLE_DIAGNOSTIC", ">>> BATTERY RECEIVED: target=$target level=$level%")
        // 0xFF(255) 是协议里的“断开/未知”哨兵值，统一归一化为 null。
        val validLevel = PanaBridge.normalizeBatteryOrNull(level)
        if (validLevel == null) {
            // v176：文案与行为对齐——并非忽略，而是按协议哨兵（255/越界）归一化为
            // null 后照常交给 tracker：AGENT 侧清显示；PARTNER 侧 255=不在位，
            // 清显示并记为已应答（本轮不等它）。
            PanaLog.d(TAG, "onBatteryReceived: level=$level not a valid percentage, normalized to null (target=$target)")
        }
        if (!batteryTracker.onBatteryReceived(target, validLevel)) {
            PanaLog.d(TAG, "onBatteryReceived: ignoring unknown target=$target level=$level")
        }
        recomputeBatteryState()
    }

    override fun onPartnerBatteryReceived(level: Int) {
        PanaLog.d("BLE_DIAGNOSTIC", ">>> PARTNER BATTERY RECEIVED: level=$level%")
        batteryTracker.onPartnerBatteryReceived(PanaBridge.normalizeBatteryOrNull(level))
        recomputeBatteryState()
    }

    override fun onDstDiscovered(dstType: Int, dstId: Int) {
        PanaLog.i(TAG, "Partner dst discovered: type=$dstType id=$dstId")
        if (dstType == PanaProtocolEngine.DST_TYPE_AWS_PEER) {
            // v168：缓存 dst，供后续漏轮补发 relay 使用
            partnerDstType = dstType
            partnerDstId = dstId
            protocolEngine?.relayGetBattery(dstType, dstId)
        }
    }

    override fun onSideProbeReceived(side: Int, present: Boolean) {
        if (PanaLog.enabled) {
            val t = SystemClock.elapsedRealtime() % 100000
            stateListener?.onLogReceived("SIDE t=$t side=$side present=$present")
        }
        PanaLog.d(TAG, "Side probe: side=$side present=$present")
        batteryTracker.onSideProbeReceived(side, present)
        recomputeBatteryState()
    }

    /**
     * 把 agent/partner 电量映射到物理左右耳（计算逻辑在 BatteryStateTracker），
     * 有变化时更新 currentState 并通知 UI。
     */
    private fun recomputeBatteryState() {
        // v169：左右耳对调开关（用户可选兜底）每次重算前同步，改设置立即生效
        batteryTracker.swapEarSides = config.swapEarSides
        val (newLeft, newRight) = batteryTracker.computeDisplayBatteries()
        if (newLeft != currentState.leftBattery || newRight != currentState.rightBattery) {
            currentState = currentState.copy(leftBattery = newLeft, rightBattery = newRight)
            notifyStateChange()
            // v169：左右映射诊断。直连 android.util.Log（不受调试日志开关影响），
            // 下次若再出现「左右相反」，抓这条日志即可判定是 agent 侧默认值不对、
            // 还是在位探测给错了侧。
            PanaLog.i(
                TAG,
                "battery map: agent=${batteryTracker.agentBattery} partner=${batteryTracker.partnerBattery} " +
                    "Lpresent=${batteryTracker.leftPresent} Rpresent=${batteryTracker.rightPresent} " +
                    "agentIsLeft=${batteryTracker.agentIsLeft} swap=${batteryTracker.swapEarSides} " +
                    "-> left=${newLeft} right=${newRight}"
            )
        }
    }

    /**
     * 查询主耳/副耳/充电盒电量、发现副耳并 relay 查询、探测左右在位。
     *
     * [light] 为 true（媒体播放中）时省略左右在位探测（2 条 GATT 写入），
     * 但保留副耳发现 + relay 查询——Pana 固件只接受 AGENT 直查，副耳电量
     * 必须走 relay，跳过会导致副耳电量被 2s 看门狗误清空。
     */
    private fun refreshBattery(light: Boolean = false) {
        if (!isConnected) return
        val engine = protocolEngine ?: return
        if (PanaLog.enabled) {
            val t = SystemClock.elapsedRealtime() % 100000
            stateListener?.onLogReceived("REFRESH t=$t light=$light")
        }
        if (!light || (batteryTracker.leftPresent == null && batteryTracker.rightPresent == null)) {
            // 先探测左右在位：关闭/放回单耳时，这一步的失败响应能最快清掉该侧显示。
            // 播放中（light）也保留一次探测，直到左右在位状态已知，避免单耳
            // 连接时电量被隐藏。
            engine.querySidePresence()
        }
        engine.getBattery(PanaProtocolEngine.BATTERY_TARGET_AGENT)
        // Pana 固件只接受 AGENT 直查，PARTNER 直查总是无响应还会触发 6s 超时重发。
        // 副耳电量统一走 discoverPartnerDst → relay 路径，减少无效 GATT 写入。
        engine.getCradleBattery()
        // v168：上一轮副耳电量没应答（relay 丢失 / discovery 返回 ACK）时，
        // 用缓存的对端 dst 直接补发一次 relay，避免副耳电量长期读不到。
        // 健康轮次（streak==0）不发，保持原有的 GATT 写入量。
        if (batteryTracker.partnerMissStreak > 0 && partnerDstType >= 0 && partnerDstId >= 0) {
            PanaLog.d(TAG, "supplemental partner relay to type=$partnerDstType id=$partnerDstId")
            engine.relayGetBattery(partnerDstType, partnerDstId)
        }
        // Pana 副耳电量必须走 relay，light 模式也必须保留此查询
        engine.discoverPartnerDst()
        // 本轮副耳应答看门狗：2s 内没收到副耳电量就把该侧清空（副耳已关闭/入盒）
        batteryTracker.startRefreshCycle()
        mainHandler.removeCallbacks(partnerBatteryTimeoutRunnable)
        mainHandler.postDelayed(partnerBatteryTimeoutRunnable, PARTNER_BATTERY_TIMEOUT_MS)
    }

    /** v178：触发 TWS 同步 —— 发现副耳并发起 relay 查询，建立音频转发链路。
     * 在 LE Audio 连接建立时调用，确保副耳能及时接收音频转发，防止单耳无声。 */
    private fun triggerTwSync() {
        val engine = protocolEngine ?: return
        if (!isConnected) return
        PanaLog.i(TAG, "Triggering TWS sync for audio forwarding")
        // 发现副耳 dst
        engine.discoverPartnerDst()
        // 若已有缓存的 dst，立即补发一次 relay 查询，建立转发路径
        if (partnerDstType >= 0 && partnerDstId >= 0) {
            PanaLog.d(TAG, "TWS sync: supplemental relay to type=$partnerDstType id=$partnerDstId")
            engine.relayGetBattery(partnerDstType, partnerDstId)
        }
    }

    /** 是否有媒体正在播放（音乐/视频/通话），用于自适应放缓 GATT 轮询。 */
    private fun isMusicActive(): Boolean {
        return try {
            audioManager?.isMusicActive == true
        } catch (_: Throwable) {
            false
        }
    }

    override fun onCradleBatteryReceived(level: Int) {
        // 同样归一化：255=盒不在位/未知，不能当成 100% 显示
        currentState = currentState.copy(cradleBattery = PanaBridge.normalizeBatteryOrNull(level))
        notifyStateChange()
    }

    override fun onOutsideCtrlReceived(mode: Int, ncLevel: Int, ambientLevel: Int) {
        PanaLog.d("BLE_DIAGNOSTIC", ">>> OUTSIDE CTRL RECEIVED: mode=$mode ncLevel=$ncLevel ambientLevel=$ambientLevel")
        // v175：耳机回包的 mode 未做范围校验（协议层只 and 0xFF），异常值会污染
        // currentState.outsideCtrl，让 ANC 选择器三个按钮全部失焦。
        if (!AncMode.isValid(mode)) {
            PanaLog.w(TAG, "onOutsideCtrlReceived: invalid mode=$mode, ignore")
            return
        }
                // v95.3: 保护窗口内，如果耳机返回的不是期望模式，则是处理当中切换的旧值，忽略避免 UI 闪烁
        val now = SystemClock.elapsedRealtime()
        if (AncMode.isValid(pendingAncMode) && now < pendingAncUntil && mode != pendingAncMode) {
            PanaLog.d(TAG, "onOutsideCtrlReceived: ignoring stale mode=$mode during pending=$pendingAncMode (${pendingAncUntil - now}ms left)")
            return
        }
                // 实际模式到达（或保护窗口结束），清除待定状态
        if (mode == pendingAncMode || now >= pendingAncUntil) pendingAncMode = -1
        currentState = currentState.copy(
            outsideCtrl = mode,
            ncLevel = ncLevel,
            ambientLevel = ambientLevel
        )
        lastAncMode = mode
        persistAncMode(mode)
        notifyStateChange()
    }

    override fun onAmbientModeReceived(mode: Int) {
        currentState = currentState.copy(ambientMode = mode)
        notifyStateChange()
    }

    override fun onSoundModeReceived(mode: Int) {
        currentState = currentState.copy(soundMode = mode)
        notifyStateChange()
    }

    override fun onStatusReceived(data: ByteArray) {
        if (PanaLog.enabled) {
            stateListener?.onLogReceived("Status: ${data.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }}")
        }
    }

    override fun onMultiPointReceived(mode: Int) {
        currentState = currentState.copy(multiPoint = mode)
        notifyStateChange()
    }

    override fun onAdaptiveAncReceived(enabled: Boolean) {
        currentState = currentState.copy(adaptiveAnc = enabled)
        notifyStateChange()
    }

    override fun onSpatialAudioReceived(enabled: Boolean, headTracking: Boolean) {
        currentState = currentState.copy(spatialAudio = enabled, headTracking = headTracking)
        notifyStateChange()
    }

    override fun onModelIdReceived(modelId: Int) {
        currentState = currentState.copy(modelId = modelId)
        notifyStateChange()
    }

    override fun onResponseReceived(packet: RacePacket) {
        stateListener?.onLogReceived("RESP: id=${packet.raceId}")
    }

    override fun onIndicationReceived(packet: RacePacket) {
        stateListener?.onLogReceived("IND: id=${packet.raceId}")
    }

        // ============ 内部工具 ============

    /**
     * Clear per-session sensor data (battery/cradle) on disconnect so the UI
     * shows "--" instead of stale values. macAddress/deviceName are kept for
     * reconnect and display purposes.
     */
    private fun resetTransientState() {
        currentState = currentState.copy(
            leftBattery = null,
            rightBattery = null,
            cradleBattery = null
        )
        batteryTracker.reset()
        // v168：副耳 dst 是跨会话无效的（重连后固件可能重新分配）
        partnerDstType = -1
        partnerDstId = -1
        // v178：重置 LE Audio 连接状态标记，下次连接时重新触发 TWS 同步
        lastLeAudioConnected = false
    }

    /**
     * 统一释放当前 BLE 客户端与协议引擎。
     * [notifyDisconnected] 为 true 时对外广播断开事件；重连前清理旧连接时传 false，
     * 避免 UI 在“重连中”出现一次多余的断开闪烁。
     */
    private fun teardownClient(notifyDisconnected: Boolean) {
        PanaLog.i(
            TAG,
            "CONN-TRACE: teardown notify=$notifyDisconnected streak=${connectBackoff.streak}"
        )
        isConnecting = false
        isConnected = false
        bridgePublishPending = false
        connectionCoordinator.cancelConnectTimeout()
        mainHandler.removeCallbacks(batteryRefreshRunnable)
        mainHandler.removeCallbacks(partnerBatteryTimeoutRunnable)
        // 先 detach 旧客户端，避免其异步 GATT 回调（STATE_DISCONNECTED 等）
        // 在后续重连时把新连接状态重置掉；再 release 引擎停止超时轮询器。
        bleClient?.detach()
        bleClient?.disconnect()
        bleClient = null
        protocolEngine?.release()
        protocolEngine = null
        // Clear per-session sensor data so the UI does not keep stale values.
        resetTransientState()
        if (notifyDisconnected) {
            stateListener?.onStateChanged(currentState)
            stateListener?.onDisconnected()
            notificationController.update(getString(R.string.service_not_connected))
        }
    }

    /** 查询系统蓝牙栈中该设备真实的 GATT 连接状态，用于识别 stale isConnected。 */
    /**
     * 给定地址当前是否在系统里真实连接（A2DP / HEADSET / LE_AUDIO 任一，按地址精确匹配）。
     *
     * 精确匹配是刻意为之：同一副耳机的经典/LE 两个地址前 4 段相同，若用兄弟匹配，
     * 已回盒那只也会被误判为「在线」，跟随逻辑就失效了。
     */
    private fun isAddressConnected(address: String): Boolean {
        val btManager = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager ?: return false
        profiles@ for (profile in intArrayOf(
            BluetoothProfile.A2DP, BluetoothProfile.HEADSET, BluetoothProfile.LE_AUDIO
        )) {
            val devices = try { btManager.getConnectedDevices(profile) } catch (_: Throwable) { null }
                ?: continue@profiles
            for (d in devices) {
                val a = try { d.address } catch (_: Throwable) { null } ?: continue
                if (a.equals(address, ignoreCase = true)) return true
            }
            // v173：同一 profile 的代理视角（BluetoothManager 快捷查询在本 ROM 上对
            // LE_AUDIO 盲区，代理能看到的设备集不一定与之相同）。
            val proxyDevices = profileProxyDevices(profile) ?: continue@profiles
            for (d in proxyDevices) {
                val a = try { d.address } catch (_: Throwable) { null } ?: continue
                if (a.equals(address, ignoreCase = true)) return true
            }
        }
        // v173：AudioManager 输出设备精确匹配。
        run {
            val am = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            if (am != null) {
                for (info in try { am.getDevices(AudioManager.GET_DEVICES_OUTPUTS) } catch (_: Throwable) {
                    emptyArray<AudioDeviceInfo>()
                }) {
                    val t = try { info.type } catch (_: Throwable) { -1 }
                    if (t != 26 && t != 22) continue
                    val a = try { info.address } catch (_: Throwable) { "" } ?: ""
                    if (a.equals(address, ignoreCase = true)) return true
                }
            }
        }
        // v173：广播观测集（ACL/LE-Audio 连接事件）精确匹配。
        if (systemConnectedAddresses.any { it.equals(address, ignoreCase = true) }) return true
        return false
    }

    /** 本机当前在线的本耳机地址（A2DP/HEADSET/LE_AUDIO），解析不到返回 null。 */
    private fun livePanaAddress(): String? = findConnectedPanaAddress(
        this,
        listOf(
            currentState.macAddress,
            config.lastBtAddress,
            PanaBridge.getMacAddress(),
            PanaBridge.getLc3MacAddress()
        )
    )

    private fun isActuallyGattConnected(address: String): Boolean {
        return try {
            val btManager = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            val device = btManager?.adapter?.getRemoteDevice(address) ?: return false
            btManager.getConnectionState(device, BluetoothProfile.GATT) == BluetoothProfile.STATE_CONNECTED
        } catch (e: Throwable) {
            PanaLog.w(TAG, "isActuallyGattConnected failed: ${e.message}")
            false
        }
    }

    /** 系统是否已通过 LE Audio/LC3 连接本耳机（音频由 LE Audio 承载）。 */
    private fun isLeAudioConnected(): Boolean {
        return try {
            val btManager = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager ?: return false
            val current = currentState.macAddress
            val live: (String) -> Boolean = { addr ->
                PanaBridge.isCurrentDevice(addr) ||
                    (current != null && current.equals(addr, ignoreCase = true))
            }
            // 来源一：BluetoothManager 快捷查询（经典路径，本 ROM 上对三方可能为空）。
            val devices = btManager.getConnectedDevices(BluetoothProfile.LE_AUDIO)
            if (devices.any { d -> live(d.address) }) return true
            // v173 来源二：LE_AUDIO profile 代理。
            profileProxyDevices(BluetoothProfile.LE_AUDIO)?.let { list ->
                if (list.any { d -> runCatching { live(d.address) }.getOrDefault(false) }) return true
            }
            // v173 来源三：AudioManager 输出设备。
            run {
                val am = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                if (am != null) {
                    for (info in am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
                        val t = try { info.type } catch (_: Throwable) { -1 }
                        if (t != 26 /* TYPE_BLE_HEADSET */) continue
                        val a = try { info.address } catch (_: Throwable) { "" } ?: ""
                        if (a.isNotBlank() && live(a)) return true
                    }
                }
            }
            // v173 来源四：广播观测集。
            if (systemConnectedAddresses.any { live(it) }) return true
            false
        } catch (_: Throwable) { false }
    }

    // ============ v173：系统层连接观测（广播接收器 + profile 代理申请） ============

    private var observersRegistered = false
    private var connectionObserverReceiver: android.content.BroadcastReceiver? = null

    /**
     * 注册：
     * 1. ACL 连接状态广播 → 实时维护 systemConnectedAddresses（可靠的“耳机连/断”信号，
     *    不依赖被 ROM 收紧的三方查询 API；LE 连接同样会触发 ACL 广播）；
     * 2. profile 代理请求（A2DP/HEADSET/LE_AUDIO）→ 补足查询视角。
     */
    private fun registerSystemConnectionObservers() {
        if (observersRegistered) return
        observersRegistered = true
        requestProfileProxiesOnce(this)
        try {
            val receiver = object : android.content.BroadcastReceiver() {
                override fun onReceive(ctx: Context?, intent: Intent?) {
                    val dev = intent?.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return
                    val addr = try { dev.address } catch (_: Throwable) { null } ?: return
                    val refs = liveRefsSnapshotForObserver()
                    when (intent.action) {
                        BluetoothDevice.ACTION_ACL_CONNECTED -> {
                            if (isObservedPanaAddress(applicationContext, addr, refs)) {
                                if (systemConnectedAddresses.add(addr.uppercase())) {
                                    PanaLog.i(TAG, "SYS-OBS: system connected $addr (watch=$systemConnectedAddresses)")
                                }
                            }
                        }
                        BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                            if (systemConnectedAddresses.removeAll {
                                    it.equals(addr, ignoreCase = true) ||
                                        PanaBridge.isSameDeviceAddress(it, addr)
                                }) {
                                PanaLog.i(TAG, "SYS-OBS: system disconnected $addr (watch=$systemConnectedAddresses)")
                            }
                        }
                    }
                }
            }
            val filter = IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
                addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            }
            connectionObserverReceiver = receiver.also {
                registerReceiver(it, filter, Context.RECEIVER_EXPORTED)
            }
        } catch (t: Throwable) {
            PanaLog.w(TAG, "registerSystemConnectionObservers failed: ${t.message}")
        }
    }

    private fun liveRefsSnapshotForObserver(): List<String?> = listOf(
        currentState.macAddress,
        config.lastBtAddress,
        PanaBridge.getMacAddress(),
        PanaBridge.getLc3MacAddress()
    )

    private fun unregisterSystemConnectionObservers() {
        val receiver = connectionObserverReceiver ?: return
        connectionObserverReceiver = null
        try { unregisterReceiver(receiver) } catch (_: Throwable) {}
    }

    private fun isBluetoothOn(): Boolean {
        return try {
            val btManager = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            btManager?.adapter?.state == BluetoothAdapter.STATE_ON
        } catch (_: Throwable) { false }
    }

    private fun isBonded(address: String): Boolean {
        return try {
            val btManager = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            btManager?.adapter?.getRemoteDevice(address)?.bondState == BluetoothDevice.BOND_BONDED
        } catch (_: Throwable) { false }
    }

    private var bridgePublishPending = false
    private val bridgePublishRunnable = Runnable {
        bridgePublishPending = false
        notificationController.update(buildStatusText())
        publishBridgeState()
    }

    private fun notifyStateChange() {
        lastStateChangeAt = SystemClock.elapsedRealtime()
        PanaLog.d(TAG, "notifyStateChange: L=${currentState.leftBattery} R=${currentState.rightBattery} C=${currentState.cradleBattery} anc=${currentState.outsideCtrl}")
        stateListener?.onStateChanged(currentState)
        // Battery/ANC responses often arrive in bursts (agent, partner, cradle, anc...).
        // Debounce the notification + cross-process broadcast so we don't spam the
        // system on every single field.
        if (!bridgePublishPending) {
            bridgePublishPending = true
            mainHandler.postDelayed(bridgePublishRunnable, 300)
        }
    }

    /**
          * 广播状态到 Xposed Hook 进程
          * Hook 侧的 PanaBridge 接收后缓存，注入到系统蓝牙设置/MiLink
     */
    private fun publishBridgeState() {
        PanaLog.d("BLE_DIAGNOSTIC", ">>> publishBridgeState: L=${currentState.leftBattery} R=${currentState.rightBattery} C=${currentState.cradleBattery} anc=${currentState.outsideCtrl}")
        PanaBridge.publishState(
            context = this,
            left = currentState.leftBattery ?: -1,
            right = currentState.rightBattery ?: -1,
            cradle = currentState.cradleBattery ?: -1,
            anc = currentState.outsideCtrl,
            name = currentState.deviceName,
            addr = currentState.macAddress,
            connected = isConnected,
            lc3Addr = PanaBridge.getLc3MacAddress()  // v127：通知 Hook 进程 LC3 副地址
        )
    }

    private fun buildStatusText(): String {
        // v166：缺失电量用 "-" 表示（以前是 "?"）。有效值带 "%"，无效/未知只显示 "-"，
        // 与 App 内电量组件（StatusComponents）及融合中心面板的 "-" 保持一致。
        fun slot(v: Int?): String = if (v != null && v in 0..100) "$v%" else "-"
        val anc = HeadphoneText.ancModeText(this, currentState.outsideCtrl)
        return getString(
            R.string.notification_status_format,
            slot(currentState.leftBattery),
            slot(currentState.rightBattery),
            slot(currentState.cradleBattery),
            anc
        )
    }
}
