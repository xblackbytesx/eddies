package com.eddies.app.data.price

import com.eddies.app.data.db.dao.FxDao
import com.eddies.app.data.db.entity.FxRateEntity
import com.eddies.app.data.prefs.SettingsDataStore
import com.eddies.app.domain.FiatConverter
import com.eddies.app.domain.FxTable
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fiat conversion, from Frankfurter (api.frankfurter.dev): keyless, MIT
 * licensed, self-hostable, and sourced from the ECB's own reference rates.
 *
 * Refreshed once a day, not once a minute. The ECB publishes a single reference
 * set per working day, so polling harder returns the identical number and only
 * tells a third party how often the app is open.
 */
@Singleton
class FxRepository @Inject constructor(
    private val http: HttpClient,
    private val json: Json,
    private val fxDao: FxDao,
    private val settings: SettingsDataStore,
) {

    /** Rates for the current day, as a converter. EUR is the pivot, per the ECB. */
    fun converter(): Flow<FiatConverter> = fxDao.observeLatest().map { rows ->
        FiatConverter(rows.associate { it.quote to BigDecimal(it.rate) }, pivot = PIVOT)
    }

    /**
     * The historical table as a flow, rebuilt only when the stored rates change.
     *
     * Deliberately not a suspend call inside the portfolio combine: prices tick
     * about once a second per asset, and a DB read per tick would turn a cheap
     * recompute into constant disk traffic.
     */
    fun historicalTableFlow(): Flow<FxTable> = fxDao.observeAll().map { rows -> tableOf(rows) }

    /**
     * Historical lookup for the cost basis engine: the rate on or before a
     * transaction's date, so a purchase made in USD two years ago is valued at
     * that day's rate rather than today's.
     */
    suspend fun historicalTable(): FxTable = tableOf(fxDao.all())

    /**
     * Rates for drawing charts, which fall back to the oldest rate held when a
     * date predates everything known. Never use this for a cost basis.
     */
    fun chartTableFlow(): Flow<FxTable> = fxDao.observeAll().map { rows -> tableOf(rows, lenient = true) }

    suspend fun chartTable(): FxTable = tableOf(fxDao.all(), lenient = true)

    private fun tableOf(rows: List<FxRateEntity>, lenient: Boolean = false): FxTable {
        // Parsed and sorted once, here, not per lookup. The value chart asks for
        // a rate per asset per day, and re-parsing every stored rate for each
        // of those questions was most of the cost of drawing it.
        val byQuote = rows.groupBy { it.quote }.mapValues { (_, v) ->
            v.mapNotNull { r -> runCatching { r.day to BigDecimal(r.rate) }.getOrNull() }
                .sortedBy { it.first }
        }
        return FxTable { from, to, atEpochMs ->
            if (from == to) return@FxTable BigDecimal.ONE
            val day = dayOf(atEpochMs)
            val fromRate = rateOn(byQuote, from, day, lenient) ?: return@FxTable null
            val toRate = rateOn(byQuote, to, day, lenient) ?: return@FxTable null
            if (fromRate.signum() == 0) null else toRate.divide(fromRate, com.eddies.app.domain.MC)
        }
    }

    private fun rateOn(
        byQuote: Map<String, List<Pair<String, BigDecimal>>>,
        currency: String,
        day: String,
        lenient: Boolean,
    ): BigDecimal? {
        if (currency == PIVOT) return BigDecimal.ONE
        val byDay = byQuote[currency] ?: return null
        // The rate in force on a date is the last one published on or before it:
        // weekends and holidays have no publication and must not read as a gap.
        //
        // If nothing was published on or before it, the answer is "unknown", not
        // "the oldest rate we happen to hold". Falling back used to value a 2024
        // purchase at a 2026 rate, which is a wrong cost basis that looks
        // perfectly plausible. Unknown degrades to no basis, which is visible.
        return if (lenient) com.eddies.app.domain.HistoricalRates.nearest(byDay, day)
        else com.eddies.app.domain.HistoricalRates.onOrBefore(byDay, day)
    }

    /**
     * Makes sure rates exist back to [earliestNeeded], which is normally the date
     * of the oldest transaction.
     *
     * Without this, a purchase made in a currency other than the base one has no
     * rate for its own date and gets no cost basis at all. Frankfurter serves a
     * whole range in one request, so covering years of ledger is a single call.
     *
     * Asking for a weekend is safe: the API answers with the last publication
     * before it, which is exactly the rate that was in force.
     */
    suspend fun ensureHistoryFrom(earliestNeeded: String) {
        val have = fxDao.earliestDay()
        if (have != null && have <= earliestNeeded) return
        // Fetch a little before the earliest need, so the first transaction is
        // not sitting exactly on the boundary of what is known.
        val from = runCatching {
            java.time.LocalDate.parse(earliestNeeded).minusDays(7).toString()
        }.getOrDefault(earliestNeeded)
        runCatching { fetchRange(from, todayIso()) }
    }

    private suspend fun fetchRange(from: String, to: String) {
        val body = http.get("https://api.frankfurter.dev/v1/$from..$to") {
            parameter("base", PIVOT)
            parameter("symbols", SUPPORTED.joinToString(","))
        }.bodyAsText()

        val rates = json.parseToJsonElement(body).jsonObject["rates"]?.jsonObject ?: return
        val rows = ArrayList<FxRateEntity>()
        for ((day, perDay) in rates) {
            val quotes = perDay as? kotlinx.serialization.json.JsonObject ?: continue
            for ((quote, value) in quotes) {
                value.asBigDecimal()?.let {
                    rows += FxRateEntity(quote = quote, day = day, rate = it.toPlainString())
                }
            }
        }
        if (rows.isNotEmpty()) fxDao.upsert(rows)
    }

    private fun todayIso(): String = dayOf(System.currentTimeMillis())

    /** Fetches today's rates if the cache is older than a day. Safe to call often. */
    suspend fun refreshIfStale(force: Boolean = false) {
        val last = settings.lastFxFetch()
        val age = System.currentTimeMillis() - last
        if (!force && age < REFRESH_INTERVAL_MS && fxDao.latestDay() != null) return
        runCatching { fetchLatest() }
    }

    private suspend fun fetchLatest() {
        val body = http.get("https://api.frankfurter.dev/v1/latest") {
            parameter("base", PIVOT)
            parameter("symbols", SUPPORTED.joinToString(","))
        }.bodyAsText()

        val root = json.parseToJsonElement(body).jsonObject
        val day = root["date"].asStringOrNull() ?: return
        val rates = root["rates"]?.jsonObject ?: return

        val rows = rates.mapNotNull { (quote, value) ->
            value.asBigDecimal()?.let { FxRateEntity(quote = quote, day = day, rate = it.toPlainString()) }
        }
        if (rows.isEmpty()) return
        fxDao.upsert(rows)
        settings.setLastFxFetch(System.currentTimeMillis())
    }

    private fun dayOf(epochMs: Long): String =
        DAY_FORMAT.format(Instant.ofEpochMilli(epochMs).atZone(ZoneOffset.UTC))

    companion object {
        /** Frankfurter mirrors the ECB, which publishes against EUR. */
        const val PIVOT = "EUR"

        /** USD and EUR ship first; the rest cost nothing extra in the same call. */
        val SUPPORTED = listOf(
            "USD", "GBP", "CHF", "JPY", "AUD", "CAD", "SEK", "NOK", "DKK",
            "PLN", "CZK", "NZD", "SGD", "HKD", "ZAR", "BRL", "MXN", "TRY",
        )

        val ALL_CURRENCIES = listOf(PIVOT) + SUPPORTED

        private const val REFRESH_INTERVAL_MS = 12 * 60 * 60 * 1000L
        private val DAY_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    }
}
