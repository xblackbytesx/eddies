package com.eddies.app.core.design

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp

/**
 * The system font throughout, never a bundled one, so the user's accessibility
 * font size setting is respected.
 *
 * Named alias rather than a bare Typography() so a brand face could be swapped
 * in later without touching a call site.
 */
val EddiesTypography: Typography = Typography().let { base ->
    base.copy(
        headlineLarge = base.headlineLarge.copy(fontWeight = FontWeight.SemiBold),
        headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.SemiBold),
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.Medium),
        labelLarge = base.labelLarge.copy(fontWeight = FontWeight.Medium),
    )
}

/**
 * Monospace figures for anything in a column that has to line up: balances,
 * prices, quantities. Proportional digits make a list of numbers ragged and
 * genuinely harder to compare down the column.
 */
val NumericStyle: TextStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontFeatureSettings = "tnum",
    textAlign = TextAlign.End,
)
