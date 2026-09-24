package com.panapods.bridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.panapods.utils.PanaLog
import com.panapods.hook.HyperOSHeadsetHook

/**
 * 显式 exported 接收器，接收 PanaPods App 发来的状态广播。
 *
 * 由于 Android 14 对跨应用隐式广播的限制，动态注册的 exported receiver
 * 可能收不到 App 的普通广播。改用 manifest 声明的显式 receiver 后，
 * 每个注入了本 APK 的系统进程（com.android.bluetooth / com.android.settings /
 * com.miui.contentcatcher）都能稳定收到状态并刷新系统 UI。
 */
class PanaStateReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "PanaStateReceiver"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != PanaBridge.ACTION_STATE_UPDATED) return

        PanaLog.d(TAG, "State received in ${context.packageName}")

        // 更新本进程缓存（校验 state token，未授权广播直接丢弃）
        if (!PanaBridge.updateCacheFromIntent(intent)) {
            PanaLog.w(TAG, "Ignored unauthorized state broadcast")
            return
        }

        // 通知 HyperOSHeadsetHook 刷新已注册的 MiuiHeadsetCallback
        runCatching {
            HyperOSHeadsetHook.pushStatusToCurrent()
            PanaLog.d(TAG, "pushStatusToCurrent called")
        }.onFailure { e ->
            PanaLog.w(TAG, "pushStatusToCurrent failed: ${e.message}")
        }
    }
}
