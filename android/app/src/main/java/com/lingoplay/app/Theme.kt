package com.lingoplay.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val LpBackground = Color(0xFF070912)
val LpSurface = Color(0xFF111522)
val LpSurfaceStrong = Color(0xFF171C2C)
val LpBorder = Color(0xFF283149)
val LpViolet = Color(0xFFA94BFF)
val LpBlue = Color(0xFF596FFF)
val LpCyan = Color(0xFF1BD1FF)
val LpSecondaryText = Color(0xFF9BA5BA)

private val LingoPlayColors = darkColorScheme(
    primary = LpCyan,
    secondary = LpViolet,
    background = LpBackground,
    surface = LpSurface,
    surfaceVariant = LpSurfaceStrong,
    outline = LpBorder,
    onPrimary = Color.White,
    onBackground = Color.White,
    onSurface = Color.White,
    onSurfaceVariant = LpSecondaryText,
)

@Composable
fun LingoPlayTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = LingoPlayColors,
        content = content,
    )
}
