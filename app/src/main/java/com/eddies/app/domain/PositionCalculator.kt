package com.eddies.app.domain

import java.math.BigDecimal
import java.math.MathContext

/**
 * One acquisition still (partly) held. [fromStaking] is what lets the UI split a
 * holding into "bought" and "earned" without a second pass over the ledger.
 */
data class Lot(
    val quantity: BigDecimal,
    val unitCost: BigDecimal,
    val timestamp: Long,
    val fromStaking: Boolean,
)

/**
 * A position derived from the ledger. Nothing here is stored; recomputing from
 * the rows is cheap and means a corrected transaction cannot leave a stale
 * balance behind.
 */
data class PositionSnapshot(
    val assetId: String,
    val quantity: BigDecimal,
    val costBasis: BigDecimal,
    val realizedPnl: BigDecimal,
    val stakingQuantity: BigDecimal,
    val stakingCostBasis: BigDecimal,
    val lots: List<Lot>,
    /** Rows the fold could not apply, for example a sell with nothing held. */
    val warnings: List<String>,
) {
    /** Average acquisition cost per unit, or zero for an empty position. */
    val averageUnitCost: BigDecimal
        get() = if (quantity.signum() == 0) BigDecimal.ZERO
        else costBasis.divide(quantity, MC)

    /** Quantity that was bought rather than earned. */
    val principalQuantity: BigDecimal get() = quantity - stakingQuantity

    fun marketValue(unitPrice: BigDecimal): BigDecimal = quantity * unitPrice

    fun unrealizedPnl(unitPrice: BigDecimal): BigDecimal = marketValue(unitPrice) - costBasis

    /** Fiat value attributable to staking rewards at the current price. */
    fun stakingValue(unitPrice: BigDecimal): BigDecimal = stakingQuantity * unitPrice
}

/** DECIMAL128 everywhere: 34 significant digits, and division never throws on a repeating decimal. */
internal val MC: MathContext = MathContext.DECIMAL128

/**
 * Folds a transaction list into a position.
 *
 * Pure, framework-free and therefore JVM-testable, which is the only kind of
 * test this project has. Every number the user reads about their money comes
 * out of this function, so treat a change here as a change to the product.
 */
object PositionCalculator {

    /**
     * [txs] need not be sorted; they are ordered by timestamp here, because a
     * ledger built by hand arrives in whatever order the user typed it and FIFO
     * is meaningless against an unsorted list.
     *
     * [includeFeesInBasis] follows the user's setting: fees either raise the
     * cost of an acquisition and lower the proceeds of a disposal, or they are
     * ignored entirely.
     */
    fun fold(
        txs: List<Transaction>,
        method: CostBasisMethod,
        baseCurrency: String,
        fx: FxTable = IdentityFx,
        includeFeesInBasis: Boolean = true,
        assetId: String = txs.firstOrNull()?.assetId ?: "",
    ): PositionSnapshot {
        val lots = ArrayList<Lot>()
        var realized = BigDecimal.ZERO
        var stakingQty = BigDecimal.ZERO
        var stakingBasis = BigDecimal.ZERO
        val warnings = ArrayList<String>()

        for (tx in txs.sortedBy { it.timestamp }) {
            val qty = tx.quantity.abs()
            if (qty.signum() == 0 && tx.type != TxType.FEE) {
                warnings += "Ignored a zero-quantity ${tx.type} row."
                continue
            }
            val unitCost = tx.pricePerUnit?.let { convert(it, tx.quoteCurrency, baseCurrency, tx.timestamp, fx) }
            val feeInBase = feeInBaseCurrency(tx, baseCurrency, fx).takeIf { includeFeesInBasis } ?: BigDecimal.ZERO

            when (tx.type) {
                TxType.BUY, TxType.TRANSFER_IN, TxType.AIRDROP, TxType.STAKING_REWARD -> {
                    // A transfer or an airdrop with no stated price has no basis
                    // we can defend, so it enters at zero rather than at a guess.
                    val gross = (unitCost ?: BigDecimal.ZERO) * qty
                    val total = gross + feeInBase
                    val effectiveUnit = if (qty.signum() == 0) BigDecimal.ZERO else total.divide(qty, MC)
                    val staking = tx.type == TxType.STAKING_REWARD
                    lots += Lot(qty, effectiveUnit, tx.timestamp, staking)
                    if (staking) {
                        stakingQty += qty
                        stakingBasis += total
                    }
                }

                TxType.SELL -> {
                    val held = lots.sumOf { it.quantity }
                    if (qty > held) {
                        warnings += "Sold ${qty.toPlainString()} but only ${held.toPlainString()} was held; " +
                            "the excess was ignored."
                    }
                    val sellQty = qty.min(held)
                    if (sellQty.signum() == 0) continue
                    val consumed = consume(lots, sellQty, method)
                    val proceeds = (unitCost ?: BigDecimal.ZERO) * sellQty - feeInBase
                    realized += proceeds - consumed.basis
                    stakingQty = (stakingQty - consumed.stakingQuantity).max(BigDecimal.ZERO)
                    stakingBasis = (stakingBasis - consumed.stakingBasis).max(BigDecimal.ZERO)
                }

                TxType.TRANSFER_OUT, TxType.FEE -> {
                    // Leaving your custody is not a disposal, so it moves no
                    // realized P/L. It does reduce the holding and its basis,
                    // because those coins are gone.
                    val held = lots.sumOf { it.quantity }
                    val outQty = qty.min(held)
                    if (outQty.signum() == 0) {
                        if (qty.signum() != 0) warnings += "Ignored a ${tx.type} row with nothing held."
                        continue
                    }
                    val consumed = consume(lots, outQty, method)
                    stakingQty = (stakingQty - consumed.stakingQuantity).max(BigDecimal.ZERO)
                    stakingBasis = (stakingBasis - consumed.stakingBasis).max(BigDecimal.ZERO)
                }
            }
        }

        val remaining = lots.filter { it.quantity.signum() > 0 }
        return PositionSnapshot(
            assetId = assetId,
            quantity = remaining.sumOf { it.quantity },
            costBasis = remaining.sumOf { it.quantity * it.unitCost },
            realizedPnl = realized,
            stakingQuantity = stakingQty,
            stakingCostBasis = stakingBasis,
            lots = remaining,
            warnings = warnings,
        )
    }

    private data class Consumed(
        val basis: BigDecimal,
        val stakingQuantity: BigDecimal,
        val stakingBasis: BigDecimal,
    )

    /**
     * Removes [qty] from [lots] in the order [method] dictates and reports what
     * that cost, including how much of it came from staking rewards.
     *
     * AVERAGE consumes pro rata across every lot rather than in an order. That
     * keeps one code path for the staking split: taking 10% of the holding takes
     * 10% of the earned portion with it, which is what an averaged position
     * means.
     */
    private fun consume(lots: MutableList<Lot>, qty: BigDecimal, method: CostBasisMethod): Consumed {
        val held = lots.sumOf { it.quantity }
        if (held.signum() == 0 || qty.signum() == 0) return Consumed(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO)

        if (method == CostBasisMethod.AVERAGE) {
            val fraction = qty.divide(held, MC)
            var basis = BigDecimal.ZERO
            var stakeQty = BigDecimal.ZERO
            var stakeBasis = BigDecimal.ZERO
            for (i in lots.indices) {
                val lot = lots[i]
                val take = lot.quantity * fraction
                val takenBasis = take * lot.unitCost
                basis += takenBasis
                if (lot.fromStaking) {
                    stakeQty += take
                    stakeBasis += takenBasis
                }
                lots[i] = lot.copy(quantity = lot.quantity - take)
            }
            lots.removeAll { it.quantity.signum() <= 0 }
            return Consumed(basis, stakeQty, stakeBasis)
        }

        val order = when (method) {
            CostBasisMethod.FIFO -> lots.sortedBy { it.timestamp }
            CostBasisMethod.LIFO -> lots.sortedByDescending { it.timestamp }
            CostBasisMethod.HIFO -> lots.sortedByDescending { it.unitCost }
            CostBasisMethod.AVERAGE -> error("handled above")
        }

        var remaining = qty
        var basis = BigDecimal.ZERO
        var stakeQty = BigDecimal.ZERO
        var stakeBasis = BigDecimal.ZERO
        for (lot in order) {
            if (remaining.signum() <= 0) break
            val take = remaining.min(lot.quantity)
            val takenBasis = take * lot.unitCost
            basis += takenBasis
            if (lot.fromStaking) {
                stakeQty += take
                stakeBasis += takenBasis
            }
            val idx = lots.indexOfFirst { it === lot }
            if (idx >= 0) lots[idx] = lot.copy(quantity = lot.quantity - take)
            remaining -= take
        }
        lots.removeAll { it.quantity.signum() <= 0 }
        return Consumed(basis, stakeQty, stakeBasis)
    }

    /**
     * A fee expressed in the base currency. A fee paid in the asset itself is
     * not converted here: it is quantity, not cost, and the FEE row type is how
     * the user records that.
     */
    private fun feeInBaseCurrency(tx: Transaction, base: String, fx: FxTable): BigDecimal {
        val fee = tx.feeQuantity ?: return BigDecimal.ZERO
        if (fee.signum() == 0) return BigDecimal.ZERO
        val feeCurrency = tx.feeAssetId ?: tx.quoteCurrency
        if (feeCurrency == tx.assetId) return BigDecimal.ZERO
        return convert(fee, feeCurrency, base, tx.timestamp, fx) ?: BigDecimal.ZERO
    }

    private fun convert(
        amount: BigDecimal,
        from: String,
        to: String,
        at: Long,
        fx: FxTable,
    ): BigDecimal? {
        if (from == to) return amount
        val rate = fx.rate(from, to, at) ?: return null
        return amount * rate
    }

}
