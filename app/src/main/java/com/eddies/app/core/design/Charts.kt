package com.eddies.app.core.design

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.toSize

/**
 * Pure Compose Canvas, no chart library. All the geometry that could be wrong by
 * an index or a pixel lives in ChartMath, which is JVM-testable; this file only
 * draws what that returns.
 */

/** Colours pulled from the theme so charts follow light, dark and OLED for free. */
internal data class ChartColors(
    val line: Color,
    val fillTop: Color,
    val fillBottom: Color,
    val grid: Color,
    val label: Color,
    val labelBg: Color,
    val crosshair: Color,
)

@Composable
internal fun chartColors(accent: Color = MaterialTheme.colorScheme.primary): ChartColors = ChartColors(
    line = accent,
    fillTop = accent.copy(alpha = 0.28f),
    fillBottom = accent.copy(alpha = 0.02f),
    grid = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
    label = MaterialTheme.colorScheme.onSurfaceVariant,
    labelBg = MaterialTheme.colorScheme.surfaceContainerHighest,
    crosshair = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
)

/**
 * A small unlabelled line, for a list row or a summary card.
 * Deliberately has no axes: at this size they would be noise, not information.
 */
@Composable
fun Sparkline(
    points: List<ChartPoint>,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    height: Dp = 40.dp,
) {
    if (points.size < 2) {
        Box(modifier.fillMaxWidth().height(height))
        return
    }
    val norm = remember(points) { ChartMath.normalise(ChartMath.sample(points, 120)) }
    val fillTop = color.copy(alpha = 0.22f)

    Canvas(modifier.fillMaxWidth().height(height)) {
        val path = Path()
        val fill = Path()
        norm.forEachIndexed { i, (fx, fy) ->
            val x = fx * size.width
            val y = size.height - fy * size.height
            if (i == 0) {
                path.moveTo(x, y)
                fill.moveTo(x, size.height)
                fill.lineTo(x, y)
            } else {
                path.lineTo(x, y)
                fill.lineTo(x, y)
            }
        }
        fill.lineTo(size.width, size.height)
        fill.close()
        drawPath(fill, Brush.verticalGradient(listOf(fillTop, Color.Transparent)))
        drawPath(path, color, style = Stroke(width = 2.dp.toPx()))
    }
}

/**
 * The full chart: gridlines, axis labels, gradient fill, animated reveal and a
 * touch scrubber with a floating value label.
 */
@Composable
fun InteractiveLineChart(
    points: List<ChartPoint>,
    modifier: Modifier = Modifier,
    height: Dp = 200.dp,
    accent: Color = MaterialTheme.colorScheme.primary,
    formatValue: (Double) -> String,
    formatTs: (Long) -> String,
    onScrub: (ChartPoint?) -> Unit = {},
) {
    val colors = chartColors(accent)
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current

    val sampled = remember(points) { ChartMath.sample(points, 240) }
    var scrubIndex by remember(sampled) { mutableStateOf<Int?>(null) }

    val reveal = remember(sampled) { Animatable(0f) }
    LaunchedEffect(sampled) { reveal.animateTo(1f, tween(550)) }

    if (sampled.size < 2) {
        Box(modifier.fillMaxWidth().height(height))
        return
    }

    val values = sampled.map { it.value }
    val (lo, hi) = ChartMath.axisBounds(values.min(), values.max())
    val span = (hi - lo).takeIf { it != 0.0 } ?: 1.0

    Canvas(
        modifier
            .fillMaxWidth()
            .height(height)
            .pointerInput(sampled) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    fun update(x: Float) {
                        val plot = plotRect(size.toSize(), density.density)
                        val fraction = ((x - plot.left) / plot.width).coerceIn(0f, 1f)
                        val idx = ChartMath.nearestIndex(sampled.size, fraction)
                        scrubIndex = idx
                        onScrub(sampled[idx])
                    }
                    update(down.position.x)
                    while (true) {
                        val event = androidx.compose.ui.input.pointer.PointerEventPass.Main
                            .let { awaitPointerEvent(it) }
                        val change = event.changes.firstOrNull() ?: break
                        if (!change.pressed) break
                        update(change.position.x)
                        change.consume()
                    }
                    scrubIndex = null
                    onScrub(null)
                }
            },
    ) {
        val plot = plotRect(size, density.density)
        drawGrid(plot, lo, hi, colors, measurer)

        val pts = sampled.mapIndexed { i, p ->
            val x = plot.left + plot.width * i / (sampled.size - 1)
            val y = plot.bottom - (((p.value - lo) / span).toFloat() * plot.height)
            Offset(x, y)
        }

        // Reveal by clipping rather than by rebuilding the path each frame.
        clipRect(right = plot.left + plot.width * reveal.value) {
            val line = Path().apply {
                pts.forEachIndexed { i, o -> if (i == 0) moveTo(o.x, o.y) else lineTo(o.x, o.y) }
            }
            val fill = Path().apply {
                moveTo(pts.first().x, plot.bottom)
                pts.forEach { lineTo(it.x, it.y) }
                lineTo(pts.last().x, plot.bottom)
                close()
            }
            drawPath(fill, Brush.verticalGradient(listOf(colors.fillTop, colors.fillBottom), plot.top, plot.bottom))
            drawPath(line, colors.line, style = Stroke(width = 2.5.dp.toPx()))
        }

        scrubIndex?.let { idx ->
            val o = pts[idx]
            drawLine(
                colors.crosshair,
                Offset(o.x, plot.top),
                Offset(o.x, plot.bottom),
                strokeWidth = 1.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)),
            )
            drawCircle(colors.line, radius = 4.dp.toPx(), center = o)
            drawScrubLabel(
                plot = plot,
                x = o.x,
                text = formatValue(sampled[idx].value),
                sub = formatTs(sampled[idx].ts),
                colors = colors,
                measurer = measurer,
            )
        }
    }
}

/** A fixed left gutter for y labels and a bottom strip for x labels. */
private fun plotRect(size: Size, density: Float): Rect = Rect(
    left = 48f * density / 2.75f,
    top = 8f * density / 2.75f,
    right = size.width - 8f * density / 2.75f,
    bottom = size.height - 22f * density / 2.75f,
)

private fun DrawScope.drawGrid(
    plot: Rect,
    lo: Double,
    hi: Double,
    colors: ChartColors,
    measurer: TextMeasurer,
) {
    val labels = listOf(hi, (hi + lo) / 2, lo)
    labels.forEachIndexed { i, value ->
        val y = plot.top + plot.height * i / (labels.size - 1)
        drawLine(colors.grid, Offset(plot.left, y), Offset(plot.right, y), strokeWidth = 1f)
        val text = compactAxis(value)
        val layout = measurer.measure(text, TextStyle(fontSize = 10.sp, color = colors.label))
        drawText(layout, topLeft = Offset(0f, y - layout.size.height / 2f))
    }
}

private fun DrawScope.drawScrubLabel(
    plot: Rect,
    x: Float,
    text: String,
    sub: String,
    colors: ChartColors,
    measurer: TextMeasurer,
) {
    val main = measurer.measure(text, TextStyle(fontSize = 12.sp, color = colors.label))
    val small = measurer.measure(sub, TextStyle(fontSize = 10.sp, color = colors.label))
    val w = maxOf(main.size.width, small.size.width) + 16f
    val h = main.size.height + small.size.height + 12f
    // Keep the label inside the plot when scrubbing near either edge.
    val left = (x - w / 2f).coerceIn(plot.left, plot.right - w)
    drawRoundRect(
        color = colors.labelBg,
        topLeft = Offset(left, plot.top),
        size = Size(w, h),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(8f, 8f),
    )
    drawText(main, topLeft = Offset(left + 8f, plot.top + 4f))
    drawText(small, topLeft = Offset(left + 8f, plot.top + 4f + main.size.height))
}

// Locale.US explicitly, matching MoneyFormat. Without it the default locale
// applies here and not there, so an axis could read "1,2k" beside a balance
// reading "1.20" on the same screen.
private fun compactAxis(v: Double): String {
    val a = kotlin.math.abs(v)
    val l = java.util.Locale.US
    return when {
        a >= 1_000_000_000 -> String.format(l, "%.1fB", v / 1_000_000_000)
        a >= 1_000_000 -> String.format(l, "%.1fM", v / 1_000_000)
        a >= 1_000 -> String.format(l, "%.1fk", v / 1_000)
        a >= 1 -> String.format(l, "%.0f", v)
        a >= 0.01 -> String.format(l, "%.2f", v)
        else -> String.format(l, "%.4f", v)
    }
}

/**
 * Allocation ring for the insights screen.
 *
 * Two separate animations, and keeping them separate is the whole point.
 *
 * The reveal sweeps the ring out once when the set of holdings changes, and is
 * keyed on the labels rather than on the slice sizes. Keying it on the sizes
 * meant every price tick built a fresh Animatable at zero and restarted the
 * sweep, so with a feed that ticks several times a second the ring never
 * finished revealing: it sat there collapsing and re-expanding forever.
 *
 * The sizes then glide to their new values instead of jumping, so a holding
 * drifting from 34.1 to 34.2 percent reads as movement rather than as a redraw.
 */
@Composable
fun AllocationDonut(
    slices: List<Pair<String, Float>>,
    colors: List<Color>,
    modifier: Modifier = Modifier,
    strokeWidth: Dp = 22.dp,
) {
    // Which holdings, not how big they are. A List<String> compares equal across
    // ticks, so the reveal survives a price change.
    val labels = slices.map { it.first }
    val reveal = remember(labels) { Animatable(0f) }
    LaunchedEffect(labels) { reveal.animateTo(1f, tween(600)) }

    val animated = slices.map { (label, fraction) ->
        animateFloatAsState(
            targetValue = fraction,
            animationSpec = tween(durationMillis = 450),
            label = "slice-$label",
        ).value
    }

    Canvas(modifier) {
        val stroke = strokeWidth.toPx()
        val diameter = minOf(size.width, size.height) - stroke
        val topLeft = Offset((size.width - diameter) / 2f, (size.height - diameter) / 2f)
        var start = -90f
        animated.forEachIndexed { i, fraction ->
            val sweep = fraction * 360f * reveal.value
            drawArc(
                color = colors[i % colors.size],
                startAngle = start,
                sweepAngle = sweep,
                useCenter = false,
                topLeft = topLeft,
                size = Size(diameter, diameter),
                style = Stroke(width = stroke),
            )
            start += fraction * 360f
        }
    }
}
