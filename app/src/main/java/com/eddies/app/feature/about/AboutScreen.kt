package com.eddies.app.feature.about

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.eddies.app.BuildConfig
import com.eddies.app.core.ui.Section
import com.eddies.app.feature.settings.InfoRow

@Composable
fun AboutScreen(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth().verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp).padding(bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Spacer(Modifier.size(4.dp))

        Section("Eddies") {
            InfoRow("Version", BuildConfig.VERSION_NAME)
            HorizontalDivider()
            InfoRow("Package", BuildConfig.APPLICATION_ID)
        }

        Section("Privacy") {
            Text(
                text = PRIVACY,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(16.dp),
            )
        }

        Section("Open source") {
            Text(
                text = LICENCES,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        }
    }
}

private const val PRIVACY = """Eddies has no account and no server of its own. Your holdings are stored only on this device, in a database encrypted with a key held in the Android Keystore.

There is no analytics, no crash reporting and no advertising. The app declares one permission: internet access.

It talks to a handful of services, and only about prices:
  - an exchange (Kraken or Binance) for live crypto prices
  - CoinPaprika, or CoinGecko with your own key, for coins the exchange does not list
  - Yahoo Finance, or Finnhub with your own key, for shares and ETFs
  - Tradegate, for instruments held there
  - Koios, if you add a Cardano stake address
  - Frankfurter, for European Central Bank currency rates

Those requests name the instruments you are pricing. That is unavoidable for a price, but it is why coin icons ship inside the app rather than being fetched, and why searching is offline unless you turn remote lookup on.

Backups are encrypted with a passphrase you choose, and are written wherever you point them. Nothing is uploaded."""

// GPLv3 section 5 wants a distributed work to carry its own notice, so Eddies
// states its own licence here rather than only in the repository. The source URL
// is section 6: anyone holding the APK has to be able to find the source.
private const val LICENCES = """Eddies
  Copyright (C) 2026 xblackbytesx
  GNU General Public License v3.0 or later
  Source: https://github.com/xblackbytesx/eddies

  This is free software: you may change it and redistribute it.
  There is NO WARRANTY, to the extent permitted by law.

Cryptocurrency icons
  ErikThiart/cryptocurrency-icons, MIT
  spothq/cryptocurrency-icons, CC0-1.0

Libraries
  Jetpack Compose, Room, DataStore, WorkManager, Hilt: Apache-2.0
  Ktor, kotlinx.serialization, kotlinx.coroutines: Apache-2.0
  Coil: Apache-2.0
  Google Tink: Apache-2.0
  SQLCipher for Android: BSD-style, Zetetic LLC

Market data
  Kraken, Binance, CoinPaprika, CoinGecko, Frankfurter"""
