package com.eddies.app.feature.assetdetail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.eddies.app.core.ui.AssetIcon
import com.eddies.app.core.ui.EmptyHint
import com.eddies.app.core.ui.PnlText
import com.eddies.app.core.ui.Section
import com.eddies.app.core.ui.StaleBadge
import com.eddies.app.data.repo.label
import com.eddies.app.domain.MoneyFormat
import com.eddies.app.domain.Transaction
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun AssetDetailScreen(
    onEditTransaction: (Long) -> Unit,
    onAddTransaction: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AssetDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val holding = state.holding

    Column(
        modifier = modifier.fillMaxWidth().verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp).padding(bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        if (holding == null) {
            EmptyHint("No position in this asset yet.")
            return@Column
        }

        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
            AssetIcon(holding.asset, state.iconUri, size = 48.dp)
            Spacer(Modifier.size(12.dp))
            Column(Modifier.weight(1f)) {
                Text(holding.asset.name, style = MaterialTheme.typography.titleLarge)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        holding.price?.let { MoneyFormat.price(it.price, state.currency) } ?: "No price",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (holding.isStale && holding.hasPrice) {
                        Spacer(Modifier.size(6.dp))
                        StaleBadge()
                    }
                }
            }
            holding.price?.changePct24h?.let { pct ->
                PnlText(
                    text = MoneyFormat.percent(pct, state.hidden),
                    value = BigDecimal.valueOf(pct),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }

        Section("Your position") {
            DetailRow("Holding", MoneyFormat.quantity(holding.position.quantity, holding.asset.decimals, state.hidden))
            HorizontalDivider()
            DetailRow("Value", MoneyFormat.fiat(holding.marketValue, state.currency, state.hidden))
            HorizontalDivider()
            DetailRow(
                "Unrealised",
                MoneyFormat.signedFiat(holding.unrealizedPnl, state.currency, state.hidden),
                pnl = holding.unrealizedPnl,
            )
            if (state.advanced) {
                HorizontalDivider()
                DetailRow("Cost basis", MoneyFormat.fiat(holding.position.costBasis, state.currency, state.hidden))
                HorizontalDivider()
                DetailRow("Average cost", MoneyFormat.price(holding.position.averageUnitCost, state.currency, state.hidden))
                HorizontalDivider()
                DetailRow(
                    "Realised",
                    MoneyFormat.signedFiat(holding.position.realizedPnl, state.currency, state.hidden),
                    pnl = holding.position.realizedPnl,
                )
            }
        }

        // Staking is only shown when there is any, so a coin that cannot stake
        // never carries an empty section explaining that it has none.
        if (holding.position.stakingQuantity.signum() > 0) {
            Section(
                title = "Staking",
                subtitle = "Earned on top of what you bought.",
            ) {
                DetailRow(
                    "Rewards earned",
                    MoneyFormat.quantity(holding.position.stakingQuantity, holding.asset.decimals, state.hidden),
                )
                HorizontalDivider()
                DetailRow("Rewards value", MoneyFormat.fiat(holding.stakingValue, state.currency, state.hidden))
                HorizontalDivider()
                DetailRow(
                    "Bought",
                    MoneyFormat.quantity(holding.position.principalQuantity, holding.asset.decimals, state.hidden),
                )
                HorizontalDivider()
                StakingBar(
                    principal = holding.position.principalQuantity.toDouble(),
                    staked = holding.position.stakingQuantity.toDouble(),
                )
            }
        }

        Section(
            title = "Transactions",
            subtitle = "${state.transactions.size} recorded",
        ) {
            if (state.transactions.isEmpty()) {
                EmptyHint("Nothing recorded yet.")
            } else {
                state.transactions.forEachIndexed { i, tx ->
                    if (i > 0) HorizontalDivider()
                    TransactionRow(
                        tx = tx,
                        decimals = holding.asset.decimals,
                        hidden = state.hidden,
                        onClick = { onEditTransaction(tx.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String, pnl: BigDecimal? = null) {
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

/** Shows at a glance how much of the holding was earned rather than bought. */
@Composable
private fun StakingBar(principal: Double, staked: Double) {
    val total = principal + staked
    val fraction = if (total <= 0) 0f else (staked / total).toFloat()
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        LinearProgressIndicator(
            progress = { fraction },
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.tertiary,
        )
        Spacer(Modifier.size(4.dp))
        Text(
            "${MoneyFormat.percent(fraction * 100.0).removePrefix("+")} of this holding came from staking",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private val txDateFormat = DateTimeFormatter.ofPattern("d MMM yyyy")

@Composable
private fun TransactionRow(tx: Transaction, decimals: Int, hidden: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(tx.type.label, style = MaterialTheme.typography.bodyMedium)
            Text(
                txDateFormat.format(Instant.ofEpochMilli(tx.timestamp).atZone(ZoneId.systemDefault())),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(MoneyFormat.quantity(tx.quantity, decimals, hidden), style = MaterialTheme.typography.bodyMedium)
            tx.pricePerUnit?.let {
                Text(
                    "@ ${MoneyFormat.price(it, tx.quoteCurrency, hidden)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
