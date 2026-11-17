package io.embodit.spatialoffice.ui.chart

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import io.embodit.spatialoffice.ui.tr
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.pico.spatial.core.ecs.CollisionComponent
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.InteractableComponent
import com.pico.spatial.core.ecs.ModelComponent
import com.pico.spatial.core.ecs.TransformComponent
import com.pico.spatial.core.ecs.ViewCoordinateSpace
import com.pico.spatial.core.ecs.resource.BlendingMode
import com.pico.spatial.core.ecs.resource.MeshResource
import com.pico.spatial.core.ecs.resource.PhysicsMaterialResource
import com.pico.spatial.core.ecs.resource.ShapeResource
import com.pico.spatial.core.ecs.resource.UnlitMaterial
import com.pico.spatial.core.math.Color4
import com.pico.spatial.core.math.Quat
import com.pico.spatial.core.math.Vector3
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.foundation.content.SpatialView
import com.pico.spatial.ui.foundation.gesture.TargetEntity
import com.pico.spatial.ui.foundation.gesture.detectSpatialTapGesture
import io.embodit.spatialoffice.R
import io.embodit.spatialoffice.core.xlsx.A1
import io.embodit.spatialoffice.core.xlsx.ChartInfo
import io.embodit.spatialoffice.platform.Graph
import io.embodit.spatialoffice.platform.SheetSession
import io.embodit.spatialoffice.platform.TAG
import io.embodit.spatialoffice.platform.Win
import io.embodit.spatialoffice.ui.Ink
import io.embodit.spatialoffice.ui.Segments
import io.embodit.spatialoffice.ui.ToolIcon
import io.embodit.spatialoffice.ui.ToolRow
import io.embodit.spatialoffice.ui.Txt
import io.embodit.spatialoffice.ui.WindowFrame
import io.embodit.spatialoffice.ui.localized
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

private const val LABEL = "chart-label"
private const val TIP = "chart-tip"
/** Tip of the chart towards the user (degrees about x): the volume is above the eye, 25°–35° up. */
private const val TILT = 40f

/** One mark of the chart and the cell it shows. */
data class Mark(val series: Int, val index: Int, val value: Double, val row: Int, val col: Int)

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

/**
 * The 3D chart: a standard chart from the file drawn as columns (x = categories, z = series, height = value) or as
 * points. Everything sits on a base plate under a pivot that turns about the vertical axis.
 *
 * ```
 * root (volume centre) └ fit (uniform scale to the volume's real size) └ pivot (yaw) ├ base plate ├ marks…
 * ```
 */
internal class ChartScene {
    val root = Entity()
    private val fit = Entity()
    private val pivot = Entity()
    private val marks = HashMap<Entity, Mark>()
    private var signature = ""
    private var scale = 1f

    init {
        root.addChild(fit); fit.addChild(pivot)
    }

    fun setRegion(halfX: Float, halfY: Float) {
        // the chart is modelled 0.64 m wide (base plate), 0.30 m high and 0.36 m deep; it takes the middle half of
        // the volume's width, the label panel stands on its left and the tip + controls on its right. v0.2.2: the
        // volume sits over the sheet window, so the chart is tipped [TILT]° towards the user (its top faces the eye
        // below it); tipped, it needs about 0.42 m of height per unit scale
        val s = minOf(halfX * 2f * 0.5f / 0.64f, halfY * 2f / 0.42f).coerceIn(0.2f, 3f)
        if (abs(s - scale) < 1e-3f) return
        scale = s
        fit.components[TransformComponent::class.java]?.apply {
            setScaleVector(Vector3(s, s, s))
            val h = Math.toRadians(TILT.toDouble()).toFloat() / 2f
            setQuaternion(Quat(sin(h), 0f, 0f, cos(h)))
        }
        Log.i(TAG, "CHART region half=%.3fx%.3f scale=%.2f tilt=%.0f".format(halfX, halfY, s, TILT))
    }

    fun setYaw(deg: Float) {
        val h = Math.toRadians(deg.toDouble()).toFloat() / 2f
        pivot.components[TransformComponent::class.java]?.setQuaternion(Quat(0f, sin(h), 0f, cos(h)))
    }

    fun markOf(e: Entity?): Mark? {
        var cur = e
        while (cur != null) { marks[cur]?.let { return it }; cur = cur.getParent() }
        return null
    }

    fun render(chart: ChartInfo?, scatter: Boolean, selRow: Int, selCol: Int) {
        val sig = (chart?.series?.joinToString("|") { it.values.joinToString(",") + "/" + (it.xValues?.joinToString(",") ?: "") } ?: "") + "#$scatter#$selRow,$selCol"
        if (sig == signature) return
        signature = sig
        for (c in pivot.getChildren().toList()) { runCatching { pivot.removeChild(c) }; runCatching { c.destroy(true) } }
        marks.clear()
        chart ?: return
        runCatching { build(chart, scatter, selRow, selCol) }.onFailure { Log.e(TAG, "CHART build failed", it) }
    }

    private fun mat(c: Color, lighten: Float = 0f): UnlitMaterial = UnlitMaterial.create(BlendingMode.OPAQUE).apply {
        setBaseColor(Color4(c.red + (1f - c.red) * lighten, c.green + (1f - c.green) * lighten, c.blue + (1f - c.blue) * lighten, 1f))
    }

    private fun box(w: Float, h: Float, d: Float, x: Float, y: Float, z: Float, m: UnlitMaterial, mark: Mark? = null): Entity {
        val e = Entity()
        // gate:allow geometry data marks of a chart (columns, points) and its base plate; abstract, no prop or character
        e.components.set(ModelComponent(MeshResource.createBox(Vector3(w, h, d), 0f), m))
        e.components[TransformComponent::class.java]?.setPosition(Vector3(x, y, z))
        if (mark != null) {
            // gate:allow geometry invisible hit box of one chart mark (a little larger than the mark, so thin columns are easy to hit)
            e.components.set(CollisionComponent(collisionShape = listOf(ShapeResource.createBox(Vector3(w * 1.15f, h.coerceAtLeast(0.02f), d * 1.15f))), physicsMaterial = PhysicsMaterialResource()))
            e.components.set(InteractableComponent())
            marks[e] = mark
        }
        pivot.addChild(e)
        return e
    }

    private fun build(chart: ChartInfo, scatter: Boolean, selRow: Int, selCol: Int) {
        val w = 0.60f; val d = 0.32f; val hMax = 0.26f; val floor = -0.16f
        box(w + 0.04f, 0.008f, d + 0.04f, 0f, floor - 0.004f, 0f, mat(Color(0xFFE9ECF2)))
        val series = chart.series.take(6)
        if (series.isEmpty()) return
        val max = series.flatMap { it.values }.filterNotNull().maxOrNull()?.takeIf { it > 0 } ?: 1.0
        val white = Color.White
        if (!scatter) {
            val nCat = series.maxOf { it.values.size }.coerceAtLeast(1)
            val cellW = w / nCat; val cellD = d / series.size
            series.forEachIndexed { si, s ->
                val color = Ink.Series[si % Ink.Series.size]
                s.values.forEachIndexed { ci, v ->
                    val value = v ?: return@forEachIndexed
                    val cell = refCell(s.valuesRef, ci)
                    val selected = cell != null && cell.first == selRow && cell.second == selCol
                    val h = (value / max * hMax).toFloat().coerceAtLeast(0.004f)
                    val x = -w / 2 + cellW * (ci + 0.5f); val z = -d / 2 + cellD * (si + 0.5f)
                    val mark = Mark(si, ci, value, cell?.first ?: -1, cell?.second ?: -1)
                    box(cellW * 0.72f, h, cellD * 0.72f, x, floor + h / 2, z, mat(color, if (selected) 0.45f else 0f), mark)
                    // a lighter cap makes the top face read without lighting; the selected column gets a white one
                    box(cellW * 0.72f, 0.004f, cellD * 0.72f, x, floor + h + 0.002f, z, mat(if (selected) white else color, if (selected) 0f else 0.35f))
                }
            }
        } else {
            // points: x = the series' x values (or the position), height = value, one row of depth per series
            val xs = series.flatMap { s -> s.xValues?.filterNotNull() ?: s.values.indices.map { it.toDouble() } }
            val x0 = xs.minOrNull() ?: 0.0; val x1 = (xs.maxOrNull() ?: 1.0).let { if (it == x0) x0 + 1 else it }
            val cellD = d / series.size
            series.forEachIndexed { si, s ->
                val color = Ink.Series[si % Ink.Series.size]
                s.values.forEachIndexed { ci, v ->
                    val value = v ?: return@forEachIndexed
                    val xv = s.xValues?.getOrNull(ci) ?: ci.toDouble()
                    val cell = refCell(s.valuesRef, ci)
                    val selected = cell != null && cell.first == selRow
                    val x = (-w / 2 + (xv - x0) / (x1 - x0) * w).toFloat(); val z = -d / 2 + cellD * (si + 0.5f)
                    val y = floor + (value / max * hMax).toFloat()
                    // v0.2.2: bigger points (QA #2: hard to see with a small data set)
                    val size = if (selected) 0.042f else 0.032f
                    box(size, size, size, x, y, z, mat(if (selected) white else color), Mark(si, ci, value, cell?.first ?: -1, cell?.second ?: -1))
                    box(0.002f, (y - floor).coerceAtLeast(0.001f), 0.002f, x, floor + (y - floor) / 2, z, mat(color, 0.5f)) // drop line to the base
                }
            }
        }
        Log.i(TAG, "CHART built type=${if (scatter) "scatter" else "columns"} series=${series.size} marks=${marks.size}")
    }
}

/** V1 spatial chart (Volume, 0.90 × 0.26 × 0.32 m, over the sheet window): the sheet's standard chart shown in 3D, linked with the grid. */
@Composable
fun ChartVolume() {
    WindowFrame(Win.CHART) { graph ->
        val s = graph.sheet
        val context = LocalContext.current
        val scene = remember { ChartScene() }
        var viewSize by remember { mutableStateOf(IntSize.Zero) }
        var ready by remember { mutableStateOf(false) }
        val chart = remember(s, s?.version, graph.chartIndex) { runCatching { s?.book?.charts()?.getOrNull(graph.chartIndex) }.getOrNull() }
        LaunchedEffect(ready, chart, graph.chartScatter, s?.row, s?.col) {
            if (ready) scene.render(chart, graph.chartScatter, s?.row ?: -1, s?.col ?: -1)
        }
        LaunchedEffect(ready, graph.chartYaw) { if (ready) scene.setYaw(graph.chartYaw) }
        DisposableEffect(Unit) { onDispose { Log.i(TAG, "CHART volume disposed") } }
        SpatialView(
            modifier = Modifier.fillMaxSize().onSizeChanged { viewSize = it }
                .pointerInput(s) {
                    detectSpatialTapGesture(context = context, targetedToEntity = TargetEntity.any()) { v ->
                        val m = scene.markOf(v.targetEntity)
                        Log.i(TAG, "CHART tap mark=$m")
                        if (m != null && s != null && m.row >= 0) { s.select(m.row, m.col); graph.windows.open(Win.SHEET) }
                    }
                },
            attachments = {
                AttachmentPanel(LABEL) { PanelTheme(graph) { ChartLabel(graph, s, chart) } }
                AttachmentPanel(TIP) { PanelTheme(graph) { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { ChartTip(graph, s, chart); ChartControls(graph) } } }
            },
            initial = { content, attachments ->
                content.addEntity(scene.root)
                // the two panels stand left and right of the chart, inside the volume, a little towards the user
                attachments.entity(LABEL)?.let { p -> p.components[TransformComponent::class.java]?.setPosition(Vector3(-0.335f, 0f, 0.06f)); scene.root.addChild(p) }
                attachments.entity(TIP)?.let { p -> p.components[TransformComponent::class.java]?.setPosition(Vector3(0.335f, 0f, 0.06f)); scene.root.addChild(p) }
                ready = true
                Log.i(TAG, "CHART volume initial done")
            },
            update = { content, _ ->
                if (viewSize != IntSize.Zero) {
                    val corner = content.convertPosition(Vector3.ZERO, ViewCoordinateSpace.Local, content.localSpatialCoordinateSpace)
                    scene.setRegion(abs(corner.x), abs(corner.y))
                }
            },
        )
    }
}

/**
 * Columns / points, turn, reset, close. They used to be a system Toolbar under the volume; with the volume under the
 * sheet window that ornament would fall below the view (q2-2), so they live in the right-hand panel.
 */
@Composable
private fun ChartControls(graph: Graph) {
    Column(
        Modifier.width(250.dp).clip(RoundedCornerShape(20.dp)).background(Color(0xF2F2F4F7)).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp), horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // gate:allow tabs-segment chart type option (columns / points) of the same chart, not page switching
        Segments(listOf(false to tr(R.string.chart_columns), true to tr(R.string.chart_scatter)), graph.chartScatter, "chart/type", itemWidth = 104.dp) { graph.chartScatter = it }
        ToolRow {
            ToolIcon(R.drawable.ic_arrow_left, tr(R.string.chart_turn_left), onClick = { graph.chartYaw -= 30f })
            ToolIcon(R.drawable.ic_arrow_right, tr(R.string.chart_turn_right), onClick = { graph.chartYaw += 30f })
            ToolIcon(R.drawable.ic_rotate, tr(R.string.chart_reset), onClick = { graph.chartYaw = 0f })
            ToolIcon(R.drawable.ic_cross, tr(R.string.chart_close), onClick = { graph.windows.close(Win.CHART) })
        }
    }
}

@Composable
private fun PanelTheme(graph: Graph, content: @Composable () -> Unit) {
    val base = LocalContext.current
    val lang = graph.settings.value.lang
    val localized = remember(base, lang) { base.localized(lang) }
    androidx.compose.runtime.CompositionLocalProvider(LocalContext provides localized, io.embodit.spatialoffice.ui.LocalRes provides localized.resources) { PicoTheme { content() } }
}

/** Title, source range and legend. Dark pill so it reads on any room behind the volume. */
@Composable
private fun ChartLabel(graph: Graph, s: SheetSession?, chart: ChartInfo?) {
    s?.version
    Column(Modifier.width(250.dp).clip(RoundedCornerShape(20.dp)).background(Color(0xE61B1D22)).padding(horizontal = 18.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (s == null || chart == null) { Txt(tr(R.string.chart_closed_file), "chart/none", color = Color.White, maxLines = 2); return@Column }
        Txt(chart.title.ifEmpty { tr(R.string.chart_window) }, "chart/title", style = PicoTheme.typography.titleMedium, color = Color.White, weight = FontWeight.Bold, maxLines = 2)
        Txt(tr(R.string.chart_src, s.book.sheetNames.getOrElse(chart.sheet) { "" } + (chart.range?.let { "!$it" } ?: "")), "chart/src", style = PicoTheme.typography.bodySmall, color = Color(0xFFD5D9E0), maxLines = 2)
        chart.series.take(6).forEachIndexed { i, se ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(12.dp).clip(RoundedCornerShape(3.dp)).background(Ink.Series[i % Ink.Series.size]))
                Txt(se.name.ifEmpty { "${i + 1}" }, "chart/legend", Modifier.padding(start = 8.dp), style = PicoTheme.typography.bodySmall, color = Color.White)
            }
        }
    }
}

/** The selected mark's name and value, and how to pick another. */
@Composable
private fun ChartTip(graph: Graph, s: SheetSession?, chart: ChartInfo?) {
    s?.version
    if (s == null || chart == null) return
    var name = ""; var value = ""
    chart.series.forEachIndexed { _, se ->
        se.values.indices.forEach { i ->
            val cell = refCell(se.valuesRef, i)
            if (cell != null && cell.first == s.row && cell.second == s.col) {
                name = listOf(se.name, se.categories.getOrNull(i).orEmpty()).filter { it.isNotEmpty() }.joinToString(" · ")
                value = s.book.cell(chart.sheet, cell.first, cell.second).display
            }
        }
    }
    Column(Modifier.width(250.dp).clip(RoundedCornerShape(20.dp)).background(Color(0xE61B1D22)).padding(horizontal = 18.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (name.isNotEmpty()) {
            Txt(name, "chart/tip-name", style = PicoTheme.typography.bodyMedium, color = Color(0xFFD5D9E0), maxLines = 2)
            Txt(value, "chart/tip-value", style = PicoTheme.typography.headlineSmall, color = Color.White, weight = FontWeight.Bold)
        }
        Txt(tr(if (graph.chartScatter) R.string.tip_dot else R.string.tip_bar), "chart/tip-hint", style = PicoTheme.typography.bodySmall, color = Color(0xFFD5D9E0), maxLines = 2)
    }
}
