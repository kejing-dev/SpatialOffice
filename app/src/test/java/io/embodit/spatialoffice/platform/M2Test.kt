package io.embodit.spatialoffice.platform

import io.embodit.spatialoffice.core.docx.DocxDocument
import io.embodit.spatialoffice.core.docx.ParaStyle
import io.embodit.spatialoffice.core.docx.Paragraph
import io.embodit.spatialoffice.core.files.FileKind
import io.embodit.spatialoffice.core.pptx.PptxDeck
import io.embodit.spatialoffice.core.text.Attr
import io.embodit.spatialoffice.core.text.FontSizes
import io.embodit.spatialoffice.core.text.RichText
import io.embodit.spatialoffice.core.text.Span
import io.embodit.spatialoffice.core.xlsx.XlsxWorkbook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipInputStream

/**
 * v0.4.0 (M2) through the same session calls the menus, keys and debug commands use: the QA acceptance points of
 * design-package roadmap[1], read back from the saved bytes.
 */
class M2Test {

    private fun part(bytes: ByteArray, name: String): String {
        ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
            while (true) { val e = z.nextEntry ?: break; if (e.name == name) return String(z.readBytes(), Charsets.UTF_8) }
        }
        error("no $name")
    }

    private fun doc(vararg paras: Pair<ParaStyle, String>): DocSession {
        val d = DocSession(FileRef.Path(File("t.docx")), FileKind.DOC, DocxDocument.blank().save(), File("build/tmp"))
        val first = d.blocks().first().id
        d.setParagraph(first, listOf(Span(paras[0].second)), paras[0].first)
        var at = first
        for (p in paras.drop(1)) at = d.insertAfter(at, listOf(Span(p.second)), p.first)
        return d
    }

    private fun paras(d: DocSession) = d.blocks().filterIsInstance<UiBlock.Para>()

    // ---- d15 / d16 / d18

    @Test fun redHighlightAndTwoSizesUpOnSelectedWordsThenClear() {
        val d = doc(ParaStyle.BODY to "Plain then marked words end", ParaStyle.BODY to "Another paragraph stays")
        val saved = d.serialize()
        val otherBefore = Regex("<w:p>(?:(?!<w:p>).)*?Another paragraph stays.*?</w:p>").find(part(saved, "word/document.xml"))!!.value
        val p = paras(d)[0]
        val a = "Plain then ".length; val b = a + "marked words".length
        assertTrue(d.formatRange(p.id, a, b) { sp, x, y, _ -> RichText.setColor(sp, x, y, 0xC00000) })
        assertTrue(d.formatRange(p.id, a, b) { sp, x, y, _ -> RichText.setHighlight(sp, x, y, "yellow") })
        repeat(2) { assertTrue(d.formatRange(p.id, a, b) { sp, x, y, pp -> RichText.stepSize(sp, x, y, 1, pp.baseSizeHp) }) }
        val xml = part(d.serialize(), "word/document.xml")
        val reopened = DocxDocument.open(d.serialize()).blocks.filterIsInstance<Paragraph>()[0]
        val marked = reopened.runs.first { it.text == "marked words" }
        assertEquals(0xC00000, marked.color)
        assertEquals("yellow", marked.highlight)
        assertEquals(FontSizes.step(reopened.baseSizeHp, 2), marked.size)
        assertTrue(xml.contains("<w:color w:val=\"C00000\"/>") && xml.contains("<w:highlight w:val=\"yellow\"/>"))
        assertNull(reopened.runs.first { it.text.startsWith("Plain") }.color)
        assertTrue("the other paragraph is byte for byte as it was", xml.contains(otherBefore))
        // clear formatting
        assertTrue(d.formatRange(p.id, a, b) { sp, x, y, _ -> RichText.clearFormat(sp, x, y) })
        val xml2 = part(d.serialize(), "word/document.xml")
        assertFalse(xml2.contains("<w:color ") || xml2.contains("<w:highlight ") || xml2.contains("<w:sz "))
    }

    // ---- d17 / d19

    @Test fun tabTabShiftTabLeavesLevelOneAndLineSpacingOnePointFive() {
        val d = doc(ParaStyle.NUMBERED to "first", ParaStyle.NUMBERED to "second", ParaStyle.BODY to "after")
        val p = paras(d)[1]
        // Tab, Tab, Shift+Tab: what the key handler and the debug command call
        assertTrue(d.setListLevel(p.id, paras(d)[1].level + 1))
        assertTrue(d.setListLevel(p.id, paras(d)[1].level + 1))
        assertTrue(d.setListLevel(p.id, paras(d)[1].level - 1))
        assertEquals(1, paras(d)[1].level)
        assertEquals(ParaStyle.NUMBERED, paras(d)[1].style)
        assertTrue(d.setLineSpacing(p.id, 360))
        val xml = part(d.serialize(), "word/document.xml")
        assertTrue(xml.contains("<w:ilvl w:val=\"1\"/>"))
        assertTrue(Regex("<w:spacing [^>]*w:line=\"360\"").containsMatchIn(xml))
        val back = DocxDocument.open(d.serialize()).blocks.filterIsInstance<Paragraph>()
        assertEquals(1, back[1].level); assertEquals(360, back[1].lineSpacing)
        // a body paragraph: indent by one step and back, space around it
        val body = paras(d)[2]
        assertTrue(d.indent(body.id, true)); assertEquals(INDENT_STEP_TW, paras(d)[2].indentTw)
        assertTrue(d.indent(body.id, false)); assertEquals(0, paras(d)[2].indentTw)
        assertFalse(d.indent(body.id, false))
        assertTrue(d.setParaSpacing(body.id, 120, 120)); assertEquals(120, paras(d)[2].spaceAfter)
    }

    // ---- s23

    @Test fun selectedCellsItalicAndRed() {
        val b = XlsxWorkbook.blank(); b.setCell(0, 0, 0, "a"); b.setCell(0, 1, 1, "b"); b.setCell(0, 3, 3, "out")
        val s = SheetSession(FileRef.Path(File("t.xlsx")), FileKind.SHEET, b.save())
        s.select(0, 0); s.extendTo(1, 1)
        s.setItalic(true); s.setFontColor(0xFF0000)
        val back = XlsxWorkbook.open(s.serialize())
        for (r in 0..1) for (c in 0..1) { assertTrue(back.cell(0, r, c).italic); assertEquals(0xFF0000, back.cell(0, r, c).colorRgb) }
        assertFalse(back.cell(0, 3, 3).italic)
        val styles = part(s.serialize(), "xl/styles.xml")
        assertTrue(styles.contains("<i/>") && styles.contains("FFFF0000"))
        s.stepFontSize(1); assertTrue(XlsxWorkbook.open(s.serialize()).cell(0, 0, 0).fontPt!! > back.defaultFontPt())
        s.clearFont(); val c = XlsxWorkbook.open(s.serialize()).cell(0, 1, 1)
        assertFalse(c.italic); assertNull(c.colorRgb)
    }

    // ---- p17 / p18 / p22 / p24

    private fun deckWithBox(text: String): DeckSession {
        val s = DeckSession(FileRef.Path(File("t.pptx")), PptxDeck.blank().save())
        s.addTextBox(text)
        return s
    }

    @Test fun middleWordsBoldBlue24CentredBulletedOthersUntouched() {
        val s = deckWithBox("Before middle after")
        val id = s.selected
        val a = "Before ".length; val b = a + "middle".length
        s.textSel = DeckSession.TextSel(s.current, id, a, b)
        assertTrue(io.embodit.spatialoffice.ui.slides.slideToggle(s, Attr.BOLD))
        assertTrue(s.formatText { sp, x, y -> RichText.setColor(sp, x, y, 0x0070C0) })
        assertTrue(s.formatText { sp, x, y -> RichText.setSize(sp, x, y, 48) })
        assertTrue(s.setParaFormat(align = 1)); assertTrue(s.setParaFormat(bullet = true))
        val back = PptxDeck.open(s.serialize()).slides[0].shapes.first { it.id == id }
        val runs = back.paras[0].runs!!
        val mid = runs.first { it.text == "middle" }
        assertTrue(mid.bold); assertEquals(0x0070C0, mid.color); assertEquals(48, mid.size)
        runs.filter { it.text != "middle" }.forEach { assertFalse(it.bold); assertTrue(it.size != 48) }
        assertEquals(1, back.paras[0].align); assertTrue(back.paras[0].bullet)
        val xml = part(s.serialize(), "ppt/slides/slide1.xml")
        assertTrue(xml.contains("algn=\"ctr\"") && xml.contains("<a:buChar") && xml.contains("0070C0") && xml.contains("sz=\"2400\""))
    }

    @Test fun typingIntoMixedTextKeepsTheOtherRunsFormat() {
        val s = deckWithBox("Bold plain")
        val id = s.selected
        s.textSel = DeckSession.TextSel(s.current, id, 0, 4)
        assertTrue(io.embodit.spatialoffice.ui.slides.slideToggle(s, Attr.BOLD))
        // reopen as from disk, then type in the plain run as the text field reports it
        val r = DeckSession(FileRef.Path(File("t.pptx")), s.serialize()); r.selected = id
        r.setText(0, id, "Bold plainer")
        val runs = PptxDeck.open(r.serialize()).slides[0].shapes.first { it.id == id }.paras[0].runs!!
        assertEquals("Bold plainer", runs.joinToString("") { it.text })
        assertTrue(runs.first { it.text.startsWith("Bold") }.bold)
        assertFalse(runs.last().bold)
        // a find / replace in the plain part keeps the bold run too
        r.replaceAllHits("plainer", "text", matchCase = true)
        val runs2 = PptxDeck.open(r.serialize()).slides[0].shapes.first { it.id == id }.paras[0].runs!!
        assertTrue(runs2.first().bold && runs2.first().text.startsWith("Bold"))
    }

    @Test fun fillAndRotationReadBack() {
        val s = DeckSession(FileRef.Path(File("t.pptx")), PptxDeck.blank().save())
        s.addShape("rect", 0x2563EB)
        val id = s.selected
        assertTrue(s.setFill(0xDC2626))
        assertTrue(s.rotateBy(90))
        val back = PptxDeck.open(s.serialize()).slides[0].shapes.first { it.id == id }
        assertEquals(0xDC2626, back.fillRgb); assertEquals(90, back.rotation)
        assertTrue(part(s.serialize(), "ppt/slides/slide1.xml").contains("rot=\"5400000\""))
        // the handle: press, drag to 97° (snaps to 90 … here 105 from 97+8), release
        assertTrue(s.beginRotate(id)); s.rotateTo(io.embodit.spatialoffice.core.layout.Rotation.snap(187f)); assertTrue(s.endRotate())
        assertEquals(180, PptxDeck.open(s.serialize()).slides[0].shapes.first { it.id == id }.rotation)
        assertTrue(s.setLine(0x111111, 28575L))
        val l = PptxDeck.open(s.serialize()).slides[0].shapes.first { it.id == id }
        assertEquals(0x111111, l.lineRgb); assertEquals(28575L, l.lineWidthEmu)
        // undo takes the line back off
        s.undo(); assertNull(PptxDeck.open(s.serialize()).slides[0].shapes.first { it.id == id }.lineRgb)
    }

    // ---- d28

    @Test fun threeSavesThreeVersionsRestoreTheFirstKeepsTheCurrent() {
        val dir = Files.createTempDirectory("hist").toFile()
        val h = History(dir)
        val d = doc(ParaStyle.BODY to "v1")
        val key = d.ref.key
        val texts = listOf("v1", "v2", "v3")
        for ((i, t) in texts.withIndex()) {
            d.setParagraph(paras(d)[0].id, listOf(Span(t)), ParaStyle.BODY)
            h.add(key, d.serialize(), now = 1000L + i); d.saved()
        }
        h.add(key, d.serialize(), now = 2000L)                       // saved again unchanged: not kept twice
        assertEquals(3, h.list(key).size)
        val first = h.list(key).last()
        assertTrue(h.restore(d, first))
        assertEquals("v1", RichText.text(paras(d)[0].spans))
        assertTrue(d.dirty)
        val list = h.list(key)
        assertEquals(4, list.size)
        assertEquals(History.KIND_BEFORE_RESTORE, list.first().kind)
        assertEquals("v3", DocxDocument.open(h.read(list.first())!!).blocks.filterIsInstance<Paragraph>()[0].text)
        // undo returns to v3
        d.undo(); assertEquals("v3", RichText.text(paras(d)[0].spans))
        // never more than KEEP
        repeat(15) { i -> h.add(key, ("x$i").toByteArray(), now = 3000L + i) }
        assertEquals(History.KEEP, h.list(key).size)
        dir.deleteRecursively()
    }
}
