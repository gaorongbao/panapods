package com.panapods.hook

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.panapods.utils.PanaLog
import com.panapods.xposed.XposedBridge
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam

/**
 * Xposed module entry (LSPosed modern API)
 */
class HookEntry : XposedModule() {

    companion object {
        const val TAG = "PanaPods"
        const val PKG_BLUETOOTH = "com.android.bluetooth"
        const val PKG_SETTINGS = "com.android.settings"
        const val PKG_MILINK = "com.milink.service"
        const val PKG_SYSTEMUI = "com.android.systemui"
        const val PKG_XIAOMI_BT = "com.xiaomi.bluetooth"
    }

    private var processName: String = ""

    @Volatile
    private var settingsHooksInstalled = false

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        processName = param.processName
        XposedBridge.init(this)
        // v175：先把日志镜像挂到 LSPosed 模块日志，后续所有 PanaLog 输出
        // （w/e 始终、d/i/v 开关开启时）都能在 LSPosed Manager 日志页看到。
        attachLspLogger()
        PanaLog.i(TAG, "onModuleLoaded: process=$processName isSystemServer=${param.isSystemServer}")
    }

    /**
     * v175：挂接 LSPosed 日志出口。
     * 现代 API XposedInterface.log(priority, tag, msg)，priority 与
     * android.util.Log 常量一致（PanaLog 已按此传入）。
     * 挂接/调用失败仅降级为 logcat + 文件输出，不影响 Hook 逻辑。
     */
    private fun attachLspLogger() {
        try {
            PanaLog.attachLspLogger { prio, tag, msg -> log(prio, tag, msg) }
            PanaLog.i(TAG, "LSPosed log mirror attached ✓")
        } catch (t: Throwable) {
            PanaLog.w(TAG, "attach LSPosed log mirror failed: ${t.message}")
        }
    }

    override fun onPackageLoaded(param: PackageLoadedParam) {
        // contentcatcher 进程不在 Provider 白名单中，无法查询日志开关，跳过 initLogSwitch
        val skipLogInit = param.packageName == "com.miui.contentcatcher" || processName == "com.miui.contentcatcher"
        if (!skipLogInit) initLogSwitch()
        try {
            val pkg = param.packageName
            val defaultClassLoader = param.defaultClassLoader
            PanaLog.i(TAG, "onPackageLoaded: pkg=$pkg process=$processName")

            when {
                pkg == PKG_BLUETOOTH || processName == PKG_BLUETOOTH -> {
                    PanaLog.i(TAG, "[$PKG_BLUETOOTH] Installing HyperOS integration")
                    HyperOSHeadsetHook.install(defaultClassLoader)
                }
                pkg == PKG_SETTINGS || processName == PKG_SETTINGS -> {
                    PanaLog.i(TAG, "[$PKG_SETTINGS] Installing settings hooks (direct)")
                    installSettingsHooks(defaultClassLoader)
                }
                pkg == "com.miui.contentcatcher" || processName == "com.miui.contentcatcher" -> {
                    PanaLog.i(TAG, "[contentcatcher] Deferring settings hooks to main thread")
                    Handler(Looper.getMainLooper()).post {
                        try {
                            val app = Class.forName("android.app.ActivityThread")
                                .getMethod("currentApplication")
                                .invoke(null) as? Context
                            val classLoader = getSettingsClassLoader(app, defaultClassLoader)
                            PanaLog.i(TAG, "[contentcatcher] Installing settings hooks (deferred) classLoader=$classLoader")
                            installSettingsHooks(classLoader)
                        } catch (t: Throwable) {
                            PanaLog.e(TAG, "Failed to install deferred settings hooks", t)
                        }
                    }
                }
                pkg == PKG_MILINK || processName == PKG_MILINK -> {
                    PanaLog.i(TAG, "[$PKG_MILINK] Installing MiLink hooks")
                    MiLinkServiceHook.install(defaultClassLoader)
                    // 融合中心卡片图：系统 fallback 的 circulate_* 头戴/索尼样式图替换为 Pana 图。
                    MiLinkCardArtHook.install(defaultClassLoader)
                }
                pkg == PKG_SYSTEMUI || processName == PKG_SYSTEMUI -> {
                    // SystemUI 渲染融合中心耳机卡片，ANC/音量区块的 gating 在 SystemUI 本地执行。
                    // 必须在 SystemUI 里也安装 MiLink 伪装（checkIsMiTWS/getDeviceId/HeadsetInfo getter），
                    // 否则 milink 侧数据再对，卡片渲染时仍可能回退到最简版。
                    PanaLog.i(TAG, "[$PKG_SYSTEMUI] Installing MiLink hooks (SystemUI)")
                    MiLinkServiceHook.install(defaultClassLoader)
                    // SystemUI 侧同样替换融合中心卡片图。
                    MiLinkCardArtHook.install(defaultClassLoader)
                }
                pkg == PKG_XIAOMI_BT || processName == PKG_XIAOMI_BT -> {
                    PanaLog.i(TAG, "[$PKG_XIAOMI_BT] Installing Xiaomi bluetooth hooks")
                    HyperOSHeadsetHook.install(defaultClassLoader)
                    // AIVS 对 Pana 反复 connectGatt/SPP 探测（AF06 永远找不到），
                    // 连接风暴会诱发耳机端主动断联，这里阻断对 Pana 的探测连接。
                    AivsConnectionBlockHook.install(defaultClassLoader)
                }
            }
        } catch (t: Throwable) {
            PanaLog.e(TAG, "Failed to handle package load: pkg=${param.packageName} process=$processName", t)
        }
    }

    private fun installSettingsHooks(classLoader: ClassLoader) {
        if (settingsHooksInstalled) {
            PanaLog.i(TAG, "Settings hooks already installed, skipping")
            return
        }
        settingsHooksInstalled = true
        SettingsHeadsetHook.install(classLoader)
        DeviceProfilesTwsHook.install(classLoader)
        MiuiBluetoothSettingsTwsHook.install(classLoader)
        // Diagnostics hooks are reflection/stack-trace heavy; install them only
        // when protocol logging is enabled so release builds stay lean.
        if (PanaLog.enabled) {
            SettingsDiagnosticsHook.install(classLoader)
        }
    }

    private fun getSettingsClassLoader(app: Context?, fallback: ClassLoader): ClassLoader {
        if (app == null) {
            PanaLog.w(TAG, "Application not available yet, using default classloader")
            return fallback
        }
        return try {
            val ctx = app.createPackageContext(PKG_SETTINGS, Context.CONTEXT_INCLUDE_CODE)
            PanaLog.i(TAG, "Got Settings classLoader via createPackageContext: ${ctx.classLoader}")
            ctx.classLoader
        } catch (e: Throwable) {
            PanaLog.w(TAG, "createPackageContext failed, using app classloader: ${e.message}")
            app.classLoader
        }
    }

    @Volatile private var logSwitchInitialized = false
    private fun initLogSwitch() {
        if (logSwitchInitialized) return
        logSwitchInitialized = true
        // v110：onPackageLoaded 阶段 currentApplication() 常为 null（Application 尚未创建），
        // 旧逻辑直接 return，导致 Hook 进程永远读不到日志开关（PanaLog 恒为关）。
        // 改为延迟重试：等 Application 就绪后再查 Provider，最多重试 5 次。
        val readOnce = Runnable {
            try {
                val app = Class.forName("android.app.ActivityThread")
                    .getMethod("currentApplication")
                    .invoke(null) as? Context ?: return@Runnable
                val bundle = app.contentResolver.call(
                    android.net.Uri.parse("content://com.panapods.provider"),
                    "get_log_enabled", null, null
                )
                if (bundle != null) {
                    PanaLog.enabled = bundle.getBoolean("enabled", false)
                    // v176：删掉此处冗余的 logSwitchInitialized = true——
                    // 该标志在 initLogSwitch() 入口就已置位，真正的重试节奏由下面的
                    // attempt 计数控制，二次赋值毫无作用还容易误导阅读。
                }
            } catch (_: Throwable) {
                // Provider unavailable, retry later
            }
        }
        val handler = Handler(Looper.getMainLooper())
        var attempt = 0
        val retry = object : Runnable {
            override fun run() {
                if (PanaLog.enabled || attempt >= 5) return
                attempt++
                readOnce.run()
                handler.postDelayed(this, 2000L * attempt)
            }
        }
        readOnce.run()
        handler.postDelayed(retry, 2000L)
    }
}
