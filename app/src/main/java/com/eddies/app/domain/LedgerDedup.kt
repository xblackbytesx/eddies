package com.eddies.app.domain

import java.math.BigDecimal

/**
 * Decides which rows of an import are not in the ledger yet.
 *
 * The database can only refuse a duplicate that carries an externalId, and a
 * row typed in by hand has none. Restoring the same backup twice, or importing
 * the CSV that was just exported, therefore used to double every manual
 * transaction, and a doubled ledger is a doubled net worth that looks entirely
 * plausible.
 *
 * So rows are matched on what they say rather than on an id they do not have.
 *
 * Counted, not just matched. Two identical buys in the same minute are unusual
 * but real, and a file that holds two of them must still produce two. A row is
 * skipped only as many times as the ledger already holds it: a file with two
 * against a ledger with one adds one.
 */
object LedgerDedup {

    /**
     * What makes two rows the same transaction.
     *
     * Leaves out the account, because account ids are per device and a restore
     * renumbers them, and the source, because the same purchase is MANUAL where
     * it was typed and IMPORT_CSV once it has been through a spreadsheet.
     * Amounts are compared by value, so 1.50 and 1.5 are one number.
     */
    fun fingerprint(tx: Transaction): String = listOf(
        tx.assetId,
        tx.type.name,
        tx.quantity.canonical(),
        tx.pricePerUnit?.canonical().orEmpty(),
        tx.quoteCurrency.trim().uppercase(),
        tx.feeQuantity?.canonical().orEmpty(),
        // CSV import uppercases this column, so compare it that way.
        tx.feeAssetId?.trim()?.uppercase().orEmpty(),
        tx.timestamp.toString(),
        tx.cashAmount?.canonical().orEmpty(),
        tx.note?.trim().orEmpty(),
    ).joinToString("\u001F")

    /** The rows of [incoming] that [existing] does not already hold, in their original order. */
    fun newRows(existing: List<Transaction>, incoming: List<Transaction>): List<Transaction> {
        val held = HashMap<String, Int>()
        for (tx in existing) held.merge(fingerprint(tx), 1, Int::plus)

        val out = ArrayList<Transaction>()
        for (tx in incoming) {
            val key = fingerprint(tx)
            val left = held[key] ?: 0
            if (left > 0) held[key] = left - 1 else out += tx
        }
        return out
    }

    private fun BigDecimal.canonical(): String =
        if (signum() == 0) "0" else stripTrailingZeros().toPlainString()
}
