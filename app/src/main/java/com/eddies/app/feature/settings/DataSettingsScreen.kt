package com.eddies.app.feature.settings

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
        Section("Your ledger") {
            NavRow(
                title = "Transaction history",
                subtitle = "Every buy, sell, dividend and reward, across all assets.",
                onClick = onOpenTransactions,
            )
            HorizontalDivider()
            NavRow(
                title = "Backup and restore",
                subtitle = "Encrypted portfolio file, or a plain CSV.",
                onClick = onOpenBackup,
            )
        }

        Section("Repair") {
            NavRow(
                title = "Merge duplicate holdings",
                subtitle = "One instrument showing as two entries. Nothing moves until you confirm.",
                onClick = onOpenMergeDuplicates,
            )
        }

        // Its own card, at the bottom, away from anything routine. A destructive
        // action sitting in a list of navigation rows is one mis-tap.
        Section(
            title = "Danger zone",
            subtitle = "There is no undo and no copy anywhere else.",
        ) {
            NavRow(
                title = "Delete all transactions",
                subtitle = "Export a backup first.",
                onClick = { confirmErase = true },
            )
        }
    }

    if (confirmErase) {
        AlertDialog(
            onDismissRequest = { confirmErase = false },
            title = { Text("Delete every transaction?") },
            text = { Text("Your whole ledger is removed from this device. There is no undo, and no copy anywhere else.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.eraseEverything()
                    confirmErase = false
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmErase = false }) { Text("Cancel") } },
        )
    }
}
