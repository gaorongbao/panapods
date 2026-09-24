package com.panapods.ui.pages

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/**
 * 协议调试页面
 *
 * 显示 BLE 收发的原始 HEX 数据日志，
 * 用于开发和验证 Airoha Race 协议。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProtocolDebugPage(
    logs: List<String>,
    onClearLogs: () -> Unit,
    onSendRawHex: (String) -> Unit,
    onBack: (() -> Unit)? = null
) {
    var hexInput by remember { mutableStateOf("") }
    var autoScroll by remember { mutableStateOf(true) }
    val listState = rememberLazyListState()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val snackbarScope = rememberCoroutineScope()

    // v175：系统返回手势原本不生效，只能点左上角"返回"，否则直接退出 App。
    BackHandler(enabled = onBack != null) { onBack?.invoke() }

    // 自动滚动到底部
    LaunchedEffect(logs.size, autoScroll) {
        if (autoScroll && logs.isNotEmpty()) {
            listState.animateScrollToItem(logs.size - 1)
        }
    }

    fun toast(message: String) {
        snackbarScope.launch { snackbarHostState.showSnackbar(message) }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("协议调试") },
                navigationIcon = {
                    if (onBack != null) {
                        TextButton(onClick = onBack) {
                            Text("返回")
                        }
                    }
                },
                actions = {
                    TextButton(onClick = {
                        val text = logs.joinToString("\n")
                        if (text.isBlank()) {
                            toast("暂无可复制的日志")
                            return@TextButton
                        }
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("PanaPods logs", text))
                        // v175：snackbarHostState 此前创建后从未使用 —— 复制零反馈，
                        // 用户不知道有没有复制成功。
                        toast("已复制 ${logs.size} 条日志")
                    }) {
                        Text("复制")
                    }
                    TextButton(onClick = {
                        onClearLogs()
                        toast("已清除")
                    }) {
                        Text("清除")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // 日志列表
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(Color(0xFF1E1E1E))
                    .padding(8.dp)
            ) {
                items(logs) { log ->
                    val isTx = log.startsWith("TX:")
                    val isRx = log.startsWith("RX:")
                    val isResp = log.contains("RESP:")
                    val isInd = log.contains("IND:")
                    val isError = log.contains("ERROR:")

                    val color = when {
                        isError -> Color(0xFFFF5252)
                        isTx -> Color(0xFF64FFDA)
                        isRx -> Color(0xFFFFD740)
                        isResp -> Color(0xFF69F0AE)
                        isInd -> Color(0xFFB388FF)
                        else -> Color(0xFFE0E0E0)
                    }

                    Text(
                        text = log,
                        color = color,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(vertical = 1.dp)
                    )
                }

                // v175：空态给出可行动的提示。日志受"调试日志"开关把关，
                // 开关没开时这里永远空白，用户会以为页面坏了。
                if (logs.isEmpty()) {
                    item {
                        Text(
                            text = "暂无日志。\n若确认已连接仍无输出，请到「设置」开启「调试日志」后重试。",
                            color = Color(0xFF9E9E9E),
                            fontSize = 12.sp,
                            modifier = Modifier.padding(vertical = 12.dp)
                        )
                    }
                }
            }

            // 自动滚动开关
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text(
                    text = "自动滚动",
                    fontSize = 12.sp,
                    modifier = Modifier.weight(1f)
                )
                Switch(
                    checked = autoScroll,
                    onCheckedChange = { autoScroll = it }
                )
            }

            // 手动发送 HEX
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = hexInput,
                    onValueChange = { hexInput = it },
                    label = { Text("发送 HEX (如: 05 5A 02 00 00 00)") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    textStyle = LocalTextStyle.current.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp
                    )
                )
                Button(
                    onClick = {
                        val raw = hexInput.trim()
                        if (raw.isEmpty()) return@Button
                        // v175：本地先校验格式。原先无条件清空输入框，而发送失败的
                        // "ERROR: invalid hex / raw send failed" 会被 UI 侧的
                        // PanaLog.enabled 把关丢弃 —— 用户看到的是"输入被清空、
                        // 屏幕毫无反应"，完全不知道发出去没有。
                        val cleaned = raw.replace(Regex("[\\s,]"), "")
                        if (cleaned.isEmpty() || cleaned.length % 2 != 0 ||
                            !cleaned.all { it.isDigit() || it in "0123456789abcdefABCDEF" }
                        ) {
                            toast("HEX 格式无效，示例: 05 5A 02 00")
                            return@Button
                        }
                        onSendRawHex(raw)
                        hexInput = ""
                        toast("已发送 ${cleaned.length / 2} 字节")
                    }
                ) {
                    Text("发送")
                }
            }
        }
    }
}
