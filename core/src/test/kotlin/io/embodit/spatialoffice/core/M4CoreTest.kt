package io.embodit.spatialoffice.core

import io.embodit.spatialoffice.core.docx.DocxDocument
import io.embodit.spatialoffice.core.docx.PackageAsserts.assertConsistent
import io.embodit.spatialoffice.core.docx.PackageAsserts.changedParts
import io.embodit.spatialoffice.core.docx.PackageAsserts.fixture
import io.embodit.spatialoffice.core.docx.PackageAsserts.parts
import io.embodit.spatialoffice.core.docx.PackageAsserts.text
import io.embodit.spatialoffice.core.docx.ParaStyle
import io.embodit.spatialoffice.core.docx.Paragraph
import io.embodit.spatialoffice.core.docx.TableBlock
import io.embodit.spatialoffice.core.layout.Arrange
import io.embodit.spatialoffice.core.layout.UnitBox
import io.embodit.spatialoffice.core.pptx.PptxDeck
import io.embodit.spatialoffice.core.templates.DeckTemplate
import io.embodit.spatialoffice.core.templates.DocTemplate
import io.embodit.spatialoffice.core.templates.Templates
import io.embodit.spatialoffice.core.xlsx.FilterRule
import io.embodit.spatialoffice.core.xlsx.FormulaHints
import io.embodit.spatialoffice.core.xlsx.SortPlan
import io.embodit.spatialoffice.core.xlsx.XlsxWorkbook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** v0.6.0 (M4): s28 function hints, s29 filters, s30 freeze columns, s31 multi-column sort, s42 hidden rows / columns, p29 groups, p25 align / distribute / snap, d26 / p27 templates. */
class M4CoreTest {

    private fun data(): XlsxWorkbook {
        val wb = XlsxWorkbook.blank()
        val rows = listOf(
            listOf("部门", "姓名", "金额"),
            listOf("销售", "张三", "1200"),
            listOf("研发", "李四", "800"),
            listOf("销售", "王五", "3000"),
            listOf("研发", "赵六", "1500"),
            listOf("市场", "钱七", "1000"),
        )
        for ((r, line) in rows.withIndex()) for ((c, v) in line.withIndex()) wb.setCell(0, r, c, v)
        return wb
    }

    // ---- s28

    @Test fun everyKnownFunctionHasASignature() {
        val missing = FormulaHints.SIGNATURES.keys.let { k -> listOf("SUM", "XLOOKUP", "TEXTJOIN").filter { it !in k } }
        assertTrue(missing.toString(), missing.isEmpty())
        // all functions the evaluator knows are offered with their arguments
        for (f in listOf("SUMIFS", "IFNA", "LOG10", "RANK.EQ")) assertTrue(f, FormulaHints.SIGNATURES[f] != null)
    }

    @Test fun typingSuOffersSumFirstAndTabCompletesIt() {
        val s = FormulaHints.suggest("=SU", 3)
        assertEquals("SUM", s.first())
        assertTrue(s.contains("SUMIF"))
        val (text, caret) = FormulaHints.complete("=SU", 3, s.first())!!
        assertEquals("=SUM(", text); assertEquals(5, caret)
        // inside the brackets the arguments are shown, the comma counts
        assertEquals("SUM" to 0, FormulaHints.argHint("=SUM(", 5))
        assertEquals("SUMIF" to 2, FormulaHints.argHint("=SUMIF(A1:A5,\">3\",", 19))
        assertEquals("ROUND" to 1, FormulaHints.argHint("=ROUND(SUM(A1,B1),", 18))
    }

    @Test fun noHintForCellReferencesQuotedTextOrPlainValues() {
        assertTrue(FormulaHints.suggest("=B2", 3).isEmpty())
        assertTrue(FormulaHints.suggest("=\"SU", 4).isEmpty())
        assertTrue(FormulaHints.suggest("SU", 2).isEmpty())
        assertNull(FormulaHints.argHint("=1+2", 4))
        assertEquals("=SUM(A1)", FormulaHints.complete("=S(A1)", 2, "SUM")!!.first)
    }

    // ---- s29

    @Test fun filterRulesCompareNumbersAndText() {
        val wb = data()
        val gt = FilterRule(2, FilterRule.Op.GT, "1000")
        val kept = (1..5).filter { gt.pass(wb.cell(0, it, 2)) }
        assertEquals(listOf(1, 3, 4), kept)
        assertEquals(listOf(5), (1..5).filter { FilterRule(2, FilterRule.Op.LE, "1,000").pass(wb.cell(0, it, 2)) }.filter { it == 5 })
        assertEquals(listOf(2), (1..5).filter { FilterRule(1, FilterRule.Op.CONTAINS, "李").pass(wb.cell(0, it, 1)) })
        assertEquals(listOf(1, 3, 5), (1..5).filter { FilterRule(0, FilterRule.Op.VALUES, values = setOf("销售", "市场")).pass(wb.cell(0, it, 0)) })
        assertFalse(FilterRule(1, FilterRule.Op.GT, "1").pass(wb.cell(0, 1, 1)))
        assertEquals(0.12, FilterRule.parseNumber("12%")!!, 1e-9)
    }

    // ---- s31

    @Test fun sortByDepartmentThenAmount() {
        val wb = data()
        assertTrue(wb.sortRowsBy(0, listOf(0 to true, 2 to false), 1, 5))
        val got = (1..5).map { wb.cell(0, it, 0).display + ":" + wb.cell(0, it, 2).display }
        // text by pinyin: 市场 (shi) < 销售 (xiao) < 研发 (yan)
        assertEquals(listOf("市场:1000", "销售:3000", "销售:1200", "研发:1500", "研发:800"), got)
        val header = (0..2).map { wb.cell(0, 0, it) }
        val second = (0..2).map { wb.cell(0, 1, it) }
        assertTrue(SortPlan.looksLikeHeader(header, second))
        assertFalse(SortPlan.looksLikeHeader(second, header))
    }

    // ---- s30 / s42 (saved and read back)

    @Test fun freezeAtB2AndHideColumnCAreWrittenAndReadBack() {
        val wb = data()
        wb.setFreeze(0, 1, 1)
        assertEquals(1, wb.setColsHidden(0, 2, 2, true))
        assertEquals(2, wb.setRowsHidden(0, 3, 4, true))
        val bytes = wb.save()
        val sheet = text(bytes, "xl/worksheets/sheet1.xml")
        assertTrue(sheet, sheet.contains("<pane xSplit=\"1\" ySplit=\"1\" topLeftCell=\"B2\" activePane=\"bottomRight\" state=\"frozen\"/>"))
        assertTrue(sheet, Regex("<col [^>]*min=\"3\" max=\"3\"[^>]*hidden=\"1\"").containsMatchIn(sheet))
        assertTrue(sheet, Regex("<row r=\"4\"[^>]*hidden=\"1\"").containsMatchIn(sheet))
        val back = XlsxWorkbook.open(bytes)
        assertEquals(1, back.frozenRows(0)); assertEquals(1, back.frozenCols(0))
        assertTrue(back.isColHidden(0, 2)); assertFalse(back.isColHidden(0, 1))
        assertEquals(listOf(3, 4), back.hiddenRows(0))
        // and shown again
        back.setColsHidden(0, 0, 5, false); back.setRowsHidden(0, 0, 10, false); back.setFreeze(0, 0, 2)
        val again = text(back.save(), "xl/worksheets/sheet1.xml")
        assertFalse(again.contains("hidden=\"1\""))
        assertTrue(again, again.contains("<pane xSplit=\"2\" topLeftCell=\"C1\" activePane=\"topRight\" state=\"frozen\"/>"))
    }

    @Test fun hidingAColumnInAWideSpanSplitsItAndKeepsTheOtherParts() {
        val before = fixture("xlsx-sales.xlsx")
        val wb = XlsxWorkbook.open(before)
        wb.setColsHidden(0, 1, 1, true)
        val after = wb.save()
        // only the sheet changes
        assertEquals(setOf("xl/worksheets/sheet1.xml"), changedParts(before, after).filter { !it.startsWith("docProps") }.toSet())
        assertTrue(XlsxWorkbook.open(after).isColHidden(0, 1))
        assertFalse(XlsxWorkbook.open(after).isColHidden(0, 0))
    }

    // ---- p29

    @Test fun threeShapesGroupedKeepTheirOffsetsAndMoveTogether() {
        val d = PptxDeck.blank()
        val ids = listOf(100L, 200L, 300L).mapIndexed { i, y -> d.addShape(0, "rect", 1_000_000L + i * 50_000L, y * 10_000L, 800_000L, 500_000L, 0x2563EB) }
        // left align + distribute across first (as the app does through setShapeBox)
        val boxes = ids.map { id -> d.slides[0].shapes.first { it.id == id }.let { UnitBox(it.x, it.y, it.w, it.h) } }
        val aligned = Arrange.distribute(Arrange.align(boxes, Arrange.Align.LEFT), horizontal = true)
        ids.forEachIndexed { i, id -> val b = aligned[i]; d.setShapeBox(0, id, b.x, b.y, b.w, b.h) }
        val gid = d.groupShapes(0, ids)
        val g = d.slides[0].shapes.first { it.id == gid }
        assertEquals(3, g.children!!.size)
        assertEquals(1_000_000L, g.x)
        // move the group: the children's own a:off stay, the group's off moves
        d.setShapeBox(0, gid, g.x + 500_000L, g.y, g.w, g.h)
        val bytes = d.save()
        assertConsistent(bytes)
        val x = text(bytes, "ppt/slides/slide1.xml")
        val grp = Regex("<p:grpSp>.*</p:grpSp>").find(x)!!.value
        val offs = Regex("<a:off x=\"(\\d+)\"").findAll(grp).map { it.groupValues[1].toLong() }.toList()
        // group off + chOff... the first a:off is the group's own, the three after chOff are the children's
        val children = Regex("<p:sp[ >].*?</p:sp>").findAll(grp).map { Regex("<a:off x=\"(\\d+)\"").find(it.value)!!.groupValues[1].toLong() }.toList()
        assertEquals(listOf(1_000_000L, 1_000_000L, 1_000_000L), children)
        assertEquals(1_500_000L, offs.first())
        val back = PptxDeck.open(bytes).slides[0].shapes.first { it.id == gid }
        assertEquals(listOf(1_500_000L, 1_500_000L, 1_500_000L), back.children!!.map { it.x })
        // and apart again: back in slide coordinates
        val d2 = PptxDeck.open(bytes)
        assertEquals(ids, d2.ungroupShape(0, gid))
        assertEquals(listOf(1_500_000L, 1_500_000L, 1_500_000L), ids.map { id -> d2.slides[0].shapes.first { it.id == id }.x })
        assertNull(d2.slides[0].shapes.firstOrNull { it.id == gid })
    }

    @Test fun groupingAPlaceholderWritesItsBoxIntoIt() {
        val d = PptxDeck.blank()
        val title = d.slides[0].shapes.first().id
        val r = d.addShape(0, "ellipse", 100, 100, 1000, 1000, 0xFF0000)
        val gid = d.groupShapes(0, listOf(title, r))
        val x = text(d.save(), "ppt/slides/slide1.xml")
        assertFalse(Regex("<p:grpSp>.*<p:ph ").containsMatchIn(x))
        assertEquals(2, d.slides[0].shapes.first { it.id == gid }.children!!.size)
    }

    // ---- p25

    @Test fun alignDistributeAndSnap() {
        val bs = listOf(UnitBox(10, 0, 20, 10), UnitBox(50, 30, 40, 10), UnitBox(200, 60, 20, 10))
        assertEquals(listOf(10L, 10L, 10L), Arrange.align(bs, Arrange.Align.LEFT).map { it.x })
        assertEquals(listOf(200L, 180L, 200L), Arrange.align(bs, Arrange.Align.RIGHT).map { it.x })
        assertEquals(listOf(105L, 95L, 105L), Arrange.align(bs, Arrange.Align.CENTER).map { it.x })
        assertEquals(listOf(0L, 0L, 0L), Arrange.align(bs, Arrange.Align.TOP).map { it.y })
        // span 10..220, widths 80: gap 65
        assertEquals(listOf(10L, 95L, 200L), Arrange.distribute(bs, true).map { it.x })
        assertEquals(listOf(0L, 30L, 60L), Arrange.distribute(bs, false).map { it.y })
        // two boxes: nothing to distribute
        assertEquals(bs.take(2), Arrange.distribute(bs.take(2), true))
        val s = Arrange.snap(UnitBox(102, 300, 40, 40), listOf(UnitBox(100, 0, 50, 50)), 1000, 1000, 5)
        assertEquals(100L, s.box.x)
        assertEquals(listOf(Arrange.Guide(true, 100)), s.guides)
        val c = Arrange.snap(UnitBox(478, 300, 40, 40), emptyList(), 1000, 1000, 5)
        assertEquals(480L, c.box.x) // centre of the slide
        assertTrue(Arrange.snap(UnitBox(300, 300, 40, 40), emptyList(), 1000, 1000, 5).guides.isEmpty())
    }

    // ---- d26 / p27

    @Test fun everyDocTemplateIsAPlainDocxInBothLanguages() {
        for (t in DocTemplate.values()) for (zh in listOf(true, false)) {
            val bytes = Templates.doc(t, zh)
            assertConsistent(bytes)
            val d = DocxDocument.open(bytes)
            assertTrue((d.blocks.first() as Paragraph).runs.joinToString("") { it.text }.isNotBlank())
            assertEquals(ParaStyle.TITLE, (d.blocks.first() as Paragraph).style)
            assertTrue(d.blocks.count { it is Paragraph && (it.style == ParaStyle.H1) } >= 3)
            // nothing of this app's own: only the standard parts
            val names = parts(bytes).keys
            assertTrue(names.toString(), names.all { it.startsWith("word/") || it.startsWith("docProps/") || it.startsWith("_rels/") || it == "[Content_Types].xml" })
            assertFalse(String(bytes.let { parts(it).getValue("word/document.xml") }, Charsets.UTF_8).contains("spatialoffice"))
        }
        val weekly = DocxDocument.open(Templates.doc(DocTemplate.WEEKLY, true))
        assertTrue(weekly.blocks.any { it is Paragraph && it.style == ParaStyle.BULLET })
        assertTrue(DocxDocument.open(Templates.doc(DocTemplate.MEETING, false)).blocks.any { it is TableBlock })
    }

    @Test fun everyDeckTemplateHasItsColoursAndSlides() {
        for (t in DeckTemplate.values()) for (zh in listOf(true, false)) {
            val bytes = Templates.deck(t, zh)
            assertConsistent(bytes)
            val d = PptxDeck.open(bytes)
            assertTrue(d.slides.size >= 4)
            assertEquals(Templates.deckColors(t).getValue("lt1"), d.colorScheme()["lt1"])
            assertEquals(t.zh.takeIf { zh } ?: t.en, t.title(zh))
            assertNotNull(d.firstTitle())
            assertTrue(d.slides.all { s -> s.shapes.any { it.fillRgb == Templates.deckColors(t)["accent1"] } })
        }
    }

    // ---- K1 (QA #15): after the Stage the windows keep their sides and the rehearsed one stands next to the home window

    @Test fun afterTheStageTheRehearsedWindowIsNextToHomeAndNoWindowChangesSides() {
        val ring = io.embodit.spatialoffice.core.layout.ArcRing()
        // QA #15: sheet right of home, document left, slides right of the sheet (~126°)
        val r = io.embodit.spatialoffice.core.layout.ArcRing()
        assertEquals("Home" to "right", r.place("Sheet", hub = "Home"))
        assertEquals("Home" to "left", r.place("Doc", hub = "Home"))
        assertEquals("Sheet" to "right", r.place("Slides", hub = "Home"))
        assertEquals(listOf("Doc", "Home", "Sheet", "Slides"), r.order)
        val plan = r.replan(r.order, "Home", listOf("Doc", "Sheet", "Slides"), first = "Slides")
        // the rehearsed slides open first, right beside home; the sheet stays right (outside it), the document stays left
        assertEquals(listOf("Slides" to ("Home" to "right"), "Doc" to ("Home" to "left"), "Sheet" to ("Slides" to "right")), plan)
        assertEquals(listOf("Doc", "Home", "Slides", "Sheet"), r.order)
        // the windows opening take exactly those places, whatever is up yet
        assertEquals("Home" to "right", r.place("Slides", hub = "Home") { false })
        assertEquals("Slides" to "right", r.place("Sheet", hub = "Home") { false })
        assertEquals("Home" to "left", r.place("Doc", hub = "Home") { false })
        // a window rehearsed from the left side comes back on the left, next to home
        val l = io.embodit.spatialoffice.core.layout.ArcRing()
        l.place("A", hub = "Home"); l.place("B", hub = "Home"); l.place("C", hub = "Home"); l.place("D", hub = "Home")
        assertEquals(listOf("D", "B", "Home", "A", "C"), l.order)
        val p2 = l.replan(l.order, "Home", listOf("A", "B", "C", "D"), first = "D")
        assertEquals("D" to ("Home" to "left"), p2.first())
        assertEquals(listOf("B", "D", "Home", "A", "C"), l.order)
    }
}
