package com.eddies.app.data.price

import android.content.Context
import androidx.core.content.edit
import com.eddies.app.data.db.dao.AssetDao
import com.eddies.app.data.db.dao.PriceDao
import com.eddies.app.data.db.entity.CandleInterval
import com.eddies.app.data.db.entity.PriceCandleEntity
import com.eddies.app.data.prefs.RealtimeFeed
import com.eddies.app.data.prefs.SettingsDataStore
import com.eddies.app.domain.AssetClass
import com.eddies.app.data.stocks.TradegateSource
import com.eddies.app.domain.AssetIds
import com.eddies.app.domain.DailyClose
import com.eddies.app.domain.PriceSourceId
import com.eddies.app.domain.QuoteCurrency
import com.eddies.app.domain.TimedClose
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneOffset
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
    @param:ApplicationContext private val context: Context,
    private val priceDao: PriceDao,
    private val assetDao: AssetDao,
    private val sourceRefDao: com.eddies.app.data.db.dao.AssetSourceRefDao,
    private val settings: SettingsDataStore,
    private val kraken: KrakenHistorySource,
    private val binance: BinanceHistorySource,
    private val aggregator: AggregatorHistorySource,
    private val stocks: com.eddies.app.data.stocks.StockHistorySource,
) {
    /**
     * When each series was last fetched, kept outside the database.
     *
     * Freshness used to be judged by the newest candle's own timestamp. A daily
     * candle is stamped with the start of its day, so by mid-morning every
     * series looked stale and every chart opened, and every share at a weekend,
     * cost a request. What matters is when we last asked, and a cache's
     * bookkeeping does not deserve a schema migration.
     */
    private val fetchLog by lazy { context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE) }

    /** A failed fetch is not retried on every screen open. Not persisted: a restart may try again. */
    private val failedAt = HashMap<String, Long>()

    private val cacheChecked = Mutex()
    @Volatile private var cacheCurrent = false

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
        dropOutdatedCache()
        val key = "$assetId:${interval.name}"
        val lock = inFlightGuard.withLock { inFlight.getOrPut(key) { Mutex() } }
        lock.withLock {
            val now = System.currentTimeMillis()
            val newest = priceDao.newestCandle(assetId, interval)
            if (newest != null && now - fetchLog.getLong(key, 0L) < staleAfterMs) return@withLock
            if (now - (failedAt[key] ?: 0L) < RETRY_AFTER_MS) return@withLock

            // Resume a little before the newest candle rather than after it.
            // The newest one was usually fetched while its day was still
            // running, so it holds a price from mid-session, not the close, and
            // a delta that starts after it would never correct that.
            val since = newest?.let { it - 2 * interval.minutes * 60_000L }
            if (fetchInto(assetId, interval, since)) {
                fetchLog.edit { putLong(key, now) }
                failedAt.remove(key)
            } else {
                failedAt[key] = now
            }
        }
    }

    /**
     * Empties the candle cache once when the way candles are stored changes.
     *
     * Candles used to be converted to the base currency as they were stored, at
     * that day's rate, and London shares were stored in pounds at their pence
     * value. Rows written that way cannot be told from correct ones, and it is
     * a cache: refetching costs one request per asset.
     */
    private suspend fun dropOutdatedCache() {
        if (cacheCurrent) return
        cacheChecked.withLock {
            if (cacheCurrent) return
            if (fetchLog.getInt(KEY_CACHE_VERSION, 0) < CACHE_VERSION) {
                priceDao.clearAllCandles()
                fetchLog.edit {
                    clear()
                    putInt(KEY_CACHE_VERSION, CACHE_VERSION)
                }
            }
            cacheCurrent = true
        }
    }

    /**
     * The fallback ladder, same order as live prices: the exchange the user
     * chose, then the aggregator for anything it does not list.
     */
    private suspend fun fetchInto(assetId: String, interval: CandleInterval, since: Long?): Boolean {
        val asset = assetDao.byId(assetId) ?: return false
        val cfg = settings.current()

        // Tradegate serves a live snapshot and no history at all, so a chart for
        // a Tradegate holding is drawn from the same instrument's Yahoo listing,
        // resolved by ISIN when the asset was added and kept in
        // asset_source_refs. Same security, a different venue's prints.
        val ticker = if (AssetIds.exchangeOf(assetId) == TradegateSource.EXCHANGE) {
            sourceRefDao.forAssets(listOf(assetId))
                .firstOrNull { it.source == PriceSourceId.YAHOO }
                ?.sourceSymbol
                ?: return false
        } else {
            asset.symbol
        }

        // A share has exactly one source; a coin has a ladder of three.
        val ordered = if (AssetIds.classOf(assetId) == AssetClass.STOCK) {
            listOf(stocks)
        } else when (cfg.realtimeFeed) {
            RealtimeFeed.BINANCE -> listOf(binance, kraken, aggregator)
            RealtimeFeed.KRAKEN, RealtimeFeed.OFF -> listOf(kraken, binance, aggregator)
        }

        for (source in ordered) {
            val series = source.fetch(assetId, ticker, interval, cfg.baseCurrency, since) ?: continue
            store(series)
            return true
        }
        return false
    }

    /**
     * Stores candles in the currency the source quoted, not the user's.
     *
     * They used to be converted on the way in, at the rate of the day they were
     * fetched. That made a two year chart of a dollar share move with one
     * day's exchange rate, fell back to a rate of 1.0 when none was loaded yet,
     * and left the whole cache in the wrong currency the moment the base
     * currency changed. Kept as quoted, a candle is simply true, and whoever
     * reads it converts at that candle's own date.
     */
    private suspend fun store(series: CandleSeries) {
        val currency = QuoteCurrency.fiatOf(series.currency)
        priceDao.upsertCandles(
            series.candles.map { candle ->
                PriceCandleEntity(
                    assetId = series.assetId,
                    interval = series.interval,
                    timestamp = candle.timestamp,
                    close = candle.close.toPlainString(),
                    high = candle.high?.toPlainString(),
                    low = candle.low?.toPlainString(),
                    currency = currency,
                    source = series.source,
                )
            },
        )
    }

    /**
     * Daily closes for several assets at once, for the portfolio value chart.
     *
     * Each close is filed under its UTC date, which is the day an exchange's
     * daily candle covers, and keeps the currency it was quoted in.
     */
    suspend fun dailyCloses(assetIds: List<String>, since: Long): Map<String, List<DailyClose>> =
        withContext(Dispatchers.IO) {
            if (assetIds.isEmpty()) return@withContext emptyMap()
            priceDao.dailyForAssets(assetIds, since).groupBy { it.assetId }.mapValues { (_, rows) ->
                rows.mapNotNull { row ->
                    val close = runCatching { BigDecimal(row.close) }.getOrNull() ?: return@mapNotNull null
                    DailyClose(
                        day = Instant.ofEpochMilli(row.timestamp).atZone(ZoneOffset.UTC).toLocalDate(),
                        close = close,
                        currency = row.currency,
                    )
                }
            }
        }

    /**
     * Hourly closes for several assets at once, for the intraday value chart.
     *
     * A candle is stamped with the start of its hour and its close is the price
     * at the end of it, so each is filed an hour later than its timestamp.
     * Filed at the start, every point on the chart would show a price from up
     * to an hour in its own future.
     */
    suspend fun hourlyCloses(assetIds: List<String>, since: Long): Map<String, List<TimedClose>> =
        withContext(Dispatchers.IO) {
            if (assetIds.isEmpty()) return@withContext emptyMap()
            priceDao.hourlyForAssets(assetIds, since - HOUR_MS).groupBy { it.assetId }.mapValues { (_, rows) ->
                rows.mapNotNull { row ->
                    val close = runCatching { BigDecimal(row.close) }.getOrNull() ?: return@mapNotNull null
                    TimedClose(at = row.timestamp + HOUR_MS, close = close, currency = row.currency)
                }
            }
        }

    private fun defaultStaleness(interval: CandleInterval): Long = when (interval) {
        CandleInterval.DAY -> 6 * 60 * 60 * 1000L
        CandleInterval.HOUR -> 15 * 60 * 1000L
    }

    private companion object {
        const val PREF_FILE = "eddies_history_cache"
        const val KEY_CACHE_VERSION = "candle_cache_version"

        /** Bump when stored candles stop meaning what they used to. */
        const val CACHE_VERSION = 2

        const val RETRY_AFTER_MS = 15 * 60 * 1000L
        const val HOUR_MS = 60 * 60 * 1000L
    }
}
