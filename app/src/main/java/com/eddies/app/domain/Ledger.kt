package com.eddies.app.domain

import java.math.BigDecimal

/**
 * The asset classes the ledger can hold. CRYPTO ships first; STOCK exists here
 * from the start because the whole point of prefixing [assetId] with a class is
 * that adding equities later touches no other table.
 */
enum class AssetClass { CRYPTO, STOCK, CASH }

/**
 * What a ledger row does to a position.
 *
 * STAKING_REWARD is deliberately its own type rather than a flagged BUY: it is
 * the only way the app can answer "how much of this holding did I earn rather
 * than buy", which is a question the UI asks on every asset detail screen.
 */
enum class TxType {
    BUY,
    SELL,
    TRANSFER_IN,
    TRANSFER_OUT,
    STAKING_REWARD,
    AIRDROP,
    FEE,

    /**
     * Cash paid out for holding something. Changes no quantity and no cost
     * basis, so it carries [Transaction.cashAmount] rather than a quantity.
     *
     * The stock-side twin of STAKING_REWARD: both are earned rather than bought,
     * which is why the combined view can present them as one income figure.
     */
    DIVIDEND,
}

/** Where a row came from. Auto-imported rows carry an externalId for dedupe. */
enum class TxSource { MANUAL, IMPORT_CSV, IMPORT_KOIOS }

/**
 * One ledger entry, framework-free so the calculator that folds these is
 * JVM-testable.
 *
 * Quantities and prices are BigDecimal, never Double. An 18-decimal ERC-20
 * balance does not survive a Double, and the failure mode is a wrong number on
 * the user's net worth with nothing in the logs.
 */
data class Transaction(
    val id: Long = 0,
    val accountId: Long = 0,
    val assetId: String,
    val type: TxType,
    val quantity: BigDecimal,
    val pricePerUnit: BigDecimal? = null,
    val quoteCurrency: String = "USD",
    val feeQuantity: BigDecimal? = null,
    val feeAssetId: String? = null,
    val timestamp: Long = 0,
    val note: String? = null,
    val source: TxSource = TxSource.MANUAL,
    val externalId: String? = null,
    /** Cash received, for DIVIDEND rows. In [quoteCurrency]. */
    val cashAmount: BigDecimal? = null,
)

/**
 * A share split, as the exchange applied it.
 *
 * Held as an event and applied when folding, never by rewriting the ledger. A
 * 4:1 split turns 10 shares into 40 and quarters the unit cost, leaving total
 * basis untouched. Ignoring it makes a portfolio look like it lost 75 percent
 * overnight; rewriting the ledger for it destroys what the user actually typed.
 */
data class SplitEvent(
    val assetId: String,
    val timestamp: Long,
    val numerator: BigDecimal,
    val denominator: BigDecimal,
) {
    /** 4:1 is a ratio of 4. A reverse 1:10 split is 0.1. */
    val ratio: BigDecimal
        get() = if (denominator.signum() == 0) BigDecimal.ONE else numerator.divide(denominator, MC)
}

/** How a disposal picks which acquisition it is disposing of. */
enum class CostBasisMethod { AVERAGE, FIFO, LIFO, HIFO }

/**
 * Historical FX lookup, kept as an interface so the calculator stays pure and
 * tests can hand it a fixed table.
 *
 * Returns the multiplier that converts an amount in [from] into [to] as of
 * [atEpochMs], or null when no rate is known. Null degrades: the caller keeps
 * the original currency rather than inventing a number.
 */
fun interface FxTable {
    fun rate(from: String, to: String, atEpochMs: Long): BigDecimal?
}

/** An FxTable that only knows the identity conversion. Useful in tests and for a single-currency user. */
val IdentityFx = FxTable { from, to, _ -> if (from == to) BigDecimal.ONE else null }
