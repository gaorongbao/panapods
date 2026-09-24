package com.panapods

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Build
import android.os.IBinder
import com.panapods.utils.PanaLog
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.panapods.ble.PanaBleService
import com.panapods.bridge.PanaBridge
import com.panapods.ui.PanaPodsUI
import com.panapods.ui.theme.PanaPodsTheme

/**
 * 主 Activity
 *
 * 职责:
 * 1. 请求权限 (蓝牙、通知、位置)
 * 2. 绑定 BLE 前台服务
 * 3. 注册蓝牙事件接收器
 * 4. 自动扫描已配对 Pana 并连接
 *
 * 关键: bleService 必须是 mutableStateOf，
 * 否则 Compose 不知道 service 从 null 变为非 null，不会触发 UI 重组。
 */
class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    // ★ 必须用 mutableStateOf — Compose 需要知道值变化以触发重组
    private var bleService by mutableStateOf<PanaBleService?>(null)
    @Volatile
    private var bound = false
    // v175：unbind 的依据必须是"发起过 bindService"，而不是"收到过 onServiceConnected"。
    // 原先用 bound 判断，若 Activity 在绑定回调之前就被销毁（慢机/权限弹窗期间退出），
    // bound 仍为 false → 从不 unbind → ServiceConnection 泄漏并被系统打日志。
    @Volatile
    private var bindRequested = false
    // v175：权限请求进行中不做 hideFromRecents 的 finish（见 onStop 注释）
    private var permissionRequestInFlight = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as? PanaBleService.LocalBinder
            if (binder == null) {
                PanaLog.e(TAG, "onServiceConnected: unexpected binder type ${service?.javaClass?.name}")
                return
            }
            bleService = binder.getService()
            bound = true
            PanaLog.d(TAG, "Service connected — Compose will recompose")
            // 服务绑定完成后再尝试自动连接，避免 bleService 为 null
            autoConnectIfAvailable()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            bleService = null
            bound = false
            PanaLog.d(TAG, "Service disconnected — Compose will recompose")
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        // v175：原先 `permissions.values.all { it }` 才 initApp —— 只要拒绝任意一个
        // （包括与 BLE 无关的 POST_NOTIFICATIONS），服务就永远不绑定、bleService 恒 null，
        // 用户点"连接"是 `bleService?.connect()` 安全调用：不报错、不转圈、毫无反馈，
        // 且选过"不再询问"后每次启动都会永久卡在这里。
        // 现在无论结果如何都把服务拉起来，让 UI 至少可见可用；缺权限由调用处提示。
        permissionRequestInFlight = false
        val denied = permissions.filterValues { !it }.keys
        if (denied.isNotEmpty()) {
            PanaLog.w(TAG, "permissions denied (${denied.size}): will run with reduced capability")
        }
        initApp()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            // bleService 是 mutableStateOf — 当它变化时，这个 Composable 会自动重组
            val currentService = bleService

            PanaPodsTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    PanaPodsUI(
                        service = { currentService },
                        onConnect = { address -> connectToEarphone(address) },
                        onDisconnect = { disconnectEarphone() }
                    )
                }
            }
        }

        checkPermissionsAndInit()
    }

    override fun onDestroy() {
        // v175：按 bindRequested 解绑（bindService 返回 true 即视为已注册回调），
        // 不再依赖 onServiceConnected 是否已回调。
        if (bindRequested) {
            runCatching { unbindService(serviceConnection) }
                .onFailure { PanaLog.w(TAG, "unbindService failed: ${it.message}") }
            bindRequested = false
            bound = false
        }
        super.onDestroy()
    }

    override fun onStop() {
        super.onStop()
        // 隐藏后台：用户开启后，App 进入后台时从最近任务移除并结束 Activity，
        // BLE 前台服务不受影响，仍在后台保持连接。
        if (!isChangingConfigurations) {
            // v175：权限弹窗（部分 ROM 是不透明 Activity）会让本 Activity 走 onStop，
            // 此时 finish 会把权限结果一起带没 → App 表现为"点了允许却毫无反应"。
        // 权限请求在途时跳过隐藏，等回调清掉标记后再由下一次 onStop 处理。
            if (permissionRequestInFlight) {
                PanaLog.d(TAG, "hideFromRecents deferred: permission request in flight")
                return
            }
            val app = application as? PanaPodsApp
            if (app?.configManager?.hideFromRecents == true) {
                PanaLog.d(TAG, "hideFromRecents enabled, removing task from recents")
                finishAndRemoveTask()
            }
        }
    }

    private fun checkPermissionsAndInit() {
        val permissions = mutableListOf(
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.POST_NOTIFICATIONS
        )
        // Location is only needed for BLE discovery on legacy Android (< S);
        // on Android 12+ BLUETOOTH_SCAN covers bonded-device access.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        val notGranted = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (notGranted.isEmpty()) {
            initApp()
        } else {
            permissionRequestInFlight = true
            permissionLauncher.launch(notGranted.toTypedArray())
        }
    }

    private fun initApp() {
        bindAndStartService()
        // BluetoothEventReceiver 已由 Manifest 静态声明，系统广播（ACL_CONNECTED/
        // ACL_DISCONNECTED/STATE_CHANGED）无需动态注册，避免存活期间重复处理。
        // autoConnect 移到 onServiceConnected 中，确保服务已绑定
    }

    private fun bindAndStartService() {
        val intent = Intent(this, PanaBleService::class.java)
        startForegroundService(intent)
        if (!bindRequested) {
            bindRequested = bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        }
    }

    /**
     * 自动连接: 查找已配对的 Pana 设备并连接
     */
    private fun autoConnectIfAvailable() {
        if (bleService == null) {
            PanaLog.w(TAG, "autoConnectIfAvailable: bleService is null, skip")
            return
        }
        val app = application as? PanaPodsApp ?: return
        val config = app.configManager

        // 优先使用「系统当前已连接」的地址（单耳时可能不是上次保存的那只耳），
        // 其次用保存的地址，最后扫描已配对设备。
        val address = PanaBleService.findConnectedPanaAddress(
            this,
            listOf(config.lastBtAddress, PanaBridge.getMacAddress(), PanaBridge.getLc3MacAddress())
        ) ?: config.lastBtAddress ?: findPairedPana()
        if (address != null && config.autoConnect) {
            PanaLog.i(TAG, "Auto-connecting to: $address")
            config.lastBtAddress = address
            connectToEarphone(address)
        } else {
            PanaLog.w(TAG, "Auto-connect skipped: address=$address, autoConnect=${config.autoConnect}")
        }
    }

    private fun connectToEarphone(address: String) {
        PanaLog.i(TAG, "connectToEarphone: $address")
        bleService?.connect(address)
        // 保存地址
        val app = application as? PanaPodsApp
        app?.configManager?.lastBtAddress = address
    }

    private fun disconnectEarphone() {
        bleService?.disconnect()
    }

    /**
     * 扫描已配对设备查找 Pana
     */
    private fun findPairedPana(): String? {
        val btManager = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = btManager?.adapter ?: return null
        val saved = (application as? PanaPodsApp)?.configManager?.lastBtAddress
        return try {
            // 优先：系统当前已连接的 Pana 地址。两个配对地址（经典/LE 副地址）都属于
            // 同一副耳机且都能通过名字匹配，直接取 firstOrNull 会等概率选中已回盒的那只，
            // 之后就会一直连不上——必须先挑「真正在线」的那个。
            PanaBleService.findConnectedPanaAddress(this, listOf(saved))
                ?: adapter.bondedDevices
                    .firstOrNull { device -> PanaBridge.isPanaDevice(device.name) }
                    ?.address
        } catch (e: SecurityException) {
            PanaLog.e(TAG, "Permission denied for bonded devices", e)
            null
        }
    }
}
