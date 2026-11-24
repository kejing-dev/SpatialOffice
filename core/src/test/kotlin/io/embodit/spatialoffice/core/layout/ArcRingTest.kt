package io.embodit.spatialoffice.core.layout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** v0.5.1: new editor windows stand beside the ones already up, alternately right / left, never in front of them. */
class ArcRingTest {
    @Test fun firstInFrontThenRightThenLeftAndTheRowEndsThere() {
        val r = ArcRing()
        assertNull(r.place("doc/f1"))
        assertEquals("doc/f1" to "right", r.place("doc/f2"))
        assertEquals("doc/f1" to "left", r.place("doc/f3"))
        // 2026-11-24: left · centre · right is the whole row; the next windows open in front, over it, and are not part of it
        assertNull(r.place("doc/f4"))
        assertNull(r.place("doc/f5"))
        assertEquals(listOf("doc/f3", "doc/f1", "doc/f2"), r.order)
        assertEquals(0, r.slot("doc/f1")); assertEquals(1, r.slot("doc/f2")); assertEquals(-1, r.slot("doc/f3"))
        assertNull(r.slot("doc/f4"))
        // a place at a side comes free: the next window takes it
        r.remove("doc/f2")
        assertEquals("doc/f1" to "right", r.place("doc/f6"))
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

    // v0.5.2 (QA #14 q14-1): home → open 3 files in a row, going back home between them (the real path)
    @Test fun homeWindowTakesAPlaceOfItsOwnAndNoFileOpensOnItsSpot() {
        val r = ArcRing()
        val home = "SpatialOfficeHome"
        val up = mutableSetOf(home)
        val live: (String) -> Boolean = { it in up }
        // file 1: beside the home window (v0.5.1: null = in front, on the home window's spot)
        assertEquals(home to "right", r.place("SpatialOfficeSheet/f1", hub = home, live = live)); up += "SpatialOfficeSheet/f1"
        // [Window 1] › [Home] only restores the home window in its own place; then file 2, file 3
        assertEquals(home to "left", r.place("SpatialOfficeSheet/f2", hub = home, live = live)); up += "SpatialOfficeSheet/f2"
        assertEquals(listOf("SpatialOfficeSheet/f2", home, "SpatialOfficeSheet/f1"), r.order)
        // three windows, three places: no two share a slot, no file on the home window's spot (slot 0)
        val slots = r.order.map { r.slot(it) }
        assertEquals(slots.toSet().size, slots.size)
        assertEquals(0, r.slot(home))
        r.order.filter { it != home }.forEach { assertTrue(it, r.slot(it) != 0) }
        assertTrue(r.order.zipWithNext().all { (a, b) -> r.slot(b)!! - r.slot(a)!! == 1 })
        // 2026-11-24: the row is full; file 3 opens in front, over the row (never behind the shoulders), outside the bookkeeping
        assertNull(r.place("SpatialOfficeSheet/f3", hub = home, live = live)); up += "SpatialOfficeSheet/f3"
        assertEquals(listOf("SpatialOfficeSheet/f2", home, "SpatialOfficeSheet/f1"), r.order)
    }

    @Test fun homeWindowNotUpOrFilesAlreadyUpLeavesTheOldRule() {
        val r = ArcRing()
        assertNull(r.place("doc/f1", hub = "home") { it != "home" }) // home closed (Stage): first file in front
        assertEquals("doc/f1" to "right", r.place("doc/f2", hub = "home")) // home came back later: not counted in
        assertEquals(listOf("doc/f1", "doc/f2"), r.order)
    }

    @Test fun allFilesClosedTheNextOneGoesBesideHomeAgain() {
        val r = ArcRing()
        val up = mutableSetOf("home")
        assertEquals("home" to "right", r.place("a", hub = "home") { it in up }); up += "a"
        up -= "a"; r.remove("a")
        assertEquals("home" to "right", r.place("b", hub = "home") { it in up })
    }
}
