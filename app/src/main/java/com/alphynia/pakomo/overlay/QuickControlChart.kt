package com.alphynia.pakomo.overlay

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.MutableTransitionState
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
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

/** Time buckets for the composite (overall-average) curve. */
private const val COMPOSITE_BUCKETS = 26

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

private fun flowColor(flow: FlowLatencySeries): Color {
    val key = "${flow.host}|${flow.packageName.orEmpty()}".hashCode()
    return FLOW_PALETTE[((key % FLOW_PALETTE.size) + FLOW_PALETTE.size) % FLOW_PALETTE.size]
}

/**
 * The overlay's latency monitor, shown in its own window that slides out sideways from the pill
 * (never moving it). Each measured-RTT reading is plotted as a dot in its flow's colour over a shared
 * time axis — one connection is one measurement, so the readings form a scatter, not per-flow lines —
 * and the whole picture's fluctuation is visible at once, with one bold neutral composite curve (the
 * time-bucketed average across all readings) standing out as the overall trend. Fed live from
 * `RuntimeStats.flowLatencies` only while open.
 */
@Composable
fun QuickLatencyChart(
    series: List<FlowLatencySeries>,
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
            // Flows that actually have a reading, and all their points flattened for scale + composite.
            val flows = series.filter { it.samples.isNotEmpty() }
            val all = flows.flatMap { f -> f.samples.map { Sample(it.atMs, it.measuredRttMs) } }
            val shapedCount = series.count { it.shaped }

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
                    if (all.isNotEmpty()) {
                        Text(
                            text = if (shapedCount > 0) {
                                t("${flows.size} 条流 · 整形 $shapedCount", "${flows.size} flows · $shapedCount shaped")
                            } else {
                                t("${flows.size} 条流", "${flows.size} flows")
                            },
                            color = colors.textSecondary,
                            fontSize = 9.5.sp,
                            modifier = Modifier.padding(end = 2.dp),
                        )
                    }
                }
                Spacer(Modifier.size(8.dp))
                if (all.isEmpty()) {
                    Text(
                        text = t("运行后显示各流的延迟趋势", "Trends appear once traffic flows"),
                        color = colors.textSecondary,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(start = 2.dp, bottom = 4.dp),
                    )
                } else {
                    AggregateChart(flows, all, compositeColor = colors.textPrimary, heightDp = listMaxDp.coerceIn(96, 150))
                    Spacer(Modifier.size(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // A few palette dots to signal that each flow has its own colour.
                        FLOW_PALETTE.take(3).forEach { c ->
                            LegendDot(c)
                            Spacer(Modifier.size(3.dp))
                        }
                        Spacer(Modifier.size(2.dp))
                        Text(t("各流读数", "per-flow"), color = colors.textSecondary, fontSize = 9.5.sp)
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

private data class Sample(val t: Long, val v: Int)

@Composable
private fun LegendDot(color: Color) {
    Box(Modifier.size(6.dp).clip(CircleShape).background(color))
}

@Composable
private fun LegendSwatch(color: Color) {
    Box(Modifier.size(width = 11.dp, height = 3.dp).clip(CircleShape).background(color))
}

@Composable
private fun AggregateChart(
    flows: List<FlowLatencySeries>,
    all: List<Sample>,
    compositeColor: Color,
    heightDp: Int,
) {
    val colors = LocalPakomoColors.current
    val trackColor = colors.muted.copy(alpha = 0.10f)
    val vMin = all.minOf { it.v }
    val vMax = all.maxOf { it.v }

    Box(Modifier.fillMaxWidth()) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(heightDp.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(trackColor),
        ) {
            val w = size.width
            val h = size.height
            val padY = 8.dp.toPx()
            val padX = 3.dp.toPx()
            val tMin = all.minOf { it.t }
            val tMax = all.maxOf { it.t }
            val tSpan = (tMax - tMin).coerceAtLeast(1L)
            val vSpan = (vMax - vMin).coerceAtLeast(1)
            fun xOf(t: Long): Float = padX + (t - tMin).toFloat() / tSpan * (w - 2 * padX)
            fun yOf(v: Float): Float = h - padY - (v - vMin) / vSpan * (h - 2 * padY)

            // Every reading is a dot in its flow's colour — one connection is one RTT measurement, so
            // the readings are a scatter rather than continuous per-flow lines.
            val dotRadius = 2.2.dp.toPx()
            flows.forEach { flow ->
                val c = flowColor(flow)
                flow.samples.forEach { s ->
                    drawCircle(c, radius = dotRadius, alpha = 0.9f, center = Offset(xOf(s.atMs), yOf(s.measuredRttMs.toFloat())))
                }
            }

            // Composite: average measured RTT across all flows, bucketed over the shared time axis.
            val sums = LongArray(COMPOSITE_BUCKETS)
            val counts = IntArray(COMPOSITE_BUCKETS)
            all.forEach { s ->
                val b = (((s.t - tMin).toFloat() / tSpan) * (COMPOSITE_BUCKETS - 1)).toInt()
                    .coerceIn(0, COMPOSITE_BUCKETS - 1)
                sums[b] += s.v
                counts[b]++
            }
            val composite = ArrayList<Offset>()
            for (b in 0 until COMPOSITE_BUCKETS) {
                if (counts[b] == 0) continue
                val avg = sums[b].toFloat() / counts[b]
                val bx = padX + (b.toFloat() / (COMPOSITE_BUCKETS - 1)) * (w - 2 * padX)
                composite.add(Offset(bx, yOf(avg)))
            }
            if (composite.size == 1) {
                drawCircle(compositeColor, radius = 3.dp.toPx(), center = composite[0])
            } else if (composite.size >= 2) {
                val path = Path().apply {
                    moveTo(composite.first().x, composite.first().y)
                    composite.drop(1).forEach { lineTo(it.x, it.y) }
                }
                drawPath(path, compositeColor, style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
        // Y-scale hints (measured RTT range), so the shared vertical scale is readable.
        Text(
            text = "${vMax}ms",
            color = colors.muted,
            fontSize = 8.5.sp,
            modifier = Modifier.align(Alignment.TopEnd).padding(end = 4.dp, top = 2.dp),
        )
        if (vMax != vMin) {
            Text(
                text = "${vMin}ms",
                color = colors.muted,
                fontSize = 8.5.sp,
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 4.dp, bottom = 2.dp),
            )
        }
    }
}
