package io.embodit.spatialoffice.platform

import io.embodit.spatialoffice.core.files.FileKind
import io.embodit.spatialoffice.core.xlsx.Borders
import io.embodit.spatialoffice.core.xlsx.XlsxWorkbook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** v0.5.0 M3 sheets through the session calls the menus, the shortcuts and the debug commands use; zoom stepping. */
class M3SheetTest {
    private fun sheet(): SheetSession {
        val b = XlsxWorkbook.blank()
        b.setCell(0, 0, 0, "1"); b.setCell(0, 1, 0, "2")
        b.setCell(0, 0, 1, "=A1*10")
        return SheetSession(FileRef.Path(File("t.xlsx")), FileKind.SHEET, b.save())
    }

    @Test fun fillDownSeriesAndOneUndoStepEach() {
        val s = sheet()
        s.select(0, 1); s.extendTo(3, 1)
        assertEquals(3, s.fill(down = true))
        assertEquals("=A4*10", s.book.cell(0, 3, 1).input)
        s.select(0, 0); s.extendTo(5, 0)
        assertEquals(4, s.fillSeries(down = true))
        assertEquals("6", s.book.cell(0, 5, 0).input)
        s.undo()
        assertEquals("", s.book.cell(0, 5, 0).input)
        assertTrue(s.dirty)
    }

    @Test fun mergeSelectsTheMergedCellAndUnmergeSplitsIt() {
        val s = sheet()
        s.select(1, 1); s.extendTo(2, 2)
        assertTrue(s.merge())
        assertEquals("B2:C3", s.selection.name())
        s.select(2, 2)
        assertTrue(s.mergeHere() != null)
        assertTrue(s.unmerge())
        assertTrue(s.book.merges(0).isEmpty())
        s.select(0, 0)
        assertFalse("one cell does not merge", s.merge())
    }

    @Test fun outlineAllBottomNone() {
        val s = sheet()
        s.select(1, 1); s.extendTo(2, 2)
        s.setBorders("outline")
        assertEquals(Borders.LEFT or Borders.TOP, s.book.borderMask(0, 1, 1))
        assertEquals(Borders.RIGHT or Borders.BOTTOM, s.book.borderMask(0, 2, 2))
        s.setBorders("all")
        assertEquals(15, s.book.borderMask(0, 1, 2))
        s.setBorders("none")
        assertEquals(0, s.book.borderMask(0, 1, 2))
        s.select(4, 0); s.extendTo(4, 2)
        s.setBorders("bottom")
        assertEquals(Borders.BOTTOM, s.book.borderMask(0, 4, 1))
    }

    @Test fun zoomStepsAndNeverMarksTheFileChanged() {
        val s = sheet()
        assertEquals(100, s.zoom)
        s.zoomBy(1); s.zoomBy(1)
        assertEquals(125, s.zoom)
        s.zoomBy(-1)
        assertEquals(110, s.zoom)
        repeat(20) { s.zoomBy(-1) }
        assertEquals(ZOOM_STEPS.first(), s.zoom)
        s.setZoomPct(133); s.zoomBy(1)
        assertEquals(150, s.zoom)
        s.zoomReset()
        assertEquals(100, s.zoom)
        assertFalse(s.dirty)
    }
}
