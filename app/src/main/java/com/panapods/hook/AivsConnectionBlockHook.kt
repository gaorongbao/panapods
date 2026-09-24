package com.panapods.hook

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Context
import com.panapods.utils.Async
import com.panapods.utils.PanaLog
import com.panapods.xposed.XposedBridge
import com.panapods.xposed.XposedHelpers
import com.panapods.xposed.XC_MethodHook
import com.panapods.bridge.PanaBridge
import java.util.concurrent.ConcurrentHashMap

/**
 * AIVS (com.xiaomi.bluetooth) 连接风暴拦截 Hook。
 *
 * 背景：HyperOS 的 AIVS 蓝牙 SDK 会把 Pana 当作支持 MMA 的设备（云端 mma:true），
 * 每隔几分钟就对耳机做一轮：
 *   BLE connectGatt -> discoverServices -> "AF06 service not Found" -> disconnect
 *   同时通过经典蓝牙尝试 SPP 连接，偶尔挂起直到 ConnectTaskTimeout。
 * 日志证据（lc3_debug*.log）：
 *   W/AIVS: BluetoothEngine:---connect--- device : Technics EAH-AZ100
 *   W/AIVS: BluetoothBle:onServicesDiscovered service not Found---- disconnectBleDevice
 *   W/AIVS: BluetoothBase:-ConnectTaskTimeout- connect timeout, deviceExt : Technics EAH-AZ100
 * 该连接/断开风暴与耳机端主动断联（HCI 0x13 REMOTE_USER_TERMINATED_CONNECTION）高度相关。
 *
 * 本 Hook 仅作用于 com.xiaomi.bluetooth 进程，对 Pana 设备：
 * 1. BluetoothDevice.connectGatt(...) 直接返回 null（阻断 BLE 探测循环）；
 * 2. BluetoothSocket.connect() 直接 returnEarly 变成 no-op（阻断经典 SPP 探测）。
 * 二者对 Pana 均无实际功能（AF06 服务不存在，MMA 永远协商失败），阻断无副作用。
 */
object AivsConnectionBlockHook {

    private const val TAG = "PanaPods/AivsBlock"

    // 已确认的 Pana 地址缓存（本地字符串比较短路，避免每次 connect 都查 device.name 做 Binder IPC）
    private val panaAddresses =
        java.util.Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    // 阻断日志节流：每地址每方法只打一次，避免刷屏
    private val loggedBlocks =
        java.util.Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    fun install(classLoader: ClassLoader) {
        PanaLog.i(TAG, "Installing AIVS connection block hooks...")

        // 注册 Bridge 状态接收器：拿到 app 广播的经典/LC3 地址用于快速匹配
        try {
            val ctx = XposedHelpers.callStaticMethod(
                Class.forName("android.app.ActivityThread"), "currentApplication"
            ) as? Context
            PanaBridge.registerStateReceiver(ctx)
        } catch (e: Throwable) {
            PanaLog.e(TAG, "Cannot register bridge receiver", e)
        }

        // 预热：枚举已配对设备，把 Pana 地址提前登记，避免连接发生时 bridge 缓存为空
        prewarmPanaAddresses()

        hookConnectGatt()
        hookSppConnect()

        PanaLog.i(TAG, "AIVS connection block hooks installed ✓")
    }

    // ============ 设备匹配 ============

    private fun isPana(device: BluetoothDevice?): Boolean {
        if (device == null) return false
        val addr = try { device.address?.uppercase() } catch (_: Throwable) { null }
        if (addr != null && panaAddresses.contains(addr)) return true
        if (addr != null && PanaBridge.isCurrentDevice(addr)) {
            panaAddresses.add(addr)
            return true
        }
        // 名字兜底：一次 Binder IPC 换取识别准确性（connect 是低频事件，可以接受）
        val name = try { device.name ?: device.alias } catch (_: Throwable) { null }
        if (PanaBridge.isPanaDevice(name)) {
            addr?.let { panaAddresses.add(it) }
            return true
        }
        return false
    }

    private fun isPanaSocket(socket: BluetoothSocket?): Boolean {
        if (socket == null) return false
        val device = try { socket.remoteDevice } catch (_: Throwable) { null }
        return isPana(device)
    }

    /** 安装时预热正缓存：枚举已配对设备，把 Pana 地址提前登记。 */
    private fun prewarmPanaAddresses() {
        doPrewarm()
        Async.run("aivs-prewarm") loop@{
            for (delay in longArrayOf(1000, 3000, 6000)) {
                try { Thread.sleep(delay) } catch (_: InterruptedException) { return@loop }
                doPrewarm()
            }
        }
    }

    private fun doPrewarm() {
        try {
            val adapter = BluetoothAdapter.getDefaultAdapter() ?: return
            adapter.bondedDevices?.forEach { d ->
                val n = try { d.name } catch (_: Throwable) { null }
                val addr = try { d.address?.uppercase() } catch (_: Throwable) { null } ?: return@forEach
                if (!PanaBridge.isPanaDevice(n)) return@forEach
                if (panaAddresses.add(addr)) PanaLog.i(TAG, "prewarm Pana addr=$addr name=$n")
            }
            PanaBridge.getMacAddress()?.uppercase()?.let {
                if (panaAddresses.add(it)) PanaLog.i(TAG, "prewarm Pana from bridge mac=$it")
            }
            PanaBridge.getLc3MacAddress()?.uppercase()?.let {
                if (panaAddresses.add(it)) PanaLog.i(TAG, "prewarm Pana from bridge lc3=$it")
            }
        } catch (e: Throwable) {
            PanaLog.w(TAG, "prewarm failed: ${e.message}")
        }
    }

    // ============ Hook 1: 阻断 BLE GATT 探测 ============

    private fun hookConnectGatt() {
        try {
            XposedBridge.hookAllMethods(BluetoothDevice::class.java, "connectGatt",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val device = param.thisObject as? BluetoothDevice ?: return
                        if (!isPana(device)) return
                        val addr = try { device.address } catch (_: Throwable) { "?" }
                        if (loggedBlocks.add("gatt|$addr")) {
                            PanaLog.i(TAG, "BLOCK connectGatt for Pana addr=$addr (AIVS MMA probe)")
                        }
                        // 返回 null 表示连接建立失败；AIVS 原生对 connectGatt 返回值有判空处理
                        param.result = null
                        param.returnEarly = true
                    }
                })
            PanaLog.i(TAG, "BluetoothDevice.connectGatt block hooked ✓")
        } catch (t: Throwable) {
            PanaLog.w(TAG, "hook connectGatt failed: ${t.message}")
        }
    }

    // ============ Hook 2: 阻断经典 SPP 探测 ============

    private fun hookSppConnect() {
        try {
            XposedBridge.hookAllMethods(BluetoothSocket::class.java, "connect",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val socket = param.thisObject as? BluetoothSocket ?: return
                        if (!isPanaSocket(socket)) return
                        val addr = try { socket.remoteDevice?.address } catch (_: Throwable) { "?" }
                        if (loggedBlocks.add("spp|$addr")) {
                            PanaLog.i(TAG, "BLOCK SPP connect for Pana addr=$addr (AIVS MMA probe)")
                        }
                        // 注意：桥接层会吞掉 beforeHookedMethod 抛出的异常，所以不能靠 throw。
                        // connect() 是 void 方法：直接 returnEarly 让连接变成 no-op，
                        // 之后 AIVS 检查 isConnected()/write 时会按连接失败处理。
                        param.result = null
                        param.returnEarly = true
                    }
                })
            PanaLog.i(TAG, "BluetoothSocket.connect block hooked ✓")
        } catch (t: Throwable) {
            PanaLog.w(TAG, "hook BluetoothSocket.connect failed: ${t.message}")
        }
    }
}
