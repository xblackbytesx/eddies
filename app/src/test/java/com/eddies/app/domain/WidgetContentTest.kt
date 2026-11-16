package com.eddies.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * What the home screen widget may say. It sits outside the app lock, so the
 * masking rules are the part that must not regress.
 */
class WidgetContentTest {

    private val zone = ZoneOffset.UTC
    private fun at(d: Int, h: Int, m: Int) = LocalDateTime.of(2024, 11, d, h, m).toInstant(zone).toEpochMilli()

    private fun content(
        total: String = "10500",
        previous: String? = "10000",
        hide: Boolean = false,
        lock: Boolean = false,
        at: Long = at(5, 14, 5),
        now: Long = at(5, 14, 6),
    ) = WidgetContent.of(
        total = BigDecimal(total),
        previousClose = previous?.let(::BigDecimal),
        currency = "EUR",
        hideBalances = hide,
        appLock = lock,
        at = at,
        now = now,
        zone = zone,
    )

    @Test
    fun `an open widget shows the total and the move since the last close`() {
        val c = content()
        assertTrue(c.value.contains("10,500"))
        assertTrue(c.change.contains("500"))
        assertTrue(c.change.contains("+5.00%"))
        assertEquals(1, c.direction)
    }

    @Test
    fun `a loss carries a sign and an arrow, not only a color`() {
        val c = content(total = "9500")
        assertTrue(c.change.startsWith("▼"))
        assertTrue(c.change.contains("-"))
        assertEquals(-1, c.direction)
    }

    @Test
    fun `with the app lock on no amount reaches the home screen`() {
        val c = content(lock = true)
        assertFalse(c.value.any { it.isDigit() })
        assertFalse(c.change.contains("500"))
        // How the day is going is still worth a glance, and gives no amount away.
        assertTrue(c.change.contains("+5.00%"))
    }

    @Test
    fun `hidden balances hide the percentage as well, as they do in the app`() {
        val c = content(hide = true)
        assertFalse(c.value.any { it.isDigit() })
        assertEquals("", c.change)
        assertEquals(0, c.direction)
    }

    @Test
    fun `with nothing to compare against there is no change line`() {
        assertEquals("", content(previous = null).change)
        assertEquals("", content(previous = "0").change)
    }

    @Test
    fun `the time is always shown, and an older figure shows its day`() {
        assertEquals("14:05", content().updated)
        val old = content(at = at(4, 9, 30), now = at(5, 14, 6))
        assertTrue(old.updated.contains("4 Nov"))
        assertTrue(old.updated.contains("09:30"))
    }
}
