package io.embodit.spatialoffice.ui.chart

import android.os.SystemClock
import android.util.Log
import android.view.Choreographer
import android.os.Looper
import androidx.compose.ui.graphics.toArgb
import io.embodit.spatialoffice.core.layout.ChartPlan
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.withFrameNanos
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
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.text.TextStyle
import com.pico.spatial.ui.platform.LengthUnit
import com.pico.spatial.ui.platform.LocalPhysicalLengthConverter
import io.embodit.spatialoffice.core.layout.ChartPanels
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
/** v0.2.3: the chart opens turned this much (QA #3: seen straight on, the front row hid the other series). */
const val CHART_DEFAULT_YAW = -35f
/** Panel text sizes (≥ [ChartPanels.MIN_SP]) and button size, in the panels' own dp (fixed physical size, q3-1). */
private val SMALL = TextStyle(fontSize = ChartPanels.MIN_SP.sp, lineHeight = (ChartPanels.MIN_SP + 5).sp)
private val TITLE = TextStyle(fontSize = 18.sp, lineHeight = 22.sp)
private val VALUE = TextStyle(fontSize = 18.sp, lineHeight = 24.sp)
private val BUTTON = ChartPanels.BUTTON_DP.dp

/** One mark of the chart and the cell it shows (planned in core, [ChartPlan]). */
typealias Mark = ChartPlan.Mark

/** The cell behind value [index] of a series reference such as `Sales!$B$2:$M$2` (row-major). */
fun refCell(ref: String?, index: Int): Pair<Int, Int>? = ChartPlan.refCell(ref, index)

/**
 * The 3D chart: a standard chart from the file drawn as columns (x = categories, z = series, height = value) or as
 * points. Everything sits on a base plate under a pivot that turns about the vertical axis.
 *
 * ```
 * root (volume centre) └ fit (uniform scale to the volume's real size) └ pivot (yaw) └ marks group ├ base plate ├ marks…
 * ```
 *
 * v0.2.4 (QA #4 q4-1): v0.2.3 made a new UnlitMaterial and a new box mesh for each of 31 boxes on the main thread;
 * every `setBaseColor` / `createBox` waits on the engine thread, the chart took 5–7.5 s and the system showed
 * "not responding". Now [ChartPlan] lists the boxes; one material per colour, one mesh per size and the hit shapes
 * are made on a background thread; the entities (the SDK wants them on the main thread) are made in short batches
 * with a frame between them and hung in as one group (`CHART built … longest <ms>`). [MainWatch] logs the longest
 * main-thread frame gap while the chart opens.
 */
internal class ChartScene {
    val root = Entity()
    private val fit = Entity()
    private val pivot = Entity()
    @Volatile private var marks: Map<Entity, Mark> = emptyMap()
    private var group: Entity? = null
    private var signature = ""
    private var scale = 1f
    private val materials get() = ChartResources.materials
    private val meshes get() = ChartResources.meshes
    private val hits get() = ChartResources.hits
    /** When the volume asked for its first build (for the open time). */
    var openedAt = 0L
    var builds = 0
        private set

    init {
        root.addChild(fit); fit.addChild(pivot)
    }

    fun setRegion(halfX: Float, halfY: Float) {
        // the chart is modelled 0.64 m wide (base plate), 0.30 m high and 0.36 m deep. v0.2.3: it takes the span
        // between the two fixed-size panels (ChartPanels.chartSpan); v0.2.2: the volume sits over the sheet window,
        // so the chart is tipped [TILT]° towards the user (its top faces the eye below it); tipped, it needs about
        // 0.42 m of height per unit scale
        val (spanW, spanX) = ChartPanels.chartSpan(halfX * 2.0)
        val s = minOf(spanW.toFloat() / 0.64f, halfY * 2f / 0.48f).coerceIn(0.2f, 3f)
        if (abs(s - scale) < 1e-3f) return
        scale = s
        fit.components[TransformComponent::class.java]?.apply {
            setPosition(Vector3(spanX.toFloat(), 0f, 0f))
            setScaleVector(Vector3(s, s, s))
            val h = Math.toRadians(TILT.toDouble()).toFloat() / 2f
            setQuaternion(Quat(sin(h), 0f, 0f, cos(h)))
        }
        Log.i(TAG, "CHART region half=%.3fx%.3f scale=%.2f tilt=%.0f span=%.2f@%.3f".format(halfX, halfY, s, TILT, spanW, spanX))
    }

    fun setYaw(deg: Float) {
        val h = Math.toRadians(deg.toDouble()).toFloat() / 2f
        pivot.components[TransformComponent::class.java]?.setQuaternion(Quat(0f, sin(h), 0f, cos(h)))
    }

    fun markOf(e: Entity?): Mark? {
        val m = marks
        var cur = e
        while (cur != null) { m[cur]?.let { return it }; cur = cur.getParent() }
        return null
    }

    /**
     * Rebuilds the marks when the data, type or selection changed. Called on the main thread. The resources that wait
     * on the engine (materials, meshes, hit shapes) are made in [prepare] on [Dispatchers.Default], cached per
     * colour / size; the SDK only lets entities be made on the main thread, so they are made here a few at a time,
     * yielding a frame between batches ([BATCH_MS]), into a group that is hung into the volume in one step at the end.
     */
    suspend fun render(chart: ChartInfo?, scatter: Boolean, selRow: Int, selCol: Int) {
        val sig = (chart?.series?.joinToString("|") { it.values.joinToString(",") + "/" + (it.xValues?.joinToString(",") ?: "") } ?: "") + "#$scatter#$selRow,$selCol"
        if (sig == signature) return
        val t0 = SystemClock.uptimeMillis()
        val boxes = chart?.let { ChartPlan.boxes(it, scatter, selRow, selCol, PALETTE) } ?: emptyList()
        val made = withContext(Dispatchers.Default) { runCatching { ChartResources.prepare(boxes) }.onFailure { Log.e(TAG, "CHART prepare failed", it) }.getOrNull() } ?: return
        val t1 = SystemClock.uptimeMillis()
        // main thread, in batches: entities into a detached group
        val g = Entity()
        val map = HashMap<Entity, Mark>()
        var longest = 0L; var batches = 0; var i = 0
        while (i < boxes.size) {
            val b0 = SystemClock.uptimeMillis()
            while (i < boxes.size && SystemClock.uptimeMillis() - b0 < BATCH_MS) { entity(boxes[i], g, map); i++ }
            val took = SystemClock.uptimeMillis() - b0
            if (took > longest) longest = took
            batches++
            if (i < boxes.size) withFrameNanos { }
        }
        val t2 = SystemClock.uptimeMillis()
        // one step: swap the finished group in (the old one is destroyed on a later frame)
        val old = group
        old?.let { runCatching { pivot.removeChild(it) } }
        pivot.addChild(g)
        group = g; marks = map; signature = sig
        val swap = SystemClock.uptimeMillis() - t2
        if (old != null) { withFrameNanos { }; val d0 = SystemClock.uptimeMillis(); runCatching { old.destroy(true) }; Log.i(TAG, "CHART old group destroyed in ${SystemClock.uptimeMillis() - d0} ms") }
        builds++
        val first = builds == 1 && openedAt > 0
        Log.i(TAG, "CHART built type=${if (scatter) "scatter" else "columns"} series=${chart?.series?.size ?: 0} marks=${map.size} boxes=${boxes.size} " +
            "materials=${materials.size} meshes=${meshes.size} newResources=$made background=${t1 - t0} ms entities=${t2 - t1} ms in $batches batches (longest $longest ms) swap=$swap ms" +
            if (first) " open=${t2 + swap - openedAt} ms (asked → built)" else "")
    }

    /** Main thread: one box with its cached resources, into [g]. */
    private fun entity(b: ChartPlan.Box, g: Entity, map: HashMap<Entity, Mark>) {
        val e = Entity()
        // the SDK wants components made on the main thread (v0.2.4 emulator: IllegalStateException off it)
        val p = ChartResources.partsOf(b)
        e.components.set(p.model)
        e.components[TransformComponent::class.java]?.setPosition(Vector3(b.x, b.y, b.z))
        val mark = b.mark
        if (mark != null && p.hit != null) {
            e.components.set(p.hit)
            e.components.set(InteractableComponent())
            map[e] = mark
        }
        g.addChild(e)
    }

    companion object {
        val PALETTE = Ink.Series.map { it.toArgb() }
        /** Main-thread time per batch of entities before yielding a frame. */
        private const val BATCH_MS = 24L
    }
}

/**
 * The chart's materials (one per colour), box meshes (one per size) and hit shapes, kept for the whole process
 * (v0.2.4, q4-1): made on a background thread, first when the chart is asked for ([prewarm], while the system makes
 * the volume) so the volume finds them ready, and reused by every later opening and rebuild.
 */
internal object ChartResources {
    val materials = ConcurrentHashMap<Int, UnlitMaterial>()
    val meshes = ConcurrentHashMap<Long, MeshResource>()
    val hits = ConcurrentHashMap<Long, ShapeResource>()

    /** Background thread: makes what [boxes] need and is not made yet; returns how many were made. */
    fun prepare(boxes: List<ChartPlan.Box>): Int = synchronized(this) {
        val t0 = SystemClock.uptimeMillis()
        var made = 0
        for (b in boxes) {
            if (kept(materials, b.argb) { mat(b.argb) }) made++
            // gate:allow geometry data marks of a chart (columns, points) and its base plate; abstract, no prop or character
            if (kept(meshes, b.meshKey) { ChartPlan.size(b.meshKey).let { (w, h, d) -> MeshResource.createBox(Vector3(w, h, d), 0f) } }) made++
            // gate:allow geometry invisible hit box of one chart mark (a little larger than the mark, so thin columns are easy to hit)
            b.hitKey?.let { hk -> if (kept(hits, hk) { ChartPlan.size(hk).let { (w, h, d) -> ShapeResource.createBox(Vector3(w, h, d)) } }) made++ } // kept() makes it global: r.toGlobal()
        }
        Log.i(TAG, "CHART prepared ${boxes.size} boxes on ${Thread.currentThread().name}: $made new resources in ${SystemClock.uptimeMillis() - t0} ms")
        made
    }

    /** The components of one box (its model and, for a mark, its hit box), from the cached resources. */
    class Parts(val model: ModelComponent, val hit: CollisionComponent?)

    /**
     * Makes [key]'s resource if it is missing or no longer valid; true if it made one. Made global: a resource made
     * for one volume is released with that volume otherwise (v0.2.4 emulator: reopening the chart crashed with
     * "You can't construct ModelComponent with a closed MeshResource … convert the original into a global resource
     * via toGlobal()").
     */
    /** `Resource.isValid()` is public in the bytecode but not visible from Kotlin in SDK 6.2.8: read it by reflection. */
    private val isValidMethod by lazy { runCatching { com.pico.spatial.core.ecs.resource.Resource::class.java.getMethod("isValid") }.getOrNull() }
    private fun valid(r: com.pico.spatial.core.ecs.resource.Resource): Boolean = isValidMethod?.let { m -> runCatching { m.invoke(r) as Boolean }.getOrDefault(false) } ?: true

    private fun <K, R : com.pico.spatial.core.ecs.resource.Resource> kept(map: ConcurrentHashMap<K, R>, key: K, make: () -> R): Boolean {
        val cur = map[key]
        if (cur != null && valid(cur)) return false
        val r = make()
        runCatching { r.toGlobal() }.onFailure { Log.w(TAG, "CHART toGlobal: $it") }
        map[key] = r
        return true
    }

    fun partsOf(b: ChartPlan.Box): Parts {
        // a resource released in between (never expected once global): make it again here
        val ok = materials[b.argb]?.let(::valid) == true && meshes[b.meshKey]?.let(::valid) == true && (b.hitKey == null || b.mark == null || hits[b.hitKey!!]?.let(::valid) == true)
        if (!ok) Log.w(TAG, "CHART remade ${prepare(listOf(b))} resources on the main thread")
        // gate:allow geometry data marks of a chart (columns, points) and its base plate; abstract, no prop or character
        val model = ModelComponent(meshes.getValue(b.meshKey), materials.getValue(b.argb))
        // gate:allow geometry invisible hit box of one chart mark (a little larger than the mark, so thin columns are easy to hit)
        val hit = b.hitKey?.takeIf { b.mark != null }?.let { CollisionComponent(collisionShape = listOf(hits.getValue(it)), physicsMaterial = PhysicsMaterialResource()) }
        return Parts(model, hit)
    }

    /** Every colour the chart can use (series colours and their lighter variants, base plate, white) made ahead. */
    fun prewarmPalette(graph: Graph) {
        graph.scope.launch(Dispatchers.Default) {
            val t0 = SystemClock.uptimeMillis(); var made = 0
            synchronized(this@ChartResources) {
                val keys = listOf(ChartPlan.BASE, ChartPlan.WHITE) + ChartScene.PALETTE.flatMap { c -> listOf(0f, 0.35f, 0.45f, 0.5f).map { ChartPlan.lighten(c, it) } }
                runCatching { for (k in keys) if (kept(materials, k) { mat(k) }) made++ }.onFailure { Log.w(TAG, "CHART prewarm palette: $it") }
            }
            Log.i(TAG, "CHART prewarmed palette: $made materials in ${SystemClock.uptimeMillis() - t0} ms on ${Thread.currentThread().name}")
        }
    }

    /** Starts making the resources of the sheet's current chart (both types) in the background. */
    fun prewarm(graph: Graph) {
        val s = graph.chartSheet ?: return
        val chart = runCatching { s.book.charts().getOrNull(graph.chartIndex) }.getOrNull() ?: return
        val row = s.row; val col = s.col
        graph.scope.launch(Dispatchers.Default) {
            runCatching {
                ChartResources.prepare(ChartPlan.boxes(chart, graph.chartScatter, row, col, ChartScene.PALETTE))
                ChartResources.prepare(ChartPlan.boxes(chart, !graph.chartScatter, row, col, ChartScene.PALETTE))
            }.onFailure { Log.w(TAG, "CHART prewarm: $it") }
        }
    }

    private fun mat(argb: Int): UnlitMaterial = UnlitMaterial.create(BlendingMode.OPAQUE).apply {
        setBaseColor(Color4(((argb ushr 16) and 0xFF) / 255f, ((argb ushr 8) and 0xFF) / 255f, (argb and 0xFF) / 255f, 1f))
    }
}

/**
 * Logs the longest gap between main-thread frames while it runs (v0.2.4, q4-1: no single main-thread step > 100 ms).
 */
internal class MainWatch(private val label: String) : Choreographer.FrameCallback {
    @Volatile private var last = 0L
    private var worst = 0L
    private var frames = 0
    @Volatile private var running = false
    private var sampler: Thread? = null
    fun start() {
        if (running) return; running = true; last = 0L; worst = 0L; frames = 0
        Choreographer.getInstance().postFrameCallback(this)
        // while the main thread is held > 150 ms, log what it is doing once per stall
        sampler = Thread({
            var sampledFor = -1L
            while (running) {
                runCatching { Thread.sleep(50) }
                val l = last
                if (l > 0 && SystemClock.uptimeMillis() - l > 150 && sampledFor != l) {
                    sampledFor = l
                    val st = Looper.getMainLooper().thread.stackTrace.take(14).joinToString(" < ") { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" }
                    Log.w(TAG, "MAINWATCH $label stall sample: $st")
                }
            }
        }, "mainwatch").apply { isDaemon = true; start() }
    }
    fun stop(): Long { if (!running) return worst; running = false; Choreographer.getInstance().removeFrameCallback(this); Log.i(TAG, "MAINWATCH $label frames=$frames longest=${worst} ms"); return worst }
    override fun doFrame(frameTimeNanos: Long) {
        if (!running) return
        val now = SystemClock.uptimeMillis()
        if (last > 0) { val gap = now - last; if (gap > worst) worst = gap; if (gap > 100) Log.w(TAG, "MAINWATCH $label main thread held $gap ms") }
        last = now; frames++
        Choreographer.getInstance().postFrameCallback(this)
    }
}

/** V1 spatial chart (Volume, 1.76 × 0.26 × 0.32 m since v0.2.3, over the sheet window): the sheet's standard chart shown in 3D, linked with the grid. */
@Composable
fun ChartVolume() {
    WindowFrame(Win.CHART) { graph ->
        val s = graph.chartSheet
        val context = LocalContext.current
        val scene = remember { ChartScene().also { it.openedAt = graph.windows.chartAskedAt.takeIf { a -> a > 0 } ?: SystemClock.uptimeMillis() } }
        // q4-1: watch the main thread from the volume's first frame until 3 s after the chart is built
        val watch = remember { MainWatch("chart-open").also { it.start() } }
        // metres per dp of this volume's attachment panels; the panels are scaled to ChartPanels.M_PER_DP (q3-1)
        val baseMPerDp = LocalPhysicalLengthConverter.current.dpToLength(1.dp, LengthUnit.Meters)
        var panelsPlaced by remember { mutableStateOf(-1f) }
        var viewSize by remember { mutableStateOf(IntSize.Zero) }
        var ready by remember { mutableStateOf(false) }
        val chart = remember(s, s?.version, graph.chartIndex) { runCatching { s?.book?.charts()?.getOrNull(graph.chartIndex) }.getOrNull() }
        LaunchedEffect(ready, chart, graph.chartScatter, s?.row, s?.col) {
            if (ready) scene.render(chart, graph.chartScatter, s?.row ?: -1, s?.col ?: -1)
            if (ready && scene.builds == 1) { delay(3000); watch.stop() }
        }
        LaunchedEffect(ready, graph.chartYaw) { if (ready) scene.setYaw(graph.chartYaw) }
        DisposableEffect(Unit) { onDispose { watch.stop(); Log.i(TAG, "CHART volume disposed") } }
        SpatialView(
            modifier = Modifier.fillMaxSize().onSizeChanged { viewSize = it }
                .pointerInput(s) {
                    detectSpatialTapGesture(context = context, targetedToEntity = TargetEntity.any()) { v ->
                        val m = scene.markOf(v.targetEntity)
                        Log.i(TAG, "CHART tap mark=$m")
                        if (m != null && s != null && m.row >= 0) { s.select(m.row, m.col); graph.windows.front(graph.refOf(s)) }
                    }
                },
            attachments = {
                AttachmentPanel(LABEL) { PanelTheme(graph) { ChartLabel(graph, s, chart) } }
                AttachmentPanel(TIP) { PanelTheme(graph) { ChartControls(graph, s, chart) } }
            },
            initial = { content, attachments ->
                content.addEntity(scene.root)
                // the two panels stand left and right of the chart, inside the volume, a little towards the user
                attachments.entity(LABEL)?.let { p -> scene.root.addChild(p) }
                attachments.entity(TIP)?.let { p -> scene.root.addChild(p) }
                ready = true
                Log.i(TAG, "CHART volume initial done")
            },
            update = { content, attachments ->
                if (viewSize != IntSize.Zero) {
                    val corner = content.convertPosition(Vector3.ZERO, ViewCoordinateSpace.Local, content.localSpatialCoordinateSpace)
                    scene.setRegion(abs(corner.x), abs(corner.y))
                    val halfX = abs(corner.x); val halfY = abs(corner.y)
                    if (abs(panelsPlaced - halfX) > 1e-3f && baseMPerDp > 0f) {
                        panelsPlaced = halfX
                        // fixed physical size: the panel's dp become ChartPanels.M_PER_DP metres whatever the volume does
                        val k = (ChartPanels.M_PER_DP / baseMPerDp).toFloat()
                        val h = Math.toRadians(ChartPanels.TILT_DEG).toFloat() / 2f
                        for ((key, left) in listOf(LABEL to true, TIP to false)) attachments.entity(key)?.components?.get(TransformComponent::class.java)?.apply {
                            setPosition(Vector3(ChartPanels.panelX(left, halfX * 2.0).toFloat(), ChartPanels.panelY(halfY * 2.0).toFloat(), ChartPanels.PANEL_Z.toFloat()))
                            setScaleVector(Vector3(k, k, k))
                            setQuaternion(Quat(sin(h), 0f, 0f, cos(h)))
                        }
                        Log.i(TAG, "CHART panels base=%.5f m/dp target=%.5f scale=%.2f halfX=%.3f leftX=%.3f rightX=%.3f tilt=%.0f".format(
                            baseMPerDp, ChartPanels.M_PER_DP, k, halfX, ChartPanels.panelX(true, halfX * 2.0), ChartPanels.panelX(false, halfX * 2.0), ChartPanels.TILT_DEG))
                    }
                }
            },
        )
    }
}

/**
 * Selected value, columns / points, turn, reset, close (right panel). v0.2.3 (q3-1): one panel of a fixed physical
 * size, [ChartPanels.RIGHT_W_DP] × at most [ChartPanels.MAX_H_DP] dp, text ≥ [ChartPanels.MIN_SP] sp, round buttons
 * [ChartPanels.BUTTON_DP] dp; the "click a bar" hint moved to the sheet window's footer.
 */
@Composable
private fun ChartControls(graph: Graph, s: SheetSession?, chart: ChartInfo?) {
    Column(
        Modifier.width(ChartPanels.RIGHT_W_DP.dp).height(ChartPanels.MAX_H_DP.dp).clip(RoundedCornerShape(20.dp)).background(Color(0xF2F2F4F7))
            .padding(horizontal = 8.dp, vertical = 6.dp)
            .onSizeChanged { Log.i(TAG, "CHART panel right ${it.width}x${it.height}px") },
        verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically), horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // gate:allow tabs-segment chart type option (columns / points) of the same chart, not page switching
        Segments(listOf(false to tr(R.string.chart_columns), true to tr(R.string.chart_scatter)), graph.chartScatter, "chart/type", itemWidth = 108.dp, labelStyle = SMALL.copy(fontSize = 16.sp)) { graph.chartScatter = it }
        ToolRow {
            ToolIcon(R.drawable.ic_arrow_left, tr(R.string.chart_turn_left), onClick = { graph.chartYaw -= 30f }, size = BUTTON)
            ToolIcon(R.drawable.ic_arrow_right, tr(R.string.chart_turn_right), onClick = { graph.chartYaw += 30f }, size = BUTTON)
            ToolIcon(R.drawable.ic_rotate, tr(R.string.chart_reset), onClick = { graph.chartYaw = CHART_DEFAULT_YAW }, size = BUTTON)
            ToolIcon(R.drawable.ic_cross, tr(R.string.chart_close), onClick = { graph.windows.close(io.embodit.spatialoffice.platform.WinRef(Win.CHART)) }, size = BUTTON)
        }
    }
}

@Composable
private fun PanelTheme(graph: Graph, content: @Composable () -> Unit) {
    val base = LocalContext.current
    val lang = graph.settings.value.lang
    val localized = remember(base, lang) { base.localized(lang) }
    androidx.compose.runtime.CompositionLocalProvider(LocalContext provides localized, io.embodit.spatialoffice.ui.LocalRes provides localized.resources) { PicoTheme { io.embodit.spatialoffice.ui.SafeClickSound { content() } } }
}

/** Title, source range and legend (left panel, fixed physical size, q3-1). Dark pill so it reads on any room behind the volume. */
@Composable
private fun ChartLabel(graph: Graph, s: SheetSession?, chart: ChartInfo?) {
    s?.version
    Column(
        Modifier.width(ChartPanels.LEFT_W_DP.dp).height(ChartPanels.MAX_H_DP.dp).clip(RoundedCornerShape(20.dp)).background(Color(0xE61B1D22))
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .onSizeChanged { Log.i(TAG, "CHART panel left ${it.width}x${it.height}px") },
        verticalArrangement = Arrangement.spacedBy(0.dp, Alignment.CenterVertically),
    ) {
        if (s == null || chart == null) { Txt(tr(R.string.chart_closed_file), "chart/none", style = SMALL, color = Color.White, maxLines = 2); return@Column }
        Txt(chart.title.ifEmpty { tr(R.string.chart_window) }, "chart/title", style = TITLE, color = Color.White, weight = FontWeight.Bold)
        Txt(s.book.sheetNames.getOrElse(chart.sheet) { "" } + (chart.range?.let { "!$it" } ?: ""), "chart/src", style = SMALL, color = Color(0xFFD5D9E0))
        // legend: three per row (up to six series in two rows)
        chart.series.take(6).chunked(3).forEachIndexed { row, items ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                items.forEachIndexed { j, se ->
                    val i = row * 3 + j
                    Row(Modifier.width(88.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(14.dp).clip(RoundedCornerShape(3.dp)).background(Ink.Series[i % Ink.Series.size]))
                        Txt(se.name.ifEmpty { "${i + 1}" }, "chart/legend", Modifier.padding(start = 6.dp), style = SMALL, color = Color.White)
                    }
                }
            }
        }
        ChartTip(s, chart)
    }
}

/** The selected mark's name and value, one line. */
@Composable
private fun ChartTip(s: SheetSession?, chart: ChartInfo?) {
    s?.version
    if (s == null || chart == null) return
    var name = ""; var value = ""
    chart.series.forEachIndexed { _, se ->
        se.values.indices.forEach { i ->
            val cell = refCell(se.valuesRef, i)
            if (cell != null && cell.first == s.row && cell.second == s.col) {
                name = listOf(se.categories.getOrNull(i).orEmpty(), se.name).filter { it.isNotEmpty() }.joinToString(" · ")
                value = s.book.cell(chart.sheet, cell.first, cell.second).display
            }
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (name.isNotEmpty()) {
            Txt(name, "chart/tip-name", Modifier.weight(1f, fill = false), style = SMALL, color = Color(0xFFD5D9E0))
            Txt(value, "chart/tip-value", Modifier.padding(start = 10.dp), style = VALUE, color = Color.White, weight = FontWeight.Bold)
        }
    }
}
