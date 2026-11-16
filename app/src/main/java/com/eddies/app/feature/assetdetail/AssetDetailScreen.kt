package com.eddies.app.feature.assetdetail

import com.eddies.app.R
import androidx.compose.ui.res.stringResource
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.eddies.app.core.design.ChartMath
import com.eddies.app.core.design.ChartRange
import com.eddies.app.core.design.InteractiveLineChart
import com.eddies.app.core.ui.AssetIcon
import com.eddies.app.core.ui.EmptyHint
import com.eddies.app.core.ui.PnlText
import com.eddies.app.core.ui.Section
import com.eddies.app.core.ui.StaleBadge
import com.eddies.app.core.ui.labelRes
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
    val zone = remember { ZoneId.systemDefault() }
    var showCustodyDialog by remember { mutableStateOf(false) }

    Column(
        modifier = modifier.fillMaxWidth().verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp).padding(bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        if (holding == null) {
            // A watched coin has no position, and Markets links straight here,
            // so this cannot be a dead end.
            EmptyHint(stringResource(R.string.asset_no_position))
            return@Column
        }

        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
            AssetIcon(holding.asset, state.iconUri, size = 48.dp)
            Spacer(Modifier.size(12.dp))
            Column(Modifier.weight(1f)) {
                Text(holding.asset.name, style = MaterialTheme.typography.titleLarge)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        state.scrubbed?.let { MoneyFormat.price(BigDecimal.valueOf(it.value), state.currency) }
                            ?: holding.price?.let { MoneyFormat.price(it.price, state.currency) }
                            ?: stringResource(R.string.asset_no_price),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (holding.isStale && holding.hasPrice) {
                        Spacer(Modifier.size(6.dp))
                        StaleBadge()
                    }
                }
            }
            IconButton(onClick = viewModel::toggleWatch) {
                Icon(
                    imageVector = if (state.watched) Icons.Default.Star else Icons.Default.StarBorder,
                    contentDescription = stringResource(if (state.watched) R.string.asset_unwatch else R.string.asset_watch),
                    tint = if (state.watched) MaterialTheme.colorScheme.tertiary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // Over the charted window when there is a chart, otherwise 24h.
            val shownPct = state.rangeChangePct ?: holding.price?.changePct24h
            shownPct?.let { pct ->
                Column(horizontalAlignment = Alignment.End) {
                    PnlText(
                        text = MoneyFormat.percent(pct, state.hidden),
                        value = BigDecimal.valueOf(pct),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        if (state.rangeChangePct != null) stringResource(state.range.labelRes) else stringResource(R.string.asset_24h),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // The chart, drawn from cached candles. A coin with no history yet
        // simply has no chart rather than an empty frame promising one.
        if (state.history.size >= 2) {
            InteractiveLineChart(
                points = state.history,
                height = 190.dp,
                formatValue = { MoneyFormat.price(BigDecimal.valueOf(it), state.currency) },
                formatTs = { ChartMath.formatScrubTs(it, zone, state.range) },
                onScrub = viewModel::onScrub,
            )
            // A Tradegate holding is priced live by Tradegate, but Tradegate
            // publishes no history, so the chart is the same instrument on
            // whichever venue Yahoo maps the ISIN to. Close, but not the same
            // prints, and the last point can differ from the price above.
            if (state.pricedByTradegate) {
                Text(
                    stringResource(R.string.asset_tradegate_chart, holding.asset.symbol),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                ChartRange.entries.forEach { r ->
                    FilterChip(
                        selected = r == state.range,
                        onClick = { viewModel.setRange(r) },
                        label = { Text(stringResource(r.labelRes), style = MaterialTheme.typography.labelMedium) },
                    )
                }
            }
        } else if (state.loadingHistory) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }

        Section(stringResource(R.string.asset_position)) {
            DetailRow(stringResource(R.string.asset_holding), MoneyFormat.quantity(holding.position.quantity, holding.asset.decimals, state.hidden))
            HorizontalDivider()
            DetailRow(stringResource(R.string.asset_value), MoneyFormat.fiat(holding.marketValue, state.currency, state.hidden))
            HorizontalDivider()
            DetailRow(
                stringResource(R.string.asset_unrealised),
                MoneyFormat.signedFiat(holding.unrealizedPnl, state.currency, state.hidden),
                pnl = holding.unrealizedPnl,
            )
            if (state.advanced) {
                HorizontalDivider()
                DetailRow(stringResource(R.string.settings_cost_basis), MoneyFormat.fiat(holding.position.costBasis, state.currency, state.hidden))
                HorizontalDivider()
                DetailRow(stringResource(R.string.asset_average_cost), MoneyFormat.price(holding.position.averageUnitCost, state.currency, state.hidden))
                HorizontalDivider()
                DetailRow(
                    stringResource(R.string.portfolio_realised),
                    MoneyFormat.signedFiat(holding.position.realizedPnl, state.currency, state.hidden),
                    pnl = holding.position.realizedPnl,
                )
            }
        }

        // Where it is kept. Always shown, including when unset, because an
        // empty row is the prompt to fill it in; a row that only appears once
        // filled is a feature nobody discovers.
        Section(
            title = stringResource(R.string.custody_stored_at),
            subtitle = state.custody?.note,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth()
                    .clickable {
                        viewModel.refreshCustodySuggestions()
                        showCustodyDialog = true
                    }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val custody = state.custody
                if (custody != null) {
                    Icon(
                        custody.type.icon(),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.size(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(custody.label, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            stringResource(custody.type.labelRes),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(stringResource(R.string.asset_change), style = MaterialTheme.typography.labelMedium)
                } else {
                    Text(
                        stringResource(R.string.asset_custody_none),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    Text(stringResource(R.string.asset_set), style = MaterialTheme.typography.labelMedium)
                }
            }
        }

        // Staking is only shown when there is any, so a coin that cannot stake
        // never carries an empty section explaining that it has none.
        if (holding.stakingQuantityTotal.signum() > 0) {
            Section(
                title = stringResource(R.string.asset_staking),
                subtitle = stringResource(R.string.asset_staking_hint),
            ) {
                if (holding.hasPendingStaking) {
                    DetailRow(
                        stringResource(R.string.asset_accruing),
                        MoneyFormat.quantity(holding.stakingPending, holding.asset.decimals, state.hidden),
                    )
                    HorizontalDivider()
                }
                if (holding.position.stakingQuantity.signum() > 0) {
                    DetailRow(
                        stringResource(R.string.asset_recorded_rewards),
                        MoneyFormat.quantity(
                            holding.position.stakingQuantity, holding.asset.decimals, state.hidden,
                        ),
                    )
                    HorizontalDivider()
                }
                DetailRow(stringResource(R.string.asset_worth_today), MoneyFormat.fiat(holding.stakingValue, state.currency, state.hidden))
                HorizontalDivider()
                DetailRow(
                    stringResource(R.string.asset_bought),
                    MoneyFormat.quantity(holding.position.quantity, holding.asset.decimals, state.hidden),
                )
                HorizontalDivider()
                StakingBar(
                    principal = holding.position.quantity.toDouble(),
                    staked = holding.stakingQuantityTotal.toDouble(),
                )
                state.stakingStatus?.let { status ->
                    HorizontalDivider()
                    Text(
                        status,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    )
                }
            }
        }

        Section(
            title = stringResource(R.string.asset_transactions),
            subtitle = stringResource(R.string.asset_tx_count, state.transactions.size),
            // Adding another trade in something already held should not mean
            // going back to search and finding it again.
            action = {
                TextButton(onClick = { onAddTransaction(holding.asset.id) }) {
                    Icon(
                        Icons.Default.Add,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.size(4.dp))
                    Text(stringResource(R.string.action_add))
                }
            },
        ) {
            if (state.transactions.isEmpty()) {
                EmptyHint(stringResource(R.string.tx_none))
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

    if (showCustodyDialog) {
        CustodyDialog(
            initialType = state.custody?.type,
            initialLabel = state.custody?.label.orEmpty(),
            initialNote = state.custody?.note.orEmpty(),
            suggestions = state.custodySuggestions,
            onDismiss = { showCustodyDialog = false },
            onSave = { type, label, note ->
                viewModel.setCustody(type, label, note)
                showCustodyDialog = false
            },
            onClear = {
                viewModel.clearCustody()
                showCustodyDialog = false
            },
        )
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
            stringResource(R.string.asset_staking_share, MoneyFormat.percent(fraction * 100.0).removePrefix("+")),
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
            Text(stringResource(tx.type.labelRes), style = MaterialTheme.typography.bodyMedium)
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
