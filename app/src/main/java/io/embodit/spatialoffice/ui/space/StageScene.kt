package io.embodit.spatialoffice.ui.space

import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.mutableStateMapOf
import com.pico.spatial.core.container.SpatialViewContent
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.OpacityControllerComponent
import com.pico.spatial.core.ecs.SceneUpdateContext
import com.pico.spatial.core.ecs.System
import com.pico.spatial.core.ecs.TransformComponent
import com.pico.spatial.core.math.Quat
import com.pico.spatial.core.math.Vector3
import com.pico.spatial.ui.foundation.content.SpatialViewAttachments
import io.embodit.spatialoffice.core.files.FileKind
import io.embodit.spatialoffice.core.layout.Arrangement
import io.embodit.spatialoffice.platform.DeckSession
import io.embodit.spatialoffice.core.layout.LayoutRules
import io.embodit.spatialoffice.core.layout.Motion
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
    /** Panels in the pool: the pieces shown at once ([WINDOW], or a chapter and its piles) plus the ones still on their way out. */
    const val MAX_PIECES = 18
    /** Pieces laid out at once when the file is not laid out by chapters. */
    const val WINDOW = 12
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
 * Stage space; holders move to their target pose as damped springs (core/layout/Motion), played by [choreograph]. Nothing is dragged and nothing follows the head: the
 * seat is fixed when the layout is entered and when the user presses [Bring back to front].
 */
class StageScene(private val graph: Graph) {
    /** Panel id → shown; the Compose side draws a panel's content only while it is shown. */
    val shown = mutableStateMapOf<String, Boolean>()
    /** Piece slot → piece index it currently shows. */
    val assigned = mutableStateMapOf<Int, Int>()
    /**
     * Panels that are on their way out of the layout (id → true when the panel showed a chapter's pile): they keep
     * showing what they showed, whatever the layout says they would be now.
     */
    val frozen = mutableStateMapOf<String, Boolean>()

    private var root: Entity? = null
    private val holders = HashMap<String, Entity>()
    private val panels = HashMap<String, Entity>()
    private val boundAt = HashMap<String, Long>()
    private val current = HashMap<String, FloatArray>() // x, y, z, yaw, scale
    private var seat: Seat? = null
    // ---- motion (core/layout/Motion): every panel is a spring towards its pose ----
    private val bodies = HashMap<String, Motion.Body>()
    /** How each panel moves right now (the focused piece lands with a small give). */
    private val feels = HashMap<String, Motion.Feel>()
    /** Where a panel that is about to appear starts from, and how long it waits (set by [choreograph], used once). */
    private val starts = HashMap<String, FloatArray>()
    private val delays = HashMap<String, Float>()
    /** Panels on their way out (the Stage is closing, or their chapter closes into its pile): the pose they go to, and until when at the latest. */
    private val exits = HashMap<String, FloatArray>()
    private val exitUntil = HashMap<String, Long>()
    /** This frame's pieces that are shown and no longer laid out / that just got a panel (id → piece index); [choreograph] plays them. */
    private val leavers = LinkedHashMap<String, Int>()
    /** Where every laid-out piece was to stand at the last frame (piece → pose): a step is played as one move of the whole row. */
    private val prevWant = HashMap<Int, FloatArray>()
    /**
     * A panel that changes its part when another chapter comes to the front (a slide of the row becomes its chapter's
     * pile, or the other way round): it first goes out with the row ([relays], until [relayUntil]), then comes in at its new place.
     */
    private val relays = HashMap<String, FloatArray>()
    private val relayUntil = HashMap<String, Long>()
    /** Where such a panel comes in from, and how long it waits first (default: at its place, at once). */
    private val relayStart = HashMap<String, FloatArray>()
    private val relayDelay = HashMap<String, Float>()
    /** Largest overlap of two pieces seen during the move that is being played (degrees, weighted by how visible both are), for the log. */
    private var overlapMax = 0f
    private var overlapWho = ""
    private val newcomers = LinkedHashMap<String, Int>()
    /** Chapter of the slide in front at the last frame (−1 = not laid out by chapters). */
    private var prevChapter = -1
    private var chapters: List<io.embodit.spatialoffice.core.pptx.Chapter> = emptyList()
    private var lastFrameAt = 0L
    private var prevMode: StageMode? = null
    private var prevArrangement: Arrangement? = null
    private var prevFocus = -1
    private var lastFocusPose: FloatArray? = null
    private var moveName: String? = null
    private var moveAt = 0L
    private var moveFrame = 0L
    private var seenRecenterForMotion = -1
    private var prevSlide = -1
    /** An opening move is waiting for calm frames ([onFrame]). */
    private var holding = false
    private var holdSince = 0L
    private var calm = 0
    /** An opening move has begun and its first panel is not shown yet (the windows behind the Stage close at that moment). */
    private var opening = false
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
        bodies.clear(); exits.clear(); exitUntil.clear(); starts.clear(); delays.clear(); feels.clear(); prevMode = null; lastFrameAt = 0L; moveName = null
        leavers.clear(); newcomers.clear(); prevChapter = -1
        prevWant.clear(); endRelays(); frozen.clear()
        root = null
        Log.i(TAG, "STAGE detached")
    }

    /** First piece laid out when the file is not laid out by chapters: a window of [StagePanels.WINDOW] pieces around the focused one. */
    private fun windowStart(count: Int, focus: Int): Int =
        if (count <= StagePanels.WINDOW) 0 else (focus - StagePanels.WINDOW / 2).coerceIn(0, count - StagePanels.WINDOW)

    /** The storyline is laid out by chapters: a deck in the Chapters arrangement. */
    private fun byChapters(): Boolean = graph.arrangement == Arrangement.CHAPTERS && graph.stageSession is DeckSession

    /** The pieces that are laid out now, by piece index: never further to the side than the view goes ([LayoutRules.MAX_AZ]). */
    private fun piecePoses(count: Int, focus: Int): Map<Int, PanelPose> {
        val deck = graph.stageSession as? DeckSession
        val arrangement = graph.arrangement
        chapters = emptyList()
        if (arrangement.front) return LayoutRules.frontPoses(graph.stageKind, count, arrangement, focus, graph.radiusSetting)
        if (deck != null && arrangement == Arrangement.CHAPTERS) {
            chapters = deck.chapters()
            if (chapters.isNotEmpty()) return LayoutRules.chapterPoses(graph.stageKind, chapters.map { it.first to it.count }, focus, graph.radiusSetting)
        }
        val poses = LayoutRules.poses(graph.stageKind, count, arrangement, focus, graph.radiusSetting)
        val start = windowStart(count, focus)
        val out = LinkedHashMap<Int, PanelPose>()
        for (i in start until minOf(count, start + StagePanels.WINDOW)) if (i == focus || LayoutRules.inView(poses[i])) out[i] = poses[i]
        return out
    }

    /**
     * Gives every laid-out piece a panel and keeps it: a piece stays on its panel as long as it is shown, so it moves
     * as one thing when the layout changes (no content jumping from panel to panel). Pieces that are shown and no
     * longer laid out go to [leavers], pieces that just got a panel to [newcomers].
     */
    private fun assign(want: Map<Int, PanelPose>) {
        leavers.clear(); newcomers.clear()
        for ((slot, piece) in assigned.toMap()) {
            val id = StagePanels.piece(slot)
            if (piece in want) { exits.remove(id); exitUntil.remove(id); continue }
            if (id in exits) continue
            if (shown[id] == true && bodies[id] != null) leavers[id] = piece else assigned.remove(slot)
        }
        val have = assigned.values.toHashSet()
        for (piece in want.keys) {
            if (piece in have) continue
            var slot = (0 until StagePanels.MAX_PIECES).firstOrNull { it !in assigned }
            if (slot == null) {
                // the pool is full: a panel that is on its way out gives way at once
                slot = assigned.keys.firstOrNull { StagePanels.piece(it) in exits || StagePanels.piece(it) in leavers } ?: continue
                val id = StagePanels.piece(slot)
                exits.remove(id); exitUntil.remove(id); leavers.remove(id); bodies.remove(id); shown[id] = false
            }
            assigned[slot] = piece
            newcomers[StagePanels.piece(slot)] = piece
        }
    }

    private fun idOf(piece: Int): String? = assigned.entries.firstOrNull { it.value == piece }?.let { StagePanels.piece(it.key) }

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
            val s = graph.stageSession
            val count = s?.pieceCount ?: 0
            val focus = graph.focus.coerceIn(0, (count - 1).coerceAtLeast(0))
            val want = piecePoses(count, focus)
            assign(want)
            val k = StagePanels.pieceScale(graph.stageKind)
            for ((slot, piece) in assigned) want[piece]?.let { wanted[StagePanels.piece(slot)] = target(st, it, k * it.scale, it.tiltDeg) }
            // the bar sits under the pieces in front of the seat, whatever the template's sizes and rows
            val bottom = want.values.filter { kotlin.math.abs(it.azimuthDeg) < 32f }.minOfOrNull { it.bottom }
            wanted[StagePanels.BAR] = target(st, LayoutRules.barPose(graph.stageKind, graph.radiusSetting, bottom), 1f, tiltDeg = -22f)
        } else if (st != null && mode == StageMode.REHEARSAL) {
            val r = LayoutRules.rehearsal()
            wanted[StagePanels.SCREEN] = target(st, r.getValue("screen"), r.getValue("screen").width / (960 * StagePanels.DP_M))
            // watching: the screen and the bar only; the speaker's panels belong to rehearsing
            if (!graph.watching) {
                wanted[StagePanels.NOTES] = target(st, r.getValue("notes"), 1.1f)
                wanted[StagePanels.NEXT] = target(st, r.getValue("next"), 1.1f)
            }
            wanted[StagePanels.BAR] = target(st, r.getValue("bar"), 1f, tiltDeg = -22f)
        }
        val now = SystemClock.elapsedRealtime()
        // real time between frames: the motion is the same whatever the container's frame rate (the system lowers it at will)
        val dt = if (lastFrameAt == 0L) 1f / 72f else ((now - lastFrameAt) / 1000f).coerceIn(0.001f, Motion.MAX_DT)
        lastFrameAt = now
        choreograph(st, mode, wanted, now)
        // An opening move waits out the stall that comes with it (the panels are composed for the first time, the windows
        // close): it starts on the second calm frame in a row, or after half a second. The pieces stand in their stack meanwhile.
        var step = dt
        if (holding) {
            calm = if (dt < 0.05f) calm + 1 else 0
            if (calm >= 2 || now - holdSince > 500) { holding = false; Log.i(TAG, "MOTION go after ${now - holdSince} ms hold (calm frames=$calm)") } else step = 0f
        }
        var moving = 0
        for (id in StagePanels.all) {
            val h = holders[id] ?: continue
            val settled = boundAt[id]?.let { now - it > 600L } == true
            val leaving = exits[id]
            var relay = relays[id]
            if (relay != null && now > (relayUntil[id] ?: 0L)) {
                // gone with the row: now it comes in at its new place (the others have made room by then)
                relays.remove(id); relayUntil.remove(id); relay = null
                val from = relayStart.remove(id); val wait = relayDelay.remove(id) ?: 0.02f
                wanted[id]?.let { to -> bodies.remove(id); starts[id] = from ?: to.copyOf().also { it[Motion.SCALE] *= 0.88f; it[Motion.ALPHA] = 0f }; delays[id] = wait }
            }
            val w = relay ?: wanted[id] ?: leaving
            val out = relay != null || (leaving != null && wanted[id] == null)
            if (!out && id in frozen) frozen.remove(id)
            if (w != null && settled) {
                if (!h.enabled) h.enabled = true
                var body = bodies[id]
                if (body == null || (shown[id] != true && leaving == null)) {
                    // a panel that was hidden: it starts where the choreography put it, or in place
                    body = Motion.Body((starts.remove(id) ?: w).copyOf(), delay = delays.remove(id) ?: 0f)
                    bodies[id] = body
                }
                Motion.step(body, w, step, feels[id] ?: Motion.GLIDE)
                apply(h, body.pos)
                val there = Motion.arrived(body, w)
                if (!there) moving++
                if (leaving != null && (there || now > (exitUntil[id] ?: 0L))) {
                    exits.remove(id); exitUntil.remove(id); bodies.remove(id); shown[id] = false; park(h)
                    // its panel is free for another piece
                    if (wanted[id] == null && id.startsWith("piece-")) assigned.remove(slotOf(id))
                    Log.i(TAG, "PANEL hide $id (gathered)")
                } else if (shown[id] != true) {
                    // the first panel of an opening move: the windows behind the Stage can go now
                    if (opening) { opening = false; graph.windows.stageReady("first panel") }
                    shown[id] = true
                    Log.i(TAG, "PANEL show $id at=${"%.2f,%.2f,%.2f".format(w[0], w[1], w[2])} scale=${"%.2f".format(w[4])}")
                }
            } else {
                if (w != null) moving++ // wanted, but its panel is not bound long enough yet: the move has not begun
                if (shown[id] == true) { shown[id] = false; Log.i(TAG, "PANEL hide $id") }
                bodies.remove(id)
                park(h)
                if (settled && h.enabled) h.enabled = false
            }
        }
        // one line when a move has come to rest: what it was, how long it took and whether pieces lay over one another on the way (QA reads these)
        val name = moveName
        val stepMove = name != null && (name.startsWith("focus") || name.startsWith("chapter"))
        if (stepMove && st != null && mode == StageMode.SPREAD) measureOverlap(st)
        if (name != null && moving == 0 && now - moveAt > 80) {
            val ms = (now - moveAt).coerceAtLeast(1)
            Log.i(TAG, "MOTION done $name in $ms ms frames=${frames - moveFrame} (${"%.0f".format((frames - moveFrame) * 1000f / ms)} fps)${if (stepMove) " overlap=${"%.1f".format(overlapMax)}°$overlapWho" else ""}")
            moveName = null
        }
        if (now - lastLog > 5000) {
            lastLog = now
            Log.i(TAG, "STAGE frame=$frames mode=$mode head=${head?.let { "%.2f,%.2f,%.2f pitch=%.0f".format(it.x, it.y, it.z, it.pitchDeg) }} shown=${shown.filterValues { it }.keys.sorted()}")
        }
    }

    private fun began(name: String, now: Long) { moveName = name; moveAt = now; moveFrame = frames; overlapMax = 0f; overlapWho = ""; Log.i(TAG, "MOTION start $name") }

    /**
     * How far two pieces lie over one another right now, seen from the seat: the widest such pair of this move is kept
     * for the log. Degrees of overlap from side to side, times how visible the fainter of the two is; pieces at clearly
     * different distances (the far ring of the layers) are one behind the other on purpose and do not count.
     */
    private fun measureOverlap(st: Seat) {
        val (wDp, hDp) = StagePanels.pieceDp(graph.stageKind)
        val ids = bodies.keys.filter { it.startsWith("piece-") && shown[it] == true }
        for (i in ids.indices) for (j in i + 1 until ids.size) {
            val a = bodies[ids[i]]?.pos ?: continue; val b = bodies[ids[j]]?.pos ?: continue
            val seen = minOf(a[Motion.ALPHA], b[Motion.ALPHA]).coerceIn(0f, 1f)
            if (seen < 0.15f) continue
            val da = kotlin.math.hypot(a[0] - st.x, a[2] - st.z); val db = kotlin.math.hypot(b[0] - st.x, b[2] - st.z)
            if (kotlin.math.abs(da - db) > 0.3f) continue
            val ha = hDp * StagePanels.DP_M * a[Motion.SCALE] / 2f; val hb = hDp * StagePanels.DP_M * b[Motion.SCALE] / 2f
            if (kotlin.math.abs(a[1] - b[1]) >= ha + hb - 0.01f) continue
            // each panel's left and right edge as directions from the seat, measured from the direction between the two
            val rx = (a[0] - st.x) + (b[0] - st.x); val rz = (a[2] - st.z) + (b[2] - st.z)
            fun span(p: FloatArray): Pair<Float, Float> {
                val hw = wDp * StagePanels.DP_M * p[Motion.SCALE] / 2f
                val ex = cos(p[Motion.YAW]) * hw; val ez = -sin(p[Motion.YAW]) * hw
                fun dir(x: Float, z: Float) = Math.toDegrees(kotlin.math.atan2((rx * z - rz * x).toDouble(), (rx * x + rz * z).toDouble())).toFloat()
                val l = dir(p[0] - st.x - ex, p[2] - st.z - ez); val r = dir(p[0] - st.x + ex, p[2] - st.z + ez)
                return minOf(l, r) to maxOf(l, r)
            }
            val sa = span(a); val sb = span(b)
            val over = (minOf(sa.second, sb.second) - maxOf(sa.first, sb.first)) * seen
            if (over > overlapMax) { overlapMax = over; overlapWho = " (pieces ${pieceOf(ids[i]) + 1} and ${pieceOf(ids[j]) + 1}, ${"%.0f".format(seen * 100)} % visible)" }
        }
    }

    private fun hold(now: Long) { holding = true; holdSince = now; calm = 0; opening = true }

    private fun slotOf(id: String): Int = id.removePrefix("piece-").toIntOrNull() ?: 0

    /** The piece a piece panel shows. */
    private fun pieceOf(id: String): Int = assigned[slotOf(id)] ?: 0

    private fun leave(id: String, to: FloatArray, now: Long, ms: Long, feel: Motion.Feel = Motion.GATHER, asPile: Boolean = false) {
        exits[id] = to; exitUntil[id] = now + ms; feels[id] = feel; frozen[id] = asPile
    }

    /** [place] moved by [by] (x, y, z, yaw; [part] of it). */
    private fun shifted(place: FloatArray, by: FloatArray, part: Float = 1f): FloatArray =
        place.copyOf().also { for (i in intArrayOf(Motion.X, Motion.Y, Motion.Z, Motion.YAW)) it[i] += by[i] * part }

    private fun between(from: FloatArray, to: FloatArray): FloatArray? {
        val d = FloatArray(Motion.SIZE)
        for (i in intArrayOf(Motion.X, Motion.Y, Motion.Z)) d[i] = to[i] - from[i]
        d[Motion.YAW] = Motion.shortest(to[Motion.YAW] - from[Motion.YAW])
        return d.takeIf { kotlin.math.abs(d[0]) + kotlin.math.abs(d[1]) + kotlin.math.abs(d[2]) > 0.005f }
    }

    private fun same(a: FloatArray, b: FloatArray): Boolean =
        kotlin.math.abs(a[0] - b[0]) < 0.03f && kotlin.math.abs(a[1] - b[1]) < 0.03f && kotlin.math.abs(a[2] - b[2]) < 0.03f

    /** How far the piece that stands at [place] now has come since the last frame's layout; null when none of [movers] stands there. */
    private fun cameBy(place: FloatArray, nowWant: Map<Int, FloatArray>, movers: Set<Int>): FloatArray? {
        for (q in movers) { val to = nowWant[q] ?: continue; val was = prevWant[q] ?: continue; if (same(to, place)) return between(was, to) }
        return null
    }

    /** How far the piece that stood at [place] has gone on; null when none of [movers] stood there. */
    private fun wentBy(place: FloatArray, nowWant: Map<Int, FloatArray>, movers: Set<Int>): FloatArray? {
        for (q in movers) { val to = nowWant[q] ?: continue; val was = prevWant[q] ?: continue; if (same(was, place)) return between(was, to) }
        return null
    }

    /** A panel's place seen from the seat: direction (degrees), height, distance. */
    private fun polar(p: FloatArray, st: Seat): FloatArray {
        val dx = p[Motion.X] - st.x; val dz = p[Motion.Z] - st.z
        return floatArrayOf(Math.toDegrees(kotlin.math.atan2(dx, -dz).toDouble()).toFloat(), p[Motion.Y], kotlin.math.hypot(dx, dz))
    }

    /** True when the move a → b is the move fa → fb of the piece in front: the same turn round the seat (a ring), or the same shift (a straight row). */
    private fun withFocus(a: FloatArray, b: FloatArray, fa: FloatArray, fb: FloatArray, st: Seat): Boolean {
        val pa = polar(a, st); val pb = polar(b, st); val qa = polar(fa, st); val qb = polar(fb, st)
        val turn = Math.toDegrees(Motion.shortest(Math.toRadians((pb[0] - pa[0] - (qb[0] - qa[0])).toDouble()).toFloat()).toDouble())
        if (kotlin.math.abs(turn) < 0.7 && kotlin.math.abs((pb[1] - pa[1]) - (qb[1] - qa[1])) < 0.012f && kotlin.math.abs((pb[2] - pa[2]) - (qb[2] - qa[2])) < 0.03f) return true
        return (0..2).all { kotlin.math.abs((b[it] - a[it]) - (fb[it] - fa[it])) < 0.015f }
    }

    private fun endRelays() { relays.clear(); relayUntil.clear(); relayStart.clear(); relayDelay.clear() }

    /** [p] a little further from the seat ([by] metres; below 0 = nearer): two panels never lie in one plane while one fades over the other. */
    private fun back(p: FloatArray, st: Seat, by: Float): FloatArray {
        val dx = p[Motion.X] - st.x; val dz = p[Motion.Z] - st.z
        val len = kotlin.math.sqrt(dx * dx + dz * dz).coerceAtLeast(1e-4f)
        p[Motion.X] += dx / len * by; p[Motion.Z] += dz / len * by
        return p
    }

    /**
     * Decides how this frame's change is played. Nothing here moves a panel: it only sets where new panels start,
     * how long each waits and how each moves; [Motion.step] does the rest.
     * - entering the spread: the pieces are dealt out of one stack at the focused place, the focused one first;
     * - another arrangement: the pieces leave for their new places one after the other, from the focus outwards;
     * - another piece in front: the whole row moves on as one, every piece at the same speed (pieces stand edge to
     *   edge: one that is quicker than its neighbour runs into it). A piece that is pushed out at the end of the row
     *   goes on the same way and is gone before it gets anywhere; the one that comes in follows the row the same way.
     *   Where a chapter's pile stands at that end, the piece shrinks away into the gap instead;
     * - another chapter in front (storyline by chapters): the row moves on by the way the new piece in front has to
     *   go; the old chapter's slides go with it and are gone on the way, the piles move up, then the new chapter's
     *   slides appear in their places. No piece comes out from under another;
     * - another page of the grid: the old four fade where they are, then the new four fade in;
     * - a piece that would have to cross the others to get to its new place (up into the top row, into a side column,
     *   to the far ring) does not travel: it goes out where it is and comes in where it belongs;
     * - rehearsal: the slide in front grows into the big screen, notes and next slide come in from the sides, the bar rises;
     * - leaving: everything gathers back into the stack within the 350 ms before the Stage closes.
     */
    private fun choreograph(st: Seat?, mode: StageMode?, wanted: HashMap<String, FloatArray>, now: Long) {
        val focus = graph.focus
        if (st != null && mode == StageMode.SPREAD) {
            val focusId = idOf(focus)
            val focusPose = focusId?.let { wanted[it] }
            val chapter = if (chapters.isEmpty()) -1 else io.embodit.spatialoffice.core.pptx.Chapters.indexOf(chapters, focus)
            if (focusPose != null) lastFocusPose = focusPose
            val entering = prevMode != StageMode.SPREAD
            if (entering && focusPose != null) {
                val stack = stackAt(st, focusPose)
                // coming back from the rehearsal, the big screen shrinks into the piece in front
                val from = bodies[StagePanels.SCREEN]?.pos
                for (id in wanted.keys) {
                    val w = wanted.getValue(id)
                    if (id == StagePanels.BAR) {
                        starts[id] = w.copyOf().also { it[Motion.Y] -= 0.16f; it[Motion.SCALE] *= 0.6f; it[Motion.ALPHA] = 0f }
                        delays[id] = 0.22f
                    } else {
                        starts[id] = if (id == focusId && from != null) from.copyOf() else stack
                        delays[id] = Motion.fanDelay(pieceOf(id), focus)
                    }
                    bodies.remove(id)
                }
                began("spread-open pieces=${wanted.size - 1} arrangement=${graph.arrangement}", now)
                hold(now)
            } else if (!entering && prevArrangement != null && prevArrangement != graph.arrangement) {
                for ((id, body) in bodies) {
                    if (id.startsWith("piece-") && body.delay <= 0f && id !in exits) body.delay = Motion.fanDelay(pieceOf(id), focus, stepMs = 30, maxMs = 240)
                }
                began("arrange $prevArrangement -> ${graph.arrangement}", now)
            } else if (!entering && chapter >= 0 && prevChapter >= 0 && chapter != prevChapter) {
                began("chapter ${prevChapter + 1} -> ${chapter + 1} (in=${newcomers.size} out=${leavers.size})", now)
            } else if (!entering && prevFocus != focus && prevFocus >= 0) {
                began("focus $prevFocus -> $focus", now)
            }
            // every piece → where it is to stand now
            val nowWant = HashMap<Int, FloatArray>()
            for ((slot, piece) in assigned) wanted[StagePanels.piece(slot)]?.let { nowWant[piece] = it }
            val sameLayout = !entering && prevArrangement == graph.arrangement
            val chapterChanged = sameLayout && chapter >= 0 && prevChapter >= 0 && chapter != prevChapter
            val stepping = sameLayout && !chapterChanged && prevFocus != focus && prevFocus >= 0
            if (chapterChanged) endRelays()
            if (chapterChanged && focusPose != null) {
                // Another chapter in front. The whole row moves on by the way the new piece in front has to go ([row]):
                // the old chapter's slides go with it and are gone on the way, the piles move up, and only then do the new
                // chapter's slides appear in their places. Nothing comes out from under another piece.
                val was = { piece: Int -> io.embodit.spatialoffice.core.pptx.Chapters.indexOf(chapters, piece) != prevChapter } // it showed as a pile
                val pileOfNew = prevWant[chapters[chapter].first]
                val row = (prevWant[focus] ?: pileOfNew)?.let { between(it, focusPose) }
                for ((id, piece) in leavers) {
                    val body = bodies[id] ?: continue
                    val place = prevWant[piece] ?: body.pos
                    body.delay = 0f
                    val to = (if (row != null) shifted(place, row) else place.copyOf()).also { it[Motion.ALPHA] = -1f }
                    // the pile of the chapter that opens goes with the slide that comes out of it, at that slide's size:
                    // one turns into the other (it fades more slowly, so the room never shows through the two)
                    val opening = piece == chapters[chapter].first
                    if (opening) { to[Motion.SCALE] = focusPose[Motion.SCALE]; to[Motion.ALPHA] = -0.3f }
                    leave(id, back(to, st, 0.015f), now, if (opening) MORPH_MS else GONE_MS, Motion.GLIDE, asPile = was(piece))
                }
                for ((slot, piece) in assigned.toMap()) {
                    val id = StagePanels.piece(slot)
                    val to = wanted[id] ?: continue
                    val before = prevWant[piece] ?: continue
                    val body = bodies[id] ?: continue
                    if (id in newcomers || piece == focus || piece == prevFocus) continue
                    val isPile = io.embodit.spatialoffice.core.pptx.Chapters.indexOf(chapters, piece) != chapter
                    if (was(piece) == isPile) { if (same(before, to).not()) body.delay = 0.1f; continue } // a pile that moves up: after the row has started
                    // slide ↔ pile: out with the row, then in at its new place
                    val out = (if (row != null) shifted(before, row) else before.copyOf()).also { it[Motion.ALPHA] = -1f }
                    if (was(piece)) { out[Motion.SCALE] = focusPose[Motion.SCALE]; out[Motion.ALPHA] = -0.3f }
                    relays[id] = back(out, st, 0.015f); relayUntil[id] = now + (if (was(piece)) MORPH_MS else GONE_MS); frozen[id] = was(piece); body.delay = 0f
                }
                for ((id, piece) in newcomers) {
                    val w = wanted[id] ?: continue
                    val mine = piece in chapters[chapter]
                    if (piece == focus && pileOfNew != null) {
                        // the slide in front comes out of its chapter's pile, with the pile
                        starts[id] = back(pileOfNew.copyOf(), st, -0.012f).also { it[Motion.ALPHA] = 0f }; delays[id] = 0f
                    } else {
                        starts[id] = w.copyOf().also { it[Motion.SCALE] *= if (mine) 0.92f else 0.85f; it[Motion.ALPHA] = 0f }
                        delays[id] = if (mine) minOf(0.2f + (kotlin.math.abs(piece - focus) - 1).coerceAtLeast(0) * 0.04f, 0.36f) else 0.15f
                    }
                    bodies.remove(id)
                }
            } else if (!entering) {
                // Another piece in front. What moves is the piece in front and every piece that makes the same move
                // (the row); a piece of a ring further away slides along its own ring. A piece that would have to
                // cross the others to get to its new place (up into the top row, into the side column, to the far
                // ring) does not travel: it goes out where it is and comes in where it belongs ([turns]).
                val movers = HashSet<Int>(); val sliders = HashSet<Int>(); val turns = ArrayList<Int>()
                val fFrom = prevWant[focus]; val fTo = nowWant[focus]
                if (stepping) for ((q, to) in nowWant) {
                    val from = prevWant[q] ?: continue
                    val still = between(from, to) == null && kotlin.math.abs(from[Motion.SCALE] - to[Motion.SCALE]) < 0.01f * to[Motion.SCALE]
                    if (still) continue
                    val slide = kotlin.math.abs(from[Motion.Y] - to[Motion.Y]) < 0.012f && kotlin.math.abs(from[Motion.SCALE] - to[Motion.SCALE]) < 0.01f * to[Motion.SCALE]
                    val inRow = q == focus || (fFrom != null && fTo != null && withFocus(from, to, fFrom, fTo, st))
                    val otherRing = slide && fTo != null && kotlin.math.abs(polar(to, st)[2] - polar(fTo, st)[2]) > 0.3f
                    if (inRow || otherRing) { movers += q; if (slide) sliders += q } else turns += q
                }
                if (stepping) endRelays()
                for ((id, piece) in leavers) {
                    val body = bodies[id] ?: continue
                    val place = prevWant[piece] ?: body.pos
                    body.delay = 0f
                    // the piece that takes its place pushes it on: it goes the same way, at the same speed, and is gone before it gets anywhere
                    val by = cameBy(place, nowWant, sliders)
                    val pileThere = chapter >= 0 && chapters.getOrNull(chapter + if (piece < focus) -1 else 1) != null
                    when {
                        // a chapter's pile stands next to it: it shrinks away into the gap between the pile and its neighbour
                        by != null && pileThere -> leave(id, shifted(place, by, Motion.SQUEEZE).also { it[Motion.SCALE] *= Motion.SQUEEZE_SCALE; it[Motion.ALPHA] = -0.3f }, now, CARRY_MS, Motion.GLIDE)
                        by != null -> leave(id, shifted(place, by).also { it[Motion.ALPHA] = -0.5f }, now, CARRY_MS, Motion.GLIDE)
                        // nothing takes its place (another page of the grid, another arrangement): it fades where it is, a little behind
                        else -> leave(id, back(body.pos.copyOf(), st, 0.03f).also { it[Motion.SCALE] *= 0.9f; it[Motion.ALPHA] = -1f }, now, GONE_MS)
                    }
                }
                for ((id, piece) in newcomers) {
                    val w = wanted[id] ?: continue
                    // the piece that stood here has moved on: the new one comes after it, the same way at the same speed
                    val by = wentBy(w, nowWant, sliders)
                    val pileThere = chapter >= 0 && chapters.getOrNull(chapter + if (piece < focus) -1 else 1) != null
                    starts[id] = when {
                        by != null && pileThere -> shifted(w, by, -Motion.SQUEEZE).also { it[Motion.SCALE] *= Motion.SQUEEZE_SCALE; it[Motion.ALPHA] = 0f }
                        by != null -> shifted(w, by, -1f).also { it[Motion.ALPHA] = 0f }
                        else -> w.copyOf().also { it[Motion.SCALE] *= 0.9f; it[Motion.ALPHA] = 0f }
                    }
                    // in the place of a piece that fades out: after it has gone
                    delays[id] = if (by == null && (leavers.isNotEmpty() || turns.isNotEmpty())) (if (turns.isNotEmpty()) TURN_IN else 0.13f) else 0f
                    bodies.remove(id)
                }
                for (q in turns) {
                    val id = idOf(q) ?: continue
                    val body = bodies[id] ?: continue
                    val place = prevWant[q] ?: continue; val w = nowWant[q] ?: continue
                    // out like a piece that leaves, in like a piece that comes
                    val outBy = cameBy(place, nowWant, sliders)
                    relays[id] = (outBy?.let { shifted(place, it) } ?: back(place.copyOf(), st, 0.015f).also { it[Motion.SCALE] *= 0.94f }).also { it[Motion.ALPHA] = -1f }
                    relayUntil[id] = now + GONE_MS; frozen[id] = false; body.delay = 0f
                    val inBy = wentBy(w, nowWant, sliders)
                    relayStart[id] = (inBy?.let { shifted(w, it, -1f) } ?: w.copyOf().also { it[Motion.SCALE] *= 0.9f }).also { it[Motion.ALPHA] = 0f }
                    relayDelay[id] = if (inBy != null) 0.02f else TURN_IN - GONE_MS / 1000f
                }
                // the pieces that go out where they are get a head start on the row that moves into their places
                if (turns.isNotEmpty()) for (q in movers) idOf(q)?.let { bodies[it] }?.let { if (it.delay < ROW_WAITS) it.delay = ROW_WAITS }
            }
            // One feel for every piece: the row moves as one. (A quicker piece in front ran into the one before it and lay
            // over it for a third of a second: user on the headset, 2026-11-24.)
            for (id in wanted.keys) feels[id] = Motion.GLIDE
            prevWant.clear(); prevWant.putAll(nowWant)
            prevArrangement = graph.arrangement
            prevChapter = chapter
        } else if (st != null && mode == StageMode.REHEARSAL) {
            if (prevMode != StageMode.REHEARSAL) {
                val screen = wanted[StagePanels.SCREEN]
                // the slide in front (coming from the spread) grows into the screen; otherwise it rises from a small one in front of the seat
                val front = if (prevMode == StageMode.SPREAD) lastFocusPose else null
                if (screen != null) {
                    starts[StagePanels.SCREEN] = front?.copyOf() ?: stackAt(st, screen).also { it[Motion.SCALE] = screen[Motion.SCALE] * 0.12f }
                    bodies.remove(StagePanels.SCREEN)
                }
                for (id in listOf(StagePanels.NOTES, StagePanels.NEXT)) {
                    val w = wanted[id] ?: continue
                    // from further out on the same side, small
                    starts[id] = w.copyOf().also {
                        it[Motion.X] = st.x + (w[Motion.X] - st.x) * 1.5f; it[Motion.Z] = st.z + (w[Motion.Z] - st.z) * 1.5f; it[Motion.SCALE] *= 0.5f; it[Motion.ALPHA] = 0f
                    }
                    delays[id] = 0.18f
                    bodies.remove(id)
                }
                wanted[StagePanels.BAR]?.let { w ->
                    starts[StagePanels.BAR] = w.copyOf().also { it[Motion.Y] -= 0.16f; it[Motion.SCALE] *= 0.6f; it[Motion.ALPHA] = 0f }
                    delays[StagePanels.BAR] = 0.28f
                    bodies.remove(StagePanels.BAR)
                }
                began("rehearsal-open from=${if (front != null) "the slide in front" else "the seat"}", now)
                hold(now)
            }
            feels[StagePanels.SCREEN] = Motion.GRAND
            for (id in listOf(StagePanels.NOTES, StagePanels.NEXT, StagePanels.BAR)) feels[id] = Motion.GLIDE
            // the slide changed: the screen itself stays still (its content fades over, StageMotion.slideChange). A screen
            // that gave and came back, and slides that moved sideways, read as a jolt on the headset (user, 2026-11-24).
            val slide = (graph.stageSession as? DeckSession)?.current ?: -1
            if (prevMode == StageMode.REHEARSAL && prevSlide >= 0 && slide != prevSlide) {
                began("slide ${prevSlide + 1} -> ${slide + 1} (${io.embodit.spatialoffice.ui.space.StageMotion.kind(prevSlide, slide)})", now)
            }
            prevSlide = slide
        } else if (mode == null && prevMode != null && st != null) {
            // [Collapse] / the rehearsal ending: gather, then the Stage closes (WindowCtl.leaveStage waits 350 ms)
            val focusPose = lastFocusPose
            val shownNow = bodies.keys.filter { shown[it] == true }
            val pieces = shownNow.filter { it.startsWith("piece-") }.sortedBy { pieceOf(it) }
            val focusAt = pieces.indexOfFirst { pieceOf(it) == focus }.coerceAtLeast(0)
            for (id in shownNow) {
                val body = bodies[id] ?: continue
                val to = when {
                    id.startsWith("piece-") && focusPose != null -> stackAt(st, focusPose).also { q -> q[Motion.ALPHA] = 0f }
                    id == StagePanels.SCREEN -> body.pos.copyOf().also { q ->
                        q[Motion.X] = st.x + (q[Motion.X] - st.x) * 0.35f; q[Motion.Z] = st.z + (q[Motion.Z] - st.z) * 0.35f
                        q[Motion.Y] -= 0.45f; q[Motion.SCALE] *= 0.1f; q[Motion.ALPHA] = 0f
                    }
                    else -> body.pos.copyOf().also { q -> q[Motion.Y] -= 0.14f; q[Motion.SCALE] *= 0.4f; q[Motion.ALPHA] = 0f }
                }
                exits[id] = to
                body.delay = if (id.startsWith("piece-")) Motion.gatherDelay(pieces.indexOf(id), focusAt, pieces.size) else 0f
            }
            for (id in shownNow) { feels[id] = Motion.GATHER; exitUntil[id] = now + EXIT_MS }
            holding = false
            if (shownNow.isNotEmpty()) began("gather panels=${shownNow.size} from=$prevMode", now)
        }
        // back in a layout: nothing but pieces that left it is still on its way out (the rehearsal has no pieces at all)
        if (mode != null) {
            val keep = if (mode == StageMode.SPREAD) exits.keys.filter { it.startsWith("piece-") && wanted[it] == null }.toSet() else emptySet()
            exits.keys.retainAll(keep); exitUntil.keys.retainAll(keep)
        }
        if (mode != StageMode.SPREAD) { prevChapter = -1; prevWant.clear(); endRelays() }
        if (seat != null && seenRecenterForMotion != graph.recenterTick) {
            if (seenRecenterForMotion >= 0 && mode != null) began("recenter", now)
            seenRecenterForMotion = graph.recenterTick
        }
        // without a seat nothing was laid out yet: the change is still to be played once there is one
        if (st != null) { prevMode = mode; prevFocus = focus }
    }

    /** The stack the pieces are dealt from and gathered to: at the piece in front, a little lower and nearer the seat, small. */
    private fun stackAt(st: Seat, focusPose: FloatArray): FloatArray {
        val dx = st.x - focusPose[Motion.X]; val dz = st.z - focusPose[Motion.Z]
        val len = kotlin.math.sqrt(dx * dx + dz * dz).coerceAtLeast(1e-4f)
        return Motion.stack(focusPose, dx / len, dz / len)
    }

    /** Seat-local pose → Stage-space target (x, y, z, yaw, scale, tilt). */
    private fun target(seat: Seat, p: PanelPose, scale: Float, tiltDeg: Float = 0f): FloatArray {
        val (x, y, z) = seat.toStage(p.x, p.y, p.z)
        // alpha 1: a panel in its place is opaque (PanelPose.alpha is not used: translucent pages over a real room read badly)
        return floatArrayOf(x, y, z, seat.yawRad + (p.yawDeg * PI / 180.0).toFloat(), scale, (tiltDeg * PI / 180.0).toFloat(), 1f)
    }

    private fun apply(e: Entity, v: FloatArray) {
        val t = e.components[TransformComponent::class.java] ?: return
        t.setPosition(Vector3(v[0], v[1], v[2]))
        // yaw about the vertical axis, then a tilt about the panel's own horizontal axis (bars lean back towards the eyes)
        val hy = v[3] / 2f; val hx = v[5] / 2f
        val cy = cos(hy); val sy = sin(hy); val cx = cos(hx); val sx = sin(hx)
        t.setQuaternion(Quat(cy * sx, sy * cx, -sy * sx, cy * cx))
        t.setScaleVector(Vector3(v[4], v[4], v[4]))
        fade(e, v[Motion.ALPHA])
    }

    /**
     * Alpha of a holder (and the panel under it): only while a panel comes or goes. At rest the component is taken off
     * again: an entity that carries it is drawn blended even at 1.0 (factory lesson, 2026-10-29).
     */
    private fun fade(e: Entity, alpha: Float) {
        val a = alpha.coerceIn(0f, 1f)
        runCatching {
            val c = e.components[OpacityControllerComponent::class.java]
            if (a >= 0.99f) { if (c != null) e.components.remove(OpacityControllerComponent::class.java) }
            else if (c == null) e.components.set(OpacityControllerComponent(a)) else c.opacity = a
        }.onFailure { if (!fadeFailed) { fadeFailed = true; Log.w(TAG, "MOTION opacity not applied: $it") } }
    }

    private var fadeFailed = false

    private fun park(h: Entity) {
        val t = h.components[TransformComponent::class.java] ?: return
        t.setPosition(Vector3(0f, -40f, 0f))
        t.setScaleVector(Vector3(0.001f, 0.001f, 0.001f))
    }

    fun seatForDebug(): Seat? = seat

    private companion object {
        /** Longest a gathering panel is still drawn: the span WindowCtl.leaveStage gives the gathering before the home window comes back. */
        const val EXIT_MS = 300L
        /** Longest the slides of a chapter take to close into its pile, and a piece takes to fade out of the layout. */
        const val CHAPTER_MS = 340L
        const val FADE_MS = 240L
        /** A piece that goes out with the row is no longer seen after about 135 ms (its alpha heads for −1); its panel is free after this long. */
        const val GONE_MS = 170L
        /** The same for a pile that turns into the slide coming out of it (alpha heads for −0.3: gone after about 220 ms). */
        const val MORPH_MS = 240L
        /** Seconds after a step at which a piece that changed its part comes in at its new place: the row has passed by then. */
        const val TURN_IN = 0.34f
        /** Seconds the row waits when pieces go out where they stand: they are faint before the row reaches their places. */
        const val ROW_WAITS = 0.08f
        /** Longest a piece that is pushed out of the row at its end is still drawn. */
        const val CARRY_MS = 260L
    }
}
