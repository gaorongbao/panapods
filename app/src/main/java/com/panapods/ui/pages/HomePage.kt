package com.panapods.ui.pages

import android.bluetooth.BluetoothManager
import android.content.Context
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.panapods.ui.components.PageHeader
import com.panapods.ui.theme.AppColors
import com.panapods.utils.ScopeRestarter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class BluetoothSummary(
    val enabled: Boolean,
    val bondedCount: Int,
    /** true = 读 bondedDevices 被权限拦截。此时不能谎报成"蓝牙未开启"。 */
    val noPermission: Boolean = false,
    /** false = 还没完成第一次读取，避免开屏先闪一句"未开启"。 */
    val loaded: Boolean = false
)

private fun readBluetoothSummary(context: Context): BluetoothSummary {
    val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    // isEnabled 不需要运行时权限，可以独立读取；bondedDevices 需要 BLUETOOTH_CONNECT。
    val enabled = adapter?.isEnabled == true
    return try {
        BluetoothSummary(
            enabled = enabled,
            bondedCount = adapter?.bondedDevices?.size ?: 0,
            loaded = true
        )
    } catch (_: SecurityException) {
        // v175：权限被拒原本被编码成 enabled=false → 状态卡显示"未开启"，
        // 把权限问题谎报成"蓝牙没开"，用户会去开一个本来就开着的蓝牙。
        BluetoothSummary(enabled = enabled, bondedCount = 0, noPermission = true, loaded = true)
    }
}

/**
 * 首页 — 对应 SonyPods HomePage：大标题 + 状态卡网格 + 信息卡。
 */
@Composable
fun HomePage(
    isConnected: Boolean,
    isConnecting: Boolean,
    deviceName: String,
    modelName: String,
    bottomPadding: Dp = 0.dp,
    onOpenEarphones: () -> Unit
) {
    val context = LocalContext.current
    var bluetooth by remember { mutableStateOf(BluetoothSummary(enabled = false, bondedCount = 0)) }
    var showRestartDialog by remember { mutableStateOf(false) }
    var restarting by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()
    // v174：readScopePackages 要打开模块 APK 读 ZipFile，是磁盘 IO ——
    // 原来在组合阶段同步执行，会卡首帧；改为 IO 线程异步读取。
    var scopePackages by remember { mutableStateOf<List<String>>(emptyList()) }
    var selectedPackages by remember { mutableStateOf<Set<String>>(emptySet()) }

    LaunchedEffect(Unit) {
        scopePackages = withContext(Dispatchers.IO) { ScopeRestarter.readScopePackages(context) }
        selectedPackages = scopePackages.toSet()
        while (true) {
            // bondedDevices 是 Binder IPC，放到 IO 线程避免主线程卡顿。
            bluetooth = withContext(Dispatchers.IO) { readBluetoothSummary(context) }
            delay(4_000L)
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp,
            top = 12.dp,
            end = 16.dp,
            bottom = bottomPadding + 16.dp
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            PageHeader(
                title = "PanaPods",
                actions = {
                    RestartScopeButton(
                        restarting = restarting,
                        onClick = { showRestartDialog = true }
                    )
                }
            )
        }

        item {
            StatusGrid(
                isConnected = isConnected,
                isConnecting = isConnecting,
                bluetooth = bluetooth,
                bondedDeviceCount = bluetooth.bondedCount,
                onEarphoneStatusClick = onOpenEarphones
            )
        }

        item {
            MiuixInfoCard(deviceName = deviceName, modelName = modelName)
        }
    }

    if (showRestartDialog) {
        RestartScopeDialog(
            packages = scopePackages,
            selected = selectedPackages,
            onToggle = { pkg ->
                selectedPackages =
                    if (pkg in selectedPackages) selectedPackages - pkg else selectedPackages + pkg
            },
            restarting = restarting,
            onConfirm = {
                if (!restarting && selectedPackages.isNotEmpty()) {
                    restarting = true
                    coroutineScope.launch {
                        val result = ScopeRestarter.restartScopedApps(
                            context,
                            selectedPackages.toList()
                        )
                        restarting = false
                        showRestartDialog = false
                        Toast.makeText(
                            context,
                            if (result.success) {
                                "已通过 root 重启 ${result.packages.size} 个作用域应用"
                            } else {
                                "root 重启失败：${result.output.trim().take(120)}"
                            },
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            },
            onDismiss = { if (!restarting) showRestartDialog = false }
        )
    }
}

/**
 * 主页右上角的重启按钮（仅白色图标，无文字）。
 */
@Composable
private fun RestartScopeButton(
    restarting: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(32.dp)
            .background(MaterialTheme.colorScheme.primary, CircleShape)
            .clickable(enabled = !restarting, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (restarting) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
                color = Color.White
            )
        } else {
            Icon(
                imageVector = Icons.Default.Refresh,
                contentDescription = "重启",
                tint = Color.White,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

/**
 * 重启确认对话框：勾选要重启的作用域应用并提示风险。
 */
@Composable
private fun RestartScopeDialog(
    packages: List<String>,
    selected: Set<String>,
    onToggle: (String) -> Unit,
    restarting: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("重启作用域应用") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("选择要通过 root 重启的作用域应用：")
                packages.forEach { pkg ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !restarting) { onToggle(pkg) },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = pkg in selected,
                            onCheckedChange = null,
                            enabled = !restarting
                        )
                        Text(
                            text = pkg,
                            fontSize = 13.sp,
                            color = AppColors.textPrimary,
                            modifier = Modifier.padding(start = 4.dp)
                        )
                    }
                }
                Text(
                    text = "注意：SystemUI 会闪烁一下，蓝牙可能短暂断开。",
                    fontSize = 12.sp,
                    color = Color(0xFFE5484D)
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = !restarting && selected.isNotEmpty()
            ) {
                Text(
                    if (restarting) "重启中…"
                    else if (selected.isEmpty()) "重启"
                    else "重启 (${selected.size})"
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !restarting) {
                Text("取消")
            }
        }
    )
}

@Composable
private fun StatusGrid(
    isConnected: Boolean,
    isConnecting: Boolean,
    bluetooth: BluetoothSummary,
    bondedDeviceCount: Int,
    onEarphoneStatusClick: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        EarphoneStatusCard(
            isConnected = isConnected,
            isConnecting = isConnecting,
            modifier = Modifier
                .weight(1f)
                .aspectRatio(1f),
            onClick = onEarphoneStatusClick
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .aspectRatio(1f),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            StatCard(
                title = "蓝牙状态",
                // v175: 区分"读取中 / 缺权限 / 真关闭"，避免权限被拒时谎报"未开启"
                value = when {
                    !bluetooth.loaded -> "…"
                    bluetooth.noPermission -> "无权限"
                    bluetooth.enabled -> "已开启"
                    else -> "未开启"
                },
                modifier = Modifier.weight(1f)
            )
            StatCard(
                title = "配对设备",
                value = bondedDeviceCount.toString(),
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun EarphoneStatusCard(
    isConnected: Boolean,
    isConnecting: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val statusColor = when {
        isConnected -> Color(0xFF36D167)
        isConnecting -> Color(0xFFFF9F0A)
        else -> Color(0xFF9E9E9E)
    }
    val statusBackground = when {
        isConnected -> Color(0xFFDFFAE4)
        isConnecting -> Color(0xFFFFF0D7)
        else -> Color(0xFFF2F2F2)
    }

    Box(
        modifier = modifier
            .background(statusBackground, RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
    ) {
        Text(
            text = "🎧",
            fontSize = 56.sp,
            color = statusColor.copy(alpha = 0.78f),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(10.dp)
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            Text(
                text = "耳机状态",
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = Color(0xFF888888)
            )
            Text(
                text = when {
                    isConnected -> "已连接"
                    isConnecting -> "连接中…"
                    else -> "未连接"
                },
                fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFF101010),
                modifier = Modifier.padding(top = 6.dp)
            )
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = "点击查看详情",
                fontSize = 12.sp,
                color = statusColor,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
private fun StatCard(
    title: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(AppColors.card, RoundedCornerShape(20.dp))
            .padding(14.dp),
        horizontalAlignment = Alignment.Start,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = title,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = AppColors.textSecondary
        )
        Text(
            text = value,
            fontSize = 24.sp,
            fontWeight = FontWeight.SemiBold,
            color = AppColors.textPrimary,
            modifier = Modifier.padding(top = 2.dp)
        )
    }
}

@Composable
private fun MiuixInfoCard(deviceName: String, modelName: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(AppColors.card, RoundedCornerShape(20.dp))
            .padding(16.dp)
    ) {
        InfoText("设备名称", deviceName.ifBlank { "Technics EAH-AZ" })
        InfoText("型号", modelName)
        InfoText("系统版本", Build.VERSION.INCREMENTAL)
        InfoText("Android 版本", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        InfoText(
            "设备型号",
            listOf(Build.MANUFACTURER, Build.MODEL).filter { it.isNotBlank() }.joinToString(" "),
            bottomPadding = 0.dp
        )
    }
}

@Composable
private fun InfoText(title: String, content: String, bottomPadding: Dp = 24.dp) {
    Text(
        text = title,
        fontSize = 15.sp,
        fontWeight = FontWeight.Medium,
        color = AppColors.textPrimary
    )
    Text(
        text = content,
        fontSize = 13.sp,
        color = AppColors.textSecondary,
        modifier = Modifier.padding(top = 2.dp, bottom = bottomPadding)
    )
}
