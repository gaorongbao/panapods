package com.panapods.utils

import android.content.Context
import android.content.Intent
import com.panapods.ble.PanaBleService

/**
 * Root-based process keep-alive helper.
 *
 * Uses `su` to:
 * 1. add com.panapods to the Doze/deviceidle whitelist so background work is not
 *    throttled aggressively by HyperOS power management;
 * 2. start the BLE foreground service from root shell (bypasses background
 *    startForegroundService restrictions) when the app process was killed.
 *
 * All su calls are short-lived and never spawn a persistent root daemon, so the
 * battery cost stays low. The periodic restart is driven by AlarmManager
 * (KeepAliveReceiver) and the in-service watchdog.
 */
object RootKeepAlive {

    private const val TAG = "RootKeepAlive"

    /** AlarmManager periodic restart interval (ms). */
    const val ALARM_INTERVAL_MS = 15 * 60_000L

    /** Quick root availability check (cached for 30s to avoid repeated su spawns). */
    @Volatile private var lastAvailabilityCheck = 0L
    @Volatile private var lastAvailability = false

    fun isRootAvailable(): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastAvailabilityCheck < 30_000L) return lastAvailability
        val result = SuExecutor.run("id", timeoutSeconds = 5)
        val available = result.available && result.exitCode == 0 &&
            result.output.contains("uid=0", ignoreCase = true)
        lastAvailability = available
        lastAvailabilityCheck = now
        if (available) {
            PanaLog.i(TAG, "root available (uid=0)")
        } else {
            PanaLog.w(TAG, "root unavailable: ${result.output}")
        }
        return available
    }

    /**
     * Apply keep-alive once:
     * - whitelist the app in deviceidle (battery optimization)
     * - make sure the BLE service is running (root can start it from background)
     */
    fun apply(context: Context): Boolean {
        val script = buildString {
            appendLine("dumpsys deviceidle whitelist +${context.packageName} 2>/dev/null")
            appendLine("am start-foreground-service -n ${context.packageName}/.ble.PanaBleService 2>/dev/null")
            appendLine("exit 0")
        }
        val result = SuExecutor.run(script, timeoutSeconds = 20)
        PanaLog.i(TAG, "applyKeepAlive exit=${result.exitCode} output=${result.output.trim()}")
        return result.available && result.exitCode == 0
    }

    /**
     * v175：关闭"Root 自动保活"时回收 apply() 加上的 deviceidle 白名单。
     * 原先只 cancel 了闹钟，白名单永久留在系统里 —— 用户以为关了，电池优化
     * 实际仍被豁免，且无从得知、也无法再关掉。
     */
    fun remove(context: Context): Boolean {
        val script = buildString {
            appendLine("dumpsys deviceidle whitelist -${context.packageName} 2>/dev/null")
            appendLine("exit 0")
        }
        val result = SuExecutor.run(script, timeoutSeconds = 20)
        PanaLog.i(TAG, "removeKeepAlive exit=${result.exitCode} output=${result.output.trim()}")
        return result.available && result.exitCode == 0
    }

    /**
     * Restart the service only when the app process is dead. Called by the
     * periodic alarm so the already-running service is not disturbed.
     */
    fun ensureServiceAlive(context: Context): Boolean {
        val script = buildString {
            appendLine("if pidof ${context.packageName} >/dev/null 2>&1; then")
            appendLine("  exit 0")
            appendLine("fi")
            appendLine("am start-foreground-service -n ${context.packageName}/.ble.PanaBleService 2>/dev/null")
            appendLine("exit 0")
        }
        val result = SuExecutor.run(script, timeoutSeconds = 20)
        PanaLog.i(TAG, "ensureServiceAlive exit=${result.exitCode} output=${result.output.trim()}")
        return result.available && result.exitCode == 0
    }

}
