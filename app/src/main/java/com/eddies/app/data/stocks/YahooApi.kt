package com.eddies.app.data.stocks

import com.eddies.app.data.price.asBigDecimal
import com.eddies.app.data.price.asDoubleOrNull
import com.eddies.app.data.price.asStringOrNull
import com.eddies.app.domain.Asset
import com.eddies.app.domain.AssetClass
import com.eddies.app.domain.AssetIds
import com.eddies.app.domain.SplitEvent
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.math.BigDecimal
import javax.inject.Inject
import javax.inject.Singleton

/** One quote, in the listing's own currency. */
data class StockQuote(
    val assetId: String,
    val price: BigDecimal,
    val currency: String,
    val changePct: Double?,
    val at: Long,
    /** True when the market is shut, so the price is a close rather than live. */
    val marketClosed: Boolean,
)

data class StockBar(val timestamp: Long, val close: BigDecimal, val high: BigDecimal?, val low: BigDecimal?)

data class StockHistory(
    val assetId: String,
    val currency: String,
    val bars: List<StockBar>,
    val splits: List<SplitEvent>,
)

/**
 * Yahoo Finance, which is what practically every open-source tracker uses.
 *
 * Keyless and complete: it returns each listing's own currency, its exchange,
 * historical bars, and crucially the split and dividend events. It is however an
 * **unofficial** endpoint. It can change without notice, which is why Settings
 * offers an official provider as an alternative and why every parse here fails
 * soft rather than throwing.
 *
 * Verified against the live endpoint 2026-08-26.
 */
@Singleton
class YahooApi @Inject constructor(
    private val http: HttpClient,
    private val json: Json,
) {

    /**
     * A **desktop** browser user agent, and it is load-bearing.
     *
     * Verified 2026-08-26: sending no user agent returns HTTP 429. Sending a
     * mobile one (anything advertising Android or Mobile) returns **HTTP 200
     * with the body "Too Many Requests"**, which is the worst possible failure:
     * a success status carrying no JSON, so every parse silently yields nothing
     * and the stock side looks empty rather than broken.
     *
     * A desktop string works. So does okhttp's own default, but relying on the
     * engine default would make this depend on a library version.
     */
    private fun io.ktor.client.request.HttpRequestBuilder.browserish() {
        header("User-Agent", UA)
        header("Accept", "application/json")
    }

    suspend fun quote(assetId: String, symbol: String): StockQuote? = runCatching {
        val meta = chartMeta(symbol) ?: return null
        val price = meta["regularMarketPrice"].asBigDecimal() ?: return null
        val previous = meta["chartPreviousClose"].asBigDecimal()
            ?: meta["previousClose"].asBigDecimal()
        StockQuote(
            assetId = assetId,
            price = price,
            currency = meta["currency"].asStringOrNull()?.uppercase() ?: "USD",
            changePct = previous?.takeIf { it.signum() != 0 }?.let {
                (price - it).divide(it, com.eddies.app.domain.MC).toDouble() * 100.0
            },
            at = (meta["regularMarketTime"].asStringOrNull()?.toLongOrNull() ?: 0L) * 1000,
            marketClosed = meta["marketState"].asStringOrNull()
                ?.let { it != "REGULAR" } ?: false,
        )
    }.getOrNull()

    private suspend fun chartMeta(symbol: String): JsonObject? {
        val body = http.get("$CHART/$symbol") {
            browserish()
            parameter("range", "5d")
            parameter("interval", "1d")
        }.bodyAsText()
        if (!body.looksLikeJson()) return null
        val result = json.parseToJsonElement(body).jsonObject["chart"]
            ?.jsonObject?.get("result") as? JsonArray ?: return null
        return (result.firstOrNull() as? JsonObject)?.get("meta")?.jsonObject
    }

    /**
     * Bars plus corporate actions in one request.
     *
     * The splits come back keyed by timestamp with a numerator and denominator,
     * which is exactly what the fold needs. Fetching them alongside the bars
     * means a chart and a correct share count can never disagree about which
     * splits are known.
     */
    suspend fun history(assetId: String, symbol: String, range: String, interval: String): StockHistory? =
        runCatching {
            val body = http.get("$CHART/$symbol") {
                browserish()
                parameter("range", range)
                parameter("interval", interval)
                parameter("events", "div,split")
            }.bodyAsText()
            if (!body.looksLikeJson()) return null

            val result = json.parseToJsonElement(body).jsonObject["chart"]
                ?.jsonObject?.get("result") as? JsonArray ?: return null
            val root = result.firstOrNull() as? JsonObject ?: return null
            val meta = root["meta"]?.jsonObject ?: return null
            val currency = meta["currency"].asStringOrNull()?.uppercase() ?: "USD"

            val stamps = root["timestamp"] as? JsonArray ?: JsonArray(emptyList())
            val quote = (root["indicators"]?.jsonObject?.get("quote") as? JsonArray)
                ?.firstOrNull() as? JsonObject
            val closes = quote?.get("close") as? JsonArray
            val highs = quote?.get("high") as? JsonArray
            val lows = quote?.get("low") as? JsonArray

            val bars = stamps.mapIndexedNotNull { i, stamp ->
                val ts = stamp.asStringOrNull()?.toLongOrNull() ?: return@mapIndexedNotNull null
                // A null close is a non-trading gap, not a zero. Charting it as
                // zero would draw a cliff to the axis on every market holiday.
                val close = closes?.getOrNull(i).asBigDecimal() ?: return@mapIndexedNotNull null
                StockBar(ts * 1000, close, highs?.getOrNull(i).asBigDecimal(), lows?.getOrNull(i).asBigDecimal())
            }

            val splitObj = root["events"]?.jsonObject?.get("splits")?.jsonObject
            val splits = splitObj?.values?.mapNotNull { element ->
                val o = element as? JsonObject ?: return@mapNotNull null
                val date = o["date"].asStringOrNull()?.toLongOrNull() ?: return@mapNotNull null
                val numerator = o["numerator"].asBigDecimal() ?: return@mapNotNull null
                val denominator = o["denominator"].asBigDecimal() ?: return@mapNotNull null
                SplitEvent(assetId, date * 1000, numerator, denominator)
            }.orEmpty()

            StockHistory(assetId, currency, bars, splits)
        }.getOrNull()

    /**
     * Ticker search.
     *
     * Results carry the exchange, which matters: ASML trades in Amsterdam in
     * euros and as an ADR on NASDAQ in dollars. They are different instruments
     * at different prices, and the asset id keeps them apart.
     */
    suspend fun search(term: String, limit: Int = 12): List<Asset> = runCatching {
        val body = http.get(SEARCH) {
            browserish()
            parameter("q", term)
            parameter("quotesCount", limit.toString())
            parameter("newsCount", "0")
        }.bodyAsText()

        val quotes = json.parseToJsonElement(body).jsonObject["quotes"] as? JsonArray ?: return emptyList()
        quotes.mapNotNull { element ->
            val o = element as? JsonObject ?: return@mapNotNull null
            val type = o["quoteType"].asStringOrNull()
            if (type != null && type !in TRADEABLE) return@mapNotNull null
            val symbol = o["symbol"].asStringOrNull() ?: return@mapNotNull null
            val exchange = o["exchDisp"].asStringOrNull() ?: o["exchange"].asStringOrNull() ?: "UNKNOWN"
            Asset(
                id = AssetIds.stock(exchange.uppercase().replace(' ', '_'), symbol.uppercase()),
                assetClass = AssetClass.STOCK,
                symbol = symbol.uppercase(),
                name = o["shortname"].asStringOrNull() ?: o["longname"].asStringOrNull() ?: symbol,
                // Fractional shares are common, but not to eight places.
                decimals = 4,
                iconSlug = null,
                rank = null,
            )
        }
    }.getOrDefault(emptyList())

    private companion object {
        const val CHART = "https://query1.finance.yahoo.com/v8/finance/chart"
        const val SEARCH = "https://query1.finance.yahoo.com/v1/finance/search"
        const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"

        /** ETFs and funds are held like shares. Options and futures are not. */
        val TRADEABLE = setOf("EQUITY", "ETF", "MUTUALFUND", "INDEX")
    }
}

/**
 * Guards against a 200 that is not JSON.
 *
 * Yahoo answers a rejected client with status 200 and a plain-text body, so
 * status alone cannot be trusted and a bare parse would throw inside a
 * runCatching and read as "no data" rather than "we were turned away".
 */
private fun String.looksLikeJson(): Boolean {
    val t = trimStart()
    return t.startsWith("{") || t.startsWith("[")
}
