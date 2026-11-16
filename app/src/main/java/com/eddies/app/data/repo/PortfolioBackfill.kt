package com.eddies.app.data.repo

import com.eddies.app.data.db.dao.CorporateActionDao
import com.eddies.app.data.db.dao.PortfolioSnapshotDao
import com.eddies.app.data.db.entity.CandleInterval
import com.eddies.app.data.db.entity.PortfolioSnapshotEntity
import com.eddies.app.data.prefs.SettingsDataStore
import com.eddies.app.data.price.FxRepository
import com.eddies.app.data.price.PriceHistoryRepository
import com.eddies.app.domain.PortfolioHistory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Rebuilds the portfolio value chart from the ledger and cached daily closes.
 *
 * The arithmetic is [PortfolioHistory], which is pure and tested. This class
 * only gathers its inputs and stores what it returns.
 *
 * The history is derived, never recorded. It is replayed in full whenever the
 * ledger, the splits or the settings it depends on change, so a transaction
 * added with last year's date moves last year's line, and a deleted one takes
 * its days off the chart again. Nothing is lost by throwing the rows away: the
 * closes they are computed from stay cached for good.
 *
 * It stops at yesterday. Today has no close yet, and the screen draws today's
 * point from the live total instead, so the end of the line is always the
 * number at the top of the screen.
 */
@Singleton
class PortfolioBackfill @Inject constructor(
    private val transactions: TransactionRepository,
    private val history: PriceHistoryRepository,
    private val snapshotDao: PortfolioSnapshotDao,
    private val settings: SettingsDataStore,
    private val corporateActions: CorporateActionDao,
    private val fx: FxRepository,
) {
    /** One replay at a time: the worker and the screen can both ask for one. */
    private val running = Mutex()

    /** Returns how many rows the rebuilt history holds. */
    suspend fun run(): Int = running.withLock {
        withContext(Dispatchers.IO) {
            val txs = transactions.all()
            if (txs.isEmpty()) {
                snapshotDao.deleteAll()
                return@withContext 0
            }

            val cfg = settings.current()
            val assetIds = txs.map { it.assetId }.distinct()

            // The same cache the asset chart uses, so anything already viewed
            // costs a small delta, and nothing at all when it was fetched
            // recently. Every held asset, not just the ones somebody opened: a
            // total built from part of the portfolio is quietly missing the rest.
            for (assetId in assetIds) {
                runCatching { history.ensureFresh(assetId, CandleInterval.DAY) }
            }

            val firstTx = txs.minOf { it.timestamp }
            val zone = ZoneId.systemDefault()
            val days = PortfolioHistory.build(
                transactions = txs,
                // From a little before the first purchase, so one made at a
                // weekend has the preceding close to be valued at.
                closes = history.dailyCloses(assetIds, firstTx - LOOKBACK_MS),
                baseCurrency = cfg.baseCurrency,
                lastDay = LocalDate.now(zone).minusDays(1),
                zone = zone,
                method = cfg.costBasisMethod,
                fx = fx.historicalTable(),
                valueFx = fx.chartTable(),
                includeFeesInBasis = cfg.includeFeesInBasis,
                splitsByAsset = corporateActions.all()
                    .groupBy { it.assetId }
                    .mapValues { (_, rows) -> rows.map { it.toDomain() } },
            )

            val rows = days.flatMap { day ->
                day.perClass.map { (assetClass, totals) ->
                    PortfolioSnapshotEntity(
                        day = day.day.toString(),
                        assetClass = assetClass,
                        totalValue = totals.value.toPlainString(),
                        costBasis = totals.costBasis.toPlainString(),
                        currency = cfg.baseCurrency,
                    )
                }
            }
            snapshotDao.replaceAll(rows)
            rows.size
        }
    }

    private companion object {
        const val LOOKBACK_MS = 14 * 86_400_000L
    }
}
