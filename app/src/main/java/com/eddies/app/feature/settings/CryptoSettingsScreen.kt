package com.eddies.app.feature.settings

import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.eddies.app.core.ui.Section
import com.eddies.app.data.prefs.Aggregator
import com.eddies.app.data.prefs.RealtimeFeed

/** Price sources and options that only affect coins. */
@Composable
fun CryptoSettingsScreen(
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val cfg = state.settings

    SettingsPage(modifier, state.message) {
        Section(
            title = "Live prices",
            subtitle = "A push feed, so coins tick as trades happen rather than on a timer.",
        ) {
            ChoiceRow(
                title = "Feed",
                subtitle = "Kraken quotes EUR pairs directly. Binance covers more coins but prices in USDT.",
                options = RealtimeFeed.entries,
                selected = cfg.realtimeFeed,
                label = { it.label },
                onSelect = viewModel::setRealtimeFeed,
            )
        }

        Section(
            title = "Everything else",
            subtitle = "Used for coins the live feed does not list, and for chart history.",
        ) {
            ChoiceRow(
                title = "Source",
                options = Aggregator.entries,
                selected = cfg.aggregator,
                label = { it.label },
                onSelect = viewModel::setAggregator,
            )
            // Only CoinGecko takes a key, so showing the field for CoinPaprika
            // would suggest one is missing when nothing is.
            if (cfg.aggregator == Aggregator.COINGECKO) {
                HorizontalDivider()
                ApiKeyEditor(
                    title = "CoinGecko API key",
                    savedNote = "A key is saved and encrypted on this device.",
                    unsavedNote = "CoinGecko needs your own key. It is stored encrypted and never leaves the phone.",
                    draft = state.geckoKeyDraft,
                    hasKey = cfg.hasCoinGeckoKey,
                    onDraftChange = viewModel::setGeckoDraft,
                    onSave = viewModel::saveGeckoKey,
                    onClear = viewModel::clearGeckoKey,
                )
            }
        }

        Section("Icons") {
            SettingSwitch(
                title = "Fetch missing coin icons",
                subtitle = "Off by default. Turning this on tells the image host which coins you hold.",
                checked = cfg.remoteIcons,
                onCheckedChange = viewModel::setRemoteIcons,
            )
        }
    }
}
