package com.eddies.app.feature.settings

import com.eddies.app.R
import androidx.compose.ui.res.stringResource
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
            title = stringResource(R.string.settings_live),
            subtitle = stringResource(R.string.settings_live_hint),
        ) {
            ChoiceRow(
                title = stringResource(R.string.settings_feed),
                subtitle = stringResource(R.string.settings_feed_hint),
                options = RealtimeFeed.entries,
                selected = cfg.realtimeFeed,
                label = { if (it == RealtimeFeed.OFF) stringResource(R.string.off) else it.label },
                onSelect = viewModel::setRealtimeFeed,
            )
        }

        Section(
            title = stringResource(R.string.settings_rest),
            subtitle = stringResource(R.string.settings_rest_hint),
        ) {
            ChoiceRow(
                title = stringResource(R.string.settings_source),
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
                    title = stringResource(R.string.settings_gecko_key),
                    savedNote = stringResource(R.string.settings_key_saved),
                    unsavedNote = stringResource(R.string.settings_gecko_key_hint),
                    draft = state.geckoKeyDraft,
                    hasKey = cfg.hasCoinGeckoKey,
                    onDraftChange = viewModel::setGeckoDraft,
                    onSave = viewModel::saveGeckoKey,
                    onClear = viewModel::clearGeckoKey,
                )
            }
        }

        Section(stringResource(R.string.settings_icons)) {
            SettingSwitch(
                title = stringResource(R.string.settings_remote_icons),
                subtitle = stringResource(R.string.settings_remote_icons_hint),
                checked = cfg.remoteIcons,
                onCheckedChange = viewModel::setRemoteIcons,
            )
        }
    }
}
