package com.alphynia.pakomo.overlay

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alphynia.pakomo.core.model.FlowLatencySeries
import com.alphynia.pakomo.ui.theme.LocalPakomoColors
import com.alphynia.pakomo.ui.theme.t
import kotlin.math.ceil
import kotlinx.coroutines.delay

/** Intermittent pan cadence: slide one step over [SLIDE_MS], then hold until the next [SAMPLE_MS]. */
private const val SLIDE_MS = 280
private const val SAMPLE_MS = 1_000

/** Round a peak value up to a "nice" step so the Y-scale only changes at boundaries (stable shape). */
private fun niceCeil(v: Float): Float {
    if (v <= 1f) return 1f
    val step = when {
        v <= 20f -> 5f
        v <= 50f -> 10f
        v <= 100f -> 20f
        v <= 200f -> 25f
        v <= 500f -> 50f
        v <= 1000f -> 100f
        v <= 2000f -> 200f
        else -> 500f
    }
    return ceil(v / step) * step
}

/** Number of history columns kept (one per second) — the visible time span. */
private const val LAT_HISTORY = 60

/** Smoothing factor for the composite (overall-average) curve — lower is steadier. */
private const val COMPOSITE_ALPHA = 0.35f

/**
 * One appended column of the chart history, captured once per heartbeat and never recomputed: each
 * flow's current RTT keyed by identity, the smoothed average, and how many flows are shaped. Columns
 * are immutable once added, so drawn history (and the composite tail) can't shift when new data comes.
 */
internal class LatencyColumn(
    val slot: Int,
    val perFlow: Map<String, Int>,
    val average: Float?,
    val shapedCount: Int,
)

/**
 * The latency chart's rolling history. Held by [QuickControlService] (not by the composable) so it
 * survives the chart window opening and closing — reopening shows the running history rather than a
 * blank restart. [run] accumulates one column per second in the service scope (no frame clock needed,
 * as it only mutates state); the composable observes [nextSlot] and animates [scrollOffset] itself.
 */
internal class LatencyChartState {
    val columns = mutableStateListOf<LatencyColumn>()
    val scrollOffset = Animatable(0f)
    private val slotState = mutableIntStateOf(0)
    val nextSlot: Int get() = slotState.intValue
    private var ema: Float? = null

    private fun record(flows: List<FlowLatencySeries>) {
        // Post-shaping latency = the real handshake RTT plus the shaper's injected round-trip delay
        // (0 when unshaped), i.e. what the flow effectively experiences once the rule is applied.
        val perFlow = flows.associate { f -> flowKey(f) to f.samples.last().let { it.measuredRttMs + it.injectedMs } }
        val mean = if (perFlow.isEmpty()) null else perFlow.values.sum().toFloat() / perFlow.size
        if (mean != null) ema = ema?.let { COMPOSITE_ALPHA * mean + (1 - COMPOSITE_ALPHA) * it } ?: mean
        columns.add(LatencyColumn(slotState.intValue, perFlow, if (mean == null) null else ema, flows.count { it.shaped }))
        slotState.intValue += 1
        while (columns.size > LAT_HISTORY) columns.removeAt(0)
    }

    /** Appends a column every second from [flowsProvider]; runs for the service's lifetime. */
    suspend fun run(flowsProvider: () -> List<FlowLatencySeries>) {
        while (true) {
            record(flowsProvider())
            delay(SAMPLE_MS.toLong())
        }
    }
}

/**
 * A categorical palette for per-flow curves — medium-lightness hues that stay legible on both the
 * light off-white and the dark overlay surface. A flow keeps its colour across pulses because the
 * index is derived from its identity (host + app), not its position in the list.
 */
private val FLOW_PALETTE = listOf(
    Color(0xFF4C8DFF), // blue
    Color(0xFFF2994A), // orange
    Color(0xFF27AE60), // green
    Color(0xFFEB5757), // red
    Color(0xFF9B51E0), // purple
    Color(0xFF2D9CDB), // sky
    Color(0xFFE0B93C), // amber
    Color(0xFFEB5FA0), // pink
)

private fun flowKey(flow: FlowLatencySeries): String = "${flow.host}|${flow.packageName.orEmpty()}"

private fun colorForKey(key: String): Color {
    val h = key.hashCode()
    return FLOW_PALETTE[((h % FLOW_PALETTE.size) + FLOW_PALETTE.size) % FLOW_PALETTE.size]
}

/**
 * A smooth curve through [points] using cubic segments with horizontal-midpoint control points (an
 * elegant, monotonic-in-x spline). When [closeToBottom] is given, the path is closed down to that Y so
 * it can be filled as an area under the curve.
 */
private fun smoothPath(points: List<Offset>, closeToBottom: Float? = null): Path {
    val path = Path()
    if (points.isEmpty()) return path
    path.moveTo(points[0].x, points[0].y)
    for (i in 1 until points.size) {
        val p0 = points[i - 1]
        val p1 = points[i]
        val midX = (p0.x + p1.x) / 2f
        path.cubicTo(midX, p0.y, midX, p1.y, p1.x, p1.y)
    }
    if (closeToBottom != null) {
        path.lineTo(points.last().x, closeToBottom)
        path.lineTo(points.first().x, closeToBottom)
        path.close()
    }
    return path
}

/**
 * The overlay's latency monitor, shown in its own window that slides out sideways from the pill
 * (never moving it). It keeps its own append-only, one-column-per-second history (like the home
 * traffic graph): each flow is a continuous coloured curve of its measured RTT, with one bold neutral
 * composite curve — a smoothed average across all flows — as the overall trend. A steady heartbeat
 * slides the plot one step then holds, so it pans smoothly and never freezes when flows go idle. Fed
 * live from `RuntimeStats.flowLatencies` only while open.
 */
@Composable
internal fun QuickLatencyChart(
    state: LatencyChartState,
    expanded: Boolean,
    openLeft: Boolean,
    listMaxDp: Int,
) {
    val colors = LocalPakomoColors.current
    val slide = with(LocalDensity.current) { 16.dp.roundToPx() }
    val visible = remember { MutableTransitionState(false) }
    visible.targetState = expanded

    AnimatedVisibility(
        visibleState = visible,
        enter = fadeIn(tween(200)) + slideInHorizontally(
            tween(260, easing = FastOutSlowInEasing), initialOffsetX = { if (openLeft) slide else -slide },
        ),
        exit = fadeOut(tween(150)) + slideOutHorizontally(
            tween(190, easing = FastOutSlowInEasing), targetOffsetX = { if (openLeft) slide else -slide },
        ),
    ) {
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = colors.surface,
            shadowElevation = 6.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            val latest = state.columns.lastOrNull()
            val flowCount = latest?.perFlow?.size ?: 0
            val shapedCount = latest?.shapedCount ?: 0

            Column(Modifier.padding(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = t("延迟趋势", "LATENCY"),
                        color = colors.muted,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(start = 2.dp),
                    )
                    Spacer(Modifier.weight(1f))
                    if (flowCount > 0) {
                        Text(
                            text = if (shapedCount > 0) {
                                t("$flowCount 条流 · 整形 $shapedCount", "$flowCount flows · $shapedCount shaped")
                            } else {
                                t("$flowCount 条流", "$flowCount flows")
                            },
                            color = colors.textSecondary,
                            fontSize = 9.5.sp,
                            modifier = Modifier.padding(end = 2.dp),
                        )
                    }
                }
                Spacer(Modifier.size(8.dp))
                if (state.columns.none { it.perFlow.isNotEmpty() }) {
                    Text(
                        text = t("运行后显示各流的延迟趋势", "Trends appear once traffic flows"),
                        color = colors.textSecondary,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(start = 2.dp, bottom = 4.dp),
                    )
                } else {
                    AggregateChart(
                        state = state,
                        compositeColor = colors.textPrimary,
                        heightDp = listMaxDp.coerceIn(96, 150),
                    )
                    Spacer(Modifier.size(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        LegendSwatch(FLOW_PALETTE[0])
                        Spacer(Modifier.size(2.dp))
                        LegendSwatch(FLOW_PALETTE[2])
                        Spacer(Modifier.size(4.dp))
                        Text(t("各流", "per-flow"), color = colors.textSecondary, fontSize = 9.5.sp)
                        Spacer(Modifier.size(12.dp))
                        LegendSwatch(colors.textPrimary)
                        Spacer(Modifier.size(4.dp))
                        Text(t("综合平均", "average"), color = colors.textSecondary, fontSize = 9.5.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun LegendSwatch(color: Color) {
    Box(Modifier.size(width = 11.dp, height = 3.dp).clip(CircleShape).background(color))
}

@Composable
private fun AggregateChart(
    state: LatencyChartState,
    compositeColor: Color,
    heightDp: Int,
) {
    val colors = LocalPakomoColors.current
    val trackColor = colors.muted.copy(alpha = 0.10f)

    // The service appends columns; here we only animate the pan. On (re)open, snap to the current slot
    // so the running history shows at once; on each new column, slide one step. The Animatable lives in
    // the composable so it has a frame clock.
    val firstShow = remember { booleanArrayOf(true) }
    LaunchedEffect(state.nextSlot) {
        val target = (state.nextSlot - 1).coerceAtLeast(0).toFloat()
        if (firstShow[0]) {
            state.scrollOffset.snapTo(target)
            firstShow[0] = false
        } else {
            state.scrollOffset.animateTo(target, tween(SLIDE_MS, easing = FastOutSlowInEasing))
        }
    }

    // Stable 0-based scale: round the peak up to a "nice" step so the top changes only at boundaries.
    val dataMax = state.columns.flatMap { it.perFlow.values }.maxOrNull()?.toFloat() ?: 1f
    val vMax by animateFloatAsState(niceCeil(dataMax), tween(500), label = "vMax")

    Box(Modifier.fillMaxWidth()) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(heightDp.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(trackColor),
        ) {
            val columns = state.columns
            if (columns.isEmpty()) return@Canvas
            val w = size.width
            val h = size.height
            val padY = 8.dp.toPx()
            val stepX = w / (LAT_HISTORY - 1)
            val offset = state.scrollOffset.value
            val scaleTop = vMax.coerceAtLeast(1f)
            fun xOf(slot: Int): Float = w + (slot - offset) * stepX
            fun yOf(v: Float): Float = h - padY - (v / scaleTop) * (h - 2 * padY)

            // Build each flow's points (present columns only), then draw as a smooth curve with a soft
            // gradient fill beneath — fills first, then lines, so the strokes sit cleanly on top.
            val keys = HashSet<String>()
            columns.forEach { keys.addAll(it.perFlow.keys) }
            val series = keys.map { key ->
                key to columns.mapNotNull { col -> col.perFlow[key]?.let { Offset(xOf(col.slot), yOf(it.toFloat())) } }
            }
            val lineWidth = 1.4.dp.toPx()
            series.forEach { (key, pts) ->
                if (pts.size < 2) return@forEach
                val fill = smoothPath(pts, closeToBottom = h)
                drawPath(
                    fill,
                    brush = Brush.verticalGradient(
                        listOf(colorForKey(key).copy(alpha = 0.16f), Color.Transparent),
                        startY = 0f,
                        endY = h,
                    ),
                )
            }
            series.forEach { (key, pts) ->
                if (pts.size < 2) return@forEach
                drawPath(smoothPath(pts), colorForKey(key), style = Stroke(width = lineWidth, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }

            // Composite: the smoothed average, drawn on top as a bold neutral curve.
            val cpts = columns.mapNotNull { col -> col.average?.let { Offset(xOf(col.slot), yOf(it)) } }
            if (cpts.size >= 2) {
                drawPath(smoothPath(cpts), compositeColor, style = Stroke(width = 1.9.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
        // Y-scale hints: the peak RTT at the top, the fixed 0 baseline at the bottom.
        Text(
            text = "${vMax.toInt()}ms",
            color = colors.muted,
            fontSize = 8.5.sp,
            modifier = Modifier.align(Alignment.TopEnd).padding(end = 4.dp, top = 2.dp),
        )
        Text(
            text = "0",
            color = colors.muted,
            fontSize = 8.5.sp,
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 4.dp, bottom = 2.dp),
        )
    }
}
