package com.panapods.xposed

import com.panapods.utils.PanaLog

import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Constructor
import java.lang.reflect.Method

/**
 * 传统 Xposed API 桥接类（兼容层）
 *
 * 将传统 XposedBridge.hookAllMethods / hookMethod / hookAllConstructors
 * 桥接到 LSPosed 现代 API 的 XposedInterface.hook()。
 *
 * 需在模块加载时调用 XposedBridge.init(xposedInterface) 初始化。
 */
object XposedBridge {

    @Volatile
    private var xi: XposedInterface? = null

    fun init(xposedInterface: XposedInterface) {
        xi = xposedInterface
    }

    fun hookAllMethods(clazz: Class<*>, methodName: String, callback: XC_MethodHook): Set<Method> {
        val hooked = mutableSetOf<Method>()
        val xposed = xi ?: return hooked

        var c: Class<*>? = clazz
        while (c != null) {
            for (method in c.declaredMethods) {
                if (method.name == methodName && method !in hooked) {
                    try {
                        xposed.hook(method).intercept(createHooker(callback))
                        hooked.add(method)
                    } catch (t: Throwable) {
                        PanaLog.e("XposedBridge", "Failed to hook ${clazz.name}.$methodName", t)
                    }
                }
            }
            c = c.superclass
        }
        return hooked
    }

    fun hookMethod(method: Method, callback: XC_MethodHook): Any? {
        val xposed = xi ?: return null
        return xposed.hook(method).intercept(createHooker(callback))
    }

    fun hookAllConstructors(clazz: Class<*>, callback: XC_MethodHook): Set<Constructor<*>> {
        val hooked = mutableSetOf<Constructor<*>>()
        val xposed = xi ?: return hooked

        for (constructor in clazz.declaredConstructors) {
            try {
                xposed.hook(constructor).intercept(createHooker(callback))
                hooked.add(constructor)
            } catch (t: Throwable) {
                PanaLog.e("XposedBridge", "Failed to hook ${clazz.name} constructor", t)
            }
        }
        return hooked
    }

    private fun createHooker(callback: XC_MethodHook): XposedInterface.Hooker {
        return XposedInterface.Hooker { chain ->
            val param = XC_MethodHook.MethodHookParam()
            param.method = chain.executable
            param.thisObject = chain.thisObject
            param.args = chain.args.toTypedArray()

            // 稳定性：Hook 回调中的任何异常都不允许击穿被 Hook 的系统进程。
            // beforeHookedMethod 抛错时吞掉异常并继续执行原方法，保证系统蓝牙/设置不崩溃。
            try {
                callback.beforeHookedMethod(param)
            } catch (t: Throwable) {
                PanaLog.e("XposedBridge",
                    "beforeHookedMethod threw in ${chain.executable}", t)
            }

            if (param.returnEarly) {
                try {
                    callback.afterHookedMethod(param)
                } catch (t: Throwable) {
                    PanaLog.e("XposedBridge",
                        "afterHookedMethod threw in ${chain.executable}", t)
                }
                return@Hooker param.result
            }

            try {
                param.result = chain.proceed(param.args)
            } catch (t: Throwable) {
                param.throwable = t
            }

            try {
                callback.afterHookedMethod(param)
            } catch (t: Throwable) {
                PanaLog.e("XposedBridge",
                    "afterHookedMethod threw in ${chain.executable}", t)
            }

            if (param.throwable != null) {
                throw param.throwable
            }

            param.result
        }
    }
}
