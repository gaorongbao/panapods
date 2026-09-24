package com.panapods.utils

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipFile

/**
 * 通过 root (su) 重启 Xposed 作用域内的应用。
 *
 * 作用域列表直接读取 APK 中打包的 META-INF/xposed/scope.list，
 * 与 LSPosed 使用的作用域声明保持一致；读取失败时回退到内置默认列表。
 *
 * 重启策略（与 LSPosed "重启作用域应用" 一致）：
 * 1. am force-stop：强制停止应用
 * 2. killall：结束常驻进程（SystemUI / Bluetooth 等 persistent 进程
 *    对 force-stop 免疫，但被 kill 后会被系统自动拉起，实现重启）
 */
object ScopeRestarter {

    private const val TAG = "ScopeRestarter"
    private const val SCOPE_LIST_ENTRY = "META-INF/xposed/scope.list"

    /** scope.list 读取失败时的兜底列表（与 META-INF/xposed/scope.list 保持一致） */
    val FALLBACK_SCOPE_PACKAGES: List<String> = listOf(
        "com.android.bluetooth",
        "com.android.settings",
        "com.android.systemui",
        "com.miui.contentcatcher",
        "com.milink.service",
        "com.xiaomi.bluetooth",
    )

    data class RestartResult(
        val success: Boolean,
        val packages: List<String>,
        val output: String,
    )

    /**
     * 从当前 APK 读取打包好的 scope.list（每行一个包名，支持 # 注释）。
     */
    fun readScopePackages(context: Context): List<String> {
        val apkPath = context.applicationInfo?.sourceDir
        if (apkPath.isNullOrBlank()) return FALLBACK_SCOPE_PACKAGES

        return runCatching {
            ZipFile(File(apkPath)).use { zip ->
                val entry = zip.getEntry(SCOPE_LIST_ENTRY) ?: return FALLBACK_SCOPE_PACKAGES
                zip.getInputStream(entry).bufferedReader().useLines { lines ->
                    lines.map { it.trim() }
                        .filter { it.isNotEmpty() && !it.startsWith("#") }
                        .toList()
                }
            }
        }.getOrElse {
            PanaLog.w(TAG, "readScopePackages failed: ${it.message}")
            FALLBACK_SCOPE_PACKAGES
        }
    }

    /**
     * 在 IO 线程执行 root 重启（仅重启指定包）。
     */
    suspend fun restartScopedApps(context: Context, packages: List<String>): RestartResult =
        withContext(Dispatchers.IO) {
            val target = packages.filter { it.isNotBlank() }.distinct()
            if (target.isEmpty()) {
                return@withContext RestartResult(
                    success = false,
                    packages = emptyList(),
                    output = "no packages selected",
                )
            }

            PanaLog.i(TAG, "Restarting scoped apps: $target")

            val (code, output) = runSu(buildRestartScript(target))
            val success = code == 0
            PanaLog.i(TAG, "su exit=$code output=$output")

            RestartResult(success = success, packages = target, output = output)
        }

    /**
     * 生成 root shell 脚本：
     * 对每个作用域包先 am force-stop，再 killall（覆盖 persistent 进程）；
     * killall 不可用时回退到 pidof + kill。
     *
     * 关键：脚本最后统一 `exit 0`。因为 `am force-stop` / `killall`
     * 对单个包可能返回非 0（如进程已不存在），但这不是 root 失败；
     * 只要 su 本身授权执行成功（脚本能跑完），就视为重启成功。
     * su 拒绝授权/未找到时，su 进程自身返回非 0，仍会被判定为失败。
     */
    private fun buildRestartScript(packages: List<String>): String {
        val quoted = packages.joinToString(" ") { "\"$it\"" }
        return buildString {
            appendLine("AM=/system/bin/am")
            appendLine("[ -x \"\$AM\" ] || AM=\$(command -v am 2>/dev/null)")
            appendLine("KILLALL=/system/bin/killall")
            appendLine("[ -x \"\$KILLALL\" ] || KILLALL=\$(command -v killall 2>/dev/null)")
            append("for pkg in ").append(quoted).appendLine("; do")
            appendLine("  \"\$AM\" force-stop \"\$pkg\" 2>/dev/null")
            appendLine("  if [ -n \"\$KILLALL\" ] && [ -x \"\$KILLALL\" ]; then")
            appendLine("    \"\$KILLALL\" \"\$pkg\" 2>/dev/null")
            appendLine("  else")
            appendLine("    pid=\$(/system/bin/pidof \"\$pkg\" 2>/dev/null)")
            appendLine("    [ -n \"\$pid\" ] && /system/bin/kill \$pid 2>/dev/null")
            appendLine("  fi")
            appendLine("done")
            appendLine("exit 0")
        }
    }

    /**
     * 依次尝试常见 su 路径，执行 `su -c <script>`。
     *
     * @return Pair(exitCode, mergedOutput)
     */
    private fun runSu(script: String): Pair<Int, String> {
        val result = SuExecutor.run(script, timeoutSeconds = 60)
        return result.exitCode to result.output
    }
}
