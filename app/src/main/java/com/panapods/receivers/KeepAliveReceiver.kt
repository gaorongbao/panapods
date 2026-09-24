package com.panapods.receivers

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import com.panapods.config.ConfigManager
import com.panapods.utils.Async
import com.panapods.utils.PanaLog
import com.panapods.utils.RootKeepAlive

/**
 * Periodic root keep-alive tick.
 *
 * The alarm is scheduled while the app process is alive; if the process gets
 * killed, AlarmManager still delivers the next tick and the receiver restarts
 * the BLE service through root (which is allowed to start foreground services
 * from the background). Battery cost: one `pidof` + maybe one `am` every 15 min.
 */
class KeepAliveReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "KeepAliveReceiver"
        const val ACTION_TICK = "com.panapods.action.KEEPALIVE_TICK"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val config = ConfigManager(context)
        if (!config.rootKeepAlive) {
            PanaLog.d(TAG, "root keep-alive disabled, skipping tick")
            return
        }
        PanaLog.d(TAG, "keep-alive tick")
        // v175：广播返回后进程可被立刻回收，`Async.run` 的守护线程随之被杀，
        // 根命令（su 拉起服务）根本跑不完 —— 这正是"进程被杀后服务没被拉起"的来源。
        // goAsync 把 receiver 生命周期延长到 su 命令结束（上限约 10s，su 通常 <1s）。
        val pending = goAsync()
        Async.run("keepalive-tick") {
            try {
                RootKeepAlive.ensureServiceAlive(context)
            } catch (t: Throwable) {
                PanaLog.w(TAG, "tick failed: ${t.message}")
            } finally {
                pending.finish()
            }
        }
        // Reschedule the next tick in case the alarm was one-shot.
        KeepAliveScheduler.schedule(context)
    }
}

object KeepAliveScheduler {

    private const val TAG = "KeepAliveScheduler"

    fun schedule(context: Context) {
        val config = ConfigManager(context)
        if (!config.rootKeepAlive) {
            PanaLog.d(TAG, "root keep-alive disabled, not scheduling")
            return
        }
        try {
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pi = pendingIntent(context)
            // Inexact repeating alarm: no SCHEDULE_EXACT_ALARM permission needed.
            // Doze throttling is mitigated by the deviceidle whitelist applied via root.
            am.setInexactRepeating(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + RootKeepAlive.ALARM_INTERVAL_MS,
                RootKeepAlive.ALARM_INTERVAL_MS,
                pi
            )
            PanaLog.i(TAG, "keep-alive alarm scheduled every ${RootKeepAlive.ALARM_INTERVAL_MS}ms")
        } catch (t: Throwable) {
            PanaLog.w(TAG, "schedule failed: ${t.message}")
        }
    }

    fun cancel(context: Context) {
        try {
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            am.cancel(pendingIntent(context))
            PanaLog.i(TAG, "keep-alive alarm cancelled")
        } catch (t: Throwable) {
            PanaLog.w(TAG, "cancel failed: ${t.message}")
        }
    }

    private fun pendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, KeepAliveReceiver::class.java).apply {
            action = KeepAliveReceiver.ACTION_TICK
        }
        return PendingIntent.getBroadcast(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
