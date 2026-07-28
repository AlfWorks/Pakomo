package com.pakomo.ui.screens

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.platform.LocalContext
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
import kotlin.math.ln
import kotlinx.coroutines.delay

private const val TRAFFIC_HISTORY = 48
private const val TRAFFIC_SAMPLE_MS = 1_000
private const val TRAFFIC_SLIDE_MS = 280
private const val TRAFFIC_CHART_REFERENCE_BPS = 100_000_000.0

internal data class TrafficSample(
    val slot: Int,
    val upload: Long,
    val download: Long,
)

internal class TrafficChartState {
    internal val history = mutableStateListOf<TrafficSample>()
    internal val scrollOffset = Animatable(0f)
}

@Composable
internal fun rememberTrafficChartState(state: PakomoUiState): TrafficChartState {
    val chartState = remember { TrafficChartState() }
    val latestStats = rememberUpdatedState(state.stats)
    val running = state.engineStage == EngineStage.FORWARDING
    LaunchedEffect(running) {
        if (running) {
            val initial = latestStats.value
            chartState.history.add(
                TrafficSample(
                    slot = 0,
                    upload = initial.uploadBytesPerSecond,
                    download = initial.downloadBytesPerSecond,
                ),
            )
            var nextSlot = 1
            while (true) {
                val s = latestStats.value
                chartState.history.add(
                    TrafficSample(
                        slot = nextSlot,
                        upload = s.uploadBytesPerSecond,
                        download = s.downloadBytesPerSecond,
                    ),
                )
                chartState.scrollOffset.animateTo(
                    targetValue = nextSlot.toFloat(),
                    animationSpec = tween(
                        durationMillis = TRAFFIC_SLIDE_MS,
                        easing = FastOutSlowInEasing,
                    ),
                )
                if (chartState.history.size > TRAFFIC_HISTORY) {
                    chartState.history.removeAt(0)
                }
                nextSlot += 1
                delay((TRAFFIC_SAMPLE_MS - TRAFFIC_SLIDE_MS).toLong())
            }
        } else {
            chartState.history.clear()
            chartState.scrollOffset.snapTo(0f)
        }
    }
    return chartState
}

@Composable
internal fun HomeScreen(
    state: PakomoUiState,
    trafficChartState: TrafficChartState,
    appScopeEnabled: Boolean,
    onScopeSelected: (TargetScope) -> Unit,
    onOpenScope: () -> Unit,
    onOpenRules: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onOpenLatencyTest: () -> Unit,
    onOpenSettings: () -> Unit,
    onToggleService: () -> Unit,
    onEmergencyStop: () -> Unit,
) {
    val context = LocalContext.current
    val showAppListUnavailable = {
        Toast.makeText(
            context,
            "应用列表不可用，请在设置中检查权限",
            Toast.LENGTH_SHORT,
        ).show()
    }
    val activeTargetCount = remember(state.scope, state.apps, state.addressDomains) {
        when (state.scope) {
            TargetScope.APPLICATIONS -> state.apps.count { it.isSelected }
            TargetScope.ADDRESSES -> state.addressDomains.size
            TargetScope.GLOBAL -> null
        }
    }
    val isIdleRunning = state.engineStage == EngineStage.FORWARDING &&
        activeTargetCount == 0

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
                Text(
                    text = "Pakomo",
                    style = MaterialTheme.typography.headlineSmall,
                    color = OnSurface,
                )
            }

            Spacer(Modifier.height(16.dp))
            ServiceStatusCard(
                stage = state.engineStage,
                state = state,
                isIdleRunning = isIdleRunning,
                onToggle = onToggleService,
            )
            AnimatedVisibility(
                visible = state.engineStage == EngineStage.FORWARDING,
                enter = expandVertically(
                    animationSpec = tween(180),
                    expandFrom = Alignment.Top,
                ) + fadeIn(animationSpec = tween(100)),
                exit = shrinkVertically(
                    animationSpec = tween(160),
                    shrinkTowards = Alignment.Top,
                ) + fadeOut(animationSpec = tween(90)),
            ) {
                Column {
                    Spacer(Modifier.height(12.dp))
                    TrafficCard(state, trafficChartState)
                }
            }

            Spacer(Modifier.height(18.dp))
            Text(
                text = "接管范围",
                style = MaterialTheme.typography.labelMedium,
                color = OnSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            ScopeCard(
                state = state,
                activeTargetCount = activeTargetCount,
                appScopeEnabled = appScopeEnabled,
                onSelected = onScopeSelected,
                onOpenScope = onOpenScope,
                onAppScopeUnavailable = showAppListUnavailable,
            )
            Spacer(Modifier.height(12.dp))
        }

        NavigationRow(
            icon = Icons.Rounded.Bolt,
            title = "规则",
            onClick = onOpenRules,
        )
        Hairline()
        NavigationRow(
            icon = Icons.Rounded.BugReport,
            title = "日志",
            onClick = onOpenDiagnostics,
        )
        Hairline()
        NavigationRow(
            icon = Icons.Rounded.Public,
            title = "域名延迟测试",
            onClick = onOpenLatencyTest,
        )
        Hairline()
        NavigationRow(
            icon = Icons.Rounded.Settings,
            title = "设置",
            onClick = onOpenSettings,
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

}

@Composable
private fun TrafficCard(state: PakomoUiState, chartState: TrafficChartState) {
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
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(72.dp)
                    .drawWithCache {
                        val stepX = size.width / (TRAFFIC_HISTORY - 1)
                        val samples = chartState.history.toList()
                        val downloadPath = buildSparkPath(
                            samples = samples,
                            chartWidth = size.width,
                            chartHeight = size.height,
                            stepX = stepX,
                        ) { it.download }
                        val uploadPath = buildSparkPath(
                            samples = samples,
                            chartWidth = size.width,
                            chartHeight = size.height,
                            stepX = stepX,
                        ) { it.upload }
                        onDrawBehind {
                            for (i in 0..3) {
                                val y = size.height * i / 3f
                                drawLine(
                                    Border,
                                    Offset(0f, y),
                                    Offset(size.width, y),
                                    strokeWidth = 1f,
                                )
                            }
                            val translation = -chartState.scrollOffset.value * stepX
                            clipRect(
                                left = 0f,
                                top = 0f,
                                right = size.width,
                                bottom = size.height,
                            ) {
                                translate(left = translation) {
                                    drawPath(
                                        path = downloadPath,
                                        color = Muted.copy(alpha = 0.5f),
                                        style = Stroke(width = 4f),
                                    )
                                    drawPath(
                                        path = uploadPath,
                                        color = Accent,
                                        style = Stroke(width = 4f),
                                    )
                                }
                            }
                        }
                    },
            )
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

private fun buildSparkPath(
    samples: List<TrafficSample>,
    chartWidth: Float,
    chartHeight: Float,
    stepX: Float,
    valueOf: (TrafficSample) -> Long,
): Path {
    val path = Path()
    if (samples.size < 2) return path
    samples.forEachIndexed { i, sample ->
        val x = chartWidth + sample.slot * stepX
        val normalized = (
            ln(valueOf(sample).coerceAtLeast(0L).toDouble() + 1.0) /
                ln(TRAFFIC_CHART_REFERENCE_BPS + 1.0)
            ).toFloat().coerceIn(0f, 1f)
        val y = chartHeight - normalized * chartHeight
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    return path
}

@Composable
private fun ServiceStatusCard(
    stage: EngineStage,
    state: PakomoUiState,
    isIdleRunning: Boolean,
    onToggle: () -> Unit,
) {
    val isError = stage == EngineStage.ERROR
    val running = stage == EngineStage.FORWARDING
    val stopped = stage == EngineStage.STOPPED

    val container = when {
        isError -> Color(0xFFFFF5F4)
        isIdleRunning -> Color(0xFFE5A23B)
        running -> Accent
        stopped -> Color(0xFF8D8D8D)
        else -> Color.White
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(72.dp)
            .clickable(onClick = onToggle),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = container),
        border = if (running || stopped) {
            null
        } else {
            BorderStroke(1.dp, if (isError) Color(0xFFF0C8C4) else Border)
        },
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
                        color = if (running) {
                            Color.White.copy(alpha = 0.18f)
                        } else {
                            Color.Transparent
                        },
                        shape = CircleShape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = when (stage) {
                        EngineStage.ERROR -> Icons.Rounded.PowerSettingsNew
                        EngineStage.STOPPED -> Icons.Rounded.Block
                        EngineStage.FORWARDING ->
                            if (isIdleRunning) Icons.Rounded.Pause else Icons.Rounded.Check
                        else -> Icons.Rounded.Check
                    },
                    contentDescription = null,
                    tint = when (stage) {
                        EngineStage.FORWARDING, EngineStage.STOPPED -> Color.White
                        EngineStage.ERROR -> Danger
                        else -> Muted
                    },
                    modifier = Modifier.size(
                        if (stage == EngineStage.FORWARDING) 22.dp else 26.dp,
                    ),
                )
            }
            Spacer(Modifier.size(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = when (stage) {
                        EngineStage.STOPPED -> "已停止"
                        EngineStage.STARTING -> "正在启动"
                        EngineStage.FORWARDING ->
                            if (isIdleRunning) "空闲运行" else "运行中"
                        EngineStage.ERROR -> "启动失败"
                    },
                    fontWeight = FontWeight.Bold,
                    fontSize = 19.sp,
                    color = if (running || stopped) Color.White else OnSurface,
                )
                val detail = when (stage) {
                    EngineStage.STOPPED -> "点此启动"
                    EngineStage.STARTING -> state.engineMessage
                    EngineStage.FORWARDING -> if (isIdleRunning) {
                        when (state.scope) {
                            TargetScope.APPLICATIONS -> "请选择应用"
                            TargetScope.ADDRESSES -> "请添加地址"
                            TargetScope.GLOBAL -> formatUptime(state.stats.uptimeMs)
                        }
                    } else {
                        formatUptime(state.stats.uptimeMs)
                    }
                    EngineStage.ERROR -> state.engineMessage
                }
                if (!detail.isNullOrBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = when (stage) {
                            EngineStage.FORWARDING,
                            EngineStage.STOPPED -> Color.White.copy(alpha = 0.82f)
                            EngineStage.ERROR -> Danger
                            EngineStage.STARTING -> OnSurfaceVariant
                        },
                        fontFamily = if (running && !isIdleRunning) {
                            FontFamily.Monospace
                        } else {
                            FontFamily.Default
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** v2 combines the scope segmented control and the target picker into a single card. */
@Composable
private fun ScopeCard(
    state: PakomoUiState,
    activeTargetCount: Int?,
    appScopeEnabled: Boolean,
    onSelected: (TargetScope) -> Unit,
    onOpenScope: () -> Unit,
    onAppScopeUnavailable: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.dp, Border),
        elevation = CardDefaults.cardElevation(0.dp),
    ) {
        Column(modifier = Modifier.padding(6.dp)) {
            ScopeSelector(
                selected = state.scope,
                onSelected = onSelected,
                disabledScopes = if (appScopeEnabled) {
                    emptySet()
                } else {
                    setOf(TargetScope.APPLICATIONS)
                },
                onDisabledScopeClick = {
                    if (it == TargetScope.APPLICATIONS) onAppScopeUnavailable()
                },
            )
            AnimatedVisibility(
                visible = state.scope != TargetScope.GLOBAL,
                enter = expandVertically(
                    animationSpec = tween(140),
                    expandFrom = Alignment.Top,
                ) + fadeIn(animationSpec = tween(100)),
                exit = shrinkVertically(
                    animationSpec = tween(120),
                    shrinkTowards = Alignment.Top,
                ) + fadeOut(animationSpec = tween(80)),
            ) {
                val targetPickerEnabled =
                    state.scope != TargetScope.APPLICATIONS || appScopeEnabled
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            if (targetPickerEnabled) {
                                onOpenScope()
                            } else {
                                onAppScopeUnavailable()
                            }
                        }
                        .padding(horizontal = 8.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .background(
                                if (targetPickerEnabled) AccentTint else Color(0xFFE7E9EC),
                                RoundedCornerShape(8.dp),
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.GridView,
                            contentDescription = null,
                            tint = if (targetPickerEnabled) Accent else Muted,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                    Spacer(Modifier.size(12.dp))
                    Text(
                        text = when (state.scope) {
                            TargetScope.APPLICATIONS -> "选择应用"
                            TargetScope.ADDRESSES -> "指定地址"
                            TargetScope.GLOBAL -> ""
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (targetPickerEnabled) OnSurface else Muted,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = (activeTargetCount ?: 0).toString(),
                        style = MaterialTheme.typography.labelMedium,
                        color = if (!targetPickerEnabled) {
                            Muted
                        } else if (activeTargetCount == 0) {
                            Color(0xFFB56D00)
                        } else {
                            Accent
                        },
                        modifier = Modifier
                            .background(
                                color = if (!targetPickerEnabled) {
                                    Color(0xFFE7E9EC)
                                } else if (activeTargetCount == 0) {
                                    Color(0xFFFFF1D8)
                                } else {
                                    AccentTint
                                },
                                shape = RoundedCornerShape(10.dp),
                            )
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                    Spacer(Modifier.size(6.dp))
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                        contentDescription = null,
                        tint = Muted,
                    )
                }
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
