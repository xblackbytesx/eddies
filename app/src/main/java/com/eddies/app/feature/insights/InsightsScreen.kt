package com.eddies.app.feature.insights

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.eddies.app.core.design.AllocationDonut
import com.eddies.app.core.ui.AssetIcon
import com.eddies.app.core.ui.EmptyHint
import com.eddies.app.core.ui.PnlText
import com.eddies.app.core.ui.Section
import com.eddies.app.domain.MoneyFormat
import java.math.BigDecimal

/**
 * A categorical palette that stays distinguishable in both themes and does not
 * collide with the gain and loss colours, which mean something specific here.
 */
private val AllocationPalette = listOf(
    Color(0xFF3DD6D0), Color(0xFFFF4D8D), Color(0xFFFFC24B), Color(0xFF7C7CFF),
    Color(0xFF4DD07A), Color(0xFFFF8A5C), Color(0xFF5AB0FF), Color(0xFFC77DFF),
)

@Composable
fun InsightsScreen(
    modifier: Modifier = Modifier,
    viewModel: InsightsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val currency = state.summary.currency

    if (state.summary.holdings.isEmpty()) {
        EmptyHint("Add a position to see how your portfolio breaks down.")
        return
    }

    Column(
        modifier = modifier.fillMaxWidth().verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp).padding(bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Spacer(Modifier.size(4.dp))

        Section("Allocation") {
            Box(
                modifier = Modifier.fillMaxWidth().height(200.dp).padding(16.dp),
                contentAlignment = Alignment.Center,
            ) {
                AllocationDonut(
                    slices = state.allocation.map { it.first.asset.symbol to it.second.toFloat() },
                    colors = AllocationPalette,
                    modifier = Modifier.fillMaxWidth().height(180.dp),
                )
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        MoneyFormat.compact(state.summary.totalValue, currency),
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Text(
                        "${state.allocation.size} assets",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            state.allocation.forEachIndexed { i, (holding, fraction) ->
                HorizontalDivider()
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier.size(10.dp).then(
                            Modifier.background(
                                AllocationPalette[i % AllocationPalette.size],
                                androidx.compose.foundation.shape.CircleShape,
                            ),
                        ),
                    )
                    Spacer(Modifier.size(10.dp))
                    Text(holding.asset.symbol, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Text(
                        MoneyFormat.percent(fraction * 100.0).removePrefix("+"),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.size(12.dp))
                    Text(
                        MoneyFormat.fiat(holding.marketValue, currency, state.hidden),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }

        Section("Profit and loss") {
            SummaryRow("Unrealised", MoneyFormat.signedFiat(state.summary.totalUnrealizedPnl, currency, state.hidden), state.summary.totalUnrealizedPnl)
            HorizontalDivider()
            SummaryRow("Realised", MoneyFormat.signedFiat(state.summary.totalRealizedPnl, currency, state.hidden), state.summary.totalRealizedPnl)
            HorizontalDivider()
            SummaryRow(
                "Total",
                MoneyFormat.signedFiat(
                    state.summary.totalUnrealizedPnl + state.summary.totalRealizedPnl, currency, state.hidden,
                ),
                state.summary.totalUnrealizedPnl + state.summary.totalRealizedPnl,
            )
            if (state.summary.totalStakingValue.signum() > 0) {
                HorizontalDivider()
                SummaryRow(
                    "Of which staking rewards",
                    MoneyFormat.fiat(state.summary.totalStakingValue, currency, state.hidden),
                    null,
                )
            }
        }

        if (state.movers.size >= 2) {
            Section("Movers", subtitle = "Last 24 hours") {
                state.movers.take(3).forEachIndexed { i, holding ->
                    if (i > 0) HorizontalDivider()
                    MoverRow(holding, viewModel.icons.uriFor(holding.asset.iconSlug), state.hidden)
                }
                if (state.movers.size > 3) {
                    HorizontalDivider()
                    state.movers.takeLast(2).forEach { holding ->
                        MoverRow(holding, viewModel.icons.uriFor(holding.asset.iconSlug), state.hidden)
                    }
                }
            }
        }
    }
}

@Composable
private fun SummaryRow(label: String, value: String, pnl: BigDecimal?) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        if (pnl != null) {
            PnlText(text = value, value = pnl, style = MaterialTheme.typography.bodyMedium, showArrow = false)
        } else {
            Text(value, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun MoverRow(holding: com.eddies.app.domain.Holding, iconUri: String?, hidden: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AssetIcon(holding.asset, iconUri, size = 28.dp)
        Spacer(Modifier.size(10.dp))
        Text(holding.asset.symbol, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        PnlText(
            text = MoneyFormat.percent(holding.price?.changePct24h, hidden),
            value = holding.price?.changePct24h?.let { BigDecimal.valueOf(it) },
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}
