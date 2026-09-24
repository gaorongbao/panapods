package com.panapods.utils

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * v175：异步滚动文件日志（仅 App 进程 init）。
 *
 * - 目录：外部应用专属目录 logs/（adb 可直接取走：
 *   /sdcard/Android/data/com.panapods/files/logs/），获取失败回退内部 filesDir/logs/。
 * - 滚动：panapods.log 达 512KB 时 .1→.2、当前→.1（旧 .2 丢弃），
 *   共保留当前 + 2 份备份，约 1.5MB 上限。
 * - 写入在单线程后台执行，任何异常静默吞掉 —— 日志不允许影响主流程。
 * - Hook 进程不调用 init：write() 直接 no-op（跨应用无写权限，
 *   Hook 侧日志统一走 LSPosed 模块日志，见 PanaLog.attachLspLogger）。
 */
object FileLog {

    private const val FILE_NAME = "panapods.log"
    private const val MAX_BYTES = 512L * 1024
    private const val BACKUPS = 2

    /** 未初始化（Hook 进程）时为 null，write() 直接短路。 */
    @Volatile
    private var dir: File? = null

    /** daemon 线程：进程退出时不阻塞。目录未就绪时不会被创建。 */
    private val io by lazy {
        Executors.newSingleThreadExecutor { r ->
            Thread(r, "pana-file-log").apply { isDaemon = true }
        }
    }

    fun init(context: Context) {
        if (dir != null) return
        val base = try {
            context.getExternalFilesDir(null)
        } catch (_: Throwable) {
            null
        } ?: context.filesDir
        val d = File(base, "logs")
        try { d.mkdirs() } catch (_: Throwable) {}
        dir = d
        write("---- file log started, pid=${android.os.Process.myPid()} dir=${d.absolutePath} ----")
    }

    /** 当前日志文件绝对路径（未初始化返回 null，诊断页/设置页可展示）。 */
    fun path(): String? = dir?.let { File(it, FILE_NAME).absolutePath }

    fun write(line: String) {
        val d = dir ?: return
        try {
            io.execute { try { append(d, line) } catch (_: Throwable) {} }
        } catch (_: Throwable) {
            // executor 已 shutdown 等极端情况：丢日志，不影响调用方
        }
    }

    private fun append(d: File, line: String) {
        val f = File(d, FILE_NAME)
        if (f.length() >= MAX_BYTES) rotate(f)
        val stamp = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
        FileOutputStream(f, true).use { out ->
            out.write("$stamp $line\n".toByteArray(Charsets.UTF_8))
        }
    }

    private fun rotate(f: File) {
        for (i in BACKUPS downTo 1) {
            val from = if (i == 1) f else File(f.parentFile, "$FILE_NAME.${i - 1}")
            val to = File(f.parentFile, "$FILE_NAME.$i")
            if (from.exists()) {
                if (to.exists()) to.delete()
                from.renameTo(to)
            }
        }
    }
}
