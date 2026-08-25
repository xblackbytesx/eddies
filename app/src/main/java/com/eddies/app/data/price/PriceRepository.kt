package com.eddies.app.data.price

import com.eddies.app.data.db.dao.AssetDao
import com.eddies.app.data.db.dao.PriceDao
import com.eddies.app.data.db.dao.TransactionDao
import com.eddies.app.data.db.dao.WatchlistDao
import com.eddies.app.data.db.entity.PriceLatestEntity
import com.eddies.app.data.prefs.RealtimeFeed
import com.eddies.app.data.prefs.SettingsDataStore
import com.eddies.app.domain.PriceSourceId
import com.eddies.app.domain.PriceTick
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import java.math.BigDecimal
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The single place prices come from.
 *
 * Merges a realtime socket and a REST poller into one map keyed by asset id, and
 * applies the fallback ladder: the selected exchange first, the aggregator for
 * anything it does not quote, and the last stored snapshot (marked stale) for
 * anything neither can price.
 *
 * Lifecycle is handled by WhileSubscribed rather than a lifecycle observer. When
 * no screen is collecting, the subscriber count drops to zero, the sockets close
 * five seconds later, and they reopen on return. There is no pause/resume code
 * to get wrong, and a backgrounded app holds no connection.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class PriceRepository @Inject constructor(
    private val transactionDao: TransactionDao,
    private val watchlistDao: WatchlistDao,
    private val assetDao: AssetDao,
    private val priceDao: PriceDao,
    private val settings: SettingsDataStore,
    private val kraken: KrakenWsSource,
    private val binance: BinanceWsSource,
    private val aggregator: AggregatorSource,
) {
    private val scope = CoroutineScope(SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)

    /** Assets worth pricing: everything held, plus the watchlist. */
    private val trackedAssetIds: Flow<Set<String>> =
        combine(
            transactionDao.observeHeldAssetIds(),
            watchlistDao.observeAll().map { list -> list.map { it.assetId } },
        ) { held, watched -> (held + watched).toSet() }
            .distinctUntilChanged()

    val prices: Flow<Map<String, PriceTick>> =
        combine(
            trackedAssetIds,
            settings.settings.map { it.realtimeFeed to it.baseCurrency }.distinctUntilChanged(),
        ) { ids, (feed, base) -> Triple(ids, feed, base) }
            .flatMapLatest { (ids, feed, base) -> pricesFor(ids, feed, base) }
            .stateIn(
                scope = scope,
                started = SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000),
                initialValue = emptyMap(),
            )

    private fun pricesFor(
        assetIds: Set<String>,
        feed: RealtimeFeed,
        baseCurrency: String,
    ): Flow<Map<String, PriceTick>> = channelFlow {
        if (assetIds.isEmpty()) {
            // An empty portfolio opens no socket at all.
            send(emptyMap())
            awaitClose { }
            return@channelFlow
        }

        val latest = LinkedHashMap<String, PriceTick>()

        // Seed from the last stored snapshot so a cold start shows numbers
        // immediately, flagged stale until something live replaces them.
        priceDao.allLatest()
            .filter { it.assetId in assetIds }
            .forEach { row ->
                runCatching { BigDecimal(row.price) }.getOrNull()?.let { price ->
                    latest[row.assetId] = PriceTick(
                        assetId = row.assetId,
                        price = price,
                        currency = row.currency,
                        at = row.timestamp,
                        source = row.source,
                        stale = true,
                    )
                }
            }
        send(latest.toMap())

        val tickers = assetDao.byIds(assetIds.toList())
            .associate { it.id to it.symbol }

        val realtime = when (feed) {
            RealtimeFeed.KRAKEN -> kraken
            RealtimeFeed.BINANCE -> binance
            RealtimeFeed.OFF -> null
        }

        val liveSymbols = realtime?.resolve(tickers, baseCurrency).orEmpty()
        // Whatever the exchange cannot quote falls to the aggregator. This is the
        // long tail: most coins are not listed on any single exchange.
        val remaining = tickers.filterKeys { it !in liveSymbols.keys }
        val pollSymbols = aggregator.resolve(remaining, baseCurrency)

        val emitMutex = kotlinx.coroutines.sync.Mutex()
        suspend fun publish(tick: PriceTick) {
            emitMutex.withLock {
                latest[tick.assetId] = tick
                send(latest.toMap())
            }
        }

        if (realtime != null && liveSymbols.isNotEmpty()) {
            launch {
                realtime.stream(liveSymbols).collect { tick ->
                    publish(tick)
                    persist(tick)
                }
            }
        }
        if (pollSymbols.isNotEmpty()) {
            launch {
                aggregator.stream(pollSymbols).collect { tick ->
                    publish(tick)
                    persist(tick)
                }
            }
        }
        awaitClose { }
    }

    /**
     * Records the latest price as a single upserted row per asset.
     *
     * Deliberately not append-only. Kraken's ticker fires on every trade, so an
     * insert per tick is tens of thousands of rows a day for one liquid pair,
     * plus a database write per tick on a phone. Chart series live in
     * price_candles and are written by PriceHistoryRepository instead.
     */
    private suspend fun persist(tick: PriceTick) {
        priceDao.upsertLatest(
            listOf(
                PriceLatestEntity(
                    assetId = tick.assetId,
                    timestamp = tick.at,
                    price = tick.price.toPlainString(),
                    currency = tick.currency,
                    source = tick.source,
                ),
            ),
        )
    }

    /**
     * Drops hourly candles past the retention window, called from the daily
     * worker. Daily candles are the long-term series and are never pruned: they
     * are what the 1Y and All ranges draw, and refetching them costs a request
     * per asset.
     */
    suspend fun prune(hourlyRetentionDays: Int = 60) {
        priceDao.pruneHourly(System.currentTimeMillis() - hourlyRetentionDays * 24L * 60 * 60 * 1000)
    }
}
