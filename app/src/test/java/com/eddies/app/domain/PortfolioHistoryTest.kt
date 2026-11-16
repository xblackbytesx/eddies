package com.eddies.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * The value chart on the home screen.
 *
 * It used to be a square wave: the stock side of the portfolio fell to zero
 * every weekend, because a day with no candle was valued at nothing. Each test
 * here is one way a day's total can be wrong while every individual number
 * feeding it is right.
 */
class PortfolioHistoryTest {

    private val zone = ZoneOffset.UTC
    private val asml = AssetIds.stock("AMSTERDAM", "ASML.AS")
    private val aapl = AssetIds.stock("NASDAQ", "AAPL")
    private val btc = AssetIds.crypto("btc-bitcoin")

    // A real week: the 4th is a Monday, the 9th and 10th a weekend.
    private fun day(d: Int) = LocalDate.of(2024, 11, d)
    private fun at(d: Int) = day(d).atTime(10, 0).toInstant(ZoneOffset.UTC).toEpochMilli()

    private fun buy(asset: String, qty: String, price: String?, d: Int, currency: String = "EUR") = Transaction(
        assetId = asset,
        type = TxType.BUY,
        quantity = BigDecimal(qty),
        pricePerUnit = price?.let(::BigDecimal),
        quoteCurrency = currency,
        timestamp = at(d),
    )

    private fun close(d: Int, price: String, currency: String = "EUR") =
        DailyClose(day(d), BigDecimal(price), currency)

    private fun build(
        txs: List<Transaction>,
        closes: Map<String, List<DailyClose>>,
        last: Int,
        fx: FxTable = IdentityFx,
        splits: Map<String, List<SplitEvent>> = emptyMap(),
    ) = PortfolioHistory.build(
        transactions = txs,
        closes = closes,
        baseCurrency = "EUR",
        lastDay = day(last),
        zone = zone,
        fx = fx,
        splitsByAsset = splits,
    )

    private fun List<HistoryDay>.valueOn(d: Int): BigDecimal = first { it.day == day(d) }.value

    private fun assertMoney(expected: String, actual: BigDecimal) =
        assertEquals("expected $expected but was $actual", 0, BigDecimal(expected).compareTo(actual))

    @Test
    fun `a share keeps its friday close over the weekend`() {
        val history = build(
            txs = listOf(buy(asml, "10", "600", 4), buy(btc, "1", "70000", 4)),
            closes = mapOf(
                // No rows for the 9th and 10th: the market was shut.
                asml to listOf(close(4, "600"), close(7, "640"), close(8, "650"), close(11, "660")),
                btc to (4..11).map { close(it, "70000") },
            ),
            last = 11,
        )

        assertMoney("76500", history.valueOn(8))
        // The bug: these two were 70000, the shares having vanished.
        assertMoney("76500", history.valueOn(9))
        assertMoney("76500", history.valueOn(10))
        assertMoney("76600", history.valueOn(11))
        assertEquals("every day is present, weekend included", 8, history.size)
    }

    @Test
    fun `the stock class alone does not drop to zero at a weekend`() {
        val history = build(
            txs = listOf(buy(asml, "10", "600", 4)),
            closes = mapOf(asml to listOf(close(4, "600"), close(8, "650"), close(11, "660"))),
            last = 11,
        )

        assertMoney("6500", history.first { it.day == day(9) }.perClass.getValue(AssetClass.STOCK).value)
        assertTrue("no day of a held position is worth nothing", history.none { it.value.signum() == 0 })
    }

    @Test
    fun `a missing close mid week carries the previous one forward`() {
        // A market holiday on the 6th.
        val history = build(
            txs = listOf(buy(asml, "2", "600", 4)),
            closes = mapOf(asml to listOf(close(4, "600"), close(5, "610"), close(7, "630"))),
            last = 7,
        )

        assertMoney("1220", history.valueOn(6))
    }

    @Test
    fun `before any close is known a holding is worth what was paid`() {
        // Free history starts on the 7th, after the purchase.
        val history = build(
            txs = listOf(buy(btc, "2", "50000", 4)),
            closes = mapOf(btc to listOf(close(7, "70000"), close(8, "71000"))),
            last = 8,
        )

        // Not zero, which drew a cliff from the floor on the day the data began.
        assertMoney("100000", history.valueOn(4))
        assertMoney("100000", history.valueOn(6))
        assertMoney("140000", history.valueOn(7))
    }

    @Test
    fun `a holding with no close and no price counts for nothing rather than a guess`() {
        val history = build(
            txs = listOf(buy(btc, "2", null, 4)),
            closes = emptyMap(),
            last = 5,
        )

        assertMoney("0", history.valueOn(5))
    }

    @Test
    fun `a purchase raises the total from its own day and not before`() {
        val history = build(
            txs = listOf(buy(btc, "1", "70000", 4), buy(btc, "1", "70000", 7)),
            closes = mapOf(btc to (4..8).map { close(it, "70000") }),
            last = 8,
        )

        assertMoney("70000", history.valueOn(6))
        assertMoney("140000", history.valueOn(7))
    }

    @Test
    fun `a purchase at a weekend is valued at the close before it`() {
        val history = build(
            txs = listOf(buy(asml, "1", "655", 9)),
            // Friday's close arrives through the lookback, before the first transaction.
            closes = mapOf(asml to listOf(close(8, "650"), close(11, "660"))),
            last = 11,
        )

        assertEquals(day(9), history.first().day)
        assertMoney("650", history.valueOn(9))
    }

    @Test
    fun `a close in another currency is converted at its own day's rate`() {
        // The dollar weakens between the two days.
        val fx = FxTable { from, to, atEpochMs ->
            if (from == "USD" && to == "EUR") {
                if (atEpochMs < at(6)) BigDecimal("0.90") else BigDecimal("0.80")
            } else null
        }
        val history = build(
            txs = listOf(buy(aapl, "10", "100", 4, currency = "USD")),
            closes = mapOf(aapl to listOf(close(4, "100", "USD"), close(7, "100", "USD"))),
            last = 7,
            fx = fx,
        )

        assertMoney("900", history.valueOn(4))
        assertMoney("800", history.valueOn(7))
    }

    @Test
    fun `a close quoted in a dollar stablecoin is converted as dollars`() {
        val fx = FxTable { from, to, _ -> if (from == "USD" && to == "EUR") BigDecimal("0.90") else null }
        val history = build(
            txs = listOf(buy(btc, "1", "60000", 4)),
            closes = mapOf(btc to listOf(close(4, "70000", "USDT"))),
            last = 4,
            fx = fx,
        )

        assertMoney("63000", history.valueOn(4))
    }

    @Test
    fun `a close that cannot be converted leaves the previous one in force`() {
        val fx = FxTable { from, to, atEpochMs ->
            if (from == "USD" && to == "EUR" && atEpochMs < at(5)) BigDecimal("0.90") else null
        }
        val history = build(
            txs = listOf(buy(aapl, "10", "90", 4)),
            closes = mapOf(aapl to listOf(close(4, "100", "USD"), close(5, "120", "USD"))),
            last = 5,
            fx = fx,
        )

        // Neither zero nor 1200 dollars passed off as euros.
        assertMoney("900", history.valueOn(5))
    }

    @Test
    fun `a price paid before a split is restated in today's shares`() {
        val split = SplitEvent(aapl, at(6), BigDecimal(4), BigDecimal.ONE)
        val history = build(
            txs = listOf(buy(aapl, "10", "400", 4)),
            closes = emptyMap(),
            last = 7,
            splits = mapOf(aapl to listOf(split)),
        )

        // Forty shares at a hundred, not forty at four hundred.
        assertMoney("4000", history.valueOn(4))
        assertMoney("4000", history.valueOn(7))
    }

    @Test
    fun `a position that is sold stops counting but keeps its days`() {
        val sell = Transaction(
            assetId = btc, type = TxType.SELL, quantity = BigDecimal.ONE,
            pricePerUnit = BigDecimal("72000"), quoteCurrency = "EUR", timestamp = at(6),
        )
        val history = build(
            txs = listOf(buy(btc, "1", "70000", 4), sell),
            closes = mapOf(btc to (4..7).map { close(it, "71000") }),
            last = 7,
        )

        assertMoney("71000", history.valueOn(5))
        assertMoney("0", history.valueOn(6))
        assertEquals(4, history.size)
    }

    @Test
    fun `cost basis follows the ledger day by day`() {
        val history = build(
            txs = listOf(buy(btc, "1", "70000", 4), buy(btc, "1", "60000", 6)),
            closes = mapOf(btc to (4..6).map { close(it, "65000") }),
            last = 6,
        )

        assertMoney("70000", history.first { it.day == day(5) }.costBasis)
        assertMoney("130000", history.first { it.day == day(6) }.costBasis)
    }

    @Test
    fun `the newest of several closes for one day wins`() {
        // Yahoo stamps a running session with the time of the last trade, so one
        // day can arrive more than once.
        val history = build(
            txs = listOf(buy(asml, "1", "600", 4)),
            closes = mapOf(asml to listOf(close(4, "600"), close(4, "615"))),
            last = 4,
        )

        assertMoney("615", history.valueOn(4))
    }

    @Test
    fun `an empty ledger and a range before the first transaction give nothing`() {
        assertTrue(build(emptyList(), emptyMap(), last = 8).isEmpty())
        assertTrue(build(listOf(buy(btc, "1", "1", 8)), emptyMap(), last = 7).isEmpty())
    }
}
