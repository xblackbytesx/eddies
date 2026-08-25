package com.eddies.app.core.design

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

// Eddies runs on a cyan/magenta accent against near-black, which is where the
// name comes from. The gain and loss colours are defined separately below,
// because they carry meaning and must not be tinted by Material You.
private val Cyan = Color(0xFF3DD6D0)
private val CyanDark = Color(0xFF0E4F4D)
private val Magenta = Color(0xFFFF4D8D)
private val MagentaDark = Color(0xFF5C1030)
private val Amber = Color(0xFFFFC24B)

val EddiesDarkColors: ColorScheme = darkColorScheme(
    primary = Cyan,
    onPrimary = Color(0xFF00201F),
    primaryContainer = CyanDark,
    onPrimaryContainer = Color(0xFFB6F5F1),
    secondary = Magenta,
    onSecondary = Color(0xFF3D0019),
    secondaryContainer = MagentaDark,
    onSecondaryContainer = Color(0xFFFFD9E3),
    tertiary = Amber,
    onTertiary = Color(0xFF3D2A00),
    background = Color(0xFF0B0E11),
    onBackground = Color(0xFFE3E6E8),
    surface = Color(0xFF0B0E11),
    onSurface = Color(0xFFE3E6E8),
    surfaceVariant = Color(0xFF23282D),
    onSurfaceVariant = Color(0xFFA9B2B9),
    surfaceContainerLowest = Color(0xFF07090B),
    surfaceContainerLow = Color(0xFF11151A),
    surfaceContainer = Color(0xFF161B21),
    surfaceContainerHigh = Color(0xFF1D242B),
    surfaceContainerHighest = Color(0xFF262E36),
    outline = Color(0xFF6B747C),
    outlineVariant = Color(0xFF3A424A),
    error = Color(0xFFFF6B6B),
    onError = Color(0xFF3D0000),
)

val EddiesLightColors: ColorScheme = lightColorScheme(
    primary = Color(0xFF006966),
    onPrimary = Color.White,
    primaryContainer = Color(0xFF9CF1EC),
    onPrimaryContainer = Color(0xFF00201F),
    secondary = Color(0xFFA6215A),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFD9E3),
    onSecondaryContainer = Color(0xFF3D0019),
    tertiary = Color(0xFF7A5900),
    onTertiary = Color.White,
    background = Color(0xFFF7FAFA),
    onBackground = Color(0xFF171D1D),
    surface = Color(0xFFF7FAFA),
    onSurface = Color(0xFF171D1D),
    surfaceVariant = Color(0xFFDAE5E4),
    onSurfaceVariant = Color(0xFF3F4948),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF1F4F4),
    surfaceContainer = Color(0xFFEBEFEF),
    surfaceContainerHigh = Color(0xFFE5E9E9),
    surfaceContainerHighest = Color(0xFFDFE3E3),
    outline = Color(0xFF6F7978),
    outlineVariant = Color(0xFFBEC9C8),
)

/**
 * Gain and loss colours, deliberately outside the ColorScheme.
 *
 * Two reasons. They must survive Material You (a wallpaper-tinted "green" that
 * reads amber is a portfolio that lies), and green-versus-red alone fails for
 * roughly one man in twelve. Everywhere these are used the sign and a direction
 * arrow carry the same information, so hue is never the only channel.
 */
data class PnlColors(val gain: Color, val loss: Color, val flat: Color)

val DarkPnlColors = PnlColors(
    gain = Color(0xFF3DDC97),
    loss = Color(0xFFFF5C7A),
    flat = Color(0xFF8A939B),
)

val LightPnlColors = PnlColors(
    gain = Color(0xFF00875A),
    loss = Color(0xFFC9184A),
    flat = Color(0xFF6F7978),
)

/**
 * OLED is a transform of whatever scheme is active, not a second palette, so it
 * composes with Material You.
 *
 * The containers are deliberately left off pure black. On an OLED panel a black
 * pixel is an off pixel with no edge at all, so blackening the containers too
 * would dissolve every card and sheet into the background.
 */
fun ColorScheme.asOled(): ColorScheme = copy(
    background = Color.Black,
    surface = Color.Black,
    surfaceContainerLowest = Color.Black,
    surfaceContainerLow = Color(0xFF0A0A0A),
    surfaceContainer = Color(0xFF111111),
    surfaceContainerHigh = Color(0xFF1A1A1A),
    surfaceContainerHighest = Color(0xFF232323),
)
