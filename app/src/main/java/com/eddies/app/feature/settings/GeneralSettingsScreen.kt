package com.eddies.app.feature.settings

import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.eddies.app.core.design.ThemeMode
import com.eddies.app.core.ui.Section

/** Everything that applies to the whole app, whatever it is you hold. */
@Composable
fun GeneralSettingsScreen(
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val cfg = state.settings

    SettingsPage(modifier, state.message) {
        Section("Appearance") {
            ChoiceRow(
                title = "Theme",
                options = ThemeMode.entries,
                selected = cfg.themeMode,
                label = { it.label },
                onSelect = viewModel::setThemeMode,
            )
            HorizontalDivider()
            SettingSwitch(
                title = "Material You colours",
                subtitle = "Off by default: a wallpaper tint fights the gain and loss colours.",
                checked = cfg.dynamicColor,
                onCheckedChange = viewModel::setDynamicColor,
            )
            HorizontalDivider()
            SettingSwitch(
                title = "Compact rows",
                subtitle = "Fit more holdings on screen.",
                checked = cfg.compactRows,
                onCheckedChange = viewModel::setCompactRows,
            )
        }

        Section("Currency") {
            ChoiceRow(
                title = "Main currency",
                subtitle = "Everything is valued and totalled in this.",
                options = state.currencies,
                selected = cfg.baseCurrency,
                label = { it },
                onSelect = viewModel::setBaseCurrency,
            )
            HorizontalDivider()
            // Displayed under the portfolio total since milestone 8, and until
            // now the only way to change it was restoring a backup that carried
            // a different one.
            ChoiceRow(
                title = "Second currency",
                subtitle = "Shown under the portfolio total. Off unless you want it.",
                options = state.secondaryCurrencies,
                selected = cfg.secondaryCurrency,
                label = { if (it.isEmpty()) "Off" else it },
                onSelect = viewModel::setSecondaryCurrency,
            )
        }

        Section("Detail") {
            SettingSwitch(
                title = "Advanced trader mode",
                subtitle = "Adds cost basis, realised P/L, ranks and per-row detail.",
                checked = cfg.advancedMode,
                onCheckedChange = viewModel::setAdvancedMode,
            )
            HorizontalDivider()
            // Shared by both asset classes, so it belongs here rather than in
            // either market screen.
            ChoiceRow(
                title = "Price refresh",
                subtitle = "How often polled prices update. Live feeds push as they happen.",
                options = listOf(15, 30, 60, 120, 300),
                selected = cfg.pollIntervalSeconds,
                label = { if (it < 60) "${it}s" else "${it / 60}m" },
                onSelect = viewModel::setPollInterval,
            )
        }
    }
}
