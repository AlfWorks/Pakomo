package com.pakomo.ui.components

import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pakomo.core.model.EngineStage

/**
 * 看板娘 Pako 的状态表现。对齐真实引擎的 4+1 态(执行文档 §5),不引入引擎里不存在的状态。
 */
enum class MascotState { Stopped, Starting, Running, Idle, Error }

fun mascotStateOf(stage: EngineStage, isIdleRunning: Boolean): MascotState = when (stage) {
    EngineStage.STOPPED -> MascotState.Stopped
    EngineStage.STARTING -> MascotState.Starting
    EngineStage.FORWARDING -> if (isIdleRunning) MascotState.Idle else MascotState.Running
    EngineStage.ERROR -> MascotState.Error
}

/**
 * 角色美术的**唯一接缝**。当前是**白底黑框黑字编号占位**,只为看清"哪个状态的角色落在哪",
 * 不做任何美术效果。编号对应执行文档 §8.1 manifest 批次 A。
 * 真美术到位后,只把本函数体换成 `painterResource(...)` 按 [state] 取图,调用点不变。
 */
@Composable
fun PakomoMascot(state: MascotState, modifier: Modifier = Modifier) {
    val label = when (state) {
        MascotState.Running -> "A1\nmascot_status_running"
        MascotState.Idle -> "A2\nmascot_status_idle"
        MascotState.Starting -> "A3\nmascot_status_starting"
        MascotState.Stopped -> "A4\nmascot_status_stopped"
        MascotState.Error -> "A5\nmascot_status_error"
    }
    ArtPlaceholder(label = label, modifier = modifier)
}

/** 空状态/装饰角色的分类。占位阶段决定编号标签,真图到位后按 kind 取不同图。 */
enum class EmptyArtKind { Search, Targets, Address, Logs, Generic }

/**
 * 空状态/装饰角色的**唯一接缝**。当前是白底黑框黑字编号占位,编号对应执行文档 §8.1 manifest。
 * 真美术到位后只把本函数体换成 `painterResource(...)`(按 [kind] 取图),调用点不变。
 *
 * 真图接入方式按 kind 区分:
 * - [EmptyArtKind.Generic](C1 首页主卡):**差分变体**——按当前区域高度选档(tall/mid/short)、随机取一张,
 *   贴底满宽显示(各档 aspect 已匹配区域,无需拉伸)。详见执行文档 §8.1 C1 方案。
 * - 其余(B 空状态):居中 `ContentScale.Fit`。
 */
@Composable
fun EmptyStateArt(kind: EmptyArtKind, modifier: Modifier = Modifier) {
    val label = when (kind) {
        EmptyArtKind.Search -> "B1\nempty_apps"
        EmptyArtKind.Address -> "B2\nempty_address"
        EmptyArtKind.Targets -> "B3\nempty_targets"
        EmptyArtKind.Logs -> "B4\nempty_logs"
        EmptyArtKind.Generic -> "C1\nmascot_home"
    }
    ArtPlaceholder(label = label, modifier = modifier)
}

/**
 * 占位图基元:白底 + 黑框 + 黑字编号,只为看清角色资源**落在哪个位置**,不做任何美术效果。
 * 编号对应执行文档 §8.1。装饰层:从语义树移除、不可点击。
 */
@Composable
private fun ArtPlaceholder(label: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clearAndSetSemantics {}
            .background(Color.White, RoundedCornerShape(6.dp))
            .border(1.dp, Color.Black, RoundedCornerShape(6.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = Color.Black,
            fontSize = 9.sp,
            lineHeight = 11.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(4.dp),
        )
    }
}

/** 系统"减少动画"是否开启(ANIMATOR_DURATION_SCALE == 0)。故障效果据此降级为静态。 */
@Composable
fun rememberReduceMotion(): Boolean {
    val context = LocalContext.current
    return remember {
        Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        ) == 0f
    }
}
