package io.embodit.spatialoffice.platform

import androidx.compose.runtime.snapshots.Snapshot
import io.embodit.spatialoffice.core.docx.DocxDocument
import io.embodit.spatialoffice.core.docx.ParaStyle
import io.embodit.spatialoffice.core.files.FileKind
import io.embodit.spatialoffice.core.pptx.PptxDeck
import io.embodit.spatialoffice.core.pptx.SlideLayoutKind
import io.embodit.spatialoffice.core.text.Span
import io.embodit.spatialoffice.core.xlsx.XlsxWorkbook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Profile D57 (2026-11-16, v0.1.0 on the headset: "PPT 新建之后没有立刻显示新页面，重新打开才有"): whatever a list on
 * screen reads must be snapshot state that the edit itself writes, so the list recomposes in the same frame. Each
 * test reads the count the UI reads, makes the edit without reopening anything, and checks that a state read was
 * written (= the composable is invalidated) and that the new item is there.
 */
class NewItemsShowAtOnceTest {

    /** Runs [read] in a snapshot recording state reads; returns true when [edit] then changes one of them. */
    private fun invalidates(read: () -> Unit, edit: () -> Unit): Boolean {
        val reads = HashSet<Any>()
        val snap = Snapshot.takeMutableSnapshot(readObserver = { reads.add(it) })
        try { snap.enter { read() } } finally { snap.dispose() }
        var hit = false
        val h = Snapshot.registerApplyObserver { changed, _ -> if (changed.any { it in reads }) hit = true }
        try {
            edit()
            Snapshot.sendApplyNotifications()
        } finally { h.dispose() }
        return hit
    }

    @Test fun newSlideIsListedAndSelectedWithoutReopening() {
        val s = DeckSession(FileRef.Path(File("t.pptx")), PptxDeck.blank().save())
        assertEquals(1, s.slideCount)
        assertTrue(invalidates({ s.slideCount; s.current }) { s.addSlide(SlideLayoutKind.TITLE_BODY) })
        assertEquals(2, s.slideCount)
        assertEquals(1, s.current)               // the new slide is the current one (thumbnail list scrolls to it)
        assertTrue(invalidates({ s.slideCount }) { s.duplicateSlide() })
        assertEquals(3, s.slideCount); assertEquals(2, s.current)
        assertTrue(invalidates({ s.slideCount }) { s.addSlide(SlideLayoutKind.BLANK) })
        assertTrue(invalidates({ s.slideCount }) { s.addSlide(SlideLayoutKind.TWO_COLUMN) })
        assertEquals(5, s.slideCount)
        assertTrue(invalidates({ s.slideCount }) { s.deleteSlide() })
        assertEquals(4, s.slideCount)
        assertTrue(invalidates({ s.slideCount }) { s.undo() })
        assertEquals(5, s.slideCount)
        // a shape added to the current slide is picked at once, so the object tools act on it
        assertTrue(invalidates({ s.selected; s.version }) { s.addShape("ellipse", 0x2563EB) })
        assertTrue(s.selected > 0)
        assertTrue(s.deck.slides[s.current].shapes.any { it.id == s.selected })
    }

    @Test fun newParagraphAndTableShowWithoutReopening() {
        val s = DocSession(FileRef.Path(File("t.docx")), FileKind.DOC, DocxDocument.blank().save(), File("build/tmp"))
        val before = s.blockCount
        assertTrue(invalidates({ s.blockCount }) { s.insertAfter(s.blocks().last().id, listOf(Span("new")), ParaStyle.BODY) })
        assertEquals(before + 1, s.blockCount)
        assertTrue(invalidates({ s.blockCount }) { s.insertTable(s.blocks().last().id, 2, 2) })
        assertTrue(s.blocks().any { it is UiBlock.Table })
    }

    @Test fun newRowAndCellsShowWithoutReopening() {
        val b = XlsxWorkbook.blank()
        b.setCell(0, 0, 0, "a"); b.setCell(0, 1, 0, "1")
        val s = SheetSession(FileRef.Path(File("t.xlsx")), FileKind.SHEET, b.save())
        assertEquals(2, s.rowCount)
        assertTrue(invalidates({ s.rowCount }) { s.setCell(4, 0, "5") })
        assertEquals(5, s.rowCount)
        s.select(1, 0)
        assertTrue(invalidates({ s.rowCount; s.version }) { s.insertRowBelow() })
        assertEquals(6, s.rowCount)
        assertTrue(invalidates({ s.colCount }) { s.setCell(0, 3, "x") })
        assertEquals(4, s.colCount)
    }
}
