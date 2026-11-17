package io.embodit.spatialoffice.core

import io.embodit.spatialoffice.core.layout.WindowFit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** q1-1: every corner of every editor window (and the home window) inside ±39° / ≤ 40° below, as opened. */
class WindowFitTest {
    private val editors = mapOf("doc" to (1160.0 to 860.0), "sheet" to (1280.0 to 800.0), "slides" to (1360.0 to 860.0))

    @Test fun editorsOpenInFrontAndAllEightCornersAreInView() {
        assertEquals(WindowFit.Spot.FRONT, WindowFit.editorSpot())
        for ((name, size) in editors) {
            val b = WindowFit.box(WindowFit.editorSpot(), size.first, size.second)
            assertEquals(8, b.corners().size)
            val f = WindowFit.fit(b)
            assertTrue("$name: $f", f.fits)
        }
        val home = WindowFit.fit(WindowFit.box(WindowFit.Spot.FRONT, 1120.0, 660.0))
        assertTrue("home $home", home.fits)
    }

    @Test fun theOldRightOfHomePlacementLeftTheViewThatIsWhatQaSaw() {
        val f = WindowFit.fit(WindowFit.box(WindowFit.Spot.RIGHT_OF_HOME, 1360.0, 860.0))
        assertFalse(f.fits)
        assertTrue("slides right edge ${f.maxYaw}° (QA: about 55°)", f.maxYaw in 50.0..62.0)
    }

    @Test fun cornersAreMeasuredNotCentres() {
        // a window whose centre is straight ahead but whose corners are not must fail
        val wide = WindowFit.fit(WindowFit.box(WindowFit.Spot.FRONT, 2600.0, 600.0))
        assertFalse(wide.fits)
    }
}

/** v0.2.2, QA #2 q2-1 / q2-2: 8 projected corners of the compatibility sheet and the chart volume. */
class WindowFitV022Test {
    private fun line(name: String, b: WindowFit.Box): String {
        val f = WindowFit.fit(b); val r = WindowFit.rect(b)
        return "%s yaw %.1f..%.1f pitch %.1f..%.1f (max |yaw| %.1f, max below %.1f)".format(name, r.left, r.right, r.bottom, r.top, f.maxYaw, f.maxBelow)
    }

    @Test fun compatSheetAllEightCornersInView() {
        val b = WindowFit.compatSheet()
        assertEquals(8, b.corners().size)
        val f = WindowFit.fit(b)
        println(line("compat sheet", b))
        assertTrue("compat sheet $f", f.fits)
        // and it is inside the editor it belongs to (doc is the narrowest editor)
        val doc = WindowFit.rect(WindowFit.frontWindow(1160.0, 860.0)); val c = WindowFit.rect(b)
        assertTrue("inside doc window: $c vs $doc", c.left >= doc.left - 1 && c.right <= doc.right + 1)
    }

    @Test fun oldCompatSubwindowWasOutOfView() {
        val f = WindowFit.fit(WindowFit.oldCompatSubwindow())
        assertFalse(f.fits)
        assertTrue(f.maxYaw > 39.0)
    }

    @Test fun chartAboveSheetInViewAndNotOverTheSheet() {
        val chart = WindowFit.chartAboveSheet()
        val sheet = WindowFit.frontWindow(WindowFit.SHEET_W_DP, WindowFit.SHEET_H_DP)
        println(line("sheet", sheet)); println(line("chart", chart))
        val fc = WindowFit.fit(chart); val fs = WindowFit.fit(sheet)
        assertTrue("chart $fc", fc.fits)
        assertTrue("sheet $fs", fs.fits)
        val rs = WindowFit.sheetWithControls(); val rc = WindowFit.rect(chart)
        assertFalse("chart $rc overlaps sheet+controls $rs", rc.overlaps(rs))
        assertTrue("at least 0.5° clear: ${rc.bottom - rs.top}", rc.bottom - rs.top >= 0.5)
        // the emulator shot build/v0.2.2/70-chart3d-columns-zh.png: the volume and its panels 27°–35° up
        assertTrue("top edge ${rc.top}° within 39°", rc.top <= 39.0)
    }

    @Test fun chartInFrontWasOverTheSheetThatIsWhatQaSaw() {
        val rc = WindowFit.rect(WindowFit.chartInFront())
        assertTrue(rc.overlaps(WindowFit.sheetWithControls()))
    }

    @Test fun theOldGapOf120dpWithTheTallVolumeLeftTheView() {
        // v0.2.1 try: Placement.bottom(sheet, 120.dp) with the 0.60 m volume -> on the floor, out of view
        val f = WindowFit.fit(WindowFit.chartBelowSheet(120.0, 0.76, 0.60, 0.48))
        assertFalse(f.fits)
    }
}
