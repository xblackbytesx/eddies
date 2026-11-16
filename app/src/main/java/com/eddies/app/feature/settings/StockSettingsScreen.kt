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

/**
 * Price sources and options that only affect shares and ETFs.
 *
 * One control and two explanations. The explanations are the point: shares are
 * priced through an unofficial endpoint by default and through Tradegate when a
 * holding was added by ISIN, and someone wondering why a price looks the way it
 * does has nowhere else to find that out.
 */
@Composable
fun StockSettingsScreen(
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val cfg = state.settings

    SettingsPage(modifier, state.message) {
        Section(
            title = stringResource(R.string.settings_stock_sources),
            subtitle = stringResource(R.string.settings_stock_sources_hint),
        ) {
            InfoRow(stringResource(R.string.settings_shares_etfs), "Yahoo Finance")
            HorizontalDivider()
            InfoRow(stringResource(R.string.settings_by_isin), "Tradegate")
        }

        Section(
            title = stringResource(R.string.settings_official),
            subtitle = stringResource(R.string.settings_official_hint),
        ) {
            ApiKeyEditor(
                title = stringResource(R.string.settings_finnhub_key),
                savedNote = stringResource(R.string.settings_key_saved),
                unsavedNote = stringResource(R.string.settings_finnhub_key_hint),
                draft = state.stockKeyDraft,
                hasKey = cfg.hasStockApiKey,
                onDraftChange = viewModel::setStockKeyDraft,
                onSave = viewModel::saveStockKey,
                onClear = viewModel::clearStockKey,
            )
        }
    }
}
