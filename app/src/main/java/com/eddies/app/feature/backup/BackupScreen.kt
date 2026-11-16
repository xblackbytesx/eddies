package com.eddies.app.feature.backup

import androidx.compose.ui.res.pluralStringResource
import com.eddies.app.R
import androidx.compose.ui.res.stringResource
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
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
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
            title = stringResource(R.string.backup_encrypted),
            subtitle = stringResource(R.string.backup_encrypted_hint),
        ) {
            Column(Modifier.padding(16.dp)) {
                OutlinedTextField(
                    value = state.passphrase,
                    onValueChange = viewModel::setPassphrase,
                    label = { Text(stringResource(R.string.backup_passphrase)) },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    supportingText = {
                        Text(pluralStringResource(
                            R.plurals.backup_passphrase_min,
                            BackupUiState.MIN_PASSPHRASE,
                            BackupUiState.MIN_PASSPHRASE,
                        ))
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            HorizontalDivider()
            SettingSwitch(
                title = stringResource(R.string.backup_include_settings),
                checked = state.includeSettings,
                onCheckedChange = viewModel::setIncludeSettings,
            )
            HorizontalDivider()
            SettingSwitch(
                title = stringResource(R.string.backup_include_portfolio),
                subtitle = stringResource(R.string.backup_include_portfolio_hint),
                checked = state.includePortfolio,
                onCheckedChange = viewModel::setIncludePortfolio,
            )
            HorizontalDivider()
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { createBackup.launch("eddies-$today.eddies") },
                    enabled = state.canExport,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.backup_write)) }
                OutlinedButton(
                    onClick = { openBackup.launch(arrayOf("*/*")) },
                    enabled = state.canPreview,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.backup_restore)) }
            }
        }

        Section(
            title = stringResource(R.string.backup_csv),
            subtitle = stringResource(R.string.backup_csv_hint),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { createCsv.launch("eddies-$today.csv") },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.backup_export_csv)) }
                OutlinedButton(
                    onClick = { openCsv.launch(arrayOf("text/csv", "text/comma-separated-values", "text/plain", "*/*")) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.backup_import_csv)) }
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
            title = { Text(stringResource(R.string.backup_restore_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.backup_manifest, manifest.transactionCount, manifest.accountCount))
                    if (manifest.hasSettings) Text(stringResource(R.string.backup_manifest_settings))
                    Spacer(Modifier.size(8.dp))
                    Text(
                        stringResource(R.string.backup_merge_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = { TextButton(onClick = viewModel::confirmRestore) { Text(stringResource(R.string.backup_restore_confirm)) } },
            dismissButton = { TextButton(onClick = viewModel::dismissManifest) { Text(stringResource(R.string.action_cancel)) } },
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
