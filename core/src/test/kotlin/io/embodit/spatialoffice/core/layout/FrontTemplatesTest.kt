package io.embodit.spatialoffice.core.layout

import io.embodit.spatialoffice.core.files.FileKind
import kotlin.math.abs
import kotlin.math.atan2
import org.junit.Assert
import org.junit.Test

/** The front templates (three across, grid, crown, main) and the half-circle limit of every layout. */
class FrontTemplatesTest {
    private val kinds = listOf(FileKind.DOC, FileKind.SHEET, FileKind.SLIDES)
    private val fronts = Arrangement.values().filter { it.front }

    private fun assertTrue(ok: Boolean, why: String = "") = Assert.assertTrue(why, ok)
    private fun assertEquals(want: Any?, got: Any?, why: String = "") = Assert.assertEquals(why, want, got)
    private fun assertEquals(want: Float, got: Float, eps: Float, why: String = "") = Assert.assertEquals(why, want, got, eps)

    private fun deg(a: Double) = a * 180.0 / Math.PI

    /** Angular box of a panel seen from the seat: left, right, bottom, top (degrees). */
    private fun box(p: PanelPose): DoubleArray {
        val r = Math.hypot(p.x.toDouble(), p.z.toDouble())
        val halfW = deg(atan2(p.width * p.scale / 2.0, r)); val az = p.azimuthDeg.toDouble()
        val lo = deg(atan2(p.y - p.height * p.scale / 2.0, r)); val hi = deg(atan2(p.y + p.height * p.scale / 2.0, r))
        return doubleArrayOf(az - halfW, az + halfW, lo, hi)
    }

    private fun each(run: (FileKind, Float, Arrangement, Int, Int, Map<Int, PanelPose>) -> Unit) {
        for (kind in kinds) for (radius in LayoutRules.RADII) for (a in fronts) for (count in listOf(1, 2, 3, 4, 5, 9, 30)) for (focus in 0 until count) {
            run(kind, radius, a, count, focus, LayoutRules.frontPoses(kind, count, a, focus, radius))
        }
    }

    @Test
    fun `a template shows a handful of pieces and always the one in front`() = each { kind, _, a, count, focus, p ->
        assertTrue(focus in p, "$kind $a $count/$focus has no focused piece")
        val most = when (a) { Arrangement.TRIPLE -> 3; Arrangement.GRID -> 4; else -> 5 }
        assertTrue(p.size <= minOf(most, count), "$kind $a shows ${p.size}")
        assertTrue(p.keys.all { it in 0 until count })
        if (a != Arrangement.GRID) assertEquals(0f, p.getValue(focus).azimuthDeg, 0.01f, "$a: the focused piece is straight ahead")
    }

    @Test
    fun `every panel of a template is in the view without turning, and none is overhead or at the feet`() = each { kind, radius, a, count, focus, p ->
        for ((i, pose) in p) {
            val b = box(pose)
            assertTrue(b[0] >= -39.5 && b[1] <= 39.5, "$kind r=$radius $a $count/$focus piece $i spans ${b[0]}..${b[1]}")
            assertTrue(b[2] >= -40 && b[3] <= 39, "$kind r=$radius $a $count/$focus piece $i is at ${b[2]}..${b[3]} up")
        }
    }

    @Test
    fun `the panels of a template do not cover one another`() = each { kind, radius, a, count, focus, p ->
        val boxes = p.entries.map { it.key to box(it.value) }
        for (i in boxes.indices) for (j in i + 1 until boxes.size) {
            val x = boxes[i].second; val y = boxes[j].second
            val over = x[0] < y[1] - 0.2 && y[0] < x[1] - 0.2 && x[2] < y[3] - 0.2 && y[2] < x[3] - 0.2
            assertTrue(!over, "$kind r=$radius $a $count/$focus: pieces ${boxes[i].first} and ${boxes[j].first} overlap")
        }
    }

    @Test
    fun `stepping on moves every piece one place along the same places`() {
        for (kind in kinds) for (a in listOf(Arrangement.TRIPLE, Arrangement.CROWN, Arrangement.MAIN)) {
            val here = LayoutRules.frontPoses(kind, 12, a, 5, 1.5f)
            val next = LayoutRules.frontPoses(kind, 12, a, 6, 1.5f)
            // the same set of places, each piece one further on
            for ((i, pose) in here) assertEquals(pose, next.getValue(i + 1), "$kind $a: piece ${i + 1} takes the place piece $i had")
        }
    }

    @Test
    fun `the grid keeps its four places while the focus moves inside the group, and turns the page after it`() {
        val a = LayoutRules.frontPoses(FileKind.SLIDES, 10, Arrangement.GRID, 4, 1.5f)
        val b = LayoutRules.frontPoses(FileKind.SLIDES, 10, Arrangement.GRID, 7, 1.5f)
        assertEquals(a, b)
        assertEquals(setOf(4, 5, 6, 7), a.keys)
        assertEquals(setOf(8, 9), LayoutRules.frontPoses(FileKind.SLIDES, 10, Arrangement.GRID, 8, 1.5f).keys)
        // reading order: 4 upper left, 5 upper right, 6 lower left, 7 lower right; the upper row leans down, the lower up
        assertTrue(a.getValue(4).x < 0 && a.getValue(5).x > 0 && a.getValue(4).y > 0 && a.getValue(6).y < 0)
        assertTrue(a.getValue(4).tiltDeg > 0 && a.getValue(6).tiltDeg < 0)
    }

    @Test
    fun `the two above the row of three lean down towards the eyes`() {
        val p = LayoutRules.frontPoses(FileKind.SLIDES, 9, Arrangement.CROWN, 4, 1.5f)
        assertEquals(setOf(2, 3, 4, 5, 6), p.keys)
        assertTrue(p.getValue(2).y > p.getValue(4).y && p.getValue(6).y > p.getValue(4).y)
        assertTrue(p.getValue(2).tiltDeg > 5f && p.getValue(6).tiltDeg > 5f)
        assertTrue(p.getValue(2).x < 0 && p.getValue(6).x > 0)
    }

    @Test
    fun `the main piece is the largest and the small ones stand at its sides`() {
        val p = LayoutRules.frontPoses(FileKind.DOC, 9, Arrangement.MAIN, 4, 1.5f)
        val main = p.getValue(4)
        for (i in listOf(2, 3, 5, 6)) assertTrue(p.getValue(i).scale < main.scale / 2f + 0.01f)
        assertTrue(p.getValue(2).x < 0 && p.getValue(3).x < 0 && p.getValue(5).x > 0 && p.getValue(6).x > 0)
    }

    @Test
    fun `no layout puts a panel behind the shoulders`() {
        for (kind in kinds) for (radius in LayoutRules.RADII) {
            for (a in listOf(Arrangement.ROW, Arrangement.ARC, Arrangement.LAYERS)) {
                val shown = LayoutRules.poses(kind, 30, a, 12, radius).filter { LayoutRules.inView(it) }
                assertTrue(shown.isNotEmpty() && shown.all { abs(it.azimuthDeg) <= LayoutRules.MAX_AZ + 0.01f })
            }
            // the storyline by chapters: slides and piles, wherever the focus is in its chapter
            val chapters = listOf(0 to 7, 7 to 7, 14 to 7, 21 to 7, 28 to 2)
            for (focus in 0 until 30) {
                val p = LayoutRules.chapterPoses(FileKind.SLIDES, chapters, focus, radius)
                assertTrue(focus in p)
                for ((i, pose) in p) assertTrue(abs(pose.azimuthDeg) <= LayoutRules.MAX_AZ + 0.01f, "r=$radius focus=$focus piece $i at ${pose.azimuthDeg}")
            }
        }
    }

    @Test
    fun `the bar goes under the lowest piece in front`() {
        val p = LayoutRules.frontPoses(FileKind.DOC, 8, Arrangement.GRID, 0, 1.5f)
        val bottom = p.values.minOf { it.bottom }
        val bar = LayoutRules.barPose(FileKind.DOC, 1.5f, bottom)
        assertTrue(bar.y < bottom - 0.1f)
        // and still not at the feet: no more than 40° under the eyes
        assertTrue(deg(atan2(-bar.y.toDouble(), -bar.z.toDouble())) <= 40.0)
        assertEquals(LayoutRules.barPose(FileKind.DOC, 1.5f), LayoutRules.barPose(FileKind.DOC, 1.5f, -LayoutRules.panelSize(FileKind.DOC).height / 2f))
    }
}
