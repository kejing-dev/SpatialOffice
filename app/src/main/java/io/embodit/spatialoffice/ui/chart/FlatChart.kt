package io.embodit.spatialoffice.ui.chart

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.PicoTheme
import io.embodit.spatialoffice.core.xlsx.ChartInfo
import io.embodit.spatialoffice.ui.Ink
import io.embodit.spatialoffice.ui.Txt

/** Series colours: one hue per series, readable on the white chart card (≥ 3:1 for marks). */
val SERIES = listOf(Color(0xFF2563EB), Color(0xFFEA580C), Color(0xFF059669), Color(0xFF9333EA), Color(0xFFDB2777), Color(0xFF0891B2))

/** A flat preview of a sheet chart: columns, lines or a pie, with the categories / series named underneath. */
@Composable
fun FlatChart(c: ChartInfo, modifier: Modifier = Modifier) {
    val series = c.series.filter { s -> s.values.any { it != null } }
    Column(modifier.clip(RoundedCornerShape(16.dp)).background(Ink.Paper).padding(20.dp).semantics { contentDescription = "flat-chart-${c.type}" }) {
        Canvas(Modifier.fillMaxWidth().weight(1f)) {
            if (series.isEmpty()) return@Canvas
            val w = size.width; val h = size.height
            if (c.type == "pie") {
                val vals = series[0].values.map { (it ?: 0.0).coerceAtLeast(0.0) }
                val total = vals.sum().takeIf { it > 0 } ?: return@Canvas
                val d = minOf(w, h) * 0.9f
                var start = -90f
                vals.forEachIndexed { i, v ->
                    val sweep = (v / total * 360.0).toFloat()
                    drawArc(SERIES[i % SERIES.size], start, sweep, true, Offset((w - d) / 2, (h - d) / 2), Size(d, d))
                    start += sweep
                }
                return@Canvas
            }
            val n = series.maxOf { it.values.size }
            val all = series.flatMap { s -> s.values.filterNotNull() }
            val max = (all.maxOrNull() ?: 1.0).coerceAtLeast(0.0)
            val min = (all.minOrNull() ?: 0.0).coerceAtMost(0.0)
            val span = (max - min).takeIf { it > 0 } ?: 1.0
            fun y(v: Double) = (h - ((v - min) / span * h)).toFloat()
            drawLine(Ink.Line, Offset(0f, y(0.0)), Offset(w, y(0.0)), strokeWidth = 2f)
            val slot = w / n
            if (c.type == "line") {
                series.forEachIndexed { k, s ->
                    val path = Path()
                    var started = false
                    s.values.forEachIndexed { i, v ->
                        if (v == null) return@forEachIndexed
                        val p = Offset(slot * i + slot / 2, y(v))
                        if (!started) { path.moveTo(p.x, p.y); started = true } else path.lineTo(p.x, p.y)
                        drawCircle(SERIES[k % SERIES.size], 7f, p)
                    }
                    drawPath(path, SERIES[k % SERIES.size], style = Stroke(width = 5f))
                }
            } else {
                val bw = slot * 0.7f / series.size
                series.forEachIndexed { k, s ->
                    s.values.forEachIndexed { i, v ->
                        if (v == null) return@forEachIndexed
                        val x = slot * i + slot * 0.15f + bw * k
                        val top = minOf(y(v), y(0.0)); val bottom = maxOf(y(v), y(0.0))
                        drawRect(SERIES[k % SERIES.size], Offset(x, top), Size(bw * 0.92f, (bottom - top).coerceAtLeast(1f)))
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        // legend: categories for a pie, series names otherwise
        val names = if (c.type == "pie") series.firstOrNull()?.categories ?: emptyList() else series.map { it.name }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            names.take(8).forEachIndexed { i, name ->
                Box(Modifier.size(14.dp).clip(RoundedCornerShape(3.dp)).background(SERIES[i % SERIES.size]))
                Txt(name, "chart/legend", Modifier.padding(start = 6.dp, end = 16.dp), style = PicoTheme.typography.bodyMedium, color = Ink.Text)
            }
        }
        if (c.type != "pie") {
            val cats = series.firstOrNull()?.categories ?: emptyList()
            Txt(cats.take(12).joinToString("   "), "chart/cats", Modifier.padding(top = 6.dp), style = PicoTheme.typography.bodySmall, color = Ink.TextSoft, maxLines = 2)
        }
        Spacer(Modifier.width(1.dp))
    }
}
