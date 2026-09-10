package com.alphynia.pakomo.overlay

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.Timeline
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alphynia.pakomo.core.model.EngineStage
import com.alphynia.pakomo.core.model.NetworkRule
import com.alphynia.pakomo.ui.theme.LocalAppLanguage
import com.alphynia.pakomo.ui.theme.LocalPakomoColors
import com.alphynia.pakomo.ui.theme.t

/**
 * The quick-control overlay in the app's own design language (status-card colours, Material vector
 * icons). It renders in independent overlay windows hosted by [QuickControlService]: [QuickPill] is
 * the always-present compact stub (power over the rule-expand and chart toggles, fixed, never moves),
 * [QuickPanel] is the rule list that appears in its own wider window above or below the pill, and
 * [QuickLatencyChart] is the per-flow latency trend that slides out sideways — so opening either side
 * window never nudges the pill.
 */
@Composable
fun QuickPill(
    stage: EngineStage,
    expanded: Boolean,
    chartOpen: Boolean,
    busy: Boolean,
    onToggle: () -> Unit,
    onExpandToggle: () -> Unit,
    onChartToggle: () -> Unit,
) {
    val colors = LocalPakomoColors.current
    val running = stage == EngineStage.FORWARDING
    val starting = stage == EngineStage.STARTING
    val container by animateColorAsState(
        targetValue = when (stage) {
            EngineStage.FORWARDING, EngineStage.STARTING -> colors.statusRunning
            EngineStage.ERROR -> colors.danger
            EngineStage.STOPPED -> colors.statusStopped
        },
        animationSpec = tween(320),
        label = "container",
    )
    // A compact vertical stub: power on top, then the rule-list expand toggle, then the latency-chart
    // toggle. A little more end padding leaves a small blank strip toward the inward side.
    Surface(
        shape = RoundedCornerShape(15.dp),
        color = container,
        shadowElevation = 7.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(start = 6.dp, end = 10.dp, top = 6.dp, bottom = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(if (running || starting) Color.White.copy(alpha = 0.18f) else Color.Transparent)
                    .clickable(onClick = onToggle),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = when {
                        busy || starting -> Icons.Rounded.Sync
                        running -> Icons.Rounded.Check
                        stage == EngineStage.ERROR -> Icons.Rounded.PowerSettingsNew
                        else -> Icons.Rounded.Block
                    },
                    contentDescription = if (stage.isActive) t("停止 Pakomo", "Stop Pakomo") else t("启动 Pakomo", "Start Pakomo"),
                    tint = Color.White,
                    modifier = Modifier.size(18.dp),
                )
            }
            Spacer(Modifier.size(5.dp))
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(Color.White.copy(alpha = if (expanded) 0.26f else 0.12f))
                    .clickable(onClick = onExpandToggle),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                    contentDescription = t("展开或收起规则列表", "Expand or collapse the rule list"),
                    tint = Color.White,
                    modifier = Modifier.size(18.dp),
                )
            }
            Spacer(Modifier.size(5.dp))
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(Color.White.copy(alpha = if (chartOpen) 0.26f else 0.12f))
                    .clickable(onClick = onChartToggle),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Timeline,
                    contentDescription = t("展开或收起延迟趋势", "Show or hide the latency trend"),
                    tint = Color.White,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

@Composable
fun QuickPanel(
    stage: EngineStage,
    rule: NetworkRule?,
    rules: List<NetworkRule>,
    expanded: Boolean,
    openUp: Boolean,
    listMaxDp: Int,
    busy: Boolean,
    message: String?,
    onRule: (NetworkRule) -> Unit,
) {
    val colors = LocalPakomoColors.current
    val language = LocalAppLanguage.current
    val slide = with(LocalDensity.current) { 14.dp.roundToPx() }
    // Start hidden so the first composition animates the panel in; the service flips [expanded] to
    // false to animate it out before removing the window.
    val visible = remember { MutableTransitionState(false) }
    visible.targetState = expanded

    AnimatedVisibility(
        visibleState = visible,
        // Slide + fade (not expand/shrink) keeps the layout size constant, so the window resizes once.
        enter = fadeIn(tween(200)) + slideInVertically(
            tween(260, easing = FastOutSlowInEasing), initialOffsetY = { if (openUp) slide else -slide },
        ),
        exit = fadeOut(tween(150)) + slideOutVertically(
            tween(190, easing = FastOutSlowInEasing), targetOffsetY = { if (openUp) slide else -slide },
        ),
    ) {
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = colors.surface,
            shadowElevation = 6.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(8.dp)) {
                Text(
                    text = t("选择规则", "SWITCH RULE"),
                    color = colors.muted,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(start = 6.dp, bottom = 4.dp),
                )
                val detail = message ?: rule?.summary(language).orEmpty()
                if (detail.isNotEmpty()) {
                    Text(
                        text = detail,
                        color = if (message != null) colors.danger else colors.textSecondary,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(start = 6.dp, bottom = 6.dp),
                    )
                }
                val enabled = !busy && stage != EngineStage.STARTING
                // Cap the list to the space available in the chosen direction and scroll the overflow.
                Column(Modifier.heightIn(max = listMaxDp.dp).verticalScroll(rememberScrollState())) {
                    rules.forEach { candidate ->
                        val selected = candidate.id == rule?.id
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (selected) colors.accentTint else Color.Transparent)
                                .clickable(enabled = enabled) { onRule(candidate) }
                                .padding(horizontal = 12.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = candidate.displayName(language),
                                color = if (selected) colors.accent else colors.textPrimary,
                                fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                                fontSize = 13.5.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                            if (selected) {
                                Icon(Icons.Rounded.Check, contentDescription = null, tint = colors.accent, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}
