package com.pakomo.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.clearAndSetSemantics
import com.pakomo.core.model.EngineStage
import com.pakomo.ui.theme.LocalPakomoColors

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
 * 角色美术的**唯一接缝**。
 *
 * 当前为 Compose 占位实现(一枚随状态点亮/熄灭/轻故障的"数据方块",呼应 Pako 手心托举的画面)。
 * 真美术到位后,只替换本函数体为 `painterResource(...)` 按 [state] 取图即可,所有调用点不变。
 * 装饰层:从语义树移除、不可点击、不承担功能含义。
 *
 * 资源清单见执行文档「看板娘 Pako 资产 manifest」。
 */
@Composable
fun PakomoMascot(state: MascotState, modifier: Modifier = Modifier) {
    val colors = LocalPakomoColors.current
    val fill = when (state) {
        MascotState.Running -> colors.glitchCyan
        MascotState.Idle -> colors.statusIdle
        MascotState.Starting -> colors.glitchCyan
        MascotState.Stopped -> colors.muted
        MascotState.Error -> colors.danger
    }
    val lit = state == MascotState.Running || state == MascotState.Idle
    Canvas(modifier = modifier.clearAndSetSemantics {}) {
        val s = size.minDimension
        val cube = s * 0.44f
        val left = size.width - cube - s * 0.14f
        val top = (size.height - cube) / 2f
        if (lit) {
            drawRoundRect(
                color = fill.copy(alpha = 0.16f),
                topLeft = Offset(left - s * 0.07f, top - s * 0.07f),
                size = Size(cube + s * 0.14f, cube + s * 0.14f),
                cornerRadius = CornerRadius(s * 0.16f),
            )
        }
        drawRoundRect(
            color = fill.copy(alpha = if (state == MascotState.Stopped) 0.5f else 0.9f),
            topLeft = Offset(left, top),
            size = Size(cube, cube),
            cornerRadius = CornerRadius(s * 0.12f),
        )
        // 启动/异常:一枚错位的碎块,表达"轻故障"
        if (state == MascotState.Starting || state == MascotState.Error) {
            drawRect(
                color = colors.glitchPink.copy(alpha = 0.55f),
                topLeft = Offset(left + cube * 0.62f, top - s * 0.06f),
                size = Size(cube * 0.26f, cube * 0.26f),
            )
        }
    }
}
