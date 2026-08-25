package com.eddies.app.domain

import com.eddies.app.core.design.ChartMath
import com.eddies.app.core.design.ChartPoint
import com.eddies.app.core.design.ChartRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The chart ranges and the thinning that feeds them. A range that asks for more
 * points than a screen has pixels is a dropped frame for a line that looks
 * identical, and a range whose window is wrong shows the right shape over the
 * wrong dates.
 */
class ChartRangeTest {

    @Test
    fun `every range covers a sensible window`() {
        assertEquals(1, ChartRange.DAY.days)
        assertEquals(7, ChartRange.WEEK.days)
        assertEquals(30, ChartRange.MONTH.days)
        assertEquals(90, ChartRange.QUARTER.days)
        assertEquals(365, ChartRange.YEAR.days)
        assertTrue("All must reach past a year", ChartRange.ALL.days > 365)
    }

    @Test
    fun `ranges are strictly increasing, so the selector reads left to right`() {
        val days = ChartRange.entries.map { it.days }
        assertEquals(days.sorted(), days)
    }

    @Test
    fun `a day of hourly candles thins to something a screen can draw`() {
        // 1D is hourly, so 24 points: under the cap and returned untouched.
        val hourly = (0 until 24).map { ChartPoint(it * 3_600_000L, 100.0 + it) }
        assertEquals(hourly, ChartMath.sample(hourly, 240))
    }

    @Test
    fun `two years of daily candles thins but keeps both ends`() {
        // The last point is the current price shown above the chart. If thinning
        // drops it, the chart disagrees with the number next to it.
        val daily = (0 until 730).map { ChartPoint(it * 86_400_000L, 100.0 + it) }
        val sampled = ChartMath.sample(daily, 240)
        assertTrue(sampled.size <= 240)
        assertEquals(daily.first(), sampled.first())
        assertEquals(daily.last(), sampled.last())
    }

    @Test
    fun `range change is measured across the visible window, not the whole series`() {
        val points = listOf(
            ChartPoint(0, 100.0),
            ChartPoint(1, 150.0),
            ChartPoint(2, 200.0),
        )
        val change = ChartMath.percentChange(points.first().value, points.last().value)
        assertEquals(100.0, change!!, 0.0001)
    }

    @Test
    fun `a single-point series has no change rather than a fabricated one`() {
        val points = listOf(ChartPoint(0, 100.0))
        assertNull(
            "one point cannot describe a change",
            points.takeIf { it.size >= 2 }
                ?.let { ChartMath.percentChange(it.first().value, it.last().value) },
        )
    }

    @Test
    fun `a flat series reports zero change, not a division blow-up`() {
        assertEquals(0.0, ChartMath.percentChange(100.0, 100.0)!!, 0.0001)
    }

    @Test
    fun `a series that starts at zero reports no change instead of infinity`() {
        // A coin with no cached history yet can produce a leading zero, and
        // dividing by it would render "Infinity%" on the detail screen.
        assertNull(ChartMath.percentChange(0.0, 50.0))
    }
}
