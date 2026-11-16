package com.eddies.app.data.stocks

import com.eddies.app.data.prefs.SettingsDataStore
import com.eddies.app.data.price.asBigDecimal
import com.eddies.app.data.price.asDoubleOrNull
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.math.BigDecimal
import javax.inject.Inject
import javax.inject.Singleton

/**
 * An official, sanctioned quote source, used only when the user supplies a key.
 *
 * Exists because Yahoo's endpoint is unofficial and can change without notice.
 * If it does, this is the switch the user can flip themselves rather than
 * waiting for a new build.
 *
 * Same rule as CoinGecko: no key ships in the APK. An embedded key is extracted
 * within a day and then shared by every install until it is revoked.
 *
 * Quotes only. Finnhub's free tier does not serve the daily candles the charts
 * need, so history stays with Yahoo either way, and the app says so rather than
 * leaving an empty chart unexplained.
 */
@Singleton
class FinnhubSource @Inject constructor(
    private val http: HttpClient,
    private val json: Json,
    private val settings: SettingsDataStore,
) {

    suspend fun quote(assetId: String, symbol: String): StockQuote? = runCatching {
        val key = settings.stockApiKey()
        if (key.isBlank()) return null
        // Only listings without an exchange suffix, which are the US ones this
        // endpoint prices. The suffix used to be stripped instead, so SHEL.L
        // was priced as the New York ADR: a different instrument, in dollars,
        // worth two London shares. Anything suffixed falls through to Yahoo.
        if ('.' in symbol) return null

        val body = http.get("$BASE/quote") {
            parameter("symbol", symbol)
            // A header rather than a query parameter, so the key stays out of
            // URLs, which are what proxies and server logs record.
            header("X-Finnhub-Token", key)
        }.bodyAsText()

        val o = json.parseToJsonElement(body).jsonObject
        val current = o["c"].asBigDecimal() ?: return null
        // Finnhub answers an unknown symbol with a zero-filled object rather
        // than an error, so a zero price means "not found", not "worthless".
        if (current.signum() == 0) return null

        val at = (o["t"].asDoubleOrNull()?.toLong() ?: 0L) * 1000
        StockQuote(
            assetId = assetId,
            price = current,
            // The free tier is US markets, which are dollar denominated.
            currency = "USD",
            changePct = o["dp"].asDoubleOrNull(),
            at = at,
            // The quote carries no market state, only the time of the last
            // trade. Reporting "open" unconditionally kept the poller at full
            // speed all night and showed a Friday close as a live price.
            marketClosed = at > 0 && System.currentTimeMillis() - at > QUIET_AFTER_MS,
        )
    }.getOrNull()

    private companion object {
        const val BASE = "https://finnhub.io/api/v1"

        /** No trade for this long means the market is shut, not merely quiet. */
        const val QUIET_AFTER_MS = 30 * 60 * 1000L
    }
}
