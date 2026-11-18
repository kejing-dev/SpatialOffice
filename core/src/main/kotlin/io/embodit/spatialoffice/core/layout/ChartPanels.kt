package io.embodit.spatialoffice.core.layout

import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The two panels beside the 3D chart (title / legend on the left, value + controls on the right), v0.2.3.
 *
 * QA #3 q3-1: in v0.2.2 the panels were attachment panels at the volume's own dp scale (about 0.83 mm per dp, half of
 * a window's), so at about 2 m their small text was 0.24°–0.30° high and the round buttons about 20 dp of a window.
 * Now the panels have a fixed physical size, set from the viewing distance and not from the chart's size:
 * [M_PER_DP] metres per dp (a little more than a Shared Space window at the volume's distance), text at least
 * [MIN_SP] sp, buttons [BUTTON_DP] dp. The app scales each attachment entity by `M_PER_DP / (the volume's own m per dp)`.
 *
 * Geometry: the volume above the sheet window as [WindowFit.chartAboveSheet] places it; the panels, [MAX_H_DP] high,
 * stand on its bottom edge at its left and right ends, [PANEL_Z] m in front of its centre, tipped [TILT_DEG]° so they face the eye below.
 */
object ChartPanels {
    /** Metres per dp of the panels. A window at 1.75 m has 0.00158 m/dp; the volume is about 2.0 m away (0.00181). */
    const val M_PER_DP = 0.0019
    /** Smallest text on the panels (sp = dp here, font scale 1). */
    const val MIN_SP = 15.0
    /** Round buttons on the panels (dp). */
    const val BUTTON_DP = 50.0
    /** Panel widths and the most height either may take (dp). */
    const val LEFT_W_DP = 300.0
    const val RIGHT_W_DP = 250.0
    const val MAX_H_DP = 130.0
    /**
     * Panel bottoms sit this far above the volume's bottom edge (m). The volume clips its attachments (emulator,
     * v0.2.3 trials: a panel hung 6 cm below the volume was cut at the volume's edge), and a taller volume pushed the
     * panels' top past the top of the view, so the volume stays 0.26 m high and the panels fill it.
     */
    const val LIFT = 0.004
    /** Space between the volume's end and a panel, and between a panel and the chart (m). */
    const val MARGIN = 0.02
    const val GAP = 0.03
    /** Panels stand this far in front of the volume centre (m) and are tipped this much towards the eye below. */
    const val PANEL_Z = 0.06
    const val TILT_DEG = 25.0
    /** Glyph height over font size: Latin capitals (the smaller one; CJK glyphs are about 0.88). */
    const val CAP_RATIO = 0.70
    const val CJK_RATIO = 0.88
    /** D13: smallest glyph angle; B19: a 48 dp button of a window at its 1.75 m, in degrees. */
    const val MIN_GLYPH_DEG = 0.5
    val WINDOW_48DP_DEG: Double = angleOfSegment(Triple(0.0, -24 * WindowFit.M_PER_DP, -WindowFit.DISTANCE), Triple(0.0, 24 * WindowFit.M_PER_DP, -WindowFit.DISTANCE))

    data class V(val x: Double, val y: Double, val z: Double) {
        operator fun plus(o: V) = V(x + o.x, y + o.y, z + o.z)
        operator fun times(k: Double) = V(x * k, y * k, z * k)
        fun t() = Triple(x, y, z)
    }

    /** A panel in eye space: centre, its up and right unit vectors, size in metres. */
    data class Panel(val centre: V, val up: V, val right: V, val w: Double, val h: Double) {
        fun corners(): List<Triple<Double, Double, Double>> = listOf(-0.5, 0.5).flatMap { sx -> listOf(-0.5, 0.5).map { sy -> (centre + right * (sx * w) + up * (sy * h)).t() } }
        val distance: Double get() = sqrt(centre.x * centre.x + centre.y * centre.y + centre.z * centre.z)
    }

    /** Local x of a panel centre in a volume [volumeW] m wide (left = negative). */
    fun panelX(left: Boolean, volumeW: Double = WindowFit.CHART_W): Double {
        val half = volumeW / 2
        return if (left) -(half - MARGIN - LEFT_W_DP * M_PER_DP / 2) else half - MARGIN - RIGHT_W_DP * M_PER_DP / 2
    }

    /** Local y of a panel centre ([MAX_H_DP] high) in a volume [volumeH] m high. */
    fun panelY(volumeH: Double = WindowFit.CHART_H): Double = -volumeH / 2 + LIFT + MAX_H_DP * M_PER_DP * cos(Math.toRadians(TILT_DEG)) / 2

    /** Width left for the chart between the panels, and the chart's centre x (local, m). */
    fun chartSpan(volumeW: Double = WindowFit.CHART_W): Pair<Double, Double> {
        val l = -volumeW / 2 + MARGIN + LEFT_W_DP * M_PER_DP + GAP
        val r = volumeW / 2 - MARGIN - RIGHT_W_DP * M_PER_DP - GAP
        return (r - l) to (l + r) / 2
    }

    /** The panel in eye space, [hDp] high, for the volume [vol] (the box WindowFit places above the sheet). */
    fun panel(left: Boolean, hDp: Double = MAX_H_DP, vol: WindowFit.Box = WindowFit.chartAboveSheet()): Panel {
        val t = Math.toRadians(TILT_DEG)
        // tipped about x by +t: up (0,1,0) -> (0, cos t, sin t), i.e. the top leans towards the user (+z)
        val up = V(0.0, cos(t), sin(t))
        val c = V(vol.cx + panelX(left, vol.w), vol.cy + panelY(vol.h), vol.cz + PANEL_Z)
        return Panel(c, up, V(1.0, 0.0, 0.0), (if (left) LEFT_W_DP else RIGHT_W_DP) * M_PER_DP, hDp * M_PER_DP)
    }

    /** Angle (degrees) a segment subtends at the eye (origin). */
    fun angleOfSegment(a: Triple<Double, Double, Double>, b: Triple<Double, Double, Double>): Double {
        val la = sqrt(a.first * a.first + a.second * a.second + a.third * a.third)
        val lb = sqrt(b.first * b.first + b.second * b.second + b.third * b.third)
        val dot = (a.first * b.first + a.second * b.second + a.third * b.third) / (la * lb)
        return Math.toDegrees(acos(dot.coerceIn(-1.0, 1.0)))
    }

    /** Angle of a vertical (panel-up) extent of [dp] centred on the panel centre. */
    fun verticalDeg(p: Panel, dp: Double, mPerDp: Double = M_PER_DP): Double {
        val h = dp * mPerDp / 2
        return angleOfSegment((p.centre + p.up * -h).t(), (p.centre + p.up * h).t())
    }

    /** Glyph height angle of [sp] text on [p] for a glyph/font ratio. */
    fun glyphDeg(p: Panel, sp: Double = MIN_SP, ratio: Double = CAP_RATIO, mPerDp: Double = M_PER_DP): Double = verticalDeg(p, sp * ratio, mPerDp)

    /** Button diameter angle on [p]. */
    fun buttonDeg(p: Panel, dp: Double = BUTTON_DP, mPerDp: Double = M_PER_DP): Double = verticalDeg(p, dp, mPerDp)

    /** The button as dp of a window at 1.75 m (what QA compares with B19's 48 dp). */
    fun buttonWindowDp(p: Panel, dp: Double = BUTTON_DP, mPerDp: Double = M_PER_DP): Double = buttonDeg(p, dp, mPerDp) / WINDOW_48DP_DEG * 48.0

    /** v0.2.2's panels for comparison: volume scale 0.00083 m/dp (measured on QA #3 screenshot 70), 12 sp text. */
    const val OLD_M_PER_DP = 0.00083
}
