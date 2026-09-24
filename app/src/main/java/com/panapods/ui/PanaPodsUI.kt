package com.panapods.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.panapods.R
import com.panapods.ble.PanaBleService
import com.panapods.headphones.HeadphoneState
import com.panapods.ui.pages.DevicePickerPage
import com.panapods.ui.pages.HomePage
import com.panapods.ui.pages.PodDetailPage
import com.panapods.ui.pages.ProtocolDebugPage
import com.panapods.ui.pages.SettingsPage
import com.panapods.ui.pages.BleDeviceInfo
import com.panapods.ui.pages.queryPairedDevices
import com.panapods.ui.theme.AppColors
import com.panapods.utils.PanaLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * PanaPods 主界面 — 视觉 1:1 还原 Mercury000/SonyPods 的 Miuix 风格：
 * 悬浮圆角底部导航 + 三个标签页 + HorizontalPager 左右滑动。
 */
@Composable
fun PanaPodsUI(
    service: () -> PanaBleService?,
    onConnect: (String) -> Unit,
    onDisconnect: () -> Unit
) {
    val svc = service()
    var currentState by remember { mutableStateOf(HeadphoneState()) }
    var isConnected by remember { mutableStateOf(false) }
    var isConnecting by remember { mutableStateOf(false) }
    var debugLogs by remember { mutableStateOf(listOf<String>()) }
    var selectedTab by remember { mutableIntStateOf(0) }
    var showDebug by remember { mutableStateOf(false) }
    var deviceRefreshKey by remember { mutableIntStateOf(0) }
    val pagerState = rememberPagerState(pageCount = { 3 })
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current

    // 配对设备列表：蓝牙 bondedDevices 是 Binder IPC，必须放 IO 线程查询，
    // 否则每次切到「耳机」页都会卡住主线程，导致切换动画掉帧。
    var pairedDevices by remember { mutableStateOf<List<BleDeviceInfo>>(emptyList()) }
    // v174：列表异步查询期间显示 loading，避免先闪一下"没有找到已配对设备"。
    var devicesLoading by remember { mutableStateOf(true) }
    LaunchedEffect(deviceRefreshKey, isConnected) {
        if (!isConnected) {
            devicesLoading = true
            pairedDevices = withContext(Dispatchers.IO) { queryPairedDevices(context) }
            devicesLoading = false
        }
    }

    LaunchedEffect(selectedTab) {
        if (selectedTab == 1 && !isConnected) deviceRefreshKey++
    }

    if (svc != null) {
        DisposableEffect(svc) {
            val listener = object : PanaBleService.StateListener {
                override fun onStateChanged(state: HeadphoneState) {
                    currentState = state
                }

                override fun onConnecting(address: String) {
                    isConnecting = true
                    isConnected = false
                    currentState = currentState.copy(macAddress = address)
                }

                override fun onConnected(address: String) {
                    isConnected = true
                    isConnecting = false
                    currentState = currentState.copy(macAddress = address)
                }

                override fun onDisconnected() {
                    isConnected = false
                    isConnecting = false
                }

                override fun onLogReceived(message: String) {
                    if (PanaLog.enabled) {
                        debugLogs = (debugLogs + message).takeLast(200)
                    }
                }
            }
            svc.setStateListener(listener)
            onDispose {
                svc.setStateListener(null)
            }
        }
    }

    if (showDebug) {
        ProtocolDebugPage(
            logs = debugLogs,
            onClearLogs = { debugLogs = emptyList() },
            onSendRawHex = { hex -> svc?.sendRawHex(hex) },
            onBack = { showDebug = false }
        )
        return
    }

    Scaffold(
        containerColor = AppColors.background,
        bottomBar = {
            FloatingBottomNavigation(
                selectedTab = selectedTab,
                onTabSelected = { tab ->
                    selectedTab = tab
                    coroutineScope.launch { pagerState.animateScrollToPage(tab) }
                }
            )
        }
    ) { padding ->
        LaunchedEffect(pagerState.currentPage) {
            selectedTab = pagerState.currentPage
        }

        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxSize()
                .padding(top = padding.calculateTopPadding()),
            beyondViewportPageCount = 1
        ) { page ->
            Box(modifier = Modifier.fillMaxSize()) {
                when (page) {
                    0 -> HomePage(
                        isConnected = isConnected,
                        isConnecting = isConnecting,
                        deviceName = currentState.deviceName,
                        modelName = currentState.modelName(),
                        bottomPadding = padding.calculateBottomPadding(),
                        onOpenEarphones = {
                            selectedTab = 1
                            coroutineScope.launch { pagerState.animateScrollToPage(1) }
                        }
                    )

                    1 -> if (isConnected) {
                        PodDetailPage(
                            state = currentState,
                            isConnected = isConnected,
                            svc = svc,
                            bottomPadding = padding.calculateBottomPadding(),
                            onDisconnect = onDisconnect
                        )
                    } else {
                        DevicePickerPage(
                            devices = pairedDevices,
                            loading = devicesLoading,
                            connectedAddress = if (isConnected) currentState.macAddress else null,
                            connectingAddress = if (isConnecting) currentState.macAddress else null,
                            bottomPadding = padding.calculateBottomPadding(),
                            onConnect = onConnect,
                            onDisconnect = onDisconnect,
                            onRefresh = { deviceRefreshKey++ }
                        )
                    }

                    else -> SettingsPage(
                        bottomPadding = padding.calculateBottomPadding(),
                        onOpenDebug = { showDebug = true }
                    )
                }
            }
        }
    }
}

private data class BottomTab(val index: Int, val icon: String, val label: String)

/**
 * 悬浮圆角底部导航 — 模仿 Miuix FloatingNavigationBar。
 */
@Composable
private fun FloatingBottomNavigation(
    selectedTab: Int,
    onTabSelected: (Int) -> Unit
) {
    // v174：列表在重组间复用，避免每次重组都新建导致底部导航无法 skip。
    val homeLabel = stringResource(R.string.home)
    val earphonesLabel = stringResource(R.string.earphones)
    val settingsLabel = stringResource(R.string.settings)
    val tabs = remember(homeLabel, earphonesLabel, settingsLabel) {
        listOf(
            BottomTab(0, "🏠", homeLabel),
            BottomTab(1, "🎧", earphonesLabel),
            BottomTab(2, "⚙️", settingsLabel),
        )
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            modifier = Modifier
                .shadow(18.dp, RoundedCornerShape(50.dp))
                .background(AppColors.floatingBar, RoundedCornerShape(50.dp))
                .padding(horizontal = 10.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            tabs.forEach { tab ->
                val selected = selectedTab == tab.index
                Row(
                    modifier = Modifier
                        .background(
                            if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.30f) else Color.Transparent,
                            RoundedCornerShape(22.dp)
                        )
                        .clickable(
                            interactionSource = null,
                            indication = null,
                            onClick = { onTabSelected(tab.index) }
                        )
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(text = tab.icon, fontSize = 16.sp)
                    Text(
                        text = tab.label,
                        fontSize = 13.sp,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (selected) MaterialTheme.colorScheme.primary else AppColors.textSecondary
                    )
                }
            }
        }
    }
}
