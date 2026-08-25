package com.eddies.app.data.price

import com.eddies.app.data.db.entity.CandleInterval
import com.eddies.app.domain.KrakenSymbols
import com.eddies.app.domain.PriceSourceId
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Daily and hourly candles from Kraken's public OHLC endpoint. No key.
 *
 * **This endpoint speaks REST v1, not the v2 socket dialect.** It wants the
 * altname `XBTEUR` and keys the reply `XXBTZEUR`; sending the socket's `BTC/EUR`
 * gets an "Unknown asset pair" error. That is the same trap as `wsname` in the
 * other direction, which is why `KrakenSymbols` carries both mappings and this
 * class reads the result by taking whatever single key comes back rather than
 * guessing at the spelling.
 *
 * Returns at most 720 candles, so roughly two years of daily data.
 */
@Singleton
class KrakenHistorySource @Inject constructor(
    private val http: HttpClient,
    private val json: Json,
) : HistorySource {

    override val id = PriceSourceId.KRAKEN

    override suspend fun fetch(
        assetId: String,
        ticker: String,
        interval: CandleInterval,
        currency: String,
        since: Long?,
    ): CandleSeries? = runCatching {
        val pair = KrakenSymbols.restPair(ticker, currency)
        val body = http.get("https://api.kraken.com/0/public/OHLC") {
            parameter("pair", pair)
            parameter("interval", interval.minutes.toString())
            // Kraken takes seconds, not millis, and treats `since` as exclusive.
            since?.let { parameter("since", (it / 1000).toString()) }
        }.bodyAsText()

        val root = json.parseToJsonElement(body).jsonObject
        val errors = root["error"] as? JsonArray
        if (errors != null && errors.isNotEmpty()) return null

        val result = root["result"]?.jsonObject ?: return null
        // The reply is keyed by Kraken's own spelling of the pair, which is not
        // what we asked with. Take the one array-valued entry instead of
        // reconstructing the name.
        val series = result.entries.firstOrNull { it.value is JsonArray }?.value?.jsonArray ?: return null

        val candles = series.mapNotNull { element ->
            val row = element as? JsonArray ?: return@mapNotNull null
            // [time, open, high, low, close, vwap, volume, count]
            val time = row.getOrNull(0).asStringOrNull()?.toLongOrNull() ?: return@mapNotNull null
            val close = row.getOrNull(4).asBigDecimal() ?: return@mapNotNull null
            Candle(
                timestamp = time * 1000,
                close = close,
                high = row.getOrNull(2).asBigDecimal(),
                low = row.getOrNull(3).asBigDecimal(),
            )
        }
        if (candles.isEmpty()) return null

        CandleSeries(assetId, interval, currency.uppercase(), PriceSourceId.KRAKEN, candles)
    }.getOrNull()
}
