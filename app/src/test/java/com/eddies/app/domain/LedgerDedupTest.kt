package com.eddies.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

/**
 * Restoring a backup twice used to double every hand-entered transaction. These
 * tests pin the rule that stops it, and the cases where two rows that look
 * alike really are two.
 */
class LedgerDedupTest {

    private val btc = "crypto:btc-bitcoin"

    private fun tx(
        qty: String = "1.5",
        price: String? = "100",
        at: Long = 1_700_000_000_000,
        type: TxType = TxType.BUY,
        note: String? = null,
        source: TxSource = TxSource.MANUAL,
        accountId: Long = 1,
        cash: String? = null,
        feeAsset: String? = null,
    ) = Transaction(
        accountId = accountId,
        assetId = btc,
        type = type,
        quantity = BigDecimal(qty),
        pricePerUnit = price?.let { BigDecimal(it) },
        quoteCurrency = "EUR",
        timestamp = at,
        note = note,
        source = source,
        cashAmount = cash?.let { BigDecimal(it) },
        feeAssetId = feeAsset,
    )

    @Test
    fun `importing the ledger into itself adds nothing`() {
        val ledger = listOf(tx(), tx(qty = "2", at = 1_700_000_100_000), tx(type = TxType.SELL, qty = "0.5"))
        assertTrue(LedgerDedup.newRows(ledger, ledger).isEmpty())
    }

    @Test
    fun `a row the ledger does not hold is kept`() {
        val fresh = tx(qty = "3", at = 1_700_000_200_000)
        assertEquals(listOf(fresh), LedgerDedup.newRows(listOf(tx()), listOf(tx(), fresh)))
    }

    @Test
    fun `two identical rows in one file are both imported into an empty ledger`() {
        assertEquals(2, LedgerDedup.newRows(emptyList(), listOf(tx(), tx())).size)
    }

    @Test
    fun `a file with two against a ledger with one adds exactly one`() {
        assertEquals(1, LedgerDedup.newRows(listOf(tx()), listOf(tx(), tx())).size)
    }

    @Test
    fun `the account and the source do not make a row different`() {
        // A restore renumbers accounts, and a CSV round trip turns MANUAL into
        // IMPORT_CSV. Neither is a second purchase.
        val here = tx(accountId = 1, source = TxSource.MANUAL)
        val file = tx(accountId = 7, source = TxSource.IMPORT_CSV)
        assertTrue(LedgerDedup.newRows(listOf(here), listOf(file)).isEmpty())
    }

    @Test
    fun `amounts are compared by value, not by how they were written`() {
        assertEquals(
            LedgerDedup.fingerprint(tx(qty = "1.50", price = "100.00")),
            LedgerDedup.fingerprint(tx(qty = "1.5", price = "100")),
        )
        assertEquals(
            LedgerDedup.fingerprint(tx(qty = "0.000", type = TxType.DIVIDEND, cash = "12.30")),
            LedgerDedup.fingerprint(tx(qty = "0", type = TxType.DIVIDEND, cash = "12.3")),
        )
    }

    @Test
    fun `the fee asset survives the uppercasing a csv import applies`() {
        assertEquals(
            LedgerDedup.fingerprint(tx(feeAsset = "crypto:btc-bitcoin")),
            LedgerDedup.fingerprint(tx(feeAsset = "CRYPTO:BTC-BITCOIN")),
        )
    }

    @Test
    fun `anything that changes what happened makes a different row`() {
        val base = LedgerDedup.fingerprint(tx())
        assertNotEquals(base, LedgerDedup.fingerprint(tx(qty = "1.6")))
        assertNotEquals(base, LedgerDedup.fingerprint(tx(price = "101")))
        assertNotEquals(base, LedgerDedup.fingerprint(tx(price = null)))
        assertNotEquals(base, LedgerDedup.fingerprint(tx(at = 1_700_000_000_001)))
        assertNotEquals(base, LedgerDedup.fingerprint(tx(type = TxType.SELL)))
        assertNotEquals(base, LedgerDedup.fingerprint(tx(note = "second lot")))
        assertNotEquals(base, LedgerDedup.fingerprint(tx(cash = "1")))
    }

    @Test
    fun `new rows keep the order of the file`() {
        val a = tx(qty = "1", at = 3)
        val b = tx(qty = "2", at = 1)
        val c = tx(qty = "3", at = 2)
        assertEquals(listOf(a, c), LedgerDedup.newRows(listOf(b), listOf(a, b, c)))
    }
}
