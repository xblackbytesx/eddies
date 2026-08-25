package com.eddies.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

/**
 * Every number the user reads about their money comes out of PositionCalculator.
 * These are written as specifications: each one names a way the arithmetic could
 * be wrong in a manner nobody would catch by reading the code.
 */
class PositionCalculatorTest {

    private val btc = "crypto:btc-bitcoin"

    private fun buy(qty: String, price: String, at: Long = 0, fee: String? = null) = Transaction(
        assetId = btc, type = TxType.BUY, quantity = bd(qty), pricePerUnit = bd(price),
        timestamp = at, feeQuantity = fee?.let { bd(it) }, feeAssetId = fee?.let { "USD" },
    )

    private fun sell(qty: String, price: String, at: Long = 0, fee: String? = null) = Transaction(
        assetId = btc, type = TxType.SELL, quantity = bd(qty), pricePerUnit = bd(price),
        timestamp = at, feeQuantity = fee?.let { bd(it) }, feeAssetId = fee?.let { "USD" },
    )

    private fun reward(qty: String, price: String, at: Long = 0) = Transaction(
        assetId = btc, type = TxType.STAKING_REWARD, quantity = bd(qty),
        pricePerUnit = bd(price), timestamp = at,
    )

    private fun fold(
        txs: List<Transaction>,
        method: CostBasisMethod = CostBasisMethod.AVERAGE,
        includeFees: Boolean = true,
    ) = PositionCalculator.fold(txs, method, "USD", IdentityFx, includeFees, btc)

    @Test
    fun `a single buy is quantity and cost, with nothing realized`() {
        val p = fold(listOf(buy("2", "100")))
        assertEq("2", p.quantity)
        assertEq("200", p.costBasis)
        assertEq("0", p.realizedPnl)
        assertEq("100", p.averageUnitCost)
    }

    @Test
    fun `average cost blends two buys at different prices`() {
        val p = fold(listOf(buy("1", "100", at = 1), buy("1", "300", at = 2)))
        assertEq("2", p.quantity)
        assertEq("400", p.costBasis)
        assertEq("200", p.averageUnitCost)
    }

    @Test
    fun `a partial sell under average cost leaves the average untouched`() {
        // Selling half of a blended position must not move the unit cost of what
        // remains. Getting this wrong silently rewrites the basis of the holding.
        val p = fold(listOf(buy("1", "100", at = 1), buy("1", "300", at = 2), sell("1", "500", at = 3)))
        assertEq("1", p.quantity)
        assertEq("200", p.costBasis)
        assertEq("200", p.averageUnitCost)
        assertEq("300", p.realizedPnl)
    }

    @Test
    fun `FIFO consumes the oldest lot first`() {
        val txs = listOf(buy("1", "100", at = 1), buy("1", "300", at = 2), sell("1", "500", at = 3))
        val p = fold(txs, CostBasisMethod.FIFO)
        assertEq("400", p.realizedPnl)   // sold the 100 lot
        assertEq("300", p.costBasis)     // the 300 lot remains
    }

    @Test
    fun `LIFO consumes the newest lot first`() {
        val txs = listOf(buy("1", "100", at = 1), buy("1", "300", at = 2), sell("1", "500", at = 3))
        val p = fold(txs, CostBasisMethod.LIFO)
        assertEq("200", p.realizedPnl)
        assertEq("100", p.costBasis)
    }

    @Test
    fun `HIFO consumes the most expensive lot first regardless of age`() {
        val txs = listOf(buy("1", "300", at = 1), buy("1", "100", at = 2), sell("1", "500", at = 3))
        val p = fold(txs, CostBasisMethod.HIFO)
        assertEq("200", p.realizedPnl)
        assertEq("100", p.costBasis)
    }

    @Test
    fun `FIFO exhausts lots across three acquisitions`() {
        val txs = listOf(
            buy("1", "100", at = 1), buy("1", "200", at = 2), buy("1", "300", at = 3),
            sell("2.5", "400", at = 4),
        )
        val p = fold(txs, CostBasisMethod.FIFO)
        assertEq("0.5", p.quantity)
        // Consumed 1@100 + 1@200 + 0.5@300 = 450 basis, proceeds 2.5*400 = 1000.
        assertEq("550", p.realizedPnl)
        assertEq("150", p.costBasis)
    }

    @Test
    fun `rows are folded in timestamp order regardless of list order`() {
        // A hand-typed ledger arrives in whatever order the user entered it, and
        // FIFO against an unsorted list is meaningless.
        val txs = listOf(sell("1", "500", at = 3), buy("1", "300", at = 2), buy("1", "100", at = 1))
        val p = fold(txs, CostBasisMethod.FIFO)
        assertEq("400", p.realizedPnl)
    }

    @Test
    fun `selling more than held is clamped and warned about, never negative`() {
        val p = fold(listOf(buy("1", "100", at = 1), sell("5", "200", at = 2)))
        assertEq("0", p.quantity)
        assertEq("100", p.realizedPnl)   // only the 1 held was disposed of
        assertTrue("expected a warning", p.warnings.any { it.contains("only") })
    }

    @Test
    fun `a sell with nothing held records nothing rather than a negative holding`() {
        val p = fold(listOf(sell("1", "200", at = 1)))
        assertEq("0", p.quantity)
        assertEq("0", p.realizedPnl)
    }

    @Test
    fun `a zero-quantity row is ignored and reported`() {
        val p = fold(listOf(buy("0", "100", at = 1), buy("1", "100", at = 2)))
        assertEq("1", p.quantity)
        assertTrue(p.warnings.any { it.contains("zero-quantity") })
    }

    @Test
    fun `a buy at zero price is legal and contributes no basis`() {
        val p = fold(listOf(buy("1", "0", at = 1)))
        assertEq("1", p.quantity)
        assertEq("0", p.costBasis)
    }

    @Test
    fun `a fiat fee raises the cost of a buy and lowers the proceeds of a sell`() {
        val bought = fold(listOf(buy("1", "100", at = 1, fee = "10")))
        assertEq("110", bought.costBasis)

        val sold = fold(listOf(buy("1", "100", at = 1), sell("1", "200", at = 2, fee = "10")))
        assertEq("90", sold.realizedPnl)   // 200 proceeds - 10 fee - 100 basis
    }

    @Test
    fun `fees are excluded from basis when the setting says so`() {
        val p = fold(listOf(buy("1", "100", at = 1, fee = "10")), includeFees = false)
        assertEq("100", p.costBasis)
    }

    @Test
    fun `a fee denominated in the asset itself does not inflate its own cost`() {
        // Paying 0.001 BTC to acquire BTC is a quantity event, not a cost event.
        // Converting it as if it were fiat would double-count the fee.
        val tx = Transaction(
            assetId = btc, type = TxType.BUY, quantity = bd("1"), pricePerUnit = bd("100"),
            feeQuantity = bd("0.001"), feeAssetId = btc, timestamp = 1,
        )
        val p = fold(listOf(tx))
        assertEq("100", p.costBasis)
    }

    @Test
    fun `a transfer out reduces the holding and its basis but realizes nothing`() {
        val p = fold(
            listOf(
                buy("2", "100", at = 1),
                Transaction(assetId = btc, type = TxType.TRANSFER_OUT, quantity = bd("1"), timestamp = 2),
            ),
        )
        assertEq("1", p.quantity)
        assertEq("100", p.costBasis)
        assertEq("0", p.realizedPnl)
    }

    @Test
    fun `a transfer in with no stated price enters at zero rather than a guess`() {
        val p = fold(
            listOf(Transaction(assetId = btc, type = TxType.TRANSFER_IN, quantity = bd("1"), timestamp = 1)),
        )
        assertEq("1", p.quantity)
        assertEq("0", p.costBasis)
    }

    @Test
    fun `staking rewards are counted in the holding and split out separately`() {
        val p = fold(listOf(buy("100", "1", at = 1), reward("5", "2", at = 2)))
        assertEq("105", p.quantity)
        assertEq("5", p.stakingQuantity)
        assertEq("100", p.principalQuantity)
        assertEq("110", p.costBasis)         // 100 bought + 10 income at receipt
        assertEq("10", p.stakingCostBasis)
    }

    @Test
    fun `selling part of a staked position reduces the staking share proportionally`() {
        // Under average cost, disposing of 10% of the holding disposes of 10% of
        // the earned portion. Anything else lets stakingQuantity exceed quantity.
        val p = fold(listOf(buy("90", "1", at = 1), reward("10", "1", at = 2), sell("50", "2", at = 3)))
        assertEq("50", p.quantity)
        assertEq("5", p.stakingQuantity)
        assertTrue("staking must never exceed the holding", p.stakingQuantity <= p.quantity)
    }

    @Test
    fun `a staking reward followed by a full sell realizes its basis too`() {
        val p = fold(listOf(buy("1", "100", at = 1), reward("1", "50", at = 2), sell("2", "200", at = 3)))
        assertEq("0", p.quantity)
        assertEq("0", p.stakingQuantity)
        // Proceeds 400, basis 100 + 50 = 150.
        assertEq("250", p.realizedPnl)
    }

    @Test
    fun `FIFO tracks which consumed lots came from staking`() {
        val txs = listOf(reward("1", "10", at = 1), buy("1", "100", at = 2), sell("1", "200", at = 3))
        val p = fold(txs, CostBasisMethod.FIFO)
        // The reward was oldest, so FIFO sold it: nothing earned remains.
        assertEq("0", p.stakingQuantity)
        assertEq("1", p.quantity)
        assertEq("190", p.realizedPnl)
    }

    @Test
    fun `an 18-decimal quantity survives the fold intact`() {
        // The reason quantities are BigDecimal and not Double. A Double loses
        // this past the fifteenth significant digit and the balance shown is wrong.
        val qty = "0.123456789012345678"
        val p = fold(listOf(buy(qty, "1", at = 1)))
        assertEq(qty, p.quantity)
    }

    @Test
    fun `historical FX converts a foreign-quoted buy into the base currency`() {
        val fx = FxTable { from, to, _ -> if (from == "EUR" && to == "USD") bd("1.10") else null }
        val tx = Transaction(
            assetId = btc, type = TxType.BUY, quantity = bd("1"),
            pricePerUnit = bd("100"), quoteCurrency = "EUR", timestamp = 1,
        )
        val p = PositionCalculator.fold(listOf(tx), CostBasisMethod.AVERAGE, "USD", fx, true, btc)
        assertEq("110.0", p.costBasis)
    }

    @Test
    fun `an unknown FX rate degrades to zero basis rather than throwing`() {
        val tx = Transaction(
            assetId = btc, type = TxType.BUY, quantity = bd("1"),
            pricePerUnit = bd("100"), quoteCurrency = "JPY", timestamp = 1,
        )
        val p = PositionCalculator.fold(listOf(tx), CostBasisMethod.AVERAGE, "USD", IdentityFx, true, btc)
        assertEq("1", p.quantity)
        assertEq("0", p.costBasis)
    }

    @Test
    fun `an empty ledger is an empty position, not a crash`() {
        val p = fold(emptyList())
        assertEq("0", p.quantity)
        assertEq("0", p.averageUnitCost)
        assertTrue(p.lots.isEmpty())
    }

    private fun bd(s: String) = BigDecimal(s)

    private fun assertEq(expected: String, actual: BigDecimal) {
        assertEquals(
            "expected $expected but was ${actual.toPlainString()}",
            0,
            BigDecimal(expected).compareTo(actual),
        )
    }
}
