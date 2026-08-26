package com.eddies.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

/**
 * Crypto and stocks share one ledger, one price pipeline and one set of totals.
 * These pin that they coexist without contaminating each other: a per-class
 * figure and the combined figure are derived from the same holdings, so they can
 * never disagree, and neither class can silently absorb the other's assets.
 */
class PortfolioScopeTest {

    private val btc = AssetIds.crypto("btc-bitcoin")
    private val aapl = AssetIds.stock("NASDAQ", "AAPL")
    private val asmlAms = AssetIds.stock("AMSTERDAM", "ASML.AS")

    private val assets = mapOf(
        btc to Asset(btc, AssetClass.CRYPTO, "BTC", "Bitcoin"),
        aapl to Asset(aapl, AssetClass.STOCK, "AAPL", "Apple", decimals = 4),
        asmlAms to Asset(asmlAms, AssetClass.STOCK, "ASML.AS", "ASML Holding", decimals = 4),
    )

    private fun buy(assetId: String, qty: String, price: String) = Transaction(
        assetId = assetId,
        type = TxType.BUY,
        quantity = BigDecimal(qty),
        pricePerUnit = BigDecimal(price),
        quoteCurrency = "EUR",
        timestamp = 1,
    )

    private fun summary(vararg txs: Transaction) = PortfolioBuilder.build(
        transactions = txs.toList(),
        assets = assets,
        prices = mapOf(
            btc to PriceTick(btc, BigDecimal("100"), "EUR"),
            aapl to PriceTick(aapl, BigDecimal("10"), "EUR"),
            asmlAms to PriceTick(asmlAms, BigDecimal("20"), "EUR"),
        ),
        currency = "EUR",
    )

    @Test
    fun `scope matching keys off the asset class prefix, not the ticker`() {
        assertTrue(PortfolioScope.CRYPTO.matches(btc))
        assertFalse(PortfolioScope.CRYPTO.matches(aapl))
        assertTrue(PortfolioScope.STOCKS.matches(aapl))
        assertFalse(PortfolioScope.STOCKS.matches(btc))
        assertTrue(PortfolioScope.ALL.matches(btc))
        assertTrue(PortfolioScope.ALL.matches(aapl))
    }

    @Test
    fun `the same ticker on two exchanges stays two holdings`() {
        // ASML trades in Amsterdam in euros and as an ADR on NASDAQ in dollars.
        // They are different instruments at different prices, and merging them
        // would be a wrong share count in a wrong currency.
        val nasdaqAsml = AssetIds.stock("NASDAQ", "ASML")
        assertTrue(nasdaqAsml != asmlAms)
        assertEquals(AssetClass.STOCK, AssetIds.classOf(nasdaqAsml))
        assertEquals(AssetClass.STOCK, AssetIds.classOf(asmlAms))
    }

    @Test
    fun `a token and a share with the same ticker never collide`() {
        // The reason asset ids are prefixed by class at all.
        assertTrue(AssetIds.crypto("meta-metadium") != AssetIds.stock("NASDAQ", "META"))
    }

    @Test
    fun `per-class totals sum to the combined total`() {
        // The invariant the combined view rests on. If these ever drift, one of
        // the two screens is lying and nothing says which.
        val s = summary(buy(btc, "2", "50"), buy(aapl, "10", "8"), buy(asmlAms, "5", "15"))
        val perClass = s.perClass()
        val summed = perClass.values.fold(BigDecimal.ZERO) { acc, t -> acc + t.value }
        assertEquals(0, s.totalValue.compareTo(summed))
    }

    @Test
    fun `classes are totalled separately and correctly`() {
        val s = summary(buy(btc, "2", "50"), buy(aapl, "10", "8"), buy(asmlAms, "5", "15"))
        val perClass = s.perClass()
        // 2 BTC at 100.
        assertEquals(0, BigDecimal("200").compareTo(perClass[AssetClass.CRYPTO]!!.value))
        // 10 AAPL at 10, plus 5 ASML at 20.
        assertEquals(0, BigDecimal("200").compareTo(perClass[AssetClass.STOCK]!!.value))
        assertEquals(1, perClass[AssetClass.CRYPTO]!!.holdings)
        assertEquals(2, perClass[AssetClass.STOCK]!!.holdings)
    }

    @Test
    fun `cost and profit are tracked per class, not pooled`() {
        val s = summary(buy(btc, "2", "50"), buy(aapl, "10", "8"))
        val perClass = s.perClass()
        // Crypto: cost 100, value 200.
        assertEquals(0, BigDecimal("100").compareTo(perClass[AssetClass.CRYPTO]!!.costBasis))
        assertEquals(0, BigDecimal("100").compareTo(perClass[AssetClass.CRYPTO]!!.unrealizedPnl))
        // Stocks: cost 80, value 100.
        assertEquals(0, BigDecimal("80").compareTo(perClass[AssetClass.STOCK]!!.costBasis))
        assertEquals(0, BigDecimal("20").compareTo(perClass[AssetClass.STOCK]!!.unrealizedPnl))
    }

    @Test
    fun `holding only one class means no combined view is offered`() {
        assertFalse(summary(buy(btc, "1", "50")).isMixed)
        assertTrue(summary(buy(btc, "1", "50"), buy(aapl, "1", "8")).isMixed)
    }

    @Test
    fun `an empty portfolio has no classes rather than empty ones`() {
        val s = summary()
        assertTrue(s.perClass().isEmpty())
        assertFalse(s.isMixed)
    }

    @Test
    fun `income unifies staking and dividends into one earned figure`() {
        // A coin earns rewards, a share pays dividends. Both are earned rather
        // than bought, so the combined view adds them rather than making the
        // user hold two ideas at once.
        val reward = Transaction(
            assetId = btc, type = TxType.STAKING_REWARD, quantity = BigDecimal("1"),
            pricePerUnit = BigDecimal("50"), quoteCurrency = "EUR", timestamp = 2,
        )
        val dividend = Transaction(
            assetId = aapl, type = TxType.DIVIDEND, quantity = BigDecimal.ZERO,
            cashAmount = BigDecimal("15"), quoteCurrency = "EUR", timestamp = 2,
        )
        val s = summary(buy(btc, "2", "50"), reward, buy(aapl, "10", "8"), dividend)
        val perClass = s.perClass()
        // One BTC of rewards at 100.
        assertEquals(0, BigDecimal("100").compareTo(perClass[AssetClass.CRYPTO]!!.income))
        assertEquals(0, BigDecimal("15").compareTo(perClass[AssetClass.STOCK]!!.income))
    }

    @Test
    fun `a class with no price still counts its cost, and does not poison the other`() {
        val unpriced = PortfolioBuilder.build(
            transactions = listOf(buy(btc, "2", "50"), buy(aapl, "10", "8")),
            assets = assets,
            prices = mapOf(btc to PriceTick(btc, BigDecimal("100"), "EUR")),
            currency = "EUR",
        )
        val perClass = unpriced.perClass()
        assertEquals(0, BigDecimal("200").compareTo(perClass[AssetClass.CRYPTO]!!.value))
        assertEquals(0, BigDecimal.ZERO.compareTo(perClass[AssetClass.STOCK]!!.value))
        assertEquals(0, BigDecimal("80").compareTo(perClass[AssetClass.STOCK]!!.costBasis))
    }
}
