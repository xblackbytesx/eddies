package com.eddies.app.feature.settings

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
            title = "App lock",
            subtitle = "The database is encrypted either way. This guards an unlocked phone.",
        ) {
            SettingSwitch(
                title = "Require unlock",
                subtitle = "Ask for your fingerprint or PIN when opening the app.",
                checked = cfg.appLockEnabled,
                onCheckedChange = viewModel::setAppLock,
            )
            HorizontalDivider()
            InfoRow("Re-locks", "Whenever you leave the app")
        }

        Section("On screen") {
            SettingSwitch(
                title = "Hide balances",
                subtitle = "Blur every amount until you tap the eye.",
                checked = cfg.hideBalances,
                onCheckedChange = viewModel::setHideBalances,
            )
            HorizontalDivider()
            SettingSwitch(
                title = "Hide from recent apps",
                subtitle = "Keeps balances out of the app switcher thumbnail, and blocks screenshots.",
                checked = cfg.hideInRecents,
                onCheckedChange = viewModel::setHideInRecents,
            )
        }
    }
}
