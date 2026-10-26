package com.example.navisense.ui.theme

import android.app.Activity
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

// Warm, premium Material 3 light color scheme for NaviSense
val NaviSenseLightColorScheme = lightColorScheme(
    primary = DeepBerry,               // #69346C Deep Berry
    onPrimary = VanillaCloud,          // #FFF8DF Vanilla Cloud
    primaryContainer = PressedInk,     // #3D1A40 Pressed Ink
    onPrimaryContainer = VanillaCloud, // #FFF8DF
    secondary = MutedBerry,
    onSecondary = VanillaCloud,
    secondaryContainer = SoftCardSurface,
    onSecondaryContainer = DarkInk,
    tertiary = SignalOrange,           // #FC6C26 Signal Orange (fill/border/icon only)
    onTertiary = DarkInk,              // #2A1230 Dark text on top of Signal Orange
    tertiaryContainer = SoftCardSurface,
    onTertiaryContainer = DarkInk,
    background = VanillaCloud,         // #FFF8DF Vanilla Cloud
    onBackground = DarkInk,            // #2A1230
    surface = VanillaCloud,            // #FFF8DF Vanilla Cloud
    onSurface = DarkInk,               // #2A1230
    surfaceVariant = SoftCardSurface,  // #F3E8EE Soft Card Surface
    onSurfaceVariant = DarkInk,        // #2A1230
    error = SignalOrange,              // #FC6C26
    onError = DarkInk,                 // #2A1230 Dark text on top
    errorContainer = SoftCardSurface,
    onErrorContainer = DarkInk,
    outline = MutedBerry.copy(alpha = 0.5f),
    outlineVariant = SoftCardSurface
)

val NaviSenseShapes = Shapes(
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(20.dp), // 20dp rounded cards
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun NaviSenseTheme(
    content: @Composable () -> Unit
) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window
            if (window != null) {
                // Vanilla Cloud background for bars with dark icons
                window.statusBarColor = VanillaCloud.toArgb()
                window.navigationBarColor = VanillaCloud.toArgb()
                val windowInsetsController = WindowCompat.getInsetsController(window, view)
                windowInsetsController.isAppearanceLightStatusBars = true
                windowInsetsController.isAppearanceLightNavigationBars = true
            }
        }
    }

    MaterialTheme(
        colorScheme = NaviSenseLightColorScheme,
        typography = Typography,
        shapes = NaviSenseShapes,
        content = content
    )
}
