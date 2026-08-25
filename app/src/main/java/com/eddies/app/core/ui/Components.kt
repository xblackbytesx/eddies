package com.eddies.app.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.TrendingDown
import androidx.compose.material.icons.automirrored.filled.TrendingFlat
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.eddies.app.core.design.LocalPnlColors
import com.eddies.app.domain.Asset
import java.math.BigDecimal

/**
 * A coin icon.
 *
 * Resolves in three steps: a bundled asset (privacy-safe, offline, the default),
 * then a remote URL only when the user has switched remote icons on, then a
 * generated monogram tile.
 *
 * The monogram is not a placeholder to be embarrassed about: roughly a quarter
 * of the seeded coins have no artwork in either bundled set, so it is the normal
 * appearance for the long tail and is designed to look deliberate.
 */
@Composable
fun AssetIcon(
    asset: Asset?,
    iconUri: String?,
    modifier: Modifier = Modifier,
    size: androidx.compose.ui.unit.Dp = 40.dp,
    remoteUrl: String? = null,
) {
    val symbol = asset?.symbol.orEmpty()
    val model = iconUri ?: remoteUrl

    Box(modifier = modifier.size(size), contentAlignment = Alignment.Center) {
        if (model != null) {
            AsyncImage(
                model = model,
                contentDescription = asset?.name,
                modifier = Modifier.size(size),
                // A coin with no artwork is the normal case for the long tail,
                // so the monogram is the fallback rather than a broken-image box.
                error = null,
            )
        } else {
            MonogramTile(symbol, size)
        }
    }
}

/** Ticker letters on a colour derived from the symbol, so it is stable per coin. */
@Composable
fun MonogramTile(symbol: String, size: androidx.compose.ui.unit.Dp) {
    val text = symbol.take(if (symbol.length <= 3) symbol.length else 3).uppercase()
    val hue = (symbol.hashCode().toDouble() % 360.0 + 360.0) % 360.0
    val background = Color.hsl(hue.toFloat(), 0.45f, 0.35f)
    Surface(
        modifier = Modifier.size(size),
        shape = CircleShape,
        color = background,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = text,
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
                fontSize = (size.value / (1.6f + text.length * 0.55f)).sp,
                maxLines = 1,
            )
        }
    }
}

/**
 * A gain or loss, coloured and signed and arrowed.
 *
 * All three channels carry the same information on purpose. Red against green
 * is invisible to roughly one man in twelve, so colour is never the only thing
 * saying which direction a number went.
 */
@Composable
fun PnlText(
    text: String,
    value: BigDecimal?,
    modifier: Modifier = Modifier,
    style: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.bodyMedium,
    showArrow: Boolean = true,
) {
    val colors = LocalPnlColors.current
    val sign = value?.signum() ?: 0
    val color = when {
        sign > 0 -> colors.gain
        sign < 0 -> colors.loss
        else -> colors.flat
    }
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        if (showArrow) {
            Icon(
                imageVector = when {
                    sign > 0 -> Icons.AutoMirrored.Filled.TrendingUp
                    sign < 0 -> Icons.AutoMirrored.Filled.TrendingDown
                    else -> Icons.AutoMirrored.Filled.TrendingFlat
                },
                contentDescription = when {
                    sign > 0 -> "Up"
                    sign < 0 -> "Down"
                    else -> "Unchanged"
                },
                tint = color,
                modifier = Modifier.size(16.dp).padding(end = 2.dp),
            )
        }
        Text(text = text, color = color, style = style, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * A titled card section, the building block of every settings and detail screen.
 * Matches the house pattern: a small primary-coloured header above a rounded
 * surfaceContainer card.
 */
@Composable
fun Section(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    action: (@Composable () -> Unit)? = null,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            action?.invoke()
        }
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(0.dp),
                content = content,
            )
        }
    }
}

/** A staleness marker. A cached price shown as if it were live is worse than none. */
@Composable
fun StaleBadge(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(6.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
    ) {
        Text(
            text = "cached",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

@Composable
fun EmptyHint(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxWidth().padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
