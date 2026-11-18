package io.embodit.spatialoffice.core

import io.embodit.spatialoffice.core.layout.ChartPlan
import io.embodit.spatialoffice.core.layout.LiveAnchor
import io.embodit.spatialoffice.core.layout.LiveAnchor.Listed
import io.embodit.spatialoffice.core.xlsx.ChartInfo
import io.embodit.spatialoffice.core.xlsx.ChartSeries
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private val PALETTE = listOf(0xFF2A78D6, 0xFFEB6834, 0xFF1BAF7A, 0xFFEDA100, 0xFF8A5BD6, 0xFFD6457A).map { it.toInt() }

/** The QA sample chart: 5 series × 3 quarters (QA #4 log `CHART built type=columns series=5 marks=15`). */
private fun qaChart(): ChartInfo = ChartInfo(
    part = "xl/charts/chart1.xml", sheet = 0, title = "Sales", type = "bar",
    series = (0 until 5).map { r ->
        ChartSeries("S${r + 1}", listOf("Q1", "Q2", "Q3"), listOf(10.0 + r, 20.0 + r * 2, 15.0 + r * 3), "Sheet1!\$B\$${r + 2}:\$D\$${r + 2}", null)
    },
    range = "A1:D6",
)

/** QA #4 q4-1: one material per colour, one mesh per size, both prepared off the main thread. */
class ChartPlanTest {
    @Test fun qaChartNeedsAFewMaterialsNotOnePerBox() {
        val boxes = ChartPlan.boxes(qaChart(), scatter = false, selRow = 2, selCol = 1, palette = PALETTE)
        val mats = ChartPlan.materialKeys(boxes); val meshes = ChartPlan.meshKeys(boxes)
        println("columns: ${boxes.size} boxes (v0.2.3: ${boxes.size} materials + ${boxes.size} meshes made on the main thread), now ${mats.size} materials, ${meshes.size} meshes, off the main thread")
        assertEquals(31, boxes.size) // base + 15 columns + 15 caps
        assertEquals(15, boxes.count { it.mark != null })
        // per series: the column colour and its lighter cap; plus the base, the selected column and its white cap
        assertTrue("${mats.size}", mats.size <= 5 * 2 + 3)
        assertTrue(meshes.size < boxes.size)
    }

    @Test fun sameColourIsOneMaterialWhateverTheSelection() {
        for (sel in listOf(-1 to -1, 1 to 1, 5 to 3)) {
            val boxes = ChartPlan.boxes(qaChart(), false, sel.first, sel.second, PALETTE)
            val byColour = boxes.groupBy { it.argb }
            assertEquals(ChartPlan.materialKeys(boxes).size, byColour.size)
        }
    }

    @Test fun scatterToo() {
        val boxes = ChartPlan.boxes(qaChart(), scatter = true, selRow = 3, selCol = 1, palette = PALETTE)
        val mats = ChartPlan.materialKeys(boxes)
        println("points: ${boxes.size} boxes, ${mats.size} materials, ${ChartPlan.meshKeys(boxes).size} meshes")
        assertTrue(mats.size <= 5 * 2 + 2)
    }

    @Test fun sameGeometryAsV023() {
        val boxes = ChartPlan.boxes(qaChart(), false, -1, -1, PALETTE)
        val base = boxes[0]
        assertEquals(0.64f, base.w, 1e-6f); assertEquals(-0.164f, base.y, 1e-6f)
        // tallest column = max value -> 0.26 m; the max is S5 Q2 = 28, cell C6 (0-based row 5, col 2)
        val tallest = boxes.filter { it.mark != null }.maxByOrNull { it.h }!!
        assertEquals(0.26f, tallest.h, 1e-5f)
        assertEquals(5 to 2, tallest.mark!!.row to tallest.mark!!.col)
    }

    @Test fun meshKeyRoundTrips() {
        val b = ChartPlan.Box(0.144f, 0.2167f, 0.0461f, 0f, 0f, 0f, 0)
        val (w, h, d) = ChartPlan.size(b.meshKey)
        assertEquals(0.144f, w, 1e-4f); assertEquals(0.2167f, h, 1e-4f); assertEquals(0.0461f, d, 1e-4f)
    }

    @Test fun lightenMatchesTheOldFloatMix() {
        val c = PALETTE[0]
        val l = ChartPlan.lighten(c, 0.35f)
        val r = (c ushr 16) and 0xFF
        assertEquals((r + (255 - r) * 0.35f).toInt().toFloat(), ((l ushr 16) and 0xFF).toFloat(), 1f)
        assertEquals(c, ChartPlan.lighten(c, 0f))
    }
}

/**
 * QA #4 q4-2: the placement list keeps destroyed windows. A fake of what the system listed (QA #4 log lines 558 /
 * 654 / 750 / 851 / 954 / 1053): destroyed proxies stay, all `isOnstage=true`; instance ids grow.
 */
class LiveAnchorTest {
    private val sheet = "SpatialOfficeSheet"; private val slides = "SpatialOfficeSlides"
    private val chart = "SpatialOfficeChart"; private val home = "SpatialOfficeHome"; private val doc = "SpatialOfficeDoc"

    private class FakeSystem {
        var next = 100
        val listed = ArrayList<Listed>()
        val live = HashMap<String, Int>()
        val dead = HashSet<Int>()
        fun open(name: String): Int { val id = next++; live[name] = id; listed.add(Listed(name, id)); return id }
        /** Destroyed: the app hears ON_DESTROY, the system list keeps the entry (what QA #4 saw). */
        fun destroy(name: String) { live.remove(name)?.let { dead += it } }
    }

    /** v0.2.3's rule: the first entry with the sheet's name. */
    private fun v023(listed: List<Listed>) = listed.indexOfFirst { it.name == sheet }

    private fun v024(s: FakeSystem) = LiveAnchor.anchor(s.listed, sheet, listOf(doc, slides, home), { s.live[it] }, s.dead)

    /** One rehearsal or spread: the Stage closes every window, then home, the editor, the sheet and the chart come back. */
    private fun cycle(s: FakeSystem, back: String) {
        for (w in listOf(sheet, chart, slides, home)) s.destroy(w)
        s.open(home); s.open(back); if (back != sheet) s.open(sheet)
    }

    @Test fun threeRehearsalsThreeSpreadsAlwaysAboveTheLiveSheet() {
        val s = FakeSystem()
        s.open(home); s.open(sheet); s.open(chart); s.open(slides)
        var v023Wrong = 0
        for ((i, back) in listOf(slides, slides, slides, sheet, sheet, sheet).withIndex()) {
            cycle(s, back)
            val pick = s.listed[v024(s)!!].instance
            val old = s.listed[v023(s.listed)].instance
            println("run ${i + 1} (${if (i < 3) "rehearsal" else "spread"}): listed ${s.listed.count { it.name == sheet }} sheet entries, live #${s.live[sheet]}, v0.2.4 picks #$pick, v0.2.3 picked #$old${if (old in s.dead) " (destroyed)" else ""}")
            assertEquals("run ${i + 1}: anchor is the live sheet", s.live[sheet], pick)
            assertTrue(pick !in s.dead)
            if (old in s.dead) v023Wrong++
            s.open(chart)
        }
        assertTrue("v0.2.3 rule anchors to a destroyed sheet in this replay ($v023Wrong / 6)", v023Wrong > 0)
    }

    @Test fun qa4Run2ListAsLogged() {
        // QA #4 line 654: Slides(new) Home(new) Slides(old) Chart(old) Sheet(old) Sheet(new)
        val listed = listOf(Listed(slides, 9), Listed(home, 8), Listed(slides, 4), Listed(chart, 3), Listed(sheet, 2), Listed(sheet, 10))
        val dead = setOf(1, 2, 3, 4)
        assertEquals(4, v023(listed)) // the destroyed sheet #2
        assertEquals(5, LiveAnchor.pick(listed, sheet, live = 10, dead = dead))
        // live id not known yet (window content not composed): still never a destroyed one
        assertEquals(5, LiveAnchor.pick(listed, sheet, live = null, dead = dead))
    }

    @Test fun onlyDeadSheetsFallBackToAnotherLiveWindowNeverTheDeadSheet() {
        val listed = listOf(Listed(home, 8), Listed(sheet, 2))
        val i = LiveAnchor.anchor(listed, sheet, listOf(doc, slides, home), { if (it == home) 8 else null }, setOf(2))
        assertEquals(0, i)
        assertNotEquals(1, i)
    }

    @Test fun unknownIdsKeepTheOldBehaviour() {
        val listed = listOf(Listed(home, -1), Listed(sheet, -1))
        assertEquals(1, LiveAnchor.pick(listed, sheet, null, emptySet()))
    }
}
