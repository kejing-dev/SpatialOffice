package io.embodit.spatialoffice.core.layout

import io.embodit.spatialoffice.core.xlsx.A1
import io.embodit.spatialoffice.core.xlsx.ChartInfo
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * What the 3D chart is made of, as plain data (v0.2.4, QA #4 q4-1).
 *
 * v0.2.3 built the chart on the main thread and gave every box a new UnlitMaterial: each `setBaseColor` and
 * `MeshResource.createBox` waits on the engine thread (`runBlocking`), 31 boxes took 5–7.5 s and the system showed
 * "not responding". The app now plans the boxes here, creates one material per distinct colour and one mesh per
 * distinct size off the main thread ([materialKeys], [meshKeys]), builds the entity tree off the main thread too and
 * only hangs the finished tree into the volume on the main thread (one `addChild`).
 */
object ChartPlan {
    /** One chart mark and the cell it shows. */
    data class Mark(val series: Int, val index: Int, val value: Double, val row: Int, val col: Int)

    /** One box of the chart, in the chart's model space (metres), its colour (ARGB) and the mark it stands for. */
    data class Box(val w: Float, val h: Float, val d: Float, val x: Float, val y: Float, val z: Float, val argb: Int, val mark: Mark? = null) {
        /** Boxes of the same size share one mesh (0.1 mm steps). */
        val meshKey: Long get() = key(w, h, d)
        val hitKey: Long? get() = mark?.let { key(w * 1.15f, h.coerceAtLeast(0.02f), d * 1.15f) }
    }

    const val BASE = 0xFFE9ECF2.toInt()
    const val WHITE = 0xFFFFFFFF.toInt()
    const val MAX_SERIES = 6

    /** The cell behind value [index] of a series reference such as `Sales!$B$2:$M$2` (row-major). */
    fun refCell(ref: String?, index: Int): Pair<Int, Int>? {
        val r = ref?.substringAfterLast('!')?.replace("$", "") ?: return null
        val parts = r.split(':')
        val a = A1.parse(parts[0]) ?: return null
        val b = A1.parse(parts.getOrElse(1) { parts[0] }) ?: return null
        val rows = abs(b.first - a.first) + 1; val cols = abs(b.second - a.second) + 1
        if (index !in 0 until rows * cols) return null
        return if (rows == 1) a.first to minOf(a.second, b.second) + index else if (cols == 1) minOf(a.first, b.first) + index to a.second
        else minOf(a.first, b.first) + index / cols to minOf(a.second, b.second) + index % cols
    }

    /** [argb] mixed towards white by [t] (0 = as is, 1 = white). */
    fun lighten(argb: Int, t: Float): Int {
        if (t <= 0f) return argb
        fun ch(shift: Int): Int { val c = (argb ushr shift) and 0xFF; return (c + (255 - c) * t).roundToInt().coerceIn(0, 255) }
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    /**
     * The boxes of [chart] drawn as columns (x = categories, z = series, height = value) or as points, on a base
     * plate. [palette] is the series colours (ARGB). Same geometry as v0.2.3.
     */
    fun boxes(chart: ChartInfo, scatter: Boolean, selRow: Int, selCol: Int, palette: List<Int>): List<Box> {
        val out = ArrayList<Box>()
        val w = 0.60f; val d = 0.32f; val hMax = 0.26f; val floor = -0.16f
        out += Box(w + 0.04f, 0.008f, d + 0.04f, 0f, floor - 0.004f, 0f, BASE)
        val series = chart.series.take(MAX_SERIES)
        if (series.isEmpty()) return out
        val max = series.flatMap { it.values }.filterNotNull().maxOrNull()?.takeIf { it > 0 } ?: 1.0
        if (!scatter) {
            val nCat = series.maxOf { it.values.size }.coerceAtLeast(1)
            val cellW = w / nCat; val cellD = d / series.size
            series.forEachIndexed { si, s ->
                val color = palette[si % palette.size]
                s.values.forEachIndexed { ci, v ->
                    val value = v ?: return@forEachIndexed
                    val cell = refCell(s.valuesRef, ci)
                    val selected = cell != null && cell.first == selRow && cell.second == selCol
                    val h = (value / max * hMax).toFloat().coerceAtLeast(0.004f)
                    val x = -w / 2 + cellW * (ci + 0.5f); val z = -d / 2 + cellD * (si + 0.5f)
                    val mark = Mark(si, ci, value, cell?.first ?: -1, cell?.second ?: -1)
                    out += Box(cellW * 0.72f, h, cellD * 0.72f, x, floor + h / 2, z, lighten(color, if (selected) 0.45f else 0f), mark)
                    // a lighter cap makes the top face read without lighting; the selected column gets a white one
                    out += Box(cellW * 0.72f, 0.004f, cellD * 0.72f, x, floor + h + 0.002f, z, if (selected) WHITE else lighten(color, 0.35f))
                }
            }
        } else {
            val xs = series.flatMap { s -> s.xValues?.filterNotNull() ?: s.values.indices.map { it.toDouble() } }
            val x0 = xs.minOrNull() ?: 0.0; val x1 = (xs.maxOrNull() ?: 1.0).let { if (it == x0) x0 + 1 else it }
            val cellD = d / series.size
            series.forEachIndexed { si, s ->
                val color = palette[si % palette.size]
                s.values.forEachIndexed { ci, v ->
                    val value = v ?: return@forEachIndexed
                    val xv = s.xValues?.getOrNull(ci) ?: ci.toDouble()
                    val cell = refCell(s.valuesRef, ci)
                    val selected = cell != null && cell.first == selRow
                    val x = (-w / 2 + (xv - x0) / (x1 - x0) * w).toFloat(); val z = -d / 2 + cellD * (si + 0.5f)
                    val y = floor + (value / max * hMax).toFloat()
                    val size = if (selected) 0.042f else 0.032f
                    out += Box(size, size, size, x, y, z, if (selected) WHITE else color, Mark(si, ci, value, cell?.first ?: -1, cell?.second ?: -1))
                    out += Box(0.002f, (y - floor).coerceAtLeast(0.001f), 0.002f, x, floor + (y - floor) / 2, z, lighten(color, 0.5f)) // drop line
                }
            }
        }
        return out
    }

    /** The materials the chart needs: one per distinct colour. */
    fun materialKeys(boxes: List<Box>): Set<Int> = boxes.mapTo(LinkedHashSet()) { it.argb }

    /** The meshes the chart needs: one per distinct box size. */
    fun meshKeys(boxes: List<Box>): Set<Long> = boxes.mapTo(LinkedHashSet()) { it.meshKey }

    /** Width, height, depth (metres) back from a mesh / hit key. */
    fun size(key: Long): Triple<Float, Float, Float> =
        Triple(((key ushr 42) and 0x1FFFFF).toFloat() / 10000f, ((key ushr 21) and 0x1FFFFF).toFloat() / 10000f, (key and 0x1FFFFF).toFloat() / 10000f)

    private fun key(w: Float, h: Float, d: Float): Long {
        fun q(v: Float): Long = (v * 10000f).roundToInt().toLong().coerceIn(1, 0x1FFFFF)
        return (q(w) shl 42) or (q(h) shl 21) or q(d)
    }

    /** The main thread must not be held longer than this by one step of opening the chart (q4-1). */
    const val MAIN_STEP_BUDGET_MS = 100L
    /** Chart open (asked → built) on the emulator; 2 s when it is under load. */
    const val OPEN_BUDGET_MS = 1000L
    const val OPEN_BUDGET_LOADED_MS = 2000L
}
