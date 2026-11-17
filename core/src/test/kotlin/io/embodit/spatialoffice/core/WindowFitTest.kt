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
