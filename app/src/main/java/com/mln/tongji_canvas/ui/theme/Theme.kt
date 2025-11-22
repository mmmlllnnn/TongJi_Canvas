package com.mln.tongji_canvas.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import com.google.accompanist.systemuicontroller.rememberSystemUiController

private val LightColorScheme = lightColorScheme(
    primary = CanvasPalette.Primary,
    onPrimary = Color.White,
    primaryContainer = CanvasPalette.PrimaryMuted,
    onPrimaryContainer = CanvasPalette.TextPrimary,
    secondary = CanvasPalette.TextSecondary,
    onSecondary = CanvasPalette.Snow,
    tertiary = CanvasPalette.Accent,
    background = CanvasPalette.Mist,
    surface = CanvasPalette.Card,
    surfaceVariant = CanvasPalette.Mist,
    onSurface = CanvasPalette.TextPrimary,
    onSurfaceVariant = CanvasPalette.TextSecondary,
    outline = CanvasPalette.Outline,
    error = CanvasPalette.Danger,
    onError = Color.White
)

private val DarkColorScheme = darkColorScheme(
    primary = CanvasPalette.PrimaryDark,
    onPrimary = Color.White,
    primaryContainer = CanvasPalette.PrimaryDark.copy(alpha = 0.3f),
    onPrimaryContainer = Color.White,
    secondary = CanvasPalette.TextSecondaryDark,
    onSecondary = CanvasPalette.MidnightSurface,
    tertiary = CanvasPalette.Accent,
    background = CanvasPalette.Midnight,
    surface = CanvasPalette.MidnightSurface,
    surfaceVariant = CanvasPalette.MidnightCard,
    onSurface = CanvasPalette.TextPrimaryDark,
    onSurfaceVariant = CanvasPalette.TextSecondaryDark,
    outline = CanvasPalette.MidnightOutline,
    error = CanvasPalette.Danger,
    onError = Color.White
)

@Composable
fun Canvas_batch_signTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    val systemUiController = rememberSystemUiController()
    SideEffect {
        systemUiController.setStatusBarColor(
            color = Color.Transparent,
            darkIcons = !darkTheme
        )
        systemUiController.setNavigationBarColor(
            color = Color.Transparent,
            darkIcons = !darkTheme,
            navigationBarContrastEnforced = false
        )
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}