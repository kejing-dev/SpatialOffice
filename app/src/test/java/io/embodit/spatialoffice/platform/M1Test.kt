package io.embodit.spatialoffice.platform

import io.embodit.spatialoffice.core.docx.ParaStyle
import io.embodit.spatialoffice.core.files.FileKind
import io.embodit.spatialoffice.core.pptx.PptxDeck
import io.embodit.spatialoffice.core.pptx.ZOrder
import io.embodit.spatialoffice.core.docx.DocxDocument
import io.embodit.spatialoffice.core.text.Span
import io.embodit.spatialoffice.core.xlsx.XlsxWorkbook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v0.3.0 (M1) through the same session calls the menus, keys and debug commands use: the QA acceptance points of
 * design-package roadmap[0], read back from the saved bytes where the point is about the file.
 */
class M1Test {

    private fun sheet(vararg cells: Pair<String, String>): SheetSession {
        val b = XlsxWorkbook.blank()
        for ((a, v) in cells) { val (r, c) = io.embodit.spatialoffice.core.xlsx.A1.parse(a)!!; b.setCell(0, r, c, v) }
        return SheetSession(FileRef.Path(File("t.xlsx")), FileKind.SHEET, b.save())
    }

    private fun reopen(s: SheetSession) = XlsxWorkbook.open(s.serialize())

    // ---- s17 range selection

    @Test fun dragB2toD5StatsAndFormatsTwelveCells() {
        val s = sheet("B2" to "1", "C2" to "2", "D2" to "3", "B3" to "4", "C3" to "x", "D5" to "10")
        s.select(1, 1); s.extendTo(4, 3)                       // a drag from B2 to D5
        assertEquals("B2:D5", s.selection.name())
        val st = s.stats()
        assertEquals(20.0, st.sum, 1e-9)
        assertEquals(4.0, st.average!!, 1e-9)                   // 1 2 3 4 10 → 20 / 5
        assertEquals(6, st.count)                              // non-empty cells, text included
        s.setFill(0xFFF3B0); s.setBold(true)
        val b = reopen(s)
        var n = 0
        for (r in 1..4) for (c in 1..3) { assertEquals(0xFFF3B0, b.fillRgb(0, r, c)); assertTrue(b.cell(0, r, c).bold); n++ }
        assertEquals(12, n)
        assertNull(b.fillRgb(0, 0, 0)); assertFalse(b.cell(0, 5, 1).bold)
    }

    @Test fun rowColumnAndCtrlArrowSelections() {
        val s = sheet("A1" to "a", "B1" to "b", "C1" to "c", "A2" to "1", "A3" to "2", "A4" to "3")
        s.selectRow(0); assertEquals("A1:C1", s.selection.name())
        s.selectColumn(0); assertEquals("A1:A4", s.selection.name())
        s.select(0, 0); s.jump(1, 0, extend = false); assertEquals(3, s.row)          // to the last filled cell of the run
        s.select(0, 0); s.jump(0, 1, extend = true); assertEquals("A1:C1", s.selection.name())
        s.select(3, 0); s.extendTo(3, 1); assertTrue(s.isRange)
    }

    // ---- s18 copy / cut / paste

    @Test fun pasteMovesFormulasCutEmptiesSourceOutsideTextSplits() {
        val s = sheet("A1" to "1", "A2" to "2", "A3" to "3", "B1" to "=A1*2", "B2" to "=A2*2", "B3" to "=SUM(\$A\$1:A3)")
        SheetSession.clip = null
        s.select(0, 0); s.extendTo(2, 1)
        val text = s.copy(cut = false)
        assertEquals("1\t2\n2\t4\n3\t6", text)
        s.select(0, 3)                                          // D1
        assertEquals("D1:E3", s.paste(text)!!.name())
        var b = reopen(s)
        assertEquals("=D1*2", b.cell(0, 0, 4).input)
        assertEquals("=SUM(\$A\$1:D3)", b.cell(0, 2, 4).input)
        // cut A1:A3 → G1: the source is empty afterwards
        s.select(0, 0); s.extendTo(2, 0)
        val cut = s.copy(cut = true)
        s.select(0, 6); s.paste(cut)
        b = reopen(s)
        assertEquals("", b.cell(0, 0, 0).input); assertEquals("", b.cell(0, 2, 0).input)
        assertEquals("1", b.cell(0, 0, 6).input); assertEquals("3", b.cell(0, 2, 6).input)
        // text from another app: 2 rows × 3 columns
        s.select(5, 1)
        assertEquals("B6:D7", s.paste("a\tb\tc\n1\t2\t3\n")!!.name())
        b = reopen(s)
        assertEquals("c", b.cell(0, 5, 3).input); assertEquals(3.0, b.cell(0, 6, 3).number!!, 0.0)
    }

    // ---- s19 / d41 / p21 find and replace

    @Test fun sheetFindNextReplaceOneReplaceAllOneUndo() {
        val s = sheet("A1" to "apple", "A2" to "Apple pie", "B3" to "apple apple")
        val hits = s.findHits("apple", matchCase = false)
        assertEquals(4, hits.size)
        s.showHit(hits[1]); assertEquals(1, s.row); assertEquals(0, s.col)      // [Next] twice → the 2nd one
        assertTrue(s.replaceHit(hits[1], "apple", "pear", matchCase = false))
        assertEquals("pear pie", s.book.cell(0, 1, 0).input)
        assertEquals("apple", s.book.cell(0, 0, 0).input)                       // only that one
        assertEquals(1, s.findHits("apple", matchCase = true).count { it.b == 0 })
        val before = s.serialize()
        assertEquals(3, s.replaceAllHits("apple", "kiwi", matchCase = false))
        assertEquals("kiwi kiwi", s.book.cell(0, 2, 1).input)
        s.undo()                                                               // one undo brings all of them back
        assertEquals("apple apple", s.book.cell(0, 2, 1).input)
        assertEquals(XlsxWorkbook.open(before).cell(0, 0, 0).input, s.book.cell(0, 0, 0).input)
    }

    @Test fun docFindReplaceOneAndAll() {
        val d = DocSession(FileRef.Path(File("t.docx")), FileKind.DOC, DocxDocument.blank().save(), File("."))
        val a = d.insertAfter(-1, listOf(Span("Plan the plan")), ParaStyle.BODY)
        d.insertAfter(a, listOf(Span("A PLAN for later")), ParaStyle.BODY)
        assertEquals(3, d.findHits("plan", false).size)
        assertEquals(1, d.findHits("plan", true).size)
        val second = d.findHits("plan", false)[1]
        d.showHit(second); assertEquals(second, d.findHit)
        assertTrue(d.replaceHit(second, "plan", "map", false))
        assertTrue(d.plainText().contains("Plan the map"))
        assertEquals(2, d.replaceAllHits("plan", "goal", false))
        d.undo()
        assertTrue(d.plainText().contains("Plan the map") && d.plainText().contains("A PLAN for later"))
    }

    @Test fun slidesFindJumpsToTheBoxOnSlide3AndTheNotesOnSlide2() {
        val s = DeckSession(FileRef.Path(File("t.pptx")), PptxDeck.blank().save())
        s.addSlide(io.embodit.spatialoffice.core.pptx.SlideLayoutKind.BLANK)
        s.setNotes(1, "say budget here")
        s.addSlide(io.embodit.spatialoffice.core.pptx.SlideLayoutKind.BLANK)
        s.addTextBox("the budget slide")
        val box = s.selected
        s.current = 0
        val hits = s.findHits("budget", false)
        assertEquals(2, hits.size)
        s.showHit(hits[0]); assertEquals(1, s.current); assertEquals(-1, s.selected)       // notes of slide 2
        s.showHit(hits[1]); assertEquals(2, s.current); assertEquals(box, s.selected)      // the text box on slide 3, picked
        assertTrue(s.replaceHit(hits[0], "budget", "cost", false))
        assertEquals("say cost here", s.deck.slides[1].notes)
        assertEquals(1, s.replaceAllHits("budget", "cost", false))
        assertEquals("the cost slide", s.deck.slides[2].shapes.first { it.id == box }.paras.joinToString("\n") { it.text })
    }

    // ---- s20 widths

    @Test fun widenTwiceAndAutofitReadBackFromTheFile() {
        val s = sheet("B1" to "a rather long piece of text here")
        val w0 = s.book.colWidthChars(0, 1)
        s.select(0, 1); s.widen(2f); s.widen(2f)
        assertEquals(w0 + 4f, reopen(s).colWidthChars(0, 1), 0.01f)
        s.autoFit()
        assertTrue(reopen(s).colWidthChars(0, 1) >= 32f)
        s.taller(6f)
        assertEquals(21f, reopen(s).rowHeightPt(0, 0), 0.5f)
    }

    // ---- s21 sheets

    @Test fun newRenameCopyDeleteAndRefTurnsIntoRef() {
        val s = sheet("A1" to "5")
        s.addSheet(); assertEquals(1, s.sheet); assertEquals(2, s.sheetCount)
        s.setCell(0, 0, "7")
        assertEquals("taken", s.renameSheet(s.book.sheetNames[0].uppercase()))
        assertEquals("char", s.renameSheet("a/b"))
        assertNull(s.renameSheet("汇总")); assertEquals("汇总", s.book.sheetNames[1])
        s.sheet = 0; s.setCell(1, 0, "=汇总!A1+1")
        assertEquals(8.0, s.book.cell(0, 1, 0).number!!, 0.0)
        s.sheet = 1; s.copySheet(); assertEquals(3, s.sheetCount); assertEquals(2, s.sheet)
        s.sheet = 1; assertTrue(s.deleteSheet())
        val b = reopen(s)
        assertEquals(2, b.sheetNames.size)
        assertFalse("汇总" in b.sheetNames)
        assertEquals("5", b.cell(0, 0, 0).input)               // the other sheet's data unchanged
        assertTrue(b.cell(0, 1, 0).input.contains("#REF!"))
    }

    // ---- s22

    @Test fun centreWrapAndOneMoreDecimal() {
        val s = sheet("A1" to "3.5", "A2" to "long text")
        s.select(0, 0); s.extendTo(1, 0)
        s.setHAlign("center"); s.setWrap(true)
        s.select(0, 0); s.adjustDecimals(1)
        val b = reopen(s)
        assertEquals("center", b.cell(0, 0, 0).hAlign); assertTrue(b.cell(0, 1, 0).wrap)
        assertEquals("3.50", b.cell(0, 0, 0).display)
    }

    // ---- d27 drafts

    @Test fun recoverTakesTheDraftBackAsOneUndoStep() {
        val s = sheet("A1" to "1")
        val draft = sheet("A1" to "changed").serialize()
        assertTrue(s.recover(draft))
        assertTrue(s.dirty)
        assertEquals("changed", s.book.cell(0, 0, 0).input)
        s.undo()
        assertEquals("1", s.book.cell(0, 0, 0).input)
        assertFalse(s.recover(s.serialize()))                  // nothing different: nothing to take back
    }

    // ---- p19 / p20

    @Test fun copyPasteAcrossSlidesPickedAtOnceAndLayers() {
        val s = DeckSession(FileRef.Path(File("t.pptx")), PptxDeck.blank().save())
        s.addShape("ellipse", 0x2563EB)
        val src = s.selected
        assertTrue(s.copySelected())
        s.addSlide(io.embodit.spatialoffice.core.pptx.SlideLayoutKind.BLANK)
        val before = s.deck.slides[1].shapes.size
        assertTrue(s.pasteObject())
        val pasted = s.deck.slides[1].shapes
        assertEquals(before + 1, pasted.size)
        assertEquals(pasted.last().id, s.selected)              // picked at once (D57)
        assertEquals("ellipse", pasted.last().geom)
        assertEquals(pasted.size, pasted.map { it.id }.toSet().size)
        assertTrue(s.duplicateSelected())
        val dup = s.selected
        // two stacked shapes: the lower one to the front
        val lower = s.deck.slides[1].shapes.first { it.geom == "ellipse" }.id
        s.selected = lower
        assertTrue(s.canReorder(ZOrder.FRONT))
        assertTrue(s.reorder(ZOrder.FRONT))
        assertEquals(lower, s.deck.slides[1].shapes.last().id)
        assertFalse(s.canReorder(ZOrder.FORWARD))
        assertTrue(s.reorder(ZOrder.BACK)); assertEquals(lower, s.deck.slides[1].shapes.first().id)
        assertNotEquals(src, dup)
        val reread = PptxDeck.open(s.serialize())
        assertEquals(s.deck.slides[1].shapes.map { it.id }, reread.slides[1].shapes.map { it.id })
    }

    // ---- BACK / B (profile D60)

    @Test fun backNeverLeavesTheApp() {
        assertEquals(BackAct.CLOSE_OVERLAY, BackPolicy.decide(overlayOpen = true, stageUp = false, homeOpen = true))
        assertEquals(BackAct.REOPEN_HOME, BackPolicy.decide(overlayOpen = false, stageUp = false, homeOpen = false))
        assertEquals(BackAct.NOTHING, BackPolicy.decide(overlayOpen = false, stageUp = false, homeOpen = true))
        assertEquals(BackAct.NOTHING, BackPolicy.decide(overlayOpen = false, stageUp = true, homeOpen = false))
        var fired = 0
        BackKeys.handler = { fired++ }; BackKeys.reset()
        assertTrue(BackKeys.route(BackKeys.KEYCODE_BUTTON_B, 0, 100L, false, "t"))     // DOWN eaten, nothing yet
        assertTrue(BackKeys.route(BackKeys.KEYCODE_BUTTON_B, 1, 100L, false, "t"))
        assertTrue(BackKeys.route(BackKeys.KEYCODE_BACK, 1, 100L, false, "second layer"))   // same press seen twice
        assertEquals(1, fired)
        assertFalse(BackKeys.route(111, 1, 200L, false, "t"))                            // Escape stays the editors'
    }
}
