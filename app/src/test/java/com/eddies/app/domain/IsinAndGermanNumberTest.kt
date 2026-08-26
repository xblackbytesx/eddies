package com.eddies.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

/**
 * ISINs are how a European broker statement identifies an instrument, and how
 * Tradegate is keyed. Validating the check digit locally means a typo is caught
 * before a request goes out, and the message can say "typo" rather than
 * "not found", which are different problems with different fixes.
 */
class IsinTest {

    @Test
    fun `real ISINs pass, check digit included`() {
        // Verified against the live Tradegate endpoint 2026-08-26.
        assertTrue(Isin.isValid("NL0010273215"))   // ASML
        assertTrue(Isin.isValid("DE0007164600"))   // SAP
        assertTrue(Isin.isValid("US0378331005"))   // Apple
        assertTrue(Isin.isValid("IE00B4L5Y983"))   // iShares Core MSCI World
        assertTrue(Isin.isValid("DE000BASF111"))   // BASF
    }

    @Test
    fun `a wrong check digit is rejected`() {
        // The single most common way to mistype an ISIN is the last character,
        // and it is the only error the format alone cannot catch.
        assertTrue(Isin.looksLikeIsin("NL0010273216"))
        assertFalse(Isin.isValid("NL0010273216"))
    }

    @Test
    fun `a transposition is caught`() {
        // Luhn's whole purpose. Swapping two digits changes the check digit.
        assertFalse(Isin.isValid("NL0010273251"))
    }

    @Test
    fun `malformed input is rejected before any arithmetic`() {
        assertFalse(Isin.isValid(""))
        assertFalse(Isin.isValid("ASML"))
        assertFalse(Isin.isValid("NL001027321"))     // too short
        assertFalse(Isin.isValid("NL00102732155"))   // too long
        assertFalse(Isin.isValid("1L0010273215"))    // country must be letters
        assertFalse(Isin.isValid("NL001027321X"))    // check digit must be a digit
    }

    @Test
    fun `input is normalised the way people paste it`() {
        // Copied from a statement, an ISIN often arrives spaced or hyphenated.
        assertTrue(Isin.isValid("nl0010273215"))
        assertTrue(Isin.isValid("NL 0010 2732 15"))
        assertTrue(Isin.isValid("NL-0010273215"))
        assertEquals("NL0010273215", Isin.normalise(" nl-0010 273215 "))
    }

    @Test
    fun `letters inside the body expand to two digits each`() {
        // BASF's ISIN carries letters mid-string, which is where a naive
        // implementation that only handles the country prefix falls over.
        assertTrue(Isin.isValid("DE000BASF111"))
        assertTrue(Isin.isValid("IE00B4L5Y983"))
    }

    @Test
    fun `country is the registration, and only for a well-formed ISIN`() {
        assertEquals("NL", Isin.country("NL0010273215"))
        assertEquals("US", Isin.country("US0378331005"))
        assertNull(Isin.country("nonsense"))
    }
}

/**
 * Tradegate's JSON mixes number types: the same field is a JSON number for one
 * instrument and a German comma-decimal string for another, apparently whenever
 * the value would end in a trailing zero.
 *
 * Getting this wrong is silent. The parse throws, the tick is dropped, and the
 * holding shows no price at all, but only for some instruments.
 */
class GermanNumberTest {

    private fun bd(s: String) = BigDecimal(s)

    @Test
    fun `plain numbers parse as they always did`() {
        // SAP came back this way.
        assertEquals(0, bd("180.38").compareTo(GermanNumber.parse("180.38")))
        assertEquals(0, bd("1501").compareTo(GermanNumber.parse("1501")))
    }

    @Test
    fun `a comma decimal parses, which is the whole point`() {
        // ASML came back as "1501,60" and Apple's high as "265,70".
        assertEquals(0, bd("1501.60").compareTo(GermanNumber.parse("1501,60")))
        assertEquals(0, bd("265.70").compareTo(GermanNumber.parse("265,70")))
        assertEquals(0, bd("300.00").compareTo(GermanNumber.parse("300,00")))
    }

    @Test
    fun `a thousands separator is removed, not treated as a decimal point`() {
        // "1.501,60" is one and a half thousand, not one point five. Reading the
        // dot as the decimal separator here understates a holding a thousandfold.
        assertEquals(0, bd("1501.60").compareTo(GermanNumber.parse("1.501,60")))
        assertEquals(0, bd("1234567.89").compareTo(GermanNumber.parse("1.234.567,89")))
    }

    @Test
    fun `a lone dot stays a decimal point`() {
        // No comma present means the dot is the decimal separator, so an
        // ordinary "1501.60" is not mangled into 150160.
        assertEquals(0, bd("1501.60").compareTo(GermanNumber.parse("1501.60")))
    }

    @Test
    fun `precision is preserved exactly`() {
        // Straight to BigDecimal, never through a Double.
        assertEquals("1501.60", GermanNumber.parse("1501,60")!!.toPlainString())
        assertEquals("0.123456789012345678", GermanNumber.parse("0,123456789012345678")!!.toPlainString())
    }

    @Test
    fun `negatives parse, since a change field can be negative`() {
        assertEquals(0, bd("-2.58").compareTo(GermanNumber.parse("-2,58")))
        assertEquals(0, bd("-2.58").compareTo(GermanNumber.parse("-2.58")))
    }

    @Test
    fun `nothing at all is null rather than zero`() {
        // Zero would be a price, and a missing price is not a price of zero.
        assertNull(GermanNumber.parse(null))
        assertNull(GermanNumber.parse(""))
        assertNull(GermanNumber.parse("   "))
        assertNull(GermanNumber.parse("n/a"))
    }

    @Test
    fun `surrounding whitespace is tolerated`() {
        assertEquals(0, bd("180.38").compareTo(GermanNumber.parse("  180,38  ")))
    }
}
