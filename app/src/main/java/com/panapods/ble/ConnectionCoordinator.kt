package com.panapods.ble

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.panapods.utils.PanaLog

/**
 * 连接健康协调器：周期看门狗 + 连接超时重试 + LE Audio 退避 + 延迟重连。
 *
 * 从 PanaBleService 中抽出。服务通过 [Callback] 提供状态读取与操作，
 * 协调器只持有主线程 Handler 与各调度状态，不直接依赖 Service。
 */
class ConnectionCoordinator(
    private val cb: Callback,
) {
    interface Callback {
        fun isConnected(): Boolean
        fun isConnecting(): Boolean
        fun isSuppressAutoReconnect(): Boolean
        fun currentAddress(): String?
        fun savedAddress(): String?
        fun autoConnectEnabled(): Boolean
        fun isBluetoothOn(): Boolean
        fun isBonded(address: String): Boolean
        fun isGattActuallyConnected(address: String): Boolean
        fun isLeAudioConnected(): Boolean
        fun connect(address: String)
        fun teardownClient(notifyDisconnected: Boolean)
        fun notifyGiveUpReconnect()
        fun log(message: String)
        /** v173：连接建立超时（CONNECT_TIMEOUT_MS）触发时记账到连接退避。 */
        fun noteConnectTimeoutFailure() {}
    }

    companion object {
        private const val TAG = "PanaBleService"
        // 连接建立超时：connectGatt 后迟迟没有 STATE_CONNECTED 回调时强制复位重试
        private const val CONNECT_TIMEOUT_MS = 20_000L
        // 周期看门狗：检测“服务以为连着但实际 GATT 已死”的 stale 状态，或自动补连
        private const val WATCHDOG_INTERVAL_MS = 15_000L
    }

    private val handler = Handler(Looper.getMainLooper())
    private var watchdogRunning = false
    private val leAudioBackoff = LeAudioBackoff()
    private val delayedReconnect = DelayedReconnectScheduler(
        handler = handler,
        canReconnect = {
            !cb.isConnected() && !cb.isConnecting() && !cb.isSuppressAutoReconnect()
        },
        onReconnect = { cb.connect(it) },
    )

    private val watchdogRunnable = object : Runnable {
        override fun run() {
            if (!watchdogRunning) return
            try {
                ensureConnectionHealthy()
            } catch (e: Throwable) {
                PanaLog.w(TAG, "watchdog tick failed: ${e.message}")
            }
            handler.postDelayed(this, WATCHDOG_INTERVAL_MS)
        }
    }

    private val connectTimeoutRunnable = Runnable { handleConnectTimeout() }

    fun start() {
        if (watchdogRunning) return
        watchdogRunning = true
        handler.removeCallbacks(watchdogRunnable)
        handler.postDelayed(watchdogRunnable, WATCHDOG_INTERVAL_MS)
        PanaLog.d(TAG, "Connection watchdog started")
    }

    fun stop() {
        watchdogRunning = false
        handler.removeCallbacks(watchdogRunnable)
        handler.removeCallbacks(connectTimeoutRunnable)
        delayedReconnect.cancel()
    }

    fun armConnectTimeout() {
        handler.removeCallbacks(connectTimeoutRunnable)
        handler.postDelayed(connectTimeoutRunnable, CONNECT_TIMEOUT_MS)
    }

    fun cancelConnectTimeout() {
        handler.removeCallbacks(connectTimeoutRunnable)
    }

    /** GATT 连接成功：取消超时、重置 LE 退避并清掉排队的延迟重连。 */
    fun onConnected() {
        handler.removeCallbacks(connectTimeoutRunnable)
        leAudioBackoff.reset()
        delayedReconnect.cancel()
    }

    /** GATT 断开：取消连接超时（看门狗继续周期运行）。 */
    fun onDisconnected() {
        handler.removeCallbacks(connectTimeoutRunnable)
    }

    fun cancelAutoReconnect() {
        delayedReconnect.cancel()
    }

    private fun handleConnectTimeout() {
        if (!cb.isConnecting() || cb.isConnected()) return
        // v176：下面 noteConnectTimeoutFailure() 会把下一次尝试推入退避窗口（缺席时
        // 2~30s），紧随的 cb.connect() 可能被 ConnectBackoff 挡下，真正的新一轮连接
        // 还可能要等 15s 看门狗——日志不能无条件宣称 "retrying"，排查时会误导。
        PanaLog.w(TAG, "Connect timeout after ${CONNECT_TIMEOUT_MS}ms, resetting; reconnect subject to backoff/watchdog")
        cb.log("WARN: connect timeout, retry scheduled (backoff)")
        // v173：超时也是一次“没连上”，必须计入退避，否则 streak 恒 0、
        // v171 的指数退避永不生效 → 耳机不在场时 20s 一轮重连风暴无上限。
        cb.noteConnectTimeoutFailure()
        cb.teardownClient(notifyDisconnected = false)
        val address = cb.currentAddress() ?: cb.savedAddress()
        if (address != null) {
            // LC3/LE Audio 模式下不要立刻重试 GATT：系统 LE Audio 已接管音频，
            // 频繁 GATT connect/disconnect 会干扰单侧音频流（单侧无声）。
            if (cb.isLeAudioConnected()) {
                val backoff = leAudioBackoff.delayMs()
                PanaLog.w(TAG, "LE Audio active, delaying GATT retry by ${backoff}ms to avoid audio disturbance")
                cb.log("WARN: LE Audio active, GATT retry in ${backoff / 1000}s")
                delayedReconnect.schedule(address, backoff)
            } else {
                cb.connect(address)
            }
        } else {
            cb.notifyGiveUpReconnect()
        }
    }

    /**
     * 周期看门狗：
     * 1. isConnected=true 但系统 GATT 实际已断开（耳机放回充电盒时回调丢失）→ 强制重连；
     * 2. 未连接且未在连接中、自动连接开启、设备仍配对 → 补连（覆盖“拿出耳机但 ACL_CONNECTED
     *    广播没触发/连接建立失败后重试耗尽”的场景）。
     */
    private fun ensureConnectionHealthy() {
        if (cb.isSuppressAutoReconnect()) return
        if (!cb.autoConnectEnabled()) return

        val address = cb.currentAddress() ?: cb.savedAddress() ?: return
        if (!cb.isBluetoothOn()) return
        if (!cb.isBonded(address)) return

        if (cb.isConnected()) {
            if (!cb.isGattActuallyConnected(address)) {
                PanaLog.w(TAG, "Watchdog: connected flag stale, GATT dead for $address, forcing reconnect")
                cb.log("WARN: watchdog detected dead GATT, reconnecting")
                cb.teardownClient(notifyDisconnected = false)
                cb.connect(address)
            }
        } else if (!cb.isConnecting()) {
            // LC3/LE Audio 模式下降低自动重连频率，避免 15~30s 一次 GATT 循环干扰音频。
            if (cb.isLeAudioConnected() && !leAudioBackoff.tryAcquire()) {
                PanaLog.d(TAG, "Watchdog: LE Audio active, skipping GATT auto-reconnect (backoff)")
                return
            }
            PanaLog.i(TAG, "Watchdog: not connected, auto-connecting to $address")
            cb.log("AUTO-CONNECT: $address")
            cb.connect(address)
        }
    }
}

/**
 * LE Audio 模式下 GATT 自动重连退避：默认 10 分钟一次。
 * 首次调用视为允许并开始计时。
 */
class LeAudioBackoff(private val backoffMs: Long = 10 * 60_000L) {
    private var lastReconnectAt = 0L

    fun tryAcquire(now: Long = SystemClock.elapsedRealtime()): Boolean {
        if (lastReconnectAt == 0L) {
            lastReconnectAt = now
            return true
        }
        if (now - lastReconnectAt < backoffMs) return false
        lastReconnectAt = now
        return true
    }

    fun reset() {
        lastReconnectAt = 0L
    }

    fun delayMs(): Long = backoffMs
}

/**
 * 延迟 GATT 重连调度器：持有待重连地址，到期后按条件触发重连。
 */
class DelayedReconnectScheduler(
    private val handler: Handler,
    private val canReconnect: () -> Boolean,
    private val onReconnect: (String) -> Unit,
) {
    private var pendingAddress: String? = null

    private val runnable = Runnable {
        val address = pendingAddress ?: return@Runnable
        pendingAddress = null
        if (!canReconnect()) return@Runnable
        onReconnect(address)
    }

    fun schedule(address: String, delayMs: Long) {
        handler.removeCallbacks(runnable)
        pendingAddress = address
        handler.postDelayed(runnable, delayMs)
    }

    fun cancel() {
        handler.removeCallbacks(runnable)
        pendingAddress = null
    }
}
