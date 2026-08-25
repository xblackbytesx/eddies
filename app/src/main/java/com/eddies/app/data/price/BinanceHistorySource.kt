package com.eddies.app.data.price

import com.eddies.app.data.db.entity.CandleInterval
import com.eddies.app.domain.PriceSourceId
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Candles from Binance's public klines endpoint. No key.
 *
 * Up to 1000 candles, so roughly 2.7 years of daily data, the deepest of the
 * three free sources. Values arrive as strings, so nothing is routed through a
 * Double.
 *
 * Quoted in USDT rather than fiat, so the caller converts. The currency on the
 * returned series is the real one, never the requested one, or the chart would
 * silently plot dollars against a euro axis.
 */
@Singleton
class BinanceHistorySource @Inject constructor(
    private val http: HttpClient,
    private val json: Json,
) : HistorySource {

    override val id = PriceSourceId.BINANCE

    override suspend fun fetch(
        assetId: String,
        ticker: String,
        interval: CandleInterval,
        currency: String,
        since: Long?,
    ): CandleSeries? = runCatching {
        val quote = "USDT"
        val body = http.get("https://api.binance.com/api/v3/klines") {
            parameter("symbol", "${ticker.uppercase()}$quote")
            parameter("interval", if (interval == CandleInterval.DAY) "1d" else "1h")
            parameter("limit", "1000")
            since?.let { parameter("startTime", (it + 1).toString()) }
        }.bodyAsText()

        val rows = json.parseToJsonElement(body) as? JsonArray ?: return null

        val candles = rows.mapNotNull { element ->
            val row = element as? JsonArray ?: return@mapNotNull null
            // [openTime, open, high, low, close, volume, closeTime, ...]
            val time = row.getOrNull(0).asStringOrNull()?.toLongOrNull() ?: return@mapNotNull null
            val close = row.getOrNull(4).asBigDecimal() ?: return@mapNotNull null
            Candle(
                timestamp = time,
                close = close,
                high = row.getOrNull(2).asBigDecimal(),
                low = row.getOrNull(3).asBigDecimal(),
            )
        }
        if (candles.isEmpty()) return null

        CandleSeries(assetId, interval, quote, PriceSourceId.BINANCE, candles)
    }.getOrNull()
}
