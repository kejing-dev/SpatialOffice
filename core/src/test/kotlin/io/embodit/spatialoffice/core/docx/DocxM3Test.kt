package io.embodit.spatialoffice.core.docx

import io.embodit.spatialoffice.core.docx.PackageAsserts.assertConsistent
import io.embodit.spatialoffice.core.docx.PackageAsserts.assertOnlyRangeChanged
import io.embodit.spatialoffice.core.docx.PackageAsserts.changedParts
import io.embodit.spatialoffice.core.docx.PackageAsserts.fixture
import io.embodit.spatialoffice.core.docx.PackageAsserts.text
import io.embodit.spatialoffice.core.docx.PackageAsserts.writeRoundTrip
import io.embodit.spatialoffice.core.pkg.OoxmlPackage
import io.embodit.spatialoffice.core.pkg.Rels
import io.embodit.spatialoffice.core.pkg.Xml
import io.embodit.spatialoffice.core.text.RichText
import io.embodit.spatialoffice.core.text.Span
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** M3 (v0.5.0): hyperlinks (d20), horizontal line / page break (d21), superscript / subscript (d22), table rows / columns / merge (d42). */
class DocxM3Test {
    private val doc = "word/document.xml"
    private val src: ByteArray = fixture("docx-report.docx")

    private fun para(d: DocxDocument, id: Int) = d.blocks.first { it.id == id } as Paragraph
    private fun table(d: DocxDocument, id: Int) = d.blocks.first { it.id == id } as TableBlock

    private fun bodyChild(xml: String, n: Int): Xml.Elem {
        val body = Xml.child(xml, Xml.root(xml)!!, "w:body")!!
        return Xml.children(xml, body)[n]
    }

    // the app's mapping between the two models (as Sessions.kt does it)
    private fun spans(p: Paragraph) = p.runs.map { Span(it.text, it.bold, it.italic, it.underline, it.src, it.strike, it.color, it.highlight, it.size, it.link, it.vertAlign) }
    private fun runs(spans: List<Span>) = spans.map { Run(it.text, it.bold, it.italic, it.underline, it.src, it.strike, it.color, it.highlight, it.size, it.link, it.vertAlign) }

    @Test fun linkIsWrittenAsHyperlinkWithExternalRelationshipAndReadsBack() {
        val d = DocxDocument.open(src)
        val before = text(src, doc)
        val p = para(d, 13)
        val url = "https://example.com/report?a=1&b=2"
        d.setParagraph(13, runs(RichText.setLink(spans(p), 0, 4, url)), p.style)
        val out = d.save()
        // only the document part and its relationships change
        assertEquals(setOf(doc, "word/_rels/document.xml.rels"), changedParts(src, out))
        assertConsistent(out)
        writeRoundTrip("docx-m3-link.docx", out)
        val el = bodyChild(before, 13)
        val seg = assertOnlyRangeChanged(before, text(out, doc), el.start, el.end)
        val rel = Rels.of(OoxmlPackage.read(out), doc).single { it.target == url }
        assertTrue(rel.external)
        assertTrue(rel.type.endsWith("/hyperlink"))
        assertTrue(text(out, "word/_rels/document.xml.rels").contains("TargetMode=\"External\""))
        assertTrue(seg, seg.contains("<w:hyperlink r:id=\"${rel.id}\" w:history=\"1\"><w:r><w:rPr><w:color w:val=\"0563C1\"/><w:u w:val=\"single\"/></w:rPr><w:t>${p.text.substring(0, 4)}</w:t></w:r></w:hyperlink>"))

        val re = DocxDocument.open(out)
        val rp = para(re, 13)
        assertTrue("a paragraph with a plain link stays editable", rp.editable)
        assertEquals(p.text, rp.text)
        assertEquals(url, rp.runs[0].link)
        assertNull(rp.runs.last().link)
        assertEquals(listOf(Triple(url, 0, 4)), RichText.links(spans(rp)))
        assertEquals(Triple(url, 0, 4), RichText.linkAt(spans(rp), 2))
        assertNull(RichText.linkAt(spans(rp), 6))
    }

    @Test fun editAndRemoveLink() {
        val d = DocxDocument.open(src)
        val before = text(src, doc)
        val p = para(d, 13)
        d.setParagraph(13, runs(RichText.setLink(spans(p), 0, 4, "https://a.example")), p.style)
        val re = DocxDocument.open(d.save())
        val linked = para(re, 13)
        // edit: same characters, new address; the same URL twice reuses one relationship
        re.setParagraph(13, runs(RichText.setLink(spans(linked), 0, 4, "https://b.example")), linked.style)
        re.setParagraph(13, runs(RichText.setLink(spans(para(re, 13)), 0, 4, "https://b.example")), linked.style)
        val out = re.save()
        val rels = Rels.of(OoxmlPackage.read(out), doc).filter { it.external }
        assertEquals(1, rels.count { it.target == "https://b.example" })
        assertEquals("https://b.example", para(DocxDocument.open(out), 13).runs[0].link)
        // remove on the reopened file: no hyperlink left, the text reads the same
        val re2 = DocxDocument.open(out)
        val l2 = para(re2, 13)
        re2.setParagraph(13, runs(RichText.setLink(spans(l2), 0, 4, null)), l2.style)
        val out2 = re2.save()
        assertFalse(text(out2, doc).contains("w:hyperlink"))
        val r2 = para(DocxDocument.open(out2), 13)
        assertEquals(p.text, r2.text)
        assertTrue(r2.runs.all { it.link == null && !it.underline && it.color == null })
        // link then unlink in one session: the paragraph is written exactly as the file had it
        val d3 = DocxDocument.open(src)
        val p3 = para(d3, 13)
        d3.setParagraph(13, runs(RichText.setLink(spans(p3), 0, 4, "https://c.example")), p3.style)
        d3.setParagraph(13, runs(RichText.setLink(spans(para(d3, 13)), 0, 4, null)), p3.style)
        assertEquals(before, text(d3.save(), doc))
    }

    @Test fun ruleAndPageBreakParagraphs() {
        val d = DocxDocument.open(src)
        val before = text(src, doc)
        val rule = d.insertRuleAfter(13)
        val pb = d.insertPageBreakAfter(rule)
        assertTrue(para(d, rule).rule)
        assertTrue(para(d, pb).pageBreak)
        val out = d.save()
        assertEquals(setOf(doc), changedParts(src, out))
        assertConsistent(out)
        val x = text(out, doc)
        val el = bodyChild(before, 13)
        // both go in right after paragraph 13; everything before and after is untouched
        val ins = assertOnlyRangeChanged(before, x, el.end, el.end)
        assertEquals("<w:p><w:pPr><w:pBdr><w:bottom w:val=\"single\" w:sz=\"6\" w:space=\"1\" w:color=\"auto\"/></w:pBdr></w:pPr></w:p><w:p><w:r><w:br w:type=\"page\"/></w:r></w:p>", ins)
        val re = DocxDocument.open(out)
        val ps = re.blocks.filterIsInstance<Paragraph>()
        assertTrue(ps.any { it.rule && it.text.isEmpty() && it.editable })
        assertTrue(ps.any { it.pageBreak && it.editable })
        assertFalse(para(re, 13).rule)
    }

    @Test fun superscriptAndSubscript() {
        val d = DocxDocument.open(src)
        val before = text(src, doc)
        val p = para(d, 13)
        var sp = RichText.setVertAlign(spans(p), 1, 2, "superscript")
        sp = RichText.setVertAlign(sp, 3, 4, "subscript")
        d.setParagraph(13, runs(sp), p.style)
        val out = d.save()
        val el = bodyChild(before, 13)
        val seg = assertOnlyRangeChanged(before, text(out, doc), el.start, el.end)
        assertTrue(seg, seg.contains("<w:rPr><w:vertAlign w:val=\"superscript\"/></w:rPr><w:t>${p.text[1]}</w:t>"))
        assertTrue(seg, seg.contains("<w:rPr><w:vertAlign w:val=\"subscript\"/></w:rPr><w:t>${p.text[3]}</w:t>"))
        val re = DocxDocument.open(out)
        val rp = para(re, 13)
        assertEquals(listOf(null, "superscript", null, "subscript", null), rp.runs.map { it.vertAlign })
        // clear formatting in the same session takes it away again; the paragraph is as the file had it
        d.setParagraph(13, runs(RichText.clearFormat(spans(para(d, 13)), 0, p.text.length)), p.style)
        assertEquals(before, text(d.save(), doc))
    }

    private fun tableXml(x: String): String = Regex("<w:tbl>.*</w:tbl>").find(x)!!.value

    @Test fun tableRowsAndColumnsInsertAndDelete() {
        val d = DocxDocument.blank()
        val t = d.insertTableAfter(d.blocks.first().id, 2, 2)
        d.setTableCell(t, 0, 0, "a"); d.setTableCell(t, 0, 1, "b"); d.setTableCell(t, 1, 0, "c"); d.setTableCell(t, 1, 1, "d")
        assertTrue(d.insertTableRow(t, 0, below = true))
        assertEquals(listOf(listOf("a", "b"), listOf("", ""), listOf("c", "d")), table(d, t).rows)
        assertTrue(d.insertTableRow(t, 0, below = false))
        assertEquals(4, table(d, t).rows.size)
        assertEquals(listOf("", ""), table(d, t).rows[0])
        assertTrue(d.insertTableColumn(t, 0, right = true))
        assertEquals(listOf("a", "", "b"), table(d, t).rows[1])
        assertTrue(d.insertTableColumn(t, 0, right = false))
        assertEquals(listOf("", "a", "", "b"), table(d, t).rows[1])
        var x = tableXml(text(d.save(), doc))
        // the grid keeps the table's width: 4 columns of 2250 twips, every cell as wide as its column
        assertEquals(4, Regex("<w:gridCol w:w=\"2250\"/>").findAll(x).count())
        assertEquals(16, Regex("<w:tcW w:w=\"2250\" w:type=\"dxa\"/>").findAll(x).count())
        assertTrue(d.deleteTableColumn(t, 0))
        assertTrue(d.deleteTableRow(t, 0))
        assertEquals(listOf(listOf("a", "", "b"), listOf("", "", ""), listOf("c", "", "d")), table(d, t).rows)
        x = tableXml(text(d.save(), doc))
        assertEquals(3, Regex("<w:gridCol w:w=\"3000\"/>").findAll(x).count())
        val out = d.save()
        assertConsistent(out)
        writeRoundTrip("docx-m3-table-rows.docx", out)
        assertEquals(table(d, t).rows, (DocxDocument.open(out).blocks.first { it is TableBlock } as TableBlock).rows)
    }

    @Test fun lastRowOrColumnTakesTheTable() {
        val d = DocxDocument.blank()
        val t = d.insertTableAfter(d.blocks.first().id, 1, 2)
        assertTrue(d.deleteTableColumn(t, 1))
        assertTrue(d.deleteTableRow(t, 0))
        assertTrue(d.blocks.none { it is TableBlock })
    }

    @Test fun mergeRightWritesGridSpanAndMergeDownWritesVMerge() {
        val d = DocxDocument.blank()
        val t = d.insertTableAfter(d.blocks.first().id, 3, 3)
        d.setTableCell(t, 0, 0, "x"); d.setTableCell(t, 0, 1, "y")
        assertTrue(d.mergeTableRight(t, 0, 0))
        val tb = table(d, t)
        assertEquals(listOf("x\ny", "", ""), tb.rows[0])
        assertEquals(CellMerge(0, 0, 1, 2), tb.mergeAt(0, 0))
        assertTrue(tb.covered(0, 1))
        assertFalse(tb.covered(0, 2))
        // the merged cell is as wide as the two columns
        var x = tableXml(text(d.save(), doc))
        assertTrue(x, x.contains("<w:tc><w:tcPr><w:tcW w:w=\"6000\" w:type=\"dxa\"/><w:gridSpan w:val=\"2\"/></w:tcPr>"))

        d.setTableCell(t, 1, 2, "top"); d.setTableCell(t, 2, 2, "bottom")
        assertTrue(d.mergeTableDown(t, 1, 2))
        assertEquals(CellMerge(1, 2, 2, 1), table(d, t).mergeAt(1, 2))
        assertEquals("top\nbottom", table(d, t).rows[1][2])
        assertEquals("", table(d, t).rows[2][2])
        x = tableXml(text(d.save(), doc))
        assertTrue(x, x.contains("<w:vMerge w:val=\"restart\"/>"))
        assertTrue(x, x.contains("<w:vMerge/>"))
        // no row below the last one; the cell below a 2-wide one is not the same width
        assertFalse(d.mergeTableDown(t, 2, 0))
        assertFalse(d.mergeTableDown(t, 0, 0))
        // a vertically merged cell does not merge sideways
        assertFalse(d.mergeTableRight(t, 1, 1))

        // inserting a row inside the vertical merge keeps it going; deleting its first row starts it on the next
        assertTrue(d.insertTableRow(t, 1, below = true))
        assertEquals(CellMerge(1, 2, 3, 1), table(d, t).mergeAt(1, 2))
        assertTrue(d.deleteTableRow(t, 1))
        assertEquals(CellMerge(1, 2, 2, 1), table(d, t).mergeAt(1, 2))
        // a column inserted inside the horizontal merge widens it
        assertTrue(d.insertTableColumn(t, 0, right = true))
        assertEquals(CellMerge(0, 0, 1, 3), table(d, t).mergeAt(0, 0))
        val out = d.save()
        assertConsistent(out)
        writeRoundTrip("docx-m3-merge.docx", out)
        assertEquals(table(d, t).merges, (DocxDocument.open(out).blocks.first { it is TableBlock } as TableBlock).merges)
    }

    @Test fun tableEditsLeaveTheRestOfTheDocumentByteIdentical() {
        val d0 = DocxDocument.blank()
        val t0 = d0.insertTableAfter(d0.blocks.first().id, 2, 2)
        d0.insertParagraphAfter(t0, listOf(Run("after")), ParaStyle.BODY)
        val base = d0.save()
        val before = text(base, doc)
        val d = DocxDocument.open(base)
        val t = d.blocks.first { it is TableBlock }.id
        assertTrue(d.mergeTableRight(t, 1, 0))
        assertTrue(d.insertTableColumn(t, 1, right = true))
        val out = d.save()
        assertEquals(setOf(doc), changedParts(base, out))
        val s = before.indexOf("<w:tbl>"); val e = before.indexOf("</w:tbl>") + "</w:tbl>".length
        assertOnlyRangeChanged(before, text(out, doc), s, e)
        // untouched: opened and saved again without edits gives the same bytes
        assertArrayEquals(out, DocxDocument.open(out).save())
    }
}
