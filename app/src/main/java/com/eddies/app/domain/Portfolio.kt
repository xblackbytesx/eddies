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
) {
    val marketValue: BigDecimal = price?.let { position.quantity * it.price } ?: BigDecimal.ZERO
    val unrealizedPnl: BigDecimal = if (price == null) BigDecimal.ZERO else marketValue - position.costBasis
    val stakingValue: BigDecimal = price?.let { position.stakingQuantity * it.price } ?: BigDecimal.ZERO
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
    ): PortfolioSummary {
        val byAsset = transactions.groupBy { it.assetId }
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
            Holding(asset, position, price, currency)
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
