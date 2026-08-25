package com.eddies.app.data.price

import com.eddies.app.data.db.dao.AssetDao
import com.eddies.app.data.db.dao.PriceDao
import com.eddies.app.data.db.entity.CandleInterval
import com.eddies.app.data.db.entity.PriceCandleEntity
import com.eddies.app.data.prefs.RealtimeFeed
import com.eddies.app.data.prefs.SettingsDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.math.BigDecimal
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Historical prices, fetched lazily and cached.
 *
 * The access pattern is on-demand: opening Bitcoin fetches Bitcoin's history and
 * nothing else. Nothing is prefetched for the several hundred coins in the seed,
 * because almost none of them will ever be looked at.
 *
 * After the first fetch, a refresh sends the newest cached timestamp as a delta
 * hint and gets back only what has happened since. Measured against Kraken:
 * about 61 KB for a full two-year pull, about 640 bytes for a seven-day delta.
 */
@Singleton
class PriceHistoryRepository @Inject constructor(
    private val priceDao: PriceDao,
    private val assetDao: AssetDao,
    private val settings: SettingsDataStore,
    private val kraken: KrakenHistorySource,
    private val binance: BinanceHistorySource,
    private val aggregator: AggregatorHistorySource,
    private val fx: FxRepository,
) {
    /**
     * One in-flight fetch per (asset, interval).
     *
     * Without it, opening a screen that shows a chart while the backfill is
     * already working on the same asset fires two identical requests and both
     * write the same rows.
     */
    private val inFlight = HashMap<String, Mutex>()
    private val inFlightGuard = Mutex()

    /** Cached candles, whatever is on disk right now. Never hits the network. */
    fun observe(assetId: String, interval: CandleInterval, since: Long) =
        priceDao.observeCandles(assetId, interval, since)

    /**
     * Makes sure the cache covers [interval] for this asset, fetching only the
     * delta when something is already stored.
     *
     * [staleAfterMs] stops a chart that is opened repeatedly from making a
     * request every time. A daily candle changes at most once a day.
     */
    suspend fun ensureFresh(
        assetId: String,
        interval: CandleInterval,
        staleAfterMs: Long = defaultStaleness(interval),
    ) = withContext(Dispatchers.IO) {
        val key = "$assetId:${interval.name}"
        val lock = inFlightGuard.withLock { inFlight.getOrPut(key) { Mutex() } }
        lock.withLock {
            val newest = priceDao.newestCandle(assetId, interval)
            if (newest != null && System.currentTimeMillis() - newest < staleAfterMs) return@withLock
            fetchInto(assetId, interval, newest)
        }
    }

    /**
     * The fallback ladder, same order as live prices: the exchange the user
     * chose, then the aggregator for anything it does not list.
     */
    private suspend fun fetchInto(assetId: String, interval: CandleInterval, since: Long?) {
        val asset = assetDao.byId(assetId) ?: return
        val cfg = settings.current()
        val ticker = asset.symbol

        val ordered = when (cfg.realtimeFeed) {
            RealtimeFeed.BINANCE -> listOf(binance, kraken, aggregator)
            RealtimeFeed.KRAKEN, RealtimeFeed.OFF -> listOf(kraken, binance, aggregator)
        }

        for (source in ordered) {
            val series = source.fetch(assetId, ticker, interval, cfg.baseCurrency, since) ?: continue
            store(series, cfg.baseCurrency)
            return
        }
    }

    /**
     * Stores candles in the user's currency.
     *
     * A source that quotes something else (Binance prices in USDT) is converted
     * here with today's rate rather than each candle's historical rate. That is
     * a deliberate approximation for a chart: applying a per-day FX rate would
     * need a per-day rate going back two years, which Frankfurter has but which
     * would turn one request into hundreds. Cost basis, where it actually
     * matters, uses the historical table instead.
     */
    private suspend fun store(series: CandleSeries, targetCurrency: String) {
        val rate = if (series.currency.equals(targetCurrency, ignoreCase = true)) {
            BigDecimal.ONE
        } else {
            // USDT is not a currency Frankfurter knows; treat it as USD, which is
            // what it tracks to within a fraction of a percent.
            val from = if (series.currency.equals("USDT", true)) "USD" else series.currency
            fx.converter1(from, targetCurrency) ?: BigDecimal.ONE
        }

        priceDao.upsertCandles(
            series.candles.map { candle ->
                PriceCandleEntity(
                    assetId = series.assetId,
                    interval = series.interval,
                    timestamp = candle.timestamp,
                    close = (candle.close * rate).toPlainString(),
                    high = candle.high?.let { (it * rate).toPlainString() },
                    low = candle.low?.let { (it * rate).toPlainString() },
                    currency = targetCurrency.uppercase(),
                    source = series.source,
                )
            },
        )
    }

    /** Daily closes for several assets at once, for the portfolio backfill. */
    suspend fun dailyCloses(assetIds: List<String>, since: Long): Map<String, List<PriceCandleEntity>> =
        withContext(Dispatchers.IO) {
            if (assetIds.isEmpty()) emptyMap()
            else priceDao.dailyForAssets(assetIds, since).groupBy { it.assetId }
        }

    suspend fun oldestCandle(assetId: String, interval: CandleInterval): Long? =
        priceDao.oldestCandle(assetId, interval)

    private fun defaultStaleness(interval: CandleInterval): Long = when (interval) {
        CandleInterval.DAY -> 6 * 60 * 60 * 1000L
        CandleInterval.HOUR -> 15 * 60 * 1000L
    }
}
