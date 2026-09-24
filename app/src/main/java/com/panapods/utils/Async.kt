package com.panapods.utils

import java.util.concurrent.atomic.AtomicInteger

/**
 * 轻量后台任务执行器。
 *
 * 收敛项目里散落的 `Thread { ... }.apply { isDaemon = true }.start()` 裸线程：
 * 统一为具名守护线程，并兜底捕获未处理异常避免进程内线程崩溃刷屏。
 */
object Async {

    private val seq = AtomicInteger(0)

    /** 在后台守护线程中执行 [task]；异常只记录日志不抛出。 */
    fun run(name: String = "panapods-bg", task: () -> Unit) {
        Thread({
            try {
                task()
            } catch (t: Throwable) {
                PanaLog.w("Async", "$name failed: ${t.message}")
            }
        }, "$name-${seq.incrementAndGet()}").apply {
            isDaemon = true
        }.start()
    }
}
