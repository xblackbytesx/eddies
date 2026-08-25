package com.eddies.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

/**
 * Formatting is not cosmetic here. A holding rendered as "0.00" when it is worth
 * a fraction of a cent, or a price rounded to zero decimals, is indistinguishable
 * from an arithmetic bug to the person reading it.
 */
class MoneyFormatTest {

    @Test
    fun `fiat groups thousands and keeps two decimals`() {
        assertEquals("€1,234.56", MoneyFormat.fiat(BigDecimal("1234.56"), "EUR"))
        assertEquals("$1,000,000.00", MoneyFormat.fiat(BigDecimal("1000000"), "USD"))
    }

    @Test
    fun `a tiny holding does not round away to zero`() {
        // Two decimals would render this as 0.00 and look like nothing at all.
        val out = MoneyFormat.fiat(BigDecimal("0.0004"), "EUR")
        assertTrue("expected more precision, got $out", out != "€0.00")
        assertTrue(out.contains("0.0004"))
    }

    @Test
    fun `a negative fiat amount puts the sign before the symbol`() {
        assertEquals("-€12.50", MoneyFormat.fiat(BigDecimal("-12.5"), "EUR"))
    }

    @Test
    fun `signed amounts always carry a direction`() {
        assertEquals("+€10.00", MoneyFormat.signedFiat(BigDecimal("10"), "EUR"))
        assertEquals("-€10.00", MoneyFormat.signedFiat(BigDecimal("-10"), "EUR"))
        assertEquals("+€0.00", MoneyFormat.signedFiat(BigDecimal.ZERO, "EUR"))
    }

    @Test
    fun `an unknown currency falls back to its code rather than a wrong symbol`() {
        assertEquals("PLN ", MoneyFormat.symbolFor("PLN"))
        assertTrue(MoneyFormat.fiat(BigDecimal("5"), "PLN").startsWith("PLN "))
    }

    @Test
    fun `hidden mode never leaks a digit`() {
        val hidden = listOf(
            MoneyFormat.fiat(BigDecimal("123456.78"), "EUR", hidden = true),
            MoneyFormat.signedFiat(BigDecimal("123456.78"), "EUR", hidden = true),
            MoneyFormat.price(BigDecimal("123456.78"), "EUR", hidden = true),
            MoneyFormat.quantity(BigDecimal("123456.78"), hidden = true),
            MoneyFormat.percent(12.34, hidden = true),
        )
        hidden.forEach { out ->
            assertTrue("'$out' still contains a digit", out.none { it.isDigit() })
        }
    }

    @Test
    fun `quantities trim trailing zeros but keep whole numbers whole`() {
        assertEquals("0.5", MoneyFormat.quantity(BigDecimal("0.50000000")))
        assertEquals("3", MoneyFormat.quantity(BigDecimal("3.00000000")))
        assertEquals("1,234", MoneyFormat.quantity(BigDecimal("1234")))
    }

    @Test
    fun `a sub-cent token price keeps enough places to say something`() {
        // SHIB-scale prices at two decimals are a row of zeros, which is useless.
        val out = MoneyFormat.price(BigDecimal("0.00002314"), "USD")
        assertTrue("expected precision, got $out", out.contains("0.0000231"))
    }

    @Test
    fun `a large price stays at two decimals`() {
        assertEquals("$68,035.60", MoneyFormat.price(BigDecimal("68035.6"), "USD"))
    }

    @Test
    fun `percent handles missing and non-finite values without crashing`() {
        assertEquals("–", MoneyFormat.percent(null))
        assertEquals("–", MoneyFormat.percent(Double.NaN))
        assertEquals("–", MoneyFormat.percent(Double.POSITIVE_INFINITY))
        assertEquals("+3.48%", MoneyFormat.percent(3.4812))
        assertEquals("-3.48%", MoneyFormat.percent(-3.4812))
    }

    @Test
    fun `compact notation shortens axis labels`() {
        assertEquals("€1.2k", MoneyFormat.compact(BigDecimal("1234"), "EUR"))
        assertEquals("€1.5M", MoneyFormat.compact(BigDecimal("1500000"), "EUR"))
        assertEquals("€2.0B", MoneyFormat.compact(BigDecimal("2000000000"), "EUR"))
        assertEquals("-€1.2k", MoneyFormat.compact(BigDecimal("-1234"), "EUR"))
    }

    @Test
    fun `an 18-decimal quantity is displayed truncated, not mangled`() {
        val out = MoneyFormat.quantity(BigDecimal("0.123456789012345678"), decimals = 8)
        assertEquals("0.12345679", out)
    }
}
