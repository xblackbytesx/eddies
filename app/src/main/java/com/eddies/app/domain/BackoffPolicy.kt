package com.eddies.app.domain

import kotlin.math.min
import kotlin.random.Random

/**
 * Reconnect delays for the exchange sockets.
 *
 * Full jitter rather than plain exponential: without it every client that lost
 * the same upstream comes back in the same millisecond, which is how a
 * recovering endpoint gets knocked over a second time.
 */
class BackoffPolicy(
    private val baseMs: Long = 1_000,
    private val maxMs: Long = 60_000,
    private val random: Random = Random.Default,
) {
    /** Delay before attempt number [attempt], counting from zero. */
    fun delayMs(attempt: Int): Long {
        require(attempt >= 0) { "attempt must not be negative" }
        val exponent = min(attempt, 30)
        val ceiling = min(maxMs, baseMs shl exponent)
        // Guard against a caller configuring baseMs above maxMs.
        val bounded = ceiling.coerceAtLeast(1L)
        return random.nextLong(0, bounded) + 1
    }

    /** The uniform ceiling [delayMs] draws below, exposed for tests and logging. */
    fun ceilingMs(attempt: Int): Long {
        val exponent = min(attempt.coerceAtLeast(0), 30)
        return min(maxMs, baseMs shl exponent).coerceAtLeast(1L)
    }
}
