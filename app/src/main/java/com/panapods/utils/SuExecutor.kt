package com.panapods.utils

import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 公共 root (su) 命令执行器。
 *
 * RootKeepAlive 与 ScopeRestarter 都需要依次尝试常见 su 路径、
 * 异步读取输出并处理超时；统一收口到此处避免两套几乎相同的实现。
 */
object SuExecutor {

    data class Result(
        val exitCode: Int,
        val output: String,
        val available: Boolean,
    )

    private val SU_CANDIDATES = listOf("su", "/system/bin/su", "/system/xbin/su", "/sbin/su")

    /**
     * 依次尝试常见 su 路径执行 `su -c <script>`。
     *
     * @return exitCode 为进程退出码；available 表示是否找到了可执行的 su。
     *         超时退出码约定为 124，中断为 130。
     */
    fun run(script: String, timeoutSeconds: Long = 15): Result {
        var lastOutput = "su binary not found"
        for (su in SU_CANDIDATES) {
            try {
                val process = ProcessBuilder(su, "-c", script)
                    .redirectErrorStream(true)
                    .start()

                val output = StringBuilder()
                val reader = Thread {
                    process.inputStream.bufferedReader().use { r ->
                        r.forEachLine { output.appendLine(it) }
                    }
                }.apply { isDaemon = true; start() }

                val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
                if (!finished) {
                    process.destroyForcibly()
                    reader.join(1_000)
                    output.appendLine("[timeout after ${timeoutSeconds}s]")
                    return Result(124, output.toString(), true)
                }
                reader.join(1_000)
                return Result(process.exitValue(), output.toString(), true)
            } catch (e: IOException) {
                lastOutput = e.message ?: e.javaClass.simpleName
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return Result(130, "interrupted", false)
            }
        }
        return Result(-1, lastOutput, false)
    }
}
