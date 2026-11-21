package io.embodit.spatialoffice.core.xlsx

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** M3 (v0.5.0): s24 fill down / right / series, s25 merge, s26 borders, s27 functions. */
class XlsxM3Test {

    /** The data the expected-value table (apps/spatial-office/build/m3-expected.md) is computed on. */
    private fun data(): XlsxWorkbook {
        val wb = XlsxWorkbook.blank()
        val rows = listOf(
            listOf("Region", "Amount", "Date"),
            listOf("East", "100", "2026-01-15"),
            listOf("West", "250", "2026-03-31"),
            listOf("East", "75", "2026-11-21"),
            listOf("North", "40", "2025-12-31"),
        )
        for ((r, line) in rows.withIndex()) for ((c, v) in line.withIndex()) wb.setCell(0, r, c, v)
        return wb
    }

    /** Formula → expected value, worked out by hand (not by this engine); QA checks the same table in the app. */
    val EXPECTED: List<Pair<String, Any>> = listOf(
        "=SUMIFS(B2:B5,A2:A5,\"East\")" to 175.0,
        "=SUMIFS(B2:B5,A2:A5,\"East\",B2:B5,\">80\")" to 100.0,
        "=COUNTIFS(A2:A5,\"East\",B2:B5,\"<100\")" to 1.0,
        "=AVERAGEIFS(B2:B5,A2:A5,\"<>North\")" to 425.0 / 3,
        "=MAXIFS(B2:B5,A2:A5,\"East\")" to 100.0,
        "=MINIFS(B2:B5,A2:A5,\"East\")" to 75.0,
        "=XLOOKUP(\"West\",A2:A5,B2:B5)" to 250.0,
        "=XLOOKUP(\"South\",A2:A5,B2:B5,\"none\")" to "none",
        "=XLOOKUP(90,B2:B5,A2:A5,,1)" to "East",
        "=INDEX(B2:B5,MATCH(\"North\",A2:A5,0))" to 40.0,
        "=IFNA(MATCH(\"X\",A2:A5,0),\"no\")" to "no",
        "=TEXT(1234.5,\"#,##0.00\")" to "1,234.50",
        "=TEXT(C2,\"yyyy-mm-dd\")" to "2026-01-15",
        "=TEXT(C3,\"mmm d, yyyy\")" to "Mar 31, 2026",
        "=TEXT(0.256,\"0.0%\")" to "25.6%",
        "=DATEDIF(C2,C4,\"d\")" to 310.0,
        "=DATEDIF(C5,C4,\"m\")" to 10.0,
        "=DATEDIF(C5,C4,\"y\")" to 0.0,
        "=DATEDIF(C2,C4,\"md\")" to 6.0,
        "=DATEDIF(C5,C4,\"yd\")" to 325.0,
        "=YEAR(C3)" to 2026.0,
        "=MONTH(C3)" to 3.0,
        "=DAY(C3)" to 31.0,
        "=WEEKDAY(C4)" to 7.0,
        "=WEEKDAY(C4,2)" to 6.0,
        "=TEXT(EDATE(C3,1),\"yyyy-mm-dd\")" to "2026-04-30",
        "=TEXT(EOMONTH(C2,1),\"yyyy-mm-dd\")" to "2026-02-28",
        "=TEXT(DATE(2026,14,1),\"yyyy-mm-dd\")" to "2027-02-01",
        "=DAYS(C4,C2)" to 310.0,
        "=HOUR(TIME(13,45,30))" to 13.0,
        "=MINUTE(TIME(13,45,30))" to 45.0,
        "=SECOND(TIME(13,45,30))" to 30.0,
        "=FIND(\"st\",A2)" to 3.0,
        "=SEARCH(\"ST\",A3)" to 3.0,
        "=SUBSTITUTE(\"a-b-c\",\"-\",\"+\",2)" to "a-b+c",
        "=TEXTJOIN(\", \",TRUE,A2:A5)" to "East, West, East, North",
        "=VALUE(\"12.5%\")" to 0.125,
        "=PROPER(\"hello wORLD\")" to "Hello World",
        "=REPLACE(\"abcdef\",2,3,\"XY\")" to "aXYef",
        "=CHAR(65)" to "A",
        "=CODE(\"a\")" to 97.0,
        "=STDEV(B2:B5)" to 92.5,
        "=VAR.S(B2:B5)" to 8556.25,
        "=VAR.P(B2:B5)" to 6417.1875,
        "=RANK(75,B2:B5)" to 3.0,
        "=RANK.EQ(75,B2:B5,1)" to 2.0,
        "=SUMPRODUCT(B2:B3,B4:B5)" to 17500.0,
        "=IFS(B3>200,\"big\",TRUE,\"small\")" to "big",
        "=SWITCH(A5,\"East\",1,\"North\",3,0)" to 3.0,
        "=XOR(TRUE,FALSE)" to true,
        "=ISBLANK(D1)" to true,
        "=ISNUMBER(B2)" to true,
        "=ISTEXT(B2)" to false,
        "=ISERROR(1/0)" to true,
        "=CHOOSE(2,\"a\",\"b\",\"c\")" to "b",
        "=ROWS(A2:C5)" to 4.0,
        "=COLUMNS(A2:C5)" to 3.0,
        "=ROW(C7)" to 7.0,
        "=ROUND(PI(),4)" to 3.1416,
        "=LOG(1000)" to 3.0,
        "=LN(EXP(2))" to 2.0,
        "=LOG10(0.01)" to -2.0,
        "=TRUNC(-3.789,1)" to -3.7,
    )

    @Test fun functionsMatchTheHandWorkedTable() {
        val wb = data()
        for ((i, e) in EXPECTED.withIndex()) wb.setCell(0, 10 + i, 5, e.first)
        val re = XlsxWorkbook.open(wb.save())
        for (book in listOf(wb, re)) for ((i, e) in EXPECTED.withIndex()) {
            val c = book.cell(0, 10 + i, 5)
            when (val want = e.second) {
                is Double -> { assertEquals(e.first + " → " + c.display, CellKind.NUMBER, c.kind); assertEquals(e.first, want, c.number!!, 1e-9) }
                is Boolean -> assertEquals(e.first, if (want) "TRUE" else "FALSE", c.display)
                else -> assertEquals(e.first, want, c.display)
            }
        }
        assertTrue("about 90 functions or more: ${Formula.KNOWN.size}", Formula.KNOWN.size >= 90)
    }

    @Test fun newerFunctionsAreStoredWithTheXlfnPrefixAndShownWithout() {
        val wb = data()
        wb.setCell(0, 0, 5, "=xlookup(\"West\",A2:A5,B2:B5)")
        wb.setCell(0, 1, 5, "=textjoin(\"/\",TRUE,A2:A3)")
        wb.setCell(0, 2, 5, "=sumifs(B2:B5,A2:A5,\"East\")")
        assertEquals("=XLOOKUP(\"West\",A2:A5,B2:B5)", wb.cell(0, 0, 5).input)
        val out = wb.save()
        val sheet = Fx.text(out, Fx.SHEET1)
        assertTrue(sheet, sheet.contains("<f>_xlfn.XLOOKUP(&quot;West&quot;,A2:A5,B2:B5)</f>") || sheet.contains("<f>_xlfn.XLOOKUP(\"West\",A2:A5,B2:B5)</f>"))
        assertTrue(sheet.contains("_xlfn.TEXTJOIN("))
        assertTrue("SUMIFS is a 2007 function: no prefix", sheet.contains("<f>SUMIFS("))
        val re = XlsxWorkbook.open(out)
        assertEquals("=XLOOKUP(\"West\",A2:A5,B2:B5)", re.cell(0, 0, 5).input)
        assertEquals(250.0, re.cell(0, 0, 5).number!!, 0.0)
        assertEquals("East/West", re.cell(0, 1, 5).display)
        // committing what the formula bar shows changes nothing
        re.setCell(0, 0, 5, re.cell(0, 0, 5).input)
        assertFalse(re.dirty)
    }

    @Test fun fillDownMovesFormulasAndFillRightCopiesTheFirstColumn() {
        val wb = data()
        wb.setCell(0, 1, 3, "=B2*2")
        assertEquals(3 * 1, wb.fill(0, 1, 3, 4, 3, down = true) / 1)
        assertEquals("=B5*2", wb.cell(0, 4, 3).input)
        assertEquals(80.0, wb.cell(0, 4, 3).number!!, 0.0)
        // one row selected: takes the row above (Ctrl+D)
        wb.setCell(0, 6, 1, "7")
        assertEquals(1, wb.fill(0, 7, 1, 7, 1, down = true))
        assertEquals("7", wb.cell(0, 7, 1).input)
        // Ctrl+R across
        wb.setCell(0, 8, 0, "=A$1")
        wb.fill(0, 8, 0, 8, 2, down = false)
        assertEquals("=C$1", wb.cell(0, 8, 2).input)
        assertEquals("Date", wb.cell(0, 8, 2).display)
    }

    @Test fun seriesContinueNumbersDatesAndNumberedText() {
        val wb = XlsxWorkbook.blank()
        wb.setCell(0, 0, 0, "1"); wb.setCell(0, 1, 0, "2")
        wb.setCell(0, 0, 1, "2026-11-21")
        wb.setCell(0, 0, 2, "Q1")
        wb.setCell(0, 0, 3, "5"); wb.setCell(0, 1, 3, "15")
        wb.setCell(0, 0, 4, "text")
        val n = wb.fillSeries(0, 0, 0, 5, 4, down = true)
        assertTrue(n > 0)
        assertEquals(listOf("1", "2", "3", "4", "5", "6"), (0..5).map { wb.cell(0, it, 0).input })
        assertEquals(listOf("2026-11-21", "2026-11-22", "2026-11-23"), (0..2).map { wb.cell(0, it, 1).display })
        assertEquals(NumFmt.DATE, wb.cell(0, 5, 1).fmt)
        assertEquals(listOf("Q1", "Q2", "Q3", "Q4", "Q5", "Q6"), (0..5).map { wb.cell(0, it, 2).input })
        assertEquals("45", wb.cell(0, 4, 3).input)
        assertEquals("text", wb.cell(0, 3, 4).input)
        // Fill series right
        val w2 = XlsxWorkbook.blank()
        w2.setCell(0, 0, 0, "10"); w2.setCell(0, 0, 1, "8")
        w2.fillSeries(0, 0, 0, 0, 4, down = false)
        assertEquals(listOf("10", "8", "6", "4", "2"), (0..4).map { w2.cell(0, 0, it).input })
    }

    @Test fun mergeWritesMergeCellsKeepsTopLeftAndOnlyTouchesTheSheet() {
        val src = Fx.bytes("xlsx-sales.xlsx")
        val wb = XlsxWorkbook.open(src)
        val keep = wb.cell(0, 1, 1).display
        assertTrue(wb.mergeCells(0, 1, 1, 2, 2))
        assertEquals("", wb.cell(0, 2, 2).input)
        assertEquals(keep, wb.cell(0, 1, 1).display)
        assertEquals("B2:C3", wb.mergeAt(0, 2, 1)!!.let { A1.rangeName(it[0], it[1], it[2], it[3]) })
        val out = wb.save()
        // sheet 2 holds formulas that read the emptied cells: their cached values follow; styles and the rest stay
        assertEquals(setOf(Fx.SHEET1, Fx.SHEET2), Fx.changedParts(src, out))
        val sheet = Fx.text(out, Fx.SHEET1)
        assertTrue(sheet, sheet.contains("<mergeCell ref=\"B2:C3\"/>"))
        val mc = sheet.indexOf("<mergeCells"); val sd = sheet.indexOf("</sheetData>")
        assertTrue("mergeCells comes after sheetData", mc > sd)
        val pm = sheet.indexOf("<pageMargins")
        if (pm >= 0) assertTrue("and before pageMargins", mc < pm)
        val re = XlsxWorkbook.open(out)
        val base = XlsxWorkbook.open(src).merges(0).map { A1.rangeName(it[0], it[1], it[2], it[3]) } // the fixture has its own
        assertEquals(base + "B2:C3", re.merges(0).map { A1.rangeName(it[0], it[1], it[2], it[3]) })
        assertTrue("the file's merge is kept", sheet.contains("<mergeCell ref=\"${base[0]}\"/>"))
        // a merge that touches it takes it in; unmerge splits it
        assertTrue(re.mergeCells(0, 2, 2, 3, 3))
        assertEquals(base + "B2:D4", re.merges(0).map { A1.rangeName(it[0], it[1], it[2], it[3]) })
        assertTrue(re.unmergeCells(0, 3, 3, 3, 3))
        assertEquals(base, re.merges(0).map { A1.rangeName(it[0], it[1], it[2], it[3]) })
        assertFalse(re.unmergeCells(0, 30, 30, 30, 30))
        val re2 = XlsxWorkbook.open(re.save())
        assertEquals(base, re2.merges(0).map { A1.rangeName(it[0], it[1], it[2], it[3]) })
    }

    @Test fun mergesFollowInsertedRows() {
        val wb = data()
        wb.mergeCells(0, 1, 0, 2, 1)
        wb.insertRowBelow(0, 0)
        assertEquals("A3:B4", wb.merges(0)[0].let { A1.rangeName(it[0], it[1], it[2], it[3]) })
        val re = XlsxWorkbook.open(wb.save())
        assertEquals("A3:B4", re.merges(0)[0].let { A1.rangeName(it[0], it[1], it[2], it[3]) })
    }

    @Test fun outlineBordersReadBackAndUntouchedPartsStay() {
        val src = Fx.bytes("xlsx-sales.xlsx")
        val wb = XlsxWorkbook.open(src)
        // outline of B2:C3: each cell gets the sides on the edge
        val L = Styles.LEFT; val R = Styles.RIGHT; val T = Styles.TOP; val B = Styles.BOTTOM
        wb.setBorders(0, 1, 1, L or T); wb.setBorders(0, 1, 2, R or T)
        wb.setBorders(0, 2, 1, L or B); wb.setBorders(0, 2, 2, R or B)
        val out = wb.save()
        assertEquals(setOf(Fx.SHEET1, Fx.STYLES), Fx.changedParts(src, out))
        val st = Fx.text(out, Fx.STYLES)
        assertTrue(st, st.contains("<left style=\"thin\"><color auto=\"1\"/></left><right/><top style=\"thin\"><color auto=\"1\"/></top><bottom/>"))
        val re = XlsxWorkbook.open(out)
        assertEquals(L or T, re.borderMask(0, 1, 1))
        assertEquals(R or B, re.borderMask(0, 2, 2))
        assertEquals(0, re.borderMask(0, 3, 3))
        // the cell's value and other formats survive
        assertEquals(wb.cell(0, 1, 1).display, re.cell(0, 1, 1).display)
        re.setBorders(0, 1, 1, 0)
        assertEquals(0, XlsxWorkbook.open(re.save()).borderMask(0, 1, 1))
    }

    @Test fun textFormatDates() {
        val d = Num.serial(2026, 11, 21, false)!! + (15 * 3600 + 4 * 60 + 5) / 86400.0
        assertEquals("21/11/26 15:04:05", TextFormat.format(d, "dd/mm/yy hh:mm:ss", false))
        assertEquals("Saturday, November 21", TextFormat.format(d, "dddd, mmmm d", false))
        assertEquals("3:04 PM", TextFormat.format(d, "h:mm AM/PM", false))
        assertEquals("2026年11月21日", TextFormat.format(d, "yyyy\"年\"m\"月\"d\"日\"", false))
        assertNull(null)
    }
}
