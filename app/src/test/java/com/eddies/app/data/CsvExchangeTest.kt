package com.eddies.app.data

import com.eddies.app.data.backup.CsvExchange
import com.eddies.app.domain.Asset
import com.eddies.app.domain.AssetClass
import com.eddies.app.domain.Transaction
import com.eddies.app.domain.TxSource
import com.eddies.app.domain.TxType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

/**
 * CSV is the escape hatch to a spreadsheet or a tax tool, so it has to survive a
 * round trip and tolerate a file a human has edited on the way.
 */
class CsvExchangeTest {

    private val btc = "crypto:btc-bitcoin"
    private val assets = mapOf(
        btc to Asset(btc, AssetClass.CRYPTO, "BTC", "Bitcoin"),
    )

    private fun tx(
        qty: String = "1.5",
        price: String? = "100",
        type: TxType = TxType.BUY,
        note: String? = null,
    ) = Transaction(
        assetId = btc,
        type = type,
        quantity = BigDecimal(qty),
        pricePerUnit = price?.let { BigDecimal(it) },
        quoteCurrency = "EUR",
        timestamp = 1_700_000_000_000,
        note = note,
    )

    @Test
    fun `a round trip preserves the numbers exactly`() {
        val csv = CsvExchange.export(listOf(tx()), assets)
        val result = CsvExchange.import(csv)
        assertEquals(0, result.skipped)
        assertEquals(1, result.transactions.size)
        val back = result.transactions.first()
        assertEquals(0, BigDecimal("1.5").compareTo(back.quantity))
        assertEquals(0, BigDecimal("100").compareTo(back.pricePerUnit!!))
        assertEquals(btc, back.assetId)
        assertEquals(TxType.BUY, back.type)
        assertEquals("EUR", back.quoteCurrency)
        assertEquals(1_700_000_000_000, back.timestamp)
    }

    @Test
    fun `an 18-decimal quantity survives the round trip`() {
        val csv = CsvExchange.export(listOf(tx(qty = "0.123456789012345678")), assets)
        val back = CsvExchange.import(csv).transactions.first()
        assertEquals("0.123456789012345678", back.quantity.toPlainString())
    }

    @Test
    fun `imported rows are marked as coming from a CSV`() {
        val csv = CsvExchange.export(listOf(tx()), assets)
        assertEquals(TxSource.IMPORT_CSV, CsvExchange.import(csv).transactions.first().source)
    }

    @Test
    fun `a note containing a comma and a quote is escaped and read back intact`() {
        // Without quoting, one note with a comma shifts every later column by one
        // and the price silently becomes the currency.
        val note = """Bought the dip, "again", twice"""
        val csv = CsvExchange.export(listOf(tx(note = note)), assets)
        val back = CsvExchange.import(csv).transactions.first()
        assertEquals(note, back.note)
    }

    @Test
    fun `a reordered header still maps to the right columns`() {
        val csv = """
            type,quantity,asset_id,currency,price_per_unit
            SELL,2,$btc,USD,50
        """.trimIndent()
        val back = CsvExchange.import(csv).transactions.first()
        assertEquals(TxType.SELL, back.type)
        assertEquals(0, BigDecimal("2").compareTo(back.quantity))
        assertEquals("USD", back.quoteCurrency)
        assertEquals(0, BigDecimal("50").compareTo(back.pricePerUnit!!))
    }

    @Test
    fun `a comma decimal separator is accepted`() {
        // Half of Europe writes it this way, and a spreadsheet will happily emit it.
        val csv = "quantity,asset_id\n\"1,5\",$btc"
        val back = CsvExchange.import(csv).transactions.first()
        assertEquals(0, BigDecimal("1.5").compareTo(back.quantity))
    }

    @Test
    fun `a missing type defaults to buy rather than dropping the row`() {
        val csv = "quantity,asset_id\n1,$btc"
        val back = CsvExchange.import(csv).transactions.first()
        assertEquals(TxType.BUY, back.type)
    }

    @Test
    fun `unreadable rows are counted and reported, never silently dropped`() {
        // A partial import that says nothing is how a ledger ends up quietly wrong.
        val csv = """
            quantity,asset_id
            1,$btc
            ,$btc
            notanumber,$btc
            0,$btc
        """.trimIndent()
        val result = CsvExchange.import(csv)
        assertEquals(1, result.transactions.size)
        assertEquals(3, result.skipped)
        assertTrue(result.errors.isNotEmpty())
    }

    @Test
    fun `a file with no usable columns is refused with an explanation`() {
        val result = CsvExchange.import("date,memo\n2026-01-01,hello")
        assertTrue(result.transactions.isEmpty())
        assertTrue(result.errors.first().contains("quantity"))
    }

    @Test
    fun `an empty file is refused rather than throwing`() {
        val result = CsvExchange.import("")
        assertTrue(result.transactions.isEmpty())
        assertTrue(result.errors.isNotEmpty())
    }

    @Test
    fun `a date column is used when there is no epoch timestamp`() {
        val csv = "quantity,asset_id,date\n1,$btc,2026-03-01 12:00:00"
        val back = CsvExchange.import(csv).transactions.first()
        assertNotNull(back.timestamp)
        assertTrue("timestamp should be parsed, not defaulted to now", back.timestamp < System.currentTimeMillis())
    }

    @Test
    fun `quoted fields with embedded separators parse correctly`() {
        assertEquals(listOf("a", "b,c", "d"), CsvExchange.parseLine("""a,"b,c",d"""))
        assertEquals(listOf("a", "b\"c"), CsvExchange.parseLine("""a,"b""c""""))
        assertEquals(listOf("", "", ""), CsvExchange.parseLine(",,"))
    }

    @Test
    fun `the export header names every column it writes`() {
        val csv = CsvExchange.export(listOf(tx()), assets)
        val header = csv.lineSequence().first().split(",")
        val firstRow = CsvExchange.parseLine(csv.lineSequence().drop(1).first())
        assertEquals(header.size, firstRow.size)
    }
}
