package com.pakomo.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pakomo.core.model.EngineStage
import com.pakomo.core.model.PakomoUiState
import com.pakomo.core.model.TargetScope
import com.pakomo.ui.components.Hairline
import com.pakomo.ui.components.NavigationRow
import com.pakomo.ui.components.ScopeSelector
import com.pakomo.ui.theme.Accent
import com.pakomo.ui.theme.AccentTint
import com.pakomo.ui.theme.Border
import com.pakomo.ui.theme.Danger
import com.pakomo.ui.theme.Muted
import com.pakomo.ui.theme.OnSurface
import com.pakomo.ui.theme.OnSurfaceVariant
import java.util.Locale
import kotlinx.coroutines.delay

private const val TRAFFIC_HISTORY = 48

@Composable
fun HomeScreen(
    state: PakomoUiState,
    onScopeSelected: (TargetScope) -> Unit,
    onOpenScope: () -> Unit,
    onOpenRules: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenSecurity: () -> Unit,
    onToggleService: () -> Unit,
    onEmergencyStop: () -> Unit,
) {
    var pendingGlobal by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
        ) {
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .background(Accent, RoundedCornerShape(11.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("P", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 22.sp)
                }
                Spacer(Modifier.size(12.dp))
                Column {
                    Text(
                        text = "Pakomo",
                        style = MaterialTheme.typography.headlineSmall,
                        color = OnSurface,
                    )
                    Text(
                        text = "非 Root 弱网模拟",
                        style = MaterialTheme.typography.bodySmall,
                        color = Muted,
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            TrafficCard(state)

            Spacer(Modifier.height(12.dp))
            ServiceStatusCard(stage = state.engineStage, state = state, onToggle = onToggleService)

            Spacer(Modifier.height(18.dp))
            Text(
                text = "接管范围",
                style = MaterialTheme.typography.labelMedium,
                color = OnSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            ScopeCard(
                state = state,
                onSelected = { scope ->
                    if (scope == TargetScope.GLOBAL && state.scope != TargetScope.GLOBAL) {
                        pendingGlobal = true
                    } else {
                        onScopeSelected(scope)
                    }
                },
                onOpenScope = onOpenScope,
            )
            Spacer(Modifier.height(12.dp))
        }

        NavigationRow(
            icon = Icons.Rounded.Bolt,
            title = "规则",
            subtitle = state.activeRule.summary,
            value = "${state.rules.size} 条",
            valueColor = Accent,
            onClick = onOpenRules,
        )
        Hairline()
        NavigationRow(
            icon = Icons.Rounded.BugReport,
            title = "日志",
            subtitle = "连接与事件",
            value = if (state.engineStage == EngineStage.FORWARDING) "实时" else "可用",
            valueColor = if (state.engineStage == EngineStage.FORWARDING) Accent else OnSurfaceVariant,
            onClick = onOpenDiagnostics,
        )
        Hairline()
        NavigationRow(
            icon = Icons.Rounded.Settings,
            title = "设置",
            subtitle = "服务行为与界面偏好",
            onClick = onOpenSettings,
        )
        Hairline()
        NavigationRow(
            icon = Icons.Rounded.Security,
            title = "安全与隐私",
            subtitle = "权限、数据与开源说明",
            value = "无遥测",
            valueColor = Accent,
            onClick = onOpenSecurity,
        )

        Spacer(Modifier.weight(1f))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = state.engineStage.isActive, onClick = onEmergencyStop)
                .padding(vertical = 18.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Rounded.PowerSettingsNew,
                contentDescription = null,
                tint = if (state.engineStage.isActive) Danger else Muted,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.size(10.dp))
            Text(
                text = "紧急恢复正常网络",
                color = if (state.engineStage.isActive) Danger else Muted,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
        }
    }

    if (pendingGlobal) {
        AlertDialog(
            onDismissRequest = { pendingGlobal = false },
            title = { Text("确认全局接管") },
            text = { Text("全局模式影响范围较大，建议首次测试只选择目标应用。仍要切换到全局吗？") },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingGlobal = false
                        onScopeSelected(TargetScope.GLOBAL)
                    },
                ) { Text("继续") }
            },
            dismissButton = {
                TextButton(onClick = { pendingGlobal = false }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun TrafficCard(state: PakomoUiState) {
    // Ring buffer of recent (upload, download) B/s samples, sampled every second while running.
    val history = remember { mutableStateListOf<Pair<Long, Long>>() }
    val latestStats = rememberUpdatedState(state.stats)
    val running = state.engineStage == EngineStage.FORWARDING
    LaunchedEffect(running) {
        if (running) {
            while (true) {
                val s = latestStats.value
                history.add(s.uploadBytesPerSecond to s.downloadBytesPerSecond)
                if (history.size > TRAFFIC_HISTORY) history.removeAt(0)
                delay(1000)
            }
        } else {
            history.clear()
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.dp, Border),
        elevation = CardDefaults.cardElevation(0.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("实时流量", style = MaterialTheme.typography.titleMedium, color = OnSurface)
                Spacer(Modifier.weight(1f))
                TrafficLegend(Accent, "上行", state.stats.uploadBytesPerSecond)
                Spacer(Modifier.size(14.dp))
                TrafficLegend(Muted, "下行", state.stats.downloadBytesPerSecond)
            }
            Spacer(Modifier.height(14.dp))
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(72.dp),
            ) {
                // Faint horizontal gridlines so the chart reads as designed even when idle.
                val gridColor = Border
                for (i in 0..3) {
                    val y = size.height * i / 3f
                    drawLine(gridColor, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
                }
                val upload = history.map { it.first }
                val download = history.map { it.second }
                val peak = (upload + download).maxOrNull()?.coerceAtLeast(1L) ?: 1L
                drawSpark(download, peak, Muted.copy(alpha = 0.5f))
                drawSpark(upload, peak, Accent)
            }
        }
    }
}

@Composable
private fun TrafficLegend(color: Color, label: String, bytesPerSecond: Long) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(7.dp).background(color, CircleShape))
        Spacer(Modifier.size(6.dp))
        Text(label, style = MaterialTheme.typography.bodySmall, color = OnSurfaceVariant)
        Spacer(Modifier.size(6.dp))
        Text(
            text = formatShortRate(bytesPerSecond),
            style = MaterialTheme.typography.bodySmall,
            color = OnSurface,
            fontFamily = FontFamily.Monospace,
        )
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawSpark(
    values: List<Long>,
    peak: Long,
    color: Color,
) {
    if (values.size < 2) return
    val stepX = size.width / (TRAFFIC_HISTORY - 1)
    val startIndex = TRAFFIC_HISTORY - values.size
    val path = Path()
    values.forEachIndexed { i, v ->
        val x = (startIndex + i) * stepX
        val y = size.height - (v.toFloat() / peak) * size.height
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    drawPath(path = path, color = color, style = Stroke(width = 4f))
}

@Composable
private fun ServiceStatusCard(
    stage: EngineStage,
    state: PakomoUiState,
    onToggle: () -> Unit,
) {
    val isError = stage == EngineStage.ERROR
    val running = stage == EngineStage.FORWARDING

    val container = when {
        isError -> Color(0xFFFFF5F4)
        running -> Accent
        else -> Color.White
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(72.dp)
            .clickable(onClick = onToggle),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = container),
        border = if (running) null else BorderStroke(1.dp, if (isError) Color(0xFFF0C8C4) else Border),
        elevation = CardDefaults.cardElevation(0.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .background(
                        color = if (running) Color.White.copy(alpha = 0.18f) else Color.Transparent,
                        shape = CircleShape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (isError) Icons.Rounded.PowerSettingsNew else Icons.Rounded.Check,
                    contentDescription = null,
                    tint = when {
                        running -> Color.White
                        isError -> Danger
                        else -> Muted
                    },
                    modifier = Modifier.size(if (running) 22.dp else 26.dp),
                )
            }
            Spacer(Modifier.size(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = when (stage) {
                        EngineStage.STOPPED -> "服务已停止"
                        EngineStage.STARTING -> "正在启动"
                        EngineStage.FORWARDING -> "运行中"
                        EngineStage.ERROR -> "启动失败"
                    },
                    fontWeight = FontWeight.Bold,
                    fontSize = 19.sp,
                    color = if (running) Color.White else OnSurface,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = when (stage) {
                        EngineStage.STOPPED -> "点按开启弱网模拟"
                        EngineStage.STARTING -> state.engineMessage ?: "正在建立本地转发链路"
                        EngineStage.FORWARDING -> "已运行 ${formatUptime(state.stats.uptimeMs)}"
                        EngineStage.ERROR -> state.engineMessage ?: "点按重试，或打开日志查看原因"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = when {
                        running -> Color.White.copy(alpha = 0.82f)
                        isError -> Danger
                        else -> OnSurfaceVariant
                    },
                    fontFamily = if (running) FontFamily.Monospace else FontFamily.Default,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** v2 combines the scope segmented control and the target picker into a single card. */
@Composable
private fun ScopeCard(
    state: PakomoUiState,
    onSelected: (TargetScope) -> Unit,
    onOpenScope: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.dp, Border),
        elevation = CardDefaults.cardElevation(0.dp),
    ) {
        Column(modifier = Modifier.padding(6.dp)) {
            ScopeSelector(selected = state.scope, onSelected = onSelected)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpenScope)
                    .padding(horizontal = 8.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .background(AccentTint, RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.GridView,
                        contentDescription = null,
                        tint = Accent,
                        modifier = Modifier.size(16.dp),
                    )
                }
                Spacer(Modifier.size(12.dp))
                Text(
                    text = when (state.scope) {
                        TargetScope.APPLICATIONS -> "选择应用"
                        TargetScope.ADDRESSES -> "管理地址"
                        TargetScope.GLOBAL -> "全局接管"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = OnSurface,
                    modifier = Modifier.weight(1f),
                )
                val value = when (state.scope) {
                    TargetScope.APPLICATIONS -> "${state.selectedApps.size} 个"
                    TargetScope.ADDRESSES -> "${state.addressDomains.size} 个"
                    TargetScope.GLOBAL -> "已开启"
                }
                Box(
                    modifier = Modifier
                        .background(Color(0xFFF1F3F6), RoundedCornerShape(8.dp))
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                ) {
                    Text(
                        text = value,
                        style = MaterialTheme.typography.bodySmall,
                        color = OnSurfaceVariant,
                    )
                }
                Spacer(Modifier.size(4.dp))
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                    contentDescription = null,
                    tint = Muted,
                )
            }
        }
    }
}

private fun formatUptime(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    return String.format(
        Locale.US,
        "%02d:%02d:%02d",
        totalSeconds / 3600,
        (totalSeconds % 3600) / 60,
        totalSeconds % 60,
    )
}

private fun formatShortRate(bytesPerSecond: Long): String = when {
    bytesPerSecond >= 1_000_000 ->
        String.format(Locale.US, "%.1f MB/s", bytesPerSecond / 1_000_000.0)
    else -> "${bytesPerSecond / 1_000} KB/s"
}
