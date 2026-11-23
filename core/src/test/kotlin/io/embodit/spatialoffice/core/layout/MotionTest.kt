package io.embodit.spatialoffice.core.layout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs

/** Motion of the Stage's panels: the same path at any frame rate, arrival times, the small give of the focused piece. */
class MotionTest {
    private fun pose(x: Float = 0f, y: Float = 0f, z: Float = 0f, yaw: Float = 0f, scale: Float = 1f, tilt: Float = 0f) = floatArrayOf(x, y, z, yaw, scale, tilt)

    private fun run(from: FloatArray, to: FloatArray, seconds: Float, fps: Int, feel: Motion.Feel, delay: Float = 0f): Motion.Body {
        val b = Motion.Body(from.copyOf(), delay = delay)
        val n = Math.round(seconds * fps)
        repeat(n) { Motion.step(b, to, 1f / fps, feel) }
        return b
    }

    @Test
    fun theSamePathAtTenAndAtNinetyFramesASecond() {
        val from = pose(0f, 1.1f, -1.2f, 0f, 0.3f); val to = pose(1.4f, 1.25f, -1.6f, -0.9f, 1f)
        for (feel in listOf(Motion.GLIDE, Motion.FOCUS, Motion.GRAND, Motion.GATHER)) for (t in listOf(0.1f, 0.2f, 0.3f, 0.5f)) {
            val slow = run(from, to, t, 10, feel); val fast = run(from, to, t, 90, feel)
            for (i in 0 until Motion.SIZE) assertEquals("$feel t=$t axis $i", fast.pos[i], slow.pos[i], 2e-3f)
        }
    }

    @Test
    fun glideArrivesInHalfASecondAndNeverPassesItsPlace() {
        val from = pose(x = 0f); val to = pose(x = 1f)
        val b = Motion.Body(from.copyOf())
        var max = 0f; var last = 0f
        repeat(72) { i ->
            Motion.step(b, to, 1f / 72, Motion.GLIDE)
            assertTrue("moves forward only", b.pos[0] >= last - 1e-6f); last = b.pos[0]; max = maxOf(max, b.pos[0])
            if (i == 35) assertTrue("98 % of the way after 0.5 s, was ${b.pos[0]}", b.pos[0] > 0.98f)
        }
        assertTrue(max <= 1.0001f)
        assertTrue(Motion.arrived(b, to))
        assertTrue(Motion.GLIDE.settle in 0.4f..0.5f)
    }

    @Test
    fun theFocusedPieceLandsWithASmallGive() {
        val to = pose(x = 1f)
        val b = Motion.Body(pose(x = 0f))
        var max = 0f
        repeat(90) { Motion.step(b, to, 1f / 90, Motion.FOCUS); max = maxOf(max, b.pos[0]) }
        assertTrue("passes its place a little: $max", max > 1.005f)
        assertTrue("but no more than 2.5 %: $max", max < 1.025f)
        assertTrue(Motion.arrived(b, to))
    }

    @Test
    fun aTargetThatChangesInFlightIsPickedUpWithTheSpeedItHas() {
        val b = Motion.Body(pose(x = 0f))
        repeat(9) { Motion.step(b, pose(x = 1f), 1f / 90, Motion.GLIDE) }
        val speed = b.vel[0]; val at = b.pos[0]
        assertTrue(speed > 0f)
        Motion.step(b, pose(x = -1f), 1f / 90, Motion.GLIDE)
        // one frame later it has not jumped: it is within a frame's travel of where it was
        assertTrue(abs(b.pos[0] - at) < abs(speed) / 90f + 0.01f)
    }

    @Test
    fun yawTakesTheShortWayRound() {
        val from = pose(yaw = (350.0 * PI / 180).toFloat()); val to = pose(yaw = (10.0 * PI / 180).toFloat())
        val b = Motion.Body(from.copyOf())
        Motion.step(b, to, 0.05f, Motion.GLIDE)
        assertTrue("turns on through 360°, not back through 180°", b.pos[Motion.YAW] > from[Motion.YAW])
        repeat(120) { Motion.step(b, to, 1f / 90, Motion.GLIDE) }
        assertEquals(0f, Motion.shortest(b.pos[Motion.YAW] - to[Motion.YAW]), 2e-3f)
        assertEquals((-20.0 * PI / 180).toFloat(), Motion.shortest((340.0 * PI / 180).toFloat()), 1e-5f)
    }

    @Test
    fun aWaitingPieceStaysPutThenMoves() {
        val to = pose(x = 1f)
        val b = Motion.Body(pose(x = 0f), delay = 0.09f)
        Motion.step(b, to, 0.05f, Motion.GLIDE)
        assertEquals(0f, b.pos[0], 0f); assertFalse(Motion.arrived(b, to))
        Motion.step(b, to, 0.05f, Motion.GLIDE) // 40 ms of waiting left, 10 ms of moving
        assertTrue(b.pos[0] > 0f && b.pos[0] < 0.02f)
        // the delay is part of the path, so it is frame-rate independent too
        val slow = run(pose(), to, 0.4f, 10, Motion.GLIDE, delay = 0.135f); val fast = run(pose(), to, 0.4f, 90, Motion.GLIDE, delay = 0.135f)
        assertEquals(fast.pos[0], slow.pos[0], 6e-3f)
    }

    @Test
    fun fanningOutStartsAtTheFocusAndIsNeverLate() {
        assertEquals(0f, Motion.fanDelay(4, 4), 0f)
        assertEquals(0.045f, Motion.fanDelay(3, 4), 1e-6f); assertEquals(0.045f, Motion.fanDelay(5, 4), 1e-6f)
        assertEquals(0.36f, Motion.fanDelay(40, 4), 1e-6f)
        // gathering: the outermost leave first, the focused piece last, all inside 120 ms
        assertEquals(0f, Motion.gatherDelay(0, 0, 1), 0f)
        assertEquals(0f, Motion.gatherDelay(9, 2, 10), 1e-6f)
        assertEquals(0.12f, Motion.gatherDelay(2, 2, 10), 1e-6f)
        assertTrue(Motion.gatherDelay(5, 2, 10) in 0.01f..0.11f)
    }

    @Test
    fun gatheringIsDoneBeforeTheStageCloses() {
        // the Stage closes 350 ms after [Collapse]: by then every piece is back in the stack (scale within 5 % of it)
        val target = pose(1.2f, 1.3f, -1.5f, -0.7f, 1f)
        val stack = Motion.stack(pose(0f, 1.25f, -1.5f, 0f, 1f), 0f, 1f)
        for (i in 0 until 10) {
            val b = Motion.Body(target.copyOf(), delay = Motion.gatherDelay(i, 2, 10))
            repeat(35) { Motion.step(b, stack, 0.01f, Motion.GATHER) }
            assertTrue("piece $i scale ${b.pos[Motion.SCALE]}", abs(b.pos[Motion.SCALE] - stack[Motion.SCALE]) < 0.05f)
        }
        assertEquals(0.32f, stack[Motion.SCALE], 1e-6f)
        assertEquals(1.15f, stack[Motion.Y], 1e-6f); assertEquals(-1.32f, stack[Motion.Z], 1e-6f)
    }

    @Test
    fun theScreenGivesOnePercentWhenTheSlideChangesAndComesBack() {
        val at = pose(scale = 4.17f)
        val b = Motion.Body(at.copyOf())
        Motion.nudge(b, Motion.SCALE, -Motion.nudgeSpeed(4.17f * Motion.SLIDE_PRESS, Motion.GRAND))
        var min = 4.17f
        repeat(90) { Motion.step(b, at, 1f / 90, Motion.GRAND); min = minOf(min, b.pos[Motion.SCALE]) }
        assertEquals("deepest point is 1 % of the size", 4.17f * 0.99f, min, 0.004f)
        assertTrue("back within a second", Motion.arrived(b, at))
        assertEquals(0f, b.pos[Motion.X], 0f) // nothing else moved
    }
}
