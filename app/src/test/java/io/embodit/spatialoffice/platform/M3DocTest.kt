package io.embodit.spatialoffice.platform

import io.embodit.spatialoffice.core.docx.CellMerge
import io.embodit.spatialoffice.core.docx.DocxDocument
import io.embodit.spatialoffice.core.docx.ParaStyle
import io.embodit.spatialoffice.core.docx.Paragraph
import io.embodit.spatialoffice.core.docx.TableBlock
import io.embodit.spatialoffice.core.files.FileKind
import io.embodit.spatialoffice.core.text.Span
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipInputStream

/**
 * v0.5.0 (M3, documents) through the session calls the menus, the link sheet and the debug commands use: d20 links,
 * d21 horizontal line / page break, d22 superscript / subscript, d42 table rows / columns / merge; read back from the saved bytes.
 */
class M3DocTest {

    private fun part(bytes: ByteArray, name: String): String {
        ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
            while (true) { val e = z.nextEntry ?: break; if (e.name == name) return String(z.readBytes(), Charsets.UTF_8) }
        }
        error("no $name")
    }

    private fun doc(text: String): DocSession {
        val d = DocSession(FileRef.Path(File("t.docx")), FileKind.DOC, DocxDocument.blank().save(), File("build/tmp"))
        d.setParagraph(d.blocks().first().id, listOf(Span(text)), ParaStyle.BODY)
        return d
    }

    private fun first(d: DocSession) = d.blocks().filterIsInstance<UiBlock.Para>().first()

    @Test fun addressesGetAScheme() {
        assertEquals("https://example.com", DocSession.normalizeUrl(" example.com "))
        assertEquals("mailto:a@b.cn", DocSession.normalizeUrl("a@b.cn"))
        assertEquals("http://x.y/z?q=1", DocSession.normalizeUrl("http://x.y/z?q=1"))
        assertEquals("", DocSession.normalizeUrl("  "))
    }

    @Test fun linkSelectedWordsEditAndRemoveThemOneUndoStepEach() {
        val d = doc("Read the report today")
        val p = first(d)
        assertTrue(d.setLink(p.id, 9, 15, "example.com/r"))
        assertEquals(listOf(Triple("https://example.com/r", 9, 15)), d.linksOf(p.id))
        val xml = part(d.serialize(), "word/document.xml")
        assertTrue(xml, xml.contains("<w:hyperlink r:id="))
        assertTrue(part(d.serialize(), "word/_rels/document.xml.rels").contains("Target=\"https://example.com/r\" TargetMode=\"External\""))
        // a blank address changes nothing
        assertFalse(d.setLink(p.id, 9, 15, " "))
        // edit, then remove
        assertTrue(d.setLink(p.id, 9, 15, "https://example.org"))
        assertEquals("https://example.org", d.linksOf(p.id).single().first)
        assertTrue(d.setLink(p.id, 9, 15, null))
        assertTrue(d.linksOf(p.id).isEmpty())
        assertFalse(part(d.serialize(), "word/document.xml").contains("w:hyperlink"))
        d.undo()
        assertEquals("https://example.org", d.linksOf(p.id).single().first)
    }

    @Test fun linkTypedAtTheCaretUsesTheAddressWhenNoTextIsGiven() {
        val d = doc("See ")
        val p = first(d)
        assertEquals(4 until 15, d.insertLink(p.id, 4, "", "example.com"))
        assertEquals("See example.com", first(d).spans.joinToString("") { it.text })
        assertEquals(Triple("https://example.com", 4, 15), d.linksOf(p.id).single())
        val r = DocxDocument.open(d.serialize()).blocks.filterIsInstance<Paragraph>().first()
        assertTrue(r.editable)
        assertEquals("https://example.com", r.runs.last().link)
        assertNull(r.runs.first().link)
    }

    @Test fun ruleAndPageBreakAreInsertedAfterTheCaretParagraph() {
        val d = doc("Above")
        val p = first(d)
        val rule = d.insertRule(p.id)
        assertTrue((d.blocks().first { it.id == rule } as UiBlock.Para).rule)
        val next = d.insertPageBreak(rule)
        val all = d.blocks()
        val pb = all[all.indexOfFirst { it.id == rule } + 1] as UiBlock.Para
        assertTrue(pb.pageBreak)
        // the caret goes to a new empty paragraph after the break (nothing editable came after it)
        assertEquals(all.last().id, next)
        assertFalse((all.last() as UiBlock.Para).pageBreak)
        val xml = part(d.serialize(), "word/document.xml")
        assertTrue(xml.contains("<w:pBdr><w:bottom w:val=\"single\""))
        assertTrue(xml.contains("<w:br w:type=\"page\"/>"))
    }

    @Test fun superscriptAndSubscriptRoundTrip() {
        val d = doc("H2O and x2")
        val p = first(d)
        assertTrue(d.formatRange(p.id, 1, 2) { sp, a, b, _ -> io.embodit.spatialoffice.core.text.RichText.setVertAlign(sp, a, b, "subscript") })
        assertTrue(d.formatRange(p.id, 9, 10) { sp, a, b, _ -> io.embodit.spatialoffice.core.text.RichText.setVertAlign(sp, a, b, "superscript") })
        val r = DocxDocument.open(d.serialize()).blocks.filterIsInstance<Paragraph>().first()
        assertEquals(listOf(null, "subscript", null, "superscript"), r.runs.map { it.vertAlign })
    }

    private fun table(d: DocSession) = DocxDocument.open(d.serialize()).blocks.filterIsInstance<TableBlock>().single()

    @Test fun tableRowsColumnsAndMergeFollowTheCaret() {
        val d = doc("Before")
        val t = d.insertTable(first(d).id, 2, 2)
        assertEquals(Triple(t, 0, 0), d.caretCell())
        assertTrue(d.tableOp("row-above"))
        assertEquals(Triple(t, 1, 0), d.tableCaret)          // the caret stays on the same cell
        assertTrue(d.tableOp("col-left"))
        assertEquals(Triple(t, 1, 1), d.tableCaret)
        assertEquals(3, table(d).rows.size)
        assertEquals(3, table(d).rows[0].size)
        d.focusCell(t, 1, 1)
        assertTrue(d.tableOp("merge-right"))
        assertEquals(listOf(CellMerge(1, 1, 1, 2)), table(d).merges)
        d.focusCell(t, 0, 0)
        assertTrue(d.tableOp("merge-down"))
        assertEquals(setOf(CellMerge(1, 1, 1, 2), CellMerge(0, 0, 2, 1)), table(d).merges.toSet())
        assertTrue(part(d.serialize(), "word/document.xml").contains("<w:gridSpan w:val=\"2\"/>"))
        // nothing to merge with: no change, no undo step
        d.focusCell(t, 2, 0)
        assertFalse(d.tableOp("merge-down"))
        // delete column and row; undo brings them back
        d.focusCell(t, 2, 2)
        assertTrue(d.tableOp("col-delete"))
        assertEquals(Triple(t, 2, 1), d.tableCaret)
        assertTrue(d.tableOp("row-delete"))
        assertEquals(2, table(d).rows.size)
        d.undo(); d.undo()
        assertEquals(3, table(d).rows.size)
        assertEquals(3, table(d).rows[0].size)
    }
}
