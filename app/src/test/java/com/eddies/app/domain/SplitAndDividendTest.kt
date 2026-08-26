package com.eddies.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

/**
 * Splits are the thing that silently corrupts a stock tracker. Ignore Apple's
 * 4:1 in 2020 and a position looks like it lost three quarters of its value
 * overnight, with nothing on screen to say why.
 *
 * The rule being pinned here: a split multiplies the share count and divides the
 * unit cost, so total cost basis is unchanged. And because a sale entered before
 * a split is denominated in pre-split shares, splits and transactions have to be
 * replayed as one chronological timeline.
 */
class SplitAndDividendTest {

    private val aapl = AssetIds.stock("NASDAQ", "AAPL")

    private fun buy(qty: String, price: String, at: Long) = Transaction(
        assetId = aapl,
        type = TxType.BUY,
        quantity = BigDecimal(qty),
        pricePerUnit = BigDecimal(price),
        quoteCurrency = "EUR",
        timestamp = at,
    )

    private fun sell(qty: String, price: String, at: Long) = Transaction(
        assetId = aapl,
        type = TxType.SELL,
        quantity = BigDecimal(qty),
        pricePerUnit = BigDecimal(price),
        quoteCurrency = "EUR",
        timestamp = at,
    )

    private fun split(at: Long, numerator: String, denominator: String = "1") =
        SplitEvent(aapl, at, BigDecimal(numerator), BigDecimal(denominator))

    private fun fold(
        txs: List<Transaction>,
        splits: List<SplitEvent> = emptyList(),
        method: CostBasisMethod = CostBasisMethod.AVERAGE,
    ) = PositionCalculator.fold(
        txs = txs,
        method = method,
        baseCurrency = "EUR",
        assetId = aapl,
        splits = splits,
    )

    @Test
    fun `a four for one split quadruples the shares and quarters the unit cost`() {
        val p = fold(listOf(buy("10", "400", at = 100)), listOf(split(200, "4")))
        assertEquals(0, BigDecimal("40").compareTo(p.quantity))
        assertEquals(0, BigDecimal("100").compareTo(p.averageUnitCost))
    }

    @Test
    fun `a split leaves total cost basis untouched`() {
        // The whole invariant in one line. More shares, proportionally cheaper,
        // same money spent.
        val before = fold(listOf(buy("10", "400", at = 100)))
        val after = fold(listOf(buy("10", "400", at = 100)), listOf(split(200, "4")))
        assertEquals(0, before.costBasis.compareTo(after.costBasis))
        assertEquals(0, BigDecimal("4000").compareTo(after.costBasis))
    }

    @Test
    fun `a split before the purchase does not touch it`() {
        // Buying after a split means the price already reflects it. Applying it
        // again would quadruple a position that was never split.
        val p = fold(listOf(buy("10", "100", at = 300)), listOf(split(200, "4")))
        assertEquals(0, BigDecimal("10").compareTo(p.quantity))
        assertEquals(0, BigDecimal("1000").compareTo(p.costBasis))
    }

    @Test
    fun `a sale entered before the split is in pre-split shares`() {
        // This is why splits are replayed in order rather than applied at the
        // end. Selling 5 of 10 in 2019 is half the position; treating that 5 as
        // post-split shares would leave 35 instead of 20.
        val p = fold(
            listOf(buy("10", "400", at = 100), sell("5", "500", at = 150)),
            listOf(split(200, "4")),
        )
        assertEquals(0, BigDecimal("20").compareTo(p.quantity))
        assertEquals(0, BigDecimal("2000").compareTo(p.costBasis))
        // Sold 5 at 500 against a basis of 5 at 400.
        assertEquals(0, BigDecimal("500").compareTo(p.realizedPnl))
    }

    @Test
    fun `a sale entered after the split is in post-split shares`() {
        val p = fold(
            listOf(buy("10", "400", at = 100), sell("20", "125", at = 300)),
            listOf(split(200, "4")),
        )
        assertEquals(0, BigDecimal("20").compareTo(p.quantity))
        // 20 post-split shares at a 100 unit cost, sold at 125.
        assertEquals(0, BigDecimal("500").compareTo(p.realizedPnl))
    }

    @Test
    fun `two splits compound`() {
        val p = fold(listOf(buy("10", "800", at = 100)), listOf(split(200, "4"), split(300, "2")))
        assertEquals(0, BigDecimal("80").compareTo(p.quantity))
        assertEquals(0, BigDecimal("100").compareTo(p.averageUnitCost))
        assertEquals(0, BigDecimal("8000").compareTo(p.costBasis))
    }

    @Test
    fun `a reverse split reduces the share count and raises the unit cost`() {
        // A 1:10 reverse split. Ratios below one have to work or delisted-adjacent
        // holdings come out ten times too large.
        val p = fold(listOf(buy("100", "2", at = 100)), listOf(split(200, "1", "10")))
        assertEquals(0, BigDecimal("10").compareTo(p.quantity))
        assertEquals(0, BigDecimal("20").compareTo(p.averageUnitCost))
        assertEquals(0, BigDecimal("200").compareTo(p.costBasis))
    }

    @Test
    fun `a split after the final transaction still counts`() {
        // Buy once, never trade again, and the split still happened. Applying
        // splits only while walking transactions would miss it entirely.
        val p = fold(listOf(buy("10", "400", at = 100)), listOf(split(999, "4")))
        assertEquals(0, BigDecimal("40").compareTo(p.quantity))
    }

    @Test
    fun `FIFO lots each get split, and disposal order is unaffected`() {
        val p = fold(
            listOf(buy("10", "400", at = 100), buy("10", "800", at = 150)),
            listOf(split(200, "4")),
            method = CostBasisMethod.FIFO,
        )
        assertEquals(0, BigDecimal("80").compareTo(p.quantity))
        assertEquals(2, p.lots.size)
        assertEquals(0, BigDecimal("100").compareTo(p.lots[0].unitCost))
        assertEquals(0, BigDecimal("200").compareTo(p.lots[1].unitCost))
    }

    @Test
    fun `a zero or nonsense ratio is ignored rather than zeroing the position`() {
        // A malformed provider response must not be able to erase a holding.
        val p = fold(listOf(buy("10", "400", at = 100)), listOf(split(200, "0")))
        assertEquals(0, BigDecimal("10").compareTo(p.quantity))
    }

    @Test
    fun `staking quantity is split too, for a coin that ever splits`() {
        // Rare in crypto but not unheard of, and leaving it unsplit would let
        // the earned portion exceed the holding.
        val reward = Transaction(
            assetId = aapl,
            type = TxType.STAKING_REWARD,
            quantity = BigDecimal("10"),
            pricePerUnit = BigDecimal("400"),
            quoteCurrency = "EUR",
            timestamp = 100,
        )
        val p = fold(listOf(reward), listOf(split(200, "4")))
        assertEquals(0, BigDecimal("40").compareTo(p.quantity))
        assertEquals(0, BigDecimal("40").compareTo(p.stakingQuantity))
        assertTrue(p.stakingQuantity <= p.quantity)
    }

    @Test
    fun `a dividend is income and moves neither quantity nor basis`() {
        val dividend = Transaction(
            assetId = aapl,
            type = TxType.DIVIDEND,
            quantity = BigDecimal.ZERO,
            cashAmount = BigDecimal("25"),
            quoteCurrency = "EUR",
            timestamp = 150,
        )
        val p = fold(listOf(buy("10", "400", at = 100), dividend))
        assertEquals(0, BigDecimal("10").compareTo(p.quantity))
        assertEquals(0, BigDecimal("4000").compareTo(p.costBasis))
        assertEquals(0, BigDecimal("25").compareTo(p.dividendIncome))
        assertEquals(0, BigDecimal.ZERO.compareTo(p.realizedPnl))
    }

    @Test
    fun `dividends accumulate across payments`() {
        fun div(amount: String, at: Long) = Transaction(
            assetId = aapl,
            type = TxType.DIVIDEND,
            quantity = BigDecimal.ZERO,
            cashAmount = BigDecimal(amount),
            quoteCurrency = "EUR",
            timestamp = at,
        )
        val p = fold(listOf(buy("10", "400", at = 100), div("25", 150), div("30", 200)))
        assertEquals(0, BigDecimal("55").compareTo(p.dividendIncome))
    }

    @Test
    fun `a dividend in another currency is converted, not counted raw`() {
        val fx = FxTable { from, to, _ -> if (from == "USD" && to == "EUR") BigDecimal("0.9") else null }
        val dividend = Transaction(
            assetId = aapl,
            type = TxType.DIVIDEND,
            quantity = BigDecimal.ZERO,
            cashAmount = BigDecimal("100"),
            quoteCurrency = "USD",
            timestamp = 150,
        )
        val p = PositionCalculator.fold(
            txs = listOf(dividend),
            method = CostBasisMethod.AVERAGE,
            baseCurrency = "EUR",
            fx = fx,
            assetId = aapl,
        )
        assertEquals(0, BigDecimal("90.0").compareTo(p.dividendIncome))
    }

    @Test
    fun `no splits means the position is exactly as recorded`() {
        val p = fold(listOf(buy("10", "400", at = 100)))
        assertEquals(0, BigDecimal("10").compareTo(p.quantity))
        assertEquals(0, BigDecimal("400").compareTo(p.averageUnitCost))
    }
}
