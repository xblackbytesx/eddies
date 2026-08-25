package com.eddies.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class BackoffPolicyTest {

    @Test
    fun `the ceiling doubles per attempt until it caps`() {
        val p = BackoffPolicy(baseMs = 1_000, maxMs = 60_000)
        assertEquals(1_000, p.ceilingMs(0))
        assertEquals(2_000, p.ceilingMs(1))
        assertEquals(4_000, p.ceilingMs(2))
        assertEquals(32_000, p.ceilingMs(5))
        assertEquals(60_000, p.ceilingMs(6))
        assertEquals(60_000, p.ceilingMs(50))
    }

    @Test
    fun `a very large attempt does not overflow into a negative delay`() {
        // baseMs shl 64 wraps around on a Long. The exponent clamp is what stops
        // a long outage turning into an instant reconnect storm.
        val p = BackoffPolicy()
        assertTrue(p.ceilingMs(Int.MAX_VALUE) > 0)
        assertTrue(p.delayMs(Int.MAX_VALUE) > 0)
    }

    @Test
    fun `every delay stays inside its ceiling and is never zero`() {
        val p = BackoffPolicy(baseMs = 1_000, maxMs = 60_000, random = Random(42))
        for (attempt in 0..12) {
            repeat(200) {
                val d = p.delayMs(attempt)
                assertTrue("delay $d must be positive", d > 0)
                assertTrue("delay $d exceeded ceiling ${p.ceilingMs(attempt)}", d <= p.ceilingMs(attempt))
            }
        }
    }

    @Test
    fun `jitter actually varies, so clients do not resynchronise`() {
        val p = BackoffPolicy(baseMs = 1_000, maxMs = 60_000, random = Random(7))
        val seen = (1..100).map { p.delayMs(6) }.toSet()
        assertTrue("expected jitter, got ${seen.size} distinct values", seen.size > 10)
    }

    @Test
    fun `a negative attempt is a programming error`() {
        val p = BackoffPolicy()
        try {
            p.delayMs(-1)
            error("expected an IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("negative"))
        }
    }
}
