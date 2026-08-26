package com.eddies.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.math.BigDecimal

/**
 * Which exchange rate a past transaction is valued at.
 *
 * A purchase priced in a currency other than the portfolio's is converted at the
 * rate in force on its own date. Getting that wrong does not crash: it produces
 * a cost basis that looks entirely reasonable and is not, which is the worst
 * shape a money bug can take.
 */
class HistoricalRatesTest {

    // ECB publishes on working days only. This is a real week: the 19th is a
    // Friday, the 20th and 21st a weekend.
    private val rates = listOf(
        "2024-04-17" to BigDecimal("1.0638"),
        "2024-04-18" to BigDecimal("1.0679"),
        "2024-04-19" to BigDecimal("1.0653"),
        "2024-04-22" to BigDecimal("1.0661"),
    )

    @Test
    fun `an exact publication date uses that day's rate`() {
        assertEquals(0, BigDecimal("1.0679").compareTo(HistoricalRates.onOrBefore(rates, "2024-04-18")))
    }

    @Test
    fun `a weekend uses the last rate published before it`() {
        // Trades settle on Saturdays in nobody's world, but a user can date a
        // transaction then, and the rate in force is Friday's.
        assertEquals(0, BigDecimal("1.0653").compareTo(HistoricalRates.onOrBefore(rates, "2024-04-20")))
        assertEquals(0, BigDecimal("1.0653").compareTo(HistoricalRates.onOrBefore(rates, "2024-04-21")))
    }

    @Test
    fun `a date after everything known uses the most recent rate`() {
        // Today, before the daily refresh has run.
        assertEquals(0, BigDecimal("1.0661").compareTo(HistoricalRates.onOrBefore(rates, "2026-08-26")))
    }

    @Test
    fun `a date before everything known is unknown, not the oldest rate`() {
        // The fix this test exists for. Falling back to the oldest held rate
        // valued a 2024 purchase at a 2026 rate, silently, because the rate
        // table only accumulated forward from the day the app was installed.
        assertNull(HistoricalRates.onOrBefore(rates, "2024-04-16"))
        assertNull(HistoricalRates.onOrBefore(rates, "2020-01-01"))
    }

    @Test
    fun `an empty table is unknown rather than a crash`() {
        assertNull(HistoricalRates.onOrBefore(emptyList(), "2024-04-18"))
    }

    @Test
    fun `lookup is lexicographic, which ISO dates make chronological`() {
        // The whole approach rests on this. It holds for yyyy-MM-dd and for
        // nothing else, which is why dates are stored in that format.
        assertEquals(0, BigDecimal("1.0638").compareTo(HistoricalRates.onOrBefore(rates, "2024-04-17")))
        assertNull(HistoricalRates.onOrBefore(rates, "2024-04-09"))
        assertEquals(0, BigDecimal("1.0661").compareTo(HistoricalRates.onOrBefore(rates, "2024-12-31")))
    }

    @Test
    fun `a year boundary does not confuse the ordering`() {
        val spanning = listOf(
            "2023-12-29" to BigDecimal("1.1050"),
            "2024-01-02" to BigDecimal("1.0956"),
        )
        assertEquals(0, BigDecimal("1.1050").compareTo(HistoricalRates.onOrBefore(spanning, "2024-01-01")))
        assertEquals(0, BigDecimal("1.0956").compareTo(HistoricalRates.onOrBefore(spanning, "2024-01-02")))
    }
}
