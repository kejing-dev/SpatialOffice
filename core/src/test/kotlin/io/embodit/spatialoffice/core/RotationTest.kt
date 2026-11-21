package io.embodit.spatialoffice.core

import io.embodit.spatialoffice.core.layout.Rotation
import org.junit.Test
import org.junit.Assert.assertEquals

class RotationTest {
    @Test fun angleIsClockwiseFromUp() {
        assertEquals(0f, Rotation.angle(0f, 0f, 0f, -10f), 0.01f)
        assertEquals(90f, Rotation.angle(0f, 0f, 10f, 0f), 0.01f)
        assertEquals(180f, Rotation.angle(0f, 0f, 0f, 10f), 0.01f)
        assertEquals(270f, Rotation.angle(0f, 0f, -10f, 0f), 0.01f)
    }

    @Test fun snapsToFifteenDegrees() {
        assertEquals(0, Rotation.snap(7f)); assertEquals(15, Rotation.snap(8f)); assertEquals(90, Rotation.snap(97f))
        assertEquals(0, Rotation.snap(355f)); assertEquals(345, Rotation.snap(340f))
    }

    @Test fun dragTheHandleAQuarterTurn() {
        // handle 100 px above the centre, dragged to the right of the centre: a quarter turn clockwise
        assertEquals(90, Rotation.dragged(0, 100f, 100f, 100f))
        // from 90°, back up over the centre: 0°
        assertEquals(0, Rotation.dragged(90, 100f, -100f, -100f))
        // a small wobble stays on the step it started at
        assertEquals(0, Rotation.dragged(0, 100f, 6f, 0f))
    }
}
