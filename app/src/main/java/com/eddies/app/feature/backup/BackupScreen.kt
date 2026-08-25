package com.eddies.app.feature.backup

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.eddies.app.core.ui.Section
import com.eddies.app.feature.settings.SettingSwitch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate

@Composable
fun BackupScreen(
    modifier: Modifier = Modifier,
    viewModel: BackupViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val today = LocalDate.now().toString()

    // Storage Access Framework throughout: the user picks the location, and the
    // app needs no storage permission at all.
    val createBackup = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
        if (uri != null) viewModel.export { bytes -> context.writeBytes(uri, bytes) }
    }

    val openBackup = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val bytes = context.readBytes(uri)
            if (bytes != null) viewModel.preview(bytes)
        }
    }

    val createCsv = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv"),
    ) { uri ->
        if (uri != null) viewModel.exportCsv { text -> context.writeBytes(uri, text.toByteArray()) }
    }

    val openCsv = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val bytes = context.readBytes(uri)
            if (bytes != null) viewModel.importCsv(String(bytes))
        }
    }

    Column(
        modifier = modifier.fillMaxWidth().verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp).padding(bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Spacer(Modifier.size(4.dp))

        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())

        Section(
            title = "Encrypted backup",
            subtitle = "Your passphrase is the only way back in. Nothing can recover it.",
        ) {
            Column(Modifier.padding(16.dp)) {
                OutlinedTextField(
                    value = state.passphrase,
                    onValueChange = viewModel::setPassphrase,
                    label = { Text("Passphrase") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    supportingText = {
                        Text("At least ${BackupUiState.MIN_PASSPHRASE} characters.")
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            HorizontalDivider()
            SettingSwitch(
                title = "Include settings",
                checked = state.includeSettings,
                onCheckedChange = viewModel::setIncludeSettings,
            )
            HorizontalDivider()
            SettingSwitch(
                title = "Include portfolio",
                subtitle = "Transactions, accounts and the coins they reference.",
                checked = state.includePortfolio,
                onCheckedChange = viewModel::setIncludePortfolio,
            )
            HorizontalDivider()
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { createBackup.launch("eddies-$today.eddies") },
                    enabled = state.canExport,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Write backup") }
                OutlinedButton(
                    onClick = { openBackup.launch(arrayOf("*/*")) },
                    enabled = state.canPreview,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Restore from backup") }
            }
        }

        Section(
            title = "Spreadsheet export",
            subtitle = "Plain CSV. Not encrypted: anyone who opens the file can read your ledger.",
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { createCsv.launch("eddies-$today.csv") },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Export CSV") }
                OutlinedButton(
                    onClick = { openCsv.launch(arrayOf("text/csv", "text/comma-separated-values", "text/plain", "*/*")) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Import CSV") }
            }
        }

        state.message?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        }
        state.error?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        }
    }

    state.manifest?.let { manifest ->
        AlertDialog(
            onDismissRequest = viewModel::dismissManifest,
            title = { Text("Restore this backup?") },
            text = {
                Column {
                    Text("${manifest.transactionCount} transactions, ${manifest.accountCount} accounts.")
                    if (manifest.hasSettings) Text("Settings are included and will be applied.")
                    Spacer(Modifier.size(8.dp))
                    Text(
                        "Importing merges into what is already here rather than replacing it.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = { TextButton(onClick = viewModel::confirmRestore) { Text("Restore") } },
            dismissButton = { TextButton(onClick = viewModel::dismissManifest) { Text("Cancel") } },
        )
    }
}

private suspend fun Context.writeBytes(uri: android.net.Uri, bytes: ByteArray) {
    withContext(Dispatchers.IO) {
        contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
    }
}

private fun Context.readBytes(uri: android.net.Uri): ByteArray? = runCatching {
    // Bounded: a picked file is arbitrary, and reading an unbounded one into
    // memory is an out-of-memory crash the user cannot diagnose.
    contentResolver.openInputStream(uri)?.use { it.readBytes().take(MAX_IMPORT_BYTES) }
}.getOrNull()

private const val MAX_IMPORT_BYTES = 32 * 1024 * 1024

private fun ByteArray.take(max: Int): ByteArray = if (size <= max) this else copyOfRange(0, max)
