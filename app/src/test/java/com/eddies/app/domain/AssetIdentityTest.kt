package com.eddies.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AssetIdentityTest {

    private val btc = AssetIds.crypto("btc-bitcoin")
    private val doge = AssetIds.crypto("doge-dogecoin")

    private val resolver = AssetResolver(
        listOf(
            AssetSourceRef(btc, PriceSourceId.KRAKEN, "XBT/USD", "USD"),
            AssetSourceRef(btc, PriceSourceId.KRAKEN, "XBT/EUR", "EUR"),
            AssetSourceRef(btc, PriceSourceId.BINANCE, "BTCUSDT", "USDT"),
            AssetSourceRef(btc, PriceSourceId.COINPAPRIKA, "btc-bitcoin"),
            AssetSourceRef(doge, PriceSourceId.KRAKEN, "XDG/USD", "USD"),
        ),
    )

    @Test
    fun `three sources spelling bitcoin differently resolve to one asset`() {
        assertEquals(btc, resolver.fromSource(PriceSourceId.KRAKEN, "XBT/USD"))
        assertEquals(btc, resolver.fromSource(PriceSourceId.BINANCE, "BTCUSDT"))
        assertEquals(btc, resolver.fromSource(PriceSourceId.COINPAPRIKA, "btc-bitcoin"))
    }

    @Test
    fun `the same spelling on a different source is not assumed to be the same asset`() {
        assertNull(resolver.fromSource(PriceSourceId.BINANCE, "XBT/USD"))
    }

    @Test
    fun `a native quote in the base currency is preferred so no FX hop is needed`() {
        val eur = resolver.toSource(btc, PriceSourceId.KRAKEN, preferredQuote = "EUR")
        assertEquals("XBT/EUR", eur?.sourceSymbol)

        val usd = resolver.toSource(btc, PriceSourceId.KRAKEN, preferredQuote = "USD")
        assertEquals("XBT/USD", usd?.sourceSymbol)
    }

    @Test
    fun `an unavailable preferred quote falls back rather than returning nothing`() {
        val ref = resolver.toSource(btc, PriceSourceId.KRAKEN, preferredQuote = "JPY")
        assertEquals(PriceSourceId.KRAKEN, ref?.source)
    }

    @Test
    fun `an asset the source does not carry resolves to null, not to a guess`() {
        assertNull(resolver.toSource(doge, PriceSourceId.BINANCE))
    }

    @Test
    fun `asset ids are prefixed by class so a ticker collision cannot merge them`() {
        // META is both a token and a share. Without the prefix these collide the
        // moment Phase 3 lands, and a user's shares merge into their tokens.
        val token = AssetIds.crypto("meta-metadium")
        val share = AssetIds.stock("NASDAQ", "META")
        assertNotEquals(token, share)
        assertEquals(AssetClass.CRYPTO, AssetIds.classOf(token))
        assertEquals(AssetClass.STOCK, AssetIds.classOf(share))
        assertEquals(AssetClass.CASH, AssetIds.classOf(AssetIds.cash("eur")))
        assertNull(AssetIds.classOf("nonsense"))
    }

    @Test
    fun `kraken v2 uses market symbols, so REST asset codes are normalised to them`() {
        // Verified against the live socket on 2026-08-25: v2 accepts BTC/EUR and
        // answers "Currency pair not supported XBT/EUR". REST v1 still hands back
        // XXBT and ZEUR, so everything read from there goes through this.
        assertEquals("BTC", KrakenSymbols.toMarketSymbol("XXBT"))
        assertEquals("BTC", KrakenSymbols.toMarketSymbol("XBT"))
        assertEquals("EUR", KrakenSymbols.toMarketSymbol("ZEUR"))
        assertEquals("ETH", KrakenSymbols.toMarketSymbol("XETH"))
        assertEquals("DOGE", KrakenSymbols.toMarketSymbol("XDG"))
        assertEquals("ADA", KrakenSymbols.toMarketSymbol("ADA"))
    }

    @Test
    fun `a v2 pair symbol is built from market symbols with a slash`() {
        assertEquals("BTC/EUR", KrakenSymbols.v2Symbol("BTC", "EUR"))
        assertEquals("ADA/EUR", KrakenSymbols.v2Symbol("ada", "eur"))
    }

    @Test
    fun `stripping the legacy prefix must not eat a real three-letter ticker`() {
        // XRP and ZEC both start with a class-prefix letter. Stripping
        // unconditionally turns them into RP and EC, which are different assets
        // or none at all.
        assertEquals("XRP", KrakenSymbols.toMarketSymbol("XRP"))
        assertEquals("ZEC", KrakenSymbols.toMarketSymbol("ZEC"))
    }
}
