package com.example.navisense.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// High-contrast accessibility dark palette designed for low-vision readability
private val HighContrastDarkColorScheme = darkColorScheme(
    primary = Color(0xFF00E5FF),          // High-contrast Vivid Cyan
    onPrimary = Color(0xFF000000),        // Deep Black on Cyan
    primaryContainer = Color(0xFF004D40),
    onPrimaryContainer = Color(0xFFE0F7FA),
    secondary = Color(0xFFFFD600),        // High-contrast Vivid Yellow
    onSecondary = Color(0xFF000000),
    secondaryContainer = Color(0xFF37474F),
    onSecondaryContainer = Color(0xFFECEFF1),
    background = Color(0xFF121212),       // Deep Dark Background
    onBackground = Color(0xFFFFFFFF),     // Pure White text
    surface = Color(0xFF1E1E1E),          // Accessible Surface Card
    onSurface = Color(0xFFFFFFFF),
    error = Color(0xFFFF5252),
    onError = Color(0xFF000000)
)

@Composable
fun NaviSenseTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = HighContrastDarkColorScheme,
        typography = Typography,
        content = content
    )
}
