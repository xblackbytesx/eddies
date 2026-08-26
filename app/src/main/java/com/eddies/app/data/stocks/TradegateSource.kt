package com.eddies.app.data.stocks

import com.eddies.app.data.prefs.SettingsDataStore
import com.eddies.app.data.price.PriceSource
import com.eddies.app.data.price.ResolvedSymbol
import com.eddies.app.data.price.asStringOrNull
import com.eddies.app.domain.AssetIds
import com.eddies.app.domain.GermanNumber
import com.eddies.app.domain.Isin
import com.eddies.app.domain.PriceSourceId
import com.eddies.app.domain.PriceTick
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import javax.inject.Inject
import javax.inject.Singleton

/** A Tradegate quote. Everything on Tradegate trades in euros. */
data class TradegateQuote(
    val isin: String,
    val last: java.math.BigDecimal,
    val bid: java.math.BigDecimal?,
    val ask: java.math.BigDecimal?,
    val changePct: Double?,
    /** How many seconds Tradegate itself suggests waiting before asking again. */
    val refreshSeconds: Int?,
)

/**
 * Tradegate Exchange, Berlin. Keyless, keyed by ISIN, quoted in euros.
 *
 * Yahoo does not cover Tradegate: there is no `.TG` suffix, and every other
 * German venue has one. So a position actually held on Tradegate cannot be
 * priced through the normal stock path, which is why this exists.
 *
 * **The response mixes number types.** The same field is a JSON number for one
 * instrument and a German comma-decimal string for another, apparently whenever
 * the value would end in a trailing zero:
 *
 *     SAP   "last":180.38        a number
 *     ASML  "last":"1501,60"     a string, comma decimal
 *
 * Parsing that with the ordinary path throws, the tick is dropped, and the
 * holding shows no price at all. Silent, and only for some instruments. Every
 * value here therefore goes through GermanNumber.
 *
 * Snapshot only, no history. Charts come from the equivalent Yahoo listing,
 * resolved by ISIN and stored in asset_source_refs.
 *
 * Verified against the live endpoint 2026-08-26.
 */
@Singleton
class TradegateSource @Inject constructor(
    private val http: HttpClient,
    private val json: Json,
    private val settings: SettingsDataStore,
) : PriceSource {

    override val id = PriceSourceId.TRADEGATE
    override val isRealtime = false

    override suspend fun resolve(tickers: Map<String, String>, baseCurrency: String): Map<String, ResolvedSymbol> =
        tickers
            .filterKeys { AssetIds.exchangeOf(it) == EXCHANGE }
            .mapValues { (assetId, _) ->
                // The ticker for a Tradegate listing is its ISIN, which is what
                // the id already carries.
                ResolvedSymbol(assetId, AssetIds.tickerOf(assetId), CURRENCY)
            }

    override fun stream(symbols: Map<String, ResolvedSymbol>): Flow<PriceTick> = flow {
        if (symbols.isEmpty()) return@flow
        while (true) {
            var suggested: Int? = null
            for (resolved in symbols.values) {
                val quote = quote(resolved.symbol) ?: continue
                suggested = quote.refreshSeconds ?: suggested
                emit(
                    PriceTick(
                        assetId = resolved.assetId,
                        price = quote.last,
                        currency = CURRENCY,
                        changePct24h = quote.changePct,
                        at = System.currentTimeMillis(),
                        source = PriceSourceId.TRADEGATE,
                        stale = false,
                    ),
                )
            }
            // Tradegate tells us its own cadence in the payload. Honouring it
            // beats guessing, and beats hammering a venue that says 10 seconds.
            val configured = settings.current().pollIntervalSeconds
            delay(maxOf(configured, suggested ?: 0, MIN_INTERVAL_SECONDS) * 1000L)
        }
    }

    /** A single quote. Null when Tradegate does not list the ISIN. */
    suspend fun quote(isin: String): TradegateQuote? = runCatching {
        val clean = Isin.normalise(isin)
        if (!Isin.looksLikeIsin(clean)) return null

        val body = http.get(REFRESH) {
            parameter("isin", clean)
            header("User-Agent", UA)
        }.bodyAsText()

        // An unlisted ISIN comes back as an empty body, not an error object.
        if (body.isBlank() || !body.trimStart().startsWith("{")) return null

        val o = json.parseToJsonElement(body).jsonObject
        val last = number(o, "last") ?: return null
        if (last.signum() <= 0) return null

        TradegateQuote(
            isin = clean,
            last = last,
            bid = number(o, "bid"),
            ask = number(o, "ask"),
            changePct = number(o, "delta")?.toDouble(),
            refreshSeconds = number(o, "refresh")?.toInt(),
        )
    }.getOrNull()

    /** Reads a field that may be a JSON number or a comma-decimal string. */
    private fun number(o: JsonObject, key: String): java.math.BigDecimal? =
        GermanNumber.parse(o[key].asStringOrNull())

    companion object {
        /** The exchange segment used in asset ids: stock:TRADEGATE:<ISIN>. */
        const val EXCHANGE = "TRADEGATE"

        /** Tradegate is a German regulated market. Everything on it trades in euros. */
        const val CURRENCY = "EUR"

        // The final host. www.tradegate.de 301s here, and following a cross-host
        // redirect is one more thing to go wrong on a phone.
        private const val REFRESH = "https://www.tradegatebsx.com/refresh.php"
        private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"

        /** Their own payload suggests 10 seconds; never poll faster than this. */
        private const val MIN_INTERVAL_SECONDS = 10
    }
}
