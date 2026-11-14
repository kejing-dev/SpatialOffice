package io.embodit.spatialoffice.core.docx

import io.embodit.spatialoffice.core.docx.PackageAsserts.assertAllPartsIdentical
import io.embodit.spatialoffice.core.docx.PackageAsserts.assertConsistent
import io.embodit.spatialoffice.core.docx.PackageAsserts.assertOnlyRangeChanged
import io.embodit.spatialoffice.core.docx.PackageAsserts.changedParts
import io.embodit.spatialoffice.core.docx.PackageAsserts.fixture
import io.embodit.spatialoffice.core.docx.PackageAsserts.text
import io.embodit.spatialoffice.core.docx.PackageAsserts.writeRoundTrip
import io.embodit.spatialoffice.core.pkg.OoxmlPackage
import io.embodit.spatialoffice.core.pkg.Xml
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class DocxDocumentTest {
    private val doc = "word/document.xml"
    private val src: ByteArray = fixture("docx-report.docx")

    private fun open() = DocxDocument.open(src)

    /** Range of the [n]-th element child of w:body in the fixture's document part. */
    private fun bodyChild(xml: String, n: Int): Xml.Elem {
        val body = Xml.child(xml, Xml.root(xml)!!, "w:body")!!
        return Xml.children(xml, body)[n]
    }

    private fun para(d: DocxDocument, id: Int) = d.blocks.first { it.id == id } as Paragraph

    private fun paraWith(d: DocxDocument, text: String) = d.blocks.filterIsInstance<Paragraph>().first { it.text.contains(text) }

    private fun expectReadOnly(block: () -> Unit) {
        try { block(); fail("expected IllegalStateException") } catch (e: IllegalStateException) { /* expected */ }
    }

    // ---- open / untouched save

    @Test fun untouchedSaveKeepsEveryPartByteIdentical() {
        val d = open()
        assertFalse(d.dirty)
        val out = d.save()
        assertAllPartsIdentical(src, out)
        assertArrayEquals(src, out)
        assertEquals(0, d.changedBlockCount)
        writeRoundTrip("docx-untouched.docx", out)
    }

    @Test fun readsBlocksStylesAndRuns() {
        val b = open().blocks
        assertEquals(26, b.size)
        assertEquals(b.indices.toList(), b.map { it.id })
        val styles = b.map { (it as? Paragraph)?.style }
        assertEquals(ParaStyle.TITLE, styles[0])
        assertEquals(ParaStyle.H1, styles[1])
        assertEquals(ParaStyle.H2, styles[4])
        assertEquals(listOf(ParaStyle.BULLET, ParaStyle.BULLET, ParaStyle.BULLET), styles.subList(5, 8))
        assertEquals(listOf(ParaStyle.NUMBERED, ParaStyle.NUMBERED, ParaStyle.NUMBERED), styles.subList(9, 12))
        assertEquals(ParaStyle.H3, styles[12])
        assertEquals(ParaStyle.BODY, styles[13])

        val mixed = b[2] as Paragraph
        assertTrue(mixed.editable)
        assertEquals(listOf("Revenue grew ", "18%", " year over year, with ", "strong", " demand & <stable> margins."), mixed.runs.map { it.text })
        assertEquals(listOf(false, true, false, false, false), mixed.runs.map { it.bold })
        assertEquals(listOf(false, false, false, true, false), mixed.runs.map { it.italic })
        assertEquals(listOf(0, 1, 2, 3, 4), mixed.runs.map { it.src })
        assertFalse("bold that comes from the heading style is not a run flag", (b[1] as Paragraph).runs[0].bold)
        assertEquals("bold from a character style is", listOf(false, true), (b[25] as Paragraph).runs.map { it.bold })

        assertEquals(TableBlock(14, listOf(listOf("Region totals", "", ""), listOf("Region", "Q1", "Q2"), listOf("East", "120", "144"), listOf("West", "95", "101"))), b[14])
        assertEquals(ImageBlock(16, "word/media/image1.png", 1828800L, 914400L), b[16])
        assertEquals(OpaqueBlock(19, "text-box"), b[19])
        assertEquals(OpaqueBlock(23, "section"), b[23])
    }

    @Test fun flagsWhatItCannotEdit() {
        val d = open()
        val comment = d.blocks[3] as Paragraph
        assertFalse(comment.editable); assertEquals("complex", comment.readOnlyWhy)
        val tracked = d.blocks[18] as Paragraph
        assertFalse(tracked.editable); assertEquals("tracked-changes", tracked.readOnlyWhy)
        assertEquals("the inserted text is shown, the deleted text is not", "Costs were slightly lower than forecast.", tracked.text)
        val field = d.blocks[20] as Paragraph
        assertFalse(field.editable); assertEquals("field", field.readOnlyWhy)
        assertEquals("Printed on page 1.", field.text)
        assertNull((d.blocks[2] as Paragraph).readOnlyWhy)

        val notes = d.compat().associate { it.code to it }
        assertEquals(CompatNote(CompatKind.READ_ONLY, "tracked-changes", 2), notes["tracked-changes"])
        assertEquals(CompatNote(CompatKind.READ_ONLY, "comments", 1), notes["comments"])
        assertEquals(CompatNote(CompatKind.READ_ONLY, "text-box", 1), notes["text-box"])
        assertEquals(CompatNote(CompatKind.READ_ONLY, "section", 1), notes["section"])
        assertEquals(CompatNote(CompatKind.READ_ONLY, "table", 1), notes["table"])
        assertEquals(CompatNote(CompatKind.READ_ONLY, "field", 1), notes["field"])
        assertEquals(CompatNote(CompatKind.APPROX, "columns", 1), notes["columns"])
        assertEquals(CompatNote(CompatKind.APPROX, "merged-cells", 1), notes["merged-cells"])
        assertEquals(CompatNote(CompatKind.APPROX, "font:Fixture Sans", 1), notes["font:Fixture Sans"])
    }

    @Test fun readOnlyBlocksRefuseEdits() {
        val d = open()
        for (id in listOf(3, 18, 20)) {
            expectReadOnly { d.setParagraph(id, listOf(Run("x")), ParaStyle.BODY) }
            expectReadOnly { d.deleteBlock(id) }
        }
        expectReadOnly { d.setParagraph(14, listOf(Run("x")), ParaStyle.BODY) }   // a table
        expectReadOnly { d.setParagraph(16, listOf(Run("x")), ParaStyle.BODY) }   // a picture
        expectReadOnly { d.deleteBlock(19) }                                       // the text box
        expectReadOnly { d.deleteBlock(23) }                                       // the section break
        assertFalse(d.dirty)
        assertAllPartsIdentical(src, d.save())
    }

    @Test fun firstLineAndPlainText() {
        val d = open()
        assertEquals("季度运营报告 Quarterly Report", d.firstLine())
        val t = d.plainText()
        assertTrue(t.startsWith("季度运营报告 Quarterly Report\nOverview\nRevenue grew 18% year over year"))
        assertTrue(t.contains("Region\tQ1\tQ2\nEast\t120\t144"))
        assertTrue(t.contains("Costs were slightly lower than forecast."))
        assertEquals("", DocxDocument.blank().firstLine())
    }

    @Test fun imageBytesAreReachable() {
        val d = open()
        val img = d.blocks[16] as ImageBlock
        val bytes = d.partBytes(img.part)
        assertNotNull(bytes)
        assertEquals(0x89.toByte(), bytes!![0]); assertEquals('P'.code.toByte(), bytes[1])
        assertNull(d.partBytes("word/media/none.png"))
    }

    // ---- patch-only edits

    @Test fun editingOneParagraphChangesOnlyItsRange() {
        val d = open()
        val before = text(src, doc)
        val newText = "华南地区增长 15%，价格 < 100 & \"稳定\" > 50。"
        d.setParagraph(13, listOf(Run(newText)), ParaStyle.BODY)
        assertTrue(d.dirty); assertEquals(1, d.changedBlockCount)
        val out = d.save()

        assertEquals("only the document part may differ", setOf(doc), changedParts(src, out))
        val el = bodyChild(before, 13)
        assertTrue(before.substring(el.start, el.end).contains("华东地区"))
        val replaced = assertOnlyRangeChanged(before, text(out, doc), el.start, el.end)
        assertEquals("<w:p><w:r><w:t>华南地区增长 15%，价格 &lt; 100 &amp; &quot;稳定&quot; &gt; 50。</w:t></w:r></w:p>", replaced)

        val re = DocxDocument.open(out)
        assertEquals(newText, para(re, 13).text)
        assertEquals(26, re.blocks.size)
        assertEquals(open().blocks.filter { it.id != 13 }, re.blocks.filter { it.id != 13 })
        // what the model cannot represent is still there, byte for byte
        val after = text(out, doc)
        for (marker in listOf("<w:del w:id=\"101\"", "<w:ins w:id=\"102\"", "<w:delText>flat</w:delText>", "<wps:txbx>", "<w:cols w:num=\"2\"", "<w:fldSimple w:instr=\" PAGE \">", "<w:commentRangeStart w:id=\"0\"/>")) {
            assertTrue("lost $marker", after.contains(marker))
        }
    }

    @Test fun togglingBoldItalicUnderlineKeepsTheRunsOtherFormatting() {
        val d = open()
        val before = text(src, doc)
        val p = d.blocks[2] as Paragraph
        val runs = p.runs.toMutableList()
        runs[1] = runs[1].copy(bold = false, italic = true, underline = true)      // "18%": coloured, 14 pt, named font
        runs[3] = runs[3].copy(italic = false, bold = true)                        // "strong"
        d.setParagraph(2, runs, ParaStyle.BODY)
        val out = d.save()
        assertEquals(setOf(doc), changedParts(src, out))
        val el = bodyChild(before, 2)
        val replaced = assertOnlyRangeChanged(before, text(out, doc), el.start, el.end)
        assertTrue(replaced, replaced.contains("<w:r><w:rPr><w:rFonts w:ascii=\"Fixture Sans\" w:hAnsi=\"Fixture Sans\"/><w:i/><w:color w:val=\"C0392B\"/><w:sz w:val=\"28\"/><w:u w:val=\"single\"/></w:rPr><w:t>18%</w:t></w:r>"))
        assertTrue(replaced, replaced.contains("<w:r><w:rPr><w:b/></w:rPr><w:t>strong</w:t></w:r>"))
        assertTrue(replaced, replaced.contains("<w:r><w:t xml:space=\"preserve\">Revenue grew </w:t></w:r>"))

        val re = para(DocxDocument.open(out), 2)
        assertEquals(p.runs.map { it.text }, re.runs.map { it.text })
        assertEquals(listOf(false, false, false, true, false), re.runs.map { it.bold })
        assertEquals(listOf(false, true, false, false, false), re.runs.map { it.italic })
        assertEquals(listOf(false, true, false, false, false), re.runs.map { it.underline })
    }

    @Test fun newRunTakesFormattingFromItsSourceRun() {
        val d = open()
        // split "18%" into two runs that both point at original run 1, and add one with no source
        d.setParagraph(2, listOf(Run("Up ", src = 0), Run("18", bold = true, src = 1), Run("%", bold = false, src = 1), Run(" total")), ParaStyle.BODY)
        val x = text(d.save(), doc)
        val font = "<w:rFonts w:ascii=\"Fixture Sans\" w:hAnsi=\"Fixture Sans\"/>"
        assertTrue(x.contains("<w:r><w:rPr>$font<w:b/><w:color w:val=\"C0392B\"/><w:sz w:val=\"28\"/></w:rPr><w:t>18</w:t></w:r>"))
        assertTrue(x.contains("<w:r><w:rPr>$font<w:color w:val=\"C0392B\"/><w:sz w:val=\"28\"/></w:rPr><w:t>%</w:t></w:r>"))
        assertTrue(x.contains("<w:r><w:t xml:space=\"preserve\"> total</w:t></w:r>"))
    }

    @Test fun characterStyleBoldCanBeSwitchedOffAndHeadingRunsStayPlain() {
        val d = open()
        d.setParagraph(25, listOf(Run("Outlook: ", src = 0), Run("positive", bold = false, italic = true, src = 1)), ParaStyle.BODY)
        d.setParagraph(1, listOf(Run("Overview", src = 0), Run(" (draft)", italic = true, src = 0)), ParaStyle.H1)
        val out = d.save()
        val x = text(out, doc)
        assertTrue(x.contains("<w:r><w:rPr><w:rStyle w:val=\"Strong\"/><w:b w:val=\"0\"/><w:i/><w:color w:val=\"1E8449\"/></w:rPr><w:t>positive</w:t></w:r>"))
        assertTrue(x.contains("<w:p><w:pPr><w:pStyle w:val=\"Heading1\"/></w:pPr><w:r><w:t>Overview</w:t></w:r><w:r><w:rPr><w:i/></w:rPr><w:t xml:space=\"preserve\"> (draft)</w:t></w:r></w:p>"))
        val re = DocxDocument.open(out)
        assertEquals(listOf(false, false), para(re, 25).runs.map { it.bold })
        assertEquals(listOf(false, true), para(re, 25).runs.map { it.italic })
        assertEquals(ParaStyle.H1, para(re, 1).style)
        assertEquals(d.blocks, re.blocks.map { b -> if (b is Paragraph && (b.id == 1 || b.id == 25)) b.copy(runs = (d.blocks[b.id] as Paragraph).runs) else b })
    }

    @Test fun styleChangeRewritesOnlyTheStyleReference() {
        val d = open()
        val before = text(src, doc)
        d.setParagraph(13, (d.blocks[13] as Paragraph).runs, ParaStyle.H2)          // body → heading 2
        d.setParagraph(5, (d.blocks[5] as Paragraph).runs, ParaStyle.BODY)           // bullet → body
        d.setParagraph(15, (d.blocks[15] as Paragraph).runs, ParaStyle.TITLE)        // body → title
        assertEquals(3, d.changedBlockCount)
        val out = d.save()
        assertEquals(setOf(doc), changedParts(src, out))
        val after = text(out, doc)
        assertTrue(after.contains("<w:p><w:pPr><w:pStyle w:val=\"Heading2\"/></w:pPr><w:r><w:t>华东地区"))
        assertTrue(after.contains("<w:p><w:r><w:t>New customers in three regions</w:t></w:r></w:p>"))
        // everything before the first and after the last edited paragraph is untouched
        val first = bodyChild(before, 5)
        val last = bodyChild(before, 15)
        assertOnlyRangeChanged(before, after, first.start, last.end)

        val re = DocxDocument.open(out)
        assertEquals(ParaStyle.H2, para(re, 13).style)
        assertFalse("the runs themselves were not touched", para(re, 13).runs[0].bold)
        assertEquals(ParaStyle.BODY, para(re, 5).style)
        assertEquals(ParaStyle.BULLET, para(re, 6).style)
        assertEquals(ParaStyle.TITLE, para(re, 15).style)
    }

    @Test fun listStyleJoinsTheNeighbouringListOrAddsADefinition() {
        // next to an existing bullet list: reuse its formatting, no other part changes
        val d = open()
        val id = d.insertParagraphAfter(7, listOf(Run("One more highlight")), ParaStyle.BULLET)
        d.setParagraph(8, (d.blocks.first { it.id == 8 } as Paragraph).runs, ParaStyle.BULLET)   // the heading after the list
        val out = d.save()
        assertEquals(setOf(doc), changedParts(src, out))
        val re = DocxDocument.open(out)
        assertEquals(ParaStyle.BULLET, paraWith(re, "One more highlight").style)
        assertEquals(ParaStyle.BULLET, paraWith(re, "Next steps").style)
        assertTrue(text(out, doc).contains("<w:p><w:pPr><w:pStyle w:val=\"ListBullet\"/></w:pPr><w:r><w:t>One more highlight</w:t></w:r></w:p>"))
        assertTrue(id > 24)

        // far from any list: a numbering definition is appended to the numbering part, nothing else changes
        val d2 = open()
        val numberingBefore = text(src, "word/numbering.xml")
        d2.setParagraph(24, (d2.blocks[24] as Paragraph).runs, ParaStyle.NUMBERED)
        d2.setParagraph(21, (d2.blocks[21] as Paragraph).runs, ParaStyle.BULLET)
        val out2 = d2.save()
        assertEquals(setOf(doc, "word/numbering.xml"), changedParts(src, out2))
        val numberingAfter = text(out2, "word/numbering.xml")
        fun defs(x: String) = Xml.children(x, Xml.root(x)!!).map { x.substring(it.start, it.end) }
        val oldDefs = defs(numberingBefore)
        val newDefs = defs(numberingAfter)
        assertEquals("two list definitions (abstract + instance each) were added", oldDefs.size + 4, newDefs.size)
        assertEquals("existing numbering definitions are kept verbatim and in order", oldDefs, newDefs.filter { it in oldDefs })
        assertEquals(numberingBefore.substring(0, Xml.root(numberingBefore)!!.openEnd), numberingAfter.substring(0, Xml.root(numberingAfter)!!.openEnd))
        val kinds = Xml.children(numberingAfter, Xml.root(numberingAfter)!!).map { it.name }
        assertTrue("every abstract definition precedes every instance", kinds.lastIndexOf("w:abstractNum") < kinds.indexOf("w:num"))
        val re2 = DocxDocument.open(out2)
        assertEquals(ParaStyle.NUMBERED, para(re2, 24).style)
        assertEquals(ParaStyle.BULLET, para(re2, 21).style)
        assertEquals(ParaStyle.BODY, para(re2, 22).style)
        assertConsistent(out2)
    }

    @Test fun insertAndDeleteParagraphs() {
        val d = open()
        val before = text(src, doc)
        val idsBefore = d.blocks.map { it.id }
        val a = d.insertParagraphAfter(1, listOf(Run("Inserted after the first heading.")), ParaStyle.BODY)
        val b = d.insertParagraphAfter(-1, listOf(Run("Draft — not for distribution")), ParaStyle.BODY)
        d.deleteBlock(10)                                                    // "Hire two engineers"
        assertEquals(3, d.changedBlockCount)
        assertEquals(listOf(b, 0, 1, a) + idsBefore.subList(2, 10) + idsBefore.subList(11, 26), d.blocks.map { it.id })

        val out = d.save()
        assertEquals(setOf(doc), changedParts(src, out))
        val after = text(out, doc)
        // the three edits are pure insertions / removals: every original block other than #10 is still there verbatim
        for (n in 0 until 26) {
            val el = bodyChild(before, n)
            val chunk = before.substring(el.start, el.end)
            assertEquals("block $n", n != 10, after.contains(chunk))
        }
        assertEquals(before.length + "<w:p><w:r><w:t>Inserted after the first heading.</w:t></w:r></w:p>".length +
            "<w:p><w:r><w:t>Draft — not for distribution</w:t></w:r></w:p>".length - bodyChild(before, 10).let { it.end - it.start }, after.length)

        val re = DocxDocument.open(out)
        assertEquals(27, re.blocks.size)
        assertEquals("Draft — not for distribution", (re.blocks[0] as Paragraph).text)
        assertEquals("Inserted after the first heading.", (re.blocks[3] as Paragraph).text)
        assertFalse(re.plainText().contains("Hire two engineers"))
        assertTrue(re.plainText().contains("Finalise the budget\nShip the spring release"))
    }

    @Test fun insertedParagraphContinuesTheParagraphBeforeIt() {
        val d = open()
        // after a numbered item with the same style → same list; after the coloured run → same run formatting
        d.insertParagraphAfter(11, listOf(Run("Review in June")), ParaStyle.NUMBERED)
        d.setParagraph(2, listOf(Run("Revenue grew "), Run("18%", bold = true, src = 1)), ParaStyle.BODY)
        d.insertParagraphAfter(2, listOf(Run("and again")), ParaStyle.BODY)
        val x = text(d.save(), doc)
        assertTrue(x.contains("<w:p><w:pPr><w:pStyle w:val=\"ListNumber\"/></w:pPr><w:r><w:t>Review in June</w:t></w:r></w:p>"))
        assertTrue(x.contains("<w:rFonts w:ascii=\"Fixture Sans\" w:hAnsi=\"Fixture Sans\"/><w:color w:val=\"C0392B\"/><w:sz w:val=\"28\"/></w:rPr><w:t>and again</w:t>"))
    }

    @Test fun deletingATableOrPictureLeavesTheRestAlone() {
        val d = open()
        val before = text(src, doc)
        d.deleteBlock(14)
        d.deleteBlock(16)
        val out = d.save()
        assertEquals(setOf(doc), changedParts(src, out))
        val after = text(out, doc)
        assertFalse(after.contains("<w:tbl>")); assertFalse(after.contains("<w:drawing><wp:inline"))
        val tbl = bodyChild(before, 14)
        val pic = bodyChild(before, 16)
        assertEquals(before.substring(0, tbl.start) + before.substring(tbl.end, pic.start) + before.substring(pic.end), after)
        assertEquals(24, DocxDocument.open(out).blocks.size)
        assertConsistent(out)
    }

    @Test fun bookmarksOfAnEditedParagraphAreKept() {
        val d = open()
        d.setParagraph(17, listOf(Run("Summary rewritten.")), ParaStyle.BODY)
        val x = text(d.save(), doc)
        assertTrue(x.contains("<w:bookmarkStart w:id=\"7\" w:name=\"summary_anchor\"/><w:r><w:t>Summary rewritten.</w:t></w:r><w:bookmarkEnd w:id=\"7\"/></w:p>"))
    }

    @Test fun nonAsciiSpecialCharactersTabsAndBreaksRoundTrip() {
        val d = open()
        val text = "中文、日本語、한국어 & <tags> \"quotes\" 'apostrophes' — 😀\tafter tab\nsecond line"
        d.setParagraph(13, listOf(Run(" leading and trailing spaces "), Run(text, italic = true)), ParaStyle.BODY)
        val out = d.save()
        val p = para(DocxDocument.open(out), 13)
        assertEquals(" leading and trailing spaces $text", p.text)
        assertEquals(listOf(false, true), p.runs.map { it.italic })
        val x = text(out, doc)
        assertTrue(x.contains("<w:tab/>")); assertTrue(x.contains("<w:br/>")); assertTrue(x.contains("&amp; &lt;tags&gt;"))
        // control characters that XML cannot hold are dropped instead of corrupting the part
        val d2 = open()
        d2.setParagraph(13, listOf(Run("a\u0001b\u0000c")), ParaStyle.BODY)
        assertEquals("abc", para(DocxDocument.open(d2.save()), 13).text)
    }

    @Test fun saveClearsDirtyAndEditingCanContinue() {
        val d = open()
        d.setParagraph(13, listOf(Run("first edit")), ParaStyle.BODY)
        val one = d.save()
        assertFalse(d.dirty); assertEquals(0, d.changedBlockCount)
        assertArrayEquals("a second save with no edit in between returns the same bytes", one, d.save())
        val newId = d.insertParagraphAfter(13, listOf(Run("second edit")), ParaStyle.BODY)
        d.setParagraph(24, listOf(Run("third edit")), ParaStyle.BODY)
        assertEquals(2, d.changedBlockCount)
        val two = d.save()
        assertEquals(setOf(doc), changedParts(src, two))
        val re = DocxDocument.open(two)
        assertEquals(listOf("first edit", "second edit"), re.blocks.subList(13, 15).map { (it as Paragraph).text })
        assertEquals("third edit", (re.blocks[25] as Paragraph).text)
        assertEquals("second edit", para(d, newId).text)
        assertTrue(text(two, doc).contains("<w:del w:id=\"101\""))
    }

    @Test fun unknownIdIsRejected() {
        val d = open()
        try { d.setParagraph(999, listOf(Run("x")), ParaStyle.BODY); fail() } catch (e: IllegalArgumentException) { /* expected */ }
        try { d.insertParagraphAfter(999, listOf(Run("x")), ParaStyle.BODY); fail() } catch (e: IllegalArgumentException) { /* expected */ }
        assertFalse(d.dirty)
    }

    @Test fun rewritingEveryEditableParagraphWithItsOwnContentChangesNothingVisible() {
        val d = open()
        val before = d.blocks
        val editable = before.filterIsInstance<Paragraph>().filter { it.editable }
        assertEquals(19, editable.size)
        for (p in editable) d.setParagraph(p.id, p.runs, p.style)
        assertEquals(before, d.blocks)
        assertTrue(d.dirty); assertEquals(19, d.changedBlockCount)
        val out = d.save()
        // this fixture's paragraphs carry nothing a rewrite drops (no revision ids, no spell-check marks), so the
        // regenerated XML is the original XML, character for character
        assertEquals(emptySet<String>(), changedParts(src, out))
        assertEquals("what is read back is what was there", before, DocxDocument.open(out).blocks)
        assertEquals(open().compat(), DocxDocument.open(out).compat())
        assertEquals(open().plainText(), DocxDocument.open(out).plainText())
        writeRoundTrip("docx-rewritten.docx", out)
    }

    // ---- hand-made documents: shapes of XML the generated fixture does not contain

    private val nsW = "xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\""

    /** A package with only the parts given: the document body, and optionally a styles part. */
    private fun handMade(body: String, styles: String? = null): ByteArray {
        val p = OoxmlPackage.empty()
        p.putText(
            OoxmlPackage.CONTENT_TYPES,
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
                "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
                "<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>" +
                (if (styles != null) "<Override PartName=\"/word/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml\"/>" else "") + "</Types>",
        )
        p.putText("_rels/.rels", "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/></Relationships>")
        p.putText("word/document.xml", "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<w:document $nsW xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><w:body>$body<w:sectPr/></w:body></w:document>")
        if (styles != null) {
            p.putText("word/styles.xml", "<w:styles $nsW>$styles</w:styles>")
            p.putText("word/_rels/document.xml.rels", "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/></Relationships>")
        }
        return p.write()
    }

    @Test fun classifiesHandWrittenParagraphShapes() {
        val bytes = handMade(
            "<w:p w:rsidR=\"00AA11BB\" w:rsidRDefault=\"00CC22DD\"><w:pPr><w:jc w:val=\"center\"/></w:pPr><w:proofErr w:type=\"spellStart\"/>" +
                "<w:r w:rsidRPr=\"00EE33FF\"><w:rPr><w:b/><w:sz w:val=\"30\"/></w:rPr><w:lastRenderedPageBreak/><w:t>Helo</w:t></w:r><w:proofErr w:type=\"spellEnd\"/><w:r><w:t xml:space=\"preserve\"> world</w:t><w:tab/><w:t>x</w:t><w:br/><w:t>y</w:t></w:r></w:p>" +   // 0 editable
                "<w:p><w:hyperlink r:id=\"rId9\"><w:r><w:t>a link</w:t></w:r></w:hyperlink></w:p>" +                                      // 1 complex
                "<w:p><w:r><w:t>before</w:t></w:r><w:r><w:br w:type=\"page\"/></w:r><w:r><w:t>after</w:t></w:r></w:p>" +                  // 2 editable, page break
                "<w:sdt><w:sdtContent><w:p><w:r><w:t>inside a content control</w:t></w:r></w:p></w:sdtContent></w:sdt>" +                 // 3 opaque
                "<w:p><w:r><w:t>see note</w:t></w:r><w:r><w:footnoteReference w:id=\"2\"/></w:r></w:p>" +                                 // 4 complex
                "<w:p><w:r><w:fldChar w:fldCharType=\"begin\"/></w:r><w:r><w:instrText> DATE </w:instrText></w:r><w:r><w:fldChar w:fldCharType=\"separate\"/></w:r><w:r><w:t>today</w:t></w:r><w:r><w:fldChar w:fldCharType=\"end\"/></w:r></w:p>" + // 5 field
                "<w:p><w:pPr><w:outlineLvl w:val=\"1\"/></w:pPr><w:r><w:t>outline level two</w:t></w:r></w:p>" +                           // 6 H2 by outline level
                "<w:p><w:pPr><w:rPr><w:ins w:id=\"5\" w:author=\"a\"/></w:rPr></w:pPr><w:r><w:t>tracked paragraph mark</w:t></w:r></w:p>" + // 7 tracked
                "<w:bookmarkStart w:id=\"1\" w:name=\"between\"/><w:bookmarkEnd w:id=\"1\"/>" +                                             // not a block
                "<w:p/>",                                                                                                                 // 8 empty, editable
        )
        val d = DocxDocument.open(bytes)
        val b = d.blocks
        assertEquals(9, b.size)
        val p0 = b[0] as Paragraph
        assertTrue(p0.editable); assertEquals("Helo world\tx\ny", p0.text); assertEquals(listOf(true, false), p0.runs.map { it.bold })
        assertEquals("complex", (b[1] as Paragraph).readOnlyWhy); assertEquals("a link", (b[1] as Paragraph).text)
        assertTrue((b[2] as Paragraph).editable); assertEquals("before\u000Cafter", (b[2] as Paragraph).text)
        assertEquals(OpaqueBlock(3, "other"), b[3])
        assertEquals("complex", (b[4] as Paragraph).readOnlyWhy)
        assertEquals("field", (b[5] as Paragraph).readOnlyWhy); assertEquals("today", (b[5] as Paragraph).text)
        assertEquals(ParaStyle.H2, (b[6] as Paragraph).style)
        assertEquals("tracked-changes", (b[7] as Paragraph).readOnlyWhy)
        assertEquals(Paragraph(8, ParaStyle.BODY, emptyList(), true, null), b[8])
        assertAllPartsIdentical(bytes, d.save())

        // editing: start-tag attributes and w:pPr stay, spell-check marks (which describe the old text) go
        d.setParagraph(0, listOf(Run("Hello", bold = true, src = 0), Run(" world", src = 1)), ParaStyle.BODY)
        d.setParagraph(2, listOf(Run("one\u000Ctwo")), ParaStyle.BODY)
        d.setParagraph(8, listOf(Run("was empty")), ParaStyle.BODY)
        val out = d.save()
        val x = text(out, doc)
        assertTrue(x.contains("<w:p w:rsidR=\"00AA11BB\" w:rsidRDefault=\"00CC22DD\"><w:pPr><w:jc w:val=\"center\"/></w:pPr><w:r><w:rPr><w:b/><w:sz w:val=\"30\"/></w:rPr><w:t>Hello</w:t></w:r><w:r><w:t xml:space=\"preserve\"> world</w:t></w:r></w:p>"))
        assertTrue(x.contains("<w:p><w:r><w:t>one</w:t><w:br w:type=\"page\"/><w:t>two</w:t></w:r></w:p>"))
        assertTrue(x.contains("<w:bookmarkStart w:id=\"1\" w:name=\"between\"/><w:bookmarkEnd w:id=\"1\"/><w:p><w:r><w:t>was empty</w:t></w:r></w:p><w:sectPr/>"))
        val re = DocxDocument.open(out)
        assertEquals("one\u000Ctwo", (re.blocks[2] as Paragraph).text)
        assertEquals(b.subList(3, 8), re.blocks.subList(3, 8))
    }

    @Test fun headingStyleIsAddedWhenTheFileLacksIt() {
        // a styles part without heading styles: one definition is appended, the rest of the part is untouched
        val normal = "<w:style w:type=\"paragraph\" w:default=\"1\" w:styleId=\"a\"><w:name w:val=\"Normal\"/></w:style>"
        val bytes = handMade("<w:p><w:r><w:t>Chapter</w:t></w:r></w:p><w:p><w:r><w:t>text</w:t></w:r></w:p>", normal)
        val d = DocxDocument.open(bytes)
        d.setParagraph(0, (d.blocks[0] as Paragraph).runs, ParaStyle.H1)
        val out = d.save()
        assertEquals(setOf(doc, "word/styles.xml"), changedParts(bytes, out))
        val sx = text(out, "word/styles.xml")
        assertTrue(sx.startsWith("<w:styles $nsW>$normal<w:style w:type=\"paragraph\" w:styleId=\"Heading1\"><w:name w:val=\"heading 1\"/><w:basedOn w:val=\"a\"/>"))
        assertTrue(text(out, doc).contains("<w:p><w:pPr><w:pStyle w:val=\"Heading1\"/></w:pPr><w:r><w:t>Chapter</w:t></w:r></w:p>"))
        assertEquals(listOf(ParaStyle.H1, ParaStyle.BODY), DocxDocument.open(out).blocks.map { (it as Paragraph).style })
        assertConsistent(out)

        // no styles part and no numbering part at all: both are created and wired into the package
        val bare = handMade("<w:p><w:r><w:t>Title here</w:t></w:r></w:p><w:p><w:r><w:t>item</w:t></w:r></w:p>")
        val d2 = DocxDocument.open(bare)
        d2.setParagraph(0, (d2.blocks[0] as Paragraph).runs, ParaStyle.TITLE)
        d2.setParagraph(1, (d2.blocks[1] as Paragraph).runs, ParaStyle.BULLET)
        val out2 = d2.save()
        assertEquals(setOf(doc, "[Content_Types].xml", "word/_rels/document.xml.rels", "word/styles.xml", "word/numbering.xml"), changedParts(bare, out2))
        assertConsistent(out2)
        assertEquals(listOf(ParaStyle.TITLE, ParaStyle.BULLET), DocxDocument.open(out2).blocks.map { (it as Paragraph).style })
    }

    // ---- new files

    @Test fun blankDocumentIsValidAndCarriesNoAuthor() {
        val out = DocxDocument.blank().save()
        assertConsistent(out)
        assertTrue(text(out, "docProps/app.xml").contains("<Application>Spatial Office</Application>"))
        val core = text(out, "docProps/core.xml")
        assertFalse(core.contains("creator")); assertFalse(core.contains("lastModifiedBy"))
        val d = DocxDocument.open(out)
        assertEquals(listOf(Paragraph(0, ParaStyle.BODY, emptyList(), true, null)), d.blocks)
        assertTrue(d.compat().isEmpty())
        assertAllPartsIdentical(out, d.save())
    }

    @Test fun blankEditSaveReopen() {
        val d = DocxDocument.blank()
        assertFalse(d.dirty)
        d.setParagraph(0, listOf(Run("项目计划 Project Plan")), ParaStyle.TITLE)
        var last = d.insertParagraphAfter(0, listOf(Run("Scope")), ParaStyle.H1)
        last = d.insertParagraphAfter(last, listOf(Run("We ship "), Run("three", bold = true), Run(" features & fix <all> "), Run("known", italic = true, underline = true), Run(" bugs.")), ParaStyle.BODY)
        last = d.insertParagraphAfter(last, listOf(Run("Risks")), ParaStyle.H2)
        last = d.insertParagraphAfter(last, listOf(Run("供应链延迟")), ParaStyle.BULLET)
        last = d.insertParagraphAfter(last, listOf(Run("Staffing")), ParaStyle.BULLET)
        last = d.insertParagraphAfter(last, listOf(Run("Details")), ParaStyle.H3)
        last = d.insertParagraphAfter(last, listOf(Run("Plan")), ParaStyle.NUMBERED)
        last = d.insertParagraphAfter(last, listOf(Run("Build")), ParaStyle.NUMBERED)
        d.insertParagraphAfter(last, listOf(Run("Done.")), ParaStyle.BODY)
        assertTrue(d.dirty)
        val out = d.save()
        assertConsistent(out)
        writeRoundTrip("docx-blank.docx", out)

        val re = DocxDocument.open(out)
        assertEquals(
            listOf(ParaStyle.TITLE, ParaStyle.H1, ParaStyle.BODY, ParaStyle.H2, ParaStyle.BULLET, ParaStyle.BULLET, ParaStyle.H3, ParaStyle.NUMBERED, ParaStyle.NUMBERED, ParaStyle.BODY),
            re.blocks.map { (it as Paragraph).style },
        )
        assertEquals("项目计划 Project Plan", re.firstLine())
        val body = re.blocks[2] as Paragraph
        assertEquals("We ship three features & fix <all> known bugs.", body.text)
        assertEquals(listOf(false, true, false, false, false), body.runs.map { it.bold })
        assertEquals(listOf(false, false, false, true, false), body.runs.map { it.underline })
        assertTrue(re.blocks.all { (it as Paragraph).editable })
        assertEquals("the two bullets share one list definition, the numbered list has its own", 2, Regex("<w:num w:numId").findAll(text(out, "word/numbering.xml")).count())
        assertAllPartsIdentical(out, re.save())
    }

    // ---- files for the independent verifier (tools/verify/verify_docx_pptx.py)

    @Test fun writesEditedFixtureForTheVerifier() {
        val d = open()
        d.setParagraph(13, listOf(Run("华南地区增长 15%，价格 < 100 & \"稳定\"。")), ParaStyle.BODY)
        val runs = (d.blocks[2] as Paragraph).runs.toMutableList()
        runs[1] = runs[1].copy(bold = false, underline = true)
        d.setParagraph(2, runs, ParaStyle.BODY)
        d.setParagraph(15, listOf(Run("Picture section")), ParaStyle.H2)
        d.insertParagraphAfter(7, listOf(Run("Fourth highlight")), ParaStyle.BULLET)
        d.insertParagraphAfter(-1, listOf(Run("Draft copy")), ParaStyle.BODY)
        d.deleteBlock(10)
        d.setParagraph(24, listOf(Run("Closing item")), ParaStyle.NUMBERED)
        val out = d.save()
        assertEquals(setOf(doc, "word/numbering.xml"), changedParts(src, out))
        assertConsistent(out)
        writeRoundTrip("docx-edited.docx", out)
    }
}
