package com.eddies.app.data.backup

import com.eddies.app.domain.AssetClass
import com.eddies.app.domain.KrakenSymbols
import com.eddies.app.domain.QuoteCurrency
import com.eddies.app.domain.TxType
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** The exports this app can read without the user mapping a single column. */
enum class Broker(val label: String) {
    DEGIRO("DEGIRO"),
    KRAKEN("Kraken"),
    BITVAVO("Bitvavo"),
}

/**
 * One row of a broker export, before the instrument has been matched to an
 * asset the app knows.
 *
 * A share is named by [isin], because that is the one identifier a European
 * broker statement and a price feed agree on. A coin is named by [symbol].
 */
data class BrokerRow(
    val assetClass: AssetClass,
    val type: TxType,
    val isin: String? = null,
    val symbol: String? = null,
    val name: String? = null,
    val quantity: BigDecimal,
    val pricePerUnit: BigDecimal? = null,
    val currency: String,
    val fee: BigDecimal? = null,
    val feeCurrency: String? = null,
    val timestamp: Long,
    val externalId: String? = null,
) {
    /** What the importer looks the instrument up by, and what it reports when it cannot. */
    val instrumentKey: String get() = isin ?: symbol.orEmpty()
}

data class BrokerParse(
    val broker: Broker,
    val rows: List<BrokerRow>,
    /** Rows that should have been a transaction and could not be read. */
    val skipped: Int,
    val errors: List<String>,
)

/**
 * Reads the transaction exports of the brokers people actually use, recognized
 * by their header row.
 *
 * The alternative is a column-mapping screen, which asks the user to know what
 * "vol" and "Koers" mean and to get it right on a file with their money in it.
 * A header row identifies its broker unambiguously, so the app can do that work.
 *
 * Pure functions over strings, like [CsvExchange], so every format is pinned by
 * a JVM test.
 *
 * What is deliberately not imported: cash movements (a euro deposit is not a
 * holding), and crypto-to-crypto trades on Kraken, which are two transactions
 * in one row and would need a price in fiat that the file does not carry. Those
 * are counted and reported, never guessed.
 */
object BrokerCsv {

    /** Null when the header is not one of ours, so the caller can try the plain format. */
    fun parse(csv: String, zone: ZoneId = ZoneId.systemDefault()): BrokerParse? {
        val lines = csv.removePrefix("\uFEFF").lineSequence().filter { it.isNotBlank() }.toList()
        if (lines.isEmpty()) return null
        val header = CsvExchange.parseLine(lines.first()).map { it.trim().lowercase() }
        val broker = detect(header) ?: return null
        val body = lines.drop(1).map { CsvExchange.parseLine(it) }
        return when (broker) {
            Broker.DEGIRO -> degiro(header, body, zone)
            Broker.KRAKEN -> kraken(header, body)
            Broker.BITVAVO -> bitvavo(header, body)
        }
    }

    fun detect(header: List<String>): Broker? {
        val h = header.map { it.trim().lowercase() }.toSet()
        return when {
            "isin" in h && "order id" in h -> Broker.DEGIRO
            "txid" in h && "pair" in h && "vol" in h -> Broker.KRAKEN
            "quote currency" in h && "quote price" in h && "fee amount" in h -> Broker.BITVAVO
            else -> null
        }
    }

    // ---- DEGIRO ----------------------------------------------------------

    /**
     * DEGIRO's Transactions export.
     *
     * Its header is translated per account language and several columns have
     * no name at all: the currency of an amount sits in an unnamed column to
     * its right. So columns are found by any of their known names, and a
     * currency by its position next to the amount it belongs to.
     *
     * A sale is a negative quantity. Dates are day-first and times are local
     * to the account, which is taken to be the phone's zone.
     */
    private fun degiro(header: List<String>, body: List<List<String>>, zone: ZoneId): BrokerParse {
        fun col(vararg names: String): Int? =
            header.indexOfFirst { h -> names.any { h == it || h.startsWith(it) } }.takeIf { it >= 0 }

        val dateCol = col("date", "datum")
        val timeCol = col("time", "tijd", "uhrzeit", "heure")
        val productCol = col("product", "produkt", "produit")
        val isinCol = col("isin")
        val qtyCol = col("quantity", "aantal", "anzahl", "quantité")
        val priceCol = col("price", "koers", "kurs", "cours")
        val feeCol = col("transaction and/or third", "transaction costs", "transactiekosten", "transaktionsgeb", "frais de transaction")
        val idCol = col("order id")

        if (dateCol == null || isinCol == null || qtyCol == null || priceCol == null) {
            return BrokerParse(Broker.DEGIRO, emptyList(), body.size, listOf("This DEGIRO file is missing a date, ISIN, quantity or price column."))
        }

        val rows = ArrayList<BrokerRow>()
        val errors = ArrayList<String>()
        var skipped = 0
        for ((n, cells) in body.withIndex()) {
            fun cell(i: Int?): String? = i?.let { cells.getOrNull(it) }?.trim()?.takeIf { it.isNotEmpty() }

            val isin = cell(isinCol)?.uppercase()
            val quantity = cell(qtyCol)?.let(::number)
            val price = cell(priceCol)?.let(::number)
            val date = cell(dateCol)?.let { parseDayFirst(it) }
            if (isin == null || quantity == null || quantity.signum() == 0 || date == null) {
                skipped++
                if (errors.size < MAX_ERRORS) errors += "Row ${n + 2}: no ISIN, quantity or date."
                continue
            }
            val time = cell(timeCol)?.let { runCatching { LocalTime.parse(it) }.getOrNull() } ?: LocalTime.NOON
            val fee = cell(feeCol)?.let(::number)?.abs()?.takeIf { it.signum() > 0 }
            rows += BrokerRow(
                assetClass = AssetClass.STOCK,
                type = if (quantity.signum() > 0) TxType.BUY else TxType.SELL,
                isin = isin,
                name = cell(productCol),
                quantity = quantity.abs(),
                pricePerUnit = price?.abs(),
                currency = cell(priceCol + 1)?.uppercase() ?: "EUR",
                fee = fee,
                feeCurrency = fee?.let { cell(feeCol?.plus(1))?.uppercase() ?: "EUR" },
                timestamp = LocalDateTime.of(date, time).atZone(zone).toInstant().toEpochMilli(),
                // One order can fill in several parts that share an order id, so
                // the id cannot stand as a unique key. Content matching covers it.
                externalId = null,
            )
        }
        return BrokerParse(Broker.DEGIRO, rows, skipped, errors)
    }

    // ---- Kraken ----------------------------------------------------------

    /**
     * Kraken's trades export: one row per fill, `pair` naming both sides.
     *
     * The pair is spelled three ways depending on the age of the account and
     * the export: `XXBTZEUR`, `XBTEUR` and `BTC/EUR`. Times are UTC.
     */
    private fun kraken(header: List<String>, body: List<List<String>>): BrokerParse {
        val idx = header.withIndex().associate { (i, name) -> name to i }
        val rows = ArrayList<BrokerRow>()
        val errors = ArrayList<String>()
        var skipped = 0
        for ((n, cells) in body.withIndex()) {
            fun cell(name: String): String? = idx[name]?.let { cells.getOrNull(it) }?.trim()?.takeIf { it.isNotEmpty() }

            val pair = cell("pair")?.let { splitKrakenPair(it) }
            val type = when (cell("type")?.lowercase()) {
                "buy" -> TxType.BUY
                "sell" -> TxType.SELL
                else -> null
            }
            val volume = cell("vol")?.let(::number)
            val time = cell("time")?.let { parseKrakenTime(it) }
            if (pair == null || type == null || volume == null || volume.signum() <= 0 || time == null) {
                skipped++
                if (errors.size < MAX_ERRORS) errors += "Row ${n + 2}: not a readable trade."
                continue
            }
            val (base, quote) = pair
            if (quote !in FIAT && QuoteCurrency.fiatOf(quote) == quote) {
                skipped++
                if (errors.size < MAX_ERRORS) errors += "Row ${n + 2}: $base for $quote is a coin-to-coin trade, enter it by hand."
                continue
            }
            val currency = QuoteCurrency.fiatOf(quote)
            val fee = cell("fee")?.let(::number)?.takeIf { it.signum() > 0 }
            rows += BrokerRow(
                assetClass = AssetClass.CRYPTO,
                type = type,
                symbol = base,
                quantity = volume,
                pricePerUnit = cell("price")?.let(::number),
                currency = currency,
                fee = fee,
                feeCurrency = fee?.let { currency },
                timestamp = time,
                externalId = cell("txid")?.let { "kraken:$it" },
            )
        }
        return BrokerParse(Broker.KRAKEN, rows, skipped, errors)
    }

    /** Base and quote as market symbols: `XXBTZEUR`, `XBTEUR` and `BTC/EUR` are all (BTC, EUR). */
    internal fun splitKrakenPair(raw: String): Pair<String, String>? {
        val pair = raw.trim().uppercase()
        if ('/' in pair) {
            val (base, quote) = pair.split('/', limit = 2)
            if (base.isEmpty() || quote.isEmpty()) return null
            return KrakenSymbols.toMarketSymbol(base) to KrakenSymbols.toMarketSymbol(quote)
        }
        // Longest suffix first, so ZEUR is tried before EUR and the Z is not
        // left stuck to the base.
        for (suffix in KRAKEN_QUOTES) {
            if (pair.length > suffix.length && pair.endsWith(suffix)) {
                val base = pair.removeSuffix(suffix)
                return KrakenSymbols.toMarketSymbol(base) to KrakenSymbols.toMarketSymbol(suffix)
            }
        }
        return null
    }

    private fun parseKrakenTime(value: String): Long? = runCatching {
        // "2024-01-05 12:34:56.1234": the fraction varies in length, and a
        // second is more than a ledger needs.
        LocalDateTime.parse(value.take(19), KRAKEN_TIME).toInstant(ZoneOffset.UTC).toEpochMilli()
    }.getOrNull()

    // ---- Bitvavo ---------------------------------------------------------

    /**
     * Bitvavo's transaction history: one row per event, the file naming its own
     * timezone in the first column.
     *
     * Buys, sells and staking rewards are imported, and so are coins moved in
     * or out. Euro deposits and withdrawals are passed over without comment:
     * they are not holdings, and they are not errors either.
     */
    private fun bitvavo(header: List<String>, body: List<List<String>>): BrokerParse {
        val idx = header.withIndex().associate { (i, name) -> name to i }
        val rows = ArrayList<BrokerRow>()
        val errors = ArrayList<String>()
        var skipped = 0
        for ((n, cells) in body.withIndex()) {
            fun cell(name: String): String? = idx[name]?.let { cells.getOrNull(it) }?.trim()?.takeIf { it.isNotEmpty() }

            val status = cell("status")?.lowercase()
            if (status != null && status != "completed" && status != "distributed") continue

            val symbol = cell("currency")?.uppercase()
            if (symbol == null || symbol in FIAT) continue

            val type = when (cell("type")?.lowercase()) {
                "buy" -> TxType.BUY
                "sell" -> TxType.SELL
                "staking", "fixed_staking", "fixed staking" -> TxType.STAKING_REWARD
                "deposit" -> TxType.TRANSFER_IN
                "withdrawal" -> TxType.TRANSFER_OUT
                else -> null
            }
            val amount = cell("amount")?.let(::number)
            val time = bitvavoTime(cell("timezone"), cell("date"), cell("time"))
            if (type == null || amount == null || amount.signum() == 0 || time == null) {
                skipped++
                if (errors.size < MAX_ERRORS) errors += "Row ${n + 2}: not a readable transaction."
                continue
            }
            val currency = cell("quote currency")?.uppercase()?.let(QuoteCurrency::fiatOf) ?: "EUR"
            // A fee is only a cost in money when it was charged in money. One
            // charged in the coin is a quantity, and the amount already has it.
            val feeCurrency = cell("fee currency")?.uppercase()
            val fee = cell("fee amount")?.let(::number)?.abs()
                ?.takeIf { it.signum() > 0 && feeCurrency != null && feeCurrency in FIAT }
            rows += BrokerRow(
                assetClass = AssetClass.CRYPTO,
                type = type,
                symbol = symbol,
                quantity = amount.abs(),
                pricePerUnit = cell("quote price")?.let(::number)?.takeIf { it.signum() > 0 },
                currency = currency,
                fee = fee,
                feeCurrency = fee?.let { feeCurrency },
                timestamp = time,
                externalId = cell("transaction id")?.let { "bitvavo:$it" },
            )
        }
        return BrokerParse(Broker.BITVAVO, rows, skipped, errors)
    }

    private fun bitvavoTime(zoneName: String?, date: String?, time: String?): Long? = runCatching {
        val zone = zoneName?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: ZoneOffset.UTC
        val day = LocalDate.parse(date)
        val clock = time?.take(8)?.let { LocalTime.parse(it) } ?: LocalTime.NOON
        LocalDateTime.of(day, clock).atZone(zone).toInstant().toEpochMilli()
    }.getOrNull()

    // ---- shared ----------------------------------------------------------

    /**
     * A number as either convention writes it.
     *
     * The same broker writes 1,234.56 for an English account and 1.234,56 for a
     * Dutch one. When both separators appear, the last one is the decimal. A
     * lone comma is a decimal comma, which is right for every file this reads:
     * none of them group thousands without also writing decimals.
     */
    internal fun number(raw: String): BigDecimal? {
        val s = raw.trim().replace(" ", "").replace("\u00A0", "")
        if (s.isEmpty()) return null
        val comma = s.lastIndexOf(',')
        val dot = s.lastIndexOf('.')
        val normal = when {
            comma >= 0 && dot >= 0 ->
                if (comma > dot) s.replace(".", "").replace(',', '.') else s.replace(",", "")
            comma >= 0 -> s.replace(',', '.')
            else -> s
        }
        return runCatching { BigDecimal(normal) }.getOrNull()
    }

    private fun parseDayFirst(value: String): LocalDate? =
        DAY_FIRST.firstNotNullOfOrNull { format -> runCatching { LocalDate.parse(value, format) }.getOrNull() }

    private const val MAX_ERRORS = 5

    private val DAY_FIRST = listOf(
        DateTimeFormatter.ofPattern("dd-MM-yyyy"),
        DateTimeFormatter.ofPattern("dd/MM/yyyy"),
        DateTimeFormatter.ofPattern("dd.MM.yyyy"),
    )

    private val KRAKEN_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    private val FIAT = setOf("EUR", "USD", "GBP", "CHF", "CAD", "AUD", "JPY")

    private val KRAKEN_QUOTES = listOf(
        "ZEUR", "ZUSD", "ZGBP", "ZCAD", "ZJPY", "ZAUD",
        "USDT", "USDC", "XXBT", "XETH",
        "EUR", "USD", "GBP", "CHF", "CAD", "JPY", "AUD", "XBT", "ETH", "BTC", "DAI",
    )
}
