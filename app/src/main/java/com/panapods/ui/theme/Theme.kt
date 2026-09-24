package com.panapods.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/**
 * MIUI 风格中性色（参考 Miuix KMP 的浅色/深色面板配色）。
 * 组件中统一使用这些颜色，保证视觉与 SonyPods 一致。
 */
object AppColors {
    val background: Color
        @Composable get() = if (isSystemInDarkTheme()) Color(0xFF0D0D0D) else Color(0xFFFFFFFF)

    val surface: Color
        @Composable get() = if (isSystemInDarkTheme()) Color(0xFF141414) else Color(0xFFFFFFFF)

    /** 卡片/次级面板底色 */
    val card: Color
        @Composable get() = if (isSystemInDarkTheme()) Color(0xFF1C1C1E) else Color(0xFFF7F7F7)

    val divider: Color
        @Composable get() = if (isSystemInDarkTheme()) Color(0xFF333333) else Color(0xFFEEEEEE)

    val textPrimary: Color
        @Composable get() = if (isSystemInDarkTheme()) Color(0xFFECECEC) else Color(0xFF101010)

    val textSecondary: Color
        @Composable get() = if (isSystemInDarkTheme()) Color(0xFF8E8E93) else Color(0xFF8E8E93)

    /** 悬浮导航栏底色 */
    val floatingBar: Color
        @Composable get() = if (isSystemInDarkTheme()) Color(0xFF2C2C2E) else Color(0xFFFFFFFF)
}

@Composable
fun PanaPodsTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val context = LocalContext.current
        val dynamic = if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dynamic.copy(
            background = if (darkTheme) Color(0xFF0D0D0D) else Color(0xFFFFFFFF),
            surface = if (darkTheme) Color(0xFF141414) else Color(0xFFFFFFFF),
            surfaceVariant = if (darkTheme) Color(0xFF1C1C1E) else Color(0xFFF7F7F7),
            surfaceContainer = if (darkTheme) Color(0xFF1C1C1E) else Color(0xFFF2F2F2),
            surfaceContainerHigh = if (darkTheme) Color(0xFF232325) else Color(0xFFEDEDED),
            surfaceContainerLowest = if (darkTheme) Color(0xFF0D0D0D) else Color(0xFFFFFFFF),
            onSurfaceVariant = if (darkTheme) Color(0xFF8E8E93) else Color(0xFF8E8E93),
        )
    } else {
        if (darkTheme) darkColorScheme() else lightColorScheme()
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography(),
        content = content
    )
}
