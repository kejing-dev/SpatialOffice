package io.embodit.spatialoffice.core

import io.embodit.spatialoffice.core.docx.DocxDocument
import io.embodit.spatialoffice.core.docx.ImageBlock
import io.embodit.spatialoffice.core.docx.PackageAsserts.assertConsistent
import io.embodit.spatialoffice.core.docx.PackageAsserts.writeRoundTrip
import io.embodit.spatialoffice.core.docx.ParaStyle
import io.embodit.spatialoffice.core.docx.Paragraph
import io.embodit.spatialoffice.core.docx.Run
import io.embodit.spatialoffice.core.docx.TableBlock
import io.embodit.spatialoffice.core.pptx.PptxDeck
import io.embodit.spatialoffice.core.pptx.ShapeKind
import io.embodit.spatialoffice.core.pptx.SlideLayoutKind
import io.embodit.spatialoffice.core.text.Attr
import io.embodit.spatialoffice.core.text.RichText
import io.embodit.spatialoffice.core.text.Span
import io.embodit.spatialoffice.core.text.WordCount
import io.embodit.spatialoffice.core.xlsx.NumFmt
import io.embodit.spatialoffice.core.xlsx.XlsxWorkbook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/** v0.2.0 feature set (build/feature-matrix.md): every new edit survives save → reopen and leaves a consistent package. */
class V02FeaturesTest {

    // a 2 × 2 red PNG
    private val png: ByteArray = Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAIAAAACCAIAAAD91JpzAAAAFklEQVR4nGP8z8DAwMDAxMDAwMDAAAANHQEDasKb6QAAAABJRU5ErkJggg==",
    )

    // ------------------------------------------------------------------ spreadsheet

    private fun book(vararg cells: Pair<String, String>): XlsxWorkbook {
        val b = XlsxWorkbook.blank()
        for ((ref, v) in cells) {
            val (r, c) = io.embodit.spatialoffice.core.xlsx.A1.parse(ref)!!
            b.setCell(0, r, c, v)
        }
        return b
    }

    private fun XlsxWorkbook.v(ref: String): String {
        val (r, c) = io.embodit.spatialoffice.core.xlsx.A1.parse(ref)!!
        return cell(0, r, c).display
    }

    @Test fun formulaLibraryHasAtLeastFortyFunctions() {
        val known = Class.forName("io.embodit.spatialoffice.core.xlsx.Formula").getDeclaredField("INSTANCE").get(null).let { f ->
            @Suppress("UNCHECKED_CAST")
            f.javaClass.getMethod("getKNOWN").invoke(f) as Set<String>
        }
        assertTrue("functions: ${known.size}", known.size >= 40)
    }

    @Test fun newFunctionsEvaluate() {
        val b = book(
            "A1" to "apple", "A2" to "pear", "A3" to "apple", "A4" to "plum",
            "B1" to "3", "B2" to "5", "B3" to "7", "B4" to "10",
        )
        val f = listOf(
            "=COUNTIF(A1:A4,\"apple\")" to "2",
            "=COUNTIF(B1:B4,\">4\")" to "3",
            "=SUMIF(A1:A4,\"apple\",B1:B4)" to "10",
            "=AVERAGEIF(B1:B4,\">=5\")" to "7.333333333",
            "=COUNTIF(A1:A4,\"p*\")" to "2",
            "=MEDIAN(B1:B4)" to "6",
            "=LARGE(B1:B4,1)" to "10",
            "=SMALL(B1:B4,2)" to "5",
            "=LEFT(A2,2)" to "pe",
            "=RIGHT(A4,3)" to "lum",
            "=MID(\"spatial\",2,3)" to "pat",
            "=VLOOKUP(\"plum\",A1:B4,2,FALSE)" to "10",
            "=CEILING(4.2,1)" to "5",
            "=FLOOR(4.8,2)" to "4",
            "=SIGN(-3)" to "-1",
            "=EXACT(\"a\",\"A\")" to "FALSE",
            "=REPT(\"ab\",3)" to "ababab",
            "=COUNTBLANK(C1:C4)" to "4",
        )
        f.forEachIndexed { i, (formula, want) -> b.setCell(0, i, 5, formula); assertEquals(formula, want, b.cell(0, i, 5).display) }
        // and they survive save → reopen as formulas
        val again = XlsxWorkbook.open(b.save())
        assertEquals("=SUMIF(A1:A4,\"apple\",B1:B4)", again.cell(0, 2, 5).input)
        assertEquals("10", again.cell(0, 2, 5).display)
    }

    @Test fun deleteRowMovesCellsAndAdjustsFormulas() {
        val b = book("A1" to "1", "A2" to "2", "A3" to "3", "A4" to "=SUM(A1:A3)", "B1" to "=A2", "B4" to "=A3*2")
        b.deleteRow(0, 1)
        assertEquals("3", b.v("A2"))
        assertEquals("=SUM(A1:A2)", b.cell(0, 2, 0).input)
        assertEquals("4", b.v("A3"))
        assertEquals("=#REF!", b.cell(0, 0, 1).input)
        assertEquals("=A2*2", b.cell(0, 2, 1).input)
        val bytes = b.save()
        assertConsistent(bytes)
        writeRoundTrip("v02-delete-row.xlsx", bytes)
        val again = XlsxWorkbook.open(bytes)
        assertEquals("=SUM(A1:A2)", again.cell(0, 2, 0).input)
        assertEquals(3, again.rows(0))
    }

    @Test fun insertAndDeleteColumns() {
        val b = book("A1" to "1", "B1" to "2", "C1" to "=A1+B1")
        b.insertColumnRight(0, 0)
        assertEquals("", b.v("B1"))
        assertEquals("2", b.v("C1"))
        assertEquals("=A1+C1", b.cell(0, 0, 3).input)
        b.setCell(0, 0, 1, "10")
        b.deleteColumn(0, 2)
        assertEquals("=A1+#REF!", b.cell(0, 0, 2).input)
        val again = XlsxWorkbook.open(b.save().also { assertConsistent(it); writeRoundTrip("v02-columns.xlsx", it) })
        assertEquals("10", again.v("B1"))
    }

    @Test fun freezeFillFormatAndChartsRoundTrip() {
        val b = book("A1" to "", "B1" to "Q1", "C1" to "Q2", "A2" to "North", "B2" to "10", "C2" to "20", "A3" to "South", "B3" to "15", "C3" to "5")
        b.setFrozenRows(0, 1)
        b.setFill(0, 0, 1, 0xFFE08A)
        b.setBold(0, 0, 1, true)
        b.setFmt(0, 1, 1, NumFmt.CURRENCY)
        b.setFmt(0, 1, 2, NumFmt.PERCENT)
        b.setFmt(0, 2, 1, NumFmt.DATE)
        b.setFmt(0, 2, 2, NumFmt.OTHER)
        b.addChart(0, "A1:C3", "Sales", "bar")
        b.addChart(0, "A1:C3", "Trend", "line")
        b.addChart(0, "A1:C3", "Share", "pie")
        val bytes = b.save()
        assertConsistent(bytes)
        writeRoundTrip("v02-format-charts.xlsx", bytes)
        val again = XlsxWorkbook.open(bytes)
        assertEquals(1, again.frozenRows(0))
        assertEquals(0xFFE08A, again.fillRgb(0, 0, 1))
        assertTrue(again.cell(0, 0, 1).bold)
        assertEquals(NumFmt.CURRENCY, again.cell(0, 1, 1).fmt)
        assertEquals(NumFmt.PERCENT, again.cell(0, 1, 2).fmt)
        assertEquals(NumFmt.DATE, again.cell(0, 2, 1).fmt)
        assertEquals(listOf("bar", "line", "pie"), again.charts().map { it.type })
        // unfreeze and clear the fill
        again.setFrozenRows(0, 0)
        again.setFill(0, 0, 1, null)
        val third = XlsxWorkbook.open(again.save())
        assertEquals(0, third.frozenRows(0))
        assertNull(third.fillRgb(0, 0, 1))
    }

    // ------------------------------------------------------------------ document

    @Test fun strikeAlignImageTableRoundTrip() {
        val d = DocxDocument.blank()
        val first = d.blocks.first().id
        d.setParagraph(first, listOf(Run("Plan "), Run("old", strike = true), Run(" new", bold = true)), ParaStyle.H2, align = 1)
        val img = d.insertImageAfter(first, png, "png", 914400, 914400)
        val tbl = d.insertTableAfter(img, 2, 3)
        d.setTableCell(tbl, 0, 1, "Owner")
        d.setTableCell(tbl, 1, 2, "Done\nnext week")
        val bytes = d.save()
        assertConsistent(bytes)
        writeRoundTrip("v02-doc.docx", bytes)
        val again = DocxDocument.open(bytes)
        val p = again.blocks.first() as Paragraph
        assertEquals(ParaStyle.H2, p.style)
        assertEquals(1, p.align)
        assertTrue(p.runs.first { it.text == "old" }.strike)
        assertTrue(again.blocks.any { it is ImageBlock && again.partBytes(it.part)!!.contentEquals(png) })
        val t = again.blocks.filterIsInstance<TableBlock>().single()
        assertEquals(listOf("", "Owner", ""), t.rows[0])
        assertEquals("Done\nnext week", t.rows[1][2])
        // justified, then back to start
        again.setParagraph(p.id, p.runs, p.style, align = 3)
        assertEquals(3, (DocxDocument.open(again.save()).blocks.first() as Paragraph).align)
    }

    @Test fun findReplaceKeepsFormatAndCounts() {
        val spans = listOf(Span("Spatial "), Span("Office", bold = true), Span(" office"))
        val (out, n) = RichText.replaceAll(spans, "office", "Desk")
        assertEquals(2, n)
        assertEquals("Spatial Desk Desk", RichText.text(out))
        assertTrue(RichText.hasAttr(out, 8, 12, Attr.BOLD))
        assertEquals(listOf(0, 7), RichText.matches("ab ab", "AB").let { listOf(it[0], it[1] + 4) })
        val struck = RichText.setAttr(listOf(Span("abc")), 0, 2, Attr.STRIKE, true)
        assertTrue(struck.first().strike)
    }

    @Test fun wordCountMixesChineseAndLatin() {
        val r = WordCount.count("空间办公 v0.2 is here.\n第二段")
        assertEquals(4 + 3 + 3, r.words)
        assertEquals(2, r.paragraphs)
    }

    // ------------------------------------------------------------------ presentation

    @Test fun duplicateLayoutsPicturesShapesAndTheme() {
        val d = PptxDeck.blank()
        d.setShapeText(0, d.slides[0].shapes.first { it.kind == ShapeKind.TITLE }.id, listOf(io.embodit.spatialoffice.core.pptx.TextPara("Kickoff")))
        d.setNotes(0, "say hello")
        val copy = d.duplicateSlide(0)
        assertEquals(1, copy)
        assertEquals(2, d.slides.size)
        assertEquals("Kickoff", d.slides[1].shapes.first { it.kind == ShapeKind.TITLE }.paras.first().text)
        assertEquals("say hello", d.slides[1].notes)

        val two = d.addSlide(1, SlideLayoutKind.TWO_COLUMN)
        assertEquals(2, d.slides[two].shapes.count { it.kind == ShapeKind.TEXT })
        val blank = d.addSlide(two, SlideLayoutKind.BLANK)
        assertEquals(0, d.slides[blank].shapes.size)

        val pic = d.addPicture(blank, png, "png", 100000, 100000, 2000000, 2000000)
        val shp = d.addShape(blank, "ellipse", 3000000, 1000000, 1500000, 1500000, 0x2563EB)
        d.setShapeText(blank, shp, listOf(io.embodit.spatialoffice.core.pptx.TextPara("Go")))
        d.setShapeBox(blank, pic, 200000, 300000, 2500000, 2500000)
        val tmp = d.addShape(blank, "rect", 0, 0, 10, 10, 0)
        d.deleteShape(blank, tmp)

        d.setColorScheme(mapOf("lt1" to 0x111827, "dk1" to 0xF9FAFB, "accent1" to 0xF97316))
        val bytes = d.save()
        assertConsistent(bytes)
        writeRoundTrip("v02-deck.pptx", bytes)

        val again = PptxDeck.open(bytes)
        assertEquals(4, again.slides.size)
        val s = again.slides[3]
        val p = s.shapes.first { it.kind == ShapeKind.PICTURE }
        assertEquals(200000L, p.x); assertEquals(2500000L, p.w)
        assertNotNull(p.imagePart)
        val e = s.shapes.first { it.id == shp }
        assertEquals("ellipse", e.geom); assertEquals(0x2563EB, e.fillRgb); assertEquals("Go", e.paras.first().text)
        assertTrue(s.shapes.none { it.id == tmp })
        assertEquals(0x111827, again.colorScheme()["lt1"])
        assertEquals(0xF97316, again.colorScheme()["accent1"])
        assertEquals("say hello", again.slides[1].notes)
    }
}
