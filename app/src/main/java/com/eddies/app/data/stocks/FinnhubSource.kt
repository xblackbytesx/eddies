package com.eddies.app.data.stocks

import com.eddies.app.data.prefs.SettingsDataStore
import com.eddies.app.data.price.asBigDecimal
import com.eddies.app.data.price.asDoubleOrNull
import io.ktor.client.HttpClient
import io.ktor.client.request.get
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

        val body = http.get("$BASE/quote") {
            // Finnhub uses the bare ticker, without Yahoo's exchange suffix.
            parameter("symbol", symbol.substringBefore('.'))
            parameter("token", key)
        }.bodyAsText()

        val o = json.parseToJsonElement(body).jsonObject
        val current = o["c"].asBigDecimal() ?: return null
        // Finnhub answers an unknown symbol with a zero-filled object rather
        // than an error, so a zero price means "not found", not "worthless".
        if (current.signum() == 0) return null

        StockQuote(
            assetId = assetId,
            price = current,
            // The free tier is US markets, which are dollar denominated.
            currency = "USD",
            changePct = o["dp"].asDoubleOrNull(),
            at = (o["t"].asDoubleOrNull()?.toLong() ?: 0L) * 1000,
            marketClosed = false,
        )
    }.getOrNull()

    private companion object {
        const val BASE = "https://finnhub.io/api/v1"
    }
}
