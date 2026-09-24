package com.panapods.hook

import android.bluetooth.BluetoothDevice
import android.content.Intent
import com.panapods.utils.PanaLog
import com.panapods.xposed.XC_MethodHook
import com.panapods.xposed.XposedBridge
import com.panapods.xposed.XposedHelpers
import com.panapods.bridge.PanaBridge

/**
  * 欺骗 MiuiBluetoothSettings 把 Pana 识别为支持小米耳机详情页的设备
 *
  * HyperOS 在蓝牙列表点击设备时，会通过 MiuiBluetoothSettings.checkStartMiuiHeadset() 判断
  * 是否应该打开 MiuiHeadsetActivity（小米原生 TWS 详情页）。
 *
  * 反编译后发现该方法内部会构造 Intent 并调用 startActivityForResult；仅仅把返回值改成 true
  * 并不能真正启动 Activity，反而会让点击处理提前返回，导致“第三方界面也无法进入”。
  * 因此对 Pana 直接构造并启动 MiuiHeadsetActivity，再把返回值设为 true，跳过原方法。
 */
object MiuiBluetoothSettingsTwsHook {

    private const val TAG = "PanaPods/TWS"

    /**
          * 使用一个已知能通过 HeadsetIDConstants.checkSupport() 的小米 TWS ID。
          * 01010600 在 Settings.apk 中有完整图片资源，且被 HyperOS 识别为支持 ANC UI 切换，
          * 后面 24 位 bit-mask 控制功能开关：bit-16 置 1 表示支持 ANC。
     */
    private const val Pana_MIUI_HEADSET_SUPPORT = PanaBridge.MIUI_HEADSET_SUPPORT

    fun install(classLoader: ClassLoader) {
        PanaLog.i(TAG, "Installing MiuiBluetoothSettings TWS spoof hooks...")

                // BluetoothSettings.checkStartMiuiHeadset() 在基类里直接返回 false，
                // 真正被调用的是 MiuiBluetoothSettings 的重写方法，所以只 hook 后者。
        hookCheckStartMiuiHeadset(classLoader)

        PanaLog.i(TAG, "MiuiBluetoothSettings TWS spoof hooks installed ✓")
    }

    /**
     * Hook MiuiBluetoothSettings.checkStartMiuiHeadset(CachedBluetoothDevice)
          * 对 Pana 直接启动 MiuiHeadsetActivity 并返回 true。
     */
    private fun hookCheckStartMiuiHeadset(classLoader: ClassLoader) {
        try {
            val clazz = XposedHelpers.findClass(
                "com.android.settings.bluetooth.MiuiBluetoothSettings",
                classLoader
            )
            XposedBridge.hookAllMethods(clazz, "checkStartMiuiHeadset", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val cachedDevice = param.args.getOrNull(0)
                    if (cachedDevice == null) {
                        PanaLog.i(TAG, "checkStartMiuiHeadset called: args=null (skipping)")
                        return
                    }
                    val device = runCatching {
                        XposedHelpers.callMethod(cachedDevice, "getDevice") as? BluetoothDevice
                    }.getOrNull()
                    val name = device?.let { runCatching { it.name }.getOrNull() }
                        ?: runCatching { XposedHelpers.callMethod(cachedDevice, "getName") as? String }.getOrNull()
                    val address = device?.let { runCatching { it.address }.getOrNull() }
                        ?: runCatching { XposedHelpers.callMethod(cachedDevice, "getAddress") as? String }.getOrNull()

                    val isPana = PanaBridge.isPanaDevice(name) ||
                        (address != null && PanaBridge.isCurrentDevice(address))
                    PanaLog.i(TAG, "checkStartMiuiHeadset called: name=$name addr=$address isPana=$isPana thisClass=${param.thisObject?.javaClass?.name}")

                    if (!isPana) return

                                        // 防止循环: 如果当前已经在 MiuiHeadsetActivity 中, 不要再启动一次
                    val thisClassName = param.thisObject?.javaClass?.name ?: ""
                    if (thisClassName.contains("MiuiHeadsetActivity")) {
                        PanaLog.i(TAG, "checkStartMiuiHeadset: already in MiuiHeadsetActivity, skipping to prevent loop")
                        param.result = true
                        param.returnEarly = true
                        return
                    }

                    PanaLog.i(TAG, "checkStartMiuiHeadset(${name ?: address}) launching MiuiHeadsetActivity for Pana")

                    val intent = Intent("miui.bluetooth.action.HEADSET_SETTINGS").apply {
                        putExtra(BluetoothDevice.EXTRA_DEVICE, device)
                        putExtra("MIUI_HEADSET_SUPPORT", Pana_MIUI_HEADSET_SUPPORT)
                        putExtra("COME_FROM", "MIUI_BLUETOOTH_SETTINGS")
                        addCategory(Intent.CATEGORY_DEFAULT)
                        // v175：去掉 NEW_TASK|CLEAR_TASK ——
                        // ① FLAG_ACTIVITY_NEW_TASK 会让下面的 startActivityForResult
                        //    永远收不到结果（系统规定 NEW_TASK 启动的 Activity 不回投结果）；
                        // ② FLAG_ACTIVITY_CLEAR_TASK 会清空 Settings 的任务栈，
                        //    从 TWS 详情页按返回直接落到桌面，而不是回到蓝牙列表。
                        // 同 task 内启动即可，无需任何 flag。
                    }

                    runCatching {
                        XposedHelpers.callMethod(param.thisObject, "startActivityForResult", intent, 0)
                        PanaLog.i(TAG, "startActivityForResult launched MiuiHeadsetActivity ✓")
                    }.onFailure { e1 ->
                        PanaLog.w(TAG, "startActivityForResult failed: $e1, trying startActivity")
                        runCatching {
                            XposedHelpers.callMethod(param.thisObject, "startActivity", intent)
                            PanaLog.i(TAG, "startActivity launched MiuiHeadsetActivity ✓")
                        }.onFailure { e2 ->
                            PanaLog.e(TAG, "Failed to launch MiuiHeadsetActivity", e2)
                        }
                    }

                                        // 跳过原方法，直接向点击处理返回已启动 TWS 详情页
                                        // 必须设置 returnEarly=true，否则原方法会继续执行并返回 false，
                                        // 导致调用方继续启动标准 SubSettings 详情页覆盖 TWS 页面
                    param.result = true
                    param.returnEarly = true
                }
            })
            PanaLog.i(TAG, "MiuiBluetoothSettings.checkStartMiuiHeadset() hooked ✓")
        } catch (t: Throwable) {
            PanaLog.e(TAG, "Failed to hook MiuiBluetoothSettings.checkStartMiuiHeadset", t)
        }
    }
}
