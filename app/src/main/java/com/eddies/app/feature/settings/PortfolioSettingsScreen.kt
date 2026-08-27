package com.eddies.app.feature.settings

import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.eddies.app.core.ui.Section
import com.eddies.app.domain.CostBasisMethod

/** How positions are calculated, for both asset classes alike. */
@Composable
fun PortfolioSettingsScreen(
    onOpenAccounts: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val cfg = state.settings

    SettingsPage(modifier, state.message) {
        Section(
            title = "Cost basis",
            subtitle = "Changing this recalculates every realised profit you have.",
        ) {
            ChoiceRow(
                title = "Method",
                subtitle = "How a sale decides which purchase it is selling.",
                options = CostBasisMethod.entries,
                selected = cfg.costBasisMethod,
                label = { it.name.lowercase().replaceFirstChar(Char::uppercase) },
                onSelect = viewModel::setCostBasisMethod,
            )
            HorizontalDivider()
            SettingSwitch(
                title = "Count fees in cost basis",
                subtitle = "Fees raise what a buy cost you and lower what a sale returned.",
                checked = cfg.includeFeesInBasis,
                onCheckedChange = viewModel::setIncludeFees,
            )
        }

        Section("Grouping") {
            NavRow(
                title = "Accounts and wallets",
                subtitle = "Group positions, and add a staking address.",
                onClick = onOpenAccounts,
            )
        }
    }
}
