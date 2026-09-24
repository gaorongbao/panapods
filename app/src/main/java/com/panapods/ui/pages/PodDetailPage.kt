package com.panapods.ui.pages

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.panapods.R
import com.panapods.ble.PanaBleService
import com.panapods.headphones.HeadphoneState
import com.panapods.ui.components.AncModeSelector
import com.panapods.ui.components.BatteryStatusCard
import com.panapods.ui.components.MiuixCard
import com.panapods.ui.components.PageHeader
import com.panapods.ui.theme.AppColors
import kotlinx.coroutines.delay

/**
 * 耳机详情页 — 对应 SonyPods PodDetailPage：耳机图 + 电量卡 + ANC 卡 + EQ 卡 + 功能卡 + 设备信息卡。
 */
@Composable
fun PodDetailPage(
    state: HeadphoneState,
    isConnected: Boolean,
    svc: PanaBleService?,
    bottomPadding: Dp = 0.dp,
    onDisconnect: () -> Unit
) {
    // v174：断开是异步的，点击后置灰防连点。
    var disconnecting by remember { mutableStateOf(false) }
    // v176：断开可能静默失败（svc 为 null / 断连回调丢失）→ 按钮永久卡在“断开中”不可点。
    // 4s 后仍处于已连接则复位；断开成功时页面早已切走，本协程随组合销毁，不会误触。
    //（AirohaBleClient.disconnect 的强制收尾窗口是 2s，4s 足够覆盖正常断开全流程。）
    LaunchedEffect(disconnecting) {
        if (disconnecting) {
            delay(4000L)
            if (isConnected) disconnecting = false
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
                title = state.deviceName.ifBlank { "Technics EAH-AZ" },
                subtitle = if (isConnected) "已连接 · ${state.modelName()}" else "未连接",
                actions = {
                    if (isConnected) {
                        Text(
                            text = if (disconnecting) "断开中" else "断开",
                            fontSize = 14.sp,
                            color = Color(0xFFFF5A52),
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier
                                .alpha(if (disconnecting) 0.4f else 1f)
                                .clickable(enabled = !disconnecting) {
                                    disconnecting = true
                                    onDisconnect()
                                }
                                .padding(horizontal = 8.dp, vertical = 6.dp)
                        )
                    }
                }
            )
        }

        item {
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                // v175：大 emoji → 产品图（与融合中心卡图同源的透明底产品照）
                Image(
                    painter = painterResource(R.drawable.pana_headset),
                    contentDescription = "耳机",
                    modifier = Modifier.width(220.dp)
                )
            }
        }

        item {
            BatteryStatusCard(
                leftBattery = state.leftBattery,
                rightBattery = state.rightBattery,
                cradleBattery = state.cradleBattery,
                isConnected = isConnected
            )
        }

        item {
            MiuixCard {
                Column(modifier = Modifier.padding(16.dp)) {
                    CardTitle("降噪模式")
                    AncModeSelector(
                        currentMode = state.outsideCtrl,
                        ncLevel = state.ncLevel,
                        ambientLevel = state.ambientLevel,
                        ambientMode = state.ambientMode,
                        isConnected = isConnected,
                        onModeChange = { mode -> svc?.setAncMode(mode) },
                        onNcLevelChange = { level -> svc?.setNcLevel(level) },
                        onAmbientModeChange = { mode -> svc?.setAmbientMode(mode) },
                        onAmbientLevelChange = { level ->
                            svc?.setAncMode(state.outsideCtrl, state.ncLevel, level)
                        }
                    )
                }
            }
        }

        item {
            MiuixCard {
                Column(modifier = Modifier.padding(16.dp)) {
                    CardTitle("设备信息")
                    InfoRow("型号", state.modelName())
                    // v175：firmwareVersion 协议层尚未解析、恒为 null，原先固定显示
                    // "固件版本 未知"，用户会以为功能坏了。未实现就整行隐藏。
                    state.firmwareVersion?.let { InfoRow("固件版本", it) }
                    InfoRow("蓝牙地址", state.macAddress ?: "未知")
                }
            }
        }

        item {
            Spacer(modifier = Modifier.height(4.dp))
        }
    }
}

@Composable
private fun CardTitle(title: String) {
    Text(
        text = title,
        fontSize = 16.sp,
        fontWeight = FontWeight.Bold,
        color = AppColors.textPrimary,
        modifier = Modifier.padding(bottom = 12.dp)
    )
}

@Composable
private fun InfoRow(title: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp)
    ) {
        Text(
            text = title,
            fontSize = 13.sp,
            color = AppColors.textSecondary,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = value,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = AppColors.textPrimary
        )
    }
}
