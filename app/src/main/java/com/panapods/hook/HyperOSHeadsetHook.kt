package com.panapods.hook

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.panapods.utils.PanaLog
import com.panapods.xposed.XC_MethodHook
import com.panapods.xposed.XposedBridge
import com.panapods.xposed.XposedHelpers
import com.panapods.bridge.PanaBridge
import com.panapods.bridge.PanaPodsProvider
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

/**
 * HyperOS 系统耳机深度集成 Hook
 *
  * 参考 HuaweiPods / OppoPods 实现思路：
  * 小米 HyperOS 内部有一个私有 AIDL 服务 IMiuiHeadsetService，
  * 系统蓝牙详情页、连接弹窗、超级岛、控制中心都通过它识别小米 TWS 耳机。
 *
  * 本 Hook 让松下/Technics EAH-AZ 系列耳机伪装成小米 TWS，从而：
  * 1. 系统蓝牙设置页显示 Pana 电量和 ANC 状态
  * 2. 连接时弹出小米风格连接动画
  * 3. 控制中心/融合设备中心显示 Pana
  * 4. 支持系统快捷切换 ANC 模式
 *
 * 作用域: com.android.bluetooth (服务端) / com.android.settings (客户端) / com.xiaomi.bluetooth (通知)
 */
object HyperOSHeadsetHook {

    private const val TAG = "PanaPods/HyperOS"

    // HyperOS 私有 AIDL
    private const val DESCRIPTOR = "com.android.bluetooth.ble.app.IMiuiHeadsetService"
    private const val CLS_CALLBACK = "com.android.bluetooth.ble.app.IMiuiHeadsetCallback"
    private const val CLS_HEADSET_SERVICE = "com.android.bluetooth.ble.app.headset.BluetoothHeadsetService"

    // setCommonCommand 已知命令码
    private const val COMMAND_WEAR_STATUS = 103        // 佩戴状态检查
    private const val COMMAND_GET_ANC_MODE = 106       // ANC 模式查询
    private const val COMMAND_GET_BLE_MMA_STATE = 123  // getBleMmaState

    // 已注册的回调：IBinder -> CallbackRegistration
    private val callbacks = ConcurrentHashMap<IBinder, CallbackRegistration>()

        // 当前连接状态
    @Volatile private var currentAddress: String? = null
    @Volatile private var receiverRegistered = false
    @Volatile private var aclReceiverRegistered = false
    @Volatile private var isPolling = false
    private val handler = Handler(Looper.getMainLooper())

    private const val PROVIDER_POLL_INTERVAL_MS = 3000L
    // 广播链路正常时轮询退避到 15s，避免 5 个 Hook 进程各自每 3s 一次 Binder IPC
    private const val PROVIDER_POLL_SLOW_INTERVAL_MS = 15_000L
    private const val BROADCAST_FRESH_WINDOW_MS = 10_000L

    // 最近一次收到 App 状态广播的时间；轮询据此自适应退避。
    @Volatile private var lastStateBroadcastAt = 0L

    private data class CallbackRegistration(
        val callback: Any,
        val address: String
    )

        // ============ 安装入口 ============

    fun install(classLoader: ClassLoader) {
        PanaLog.i(TAG, "Installing HyperOS headset integration hooks")

        try {
            registerStateReceiver(classLoader)
            hookHeadsetService(classLoader)
            hookHeadsetBinderAidl(classLoader)
            registerAclReceiver(classLoader)
                        // 启动时立即从 ContentProvider 拉取一次状态，避免当前地址为空
            refreshStateFromProvider()
            PanaLog.i(TAG, "HyperOS hooks installed successfully for ${getPackageName()}")
        } catch (e: Exception) {
            PanaLog.e(TAG, "Failed to install HyperOS hooks", e)
        }
    }

    private fun getPackageName(): String {
        return runCatching {
            (XposedHelpers.callStaticMethod(
                Class.forName("android.app.ActivityThread"),
                "currentPackageName"
            ) as? String) ?: "unknown"
        }.getOrDefault("unknown")
    }

        // ============ 状态接收器 ============

    private fun registerStateReceiver(classLoader: ClassLoader) {
        PanaLog.d(TAG, "registerStateReceiver called, receiverRegistered=$receiverRegistered")
        if (receiverRegistered) return

        val ctx = try {
            XposedHelpers.callStaticMethod(
                Class.forName("android.app.ActivityThread"),
                "currentApplication"
            ) as? Context
        } catch (e: Exception) {
            PanaLog.e(TAG, "Cannot get application context", e)
            return
        }

        if (ctx == null) {
            PanaLog.w(TAG, "Application context is null, cannot register receiver")
            return
        }

        try {
            val filter = IntentFilter(PanaBridge.ACTION_STATE_UPDATED)
            ctx.registerReceiver(object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    PanaLog.d(TAG, "State receiver onReceive: action=${intent?.action}, " +
                            "L=${intent?.getIntExtra(PanaBridge.EXTRA_LEFT_BATTERY, -1)}, " +
                            "R=${intent?.getIntExtra(PanaBridge.EXTRA_RIGHT_BATTERY, -1)}, " +
                            "C=${intent?.getIntExtra(PanaBridge.EXTRA_CRADLE_BATTERY, -1)}, " +
                            "anc=${intent?.getIntExtra(PanaBridge.EXTRA_ANC_MODE, -1)}, " +
                            "connected=${intent?.getBooleanExtra(PanaBridge.EXTRA_IS_CONNECTED, false)}")
                    // Single receiver per process: update bridge cache first, then
                    // push status to registered callbacks (previously this was two
                    // receivers, so every broadcast was handled twice).
                    // 先校验 state token，拒绝伪造状态广播。
                    if (!PanaBridge.updateCacheFromIntent(intent)) {
                        PanaLog.w(TAG, "Ignored unauthorized state broadcast")
                        return
                    }
                    updateStateAndNotify()
                }
            }, filter, Context.RECEIVER_EXPORTED)
            receiverRegistered = true
            PanaLog.i(TAG, "State receiver registered in ${ctx.packageName}")
        } catch (e: Exception) {
            PanaLog.e(TAG, "Failed to register state receiver", e)
        }
    }

    // ============ ACL 连接事件 ============

    /**
     * 系统级连接体验增强：在 Hook 进程内监听 ACL_CONNECTED/ACL_DISCONNECTED。
     * Pana 连接/断开的瞬间立即从 Provider 拉新状态并推送给已注册的
     * MiuiHeadsetCallback，让系统详情页/弹窗/融合中心第一时间拿到正确数据，
     * 而不是等 3s 轮询或 app 广播。
     */
    private fun registerAclReceiver(classLoader: ClassLoader) {
        if (aclReceiverRegistered) return
        val ctx = try {
            XposedHelpers.callStaticMethod(
                Class.forName("android.app.ActivityThread"),
                "currentApplication"
            ) as? Context
        } catch (e: Exception) {
            PanaLog.e(TAG, "Cannot get application context for ACL receiver", e)
            return
        }
        if (ctx == null) {
            PanaLog.w(TAG, "Application context is null, cannot register ACL receiver")
            return
        }
        try {
            val filter = IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
                addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            }
            ctx.registerReceiver(object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    val device = try {
                        intent?.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                    } catch (_: Throwable) { null } ?: return
                    if (!isPana(device)) return
                    PanaLog.d(TAG, "ACL event for Pana: ${intent?.action} ${device.address}")
                    refreshStateFromProvider()
                    val address = try { device.address } catch (_: Throwable) { null } ?: return
                    pushStatusTo(address)
                }
            }, filter, Context.RECEIVER_EXPORTED)
            aclReceiverRegistered = true
            PanaLog.i(TAG, "ACL receiver registered in ${ctx.packageName}")
        } catch (e: Exception) {
            PanaLog.e(TAG, "Failed to register ACL receiver", e)
        }
    }

    // ============ Hook 1: 蓝牙头戴服务生命周期 ============
    private fun hookHeadsetService(classLoader: ClassLoader) {
        try {
            val serviceClass = XposedHelpers.findClass(CLS_HEADSET_SERVICE, classLoader)

                        // onBind 时注册接收器并 Hook binder
            XposedBridge.hookAllMethods(serviceClass, "onBind",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val context = param.thisObject as? Context
                        registerStateReceiver(context?.classLoader ?: param.thisObject.javaClass.classLoader!!)
                        val binder = param.result
                        if (binder != null) {
                            installBinderHooks(binder.javaClass)
                        }
                    }
                }
            )

                        // onCreate 时也注册接收器
            XposedBridge.hookAllMethods(serviceClass, "onCreate",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val context = param.thisObject as? Context
                        registerStateReceiver(context?.classLoader ?: param.thisObject.javaClass.classLoader!!)
                    }
                }
            )

            PanaLog.i(TAG, "$CLS_HEADSET_SERVICE hooked ✓")
        } catch (e: Exception) {
            PanaLog.w(TAG, "$CLS_HEADSET_SERVICE not found or hook failed: ${e.message}")
        }
    }

    // ============ Hook 2: Headset Binder 方法 ============

    private val hookedBinderClasses = HashSet<String>()

    private fun installBinderHooks(binderClass: Class<*>) {
        val className = binderClass.name
        if (!hookedBinderClasses.add(className)) return

        PanaLog.i(TAG, "Installing binder hooks for $className")

                // checkSupport(BluetoothDevice) -> 返回假支持信息
        hookMethodIfExists(binderClass, "checkSupport",
            arrayOf<Class<*>>(BluetoothDevice::class.java)
        ) { param ->
            val device = param.args[0] as? BluetoothDevice ?: return@hookMethodIfExists
            if (!isPana(device)) return@hookMethodIfExists
            param.result = fakeSupportString()
            param.returnEarly = true
            PanaLog.d(TAG, "checkSupport forced for ${device.address}")
        }

        // isMiTWS(String) / checkIsMiTWS(String) -> true
        listOf("isMiTWS", "checkIsMiTWS").forEach { methodName ->
            hookMethodIfExists(binderClass, methodName, arrayOf<Class<*>>(String::class.java)) { param ->
                val address = param.args[0] as? String ?: return@hookMethodIfExists
                if (!PanaBridge.isCurrentDevice(address)) return@hookMethodIfExists
                param.result = true
                param.returnEarly = true
            }
        }

        // isSupportAudioSwitch(String) -> "1"
        hookMethodIfExists(binderClass, "isSupportAudioSwitch", arrayOf<Class<*>>(String::class.java)) { param ->
            val address = param.args[0] as? String ?: return@hookMethodIfExists
            if (!PanaBridge.isCurrentDevice(address)) return@hookMethodIfExists
            param.result = "1"
            param.returnEarly = true
        }

        // getDeviceInfo(String) -> 假支持信息
        hookMethodIfExists(binderClass, "getDeviceInfo", arrayOf<Class<*>>(String::class.java)) { param ->
            val address = param.args[0] as? String ?: return@hookMethodIfExists
            if (!PanaBridge.isCurrentDevice(address)) return@hookMethodIfExists
            param.result = fakeSupportString()
            param.returnEarly = true
        }

        // getRingFindState(String) -> false
        hookMethodIfExists(binderClass, "getRingFindState", arrayOf<Class<*>>(String::class.java)) { param ->
            val address = param.args[0] as? String ?: return@hookMethodIfExists
            if (!PanaBridge.isCurrentDevice(address)) return@hookMethodIfExists
            param.result = false
            param.returnEarly = true
        }

        // setCommonCommand(int, String, BluetoothDevice) -> 模拟成功
        hookMethodIfExists(binderClass, "setCommonCommand",
            arrayOf<Class<*>>(Int::class.java, String::class.java, BluetoothDevice::class.java)
        ) { param ->
            val device = param.args[2] as? BluetoothDevice ?: return@hookMethodIfExists
            if (!isPana(device)) return@hookMethodIfExists
            val command = param.args[0] as? Int
            val value = param.args[1] as? String
            val result = commonCommandResult(command, value)
            PanaLog.d(TAG, "setCommonCommand($command, value=$value) →$result for Pana")
            param.result = result
            param.returnEarly = true
        }

        // register(IMiuiHeadsetCallback)
        hookCallbackRegister(binderClass, "register")

        // registerCallbackDevice(IMiuiHeadsetCallback, BluetoothDevice)
        hookCallbackRegisterDevice(binderClass, "registerCallbackDevice")

        // unregister(IMiuiHeadsetCallback, BluetoothDevice)
        hookCallbackUnregister(binderClass, "unregister")

        // changeAncMode(int, BluetoothDevice)
        hookMethodIfExists(binderClass, "changeAncMode",
            arrayOf<Class<*>>(Int::class.java, BluetoothDevice::class.java)
        ) { param ->
            val device = param.args[1] as? BluetoothDevice ?: return@hookMethodIfExists
            if (!isPana(device)) return@hookMethodIfExists
            val mode = param.args[0] as? Int ?: return@hookMethodIfExists
            PanaLog.d(TAG, "changeAncMode($mode) intercepted for Pana")
            broadcastAncMode(mode)
            param.result = null
            param.returnEarly = true
        }

        // changeAncLevel(String, BluetoothDevice)
        hookMethodIfExists(binderClass, "changeAncLevel",
            arrayOf<Class<*>>(String::class.java, BluetoothDevice::class.java)
        ) { param ->
            val device = param.args[1] as? BluetoothDevice ?: return@hookMethodIfExists
            if (!isPana(device)) return@hookMethodIfExists
            val level = param.args[0] as? String
            PanaLog.d(TAG, "changeAncLevel($level) intercepted for Pana")
            param.result = null
            param.returnEarly = true
        }
    }

    private fun hookMethodIfExists(
        clazz: Class<*>,
        methodName: String,
        paramTypes: Array<Class<*>>,
        hook: (XC_MethodHook.MethodHookParam) -> Unit
    ) {
        try {
            val method = clazz.getDeclaredMethod(methodName, *paramTypes).apply { isAccessible = true }
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    hook(param)
                }
            })
            PanaLog.d(TAG, "$methodName hooked ✓")
        } catch (e: Exception) {
            PanaLog.d(TAG, "$methodName not found in ${clazz.name}: ${e.message}")
        }
    }

    private fun hookCallbackRegister(binderClass: Class<*>, methodName: String) {
        try {
            val callbackClass = XposedHelpers.findClass(CLS_CALLBACK, binderClass.classLoader!!)
            val method = binderClass.getDeclaredMethod(methodName, callbackClass).apply { isAccessible = true }
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val callback = param.args[0] ?: return
                    val binder = callAsBinder(callback) ?: return
                                        // 先拉取最新状态，确保当前地址和电量不为空
                    refreshStateFromProvider()
                    val address = currentAddress ?: return
                    callbacks[binder] = CallbackRegistration(callback, address)
                    param.result = null
                    param.returnEarly = true
                    PanaLog.d(TAG, "$methodName swallowed callback for $address")
                    pushStatusTo(address)
                    startPollingProvider()
                }
            })
            PanaLog.d(TAG, "$methodName hooked ✓")
        } catch (e: Exception) {
            PanaLog.d(TAG, "$methodName not found: ${e.message}")
        }
    }

    private fun hookCallbackRegisterDevice(binderClass: Class<*>, methodName: String) {
        try {
            val callbackClass = XposedHelpers.findClass(CLS_CALLBACK, binderClass.classLoader!!)
            val method = binderClass.getDeclaredMethod(methodName, callbackClass, BluetoothDevice::class.java)
                .apply { isAccessible = true }
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val callback = param.args[0] ?: return
                    val device = param.args[1] as? BluetoothDevice ?: return
                    if (!isPana(device)) return
                    val binder = callAsBinder(callback) ?: return
                    callbacks[binder] = CallbackRegistration(callback, device.address ?: return)
                    param.result = null
                    param.returnEarly = true
                    PanaLog.d(TAG, "$methodName swallowed callback for ${device.address}")
                    refreshStateFromProvider()
                    pushStatusTo(device.address)
                    startPollingProvider()
                }
            })
            PanaLog.d(TAG, "$methodName hooked ✓")
        } catch (e: Exception) {
            PanaLog.d(TAG, "$methodName not found: ${e.message}")
        }
    }

    private fun hookCallbackUnregister(binderClass: Class<*>, methodName: String) {
        try {
            val callbackClass = XposedHelpers.findClass(CLS_CALLBACK, binderClass.classLoader!!)
            val method = binderClass.getDeclaredMethod(methodName, callbackClass, BluetoothDevice::class.java)
                .apply { isAccessible = true }
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val callback = param.args[0] ?: return
                    val binder = callAsBinder(callback) ?: return
                    callbacks.remove(binder)
                    if (callbacks.isEmpty()) stopPollingProvider()
                    param.result = null
                    param.returnEarly = true
                    PanaLog.d(TAG, "$methodName removed callback")
                }
            })
            PanaLog.d(TAG, "$methodName hooked ✓")
        } catch (e: Exception) {
            PanaLog.d(TAG, "$methodName not found: ${e.message}")
        }
    }

        // ============ Hook 3: AIDL Stub.onTransact 低层级拦截 ============

    private fun hookHeadsetBinderAidl(classLoader: ClassLoader) {
        try {
            val stubClass = XposedHelpers.findClass("$DESCRIPTOR\$Stub", classLoader)
            val onTransact = stubClass.getDeclaredMethod(
                "onTransact",
                Int::class.javaPrimitiveType,
                Parcel::class.java,
                Parcel::class.java,
                Int::class.javaPrimitiveType
            ).apply { isAccessible = true }

            XposedBridge.hookMethod(onTransact, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val code = param.args[0] as? Int ?: return
                    val data = param.args[1] as? Parcel ?: return
                    val reply = param.args[2] as? Parcel ?: return

                    val handled = handleTransact(
                        code, data, reply,
                        param.thisObject?.javaClass?.classLoader
                    )
                    if (handled) {
                        // 已自行写回 reply，必须 returnEarly 跳过原方法；
                        // 否则原 onTransact 会在已写入的 reply 上继续追加数据，
                        // 造成 Parcel 错乱、系统进程 Binder 调用异常。
                        param.result = true
                        param.returnEarly = true
                    }
                }
            })
            PanaLog.i(TAG, "IMiuiHeadsetService.Stub.onTransact hooked ✓")
        } catch (e: Exception) {
            PanaLog.w(TAG, "IMiuiHeadsetService.Stub.onTransact hook failed: ${e.message}")
        }
    }

    @SuppressLint("DiscouragedPrivateApi")
    private fun handleTransact(
        code: Int,
        data: Parcel,
        reply: Parcel,
        classLoader: ClassLoader? = null
    ): Boolean {
        val originalPosition = data.dataPosition()
        return try {
            data.enforceInterface(DESCRIPTOR)
            when (code) {
                1 -> handleCheckSupport(data, reply)
                2 -> handleRegister(data, reply, classLoader)
                3 -> handleUnregister(data)
                9 -> handleAncMode(data, reply)
                10 -> handleAncLevel(data, reply)
                11 -> handleGetDeviceInfo(data, reply)
                14 -> handleSetCommonCommand(data, reply)
                16 -> handleRegisterCallbackDevice(data, reply, classLoader)
                18 -> handleAddressBoolean(data, reply, true)
                19 -> handleAddressBoolean(data, reply, true)
                20 -> handleAddressString(data, reply, "1")
                24 -> handleAddressBoolean(data, reply, false)
                else -> false
            }
        } catch (e: Exception) {
            PanaLog.w(TAG, "handleTransact failed code=$code", e)
            false
        } finally {
            data.setDataPosition(originalPosition)
        }
    }

    private fun handleCheckSupport(data: Parcel, reply: Parcel): Boolean {
        val device = readBluetoothDevice(data) ?: return false
        if (!isPana(device)) return false
        reply.writeNoException()
        reply.writeString(fakeSupportString())
        // 系统详情页/连接弹窗发起 checkSupport 时同步预热并推送状态，
        // 让弹窗第一次渲染就带电量/ANC，而不是等下一次轮询。
        refreshStateFromProvider()
        runCatching { device.address }.getOrNull()?.let { pushStatusTo(it) }
        PanaLog.d(TAG, "onTransact checkSupport forced for ${device.address}")
        return true
    }

    private fun handleRegister(data: Parcel, reply: Parcel, classLoader: ClassLoader? = null): Boolean {
        val callback = readCallbackBinder(data, classLoader) ?: return false
        refreshStateFromProvider()
        val address = currentAddress ?: return false
        val binder = callAsBinder(callback) ?: return false
        callbacks[binder] = CallbackRegistration(callback, address)
        reply.writeNoException()
        pushStatusTo(address)
        startPollingProvider()
        return true
    }

    private fun handleUnregister(data: Parcel): Boolean {
        val binder = data.readStrongBinder() ?: return false
        callbacks.remove(binder)
        if (callbacks.isEmpty()) stopPollingProvider()
        return false // 不拦截，让系统也处理
    }

    private fun handleRegisterCallbackDevice(data: Parcel, reply: Parcel, classLoader: ClassLoader? = null): Boolean {
        val callback = readCallbackBinder(data, classLoader) ?: return false
        val device = readBluetoothDevice(data) ?: return false
        if (!isPana(device)) return false
        val binder = callAsBinder(callback) ?: return false
        callbacks[binder] = CallbackRegistration(callback, device.address)
        reply.writeNoException()
        refreshStateFromProvider()
        pushStatusTo(device.address)
        startPollingProvider()
        return true
    }

    private fun handleAncMode(data: Parcel, reply: Parcel): Boolean {
        val mode = data.readInt()
        val device = readBluetoothDevice(data) ?: return false
        if (!isPana(device)) return false
        PanaLog.d(TAG, "onTransact changeAncMode mode=$mode for ${device.address}")
        broadcastAncMode(mode)
        reply.writeNoException()
        return true
    }

    private fun handleAncLevel(data: Parcel, reply: Parcel): Boolean {
        val level = data.readString()
        val device = readBluetoothDevice(data) ?: return false
        if (!isPana(device)) return false
        PanaLog.d(TAG, "onTransact changeAncLevel level=$level for ${device.address}")
        // TODO: broadcast to app
        reply.writeNoException()
        return true
    }

    private fun handleGetDeviceInfo(data: Parcel, reply: Parcel): Boolean {
        val address = data.readString() ?: return false
        if (!PanaBridge.isCurrentDevice(address)) return false
        reply.writeNoException()
        reply.writeString(fakeSupportString())
        refreshStateFromProvider()
        pushStatusTo(address)
        return true
    }

    private fun handleSetCommonCommand(data: Parcel, reply: Parcel): Boolean {
        val command = data.readInt()
        val value = data.readString()
        val device = readBluetoothDevice(data) ?: return false
        if (!isPana(device)) return false
        reply.writeNoException()
        val result = commonCommandResult(command, value)
        reply.writeString(result)
        PanaLog.d(TAG, "onTransact setCommonCommand cmd=$command value=$value result=$result")
        return true
    }

    private fun handleAddressString(data: Parcel, reply: Parcel, forced: String): Boolean {
        val address = data.readString() ?: return false
        if (!PanaBridge.isCurrentDevice(address)) return false
        reply.writeNoException()
        reply.writeString(forced)
        return true
    }

    private fun handleAddressBoolean(data: Parcel, reply: Parcel, forced: Boolean): Boolean {
        val address = data.readString() ?: return false
        if (!PanaBridge.isCurrentDevice(address)) return false
        reply.writeNoException()
        reply.writeInt(if (forced) 1 else 0)
        return true
    }

        // ============ 状态推送 ============

    private fun updateStateAndNotify() {
        lastStateBroadcastAt = SystemClock.elapsedRealtime()
        val address = PanaBridge.getMacAddress()
        currentAddress = address
        PanaLog.d(TAG, "updateStateAndNotify: address=$address, " +
                "L=${PanaBridge.getLeftBattery()}, R=${PanaBridge.getRightBattery()}, " +
                "C=${PanaBridge.getCradleBattery()}, anc=${PanaBridge.getAncMode()}, " +
                "connected=${PanaBridge.isConnected()}")
        if (address != null) {
            pushStatusTo(address)
        }
    }

    private fun pushStatusTo(address: String) {
        if (callbacks.isEmpty()) {
            PanaLog.d(TAG, "pushStatusTo($address): callbacks empty")
            return
        }

        val payload = buildRefreshPayload()
                // LC3/LE-Audio 模式下系统 callback 可能注册在经典地址或 LC3 地址上，
                // 同时向这两个地址及其地址变体推送，确保 TWS 详情页能收到状态。
        val targetAddresses = buildSet {
            add(address)
            PanaBridge.getLc3MacAddress()?.let { add(it) }
        }
        PanaLog.d(TAG, "pushStatusTo($address): targets=$targetAddresses, registered=${callbacks.values.map { it.address }}")
        handler.post {
            val matched = callbacks.values.filter { reg ->
                targetAddresses.any { it.equals(reg.address, ignoreCase = true) } ||
                        targetAddresses.any { PanaBridge.isAddressSibling(it, reg.address) }
            }
            if (matched.isEmpty()) {
                PanaLog.d(TAG, "pushStatusTo: no callback matched for targets=$targetAddresses")
                return@post
            }
            matched.forEach { reg ->
                                // 向 callback 注册地址以及所有目标地址都推送一次，
                                // TWS 详情页会根据自身期望的地址选择接受哪一次刷新。
                val deliveryAddresses = buildSet {
                    add(reg.address)
                    addAll(targetAddresses)
                }
                deliveryAddresses.forEach { targetAddr ->
                    runCatching {
                        XposedHelpers.callMethod(reg.callback, "refreshStatus", targetAddr, payload)
                        PanaLog.d(TAG, "refreshStatus sent to ${reg.callback} at $targetAddr: $payload")
                    }.onFailure { e ->
                        PanaLog.w(TAG, "refreshStatus failed, removing callback", e)
                        callAsBinder(reg.callback)?.let { callbacks.remove(it) }
                    }
                }
            }
        }
    }

    /**
          * 供 PanaStateReceiver 调用：将当前缓存状态推送到本进程的 MiuiHeadsetCallback。
     */
    @JvmStatic
    fun pushStatusToCurrent() {
        refreshStateFromProvider()
        val address = PanaBridge.getMacAddress()
        if (address.isNullOrBlank()) {
            PanaLog.w(TAG, "pushStatusToCurrent: no current address")
            return
        }
        PanaLog.d(TAG, "pushStatusToCurrent: address=$address")
        pushStatusTo(address)
    }

    /**
          * 从 PanaPodsProvider 拉取最新状态并更新本进程缓存。
          * 用于绕过 Android 14 跨应用广播限制。
     */
    private fun refreshStateFromProvider() {
        val ctx = runCatching {
            XposedHelpers.callStaticMethod(
                Class.forName("android.app.ActivityThread"),
                "currentApplication"
            ) as? Context
        }.getOrNull() ?: return

        runCatching {
            val cursor = ctx.contentResolver.query(
                PanaPodsProvider.CONTENT_URI,
                null, null, null, null
            )
            cursor?.use {
                if (it.moveToFirst()) {
                    val left = it.getInt(it.getColumnIndexOrThrow(PanaPodsProvider.COLUMN_LEFT))
                    val right = it.getInt(it.getColumnIndexOrThrow(PanaPodsProvider.COLUMN_RIGHT))
                    val cradle = it.getInt(it.getColumnIndexOrThrow(PanaPodsProvider.COLUMN_CRADLE))
                    val anc = it.getInt(it.getColumnIndexOrThrow(PanaPodsProvider.COLUMN_ANC))
                    val connected = it.getInt(it.getColumnIndexOrThrow(PanaPodsProvider.COLUMN_CONNECTED)) != 0
                    val name = it.getString(it.getColumnIndexOrThrow(PanaPodsProvider.COLUMN_NAME))
                    val addr = it.getString(it.getColumnIndexOrThrow(PanaPodsProvider.COLUMN_ADDRESS))

                    PanaBridge.publishStateToCache(left, right, cradle, anc, name, addr, connected)
                    // v175：仅连接时保留活动地址 —— 旧逻辑无条件写入，断开后
                    // currentAddress 残留非空，handleRegister/hookCallbackRegister
                    // 的"有地址就劫持"从此永久生效：连别的小米耳机的回调注册
                    // 都被吞掉。断开（或地址为空）时清空，拦截放行原系统逻辑。
                    currentAddress = if (connected && !addr.isNullOrBlank()) addr else null
                    PanaLog.d(TAG, "Provider refresh: L=$left R=$right C=$cradle anc=$anc connected=$connected")
                }
            }
        }.onFailure { e ->
            PanaLog.w(TAG, "refreshStateFromProvider failed: ${e.message}")
        }
    }

    /**
          * 启动 Provider 轮询。当有 MiuiHeadsetCallback 注册时，
          * 每 3 秒从 App 的 Provider 拉取一次最新状态并刷新 UI。
     */
    private fun startPollingProvider() {
        if (isPolling) return
        isPolling = true
        PanaLog.d(TAG, "startPollingProvider")
        pollProviderLoop()
    }

    // v176：轮询 tick 用具名 Runnable——旧实现 stopPollingProvider 里
    // removeCallbacksAndMessages(null) 会把 pushStatusTo 排队的状态推送一并取消
    //（两者共用同一 handler），只应撤掉轮询自己。
    private val pollTick = Runnable { pollProviderLoop() }

    private fun stopPollingProvider() {
        if (!isPolling) return
        isPolling = false
        handler.removeCallbacks(pollTick)
        PanaLog.d(TAG, "stopPollingProvider")
    }

    private fun pollProviderLoop() {
        if (!isPolling) return
        refreshStateFromProvider()
        val address = PanaBridge.getMacAddress()
        if (address != null) {
            pushStatusTo(address)
        }
        // 广播链路正常时退避轮询，降低多进程下的 Binder IPC 开销；
        // 收不到广播超过 10s 说明广播可能被系统拦截，恢复 3s 快轮询兜底。
        val now = SystemClock.elapsedRealtime()
        val delay = if (now - lastStateBroadcastAt < BROADCAST_FRESH_WINDOW_MS) {
            PROVIDER_POLL_SLOW_INTERVAL_MS
        } else {
            PROVIDER_POLL_INTERVAL_MS
        }
        handler.postDelayed(pollTick, delay)
    }

    /**
          * 构造 refreshStatus 的 payload 字符串。
     *
          * MiuiHeadsetFragment.refreshStatus(String address, String csv) 期望一个 16 元素 CSV，
          * [0]左耳 [1]右耳 [2]充电盒 电量，0-100，断开=255；
     * [7] ANC 模式 hex
     * [8] "true"/"false"
     * [11][13][14] = "00"
     */
    private fun buildRefreshPayload(): String {
        val left = formatBattery(PanaBridge.getLeftBattery())
        val right = formatBattery(PanaBridge.getRightBattery())
        val cradle = formatBattery(PanaBridge.getCradleBattery())
        val ancHex = formatAncMode(PanaBridge.getAncMode())

        val values = Array(16) { "00" }
        values[0] = left
        values[1] = right
        values[2] = cradle
        values[7] = ancHex
        values[8] = "true"
        values[11] = "00"
        values[13] = "00"
        values[14] = "00"

        return values.joinToString(",")
    }

    private fun formatBattery(level: Int): String {
        // 只接受 0..100；-1(未知) 与 255(断开) 都输出协议里的断开哨兵值 255，
        // 否则单耳连接时缺失的那只耳会被系统当成 100% 在线显示。
        return PanaBridge.normalizeBatteryOrNull(level)?.toString() ?: "255"
    }

    private fun formatAncMode(mode: Int): String {
                // 0000=关, 01XX=降噪, 02XX=通透/环境声
        return when (mode) {
            0 -> "0000"
            1 -> "0100"  // 降噪
            2 -> "0200"  // 环境声/通透
            else -> "0000"
        }
    }

    // ============ 辅助方法 ============

    private fun fakeSupportString(): String = PanaBridge.MIUI_HEADSET_SUPPORT

    /**
          * 根据 command code 返回模拟的 BLE MMA 响应。
     *
     * 已知 command codes:
          * - [COMMAND_WEAR_STATUS]: 佩戴状态检查 → "2" = 双耳已佩戴
          * - [COMMAND_GET_ANC_MODE]: ANC 模式查询 → 当前 ANC 模式 (0=关, 1=降噪, 2=通透)
          * - [COMMAND_GET_BLE_MMA_STATE]: getBleMmaState → "4" = BLE MMA 已连接
     * - 其他: "1" = 成功
     */
    private fun commonCommandResult(command: Int?, value: String?): String {
        return when (command) {
            COMMAND_WEAR_STATUS -> wearStatusResult()
            COMMAND_GET_ANC_MODE -> PanaBridge.getAncMode().toString()  // 当前 ANC 模式
            COMMAND_GET_BLE_MMA_STATE -> "4"  // BLE MMA 已连接
            else -> "1"  // 默认成功
        }
    }

    /**
     * 佩戴状态检查结果：2=双耳已佩戴, 1=单耳已佩戴, 0=未佩戴。
     * 用左右耳电量是否有效推断佩戴状态；电量未知时维持原返回值 "2"，
     * 避免连接初期误判未佩戴导致系统弹「请连接并佩戴耳机」。
     */
    private fun wearStatusResult(): String {
        val l = PanaBridge.normalizeBatteryOrNull(PanaBridge.getLeftBattery()) != null
        val r = PanaBridge.normalizeBatteryOrNull(PanaBridge.getRightBattery()) != null
        return when {
            l && r -> "2"
            l || r -> "1"
            else -> "2"
        }
    }

    /**
          * 把系统 TWS 详情页触发的 ANC 模式切换转发给 PanaPods App
     *
          * 优先通过 ContentProvider.call 发送（绕过 Android 14 跨应用广播限制），
          * 失败时回退到显式广播。
     *
          * HyperOS 的 ANC mode 映射:
     *   0 -> OFF
     *   1 -> NOISE_CANCELING
          *   2 -> AMBIENT (通透)
          * 与 Pana 的 AncMode 常量一致。
     */
    private fun broadcastAncMode(mode: Int) {
        val ctx = runCatching {
            XposedHelpers.callStaticMethod(
                Class.forName("android.app.ActivityThread"),
                "currentApplication"
            ) as? Context
        }.getOrNull() ?: return
        PanaBridge.sendAncModeToApp(ctx, mode)
    }

    private fun isPana(device: BluetoothDevice?): Boolean {
        if (device == null) return false
        val name = runCatching { device.name ?: device.alias }.getOrNull()
        val address = runCatching { device.address }.getOrNull()
        if (PanaBridge.isPanaDevice(name)) return true
        if (address != null && PanaBridge.isCurrentDevice(address)) return true
        return false
    }

    private fun readBluetoothDevice(data: Parcel): BluetoothDevice? {
        return if (data.readInt() != 0) {
            BluetoothDevice.CREATOR.createFromParcel(data)
        } else null
    }

    private fun readCallbackBinder(data: Parcel, classLoader: ClassLoader? = null): Any? {
        val binder = data.readStrongBinder() ?: return null
        // 使用 Stub 实例所在进程的 ClassLoader，不能传 null（null 只查 bootstrap classloader）。
        val cl: ClassLoader? = classLoader ?: binder.javaClass.classLoader
        if (cl == null) return null
        return runCatching {
            val stubClass = XposedHelpers.findClass("$CLS_CALLBACK\$Stub", cl)
            stubClass.getDeclaredMethod("asInterface", IBinder::class.java)
                .invoke(null, binder)
        }.getOrNull()
    }

    private fun callAsBinder(callback: Any): IBinder? {
        return runCatching {
            XposedHelpers.callMethod(callback, "asBinder") as? IBinder
        }.getOrNull()
    }
}
