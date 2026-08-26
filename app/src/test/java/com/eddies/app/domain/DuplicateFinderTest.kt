package com.eddies.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Merging moves a user's transactions between assets and cannot be undone, so
 * what gets offered as a duplicate matters more than usual. Offering a false
 * match would silently combine two genuinely different holdings.
 */
class DuplicateFinderTest {

    private fun stock(id: String, symbol: String, name: String = symbol) =
        Asset(id, AssetClass.STOCK, symbol, name, decimals = 4)

    private fun coin(id: String, symbol: String) =
        Asset(id, AssetClass.CRYPTO, symbol, symbol)

    @Test
    fun `the same instrument reached two ways is one group`() {
        // The case this exists for: an ETF added by name through the stock
        // search and by ISIN through the Tradegate tab.
        val viaYahoo = stock("stock:LONDON:IWDA.L", "IWDA.L", "iShares Core MSCI World")
        val viaTradegate = stock("stock:TRADEGATE:IE00B4L5Y983", "IWDA.L", "iShares Core MSCI World")

        val groups = DuplicateFinder.find(
            listOf(viaYahoo, viaTradegate),
            mapOf(viaYahoo.id to 2, viaTradegate.id to 1),
        )
        assertEquals(1, groups.size)
        assertEquals(viaYahoo.id, groups.single().keep.id)
        assertEquals(listOf(viaTradegate.id), groups.single().merge.map { it.id })
    }

    @Test
    fun `the asset with the most history is kept, so the fewest rows move`() {
        val a = stock("stock:LONDON:IWDA.L", "IWDA.L")
        val b = stock("stock:TRADEGATE:IE00B4L5Y983", "IWDA.L")
        val groups = DuplicateFinder.find(listOf(a, b), mapOf(a.id to 1, b.id to 9))
        assertEquals(b.id, groups.single().keep.id)
    }

    @Test
    fun `a tie keeps the plain listing rather than the venue-specific one`() {
        val listing = stock("stock:LONDON:IWDA.L", "IWDA.L")
        val venue = stock("stock:TRADEGATE:IE00B4L5Y983", "IWDA.L")
        val groups = DuplicateFinder.find(listOf(venue, listing), mapOf(listing.id to 2, venue.id to 2))
        assertEquals(listing.id, groups.single().keep.id)
    }

    @Test
    fun `the same ticker on two exchanges is not a duplicate`() {
        // ASML in Amsterdam and its NASDAQ ADR are different instruments in
        // different currencies. Their tickers differ, and that is the point.
        val ams = stock("stock:AMSTERDAM:ASML.AS", "ASML.AS")
        val nasdaq = stock("stock:NASDAQ:ASML", "ASML")
        assertTrue(DuplicateFinder.find(listOf(ams, nasdaq), mapOf(ams.id to 1, nasdaq.id to 1)).isEmpty())
    }

    @Test
    fun `a token and a share sharing a ticker never merge`() {
        // The reason asset ids carry a class, applied again here.
        val token = coin("crypto:meta-metadium", "META")
        val share = stock("stock:NASDAQ:META", "META")
        assertTrue(DuplicateFinder.find(listOf(token, share), mapOf(token.id to 1, share.id to 1)).isEmpty())
    }

    @Test
    fun `matching is on ticker, not on name`() {
        // Two share classes of one fund read almost identically by name and are
        // genuinely different instruments. Matching on name would merge them.
        val acc = stock("stock:LONDON:IWDA.L", "IWDA.L", "iShares Core MSCI World UCITS ETF")
        val dist = stock("stock:LONDON:IWRD.L", "IWRD.L", "iShares Core MSCI World UCITS ETF")
        assertTrue(DuplicateFinder.find(listOf(acc, dist), mapOf(acc.id to 1, dist.id to 1)).isEmpty())
    }

    @Test
    fun `a group with no transactions at all is not offered`() {
        // Nothing to repair, and merging is one way. Tidying is not worth the risk.
        val a = stock("stock:LONDON:IWDA.L", "IWDA.L")
        val b = stock("stock:TRADEGATE:IE00B4L5Y983", "IWDA.L")
        assertTrue(DuplicateFinder.find(listOf(a, b), emptyMap()).isEmpty())
    }

    @Test
    fun `three copies of one instrument come back as a single group`() {
        val a = stock("stock:LONDON:IWDA.L", "IWDA.L")
        val b = stock("stock:TRADEGATE:IE00B4L5Y983", "IWDA.L")
        val c = stock("stock:XETRA:IWDA.L", "IWDA.L")
        val groups = DuplicateFinder.find(
            listOf(a, b, c),
            mapOf(a.id to 3, b.id to 1, c.id to 1),
        )
        assertEquals(1, groups.size)
        assertEquals(2, groups.single().merge.size)
        assertEquals(a.id, groups.single().keep.id)
    }

    @Test
    fun `ticker case does not hide a duplicate`() {
        val a = stock("stock:LONDON:IWDA.L", "iwda.l")
        val b = stock("stock:TRADEGATE:IE00B4L5Y983", "IWDA.L")
        assertEquals(1, DuplicateFinder.find(listOf(a, b), mapOf(a.id to 1, b.id to 1)).size)
    }

    @Test
    fun `an asset with no ticker is ignored rather than grouped with others like it`() {
        // A blank symbol is missing data, not a shared identity.
        val a = stock("stock:X:A", "")
        val b = stock("stock:Y:B", "")
        assertTrue(DuplicateFinder.find(listOf(a, b), mapOf(a.id to 1, b.id to 1)).isEmpty())
    }

    @Test
    fun `different ISINs are never grouped, however alike they look`() {
        // The case that matters most. Several funds from one family, each a
        // genuinely different product, must never be offered as one holding,
        // and a ticker match is not allowed to override that.
        val core = stock("stock:TRADEGATE:IE00B4L5Y983", "IWDA.L", "iShares Core MSCI World")
        val sri = stock("stock:TRADEGATE:IE00BYX2JD69", "IWDA.L", "iShares MSCI World SRI")
        val quality = stock("stock:TRADEGATE:IE00BP3QZ601", "IWDA.L", "iShares Edge MSCI World Quality")

        val groups = DuplicateFinder.find(
            listOf(core, sri, quality),
            mapOf(core.id to 2, sri.id to 1, quality.id to 1),
            mapOf(
                core.id to "IE00B4L5Y983",
                sri.id to "IE00BYX2JD69",
                quality.id to "IE00BP3QZ601",
            ),
        )
        assertTrue("different funds must never be welded together", groups.isEmpty())
    }

    @Test
    fun `one ISIN is one holding even when the symbols disagree`() {
        // A naming lookup can answer with a different listing for the same ISIN
        // on different days, so the symbol is the unreliable half here.
        val london = stock("stock:LONDON:IWDA.L", "IWDA.L")
        val xetra = stock("stock:XETRA:EUNL.DE", "EUNL.DE")
        val groups = DuplicateFinder.find(
            listOf(london, xetra),
            mapOf(london.id to 1, xetra.id to 2),
            mapOf(london.id to "IE00B4L5Y983", xetra.id to "IE00B4L5Y983"),
        )
        assertEquals(1, groups.size)
        assertEquals(xetra.id, groups.single().keep.id)
    }

    @Test
    fun `an unknown ISIN does not attach itself to a known one`() {
        // Only one side carries an ISIN. Grouping on the ticker alone is still
        // allowed here: there is no contradiction, only missing information.
        val known = stock("stock:TRADEGATE:IE00B4L5Y983", "IWDA.L")
        val unknown = stock("stock:LONDON:IWDA.L", "IWDA.L")
        val groups = DuplicateFinder.find(
            listOf(known, unknown),
            mapOf(known.id to 1, unknown.id to 2),
            mapOf(known.id to "IE00B4L5Y983"),
        )
        assertEquals(1, groups.size)
    }

    @Test
    fun `a shared ticker splits cleanly around the ISINs that contradict`() {
        // Three rows on one ticker: two are the same fund, one is not. The pair
        // must still be offered, and the odd one out must stay out of it.
        val a = stock("stock:LONDON:IWDA.L", "IWDA.L")
        val b = stock("stock:TRADEGATE:IE00B4L5Y983", "IWDA.L")
        val other = stock("stock:TRADEGATE:IE00BYX2JD69", "IWDA.L")
        val groups = DuplicateFinder.find(
            listOf(a, b, other),
            mapOf(a.id to 2, b.id to 1, other.id to 3),
            mapOf(b.id to "IE00B4L5Y983", other.id to "IE00BYX2JD69"),
        )
        assertEquals(1, groups.size)
        assertEquals(setOf(a.id, b.id), groups.single().all.map { it.id }.toSet())
    }

    @Test
    fun `ISIN comparison ignores case and stray whitespace`() {
        val a = stock("stock:LONDON:IWDA.L", "IWDA.L")
        val b = stock("stock:XETRA:EUNL.DE", "EUNL.DE")
        val groups = DuplicateFinder.find(
            listOf(a, b),
            mapOf(a.id to 1, b.id to 1),
            mapOf(a.id to " ie00b4l5y983 ", b.id to "IE00B4L5Y983"),
        )
        assertEquals(1, groups.size)
    }

    @Test
    fun `a blank ISIN counts as unknown rather than as a shared identity`() {
        val a = stock("stock:X:AAA", "AAA")
        val b = stock("stock:Y:BBB", "BBB")
        val groups = DuplicateFinder.find(
            listOf(a, b),
            mapOf(a.id to 1, b.id to 1),
            mapOf(a.id to "", b.id to "   "),
        )
        assertTrue(groups.isEmpty())
    }

    @Test
    fun `a clean portfolio produces nothing`() {
        val groups = DuplicateFinder.find(
            listOf(stock("stock:NASDAQ:AAPL", "AAPL"), coin("crypto:btc-bitcoin", "BTC")),
            mapOf("stock:NASDAQ:AAPL" to 4, "crypto:btc-bitcoin" to 7),
        )
        assertTrue(groups.isEmpty())
    }
}
