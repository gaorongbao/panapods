package com.panapods.ui.pages

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.panapods.R
import com.panapods.bridge.PanaBridge
import com.panapods.ui.components.PageHeader
import com.panapods.ui.theme.AppColors

data class BleDeviceInfo(
    val address: String,
    val name: String,
    val isPana: Boolean
)

@SuppressLint("MissingPermission")
fun queryPairedDevices(context: Context): List<BleDeviceInfo> {
    return try {
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        adapter?.bondedDevices
            ?.map { device ->
                BleDeviceInfo(
                    address = device.address,
                    name = device.name?.takeIf { it.isNotBlank() } ?: device.address,
                    isPana = PanaBridge.isPanaDevice(device.name)
                )
            }
            ?.sortedWith(compareByDescending<BleDeviceInfo> { it.isPana }.thenBy { it.name })
            ?: emptyList()
    } catch (_: SecurityException) {
        emptyList()
    }
}

/**
 * 空列表的真实原因 —— 三种情况的指引完全不同，混成一句"请先配对"是误导：
 * - 缺蓝牙权限 → 该去授权，配对列表根本读不到；
 * - 蓝牙整体关闭 → 该开蓝牙，不是去配对；
 * - 权限与蓝牙都正常 → 才是"确实没有已配对设备"。
 */
private sealed interface EmptyReason {
    data object NoPermission : EmptyReason
    data object AdapterOff : EmptyReason
    data object ReallyEmpty : EmptyReason
}

@SuppressLint("MissingPermission")
private fun resolveEmptyReason(context: Context): EmptyReason {
    val hasPermission = ContextCompat.checkSelfPermission(
        context, Manifest.permission.BLUETOOTH_CONNECT
    ) == PackageManager.PERMISSION_GRANTED
    if (!hasPermission) return EmptyReason.NoPermission
    val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    if (adapter == null || !adapter.isEnabled) return EmptyReason.AdapterOff
    return EmptyReason.ReallyEmpty
}

/**
 * 设备选择页 — 对应 SonyPods DevicePickerPage：大标题 + 配对设备卡片列表。
 */
@Composable
fun DevicePickerPage(
    devices: List<BleDeviceInfo>,
    connectedAddress: String?,
    connectingAddress: String?,
    bottomPadding: Dp = 0.dp,
    loading: Boolean = false,
    onConnect: (String) -> Unit,
    onDisconnect: () -> Unit,
    onRefresh: () -> Unit = {}
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        PageHeader(
            title = "耳机",
            subtitle = "点击耳机进行连接，连接后自动进入详情页",
            modifier = Modifier.padding(top = 12.dp)
        )

        // v174：查询中先显示 loading，再显示空态；空态给出「重新扫描」入口，
        // 不再让用户对着一句静态提示无计可施。
        if (loading && devices.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(AppColors.card, RoundedCornerShape(20.dp))
                    .padding(vertical = 28.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp
                    )
                    Text(
                        text = "正在读取已配对设备…",
                        fontSize = 13.sp,
                        color = AppColors.textSecondary,
                        modifier = Modifier.padding(start = 10.dp)
                    )
                }
            }
            return@Column
        }

        if (devices.isEmpty()) {
            // v175：按真实原因给不同指引，而不是一律劝用户"去配对"。
            val context = LocalContext.current
            val reason = remember(loading) { resolveEmptyReason(context) }
            val (message, actionLabel) = when (reason) {
                EmptyReason.NoPermission ->
                    "缺少蓝牙权限，无法读取已配对设备列表" to "去授权"
                EmptyReason.AdapterOff ->
                    "蓝牙未开启，开启后才能读取已配对设备" to "提示"
                EmptyReason.ReallyEmpty ->
                    "没有找到已配对设备，请先在系统蓝牙设置中配对松下/Technics 耳机" to "重新读取"
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(AppColors.card, RoundedCornerShape(20.dp))
                    .padding(16.dp)
            ) {
                Text(
                    text = message,
                    fontSize = 13.sp,
                    color = AppColors.textSecondary
                )
                if (reason != EmptyReason.AdapterOff) {
                    Text(
                        text = actionLabel,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color(0xFF2C6FF2),
                        modifier = Modifier
                            .padding(top = 12.dp)
                            .clickable(onClick = onRefresh)
                            .padding(vertical = 4.dp)
                    )
                }
            }
            return@Column
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = 16.dp, bottom = bottomPadding + 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(devices, key = { it.address }) { device ->
                DeviceRow(
                    device = device,
                    isConnected = device.address.equals(connectedAddress, ignoreCase = true),
                    isConnecting = device.address.equals(connectingAddress, ignoreCase = true),
                    onConnect = { onConnect(device.address) },
                    onDisconnect = onDisconnect
                )
            }
        }
    }
}

@Composable
private fun DeviceRow(
    device: BleDeviceInfo,
    isConnected: Boolean,
    isConnecting: Boolean,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit
) {
    // v174：断开是异步的，点击后立刻置灰，防止连点触发多次 disconnect。
    var disconnecting by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(AppColors.card, RoundedCornerShape(20.dp))
            .clickable(enabled = !isConnected && !isConnecting, onClick = onConnect)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .background(
                    if (isConnected) Color(0xFFDFFAE4) else AppColors.surface,
                    CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            // v175：emoji → 产品图
            Image(
                painter = painterResource(R.drawable.pana_headset),
                contentDescription = null,
                modifier = Modifier.width(30.dp)
            )
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 12.dp)
        ) {
            Text(
                text = device.name,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = AppColors.textPrimary
            )
            Text(
                text = device.address,
                fontSize = 12.sp,
                color = AppColors.textSecondary
            )
        }

        when {
            isConnecting -> {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                Text(
                    text = "连接中",
                    fontSize = 13.sp,
                    color = Color(0xFFFF9F0A),
                    modifier = Modifier.padding(start = 8.dp)
                )
            }
            isConnected -> {
                Text(
                    text = "已连接",
                    fontSize = 13.sp,
                    color = Color(0xFF36D167),
                    modifier = Modifier.padding(start = 8.dp)
                )
                Text(
                    text = if (disconnecting) "断开中" else "断开",
                    fontSize = 13.sp,
                    color = Color(0xFFFF5A52),
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .alpha(if (disconnecting) 0.4f else 1f)
                        .clickable(enabled = !disconnecting) {
                            disconnecting = true
                            onDisconnect()
                        }
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
            else -> {
                Text(
                    text = "连接",
                    fontSize = 13.sp,
                    color = Color(0xFF2C6FF2),
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}
