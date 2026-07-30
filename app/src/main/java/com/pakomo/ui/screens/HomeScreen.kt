package com.pakomo.ui.screens

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
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
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.ui.graphics.drawscope.rotate
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
import com.pakomo.ui.components.ScopeSelector
import com.pakomo.ui.components.StatusDecor
import com.pakomo.ui.components.mascotStateOf
import com.pakomo.ui.components.rememberReduceMotion
import com.pakomo.ui.theme.LocalAppLanguage
import com.pakomo.ui.theme.LocalPakomoColors
import com.pakomo.ui.theme.LocalThemeMode
import com.pakomo.ui.theme.PakomoColors
import com.pakomo.ui.theme.ThemeMode
import com.pakomo.ui.theme.t
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
    val language = LocalAppLanguage.current
    val showAppListUnavailable = {
        Toast.makeText(
            context,
            language.tr("应用列表不可用，请在设置中检查权限", "App list unavailable. Check permissions in Settings."),
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
                text = t("接管范围", "Capture scope"),
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
            // 角色主卡:统一按**最小档尺寸**放置——满宽、固定比例、贴底;大区域时上方自然留空,不分档。
            // 导航行浮在其上,真图用透明区避开左上的导航文字。仅 Companion、不可交互、不进语义树。
            if (decorated) {
                EmptyStateArt(
                    kind = EmptyArtKind.Generic,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .aspectRatio(0.95f),
                )
            }
            Column(modifier = Modifier.fillMaxWidth()) {
                NavigationRow(
                    icon = Icons.Rounded.Bolt,
                    title = t("规则", "Rules"),
                    onClick = onOpenRules,
                    showArrow = false,
                )
                NavigationRow(
                    icon = Icons.Rounded.BugReport,
                    title = t("日志", "Logs"),
                    onClick = onOpenDiagnostics,
                    showArrow = false,
                )
                NavigationRow(
                    icon = Icons.Rounded.Settings,
                    title = t("设置", "Settings"),
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
                Text(t("实时流量", "Live traffic"), style = MaterialTheme.typography.titleMedium, color = colors.textPrimary)
                Spacer(Modifier.weight(1f))
                TrafficLegend(colors.accent, t("上行", "Up"), state.stats.uploadBytesPerSecond)
                Spacer(Modifier.size(14.dp))
                TrafficLegend(colors.muted, t("下行", "Down"), state.stats.downloadBytesPerSecond)
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
    val starting = stage == EngineStage.STARTING
    // 启停态都用「实底色」文字走白;启动过渡到运行的蓝,不再走近白的 surface。
    val onSolid = running || stopped || starting

    // 启动态原本落到 else→surface(近白),导致点击启动瞬间「停止灰→白→运行蓝」的闪白。
    // 现在启动直接用运行蓝,并对整段容器色做 crossfade,状态切换全程平滑。
    val targetContainer = when {
        isError -> colors.errorContainer
        isIdleRunning -> colors.statusIdle
        running -> colors.statusRunning
        starting -> colors.statusRunning
        stopped -> colors.statusStopped
        else -> colors.surface
    }
    val container by animateColorAsState(targetContainer, tween(320), label = "statusContainer")
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(72.dp)
            .clickable(onClick = onToggle),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = container),
        border = if (onSolid) {
            null
        } else {
            BorderStroke(1.dp, if (isError) colors.errorBorder else colors.border)
        },
        elevation = CardDefaults.cardElevation(0.dp),
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // Companion:角色装饰叠加在右侧,画在文本之下(文本 z 序更高),不改卡片布局。
            if (decorated) {
                StatusDecor(
                    state = mascotState,
                    modifier = Modifier.matchParentSize(),
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
                        color = if (running || starting) {
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
                        EngineStage.FORWARDING, EngineStage.STOPPED, EngineStage.STARTING ->
                            Color.White
                        EngineStage.ERROR -> colors.danger
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
                        EngineStage.STOPPED -> t("已停止", "Stopped")
                        EngineStage.STARTING -> t("正在启动", "Starting")
                        EngineStage.FORWARDING ->
                            if (isIdleRunning) t("空闲运行", "Idle") else t("运行中", "Running")
                        EngineStage.ERROR -> t("启动失败", "Failed to start")
                    },
                    fontWeight = FontWeight.Bold,
                    fontSize = 19.sp,
                    color = if (onSolid) Color.White else colors.textPrimary,
                )
                val detail = when (stage) {
                    EngineStage.STOPPED -> t("点此启动", "Tap to start")
                    EngineStage.STARTING -> state.engineMessage
                    EngineStage.FORWARDING -> if (isIdleRunning) {
                        when (state.scope) {
                            TargetScope.APPLICATIONS -> t("请选择应用", "Select apps")
                            TargetScope.ADDRESSES -> t("请添加地址", "Add addresses")
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
                            EngineStage.STOPPED,
                            EngineStage.STARTING -> Color.White.copy(alpha = 0.82f)
                            EngineStage.ERROR -> colors.danger
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
                            TargetScope.APPLICATIONS -> t("选择应用", "Select apps")
                            TargetScope.ADDRESSES -> t("指定地址", "By address")
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

/** pointy-top 六边形路径,中心 (cx,cy)、外接半径 r。 */
private fun hexPath(cx: Float, cy: Float, r: Float): Path {
    val p = Path()
    for (i in 0..5) {
        val a = Math.toRadians(60.0 * i - 90.0)
        val x = cx + r * cos(a).toFloat()
        val y = cy + r * sin(a).toFloat()
        if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
    }
    p.close()
    return p
}

/**
 * Companion 首页背景装饰(§4.4):呼应 app 图标的「六边形 + 全息碎裂」母题。
 * 静态纯绘制、低对比、在内容层之下;不进语义树、不承担功能含义。
 * 构图分几层:右上一角蜂巢六边形(疏密渐变、部分溢出边缘)、左下一枚大淡六边形平衡画面、
 * 全场散落的旋转数据方块 / 圆点 / 星座连线 / 十字点缀,交替青/粉/蓝,营造「全息数据场」而非单调一格。
 */
private fun DrawScope.drawCompanionBackdrop(colors: PakomoColors) {
    val w = size.width
    val h = size.height
    val u = size.minDimension
    val accent = colors.accent
    val cyan = colors.glitchCyan
    val pink = colors.glitchPink

    // —— 右上:一角蜂巢六边形(pointy-top,疏密/明暗渐变,部分溢出屏幕)——
    val hbx = w * 0.88f
    val hby = h * 0.10f
    val r = u * 0.085f
    val sx = 1.732f * r  // 水平间距
    val sy = 1.5f * r    // 垂直间距
    val cells = listOf(   // (col, row, alpha 系数)
        Triple(0f, 0f, 1.0f),
        Triple(1f, 0f, 0.6f),
        Triple(0f, 1f, 0.8f),
        Triple(-1f, 1f, 0.45f),
        Triple(1f, 1f, 0.4f),
        Triple(0f, -1f, 0.5f),
    )
    cells.forEach { (col, row, a) ->
        val cx = hbx + sx * (col + row / 2f)
        val cy = hby + sy * row
        drawPath(hexPath(cx, cy, r * 0.92f), accent.copy(alpha = 0.11f * a), style = Stroke(width = u * 0.0035f))
    }
    // 蜂巢里一枚填充青做点睛
    drawPath(hexPath(hbx + sx * 0.5f, hby + sy, r * 0.5f), cyan.copy(alpha = 0.10f))

    // —— 左下:一枚更大的淡六边形轮廓,平衡右上重心 ——
    drawPath(hexPath(w * 0.08f, h * 0.93f, u * 0.20f), accent.copy(alpha = 0.06f), style = Stroke(width = u * 0.004f))

    // —— 下半场散落的中小六边形,填充空旷的下方背景(不聚成簇,各自独立)——
    val lowerHexes = listOf(   // (fx, fy, r 系数, alpha, 是否填充)
        Triple(0.82f, 0.70f, 0.070f),
        Triple(0.34f, 0.80f, 0.055f),
        Triple(0.60f, 0.60f, 0.045f),
        Triple(0.18f, 0.66f, 0.038f),
        Triple(0.72f, 0.88f, 0.050f),
    )
    lowerHexes.forEachIndexed { i, (fx, fy, rc) ->
        val hr = u * rc
        if (i % 2 == 0) {
            drawPath(hexPath(w * fx, h * fy, hr), accent.copy(alpha = 0.075f), style = Stroke(width = u * 0.003f))
        } else {
            drawPath(hexPath(w * fx, h * fy, hr), cyan.copy(alpha = 0.07f))
        }
    }

    // —— 星座连线(先画,压在方块/点之下)——
    val links = listOf(
        (0.90f to 0.10f) to (0.78f to 0.22f),
        (0.78f to 0.22f) to (0.90f to 0.30f),
        (0.12f to 0.66f) to (0.06f to 0.80f),
        (0.82f to 0.70f) to (0.60f to 0.60f),
        (0.60f to 0.60f) to (0.34f to 0.80f),
        (0.34f to 0.80f) to (0.18f to 0.66f),
        (0.72f to 0.88f) to (0.82f to 0.70f),
    )
    links.forEach { (aPt, bPt) ->
        drawLine(
            accent.copy(alpha = 0.07f),
            Offset(w * aPt.first, h * aPt.second),
            Offset(w * bPt.first, h * bPt.second),
            strokeWidth = u * 0.002f,
        )
    }

    // —— 旋转数据方块(全息碎片),下半场加密填充空旷区 ——
    val squares = listOf(
        Triple(0.78f, 0.22f, cyan),
        Triple(0.90f, 0.30f, pink),
        Triple(0.15f, 0.30f, cyan),
        Triple(0.93f, 0.55f, pink),
        Triple(0.08f, 0.62f, cyan),
        Triple(0.70f, 0.86f, pink),
        // 下半场
        Triple(0.55f, 0.64f, cyan),
        Triple(0.30f, 0.72f, pink),
        Triple(0.84f, 0.80f, cyan),
        Triple(0.42f, 0.90f, pink),
        Triple(0.24f, 0.56f, cyan),
        Triple(0.66f, 0.50f, pink),
    )
    squares.forEachIndexed { i, (fx, fy, c) ->
        val s = u * (0.016f + 0.010f * ((i % 3) / 2f))
        val cx = w * fx
        val cy = h * fy
        rotate(18f + i * 15f, Offset(cx, cy)) {
            drawRect(c.copy(alpha = 0.13f), Offset(cx - s / 2f, cy - s / 2f), Size(s, s))
        }
    }

    // —— 圆点(数据节点),下半场加密 ——
    val dots = listOf(
        Triple(0.84f, 0.16f, accent),
        Triple(0.96f, 0.24f, cyan),
        Triple(0.20f, 0.72f, pink),
        Triple(0.06f, 0.80f, cyan),
        Triple(0.62f, 0.90f, accent),
        Triple(0.88f, 0.62f, cyan),
        // 下半场
        Triple(0.35f, 0.58f, accent),
        Triple(0.50f, 0.82f, cyan),
        Triple(0.72f, 0.68f, pink),
        Triple(0.14f, 0.88f, cyan),
        Triple(0.92f, 0.84f, accent),
        Triple(0.44f, 0.70f, cyan),
    )
    dots.forEach { (fx, fy, c) ->
        drawCircle(c.copy(alpha = 0.16f), u * 0.007f, Offset(w * fx, h * fy))
    }

    // —— 十字点缀 ——
    val plus = listOf(
        0.74f to 0.14f, 0.10f to 0.70f, 0.90f to 0.48f,
        0.30f to 0.84f, 0.62f to 0.58f, 0.86f to 0.74f, 0.48f to 0.94f,
    )
    plus.forEach { (fx, fy) ->
        val cx = w * fx
        val cy = h * fy
        val t = u * 0.014f
        drawLine(accent.copy(alpha = 0.10f), Offset(cx - t, cy), Offset(cx + t, cy), strokeWidth = u * 0.002f)
        drawLine(accent.copy(alpha = 0.10f), Offset(cx, cy - t), Offset(cx, cy + t), strokeWidth = u * 0.002f)
    }
}
