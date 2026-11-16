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

/** Who can see the numbers, on this device and over your shoulder. */
@Composable
fun PrivacySettingsScreen(
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val cfg = state.settings

    SettingsPage(modifier, state.message) {
        Section(
            title = stringResource(R.string.settings_lock),
            subtitle = stringResource(R.string.settings_lock_hint),
        ) {
            SettingSwitch(
                title = stringResource(R.string.settings_require_unlock),
                subtitle = stringResource(R.string.settings_require_unlock_hint),
                checked = cfg.appLockEnabled,
                onCheckedChange = viewModel::setAppLock,
            )
            HorizontalDivider()
            InfoRow(stringResource(R.string.settings_relocks), stringResource(R.string.settings_relocks_value))
        }

        Section(stringResource(R.string.settings_on_screen)) {
            SettingSwitch(
                title = stringResource(R.string.settings_hide_balances),
                subtitle = stringResource(R.string.settings_hide_balances_hint),
                checked = cfg.hideBalances,
                onCheckedChange = viewModel::setHideBalances,
            )
            HorizontalDivider()
            SettingSwitch(
                title = stringResource(R.string.settings_hide_recents),
                subtitle = stringResource(R.string.settings_hide_recents_hint),
                checked = cfg.hideInRecents,
                onCheckedChange = viewModel::setHideInRecents,
            )
        }
    }
}
