package io.embodit.spatialoffice.core.layout

import kotlin.math.atan2
import kotlin.math.abs

/**
 * Where an editor window lands relative to the user, and whether all of it is inside the default field of view
 * (profile D10: ±39° around the gaze; D14: nothing more than 40° below the eyes). v0.2.0 opened every editor
 * `Placement.right(home)`: with the home window centred in front, a 1360 dp slides window then spans about 22°–56°
 * to the right (QA #1 q1-1). v0.2.1 opens editors with the system's own placement in front of the user.
 *
 * Model: Shared Space windows stand upright at [DISTANCE] m, centred at eye height; 1 dp = [M_PER_DP] m (measured on
 * the emulator, filter-cam v0.1.1: 760 dp spans ±19° at 1.75 m). Each window is a box [DEPTH] m thick; all 8 corners
 * are projected.
 */
object WindowFit {
    const val DISTANCE = 1.75
    const val M_PER_DP = 0.00158
    const val DEPTH = 0.02
    const val HALF_FOV = 39.0
    const val MAX_BELOW = 40.0
    /** Gap Placement.right / left leaves between two windows (dp, measured on v0.2.0 screenshots). */
    const val GAP_DP = 48.0

    enum class Spot { FRONT, RIGHT_OF_HOME }

    data class Box(val cx: Double, val cy: Double, val cz: Double, val w: Double, val h: Double, val d: Double = DEPTH) {
        fun corners(): List<Triple<Double, Double, Double>> {
            val out = ArrayList<Triple<Double, Double, Double>>(8)
            for (sx in listOf(-0.5, 0.5)) for (sy in listOf(-0.5, 0.5)) for (sz in listOf(-0.5, 0.5))
                out.add(Triple(cx + sx * w, cy + sy * h, cz + sz * d))
            return out
        }
    }

    /** Yaw / pitch (degrees) of a point seen from the eye at the origin looking along −z. */
    fun angles(p: Triple<Double, Double, Double>): Pair<Double, Double> {
        val (x, y, z) = p
        val fwd = -z
        return Math.toDegrees(atan2(x, fwd)) to Math.toDegrees(atan2(y, fwd))
    }

    /** Box of a [wDp] × [hDp] window at [spot], given the home window's size. */
    fun box(spot: Spot, wDp: Double, hDp: Double, homeWDp: Double = 1120.0): Box {
        val w = wDp * M_PER_DP; val h = hDp * M_PER_DP
        val cx = when (spot) {
            Spot.FRONT -> 0.0
            Spot.RIGHT_OF_HOME -> (homeWDp / 2 + GAP_DP + wDp / 2) * M_PER_DP
        }
        return Box(cx, 0.0, -DISTANCE, w, h)
    }

    /** The worst corner: largest |yaw| and largest pitch below the eye; fits when every corner is inside. */
    data class Fit(val maxYaw: Double, val maxPitch: Double, val maxBelow: Double) {
        val fits: Boolean get() = maxYaw <= HALF_FOV && maxPitch <= HALF_FOV && maxBelow <= MAX_BELOW
    }

    fun fit(b: Box): Fit {
        val a = b.corners().map { angles(it) }
        return Fit(a.maxOf { abs(it.first) }, a.maxOf { abs(it.second) }, a.maxOf { -it.second }.coerceAtLeast(0.0))
    }

    /** The one placement rule for editor windows (opening, reopening, new file): in front of the user. */
    fun editorSpot(): Spot = Spot.FRONT

    // ---- v0.2.2: the compatibility sheet (q2-1) and the 3D chart volume below the sheet window (q2-2) ----

    /** Window centres sit this far below the eye (QA #2 screenshot 60: sheet centre 40 px of 2558 below the middle). */
    const val EYE_DROP = 0.055
    /** System controls under a Planar window: its Toolbar ornament and the window dot (QA #2 screenshot 60: 120 px ≈ 104 dp). */
    const val CONTROLS_BELOW_DP = 104.0
    /** The sheet window as declared in Main.kt. */
    const val SHEET_W_DP = 1280.0
    const val SHEET_H_DP = 800.0
    /** The chart volume (Main.kt): metres. */
    const val CHART_W = 1.76
    const val CHART_H = 0.26
    const val CHART_D = 0.32

    /**
     * Gap the system leaves between the sheet window's top edge and a volume placed `Placement.top(sheet, 0.dp)`.
     * Measured on the emulator (v0.2.2, build/v0.2.2/placement-trials.md): sheet top edge at 18.3° up, volume
     * bottom at 27.3° up, i.e. 0.33 m ≈ 206 dp at the window's distance. Placements tried and dropped there:
     * bottom (the volume rests on the bed / floor, 34°+ down), negative offsets (volume not shown), right / left
     * (volume about 4 m away, 55° to the side).
     */
    const val CHART_TOP_GAP_DP = 206.0

    /** A [wDp] × [hDp] window in front, its centre [EYE_DROP] below the eye. */
    fun frontWindow(wDp: Double, hDp: Double): Box = Box(0.0, -EYE_DROP, -DISTANCE, wDp * M_PER_DP, hDp * M_PER_DP)

    /** The chart volume above the sheet (Placement.top(sheet, 0.dp)): bottom [gapDp] over the sheet's top edge, centred. */
    fun chartAboveSheet(gapDp: Double = CHART_TOP_GAP_DP, w: Double = CHART_W, h: Double = CHART_H, d: Double = CHART_D): Box {
        val sheet = frontWindow(SHEET_W_DP, SHEET_H_DP)
        val bottom = sheet.cy + sheet.h / 2 + gapDp * M_PER_DP
        return Box(0.0, bottom + h / 2, -DISTANCE, w, h, d)
    }

    /** v0.2.1's try: the 0.60 m volume under the sheet, 120 dp gap (it went to the floor). */
    fun chartBelowSheet(gapDp: Double, w: Double, h: Double, d: Double): Box {
        val sheet = frontWindow(SHEET_W_DP, SHEET_H_DP)
        val top = sheet.cy - sheet.h / 2 - gapDp * M_PER_DP
        return Box(0.0, top - h / 2, -DISTANCE, w, h, d)
    }

    /** What QA #2 saw: the volume Placement.none() in front, on top of the sheet. */
    fun chartInFront(): Box = Box(0.0, -EYE_DROP, -DISTANCE, 0.76, 0.60, 0.48)

    /** Screen rectangle (yaw / pitch degrees) of a box's 8 projected corners. */
    data class Rect(val left: Double, val right: Double, val bottom: Double, val top: Double) {
        fun overlaps(o: Rect): Boolean = left < o.right && o.left < right && bottom < o.top && o.bottom < top
    }

    fun rect(b: Box): Rect {
        val a = b.corners().map { angles(it) }
        return Rect(a.minOf { it.first }, a.maxOf { it.first }, a.minOf { it.second }, a.maxOf { it.second })
    }

    /** The sheet window with the system controls under it (what the chart must not cover). */
    fun sheetWithControls(): Rect {
        val s = frontWindow(SHEET_W_DP, SHEET_H_DP)
        return rect(s.copy(cy = s.cy - CONTROLS_BELOW_DP * M_PER_DP / 2, h = s.h + CONTROLS_BELOW_DP * M_PER_DP))
    }

    /** The compatibility sheet: a [wDp] × [hDp] modal centred over the editor window, a little in front of it. */
    fun compatSheet(wDp: Double = 680.0, hDp: Double = 760.0): Box = Box(0.0, -EYE_DROP, -DISTANCE + 0.05, wDp * M_PER_DP, hDp * M_PER_DP)

    /** What QA #2 measured for the old Subwindow (hung off the editor's right edge): 36°–48° to the right. */
    fun oldCompatSubwindow(): Box {
        val l = Math.tan(Math.toRadians(36.0)) * DISTANCE; val r = Math.tan(Math.toRadians(48.0)) * DISTANCE
        return Box((l + r) / 2, -EYE_DROP, -DISTANCE, r - l, 600 * M_PER_DP)
    }
}

