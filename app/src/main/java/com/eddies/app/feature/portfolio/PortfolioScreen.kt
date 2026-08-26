package com.eddies.app.feature.portfolio

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.eddies.app.core.design.ChartRange
import com.eddies.app.core.design.InteractiveLineChart
import com.eddies.app.core.ui.AssetIcon
import com.eddies.app.core.ui.EmptyHint
import com.eddies.app.core.ui.LoadingPlaceholder
import com.eddies.app.core.ui.PnlText
import com.eddies.app.core.ui.StaleBadge
import com.eddies.app.domain.Holding
import com.eddies.app.domain.MoneyFormat
import com.eddies.app.domain.PortfolioScope
import java.math.BigDecimal
import java.time.ZoneId

@Composable
fun PortfolioScreen(
    onOpenAsset: (String) -> Unit,
    onAddPosition: () -> Unit,
    onOpenBackup: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PortfolioViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val zone = remember { ZoneId.systemDefault() }

    LaunchedEffect(state.summary.holdings.size) {
        viewModel.backfillIfEmpty()
        viewModel.markOnboardedIfHolding()
    }

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (!state.loaded) {
            item { LoadingPlaceholder() }
            return@LazyColumn
        }

        if (state.showScopeSelector) {
            item {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                ) {
                    PortfolioScope.entries.forEach { option ->
                        FilterChip(
                            selected = option == state.scope,
                            onClick = { viewModel.setScope(option) },
                            label = { Text(option.label) },
                        )
                    }
                }
            }
        }

        item {
            TotalHeader(
                state = state,
                onToggleHidden = viewModel::toggleHidden,
            )
        }

        if (state.history.size >= 2) {
            item {
                InteractiveLineChart(
                    points = state.history,
                    height = 190.dp,
                    formatValue = { MoneyFormat.compact(BigDecimal.valueOf(it), state.summary.currency) },
                    formatTs = { com.eddies.app.core.design.ChartMath.formatScrubTs(it, zone, state.range) },
                    onScrub = viewModel::onScrub,
                )
            }
            item { RangeSelector(state.range, viewModel::setRange) }
        }

        if (state.isEmpty) {
            item {
                EmptyPortfolio(
                    onboarded = state.onboarded,
                    scope = state.scope,
                    onAdd = onAddPosition,
                    onImport = onOpenBackup,
                )
            }
        } else {
            item {
                Text(
                    text = "Holdings",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            items(state.holdings, key = { it.asset.id }) { holding ->
                HoldingRow(
                    // Rows are ordered by value, so two similar holdings swap
                    // places as prices move. With a stable key and this, the row
                    // glides to its new position instead of snapping there.
                    modifier = Modifier.animateItem(),
                    holding = holding,
                    hidden = state.hidden,
                    advanced = state.advanced,
                    compact = state.compact,
                    iconUri = viewModel.icons.uriFor(holding.asset.iconSlug),
                    onClick = { onOpenAsset(holding.asset.id) },
                )
            }
        }
    }
}

@Composable
private fun TotalHeader(state: PortfolioUiState, onToggleHidden: () -> Unit) {
    val summary = state.summary
    // While scrubbing the chart, the headline shows the scrubbed value: the whole
    // point of the gesture is to read a past total, and leaving today's number up
    // there makes the crosshair meaningless.
    val displayValue = state.scrubbed?.let { BigDecimal.valueOf(it.value) } ?: state.scopedValue

    Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = when (state.scope) {
                    com.eddies.app.domain.PortfolioScope.ALL -> "Total value"
                    com.eddies.app.domain.PortfolioScope.CRYPTO -> "Crypto value"
                    com.eddies.app.domain.PortfolioScope.STOCKS -> "Stocks value"
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (summary.anyStale) {
                Spacer(Modifier.size(8.dp))
                StaleBadge()
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onToggleHidden) {
                Icon(
                    imageVector = if (state.hidden) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                    contentDescription = if (state.hidden) "Show balances" else "Hide balances",
                )
            }
        }
        Text(
            text = MoneyFormat.fiat(displayValue, summary.currency, state.hidden),
            style = MaterialTheme.typography.headlineLarge,
        )
        // The same total in the second currency, when one is set and differs.
        // Only while not scrubbing: two numbers moving under a dragging finger
        // is harder to read than one.
        if (state.scrubbed == null) {
            state.secondaryTotal?.let { secondary ->
                Text(
                    text = MoneyFormat.fiat(secondary, state.secondaryCurrency, state.hidden),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            PnlText(
                text = MoneyFormat.signedFiat(state.scopedPnl, summary.currency, state.hidden),
                value = state.scopedPnl,
                style = MaterialTheme.typography.bodyLarge,
            )
            Spacer(Modifier.size(10.dp))
            PnlText(
                text = MoneyFormat.percent(state.scopedPnlPct, state.hidden),
                value = state.scopedPnl,
                style = MaterialTheme.typography.bodyLarge,
                showArrow = false,
            )
        }
        if (state.advanced) {
            Spacer(Modifier.size(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Stat("Cost", MoneyFormat.fiat(state.scopedCost, summary.currency, state.hidden))
                Stat("Realised", MoneyFormat.signedFiat(state.scopedRealized, summary.currency, state.hidden))
                if (state.scopedIncome.signum() > 0) {
                    // Staking and dividends together: everything earned rather
                    // than bought, whichever class it came from.
                    Stat("Earned", MoneyFormat.fiat(state.scopedIncome, summary.currency, state.hidden))
                }
            }
        }
    }
}

@Composable
private fun Stat(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun RangeSelector(selected: ChartRange, onSelect: (ChartRange) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
        ChartRange.entries.forEach { range ->
            FilterChip(
                selected = range == selected,
                onClick = { onSelect(range) },
                label = { Text(range.label, style = MaterialTheme.typography.labelMedium) },
            )
        }
    }
}

@Composable
private fun HoldingRow(
    holding: Holding,
    modifier: Modifier = Modifier,
    hidden: Boolean,
    advanced: Boolean,
    compact: Boolean,
    iconUri: String?,
    onClick: () -> Unit,
) {
    Surface(
        shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(
                horizontal = 12.dp,
                vertical = if (compact) 8.dp else 12.dp,
            ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AssetIcon(holding.asset, iconUri, size = if (compact) 32.dp else 40.dp)
            Spacer(Modifier.size(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = holding.asset.symbol,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                )
                Text(
                    text = MoneyFormat.quantity(holding.position.quantity, holding.asset.decimals, hidden),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (advanced && holding.position.stakingQuantity.signum() > 0) {
                    Text(
                        text = "incl. ${MoneyFormat.quantity(
                            holding.position.stakingQuantity, holding.asset.decimals, hidden,
                        )} staked",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary,
                        maxLines = 1,
                    )
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = MoneyFormat.fiat(holding.marketValue, holding.currency, hidden),
                    style = MaterialTheme.typography.titleSmall,
                )
                PnlText(
                    text = MoneyFormat.percent(holding.price?.changePct24h, hidden),
                    value = holding.price?.changePct24h?.let { BigDecimal.valueOf(it) },
                    style = MaterialTheme.typography.bodySmall,
                    showArrow = false,
                )
                if (advanced) {
                    PnlText(
                        text = MoneyFormat.signedFiat(holding.unrealizedPnl, holding.currency, hidden),
                        value = holding.unrealizedPnl,
                        style = MaterialTheme.typography.labelSmall,
                        showArrow = false,
                    )
                }
            }
        }
    }
}

/**
 * What an empty portfolio says.
 *
 * Two different empty states, because "never started" and "sold everything" are
 * different situations. Someone who has cleared out does not need to be welcomed
 * again, and someone opening the app for the first time needs more than a plus
 * sign to aim at: typing in years of history by hand is the wrong first
 * impression when a CSV import exists.
 */
@Composable
private fun EmptyPortfolio(
    onboarded: Boolean,
    scope: PortfolioScope,
    onAdd: () -> Unit,
    onImport: () -> Unit,
) {
    // A scope filter can empty a portfolio that is not actually empty. Offering
    // to import a backup there would be nonsense.
    if (scope != PortfolioScope.ALL) {
        EmptyHint("Nothing in ${scope.label.lowercase()} yet.")
        return
    }

    if (onboarded) {
        EmptyHint("Nothing held right now. Past transactions are still in your history.")
        return
    }

    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 32.dp, bottom = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Nothing here yet", style = MaterialTheme.typography.titleMedium)
        Text(
            "Track crypto and shares together. Everything stays on this phone.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        Spacer(Modifier.size(8.dp))
        Button(onClick = onAdd, modifier = Modifier.fillMaxWidth()) {
            Text("Add your first position")
        }
        OutlinedButton(onClick = onImport, modifier = Modifier.fillMaxWidth()) {
            Text("Import a CSV or restore a backup")
        }
    }
}
