package com.eddies.app.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.eddies.app.BuildConfig
import com.eddies.app.core.design.ThemeMode
import com.eddies.app.core.ui.Section
import com.eddies.app.data.prefs.Aggregator
import com.eddies.app.data.prefs.RealtimeFeed
import com.eddies.app.domain.CostBasisMethod

/**
 * Seven sections, in the order someone actually reaches for them: how it looks,
 * what it shows, where the numbers come from, how they are calculated, who can
 * see them, getting data in and out, and what this is.
 */
@Composable
fun SettingsScreen(
    onOpenBackup: () -> Unit,
    onOpenAccounts: () -> Unit,
    onOpenAbout: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val cfg = state.settings
    var confirmErase by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Spacer(Modifier.size(4.dp))

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

        Section("Display") {
            ChoiceRow(
                title = "Main currency",
                subtitle = "Everything is valued and totalled in this.",
                options = state.currencies,
                selected = cfg.baseCurrency,
                label = { it },
                onSelect = viewModel::setBaseCurrency,
            )
            HorizontalDivider()
            SettingSwitch(
                title = "Advanced trader mode",
                subtitle = "Adds cost basis, realised P/L, ranks and per-row detail.",
                checked = cfg.advancedMode,
                onCheckedChange = viewModel::setAdvancedMode,
            )
        }

        Section("Market data") {
            ChoiceRow(
                title = "Live price feed",
                subtitle = "Kraken quotes EUR pairs directly. Binance covers more coins but prices in USDT.",
                options = RealtimeFeed.entries,
                selected = cfg.realtimeFeed,
                label = { it.label },
                onSelect = viewModel::setRealtimeFeed,
            )
            HorizontalDivider()
            ChoiceRow(
                title = "Everything else",
                subtitle = "Used for coins the exchange does not list.",
                options = Aggregator.entries,
                selected = cfg.aggregator,
                label = { it.label },
                onSelect = viewModel::setAggregator,
            )
            HorizontalDivider()
            ChoiceRow(
                title = "Refresh interval",
                options = listOf(15, 30, 60, 120, 300),
                selected = cfg.pollIntervalSeconds,
                label = { if (it < 60) "${it}s" else "${it / 60}m" },
                onSelect = viewModel::setPollInterval,
            )
            if (cfg.aggregator == Aggregator.COINGECKO) {
                HorizontalDivider()
                Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Text("CoinGecko API key", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        if (cfg.hasCoinGeckoKey) "A key is saved and encrypted on this device."
                        else "CoinGecko needs your own key. It is stored encrypted and never leaves the phone.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.size(8.dp))
                    OutlinedTextField(
                        value = state.geckoKeyDraft,
                        onValueChange = viewModel::setGeckoDraft,
                        placeholder = { Text("Paste key") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.size(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = viewModel::saveGeckoKey,
                            enabled = state.geckoKeyDraft.isNotBlank(),
                        ) { Text("Save") }
                        if (cfg.hasCoinGeckoKey) {
                            TextButton(onClick = viewModel::clearGeckoKey) { Text("Remove") }
                        }
                    }
                }
            }
            HorizontalDivider()
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text("Stock price key (optional)", style = MaterialTheme.typography.bodyLarge)
                Text(
                    if (cfg.hasStockApiKey) "A key is saved and encrypted on this device."
                    else "Shares work without this. Add a Finnhub key only if you want an " +
                        "official source instead of the default, which is unofficial and could change.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.size(8.dp))
                OutlinedTextField(
                    value = state.stockKeyDraft,
                    onValueChange = viewModel::setStockKeyDraft,
                    placeholder = { Text("Paste key") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.size(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = viewModel::saveStockKey,
                        enabled = state.stockKeyDraft.isNotBlank(),
                    ) { Text("Save") }
                    if (cfg.hasStockApiKey) {
                        TextButton(onClick = viewModel::clearStockKey) { Text("Remove") }
                    }
                }
            }
            HorizontalDivider()
            SettingSwitch(
                title = "Fetch missing coin icons",
                subtitle = "Off by default. Turning this on tells the image host which coins you hold.",
                checked = cfg.remoteIcons,
                onCheckedChange = viewModel::setRemoteIcons,
            )
        }

        Section("Portfolio") {
            ChoiceRow(
                title = "Cost basis",
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
            HorizontalDivider()
            NavRow(
                title = "Accounts and wallets",
                subtitle = "Group positions, and add a staking address.",
                onClick = onOpenAccounts,
            )
        }

        Section("Security") {
            SettingSwitch(
                title = "Require unlock",
                subtitle = "Ask for your fingerprint or PIN when opening the app.",
                checked = cfg.appLockEnabled,
                onCheckedChange = viewModel::setAppLock,
            )
            HorizontalDivider()
            SettingSwitch(
                title = "Hide balances",
                subtitle = "Blur every amount until you tap the eye.",
                checked = cfg.hideBalances,
                onCheckedChange = viewModel::setHideBalances,
            )
            HorizontalDivider()
            SettingSwitch(
                title = "Hide from recent apps",
                subtitle = "Keeps balances out of the app switcher thumbnail.",
                checked = cfg.hideInRecents,
                onCheckedChange = viewModel::setHideInRecents,
            )
        }

        Section("Data") {
            NavRow(
                title = "Backup and restore",
                subtitle = "Encrypted portfolio file, or a plain CSV.",
                onClick = onOpenBackup,
            )
            HorizontalDivider()
            NavRow(
                title = "Delete all transactions",
                subtitle = "Cannot be undone. Export first.",
                onClick = { confirmErase = true },
            )
        }

        Section("About") {
            InfoRow("Version", BuildConfig.VERSION_NAME)
            HorizontalDivider()
            NavRow(title = "Licences and privacy", onClick = onOpenAbout)
        }

        state.message?.let { msg ->
            Text(msg, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
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
