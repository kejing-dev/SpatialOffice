package io.embodit.spatialoffice.ui.space

import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.mutableStateMapOf
import com.pico.spatial.core.container.SpatialViewContent
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.SceneUpdateContext
import com.pico.spatial.core.ecs.System
import com.pico.spatial.core.ecs.TransformComponent
import com.pico.spatial.core.math.Quat
import com.pico.spatial.core.math.Vector3
import com.pico.spatial.ui.foundation.content.SpatialViewAttachments
import io.embodit.spatialoffice.core.files.FileKind
import io.embodit.spatialoffice.core.layout.LayoutRules
import io.embodit.spatialoffice.core.layout.PanelPose
import io.embodit.spatialoffice.core.layout.Seat
import io.embodit.spatialoffice.platform.Graph
import io.embodit.spatialoffice.platform.Head
import io.embodit.spatialoffice.platform.StageMode
import io.embodit.spatialoffice.platform.TAG
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

internal object ActiveStage {
    @Volatile var scene: StageScene? = null
}

/** The one per-frame system of the Stage: moves every panel to where the layout rules say. */
class StageFrameSystem : System() {
    override fun update(context: SceneUpdateContext) {
        ActiveStage.scene?.onFrame()
    }
}

/** Ids of the attachment panels. They are composed up front (the SDK needs them declared), then shown and hidden as a pool. */
object StagePanels {
    const val MAX_PIECES = 12
    fun piece(slot: Int) = "piece-$slot"
    const val BAR = "bar"
    const val SCREEN = "screen"
    const val NOTES = "notes"
    const val NEXT = "next"
    val all: List<String> = (0 until MAX_PIECES).map(::piece) + listOf(BAR, SCREEN, NOTES, NEXT)

    /** Metres per dp of an attachment panel at scale 1 (measured by the factory on SDK 6.x). */
    const val DP_M = 0.0008f

    /** Composed width of a piece panel in dp; with [pieceScale] it comes out at the size [LayoutRules.panelSize] asks for. */
    fun pieceDp(kind: FileKind): Pair<Int, Int> = when (kind.editor) {
        FileKind.SLIDES -> 700 to 394
        FileKind.SHEET -> 580 to 780
        else -> 600 to 800
    }

    fun pieceScale(kind: FileKind): Float = LayoutRules.panelSize(kind).width / (pieceDp(kind).first * DP_M)
}

/**
 * The Stage (Mixed): the pieces of one file around the seat, a layout bar under the focused piece, or the rehearsal
 * set (big screen, notes, next slide, bar). Every 2D surface is an attachment panel parented to a holder entity in
 * Stage space; holders glide to their target pose each frame. Nothing is dragged and nothing follows the head: the
 * seat is fixed when the layout is entered and when the user presses [Bring back to front].
 */
class StageScene(private val graph: Graph) {
    /** Panel id → shown; the Compose side draws a panel's content only while it is shown. */
    val shown = mutableStateMapOf<String, Boolean>()
    /** Piece slot → piece index it currently shows. */
    val assigned = mutableStateMapOf<Int, Int>()

    private var root: Entity? = null
    private val holders = HashMap<String, Entity>()
    private val panels = HashMap<String, Entity>()
    private val boundAt = HashMap<String, Long>()
    private val current = HashMap<String, FloatArray>() // x, y, z, yaw, scale
    private var seat: Seat? = null
    private var seenRecenter = -1
    private var frames = 0L
    private var lastLog = 0L

    fun attach(content: SpatialViewContent, attachments: SpatialViewAttachments) {
        val r = Entity()
        content.addEntity(r)
        // make the root's local space equal to Stage space, so holder positions are Stage coordinates
        runCatching {
            val offset = r.convertPositionFrom(Vector3.ZERO, null)
            val rot = r.convertRotationFrom(Quat.identity(), null)
            r.components[TransformComponent::class.java]?.apply { setPosition(offset); setQuaternion(rot) }
        }
        root = r
        for (id in StagePanels.all) {
            val h = Entity()
            r.addChild(h)
            holders[id] = h
            park(h)
        }
        bind(attachments)
        ActiveStage.scene = this
        Log.i(TAG, "STAGE attached panels=${StagePanels.all.size}")
    }

    fun bind(attachments: SpatialViewAttachments) {
        val now = SystemClock.elapsedRealtime()
        for (id in StagePanels.all) {
            if (panels.containsKey(id)) continue
            val p = attachments.entity(id) ?: continue
            holders[id]?.addChild(p)
            panels[id] = p
            boundAt[id] = now
        }
    }

    fun detach() {
        if (ActiveStage.scene === this) ActiveStage.scene = null
        for (h in holders.values) runCatching { h.removeAllChildren() }
        panels.clear(); holders.clear(); current.clear()
        root = null
        Log.i(TAG, "STAGE detached")
    }

    /** First piece shown by the pool: a window of [StagePanels.MAX_PIECES] pieces around the focused one. */
    private fun windowStart(count: Int, focus: Int): Int =
        if (count <= StagePanels.MAX_PIECES) 0 else (focus - StagePanels.MAX_PIECES / 2).coerceIn(0, count - StagePanels.MAX_PIECES)

    fun onFrame() {
        root ?: return
        frames++
        val head = Head.sample()
        if (seat == null || seenRecenter != graph.recenterTick) {
            // the seat: head position and heading when entering, or when [Bring back to front] is pressed
            if (head != null) { seat = head.seat(); seenRecenter = graph.recenterTick; Log.i(TAG, "STAGE seat=${seat} from head") }
            else if (frames > 45) { seat = Seat(0f, 1.25f, 0f, 0f); seenRecenter = graph.recenterTick; Log.i(TAG, "STAGE seat=default (no head pose)") }
        }
        val st = seat
        val mode = graph.stageMode
        val wanted = HashMap<String, FloatArray>()
        if (st != null && mode == StageMode.SPREAD) {
            val s = graph.session(graph.stageKind)
            val count = s?.pieceCount ?: 0
            val focus = graph.focus.coerceIn(0, (count - 1).coerceAtLeast(0))
            val poses = LayoutRules.poses(graph.stageKind, count, graph.arrangement, focus, graph.radiusSetting)
            val start = windowStart(count, focus)
            val k = StagePanels.pieceScale(graph.stageKind)
            for (slot in 0 until StagePanels.MAX_PIECES) {
                val i = start + slot
                if (i >= count) { assigned.remove(slot); continue }
                if (assigned[slot] != i) assigned[slot] = i
                wanted[StagePanels.piece(slot)] = target(st, poses[i], k * poses[i].scale)
            }
            wanted[StagePanels.BAR] = target(st, LayoutRules.barPose(graph.stageKind, graph.radiusSetting), 1f, tiltDeg = -22f)
        } else if (st != null && mode == StageMode.REHEARSAL) {
            val r = LayoutRules.rehearsal()
            wanted[StagePanels.SCREEN] = target(st, r.getValue("screen"), r.getValue("screen").width / (960 * StagePanels.DP_M))
            wanted[StagePanels.NOTES] = target(st, r.getValue("notes"), 1.1f)
            wanted[StagePanels.NEXT] = target(st, r.getValue("next"), 1.1f)
            wanted[StagePanels.BAR] = target(st, r.getValue("bar"), 1f, tiltDeg = -22f)
        }
        val now = SystemClock.elapsedRealtime()
        for (id in StagePanels.all) {
            val h = holders[id] ?: continue
            val w = wanted[id]
            val settled = boundAt[id]?.let { now - it > 600L } == true
            if (w != null && settled) {
                if (!h.enabled) h.enabled = true
                val c = current[id]
                // glide: 18 % of the way per frame; a panel that was hidden appears in place
                val next = if (c == null || shown[id] != true) w else FloatArray(6) { i -> c[i] + (w[i] - c[i]) * 0.18f }
                current[id] = next
                apply(h, next)
                if (shown[id] != true) { shown[id] = true; Log.i(TAG, "PANEL show $id at=${"%.2f,%.2f,%.2f".format(w[0], w[1], w[2])} scale=${"%.2f".format(w[4])}") }
            } else {
                if (shown[id] == true) { shown[id] = false; Log.i(TAG, "PANEL hide $id") }
                park(h)
                if (settled && h.enabled) h.enabled = false
            }
        }
        if (now - lastLog > 5000) {
            lastLog = now
            Log.i(TAG, "STAGE frame=$frames mode=$mode head=${head?.let { "%.2f,%.2f,%.2f pitch=%.0f".format(it.x, it.y, it.z, it.pitchDeg) }} shown=${shown.filterValues { it }.keys.sorted()}")
        }
    }

    /** Seat-local pose → Stage-space target (x, y, z, yaw, scale, tilt). */
    private fun target(seat: Seat, p: PanelPose, scale: Float, tiltDeg: Float = 0f): FloatArray {
        val (x, y, z) = seat.toStage(p.x, p.y, p.z)
        return floatArrayOf(x, y, z, seat.yawRad + (p.yawDeg * PI / 180.0).toFloat(), scale, (tiltDeg * PI / 180.0).toFloat())
    }

    private fun apply(e: Entity, v: FloatArray) {
        val t = e.components[TransformComponent::class.java] ?: return
        t.setPosition(Vector3(v[0], v[1], v[2]))
        // yaw about the vertical axis, then a tilt about the panel's own horizontal axis (bars lean back towards the eyes)
        val hy = v[3] / 2f; val hx = v[5] / 2f
        val cy = cos(hy); val sy = sin(hy); val cx = cos(hx); val sx = sin(hx)
        t.setQuaternion(Quat(cy * sx, sy * cx, -sy * sx, cy * cx))
        t.setScaleVector(Vector3(v[4], v[4], v[4]))
    }

    private fun park(h: Entity) {
        val t = h.components[TransformComponent::class.java] ?: return
        t.setPosition(Vector3(0f, -40f, 0f))
        t.setScaleVector(Vector3(0.001f, 0.001f, 0.001f))
    }

    fun seatForDebug(): Seat? = seat
}
