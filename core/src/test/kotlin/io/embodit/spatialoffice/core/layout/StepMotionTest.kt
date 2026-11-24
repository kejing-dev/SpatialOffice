package io.embodit.spatialoffice.core.layout

import io.embodit.spatialoffice.core.files.FileKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.atan2

/**
 * Stepping through a spread: pieces stand edge to edge, so nothing may lie over its neighbour on the way.
 * (User on the headset, 2026-11-24: "while it scrolls one page lies over another for a while; it looks like a bug".)
 */
class StepMotionTest {
    private val kind = FileKind.SLIDES
    private val width = LayoutRules.panelSize(kind).width

    private fun body(p: PanelPose) = Motion.Body(floatArrayOf(p.x, p.y, p.z, (p.yawDeg * PI / 180).toFloat(), p.scale, 0f, 1f))
    private fun goal(p: PanelPose) = body(p).pos

    /** The directions of a panel's left and right edge seen from the seat, degrees (the panel is turned by its yaw). */
    private fun span(b: FloatArray): Pair<Float, Float> {
        val hw = width * b[Motion.SCALE] / 2
        val cx = kotlin.math.cos(b[Motion.YAW]) * hw; val cz = -kotlin.math.sin(b[Motion.YAW]) * hw
        val l = (atan2((b[0] - cx).toDouble(), -(b[2] - cz).toDouble()) * 180 / PI).toFloat()
        val r = (atan2((b[0] + cx).toDouble(), -(b[2] + cz).toDouble()) * 180 / PI).toFloat()
        return minOf(l, r) to maxOf(l, r)
    }

    /** Degrees by which two panels lie over one another, seen from the seat (below 0 = clear of one another). */
    private fun over(a: FloatArray, b: FloatArray): Float { val p = span(a); val q = span(b); return minOf(p.second, q.second) - maxOf(p.first, q.first) }

    /** Plays [bodies] towards [goals] for 0.6 s at 90 frames a second; returns the largest overlap of any pair in [pairs]. */
    private fun worst(bodies: List<Motion.Body>, goals: List<FloatArray>, feels: List<Motion.Feel>, pairs: List<Pair<Int, Int>>): Float {
        var worst = -1000f
        repeat(54) {
            for (i in bodies.indices) Motion.step(bodies[i], goals[i], 1f / 90, feels[i])
            for ((i, j) in pairs) worst = maxOf(worst, over(bodies[i].pos, bodies[j].pos))
        }
        return worst
    }

    @Test
    fun aRowThatStepsAsOneNeverOverlaps() {
        for (arrangement in listOf(Arrangement.ARC, Arrangement.ROW)) for (radius in LayoutRules.RADII) {
            val before = LayoutRules.poses(kind, 9, arrangement, 4, radius)
            val after = LayoutRules.poses(kind, 9, arrangement, 5, radius)
            val pieces = (2..7).toList()
            val w = worst(pieces.map { body(before[it]) }, pieces.map { goal(after[it]) }, pieces.map { Motion.GLIDE }, pieces.indices.zipWithNext())
            assertTrue("$arrangement at $radius m: neighbours overlap by $w°", w < 0f)
        }
    }

    @Test
    fun aQuickerPieceInFrontRunsIntoItsNeighbour() {
        // why every piece of the row has the same feel: with the old quicker feel for the piece that comes to the front, it lay over the one before it
        val before = LayoutRules.poses(kind, 9, Arrangement.ARC, 4, 1.5f)
        val after = LayoutRules.poses(kind, 9, Arrangement.ARC, 5, 1.5f)
        val w = worst(listOf(body(before[4]), body(before[5])), listOf(goal(after[4]), goal(after[5])), listOf(Motion.GLIDE, Motion.FOCUS), listOf(0 to 1))
        assertTrue("expected an overlap of some degrees, got $w°", w > 2f)
    }

    @Test
    fun aSlidePushedOutNextToAPileTouchesNeitherThePileNorItsNeighbour() {
        // three chapters; the one in the middle is long, so its row slides along while the piles at both ends stay where they are
        val chapters = listOf(0 to 3, 3 to 12, 15 to 3)
        for (radius in LayoutRules.RADII) for (focus in 6..10) {
            val before = LayoutRules.chapterPoses(kind, chapters, focus, radius)
            val after = LayoutRules.chapterPoses(kind, chapters, focus + 1, radius)
            val out = before.keys.filter { it !in after }
            assertEquals("one slide leaves the row at a step (focus $focus)", 1, out.size)
            val leaver = out.single(); val neighbour = leaver + 1
            val pile = after.getValue(0)
            assertEquals("the pile stays where it is", before.getValue(0).x, pile.x, 1e-4f)
            // the leaver goes SQUEEZE of its neighbour's way and shrinks
            val from = before.getValue(leaver); val by = after.getValue(neighbour).let { n -> floatArrayOf(n.x - before.getValue(neighbour).x, n.z - before.getValue(neighbour).z) }
            val to = goal(from).also { it[0] += by[0] * Motion.SQUEEZE; it[2] += by[1] * Motion.SQUEEZE; it[Motion.SCALE] *= Motion.SQUEEZE_SCALE }
            val w = worst(
                listOf(body(from), body(before.getValue(neighbour)), body(pile)), listOf(to, goal(after.getValue(neighbour)), goal(pile)),
                List(3) { Motion.GLIDE }, listOf(0 to 1, 0 to 2),
            )
            assertTrue("focus $focus at $radius m: the slide that leaves overlaps by $w°", w < 0f)
        }
    }

    @Test
    fun whatGoesOutWithTheRowIsGoneBeforeItGetsAnywhere() {
        // alpha heads for −1: nothing is seen of the piece after about 135 ms, a third of the way through the move
        val b = Motion.Body(floatArrayOf(0f, 0f, 0f, 0f, 1f, 0f, 1f))
        val to = floatArrayOf(-0.6f, 0f, 0f, 0f, 1f, 0f, -1f)
        var gone = -1
        for (frame in 1..54) { Motion.step(b, to, 1f / 90, Motion.GLIDE); if (gone < 0 && b.pos[Motion.ALPHA] <= 0f) gone = frame }
        assertTrue("gone after ${gone * 1000 / 90} ms", gone in 9..14)
    }
}
