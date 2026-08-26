package com.eddies.app.data.repo

import com.eddies.app.data.db.dao.AssetDao
import com.eddies.app.data.db.dao.PortfolioSnapshotDao
import com.eddies.app.data.db.entity.PortfolioSnapshotEntity
import com.eddies.app.data.prefs.SettingsDataStore
import com.eddies.app.data.price.FxRepository
import com.eddies.app.data.price.PriceRepository
import com.eddies.app.data.staking.StakingRepository
import com.eddies.app.data.staking.StakingTotals
import com.eddies.app.domain.FxTable
import com.eddies.app.domain.PortfolioBuilder
import com.eddies.app.domain.PortfolioScope
import com.eddies.app.domain.PortfolioSummary
import com.eddies.app.domain.Transaction
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

/** One day of total portfolio value, for the value chart. */
data class PortfolioPoint(val day: String, val value: BigDecimal, val costBasis: BigDecimal)

/**
 * The priced portfolio, recombined whenever the ledger, the prices, the FX
 * table or the relevant settings change.
 *
 * The arithmetic itself lives in PortfolioBuilder, which is pure and tested.
 * This class only wires flows to it and moves the work off the main thread.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class PortfolioRepository @Inject constructor(
    private val transactions: TransactionRepository,
    private val assetDao: AssetDao,
    private val prices: PriceRepository,
    private val fx: FxRepository,
    private val settings: SettingsDataStore,
    private val snapshotDao: PortfolioSnapshotDao,
    private val staking: StakingRepository,
    private val corporateActions: CorporateActionRepository,
) {

    // FX and staking are paired first because combine only has typed overloads
    // up to five flows; a sixth silently falls back to the vararg form and every
    // parameter arrives as Any.
    private data class Context(
        val fx: FxTable,
        val staking: StakingTotals,
        val splits: Map<String, List<com.eddies.app.domain.SplitEvent>>,
    )

    private val context: Flow<Context> =
        combine(fx.historicalTableFlow(), staking.totals, corporateActions.splitsByAsset) { table, totals, splits ->
            Context(table, totals, splits)
        }

    val summary: Flow<PortfolioSummary> = combine(
        transactions.observeAll(),
        prices.prices,
        settings.settings,
        assetDao.observeAll(),
        context,
    ) { txs, priceMap, cfg, assetRows, ctx ->
        val assets = assetRows.associate { it.id to it.toDomain() }
        PortfolioBuilder.build(
            transactions = txs,
            assets = assets,
            prices = priceMap,
            currency = cfg.baseCurrency,
            method = cfg.costBasisMethod,
            fx = ctx.fx,
            includeFeesInBasis = cfg.includeFeesInBasis,
            stakingPending = ctx.staking.pendingByAsset,
            // Without these a share count is wrong for anything that has ever
            // split, on the live screen as well as in the replayed history.
            splitsByAsset = ctx.splits,
        )
    }.flowOn(kotlinx.coroutines.Dispatchers.Default)

    /**
     * Daily totals for a scope. Rows are summed per day rather than taken
     * directly, because a day holds one row per class and the combined chart is
     * their sum.
     */
    fun history(days: Int, scope: PortfolioScope = PortfolioScope.ALL): Flow<List<PortfolioPoint>> {
        val from = dayOf(System.currentTimeMillis() - days * 86_400_000L)
        val source = scope.assetClass
            ?.let { snapshotDao.observeFrom(from, it) }
            ?: snapshotDao.observeFrom(from)
        return source.map { rows ->
            rows.groupBy { it.day }
                .toSortedMap()
                .map { (day, dayRows) ->
                    PortfolioPoint(
                        day = day,
                        value = dayRows.sumOf { runCatching { BigDecimal(it.totalValue) }.getOrDefault(BigDecimal.ZERO) },
                        costBasis = dayRows.sumOf { runCatching { BigDecimal(it.costBasis) }.getOrDefault(BigDecimal.ZERO) },
                    )
                }
        }
    }

    /**
     * Records today's total. Called by the daily worker and after any edit.
     *
     * The value chart cannot be derived from the ledger alone: that needs a
     * historical price for every held asset back to the first purchase. One row
     * per day going forward is exact, costs nothing, and keeps working when a
     * price API changes or disappears.
     */
    suspend fun snapshotToday(summaryNow: PortfolioSummary) {
        val day = dayOf(System.currentTimeMillis())
        // One row per class. The combined chart sums them, so the parts and the
        // whole are the same numbers and cannot drift apart.
        val rows = summaryNow.perClass().map { (assetClass, totals) ->
            PortfolioSnapshotEntity(
                day = day,
                assetClass = assetClass,
                totalValue = totals.value.toPlainString(),
                costBasis = totals.costBasis.toPlainString(),
                currency = summaryNow.currency,
            )
        }
        if (rows.isEmpty()) return
        snapshotDao.upsert(rows)
        settings.setLastSnapshotDay(day)
    }

    suspend fun snapshotCount(): Int = snapshotDao.count()

    private fun dayOf(epochMs: Long): String =
        DAY_FORMAT.format(Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()))

    private companion object {
        val DAY_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    }
}
