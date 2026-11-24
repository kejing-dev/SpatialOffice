package io.embodit.spatialoffice.core.layout

import io.embodit.spatialoffice.core.files.FileKind
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * How the pieces of a file are set out around the seat.
 * ROW, ARC, LAYERS: every piece (as far as the view goes, [LayoutRules.MAX_AZ]).
 * CHAPTERS (decks, the storyline): the chapter in front is laid out on the arc, every other chapter is one pile at its side.
 * The front templates show a handful of pieces, all inside what the eyes take in without turning the body; the
 * others come in as the user steps through the file:
 * TRIPLE = left · centre · right; GRID = four in a 2 × 2 block; CROWN = three across and two above, leaning down
 * towards the eyes; MAIN = the piece in front large, two small ones stacked at each side.
 */
enum class Arrangement {
    ROW, ARC, LAYERS, CHAPTERS, TRIPLE, GRID, CROWN, MAIN;

    /** A front template: a fixed number of places, the same whatever the file's length. */
    val front: Boolean get() = this == TRIPLE || this == GRID || this == CROWN || this == MAIN
}

/**
 * Where one panel sits, in seat coordinates: origin = head position when the layout was entered (yaw only),
 * +x right, +y up, −z forward, metres. [yawDeg] turns the panel about the vertical axis (0 = facing +z, i.e. the seat
 * when the panel is straight ahead). [width] / [height] are the panel's size before [scale].
 */
data class PanelPose(
    val x: Float, val y: Float, val z: Float, val yawDeg: Float,
    val width: Float, val height: Float, val scale: Float = 1f, val alpha: Float = 1f, val near: Boolean = true,
    /** Lean about the panel's own horizontal axis, degrees: above 0 the face turns down (a panel over the eyes), below 0 it turns up. */
    val tiltDeg: Float = 0f,
) {
    /** Direction of the panel's centre seen from the seat, degrees: 0 = straight ahead, above 0 = to the right. */
    val azimuthDeg: Float get() = (atan2(x.toDouble(), -z.toDouble()) * 180.0 / PI).toFloat()
    /** Lowest point of the panel (metres above the eyes; the lean is small enough to leave out). */
    val bottom: Float get() = y - height * scale / 2f
}

/** Size of one panel per file kind (metres). Documents: one section; sheets: a block of columns; slides: one slide. */
data class PanelSize(val width: Float, val height: Float)

/**
 * Spatial layout rules (PRD §6): a file's pieces are laid out around the seat. Pure math, unit-tested; the
 * Stage only applies the poses. The focused piece is always straight ahead (azimuth 0) so typing never needs a
 * turned head.
 */
object LayoutRules {
    val RADII = floatArrayOf(1.2f, 1.5f, 1.8f)

    fun panelSize(kind: FileKind): PanelSize = when (kind.editor) {
        FileKind.SHEET -> PanelSize(0.58f, 0.78f)
        FileKind.SLIDES -> PanelSize(0.56f, 0.315f)
        else -> PanelSize(0.60f, 0.80f)
    }

    /** Distance of the near ring for [kind] given the user's setting (1.2 / 1.5 / 1.8 m). */
    fun radius(kind: FileKind, setting: Float): Float = when (kind.editor) {
        FileKind.SHEET -> setting - 0.1f
        FileKind.SLIDES -> setting + 0.3f
        else -> setting
    }

    /**
     * Poses for [count] pieces with piece [focus] straight ahead.
     * ROW = one straight line (sheet blocks touch, so the sheet reads as one flat table);
     * ARC = on a circle around the seat, every piece facing it (the "curved" layout: flat segments on an arc);
     * LAYERS = the focused piece and its neighbours near, everything else pushed back, raised and enlarged.
     */
    fun poses(kind: FileKind, count: Int, arrangement: Arrangement, focus: Int, radiusSetting: Float): List<PanelPose> {
        if (count <= 0) return emptyList()
        val f = focus.coerceIn(0, count - 1)
        val size = panelSize(kind)
        val r = radius(kind, radiusSetting)
        val sheet = kind.editor == FileKind.SHEET
        val gap = if (sheet) 0f else if (kind.editor == FileKind.SLIDES) 0.04f else 0.04f
        return when (arrangement) {
            // without chapter data the storyline is the plain arc ([chapterPoses] is the real thing)
            Arrangement.CHAPTERS -> poses(kind, count, Arrangement.ARC, focus, radiusSetting)
            // every piece of a front template that is not shown stands where the arc would have it (callers use [frontPoses])
            Arrangement.TRIPLE, Arrangement.GRID, Arrangement.CROWN, Arrangement.MAIN -> poses(kind, count, Arrangement.ARC, focus, radiusSetting)
            Arrangement.ROW -> List(count) { i -> PanelPose((i - f) * (size.width + gap), 0f, -r, 0f, size.width, size.height) }
            Arrangement.ARC -> {
                val step = stepDeg(size.width, gap, r)
                List(count) { i -> onRing((i - f) * step, r, 0f, size, 1f, 1f, true) }
            }
            Arrangement.LAYERS -> {
                val nearR = r - 0.2f
                val farR = nearR + 1.0f
                val farScale = if (sheet) 1.5f else if (kind.editor == FileKind.SLIDES) 1.35f else 1.45f
                val farUp = if (sheet) 0.86f else if (kind.editor == FileKind.SLIDES) 0.62f else 0.78f
                val nearStep = stepDeg(size.width, gap, nearR)
                val farStep = stepDeg(size.width * farScale, gap * farScale, farR)
                val nearSpan = 1 // neighbours on each side that stay near
                val far = (0 until count).filter { kotlin.math.abs(it - f) > nearSpan }
                val farMid = (far.size - 1) / 2f
                List(count) { i ->
                    if (kotlin.math.abs(i - f) <= nearSpan) onRing((i - f) * nearStep, nearR, 0f, size, 1f, 1f, true)
                    else onRing((far.indexOf(i) - farMid) * farStep, farR, farUp, size, farScale, 0.6f, false)
                }
            }
        }
    }

    /** Scale of a chapter's pile, and how many slides of the chapter in front are laid out at once (around the focused one). */
    const val PILE_SCALE = 0.55f
    const val CHAPTER_WINDOW = 7
    const val PILES_PER_SIDE = 2

    /**
     * The storyline by chapters. [chapters] = (first slide, slide count) of each chapter in order; [focus] = the slide in
     * front. Returns the pose of every piece that is shown, by slide index:
     * - the slides of the focused slide's chapter on the arc, the focused one straight ahead (at most [CHAPTER_WINDOW] of them);
     * - for each of the nearest [PILES_PER_SIDE] chapters before and after: its first slide as a pile, smaller, further round
     *   the same circle on its side (earlier chapters to the left, later ones to the right), a little lower.
     */
    fun chapterPoses(kind: FileKind, chapters: List<Pair<Int, Int>>, focus: Int, radiusSetting: Float): Map<Int, PanelPose> {
        val out = LinkedHashMap<Int, PanelPose>()
        if (chapters.isEmpty()) return out
        val size = panelSize(kind)
        val r = radius(kind, radiusSetting)
        val step = stepDeg(size.width, 0.04f, r)
        val cur = chapters.indexOfFirst { focus >= it.first && focus < it.first + it.second }.coerceAtLeast(0)
        val (first, n) = chapters[cur]
        val f = focus.coerceIn(first, first + n - 1)
        val pileStep = stepDeg(size.width * PILE_SCALE, 0.07f, r)
        val gapDeg = step / 2f + pileStep / 2f + 2f
        val pilesBefore = minOf(PILES_PER_SIDE, cur); val pilesAfter = minOf(PILES_PER_SIDE, chapters.size - 1 - cur)
        // each side holds as many slides as leave its piles inside the view (never fewer than one, never more than half the window)
        fun room(piles: Int): Int {
            val forPiles = if (piles == 0) 0f else gapDeg + (piles - 1) * pileStep
            return ((MAX_AZ - forPiles) / step).toInt().coerceIn(1, CHAPTER_WINDOW / 2)
        }
        val from = maxOf(first, f - room(pilesBefore))
        val to = minOf(first + n - 1, f + room(pilesAfter))
        for (i in from..to) out[i] = onRing((i - f) * step, r, 0f, size, 1f, 1f, true)
        for (k in 1..PILES_PER_SIDE) {
            chapters.getOrNull(cur - k)?.let { c -> out[c.first] = onRing((from - f) * step - gapDeg - (k - 1) * pileStep, r, -0.06f, size, PILE_SCALE, 1f, false) }
            chapters.getOrNull(cur + k)?.let { c -> out[c.first] = onRing((to - f) * step + gapDeg + (k - 1) * pileStep, r, -0.06f, size, PILE_SCALE, 1f, false) }
        }
        return out
    }

    /**
     * Furthest to the side a panel's centre may be, degrees. Nobody turns round in a chair to read what is behind:
     * the whole layout stays inside the half circle in front (a panel at 78° still ends before 90°).
     */
    const val MAX_AZ = 78f

    /** True when [p] is inside the view the layouts keep to ([MAX_AZ]). */
    fun inView(p: PanelPose): Boolean = abs(p.azimuthDeg) <= MAX_AZ + 0.01f

    /**
     * Half of the view a front template may fill from side to side, degrees: what the eyes take in with the head
     * still (profile D10: ±39°; the emulator's view ends at ±40°, where a wider row of three was cut off).
     */
    const val FRONT_HALF = 38f

    /** Scale at which [n] panels of [width] side by side on the circle of radius [r] fill the view from −[FRONT_HALF] to +[FRONT_HALF]. */
    private fun fitAcross(n: Int, width: Float, gap: Float, r: Float): Float {
        val step = 2.0 * FRONT_HALF / n * PI / 180.0
        return ((2.0 * r * kotlin.math.tan(step / 2.0) - gap) / width).toFloat()
    }

    /**
     * Scale of the pieces of a front template: (main pieces, small pieces). Slides are small panels and gain most.
     * The row of three is as large as the view allows at this distance, so it is the same angle wide at 1.2 m and 1.8 m.
     */
    fun frontScale(kind: FileKind, arrangement: Arrangement, radiusSetting: Float): Pair<Float, Float> {
        val slides = kind.editor == FileKind.SLIDES
        val triple = minOf(if (slides) 1.5f else 1.1f, fitAcross(3, panelSize(kind).width, 0.05f, radius(kind, radiusSetting)))
        return when (arrangement) {
            Arrangement.TRIPLE -> triple to triple
            Arrangement.GRID -> (if (slides) 1.5f else 0.8f).let { it to it }
            // upright pages are tall: with two more above them the row of three is smaller, or the top ones are overhead
            Arrangement.CROWN -> if (slides) triple * 0.95f to triple * 0.76f else triple * 0.82f to triple * 0.62f
            Arrangement.MAIN -> if (slides) 1.7f to 0.8f else 1.2f to 0.6f
            else -> 1f to 1f
        }
    }

    /**
     * The front templates: the few pieces that are shown, by piece index, for piece [focus] of [count].
     * - TRIPLE: focus straight ahead, the piece before it at its left, the one after it at its right, all facing the seat.
     * - GRID: the four pieces of the focused piece's group (0–3, 4–7, …) in a 2 × 2 block: reading order, upper row leaning down, lower row up.
     * - CROWN: TRIPLE a little lower, and above it the pieces two before (left) and two after (right), smaller, leaning down to the eyes.
     * - MAIN: focus large straight ahead; small at its left the two pieces before it, at its right the two after it (stacked, the nearer one inside).
     * Stepping the focus moves every piece one place on: a conveyor, never a new layout.
     */
    fun frontPoses(kind: FileKind, count: Int, arrangement: Arrangement, focus: Int, radiusSetting: Float): Map<Int, PanelPose> {
        val out = LinkedHashMap<Int, PanelPose>()
        if (count <= 0) return out
        val f = focus.coerceIn(0, count - 1)
        val size = panelSize(kind)
        val r = radius(kind, radiusSetting)
        val (s, small) = frontScale(kind, arrangement, radiusSetting)
        fun put(i: Int, az: Float, y: Float, scale: Float) {
            if (i !in 0 until count) return
            // a panel above or below the eyes leans to face them
            val lean = (atan2(y.toDouble(), r.toDouble()) * 180.0 / PI).toFloat()
            out[i] = onRing(az, r, y, size, scale, 1f, true).copy(tiltDeg = lean)
        }
        when (arrangement) {
            Arrangement.TRIPLE -> {
                val step = stepDeg(size.width * s, 0.05f, r)
                for (k in -1..1) put(f + k, k * step, 0f, s)
            }
            Arrangement.GRID -> {
                val step = stepDeg(size.width * s, 0.04f, r)
                val row = size.height * s / 2f + 0.02f
                val first = f / 4 * 4
                for (k in 0..3) put(first + k, (k % 2 - 0.5f) * step, if (k < 2) row else -row, s)
            }
            Arrangement.CROWN -> {
                val step = stepDeg(size.width * s, 0.05f, r)
                val low = if (kind.editor == FileKind.SLIDES) -CROWN_DROP else -CROWN_DROP_UPRIGHT
                for (k in -1..1) put(f + k, k * step, low, s)
                val high = low + size.height * s / 2f + 0.05f + size.height * small / 2f
                put(f - 2, -step / 2f, high, small)
                put(f + 2, step / 2f, high, small)
            }
            Arrangement.MAIN -> {
                put(f, 0f, 0f, s)
                val side = stepDeg(size.width * s, 0f, r) / 2f + stepDeg(size.width * small, 0f, r) / 2f + 2.5f
                val row = size.height * small / 2f + 0.02f
                put(f - 1, -side, -row, small); put(f - 2, -side, row, small)
                put(f + 1, side, row, small); put(f + 2, side, -row, small)
            }
            else -> {}
        }
        return out
    }

    /** CROWN: how far the row of three sits below the eyes, so the two above it are not overhead. */
    const val CROWN_DROP = 0.12f
    const val CROWN_DROP_UPRIGHT = 0.2f

    /** Angle between neighbours so that panels of [width] sit edge to edge (plus [gap]) on a circle of radius [r]. */
    fun stepDeg(width: Float, gap: Float, r: Float): Float = (2.0 * atan(((width + gap) / 2.0) / r) * 180.0 / PI).toFloat()

    private fun onRing(azDeg: Float, r: Float, up: Float, size: PanelSize, scale: Float, alpha: Float, near: Boolean): PanelPose {
        val a = azDeg * PI / 180.0
        return PanelPose((r * sin(a)).toFloat(), up, (-r * cos(a)).toFloat(), -azDeg, size.width, size.height, scale, alpha, near)
    }

    /** Layout bar: below the focused piece, tilted to the seat, never moves with the ring. */
    fun barPose(kind: FileKind, radiusSetting: Float, bottom: Float? = null): PanelPose {
        val size = panelSize(kind)
        val r = radius(kind, radiusSetting) - 0.25f
        // [bottom] = lowest edge of the pieces in front of the seat (the templates have larger pieces and second rows)
        return PanelPose(0f, (bottom ?: -(size.height / 2f)) - 0.2f, -r, 0f, 0.62f, 0.09f)
    }

    /** Desk-height spot for a 3D chart: right front, out of the panels' way. */
    fun chartSpot(): Triple<Float, Float, Float> = Triple(0.64f, -0.48f, -0.56f)

    /** Rehearsal: big screen, notes left, timer + next slide right (PRD §6). */
    fun rehearsal(): Map<String, PanelPose> {
        fun side(az: Float, w: Float, h: Float): PanelPose {
            val a = az * PI / 180.0
            return PanelPose((1.0 * sin(a)).toFloat(), -0.3f, (-1.0 * cos(a)).toFloat(), -az, w, h)
        }
        return mapOf(
            "screen" to PanelPose(0f, 0.62f, -3.4f, 0f, 3.2f, 1.8f),
            "notes" to side(-58f, 0.5f, 0.62f),
            "next" to side(58f, 0.5f, 0.62f),
            "bar" to PanelPose(0f, -0.45f, -0.9f, 0f, 0.62f, 0.09f),
        )
    }
}
