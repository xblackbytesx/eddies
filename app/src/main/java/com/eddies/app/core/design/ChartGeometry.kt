package com.eddies.app.core.design

import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** One point on a value or price chart. */
data class ChartPoint(val ts: Long, val value: Double)

/** The ranges the chart selector offers. */
enum class ChartRange(val label: String, val days: Int) {
    DAY("1D", 1),
    WEEK("1W", 7),
    MONTH("1M", 30),
    QUARTER("3M", 90),
    YEAR("1Y", 365),
    ALL("All", 3650),
}

/**
 * Pure geometry and formatting for the chart kit, framework-free so it is
 * JVM-testable. Compose draw code cannot be, so anything that could be wrong by
 * a pixel or an index lives here instead.
 */
object ChartMath {

    /**
     * Padded axis bounds so a line never touches the chart edge, and a flat
     * series still renders as a visible horizontal line rather than collapsing
     * onto the border.
     */
    fun axisBounds(min: Double, max: Double): Pair<Double, Double> {
        if (!min.isFinite() || !max.isFinite()) return 0.0 to 1.0
        if (min == max) {
            val pad = if (min == 0.0) 1.0 else kotlin.math.abs(min) * 0.1
            return (min - pad) to (max + pad)
        }
        val pad = (max - min) * 0.08
        return (min - pad) to (max + pad)
    }

    /** Index of the point nearest a horizontal scrub fraction in [0,1]. */
    fun nearestIndex(count: Int, fraction: Float): Int {
        if (count <= 1) return 0
        val idx = (fraction * (count - 1) + 0.5f).toInt()
        return idx.coerceIn(0, count - 1)
    }

    /**
     * Normalises points to unit coordinates: x left to right by index, y bottom
     * to top by value. Returning fractions rather than pixels is what keeps this
     * testable without a Canvas.
     */
    fun normalise(points: List<ChartPoint>): List<Pair<Float, Float>> {
        if (points.isEmpty()) return emptyList()
        if (points.size == 1) return listOf(0.5f to 0.5f)
        val values = points.map { it.value }
        val (lo, hi) = axisBounds(values.min(), values.max())
        val span = (hi - lo).takeIf { it != 0.0 } ?: 1.0
        return points.mapIndexed { i, p ->
            val x = i.toFloat() / (points.size - 1)
            val y = ((p.value - lo) / span).toFloat().coerceIn(0f, 1f)
            x to y
        }
    }

    /** Evenly spaced y-axis gridline values across the padded bounds. */
    fun gridValues(min: Double, max: Double, lines: Int = 3): List<Double> {
        val (lo, hi) = axisBounds(min, max)
        if (lines <= 1) return listOf(lo)
        return (0 until lines).map { lo + (hi - lo) * it / (lines - 1.0) }
    }

    /**
     * Trims a series to a range and thins it to at most [maxPoints].
     *
     * A year of minute-resolution prices is a third of a million points and no
     * screen has that many pixels; drawing them all is a dropped frame for a
     * line that looks identical.
     */
    fun sample(points: List<ChartPoint>, maxPoints: Int = 240): List<ChartPoint> {
        if (points.size <= maxPoints || maxPoints <= 0) return points
        val step = points.size.toDouble() / maxPoints
        // The last point is always kept: on a portfolio chart that is the number
        // shown at the top of the screen, and it must not disappear to rounding.
        val sampled = (0 until maxPoints).map { points[(it * step).toInt().coerceAtMost(points.size - 1)] }
        return if (sampled.lastOrNull() == points.last()) sampled else sampled.dropLast(1) + points.last()
    }

    fun percentChange(from: Double, to: Double): Double? {
        if (from == 0.0 || !from.isFinite() || !to.isFinite()) return null
        return (to - from) / from * 100.0
    }

    private val timeFmt = DateTimeFormatter.ofPattern("HH:mm")
    private val dayFmt = DateTimeFormatter.ofPattern("d MMM")
    private val monthFmt = DateTimeFormatter.ofPattern("MMM yy")
    private val fullFmt = DateTimeFormatter.ofPattern("d MMM, HH:mm")

    fun formatTs(ts: Long, zone: ZoneId, range: ChartRange): String {
        val t = Instant.ofEpochMilli(ts).atZone(zone)
        return when (range) {
            ChartRange.DAY -> timeFmt.format(t)
            ChartRange.WEEK, ChartRange.MONTH, ChartRange.QUARTER -> dayFmt.format(t)
            ChartRange.YEAR, ChartRange.ALL -> monthFmt.format(t)
        }
    }

    fun formatScrubTs(ts: Long, zone: ZoneId, range: ChartRange): String {
        val t = Instant.ofEpochMilli(ts).atZone(zone)
        return if (range == ChartRange.DAY) fullFmt.format(t) else dayFmt.format(t)
    }

    /** Converts a BigDecimal series to chart points without precision drama: a chart pixel is a Double. */
    fun toPoints(series: List<Pair<Long, BigDecimal>>): List<ChartPoint> =
        series.map { (ts, v) -> ChartPoint(ts, v.toDouble()) }
}
