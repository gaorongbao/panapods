package com.panapods.config

import android.content.Context
import android.content.SharedPreferences

/**
 * 配置管理器
 *
 * 持久化存储用户设置和耳机连接信息。
 * 使用 SharedPreferences (可后续迁移到 DataStore)。
 */
class ConfigManager(context: Context) {

    companion object {
        private const val PREFS_NAME = "panapods_config"
        private const val KEY_LAST_ADDRESS = "last_bt_address"
        private const val KEY_LAST_DEVICE_NAME = "last_device_name"
        private const val KEY_AUTO_CONNECT = "auto_connect"
        private const val KEY_HIDE_FROM_RECENTS = "hide_from_recents"
        private const val KEY_ROOT_KEEPALIVE = "root_keepalive"
        private const val KEY_SWAP_EAR_SIDES = "swap_ear_sides"
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ============ 蓝牙地址 ============

    var lastBtAddress: String?
        get() = prefs.getString(KEY_LAST_ADDRESS, null)
        set(value) = prefs.edit().putString(KEY_LAST_ADDRESS, value).apply()

    var lastDeviceName: String?
        get() = prefs.getString(KEY_LAST_DEVICE_NAME, null)
        set(value) = prefs.edit().putString(KEY_LAST_DEVICE_NAME, value).apply()

    // ============ 自动连接 ============

    var autoConnect: Boolean
        get() = prefs.getBoolean(KEY_AUTO_CONNECT, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_CONNECT, value).apply()

    // ============ 后台隐藏 / 保活 ============

    /** 开启后 App 进入后台时从最近任务列表中移除（默认关闭，需用户手动开启）。 */
    var hideFromRecents: Boolean
        get() = prefs.getBoolean(KEY_HIDE_FROM_RECENTS, false)
        set(value) = prefs.edit().putBoolean(KEY_HIDE_FROM_RECENTS, value).apply()

    /** 开启后使用 root 周期拉起 BLE 服务并加入电池白名单（默认关闭，需用户手动开启）。 */
    var rootKeepAlive: Boolean
        get() = prefs.getBoolean(KEY_ROOT_KEEPALIVE, false)
        set(value) = prefs.edit().putBoolean(KEY_ROOT_KEEPALIVE, value).apply()

    // ============ 电量显示 ============

    /**
     * v169：显示前把左右耳电量对调。
     *
     * 主耳（agent）的物理侧是机型/固件约定，代码里的默认值是实测得出的
     * （EAH-AZ100 主耳=右耳）。万一某些批次约定相反，用户开这个开关即可纠正，
     * 不必重新编译。默认关闭。
     */
    var swapEarSides: Boolean
        get() = prefs.getBoolean(KEY_SWAP_EAR_SIDES, false)
        set(value) = prefs.edit().putBoolean(KEY_SWAP_EAR_SIDES, value).apply()
}
