package com.pakomo.ui.screens

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import com.pakomo.ui.components.EmptyArtKind
import com.pakomo.ui.components.EmptyStateArt
import com.pakomo.ui.components.NavigationRow
import com.pakomo.ui.components.PakomoMascot
import com.pakomo.ui.components.ScopeSelector
import com.pakomo.ui.components.mascotStateOf
import com.pakomo.ui.components.rememberReduceMotion
import com.pakomo.ui.theme.LocalPakomoColors
import com.pakomo.ui.theme.LocalThemeMode
import com.pakomo.ui.theme.PakomoColors
import com.pakomo.ui.theme.ThemeMode
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import kotlin.math.cos
import kotlin.math.sin
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
    onOpenSettings: () -> Unit,
    onToggleService: () -> Unit,
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
    val colors = LocalPakomoColors.current
    val decorated = LocalThemeMode.current == ThemeMode.Companion

    Column(
        modifier = Modifier
            .fillMaxSize()
            .then(
                if (decorated) {
                    Modifier.drawBehind { drawCompanionBackdrop(colors) }
                } else {
                    Modifier
                },
            )
            .statusBarsPadding(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
        ) {
            Spacer(Modifier.height(14.dp))
            Text(
                text = "Pakomo",
                style = MaterialTheme.typography.headlineSmall,
                color = colors.textPrimary,
            )

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
                color = colors.textSecondary,
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

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            // 角色主卡:铺满从接管范围卡下方到 App 底部的整片可用区(全宽全高)。
            // 导航行浮在其上,真图用透明区避开左上的导航文字。仅 Companion、不可交互、不进语义树。
            if (decorated) {
                EmptyStateArt(
                    kind = EmptyArtKind.Generic,
                    modifier = Modifier.matchParentSize(),
                )
            }
            Column(modifier = Modifier.fillMaxWidth()) {
                NavigationRow(
                    icon = Icons.Rounded.Bolt,
                    title = "规则",
                    onClick = onOpenRules,
                    showArrow = false,
                )
                NavigationRow(
                    icon = Icons.Rounded.BugReport,
                    title = "日志",
                    onClick = onOpenDiagnostics,
                    showArrow = false,
                )
                NavigationRow(
                    icon = Icons.Rounded.Settings,
                    title = "设置",
                    onClick = onOpenSettings,
                    showArrow = false,
                )
            }
        }
    }

}

@Composable
private fun TrafficCard(state: PakomoUiState, chartState: TrafficChartState) {
    val colors = LocalPakomoColors.current
    val decorated = LocalThemeMode.current == ThemeMode.Companion
    val reduceMotion = rememberReduceMotion()
    val rnd = remember { java.util.Random() }
    val glitch = remember { Animatable(0f) }
    var glitchBands by remember { mutableStateOf(emptyList<GlitchBand>()) }
    var lastDropped by remember { mutableStateOf(state.stats.droppedTransfers) }
    var nextGlitchMs by remember { mutableStateOf(0L) }
    // 故障强度与频率**跟随当前规则的丢包率**:0% 丢包基本不故障;丢包越高,炸得越勤、越猛、越碎。
    // (这样 glitch 是丢包率的可视化,而非纯装饰;随机只用来打散节奏、避免机械感。)
    val loss = (state.activeRule.packetLossPercent / 100f).coerceIn(0f, 1f)
    LaunchedEffect(state.stats.droppedTransfers) {
        val dropped = state.stats.droppedTransfers
        val now = System.currentTimeMillis()
        // 触发概率随丢包率上升(0% → 几乎不炸;100% → ~85%),再叠随机冷却把节奏打散
        if (decorated && dropped > lastDropped && !reduceMotion &&
            now >= nextGlitchMs && rnd.nextFloat() < 0.85f * loss
        ) {
            nextGlitchMs = now + 700 + rnd.nextInt(2200)
            glitchBands = randomGlitchBands(rnd, loss)
            // 峰值强度随丢包率(0.3 基础 + 0.7*loss),再叠 ±小幅随机
            val peak = (0.3f + 0.7f * loss) * (0.75f + rnd.nextFloat() * 0.25f)
            glitch.snapTo(peak.coerceIn(0.15f, 1f))
            glitch.animateTo(0f, animationSpec = tween(130 + rnd.nextInt(120), easing = LinearEasing))
        }
        lastDropped = dropped
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = colors.surface),
        border = BorderStroke(1.dp, colors.border),
        elevation = CardDefaults.cardElevation(0.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("实时流量", style = MaterialTheme.typography.titleMedium, color = colors.textPrimary)
                Spacer(Modifier.weight(1f))
                TrafficLegend(colors.accent, "上行", state.stats.uploadBytesPerSecond)
                Spacer(Modifier.size(14.dp))
                TrafficLegend(colors.muted, "下行", state.stats.downloadBytesPerSecond)
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
                                drawLine(colors.border, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
                            }
                            val translation = -chartState.scrollOffset.value * stepX
                            val g = glitch.value
                            // 正常曲线
                            clipRect(left = 0f, top = 0f, right = size.width, bottom = size.height) {
                                translate(left = translation) {
                                    drawPath(downloadPath, color = colors.muted.copy(alpha = 0.5f), style = Stroke(width = 4f))
                                    drawPath(uploadPath, color = colors.accent, style = Stroke(width = 4f))
                                }
                            }
                            // 丢包:只重画"有错位"的细行,每行错位量不同并各自带青/粉色散,
                            // 曲线被撕成参差的碎段(未错位的行仍是上面那条正常曲线)。
                            if (g > 0f) {
                                glitchBands.forEach { band ->
                                    if (band.shift == 0f) return@forEach
                                    val top = size.height * band.y0
                                    val bottom = size.height * band.y1
                                    clipRect(left = 0f, top = top, right = size.width, bottom = bottom) {
                                        drawRect(colors.surface, topLeft = Offset(0f, top), size = Size(size.width, bottom - top))
                                        for (i in 0..3) {
                                            val y = size.height * i / 3f
                                            drawLine(colors.border, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
                                        }
                                        val base = translation + size.width * band.shift * g
                                        val split = size.width * 0.012f * g
                                        translate(left = base + split) {
                                            drawPath(uploadPath, color = colors.glitchCyan.copy(alpha = 0.7f * g), style = Stroke(width = 4f))
                                        }
                                        translate(left = base - split) {
                                            drawPath(uploadPath, color = colors.glitchPink.copy(alpha = 0.7f * g), style = Stroke(width = 4f))
                                        }
                                        translate(left = base) {
                                            drawPath(downloadPath, color = colors.muted.copy(alpha = 0.5f), style = Stroke(width = 4f))
                                            drawPath(uploadPath, color = colors.accent, style = Stroke(width = 4f))
                                        }
                                    }
                                }
                            }
                        }
                    },
            )
        }
    }
}

/** 一次故障里的一条细扫描行(比例值);shift==0 表示这行不动。 */
private class GlitchBand(val y0: Float, val y1: Float, val shift: Float)

/**
 * 把图表高度切成 7–13 条**细扫描行**,错位程度随丢包率 [loss] 变化:丢包越高,动的行越多、错位幅度越大。
 * 未动的行保持原样。错位量平方分布(多数小、偶尔大),相邻行不同 → 参差碎段,而非整行齐刷刷横滑。
 */
private fun randomGlitchBands(rnd: java.util.Random, loss: Float): List<GlitchBand> {
    val rows = 7 + rnd.nextInt(7)
    val moveChance = 0.2f + 0.55f * loss // 丢包越高,动的行越多
    val maxShift = 0.08f + 0.34f * loss  // 丢包越高,错位幅度越大
    return List(rows) { i ->
        val shift = if (rnd.nextFloat() > moveChance) {
            0f
        } else {
            val mag = rnd.nextFloat() * rnd.nextFloat()
            (rnd.nextFloat() - 0.5f) * 2f * mag * maxShift
        }
        GlitchBand(y0 = i.toFloat() / rows, y1 = (i + 1).toFloat() / rows, shift = shift)
    }
}

@Composable
private fun TrafficLegend(color: Color, label: String, bytesPerSecond: Long) {
    val colors = LocalPakomoColors.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(7.dp).background(color, CircleShape))
        Spacer(Modifier.size(6.dp))
        Text(label, style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
        Spacer(Modifier.size(6.dp))
        Text(
            text = formatShortRate(bytesPerSecond),
            style = MaterialTheme.typography.bodySmall,
            color = colors.textPrimary,
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
    val colors = LocalPakomoColors.current
    val decorated = LocalThemeMode.current == ThemeMode.Companion
    val mascotState = mascotStateOf(stage, isIdleRunning)
    val isError = stage == EngineStage.ERROR
    val running = stage == EngineStage.FORWARDING
    val stopped = stage == EngineStage.STOPPED

    val container = when {
        isError -> colors.errorContainer
        isIdleRunning -> colors.statusIdle
        running -> colors.statusRunning
        stopped -> colors.statusStopped
        else -> colors.surface
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
            BorderStroke(1.dp, if (isError) colors.errorBorder else colors.border)
        },
        elevation = CardDefaults.cardElevation(0.dp),
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // Companion:角色装饰叠加在右侧,画在文本之下(文本 z 序更高),不改卡片布局。
            if (decorated) {
                PakomoMascot(
                    state = mascotState,
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .fillMaxHeight()
                        .width(104.dp),
                )
            }
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
                        EngineStage.ERROR -> colors.danger
                        else -> colors.muted
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
                    color = if (running || stopped) Color.White else colors.textPrimary,
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
                            EngineStage.ERROR -> colors.danger
                            EngineStage.STARTING -> colors.textSecondary
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
    val colors = LocalPakomoColors.current
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = colors.surface),
        border = BorderStroke(1.dp, colors.border),
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
                                if (targetPickerEnabled) colors.accentTint else colors.scopeDisabled,
                                RoundedCornerShape(8.dp),
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.GridView,
                            contentDescription = null,
                            tint = if (targetPickerEnabled) colors.accent else colors.muted,
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
                        color = if (targetPickerEnabled) colors.textPrimary else colors.muted,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = (activeTargetCount ?: 0).toString(),
                        style = MaterialTheme.typography.labelMedium,
                        color = if (!targetPickerEnabled) {
                            colors.muted
                        } else if (activeTargetCount == 0) {
                            colors.warning
                        } else {
                            colors.accent
                        },
                        modifier = Modifier
                            .background(
                                color = if (!targetPickerEnabled) {
                                    colors.scopeDisabled
                                } else if (activeTargetCount == 0) {
                                    colors.warningContainer
                                } else {
                                    colors.accentTint
                                },
                                shape = RoundedCornerShape(10.dp),
                            )
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                    Spacer(Modifier.size(6.dp))
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                        contentDescription = null,
                        tint = colors.muted,
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

/**
 * Companion 首页背景装饰(§4.4):右上淡六边形轮廓 + 少量全息碎裂像素,呼应 app 图标。
 * 纯绘制、低对比度、在内容层之下;不进语义树、不承担任何功能含义。占位版,数值可再调。
 */
private fun DrawScope.drawCompanionBackdrop(colors: PakomoColors) {
    val w = size.width
    val h = size.height

    // 右上大六边形轮廓(pointy-top)
    val cx = w * 0.82f
    val cy = h * 0.18f
    val r = size.minDimension * 0.26f
    val hex = Path()
    for (i in 0..5) {
        val angle = Math.toRadians(60.0 * i - 90.0)
        val x = cx + r * cos(angle).toFloat()
        val y = cy + r * sin(angle).toFloat()
        if (i == 0) hex.moveTo(x, y) else hex.lineTo(x, y)
    }
    hex.close()
    drawPath(hex, color = colors.accent.copy(alpha = 0.05f), style = Stroke(width = 2f))

    // 少量全息碎裂像素(右上聚簇 + 左下点缀),交替青/粉,极低对比度
    val px = size.minDimension * 0.018f
    val marks = listOf(
        Triple(0.90f, 0.30f, colors.glitchCyan),
        Triple(0.85f, 0.36f, colors.glitchPink),
        Triple(0.93f, 0.42f, colors.glitchCyan),
        Triple(0.10f, 0.74f, colors.glitchPink),
        Triple(0.16f, 0.80f, colors.glitchCyan),
        Triple(0.07f, 0.82f, colors.glitchCyan),
    )
    marks.forEach { (fx, fy, color) ->
        drawRect(
            color = color.copy(alpha = 0.12f),
            topLeft = Offset(w * fx, h * fy),
            size = Size(px, px),
        )
    }
}
