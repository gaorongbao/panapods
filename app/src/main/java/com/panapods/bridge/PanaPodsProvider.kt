package com.panapods.bridge

import com.panapods.headphones.AncMode

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import com.panapods.utils.PanaLog
import com.panapods.ble.PanaBleService

/**
 * 跨进程状态共享 ContentProvider
 *
  * 系统进程（com.android.bluetooth / com.android.settings / com.miui.contentcatcher）
  * 中的 Xposed Hook 通过查询此 Provider 获取最新耳机状态，
  * 避免依赖可能被 Android 14 限制的跨应用广播。
 *
  * v93 升级：权限白名单机制，仅可信进程可访问
 */
class PanaPodsProvider : ContentProvider() {

    companion object {
        private const val TAG = "PanaPodsProvider"

        const val AUTHORITY = "com.panapods.provider"
        const val PATH_STATE = "state"
        val CONTENT_URI: Uri = Uri.parse("content://$AUTHORITY/$PATH_STATE")

        const val COLUMN_LEFT = "left_battery"
        const val COLUMN_RIGHT = "right_battery"
        const val COLUMN_CRADLE = "cradle_battery"
        const val COLUMN_ANC = "anc_mode"
        const val COLUMN_CONNECTED = "is_connected"
        const val COLUMN_NAME = "device_name"
        const val COLUMN_ADDRESS = "mac_address"

        // ContentProvider.call 方法名：设置 ANC 模式
        const val METHOD_SET_ANC_MODE = "setAncMode"
        const val EXTRA_MODE = "mode"

                // v93：诊断方法
        const val METHOD_GET_CACHE_STATS = "getCacheStats"

        // v93：权限白名单
        private val TRUSTED_PACKAGES = setOf(
            "com.milink.service",
            "com.android.bluetooth",
            "com.android.settings",
            "com.android.systemui",        // v101 新增：SystemUI 融合中心卡片渲染进程
            "com.miui.contentcatcher",
            "com.xiaomi.bluetooth",      // v95 新增：小米蓝牙设置 App
            "com.panapods"               // 自身 App
        )
    }

    override fun onCreate(): Boolean = true

    /**
          * v93：权限检查（白名单机制）
     *
          * 系统进程可以调用，不是每个 APP 都能查询。
          * 提高 Provider 门槛以避免融合中心控制中心每帧多次调用。
     */
    private fun checkCallerPermission(): Boolean {
        val callingPackage = callingPackage ?: return false
        val allowed = TRUSTED_PACKAGES.contains(callingPackage)
        if (!allowed) {
            PanaLog.w(TAG, "Query denied for untrusted package: $callingPackage")
        }
        return allowed
    }

    override fun query(
        uri: Uri,
        projection: Array<String>?,
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String?
    ): Cursor? {
        // v93: 权限检查
        if (!checkCallerPermission()) return null

        if (uri.lastPathSegment != PATH_STATE) return null

        // 从 BLE Service 获取实时状态
        val state = PanaBleService.peekState()
        // peekState() only returns null when the service is not running. The service
        // always holds a HeadphoneState object, so "connected" must come from the
        // service's actual connection flag instead of state != null.
        val connected = PanaBleService.isBleConnected()

        PanaLog.d(TAG, "query: L=${state?.leftBattery} R=${state?.rightBattery} " +
                "C=${state?.cradleBattery} anc=${state?.outsideCtrl} connected=$connected " +
                "addr=${state?.macAddress}")

        val cursor = MatrixCursor(arrayOf(
            COLUMN_LEFT, COLUMN_RIGHT, COLUMN_CRADLE,
            COLUMN_ANC, COLUMN_CONNECTED, COLUMN_NAME, COLUMN_ADDRESS
        ))
                // 仅返回已从耳机响应或用户操作得到的已知模式，-1 表示尚未读取
        val lastAnc = PanaBleService.getLastAncMode()
        val ancValue = if (AncMode.isValid(lastAnc)) lastAnc else -1
        cursor.addRow(arrayOf<Any?>(
            state?.leftBattery ?: -1,
            state?.rightBattery ?: -1,
            state?.cradleBattery ?: -1,
            ancValue,
            if (connected) 1 else 0,
            state?.deviceName ?: PanaBridge.PANA_DISPLAY_NAME,
            state?.macAddress ?: ""
        ))
        return cursor
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        // v93：权限检查
        if (!checkCallerPermission()) return null

        when (method) {
            METHOD_SET_ANC_MODE -> {
                val mode = arg?.toIntOrNull() ?: extras?.getInt(EXTRA_MODE, -1) ?: -1
                if (!AncMode.isValid(mode)) {
                    PanaLog.w(TAG, "call $METHOD_SET_ANC_MODE: invalid mode=$mode")
                    return null
                }
                PanaLog.d(TAG, "call $METHOD_SET_ANC_MODE mode=$mode")
                PanaBleService.setAncModeFromProvider(mode)
                                // v93：上报本地 ANC 状态（用户点击后立即生效）
                PanaBridge.setCurrentAncMode(mode)
                // 返回非 null Bundle，便于调用方确认 Provider 路径真正成功。
                return Bundle().apply { putBoolean("ok", true) }
            }
                        // v93：诊断方法——返回地址缓存命中率
            METHOD_GET_CACHE_STATS -> {
                PanaLog.d(TAG, "call $METHOD_GET_CACHE_STATS")
                val stats = PanaBridge.getCacheStats()
                return Bundle().apply {
                    stats.forEach { (k, v) -> putString(k, v.toString()) }
                }
            }
                        // v95.4：日志开关查询（Hook 进程启动时调用一次）
            "get_log_enabled" -> {
                return Bundle().apply {
                    putBoolean("enabled", com.panapods.utils.PanaLog.enabled)
                }
            }
            else -> PanaLog.d(TAG, "call unknown method=$method")
        }
        return null
    }

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?): Int = 0
}
