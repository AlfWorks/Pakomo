package com.pakomo.ui.screens

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pakomo.BuildConfig
import com.pakomo.core.model.AppLanguage
import com.pakomo.core.model.AppListAccess
import com.pakomo.core.model.EngineStage
import com.pakomo.core.model.FlowRecord
import com.pakomo.core.model.FlowStatus
import com.pakomo.core.model.PakomoUiState
import com.pakomo.forwarding.FlowLog
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.Face
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.PictureInPictureAlt
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.WarningAmber
import com.pakomo.ui.components.ScreenHeader
import com.pakomo.ui.components.SectionLabel
import com.pakomo.ui.theme.LocalAppLanguage
import com.pakomo.ui.theme.LocalPakomoColors
import com.pakomo.ui.theme.ThemeMode
import com.pakomo.ui.theme.t
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
    var tab by rememberSaveable { mutableStateOf(0) }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding(),
    ) {
        ScreenHeader(title = t("日志与诊断", "Logs & Diagnostics"), onBack = onBack)
        DiagTabRow(
            tabs = listOf(t("诊断", "Diagnostics"), t("流量", "Traffic")),
            selected = tab,
            onSelect = { tab = it },
        )
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            when (tab) {
                0 -> DiagnosticsContent(state)
                else -> FlowsContent()
            }
        }
    }
}

@Composable
private fun DiagnosticsContent(state: PakomoUiState) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(bottom = 12.dp),
    ) {
        SectionLabel(t("当前状态", "Current status"))
        CompactDiagnosticsCard(state)

        SectionLabel(t("原始运行日志", "Raw runtime logs"))
        RawLogcatPanel(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
        )
    }
}

@Composable
private fun DiagTabRow(tabs: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    val colors = LocalPakomoColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .background(colors.scopeTrack, RoundedCornerShape(10.dp))
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        tabs.forEachIndexed { index, label ->
            val active = index == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(34.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (active) colors.surface else Color.Transparent)
                    .clickable { onSelect(index) },
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

@Composable
private fun FlowsContent() {
    val colors = LocalPakomoColors.current
    val flows by FlowLog.flows.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }
    val filtered = remember(flows, query) {
        val q = query.trim()
        if (q.isEmpty()) flows
        else flows.filter {
            it.host.contains(q, ignoreCase = true) ||
                it.port.toString().contains(q) ||
                it.protocol.contains(q, ignoreCase = true)
        }
    }
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                label = {
                    Text(
                        text = t("按主机 / 端口 / 协议筛选", "Filter host / port / proto"),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(10.dp),
            )
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = { FlowLog.clear() }) { Text(t("清空", "Clear")) }
        }
        Text(
            text = t("共 ${filtered.size} / ${flows.size} 条", "${filtered.size} / ${flows.size} flows"),
            style = MaterialTheme.typography.labelSmall,
            color = colors.muted,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 2.dp),
        )
        if (flows.isEmpty()) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(
                    text = t("开启接管后,经过 Pakomo 的连接会显示在这里。", "Connections through Pakomo appear here once capture is on."),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.muted,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(32.dp),
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 16.dp, end = 16.dp, bottom = 16.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(filtered, key = { it.id }) { flow -> FlowRow(flow) }
            }
        }
    }
}

private val FLOW_TIME_FORMAT = java.text.SimpleDateFormat("HH:mm:ss", Locale.US)

@Composable
private fun FlowRow(flow: FlowRecord) {
    val colors = LocalPakomoColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surface, RoundedCornerShape(10.dp))
            .border(1.dp, colors.border, RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 9.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            FlowTag(flow.protocol, colors.accent)
            Spacer(Modifier.width(8.dp))
            Text(
                text = "${flow.host}:${flow.port}",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.textPrimary,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = FLOW_TIME_FORMAT.format(java.util.Date(flow.startedAtMs)),
                style = MaterialTheme.typography.labelSmall,
                color = colors.muted,
                fontFamily = FontFamily.Monospace,
            )
        }
        Spacer(Modifier.height(5.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "↑ ${formatFlowBytes(flow.uploadBytes)}   ↓ ${formatFlowBytes(flow.downloadBytes)}",
                style = MaterialTheme.typography.bodySmall,
                color = colors.textSecondary,
                fontFamily = FontFamily.Monospace,
            )
            Spacer(Modifier.weight(1f))
            if (flow.held) FlowTag(t("已暂扣", "held"), colors.warningStrong)
            if (flow.shaped) {
                Spacer(Modifier.width(6.dp))
                FlowTag(t("整形", "shaped"), colors.muted)
            }
            Spacer(Modifier.width(6.dp))
            Text(
                text = if (flow.status == FlowStatus.ACTIVE) t("进行中", "active") else t("已结束", "closed"),
                style = MaterialTheme.typography.labelSmall,
                color = if (flow.status == FlowStatus.ACTIVE) colors.accent else colors.muted,
            )
        }
    }
}

@Composable
private fun FlowTag(text: String, color: Color) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = color,
        modifier = Modifier
            .background(color.copy(alpha = 0.12f), RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

private fun formatFlowBytes(bytes: Long): String = when {
    bytes >= 1_000_000 -> String.format(Locale.US, "%.1f MB", bytes / 1_000_000.0)
    bytes >= 1_000 -> String.format(Locale.US, "%.1f KB", bytes / 1_000.0)
    else -> "$bytes B"
}

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenSecurity: () -> Unit,
    onOpenLatencyTest: () -> Unit,
    onOpenAbout: () -> Unit,
    quickControlEnabled: Boolean,
    onQuickControlChanged: (Boolean) -> Unit,
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    language: AppLanguage,
    onLanguageChange: (AppLanguage) -> Unit,
) {
    var resumeLast by remember { mutableStateOf(false) }
    var showSystemWarning by remember { mutableStateOf(true) }
    val colors = LocalPakomoColors.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding(),
    ) {
        ScreenHeader(title = t("设置", "Settings"), onBack = onBack)
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(bottom = 16.dp),
        ) {
            SectionLabel(t("外观", "Appearance"), startPadding = 56.dp)
            LanguageSettingRow(language = language, onLanguageChange = onLanguageChange)
            SettingRow(
                icon = Icons.Rounded.Face,
                title = t("Pako 主题", "Pako theme"),
                subtitle = t("使用Pako主题", "Use the Pako theme"),
                onClick = {
                    onThemeModeChange(
                        if (themeMode == ThemeMode.Companion) ThemeMode.Standard else ThemeMode.Companion,
                    )
                },
                trailing = {
                    Switch(
                        checked = themeMode == ThemeMode.Companion,
                        onCheckedChange = {
                            onThemeModeChange(if (it) ThemeMode.Companion else ThemeMode.Standard)
                        },
                    )
                },
            )

            SectionLabel(t("安全", "Security"), startPadding = 56.dp)
            SettingRow(
                icon = Icons.Rounded.Security,
                title = t("安全与隐私", "Security & privacy"),
                subtitle = t("权限状态与本地数据", "Permissions and local data"),
                onClick = onOpenSecurity,
                trailing = {
                    Icon(
                        Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                        contentDescription = null,
                        tint = colors.muted,
                    )
                },
            )

            SectionLabel(t("工具", "Tools"), startPadding = 56.dp)
            SettingRow(
                icon = Icons.Rounded.Public,
                title = t("域名延迟测试", "Domain latency test"),
                subtitle = t("测试常用站点的连通与延迟", "Test reachability and latency of common sites"),
                onClick = onOpenLatencyTest,
                trailing = {
                    Icon(
                        Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                        contentDescription = null,
                        tint = colors.muted,
                    )
                },
            )

            SectionLabel(t("服务", "Service"), startPadding = 56.dp)
            SettingRow(
                icon = Icons.Rounded.RestartAlt,
                title = t("恢复上次运行状态", "Restore last session"),
                subtitle = t("启动后自动恢复上次的接管状态", "Automatically restore the last capture state on launch"),
                onClick = { resumeLast = !resumeLast },
                trailing = { Switch(checked = resumeLast, onCheckedChange = { resumeLast = it }) },
            )
            SettingRow(
                icon = Icons.Rounded.WarningAmber,
                title = t("敏感应用提醒", "Sensitive-app warning"),
                subtitle = t("接管系统或敏感应用时给出提醒", "Warn when capturing system or sensitive apps"),
                onClick = { showSystemWarning = !showSystemWarning },
                trailing = {
                    Switch(checked = showSystemWarning, onCheckedChange = { showSystemWarning = it })
                },
            )
            SettingRow(
                icon = Icons.Rounded.PictureInPictureAlt,
                title = t("快捷悬浮控制", "Floating quick control"),
                subtitle = t("显示悬浮球,随时快速开关接管", "Show a floating button to toggle capture anytime"),
                onClick = { onQuickControlChanged(!quickControlEnabled) },
                trailing = {
                    Switch(checked = quickControlEnabled, onCheckedChange = onQuickControlChanged)
                },
            )

            SectionLabel(t("更多", "More"), startPadding = 56.dp)
            SettingRow(
                icon = Icons.Rounded.Info,
                title = t("关于", "About"),
                subtitle = t("版本、系统与网络信息", "Version, system and network info"),
                onClick = onOpenAbout,
                trailing = {
                    Icon(
                        Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                        contentDescription = null,
                        tint = colors.muted,
                    )
                },
            )
        }
    }
}

@Composable
private fun LanguageSettingRow(
    language: AppLanguage,
    onLanguageChange: (AppLanguage) -> Unit,
) {
    val colors = LocalPakomoColors.current
    var expanded by remember { mutableStateOf(false) }
    SettingRow(
        icon = Icons.Rounded.Language,
        title = t("语言", "Language"),
        subtitle = t("界面显示语言", "Interface language"),
        onClick = { expanded = true },
        trailing = {
            Box {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = language.selfLabel,
                        style = MaterialTheme.typography.labelLarge,
                        color = colors.accent,
                    )
                    Icon(
                        imageVector = Icons.Rounded.ArrowDropDown,
                        contentDescription = t("选择语言", "Choose language"),
                        tint = colors.accent,
                    )
                }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    AppLanguage.entries.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option.selfLabel) },
                            onClick = {
                                expanded = false
                                onLanguageChange(option)
                            },
                        )
                    }
                }
            }
        },
    )
}

@Composable
fun AboutScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val language = LocalAppLanguage.current
    val network = remember(language) { readNetworkSummary(context, language) }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding(),
    ) {
        ScreenHeader(title = t("关于", "About"), onBack = onBack)
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(bottom = 16.dp),
        ) {
            SectionLabel(t("应用与系统", "App & system"))
            SettingRow(
                title = t("应用版本", "App version"),
                subtitle = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
            )
            SettingRow(
                title = "Android",
                subtitle = "${Build.VERSION.RELEASE} · API ${Build.VERSION.SDK_INT}",
            )
            SettingRow(
                title = t("设备", "Device"),
                subtitle = "${Build.MANUFACTURER} ${Build.MODEL}",
            )

            SectionLabel(t("网络", "Network"))
            SettingRow(
                title = t("当前连接", "Current connection"),
                subtitle = "${network.connection} · ${network.protocols}",
            )
            SettingRow(
                title = "DNS",
                subtitle = network.dns,
            )
        }
    }
}

private data class NetworkSummary(
    val connection: String,
    val protocols: String,
    val dns: String,
)

private fun readNetworkSummary(context: Context, language: AppLanguage): NetworkSummary {
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
    val physicalLabel = transportLabel(physicalCapabilities, language)
    val connection = when {
        activeCapabilities == null -> language.tr("未连接", "Not connected")
        activeCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
            physicalLabel != null -> "VPN · $physicalLabel"
        else -> transportLabel(activeCapabilities, language) ?: language.tr("已连接", "Connected")
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

private fun transportLabel(capabilities: NetworkCapabilities?, language: AppLanguage): String? = when {
    capabilities == null -> null
    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> language.tr("移动网络", "Cellular")
    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> language.tr("以太网", "Ethernet")
    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
    else -> language.tr("其他网络", "Other network")
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
        ScreenHeader(title = t("安全与隐私", "Security & privacy"), onBack = onBack)
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp),
        ) {
            val granted = t("已授权", "Granted")
            val notGranted = t("未授权", "Not granted")
            SectionLabel(t("授权", "Permissions"))
            InfoCard {
                PermissionStatusRow(
                    title = t("VPN 服务", "VPN service"),
                    detail = t("建立本地网络接管", "Establishes local network capture"),
                    status = if (vpnPermissionGranted) granted else notGranted,
                    statusColor = if (vpnPermissionGranted) colors.accent else colors.danger,
                    onClick = onVpnPermissionClick,
                )
                PermissionStatusRow(
                    title = t("通知", "Notifications"),
                    detail = t("显示 VPN 运行状态", "Shows the VPN running status"),
                    status = if (notificationPermissionGranted) granted else notGranted,
                    statusColor = if (notificationPermissionGranted) colors.accent else colors.danger,
                    onClick = onNotificationPermissionClick,
                )
                PermissionStatusRow(
                    title = t("应用列表", "App list"),
                    detail = t("用于选择接管应用", "Used to pick apps to capture"),
                    status = when (appListAccess) {
                        AppListAccess.CHECKING -> t("检查中", "Checking")
                        AppListAccess.AVAILABLE -> t("可用", "Available")
                        AppListAccess.UNAVAILABLE -> t("不可用", "Unavailable")
                    },
                    statusColor = when (appListAccess) {
                        AppListAccess.CHECKING -> colors.muted
                        AppListAccess.AVAILABLE -> colors.accent
                        AppListAccess.UNAVAILABLE -> colors.danger
                    },
                    onClick = onAppListPermissionClick,
                )
            }
            SectionLabel(t("本地数据", "Local data"))
            InfoCard {
                InfoRow(t("已选应用", "Selected apps"), t("${state.selectedApps.size} 个", "${state.selectedApps.size}"))
                InfoRow(
                    t("已存域名", "Saved domains"),
                    (state.addressDomains.size + state.apps.sumOf { it.domains.size }).let {
                        t("$it 个", "$it")
                    },
                )
                TextButton(
                    onClick = { confirmClear = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(t("清除全部本地数据", "Clear all local data"), color = colors.danger)
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(t("清除全部本地数据", "Clear all local data")) },
            text = {
                Text(
                    t(
                        "已选应用、域名、规则和偏好都会被删除。此操作无法撤销。",
                        "Selected apps, domains, rules and preferences will all be deleted. This cannot be undone.",
                    ),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onClearData()
                        confirmClear = false
                    },
                ) { Text(t("清除", "Clear"), color = colors.danger) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text(t("取消", "Cancel")) }
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
        EngineStage.STOPPED -> t("已停止", "Stopped")
        EngineStage.STARTING -> t("正在启动", "Starting")
        EngineStage.FORWARDING -> t("运行中", "Running")
        EngineStage.ERROR -> t("启动失败", "Failed to start")
    }
    val colors = LocalPakomoColors.current
    val serviceColor = when (state.engineStage) {
        EngineStage.ERROR -> colors.danger
        EngineStage.STOPPED -> colors.textSecondary
        else -> colors.accent
    }
    val language = LocalAppLanguage.current
    val scope = buildString {
        append(state.stats.activeScopeLabel ?: state.scope.label(language))
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
            if (!hit.shaped) append(" · ").append(t("旁路", "bypass"))
        }
    } ?: if (state.engineStage == EngineStage.FORWARDING) {
        t("等待目标流量", "Waiting for target traffic")
    } else {
        t("未运行", "Not running")
    }

    InfoCard {
        CompactMetricRow(
            leftLabel = t("服务", "Service"),
            leftValue = state.engineMessage?.takeIf { state.engineStage != EngineStage.FORWARDING }
                ?.let { "$service · $it" }
                ?: service,
            rightLabel = t("接管范围", "Capture scope"),
            rightValue = scope,
            leftColor = serviceColor,
        )
        CompactMetricRow(
            leftLabel = t("上行", "Upload"),
            leftValue = formatRate(state.stats.uploadBytesPerSecond),
            rightLabel = t("下行", "Download"),
            rightValue = formatRate(state.stats.downloadBytesPerSecond),
        )
        CompactMetricRow(
            leftLabel = t("连接", "Connections"),
            leftValue = state.stats.activeConnections.toString(),
            rightLabel = t("处理", "Handled"),
            rightValue = t(
                "丢弃 ${state.stats.droppedTransfers} · 延迟 ${state.stats.delayedTransfers}",
                "dropped ${state.stats.droppedTransfers} · delayed ${state.stats.delayedTransfers}",
            ),
        )
        CompactValueRow(label = t("最近目标", "Latest target"), value = latestTarget)
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
                text = t("无法读取 Logcat：", "Unable to read Logcat: ") + logState.failure,
                color = colors.danger,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                modifier = Modifier.padding(12.dp),
            )
            lines.isEmpty() -> Text(
                text = t("等待原始日志输出…", "Waiting for raw log output…"),
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

/**
 * 统一的设置行(CMFA 风格):可选左图标 + 标题 + 副标题说明 + 右侧控件(开关 / 值 / 箭头)。
 * [icon] 为空时不画图标也不占位(当前每个列表要么全有图标、要么全无图标,不混排);副标题始终与标题同列。
 * [onClick] 非空则整行可点(开关行整行可切,开关只作指示,兼顾触摸目标)。
 */
@Composable
private fun SettingRow(
    title: String,
    subtitle: String? = null,
    icon: ImageVector? = null,
    onClick: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    val colors = LocalPakomoColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = colors.textSecondary,
                modifier = Modifier.size(24.dp),
            )
            Spacer(Modifier.width(16.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.textPrimary,
            )
            if (subtitle != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.muted,
                )
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(12.dp))
            trailing()
        }
    }
}
