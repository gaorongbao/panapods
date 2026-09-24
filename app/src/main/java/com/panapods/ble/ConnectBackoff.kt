package com.panapods.ble

/**
 * 连接风暴退避（v171）。
 *
 * 背景：耳机不在场/不可达时，`ACL_CONNECTED` 广播 + 周期看门狗会让 `connect()` 每
 * 5~6 秒被调用一次且永不放弃（实测 5 分钟 23 次 GATT CONN→CLOSE，gatt_if 97→119）。
 * 每次失败的 `connectGatt` 都会在耳机侧拉起又拆掉一条 ACL；对正在用 LE Audio 放音的
 * 耳机而言，这种反复连接扰动会打断单侧音频流，表现为「只剩一只耳响」。
 *
 * 策略：
 * - **只对「连续失败之后的重复尝试」限流**。首次尝试永远放行。
 * - 设备一旦在系统层面真实在线（A2DP/HEADSET/LE_AUDIO 任一），[shouldThrottle] 收到
 *   `present = true` 会立即 [reset]，保证「拿出耳机秒连」不退化。
 *
 * 纯逻辑、不依赖 Android，便于单测。
 */
class ConnectBackoff(
    private val schedule: (streak: Int) -> Long = ::defaultScheduleMs,
) {
    companion object {
        /** 连续失败 streak 次后，下一次允许尝试前的等待时长。 */
        fun defaultScheduleMs(streak: Int): Long = when {
            streak <= 1 -> 2_000L
            streak == 2 -> 5_000L
            streak == 3 -> 15_000L
            else -> 30_000L
        }
    }

    /** 连续失败计数（连接成功 / 设备在线 / 用户手动操作后归零）。 */
    var streak: Int = 0
        private set

    private var nextAllowedAt = 0L

    /**
     * 本次尝试是否应被抑制。
     *
     * @param now 单调时钟（`SystemClock.elapsedRealtime()`）
     * @param present 设备当前是否在系统层面真实在线
     */
    fun shouldThrottle(now: Long, present: Boolean): Boolean {
        if (present) {
            reset()
            return false
        }
        return streak > 0 && now < nextAllowedAt
    }

    /**
     * 记一次连接失败。
     *
     * @return 本次安排的下一次允许尝试的延迟毫秒数
     */
    fun noteFailure(now: Long): Long {
        streak += 1
        val delay = schedule(streak)
        nextAllowedAt = now + delay
        return delay
    }

    /** 距下一次允许尝试还剩多少毫秒（仅用于日志，已确保非负）。 */
    fun waitMs(now: Long): Long = (nextAllowedAt - now).coerceAtLeast(0L)

    fun reset() {
        streak = 0
        nextAllowedAt = 0L
    }
}
