package com.eddies.app.feature.settings

import com.eddies.app.core.ui.labelRes
import com.eddies.app.R
import androidx.compose.ui.res.stringResource
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
        Section(stringResource(R.string.settings_appearance)) {
            ChoiceRow(
                title = stringResource(R.string.settings_theme),
                options = ThemeMode.entries,
                selected = cfg.themeMode,
                label = { stringResource(it.labelRes) },
                onSelect = viewModel::setThemeMode,
            )
            HorizontalDivider()
            SettingSwitch(
                title = stringResource(R.string.settings_dynamic_color),
                subtitle = stringResource(R.string.settings_dynamic_color_hint),
                checked = cfg.dynamicColor,
                onCheckedChange = viewModel::setDynamicColor,
            )
            HorizontalDivider()
            SettingSwitch(
                title = stringResource(R.string.settings_compact),
                subtitle = stringResource(R.string.settings_compact_hint),
                checked = cfg.compactRows,
                onCheckedChange = viewModel::setCompactRows,
            )
            HorizontalDivider()
            SettingSwitch(
                title = stringResource(R.string.settings_hide_nav),
                subtitle = stringResource(R.string.settings_hide_nav_hint),
                checked = cfg.hideNavOnScroll,
                onCheckedChange = viewModel::setHideNavOnScroll,
            )
        }

        Section(stringResource(R.string.settings_currency)) {
            ChoiceRow(
                title = stringResource(R.string.settings_main_currency),
                subtitle = stringResource(R.string.settings_main_currency_hint),
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
                title = stringResource(R.string.settings_second_currency),
                subtitle = stringResource(R.string.settings_second_currency_hint),
                options = state.secondaryCurrencies,
                selected = cfg.secondaryCurrency,
                label = { if (it.isEmpty()) stringResource(R.string.off) else it },
                onSelect = viewModel::setSecondaryCurrency,
            )
        }

        Section(stringResource(R.string.settings_detail)) {
            SettingSwitch(
                title = stringResource(R.string.settings_advanced),
                subtitle = stringResource(R.string.settings_advanced_hint),
                checked = cfg.advancedMode,
                onCheckedChange = viewModel::setAdvancedMode,
            )
            HorizontalDivider()
            // Shared by both asset classes, so it belongs here rather than in
            // either market screen.
            ChoiceRow(
                title = stringResource(R.string.settings_poll),
                subtitle = stringResource(R.string.settings_poll_hint),
                options = listOf(15, 30, 60, 120, 300),
                selected = cfg.pollIntervalSeconds,
                label = { if (it < 60) "${it}s" else "${it / 60}m" },
                onSelect = viewModel::setPollInterval,
            )
        }
    }
}
