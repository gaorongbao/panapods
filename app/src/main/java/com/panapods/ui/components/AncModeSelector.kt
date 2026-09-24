package com.panapods.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.panapods.headphones.AmbientMode
import com.panapods.headphones.AncMode
import com.panapods.ui.theme.AppColors

private const val ANIM_DURATION = 300

/**
 * ANC 模式选择器 — 参考 SonyPods 的 AncSwitch：
 * 三个大圆形图标按钮（降噪 / 环境声 / 关闭），选中态颜色动画过渡；
 * 环境声模式下展开「透明 / 注意」子模式与环境声等级滑块；
 * 降噪模式下展开 NC 增益滑块。
 */
@Composable
fun AncModeSelector(
    currentMode: Int,
    ncLevel: Int,
    ambientLevel: Int,
    ambientMode: Int,
    isConnected: Boolean,
    onModeChange: (Int) -> Unit,
    onNcLevelChange: (Int) -> Unit,
    onAmbientModeChange: (Int) -> Unit,
    onAmbientLevelChange: (Int) -> Unit
) {
    var localAmbientMode by remember { mutableStateOf(ambientMode) }
    LaunchedEffect(ambientMode) {
        localAmbientMode = ambientMode
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        // v174：未连接时给一句明确说明，而不是只把按钮变灰让人以为 App 坏了。
        if (!isConnected) {
            Text(
                text = "耳机未连接，连接后可切换降噪 / 环境声",
                fontSize = 12.sp,
                color = AppColors.textSecondary,
                modifier = Modifier.padding(bottom = 10.dp)
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            AncButton(
                icon = "🔇",
                label = "降噪",
                isSelected = currentMode == AncMode.NOISE_CANCELING,
                isConnected = isConnected,
                onClick = { onModeChange(AncMode.NOISE_CANCELING) }
            )
            AncButton(
                icon = "🌬",
                label = "环境声",
                isSelected = currentMode == AncMode.AMBIENT,
                isConnected = isConnected,
                onClick = { onModeChange(AncMode.AMBIENT) }
            )
            AncButton(
                icon = "✕",
                label = "关闭",
                isSelected = currentMode == AncMode.OFF,
                isConnected = isConnected,
                onClick = { onModeChange(AncMode.OFF) }
            )
        }

        // 环境声子模式
        if (isConnected && currentMode == AncMode.AMBIENT) {
            Spacer(modifier = Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ModeChip(
                    label = "透明模式",
                    selected = localAmbientMode == AmbientMode.TRANSPARENT,
                    modifier = Modifier.weight(1f),
                    onClick = {
                        localAmbientMode = AmbientMode.TRANSPARENT
                        onAmbientModeChange(AmbientMode.TRANSPARENT)
                    }
                )
                ModeChip(
                    label = "注意模式",
                    selected = localAmbientMode == AmbientMode.ATTENTION,
                    modifier = Modifier.weight(1f),
                    onClick = {
                        localAmbientMode = AmbientMode.ATTENTION
                        onAmbientModeChange(AmbientMode.ATTENTION)
                    }
                )
            }

            LevelSlider(
                title = "环境声等级",
                value = ambientLevel,
                valueRange = 0f..100f,
                enabled = isConnected,
                onValueChangeFinished = onAmbientLevelChange
            )
        }

        // NC 增益调节
        if (isConnected && currentMode == AncMode.NOISE_CANCELING) {
            LevelSlider(
                title = "NC 增益",
                value = ncLevel,
                valueRange = 20f..40f,
                enabled = isConnected,
                onValueChangeFinished = onNcLevelChange
            )
        }
    }
}

@Composable
private fun AncButton(
    icon: String,
    label: String,
    isSelected: Boolean,
    isConnected: Boolean,
    onClick: () -> Unit
) {
    val containerColor by animateColorAsState(
        targetValue = if (isSelected)
            MaterialTheme.colorScheme.primary
        else
            AppColors.surface,
        animationSpec = tween(ANIM_DURATION),
        label = "anc_container"
    )
    val textColor by animateColorAsState(
        targetValue = if (isSelected) MaterialTheme.colorScheme.primary else AppColors.textPrimary,
        animationSpec = tween(ANIM_DURATION),
        label = "anc_text"
    )

    Column(
        modifier = Modifier.alpha(if (isConnected) 1f else 0.4f),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .background(containerColor, CircleShape)
                .clickable(
                    enabled = isConnected,
                    onClick = onClick,
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() }
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = icon,
                fontSize = 26.sp
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = label,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = textColor
        )
    }
}

@Composable
private fun ModeChip(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val containerColor by animateColorAsState(
        targetValue = if (selected)
            MaterialTheme.colorScheme.primary
        else
            AppColors.surface,
        animationSpec = tween(ANIM_DURATION),
        label = "chip_container"
    )
    Box(
        modifier = modifier
            .background(containerColor, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = if (selected) Color.White else AppColors.textPrimary
        )
    }
}

@Composable
private fun LevelSlider(
    title: String,
    value: Int,
    valueRange: ClosedFloatingPointRange<Float>,
    enabled: Boolean,
    onValueChangeFinished: (Int) -> Unit
) {
    var dragging by remember { mutableStateOf(false) }
    var draggingValue by remember { mutableFloatStateOf(value.toFloat()) }

    LaunchedEffect(value, enabled) {
        if (!dragging || !enabled) draggingValue = value.toFloat()
    }

    Column(modifier = Modifier.padding(top = 12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = AppColors.textPrimary,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = draggingValue.toInt().toString(),
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (enabled) MaterialTheme.colorScheme.primary else AppColors.textSecondary
            )
        }
        Slider(
            value = draggingValue,
            onValueChange = { value ->
                dragging = true
                draggingValue = value
            },
            onValueChangeFinished = {
                dragging = false
                val rounded = draggingValue.toInt().coerceIn(valueRange.start.toInt(), valueRange.endInclusive.toInt())
                if (rounded != value) {
                    onValueChangeFinished(rounded)
                }
            },
            enabled = enabled,
            valueRange = valueRange,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp)
        )
    }
}
