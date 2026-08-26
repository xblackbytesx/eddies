package com.eddies.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

/**
 * Pending staking rewards are a live balance read from the chain, not ledger
 * rows. These pin how that balance joins a holding, because the failure mode is
 * a total that is quietly too high or too low and nothing on screen says which.
 */
class StakingHoldingTest {

    private val ada = AssetIds.crypto("ada-cardano")
    private val asset = Asset(ada, AssetClass.CRYPTO, "ADA", "Cardano", decimals = 6)

    // Quoted in EUR to match the portfolio currency. Leaving it at the USD
    // default would make IdentityFx refuse the conversion and the basis would
    // correctly degrade to zero, which is a different test.
    private fun buy(qty: String, price: String) = Transaction(
        assetId = ada,
        type = TxType.BUY,
        quantity = BigDecimal(qty),
        pricePerUnit = BigDecimal(price),
        quoteCurrency = "EUR",
        timestamp = 1,
    )

    private fun summary(
        txs: List<Transaction>,
        pending: Map<String, BigDecimal> = emptyMap(),
        price: String? = "2",
    ) = PortfolioBuilder.build(
        transactions = txs,
        assets = mapOf(ada to asset),
        prices = price?.let { mapOf(ada to PriceTick(ada, BigDecimal(it), "EUR")) } ?: emptyMap(),
        currency = "EUR",
        stakingPending = pending,
    )

    @Test
    fun `pending rewards are added to the recorded holding`() {
        val holding = summary(listOf(buy("1000", "1")), mapOf(ada to BigDecimal("200"))).holdings.single()
        assertEquals(0, BigDecimal("1000").compareTo(holding.position.quantity))
        assertEquals(0, BigDecimal("200").compareTo(holding.stakingPending))
        assertEquals(0, BigDecimal("1200").compareTo(holding.totalQuantity))
    }

    @Test
    fun `market value counts the pending rewards`() {
        // 1200 ADA at 2 EUR. Valuing only the recorded 1000 would understate the
        // portfolio by the whole staked amount, which is the number being asked for.
        val holding = summary(listOf(buy("1000", "1")), mapOf(ada to BigDecimal("200"))).holdings.single()
        assertEquals(0, BigDecimal("2400").compareTo(holding.marketValue))
    }

    @Test
    fun `rewards cost nothing, so they show up entirely as gain`() {
        // Basis is the 1000 bought at 1. Value is 1200 at 2. Nothing is
        // apportioned to the rewards, because they were never paid for.
        val holding = summary(listOf(buy("1000", "1")), mapOf(ada to BigDecimal("200"))).holdings.single()
        assertEquals(0, BigDecimal("1000").compareTo(holding.position.costBasis))
        assertEquals(0, BigDecimal("1400").compareTo(holding.unrealizedPnl))
    }

    @Test
    fun `staking value is today's price, not a historical one`() {
        val holding = summary(listOf(buy("1000", "1")), mapOf(ada to BigDecimal("200"))).holdings.single()
        assertEquals(0, BigDecimal("400").compareTo(holding.stakingValue))
        assertTrue(holding.hasPendingStaking)
    }

    @Test
    fun `a coin held only as accrued rewards still appears`() {
        // Staking with nothing recorded is a real position. Keying the portfolio
        // off the ledger alone would hide it completely.
        val holding = summary(emptyList(), mapOf(ada to BigDecimal("50"))).holdings.single()
        assertEquals(0, BigDecimal.ZERO.compareTo(holding.position.quantity))
        assertEquals(0, BigDecimal("50").compareTo(holding.totalQuantity))
        assertEquals(0, BigDecimal("100").compareTo(holding.marketValue))
    }

    @Test
    fun `no pending rewards leaves the holding exactly as recorded`() {
        val holding = summary(listOf(buy("1000", "1"))).holdings.single()
        assertEquals(0, BigDecimal("1000").compareTo(holding.totalQuantity))
        assertEquals(0, BigDecimal.ZERO.compareTo(holding.stakingValue))
        assertFalse(holding.hasPendingStaking)
    }

    @Test
    fun `pending rewards for an untracked coin do not invent a holding`() {
        // The asset has to be known, or the row would have no name or icon.
        val summary = summary(
            listOf(buy("1000", "1")),
            mapOf(ada to BigDecimal("10"), AssetIds.crypto("sol-solana") to BigDecimal("5")),
        )
        assertEquals(1, summary.holdings.size)
    }

    @Test
    fun `ledger-recorded rewards and chain-pending rewards both count as earned`() {
        // Someone may have typed in a past reward by hand and also be accruing
        // new ones. Counting only one source understates what was earned.
        val recorded = Transaction(
            assetId = ada,
            type = TxType.STAKING_REWARD,
            quantity = BigDecimal("100"),
            pricePerUnit = BigDecimal("1"),
            quoteCurrency = "EUR",
            timestamp = 2,
        )
        val holding = summary(
            listOf(buy("1000", "1"), recorded),
            mapOf(ada to BigDecimal("200")),
        ).holdings.single()
        assertEquals(0, BigDecimal("300").compareTo(holding.stakingQuantityTotal))
        assertEquals(0, BigDecimal("600").compareTo(holding.stakingValue))
        assertEquals(0, BigDecimal("1300").compareTo(holding.totalQuantity))
    }

    @Test
    fun `a holding with no price has no value rather than a wrong one`() {
        val holding = summary(
            listOf(buy("1000", "1")),
            mapOf(ada to BigDecimal("200")),
            price = null,
        ).holdings.single()
        assertEquals(0, BigDecimal.ZERO.compareTo(holding.marketValue))
        assertEquals(0, BigDecimal.ZERO.compareTo(holding.stakingValue))
        assertEquals(0, BigDecimal("1200").compareTo(holding.totalQuantity))
    }

    @Test
    fun `the portfolio total includes staking across every holding`() {
        val summary = summary(listOf(buy("1000", "1")), mapOf(ada to BigDecimal("200")))
        assertEquals(0, BigDecimal("2400").compareTo(summary.totalValue))
        assertEquals(0, BigDecimal("400").compareTo(summary.totalStakingValue))
    }
}
