package com.eddies.app.feature.settings

import com.eddies.app.R
import androidx.compose.ui.res.stringResource
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.eddies.app.core.ui.Section

/** Getting data in, out, and gone. */
@Composable
fun DataSettingsScreen(
    onOpenTransactions: () -> Unit,
    onOpenBackup: () -> Unit,
    onOpenMergeDuplicates: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var confirmErase by remember { mutableStateOf(false) }

    SettingsPage(modifier, state.message) {
        Section(stringResource(R.string.settings_ledger)) {
            NavRow(
                title = stringResource(R.string.settings_history),
                subtitle = stringResource(R.string.settings_history_hint),
                onClick = onOpenTransactions,
            )
            HorizontalDivider()
            NavRow(
                title = stringResource(R.string.settings_backup),
                subtitle = stringResource(R.string.settings_backup_hint),
                onClick = onOpenBackup,
            )
        }

        Section(stringResource(R.string.settings_repair)) {
            NavRow(
                title = stringResource(R.string.settings_merge),
                subtitle = stringResource(R.string.settings_merge_hint),
                onClick = onOpenMergeDuplicates,
            )
        }

        // Its own card, at the bottom, away from anything routine. A destructive
        // action sitting in a list of navigation rows is one mis-tap.
        Section(
            title = stringResource(R.string.settings_danger),
            subtitle = stringResource(R.string.settings_danger_hint),
        ) {
            NavRow(
                title = stringResource(R.string.settings_delete_all),
                subtitle = stringResource(R.string.settings_delete_all_hint),
                onClick = { confirmErase = true },
            )
        }
    }

    if (confirmErase) {
        AlertDialog(
            onDismissRequest = { confirmErase = false },
            title = { Text(stringResource(R.string.settings_delete_all_title)) },
            text = { Text(stringResource(R.string.settings_delete_all_body)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.eraseEverything()
                    confirmErase = false
                }) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = { TextButton(onClick = { confirmErase = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}
