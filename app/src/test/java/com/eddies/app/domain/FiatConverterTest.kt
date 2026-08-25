package com.eddies.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.math.BigDecimal

class FiatConverterTest {

    // ECB publishes against EUR, so the table is "units of X per one EUR".
    private val fx = FiatConverter(mapOf("USD" to BigDecimal("1.10"), "GBP" to BigDecimal("0.85")))

    @Test
    fun `a currency converts to itself at one`() {
        assertEquals(0, BigDecimal.ONE.compareTo(fx.rate("USD", "USD")))
    }

    @Test
    fun `the pivot converts out at the published rate`() {
        assertEquals(0, BigDecimal("1.10").compareTo(fx.rate("EUR", "USD")))
    }

    @Test
    fun `converting into the pivot is the reciprocal`() {
        // Compared at a monetary scale on purpose. 1/1.10 is a repeating decimal,
        // so no finite precision round-trips it exactly; the converter keeps the
        // full precision and rounding happens once, at display.
        val eur = fx.convert(BigDecimal("110"), "USD", "EUR")!!
        assertEquals(0, BigDecimal("100.00").compareTo(eur.setScale(2, java.math.RoundingMode.HALF_UP)))
    }

    @Test
    fun `a cross rate goes through the pivot in both directions`() {
        // USD to GBP: 1 USD = (0.85 / 1.10) GBP.
        val gbp = fx.convert(BigDecimal("110"), "USD", "GBP")!!
        assertEquals(0, BigDecimal("85.00").compareTo(gbp.setScale(2, java.math.RoundingMode.HALF_UP)))

        val back = fx.convert(gbp, "GBP", "USD")!!
        assertEquals(0, BigDecimal("110.00").compareTo(back.setScale(2, java.math.RoundingMode.HALF_UP)))
    }

    @Test
    fun `an unknown currency returns null rather than throwing or guessing`() {
        assertNull(fx.rate("USD", "XYZ"))
        assertNull(fx.convert(BigDecimal.TEN, "XYZ", "USD"))
    }

    @Test
    fun `an empty table still converts a currency to itself`() {
        assertEquals(0, BigDecimal.ONE.compareTo(FiatConverter.Identity.rate("USD", "USD")))
        assertNull(FiatConverter.Identity.rate("USD", "EUR"))
    }

    @Test
    fun `a zero rate is refused rather than dividing by it`() {
        val broken = FiatConverter(mapOf("BAD" to BigDecimal.ZERO, "USD" to BigDecimal("1.10")))
        assertNull(broken.rate("BAD", "USD"))
    }

    @Test
    fun `supported names the pivot alongside the published currencies`() {
        assertEquals(setOf("EUR", "USD", "GBP"), fx.supported)
    }
}
