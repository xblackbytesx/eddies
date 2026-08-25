package com.eddies.app.core.design

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/** Gain and loss colours for the active theme, read via LocalPnlColors. */
val LocalPnlColors = staticCompositionLocalOf { DarkPnlColors }

@Composable
fun EddiesTheme(
    themeMode: ThemeMode = ThemeMode.DARK,
    // Off by default. Letting the wallpaper tint the surfaces makes the gain and
    // loss colours fight the chrome, and those two colours carry meaning here.
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val darkTheme = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK, ThemeMode.OLED -> true
    }

    val context = LocalContext.current
    val base: ColorScheme = when {
        dynamicColor && darkTheme -> dynamicDarkColorScheme(context)
        dynamicColor && !darkTheme -> dynamicLightColorScheme(context)
        darkTheme -> EddiesDarkColors
        else -> EddiesLightColors
    }
    val colorScheme = if (themeMode == ThemeMode.OLED) base.asOled() else base

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    CompositionLocalProvider(LocalPnlColors provides if (darkTheme) DarkPnlColors else LightPnlColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = EddiesTypography,
            content = content,
        )
    }
}
