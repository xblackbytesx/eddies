package com.eddies.app.feature.markets

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import com.eddies.app.domain.Asset
import com.eddies.app.domain.MoneyFormat
import com.eddies.app.domain.PriceTick
import java.math.BigDecimal

@Composable
fun MarketsScreen(
    onOpenAsset: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: MarketsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 8.dp),
        ) {
            SearchScope.entries.forEach { scope ->
                FilterChip(
                    selected = scope == state.searchScope,
                    onClick = { viewModel.setSearchScope(scope) },
                    label = { Text(scope.label) },
                )
            }
        }

        OutlinedTextField(
            value = state.query,
            onValueChange = viewModel::setQuery,
            placeholder = {
                Text(
                    when (state.searchScope) {
                        SearchScope.COINS -> "Search coins"
                        SearchScope.STOCKS -> "Search shares, ETFs, funds"
                    },
                )
            },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        )
        if (state.searching) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        if (state.results.isEmpty()) {
            EmptyHint(
                when {
                    state.searchScope == SearchScope.STOCKS && state.query.isBlank() ->
                        "Search for a share, ETF or fund. Amsterdam and New York listings are separate."
                    state.searchScope == SearchScope.STOCKS ->
                        "Nothing found. Try the ticker, for example ASML.AS for Amsterdam."
                    state.query.isBlank() -> "Search for a coin to see its price."
                    else -> "Nothing found offline. Enable remote lookup in Settings to search the full list."
                },
            )
        } else {
            LazyColumn(
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(state.results, key = { it.id }) { asset ->
                    MarketRow(
                        asset = asset,
                        price = state.prices[asset.id],
                        currency = state.currency,
                        advanced = state.advanced,
                        iconUri = viewModel.icons.uriFor(asset.iconSlug),
                        onClick = { onOpenAsset(asset.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun MarketRow(
    asset: Asset,
    price: PriceTick?,
    currency: String,
    advanced: Boolean,
    iconUri: String?,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AssetIcon(asset, iconUri, size = 36.dp)
        Spacer(Modifier.size(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(asset.symbol, style = MaterialTheme.typography.titleSmall, maxLines = 1)
            Text(
                asset.name,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        if (asset.assetClass == com.eddies.app.domain.AssetClass.STOCK) {
            Text(
                asset.id.substringAfter("stock:").substringBefore(":").replace('_', ' '),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 10.dp),
            )
        } else if (advanced && asset.rank != null) {
            Text(
                "#${asset.rank}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 10.dp),
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = price?.let { MoneyFormat.price(it.price, currency) } ?: "–",
                style = MaterialTheme.typography.bodyMedium,
            )
            price?.changePct24h?.let { pct ->
                PnlText(
                    text = MoneyFormat.percent(pct),
                    value = BigDecimal.valueOf(pct),
                    style = MaterialTheme.typography.labelSmall,
                    showArrow = false,
                )
            }
        }
    }
}
