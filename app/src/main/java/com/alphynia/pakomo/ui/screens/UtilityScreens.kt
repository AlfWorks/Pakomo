package com.alphynia.pakomo.ui.screens

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alphynia.pakomo.BuildConfig
import com.alphynia.pakomo.PakomoApplication
import com.alphynia.pakomo.update.UpdateCheckStatus
import com.alphynia.pakomo.core.model.AppLanguage
import com.alphynia.pakomo.core.model.AppListAccess
import com.alphynia.pakomo.core.model.EngineStage
import com.alphynia.pakomo.core.model.FlowRecord
import com.alphynia.pakomo.core.model.FlowStatus
import com.alphynia.pakomo.core.model.PakomoUiState
import com.alphynia.pakomo.forwarding.FlowLog
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Face
import androidx.compose.material.icons.rounded.FilterList
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.PictureInPictureAlt
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.WarningAmber
import com.alphynia.pakomo.ui.components.ScreenHeader
import com.alphynia.pakomo.ui.components.SectionLabel
import com.alphynia.pakomo.ui.theme.LocalAppLanguage
import com.alphynia.pakomo.ui.theme.LocalPakomoColors
import com.alphynia.pakomo.ui.theme.ThemeMode
import com.alphynia.pakomo.ui.theme.t
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

private val BYTE_THRESHOLDS = listOf(0L, 1_024L, 10_240L, 102_400L, 1_048_576L, 10_485_760L)
private val BYTE_LABELS = listOf("0 B", "1 KB", "10 KB", "100 KB", "1 MB", "10 MB")

@Composable
fun DiagnosticsScreen(
    state: PakomoUiState,
    onBack: () -> Unit,
) {
    val colors = LocalPakomoColors.current
    var tab by rememberSaveable { mutableStateOf(0) }
    // Traffic-tab filter, lifted so search/filter live in the top bar (PCAPdroid-style).
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var showFilter by rememberSaveable { mutableStateOf(false) }
    var showActive by rememberSaveable { mutableStateOf(true) }
    var showClosed by rememberSaveable { mutableStateOf(true) }
    var shapedOnly by rememberSaveable { mutableStateOf(false) }
    var heldOnly by rememberSaveable { mutableStateOf(false) }
    var minBytesIdx by rememberSaveable { mutableStateOf(0) }
    val onTraffic = tab == 0
    val filterActive = !showActive || !showClosed || shapedOnly || heldOnly || minBytesIdx > 0
    // Auto-focus the search field (and pop the IME) the moment search opens.
    val searchFocus = remember { FocusRequester() }
    LaunchedEffect(searching) {
        if (searching) runCatching { searchFocus.requestFocus() }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding(),
    ) {
        ScreenHeader(
            title = if (onTraffic) t("流量", "Traffic") else t("诊断", "Diagnostics"),
            onBack = if (searching) ({ searching = false; query = "" }) else onBack,
            titleContent = if (searching && onTraffic) {
                {
                    androidx.compose.foundation.text.BasicTextField(
                        value = query,
                        onValueChange = { query = it },
                        singleLine = true,
                        textStyle = androidx.compose.ui.text.TextStyle(
                            color = colors.textPrimary,
                            fontSize = 16.sp,
                        ),
                        cursorBrush = androidx.compose.ui.graphics.SolidColor(colors.accent),
                        decorationBox = { inner ->
                            if (query.isEmpty()) {
                                Text(
                                    t("搜索主机 / 端口 / 协议", "Search host / port / proto"),
                                    color = colors.muted,
                                    fontSize = 16.sp,
                                )
                            }
                            inner()
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(searchFocus),
                    )
                }
            } else {
                null
            },
            action = {
                if (onTraffic) {
                    if (searching) {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { query = "" }) {
                                Icon(Icons.Rounded.Close, t("清除", "Clear"), tint = colors.textSecondary)
                            }
                        }
                    } else {
                        IconButton(onClick = { searching = true }) {
                            Icon(Icons.Rounded.Search, t("搜索", "Search"), tint = colors.textPrimary)
                        }
                    }
                    IconButton(onClick = { showFilter = true }) {
                        Icon(
                            Icons.Rounded.FilterList,
                            t("过滤", "Filter"),
                            tint = if (filterActive) colors.accent else colors.textPrimary,
                        )
                    }
                }
            },
        )
        DiagTabRow(
            tabs = listOf(t("流量", "Traffic"), t("诊断", "Diagnostics")),
            selected = tab,
            onSelect = { tab = it },
        )
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            when (tab) {
                0 -> FlowsContent(query, showActive, showClosed, shapedOnly, heldOnly, BYTE_THRESHOLDS[minBytesIdx])
                else -> DiagnosticsContent(state)
            }
        }
    }

    if (showFilter) {
        TrafficFilterDialog(
            showActive = showActive, showClosed = showClosed,
            shapedOnly = shapedOnly, heldOnly = heldOnly, minBytesIdx = minBytesIdx,
            onChange = { a, c, s, h, b ->
                showActive = a; showClosed = c; shapedOnly = s; heldOnly = h; minBytesIdx = b
            },
            onReset = {
                showActive = true; showClosed = true; shapedOnly = false; heldOnly = false; minBytesIdx = 0
            },
            onDismiss = { showFilter = false },
        )
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
private fun FlowsContent(
    query: String,
    showActive: Boolean,
    showClosed: Boolean,
    shapedOnly: Boolean,
    heldOnly: Boolean,
    minBytes: Long,
) {
    val colors = LocalPakomoColors.current
    val flows by FlowLog.flows.collectAsState()
    // Tapping a row opens its detail sheet. Keep the id (not the snapshot) so the sheet tracks the
    // live record as pulses update it, and closes on its own if the flow is evicted from the ring.
    var selectedId by remember { mutableStateOf<Long?>(null) }
    val selectedFlow = selectedId?.let { id -> flows.firstOrNull { it.id == id } }
    val filtered = remember(flows, query, showActive, showClosed, shapedOnly, heldOnly, minBytes) {
        val q = query.trim()
        flows.filter { f ->
            (q.isEmpty() ||
                f.host.contains(q, ignoreCase = true) ||
                f.port.toString().contains(q) ||
                f.protocol.contains(q, ignoreCase = true)) &&
                (if (f.status == FlowStatus.ACTIVE) showActive else showClosed) &&
                (!shapedOnly || f.shaped) &&
                (!heldOnly || f.held) &&
                (f.uploadBytes + f.downloadBytes >= minBytes)
        }
    }
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = t("共 ${filtered.size} / ${flows.size} 条", "${filtered.size} / ${flows.size} flows"),
                style = MaterialTheme.typography.labelSmall,
                color = colors.muted,
                modifier = Modifier.weight(1f),
            )
            if (flows.isNotEmpty()) {
                Icon(
                    Icons.Rounded.DeleteSweep,
                    contentDescription = t("清空", "Clear all"),
                    tint = colors.muted,
                    modifier = Modifier
                        .size(18.dp)
                        .clip(RoundedCornerShape(50))
                        .clickable { FlowLog.clear() },
                )
            }
        }
        when {
            flows.isEmpty() -> EmptyFlowHint(
                t("开启接管后,经过 Pakomo 的连接会显示在这里。", "Connections through Pakomo appear here once capture is on."),
            )
            filtered.isEmpty() -> EmptyFlowHint(t("没有符合筛选条件的连接。", "No flows match the current filter."))
            else -> LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 16.dp, end = 16.dp, bottom = 16.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(filtered, key = { it.id }) { flow ->
                    FlowRow(flow, onClick = { selectedId = flow.id })
                }
            }
        }
    }
    if (selectedFlow != null) {
        FlowDetailSheet(flow = selectedFlow, onDismiss = { selectedId = null })
    }
}

@Composable
private fun ColumnScope.EmptyFlowHint(text: String) {
    val colors = LocalPakomoColors.current
    Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = colors.muted,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(32.dp),
        )
    }
}

@Composable
private fun TrafficFilterDialog(
    showActive: Boolean,
    showClosed: Boolean,
    shapedOnly: Boolean,
    heldOnly: Boolean,
    minBytesIdx: Int,
    onChange: (Boolean, Boolean, Boolean, Boolean, Int) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalPakomoColors.current
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(colors.surface)
                .border(1.dp, colors.border, RoundedCornerShape(20.dp))
                .padding(20.dp),
        ) {
            Text(
                t("过滤流量", "Filter flows"),
                style = MaterialTheme.typography.titleMedium,
                color = colors.textPrimary,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(18.dp))

            Text(t("状态", "Status"), style = MaterialTheme.typography.labelMedium, color = colors.textSecondary)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterPill(t("活跃", "Active"), showActive) {
                    onChange(!showActive, showClosed, shapedOnly, heldOnly, minBytesIdx)
                }
                FilterPill(t("已关闭", "Closed"), showClosed) {
                    onChange(showActive, !showClosed, shapedOnly, heldOnly, minBytesIdx)
                }
            }
            Spacer(Modifier.height(16.dp))

            Text(t("标记", "Flags"), style = MaterialTheme.typography.labelMedium, color = colors.textSecondary)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterPill(t("已整形", "Shaped"), shapedOnly) {
                    onChange(showActive, showClosed, !shapedOnly, heldOnly, minBytesIdx)
                }
                FilterPill(t("已暂扣", "Held"), heldOnly) {
                    onChange(showActive, showClosed, shapedOnly, !heldOnly, minBytesIdx)
                }
            }
            Spacer(Modifier.height(16.dp))

            Text(
                t("字节阈值 ≥ ", "Min bytes ≥ ") + BYTE_LABELS[minBytesIdx],
                style = MaterialTheme.typography.labelMedium,
                color = colors.textSecondary,
            )
            Slider(
                value = minBytesIdx.toFloat(),
                onValueChange = {
                    onChange(
                        showActive, showClosed, shapedOnly, heldOnly,
                        it.roundToInt().coerceIn(0, BYTE_THRESHOLDS.size - 1),
                    )
                },
                valueRange = 0f..(BYTE_THRESHOLDS.size - 1).toFloat(),
                steps = BYTE_THRESHOLDS.size - 2,
                colors = androidx.compose.material3.SliderDefaults.colors(
                    thumbColor = colors.accent,
                    activeTrackColor = colors.accent,
                    inactiveTrackColor = colors.scopeTrack,
                ),
            )
            Spacer(Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    t("重置", "Reset"),
                    color = colors.textSecondary,
                    fontSize = 14.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(onClick = onReset)
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    t("完成", "Done"),
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(colors.accent)
                        .clickable(onClick = onDismiss)
                        .padding(horizontal = 20.dp, vertical = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun FilterPill(label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = LocalPakomoColors.current
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) colors.accentTint else colors.scopeTrack)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
    ) {
        Text(
            text = label,
            color = if (selected) colors.accent else colors.textSecondary,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

private val FLOW_TIME_FORMAT = java.text.SimpleDateFormat("HH:mm:ss", Locale.US)

@Composable
private fun FlowRow(flow: FlowRecord, onClick: () -> Unit) {
    val colors = LocalPakomoColors.current
    val context = LocalContext.current
    // Owning-app icon shown as a small tag on the stats row (below) — not the host row — so the source
    // is identifiable without crowding the host and without overlap. Loaded off the main thread: a
    // cache hit shows instantly, a miss (getApplicationIcon + rasterize) resolves in the background so
    // it never blocks a scroll frame.
    var iconState by remember(flow.pkg) { mutableStateOf(flowIconCache[flow.pkg]) }
    if (iconState == null && flow.pkg.isNotEmpty()) {
        // Cache miss only: decode off the main thread. A cache hit needs no coroutine at all, so a
        // fast fling doesn't launch (and dispatch) one per row.
        LaunchedEffect(flow.pkg) {
            iconState = withContext(Dispatchers.Default) { appIconBitmap(context, flow.pkg) }
        }
    }
    val icon = iconState
    Column(
        modifier = Modifier
            .fillMaxWidth()
            // Clip so the tap ripple is bounded to the rounded card; the row is tappable to open its
            // detail sheet.
            .clip(RoundedCornerShape(10.dp))
            .background(colors.surface)
            .border(1.dp, colors.border, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 9.dp),
    ) {
            Row(verticalAlignment = Alignment.Top) {
                FlowTag(flow.protocol, colors.accent)
                Spacer(Modifier.width(8.dp))
                // Address fixed to two lines: the subdomain prefix on top (dimmed), the registrable
                // domain + port below (emphasized). A long host never overflows — its prefix line
                // ellipsizes — and the important "where it went" keeps its own bold line.
                val hostParts = remember(flow.host) { splitHost(flow.host) }
                val coreText = remember(flow.host, flow.port, colors) {
                    buildAnnotatedString {
                        withStyle(SpanStyle(color = colors.textPrimary, fontWeight = FontWeight.Medium)) {
                            append(hostParts.core)
                        }
                        withStyle(SpanStyle(color = colors.muted)) { append(":${flow.port}") }
                    }
                }
                Column(modifier = Modifier.weight(1f)) {
                    if (hostParts.prefix.isNotEmpty()) {
                        Text(
                            text = hostParts.prefix,
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.muted,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Text(
                        text = coreText,
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.height(5.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                val iconBitmap = icon
                if (iconBitmap != null) {
                    Image(
                        bitmap = iconBitmap,
                        contentDescription = flow.pkg,
                        modifier = Modifier
                            .size(18.dp)
                            .clip(RoundedCornerShape(5.dp)),
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    text = "↑ ${formatFlowBytes(flow.uploadBytes)}   ↓ ${formatFlowBytes(flow.downloadBytes)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    softWrap = false,
                )
                Spacer(Modifier.weight(1f))
                if (flow.held) FlowTag(t("已暂扣", "held"), colors.warningStrong)
                if (flow.shaped) {
                    Spacer(Modifier.width(6.dp))
                    FlowTag(t("整形", "shaped"), colors.muted)
                }
                Spacer(Modifier.width(8.dp))
                // Status as a small colour dot (accent = active, muted = closed) rather than a text
                // label — saves the width that was squeezing bytes and the timestamp.
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .background(
                            if (flow.status == FlowStatus.ACTIVE) colors.accent else colors.muted,
                            RoundedCornerShape(50),
                        ),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = FLOW_TIME_FORMAT.format(java.util.Date(flow.startedAtMs)),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.muted,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
    }

private val flowIconCache = java.util.concurrent.ConcurrentHashMap<String, androidx.compose.ui.graphics.ImageBitmap>()

/** The owning app's launcher icon as an [ImageBitmap], cached by package; null when unavailable. */
private fun appIconBitmap(context: android.content.Context, pkg: String): androidx.compose.ui.graphics.ImageBitmap? {
    if (pkg.isEmpty()) return null
    flowIconCache[pkg]?.let { return it }
    val bitmap = runCatching {
        val drawable = context.packageManager.getApplicationIcon(pkg)
        val px = 72
        val bmp = android.graphics.Bitmap.createBitmap(px, px, android.graphics.Bitmap.Config.ARGB_8888)
        drawable.setBounds(0, 0, px, px)
        drawable.draw(android.graphics.Canvas(bmp))
        bmp.asImageBitmap()
    }.getOrNull() ?: return null
    if (flowIconCache.size >= 128) flowIconCache.clear()
    flowIconCache[pkg] = bitmap
    return bitmap
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

private val FLOW_DETAIL_TIME_FORMAT =
    java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", java.util.Locale.US)
private val appLabelCache = java.util.concurrent.ConcurrentHashMap<String, String>()

/** The owning app's user-visible label, cached by package; falls back to the package name. */
private fun appLabelFor(context: android.content.Context, pkg: String): String {
    if (pkg.isEmpty()) return ""
    appLabelCache[pkg]?.let { return it }
    val label = runCatching {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrNull() ?: pkg
    appLabelCache[pkg] = label
    return label
}

/** A coarse layer-7 guess from the destination port, for the detail view's protocol line. */
private fun l7ForPort(port: Int): String? = when (port) {
    443 -> "HTTPS"
    80, 8080 -> "HTTP"
    53 -> "DNS"
    22 -> "SSH"
    21 -> "FTP"
    25, 465, 587 -> "SMTP"
    993 -> "IMAPS"
    995 -> "POP3S"
    else -> null
}

private fun flowProtocolLabel(flow: FlowRecord): String {
    val l7 = l7ForPort(flow.port)
    return if (l7 != null && l7 != flow.protocol) "$l7 (${flow.protocol})" else flow.protocol
}

private fun flowDurationText(flow: FlowRecord): String {
    val end = if (flow.closedAtMs > 0) flow.closedAtMs else System.currentTimeMillis()
    val ms = (end - flow.startedAtMs).coerceAtLeast(0)
    return if (ms < 1000) "$ms ms" else String.format(java.util.Locale.US, "%.1f s", ms / 1000.0)
}

/** Tap-to-open connection details, shown as a bottom sheet over the list (no page switch). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FlowDetailSheet(flow: FlowRecord, onDismiss: () -> Unit) {
    val colors = LocalPakomoColors.current
    val context = LocalContext.current
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = colors.surface) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
        ) {
            // Header = the owning app (icon + name), identical for IP and domain flows. All of the
            // destination info lives in labelled rows below, so the two look consistent.
            Row(verticalAlignment = Alignment.CenterVertically) {
                val appIcon = remember(flow.pkg) { appIconBitmap(context, flow.pkg) }
                if (appIcon != null) {
                    Image(
                        bitmap = appIcon,
                        contentDescription = null,
                        modifier = Modifier
                            .size(32.dp)
                            .clip(RoundedCornerShape(8.dp)),
                    )
                    Spacer(Modifier.width(12.dp))
                }
                Text(
                    text = appLabelFor(context, flow.pkg).ifEmpty { t("未知", "Unknown") },
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.textPrimary,
                )
            }
            Spacer(Modifier.height(16.dp))

            DetailRow(t("协议", "Protocol"), flowProtocolLabel(flow))
            if (flow.sourceIp.isNotEmpty()) {
                DetailRow(t("源", "Source"), "${flow.sourceIp}:${flow.sourcePort}", copyable = true)
            }
            // Only present when a name is known (host differs from the raw IP): the requested domain,
            // from TLS SNI / HTTP Host sniffing or observed DNS. Split into two lines like the list.
            if (flow.destIp.isNotEmpty() && flow.destIp != flow.host) {
                DetailHostRow(t("SNI / 域名", "SNI / Host"), flow.host)
            }
            // Always shown so IP flows have a destination too: the real endpoint IP:port.
            DetailRow(
                t("目的地", "Destination"),
                "${flow.destIp.ifEmpty { flow.host }}:${flow.port}",
                copyable = true,
            )
            DetailRow(
                t("状态", "Status"),
                if (flow.status == FlowStatus.ACTIVE) t("进行中", "Active") else t("已结束", "Closed"),
                mono = false,
            )

            // DNS flows carry many lookups over one association; list each queried domain and the
            // IPs it resolved to here.
            if (flow.dnsQueries.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                HorizontalDivider(color = colors.border)
                Spacer(Modifier.height(10.dp))
                Text(
                    text = t("查询域名 (${flow.dnsQueries.size})", "Queried (${flow.dnsQueries.size})"),
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.textSecondary,
                )
                Spacer(Modifier.height(6.dp))
                val copyQuery = rememberCopyAction()
                flow.dnsQueries.forEach { query ->
                    // Tap a queried domain to copy it — e.g. to paste into a Pakomo rule.
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { copyQuery(query.name) }
                            .padding(top = 4.dp, bottom = 1.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = query.name,
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.textPrimary,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.weight(1f),
                        )
                        CopyHint()
                    }
                    if (query.ips.isNotEmpty()) {
                        Text(
                            text = "→ ${query.ips.joinToString("   ")}",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.accent,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(start = 12.dp),
                        )
                    }
                }
            }

            Spacer(Modifier.height(10.dp))
            HorizontalDivider(color = colors.border)
            Spacer(Modifier.height(10.dp))

            DetailRow(
                t("流量", "Traffic"),
                "↑ ${formatFlowBytes(flow.uploadBytes)}   ↓ ${formatFlowBytes(flow.downloadBytes)}",
            )
            DetailRow(t("载荷", "Payload"), formatFlowBytes(flow.uploadBytes + flow.downloadBytes))
            DetailRow(t("持续时间", "Duration"), flowDurationText(flow))
            DetailRow(t("第一次见", "First seen"), FLOW_DETAIL_TIME_FORMAT.format(java.util.Date(flow.startedAtMs)))

            Spacer(Modifier.height(10.dp))
            HorizontalDivider(color = colors.border)
            Spacer(Modifier.height(10.dp))

            Text(
                text = t("Pakomo 处理", "Pakomo effects"),
                style = MaterialTheme.typography.labelMedium,
                color = colors.textSecondary,
            )
            Spacer(Modifier.height(6.dp))
            DetailRow(t("整形", "Shaped"), if (flow.shaped) t("是", "Yes") else t("否", "No"), mono = false)
            DetailRow(t("暂扣", "Held"), if (flow.held) t("是", "Yes") else t("否", "No"), mono = false)
        }
    }
}

/** Returns a copy action: writes [text] to the clipboard and confirms with a toast. */
@Composable
private fun rememberCopyAction(): (String) -> Unit {
    val context = LocalContext.current
    val copiedLabel = t("已复制", "Copied")
    return remember(context, copiedLabel) {
        { text: String ->
            val clip = context.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
            clip?.setPrimaryClip(android.content.ClipData.newPlainText("Pakomo", text))
            // Android 13+ shows its own copy confirmation; don't stack a second toast on top.
            if (Build.VERSION.SDK_INT < 33) {
                Toast.makeText(context, copiedLabel, Toast.LENGTH_SHORT).show()
            }
        }
    }
}

/** A small trailing copy affordance for copyable detail rows. */
@Composable
private fun CopyHint() {
    Icon(
        imageVector = Icons.Rounded.ContentCopy,
        contentDescription = t("复制", "Copy"),
        tint = LocalPakomoColors.current.muted,
        modifier = Modifier
            .padding(start = 8.dp)
            .size(14.dp),
    )
}

@Composable
private fun DetailRow(label: String, value: String, mono: Boolean = true, copyable: Boolean = false) {
    val colors = LocalPakomoColors.current
    val copy = rememberCopyAction()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (copyable) Modifier.clickable { copy(value) } else Modifier)
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = colors.muted,
            modifier = Modifier.width(88.dp),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = colors.textPrimary,
            fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
            modifier = Modifier.weight(1f),
        )
        if (copyable) CopyHint()
    }
}

/** A detail row whose value is a host, split into two lines (subdomain / registrable) like the list. */
@Composable
private fun DetailHostRow(label: String, host: String) {
    val colors = LocalPakomoColors.current
    val parts = remember(host) { splitHost(host) }
    val copy = rememberCopyAction()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { copy(host) }
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = colors.muted,
            modifier = Modifier.width(88.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            if (parts.prefix.isNotEmpty()) {
                Text(
                    text = parts.prefix,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.muted,
                    fontFamily = FontFamily.Monospace,
                )
            }
            Text(
                text = parts.core,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.textPrimary,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium,
            )
        }
        CopyHint()
    }
}

private data class HostParts(val prefix: String, val core: String)

/**
 * Splits a host into a de-emphasizable subdomain prefix and the recognizable registrable domain
 * (kept whole so "where it went" stays legible). IPs and single-label hosts are all "core".
 * Heuristic eTLD+1: keep 3 labels when the last is a short ccTLD over a common second level
 * (e.g. vivo.com.cn), otherwise 2 (e.g. google.com).
 */
private fun splitHost(host: String): HostParts {
    if (host.isEmpty() || !host.contains('.') || host.all { it.isDigit() || it == '.' }) {
        return HostParts("", host)
    }
    val labels = host.split('.')
    val commonSld = setOf("com", "net", "org", "gov", "edu", "co", "ac")
    val coreCount = (
        if (labels.size >= 3 && labels.last().length <= 2 && labels[labels.size - 2] in commonSld) 3 else 2
        ).coerceAtMost(labels.size)
    val core = labels.takeLast(coreCount).joinToString(".")
    val prefix = labels.dropLast(coreCount).joinToString(".").let { if (it.isEmpty()) "" else "$it." }
    return HostParts(prefix, core)
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
    latencyCompensationEnabled: Boolean,
    onLatencyCompensationChanged: (Boolean) -> Unit,
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

            SectionLabel(t("整形", "Shaping"), startPadding = 56.dp)
            SettingRow(
                icon = Icons.Rounded.Tune,
                title = t("延迟补偿", "Latency compensation"),
                subtitle = t(
                    "抵消隧道自身开销,让设定延迟成为实际结果",
                    "Offset the tunnel's own overhead so the set latency is the observed result",
                ),
                onClick = { onLatencyCompensationChanged(!latencyCompensationEnabled) },
                trailing = {
                    Switch(
                        checked = latencyCompensationEnabled,
                        onCheckedChange = onLatencyCompensationChanged,
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
    val updateController = remember { (context.applicationContext as PakomoApplication).updateController }
    val checkStatus by updateController.checkStatus.collectAsState()
    val colors = LocalPakomoColors.current
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
                subtitle = BuildConfig.VERSION_NAME,
            )
            SettingRow(
                title = t("包名", "Package"),
                subtitle = BuildConfig.APPLICATION_ID,
            )
            SettingRow(
                title = t("转发引擎", "Engine"),
                subtitle = if (BuildConfig.USE_KOTLIN_KERNEL) "Kernel" else "Hev",
            )
            SettingRow(
                title = t("构建", "Build"),
                subtitle = (if (BuildConfig.DEBUG) "Debug" else "Release") + " · " + BuildConfig.BUILD_SHA,
            )
            SettingRow(
                title = "Android",
                subtitle = "${Build.VERSION.RELEASE} · API ${Build.VERSION.SDK_INT}",
            )
            SettingRow(
                title = t("设备", "Device"),
                subtitle = "${Build.MANUFACTURER} ${Build.MODEL}",
            )

            SectionLabel(t("更新", "Update"))
            SettingRow(
                title = t("更新源", "Update source"),
                subtitle = updateController.displaySourceUrls(t("[已隐藏]", "[redacted]")).joinToString("\n")
                    .ifEmpty { t("未配置", "Not configured") },
            )
            var autoUpdate by remember { mutableStateOf(updateController.autoUpdateEnabled) }
            SettingRow(
                title = t("自动检查更新", "Auto-check for updates"),
                subtitle = t("启动时自动检查新版本", "Check for a new version on launch"),
                onClick = if (updateController.isEnabled) {
                    { autoUpdate = !autoUpdate; updateController.autoUpdateEnabled = autoUpdate }
                } else {
                    null
                },
                trailing = {
                    Switch(
                        checked = autoUpdate,
                        enabled = updateController.isEnabled,
                        onCheckedChange = { autoUpdate = it; updateController.autoUpdateEnabled = it },
                    )
                },
            )
            val checkState = checkStatus
            SettingRow(
                title = t("检查更新", "Check for updates"),
                subtitle = when {
                    !updateController.isEnabled -> t("此版本未启用更新", "Updates not enabled in this build")
                    checkState == UpdateCheckStatus.Checking -> t("检查中…", "Checking…")
                    checkState == UpdateCheckStatus.UpToDate -> t("已是最新版本", "Already up to date")
                    checkState is UpdateCheckStatus.Failed -> t("检查失败：", "Check failed: ") + checkState.message
                    else -> t("点按检查最新版本", "Tap to check for the latest version")
                },
                onClick = if (updateController.isEnabled && checkState != UpdateCheckStatus.Checking) {
                    { updateController.checkNow() }
                } else {
                    null
                },
                trailing = if (checkState == UpdateCheckStatus.Checking) {
                    {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = colors.accent,
                        )
                    }
                } else {
                    null
                },
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
        @Suppress("DEPRECATION") // No synchronous non-VPN underlying-network snapshot API; allNetworks is the only option.
        val candidates = manager.allNetworks
        candidates.firstOrNull { network ->
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

private fun formatAttribution(stats: com.alphynia.pakomo.core.model.RuntimeStats): String {
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
