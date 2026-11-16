package com.eddies.app.data.repo

import com.eddies.app.data.db.dao.CorporateActionDao
import com.eddies.app.data.db.entity.CandleInterval
import com.eddies.app.data.prefs.SettingsDataStore
import com.eddies.app.data.price.FxRepository
import com.eddies.app.data.price.PriceHistoryRepository
import com.eddies.app.domain.HistoryPoint
import com.eddies.app.domain.PortfolioHistory
import com.eddies.app.domain.TimedClose
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.time.ZoneOffset
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The value chart by the hour, for the 1D and 1W ranges.
 *
 * The daily chart has one point per day, so a day was a single dot and a week
 * was seven. This replays the same ledger against hourly candles instead.
 *
 * Worked out on request and never stored, unlike the daily history: it is a
 * small sum over a cache that is already there, and it would otherwise need
 * replacing every hour.
 *
 * The arithmetic is [PortfolioHistory.buildIntraday]. This class only gathers
 * its inputs.
 */
@Singleton
class PortfolioIntraday @Inject constructor(
    private val transactions: TransactionRepository,
    private val history: PriceHistoryRepository,
    private val settings: SettingsDataStore,
    private val corporateActions: CorporateActionDao,
    private val fx: FxRepository,
) {
    /** One point per hour over the last [days] days, ending at the last full hour. */
    suspend fun points(days: Int): List<HistoryPoint> = withContext(Dispatchers.IO) {
        val txs = transactions.all()
        if (txs.isEmpty()) return@withContext emptyList()

        val cfg = settings.current()
        val assetIds = txs.map { it.assetId }.distinct()

        // Side by side: these are independent requests to a cache that is
        // usually warm, and one slow asset should not hold up the rest.
        coroutineScope {
            assetIds.map { id ->
                async { runCatching { history.ensureFresh(id, CandleInterval.HOUR) } }
            }.awaitAll()
        }

        val now = System.currentTimeMillis()
        val to = now - now % HOUR_MS
        val from = to - days * DAY_MS

        // Daily closes from before the window, so every asset starts the line
        // at a real price. A share bought on Friday has no hourly candle on a
        // Sunday morning, and a coin on a source with no hourly history has
        // none at all; both are carried at their last daily close.
        val firstDayInWindow = java.time.Instant.ofEpochMilli(from).atZone(ZoneOffset.UTC).toLocalDate()
        val seeds = history.dailyCloses(assetIds, from - SEED_LOOKBACK_MS).mapValues { (_, rows) ->
            rows.filter { it.day.isBefore(firstDayInWindow) }.map {
                TimedClose(
                    at = it.day.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
                    close = it.close,
                    currency = it.currency,
                )
            }
        }
        val hourly = history.hourlyCloses(assetIds, from)

        PortfolioHistory.buildIntraday(
            transactions = txs,
            closes = assetIds.associateWith { seeds[it].orEmpty() + hourly[it].orEmpty() },
            baseCurrency = cfg.baseCurrency,
            from = from,
            to = to,
            stepMs = HOUR_MS,
            method = cfg.costBasisMethod,
            fx = fx.historicalTable(),
            valueFx = fx.chartTable(),
            includeFeesInBasis = cfg.includeFeesInBasis,
            splitsByAsset = corporateActions.all()
                .groupBy { it.assetId }
                .mapValues { (_, rows) -> rows.map { it.toDomain() } },
        )
    }

    private companion object {
        const val HOUR_MS = 60 * 60 * 1000L
        const val DAY_MS = 24 * HOUR_MS
        const val SEED_LOOKBACK_MS = 14 * DAY_MS
    }
}
