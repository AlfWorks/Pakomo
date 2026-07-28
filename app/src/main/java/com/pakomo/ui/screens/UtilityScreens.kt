package com.pakomo.ui.screens

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pakomo.BuildConfig
import com.pakomo.core.model.AppListAccess
import com.pakomo.core.model.EngineStage
import com.pakomo.core.model.PakomoUiState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Security
import com.pakomo.ui.components.NavigationRow
import com.pakomo.ui.components.ScreenHeader
import com.pakomo.ui.components.SectionLabel
import com.pakomo.ui.theme.LocalPakomoColors
import com.pakomo.ui.theme.ThemeMode
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
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
    quickControlEnabled: Boolean,
    onQuickControlChanged: (Boolean) -> Unit,
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
) {
    var resumeLast by remember { mutableStateOf(false) }
    var showSystemWarning by remember { mutableStateOf(true) }
    val context = LocalContext.current
    val network = remember { readNetworkSummary(context) }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding(),
    ) {
        ScreenHeader(title = "设置", onBack = onBack)
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(bottom = 16.dp),
        ) {
            SectionLabel("外观")
            ThemeModeSelector(current = themeMode, onChange = onThemeModeChange)
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
                SettingSwitchRow(
                    title = "快捷悬浮控制",
                    checked = quickControlEnabled,
                    onCheckedChange = onQuickControlChanged,
                )
            }
            SectionLabel("应用与系统")
            InfoCard {
                InfoRow(
                    "应用版本",
                    "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                )
                InfoRow("Android", "${Build.VERSION.RELEASE} · API ${Build.VERSION.SDK_INT}")
                InfoRow("设备", "${Build.MANUFACTURER} ${Build.MODEL}")
            }
            SectionLabel("网络")
            InfoCard {
                InfoRow("当前连接", "${network.connection} · ${network.protocols}")
                InfoRow("DNS", network.dns)
            }
        }
    }
}

@Composable
private fun ThemeModeSelector(current: ThemeMode, onChange: (ThemeMode) -> Unit) {
    val colors = LocalPakomoColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .background(colors.scopeTrack, RoundedCornerShape(10.dp))
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        listOf(
            ThemeMode.Standard to "标准",
            ThemeMode.Companion to "Pakomo 陪伴",
        ).forEach { (mode, label) ->
            val active = mode == current
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(38.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (active) colors.surface else Color.Transparent)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        role = Role.RadioButton,
                    ) { onChange(mode) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    color = if (active) colors.accent else colors.textSecondary,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                    fontSize = 13.sp,
                )
            }
        }
    }
}

private data class NetworkSummary(
    val connection: String,
    val protocols: String,
    val dns: String,
)

private fun readNetworkSummary(context: Context): NetworkSummary {
    val manager = context.getSystemService(ConnectivityManager::class.java)
    val activeNetwork = manager.activeNetwork
    val activeCapabilities = activeNetwork?.let(manager::getNetworkCapabilities)
    val physicalNetwork = if (
        activeCapabilities?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
    ) {
        manager.allNetworks.firstOrNull { network ->
            val capabilities = manager.getNetworkCapabilities(network)
            capabilities != null &&
                !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }
    } else {
        activeNetwork
    }
    val physicalCapabilities = physicalNetwork?.let(manager::getNetworkCapabilities)
    val physicalLabel = transportLabel(physicalCapabilities)
    val connection = when {
        activeCapabilities == null -> "未连接"
        activeCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
            physicalLabel != null -> "VPN · $physicalLabel"
        else -> transportLabel(activeCapabilities) ?: "已连接"
    }
    val linkProperties = physicalNetwork?.let(manager::getLinkProperties)
    val addresses = linkProperties?.linkAddresses.orEmpty().map { it.address }
    val hasIpv4 = addresses.any { it.address.size == 4 }
    val hasIpv6 = addresses.any { it.address.size == 16 }
    val protocols = when {
        hasIpv4 && hasIpv6 -> "IPv4 · IPv6"
        hasIpv4 -> "IPv4"
        hasIpv6 -> "IPv6"
        else -> "—"
    }
    val dns = linkProperties?.dnsServers
        ?.take(2)
        ?.joinToString(" · ") { it.hostAddress.orEmpty() }
        ?.takeIf(String::isNotBlank)
        ?: "—"
    return NetworkSummary(connection, protocols, dns)
}

private fun transportLabel(capabilities: NetworkCapabilities?): String? = when {
    capabilities == null -> null
    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "移动网络"
    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "以太网"
    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
    else -> "其他网络"
}

@Composable
fun SecurityScreen(
    state: PakomoUiState,
    vpnPermissionGranted: Boolean,
    notificationPermissionGranted: Boolean,
    appListAccess: AppListAccess,
    onBack: () -> Unit,
    onClearData: () -> Unit,
    onVpnPermissionClick: () -> Unit,
    onNotificationPermissionClick: () -> Unit,
    onAppListPermissionClick: () -> Unit,
) {
    val colors = LocalPakomoColors.current
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
                    status = if (vpnPermissionGranted) "已授权" else "未授权",
                    statusColor = if (vpnPermissionGranted) colors.accent else colors.danger,
                    onClick = onVpnPermissionClick,
                )
                PermissionStatusRow(
                    title = "通知",
                    detail = "显示 VPN 运行状态",
                    status = if (notificationPermissionGranted) "已授权" else "未授权",
                    statusColor = if (notificationPermissionGranted) colors.accent else colors.danger,
                    onClick = onNotificationPermissionClick,
                )
                PermissionStatusRow(
                    title = "应用列表",
                    detail = "用于选择接管应用",
                    status = when (appListAccess) {
                        AppListAccess.CHECKING -> "检查中"
                        AppListAccess.AVAILABLE -> "可用"
                        AppListAccess.UNAVAILABLE -> "不可用"
                    },
                    statusColor = when (appListAccess) {
                        AppListAccess.CHECKING -> colors.muted
                        AppListAccess.AVAILABLE -> colors.accent
                        AppListAccess.UNAVAILABLE -> colors.danger
                    },
                    onClick = onAppListPermissionClick,
                )
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
                    Text("清除全部本地数据", color = colors.danger)
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
                ) { Text("清除", color = colors.danger) }
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
    status: String,
    statusColor: Color,
    onClick: (() -> Unit)?,
) {
    val colors = LocalPakomoColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.textPrimary,
            )
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = colors.muted,
            )
        }
        Spacer(Modifier.padding(horizontal = 6.dp))
        Text(
            text = status,
            style = MaterialTheme.typography.labelMedium,
            color = statusColor,
        )
        if (onClick != null) {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = null,
                tint = colors.muted,
            )
        }
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
    val colors = LocalPakomoColors.current
    val serviceColor = when (state.engineStage) {
        EngineStage.ERROR -> colors.danger
        EngineStage.STOPPED -> colors.textSecondary
        else -> colors.accent
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
    leftColor: Color = LocalPakomoColors.current.textPrimary,
) {
    val colors = LocalPakomoColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CompactMetric(leftLabel, leftValue, leftColor, Modifier.weight(1f))
        CompactMetric(rightLabel, rightValue, colors.textPrimary, Modifier.weight(1f))
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
        Text(label, style = MaterialTheme.typography.labelSmall, color = LocalPakomoColors.current.muted)
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
    val colors = LocalPakomoColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = colors.muted)
        Spacer(Modifier.padding(horizontal = 4.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = colors.textPrimary,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun InfoCard(content: @Composable ColumnScope.() -> Unit) {
    val colors = LocalPakomoColors.current
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = colors.surface),
        border = BorderStroke(1.dp, colors.border),
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
    valueColor: Color = LocalPakomoColors.current.textSecondary,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = LocalPakomoColors.current.textPrimary)
        Spacer(Modifier.padding(horizontal = 8.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = valueColor,
            fontFamily = FontFamily.Monospace,
            textAlign = TextAlign.End,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
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
    val colors = LocalPakomoColors.current
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
        colors = CardDefaults.cardColors(containerColor = colors.surface),
        border = BorderStroke(1.dp, colors.border),
        elevation = CardDefaults.cardElevation(0.dp),
    ) {
        when {
            logState.failure != null -> Text(
                text = "无法读取 Logcat：${logState.failure}",
                color = colors.danger,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                modifier = Modifier.padding(12.dp),
            )
            lines.isEmpty() -> Text(
                text = "等待原始日志输出…",
                color = colors.textPrimary,
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
                        color = colors.textPrimary,
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
            color = LocalPakomoColors.current.textPrimary,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.padding(horizontal = 6.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
