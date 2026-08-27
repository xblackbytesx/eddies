package com.eddies.app.feature.settings

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
            title = "Where prices come from",
            subtitle = "Set per holding, not here. This is what each one means.",
        ) {
            InfoRow("Shares and ETFs", "Yahoo Finance")
            HorizontalDivider()
            InfoRow("Added by ISIN", "Tradegate")
        }

        Section(
            title = "Official source",
            subtitle = "Optional. Everything works without it.",
        ) {
            ApiKeyEditor(
                title = "Finnhub API key",
                savedNote = "A key is saved and encrypted on this device.",
                unsavedNote = "Shares work without this. Add a Finnhub key only if you want an " +
                    "official source instead of the default, which is unofficial and could change.",
                draft = state.stockKeyDraft,
                hasKey = cfg.hasStockApiKey,
                onDraftChange = viewModel::setStockKeyDraft,
                onSave = viewModel::saveStockKey,
                onClear = viewModel::clearStockKey,
            )
        }
    }
}
