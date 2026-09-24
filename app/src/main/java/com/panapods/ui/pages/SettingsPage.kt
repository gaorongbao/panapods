package com.panapods.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.widget.Toast
import com.panapods.BuildConfig
import com.panapods.PanaPodsApp
import com.panapods.ui.components.PageHeader
import com.panapods.ui.components.PreferenceRow
import com.panapods.ui.theme.AppColors
import com.panapods.utils.Async
import com.panapods.utils.PanaLog
import com.panapods.utils.RootKeepAlive
import com.panapods.receivers.KeepAliveScheduler

/**
 * 设置页 — 对应 SonyPods SettingsPage 的 Miuix 偏好列表风格。
 */
@Composable
fun SettingsPage(
    bottomPadding: Dp = 0.dp,
    onOpenDebug: () -> Unit = {}
) {
    val context = LocalContext.current
    val config = remember { (context.applicationContext as PanaPodsApp).configManager }
    val logEnabled = remember { mutableStateOf(PanaLog.enabled) }
    val autoConnect = remember { mutableStateOf(config.autoConnect) }
    val hideFromRecents = remember { mutableStateOf(config.hideFromRecents) }
    val rootKeepAlive = remember { mutableStateOf(config.rootKeepAlive) }
    val swapEarSides = remember { mutableStateOf(config.swapEarSides) }

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
            PageHeader(title = "设置")
        }

        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(AppColors.card, RoundedCornerShape(20.dp))
                    .padding(vertical = 4.dp)
            ) {
                PreferenceRow(
                    icon = "🔗",
                    title = "自动连接",
                    summary = "拿出耳机或蓝牙开启后自动连接 Pana",
                    trailing = {
                        Switch(
                            checked = autoConnect.value,
                            onCheckedChange = { enabled ->
                                autoConnect.value = enabled
                                config.autoConnect = enabled
                            }
                        )
                    }
                )
                Divider()
                PreferenceRow(
                    icon = "🐞",
                    title = "调试日志",
                    // v175：文案与实现同步 —— 现在是三路输出：
                    // logcat + 文件（FileLog 滚动落盘）+ LSPosed 模块日志（Hook 侧镜像）。
                    // w/e 始终输出，d/i/v 受开关控制，文案按此如实描述。
                    summary = "开启后详细日志写入 logcat、文件 logs/panapods.log 与 LSPosed 日志；关闭后仅保留警告与错误",
                    trailing = {
                        Switch(
                            checked = logEnabled.value,
                            onCheckedChange = { enabled ->
                                logEnabled.value = enabled
                                PanaLog.setEnabled(context, enabled)
                            }
                        )
                    }
                )
            }
        }

        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(AppColors.card, RoundedCornerShape(20.dp))
                    .padding(vertical = 4.dp)
            ) {
                PreferenceRow(
                    icon = "🔄",
                    title = "左右耳电量对调",
                    summary = "若电量显示左右相反（左槽显示右耳、右槽显示左耳），请开启此项",
                    trailing = {
                        Switch(
                            checked = swapEarSides.value,
                            onCheckedChange = { enabled ->
                                swapEarSides.value = enabled
                                config.swapEarSides = enabled
                            }
                        )
                    }
                )
            }
        }

        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(AppColors.card, RoundedCornerShape(20.dp))
                    .padding(vertical = 4.dp)
            ) {
                PreferenceRow(
                    icon = "👻",
                    title = "隐藏后台",
                    summary = "开启后 App 进入后台时从最近任务列表移除（服务仍保持连接）",
                    trailing = {
                        Switch(
                            checked = hideFromRecents.value,
                            onCheckedChange = { enabled ->
                                hideFromRecents.value = enabled
                                config.hideFromRecents = enabled
                            }
                        )
                    }
                )
                Divider()
                PreferenceRow(
                    icon = "🛡️",
                    title = "Root 自动保活",
                    summary = "使用 root 加入电池白名单并周期拉起服务（默认关闭，需已 root）",
                    trailing = {
                        Switch(
                            checked = rootKeepAlive.value,
                            onCheckedChange = { enabled ->
                                rootKeepAlive.value = enabled
                                config.rootKeepAlive = enabled
                                if (enabled) {
                                    KeepAliveScheduler.schedule(context)
                                    Async.run("root-keepalive-apply") {
                                        val ok = RootKeepAlive.apply(context)
                                        if (!ok && !RootKeepAlive.isRootAvailable()) {
                                            android.os.Handler(android.os.Looper.getMainLooper()).post {
                                                Toast.makeText(context, "未检测到可用 root，保活可能无效", Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    }
                                } else {
                                    KeepAliveScheduler.cancel(context)
                                    // v175：对称回收 apply() 加上的 deviceidle 白名单
                                    Async.run("root-keepalive-remove") {
                                        RootKeepAlive.remove(context)
                                    }
                                }
                            }
                        )
                    }
                )
            }
        }

        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(AppColors.card, RoundedCornerShape(20.dp))
                    .padding(vertical = 4.dp)
            ) {
                PreferenceRow(
                    icon = "📡",
                    title = "协议调试",
                    summary = "查看 BLE Race 协议收发日志",
                    modifier = Modifier.clickable(onClick = onOpenDebug),
                    trailing = {
                        Text(
                            text = "›",
                            fontSize = 20.sp,
                            color = AppColors.textSecondary
                        )
                    }
                )
            }
        }

        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(AppColors.card, RoundedCornerShape(20.dp))
                    .padding(16.dp)
            ) {
                Text(
                    text = "关于 PanaPods",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = AppColors.textPrimary
                )
                Text(
                    text = "为 HyperOS 设备提供系统级松下/Technics EAH-AZ 系列耳机控制",
                    fontSize = 12.sp,
                    color = AppColors.textSecondary,
                    modifier = Modifier.padding(top = 4.dp)
                )
                Text(
                    text = "版本 ${BuildConfig.VERSION_NAME} · 协议 Airoha Race (Panasonic Pana)",
                    fontSize = 12.sp,
                    color = AppColors.textSecondary,
                    modifier = Modifier.padding(top = 8.dp)
                )
                // v175：原文案对所有用户硬编码"目标 HyperOS 3 (Xiaomi 17 Ultra)"，
                // 与实际运行设备无关，容易被当成"App 只支持这台机器"。改为本机真实信息。
                Text(
                    text = "本机 ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} · " +
                        "Android ${android.os.Build.VERSION.RELEASE}",
                    fontSize = 12.sp,
                    color = AppColors.textSecondary
                )
            }
        }

        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(AppColors.card, RoundedCornerShape(20.dp))
                    .padding(16.dp)
            ) {
                Text(
                    text = "Xposed 作用域",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = AppColors.textPrimary
                )
                ScopeItem("com.android.bluetooth", "系统蓝牙 — 电量/ANC 注入, 型号伪装")
                ScopeItem("com.android.settings", "系统设置 — 耳机信息显示")
                ScopeItem("com.milink.service", "MiLink — 融合设备中心")
                ScopeItem("com.android.systemui", "SystemUI — 融合中心卡片渲染")
                ScopeItem("com.miui.contentcatcher", "ContentCatcher — 设置页兼容进程")
                ScopeItem("com.xiaomi.bluetooth", "小米蓝牙 — AIVS 探测拦截")
            }
        }
    }
}

@Composable
private fun Divider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(0.5.dp)
            .padding(start = 68.dp, end = 16.dp)
            .background(AppColors.divider)
    )
}

@Composable
private fun ScopeItem(pkg: String, desc: String) {
    Column(modifier = Modifier.padding(top = 8.dp)) {
        Text(
            text = pkg,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = AppColors.textPrimary
        )
        Text(
            text = desc,
            fontSize = 11.sp,
            color = AppColors.textSecondary
        )
    }
}
