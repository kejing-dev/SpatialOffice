package io.embodit.spatialoffice.core

import io.embodit.spatialoffice.core.layout.ChartOpenGate
import io.embodit.spatialoffice.core.layout.ChartOpenGate.Act
import io.embodit.spatialoffice.core.layout.ChartPanels
import io.embodit.spatialoffice.core.layout.WindowFit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** QA #3 q3-1: the chart panels are sized for the real viewing distance, not by the chart volume. */
class ChartPanelSizeTest {
    private val panels = listOf("left" to ChartPanels.panel(true), "right" to ChartPanels.panel(false))

    @Test fun smallestTextIsAtLeastHalfADegreeInBothLanguages() {
        for ((name, p) in panels) {
            val en = ChartPanels.glyphDeg(p, ChartPanels.MIN_SP, ChartPanels.CAP_RATIO)
            val zh = ChartPanels.glyphDeg(p, ChartPanels.MIN_SP, ChartPanels.CJK_RATIO)
            println("%s panel: distance %.2f m, %.0f sp: Latin capital %.2f°, CJK glyph %.2f°".format(name, p.distance, ChartPanels.MIN_SP, en, zh))
            assertTrue("$name en $en°", en >= ChartPanels.MIN_GLYPH_DEG)
            assertTrue("$name zh $zh°", zh >= ChartPanels.MIN_GLYPH_DEG)
        }
    }

    @Test fun buttonsAreAtLeast48dpOfAWindow() {
        for ((name, p) in panels) {
            val deg = ChartPanels.buttonDeg(p); val dp = ChartPanels.buttonWindowDp(p)
            println("%s panel: %.0f dp button %.2f° = %.1f dp of a window at 1.75 m (48 dp there = %.2f°)".format(name, ChartPanels.BUTTON_DP, deg, dp, ChartPanels.WINDOW_48DP_DEG))
            assertTrue("$name $deg° vs ${ChartPanels.WINDOW_48DP_DEG}°", deg >= ChartPanels.WINDOW_48DP_DEG)
            assertTrue("$name $dp dp", dp >= 48.0)
        }
    }

    @Test fun theOldVolumeScaledPanelsWereTooSmallThatIsWhatQaSaw() {
        val p = ChartPanels.panel(false)
        val text = ChartPanels.glyphDeg(p, 12.0, ChartPanels.CJK_RATIO, ChartPanels.OLD_M_PER_DP)
        val button = ChartPanels.buttonWindowDp(p, 48.0, ChartPanels.OLD_M_PER_DP)
        println("v0.2.2: 12 sp %.2f°, 48 dp button = %.1f dp of a window".format(text, button))
        assertTrue("QA measured 0.24°–0.30°: $text", text < 0.5)
        assertTrue("QA measured about 20 dp: $button", button < 48.0)
    }

    @Test fun panelsFitInTheVolumeAndInView() {
        val vol = WindowFit.chartAboveSheet()
        for ((name, p) in panels) {
            assertTrue("$name height ${p.h} m within volume ${vol.h}", p.h <= vol.h)
            for (c in p.corners()) {
                val (yaw, pitch) = WindowFit.angles(c)
                assertTrue("$name corner yaw $yaw", kotlin.math.abs(yaw) <= WindowFit.HALF_FOV)
                assertTrue("$name corner pitch $pitch", pitch <= WindowFit.HALF_FOV && pitch >= -WindowFit.MAX_BELOW)
                assertTrue("$name corner x ${c.first} inside volume", kotlin.math.abs(c.first - vol.cx) <= vol.w / 2 + 1e-9)
            }
        }
        // hanging below the volume, the panels still stay clear of the sheet window and its controls
        val rs = WindowFit.sheetWithControls()
        for ((name, p) in panels) {
            val a = p.corners().map { WindowFit.angles(it) }
            val r = WindowFit.Rect(a.minOf { it.first }, a.maxOf { it.first }, a.minOf { it.second }, a.maxOf { it.second })
            println("%s panel yaw %.1f..%.1f pitch %.1f..%.1f (sheet top %.1f)".format(name, r.left, r.right, r.bottom, r.top, rs.top))
            assertFalse("$name over the sheet", r.overlaps(rs))
        }
        // the volume clips its attachments: every panel corner, tipped, stays inside the volume box (local metres)
        for ((name, p) in panels) for (c in p.corners()) {
            val lx = c.first - vol.cx; val ly = c.second - vol.cy; val lz = c.third - vol.cz
            assertTrue("$name corner y $ly in ±${vol.h / 2}", kotlin.math.abs(ly) <= vol.h / 2)
            assertTrue("$name corner z $lz in ±${vol.d / 2}", kotlin.math.abs(lz) <= vol.d / 2)
            assertTrue("$name corner x $lx in ±${vol.w / 2}", kotlin.math.abs(lx) <= vol.w / 2)
        }
        val (chartW, chartX) = ChartPanels.chartSpan()
        println("chart span %.2f m at x %.3f between the panels".format(chartW, chartX))
        assertTrue("chart keeps at least 0.45 m: $chartW", chartW >= 0.45)
        // panels do not overlap the chart span
        val leftEdge = ChartPanels.panelX(true) + ChartPanels.LEFT_W_DP * ChartPanels.M_PER_DP / 2
        val rightEdge = ChartPanels.panelX(false) - ChartPanels.RIGHT_W_DP * ChartPanels.M_PER_DP / 2
        assertTrue(leftEdge < chartX - chartW / 2 && rightEdge > chartX + chartW / 2)
    }

    @Test fun panelsFaceTheEye() {
        for ((name, p) in panels) {
            // the panel normal (up × right reversed) points back to the eye within 20°
            val n = ChartPanels.V(0.0, -p.up.z, p.up.y) // right = +x, so normal = up × right flipped towards +z
            val toEye = ChartPanels.V(-p.centre.x, -p.centre.y, -p.centre.z)
            val ang = ChartPanels.angleOfSegment(n.t(), toEye.t())
            println("%s panel faces the eye within %.1f°".format(name, ang))
            assertTrue("$name $ang", ang <= 35.0)
        }
    }
}

/** QA #3 q3-2: the chart never opens before the sheet window is up, on both restore paths. */
class ChartReopenTest {
    private val sheetId = "SpatialOfficeSheet"
    private val others = listOf("SpatialOfficeDoc", "SpatialOfficeSlides", "SpatialOfficeHome")

    /** Runs the gate on a 100 ms tick; returns when it opened the chart (or -1 if dropped) and whether it asked for the sheet. */
    private fun run(askAt: Long, sheetOpenFrom: Long?, resumedAt: Long?, hasFile: Boolean = true): Pair<Long, Boolean> {
        val g = ChartOpenGate(); g.ask(askAt)
        var t = askAt; var askedSheet = false
        var openFrom = sheetOpenFrom
        while (t < askAt + 10_000) {
            val open = openFrom != null && t >= openFrom
            val res = resumedAt?.takeIf { t >= it }
            when (g.step(t, hasFile, open, res)) {
                Act.OPEN_CHART -> return t to askedSheet
                Act.DROP -> return -1L to askedSheet
                Act.OPEN_SHEET -> { askedSheet = true; if (openFrom == null) openFrom = t }
                Act.WAIT -> {}
            }
            t += 100
        }
        return -2L to askedSheet
    }

    @Test fun rehearsalPathWaitsForTheSheetQa3Timeline() {
        // QA #3 log (ms): reopen SHEET 37087, chart asked 37177, sheet ON_RESUME 37716
        val (at, _) = run(askAt = 37_177, sheetOpenFrom = 37_087, resumedAt = 37_716)
        assertTrue("chart opened at $at, after the sheet resumed + settle", at >= 37_716 + ChartOpenGate.SETTLE_MS)
        // at that time the sheet is among the containers, so the anchor is the sheet and the chart goes above it
        assertEquals(sheetId, ChartOpenGate.anchor(listOf("SpatialOfficeSlides", "SpatialOfficeHome", "SpatialOfficeDoc", sheetId), sheetId, others))
    }

    @Test fun v022OpenedTheChartBeforeTheSheetWasListed() {
        // what v0.2.2 did: placement at 37178 saw only slides + home + doc -> anchor null -> in front (q2-2 again)
        val listedAt37178 = listOf("SpatialOfficeSlides", "SpatialOfficeHome")
        assertFalse(listedAt37178.contains(sheetId))
    }

    @Test fun spreadPathOpensAtOnceWhenTheSheetIsLongUp() {
        // QA #3 log: sheet ON_RESUME 01466 (it came back first through the level gate), chart asked 02794
        val (at, asked) = run(askAt = 2_794, sheetOpenFrom = 1_185, resumedAt = 1_466)
        assertEquals(2_794L, at)
        assertFalse(asked)
    }

    @Test fun sheetClosedAsksForTheSheetFirst() {
        val (at, asked) = run(askAt = 0, sheetOpenFrom = null, resumedAt = 900)
        assertTrue(asked)
        assertTrue("$at", at >= 900 + ChartOpenGate.SETTLE_MS)
    }

    @Test fun sheetNeverComesUpTheChartIsNotOpenedInFront() {
        val (at, _) = run(askAt = 0, sheetOpenFrom = 0, resumedAt = null)
        assertEquals(-1L, at)
    }

    @Test fun chartWaitsForSharedSpaceToSettleAfterTheStage() {
        // v0.2.3 emulator run: Stage gone 43954, sheet resumed 44527, chart was placed 45147 and landed in front
        val g = ChartOpenGate(); g.ask(44_141)
        assertEquals(Act.WAIT, g.step(45_147, true, true, 44_527, 43_954))
        var t = 45_147L; var act = Act.WAIT
        while (act == Act.WAIT) { t += 100; act = g.step(t, true, true, 44_527, 43_954) }
        assertEquals(Act.OPEN_CHART, act)
        assertTrue("$t", t >= 43_954 + ChartOpenGate.STAGE_QUIET_MS)
    }

    @Test fun noSpreadsheetNoChart() {
        assertEquals(-1L, run(askAt = 0, sheetOpenFrom = 0, resumedAt = 0, hasFile = false).first)
    }

    @Test fun everyRestoreOutcomeIsAboveAWindowNotInFront() {
        // whatever is open when the placement runs, the chart stands on top of a window (Placement.top), never none()
        val cases = listOf(listOf(sheetId), listOf("SpatialOfficeDoc", sheetId), listOf("SpatialOfficeSlides", "SpatialOfficeHome"), listOf("SpatialOfficeHome"))
        for (c in cases) assertTrue("$c", ChartOpenGate.anchor(c, sheetId, others) != null)
        // and with the sheet there, above the sheet: clear of the grid (8 corners, WindowFit)
        val rc = WindowFit.rect(WindowFit.chartAboveSheet()); val rs = WindowFit.sheetWithControls()
        assertFalse(rc.overlaps(rs))
    }
}
