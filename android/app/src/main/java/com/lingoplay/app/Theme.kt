package com.lingoplay.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color

private val MidnightBackground = Color(0xFF070912)
private val MidnightSurface = Color(0xFF141927)
private val MidnightSurfaceStrong = Color(0xFF1B2233)
private val MidnightBorder = Color(0xFF34405C)
private val MidnightSecondaryText = Color(0xFFB6C0D4)

private val ContrastBackground = Color(0xFF000000)
private val ContrastSurface = Color(0xFF10131A)
private val ContrastSurfaceStrong = Color(0xFF191E28)
private val ContrastBorder = Color(0xFF5A6B8D)
private val ContrastSecondaryText = Color(0xFFD3DAE8)
private val LpPrimaryText = Color(0xFFF7F9FF)

val LpViolet = Color(0xFFA94BFF)
val LpBlue = Color(0xFF596FFF)
val LpCyan = Color(0xFF1BD1FF)

val LpBackground: Color
    @Composable
    @ReadOnlyComposable
    get() = MaterialTheme.colorScheme.background

val LpSurface: Color
    @Composable
    @ReadOnlyComposable
    get() = MaterialTheme.colorScheme.surface

val LpSurfaceStrong: Color
    @Composable
    @ReadOnlyComposable
    get() = MaterialTheme.colorScheme.surfaceVariant

val LpBorder: Color
    @Composable
    @ReadOnlyComposable
    get() = MaterialTheme.colorScheme.outline

val LpSecondaryText: Color
    @Composable
    @ReadOnlyComposable
    get() = MaterialTheme.colorScheme.onSurfaceVariant

private val MidnightColors = darkColorScheme(
    primary = LpCyan,
    secondary = LpViolet,
    background = MidnightBackground,
    surface = MidnightSurface,
    surfaceVariant = MidnightSurfaceStrong,
    outline = MidnightBorder,
    onPrimary = Color.White,
    onBackground = LpPrimaryText,
    onSurface = LpPrimaryText,
    onSurfaceVariant = MidnightSecondaryText,
)

private val HighContrastColors = darkColorScheme(
    primary = LpCyan,
    secondary = LpViolet,
    background = ContrastBackground,
    surface = ContrastSurface,
    surfaceVariant = ContrastSurfaceStrong,
    outline = ContrastBorder,
    onPrimary = Color.Black,
    onBackground = Color.White,
    onSurface = Color.White,
    onSurfaceVariant = ContrastSecondaryText,
)

@Composable
fun LingoPlayTheme(highContrast: Boolean = false, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (highContrast) HighContrastColors else MidnightColors,
        content = content,
    )
}
