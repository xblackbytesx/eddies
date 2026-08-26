package com.eddies.app.data.backup

import com.eddies.app.domain.Asset
import com.eddies.app.domain.Transaction
import com.eddies.app.domain.TxSource
import com.eddies.app.domain.TxType
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Plain CSV, unencrypted and clearly labelled as such in the UI.
 *
 * People need their ledger in a spreadsheet or a tax tool, and an encrypted
 * blob cannot go there. This is the deliberate escape hatch, not a second-class
 * backup: the encrypted file is what protects the data, this is what moves it.
 *
 * Pure functions over strings so both directions are JVM-testable.
 */
object CsvExchange {

    private val HEADER = listOf(
        "timestamp", "date", "type", "asset_id", "symbol",
        "quantity", "price_per_unit", "currency", "fee", "fee_currency", "cash_amount", "note",
    )

    private val DATE_FORMAT: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneOffset.UTC)

    fun export(transactions: List<Transaction>, assets: Map<String, Asset>): String {
        val sb = StringBuilder()
        sb.append(HEADER.joinToString(",")).append('\n')
        for (tx in transactions.sortedBy { it.timestamp }) {
            val row = listOf(
                tx.timestamp.toString(),
                DATE_FORMAT.format(Instant.ofEpochMilli(tx.timestamp)),
                tx.type.name,
                tx.assetId,
                assets[tx.assetId]?.symbol.orEmpty(),
                tx.quantity.toPlainString(),
                tx.pricePerUnit?.toPlainString().orEmpty(),
                tx.quoteCurrency,
                tx.feeQuantity?.toPlainString().orEmpty(),
                tx.feeAssetId.orEmpty(),
                tx.cashAmount?.toPlainString().orEmpty(),
                tx.note.orEmpty(),
            )
            sb.append(row.joinToString(",") { escape(it) }).append('\n')
        }
        return sb.toString()
    }

    data class ImportResult(val transactions: List<Transaction>, val skipped: Int, val errors: List<String>)

    /**
     * Reads back what [export] wrote, and tolerates a file a human has edited:
     * reordered columns, missing optional ones, a comma decimal separator.
     *
     * Rows that cannot be understood are counted and reported rather than
     * silently dropped. A partial import that says nothing is how a ledger ends
     * up quietly wrong.
     */
    fun import(csv: String, defaultCurrency: String = "EUR"): ImportResult {
        val lines = csv.lineSequence().filter { it.isNotBlank() }.toList()
        if (lines.isEmpty()) return ImportResult(emptyList(), 0, listOf("The file is empty."))

        val header = parseLine(lines.first()).map { it.trim().lowercase() }
        val idx = header.withIndex().associate { (i, name) -> name to i }
        if ("quantity" !in idx || ("asset_id" !in idx && "symbol" !in idx)) {
            return ImportResult(
                emptyList(), 0,
                listOf("Needs at least a 'quantity' column and one of 'asset_id' or 'symbol'."),
            )
        }

        val out = ArrayList<Transaction>()
        val errors = ArrayList<String>()
        var skipped = 0

        for ((n, line) in lines.drop(1).withIndex()) {
            val cells = parseLine(line)
            fun cell(name: String): String? =
                idx[name]?.let { cells.getOrNull(it) }?.trim()?.takeIf { it.isNotEmpty() }

            val quantity = cell("quantity")?.toDecimal()
            val assetId = cell("asset_id")
            val cash = cell("cash_amount")?.toDecimal()
            val type = cell("type")?.let { runCatching { TxType.valueOf(it.uppercase()) }.getOrNull() }
                ?: TxType.BUY
            // A dividend is cash with no share movement, so requiring a quantity
            // would silently drop every dividend row on import.
            val needsQuantity = type != TxType.DIVIDEND
            val quantityOk = if (needsQuantity) quantity != null && quantity.signum() > 0
            else cash != null && cash.signum() > 0
            if (!quantityOk || assetId == null) {
                skipped++
                if (errors.size < 5) errors += "Row ${n + 2}: missing an amount or an asset id."
                continue
            }
            out += Transaction(
                accountId = 0,
                assetId = assetId,
                type = type,
                quantity = quantity ?: BigDecimal.ZERO,
                pricePerUnit = cell("price_per_unit")?.toDecimal(),
                quoteCurrency = cell("currency")?.uppercase() ?: defaultCurrency,
                feeQuantity = cell("fee")?.toDecimal(),
                feeAssetId = cell("fee_currency")?.uppercase(),
                cashAmount = cell("cash_amount")?.toDecimal(),
                timestamp = cell("timestamp")?.toLongOrNull()
                    ?: cell("date")?.let { parseDate(it) }
                    ?: System.currentTimeMillis(),
                note = cell("note"),
                source = TxSource.IMPORT_CSV,
                // No externalId: a hand-edited CSV has no stable identity, so
                // re-importing the same file legitimately adds the rows again.
                externalId = null,
            )
        }
        return ImportResult(out, skipped, errors)
    }

    private fun String.toDecimal(): BigDecimal? =
        replace(',', '.').replace(" ", "").let { runCatching { BigDecimal(it) }.getOrNull() }

    private fun parseDate(value: String): Long? = runCatching {
        java.time.LocalDateTime.parse(value, DATE_FORMAT).toInstant(ZoneOffset.UTC).toEpochMilli()
    }.getOrNull() ?: runCatching {
        java.time.LocalDate.parse(value).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    }.getOrNull()

    /** Minimal RFC 4180: quoted fields, doubled quotes inside them. */
    internal fun parseLine(line: String): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                inQuotes && c == '"' && i + 1 < line.length && line[i + 1] == '"' -> {
                    sb.append('"'); i++
                }
                c == '"' -> inQuotes = !inQuotes
                c == ',' && !inQuotes -> { out += sb.toString(); sb.clear() }
                else -> sb.append(c)
            }
            i++
        }
        out += sb.toString()
        return out
    }

    private fun escape(value: String): String =
        if (value.any { it == ',' || it == '"' || it == '\n' }) {
            "\"" + value.replace("\"", "\"\"") + "\""
        } else {
            value
        }
}
