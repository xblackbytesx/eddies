package com.eddies.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

/**
 * The hourly value chart behind the 1D and 1W ranges. Same rules as the daily
 * one, on a finer clock.
 */
class PortfolioIntradayTest {

    private val hour = 3_600_000L
    private val t0 = 1_731_000_000_000L - 1_731_000_000_000L % hour
    private val asml = AssetIds.stock("AMSTERDAM", "ASML.AS")
    private val btc = AssetIds.crypto("btc-bitcoin")

    private fun h(n: Int) = t0 + n * hour

    private fun buy(asset: String, qty: String, price: String?, at: Long) = Transaction(
        assetId = asset,
        type = TxType.BUY,
        quantity = BigDecimal(qty),
        pricePerUnit = price?.let(::BigDecimal),
        quoteCurrency = "EUR",
        timestamp = at,
    )

    private fun close(at: Long, price: String, currency: String = "EUR") =
        TimedClose(at, BigDecimal(price), currency)

    private fun build(
        txs: List<Transaction>,
        closes: Map<String, List<TimedClose>>,
        from: Int,
        to: Int,
        fx: FxTable = IdentityFx,
    ) = PortfolioHistory.buildIntraday(
        transactions = txs,
        closes = closes,
        baseCurrency = "EUR",
        from = h(from),
        to = h(to),
        stepMs = hour,
        fx = fx,
    )

    private fun List<HistoryPoint>.valueAt(n: Int): BigDecimal = first { it.at == h(n) }.value

    private fun assertMoney(expected: String, actual: BigDecimal) =
        assertEquals("expected $expected but was $actual", 0, BigDecimal(expected).compareTo(actual))

    @Test
    fun `there is one point per hour across the window`() {
        val points = build(
            txs = listOf(buy(btc, "1", "100", h(-48))),
            closes = mapOf(btc to listOf(close(h(-1), "100"))),
            from = 0, to = 24,
        )
        assertEquals(25, points.size)
        assertEquals(h(0), points.first().at)
        assertEquals(h(24), points.last().at)
    }

    @Test
    fun `each hour is valued at the newest close on or before it`() {
        val points = build(
            txs = listOf(buy(btc, "2", "100", h(-48))),
            closes = mapOf(btc to listOf(close(h(0), "100"), close(h(1), "110"), close(h(3), "90"))),
            from = 0, to = 3,
        )
        assertMoney("200", points.valueAt(0))
        assertMoney("220", points.valueAt(1))
        assertMoney("220", points.valueAt(2))
        assertMoney("180", points.valueAt(3))
    }

    @Test
    fun `a share with no hourly prices overnight is carried, not dropped to zero`() {
        // Daily seed from before the window, then nothing until the market opens.
        val points = build(
            txs = listOf(buy(asml, "10", "600", h(-100)), buy(btc, "1", "50000", h(-100))),
            closes = mapOf(
                asml to listOf(close(h(-20), "650"), close(h(9), "655")),
                btc to (0..12).map { close(h(it), "60000") },
            ),
            from = 0, to = 12,
        )
        assertMoney("66500", points.valueAt(0))
        assertMoney("66500", points.valueAt(8))
        assertMoney("66550", points.valueAt(9))
        assertTrue(points.all { it.value > BigDecimal("60000") })
    }

    @Test
    fun `a purchase inside the window appears at its own hour`() {
        val points = build(
            txs = listOf(buy(btc, "1", "100", h(-48)), buy(btc, "1", "100", h(2) + 60_000)),
            closes = mapOf(btc to listOf(close(h(-1), "100"))),
            from = 0, to = 4,
        )
        assertMoney("100", points.valueAt(2))
        assertMoney("200", points.valueAt(3))
    }

    @Test
    fun `hours before the first transaction produce no point`() {
        val points = build(
            txs = listOf(buy(btc, "1", "100", h(5))),
            closes = mapOf(btc to listOf(close(h(-1), "100"))),
            from = 0, to = 8,
        )
        assertEquals(h(5), points.first().at)
        assertEquals(4, points.size)
    }

    @Test
    fun `with no close at all the price paid stands in`() {
        val points = build(
            txs = listOf(buy(btc, "2", "75", h(-5))),
            closes = emptyMap(),
            from = 0, to = 2,
        )
        assertMoney("150", points.valueAt(1))
    }

    @Test
    fun `classes are kept apart so a scope can be drawn from the same points`() {
        val points = build(
            txs = listOf(buy(asml, "1", "600", h(-10)), buy(btc, "1", "100", h(-10))),
            closes = mapOf(asml to listOf(close(h(-1), "600")), btc to listOf(close(h(-1), "100"))),
            from = 0, to = 0,
        )
        val only = points.single()
        assertMoney("600", only.perClass.getValue(AssetClass.STOCK).value)
        assertMoney("100", only.perClass.getValue(AssetClass.CRYPTO).value)
        assertMoney("700", only.value)
    }

    @Test
    fun `a close in another currency is converted at its own moment`() {
        val fx = FxTable { from, to, at ->
            if (from == to) BigDecimal.ONE
            else if (from == "USD" && to == "EUR") (if (at < h(2)) BigDecimal("0.5") else BigDecimal("0.8"))
            else null
        }
        val points = build(
            txs = listOf(buy(btc, "1", "10", h(-10))),
            closes = mapOf(btc to listOf(close(h(0), "100", "USD"), close(h(2), "100", "USD"))),
            from = 0, to = 2,
            fx = fx,
        )
        assertMoney("50", points.valueAt(1))
        assertMoney("80", points.valueAt(2))
    }

    @Test
    fun `an empty ledger or an inverted window is an empty chart`() {
        assertTrue(build(emptyList(), emptyMap(), 0, 5).isEmpty())
        assertTrue(build(listOf(buy(btc, "1", "1", h(-1))), emptyMap(), 5, 0).isEmpty())
    }
}
