package com.panapods.ble

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context

/**
 * BLE 前台服务的通知控制器。
 *
 * 从 PanaBleService 中抽出：负责创建通知渠道、构建并刷新常驻通知。
 */
class NotificationController(context: Context) {

    companion object {
        private const val CHANNEL_ID = "panapods_ble"
        /** 前台服务通知 ID；Service.startForeground 与 notify 共用。 */
        const val NOTIFICATION_ID = 1001
    }

    private val appContext = context.applicationContext
    private val notificationManager =
        appContext.getSystemService(NotificationManager::class.java)

    init {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "PanaPods BLE Service",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Keep BLE connection alive"
        }
        notificationManager.createNotificationChannel(channel)
    }

    /** 构建常驻通知（供 Service.startForeground 与刷新复用）。 */
    fun buildNotification(text: String): Notification {
        return Notification.Builder(appContext, CHANNEL_ID)
            .setContentTitle("PanaPods")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .build()
    }

    /** 刷新常驻通知文案。 */
    fun update(text: String) {
        notificationManager.notify(NOTIFICATION_ID, buildNotification(text))
    }
}
