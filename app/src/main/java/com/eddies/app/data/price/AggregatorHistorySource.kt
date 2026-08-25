package com.eddies.app.data.price

import com.eddies.app.data.db.entity.CandleInterval
import com.eddies.app.domain.PriceSourceId
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import java.time.Instant
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * History for coins no exchange lists, from CoinPaprika. No key.
 *
 * **The free tier is a rolling one year.** Asking for anything older is refused
 * with `{"error":"Getting daily historical data before ... is not allowed in
 * this plan"}`, so the start date is clamped rather than sent optimistically:
 * an unclamped request returns an error object where the caller expects an
 * array, and the chart ends up empty instead of merely short.
 *
 * That means long-tail coins get one year of history where an exchange-listed
 * coin gets two or more. The UI does not pretend otherwise; the chart simply
 * starts where the data does.
 */
@Singleton
class AggregatorHistorySource @Inject constructor(
    private val http: HttpClient,
    private val json: Json,
) : HistorySource {

    override val id = PriceSourceId.COINPAPRIKA

    override suspend fun fetch(
        assetId: String,
        ticker: String,
        interval: CandleInterval,
        currency: String,
        since: Long?,
    ): CandleSeries? = runCatching {
        val slug = assetId.substringAfter(':')
        val earliest = Instant.now().minus(FREE_TIER_DAYS, ChronoUnit.DAYS)
        val requested = since?.let { Instant.ofEpochMilli(it) } ?: earliest
        val start = if (requested.isBefore(earliest)) earliest else requested

        val body = http.get("https://api.coinpaprika.com/v1/tickers/$slug/historical") {
            parameter("start", start.toString().substringBefore('T'))
            parameter("interval", if (interval == CandleInterval.DAY) "1d" else "1h")
            parameter("quote", currency.uppercase())
        }.bodyAsText()

        // An error comes back as an object, not an array. Treating it as a
        // parse failure rather than an exception keeps the fallback ladder moving.
        val rows = json.parseToJsonElement(body) as? JsonArray ?: return null

        val candles = rows.mapNotNull { element ->
            val row = element as? JsonObject ?: return@mapNotNull null
            val ts = row["timestamp"].asStringOrNull()
                ?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
                ?: return@mapNotNull null
            val price = row["price"].asBigDecimal() ?: return@mapNotNull null
            Candle(timestamp = ts, close = price)
        }
        if (candles.isEmpty()) return null

        CandleSeries(assetId, interval, currency.uppercase(), PriceSourceId.COINPAPRIKA, candles)
    }.getOrNull()

    private companion object {
        /** Verified against the live endpoint on 2026-08-25. */
        const val FREE_TIER_DAYS = 364L
    }
}
