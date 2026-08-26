package com.eddies.app.feature.transactions

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.eddies.app.core.ui.AssetIcon
import com.eddies.app.core.ui.EmptyHint
import com.eddies.app.core.ui.LoadingPlaceholder
import com.eddies.app.data.repo.UserSelectableTxTypes
import com.eddies.app.data.repo.label
import com.eddies.app.domain.Asset
import com.eddies.app.domain.MoneyFormat
import com.eddies.app.domain.Transaction
import com.eddies.app.domain.TxType
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TransactionsScreen(
    onEdit: (String, Long) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: TransactionsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(modifier = modifier.fillMaxWidth()) {
        // Only worth offering once there is enough history to sift through.
        if (state.total > 8) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                FilterChip(
                    selected = state.filter == null,
                    onClick = { viewModel.setFilter(null) },
                    label = { Text("All") },
                )
                UserSelectableTxTypes.forEach { type ->
                    FilterChip(
                        selected = state.filter == type,
                        onClick = { viewModel.setFilter(type) },
                        label = { Text(type.label) },
                    )
                }
            }
        }

        if (!state.loaded) {
            LoadingPlaceholder()
            return@Column
        }

        if (state.isEmpty) {
            EmptyHint(
                if (state.filter == null) "Nothing recorded yet."
                else "No ${state.filter?.label?.lowercase()} transactions.",
            )
            return@Column
        }

        LazyColumn(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            state.months.forEach { month ->
                item(key = "header-${month.label}") {
                    Text(
                        text = month.label,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                    )
                }
                items(month.transactions, key = { it.id }) { tx ->
                    TransactionRow(
                        tx = tx,
                        asset = state.assets[tx.assetId],
                        iconUri = viewModel.icons.uriFor(state.assets[tx.assetId]?.iconSlug),
                        hidden = state.hidden,
                        onClick = { onEdit(tx.assetId, tx.id) },
                    )
                }
            }
        }
    }
}

private val dayFormat = DateTimeFormatter.ofPattern("d MMM")

@Composable
private fun TransactionRow(
    tx: Transaction,
    asset: Asset?,
    iconUri: String?,
    hidden: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AssetIcon(asset, iconUri, size = 32.dp)
        Spacer(Modifier.size(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "${asset?.symbol ?: tx.assetId.substringAfterLast(':')} ${tx.type.label.lowercase()}",
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = buildString {
                    append(dayFormat.format(Instant.ofEpochMilli(tx.timestamp).atZone(ZoneId.systemDefault())))
                    tx.note?.takeIf { it.isNotBlank() }?.let { append(" · ").append(it) }
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            // A dividend is cash and moves no quantity, so showing a quantity of
            // zero for it would read as a broken row.
            if (tx.type == TxType.DIVIDEND) {
                Text(
                    MoneyFormat.fiat(tx.cashAmount ?: java.math.BigDecimal.ZERO, tx.quoteCurrency, hidden),
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                Text(
                    MoneyFormat.quantity(tx.quantity, asset?.decimals ?: 8, hidden),
                    style = MaterialTheme.typography.bodyMedium,
                )
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
}
