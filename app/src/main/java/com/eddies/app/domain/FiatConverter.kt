package com.eddies.app.domain

import java.math.BigDecimal

/**
 * Converts between fiat currencies using a rate table.
 *
 * Every rate the app holds is expressed against a single pivot (EUR, because
 * that is what the ECB publishes), so an arbitrary pair is two lookups rather
 * than an N-squared table. Pure, so the arithmetic is unit-tested and the
 * network side stays in data/price/FxRepository.
 */
class FiatConverter(
    /** Units of [currency] per one unit of the pivot. The pivot maps to ONE. */
    private val perPivot: Map<String, BigDecimal>,
    private val pivot: String = "EUR",
) {

    fun rate(from: String, to: String): BigDecimal? {
        if (from == to) return BigDecimal.ONE
        val fromRate = rateAgainstPivot(from) ?: return null
        val toRate = rateAgainstPivot(to) ?: return null
        if (fromRate.signum() == 0) return null
        return toRate.divide(fromRate, MC)
    }

    fun convert(amount: BigDecimal, from: String, to: String): BigDecimal? =
        rate(from, to)?.let { amount * it }

    private fun rateAgainstPivot(currency: String): BigDecimal? =
        if (currency == pivot) BigDecimal.ONE else perPivot[currency]

    /** The currencies this table can convert, pivot included. */
    val supported: Set<String> get() = perPivot.keys + pivot

    companion object {
        /** An empty table converts a currency to itself and nothing else. */
        val Identity = FiatConverter(emptyMap())
    }
}

/**
 * Picking the rate that was in force on a given day.
 *
 * Pure and separate from the repository so the three cases can be tested. They
 * are easy to get subtly wrong and the failure is a plausible-looking wrong
 * number rather than a crash.
 */
object HistoricalRates {

    /**
     * The rate published on or before [day].
     *
     * Weekends and holidays have no publication, so a Saturday resolves to the
     * preceding Friday. A day before the earliest known publication returns
     * null, meaning "unknown", never the oldest rate held: valuing a 2024
     * purchase at a 2026 rate produces a wrong cost basis that looks entirely
     * plausible, whereas unknown degrades to no basis and is visible.
     *
     * [byDay] must be sorted ascending by day.
     */
    fun onOrBefore(byDay: List<Pair<String, java.math.BigDecimal>>, day: String): java.math.BigDecimal? =
        byDay.lastOrNull { it.first <= day }?.second
}
