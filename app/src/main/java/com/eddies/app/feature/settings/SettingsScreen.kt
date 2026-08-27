package com.eddies.app.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CurrencyBitcoin
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PieChart
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.eddies.app.BuildConfig
import com.eddies.app.core.ui.Section

/**
 * The settings hub: seven categories, each its own screen.
 *
 * It was one flat scroll of seven cards, which stopped working once shares
 * arrived. Crypto and stock preferences ended up interleaved in a "Market data"
 * card with no labelling, so the two feeds, two fallback sources and two API
 * keys read as one undifferentiated pile, and finding the one you wanted meant
 * reading all of them.
 *
 * Splitting by subject rather than by control type is what makes it navigable:
 * someone looking for a stock setting goes to Stocks, and there is exactly one
 * place it can be. The cost is a tap, and a category that is thin today (Stocks
 * has one control) still earns its place by being where that control obviously
 * belongs.
 */
@Composable
fun SettingsScreen(
    onOpenGeneral: () -> Unit,
    onOpenCrypto: () -> Unit,
    onOpenStocks: () -> Unit,
    onOpenPortfolio: () -> Unit,
    onOpenPrivacy: () -> Unit,
    onOpenData: () -> Unit,
    onOpenAbout: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Spacer(Modifier.size(4.dp))

        Section("Preferences") {
            NavRow(
                title = "General",
                subtitle = "Theme, currencies, and how much detail to show.",
                icon = Icons.Default.Tune,
                onClick = onOpenGeneral,
            )
            HorizontalDivider()
            NavRow(
                title = "Portfolio",
                subtitle = "Cost basis, fees, accounts and wallets.",
                icon = Icons.Default.PieChart,
                onClick = onOpenPortfolio,
            )
        }

        // Crypto and stocks are peers, side by side, because that is how the
        // portfolio itself is organised. Neither is the default one.
        Section(
            title = "Markets",
            subtitle = "Where each kind of price comes from.",
        ) {
            NavRow(
                title = "Crypto",
                subtitle = "Live feed, fallback source, coin icons.",
                icon = Icons.Default.CurrencyBitcoin,
                onClick = onOpenCrypto,
            )
            HorizontalDivider()
            NavRow(
                title = "Stocks",
                subtitle = "Price sources for shares and ETFs.",
                icon = Icons.AutoMirrored.Filled.ShowChart,
                onClick = onOpenStocks,
            )
        }

        Section("Your data") {
            NavRow(
                title = "Privacy and security",
                subtitle = "App lock, hidden balances, recent apps.",
                icon = Icons.Default.Lock,
                onClick = onOpenPrivacy,
            )
            HorizontalDivider()
            NavRow(
                title = "Data management",
                subtitle = "History, backups, merging, deletion.",
                icon = Icons.Default.Storage,
                onClick = onOpenData,
            )
        }

        Section("About") {
            InfoRow("Version", BuildConfig.VERSION_NAME)
            HorizontalDivider()
            NavRow(title = "Licences and privacy", onClick = onOpenAbout)
        }

        Text(
            "Eddies keeps everything on this device. Nothing is uploaded, and no " +
                "account is needed.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    }
}
