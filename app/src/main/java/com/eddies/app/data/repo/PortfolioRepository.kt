package com.eddies.app.data.repo

import com.eddies.app.data.db.dao.AssetDao
import com.eddies.app.data.db.dao.PortfolioSnapshotDao
import com.eddies.app.data.prefs.SettingsDataStore
import com.eddies.app.data.price.FxRepository
import com.eddies.app.data.price.PriceRepository
import com.eddies.app.data.staking.StakingRepository
import com.eddies.app.data.staking.StakingTotals
import com.eddies.app.domain.FxTable
import com.eddies.app.domain.PortfolioBuilder
import com.eddies.app.domain.PortfolioScope
import com.eddies.app.domain.PortfolioSummary
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
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
     * Daily totals for a scope, up to yesterday. Rows are summed per day rather
     * than taken directly, because a day holds one row per class and the
     * combined chart is their sum.
     *
     * Only rows in the current base currency. Changing it leaves the old rows
     * behind until the history is replayed, and adding euros to dollars for
     * that moment would draw a chart in no currency at all.
     */
    fun history(days: Int, scope: PortfolioScope = PortfolioScope.ALL): Flow<List<PortfolioPoint>> {
        val from = dayOf(System.currentTimeMillis() - days * 86_400_000L)
        val source = scope.assetClass
            ?.let { snapshotDao.observeFrom(from, it) }
            ?: snapshotDao.observeFrom(from)
        return combine(
            source,
            settings.settings.map { it.baseCurrency }.distinctUntilChanged(),
        ) { rows, base ->
            rows.filter { it.currency == base }
                .groupBy { it.day }
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
     * Changes whenever the value chart needs replaying: the ledger, the splits,
     * or a setting the replay reads. Carries no data worth using, only the fact
     * that it changed.
     */
    val historyInputs: Flow<Any> = combine(
        transactions.observeAll(),
        corporateActions.splitsByAsset,
        settings.settings
            .map { Triple(it.baseCurrency, it.costBasisMethod, it.includeFeesInBasis) }
            .distinctUntilChanged(),
    ) { txs, splits, cfg -> Triple(txs, splits, cfg) }
        .distinctUntilChanged()

    private fun dayOf(epochMs: Long): String =
        DAY_FORMAT.format(Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()))

    private companion object {
        val DAY_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    }
}
