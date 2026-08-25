package com.eddies.app.domain

import com.eddies.app.data.db.entity.CustodyType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

/**
 * The custody screen answers one question: where are my things, and how much is
 * in each place. A coin that goes missing from that answer is the exact failure
 * the feature exists to prevent, so the grouping is pinned here.
 */
class CustodyGrouperTest {

    private fun holding(symbol: String, quantity: String, price: String): Holding {
        val id = AssetIds.crypto("${symbol.lowercase()}-$symbol")
        val asset = Asset(id, AssetClass.CRYPTO, symbol.uppercase(), symbol)
        val position = PositionCalculator.fold(
            txs = listOf(
                Transaction(
                    assetId = id,
                    type = TxType.BUY,
                    quantity = BigDecimal(quantity),
                    pricePerUnit = BigDecimal("1"),
                    timestamp = 1,
                ),
            ),
            method = CostBasisMethod.AVERAGE,
            baseCurrency = "EUR",
            assetId = id,
        )
        return Holding(
            asset = asset,
            position = position,
            price = PriceTick(id, BigDecimal(price), "EUR"),
            currency = "EUR",
        )
    }

    private val btc = holding("btc", "2", "100")     // 200
    private val eth = holding("eth", "10", "10")     // 100
    private val ada = holding("ada", "100", "1")     // 100

    private fun custody(vararg pairs: Pair<Holding, Pair<CustodyType, String>>) =
        pairs.associate { (h, c) -> h.asset.id to c }

    @Test
    fun `coins in the same place are grouped and their values summed`() {
        val groups = CustodyGrouper.group(
            listOf(btc, eth),
            custody(
                btc to (CustodyType.HARDWARE_WALLET to "Ledger"),
                eth to (CustodyType.HARDWARE_WALLET to "Ledger"),
            ),
        )
        assertEquals(1, groups.size)
        assertEquals("Ledger", groups[0].label)
        assertEquals(2, groups[0].holdings.size)
        assertEquals(0, BigDecimal("300").compareTo(groups[0].value))
    }

    @Test
    fun `the same name under different kinds stays separate`() {
        // "Kraken the exchange" and "Kraken" written on a paper backup are not
        // the same place, and merging them would be a lie about where coins are.
        val groups = CustodyGrouper.group(
            listOf(btc, eth),
            custody(
                btc to (CustodyType.EXCHANGE to "Kraken"),
                eth to (CustodyType.COLD_STORAGE to "Kraken"),
            ),
        )
        assertEquals(2, groups.size)
    }

    @Test
    fun `groups are ordered by value, biggest first`() {
        val groups = CustodyGrouper.group(
            listOf(btc, eth),
            custody(
                eth to (CustodyType.EXCHANGE to "Kraken"),
                btc to (CustodyType.HARDWARE_WALLET to "Ledger"),
            ),
        )
        assertEquals("Ledger", groups[0].label)
        assertEquals("Kraken", groups[1].label)
    }

    @Test
    fun `a coin with no location recorded is still shown, in a trailing group`() {
        // Dropping it would hide exactly the coin the user has forgotten about.
        val groups = CustodyGrouper.group(
            listOf(btc, ada),
            custody(btc to (CustodyType.HARDWARE_WALLET to "Ledger")),
        )
        assertEquals(2, groups.size)
        assertEquals(CustodyGrouper.UNASSIGNED, groups.last().label)
        assertEquals("ADA", groups.last().holdings.single().asset.symbol)
    }

    @Test
    fun `the unassigned group sorts last even when it is the largest`() {
        // It is a prompt to fill something in, not a place, so it does not lead.
        val groups = CustodyGrouper.group(
            listOf(btc, eth),
            custody(eth to (CustodyType.EXCHANGE to "Kraken")),
        )
        assertEquals(CustodyGrouper.UNASSIGNED, groups.last().label)
        assertTrue(groups.last().value > groups.first().value)
    }

    @Test
    fun `nothing recorded at all still lists every holding`() {
        val groups = CustodyGrouper.group(listOf(btc, eth, ada), emptyMap())
        assertEquals(1, groups.size)
        assertEquals(3, groups.single().holdings.size)
        assertEquals(0, BigDecimal("400").compareTo(groups.single().value))
    }

    @Test
    fun `an empty portfolio produces no groups rather than an empty placeholder`() {
        assertTrue(CustodyGrouper.group(emptyList(), emptyMap()).isEmpty())
    }

    @Test
    fun `holdings inside a group are ordered by value`() {
        val groups = CustodyGrouper.group(
            listOf(eth, btc),
            custody(
                eth to (CustodyType.EXCHANGE to "Kraken"),
                btc to (CustodyType.EXCHANGE to "Kraken"),
            ),
        )
        assertEquals("BTC", groups.single().holdings.first().asset.symbol)
    }

    @Test
    fun `a location recorded for a coin no longer held does not invent a group`() {
        // Selling out of a coin should not leave an empty place on the screen.
        val groups = CustodyGrouper.group(
            listOf(btc),
            custody(
                btc to (CustodyType.HARDWARE_WALLET to "Ledger"),
                eth to (CustodyType.EXCHANGE to "Kraken"),
            ),
        )
        assertEquals(1, groups.size)
        assertEquals("Ledger", groups.single().label)
    }
}
