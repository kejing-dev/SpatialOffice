package io.embodit.spatialoffice.core.layout

import io.embodit.spatialoffice.core.files.FileKind
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.sin

/** The three arrangements on the layout bar. */
/**
 * CHAPTERS (decks, the storyline): the chapter in front is laid out on the arc, every other chapter is one pile at its side.
 */
enum class Arrangement { ROW, ARC, LAYERS, CHAPTERS }

/**
 * Where one panel sits, in seat coordinates: origin = head position when the layout was entered (yaw only),
 * +x right, +y up, −z forward, metres. [yawDeg] turns the panel about the vertical axis (0 = facing +z, i.e. the seat
 * when the panel is straight ahead). [width] / [height] are the panel's size before [scale].
 */
data class PanelPose(
    val x: Float, val y: Float, val z: Float, val yawDeg: Float,
    val width: Float, val height: Float, val scale: Float = 1f, val alpha: Float = 1f, val near: Boolean = true,
)

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
        val from = if (n <= CHAPTER_WINDOW) first else (f - CHAPTER_WINDOW / 2).coerceIn(first, first + n - CHAPTER_WINDOW)
        val to = minOf(first + n, from + CHAPTER_WINDOW) - 1
        for (i in from..to) out[i] = onRing((i - f) * step, r, 0f, size, 1f, 1f, true)
        val pileStep = stepDeg(size.width * PILE_SCALE, 0.07f, r)
        val gapDeg = step / 2f + pileStep / 2f + 2f
        for (k in 1..PILES_PER_SIDE) {
            chapters.getOrNull(cur - k)?.let { c -> out[c.first] = onRing((from - f) * step - gapDeg - (k - 1) * pileStep, r, -0.06f, size, PILE_SCALE, 1f, false) }
            chapters.getOrNull(cur + k)?.let { c -> out[c.first] = onRing((to - f) * step + gapDeg + (k - 1) * pileStep, r, -0.06f, size, PILE_SCALE, 1f, false) }
        }
        return out
    }

    /** Angle between neighbours so that panels of [width] sit edge to edge (plus [gap]) on a circle of radius [r]. */
    fun stepDeg(width: Float, gap: Float, r: Float): Float = (2.0 * atan(((width + gap) / 2.0) / r) * 180.0 / PI).toFloat()

    private fun onRing(azDeg: Float, r: Float, up: Float, size: PanelSize, scale: Float, alpha: Float, near: Boolean): PanelPose {
        val a = azDeg * PI / 180.0
        return PanelPose((r * sin(a)).toFloat(), up, (-r * cos(a)).toFloat(), -azDeg, size.width, size.height, scale, alpha, near)
    }

    /** Layout bar: below the focused piece, tilted to the seat, never moves with the ring. */
    fun barPose(kind: FileKind, radiusSetting: Float): PanelPose {
        val size = panelSize(kind)
        val r = radius(kind, radiusSetting) - 0.25f
        return PanelPose(0f, -(size.height / 2f) - 0.2f, -r, 0f, 0.62f, 0.09f)
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
