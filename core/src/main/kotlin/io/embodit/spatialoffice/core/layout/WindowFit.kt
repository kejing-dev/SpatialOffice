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
}
