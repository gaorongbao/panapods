package com.panapods.ble

import android.os.Handler

/**
 * GATT 连接建立的重试退避策略。
 *
 * 从 AirohaBleClient 中抽出：每次重试延迟 = baseDelayMs * 当前重试次数（1,2,3...），
 * 最多 [maxRetries] 次。连接成功/主动断开时 reset()。
 */
class ConnectRetryPolicy(
    private val handler: Handler,
    val maxRetries: Int = 3,
    private val baseDelayMs: Long = 800L,
) {
    private var retryCount = 0

    val count: Int get() = retryCount

    fun reset() {
        retryCount = 0
    }

    fun canRetry(): Boolean = retryCount < maxRetries

    /**
     * 安排一次延迟重试（内部自增计数并投递任务）。
     *
     * @return 实际延迟毫秒数；-1 表示已达上限未安排。
     */
    fun scheduleRetry(task: () -> Unit): Long {
        if (!canRetry()) return -1L
        retryCount++
        val delay = baseDelayMs * retryCount
        handler.postDelayed(task, delay)
        return delay
    }
}
