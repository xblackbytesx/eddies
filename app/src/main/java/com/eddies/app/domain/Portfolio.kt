package com.eddies.app.domain

import java.math.BigDecimal

/** A live or last-known price for one asset. */
data class PriceTick(
    val assetId: String,
    val price: BigDecimal,
    val currency: String,
    val changePct24h: Double? = null,
    val at: Long = 0,
    val source: PriceSourceId = PriceSourceId.MANUAL,
    /**
     * True when this is a cached number rather than a live one. The UI must
     * render it differently: a three-hour-old price shown as if it were live is
     * worse than no price at all, because the user acts on it.
     */
    val stale: Boolean = false,
)

/** One asset's row on the portfolio screen, priced. */
data class Holding(
    val asset: Asset,
    val position: PositionSnapshot,
    val price: PriceTick?,
    val currency: String,
    /**
     * Outstanding staking rewards read from the chain, on top of what the ledger
     * says. Not a ledger row: rewards accrue continuously and are withdrawn in
     * lumps, so this is a balance that gets replaced, not appended to.
     */
    val stakingPending: BigDecimal = BigDecimal.ZERO,
) {
    /** What is actually held: recorded transactions plus what is still accruing. */
    val totalQuantity: BigDecimal = position.quantity + stakingPending

    val marketValue: BigDecimal = price?.let { totalQuantity * it.price } ?: BigDecimal.ZERO
    val unrealizedPnl: BigDecimal = if (price == null) BigDecimal.ZERO else marketValue - position.costBasis

    /**
     * Fiat value of everything earned rather than bought: rewards still accruing
     * on chain, plus any recorded as transactions.
     *
     * Valued at today's price, not at what it was worth when it accrued. The
     * question this answers is "how much of my ADA did I earn", not "what was my
     * income in 2021", so a historical basis would be precision nobody asked for.
     */
    val stakingValue: BigDecimal =
        price?.let { (position.stakingQuantity + stakingPending) * it.price } ?: BigDecimal.ZERO

    val stakingQuantityTotal: BigDecimal = position.stakingQuantity + stakingPending
    val hasPendingStaking: Boolean = stakingPending.signum() > 0
    val hasPrice: Boolean = price != null
    val isStale: Boolean = price?.stale ?: true

    /** Percentage return on cost, null when there is no cost to return on. */
    val unrealizedPnlPct: Double?
        get() = if (position.costBasis.signum() == 0) null
        else unrealizedPnl.divide(position.costBasis, MC).toDouble() * 100.0
}

/** The whole portfolio, priced, in one currency. */
data class PortfolioSummary(
    val currency: String,
    val holdings: List<Holding>,
    val totalValue: BigDecimal,
    val totalCostBasis: BigDecimal,
    val totalUnrealizedPnl: BigDecimal,
    val totalRealizedPnl: BigDecimal,
    val totalStakingValue: BigDecimal,
    /** True when any priced holding is running on a cached number. */
    val anyStale: Boolean,
) {
    val totalPnlPct: Double?
        get() = if (totalCostBasis.signum() == 0) null
        else totalUnrealizedPnl.divide(totalCostBasis, MC).toDouble() * 100.0

    /** Share of total value per holding, for the allocation chart. */
    fun allocation(): List<Pair<Holding, Double>> {
        if (totalValue.signum() == 0) return holdings.map { it to 0.0 }
        return holdings.map { it to it.marketValue.divide(totalValue, MC).toDouble() }
    }

    companion object {
        fun empty(currency: String) = PortfolioSummary(
            currency = currency,
            holdings = emptyList(),
            totalValue = BigDecimal.ZERO,
            totalCostBasis = BigDecimal.ZERO,
            totalUnrealizedPnl = BigDecimal.ZERO,
            totalRealizedPnl = BigDecimal.ZERO,
            totalStakingValue = BigDecimal.ZERO,
            anyStale = false,
        )
    }
}

/**
 * Builds the priced portfolio from ledger rows, asset metadata and prices.
 *
 * Pure and framework-free on purpose: this is the code that decides the number
 * at the top of the home screen, and it is the kind of code that is impossible
 * to eyeball for correctness.
 */
object PortfolioBuilder {

    fun build(
        transactions: List<Transaction>,
        assets: Map<String, Asset>,
        prices: Map<String, PriceTick>,
        currency: String,
        method: CostBasisMethod = CostBasisMethod.AVERAGE,
        fx: FxTable = IdentityFx,
        includeFeesInBasis: Boolean = true,
        /** Outstanding staking rewards per asset, read from the chain. */
        stakingPending: Map<String, BigDecimal> = emptyMap(),
    ): PortfolioSummary {
        // A coin can be staked without any recorded transaction, so the asset set
        // is the union. Keying only off the ledger would hide a holding that
        // exists entirely as accrued rewards.
        val byAsset = (transactions.groupBy { it.assetId }.keys + stakingPending.keys)
            .associateWith { id -> transactions.filter { it.assetId == id } }
        val holdings = byAsset.mapNotNull { (assetId, txs) ->
            val asset = assets[assetId] ?: return@mapNotNull null
            val position = PositionCalculator.fold(
                txs = txs,
                method = method,
                baseCurrency = currency,
                fx = fx,
                includeFeesInBasis = includeFeesInBasis,
                assetId = assetId,
            )
            val price = prices[assetId]?.let { tick ->
                if (tick.currency == currency) tick
                else fx.rate(tick.currency, currency, tick.at)
                    ?.let { tick.copy(price = tick.price * it, currency = currency) }
            }
            Holding(
                asset = asset,
                position = position,
                price = price,
                currency = currency,
                stakingPending = stakingPending[assetId] ?: BigDecimal.ZERO,
            )
        }
            // A fully sold position still carries realized P/L, so it is kept in
            // the totals but sorted below anything currently held.
            .sortedWith(compareByDescending<Holding> { it.marketValue }.thenBy { it.asset.symbol })

        return PortfolioSummary(
            currency = currency,
            holdings = holdings,
            totalValue = holdings.sumOf { it.marketValue },
            totalCostBasis = holdings.sumOf { it.position.costBasis },
            totalUnrealizedPnl = holdings.sumOf { it.unrealizedPnl },
            totalRealizedPnl = holdings.sumOf { it.position.realizedPnl },
            totalStakingValue = holdings.sumOf { it.stakingValue },
            anyStale = holdings.any { it.hasPrice && it.isStale },
        )
    }
}
