package com.panapods.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.panapods.ui.theme.AppColors

/**
 * 电量状态卡 — 参考 SonyPods PodStatus：左耳 / 右耳 / 充电盒 三列，带分隔线。
 * 未连接时显示 "-"，避免把 255 哨兵值或旧值当成真实电量。
 */
@Composable
fun BatteryStatusCard(
    leftBattery: Int?,
    rightBattery: Int?,
    cradleBattery: Int?,
    isConnected: Boolean,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = AppColors.card
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BatteryColumn(
                label = "左耳",
                percentage = leftBattery,
                isConnected = isConnected,
                modifier = Modifier.weight(1f)
            )
            Box(
                modifier = Modifier
                    .width(0.5.dp)
                    .height(56.dp)
                    .background(AppColors.divider)
            )
            BatteryColumn(
                label = "右耳",
                percentage = rightBattery,
                isConnected = isConnected,
                modifier = Modifier.weight(1f)
            )
            Box(
                modifier = Modifier
                    .width(0.5.dp)
                    .height(56.dp)
                    .background(AppColors.divider)
            )
            BatteryColumn(
                label = "充电盒",
                percentage = cradleBattery,
                isConnected = isConnected,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun BatteryColumn(
    label: String,
    percentage: Int?,
    isConnected: Boolean,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = label,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = if (isConnected && percentage != null) "$percentage%" else "-",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
