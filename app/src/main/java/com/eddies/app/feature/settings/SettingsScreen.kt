package com.eddies.app.feature.settings

import com.eddies.app.R
import androidx.compose.ui.res.stringResource
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

        Section(stringResource(R.string.settings_preferences)) {
            NavRow(
                title = stringResource(R.string.settings_general),
                subtitle = stringResource(R.string.settings_general_hint),
                icon = Icons.Default.Tune,
                onClick = onOpenGeneral,
            )
            HorizontalDivider()
            NavRow(
                title = stringResource(R.string.settings_portfolio),
                subtitle = stringResource(R.string.settings_portfolio_hint),
                icon = Icons.Default.PieChart,
                onClick = onOpenPortfolio,
            )
        }

        // Crypto and stocks are peers, side by side, because that is how the
        // portfolio itself is organised. Neither is the default one.
        Section(
            title = stringResource(R.string.settings_markets),
            subtitle = stringResource(R.string.settings_markets_hint),
        ) {
            NavRow(
                title = stringResource(R.string.class_crypto),
                subtitle = stringResource(R.string.settings_crypto_hint),
                icon = Icons.Default.CurrencyBitcoin,
                onClick = onOpenCrypto,
            )
            HorizontalDivider()
            NavRow(
                title = stringResource(R.string.class_stocks),
                subtitle = stringResource(R.string.settings_stocks_hint),
                icon = Icons.AutoMirrored.Filled.ShowChart,
                onClick = onOpenStocks,
            )
        }

        Section(stringResource(R.string.settings_your_data)) {
            NavRow(
                title = stringResource(R.string.settings_privacy),
                subtitle = stringResource(R.string.settings_privacy_hint),
                icon = Icons.Default.Lock,
                onClick = onOpenPrivacy,
            )
            HorizontalDivider()
            NavRow(
                title = stringResource(R.string.settings_data),
                subtitle = stringResource(R.string.settings_data_hint),
                icon = Icons.Default.Storage,
                onClick = onOpenData,
            )
        }

        Section(stringResource(R.string.settings_about)) {
            InfoRow(stringResource(R.string.about_version), BuildConfig.VERSION_NAME)
            HorizontalDivider()
            NavRow(title = stringResource(R.string.settings_licences), onClick = onOpenAbout)
        }

        Text(
            stringResource(R.string.settings_footer),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    }
}
