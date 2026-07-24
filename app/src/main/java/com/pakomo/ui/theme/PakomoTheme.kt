package com.pakomo.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Tokens trued to the Penpot "v2" (blue) design palette.
val Accent = Color(0xFF3B6FE0)
val AccentStrong = Color(0xFF2F5FCA)
val AccentTint = Color(0xFFE8EEFC)
val Background = Color(0xFFF7F8F9)
val Surface = Color(0xFFFFFFFF)
val SurfaceFold = Color(0xFFF6F8FA)
val Border = Color(0xFFEEF0F3)
val OnSurface = Color(0xFF1C1F24)
val OnSurfaceVariant = Color(0xFF59616E)
val Muted = Color(0xFF98A2B3)
val Danger = Color(0xFFC0392E)

private val PakomoColors = lightColorScheme(
    primary = Accent,
    onPrimary = Color.White,
    primaryContainer = AccentTint,
    onPrimaryContainer = Accent,
    background = Background,
    onBackground = OnSurface,
    surface = Surface,
    onSurface = OnSurface,
    surfaceVariant = SurfaceFold,
    onSurfaceVariant = OnSurfaceVariant,
    outline = Border,
    error = Danger,
)

private val PakomoTypography = Typography(
    headlineSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 20.sp,
        lineHeight = 28.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp,
        lineHeight = 22.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 17.sp,
    ),
)

@Composable
fun PakomoTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = PakomoColors,
        typography = PakomoTypography,
        content = content,
    )
}
