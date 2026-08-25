package com.eddies.app.domain

import com.eddies.app.core.design.ChartMath
import com.eddies.app.core.design.ChartPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChartMathTest {

    @Test
    fun `a flat series still gets a visible band rather than collapsing`() {
        val (lo, hi) = ChartMath.axisBounds(100.0, 100.0)
        assertTrue("bounds must not be degenerate", hi > lo)
        assertTrue(lo < 100.0 && hi > 100.0)
    }

    @Test
    fun `a flat series at zero is handled without dividing by zero`() {
        val (lo, hi) = ChartMath.axisBounds(0.0, 0.0)
        assertTrue(hi > lo)
    }

    @Test
    fun `bounds are padded so a line never touches the edge`() {
        val (lo, hi) = ChartMath.axisBounds(0.0, 100.0)
        assertTrue(lo < 0.0)
        assertTrue(hi > 100.0)
    }

    @Test
    fun `non-finite bounds degrade instead of propagating NaN into the draw`() {
        val (lo, hi) = ChartMath.axisBounds(Double.NaN, 5.0)
        assertTrue(lo.isFinite() && hi.isFinite())
    }

    @Test
    fun `scrubbing to either end selects the first and last point`() {
        assertEquals(0, ChartMath.nearestIndex(10, 0f))
        assertEquals(9, ChartMath.nearestIndex(10, 1f))
        assertEquals(5, ChartMath.nearestIndex(10, 0.5f))
    }

    @Test
    fun `scrubbing outside the chart is clamped, not out of bounds`() {
        assertEquals(0, ChartMath.nearestIndex(10, -0.5f))
        assertEquals(9, ChartMath.nearestIndex(10, 1.5f))
        assertEquals(0, ChartMath.nearestIndex(0, 0.5f))
        assertEquals(0, ChartMath.nearestIndex(1, 0.9f))
    }

    @Test
    fun `normalise spans the full width and stays inside the unit box`() {
        val pts = (0..4).map { ChartPoint(it.toLong(), it.toDouble()) }
        val norm = ChartMath.normalise(pts)
        assertEquals(0f, norm.first().first, 0.0001f)
        assertEquals(1f, norm.last().first, 0.0001f)
        assertTrue(norm.all { it.second in 0f..1f })
    }

    @Test
    fun `normalise centres a single point instead of dividing by zero`() {
        val norm = ChartMath.normalise(listOf(ChartPoint(0, 5.0)))
        assertEquals(1, norm.size)
        assertEquals(0.5f, norm[0].first, 0.0001f)
    }

    @Test
    fun `an empty series normalises to nothing rather than throwing`() {
        assertTrue(ChartMath.normalise(emptyList()).isEmpty())
    }

    @Test
    fun `sampling keeps the last point, which is the headline number`() {
        // The final value is what the top of the portfolio screen shows. If
        // thinning drops it, the chart disagrees with the balance above it.
        val pts = (0 until 5000).map { ChartPoint(it.toLong(), it.toDouble()) }
        val sampled = ChartMath.sample(pts, 240)
        assertTrue(sampled.size <= 240)
        assertEquals(pts.last(), sampled.last())
        assertEquals(pts.first(), sampled.first())
    }

    @Test
    fun `a short series is returned untouched`() {
        val pts = (0 until 10).map { ChartPoint(it.toLong(), it.toDouble()) }
        assertEquals(pts, ChartMath.sample(pts, 240))
    }

    @Test
    fun `grid values span the padded bounds`() {
        val grid = ChartMath.gridValues(0.0, 100.0, 3)
        assertEquals(3, grid.size)
        assertTrue(grid[0] < grid[1] && grid[1] < grid[2])
    }

    @Test
    fun `percent change guards a zero baseline`() {
        assertEquals(null, ChartMath.percentChange(0.0, 10.0))
        assertEquals(100.0, ChartMath.percentChange(10.0, 20.0)!!, 0.0001)
        assertEquals(-50.0, ChartMath.percentChange(10.0, 5.0)!!, 0.0001)
    }
}
