package com.alphynia.pakomo.ui.components

import android.provider.Settings
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import com.alphynia.pakomo.R
import com.alphynia.pakomo.core.model.EngineStage
import com.alphynia.pakomo.ui.theme.LocalPakomoColors
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 状态卡装饰的状态。对齐真实引擎的 4+1 态(执行文档 §5),不引入引擎里不存在的状态。
 */
enum class MascotState { Stopped, Starting, Running, Idle, Error }

fun mascotStateOf(stage: EngineStage, isIdleRunning: Boolean): MascotState = when (stage) {
    EngineStage.STOPPED -> MascotState.Stopped
    EngineStage.STARTING -> MascotState.Starting
    EngineStage.FORWARDING -> if (isIdleRunning) MascotState.Idle else MascotState.Running
    EngineStage.ERROR -> MascotState.Error
}

/** 每枚碎片各自的动画「性格」——不同元素做不同的事(漂/绕/摆/晃/呼吸/闪)。 */
private enum class ChipMotion { Bob, Sway, Orbit, Wobble, Breathe, Twinkle }

/** 碎片的形状:呼应品牌「数据方块 / 全息碎裂 / 六边形」母题,不再只有圆角方块。 */
private enum class ChipShape { Square, Hex, Dot, Ring, Tick, Plus }

/** 状态卡装饰里的一枚全息碎片:位置按卡片宽/高比例,大小按卡片高度比例。 */
private class StatusChip(
    val fx: Float,   // 卡片宽度比例(0=左,1=右)
    val fy: Float,   // 卡片高度比例
    val size: Float, // 特征尺寸 = 卡片高度 × size
    val rot: Float,
    val alpha: Float,
    val shape: ChipShape,
    val motion: ChipMotion,
    val speed: Int,      // 相位倍率——**必须整数**,才能保证回环点(2π→0)首尾相接不跳。
    val phase: Float,    // 常量相位偏移,用来把各碎片错开成不齐步。
    val tint: Color? = null, // null=随状态色(ink);否则用固定全息色(青/粉),做「不随状态」的holo点缀。
)

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
 * 状态卡的**轻风格化装饰**(不是角色形象):铺满整张卡的一层漂浮圆角小块 + 柔光,
 * 右侧显眼、往左渐隐(藏在图标/文字之下),呼应品牌"数据方块 / 全息碎裂"母题。
 * **颜色随状态**(饱和卡底用白、浅底用青/红,不与底色打架),状态强弱用整体亮度(energy)体现。
 * 纯绘制、低视觉重量;从语义树移除、不可点击。角色形象只放首页底部主卡(C1)。
 */
@Composable
fun StatusDecor(state: MascotState, modifier: Modifier = Modifier) {
    val colors = LocalPakomoColors.current
    val reduceMotion = rememberReduceMotion()

    // 颜色/能量/动量都取「目标值」再用动画过渡,切状态时 crossfade,避免直接跳变造成的闪白/突变。
    val targetInk = when (state) {
        MascotState.Starting -> colors.glitchCyan
        MascotState.Error -> colors.danger
        else -> Color.White
    }
    val ink by animateColorAsState(targetInk, tween(300), label = "decorInk")

    // 整体亮度随状态强弱。
    val energy by animateFloatAsState(
        targetValue = when (state) {
            MascotState.Running -> 1f
            MascotState.Starting -> 0.85f
            MascotState.Error -> 0.85f
            MascotState.Idle -> 0.70f
            MascotState.Stopped -> 0.30f
        },
        animationSpec = tween(360),
        label = "decorEnergy",
    )

    // 动量:每个状态一个幅度。Stopped=0 → 完全静止(停止态不该有动画);reduce-motion 同样归零。
    val motion by animateFloatAsState(
        targetValue = if (reduceMotion) {
            0f
        } else {
            when (state) {
                MascotState.Running -> 1f
                MascotState.Starting -> 0.75f
                MascotState.Error -> 0.55f
                MascotState.Idle -> 0.45f
                MascotState.Stopped -> 0f
            }
        },
        animationSpec = tween(360),
        label = "decorMotion",
    )

    // 两条无限相位都在 [0,2π] 线性循环。**关键**:下面所有 sin/cos 只用相位的整数倍,
    // 才能在回环点(2π→0)首尾相接、无瞬间位移(旧实现的 cos(ph*0.8) 会在回环处跳一下)。
    val transition = rememberInfiniteTransition(label = "statusDecor")
    val drift by transition.animateFloat(
        initialValue = 0f,
        targetValue = (2.0 * Math.PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(durationMillis = 5200, easing = LinearEasing)),
        label = "drift",
    )
    val pulse by transition.animateFloat(
        initialValue = 0f,
        targetValue = (2.0 * Math.PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(durationMillis = 1400, easing = LinearEasing)),
        label = "pulse",
    )

    Canvas(modifier = modifier.clearAndSetSemantics {}) {
        val w = size.width
        val h = size.height
        val ph = drift
        val pu = pulse

        // 右侧柔光:启动态让它随脉冲「呼吸」,像正在通电。
        val glowScale = if (state == MascotState.Starting) 1f + 0.35f * sin(pu) * motion else 1f
        drawCircle(ink.copy(alpha = 0.10f * energy), h * 0.72f * glowScale, Offset(w * 0.86f, h * 0.5f))

        // 一小簇**全息碎片**:形状(方块/六边形/圆点/环/短杠/十字)与动画(漂/绕/摆/晃/呼吸/闪)
        // 各自搭配、速度相位互不相同 → 看起来是一群各干各的碎片在悬浮,而不是齐步方阵。青/粉两枚 holo
        // 点缀不随状态变色。所有相位倍率取整数 → 回环无跳变;所有效果都是**有界振荡**(不累积)→
        // 可被 motion 平滑门控到静止(停止态 motion=0 全部归位,切状态时随 motion 平滑起停)。
        val chips = listOf(
            // 右侧主簇:品牌大六边形轮廓当锚点,周围数据方块 + 圆点 + 环
            StatusChip(0.86f, 0.50f, 0.62f, 0f, 0.42f, ChipShape.Hex, ChipMotion.Breathe, 1, 0.0f),
            StatusChip(0.72f, 0.30f, 0.28f, 16f, 0.70f, ChipShape.Square, ChipMotion.Bob, 2, 0.7f),
            StatusChip(0.94f, 0.72f, 0.22f, -18f, 0.55f, ChipShape.Square, ChipMotion.Sway, 3, 0.4f),
            StatusChip(0.63f, 0.60f, 0.13f, 0f, 0.65f, ChipShape.Dot, ChipMotion.Orbit, 1, 1.3f, colors.glitchCyan),
            StatusChip(0.81f, 0.66f, 0.09f, 0f, 0.75f, ChipShape.Dot, ChipMotion.Twinkle, 3, 1.7f),
            StatusChip(0.55f, 0.32f, 0.20f, 0f, 0.40f, ChipShape.Ring, ChipMotion.Breathe, 2, 2.1f, colors.glitchPink),
            StatusChip(0.99f, 0.40f, 0.26f, -24f, 0.45f, ChipShape.Tick, ChipMotion.Wobble, 2, 0.9f),
            // 左侧点缀:更小更淡,藏在文字后不抢读
            StatusChip(0.45f, 0.72f, 0.12f, 0f, 0.30f, ChipShape.Plus, ChipMotion.Twinkle, 2, 0.3f, colors.glitchCyan),
            StatusChip(0.30f, 0.40f, 0.08f, 0f, 0.20f, ChipShape.Dot, ChipMotion.Orbit, 2, 2.4f),
            StatusChip(0.20f, 0.66f, 0.14f, 10f, 0.16f, ChipShape.Square, ChipMotion.Wobble, 3, 1.1f),
        )
        chips.forEach { chip ->
            val th = ph * chip.speed + chip.phase
            var dx = 0f
            var dy = 0f
            var scale = 1f
            var spin = 0f
            var alphaMul = 1f
            when (chip.motion) {
                ChipMotion.Bob -> dy = sin(th) * h * 0.06f            // 上下浮沉
                ChipMotion.Sway -> dx = cos(th) * h * 0.05f           // 左右平移
                ChipMotion.Orbit -> {                                  // 小幅绕圈
                    dx = cos(th) * h * 0.045f
                    dy = sin(th) * h * 0.045f
                }
                ChipMotion.Wobble -> spin = sin(th) * 16f             // 原地左右摇摆(转角振荡,非整圈,便于门控)
                ChipMotion.Breathe -> scale = 1f + 0.06f * sin(th)    // 缩放呼吸
                ChipMotion.Twinkle -> alphaMul = 0.55f + 0.45f * (sin(th) * 0.5f + 0.5f) // 明暗闪烁
            }
            // motion 门控:所有量都往「静止值」收敛;motion 自身是动画值 → 起停平滑,停止态完全静止。
            dx *= motion
            dy *= motion
            spin *= motion
            scale = 1f + (scale - 1f) * motion
            alphaMul = 1f + (alphaMul - 1f) * motion

            // 异常:再叠一层高频横向抖动;最大的碎片额外整体错位,做故障感。
            val shakeX = if (state == MascotState.Error) sin(pu * 4f + chip.phase) * w * 0.010f * motion else 0f
            val glitchDx = if (state == MascotState.Error && chip.size > 0.45f) w * 0.02f * motion else 0f

            val cs = h * chip.size * scale
            val cx = w * chip.fx + dx + shakeX + glitchDx
            val cy = h * chip.fy + dy
            val left = cx - cs / 2f
            val top = cy - cs / 2f
            val col = (chip.tint ?: ink).copy(alpha = (chip.alpha * energy * alphaMul).coerceIn(0f, 1f))
            rotate(chip.rot + spin, Offset(cx, cy)) {
                when (chip.shape) {
                    ChipShape.Square ->
                        drawRoundRect(col, Offset(left, top), Size(cs, cs), CornerRadius(cs * 0.34f))
                    ChipShape.Hex ->
                        drawPath(hexPath(cx, cy, cs / 2f), col, style = Stroke(width = h * 0.022f))
                    ChipShape.Dot ->
                        drawCircle(col, cs / 2f, Offset(cx, cy))
                    ChipShape.Ring ->
                        drawCircle(col, cs / 2f, Offset(cx, cy), style = Stroke(width = h * 0.020f))
                    ChipShape.Tick ->
                        drawRoundRect(
                            col,
                            Offset(left, cy - cs * 0.16f),
                            Size(cs, cs * 0.32f),
                            CornerRadius(cs * 0.16f),
                        )
                    ChipShape.Plus -> {
                        val t = cs * 0.26f
                        drawRoundRect(col, Offset(left, cy - t / 2f), Size(cs, t), CornerRadius(t / 2f))
                        drawRoundRect(col, Offset(cx - t / 2f, top), Size(t, cs), CornerRadius(t / 2f))
                    }
                }
            }
        }
        // 异常:主块旁一枚错位小碎片(错误态卡底是浅色,用粉不打架)
        if (state == MascotState.Error) {
            drawRoundRect(
                colors.glitchPink.copy(alpha = 0.6f),
                Offset(w * 0.90f, h * 0.30f),
                Size(h * 0.14f, h * 0.14f),
                CornerRadius(h * 0.05f),
            )
        }
    }
}

/** 空状态/装饰角色的分类。占位阶段决定编号标签,真图到位后按 kind 取不同图。 */
enum class EmptyArtKind { Search, Targets, Address, Logs, Generic }

/** C1 首页主卡可用的角色图集合;差分随机从中取一张(后续加图只需往这里追加)。 */
private val mascotHomeArt = intArrayOf(
    R.drawable.mascot_home_1,
    R.drawable.mascot_home_2,
    R.drawable.mascot_home_3,
    R.drawable.mascot_home_4,
)

/**
 * 空状态/装饰角色的**唯一接缝**。真图到位的 kind 走 `painterResource`,其余仍用编号占位(执行文档 §8.1)。
 *
 * 真图接入方式按 kind 区分:
 * - [EmptyArtKind.Generic](C1 首页主卡):**统一最小档尺寸 + 差分随机**——满宽、贴底、保持比例、不拉伸,
 *   从 `mascot_home_1..n` 随机取一张;区域更高时上方自然留空。详见执行文档 §8.1 C1 方案。**已接入。**
 *   传入 [refreshKey](随其变化重掷):进入/返回首页靠重组自然重取,启停切换靠 key 变化重取,
 *   并用 Crossfade 平滑过渡。
 * - 其余(B 空状态 apps/address/targets/logs):**共用一张** `mascot_empty`(底部渐隐的半身立绘),
 *   `ContentScale.Fit` 满色居中显示。**已接入。**
 */
@Composable
fun EmptyStateArt(kind: EmptyArtKind, modifier: Modifier = Modifier, refreshKey: Any? = Unit) {
    if (kind == EmptyArtKind.Generic) {
        // 差分随机:key 变化(启停切换)或重组(进入/返回首页)时重掷一张,否则保持不变、不每帧重掷。
        val art = remember(refreshKey) { mascotHomeArt.random() }
        val pageBg = LocalPakomoColors.current.background
        // 略去饱和度,让蓝发/彩色往灰靠一档,更像统一的灰度水印(1=原色,0=纯灰)。
        val desaturate = remember { ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0.65f) }) }
        // 切换时在两张之间做淡入淡出,避免硬切。
        Crossfade(
            targetState = art,
            modifier = modifier.clearAndSetSemantics {},
            animationSpec = tween(360),
            label = "mascotHome",
        ) { res ->
            // 位图后台降采样解码 + 缓存(见 rememberMascotBitmap):就绪前不画,避免首帧被多兆位图解码卡住。
            val bitmap = rememberMascotBitmap(res)
            // 两层合成:底层用页面底色按角色轮廓填成**不透明**遮罩,挡住背后 backdrop,
            // 使碎片/六边形不会透过角色;上层再压低不透明度画真图 → 角色只淡淡浮现,却不漏底。
            if (bitmap != null) {
                Box(Modifier.fillMaxSize()) {
                    Image(
                        bitmap = bitmap,
                        contentDescription = null,
                        contentScale = ContentScale.FillWidth,   // 满宽、保持比例、不拉伸
                        alignment = Alignment.BottomCenter,       // 贴底;区域更高时上方留空
                        colorFilter = ColorFilter.tint(pageBg),   // 轮廓填成页面底色 → 遮住背后装饰
                        modifier = Modifier.matchParentSize(),
                    )
                    Image(
                        bitmap = bitmap,
                        contentDescription = null,
                        contentScale = ContentScale.FillWidth,
                        alignment = Alignment.BottomCenter,
                        colorFilter = desaturate,                 // 拉一点灰度
                        alpha = 0.18f,                            // 薄薄的水印,略回正一点
                        modifier = Modifier.matchParentSize(),
                    )
                }
            }
        }
        return
    }
    // B-series empty states (apps / address / targets / logs) all share one mascot illustration:
    // a bottom-faded bust so the half-body cut dissolves softly instead of looking chopped in a
    // small centered box. Softened (slight desaturation + alpha) so it reads as a gentle decoration
    // rather than a sharp attention-grabbing focal point.
    val softenB = remember { ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0.8f) }) }
    val emptyBitmap = rememberMascotBitmap(R.drawable.mascot_empty)
    if (emptyBitmap != null) {
        Image(
            bitmap = emptyBitmap,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            alpha = 0.85f,
            colorFilter = softenB,
            modifier = modifier.clearAndSetSemantics {},
        )
    }
}

private val mascotBitmapCache =
    java.util.concurrent.ConcurrentHashMap<Int, androidx.compose.ui.graphics.ImageBitmap>()

/**
 * Mascot art decoded off the main thread and downsampled to a watermark-appropriate width, cached by
 * resource id. Returns null until ready, so the first frame is never blocked on a multi-megabyte
 * bitmap decode — which is what made Home startup and start/stop drop frames.
 */
@Composable
private fun rememberMascotBitmap(resId: Int): androidx.compose.ui.graphics.ImageBitmap? {
    val context = LocalContext.current
    val produced by produceState<androidx.compose.ui.graphics.ImageBitmap?>(
        initialValue = mascotBitmapCache[resId],
        key1 = resId,
    ) {
        if (value == null) {
            value = withContext(Dispatchers.Default) {
                mascotBitmapCache.getOrPut(resId) { decodeSampledMascot(context, resId, 800) }
            }
        }
    }
    return produced
}

/** Decode [resId] at the largest power-of-two subsample whose width still covers [reqWidth]. */
private fun decodeSampledMascot(
    context: android.content.Context,
    resId: Int,
    reqWidth: Int,
): androidx.compose.ui.graphics.ImageBitmap {
    val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
    android.graphics.BitmapFactory.decodeResource(context.resources, resId, bounds)
    var sample = 1
    while (bounds.outWidth > 0 && bounds.outWidth / (sample * 2) >= reqWidth) sample *= 2
    val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
    return android.graphics.BitmapFactory.decodeResource(context.resources, resId, opts).asImageBitmap()
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
