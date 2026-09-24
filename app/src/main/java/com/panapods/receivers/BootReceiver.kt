package com.panapods.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.panapods.utils.Async
import com.panapods.utils.PanaLog
import com.panapods.utils.RootKeepAlive
import com.panapods.config.ConfigManager
import com.panapods.ble.PanaBleService

/**
  * 开机自启动广播接收器
 *
  * 在设备启动完成后，如果配置了自动连接，
  * 则自动启动 BLE 服务并尝试连接上次使用的耳机。
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "PanaPods/BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val config = ConfigManager(context)
        if (!config.autoConnect) {
            PanaLog.d(TAG, "Auto-connect disabled, skip")
            return
        }

        val address = config.lastBtAddress
        if (address == null) {
            PanaLog.d(TAG, "No saved BT address, skip")
        } else {
            PanaLog.i(TAG, "Boot completed, auto-connecting to: $address")

            val serviceIntent = Intent(context, PanaBleService::class.java).apply {
                action = PanaBleService.ACTION_CONNECT
                putExtra(PanaBleService.EXTRA_ADDRESS, address)
            }

            try {
                context.startForegroundService(serviceIntent)
            } catch (e: Throwable) {
                PanaLog.w(TAG, "startForegroundService blocked: ${e.message}")
            }
        }

        // Root 保活：开机后按需调度周期拉起 alarm，并在 root 可用时应用一次白名单。
        if (config.rootKeepAlive) {
            KeepAliveScheduler.schedule(context)
            Async.run("boot-root-keepalive") {
                runCatching { RootKeepAlive.apply(context) }
            }
        }
    }
}
