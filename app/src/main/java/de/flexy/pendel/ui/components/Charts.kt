package de.flexy.pendel.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.flexy.pendel.core.stats.Descriptive
import de.flexy.pendel.ui.theme.LocalPendelColors
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/*
 * Small, dependency-free Compose charts.
 * Conventions: thin marks (2 dp lines, ≥ 8 dp dots), recessive grid, one y-axis,
 * text in text colors (never series color), tap/drag for a value tooltip.
 */

private const val LEFT_AXIS_DP = 44
private const val BOTTOM_AXIS_DP = 22

@Composable
private fun axisStyle() = TextStyle(fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

/** Nice tick values between min and max. */
private fun niceTicks(min: Double, max: Double, count: Int = 4): List<Double> {
    if (max <= min) return listOf(min)
    val raw = (max - min) / count
    val mag = Math.pow(10.0, floor(Math.log10(raw)))
    val step = listOf(1.0, 2.0, 2.5, 5.0, 10.0).map { it * mag }.first { it >= raw }
    val start = ceil(min / step) * step
    return generateSequence(start) { it + step }.takeWhile { it <= max + 1e-9 }.toList()
}

/** Duration ticks on whole minutes. */
private fun minuteTicks(minS: Double, maxS: Double): List<Double> {
    val span = (maxS - minS) / 60
    val step = when {
        span <= 4 -> 1
        span <= 10 -> 2
        span <= 25 -> 5
        else -> 10
    } * 60.0
    val start = ceil(minS / step) * step
    return generateSequence(start) { it + step }.takeWhile { it <= maxS }.toList()
}

@Composable
private fun Tooltip(text: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.inverseSurface,
        shadowElevation = 2.dp,
    ) {
        Text(text, Modifier.padding(horizontal = 10.dp, vertical = 6.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.inverseOnSurface)
    }
}

data class LegendItem(val label: String, val colorIndex: Int)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChartLegend(items: List<LegendItem>, modifier: Modifier = Modifier) {
    if (items.size < 2) return
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        items.forEach {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RouteDot(it.colorIndex, 8)
                Spacer(Modifier.width(6.dp))
                Text(it.label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

// ------------------------------------------------------------------ duration over time

data class TimePoint(val x: Long, val y: Double, val colorIndex: Int, val label: String)

/**
 * Trip durations over calendar time; dots colored by route, plus a neutral rolling median
 * (window 7) so the trend is visible without trusting single rides.
 */
@Composable
fun DurationTimelineChart(points: List<TimePoint>, modifier: Modifier = Modifier) {
    if (points.isEmpty()) return
    val pal = LocalPendelColors.current
    val cs = MaterialTheme.colorScheme
    val tm = rememberTextMeasurer()
    val axis = axisStyle()
    var selected by remember(points) { mutableStateOf<Int?>(null) }
    val sorted = remember(points) { points.sortedBy { it.x } }
    val minX = sorted.first().x
    val maxX = max(sorted.last().x, minX + 1)
    val ys = sorted.map { it.y }
    val pad = max(60.0, (ys.max() - ys.min()) * 0.1)
    val minY = ys.min() - pad
    val maxY = ys.max() + pad
    val rolling = remember(sorted) {
        sorted.indices.map { i ->
            val from = max(0, i - 3)
            val to = min(sorted.size - 1, i + 3)
            Descriptive.median(sorted.subList(from, to + 1).map { it.y })
        }
    }
    Box(modifier) {
        Canvas(
            Modifier.fillMaxWidth().height(180.dp).pointerInput(sorted) {
                detectTapGestures { o ->
                    val left = LEFT_AXIS_DP.dp.toPx()
                    val w = size.width - left
                    val idx = sorted.indices.minByOrNull { i ->
                        abs(left + (sorted[i].x - minX).toFloat() / (maxX - minX) * w - o.x)
                    }
                    selected = if (selected == idx) null else idx
                }
            },
        ) {
            val left = LEFT_AXIS_DP.dp.toPx()
            val bottom = size.height - BOTTOM_AXIS_DP.dp.toPx()
            val w = size.width - left
            fun px(x: Long) = left + (x - minX).toFloat() / (maxX - minX) * w
            fun py(y: Double) = (bottom - (y - minY) / (maxY - minY) * bottom).toFloat()
            yGrid(tm, axis, minuteTicks(minY, maxY), ::py, left, cs.outlineVariant) { Fmt.mmss(it) }
            // rolling median
            val path = Path()
            sorted.forEachIndexed { i, p -> if (i == 0) path.moveTo(px(p.x), py(rolling[i])) else path.lineTo(px(p.x), py(rolling[i])) }
            drawPath(path, cs.onSurface.copy(alpha = 0.55f), style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            // dots with 2 dp surface ring
            sorted.forEachIndexed { i, p ->
                val c = Offset(px(p.x), py(p.y))
                val r = if (selected == i) 6.dp.toPx() else 4.dp.toPx()
                drawCircle(cs.surface, r + 2.dp.toPx(), c)
                drawCircle(pal.route(p.colorIndex), r, c)
            }
            // x labels: first / last date
            drawText(tm, Fmt.date(minX), Offset(left, bottom + 4.dp.toPx()), axis)
            val lastLabel = tm.measure(Fmt.date(maxX), axis)
            drawText(lastLabel, topLeft = Offset(size.width - lastLabel.size.width, bottom + 4.dp.toPx()))
        }
        selected?.let { i -> sorted.getOrNull(i)?.let { Tooltip(it.label, Modifier.align(Alignment.TopCenter)) } }
    }
}

private fun DrawScope.yGrid(
    tm: TextMeasurer, style: TextStyle, ticks: List<Double>, py: (Double) -> Float, left: Float, grid: Color, fmt: (Double) -> String,
) {
    for (t in ticks) {
        val y = py(t)
        drawLine(grid.copy(alpha = 0.6f), Offset(left, y), Offset(size.width, y), strokeWidth = 1f)
        val l = tm.measure(fmt(t), style)
        drawText(l, topLeft = Offset(left - l.size.width - 6.dp.toPx(), y - l.size.height / 2))
    }
}

// ------------------------------------------------------------------ bars per weekday

data class BarDatum(val label: String, val value: Double?, val n: Int, val tooltip: String)

/**
 * Vertical bars (median per weekday). Bars with fewer than 5 rides are drawn as outline only –
 * a secondary, non-color encoding of weak data.
 */
@Composable
fun BarChart(data: List<BarDatum>, modifier: Modifier = Modifier, valueFmt: (Double) -> String = { Fmt.mmss(it) }) {
    val cs = MaterialTheme.colorScheme
    val tm = rememberTextMeasurer()
    val axis = axisStyle()
    var selected by remember(data) { mutableStateOf<Int?>(null) }
    val values = data.mapNotNull { it.value }
    if (values.isEmpty()) return
    val maxV = values.max() * 1.1
    // durations: start the axis near the minimum so differences are visible, but say so via ticks
    val minV = if (values.min() > 600) floor(values.min() * 0.85 / 60) * 60 else 0.0
    Box(modifier) {
        Canvas(
            Modifier.fillMaxWidth().height(170.dp).pointerInput(data) {
                detectTapGestures { o ->
                    val left = LEFT_AXIS_DP.dp.toPx()
                    val slot = (size.width - left) / data.size
                    val idx = ((o.x - left) / slot).toInt().coerceIn(0, data.size - 1)
                    selected = if (selected == idx) null else idx
                }
            },
        ) {
            val left = LEFT_AXIS_DP.dp.toPx()
            val bottom = size.height - BOTTOM_AXIS_DP.dp.toPx()
            fun py(v: Double) = (bottom - (v - minV) / (maxV - minV) * bottom).toFloat()
            yGrid(tm, axis, if (minV > 0) minuteTicks(minV, maxV) else niceTicks(0.0, maxV), ::py, left, cs.outlineVariant, valueFmt)
            val slot = (size.width - left) / data.size
            val barW = min(slot * 0.55f, 28.dp.toPx())
            data.forEachIndexed { i, d ->
                val cx = left + slot * i + slot / 2
                val lbl = tm.measure(d.label, axis)
                drawText(lbl, topLeft = Offset(cx - lbl.size.width / 2, bottom + 4.dp.toPx()))
                val v = d.value ?: return@forEachIndexed
                val top = py(v)
                val color = if (selected == null || selected == i) cs.primary else cs.primary.copy(alpha = 0.35f)
                val weak = d.n < 5
                if (weak) {
                    drawRoundRect(
                        color, Offset(cx - barW / 2, top), Size(barW, bottom - top),
                        CornerRadius(4.dp.toPx()), style = Stroke(1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 4f))),
                    )
                } else {
                    // rounded data end, square at the baseline
                    val r = 4.dp.toPx()
                    val p = Path().apply {
                        moveTo(cx - barW / 2, bottom)
                        lineTo(cx - barW / 2, top + r)
                        quadraticBezierTo(cx - barW / 2, top, cx - barW / 2 + r, top)
                        lineTo(cx + barW / 2 - r, top)
                        quadraticBezierTo(cx + barW / 2, top, cx + barW / 2, top + r)
                        lineTo(cx + barW / 2, bottom)
                        close()
                    }
                    drawPath(p, color)
                }
            }
            drawLine(cs.outline, Offset(left, bottom), Offset(size.width, bottom), 1f)
        }
        selected?.let { i -> data.getOrNull(i)?.let { Tooltip(it.tooltip, Modifier.align(Alignment.TopCenter)) } }
    }
}

// ------------------------------------------------------------------ horizontal bars (ranking)

data class HBarDatum(val label: String, val value: Double, val valueLabel: String, val sub: String?)

/** Ranked horizontal bars built from layout (not canvas) – accessible and text stays crisp. */
@Composable
fun HorizontalBars(items: List<HBarDatum>, modifier: Modifier = Modifier, onClick: ((Int) -> Unit)? = null) {
    val maxV = items.maxOfOrNull { it.value }?.takeIf { it > 0 } ?: 1.0
    val cs = MaterialTheme.colorScheme
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items.forEachIndexed { i, it ->
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                    .let { m -> if (onClick != null) m.pointerInput(i) { detectTapGestures { onClick?.invoke(i) } } else m },
            ) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(it.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), maxLines = 1)
                    Text(it.valueLabel, style = MaterialTheme.typography.labelLarge)
                }
                Spacer(Modifier.height(4.dp))
                Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(cs.surfaceContainerHighest)) {
                    Box(
                        Modifier.fillMaxWidth((it.value / maxV).toFloat().coerceIn(0.02f, 1f)).fillMaxHeight()
                            .clip(RoundedCornerShape(4.dp)).background(cs.tertiary),
                    )
                }
                if (it.sub != null) {
                    Spacer(Modifier.height(2.dp))
                    Text(it.sub, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                }
            }
        }
    }
}

// ------------------------------------------------------------------ distribution per route

data class DistributionGroup(val label: String, val colorIndex: Int, val values: List<Double>)

/**
 * One row per route: every ride as a dot, IQR box and median tick. Shows spread and outliers
 * honestly instead of a single average.
 */
@Composable
fun DistributionChart(groups: List<DistributionGroup>, modifier: Modifier = Modifier) {
    val all = groups.flatMap { it.values }
    if (all.isEmpty()) return
    val pal = LocalPendelColors.current
    val cs = MaterialTheme.colorScheme
    val tm = rememberTextMeasurer()
    val axis = axisStyle()
    val minV = all.min() - 30
    val maxV = all.max() + 30
    val rowH = 34.dp
    Canvas(modifier.fillMaxWidth().height(rowH * groups.size + BOTTOM_AXIS_DP.dp)) {
        val left = 8.dp.toPx()
        val right = size.width - 8.dp.toPx()
        val bottom = size.height - BOTTOM_AXIS_DP.dp.toPx()
        fun px(v: Double) = (left + (v - minV) / (maxV - minV) * (right - left)).toFloat()
        for (t in minuteTicks(minV, maxV)) {
            val x = px(t)
            drawLine(cs.outlineVariant.copy(alpha = 0.6f), Offset(x, 0f), Offset(x, bottom), 1f)
            val l = tm.measure(Fmt.mmss(t), axis)
            drawText(l, topLeft = Offset(x - l.size.width / 2, bottom + 4.dp.toPx()))
        }
        groups.forEachIndexed { gi, g ->
            if (g.values.isEmpty()) return@forEachIndexed
            val cy = rowH.toPx() * gi + rowH.toPx() / 2
            val color = pal.route(g.colorIndex)
            val s = g.values.sorted()
            val q1 = Descriptive.quantileSorted(s, 0.25)
            val q3 = Descriptive.quantileSorted(s, 0.75)
            val med = Descriptive.quantileSorted(s, 0.5)
            drawRoundRect(color.copy(alpha = 0.18f), Offset(px(q1), cy - 9.dp.toPx()), Size(max(2f, px(q3) - px(q1)), 18.dp.toPx()), CornerRadius(4.dp.toPx()))
            s.forEachIndexed { k, v ->
                val jitter = ((k * 37) % 11 - 5) * 0.9f
                drawCircle(color.copy(alpha = 0.8f), 3.dp.toPx(), Offset(px(v), cy + jitter))
            }
            drawLine(cs.onSurface, Offset(px(med), cy - 11.dp.toPx()), Offset(px(med), cy + 11.dp.toPx()), 2.dp.toPx(), cap = StrokeCap.Round)
        }
    }
}

// ------------------------------------------------------------------ time-of-day profile

data class ProfileSeries(val label: String, val colorIndex: Int, val points: List<Triple<Int, Double, Double>>) // minute, median, ess

/**
 * Kernel-smoothed median duration over the time of day per route. Where a route has little data
 * (ESS < 3) the line is dashed – uncertainty is visible, not hidden. Drag for a crosshair.
 */
@Composable
fun TimeProfileChart(series: List<ProfileSeries>, modifier: Modifier = Modifier, markerMinute: Int? = null) {
    val withData = series.filter { it.points.size >= 2 }
    if (withData.isEmpty()) return
    val pal = LocalPendelColors.current
    val cs = MaterialTheme.colorScheme
    val tm = rememberTextMeasurer()
    val axis = axisStyle()
    val minM = withData.minOf { s -> s.points.minOf { it.first } }
    val maxM = max(withData.maxOf { s -> s.points.maxOf { it.first } }, minM + 60)
    val ys = withData.flatMap { s -> s.points.map { it.second } }
    val pad = max(45.0, (ys.max() - ys.min()) * 0.15)
    val minY = ys.min() - pad
    val maxY = ys.max() + pad
    var cross by remember(series) { mutableStateOf<Int?>(null) }

    Box(modifier) {
        Canvas(
            Modifier.fillMaxWidth().height(200.dp)
                .pointerInput(series) {
                    val left = LEFT_AXIS_DP.dp.toPx()
                    fun toMinute(x: Float) = (minM + ((x - left) / (size.width - left)) * (maxM - minM)).toInt().coerceIn(minM, maxM)
                    detectHorizontalDragGestures(
                        onDragStart = { cross = toMinute(it.x) },
                        onHorizontalDrag = { change, _ -> cross = toMinute(change.position.x) },
                    )
                }
                .pointerInput(series) {
                    val left = LEFT_AXIS_DP.dp.toPx()
                    detectTapGestures { o ->
                        val m = (minM + ((o.x - left) / (size.width - left)) * (maxM - minM)).toInt().coerceIn(minM, maxM)
                        cross = if (cross != null) null else m
                    }
                },
        ) {
            val left = LEFT_AXIS_DP.dp.toPx()
            val bottom = size.height - BOTTOM_AXIS_DP.dp.toPx()
            fun px(m: Int) = left + (m - minM).toFloat() / (maxM - minM) * (size.width - left)
            fun py(y: Double) = (bottom - (y - minY) / (maxY - minY) * bottom).toFloat()
            yGrid(tm, axis, minuteTicks(minY, maxY), ::py, left, cs.outlineVariant) { Fmt.mmss(it) }
            var h = (minM / 60 + 1) * 60
            while (h <= maxM) {
                if (h % 120 == 0) {
                    val l = tm.measure(Fmt.minuteOfDay(h), axis)
                    drawText(l, topLeft = Offset(px(h) - l.size.width / 2, bottom + 4.dp.toPx()))
                }
                h += 60
            }
            markerMinute?.takeIf { it in minM..maxM }?.let {
                drawLine(cs.primary.copy(alpha = 0.5f), Offset(px(it), 0f), Offset(px(it), bottom), 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 6f)))
            }
            for (s in withData) {
                val color = pal.route(s.colorIndex)
                // draw segment by segment: solid where ESS ≥ 3, dashed where data is thin
                for (k in 1 until s.points.size) {
                    val a = s.points[k - 1]
                    val b = s.points[k]
                    if (b.first - a.first > 30) continue // gap in data – don't bridge it
                    val solid = a.third >= 3 && b.third >= 3
                    drawLine(
                        color, Offset(px(a.first), py(a.second)), Offset(px(b.first), py(b.second)),
                        strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round,
                        pathEffect = if (solid) null else PathEffect.dashPathEffect(floatArrayOf(6f, 6f)),
                        alpha = if (solid) 1f else 0.6f,
                    )
                }
            }
            cross?.let { m ->
                drawLine(cs.onSurface.copy(alpha = 0.5f), Offset(px(m), 0f), Offset(px(m), bottom), 1.dp.toPx())
                for (s in withData) {
                    val p = s.points.minByOrNull { abs(it.first - m) } ?: continue
                    if (abs(p.first - m) > 15) continue
                    drawCircle(cs.surface, 6.dp.toPx(), Offset(px(p.first), py(p.second)))
                    drawCircle(pal.route(s.colorIndex), 4.dp.toPx(), Offset(px(p.first), py(p.second)))
                }
            }
        }
        cross?.let { m ->
            val lines = withData.mapNotNull { s ->
                val p = s.points.minByOrNull { abs(it.first - m) }?.takeIf { abs(it.first - m) <= 15 } ?: return@mapNotNull null
                "${s.label}: ${Fmt.mmss(p.second)} (≈${p.third.toInt()} Fahrten)"
            }
            if (lines.isNotEmpty()) Tooltip("${Fmt.minuteOfDay(m)} Uhr\n" + lines.joinToString("\n"), Modifier.align(Alignment.TopEnd))
        }
    }
}
