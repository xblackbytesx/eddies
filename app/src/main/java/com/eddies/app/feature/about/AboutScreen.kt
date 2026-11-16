package com.eddies.app.feature.about

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
            InfoRow(stringResource(R.string.about_version), BuildConfig.VERSION_NAME)
            HorizontalDivider()
            InfoRow(stringResource(R.string.about_package), BuildConfig.APPLICATION_ID)
        }

        Section(stringResource(R.string.about_privacy)) {
            Text(
                text = stringResource(R.string.about_privacy_text),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(16.dp),
            )
        }

        Section(stringResource(R.string.about_open_source)) {
            Text(
                text = LICENCES,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        }
    }
}

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
