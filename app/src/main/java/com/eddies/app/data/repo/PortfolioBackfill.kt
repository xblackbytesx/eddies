package com.eddies.app.data.repo

import com.eddies.app.data.db.entity.CandleInterval
import com.eddies.app.data.db.entity.PortfolioSnapshotEntity
import com.eddies.app.data.prefs.SettingsDataStore
import com.eddies.app.data.price.PriceHistoryRepository
import com.eddies.app.domain.AssetClass
import com.eddies.app.domain.AssetIds
import com.eddies.app.domain.CostBasisMethod
import com.eddies.app.domain.PositionCalculator
import com.eddies.app.domain.Transaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fills the portfolio value chart backwards, once.
 *
 * Why this exists on top of the on-view fetch: the value chart is a sum across
 * every held asset, so history for only the coins the user happened to open
 * would produce a total that is quietly missing the rest. A chart that is
 * confidently wrong is worse than one that is empty.
 *
 * The set is small. It is the coins actually held, not the several hundred in
 * the seed, and each is a single request of roughly 60 to 190 KB.
 *
 * It reuses the same candle cache as the chart, so a coin already viewed costs a
 * delta rather than a refetch.
 */
@Singleton
class PortfolioBackfill @Inject constructor(
    private val transactions: TransactionRepository,
    private val history: PriceHistoryRepository,
    private val snapshotDao: com.eddies.app.data.db.dao.PortfolioSnapshotDao,
    private val settings: SettingsDataStore,
    private val corporateActions: com.eddies.app.data.db.dao.CorporateActionDao,
) {

    /**
     * Replays the ledger against daily closes and writes one snapshot per day.
     *
     * Days before a given asset's history starts simply value that asset at
     * zero. That is honest for a chart: the alternative, carrying the oldest
     * known price backwards, invents a flat line that never happened.
     */
    suspend fun run(): Int = withContext(Dispatchers.IO) {
        val txs = transactions.all()
        if (txs.isEmpty()) return@withContext 0

        val cfg = settings.current()
        val assetIds = txs.map { it.assetId }.distinct()
        val firstTx = txs.minOf { it.timestamp }

        // Pull history for everything held, in the same cache the chart uses.
        for (assetId in assetIds) {
            runCatching { history.ensureFresh(assetId, CandleInterval.DAY) }
        }

        val splitsByAsset = corporateActions.all()
            .groupBy { it.assetId }
            .mapValues { (_, rows) -> rows.map { it.toDomain() } }

        val closes = history.dailyCloses(assetIds, firstTx)
        if (closes.isEmpty()) return@withContext 0

        // Per asset, a day-keyed close, so each simulated day is a map lookup.
        val byAssetDay: Map<String, Map<String, BigDecimal>> = closes.mapValues { (_, rows) ->
            rows.associate { row ->
                dayOf(row.timestamp) to (runCatching { BigDecimal(row.close) }.getOrDefault(BigDecimal.ZERO))
            }
        }

        val today = Instant.now()
        var day = Instant.ofEpochMilli(firstTx)
        val snapshots = ArrayList<PortfolioSnapshotEntity>()

        while (!day.isAfter(today)) {
            val key = dayOf(day.toEpochMilli())
            val cutoff = day.toEpochMilli() + DAY_MS - 1
            // Only what had actually happened by that day.
            val upToDay = txs.filter { it.timestamp <= cutoff }

            val perClassValue = HashMap<AssetClass, BigDecimal>()
            val perClassCost = HashMap<AssetClass, BigDecimal>()
            for ((assetId, assetTxs) in upToDay.groupBy(Transaction::assetId)) {
                val position = PositionCalculator.fold(
                    txs = assetTxs,
                    method = cfg.costBasisMethod,
                    baseCurrency = cfg.baseCurrency,
                    includeFeesInBasis = cfg.includeFeesInBasis,
                    assetId = assetId,
                    // Without these, a share count before an old split is wrong
                    // for every day of the replayed history.
                    splits = splitsByAsset[assetId].orEmpty(),
                )
                val assetClass = AssetIds.classOf(assetId) ?: AssetClass.CRYPTO
                perClassCost[assetClass] = (perClassCost[assetClass] ?: BigDecimal.ZERO) + position.costBasis
                val close = byAssetDay[assetId]?.get(key)
                if (close != null) {
                    perClassValue[assetClass] =
                        (perClassValue[assetClass] ?: BigDecimal.ZERO) + position.quantity * close
                }
            }

            for (assetClass in (perClassValue.keys + perClassCost.keys)) {
                snapshots += PortfolioSnapshotEntity(
                    day = key,
                    assetClass = assetClass,
                    totalValue = (perClassValue[assetClass] ?: BigDecimal.ZERO).toPlainString(),
                    costBasis = (perClassCost[assetClass] ?: BigDecimal.ZERO).toPlainString(),
                    currency = cfg.baseCurrency,
                )
            }
            day = day.plusMillis(DAY_MS)
        }

        if (snapshots.isNotEmpty()) snapshotDao.upsert(snapshots)
        snapshots.size
    }

    private fun dayOf(epochMs: Long): String =
        DAY_FORMAT.format(Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()))

    private companion object {
        const val DAY_MS = 86_400_000L
        val DAY_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    }
}
