package com.eddies.app.data.price

import com.eddies.app.data.db.entity.CandleInterval
import com.eddies.app.domain.PriceSourceId
import java.math.BigDecimal

/** One historical candle, before it is stored. */
data class Candle(
    val timestamp: Long,
    val close: BigDecimal,
    val high: BigDecimal? = null,
    val low: BigDecimal? = null,
)

data class CandleSeries(
    val assetId: String,
    val interval: CandleInterval,
    val currency: String,
    val source: PriceSourceId,
    val candles: List<Candle>,
)

/**
 * Historical prices, for charts and for the portfolio backfill.
 *
 * Separate from [PriceSource] because the shapes have nothing in common: one is
 * a subscription that pushes forever, the other is a bounded request for a past
 * window. Every implementation is keyless.
 *
 * How far back each source goes, verified 2026-08-25:
 *   Kraken       720 candles at any interval, so about 2 years of daily
 *   Binance      1000 candles, about 2.7 years of daily
 *   CoinPaprika  a rolling 1 year on the free tier, then it refuses with
 *                "not allowed in this plan"
 */
interface HistorySource {
    val id: PriceSourceId

    /**
     * Candles for one asset, newest last.
     *
     * [since] is the delta hint: pass the newest timestamp already cached and
     * the source returns only what came after, which is a few hundred bytes
     * instead of tens of kilobytes. Null means fetch everything available.
     *
     * Returns null when this source cannot price the asset at all, so the
     * caller can fall through to the next one.
     */
    suspend fun fetch(
        assetId: String,
        ticker: String,
        interval: CandleInterval,
        currency: String,
        since: Long?,
    ): CandleSeries?
}
