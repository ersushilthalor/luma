package com.datarohit.luma.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LumaColors = darkColorScheme(
    primary = Color(0xFFF2B33D),
    onPrimary = Color(0xFF1A1206),
    secondary = Color(0xFFBFC6D1),
    onSecondary = Color(0xFF14171C),
    background = Color(0xFF0A0A0B),
    onBackground = Color(0xFFEDEDED),
    surface = Color(0xFF0A0A0B),
    onSurface = Color(0xFFEDEDED),
    surfaceContainer = Color(0xFF17181A),
    surfaceContainerHigh = Color(0xFF202124),
    onSurfaceVariant = Color(0xFFA3A6AB),
    outline = Color(0xFF3A3C40),
    error = Color(0xFFFF8A80),
)

@Composable
fun LumaTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = LumaColors, typography = Typography(), content = content)
}
