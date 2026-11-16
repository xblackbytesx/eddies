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
            title = stringResource(R.string.settings_cost_basis),
            subtitle = stringResource(R.string.settings_cost_basis_hint),
        ) {
            ChoiceRow(
                title = stringResource(R.string.settings_method),
                subtitle = stringResource(R.string.settings_method_hint),
                options = CostBasisMethod.entries,
                selected = cfg.costBasisMethod,
                label = { stringResource(it.labelRes) },
                onSelect = viewModel::setCostBasisMethod,
            )
            HorizontalDivider()
            SettingSwitch(
                title = stringResource(R.string.settings_fees),
                subtitle = stringResource(R.string.settings_fees_hint),
                checked = cfg.includeFeesInBasis,
                onCheckedChange = viewModel::setIncludeFees,
            )
        }

        Section(stringResource(R.string.settings_grouping)) {
            NavRow(
                title = stringResource(R.string.settings_accounts),
                subtitle = stringResource(R.string.settings_accounts_hint),
                onClick = onOpenAccounts,
            )
        }
    }
}
