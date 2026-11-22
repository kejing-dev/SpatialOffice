package io.embodit.spatialoffice.core.layout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** v0.5.1: new editor windows stand beside the ones already up, alternately right / left, never in front of them. */
class ArcRingTest {
    @Test fun firstInFrontThenRightLeftRightAroundIt() {
        val r = ArcRing()
        assertNull(r.place("doc/f1"))
        assertEquals("doc/f1" to "right", r.place("doc/f2"))
        assertEquals("doc/f1" to "left", r.place("doc/f3"))
        assertEquals("doc/f2" to "right", r.place("doc/f4"))
        assertEquals("doc/f3" to "left", r.place("doc/f5"))
        assertEquals(listOf("doc/f5", "doc/f3", "doc/f1", "doc/f2", "doc/f4"), r.order)
        assertEquals(0, r.slot("doc/f1")); assertEquals(1, r.slot("doc/f2")); assertEquals(-1, r.slot("doc/f3"))
    }

    @Test fun differentKindsShareTheRow() {
        val r = ArcRing()
        r.place("SpatialOfficeDoc/f1")
        assertEquals("SpatialOfficeDoc/f1" to "right", r.place("SpatialOfficeSheet/f2"))
        assertEquals("SpatialOfficeDoc/f1" to "left", r.place("SpatialOfficeSlides/f3"))
    }

    @Test fun closedWindowsLeaveTheRowAndNoneIsEverAnchoredToAClosedOne() {
        val r = ArcRing()
        r.place("a"); r.place("b"); r.place("c") // c a b
        r.remove("b")
        assertEquals("a" to "right", r.place("d")) // c a d
        val open = setOf("c", "d")                   // a closed without a callback (system x)
        val p = r.place("e") { it in open }
        assertTrue(p!!.first in open)
    }

    @Test fun allClosedStartsAgainInFront() {
        val r = ArcRing()
        r.place("a"); r.place("b")
        assertNull(r.place("c") { false })
        assertEquals(listOf("c"), r.order)
    }

    @Test fun neighbouringWideWindowsAreAtLeastTheirOwnWidthApart() {
        // v0.5.1 emulator (6.2.1): a 1160 dp doc window in front spans about 70° of the 90° capture, i.e. ~1.3 m out at
        // ~1.6 mm / dp. Two such windows side by side without overlap are then ~80° apart centre to centre, so the
        // 25–35° of profile D cannot be met by full-size editor windows (deviation recorded); the gap adds a little.
        val touching = ArcRing.neighbourAngle(1160.0, 1160.0, 0.0, 1.33, 0.0016)
        val withGap = ArcRing.neighbourAngle(1160.0, 1160.0, 56.0, 1.33, 0.0016)
        assertTrue("touching $touching", touching > 35.0)
        assertTrue(withGap > touching)
        assertEquals(touching, ArcRing.neighbourAngle(580.0, 580.0, 0.0, 1.33 / 2, 0.0016), 1e-9)
    }
}
