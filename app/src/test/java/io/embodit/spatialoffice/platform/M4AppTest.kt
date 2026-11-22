package io.embodit.spatialoffice.platform

import io.embodit.spatialoffice.core.docx.DocxDocument
import io.embodit.spatialoffice.core.docx.ParaStyle
import io.embodit.spatialoffice.core.docx.Paragraph
import io.embodit.spatialoffice.core.files.FileKind
import io.embodit.spatialoffice.core.layout.Arrange
import io.embodit.spatialoffice.core.pptx.PptxDeck
import io.embodit.spatialoffice.core.text.Span
import io.embodit.spatialoffice.core.xlsx.CellRect
import io.embodit.spatialoffice.core.xlsx.FilterRule
import io.embodit.spatialoffice.core.xlsx.XlsxWorkbook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipInputStream

/** v0.6.0 (M4) through the session calls the menus, dialogs and debug commands use; read back from the saved bytes. */
class M4AppTest {

    private fun part(bytes: ByteArray, name: String): String {
        ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
            while (true) { val e = z.nextEntry ?: break; if (e.name == name) return String(z.readBytes(), Charsets.UTF_8) }
        }
        error("no $name")
    }

    // ---- d23: from the middle of paragraph 2 to the middle of paragraph 4, copied, pasted at the end: bold and list kept

    private fun fourParas(): DocSession {
        val d = DocSession(FileRef.Path(File("t.docx")), FileKind.DOC, DocxDocument.blank().save(), File("build/tmp"))
        val p1 = d.blocks().first().id
        d.setParagraph(p1, listOf(Span("First paragraph.")), ParaStyle.BODY)
        val p2 = d.insertAfter(p1, listOf(Span("Second has "), Span("bold words", bold = true), Span(" in it.")), ParaStyle.BODY)
        val p3 = d.insertAfter(p2, listOf(Span("A list item")), ParaStyle.BULLET)
        d.insertAfter(p3, listOf(Span("Fourth "), Span("closes", italic = true), Span(" it.")), ParaStyle.BODY)
        return d
    }

    private fun paras(d: DocSession) = d.blocks().filterIsInstance<UiBlock.Para>()

    @Test fun crossParagraphCopyPasteKeepsBoldAndList() {
        val d = fourParas()
        val ps = paras(d)
        // "Second has " is 11 characters: the selection starts inside the bold run's paragraph, before "bold"
        d.crossSel = DocSel(ps[1].id, 4, ps[3].id, 9)
        val parts = d.selectedParts()
        assertEquals(listOf(4 to 28, 0 to 11, 0 to 9), parts.map { it.second to it.third })
        val clip = d.copyCross()!!
        assertEquals(3, clip.paras.size)
        assertEquals("nd has bold words in it.\nA list item\nFourth cl", clip.text)
        assertEquals(ParaStyle.BULLET, clip.paras[1].style)
        // paste at the end of the last paragraph
        val last = paras(d).last()
        val caret = d.pasteClip(clip, last.id, 17, 17)!!
        val after = paras(d)
        assertEquals(6, after.size)
        assertEquals("Fourth closes it.nd has bold words in it.", after[3].spans.joinToString("") { it.text })
        assertTrue("bold is kept", after[3].spans.any { it.bold && it.text == "bold words" })
        assertEquals(ParaStyle.BULLET, after[4].style)
        assertEquals("A list item", after[4].spans.joinToString("") { it.text })
        assertEquals("Fourth cl", after[5].spans.joinToString("") { it.text })
        assertEquals(after[5].id to 9, caret)
        // in the saved file: a list paragraph (numPr) and a bold run in the pasted part
        val doc = part(d.serialize(), "word/document.xml")
        val back = DocxDocument.open(d.serialize()).blocks.filterIsInstance<Paragraph>()
        assertEquals(ParaStyle.BULLET, back[4].style)
        assertTrue(back[3].runs.any { it.bold && it.text == "bold words" })
        assertTrue(doc.contains("<w:numPr>"))
        // one undo step back to before the paste
        d.undo(); assertEquals(4, paras(d).size)
    }

    @Test fun cutAcrossParagraphsJoinsTheEnds() {
        val d = fourParas()
        val ps = paras(d)
        d.crossSel = DocSel(ps[3].id, 7, ps[1].id, 4) // either order
        val caret = d.deleteCross()!!
        assertEquals(ps[1].id to 4, caret)
        assertEquals(listOf("First paragraph.", "Secocloses it."), paras(d).map { p -> p.spans.joinToString("") { it.text } })
        assertNull(d.crossSel)
    }

    @Test fun onePartPastesIntoTheParagraph() {
        val d = fourParas()
        val ps = paras(d)
        val clip = d.copyRange(ps[1].id, 11, 21)!!
        assertEquals("bold words", clip.text)
        d.pasteClip(clip, ps[0].id, 0, 0)
        val p0 = paras(d)[0]
        assertEquals("bold wordsFirst paragraph.", p0.spans.joinToString("") { it.text })
        assertTrue(p0.spans.first().bold)
    }

    // ---- d25

    @Test fun outlineListsHeadingsAndJumps() {
        val d = DocSession(FileRef.Path(File("t.docx")), FileKind.DOC, DocxDocument.blank().save(), File("build/tmp"))
        var at = d.blocks().first().id
        d.setParagraph(at, listOf(Span("报告")), ParaStyle.TITLE)
        for ((i, t) in listOf("第一章", "第二章", "第三章").withIndex()) {
            at = d.insertAfter(at, listOf(Span(t)), ParaStyle.H1)
            at = d.insertAfter(at, listOf(Span("正文 $i")), ParaStyle.BODY)
            if (i == 1) at = d.insertAfter(at, listOf(Span("2.1 小节")), ParaStyle.H2)
        }
        val o = d.outline()
        assertEquals(listOf("报告", "第一章", "第二章", "2.1 小节", "第三章"), o.map { it.text })
        assertEquals(listOf(0, 1, 1, 2, 1), o.map { it.level })
        val third = o.first { it.text == "第三章" }
        val tick = d.scrollTick
        d.jumpTo(third.id)
        assertEquals(third.id, d.scrollTarget); assertEquals(tick + 1, d.scrollTick); assertEquals(third.id, d.caretBlock)
    }

    // ---- s29 / s30 / s31 / s42

    private fun sales(): SheetSession {
        val b = XlsxWorkbook.blank()
        val rows = listOf(listOf("部门", "姓名", "金额"), listOf("销售", "张三", "1200"), listOf("研发", "李四", "800"), listOf("销售", "王五", "3000"), listOf("研发", "赵六", "1500"))
        for ((r, line) in rows.withIndex()) for ((c, v) in line.withIndex()) b.setCell(0, r, c, v)
        return SheetSession(FileRef.Path(File("t.xlsx")), FileKind.SHEET, b.save())
    }

    @Test fun filterSortFreezeHide() {
        val s = sales()
        s.applyFilter(FilterRule(2, FilterRule.Op.GT, "1000"))
        assertEquals(listOf(0, 1, 3, 4), s.visibleRows(5))
        s.applyFilter(null)
        // sort 部门 → 金额 (descending) on the block around B2, with the header kept
        s.select(1, 1)
        val block = s.sortBlock()!!
        assertEquals(CellRect(0, 0, 4, 2), block)
        assertTrue(s.blockHasHeader(block))
        assertTrue(s.sortBy(block, listOf(0 to true, 2 to false), header = true))
        assertEquals(listOf("部门", "销售", "销售", "研发", "研发"), (0..4).map { s.book.cell(0, it, 0).display })
        assertEquals(listOf("金额", "3000", "1200", "1500", "800"), (0..4).map { s.book.cell(0, it, 2).display })
        // freeze at B2, hide column C
        s.select(1, 1); assertEquals(1 to 1, s.freezeHere())
        s.select(0, 2); assertEquals(1, s.hide(cols = true))
        assertEquals(setOf(2), s.hiddenCols())
        val x = part(s.serialize(), "xl/worksheets/sheet1.xml")
        assertTrue(x, x.contains("xSplit=\"1\" ySplit=\"1\" topLeftCell=\"B2\""))
        assertTrue(x, Regex("<col [^>]*min=\"3\" max=\"3\"[^>]*hidden=\"1\"").containsMatchIn(x))
        // shown again, one undo step each
        assertTrue(s.unhide() > 0); assertTrue(s.hiddenCols().isEmpty())
        s.undo(); assertEquals(setOf(2), s.hiddenCols())
        // hidden rows leave the grid
        s.select(2, 0); s.hide(cols = false)
        assertFalse(2 in s.visibleRows(5))
    }

    // ---- p29 / p25

    @Test fun threeShapesPickedByMenuAlignedDistributedGroupedAndDraggedTogether() {
        val s = DeckSession(FileRef.Path(File("t.pptx")), PptxDeck.blank().save())
        val ids = (0..2).map { i -> s.addShape("rect", 0x2563EB); s.nudge(i - 1, i - 1); s.selected }
        // [Object ▸ Add to selection] three times, the way the menu does it (pick, add, pick the next …)
        for (id in ids) { s.selected = id; assertTrue(s.addToMulti()) }
        assertEquals(ids, s.multiSet())
        assertTrue(s.align(Arrange.Align.LEFT))
        s.distribute(true)
        val xs = ids.map { id -> s.deck.slides[0].shapes.first { it.id == id }.x }
        assertEquals(1, xs.distinct().size)
        val g = s.group()
        assertTrue(g > 0); assertEquals(g, s.selected); assertTrue(s.multi.isEmpty())
        val before = s.deck.slides[0].shapes.first { it.id == g }
        s.snap = false
        assertTrue(s.beginBoxDrag(g)); s.moveBoxBy(400_000, 100_000); assertTrue(s.endBoxDrag())
        val moved = s.deck.slides[0].shapes.first { it.id == g }
        assertEquals(before.x + 400_000, moved.x)
        assertEquals(listOf(moved.x, moved.x, moved.x), moved.children!!.map { it.x })
        val x = part(s.serialize(), "ppt/slides/slide1.xml")
        val grp = Regex("<p:grpSp>.*</p:grpSp>").find(x)!!.value
        val childOffs = Regex("<p:sp[ >].*?</p:sp>").findAll(grp).map { Regex("<a:off x=\"(\\d+)\"").find(it.value)!!.groupValues[1] }.toList()
        assertEquals(3, childOffs.size); assertEquals(1, childOffs.distinct().size)
        // and apart again
        assertTrue(s.ungroup()); assertEquals(ids.toSet(), s.multiSet().toSet())
    }

    @Test fun aDraggedShapeSnapsToTheOthersAndTheGuidesGoAwayOnRelease() {
        val s = DeckSession(FileRef.Path(File("t.pptx")), PptxDeck.blank().save())
        s.addShape("rect", 0x2563EB); val a = s.selected
        s.nudge(-4, -4)
        s.addShape("ellipse", 0x2563EB); val b = s.selected
        val ab = s.deck.slides[0].shapes.first { it.id == a }
        val bb = s.deck.slides[0].shapes.first { it.id == b }
        assertTrue(s.beginBoxDrag(b))
        // to 30 000 EMU right of a's left edge: snaps onto it
        s.moveBoxBy(ab.x - bb.x + 30_000, 1_000_000)
        assertEquals(ab.x, s.dragBox!!.second.x)
        assertTrue(s.guides.any { it.vertical && it.at == ab.x })
        assertTrue(s.endBoxDrag())
        assertTrue(s.guides.isEmpty())
        assertEquals(ab.x, s.deck.slides[0].shapes.first { it.id == b }.x)
    }
}
