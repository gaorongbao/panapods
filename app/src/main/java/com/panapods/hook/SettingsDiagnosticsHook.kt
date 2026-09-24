package com.panapods.hook

import android.content.Intent
import android.os.Bundle
import android.util.Log
import com.panapods.utils.PanaLog
import com.panapods.xposed.XC_MethodHook
import com.panapods.xposed.XposedBridge
import com.panapods.xposed.XposedHelpers
import java.lang.reflect.Method
import java.util.Enumeration

/**
  * 设置进程诊断 Hook
 *
  * 用于定位 HyperOS 原生 TWS 详情页的实现类。
  * 当用户点击系统设置 → 蓝牙 → Pana 时，打印相关 Activity/Fragment/Intent 信息。
 */
object SettingsDiagnosticsHook {

    private const val TAG = "PanaPods/Diag"

    fun install(classLoader: ClassLoader) {
        PanaLog.i(TAG, "Installing settings diagnostics hooks...")

        hookActivityStart(classLoader)
        hookFragmentLifecycle(classLoader)
        hookBluetoothDeviceDetailsFragment(classLoader)
                // scanSettingsApkClasses(classLoader) // 已确认类名，关闭以避免 logcat 洪水

        PanaLog.i(TAG, "Settings diagnostics hooks installed ✓")
    }

    /**
          * Hook Activity.startActivity，打印所有从 Settings 进程发出的 Intent
     */
    private fun hookActivityStart(classLoader: ClassLoader) {
        try {
            val activityClass = XposedHelpers.findClass("android.app.Activity", classLoader)
            XposedBridge.hookAllMethods(activityClass, "startActivity", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val intent = param.args.firstOrNull { it is Intent } as? Intent ?: return
                    val component = intent.component?.flattenToShortString() ?: intent.action ?: "(implicit)"
                    val extras = intent.extras
                    val extraKeys = extras?.keySet()?.toList() ?: emptyList()
                    val showFragment = extras?.getString(":settings:show_fragment") ?: "(none)"
                    val argsBundle = extras?.getBundle(":settings:show_fragment_args")
                    val argsKeys = argsBundle?.keySet()?.toList() ?: emptyList()
                    PanaLog.i(TAG, "Activity.startActivity →$component | flags=${intent.flags}")
                    PanaLog.i(TAG, "  extras=$extraKeys")
                    PanaLog.i(TAG, "  :settings:show_fragment=$showFragment")
                    PanaLog.i(TAG, "  :settings:show_fragment_args keys=$argsKeys")
                    if (argsBundle != null) {
                        for (key in argsKeys) {
                            try {
                                PanaLog.i(TAG, "    arg[$key]=${argsBundle.get(key)}")
                            } catch (_: Throwable) {}
                        }
                    }
                }
            })
            PanaLog.i(TAG, "Activity.startActivity hooked ✓")
        } catch (t: Throwable) {
            PanaLog.e(TAG, "Failed to hook Activity.startActivity", t)
        }
    }

    /**
          * Hook Fragment 生命周期，打印 Settings 进程里创建的所有 Fragment
     */
    private fun hookFragmentLifecycle(classLoader: ClassLoader) {
        try {
            val fragmentClass = XposedHelpers.findClass("androidx.fragment.app.Fragment", classLoader)
            XposedBridge.hookAllMethods(fragmentClass, "onAttach", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val fragment = param.thisObject ?: return
                    val className = fragment.javaClass.name
                    if (className.startsWith("com.android.settings") ||
                        className.startsWith("com.miui") ||
                        className.contains("bluetooth", ignoreCase = true)
                    ) {
                        val activity = XposedHelpers.callMethod(fragment, "getActivity")
                        val activityName = activity?.javaClass?.name ?: "null"
                        val tag = XposedHelpers.getObjectField(fragment, "mTag") as? String ?: "null"
                        PanaLog.i(TAG, "Fragment.onAttach →$className | activity=$activityName | tag=$tag")
                    }
                }
            })
            PanaLog.i(TAG, "Fragment lifecycle hooked ✓")
        } catch (t: Throwable) {
            PanaLog.e(TAG, "Failed to hook Fragment lifecycle", t)
        }
    }

    /**
          * 专门 Hook 蓝牙详情相关 Fragment，打印参数和调用栈
     */
    private fun hookBluetoothDeviceDetailsFragment(classLoader: ClassLoader) {
        val candidates = listOf(
            "com.android.settings.bluetooth.BluetoothDeviceDetailsFragment",
            "com.android.settings.bluetooth.MiuiBluetoothDeviceDetailsFragment",
            "com.android.settings.bluetooth.DeviceProfilesSettings",
            "com.android.settings.bluetooth.BluetoothDetailsConfigurableFragment",
            "com.android.settings.connecteddevice.ConnectedDeviceDashboardFragment",
            "com.android.settings.bluetooth.AdvancedBluetoothDetailsDialogController"
        )

        for (className in candidates) {
            try {
                val clazz = XposedHelpers.findClass(className, classLoader)
                XposedBridge.hookAllMethods(clazz, "onCreate", object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val args = param.args
                        val bundle = args.firstOrNull { it is Bundle } as? Bundle
                        val arguments = try {
                            XposedHelpers.getObjectField(param.thisObject, "mArguments") as? Bundle
                        } catch (_: Throwable) { null }
                        PanaLog.i(TAG, "=== $className.onCreate ===")
                        PanaLog.i(TAG, "savedInstanceState=${bundle?.keySet()?.associateWith { bundle.get(it) }}")
                        PanaLog.i(TAG, "arguments=${arguments?.keySet()?.associateWith { arguments.get(it) }}")
                        PanaLog.i(TAG, "stack=${Log.getStackTraceString(Throwable())}")
                        if (className == "com.android.settings.bluetooth.DeviceProfilesSettings") {
                            inspectDeviceProfilesSettings(param.thisObject)
                        }
                    }
                })
                XposedBridge.hookAllMethods(clazz, "onResume", object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val fragment = param.thisObject
                        val activity = XposedHelpers.callMethod(fragment, "getActivity")
                        val parent = XposedHelpers.callMethod(fragment, "getParentFragment")
                        PanaLog.i(TAG, "=== $className.onResume ===")
                        PanaLog.i(TAG, "activity=${activity?.javaClass?.name}")
                        PanaLog.i(TAG, "parentFragment=${parent?.javaClass?.name}")
                    }
                })
                PanaLog.i(TAG, "$className hooked for diagnosis ✓")
            } catch (_: Throwable) {
                PanaLog.d(TAG, "$className not available for diagnosis")
            }
        }
    }

    /**
          * 检查 DeviceProfilesSettings 类的方法，找出可能控制 TWS UI 的开关
     */
    private fun inspectDeviceProfilesSettings(fragment: Any) {
        try {
            val clazz = fragment.javaClass
            PanaLog.i(TAG, "Inspecting ${clazz.name} methods...")
            val methods = clazz.declaredMethods
            val keywords = listOf("xiaomi", "mi", "tws", "headset", "earbud", "bud", "audio", "device", "type", "is")
            val interesting = methods.filter { m ->
                val lower = m.name.lowercase()
                keywords.any { lower.contains(it) }
            }.sortedBy { it.name }
            PanaLog.i(TAG, "Found ${interesting.size} interesting methods in ${clazz.name}:")
            for (m in interesting) {
                PanaLog.i(TAG, "  ${m.returnType.simpleName} ${m.name}(${m.parameterTypes.joinToString(",") { it.simpleName }})")
            }
                        // Hook 所有返回 boolean 且名字包含这些关键词的方法，打印调用结果
            hookBooleanMethods(clazz, interesting.filter { it.returnType == Boolean::class.javaPrimitiveType })
        } catch (t: Throwable) {
            PanaLog.e(TAG, "Failed to inspect DeviceProfilesSettings", t)
        }
    }

    private fun hookBooleanMethods(clazz: Class<*>, methods: List<Method>) {
        for (m in methods) {
            try {
                XposedBridge.hookMethod(m, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val result = param.result
                        PanaLog.i(TAG, "BOOL ${clazz.simpleName}.${m.name}() →$result")
                    }
                })
            } catch (t: Throwable) {
                PanaLog.d(TAG, "Failed to hook ${m.name}: ${t.message}")
            }
        }
    }

}
