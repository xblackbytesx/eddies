package com.eddies.app.data.prefs

import android.content.Context
import android.util.Base64
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.eddies.app.core.crypto.SecretStore
import com.eddies.app.core.design.ThemeMode
import com.eddies.app.domain.CostBasisMethod
import com.eddies.app.domain.PriceSourceId
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore by preferencesDataStore(name = "eddies_settings")

/** Which realtime feed the price pipeline prefers. OFF falls back to polling. */
enum class RealtimeFeed(val label: String) {
    KRAKEN("Kraken"),
    BINANCE("Binance"),
    OFF("Off (poll instead)"),
}

/** Which REST source fills in coins the exchanges do not quote. */
enum class Aggregator(val label: String) {
    COINPAPRIKA("CoinPaprika (no key needed)"),
    COINGECKO("CoinGecko (your own key)"),
}

/**
 * Every setting in one immutable snapshot, read as a single Flow.
 *
 * One aggregated object rather than a Flow per key: the portfolio pipeline needs
 * base currency, cost basis method and fee handling together, and combining five
 * separate flows to get them recomputes on each one arriving separately.
 */
data class AppSettings(
    // Appearance
    val themeMode: ThemeMode = ThemeMode.DARK,
    val dynamicColor: Boolean = false,
    val compactRows: Boolean = false,
    // Display
    val baseCurrency: String = "EUR",
    val secondaryCurrency: String = "USD",
    val advancedMode: Boolean = false,
    // Market data
    val realtimeFeed: RealtimeFeed = RealtimeFeed.KRAKEN,
    val aggregator: Aggregator = Aggregator.COINPAPRIKA,
    val pollIntervalSeconds: Int = 60,
    val hasCoinGeckoKey: Boolean = false,
    val hasStockApiKey: Boolean = false,
    val remoteIcons: Boolean = false,
    // Portfolio
    val costBasisMethod: CostBasisMethod = CostBasisMethod.AVERAGE,
    val includeFeesInBasis: Boolean = true,
    // Security
    val appLockEnabled: Boolean = false,
    val hideBalances: Boolean = false,
    val hideInRecents: Boolean = true,
    // Lifecycle
    val onboarded: Boolean = false,
    val seedVersion: Int = 0,
    val lastSnapshotDay: String = "",
) {
    /** The feed the pipeline should try first, or null when polling only. */
    val preferredSource: PriceSourceId?
        get() = when (realtimeFeed) {
            RealtimeFeed.KRAKEN -> PriceSourceId.KRAKEN
            RealtimeFeed.BINANCE -> PriceSourceId.BINANCE
            RealtimeFeed.OFF -> null
        }
}

@Singleton
class SettingsDataStore @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val secrets: SecretStore,
) {
    private object Keys {
        val themeMode = stringPreferencesKey("theme_mode")
        val dynamicColor = booleanPreferencesKey("dynamic_color")
        val compactRows = booleanPreferencesKey("compact_rows")
        val baseCurrency = stringPreferencesKey("base_currency")
        val secondaryCurrency = stringPreferencesKey("secondary_currency")
        val advancedMode = booleanPreferencesKey("advanced_mode")
        val realtimeFeed = stringPreferencesKey("realtime_feed")
        val aggregator = stringPreferencesKey("aggregator")
        val pollIntervalSeconds = intPreferencesKey("poll_interval_seconds")
        val coinGeckoKeyCipher = stringPreferencesKey("coingecko_key_cipher")
        val stockApiKeyCipher = stringPreferencesKey("stock_api_key_cipher")
        val remoteIcons = booleanPreferencesKey("remote_icons")
        val costBasisMethod = stringPreferencesKey("cost_basis_method")
        val includeFeesInBasis = booleanPreferencesKey("include_fees_in_basis")
        val appLockEnabled = booleanPreferencesKey("app_lock_enabled")
        val hideBalances = booleanPreferencesKey("hide_balances")
        val hideInRecents = booleanPreferencesKey("hide_in_recents")
        val onboarded = booleanPreferencesKey("onboarded")
        val seedVersion = intPreferencesKey("seed_version")
        val lastSnapshotDay = stringPreferencesKey("last_snapshot_day")
        val pinCipher = stringPreferencesKey("pin_cipher")
        val lastFxDay = longPreferencesKey("last_fx_fetch")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { p ->
        AppSettings(
            themeMode = p[Keys.themeMode].toEnum(ThemeMode.DARK),
            dynamicColor = p[Keys.dynamicColor] ?: false,
            compactRows = p[Keys.compactRows] ?: false,
            baseCurrency = p[Keys.baseCurrency] ?: "EUR",
            secondaryCurrency = p[Keys.secondaryCurrency] ?: "USD",
            advancedMode = p[Keys.advancedMode] ?: false,
            realtimeFeed = p[Keys.realtimeFeed].toEnum(RealtimeFeed.KRAKEN),
            aggregator = p[Keys.aggregator].toEnum(Aggregator.COINPAPRIKA),
            pollIntervalSeconds = p[Keys.pollIntervalSeconds] ?: 60,
            hasCoinGeckoKey = !p[Keys.coinGeckoKeyCipher].isNullOrEmpty(),
            hasStockApiKey = !p[Keys.stockApiKeyCipher].isNullOrEmpty(),
            remoteIcons = p[Keys.remoteIcons] ?: false,
            costBasisMethod = p[Keys.costBasisMethod].toEnum(CostBasisMethod.AVERAGE),
            includeFeesInBasis = p[Keys.includeFeesInBasis] ?: true,
            appLockEnabled = p[Keys.appLockEnabled] ?: false,
            hideBalances = p[Keys.hideBalances] ?: false,
            hideInRecents = p[Keys.hideInRecents] ?: true,
            onboarded = p[Keys.onboarded] ?: false,
            seedVersion = p[Keys.seedVersion] ?: 0,
            lastSnapshotDay = p[Keys.lastSnapshotDay] ?: "",
        )
    }

    suspend fun current(): AppSettings = settings.first()

    suspend fun setThemeMode(mode: ThemeMode) = put(Keys.themeMode, mode.name)
    suspend fun setDynamicColor(on: Boolean) = put(Keys.dynamicColor, on)
    suspend fun setCompactRows(on: Boolean) = put(Keys.compactRows, on)
    suspend fun setBaseCurrency(code: String) = put(Keys.baseCurrency, code.uppercase())
    suspend fun setSecondaryCurrency(code: String) = put(Keys.secondaryCurrency, code.uppercase())
    suspend fun setAdvancedMode(on: Boolean) = put(Keys.advancedMode, on)
    suspend fun setRealtimeFeed(feed: RealtimeFeed) = put(Keys.realtimeFeed, feed.name)
    suspend fun setAggregator(a: Aggregator) = put(Keys.aggregator, a.name)
    suspend fun setPollInterval(seconds: Int) =
        put(Keys.pollIntervalSeconds, seconds.coerceIn(15, 900))
    suspend fun setRemoteIcons(on: Boolean) = put(Keys.remoteIcons, on)
    suspend fun setCostBasisMethod(m: CostBasisMethod) = put(Keys.costBasisMethod, m.name)
    suspend fun setIncludeFeesInBasis(on: Boolean) = put(Keys.includeFeesInBasis, on)
    suspend fun setAppLockEnabled(on: Boolean) = put(Keys.appLockEnabled, on)
    suspend fun setHideBalances(on: Boolean) = put(Keys.hideBalances, on)
    suspend fun setHideInRecents(on: Boolean) = put(Keys.hideInRecents, on)
    suspend fun setOnboarded() = put(Keys.onboarded, true)
    suspend fun setSeedVersion(v: Int) = put(Keys.seedVersion, v)
    suspend fun setLastSnapshotDay(day: String) = put(Keys.lastSnapshotDay, day)
    suspend fun setLastFxFetch(atEpochMs: Long) = put(Keys.lastFxDay, atEpochMs)

    suspend fun lastFxFetch(): Long = context.dataStore.data.first()[Keys.lastFxDay] ?: 0L

    /** The user's own CoinGecko key, sealed at rest. Never logged, never in a URL we print. */
    suspend fun coinGeckoKey(): String {
        val cipher = context.dataStore.data.first()[Keys.coinGeckoKeyCipher] ?: return ""
        if (cipher.isEmpty()) return ""
        return runCatching { secrets.decrypt(Base64.decode(cipher, Base64.NO_WRAP)) }.getOrDefault("")
    }

    suspend fun setCoinGeckoKey(key: String) {
        context.dataStore.edit {
            if (key.isBlank()) {
                it.remove(Keys.coinGeckoKeyCipher)
            } else {
                it[Keys.coinGeckoKeyCipher] =
                    Base64.encodeToString(secrets.encrypt(key), Base64.NO_WRAP)
            }
        }
    }

    /** The user's own stock API key, sealed at rest. Never logged, never in a URL we print. */
    suspend fun stockApiKey(): String {
        val cipher = context.dataStore.data.first()[Keys.stockApiKeyCipher] ?: return ""
        if (cipher.isEmpty()) return ""
        return runCatching { secrets.decrypt(Base64.decode(cipher, Base64.NO_WRAP)) }.getOrDefault("")
    }

    suspend fun setStockApiKey(key: String) {
        context.dataStore.edit {
            if (key.isBlank()) it.remove(Keys.stockApiKeyCipher)
            else it[Keys.stockApiKeyCipher] =
                Base64.encodeToString(secrets.encrypt(key), Base64.NO_WRAP)
        }
    }

    /** The app-lock PIN, stored sealed rather than hashed so it can be verified offline. */
    suspend fun pin(): String {
        val cipher = context.dataStore.data.first()[Keys.pinCipher] ?: return ""
        return runCatching { secrets.decrypt(Base64.decode(cipher, Base64.NO_WRAP)) }.getOrDefault("")
    }

    suspend fun setPin(pin: String) {
        context.dataStore.edit {
            if (pin.isBlank()) it.remove(Keys.pinCipher)
            else it[Keys.pinCipher] = Base64.encodeToString(secrets.encrypt(pin), Base64.NO_WRAP)
        }
    }

    private suspend fun <T> put(key: androidx.datastore.preferences.core.Preferences.Key<T>, value: T) {
        context.dataStore.edit { it[key] = value }
    }
}

/** Reads an enum by name, falling back rather than throwing on an unknown value. */
private inline fun <reified T : Enum<T>> String?.toEnum(fallback: T): T =
    this?.let { runCatching { enumValueOf<T>(it) }.getOrNull() } ?: fallback
