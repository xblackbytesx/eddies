package com.eddies.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AssetIdentityTest {

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
    fun `the REST OHLC endpoint takes the legacy spelling, not the socket one`() {
        // Verified 2026-08-25: /0/public/OHLC wants XBTEUR and answers with the
        // key XXBTZEUR. Sending the v2 socket's BTC/EUR returns "Unknown asset
        // pair" and the chart silently stays empty. Same trap as wsname, other
        // direction, which is why both mappings exist.
        assertEquals("XBTEUR", KrakenSymbols.restPair("BTC", "EUR"))
        assertEquals("XBTUSD", KrakenSymbols.restPair("BTC", "USD"))
        assertEquals("XDGEUR", KrakenSymbols.restPair("DOGE", "EUR"))
        assertEquals("ADAEUR", KrakenSymbols.restPair("ADA", "EUR"))
    }

    @Test
    fun `the socket and REST spellings round trip against each other`() {
        // BTC -> XBT -> BTC. If these ever disagree, one of the two endpoints
        // stops resolving and nothing in the UI says which.
        val market = "BTC"
        val legacy = KrakenSymbols.toLegacySymbol(market)
        assertEquals("XBT", legacy)
        assertEquals(market, KrakenSymbols.toMarketSymbol(legacy))
        assertEquals("BTC/EUR", KrakenSymbols.v2Symbol(market, "EUR"))
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
