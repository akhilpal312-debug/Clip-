package com.aiclipcreator.core.common.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Soft neutral palette: warm paper, deep forest accent, brushed gold highlight.
 * Surfaces stay translucent so the layered layout reads as a video tool, not a dashboard.
 */
data class AppColors(
    val paper: Color,
    val surface: Color,
    val surfaceAlt: Color,
    val ink: Color,
    val inkMuted: Color,
    val primary: Color,
    val onPrimary: Color,
    val accent: Color,
    val success: Color,
    val warning: Color,
    val danger: Color,
    val outline: Color,
    val scrim: Color
)

private val LightColors = AppColors(
    paper = Color(0xFFF3F1EC),
    surface = Color(0xFFFFFFFF),
    surfaceAlt = Color(0xFFE9E5DD),
    ink = Color(0xFF1D2327),
    inkMuted = Color(0xFF5F6A6E),
    primary = Color(0xFF2C5648),
    onPrimary = Color(0xFFF7F5F0),
    accent = Color(0xFFB08542),
    success = Color(0xFF2F7D5C),
    warning = Color(0xFFB26B1F),
    danger = Color(0xFFA3352A),
    outline = Color(0xFFD5D0C6),
    scrim = Color(0x14000000)
)

private val DarkColors = AppColors(
    paper = Color(0xFF14181A),
    surface = Color(0xFF1C2124),
    surfaceAlt = Color(0xFF262C2F),
    ink = Color(0xFFF1EFEA),
    inkMuted = Color(0xFFA5ADB0),
    primary = Color(0xFF8FC7B1),
    onPrimary = Color(0xFF10201A),
    accent = Color(0xFFD8B876),
    success = Color(0xFF6FC79B),
    warning = Color(0xFFE0A45C),
    danger = Color(0xFFE08A80),
    outline = Color(0xFF3A4245),
    scrim = Color(0x33000000)
)

val LocalAppColors = staticCompositionLocalOf { LightColors }

private fun scheme(c: AppColors) = lightColorScheme(
    primary = c.primary,
    onPrimary = c.onPrimary,
    secondary = c.accent,
    onSecondary = Color.White,
    background = c.paper,
    onBackground = c.ink,
    surface = c.surface,
    onSurface = c.ink,
    surfaceVariant = c.surfaceAlt,
    onSurfaceVariant = c.inkMuted,
    outline = c.outline,
    error = c.danger
)

private fun darkScheme(c: AppColors) = darkColorScheme(
    primary = c.primary,
    onPrimary = c.onPrimary,
    secondary = c.accent,
    onSecondary = Color(0xFF1B1B1B),
    background = c.paper,
    onBackground = c.ink,
    surface = c.surface,
    onSurface = c.ink,
    surfaceVariant = c.surfaceAlt,
    onSurfaceVariant = c.inkMuted,
    outline = c.outline,
    error = c.danger
)

private val AppTypography = Typography(
    displaySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 30.sp,
        lineHeight = 36.sp,
        letterSpacing = (-0.4).sp
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 30.sp,
        letterSpacing = (-0.3).sp
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 19.sp,
        lineHeight = 24.sp
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 22.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 22.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 13.5.sp,
        lineHeight = 19.sp
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        letterSpacing = 0.3.sp
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        letterSpacing = 0.6.sp
    )
)

@Composable
fun AppTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val colors = if (darkTheme) DarkColors else LightColors
    CompositionLocalProvider(LocalAppColors provides colors) {
        MaterialTheme(
            colorScheme = if (darkTheme) darkScheme(colors) else scheme(colors),
            typography = AppTypography,
            content = content
        )
    }
}

/** Shorthand for the palette inside composables. */
val appColors: AppColors
    @Composable get() = LocalAppColors.current
