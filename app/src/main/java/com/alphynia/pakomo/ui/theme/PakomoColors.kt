package com.alphynia.pakomo.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Pakomo 语义颜色 Token。
 *
 * 所有 UI 颜色都应通过 [LocalPakomoColors] 读取本类型的字段,而不是引用顶层 `val` 或内联
 * `Color(0xFF…)`。这样才能随 [ThemeMode] 切换。
 *
 * 字段刻意覆盖了代码里真实存在的内联硬编码色(状态卡橙/灰、选择器轨道、0 计数警告黄),
 * 否则 Companion 主题下这些区域换不动。
 */
@Immutable
data class PakomoColors(
    // —— 基础层 ——
    val background: Color,
    val surface: Color,
    val surfaceFold: Color,
    val border: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val muted: Color,
    // —— 强调 ——
    val accent: Color,
    val accentStrong: Color,
    val accentTint: Color,
    // —— 状态语义 ——
    val statusRunning: Color,
    val statusStopped: Color,
    val statusIdle: Color,
    val errorContainer: Color,
    val errorBorder: Color,
    val warning: Color,
    val warningContainer: Color,
    val scopeTrack: Color,
    val scopeDisabled: Color,
    val selectionBorder: Color,
    val disabledContainer: Color,
    val warningStrong: Color,
    val danger: Color,
    // —— Companion 专属(Standard 下取中性回退值)——
    val glitchCyan: Color,
    val glitchPink: Color,
)

enum class ThemeMode { Standard, Companion }

/**
 * 现状风格存档。数值与重构前的顶层 `val` 逐一相等,切到 Standard 应与旧版像素一致。
 * 请勿修改这里的数值——这是"可切换选项"里的默认专业工具主题。
 */
val StandardColors = PakomoColors(
    background = Color(0xFFF7F8F9),
    surface = Color(0xFFFFFFFF),
    surfaceFold = Color(0xFFF6F8FA),
    border = Color(0xFFEEF0F3),
    textPrimary = Color(0xFF1C1F24),
    textSecondary = Color(0xFF59616E),
    muted = Color(0xFF98A2B3),
    accent = Color(0xFF3B6FE0),
    accentStrong = Color(0xFF2F5FCA),
    accentTint = Color(0xFFE8EEFC),
    statusRunning = Color(0xFF3B6FE0),
    statusStopped = Color(0xFF8D8D8D),
    statusIdle = Color(0xFFE5A23B),
    errorContainer = Color(0xFFFFF5F4),
    errorBorder = Color(0xFFF0C8C4),
    warning = Color(0xFFB56D00),
    warningContainer = Color(0xFFFFF1D8),
    scopeTrack = Color(0xFFF0F2F5),
    scopeDisabled = Color(0xFFE2E5E9),
    selectionBorder = Color(0xFFD6DFF5),
    disabledContainer = Color(0xFFEFF1F4),
    warningStrong = Color(0xFFD98600),
    danger = Color(0xFFC0392E),
    // Standard 不表现故障美学 → 回退到中性色
    glitchCyan = Color(0xFF98A2B3),
    glitchPink = Color(0xFF98A2B3),
)

/**
 * Pakomo 看板娘主题。
 * 大面积中性面走**暖中性灰**(去紫),用来衬托冷色角色;蓝发/青眼/全息粉只体现在 accent 与故障点缀。
 * 身份色采样自 `pako.png`:accent=发色灰蓝、glitchCyan=眼睛/数据方块亮青、glitchPink=图标全息碎裂粉。
 */
val CompanionColors = PakomoColors(
    background = Color(0xFFF3F2EF),
    surface = Color(0xFFFCFBFA),
    surfaceFold = Color(0xFFEEEBE6),
    border = Color(0xFFE6E2DB),
    textPrimary = Color(0xFF2A2E38),
    textSecondary = Color(0xFF5D6068),
    muted = Color(0xFFA09E98),
    accent = Color(0xFF4A72A8),
    accentStrong = Color(0xFF3A5C8A),
    accentTint = Color(0xFFE4EAF2),
    statusRunning = Color(0xFF4A72A8),
    statusStopped = Color(0xFF9C9A94),
    statusIdle = Color(0xFFC79A54),
    errorContainer = Color(0xFFF7EEEC),
    errorBorder = Color(0xFFE7CCC6),
    warning = Color(0xFF9A6A2E),
    warningContainer = Color(0xFFF6EAD6),
    // 轨道刻意比 surface 暗一档,让分段选择器的选中段(surface)明显浮起。
    scopeTrack = Color(0xFFE4E0D9),
    scopeDisabled = Color(0xFFDBD6CE),
    selectionBorder = Color(0xFFCBD3E4),
    disabledContainer = Color(0xFFECE9E3),
    warningStrong = Color(0xFFC0873C),
    danger = Color(0xFFBA5A54),
    glitchCyan = Color(0xFF5FD0DE),
    glitchPink = Color(0xFFE6B8D0),
)

/** 默认提供 [StandardColors],保证迁移期间任何未包裹主题的读取都安全回退到现状色。 */
val LocalPakomoColors = staticCompositionLocalOf { StandardColors }

/** 当前主题模式。装饰/角色层用它判断是否为 Companion;默认 Standard。 */
val LocalThemeMode = staticCompositionLocalOf { ThemeMode.Standard }

/** 把语义 Token 拼成 Material 的 [ColorScheme],保证 Material 组件(Switch/Dialog/Card 默认色)行为不变。 */
fun PakomoColors.toMaterialColorScheme(): ColorScheme = lightColorScheme(
    primary = accent,
    onPrimary = Color.White,
    primaryContainer = accentTint,
    onPrimaryContainer = accent,
    background = background,
    onBackground = textPrimary,
    surface = surface,
    onSurface = textPrimary,
    surfaceVariant = surfaceFold,
    onSurfaceVariant = textSecondary,
    outline = border,
    error = danger,
)

fun colorsFor(themeMode: ThemeMode): PakomoColors = when (themeMode) {
    ThemeMode.Standard -> StandardColors
    ThemeMode.Companion -> CompanionColors
}
