package io.embodit.spatialoffice.core.xlsx

import io.embodit.spatialoffice.core.text.RichText
import io.embodit.spatialoffice.core.text.Span
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RangeOpsTest {

    @Test fun rectNamesAndNormalises() {
        assertEquals("B2:D5", CellRect.of(4, 3, 1, 1).name())
        assertEquals("C3", CellRect.of(2, 2, 2, 2).name())
        assertEquals(12, CellRect.of(1, 1, 4, 3).size)
    }

    @Test fun statsSumAverageCount() {
        fun n(v: Double) = Cell(v.toString(), v.toString(), CellKind.NUMBER, v, false, false, NumFmt.GENERAL)
        val t = Cell("x", "x", CellKind.TEXT, null, false, false, NumFmt.GENERAL)
        val e = Cell("", "", CellKind.EMPTY, null, false, false, NumFmt.GENERAL)
        val st = RangeStats.of(listOf(n(0.1), n(0.2), t, e))
        assertEquals("0.3", RangeStats.fmt(st.sum))
        assertEquals("0.15", RangeStats.fmt(st.average!!))
        assertEquals(3, st.count)
        assertNull(RangeStats.of(listOf(t)).average)
    }

    @Test fun ctrlArrowGoesToTheEdgeOfTheData() {
        val filled = setOf(0 to 0, 1 to 0, 2 to 0, 6 to 0)
        val f = { r: Int, c: Int -> (r to c) in filled }
        assertEquals(2 to 0, DataEdge.jump(f, 0, 0, 1, 0, 100, 10))   // inside a run: its last cell
        assertEquals(6 to 0, DataEdge.jump(f, 2, 0, 1, 0, 100, 10))   // end of a run: the next filled cell
        assertEquals(100 to 0, DataEdge.jump(f, 6, 0, 1, 0, 100, 10)) // nothing further: the edge
        assertEquals(0 to 0, DataEdge.jump(f, 0, 0, -1, 0, 100, 10))  // already at the top
        assertEquals(0 to 10, DataEdge.jump(f, 0, 0, 0, 1, 100, 10))
    }

    @Test fun tsvRoundTrip() {
        assertEquals(listOf(listOf("a", "b", "c"), listOf("1", "2", "3")), Tsv.parse("a\tb\tc\r\n1\t2\t3\r\n"))
        assertEquals(listOf(listOf("x\ty", "z")), Tsv.parse(Tsv.write(listOf(listOf("x\ty", "z")))))
        assertEquals(listOf(listOf("a", "", "c")), Tsv.parse("a\t\tc"))
        assertEquals("he said \"hi\"", Tsv.parse(Tsv.write(listOf(listOf("he said \"hi\""))))[0][0])
    }

    @Test fun replaceAtKeepsTheFormat() {
        val spans = listOf(Span("Plan "), Span("the plan", bold = true))
        val out = RichText.replaceAt(spans, 9, 4, "map")
        assertEquals("Plan the map", RichText.text(out))
        assertEquals(true, out.last().bold)
        assertEquals(spans, RichText.replaceAt(spans, 20, 4, "x"))
    }
}
