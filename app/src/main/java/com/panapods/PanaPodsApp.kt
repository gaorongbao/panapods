package com.panapods

import android.app.Application
import com.panapods.config.ConfigManager
import com.panapods.utils.PanaLog
import com.panapods.receivers.KeepAliveScheduler

/**
 * PanaPods Application
 *
 * 全局单例，初始化:
 * - ConfigManager (用户配置)
 * - BluetoothEventReceiver (蓝牙事件监听)
 */
class PanaPodsApp : Application() {

    lateinit var configManager: ConfigManager
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        configManager = ConfigManager(this)
        PanaLog.init(this)
        // Root 保活：进程启动时若开关已开，恢复周期拉起 alarm。
        if (configManager.rootKeepAlive) {
            KeepAliveScheduler.schedule(this)
        }
    }

    companion object {
        lateinit var instance: PanaPodsApp
            private set
    }
}
