package io.embodit.spatialoffice.core.docx

import io.embodit.spatialoffice.core.docx.PackageAsserts.assertConsistent
import io.embodit.spatialoffice.core.docx.PackageAsserts.assertOnlyRangeChanged
import io.embodit.spatialoffice.core.docx.PackageAsserts.changedParts
import io.embodit.spatialoffice.core.docx.PackageAsserts.fixture
import io.embodit.spatialoffice.core.docx.PackageAsserts.text
import io.embodit.spatialoffice.core.docx.PackageAsserts.writeRoundTrip
import io.embodit.spatialoffice.core.pkg.Xml
import io.embodit.spatialoffice.core.text.FontSizes
import io.embodit.spatialoffice.core.text.RichText
import io.embodit.spatialoffice.core.text.Span
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** M2 (v0.4.0): run colour / highlight / size (d15, d16, d18), list levels and indent (d17), line and paragraph spacing (d19). */
class DocxM2Test {
    private val doc = "word/document.xml"
    private val src: ByteArray = fixture("docx-report.docx")

    private fun para(d: DocxDocument, id: Int) = d.blocks.first { it.id == id } as Paragraph

    private fun bodyChild(xml: String, n: Int): Xml.Elem {
        val body = Xml.child(xml, Xml.root(xml)!!, "w:body")!!
        return Xml.children(xml, body)[n]
    }

    // the app's mapping between the two models
    private fun spans(p: Paragraph) = p.runs.map { Span(it.text, it.bold, it.italic, it.underline, it.src, it.strike, it.color, it.highlight, it.size) }
    private fun runs(spans: List<Span>) = spans.map { Run(it.text, it.bold, it.italic, it.underline, it.src, it.strike, it.color, it.highlight, it.size) }

    private fun expectIllegal(block: () -> Unit) {
        try { block(); fail("expected IllegalStateException") } catch (e: IllegalStateException) { /* expected */ }
    }

    @Test fun readsDirectColourAndSizeOfRuns() {
        val d = DocxDocument.open(src)
        val mixed = para(d, 2)
        assertEquals(0xC0392B, mixed.runs[1].color)
        assertEquals(28, mixed.runs[1].size)
        assertNull(mixed.runs[0].color); assertNull(mixed.runs[0].size); assertNull(mixed.runs[0].highlight)
        // docDefaults w:sz 22, no size on Normal
        assertEquals(22, para(d, 13).baseSizeHp)
    }

    @Test fun colourHighlightAndSizeOnSelectedCharactersThenClearFormat() {
        val d = DocxDocument.open(src)
        val before = text(src, doc)
        val p = para(d, 13)
        val len = p.text.length
        assertTrue(len > 6)
        var sp = spans(p)
        sp = RichText.setColor(sp, 2, 5, 0xFF0000)
        sp = RichText.setHighlight(sp, 2, 5, "yellow")
        sp = RichText.stepSize(sp, 2, 5, 2, p.baseSizeHp)
        val big = FontSizes.step(p.baseSizeHp, 2)
        assertEquals(28, big)
        d.setParagraph(13, runs(sp), p.style)
        val out = d.save()
        assertEquals(setOf(doc), changedParts(src, out))
        assertConsistent(out)
        writeRoundTrip("docx-m2-format.docx", out)
        val el = bodyChild(before, 13)
        val seg = assertOnlyRangeChanged(before, text(out, doc), el.start, el.end)
        assertTrue(seg, seg.contains("<w:rPr><w:color w:val=\"FF0000\"/><w:sz w:val=\"28\"/><w:szCs w:val=\"28\"/><w:highlight w:val=\"yellow\"/></w:rPr>"))
        assertEquals(3, Regex("<w:r>").findAll(seg).count())

        val re = DocxDocument.open(out)
        val rp = para(re, 13)
        assertEquals(p.text, rp.text)
        assertEquals(listOf(null, 0xFF0000, null), rp.runs.map { it.color })
        assertEquals(listOf(null, "yellow", null), rp.runs.map { it.highlight })
        assertEquals(listOf(null, 28, null), rp.runs.map { it.size })
        assertEquals(p.text.substring(2, 5), rp.runs[1].text)
        // every other block reads the same
        assertEquals(d.blocks.filter { it.id != 13 }, re.blocks.filter { it.id != 13 })

        // d18 clear format in the same session: the paragraph is written exactly as the file had it
        d.setParagraph(13, runs(RichText.clearFormat(spans(para(d, 13)), 0, len)), p.style)
        assertEquals(before, text(d.save(), doc))

        // and on the reopened file (three runs from three sources): no colour, highlight or size left
        re.setParagraph(13, runs(RichText.clearFormat(spans(rp), 0, len)), rp.style)
        val out2 = re.save()
        val seg2 = assertOnlyRangeChanged(before, text(out2, doc), el.start, el.end)
        assertFalse(seg2, seg2.contains("w:color") || seg2.contains("w:highlight") || seg2.contains("w:sz"))
        val re2 = DocxDocument.open(out2)
        assertTrue(para(re2, 13).runs.all { it.color == null && it.highlight == null && it.size == null })
    }

    @Test fun clearFormatRemovesSizeAndColourTheFileHad() {
        val d = DocxDocument.open(src)
        val p = para(d, 2)
        d.setParagraph(2, runs(RichText.clearFormat(spans(p), 0, p.text.length)), p.style)
        val x = text(d.save(), doc)
        val font = "<w:rFonts w:ascii=\"Fixture Sans\" w:hAnsi=\"Fixture Sans\"/>"
        // the font (not a format the menu sets) stays; bold, colour and size go
        assertTrue(x.contains("<w:r><w:rPr>$font</w:rPr><w:t>18%</w:t></w:r>"))
        val re = DocxDocument.open(d.save())
        assertTrue(para(re, 2).runs.all { !it.bold && !it.italic && it.color == null && it.size == null })
    }

    @Test fun untouchedRunsKeepTheirPropertiesByteForByte() {
        val d = DocxDocument.open(src)
        val before = text(src, doc)
        val p = para(d, 2)
        // colour only the first run; the run with colour / size / font is written as it was
        d.setParagraph(2, runs(RichText.setColor(spans(p), 0, 3, 0x0070C0)), p.style)
        val x = text(d.save(), doc)
        assertTrue(x.contains("<w:r><w:rPr><w:rFonts w:ascii=\"Fixture Sans\" w:hAnsi=\"Fixture Sans\"/><w:b/><w:color w:val=\"C0392B\"/><w:sz w:val=\"28\"/></w:rPr><w:t>18%</w:t></w:r>"))
        assertTrue(x.contains("<w:r><w:rPr><w:color w:val=\"0070C0\"/></w:rPr><w:t>Rev</w:t></w:r>"))
        val el = bodyChild(before, 2)
        assertOnlyRangeChanged(before, x, el.start, el.end)
    }

    @Test fun numberedListLevelTabTabShiftTab() {
        val d = DocxDocument.open(src)
        val before = text(src, doc)
        assertEquals(ParaStyle.NUMBERED, para(d, 10).style)
        assertEquals(0, para(d, 10).level)
        d.setListLevel(10, para(d, 10).level + 1)
        d.setListLevel(10, para(d, 10).level + 1)
        d.setListLevel(10, para(d, 10).level - 1)
        assertEquals(1, para(d, 10).level)
        assertEquals(ParaStyle.NUMBERED, para(d, 10).style)
        val out = d.save()
        assertEquals(setOf(doc), changedParts(src, out))
        val el = bodyChild(before, 10)
        val seg = assertOnlyRangeChanged(before, text(out, doc), el.start, el.end)
        assertEquals("<w:p><w:pPr><w:pStyle w:val=\"ListNumber\"/><w:numPr><w:ilvl w:val=\"1\"/><w:numId w:val=\"5\"/></w:numPr></w:pPr><w:r><w:t>Hire two engineers</w:t></w:r></w:p>", seg)
        val re = DocxDocument.open(out)
        assertEquals(1, para(re, 10).level)
        assertEquals(ParaStyle.NUMBERED, para(re, 10).style)
        assertEquals(0, para(re, 9).level)
        // back to level 0 keeps the direct numPr (ilvl 0)
        re.setListLevel(10, 0)
        assertEquals(0, para(re, 10).level)
        expectIllegal { d.setListLevel(13, 1) }   // body text is not a list
    }

    @Test fun ownBulletListHasNineLevels() {
        val d = DocxDocument.blank()
        val id = d.blocks.first().id
        d.setParagraph(id, listOf(Run("item")), ParaStyle.BULLET)
        d.setListLevel(id, 4)
        d.setListLevel(id, 12)                  // clamped
        assertEquals(8, para(d, id).level)
        d.setListLevel(id, 4)
        val out = d.save()
        assertConsistent(out)
        val nx = text(out, "word/numbering.xml")
        for (l in 0..8) assertTrue("level $l", nx.contains("<w:lvl w:ilvl=\"$l\">"))
        val re = DocxDocument.open(out)
        assertEquals(4, para(re, id).level)
        assertEquals(ParaStyle.BULLET, para(re, id).style)
        assertTrue(text(out, doc).contains("<w:ilvl w:val=\"4\"/>"))
    }

    @Test fun lineSpacingParagraphSpacingAndIndentRoundTrip() {
        val d = DocxDocument.open(src)
        val before = text(src, doc)
        val el = bodyChild(before, 13)
        val orig = before.substring(el.start, el.end)
        d.setLineSpacing(13, 360)
        d.setParaSpacing(13, 120, 240)
        d.setIndent(13, 720)
        val out = d.save()
        val seg = assertOnlyRangeChanged(before, text(out, doc), el.start, el.end)
        assertTrue(seg, seg.startsWith("<w:p><w:pPr><w:spacing w:line=\"360\" w:lineRule=\"auto\" w:before=\"120\" w:after=\"240\"/><w:ind w:left=\"720\"/></w:pPr><w:r>"))
        assertEquals(orig.substring("<w:p>".length), seg.substring(seg.indexOf("</w:pPr>") + "</w:pPr>".length))
        val re = DocxDocument.open(out)
        val p = para(re, 13)
        assertEquals(360, p.lineSpacing); assertEquals(120, p.spaceBefore); assertEquals(240, p.spaceAfter); assertEquals(720, p.indentTw)

        // removing them all gives the paragraph back as the file had it
        re.setLineSpacing(13, null)
        re.setParaSpacing(13, null, null)
        re.setIndent(13, 0)
        assertEquals(before, text(re.save(), doc))
        val p2 = para(re, 13)
        assertNull(p2.lineSpacing); assertNull(p2.spaceBefore); assertNull(p2.spaceAfter); assertEquals(0, p2.indentTw)
    }

    @Test fun pPrEditsKeepOtherAttributesAndEditedTextSurvives() {
        val d = DocxDocument.open(src)
        val p = para(d, 13)
        d.setLineSpacing(13, 480)
        // a later text edit keeps the paragraph properties and the model keeps the spacing
        d.setParagraph(13, runs(RichText.applyEdit(spans(para(d, 13)), p.text + "!")), p.style)
        assertEquals(480, para(d, 13).lineSpacing)
        d.setLineSpacing(13, 276)
        val x = text(d.save(), doc)
        assertTrue(x.contains("<w:spacing w:line=\"276\" w:lineRule=\"auto\"/>"))
        assertEquals(276, para(DocxDocument.open(d.save()), 13).lineSpacing)
        expectIllegal { d.setIndent(20, 720) }      // read-only paragraph (a field)
        expectIllegal { d.setLineSpacing(14, 360) } // a table
    }
}
