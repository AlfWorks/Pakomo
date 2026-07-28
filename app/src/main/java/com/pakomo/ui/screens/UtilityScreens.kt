package com.pakomo.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pakomo.core.model.EngineStage
import com.pakomo.core.model.PakomoUiState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Security
import com.pakomo.ui.components.NavigationRow
import com.pakomo.ui.components.ScreenHeader
import com.pakomo.ui.components.SectionLabel
import com.pakomo.ui.theme.Accent
import com.pakomo.ui.theme.Border
import com.pakomo.ui.theme.Danger
import com.pakomo.ui.theme.Muted
import com.pakomo.ui.theme.OnSurface
import com.pakomo.ui.theme.OnSurfaceVariant
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

@Composable
fun DiagnosticsScreen(
    state: PakomoUiState,
    onBack: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding(),
    ) {
        ScreenHeader(title = "日志与诊断", onBack = onBack)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = 12.dp),
        ) {
            SectionLabel("当前状态")
            CompactDiagnosticsCard(state)

            SectionLabel("原始运行日志")
            RawLogcatPanel(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
            )
        }
    }
}

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenSecurity: () -> Unit,
) {
    var resumeLast by remember { mutableStateOf(false) }
    var showSystemWarning by remember { mutableStateOf(true) }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding(),
    ) {
        ScreenHeader(title = "设置", onBack = onBack)
        Column {
            SectionLabel("安全")
            NavigationRow(
                icon = Icons.Rounded.Security,
                title = "安全与隐私",
                onClick = onOpenSecurity,
            )
            SectionLabel("服务")
            InfoCard {
                SettingSwitchRow(
                    title = "恢复上次运行状态",
                    checked = resumeLast,
                    onCheckedChange = { resumeLast = it },
                )
                SettingSwitchRow(
                    title = "敏感应用提醒",
                    checked = showSystemWarning,
                    onCheckedChange = { showSystemWarning = it },
                )
            }
            SectionLabel("网络")
            InfoCard {
                InfoRow("IP 协议", "仅 IPv4")
                InfoRow("IPv6", "旁路，不接管")
                InfoRow("转发内核", "HEV + 本地 SOCKS5")
            }
        }
    }
}

@Composable
fun SecurityScreen(
    state: PakomoUiState,
    vpnPermissionGranted: Boolean,
    notificationPermissionGranted: Boolean,
    onBack: () -> Unit,
    onClearData: () -> Unit,
    onVpnPermissionClick: () -> Unit,
    onNotificationPermissionClick: () -> Unit,
) {
    var confirmClear by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding(),
    ) {
        ScreenHeader(title = "安全与隐私", onBack = onBack)
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp),
        ) {
            SectionLabel("授权")
            InfoCard {
                PermissionStatusRow(
                    title = "VPN 服务",
                    detail = "建立本地网络接管",
                    granted = vpnPermissionGranted,
                    onClick = onVpnPermissionClick,
                )
                PermissionStatusRow(
                    title = "通知",
                    detail = "显示 VPN 运行状态",
                    granted = notificationPermissionGranted,
                    onClick = onNotificationPermissionClick,
                )
                InfoRow("应用列表", "用于选择接管应用")
            }
            SectionLabel("本地数据")
            InfoCard {
                InfoRow("已选应用", "${state.selectedApps.size} 个")
                InfoRow(
                    "已存域名",
                    "${state.addressDomains.size + state.apps.sumOf { it.domains.size }} 个",
                )
                TextButton(
                    onClick = { confirmClear = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("清除全部本地数据", color = Danger)
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("清除全部本地数据") },
            text = { Text("已选应用、域名、规则和偏好都会被删除。此操作无法撤销。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onClearData()
                        confirmClear = false
                    },
                ) { Text("清除", color = Danger) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun PermissionStatusRow(
    title: String,
    detail: String,
    granted: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = OnSurface,
            )
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = Muted,
            )
        }
        Spacer(Modifier.padding(horizontal = 6.dp))
        Text(
            text = if (granted) "已授权" else "未授权",
            style = MaterialTheme.typography.labelMedium,
            color = if (granted) Accent else Danger,
        )
        Icon(
            imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
            contentDescription = null,
            tint = Muted,
        )
    }
}

private fun formatAttribution(stats: com.pakomo.core.model.RuntimeStats): String {
    val total = stats.attributionAttempts
    if (total <= 0) return "—"
    val hit = (total - stats.attributionMisses).coerceAtLeast(0)
    val percent = hit * 100.0 / total
    return String.format(Locale.US, "%.1f%% (%d/%d)", percent, hit, total)
}

private fun formatRate(bytesPerSecond: Long): String = when {
    bytesPerSecond >= 1_000_000 -> String.format(
        Locale.US,
        "%.1f MB/s",
        bytesPerSecond / 1_000_000.0,
    )
    bytesPerSecond >= 1_000 -> String.format(
        Locale.US,
        "%.1f KB/s",
        bytesPerSecond / 1_000.0,
    )
    else -> "$bytesPerSecond B/s"
}

@Composable
private fun CompactDiagnosticsCard(state: PakomoUiState) {
    val service = when (state.engineStage) {
        EngineStage.STOPPED -> "已停止"
        EngineStage.STARTING -> "正在启动"
        EngineStage.FORWARDING -> "运行中"
        EngineStage.ERROR -> "启动失败"
    }
    val serviceColor = when (state.engineStage) {
        EngineStage.ERROR -> Danger
        EngineStage.STOPPED -> OnSurfaceVariant
        else -> Accent
    }
    val scope = buildString {
        append(state.stats.activeScopeLabel ?: state.scope.label)
        if (state.stats.attributionAttempts > 0) {
            append(" · ")
            append(formatAttribution(state.stats))
        }
    }
    val latestHit = state.stats.recentHits.firstOrNull()
    val latestTarget = latestHit?.let { hit ->
        val app = hit.appLabel ?: hit.packageName
        buildString {
            if (!app.isNullOrBlank()) append("$app · ")
            append(hit.host)
            if (!hit.shaped) append(" · 旁路")
        }
    } ?: if (state.engineStage == EngineStage.FORWARDING) {
        "等待目标流量"
    } else {
        "未运行"
    }

    InfoCard {
        CompactMetricRow(
            leftLabel = "服务",
            leftValue = state.engineMessage?.takeIf { state.engineStage != EngineStage.FORWARDING }
                ?.let { "$service · $it" }
                ?: service,
            rightLabel = "接管范围",
            rightValue = scope,
            leftColor = serviceColor,
        )
        CompactMetricRow(
            leftLabel = "上行",
            leftValue = formatRate(state.stats.uploadBytesPerSecond),
            rightLabel = "下行",
            rightValue = formatRate(state.stats.downloadBytesPerSecond),
        )
        CompactMetricRow(
            leftLabel = "连接",
            leftValue = state.stats.activeConnections.toString(),
            rightLabel = "处理",
            rightValue = "丢弃 ${state.stats.droppedTransfers} · 延迟 ${state.stats.delayedTransfers}",
        )
        CompactValueRow(label = "最近目标", value = latestTarget)
    }
}

@Composable
private fun CompactMetricRow(
    leftLabel: String,
    leftValue: String,
    rightLabel: String,
    rightValue: String,
    leftColor: Color = OnSurface,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CompactMetric(leftLabel, leftValue, leftColor, Modifier.weight(1f))
        CompactMetric(rightLabel, rightValue, OnSurface, Modifier.weight(1f))
    }
}

@Composable
private fun CompactMetric(
    label: String,
    value: String,
    valueColor: Color,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = Muted)
        Spacer(Modifier.padding(horizontal = 4.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = valueColor,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun CompactValueRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = Muted)
        Spacer(Modifier.padding(horizontal = 4.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = OnSurface,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun InfoCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.dp, Border),
        elevation = CardDefaults.cardElevation(0.dp),
    ) {
        Column(
            modifier = Modifier.padding(vertical = 5.dp),
            content = content,
        )
    }
}

@Composable
private fun InfoRow(
    label: String,
    value: String,
    valueColor: Color = OnSurfaceVariant,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = OnSurface)
        Spacer(Modifier.padding(horizontal = 8.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = valueColor,
            fontFamily = FontFamily.Monospace,
        )
    }
}

private const val MAX_RAW_LOG_LINES = 1_000
private const val RAW_LOG_BATCH_SIZE = 32
private const val INITIAL_RAW_LOG_LINES = 80

private data class RawLogState(
    val lines: List<String> = emptyList(),
    val failure: String? = null,
    val revision: Long = 0,
)

private object RawLogcatStore {
    private val started = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(RawLogState())
    val state: StateFlow<RawLogState> = _state.asStateFlow()

    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            try {
                val process = ProcessBuilder(
                    "logcat",
                    "--pid=${android.os.Process.myPid()}",
                    "-T",
                    INITIAL_RAW_LOG_LINES.toString(),
                    "-v",
                    "brief",
                    "PakomoApp:V",
                    "PakomoState:V",
                    "PakomoLatency:V",
                    "PakomoVpn:V",
                    "PakomoSocks:V",
                    "System.err:V",
                    "AndroidRuntime:V",
                    "*:S",
                )
                    .redirectErrorStream(true)
                    .start()
                val pending = ArrayList<String>(RAW_LOG_BATCH_SIZE)
                fun flushPending() {
                    if (pending.isEmpty()) return
                    val batch = pending.toList()
                    pending.clear()
                    _state.update { current ->
                        current.copy(
                            lines = (current.lines + batch).takeLast(MAX_RAW_LOG_LINES),
                            failure = null,
                            revision = current.revision + 1,
                        )
                    }
                }
                process.inputStream.bufferedReader().use { reader ->
                    while (isActive) {
                        val line = reader.readLine() ?: break
                        if (line.startsWith("--------- beginning of") ||
                            line.startsWith("--------- switch to")
                        ) {
                            continue
                        }
                        pending.add(line)
                        if (pending.size >= RAW_LOG_BATCH_SIZE || !reader.ready()) {
                            flushPending()
                        }
                    }
                    flushPending()
                }
            } catch (error: Throwable) {
                if (isActive) {
                    _state.update { current ->
                        current.copy(
                            failure = error.message ?: error.javaClass.simpleName,
                            revision = current.revision + 1,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RawLogcatPanel(modifier: Modifier = Modifier) {
    val logState by RawLogcatStore.state.collectAsState()
    val lines = logState.lines
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = lines.lastIndex.coerceAtLeast(0),
    )
    LaunchedEffect(Unit) {
        RawLogcatStore.start()
    }

    LaunchedEffect(logState.revision) {
        if (lines.isNotEmpty()) listState.scrollToItem(lines.lastIndex)
    }

    Card(
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.dp, Border),
        elevation = CardDefaults.cardElevation(0.dp),
    ) {
        when {
            logState.failure != null -> Text(
                text = "无法读取 Logcat：${logState.failure}",
                color = Danger,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                modifier = Modifier.padding(12.dp),
            )
            lines.isEmpty() -> Text(
                text = "等待原始日志输出…",
                color = OnSurface,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                modifier = Modifier.padding(12.dp),
            )
            else -> LazyColumn(
                state = listState,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(lines) { line ->
                    Text(
                        text = line,
                        color = OnSurface,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        lineHeight = 13.sp,
                        softWrap = true,
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            color = OnSurface,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.padding(horizontal = 6.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
