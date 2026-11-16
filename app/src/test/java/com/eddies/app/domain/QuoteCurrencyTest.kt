package com.eddies.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

/**
 * What a feed's currency label means.
 *
 * Both failures pinned here are silent: one makes a London holding a hundred
 * times too large, the other makes a coin worth nothing.
 */
class QuoteCurrencyTest {

    private fun assertMoney(expected: String, actual: BigDecimal) =
        assertEquals("expected $expected but was $actual", 0, BigDecimal(expected).compareTo(actual))

    @Test
    fun `london pence become pounds`() {
        val money = QuoteCurrency.normalise(BigDecimal("6412.5"), "GBp")

        assertEquals("GBP", money.currency)
        assertMoney("64.125", money.amount)
    }

    @Test
    fun `pounds are left alone`() {
        // The whole difference is the case of one letter.
        val money = QuoteCurrency.normalise(BigDecimal("64.125"), "GBP")

        assertEquals("GBP", money.currency)
        assertMoney("64.125", money.amount)
    }

    @Test
    fun `the other hundredth-quoted exchanges are covered`() {
        assertEquals("GBP", QuoteCurrency.majorCode("GBX"))
        assertEquals("ZAR", QuoteCurrency.majorCode("ZAc"))
        assertEquals("ILS", QuoteCurrency.majorCode("ILA"))
        assertTrue(QuoteCurrency.isMinorUnit("ZAc"))
        assertMoney("1.5", QuoteCurrency.toMajor(BigDecimal("150"), "ILA"))
    }

    @Test
    fun `an ordinary code is uppercased and a missing one falls back`() {
        assertEquals("EUR", QuoteCurrency.majorCode("eur"))
        assertEquals("USD", QuoteCurrency.majorCode(null))
        assertMoney("12", QuoteCurrency.toMajor(BigDecimal("12"), "EUR"))
    }

    @Test
    fun `dollar stablecoins convert as dollars`() {
        assertEquals("USD", QuoteCurrency.fiatOf("USDT"))
        assertEquals("USD", QuoteCurrency.fiatOf("usdc"))
        assertEquals("EUR", QuoteCurrency.fiatOf("EUR"))
    }

    @Test
    fun `a holding priced against a stablecoin has a value`() {
        val btc = AssetIds.crypto("btc-bitcoin")
        val summary = PortfolioBuilder.build(
            transactions = listOf(
                Transaction(
                    assetId = btc, type = TxType.BUY, quantity = BigDecimal.ONE,
                    pricePerUnit = BigDecimal("50000"), quoteCurrency = "EUR", timestamp = 1,
                ),
            ),
            assets = mapOf(btc to Asset(btc, AssetClass.CRYPTO, "BTC", "Bitcoin")),
            prices = mapOf(btc to PriceTick(btc, BigDecimal("70000"), "USDT", at = 2)),
            currency = "EUR",
            fx = FxTable { from, to, _ -> if (from == "USD" && to == "EUR") BigDecimal("0.9") else null },
        )

        val holding = summary.holdings.single()
        // It used to be null: no bank publishes a rate for USDT.
        assertNotNull(holding.price)
        assertMoney("63000", holding.marketValue)
        assertEquals("EUR", holding.price!!.currency)
    }
}
