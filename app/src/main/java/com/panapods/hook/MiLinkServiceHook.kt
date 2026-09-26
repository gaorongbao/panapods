package com.panapods.hook

import com.panapods.headphones.AncMode

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothLeAudio
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Bundle
import com.panapods.utils.Async
import com.panapods.utils.PanaLog
import com.panapods.xposed.XposedBridge
import com.panapods.xposed.XposedHelpers
import com.panapods.xposed.XC_MethodHook
import com.panapods.bridge.PanaBridge
import com.panapods.bridge.PanaPodsProvider
import java.util.concurrent.ConcurrentHashMap

/**
  * MiLink 融合设备中心 Hook（作用域: com.milink.service，含 :core/:ui/:provider 等子进程）
 *
  * HyperOS 控制中心的耳机设备卡片 ANC 控件由 com.miui.headset.runtime.* 和
  * com.xiaomi.mxbluetoothsdk.* 承载。卡片是否显示 ANC 控件的“总开关”是
  * MxBluetooth{Manager,Service}.checkIsMiTWS(device)：原生对 Pana 返回 0（非小米 TWS），
  * 卡片因此只显示“断开/更多设置”。
 *
  * 本 Hook 参考 SonyPods 实现，对 Pana 设备：
  * 1. checkIsMiTWS → 1，getDeviceId → 小米 ANC 耳机产品 ID，使卡片长出 ANC 控件
  * 2. getAncState/getBatteryLevel/getWearStatus 等上报状态，使控件正确显示
  * 3. openAnc/closeAnc/openTransparent 及 AncBatteryController.setAncStateBlock
  *    处理点击，把模式发给 app 切换真实耳机
 *
  * ANC 模式编码：MiLink 与 Pana 一致（0=关闭 / 1=降噪 / 2=通透），无需转换。
 */
object MiLinkServiceHook {

    private const val TAG = "PanaPods/MiLink"

        // 小米 ANC 耳机产品 ID —— 让 headset runtime 把设备识别成支持 ANC 的耳机
        // v95 修改：从头戴式(01013A04)改为 TWS 双耳机样式(01010600)
    private const val HEADPHONES_DEVICE_ID = PanaBridge.MIUI_DEVICE_ID
        // 松下/Technics 耳机在融合中心卡片的正确显示名（优先用桥接缓存的真实设备名，避免退化为本机蓝牙名）
    private fun panaDisplayName(): String = PanaBridge.getDeviceName() ?: PanaBridge.PANA_DISPLAY_NAME

    private val MXBT_CLASSES = listOf(
        "com.xiaomi.mxbluetoothsdk.manager.MxBluetoothManager",
        "com.xiaomi.mxbluetoothsdk.service.MxBluetoothService"
    )
    private const val ANC_BATTERY_CONTROLLER = "com.miui.headset.runtime.AncBatteryController"
        // v162：耳机详情面板（HeadSetsDetail）的电量/ANC 数据源。面板同步首帧从它取
        // getBluetoothDeviceBattery / getBluetoothDeviceMode，首帧未就绪 → 电量区与
        // “噪声控制”标题 GONE，等异步广播才出现。hook 它即可消除首帧延迟。
    private const val HEADSET_SERVICE_CONTROLLER = "com.miui.circulate.api.protocol.headset.HeadsetServiceController"
    private const val ANC_SECTION = "com.miui.circulateplus.world.headset.r"
    private const val THIRD_PARTY_STRATEGY = "com.miui.headset.runtime.model.ThirdPartyHeadsetStrategy"
    private const val THIRD_PARTY_MODEL = "com.miui.headset.runtime.model.HeadsetStateModel\$ThirdPartyModel"
    private const val PROFILE_CONTEXT = "com.miui.headset.runtime.ProfileContext"
    private const val HEADSET_INFO = "com.miui.headset.api.HeadsetInfo"
        // 融合中心设备列表阀门：DiscoveryImpl 遍历已配对/已连接设备时用
        // ProfileContextKt.isSupportEarphone(device, boolean) 过滤哪些成为耳机卡片。
    private const val PROFILE_CONTEXT_KT = "com.miui.headset.runtime.ProfileContextKt"
        // 诊断：融合中心每张卡片的数据载体，构造 HeadsetDevice(address, name, deviceId, BluetoothDevice)
    private const val HEADSET_DEVICE = "com.miui.headset.runtime.HeadsetDevice"
        // Cir_MDC 通用蓝牙观察者（protocol 524288 / third_headset 电量路径）
    private const val CIRCULATE_SEARCH_A = "com.miui.circulate.device.service.search.a"

        // 缓存已判定的 Pana / 非 Pana 设备地址。isPana 优先用地址（本地字符串比较）短路，
        // 避免每次 hook 回调都调用 device.name 触发蓝牙 Binder IPC —— 那是过去 TWS 掉线、
        // 连接偶发失败的主因（getBatteryLevel/checkIsMiTWS 等每帧调用数十次）。
    private val panaAddresses =
        java.util.Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
        // v161：doPrewarm() 节流时间戳 —— 2s 内重复调用直接跳过，避免列表构建期
        // 高频枚举 bondedDevices / 取 device.name（Binder IPC）造成重复开销。
    @Volatile private var lastPrewarmAt = 0L
        // v163：面板 ANC 区块控制器 r 的活实例（弱引用）。点击降噪按钮时同帧调用其 M(mode)，
        // 让高亮立即切换，而不是等异步写回调/系统推送。
    private val ancSectionRefs =
        java.util.concurrent.CopyOnWriteArrayList<java.lang.ref.WeakReference<Any>>()
        // v163 加固：面板点击降噪按钮时记录的系统模式 + 时间戳。写未完成的窗口内，
        // getBluetoothDeviceMode 直接返回它，避免 a0() 重读用旧值把高亮打回去（瞬时闪烁）。
    @Volatile private var lastPanelAncSysMode: Int = -1
    @Volatile private var lastPanelAncSysAt: Long = 0L
        // Pana 经典/DUAL 地址的 BluetoothDevice（能伪装 ANC 的主地址）。
        // 当 getActiveDevice() 返回 LE 副地址时重定向到它，保证活动卡片稳定带 ANC 控件。
    @Volatile private var classicPanaDevice: BluetoothDevice? = null
        // 诊断用：记录已打印判定日志的地址，避免刷屏（每地址只打一次）
    private val loggedDecisions =
        java.util.Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
        // 诊断用：记录已打印 hook 命中日志的 method|addr，避免刷屏
    private val loggedHooks =
        java.util.Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
        // v177：reapInactivePanaAddress 两轮确认机制的待清理地址缓存。
    // 防止 profile 代理竞态导致误清：需连续两轮 3s 保活都满足离线条件才真清。
    @Volatile private var pendingReapAddr: String? = null
        // v99：融合中心当前活动 Pana 地址（ProfileContext.getActiveDevice 观测值）。
        // LC3/LE-Audio 模式下为 LE/LC3 副地址，经典模式下为主地址。卡片应建立在该地址上，
        // 而不是强行重定向回主地址（旧逻辑会让卡片与真实活动设备错位，ANC 控件时有时无）。
    @Volatile private var activePanaAddress: String? = null
        // v99.1：LE Audio 群组 lead 设备查询代理。LC3 模式下 lead 会在主地址/副地址之间
        // 动态切换，直接向系统 BluetoothLeAudio 查询比 ProfileContext.getActiveDevice 更可靠
        // （后者在 :core 发现进程中可能根本不被调用）。
    @Volatile private var leAudioProfile: BluetoothLeAudio? = null
    @Volatile private var leAudioProfileReady: Boolean = false

    // v169：卡图阀门的系统级兜底——直接查 A2DP/Headset profile 已连接设备，
    // 不依赖 App GATT/Bridge 数据，BT 连上的瞬间即可判定 Pana 卡活动。
    @Volatile private var a2dpProfile: BluetoothProfile? = null
    @Volatile private var headsetProfile: BluetoothProfile? = null
    @Volatile private var lastMediaProxyRequestAt: Long = 0L

        // 控制中心卡片 ANC 高亮状态：点击后立即记录本地模式，getAncState 优先返回它，
        // 使卡片高亮即时跳转（不必等 app→BLE→耳机→回传的异步往返）。
    @Volatile private var currentAnc: Int = -1
    @Volatile private var lastAncClickAt: Long = 0L
        // 不同类型消息的最后发送时间（用于节流）
    private val lastNotifyMap = ConcurrentHashMap<String, Long>()
        // 持有 headsetPropertyChangeListener 的 runtime 实例集合。systemui 卡片实际通过
        // ProfileContext.headsetPropertyChangeListener 订阅刷新，AncBatteryController 也可能持有，
        // 全部缓存，切换时向每个实例广播通知，确保卡片重新查询并刷新高亮。
    private val runtimeInstances =
        java.util.Collections.newSetFromMap(ConcurrentHashMap<Any, Boolean>())
        // milink 进程的 ClassLoader，供主动补发属性刷新通知时懒解析 ProfileContext / DiscoveryKt。
    @Volatile private var hookClassLoader: ClassLoader? = null
        // 主动补发 ANC 刷新通知的节流时间戳，防止与 assembleHeadsetInfo 内部构造 HeadsetDevice 形成回环。
    @Volatile private var lastNudgeAt: Long = 0L
        // v85：跟踪 HeadsetDevice 构造嵌套深度，避免并发/递归导致多次 firing；当 depth 从 1→0 时再发 ANC 通知
    @Volatile private var hdBuildDepth: Int = 0
        // v87：当前 nudge 是否正在执行中（即 listener.invoke 期间），防止递归调用（assembleHeadsetInfo→HeadsetDevice ctor→nudge）
    @Volatile private var nudgeInFlight: Boolean = false
    // v114：缓存 setHeadsetPropertyChangeListener 注入的 listener 实例。
    // getter 在部分进程/时机返回 null（runtime 懒初始化），缓存后 nudge 不再依赖 getter。
    @Volatile private var cachedHeadsetListener: Any? = null
    // v177：listener 尚未初始化时的待执行回调队列。setHeadsetPropertyChangeListener 被调用时触发。
    private val listenerReadyCallbacks = java.util.Collections.newSetFromMap(
        java.util.concurrent.ConcurrentHashMap<Runnable, Boolean>()
    )
        // v100：事件驱动刷新 —— 收到 app 状态广播（电量/ANC/连接态真正变化）时才补发 nudge，
        // 替代 v99.6 的 10 连发定时 nudge，减少 milink/SystemUI 反复重 assemble 与 app 唤醒。
    @Volatile private var bridgeStateRefreshRegistered = false
    @Volatile private var lastBridgeSignature: String? = null
    @Volatile private var lastStateNudgeAt: Long = 0L
    private val stateChangeHandler = android.os.Handler(android.os.Looper.getMainLooper())
        // 事件驱动 nudge 的最小间隔；避免 app 每 2s 一次的电池广播把卡片重建频率拉满
    private const val STATE_NUDGE_MIN_INTERVAL_MS = 5000L
        // v101：周期性保活刷新。Cir_MDC 会间歇把旧电量缓存 [-1,-1,-1] 推回卡片覆盖正确值，
        // 而事件驱动 nudge 在电量长时间不变时不会触发，导致电量/降噪控件时有时无。
        // 已连接状态下低频补发 type=8+4 nudge，让 Cir_MDC 重新查询（命中 hook 返回真实值）。
        // 间隔 10s，且 nudge 不再唤醒 app，开销远低于 v99.6 的十连发。
    // v123：保活间隔 10s→3s（全局兜底）。卡片打开时若广播/密集窗口都未及时到位，
    // 3s 内必有 nudge → 降噪控件最坏 3s 出现（原 10s）。nudge 开销低（listener 空时
    // 快速返回，有 listener 时 2 次 invoke + 系统广播），3s 频率可接受。
    private const val KEEPALIVE_NUDGE_INTERVAL_MS = 3_000L
    // v125：窗口 5s —— 足够覆盖"点开卡片→降噪出现"的窗口期，又不会让卡片
    // 长时间高频重建（500ms 一次）造成可见闪烁。
    private const val CARD_ACTIVE_WINDOW_MS = 5_000L
    private const val CARD_ACTIVE_INTERVAL_MS = 500L
    @Volatile private var cardActiveUntil = 0L
    private val keepAliveHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val keepAliveRunnable = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            val cardActive = now < cardActiveUntil
            try {
                // v175：先回收已失效的活动地址，否则它一旦非空就永不释放 →
                // 断开后条件恒真，每 3s 空转一次 nudge + 卡图替换。
                reapInactivePanaAddress()
                // 已连接（收到过 app 广播）或已观测到融合中心活动卡片时保活。
                // activePanaAddress 兜底覆盖 milink 进程晚于 app 启动、错过连接广播的场景。
                if (PanaBridge.isConnected() || activePanaAddress != null) {
                    nudgeAncCardRefresh()
                    // v109：保活同时补一次卡图替换。首连时卡片先渲染、Bridge 广播后到，
                    // 旧逻辑只靠卡图自身的 800ms 重试循环兜底，表现为"要等一会儿才出图"。
                    MiLinkCardArtHook.onPanaCardMaybeActive()
                }
            } catch (_: Throwable) {}
            // v117：卡片活跃窗口内用短间隔（节流窗口 2s 仍生效，dense tick 用于
            // 穿过节流间隙尽快重试），窗口外回到 3s 保活（v123 起 10s→3s）。
            keepAliveHandler.postDelayed(this, if (cardActive) CARD_ACTIVE_INTERVAL_MS else KEEPALIVE_NUDGE_INTERVAL_MS)
        }
    }

    /** v125：启动卡片活跃密集刷新窗口（幂等 —— 窗口进行中不重置，防止
     *  "hit→广播→窗口→nudge→重渲染→hit"自我延续的重建风暴循环）。
     *
     * v177：窗口进行中时改为延长而非拒绝，覆盖"点开→离开→5s 后再点"场景，
     * 避免二次点开回到冷启动路径（listener 缺失 + 2s 节流 + 3s 保活等待）。
     */
    fun startCardActiveWindow() {
        val now = System.currentTimeMillis()
        val newUntil = now + CARD_ACTIVE_WINDOW_MS
        if (now < cardActiveUntil) {
            // v177：窗口进行中 → 延长而非拒绝
            if (newUntil > cardActiveUntil) {
                cardActiveUntil = newUntil
                PanaLog.d(TAG, "Card active window extended to ${cardActiveUntil}")
            }
            return
        }
        cardActiveUntil = newUntil
        keepAliveHandler.removeCallbacks(keepAliveRunnable)
        keepAliveHandler.postDelayed(keepAliveRunnable, CARD_ACTIVE_INTERVAL_MS)
        // 窗口开始时立即补一次 nudge，不等下一个 tick。
        try { nudgeAncCardRefresh() } catch (_: Throwable) {}
    }

    fun install(classLoader: ClassLoader) {
        PanaLog.i(TAG, "Installing MiLink hooks...")
        hookClassLoader = classLoader

        // v110：onPackageLoaded 阶段 currentApplication() 可能还没就绪（返回 null），
        // 旧逻辑直接用 null 注册 → registerStateReceiver 静默跳过 → 该进程 Bridge
        // 缓存永远为空（卡图判定 active=false bridge=false，照片永不替换）。
        // 改为延迟重试注册，直到拿到 Application Context。
        registerBridgeReceiverWhenReady()

        // v122：启动预热 Bridge（安全版）。v121 曾因写入半成品电量导致"电量显示不全"，
        // 现在 refreshBridgeFromProviderIfStale 有 connected+完整性双重守卫，只写入
        // 完整数据。预热让 :ui 卡片首帧 assemble 时 getAncState 就有真实值 →
        // 降噪区块与照片同帧渲染。多时间点重试，覆盖 App 数据就绪的时机。
        val warmupHandler = android.os.Handler(android.os.Looper.getMainLooper())
        for (delay in longArrayOf(300L, 3300L, 6300L)) {
            warmupHandler.postDelayed({
                try { refreshBridgeFromProviderIfStale() } catch (_: Throwable) {}
            }, delay)
        }

        hookBatteryInMiLink(classLoader)
        hookCirculateSearchBattery(classLoader)
        hookHeadsetRuntime(classLoader)
        // v162：耳机详情面板（HeadSetsDetail）首帧电量 / 噪声控制标题数据源补齐。
        hookHeadsetServiceController(classLoader)
        // v163：面板 ANC 高亮同帧联动（点击降噪按钮不再等异步回调）。
        hookAncSectionHighlight(classLoader)
                // 预热：把已配对的 Pana 地址提前写入正缓存，避免卡片首次查询时
                // device.name 短时为空导致识别失败、ANC 控件时有时无。
        prewarmPanaAddresses()
        ensureLeAudioProfile()
        // v169：提前请求 A2DP/Headset 代理，让卡图阀门的系统级兜底尽早就绪。
        ensureMediaProfiles()
        // v100：安装预热只保留 2 次（2s/8s），其余刷新交给事件驱动机制。
        // v99.3 原为 2s/5s/10s 三次，实测 2s+8s 足够覆盖 milink 启动时的缓存窗口。
        scheduleDelayedNudge(2000L)
        scheduleDelayedNudge(8000L)
        // v101：启动周期性保活刷新（已连接时每 3s 一次，v123 起 10s→3s），覆盖 Cir_MDC 旧缓存推送窗口。
        keepAliveHandler.postDelayed(keepAliveRunnable, KEEPALIVE_NUDGE_INTERVAL_MS)
    
        PanaLog.i(TAG, "MiLink hooks installed ✓")
    }

        // ============ 设备匹配 ============

    private fun isPana(device: BluetoothDevice?): Boolean {
        if (device == null) return false
        val addr = try { device.address?.uppercase() } catch (_: Throwable) { null }
                // 只信任正缓存短路；负缓存不能短路——早期 name 为 null 或名字错误时
                // 可能把 Pana 误写入负缓存，之后 isCurrentDevice 兜底会被永久跳过，
                // 导致 ANC 控件时有时无。
        if (addr != null && panaAddresses.contains(addr)) return true
                // 优先用 device.name；为空时（Binder 未就绪）回退到已配对设备名解析，
                // 避免 name 瞬时为 null 导致识别失败、卡片不显示 ANC 控件。
        var name = try { device.name } catch (_: Throwable) { null }
        var match = PanaBridge.isPanaDevice(name)
        // v161：device.name 在 LE 副地址场景可能返回错误值（退化成本机蓝牙名），
        // 因此不再只在 name==null 时兜底，而是"名字不匹配就用已配对设备名再判一次"。
        if (!match) {
            val bondedName = try { resolveBondedName(addr) } catch (_: Throwable) { null }
            if (bondedName != null && PanaBridge.isPanaDevice(bondedName)) match = true
            if (name == null) name = bondedName
        }
        // LE Audio/LC3 模式下名字可能取不到或地址是只差最后一段的副地址，
        // 用 Bridge 的经典/LC3/兄弟地址匹配兜底，否则 ANC/音量控件会间歇消失。
        if (!match && addr != null && PanaBridge.isCurrentDevice(addr)) {
            match = true
        }
        if (addr != null && match) {
            panaAddresses.add(addr)
        }
        if (addr != null && loggedDecisions.add(addr)) {
            PanaLog.i(TAG, "isPana decide addr=$addr name=$name le=${isLeOnly(device)} match=$match")
        }
        return match
    }

    /** 纯 LE 传输地址（DEVICE_TYPE_LE）判定：Pana 的 LE 副地址不作为可接 ANC 设备 */
    private fun isLeOnly(device: BluetoothDevice): Boolean {
        return try { device.type == BluetoothDevice.DEVICE_TYPE_LE } catch (_: Throwable) { false }
    }

    /** device.name 为空时，从已配对设备列表按地址取名（已配对设备名持久化、可靠非空） */
    private fun resolveBondedName(addr: String?): String? {
        if (addr == null) return null
        return try {
            val adapter = BluetoothAdapter.getDefaultAdapter() ?: return null
            adapter.bondedDevices?.firstOrNull { it.address.equals(addr, ignoreCase = true) }?.name
        } catch (_: Throwable) { null }
    }

    /** 安装时预热正缓存：枚举已配对设备，把 Pana 地址提前登记，识别不再依赖查询时序 */
    private fun prewarmPanaAddresses() {
                // 进程刚启动时 BluetoothAdapter/bondedDevices 可能未就绪（返回空），
                // 手机重启后蓝牙栈就绪更慢，故立即试 + 多次延迟重试，
                // 确保缓存在用户点开卡片前就热起来，避免卡片构建时 isPana 命中失败、ANC 控件缺失。
        doPrewarm()
        Async.run("milink-prewarm") loop@{
            for (delay in longArrayOf(1000, 3000, 6000, 10000)) {
                try { Thread.sleep(delay) } catch (_: InterruptedException) { return@loop }
                doPrewarm()
            }
        }
    }

    private fun doPrewarm() {
        // v161：节流——2s 内重复调用直接跳过，避免列表构建期高频枚举 bondedDevices。
        val nowPrewarm = System.currentTimeMillis()
        if (nowPrewarm - lastPrewarmAt < 2000L) return
        lastPrewarmAt = nowPrewarm
        try {
            val adapter = BluetoothAdapter.getDefaultAdapter() ?: return
            adapter.bondedDevices?.forEach { d ->
                val n = try { d.name } catch (_: Throwable) { null }
                val addr = d.address?.uppercase() ?: return@forEach
                if (!PanaBridge.isPanaDevice(n)) return@forEach
                                // 回归（v75）：经典/DUAL 与纯 LE 两个同名地址都提前写入正缓存并伪装 ANC，
                                // 不再把 LE 地址归入负缓存/隐藏集（那会干扰连接与降噪）。
                if (panaAddresses.add(addr)) PanaLog.i(TAG, "prewarm Pana addr=$addr name=$n le=${isLeOnly(d)}")
            }
                        // v83：app 已连接时 bridge 会持有真实的经典/LE 地址，直接登记进正缓存。
                        // 这是 device.name 之外最可靠的来源，弥补 bondedDevices 未就绪时的识别空窗。
            PanaBridge.getMacAddress()?.uppercase()?.let {
                if (panaAddresses.add(it)) PanaLog.i(TAG, "prewarm Pana from bridge mac=$it")
            }
            PanaBridge.getLc3MacAddress()?.uppercase()?.let {
                if (panaAddresses.add(it)) PanaLog.i(TAG, "prewarm Pana from bridge lc3=$it")
            }
        } catch (e: Throwable) {
            PanaLog.w(TAG, "prewarm failed: ${e.message}")
        }
    }

    private fun isPanaAddress(addr: String?): Boolean {
        if (addr == null) return false
        val upper = addr.uppercase()
        if (panaAddresses.contains(upper)) return true
        // LC3 副地址兜底：与 Bridge 记录的经典/LC3 地址做同级匹配
        if (PanaBridge.isCurrentDevice(addr)) {
            panaAddresses.add(upper)
            return true
        }
        return false
    }

    /**
     * 融合中心当前活动卡片是否为 Pana（供 MiLinkCardArtHook 判断是否替换卡片图）。
     *
     * 优先取 ProfileContext 观测到的活动地址 / LE Audio lead 地址；两者都没有时，
     * 仅当 Bridge 报告 Pana 已连接（且缓存了经典地址）才返回 true，避免在用户
     * 同时拥有其他小米耳机时把别人的卡片图也换成 Pana。
     *
     * v108：增强容错 —— 即使 LE Audio profile 未就绪，只要 Bridge 报告已连接
     * 或已观测到活动 Pana 地址，都返回 true，确保卡片图替换不会因时序问题漏掉。
     */
    fun isPanaCardActive(): Boolean {
        // 优先：已观测到的活动地址（ProfileContext 或 LE Audio lead）
        val active = activePanaAddress ?: getLeAudioActivePanaAddress()
        if (active != null) return true

        // v169：系统层 BT 连接态判定——A2DP/Headset/LE Audio 任一 profile 上有
        // Pana 设备已连接即为 true。BT 连上的瞬间即可命中，不再等 App GATT/
        // Bridge 数据（修复首连点卡照片先系统图、等几秒才替换的问题）。
        if (isSystemPanaConnected()) return true

        // 兜底：Bridge 缓存已有经典地址且报告已连接
        if (PanaBridge.isConnected() && !PanaBridge.getMacAddress().isNullOrBlank()) return true

        // 再兜底：Bridge 缓存已有 LC3 地址（LE Audio 模式下经典地址可能为空）
        if (PanaBridge.isConnected() && !PanaBridge.getLc3MacAddress().isNullOrBlank()) return true

        return false
    }

    /**
     * v175：回收已失效的活动 Pana 地址。
     *
     * `activePanaAddress` 此前只写不清（全库仅 getActiveDevice 一处赋值）：
     * Pana 连过一次后它在进程生命周期内永久非空，导致
     * ① `isPanaCardActive()` 首行非空即 return true → 后续连上的其他耳机卡片
     *    也被替换成 Pana 产品图；
     * ② keepAlive 条件恒真 → 每 3s 空转一次 nudge + 卡图替换（功耗 + 串台）。
     *
     * 三重确认 Pana 确实不在线才清（Bridge 广播 / LE Audio lead / 系统 profile），
     * 保留"milink 进程错过连接广播"场景下的兜底能力 —— 这正是该字段存在的原因。
     *
     * v177：引入两轮确认机制（pendingReapAddr）。需连续两轮 3s 保活都满足离线条件
     * 才真清，防止 profile 代理竞态/惰性初始化导致误清。同时加入 Bridge 连接态兜底。
     */
    private fun reapInactivePanaAddress() {
        val addr = activePanaAddress ?: return
        // Bridge 连接态兜底：App 层仍连着时绝不清
        if (PanaBridge.isConnected()) {
            pendingReapAddr = null
            return
        }
        if (getLeAudioActivePanaAddress() != null) {
            pendingReapAddr = null
            return
        }
        if (isSystemPanaConnected()) {
            pendingReapAddr = null
            return
        }
        // 两轮确认：首轮标记，次轮真清
        if (pendingReapAddr == addr) {
            activePanaAddress = null
            pendingReapAddr = null
            PanaLog.i(TAG, "active Pana address cleared after 2 confirmations (device offline): $addr")
        } else {
            pendingReapAddr = addr
            PanaLog.d(TAG, "active Pana address marked for reap (waiting 2nd confirmation): $addr")
        }
    }

    /** 获取 LE Audio 代理（异步）。就绪后补发一次卡片刷新，使卡片跟随真实 lead 地址。 */
    private fun ensureLeAudioProfile() {
        if (leAudioProfileReady) return
        try {
            val adapter = BluetoothAdapter.getDefaultAdapter() ?: return
            val ctx = try {
                XposedHelpers.callStaticMethod(
                    Class.forName("android.app.ActivityThread"), "currentApplication"
                ) as? Context
            } catch (_: Throwable) { null } ?: return
            adapter.getProfileProxy(ctx, object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(profile: Int, proxy: BluetoothProfile?) {
                    if (profile == BluetoothProfile.LE_AUDIO && proxy is BluetoothLeAudio) {
                        leAudioProfile = proxy
                        leAudioProfileReady = true
                        PanaLog.i(TAG, "LeAudio profile connected")
                        nudgeAncCardRefresh()
                    }
                }
                override fun onServiceDisconnected(profile: Int) {
                    if (profile == BluetoothProfile.LE_AUDIO) {
                        leAudioProfile = null
                        leAudioProfileReady = false
                    }
                }
            }, BluetoothProfile.LE_AUDIO)
        } catch (t: Throwable) {
            PanaLog.w(TAG, "ensureLeAudioProfile failed: ${t.message}")
        }
    }

    /**
     * 查询系统 LE Audio 群组的 lead 设备地址（uppercase）。
     * LC3 模式下 lead 是主地址或副地址的动态事实；非 Pana / 查询失败时返回 null。
     */
    private fun getLeAudioActivePanaAddress(): String? {
        val profile = leAudioProfile ?: return null
        return try {
            val devices = profile.connectedDevices ?: return null
            var lead: BluetoothDevice? = null
            for (d in devices) {
                val gid = profile.getGroupId(d)
                if (gid != BluetoothLeAudio.GROUP_ID_INVALID) {
                    lead = profile.getConnectedGroupLeadDevice(gid)
                    break
                }
            }
            val addr = lead?.address?.uppercase() ?: return null
            if (panaAddresses.contains(addr) || PanaBridge.isCurrentDevice(addr)) addr else null
        } catch (_: Throwable) { null }
    }

    /**
     * v169：异步获取 A2DP/Headset profile 代理（供 isSystemPanaConnected 使用）。
     * 每次 isPanaCardActive 调用时若代理缺失则惰性重试（5s 节流），
     * 覆盖 install 时 currentApplication() 尚未就绪的场景。
     * v173：LE_AUDIO 代理一并纳入惰性重试——此前只在 install 时申请一次
     * （ensureLeAudioProfile），错过时机即永久缺失；而本机单耳/LE-Audio 场景
     * 恰恰只有 leAudioProfile 能看到耳机，缺失会导致照片阀门失灵。
     */
    private fun ensureMediaProfiles() {
        if (a2dpProfile != null && headsetProfile != null && leAudioProfile != null) return
        val now = System.currentTimeMillis()
        if (now - lastMediaProxyRequestAt < 5000L) return
        lastMediaProxyRequestAt = now
        try {
            val adapter = BluetoothAdapter.getDefaultAdapter() ?: return
            val ctx = try {
                XposedHelpers.callStaticMethod(
                    Class.forName("android.app.ActivityThread"), "currentApplication"
                ) as? Context
            } catch (_: Throwable) { null } ?: return
            val listener = object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(profile: Int, proxy: BluetoothProfile?) {
                    when (profile) {
                        BluetoothProfile.A2DP -> a2dpProfile = proxy as? BluetoothProfile
                        BluetoothProfile.HEADSET -> headsetProfile = proxy as? BluetoothProfile
                        BluetoothProfile.LE_AUDIO -> {
                            if (proxy is BluetoothLeAudio) {
                                leAudioProfile = proxy
                                leAudioProfileReady = true
                            }
                        }
                    }
                }
                override fun onServiceDisconnected(profile: Int) {
                    when (profile) {
                        BluetoothProfile.A2DP -> a2dpProfile = null
                        BluetoothProfile.HEADSET -> headsetProfile = null
                        BluetoothProfile.LE_AUDIO -> {
                            leAudioProfile = null
                            leAudioProfileReady = false
                        }
                    }
                }
            }
            if (a2dpProfile == null) adapter.getProfileProxy(ctx, listener, BluetoothProfile.A2DP)
            if (headsetProfile == null) adapter.getProfileProxy(ctx, listener, BluetoothProfile.HEADSET)
            if (leAudioProfile == null) adapter.getProfileProxy(ctx, listener, BluetoothProfile.LE_AUDIO)
        } catch (t: Throwable) {
            PanaLog.w(TAG, "ensureMediaProfiles failed: ${t.message}")
        }
    }

    /**
     * v169：系统层当前是否有 Pana 耳机处于 BT 连接态（A2DP/Headset/LE Audio 任一）。
     *
     * 卡图替换只需知道"这张卡是 Pana 的卡"，这个信息在 BT 连接建立瞬间系统层
     * 就已知；此前阀门依赖 App GATT 的 Bridge.isConnected()（秒级延迟 + v122
     * 完整电量守卫），导致首连点开卡片时照片先显示系统图、等一等才被替换。
     */
    private fun isSystemPanaConnected(): Boolean {
        ensureMediaProfiles()
        val oui = PanaBridge.getPanaOuiPrefix()
        for (p in listOfNotNull(leAudioProfile, a2dpProfile, headsetProfile)) {
            val devs = try { p.connectedDevices } catch (_: Throwable) { null } ?: continue
            for (d in devs) {
                val a = try { d.address?.uppercase() } catch (_: Throwable) { null }
                if (a != null) {
                    if (panaAddresses.contains(a) || PanaBridge.isCurrentDevice(a)) return true
                    // OUI 前缀兜底（Technics 双地址前 3 字节相同）
                    if (oui != null && a.startsWith(oui)) return true
                }
                // 名字兜底（address 拿不到时）
                val nm = try { d.name } catch (_: Throwable) { null }
                if (PanaBridge.isPanaDevice(nm)) return true
            }
        }
        return false
    }

        // ============ v162：耳机详情面板首帧数据源补齐 ============

    /**
     * 手机弹出的“耳机详情/控制”面板（`HeadSetsDetail`，即截图那个浮层）自上而下是：
     * 设备名 / 耳机大图 / **电量区** / 音量区 / **噪声控制（通透·降噪·关闭）**。
     *
     * 其中【电量区】与【噪声控制标题】的可见性是**数据驱动**的，都同步取自
     * `HeadsetServiceController`，而它在首帧常未就绪：
     *  - 电量：`HeadSetsDetail.S()` 同步调 `getBluetoothDeviceBattery()`，首帧返回
     *    `[-1,-1,-1,0,0,0]` → `y.g()` 判定无数据（`b()` 要求 size≥6 且前三项不全为 -1）
     *    → 两个电量子 View 置 INVISIBLE；
     *  - 标题：`HeadSetsDetail.a0()` 调 `getBluetoothDeviceMode()`，首帧返回 `-1`
     *    → `r.Z(-1)` → `r.M(-1)` → `r.J(false)` → “噪声控制”标题与 ANC 按钮行一起 GONE。
     * 之后异步广播（`onBluetoothBatteryChanged` / `onBluetoothModeChanged`）到达才填上，
     * 用户看到的就是“首帧缺、过一会才出”。
     *
     * 这两条数据都出自 `HeadsetServiceController.getBluetoothDeviceInfo()` →
     * `HeadsetDeviceManager` 缓存，对 Pana 恒为空。因此直接 hook 这两个 getter，
     * 对 Pana 地址（`CirculateServiceInfo.deviceId` 即蓝牙 MAC）返回我们 Bridge 的缓存值，
     * 让首帧同步路径就拿到有效数据，从根上消除延迟 —— 无需依赖任何 view 兜底。
     *
     * ⚠ 模式编码：`getBluetoothDeviceMode` 用的是系统 `CirculateConstants.BluetoothMode`
     * （0=NOISE_CANCELLING / 1=CLEAR / 2=OFF，见 `HeadsetLogUtil.getHeadsetAncMode`），
     * **与项目 AncMode（0=OFF/1=NC/2=Ambient）不同**，必须经 `toMiLinkAncMode()` 映射，
     * 否则 ANC 三按钮高亮会错位、且与 `r.java` 的 item 顺序对不上。
     * （注意：MXBT/AncBatteryController 路径的编码是另一套，见文件头注释，勿混用。）
     */
    private fun hookHeadsetServiceController(classLoader: ClassLoader) {
        val cls = findClass(HEADSET_SERVICE_CONTROLLER, classLoader)
        if (cls == null) {
            PanaLog.i(TAG, "HeadsetServiceController not present in this process (skip)")
            return
        }
        try {
            XposedBridge.hookAllMethods(cls, "getBluetoothDeviceBattery", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        if (!isPanaCirculateInfo(param.args.firstOrNull())) return
                        refreshBridgeFromProviderIfStale()
                        val levels = miLinkBatteryLevels()
                        // 仅在有真实电量时覆盖；全 -1 时保留原值（无数据就没必要伪造）
                        if (levels.any { it in 0..100 }) {
                            param.result = levels
                            PanaLog.i(TAG, "HSC.getBluetoothDeviceBattery → $levels (first-frame)")
                        }
                    } catch (_: Throwable) {}
                }
            })
            XposedBridge.hookAllMethods(cls, "getBluetoothDeviceMode", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        if (!isPanaCirculateInfo(param.args.firstOrNull())) return
                        val nowPanel = System.currentTimeMillis()
                        val sysMode = if (lastPanelAncSysMode in 0..2 && nowPanel - lastPanelAncSysAt < 5000L) {
                            lastPanelAncSysMode
                        } else {
                            toMiLinkAncMode(getAncStateValue())
                        }
                        if (sysMode != -1) {
                            param.result = sysMode
                            PanaLog.i(TAG, "HSC.getBluetoothDeviceMode → $sysMode (first-frame)")
                        }
                    } catch (_: Throwable) {}
                }
            })
            // v163：点击降噪按钮时同帧推高亮。setNoiseCancelling 由面板主线程在点击的同一帧调用，
            // 此时异步写尚未完成；立刻调 r.M(mode) 让高亮立即过去。不设 param.result / returnEarly，
            // 保证原写操作照常执行。
            XposedBridge.hookAllMethods(cls, "setNoiseCancelling", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    try {
                        if (!isPanaCirculateInfo(param.args.firstOrNull())) return
                        val mode = param.args.getOrNull(1) as? Int ?: return
                        if (mode < 0 || mode > 2) return
                        // 预热本地预判：项目编码（供其它消费者）+ 系统编码（供本进程 getBluetoothDeviceMode 立即返回）。
                        onAncClicked(fromMiLinkAncMode(mode))
                        lastPanelAncSysMode = mode
                        lastPanelAncSysAt = System.currentTimeMillis()
                        val fire = Runnable {
                            for (ref in ancSectionRefs) {
                                val section = ref.get() ?: continue
                                try {
                                    XposedHelpers.callMethod(section, "M", mode)
                                } catch (_: Throwable) {}
                            }
                        }
                        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
                            fire.run()
                        } else {
                            android.os.Handler(android.os.Looper.getMainLooper()).post(fire)
                        }
                        PanaLog.i(TAG, "HSC.setNoiseCancelling → optimistic highlight mode=$mode")
                    } catch (_: Throwable) {}
                }
            })
            PanaLog.i(TAG, "HeadsetServiceController hooks installed ✓")
        } catch (t: Throwable) {
            PanaLog.w(TAG, "HeadsetServiceController hooks failed: ${t.message}")
        }
    }

    /**
     * v163：登记面板 ANC 区块控制器 `com.miui.circulateplus.world.headset.r` 的活实例，
     * 供点击降噪按钮时同帧推高亮（见 hookHeadsetServiceController 里的 setNoiseCancelling hook）。
     */
    private fun hookAncSectionHighlight(classLoader: ClassLoader) {
        val cls = findClass(ANC_SECTION, classLoader)
        if (cls == null) {
            PanaLog.i(TAG, "ANC section (r) not present in this process (skip)")
            return
        }
        try {
            XposedBridge.hookAllConstructors(cls, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        ancSectionRefs.add(java.lang.ref.WeakReference(param.thisObject))
                    } catch (_: Throwable) {}
                }
            })
            PanaLog.i(TAG, "ANC section (r) ctor hooked ✓")
        } catch (t: Throwable) {
            PanaLog.w(TAG, "hookAncSectionHighlight failed: ${t.message}")
        }
    }

    /**
     * v162：判断 `CirculateServiceInfo` 是否指向 Pana。
     * 该对象的 `deviceId` 就是蓝牙 MAC（见 `HeadsetServiceController.getBluetoothDevice`
     * 里 `device.getAddress().equals(circulateServiceInfo.deviceId)` 的比对）。
     */
    private fun isPanaCirculateInfo(info: Any?): Boolean {
        if (info == null) return false
        val deviceId = try {
            XposedHelpers.getObjectField(info, "deviceId") as? String
        } catch (_: Throwable) {
            null
        } ?: return false
        val up = deviceId.uppercase()
        if (panaAddresses.contains(up)) return true
        if (PanaBridge.isCurrentDevice(up)) {
            panaAddresses.add(up)
            return true
        }
        val main = try { PanaBridge.getMacAddress()?.uppercase() } catch (_: Throwable) { null }
        if (main != null && main == up) { panaAddresses.add(up); return true }
        val lc3 = try { PanaBridge.getLc3MacAddress()?.uppercase() } catch (_: Throwable) { null }
        if (lc3 != null && lc3 == up) { panaAddresses.add(up); return true }
        return false
    }

    /**
     * v162：项目 `AncMode` 编码 → MiLink 系统 `BluetoothMode` 编码。
     * 系统语义取自 `CirculateConstants.BluetoothMode` 与 `r.java:116` 的 item 定义：
     *   0 = NOISE_CANCELLING(降噪)，1 = CLEAR(通透)，2 = OFF(关闭)，-1 = NOT_SUPPORT。
     */
    private fun toMiLinkAncMode(panaMode: Int): Int = when (panaMode) {
        AncMode.NOISE_CANCELING -> 0  // 降噪
        AncMode.AMBIENT -> 1          // 通透
        AncMode.OFF -> 2              // 关闭
        else -> -1                    // 未知 → 不拦截，交给系统
    }

    /** v163：MiLink 系统 `BluetoothMode` 编码 → 项目 `AncMode` 编码（上述 toMiLinkAncMode 的逆映射）。 */
    private fun fromMiLinkAncMode(sysMode: Int): Int = when (sysMode) {
        0 -> AncMode.NOISE_CANCELING  // 降噪
        1 -> AncMode.AMBIENT          // 通透
        2 -> AncMode.OFF              // 关闭
        else -> -1
    }

        // ============ 核心：headset runtime 属性 + ANC 控制 ============

    private fun hookHeadsetRuntime(classLoader: ClassLoader) {
        // v95 添加：Hook BluetoothDevice.getDeviceClass() 为TWS等级（控制中心图标）
        try {
            XposedBridge.hookAllMethods(BluetoothDevice::class.java, "getDeviceClass",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val device = param.thisObject as? BluetoothDevice ?: return
                        if (!isPana(device)) return
                                                // 返回 TWS 等级的设备类（代替头戴式 0x040108）
                        // 0x040114: Audio Device Class (0x04), Wearable Headset (0x14)
                        param.result = 0x040114
                        PanaLog.d(TAG, "getDeviceClass spoofed to 0x040114 (TWS) for Pana")
                    }
                }
            )
            PanaLog.i(TAG, "BluetoothDevice.getDeviceClass() hooked ✓")
        } catch (e: Throwable) {
            PanaLog.w(TAG, "Failed to hook BluetoothDevice.getDeviceClass()", e)
        }

        for (cn in MXBT_CLASSES) {
            val clazz = findClass(cn, classLoader) ?: continue
                        // 让卡片把 Pana 识别为支持 ANC 的小米耳机
            hookDevResult(clazz, "checkIsMiTWS") { 1 }
            hookDevResult(clazz, "getDeviceId") { HEADPHONES_DEVICE_ID }
            hookDevResult(clazz, "getAncState") { getAncStateValue() }
            // v103：milink 进程内跳过 MXBT getBatteryLevel 注入。
            // Cir_MDC 通用蓝牙观察者 (protocol 524288) 会调用它 3 次拼成
            // [平均,平均,平均]，与耳机路径 [盒,左,右] 互相覆盖导致卡片电量/降噪时有时无。
            // 融合中心卡片电量实际来自 getBatteryLevelCache/HeadsetInfo powers，无需此注入。
            if (!isMilinkProcess()) {
                hookDevResult(clazz, "getBatteryLevel") { batteryOrDefault() }
            } else {
                PanaLog.i(TAG, "skip MXBT getBatteryLevel hook in ${currentProcessName()} (avoid Cir_MDC battery race)")
            }
            hookDevResult(clazz, "getWearStatus") { "0,0" }
            hookDevResult(clazz, "getDeviceRunInfo") { 0 }
            hookDevResult(clazz, "isLeAudio") { false }
            // String-address 变体
            hookStrResult(clazz, "isMiTWS") { true }
                        // ANC 点击命令（MiLink 编码 == Pana 编码）
            hookAncCmd(clazz, "openAnc", 1)          // 降噪
            hookAncCmd(clazz, "closeAnc", 0)         // 关闭
            hookAncCmd(clazz, "openTransparent", 2)  // 通透
            PanaLog.i(TAG, "MxBt hooks installed on ${clazz.simpleName} ✓")
        }

                // AncBatteryController：属性 + setAncStateBlock 兜底点击处理
        val acc = findClass(ANC_BATTERY_CONTROLLER, classLoader)
        if (acc != null) {
            hookDevResult(acc, "getDeviceId") { HEADPHONES_DEVICE_ID }
            hookDevResult(acc, "getAncState") { getAncStateValue() }
            hookSetAncStateBlock(acc)
            // v99.4：融合中心列表电量来自 AncBatteryController.getBatteryLevelCache()。
            // Pana 不是小米耳机，ancBatteryModel 永远为 null，原生返回 [-1,-1,-1,0,0,0]。
            // 该方法首参 BluetoothDevice、返回 List，直接用 Bridge 值覆盖。
            hookDevResult(acc, "getBatteryLevelCache") { miLinkBatteryLevels() }
            PanaLog.i(TAG, "AncBatteryController hooks installed ✓")
        } else {
            PanaLog.w(TAG, "AncBatteryController not found!")
        }

        // v99.5：ThirdPartyHeadsetStrategy 是 Pana 这类第三方耳机真正的状态策略。
        // 融合中心列表的电量/ANC 最终来自该策略的 getBatteryLevelCache/getAncState，
        // 必须直接覆盖（AncBatteryController 只是其上层包装，部分路径不经过它）。
        val tps = findClass(THIRD_PARTY_STRATEGY, classLoader)
        if (tps != null) {
            hookDevResult(tps, "getBatteryLevelCache") { miLinkBatteryLevels() }
            hookDevResult(tps, "getBatteryCache") { miLinkBatteryLevels() }
            hookDevResult(tps, "getAncState") { getAncStateValue() }
            hookDevResult(tps, "getDeviceId") { HEADPHONES_DEVICE_ID }
            PanaLog.i(TAG, "ThirdPartyHeadsetStrategy hooks installed ✓")
        }

        // v99.5：ThirdPartyModel 是策略内部保存电量/ANC 的数据模型，无参 getter 被
        // assembleHeadsetInfo 直接读取。Pana 没有 SDK 回调填充，model 永远是空值，
        // 必须覆盖其 getBattery()/getAncState()。
        val tpm = findClass(THIRD_PARTY_MODEL, classLoader)
        if (tpm != null) {
            hookThirdPartyModel(tpm)
            PanaLog.i(TAG, "ThirdPartyModel hooks installed ✓")
        }

                // ProfileContext：systemui 卡片实际订阅的刷新通道（getHeadsetPropertyChangeListener），
                // 且卡片直接靠 ProfileContext.getAncState 决定高亮 —— 必须覆盖 getAncState 并从它发通知。
                // 注意：不要覆盖 ProfileContext 的 getDeviceId/getWearStatus/getBatteryLevel —— 这些参与
                // 设备类型识别与卡片布局，v57 保持其原始返回值时卡片能正常长出 ANC 控件；强行覆盖会
                // 导致卡片回退到“断开/更多设置”最简版（丢失降噪切换）。设备识别已由 MxBt 层负责。
        val pc = findClass(PROFILE_CONTEXT, classLoader)
        if (pc != null) {
            hookDevResult(pc, "getAncState") { getAncStateValue() }
            PanaLog.i(TAG, "ProfileContext.getAncState hook installed ✓")
                        // 诊断：记录活动设备（卡片主体）的 addr/name/alias/type，揪出“洛初”别名来源
            try {
                XposedBridge.hookAllMethods(pc, "getActiveDevice", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val dev = param.result as? BluetoothDevice
                        if (dev == null) {
                            if (loggedHooks.add("active|null")) PanaLog.i(TAG, "getActiveDevice -> null")
                            return
                        }
                        val a = try { dev.address } catch (_: Throwable) { null }
                        val nm = try { dev.name } catch (_: Throwable) { null }
                        val al = try { dev.alias } catch (_: Throwable) { null }
                        val ty = try { dev.type } catch (_: Throwable) { -1 }
                        if (loggedHooks.add("active|$a|$al")) {
                            PanaLog.i(TAG, "getActiveDevice -> addr=$a name=$nm alias=$al type=$ty")
                        }
                        // v99：LC3/LE-Audio 模式下活动设备是 LE/LC3 副地址，卡片应直接
                        // 使用该地址（而不是重定向回主地址）。经典模式下活动地址即主地址，
                        // 行为不变。观测到活动地址后清空隐藏缓存并补发一次刷新，让卡片
                        // 重建在正确的地址上，修复 ANC/音量控件时有时无。
                        if (a != null && isPanaAddress(a)) {
                            // v104：卡片每次被打开都会走到这里。先预热 Bridge 缓存，
                            // 让本次卡片构建的首帧就带上真实 ANC/电量，避免“只有音量”。
                            val refreshed = refreshBridgeFromProviderIfStale()
                            val prev = activePanaAddress
                            if (prev == null || !prev.equals(a, ignoreCase = true)) {
                                activePanaAddress = a
                                PanaLog.i(TAG, "active Pana address now $a (prev=$prev)")
                                nudgeAncCardRefresh(force = true)
                                // 活动卡片已确认：通知卡图 Hook 立即替换成 Pana 产品图，
                                // 不等下次卡图重试 tick / 卡片重建，消除“先系统图后 Pana 图”的延迟。
                                MiLinkCardArtHook.onPanaCardMaybeActive()
                            } else if (refreshed) {
                                // 地址未变但缓存刚被填满：强制补一次 nudge，让已渲染的
                                // 音量-only 卡片立刻重 assemble 出 ANC/电量。
                                nudgeAncCardRefresh(force = true)
                                MiLinkCardArtHook.onPanaCardMaybeActive()
                            } else {
                                // v112：卡片重新打开时 getActiveDevice 必经此处。
                                // 旧逻辑在地址未变且缓存未刷新时完全不 nudge，
                                // 降噪控件只能等系统 3s 广播周期 + 2s 节流，表现为“慢”。
                                // 改为总是尝试 nudge（受 2s 节流保护，不会风暴）。
                                nudgeAncCardRefresh(force = false)
                                MiLinkCardArtHook.onPanaCardMaybeActive()
                            }
                        } else if (a != null && activePanaAddress != null && !isPanaAddress(a) &&
                            !PanaBridge.isConnected() && !isSystemPanaConnected()
                        ) {
                            // v175：系统活动设备已切到非 Pana（用户连上其他耳机），且
                            // Bridge / 系统 profile 双双确认 Pana 已离线 → 立即释放。
                            // 否则 isPanaCardActive() 非空即真，会把那副耳机的卡片图
                            // 换成 Pana 产品图，并让 keepAlive 持续空转。
                            val prev = activePanaAddress
                            activePanaAddress = null
                            PanaLog.i(TAG, "active device now non-Pana ($a), cleared activePanaAddress (prev=$prev)")
                        }
                    }
                })
                PanaLog.i(TAG, "getActiveDevice hook installed ✓")
            } catch (e: Throwable) {
                PanaLog.w(TAG, "getActiveDevice hook failed: ${e.message}")
            }
        }

        // v114：hook listener 的注入时机。诊断证实：nudge 依赖的
        // headsetPropertyChangeListener 在各 milink 进程里是动态 set 的
        // （getter 大部分时间为 null，nudge 静默死亡）。
        // listener 被 set 的那一刻 = 数据链路就绪的时刻，缓存它并立即 nudge，
        // 让降噪控件在数据链路就绪的第一时间渲染，不再等 3s 广播周期。
        // v177：同时触发所有等待 listener 初始化的回调（首开/重连场景）。
        try {
            val pcClass = findClass(PROFILE_CONTEXT, classLoader)
            if (pcClass != null) {
                XposedBridge.hookAllMethods(pcClass, "setHeadsetPropertyChangeListener",
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            val listener = param.args.firstOrNull()
                            cachedHeadsetListener = listener
                            if (listener != null) {
                                PanaLog.i(TAG, "headset listener SET (${listener.javaClass.name}), nudging now")
                                nudgeAncCardRefresh(force = true)
                                MiLinkCardArtHook.onPanaCardMaybeActive()
                                // v177：触发所有等待 listener 的回调
                                listenerReadyCallbacks.forEach { it.run() }
                                listenerReadyCallbacks.clear()
                            } else {
                                PanaLog.i(TAG, "headset listener CLEARED")
                            }
                        }
                    })
                PanaLog.i(TAG, "setHeadsetPropertyChangeListener hook installed ✓")
            } else {
                PanaLog.w(TAG, "setHeadsetPropertyChangeListener: ProfileContext not found")
            }
        } catch (t: Throwable) {
            PanaLog.w(TAG, "setHeadsetPropertyChangeListener hook failed: ${t.message}")
        }

        // HeadsetInfo（com.miui.headset.api.HeadsetInfo）的无参 getter 是融合中心
        // 决定“降噪/音量区块”是否渲染的真正数据源（getDeviceId/getPowers/getSwitchState）。
        // 只 hook BluetoothDevice 变体不够，必须把这些无参方法也伪装掉，否则 ANC 控件时有时无。
        val hi = findClass(HEADSET_INFO, classLoader)
        if (hi != null) {
            hookHeadsetInfoNoArg(hi, "getDeviceId") { HEADPHONES_DEVICE_ID }
            hookHeadsetInfoNoArg(hi, "component3") { HEADPHONES_DEVICE_ID }
            hookHeadsetInfoNoArg(hi, "getPowers") { miLinkBatteryLevels() }
            hookHeadsetInfoNoArg(hi, "component4") { miLinkBatteryLevels() }
            hookHeadsetInfoNoArg(hi, "getSwitchState") { 1 }
            hookHeadsetInfoNoArg(hi, "component8") { 1 }
            PanaLog.i(TAG, "HeadsetInfo no-arg hooks installed ✓")

            // SystemUI 拿到的 HeadsetInfo 是 Parcelable 副本，getter 在 SystemUI 本地执行，
            // milink 进程里的 getter hook 覆盖不到。只能在构造时把字段直接写对。
            try {
                XposedBridge.hookAllConstructors(hi, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val info = param.thisObject ?: return
                        // v129：彻底取消任何"前置 skip"逻辑。判断只依靠 isTargetHeadsetInfo
                        // （地址 OUI/名字是否像 Pana）。任何被识别为 Pana 的 HeadsetInfo 都 patch。
                        // 经测试：LC3 模式下系统活动卡就是副地址 card，不 patch 它就不会有 Pana 控件。
                        if (!isTargetHeadsetInfo(info)) {
                            // v123：首帧 HeadsetInfo 的地址/名字可能尚未就绪（LC3 刚连上），
                            // isTargetHeadsetInfo 判 false → 不 patch → 卡片只有音量无降噪。
                            // 用 Bridge 连接状态兜底（先从 Provider 预热一次再判），
                            // 保证 Pana 卡首帧就带降噪字段。
                            // v175：兜底前先排除"明确的他设备" —— Pana 连接期间
                            // （Bridge 为 connected）别的小米耳机卡也会走到这，
                            // 旧逻辑直接 patch 会把 Pana 的 deviceId/powers/switchState
                            // 写进别人的卡片。身份未知（字段未就绪）才允许兜底。
                            if (isIdentifiedOtherHeadset(info)) {
                                PanaLog.i(TAG, "HeadsetInfo ctor skip: identified other headset")
                                return
                            }
                            if (!PanaBridge.isConnected()) {
                                if (!refreshBridgeFromProviderIfStale()) return
                                if (!PanaBridge.isConnected()) return
                            }
                            PanaLog.i(TAG, "HeadsetInfo ctor patched (bridge fallback, target check failed)")
                        }
                        trySetIntField(info, "switchState", 1)
                        trySetObjectField(info, "switchState", Integer.valueOf(1))
                        trySetObjectField(info, "deviceId", HEADPHONES_DEVICE_ID)
                        trySetObjectField(info, "powers", miLinkBatteryLevels())
                        PanaLog.i(TAG, "HeadsetInfo ctor patched")
                    }
                })
                PanaLog.i(TAG, "HeadsetInfo ctor hook installed ✓")
            } catch (t: Throwable) {
                PanaLog.w(TAG, "HeadsetInfo ctor hook failed: ${t.message}")
            }
        }

                // 融合中心设备列表阀门：isSupportEarphone 决定哪些已配对设备成为耳机卡片。
                // Pana 以经典/DUAL + 纯 LE 两个同名地址配对，LE 地址会被原生列成第二个
                // “设备可能不在附近”的卡片。对 LE 副地址返回 false，只保留经典/DUAL 那一张。
        val pck = findClass(PROFILE_CONTEXT_KT, classLoader)
        if (pck != null) {
            try {
                XposedBridge.hookAllMethods(pck, "isSupportEarphone", object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val dev = param.args.firstOrNull() as? BluetoothDevice ?: return
                        // v161：系统正在遍历 bondedDevices 时，同步刷新一次 Pana 地址缓存
                        //（枚举已配对设备，把名字含 Technics 的地址全部登记进 panaAddresses），
                        // 保证重启冷启动时第一次判断就是热的，不依赖延迟 prewarm。
                        try { doPrewarm() } catch (_: Throwable) {}

                        val hidden = isHiddenLePana(dev)
                        val a = try { dev.address } catch (_: Throwable) { null }
                        val al = try { dev.alias } catch (_: Throwable) { null }
                        if (a != null && loggedHooks.add("support|$a|$hidden")) {
                            PanaLog.i(TAG, "isSupportEarphone addr=$a alias=$al le=${isLeOnly(dev)} hidden=$hidden")
                        }
                        // v99：卡片应建立在融合中心真实活动设备的地址上（LC3 模式为副地址，
                        // 经典模式为主地址），隐藏另一张同名 Pana 卡片，避免出现两个耳机卡片。
                        if (hidden) {
                            PanaLog.i(TAG, "isSupportEarphone hide duplicate addr=$a")
                            param.result = false
                            param.returnEarly = true
                        }
                    }
                })
                PanaLog.i(TAG, "isSupportEarphone hook installed ✓")
            } catch (e: Throwable) {
                PanaLog.w(TAG, "isSupportEarphone hook failed: ${e.message}")
            }
        } else {
            // v123：诊断 —— 类找不到时 isSupportEarphone hook 静默失效（双卡隐藏不生效）
            PanaLog.w(TAG, "isSupportEarphone: ProfileContextKt not found! (list valve inactive)")
        }

                // 诊断 + 修正：打印融合中心实际生成的每张卡片（HeadsetDevice 构造），
                // 对 Pana 地址强制正确的显示名，防止 LE 副地址卡片退化为本机蓝牙名。
        val hd = findClass(HEADSET_DEVICE, classLoader)
        if (hd != null) {
            try {
                XposedBridge.hookAllConstructors(hd, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val addr = param.args.getOrNull(0) as? String ?: return
                                                // v85：跟踪构造深度，避免并发/递归导致的多次 firing
                        if (hdBuildDepth++ == 0) PanaLog.d(TAG, "HeadsetDevice ctor start depth=1")
                        else PanaLog.d(TAG, "HeadsetDevice ctor depth=${hdBuildDepth}")
                        val name = param.args.getOrNull(1) as? String
                                                // 对 Pana 地址：如果名字不是正确的 Pana 名称，强制修正
                        if (isPanaAddress(addr) && !PanaBridge.isPanaDevice(name)) {
                            param.args[1] = panaDisplayName()
                            PanaLog.i(TAG, "TILE fix name: addr=$addr \"$name\" -> \"${panaDisplayName()}\"")
                        }
                    }
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val addr = param.args.getOrNull(0) as? String
                        val name = param.args.getOrNull(1) as? String
                        val devId = param.args.getOrNull(2) as? String
                        hdBuildDepth--
                        if (hdBuildDepth == 0) PanaLog.d(TAG, "HeadsetDevice ctor done depth=0")
                        if (addr != null && loggedHooks.add("HD|$addr|$name")) {
                            PanaLog.i(TAG, "TILE addr=$addr name=$name devId=$devId")
                        }
                                                // v84：卡片建好后主动补发一次属性刷新，修复首建时降噪控件间歇缺失。
                                                // v85：仅在 depth 从 1→0 时触发（确保所有 TILE 都构造完），防止多卡并行构建时的时序竞争。
                                                // v86：对任意 Technics 地址（经典/LE）都触发 nudge，不依赖 isPanaAddress 判断。
                                                // v98：识别改用“名字或地址”双条件，并在 1s/3s 后各补发一次，
                                                //        覆盖卡片首建时缓存/Bridge 尚未就绪导致识别失败的竞态。
                        if (addr != null && hdBuildDepth == 0) {
                            val cardName = param.args.getOrNull(1) as? String
                            val isTechnics = try {
                                PanaBridge.isPanaDevice(cardName) || isPanaAddress(addr)
                            } catch (_: Throwable) { false }
                            if (isTechnics) {
                                // v104：建卡前预热 Bridge 缓存；若缓存刚被填满，本次
                                // 首帧 nudge 使用 force 绕过 2s 节流，尽快把 ANC/电量
                                // 渲染出来，消除“首帧只有音量”的闪烁。
                                val refreshed = refreshBridgeFromProviderIfStale()
                                nudgeAncCardRefresh(force = refreshed)
                                // v100：建卡后只补 2 发（2s/8s），覆盖 Cir_MDC 旧缓存推送窗口；
                                // 原 v99.6 的 1s~30s 十连发改为事件驱动（见 onBridgeStateUpdated）。
                                scheduleDelayedNudge(2000L)
                                scheduleDelayedNudge(8000L)
                            }
                        }
                    }
                })
                PanaLog.i(TAG, "HeadsetDevice ctor hook installed ✓")
            } catch (e: Throwable) {
                PanaLog.w(TAG, "HeadsetDevice ctor hook failed: ${e.message}")
            }
        }
        
                // 显示层修正：hook BluetoothDevice.getAlias()，对 Pana 地址返回正确设备名。
                // 融合中心卡片标题取自 activeHeadset.getAlias()，当 LE 副地址退化时会回退到本机蓝牙名。
                // 本 hook 仅在 milink 进程生效，不影响设备列表/连接/ANC 逻辑，纯显示层伪装。
        try {
            XposedBridge.hookAllMethods(BluetoothDevice::class.java, "getAlias", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val dev = param.thisObject as? BluetoothDevice ?: return
                    if (isPana(dev)) {
                        val orig = param.result as? String
                        if (orig == null || !PanaBridge.isPanaDevice(orig)) {
                            param.result = panaDisplayName()
                        }
                    }
                }
            })
            PanaLog.i(TAG, "BluetoothDevice.getAlias hook installed ✓")
        } catch (e: Throwable) {
            PanaLog.w(TAG, "getAlias hook failed: ${e.message}")
        }
    }

    /**
     * 需从融合中心隐藏的 Pana 副卡：卡片应建立在融合中心真实活动设备（ProfileContext.
     * getActiveDevice）的地址上——LC3/LE-Audio 模式下为 LE/LC3 副地址，经典模式下为主地址。
     * 对另一张同名 Pana 地址的卡片返回 true，避免出现两个耳机卡片。
     */
    private fun isHiddenLePana(device: BluetoothDevice): Boolean {
        val addr = try { device.address?.uppercase() } catch (_: Throwable) { null } ?: return false

        // v161：Pana 家族判定——非 Pana 设备一律不干预，
        // 否则下面的 active 分支会误隐藏用户其他耳机的卡片。
        val isPanaFamily = panaAddresses.contains(addr) ||
            PanaBridge.isCurrentDevice(addr) ||
            isPana(device)
        if (!isPanaFamily) return false

        // v161：以下判定全部幂等（每次重算，不写跨调用状态），
        // 避免 hiddenLeAddresses 残留导致两张卡都被隐藏（0 卡）。

        // 1) 活动地址已知 → 只放行活动地址（最精确）
        val active = activePanaAddress ?: getLeAudioActivePanaAddress()
        if (active != null) {
            val hide = !addr.equals(active, ignoreCase = true)
            if (loggedHooks.add("hideDup|$addr|$hide|active")) {
                PanaLog.i(TAG, "hideDup active=$active addr=$addr le=${isLeOnly(device)} hide=$hide")
            }
            return hide
        }

        // 2) Bridge 主地址优先
        val main = try { PanaBridge.getMacAddress()?.uppercase() } catch (_: Throwable) { null }
        if (main != null) {
            val hide = !addr.equals(main, ignoreCase = true)
            if (loggedHooks.add("hideDup|$addr|$hide|main")) {
                PanaLog.i(TAG, "hideDup main=$main addr=$addr hide=$hide")
            }
            return hide
        }

        // 3) Bridge LC3 地址
        val lc3 = try { PanaBridge.getLc3MacAddress()?.uppercase() } catch (_: Throwable) { null }
        if (lc3 != null) {
            val hide = !addr.equals(lc3, ignoreCase = true)
            if (loggedHooks.add("hideDup|$addr|$hide|lc3")) {
                PanaLog.i(TAG, "hideDup lc3=$lc3 addr=$addr hide=$hide")
            }
            return hide
        }

        // 4) 全部未知（重启冷启动最早期）→ first-wins + 过期窗口：
        //    10s 内只放行第一个 Pana 地址（覆盖同一次 updateHeadsetDevice 的
        //    bonded + connected 两个遍历循环），超窗后重新仲裁，避免状态长期残留。
        val now = System.currentTimeMillis()
        val allowed = lastAllowedPanaAddr
        if (allowed == null || now - lastAllowedPanaAt > 10_000L) {
            lastAllowedPanaAddr = addr
            lastAllowedPanaAt = now
            PanaLog.i(TAG, "hideDup first-wins allow $addr (le=${isLeOnly(device)})")
            return false
        }
        val hide = !addr.equals(allowed, ignoreCase = true)
        if (hide) PanaLog.i(TAG, "hideDup first-wins hide $addr (keep=$allowed)")
        return hide
    }

    /** v118：同窗去重状态 —— 最近放行的 Pana 卡片地址及其时间戳。 */
    @Volatile private var lastAllowedPanaAddr: String? = null
    @Volatile private var lastAllowedPanaAt = 0L

    private fun batteryOrDefault(): Int {
        val b = PanaBridge.getAverageBattery()
        return if (b in 0..100) b else 50
    }

    /** HeadsetInfo 无参 getter 伪装：只有该实例的地址是 Pana 时才覆盖。 */
    private fun hookHeadsetInfoNoArg(clazz: Class<*>, method: String, provider: () -> Any?) {
        try {
            XposedBridge.hookAllMethods(clazz, method, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val info = param.thisObject
                    val addr = try {
                        XposedHelpers.callMethod(info, "getAddress") as? String
                    } catch (_: Throwable) {
                        try { XposedHelpers.callMethod(info, "component1") as? String } catch (_: Throwable) { null }
                    }
                    val target = isTargetHeadsetInfo(info)
                    if (loggedHooks.add("HI|$method|$addr|$target")) {
                        PanaLog.i(TAG, "HeadsetInfo.$method called addr=$addr target=$target")
                    }
                    // v124：getter 层 Bridge 兜底 —— 首帧 HeadsetInfo 的地址/名字字段
                    // 可能尚未就绪（LC3 刚连上），isTargetHeadsetInfo 判 false 会漏 patch，
                    // 降噪区块因此晚一帧。只要 Bridge 报告已连接（= 正在构建的是 Pana 卡），
                    // 直接返回伪装值，保证降噪/电量字段首帧即正确。
                    if (!target) {
                        // v175：与 ctor 同规则 —— 身份已明确是他设备时不兜底，
                        // 否则 Pana 连接期间会把他的 getPowers/getDeviceId 覆盖掉。
                        if (isIdentifiedOtherHeadset(info)) {
                            PanaLog.i(TAG, "HeadsetInfo.$method skip: identified other headset addr=$addr")
                            return
                        }
                        if (!PanaBridge.isConnected()) {
                            // Bridge 空（进程冷启动首帧）：先从 Provider 预热一次再判
                            if (!refreshBridgeFromProviderIfStale()) return
                            if (!PanaBridge.isConnected()) return
                        }
                        PanaLog.i(TAG, "HeadsetInfo.$method bridge-fallback for addr=$addr")
                    }
                    param.result = provider()
                }
            })
            PanaLog.i(TAG, "HeadsetInfo.$method hooked ✓")
        } catch (t: Throwable) {
            PanaLog.w(TAG, "hook HeadsetInfo.$method failed: ${t.message}")
        }
    }

    /** v127b：最近一次 patch 的 Pana HeadsetInfo 地址 + 时间戳。 */
    @Volatile private var lastPatchedPanaAddr: String? = null
    @Volatile private var lastPatchedPanaAt = 0L

    private fun isTargetHeadsetInfo(info: Any?): Boolean {
        if (info == null) return false
        for (m in listOf("getAddress", "component1")) {
            val addr = try { XposedHelpers.callMethod(info, m) as? String } catch (_: Throwable) { null }
            if (addr != null && isPanaAddress(addr)) return true
        }
        // v104：SystemUI 侧的 HeadsetInfo Parcelable 副本有时取不到地址字段，
        // 用设备名兜底识别，保证 ctor 补丁在 SystemUI 也能命中（首帧即带 ANC/电量）。
        for (m in listOf("getDeviceName", "getName", "component2")) {
            val name = try { XposedHelpers.callMethod(info, m) as? String } catch (_: Throwable) { null }
            if (name != null && PanaBridge.isPanaDevice(name)) return true
        }
        return false
    }

    /**
     * v175：Bridge 兜底的身份前置校验 —— HeadsetInfo 已带明确"非 Pana"身份时
     * 返回 true（禁止兜底 patch），避免 Pana 连接期间把 Pana 的 deviceId/powers/
     * switchState 写进别的耳机卡片。判据从强到弱：
     * 1. 地址进负缓存（本进程登记过的明确他设备）→ 是他设备；
     * 2. 地址在 Pana 正缓存/当前设备集合 → 不是他设备；
     * 3. 设备名非空且不含 Technics/Panasonic 关键词 → 是他设备。
     * 地址"未登记"本身不作否定证据（LC3 首帧副地址常未登记，正是 v123/v124
     * 兜底要救的场景）；名字、地址都取不到 → 身份未知，返回 false 放行兜底。
     */
    private fun isIdentifiedOtherHeadset(info: Any?): Boolean {
        if (info == null) return false
        val addr = headsetText(info, listOf("getAddress", "component1"))
        if (addr != null) {
            if (PanaBridge.isNonPanaByAddress(addr)) return true
            if (isPanaAddress(addr) || PanaBridge.isCurrentDevice(addr)) return false
        }
        val name = headsetText(info, listOf("getDeviceName", "getName", "component2"))
        if (name != null && !PanaBridge.isPanaDevice(name)) return true
        return false
    }

    /** v175：按候选 getter 顺序读字符串字段，跳过 null/空白。 */
    private fun headsetText(info: Any, methods: List<String>): String? {
        for (m in methods) {
            val v = try { XposedHelpers.callMethod(info, m) as? String } catch (_: Throwable) { null }
            if (!v.isNullOrBlank()) return v
        }
        return null
    }

    private fun trySetIntField(obj: Any, name: String, value: Int) {
        try {
            val f = obj.javaClass.getDeclaredField(name)
            f.isAccessible = true
            if (f.type == Integer.TYPE || f.type == Int::class.java) {
                f.setInt(obj, value)
            } else {
                f.set(obj, value)
            }
            PanaLog.i(TAG, "HeadsetInfo setIntField $name=$value ok")
        } catch (_: Throwable) {}
    }

    private fun trySetObjectField(obj: Any, name: String, value: Any) {
        try {
            XposedHelpers.setObjectField(obj, name, value)
            PanaLog.i(TAG, "HeadsetInfo setObjectField $name ok")
        } catch (_: Throwable) {}
    }

    /** MiLink 电量槽位：[盒, 左, 右, 盒充电, 左充电, 右充电]，未知为 -1。 */
    private fun miLinkBatteryLevels(): List<Int> {
        val box = PanaBridge.getCradleBattery().takeIf { it in 0..100 } ?: -1
        val left = PanaBridge.getLeftBattery().takeIf { it in 0..100 } ?: -1
        val right = PanaBridge.getRightBattery().takeIf { it in 0..100 } ?: -1
        // v165：取消 v126 的“单耳对称填充”。单耳使用时另一耳不在位，App 已正确上报 -1，
        // 之前用另一耳的值对称填充会让面板把已连耳的电量复制到两只耳（用户反馈
        // “只连一只耳却显示双耳同值”）。这里直接透传 -1 —— MiLink 的
        // MLHeadsetBatteryView.g() 对 -1 渲染为 "-"，正好表达“无数据 / 未连接”。
        // （与 HyperOSHeadsetHook.formatBattery() 输出 255 断开哨兵的做法一致。）
        val direct = listOf(box, left, right, 0, 0, 0)
        if (box >= 0 || left >= 0 || right >= 0) return direct
        // v101：Bridge 缓存为空（广播丢失/进程刚启动）时，用带 TTL 的 Provider 查询兜底，
        // 避免电量长时间显示缺失；桥接缓存有值时 Provider 不再参与，防止高频 IPC。
        val now = System.currentTimeMillis()
        val cached = lastProviderBattery
        if (cached != null && now - lastProviderBatteryAt < 3000L) return cached
        runCatching {
            val ctx = XposedHelpers.callStaticMethod(
                Class.forName("android.app.ActivityThread"), "currentApplication"
            ) as? Context ?: return direct
            val cursor = ctx.contentResolver.query(
                PanaPodsProvider.CONTENT_URI, null, null, null, null
            )
            cursor?.use {
                if (it.moveToFirst()) {
                    val boxP = it.getInt(it.getColumnIndexOrThrow(PanaPodsProvider.COLUMN_CRADLE))
                        .takeIf { v -> v in 0..100 } ?: -1
                    val leftP = it.getInt(it.getColumnIndexOrThrow(PanaPodsProvider.COLUMN_LEFT))
                        .takeIf { v -> v in 0..100 } ?: -1
                    val rightP = it.getInt(it.getColumnIndexOrThrow(PanaPodsProvider.COLUMN_RIGHT))
                        .takeIf { v -> v in 0..100 } ?: -1
                    // v165：与 Bridge 路径一致 —— 缺失侧透传 -1（面板渲染为 "-"），不再对称填充。
                    val result = listOf(boxP, leftP, rightP, 0, 0, 0)
                    lastProviderBattery = result
                    lastProviderBatteryAt = now
                    return result
                }
            }
        }.onFailure { e ->
            PanaLog.w(TAG, "miLinkBatteryLevels provider query failed: ${e.message}")
        }
        return direct
    }

    /**
     * v104：卡片打开前预热 Bridge 缓存。
     *
     * LC3 模式下融合中心有时首帧只有音量（降噪/电量随后才出现）的根因是：
     * 卡片构建瞬间 Bridge 缓存尚未就绪（广播丢失/进程冷启动），HeadsetInfo
     * 首帧拿到的是 [-1,-1,-1] 与 switchState=0。这里在 getActiveDevice /
     * HeadsetDevice 构建时同步查一次 Provider 填满缓存，让首帧即正确。
     *
     * @return true 表示本次确实刷新了 Bridge 缓存（调用方可据此补发强制 nudge）
     */
    // v110：改为 public —— 卡图 Hook 所在的渲染进程可能没注册上 Bridge receiver
    // （currentApplication 时序竞争），需要自行从 Provider 拉连接状态。
    fun refreshBridgeFromProviderIfStale(): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastProviderFullRefreshAt < 3000L) return false

        val hasBattery = PanaBridge.getLeftBattery() in 0..100 ||
            PanaBridge.getRightBattery() in 0..100 ||
            PanaBridge.getCradleBattery() in 0..100
        val hasAnc = AncMode.isValid(PanaBridge.getAncMode())
        // 已连接且电量/ANC 都已知时无需预热。
        if (hasBattery && hasAnc) return false

        // v175：进入 IPC 前先打节流点 —— 旧逻辑只在"写入成功/数据不完整"两处
        // 打点，Provider 空、断开不写、查询异常等失败路径不打点，而 ctor/getter
        // 等多个调用点会立刻重试全量跨进程查询，失败态等于无节流的高频 Binder IPC。
        lastProviderFullRefreshAt = now

        return runCatching {
            val ctx = XposedHelpers.callStaticMethod(
                Class.forName("android.app.ActivityThread"), "currentApplication"
            ) as? Context ?: return false
            val cursor = ctx.contentResolver.query(
                PanaPodsProvider.CONTENT_URI, null, null, null, null
            )
            cursor?.use {
                if (it.moveToFirst()) {
                    val left = it.getInt(it.getColumnIndexOrThrow(PanaPodsProvider.COLUMN_LEFT))
                    val right = it.getInt(it.getColumnIndexOrThrow(PanaPodsProvider.COLUMN_RIGHT))
                    val cradle = it.getInt(it.getColumnIndexOrThrow(PanaPodsProvider.COLUMN_CRADLE))
                    val anc = it.getInt(it.getColumnIndexOrThrow(PanaPodsProvider.COLUMN_ANC))
                    val connected = it.getInt(it.getColumnIndexOrThrow(PanaPodsProvider.COLUMN_CONNECTED)) != 0
                    val name = it.getString(it.getColumnIndexOrThrow(PanaPodsProvider.COLUMN_NAME))
                    val addr = it.getString(it.getColumnIndexOrThrow(PanaPodsProvider.COLUMN_ADDRESS))

                    // v122：仅在已连接时才写入 Bridge。Provider 在断开/状态切换瞬间可能
                    // 是半成品数据（缺某只耳/anc=-1），写入后 refreshBridge 因"已有部分
                    // 电量"而不再刷新 → 电量永久显示不全。断开态不写，交给广播填充完整值。
                    if (!connected) return false
                    // v122：完整数据守卫 —— 三只电量缺一不写。刚连接瞬间 Provider 可能
                    // 只有部分电量（如右耳未读回），写入会固化成残缺电量。
                    if (left !in 0..100 || right !in 0..100 || cradle !in 0..100) {
                        PanaLog.i(TAG, "provider state incomplete (L=$left R=$right C=$cradle), skip write")
                        lastProviderFullRefreshAt = now  // 节流重试，避免高频 IPC
                        return false
                    }

                    PanaBridge.publishStateToCache(left, right, cradle, anc, name, addr, connected)
                    lastProviderFullRefreshAt = now
                    lastProviderBattery = listOf(
                        cradle.takeIf { it in 0..100 } ?: -1,
                        left.takeIf { it in 0..100 } ?: -1,
                        right.takeIf { it in 0..100 } ?: -1,
                        0, 0, 0
                    )
                    lastProviderBatteryAt = now
                    if (AncMode.isValid(anc)) {
                        lastProviderAnc = anc
                        lastProviderQueryAt = now
                    }
                    PanaLog.i(TAG, "card-state prewarmed from Provider: L=$left R=$right C=$cradle anc=$anc connected=$connected")
                    return true
                }
            }
            false
        }.onFailure { e ->
            PanaLog.w(TAG, "refreshBridgeFromProviderIfStale failed: ${e.message}")
        }.getOrDefault(false)
    }

    // Provider 电量查询缓存（3s TTL）：仅在 Bridge 缓存为空时参与，避免每帧跨进程查询。
    @Volatile private var lastProviderBattery: List<Int>? = null
    @Volatile private var lastProviderBatteryAt: Long = 0L

    // 最近一次从 Provider 全量预热 Bridge 缓存的时间戳。卡片每次被打开
    // （getActiveDevice / HeadsetDevice 构建）都会尝试预热，3s 节流避免高频 IPC。
    @Volatile private var lastProviderFullRefreshAt: Long = 0L

    /** hook 首参为 BluetoothDevice 的方法：命中 Pana 时用 provider 覆盖返回值并跳过原方法 */
    private fun hookDevResult(clazz: Class<*>, method: String, provider: () -> Any?) {
        try {
            XposedBridge.hookAllMethods(clazz, method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val dev = param.args.firstOrNull() as? BluetoothDevice ?: return
                    val match = isPana(dev)
                    val a = try { dev.address } catch (_: Throwable) { null }
                    if (a != null && loggedHooks.add("$method|$a|$match")) {
                        PanaLog.i(TAG, "HOOK $method addr=$a le=${isLeOnly(dev)} faked=$match")
                    }
                    if (!match) return
                    captureAncInstance(param.thisObject)
                    param.result = provider()
                    param.returnEarly = true
                }
            })
        } catch (t: Throwable) {
            PanaLog.w(TAG, "hookDevResult $method on ${clazz.simpleName} failed: ${t.message}")
        }
    }

    /** hook 首参为 String 地址的方法 */
    private fun hookStrResult(clazz: Class<*>, method: String, provider: () -> Any?) {
        try {
            XposedBridge.hookAllMethods(clazz, method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val addr = param.args.firstOrNull() as? String ?: return
                    val match = isPanaAddress(addr)
                    if (loggedHooks.add("$method|$addr")) {
                        PanaLog.i(TAG, "HOOK(str) $method addr=$addr faked=$match")
                    }
                    if (!match) return
                    param.result = provider()
                    param.returnEarly = true
                }
            })
        } catch (t: Throwable) {
            PanaLog.w(TAG, "hookStrResult $method on ${clazz.simpleName} failed: ${t.message}")
        }
    }

    /** ANC 命令（openAnc/closeAnc/openTransparent），首参 BluetoothDevice */
    private fun hookAncCmd(clazz: Class<*>, method: String, panaMode: Int) {
        try {
            XposedBridge.hookAllMethods(clazz, method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val dev = param.args.firstOrNull() as? BluetoothDevice ?: return
                    if (!isPana(dev)) return
                    PanaLog.d(TAG, "$method →Pana mode=$panaMode (control-center)")
                    captureAncInstance(param.thisObject)
                    onAncClicked(panaMode)
                    sendAncModeToApp(panaMode)
                                        // 异步发送通知，避免在 Hook 中同步执行耗时操作导致卡顿
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        notifyAncChanged(param.thisObject, dev)
                    }
                    param.result = panaMode
                    param.returnEarly = true
                }
            })
            PanaLog.i(TAG, "$method hooked on ${clazz.simpleName} ✓")
        } catch (_: Throwable) {}
    }

    private fun hookSetAncStateBlock(clazz: Class<*>) {
        try {
            XposedBridge.hookAllMethods(clazz, "setAncStateBlock", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val dev = param.args.firstOrNull() as? BluetoothDevice ?: return
                    if (!isPana(dev)) return
                    val mode = param.args.getOrNull(1) as? Int ?: return
                    PanaLog.d(TAG, "setAncStateBlock →Pana mode=$mode")
                    captureAncInstance(param.thisObject)
                    onAncClicked(mode)
                    sendAncModeToApp(mode)
                                        // 异步发送通知，避免卡顿
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        notifyAncChanged(param.thisObject, dev)
                    }
                    param.result = mode
                    param.returnEarly = true
                }
            })
            PanaLog.i(TAG, "setAncStateBlock hooked ✓")
        } catch (_: Throwable) {}
    }

    /** v99.5：ThirdPartyModel 无参 getter 覆盖。仅对当前活动 Pana 设备的 model 生效。 */
    private fun hookThirdPartyModel(clazz: Class<*>) {
        try {
            XposedBridge.hookAllMethods(clazz, "getBattery", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val dev = try {
                        XposedHelpers.callMethod(param.thisObject, "getBluetoothDevice") as? BluetoothDevice
                    } catch (_: Throwable) { null } ?: return
                    if (!isPana(dev)) return
                    param.result = miLinkBatteryLevels()
                }
            })
            XposedBridge.hookAllMethods(clazz, "getAncState", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val dev = try {
                        XposedHelpers.callMethod(param.thisObject, "getBluetoothDevice") as? BluetoothDevice
                    } catch (_: Throwable) { null } ?: return
                    if (!isPana(dev)) return
                    param.result = getAncStateValue()
                }
            })
        } catch (t: Throwable) {
            PanaLog.w(TAG, "hook ThirdPartyModel failed: ${t.message}")
        }
    }

    /** ANC 切换后刷新卡片：SonyPods 一并推送 type=8(ANC) 与 type=4(电量) 两种属性变更 */
    private fun notifyAncChanged(instance: Any?, device: BluetoothDevice) {
        notifyHeadsetPropertyChanged(instance, device, 8) // UPDATE_TYPE_ANC
        notifyHeadsetPropertyChanged(instance, device, 4) // UPDATE_TYPE_BATTERY
        // v102：点击触发的刷新之后，1.2s 再补发一次强制 nudge，覆盖 Cir_MDC 旧缓存推送窗口，
        // 修复快速连续点击时偶尔出现一两次卡片丢失降噪/电量的情况（调音量能恢复即该竞态）。
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            nudgeAncCardRefresh(force = true)
        }, 1200L)
    }

    /**
          * 触发卡片刷新指定属性（type=8 ANC / 4 电量）。向传入实例与所有缓存的 runtime 实例
          * （尤其 ProfileContext —— systemui 卡片订阅的监听器持有者）广播，确保通知送达卡片。
     * 
          * v95 优化：添加节流（50ms），防止快速点击时重复调用。
     */
    private fun notifyHeadsetPropertyChanged(instance: Any?, device: BluetoothDevice, type: Int) {
                // 节流 50ms - 快速点击时不重复发送
        val now = System.currentTimeMillis()
        val key = "notify_$type"
        val lastNotifyAt = lastNotifyMap.getOrDefault(key, 0L)
        if (now - lastNotifyAt < 50L) {
            PanaLog.d(TAG, "notifyHeadsetPropertyChanged throttled (type=$type)")
            return
        }
        lastNotifyMap[key] = now
        
        val targets = LinkedHashSet<Any>()
        instance?.let { targets.add(it) }
        targets.addAll(runtimeInstances)
        // v102：统一投递到 milink 的 SYSTEM_BROADCAST_HANDLER 串行队列（与 nudge 同线程），
        // 避免主线程直接 invoke 与 discovery 线程重装配并发竞争 —— 这正是快速点击时
        // 偶尔出现卡片丢失降噪/电量、调音量又能恢复的根因。
        val handler = resolveSystemBroadcastHandler()
        for (target in targets) {
            val fire = Runnable {
                try {
                    val listener = XposedHelpers.getObjectField(target, "headsetPropertyChangeListener")
                        ?: return@Runnable
                    XposedHelpers.callMethod(listener, "invoke", device, type)
                    PanaLog.d(TAG, "notify(type=$type) via ${target.javaClass.simpleName} ✓")
                } catch (_: Throwable) {}
            }
            if (handler != null) handler.post(fire) else fire.run()
        }
    }

    /** 解析 milink DiscoveryKt 的 SYSTEM_BROADCAST_HANDLER（属性刷新通知的串行队列）。 */
    private fun resolveSystemBroadcastHandler(): android.os.Handler? {
        val cl = hookClassLoader ?: return null
        return try {
            findClass("com.miui.headset.runtime.DiscoveryKt", cl)?.let {
                XposedHelpers.callStaticMethod(it, "getSYSTEM_BROADCAST_HANDLER")
            } as? android.os.Handler
        } catch (_: Throwable) { null }
    }

    /** getAncState 返回值：v94 增强 - 优先使用 v93 Bridge 中的本地预判
     *
     * 流程优先级：
          * 1. Bridge.getCurrentAncMode() - v93 本地预判（用户点击后 5 秒内信任）
     * 2. Hook 侧 currentAnc - 上一次点击缓存
          * 3. Bridge.getAncMode() - BLE 实时状态
          * 4. 默认 0（关闭）
     *
          * v94 改进：充分利用 v93 的三层缓存和本地预判机制，减少 Hook 侧本地化状态维护
     */
    private fun getAncStateValue(): Int {
                // v94: 优先查询 Bridge 层的本地预判（v93 新增）
        val bridgeLocal = PanaBridge.getCurrentAncMode()
        if (AncMode.isValid(bridgeLocal)) {
            PanaLog.d(TAG, "getAncState: using Bridge local=$bridgeLocal")
            return bridgeLocal
        }
        
                // 退回到 Hook 侧本地缓存（5秒内乐观模式）
        val now = System.currentTimeMillis()
        if (AncMode.isValid(currentAnc) && now - lastAncClickAt < 5000L) {
            PanaLog.d(TAG, "getAncState: using Hook local=$currentAnc")
            return currentAnc
        }
        
                // 查询 Bridge 层的真实 BLE 状态
        val bridge = PanaBridge.getAncMode()
        if (AncMode.isValid(bridge)) {
            currentAnc = bridge
            PanaLog.d(TAG, "getAncState: updated from Bridge real=$bridge")
            return currentAnc
        }

        // Provider query cache: the control center can call getAncState dozens of
        // times per second; avoid hammering ContentResolver IPC when the previous
        // query was recent.
        if (AncMode.isValid(lastProviderAnc) && now - lastProviderQueryAt < 3000L) {
            currentAnc = lastProviderAnc
            PanaLog.d(TAG, "getAncState: using cached Provider=$lastProviderAnc")
            return currentAnc
        }
        
                // v95.2 新增：Bridge缓存不可靠（广播失效），直接从 ContentProvider 查询
        runCatching {
            val ctx = XposedHelpers.callStaticMethod(
                Class.forName("android.app.ActivityThread"),
                "currentApplication"
            ) as? Context
            
            if (ctx != null) {
                val cursor = ctx.contentResolver.query(
                    PanaPodsProvider.CONTENT_URI,
                    null, null, null, null
                )
                cursor?.use {
                    if (it.moveToFirst()) {
                        val anc = it.getInt(it.getColumnIndexOrThrow(PanaPodsProvider.COLUMN_ANC))
                        if (AncMode.isValid(anc)) {
                            currentAnc = anc
                            lastProviderAnc = anc
                            lastProviderQueryAt = System.currentTimeMillis()
                            PanaLog.d(TAG, "getAncState: queried from ContentProvider=$anc")
                            return anc
                        }
                    }
                }
            }
        }.onFailure { e ->
            PanaLog.w(TAG, "getAncState: ContentProvider query failed: ${e.message}")
        }
        
                // 尚未知：向 App 发出 sync 命令，让它查询耳机并推送广播
        PanaLog.d(TAG, "getAncState: unknown, triggering sync")
        triggerSyncAnc()
        return 0
    }

    // Provider query result cache (3s TTL) to throttle cross-process queries.
    @Volatile private var lastProviderAnc: Int = -1
    @Volatile private var lastProviderQueryAt: Long = 0L

    @Volatile private var lastSyncAt = 0L
    private fun triggerSyncAnc() {
        val now = System.currentTimeMillis()
        if (now - lastSyncAt < 3000L) return
        lastSyncAt = now
        runCatching {
            val ctx = XposedHelpers.callStaticMethod(
                Class.forName("android.app.ActivityThread"),
                "currentApplication"
            ) as? Context ?: return
            val intent = android.content.Intent(PanaBridge.ACTION_COMMAND).apply {
                setClassName(PanaBridge.PACKAGE_NAME, PanaBridge.COMMAND_RECEIVER_CLASS)
                putExtra(PanaBridge.EXTRA_COMMAND, PanaBridge.COMMAND_SYNC_ANC_MODE)
                // v175：PanaCommandReceiver.onReceive 会先做 isAuthorizedCommand 校验，
                // 漏带 token 时该命令被直接 Rejected → ANC 未知时的同步恢复路径 100% 失效
                // （控制中心永远显示"关闭"）。与 sendAncModeToApp 的兜底广播保持一致。
                putExtra(PanaBridge.EXTRA_COMMAND_TOKEN, PanaBridge.COMMAND_TOKEN)
                flags = 0x01000000
            }
            ctx.sendBroadcast(intent)
            PanaLog.d(TAG, "triggerSyncAnc: broadcast sent")
        }.onFailure { e -> PanaLog.w(TAG, "triggerSyncAnc failed: ${e.message}") }
    }

    /** 卡片点击 ANC：立即记录本地模式，使高亮即时跳转 */
    private fun onAncClicked(mode: Int) {
        currentAnc = mode
        lastAncClickAt = System.currentTimeMillis()
    }

    /**
          * 卡片建好后主动补发一次 HeadsetModeChanged(type=8) 属性变更通知，模拟用户调音量
          * (HeadsetVolumeChanged) 触发的刷新：DiscoveryImpl.onHeadsetPropertyChanged 会重新
          * assembleHeadsetInfo 并 notifyHeadsetInfoUpdate 推给 SystemUI 卡片，使降噪控件在首建时
          * 就渲染出来，修复间歇缺失（实测所有 hook 返回值在缺失/正常两种情况下完全一致，问题在
          * SystemUI 侧首建未带 ANC 信息，需一次属性通知触发重 assemble）。
     *
          * 关键约束：
          * - 通过 milink 自己的 SYSTEM_BROADCAST_HANDLER 投递，匹配 notifyPropertyChanged 的线程，
          *   避免在错误线程触碰 activeHeadset/UI 回调导致崩溃；handlerEx.dispatchMessage 亦吞异常。
          * - 直接同步 post 而非 delay，让卡片在 ctor 完成瞬间就收到通知重 assemble，避免 build→render
          *   时序竞争（build 期间不 schedule nudge，等所有 TILE 都构造完毕后 post，post 是同一线程的
          *   Runnable 队列，不会阻塞 ctor 本身）。
          * - v87：仅检测回环：用 nudgeInFlight 标记正在 invoke，避免递归回调。
          *   避免递归回调。
     */
    private fun nudgeAncCardRefresh(force: Boolean = false) {
        // 节流：短时间内的重复 nudge 只执行一次，避免卡片反复重 assemble 闪烁。
        // force=true 用于活动地址变化等关键状态切换，绕过节流立即重建。
        // v100：节流从 800ms 放宽到 2000ms，进一步降低空闲时的重建频率。
        val now = System.currentTimeMillis()
        if (!force && now - lastNudgeAt < 2000L) {
            PanaLog.d(TAG, "nudge throttled (${now - lastNudgeAt}ms since last)")
            return
        }
        lastNudgeAt = now
                // v87：如果已经有 nudge 正在执行，不再重入（assembleHeadsetInfo 内部可能再构造 ctor 回来）
        if (nudgeInFlight) {
            PanaLog.d(TAG, "nudge in-flight, skip duplicate call")
            return
        }
        val cl = hookClassLoader ?: run { PanaLog.w(TAG, "nudge: hookClassLoader null"); return }
        try {
            val pcClass = findClass(PROFILE_CONTEXT, cl) ?: run { PanaLog.w(TAG, "nudge: ProfileContext class not found"); return }
            // v114：优先用 setHeadsetPropertyChangeListener 注入时缓存的 listener。
            // getter 依赖 runtime 懒初始化，各进程大部分时间为 null（nudge 静默死亡的主因）。
            val listener = cachedHeadsetListener
                ?: XposedHelpers.callMethod(XposedHelpers.getStaticObjectField(pcClass, "INSTANCE"), "getHeadsetPropertyChangeListener")
            if (listener == null) {
                // v177：listener 尚未初始化，注册一次性回调，初始化后立即补发（force=true 绕过节流）。
                val callback = Runnable { nudgeAncCardRefresh(force = true) }
                listenerReadyCallbacks.add(callback)
                PanaLog.d(TAG, "nudge: listener null, registered callback (queue=${listenerReadyCallbacks.size})")
                return
            }
            val addr = activePanaAddress
                ?: getLeAudioActivePanaAddress()
                ?: PanaBridge.getMacAddress()
                ?: panaAddresses.firstOrNull { !it.isNullOrBlank() }
                ?: run { PanaLog.w(TAG, "nudge: no pana addr available"); return }
            val adapter = BluetoothAdapter.getDefaultAdapter() ?: run { PanaLog.w(TAG, "nudge: adapter null"); return }
            val device = try { adapter.getRemoteDevice(addr) } catch (_: Throwable) { run { PanaLog.w(TAG, "nudge: getRemoteDevice($addr) failed"); return } }
            val handler = resolveSystemBroadcastHandler() ?: run { PanaLog.w(TAG, "nudge: broadcast handler null"); return }
            val fire = Runnable {
                nudgeInFlight = true
                try {
                    PanaLog.d(TAG, "nudge ANC refresh firing addr=$addr type=8+4")
                                        // HeadsetModeChanged=8：让卡片重查 ANC 模式并渲染降噪控件；
                                        // BatteryChanged=4：同时刷新电量区块（v99.3）。
                    XposedHelpers.callMethod(listener, "invoke", device, Integer.valueOf(8))
                    XposedHelpers.callMethod(listener, "invoke", device, Integer.valueOf(4))
                    // v100：不再每次都 wakeUpAppBleService()。卡片重 assemble 时若 ANC 未知，
                    // getAncStateValue 会自行 triggerSyncAnc（3s 节流），无需每个 nudge 都拉 app。
                } catch (t: Throwable) {
                    PanaLog.w(TAG, "nudge invoke failed: ${t.message}")
                } finally {
                    nudgeInFlight = false
                }
            }
            handler.post(fire)
            PanaLog.i(TAG, "nudge ANC refresh posted addr=$addr")
        } catch (t: Throwable) {
            PanaLog.w(TAG, "nudgeAncCardRefresh failed: ${t.message}")
        }
    }

    /** 延迟补发 nudge：覆盖卡片首建时 Bridge/缓存尚未就绪的竞态。 */
    private fun scheduleDelayedNudge(delayMs: Long) {
        try {
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                // v99.2：不再用 isPanaConnected() 卡门——app 刚被安装重启时 Bridge 状态
                // 尚未广播回来，但 panaAddresses/activePanaAddress 已有数据，nudge 内部
                // 自带地址回退，直接调用即可。
                nudgeAncCardRefresh()
            }, delayMs)
        } catch (_: Throwable) {}
    }

    /**
     * v100：事件驱动刷新监听器。
     * app 的 BLE 服务在电量/ANC/连接状态真正变化时广播 ACTION_STATE_UPDATED，
     * milink 进程收到后只在状态签名变化时补发一次（+1.5s 跟一发覆盖 Cir_MDC 旧缓存），
     * 替代 v99.6 的十连发定时 nudge，省掉大量空闲期重建与 app 唤醒。
     */
    private fun registerBridgeStateRefreshListener(ctx: Context?) {
        if (bridgeStateRefreshRegistered || ctx == null) return
        bridgeStateRefreshRegistered = true
        try {
            val filter = android.content.IntentFilter(PanaBridge.ACTION_STATE_UPDATED)
            ctx.registerReceiver(object : android.content.BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: android.content.Intent?) {
                    onBridgeStateUpdated(intent)
                }
            }, filter, Context.RECEIVER_EXPORTED)
            PanaLog.i(TAG, "Bridge state refresh listener registered")
        } catch (e: Throwable) {
            PanaLog.w(TAG, "registerBridgeStateRefreshListener failed: ${e.message}")
        }
    }

    /**
     * v110：延迟注册 Bridge 广播接收器。
     * onPackageLoaded 时 Application 可能尚未创建（currentApplication()==null），
     * 直接注册会静默失败。此函数在主线程轮询重试，直到拿到 Context 为止（最多 10 次）。
     */
    private fun registerBridgeReceiverWhenReady() {
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        var attempts = 0
        val task = object : Runnable {
            override fun run() {
                val ctx = try {
                    XposedHelpers.callStaticMethod(
                        Class.forName("android.app.ActivityThread"), "currentApplication"
                    ) as? Context
                } catch (_: Throwable) { null }
                if (ctx == null) {
                    if (attempts < 10) {
                        attempts++
                        handler.postDelayed(this, 1000L * attempts)
                    } else {
                        PanaLog.w(TAG, "bridge receiver registration gave up after $attempts attempts")
                    }
                    return
                }
                try {
                    PanaBridge.registerStateReceiver(ctx)
                    // v113：注册卡片渲染信号接收器 —— :ui 渲染卡片时广播 CARD_ASSEMBLED，
                    // :core 收到后触发本地 nudge（listener 在 :core 可用），驱动 :ui 卡片
                    // 重 assemble 出降噪控件，消除"点开卡片后降噪按钮等 3 秒"。
                    ctx.registerReceiver(object : android.content.BroadcastReceiver() {
                        override fun onReceive(context: Context?, intent: android.content.Intent?) {
                            if (intent?.getStringExtra(PanaBridge.EXTRA_STATE_TOKEN) != PanaBridge.STATE_TOKEN) return
                            PanaLog.d(TAG, "CARD_ASSEMBLED received, nudging ANC refresh (force)")
                            // v115：force —— 发送侧（:ui）已节流 2s，这里立即执行，
                            // 不被 keepalive 占用的节流窗口挡住。
                            nudgeAncCardRefresh(force = true)
                            // v117：启动本进程的密集刷新窗口（500ms × 3s），
                            // 覆盖广播延迟/首帧数据未就绪的间隙。
                            startCardActiveWindow()
                        }
                    }, android.content.IntentFilter(PanaBridge.ACTION_CARD_ASSEMBLED),
                        Context.RECEIVER_EXPORTED)
                    // v100：注册事件驱动刷新监听 —— app 状态广播到达时按需补发 nudge，
                    // 替代大量定时 nudge，降低 milink/SystemUI 空闲时的重 assemble 开销。
                    registerBridgeStateRefreshListener(ctx)
                    PanaLog.i(TAG, "bridge receiver registered (deferred, attempts=$attempts)")
                } catch (t: Throwable) {
                    PanaLog.w(TAG, "deferred bridge receiver registration failed: ${t.message}")
                }
            }
        }
        task.run()
    }

    private fun onBridgeStateUpdated(intent: android.content.Intent?) {
        intent ?: return
        // 只接受 App 正式广播（带 state token），拒绝第三方伪造。
        if (intent.getStringExtra(PanaBridge.EXTRA_STATE_TOKEN) != PanaBridge.STATE_TOKEN) {
            PanaLog.w(TAG, "onBridgeStateUpdated rejected broadcast without valid token")
            return
        }
        val left = intent.getIntExtra(PanaBridge.EXTRA_LEFT_BATTERY, -1)
        val right = intent.getIntExtra(PanaBridge.EXTRA_RIGHT_BATTERY, -1)
        val cradle = intent.getIntExtra(PanaBridge.EXTRA_CRADLE_BATTERY, -1)
        val anc = intent.getIntExtra(PanaBridge.EXTRA_ANC_MODE, -1)
        val connected = intent.getBooleanExtra(PanaBridge.EXTRA_IS_CONNECTED, false)
        val addr = intent.getStringExtra(PanaBridge.EXTRA_MAC_ADDRESS)
        val signature = "$addr|$left|$right|$cradle|$anc|$connected"

        val prev = lastBridgeSignature
        lastBridgeSignature = signature
        // 状态没变化不刷；断开态不主动刷（等系统事件/重连）
        if (prev == signature) return
        if (!connected) return

        val now = System.currentTimeMillis()
        if (now - lastStateNudgeAt < STATE_NUDGE_MIN_INTERVAL_MS) {
            // v110：诊断期直连 Log（不受日志开关影响）
            PanaLog.d(TAG, "state nudge throttled (${now - lastStateNudgeAt}ms since last)")
            return
        }
        lastStateNudgeAt = now
        PanaLog.d(TAG, "bridge state changed ($signature), scheduling event-driven nudge")
        nudgeAncCardRefresh()
        // v109：Bridge 状态变更（首连/重连）时立即补一次卡图替换，
        // 不等卡片重建或 3s 保活 tick，消除首连"先系统图后 Pana 图"的延迟。
        MiLinkCardArtHook.onPanaCardMaybeActive()
        // 补一发延迟 nudge：覆盖 Cir_MDC 在刷新后再次推送旧值 [-1,-1,-1] 的窗口
        stateChangeHandler.removeCallbacksAndMessages(null)
        stateChangeHandler.postDelayed({ nudgeAncCardRefresh() }, 1500L)
    }

    /** 缓存持有 headsetPropertyChangeListener 的 runtime 实例（ProfileContext / AncBatteryController 等） */
    private fun captureAncInstance(obj: Any?) {
        if (obj == null || runtimeInstances.contains(obj)) return
        try {
            XposedHelpers.getObjectField(obj, "headsetPropertyChangeListener")
            runtimeInstances.add(obj)
            PanaLog.d(TAG, "captured runtime instance ${obj.javaClass.simpleName} (total=${runtimeInstances.size})")
        } catch (_: Throwable) {}
    }

    /**
     * v130：卡图替换后立即在当前进程（:ui）触发 ANC 重建，让照片与降噪控件
     * 几乎同步出现，不再等 :core 跨进程广播后转 nudge 的 2 秒延迟。
     *
     * 路径：卡图 hook 命中 → 照片 visible → 我们直接调 listener.invoke(device, 8+4)
     * → PropertyChange 通过 ProfileContext 已 set 的 listener（systemui 注册的 IPC 代理）
     * 立刻下发到 systemui。systemui 卡片下一帧就拿到 ANC/Battery 新值并渲染。
     *
     * 没有 :core 中转、没有 CARD_ASSEMBLED 广播、没有 SYSTEM_BROADCAST_HANDLER 队列等待。
     *
     * @return true 表示本次确实调用了 listener；false 表示当前进程 listener 还没就绪。
     */
    @Volatile private var lastLocalAncFireAt = 0L
    fun fireAncSyncLocally(): Boolean {
        val now = System.currentTimeMillis()
        // 节流 200ms：照片 hook 一帧可能连续命中多次（系统覆盖动画），避免同一帧内多次 IPC 风暴。
        // 真正的节流保护在底层 systemui（重复属性变更会被去重）。
        if (now - lastLocalAncFireAt < 200L) return false
        lastLocalAncFireAt = now

        val cl = hookClassLoader ?: return false
        val pcClass = findClass(PROFILE_CONTEXT, cl) ?: return false
        // v114 优先用 setHeadsetPropertyChangeListener 注入时缓存的 listener 实例；
        // :ui 进程通常已经 set 过（systemui 在卡图渲染前就注入），这里一定能拿到。
        val listener = try {
            cachedHeadsetListener
                ?: XposedHelpers.callMethod(
                    XposedHelpers.getStaticObjectField(pcClass, "INSTANCE"),
                    "getHeadsetPropertyChangeListener"
                )
        } catch (_: Throwable) { null } ?: run {
            PanaLog.d(TAG, "fireAncSyncLocally: listener null, fallback to CARD_ASSEMBLED broadcast only")
            return false
        }

        val addr = activePanaAddress
            ?: getLeAudioActivePanaAddress()
            ?: PanaBridge.getMacAddress()
            ?: panaAddresses.firstOrNull { !it.isNullOrBlank() }
            ?: run {
                PanaLog.w(TAG, "fireAncSyncLocally: no pana addr")
                return false
            }
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return false
        val device = try { adapter.getRemoteDevice(addr) } catch (_: Throwable) { null }
            ?: run { PanaLog.w(TAG, "fireAncSyncLocally: getRemoteDevice($addr) failed"); return false }

        // 直接调 listener.invoke（不走 handler.post），让 ANC/Battery 属性变更
        // 立刻下发到 systemui。把 listener 视作同步 IPC 代理处理。
        // listener 实际是 systemui 端的 Binder proxy，会把 (device, type=8/4) 封包
        // 发给 systemui，systemui 端 Binder 线程派发到主线程触发 assembleHeadsetInfo。
        // 全链路 ~30-80ms，比 :ui → broadcast → :core → handler.post → invoke 路径快一个数量级。
        return try {
            XposedHelpers.callMethod(listener, "invoke", device, Integer.valueOf(8))
            XposedHelpers.callMethod(listener, "invoke", device, Integer.valueOf(4))
            PanaLog.i(TAG, "fireAncSyncLocally: listener.invoke(8,4) fired addr=$addr (photo + ANC should now appear together)")
            true
        } catch (t: Throwable) {
            PanaLog.w(TAG, "fireAncSyncLocally: listener.invoke failed: ${t.message}")
            false
        }
    }

    private fun findClass(name: String, cl: ClassLoader): Class<*>? = try {
        XposedHelpers.findClass(name, cl)
    } catch (_: Throwable) { null }

        // ============ 把 ANC 模式发给 app 切换真实耳机 ============

    private fun sendAncModeToApp(mode: Int) {
        val ctx = try {
            XposedHelpers.callStaticMethod(
                Class.forName("android.app.ActivityThread"), "currentApplication") as? Context
        } catch (_: Throwable) { null } ?: return
        PanaBridge.sendAncModeToApp(ctx, mode)
    }

    // ============ 电量注入 ============

    private fun hookBatteryInMiLink(classLoader: ClassLoader) {
        // v103：milink 进程内不再注入 BluetoothDevice.getBatteryLevel。
        // Cir_MDC 的通用蓝牙观察者 (BluetoothDeviceObserver, protocol 524288) 会把它
        // 拼成 [平均,平均,平均,-1,-1,-1]，与耳机路径 (HeadsetServiceController,
        // protocol 393216) 的 [盒,左,右] 互相覆盖 —— 日志里表现为
        // "foundOrUpdateDevice third_headset [100,100,100]" 与 "headset [70,100,100]"
        // 交替更新，卡片电量/降噪控件因此时有时无。融合中心卡片的电量实际来自
        // getBatteryLevelCache / HeadsetInfo powers（耳机路径），无需此注入。
        if (isMilinkProcess()) {
            PanaLog.i(TAG, "skip getBatteryLevel hook in milink process ${currentProcessName()} (avoid Cir_MDC battery race)")
            return
        }
        try {
            val btDeviceClass = XposedHelpers.findClass(
                "android.bluetooth.BluetoothDevice", classLoader)
            XposedBridge.hookAllMethods(btDeviceClass, "getBatteryLevel",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val device = param.thisObject as? BluetoothDevice ?: return
                        if (!isPana(device)) return // 走地址缓存，避免每次 getBatteryLevel 都查 name
                        val bleBattery = PanaBridge.getAverageBattery()
                        if (bleBattery in 0..100) param.result = bleBattery
                    }
                }
            )
            PanaLog.i(TAG, "MiLink getBatteryLevel() hooked ✓ (process=${currentProcessName()})")
        } catch (e: Throwable) {
            PanaLog.e(TAG, "Failed to hook getBatteryLevel in MiLink", e)
        }
    }

    private fun currentProcessName(): String? = try {
        XposedHelpers.callStaticMethod(
            Class.forName("android.app.ActivityThread"), "currentProcessName"
        ) as? String
    } catch (_: Throwable) { null }

    private fun isMilinkProcess(): Boolean =
        currentProcessName()?.startsWith("com.milink.service") == true

    /**
     * v103：兜底 hook Cir_MDC 通用蓝牙观察者 (com.miui.circulate.device.service.search.a)
     * 的 getBluetoothDeviceBattery(device)。该方法被 protocol 524288 (third_headset) 路径调用，
     * 原生返回 [-1,-1,-1] 或 [平均,平均,平均]，与耳机路径 (393216) 的 [盒,左,右] 互相覆盖。
     * 对 Pana 设备直接用耳机路径的真实电量覆盖，让两条路径数据一致，消除卡片电量时有时无。
     */
    private fun hookCirculateSearchBattery(classLoader: ClassLoader) {
        try {
            val clazz = XposedHelpers.findClass(CIRCULATE_SEARCH_A, classLoader) ?: return
            XposedBridge.hookAllMethods(clazz, "getBluetoothDeviceBattery", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val target = param.args.firstOrNull()
                    val isPanaTarget = when (target) {
                        is BluetoothDevice -> isPana(target)
                        is String -> isPanaAddress(target)
                        else -> false
                    }
                    if (!isPanaTarget) return
                    param.result = miLinkBatteryLevels()
                }
            })
            PanaLog.i(TAG, "Cir_MDC search getBluetoothDeviceBattery hooked ✓")
        } catch (t: Throwable) {
            PanaLog.w(TAG, "hook getBluetoothDeviceBattery failed: ${t.message}")
        }
    }
}
