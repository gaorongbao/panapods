package com.panapods.receivers

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.panapods.utils.PanaLog
import com.panapods.bridge.PanaBridge
import com.panapods.config.ConfigManager
import com.panapods.ble.PanaBleService

/**
  * 蓝牙事件广播接收器
 *
 * 监听:
  * 1. 蓝牙连接/断开事件 —— 自动识别 Pana 并触发连接
  * 2. ACL 断开事件 —— 自动重连
  * 3. 蓝牙状态变化
 */
class BluetoothEventReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "PanaPods/BtEventReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            BluetoothDevice.ACTION_ACL_CONNECTED -> {
                val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                handleDeviceConnected(context, device)
            }
            BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                handleDeviceDisconnected(context, device)
            }
            BluetoothAdapter.ACTION_STATE_CHANGED -> {
                val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.STATE_OFF)
                if (state == BluetoothAdapter.STATE_ON) {
                    PanaLog.d(TAG, "Bluetooth turned on, checking for auto-connect")
                    tryAutoConnect(context)
                }
            }
        }
    }

    private fun handleDeviceConnected(context: Context, device: BluetoothDevice?) {
        if (device == null) return
        val name = try { device.name } catch (e: Exception) { null }
        PanaLog.d(TAG, "ACL Connected: ${device.address} ($name)")

                // v89: 如果 device.name 为 null，尝试从缓存的上次配对设备判断
        val isPanaDevice = if (name != null) {
            PanaBridge.isPanaDevice(name)
        } else {
                        // 备用方案：检查这个 MAC 地址是否就是上次保存的 Pana 地址
            val config = ConfigManager(context)
            device.address.equals(config.lastBtAddress, ignoreCase = true)
        }

        if (isPanaDevice) {
            PanaLog.i(TAG, "Detected Pana connection: ${device.address}")
            val config = ConfigManager(context)
            config.lastBtAddress = device.address
            config.lastDeviceName = name ?: config.lastDeviceName

                        // 启动 BLE 服务连接 Pana 的 GATT
            startConnect(context, device.address)
        }
    }

    private fun handleDeviceDisconnected(context: Context, device: BluetoothDevice?) {
        if (device == null) return
        val name = try { device.name } catch (e: Exception) { null }
        PanaLog.d(TAG, "ACL Disconnected: ${device.address} ($name)")

        // 与 handleDeviceConnected 保持一致：name 为 null 时用已保存地址兜底判断。
        val isPanaDevice = if (name != null) {
            PanaBridge.isPanaDevice(name)
        } else {
            val config = ConfigManager(context)
            device.address.equals(config.lastBtAddress, ignoreCase = true)
        }

        if (isPanaDevice) {
            PanaLog.i(TAG, "Pana disconnected")
            val config = ConfigManager(context)
            if (config.autoConnect) {
                PanaLog.d(TAG, "Auto-reconnect enabled, will retry on next BT event")
            }
        }
    }

    private fun tryAutoConnect(context: Context) {
        val config = ConfigManager(context)
        if (!config.autoConnect) return
        val address = config.lastBtAddress ?: return

        try {
            val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            val adapter = btManager?.adapter ?: return
            if (adapter.state != BluetoothAdapter.STATE_ON) return

            // Check whether the saved device is still paired before trying to open a
            // GATT connection; bonding (not ACL state) is what we can query cheaply.
            val device = adapter.getRemoteDevice(address)
            val isBonded = device.bondState == BluetoothDevice.BOND_BONDED

            if (isBonded) {
                PanaLog.d(TAG, "Saved device is bonded, connecting GATT")
                startConnect(context, address)
            }
        } catch (e: Exception) {
            PanaLog.e(TAG, "Auto-connect check failed", e)
        }
    }

    /**
          * 启动 BLE 前台服务发起连接。Android 12+ 后台启动前台服务可能受限
          * （如设备未授予自启动权限），此处捕获异常避免接收器崩溃循环。
     */
    private fun startConnect(context: Context, address: String) {
        val serviceIntent = Intent(context, PanaBleService::class.java).apply {
            action = PanaBleService.ACTION_CONNECT
            putExtra(PanaBleService.EXTRA_ADDRESS, address)
        }
        try {
            context.startForegroundService(serviceIntent)
        } catch (e: Throwable) {
            PanaLog.w(TAG, "startForegroundService blocked: ${e.message}")
        }
    }
}
