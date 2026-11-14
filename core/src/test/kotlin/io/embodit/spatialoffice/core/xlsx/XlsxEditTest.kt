package io.embodit.spatialoffice.core.xlsx

import io.embodit.spatialoffice.core.pkg.OoxmlPackage
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Patch-only save: what an edit may change in the package, and what it must leave byte-identical. */
class XlsxEditTest {

    @Test fun untouchedSaveKeepsEveryPartByteIdentical() {
        for (name in listOf("xlsx-sales.xlsx", "xlsx-cached.xlsx")) {
            val src = Fx.bytes(name)
            val wb = XlsxWorkbook.open(src)
            // reading everything must not make it dirty either
            for (s in wb.sheetNames.indices) for (r in 0 until wb.rows(s)) for (c in 0 until wb.cols(s)) wb.cell(s, r, c)
            wb.charts()
            wb.compat()
            wb.toCsv(0)
            assertFalse(wb.dirty)
            val saved = Fx.out(name.replace(".xlsx", "-untouched.xlsx"), wb.save())
            Fx.assertSameParts(src, saved)
            // and a second save of the same object
            Fx.assertSameParts(src, wb.save())
        }
    }

    @Test fun openRejectsFilesThatAreNotSpreadsheets() {
        for (bad in listOf(ByteArray(0), "not a zip at all".toByteArray(), OoxmlPackage.empty().also { it.putText("[Content_Types].xml", "<Types/>") }.write())) {
            try {
                XlsxWorkbook.open(bad)
                throw AssertionError("opened ${bad.size} bytes of something else")
            } catch (e: IllegalArgumentException) {
                // expected
            }
        }
    }

    @Test fun settingTheSameValueIsNotAnEdit() {
        val wb = Fx.cached()
        wb.setCell(0, 1, 1, "10")
        wb.setCell(0, 1, 3, "=B2*C2")
        wb.setCell(0, 0, 0, "Item")
        wb.setCell(0, 5, 3, "12.5%")
        wb.setCell(0, 5, 4, "2026-03-01")
        wb.setCell(0, 20, 20, "")
        wb.setBold(0, 0, 0, true)
        wb.setBold(0, 1, 1, false)
        wb.setFmt(0, 5, 3, NumFmt.PERCENT)
        assertFalse(wb.dirty)
        Fx.assertSameParts(Fx.bytes("xlsx-cached.xlsx"), wb.save())
    }

    @Test fun editOneNumberRewritesOnlyThatCellAndDependentCaches() {
        val src = Fx.bytes("xlsx-cached.xlsx")
        val wb = XlsxWorkbook.open(src)
        wb.setCell(0, 1, 2, "3") // C2: 2.5 → 3
        assertTrue(wb.dirty)
        assertEquals(30.0, wb.cell(0, 1, 3).number!!, 0.0)   // D2 = B2*C2
        assertEquals(155.0, wb.cell(0, 4, 3).number!!, 0.0)  // D5 = SUM(D2:D4)
        val saved = Fx.out("xlsx-cached-edit-number.xlsx", wb.save())
        assertFalse(wb.dirty)

        assertEquals(setOf(Fx.SHEET1), Fx.changedParts(src, saved))
        val before = Fx.text(src, Fx.SHEET1)
        val after = Fx.text(saved, Fx.SHEET1)
        val expected = before
            .replace("<c r=\"C2\" s=\"2\"><v>2.5</v></c>", "<c r=\"C2\" s=\"2\"><v>3</v></c>")
            .replace("<c r=\"D2\" s=\"2\"><f>B2*C2</f><v>25</v></c>", "<c r=\"D2\" s=\"2\"><f>B2*C2</f><v>30</v></c>")
            .replace("<c r=\"D5\" s=\"2\"><f>SUM(D2:D4)</f><v>150</v></c>", "<c r=\"D5\" s=\"2\"><f>SUM(D2:D4)</f><v>155</v></c>")
        assertEquals(expected, after)
        // prefix / suffix around the edited range are the file's own bytes
        val start = before.indexOf("<c r=\"C2\"")
        assertTrue(Fx.commonPrefix(before, after) >= start)
        val tail = before.substring(before.indexOf("</row>", before.indexOf("<c r=\"D5\"")))
        assertTrue(after.endsWith(tail))
        assertTrue(Fx.commonSuffix(before, after) >= tail.length)
        // the stale cache of A7 and the unknown function's cached value were not touched
        assertTrue(after.contains("<c r=\"A7\"><f>B5*2</f><v>999</v></c>"))
        assertTrue(after.contains("<c r=\"C5\"><f>STDEV(B2:B4)</f><v>10</v></c>"))

        val re = XlsxWorkbook.open(saved)
        assertEquals(3.0, re.cell(0, 1, 2).number!!, 0.0)
        assertEquals("¥3.00", re.cell(0, 1, 2).display)
        assertEquals(155.0, re.cell(0, 4, 3).number!!, 0.0)
        assertEquals(10.0, re.cell(0, 4, 2).number!!, 0.0)
        // the object that saved carries on from the saved state
        assertEquals(3.0, wb.cell(0, 1, 2).number!!, 0.0)
        Fx.assertSameParts(saved, wb.save())
    }

    @Test fun editOnSalesSheetUpdatesDependentsAcrossSheetsAndNothingElse() {
        val src = Fx.bytes("xlsx-sales.xlsx")
        val wb = XlsxWorkbook.open(src)
        wb.setCell(0, 1, 1, "500") // B2: 120 → 500
        assertEquals(645.0, wb.cell(0, 5, 1).number!!, 0.0)        // B6 = SUM(B2:B5)
        assertEquals(31312.5, wb.cell(0, 1, 13).number!!, 1e-9)    // N2
        assertEquals(61525.0, wb.cell(0, 5, 13).number!!, 1e-9)    // N6
        assertEquals("50.9%", wb.cell(0, 1, 14).display)           // O2 = N2/$N$6
        assertEquals(61525.0, wb.cell(1, 1, 5).number!!, 1e-9)     // Notes!F2 = Sales!N6
        assertEquals(5010.0, wb.cell(1, 2, 5).number!!, 1e-9)      // Notes!F3
        assertEquals(810.0, wb.cell(1, 5, 5).number!!, 1e-9)       // Notes!F6
        val saved = Fx.out("xlsx-sales-edit-number.xlsx", wb.save())

        assertEquals(setOf(Fx.SHEET1, Fx.SHEET2), Fx.changedParts(src, saved))
        val b1 = Fx.text(src, Fx.SHEET1)
        val a1 = Fx.text(saved, Fx.SHEET1)
        assertEquals(Fx.skeleton(b1), Fx.skeleton(a1))
        val changed = Fx.changedCells(b1, a1)
        assertTrue(changed.toString(), changed.containsAll(setOf("B2", "B6", "N2", "N6", "O2", "O3", "O4", "O5")))
        assertTrue(changed.toString(), (changed - setOf("B2", "B6", "N2", "N6", "O2", "O3", "O4", "O5", "O6")).isEmpty())
        assertEquals("<c r=\"B2\"><v>500</v></c>", Fx.cells(a1).getValue("B2"))
        assertEquals("<c r=\"B6\"><f>SUM(B2:B5)</f><v>645</v></c>", Fx.cells(a1).getValue("B6"))
        assertEquals("<c r=\"N6\" s=\"2\"><f>SUM(N2:N5)</f><v>61525</v></c>", Fx.cells(a1).getValue("N6"))
        assertTrue(Fx.commonPrefix(b1, a1) >= b1.indexOf("<c r=\"B2\""))
        val tail = b1.substring(b1.indexOf("<row r=\"8\">"))
        assertTrue(a1.endsWith(tail))

        val b2 = Fx.text(src, Fx.SHEET2)
        val a2 = Fx.text(saved, Fx.SHEET2)
        assertEquals(Fx.skeleton(b2), Fx.skeleton(a2))
        assertEquals(setOf("F2", "F3", "F6"), Fx.changedCells(b2, a2))
        assertEquals("<c r=\"F2\" s=\"2\"><f>Sales!N6</f><v>61525</v></c>", Fx.cells(a2).getValue("F2"))

        val re = XlsxWorkbook.open(saved)
        assertEquals(500.0, re.cell(0, 1, 1).number!!, 0.0)
        assertEquals(645.0, re.cell(0, 5, 1).number!!, 0.0)
        assertEquals(810.0, re.cell(1, 5, 5).number!!, 0.0)
    }

    @Test fun textEditGoesInAsInlineStringAndSharedStringsStayUntouched() {
        val src = Fx.bytes("xlsx-cached.xlsx")
        val wb = XlsxWorkbook.open(src)
        val text = "Visor <Pro> & \"co\" 日本語 ñ"
        wb.setCell(0, 1, 0, text) // A2 was a shared string
        assertEquals(text, wb.cell(0, 1, 0).display)
        assertEquals(CellKind.TEXT, wb.cell(0, 1, 0).kind)
        val saved = Fx.out("xlsx-cached-edit-text.xlsx", wb.save())
        assertEquals(setOf(Fx.SHEET1), Fx.changedParts(src, saved))
        assertArrayEquals(Fx.parts(src).getValue(Fx.SHARED), Fx.parts(saved).getValue(Fx.SHARED))
        val before = Fx.text(src, Fx.SHEET1)
        val after = Fx.text(saved, Fx.SHEET1)
        assertEquals(
            before.replace(
                "<c r=\"A2\" t=\"s\"><v>2</v></c>",
                "<c r=\"A2\" t=\"inlineStr\"><is><t>Visor &lt;Pro&gt; &amp; \"co\" 日本語 ñ</t></is></c>",
            ),
            after,
        )
        assertEquals(text, XlsxWorkbook.open(saved).cell(0, 1, 0).display)
    }

    @Test fun awkwardTextSurvivesARoundTrip() {
        val samples = listOf(
            "  leading and trailing  ", "line one\nline two", "tab\there", "carriage\r\nreturn", "a < b && c > d",
            "quote \" and 'apostrophe'", "emoji 😀 and 汉字 and Ωμέγα", "_x0041_ literal", "bell\u0007char", "=1+", "]]>", "&amp;",
        )
        val wb = XlsxWorkbook.blank()
        for ((i, s) in samples.withIndex()) wb.setCell(0, i, 0, s)
        val saved = Fx.out("xlsx-blank-text.xlsx", wb.save())
        val re = XlsxWorkbook.open(saved)
        for ((i, s) in samples.withIndex()) {
            assertEquals(s, re.cell(0, i, 0).display)
            assertEquals(CellKind.TEXT, re.cell(0, i, 0).kind)
            assertFalse(re.cell(0, i, 0).isFormula)
        }
        assertEquals(samples.size, re.rows(0))
    }

    @Test fun clearCellKeepsStyleAndDropsValue() {
        val src = Fx.bytes("xlsx-cached.xlsx")
        val wb = XlsxWorkbook.open(src)
        wb.setCell(0, 1, 2, "") // C2
        assertEquals(CellKind.EMPTY, wb.cell(0, 1, 2).kind)
        assertEquals(0.0, wb.cell(0, 1, 3).number!!, 0.0) // D2 = B2*C2 with C2 empty
        val saved = wb.save()
        assertEquals(setOf(Fx.SHEET1), Fx.changedParts(src, saved))
        val after = Fx.text(saved, Fx.SHEET1)
        assertEquals("<c r=\"C2\" s=\"2\"/>", Fx.cells(after).getValue("C2"))
        assertEquals(setOf("C2", "D2", "D5"), Fx.changedCells(Fx.text(src, Fx.SHEET1), after))
        val re = XlsxWorkbook.open(saved)
        assertEquals(CellKind.EMPTY, re.cell(0, 1, 2).kind)
        assertEquals("", re.cell(0, 1, 2).input)
        assertEquals(125.0, re.cell(0, 4, 3).number!!, 0.0)
    }

    @Test fun newCellInNewRowInsertsOneRowAndGrowsTheDimension() {
        val src = Fx.bytes("xlsx-cached.xlsx")
        val wb = XlsxWorkbook.open(src)
        wb.setCell(0, 9, 2, "42") // C10
        assertEquals(10, wb.rows(0))
        val saved = wb.save()
        assertEquals(setOf(Fx.SHEET1), Fx.changedParts(src, saved))
        val before = Fx.text(src, Fx.SHEET1)
        val expected = before
            .replace("<dimension ref=\"A1:F7\"/>", "<dimension ref=\"A1:F10\"/>")
            .replace("</sheetData>", "<row r=\"10\"><c r=\"C10\"><v>42</v></c></row></sheetData>")
        assertEquals(expected, Fx.text(saved, Fx.SHEET1))
        assertEquals(42.0, XlsxWorkbook.open(saved).cell(0, 9, 2).number!!, 0.0)
    }

    @Test fun newCellInExistingRowIsInsertedInColumnOrder() {
        val src = Fx.bytes("xlsx-cached.xlsx")
        val wb = XlsxWorkbook.open(src)
        wb.setCell(0, 0, 5, "x")     // F1: after the last cell of row 1, outside its spans hint
        wb.setCell(1, 1, 2, "7")     // 'Other Sheet'!C2
        wb.setCell(1, 0, 3, "=A1+1") // 'Other Sheet'!D1
        val saved = wb.save()
        assertEquals(setOf(Fx.SHEET1, Fx.SHEET2), Fx.changedParts(src, saved))
        val before = Fx.text(src, Fx.SHEET1)
        val row1End = before.indexOf("</row>")
        val expected = (before.substring(0, row1End) + "<c r=\"F1\" t=\"inlineStr\"><is><t>x</t></is></c>" + before.substring(row1End))
            .replace("<row r=\"1\" spans=\"1:4\">", "<row r=\"1\" spans=\"1:6\">")
        assertEquals(expected, Fx.text(saved, Fx.SHEET1))
        val s2 = Fx.text(saved, Fx.SHEET2)
        assertTrue(s2.contains("<dimension ref=\"A1:D2\"/>"))
        assertTrue(s2.contains("<c r=\"B1\"><f>SUM(Quantities)</f><v>60</v></c><c r=\"D1\"><f>A1+1</f><v>62</v></c></row>"))
        assertTrue(s2.contains("<c r=\"A2\"><f>'Other Sheet'!A1*2</f><v>122</v></c><c r=\"C2\"><v>7</v></c></row>"))

        // a cell between two existing ones
        val blank = XlsxWorkbook.blank()
        blank.setCell(0, 0, 0, "1")
        blank.setCell(0, 0, 2, "3")
        blank.setCell(0, 2, 0, "9")
        val first = blank.save()
        blank.setCell(0, 0, 1, "2")
        blank.setCell(0, 1, 1, "5")
        val second = blank.save()
        assertEquals(setOf(Fx.SHEET1), Fx.changedParts(first, second))
        val x = Fx.text(second, Fx.SHEET1)
        assertTrue(x, x.contains("<sheetData><row r=\"1\"><c r=\"A1\"><v>1</v></c><c r=\"B1\"><v>2</v></c><c r=\"C1\"><v>3</v></c></row>" +
            "<row r=\"2\"><c r=\"B2\"><v>5</v></c></row><row r=\"3\"><c r=\"A3\"><v>9</v></c></row></sheetData>"))
    }

    @Test fun boldReusesAMatchingStyleOrAppendsOne() {
        val src = Fx.bytes("xlsx-cached.xlsx")
        val wb = XlsxWorkbook.open(src)
        wb.setBold(0, 1, 1, true) // B2: plain → bold; the file already has that exact style
        assertTrue(wb.cell(0, 1, 1).bold)
        val one = wb.save()
        assertEquals(setOf(Fx.SHEET1), Fx.changedParts(src, one))
        assertEquals(
            Fx.text(src, Fx.SHEET1).replace("<c r=\"B2\"><v>10</v></c>", "<c r=\"B2\" s=\"1\"><v>10</v></c>"),
            Fx.text(one, Fx.SHEET1),
        )

        wb.setBold(0, 1, 2, true) // C2: currency → bold currency, which needs a new cell format
        val two = Fx.out("xlsx-cached-bold.xlsx", wb.save())
        assertEquals(setOf(Fx.SHEET1, Fx.STYLES), Fx.changedParts(one, two))
        assertEquals(
            Fx.text(one, Fx.STYLES)
                .replace("<cellXfs count=\"7\">", "<cellXfs count=\"8\">")
                .replace("</cellXfs>", "<xf numFmtId=\"164\" fontId=\"1\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyNumberFormat=\"1\" applyFont=\"1\"/></cellXfs>"),
            Fx.text(two, Fx.STYLES),
        )
        assertEquals(
            Fx.text(one, Fx.SHEET1).replace("<c r=\"C2\" s=\"2\"><v>2.5</v></c>", "<c r=\"C2\" s=\"7\"><v>2.5</v></c>"),
            Fx.text(two, Fx.SHEET1),
        )
        val re = XlsxWorkbook.open(two)
        assertTrue(re.cell(0, 1, 2).bold)
        assertEquals("¥2.50", re.cell(0, 1, 2).display)
        assertTrue(re.cell(0, 1, 1).bold)
        assertFalse(re.cell(0, 2, 2).bold)

        // un-bold a header that is a shared string: only the style index changes, the string reference stays
        re.setBold(0, 0, 0, false)
        val three = re.save()
        assertEquals(setOf(Fx.SHEET1), Fx.changedParts(two, three))
        assertEquals("<c r=\"A1\" s=\"0\" t=\"s\"><v>0</v></c>", Fx.cells(Fx.text(three, Fx.SHEET1)).getValue("A1"))
        assertFalse(XlsxWorkbook.open(three).cell(0, 0, 0).bold)
    }

    @Test fun formatChangeTouchesOnlySheetAndStyles() {
        val src = Fx.bytes("xlsx-sales.xlsx")
        val wb = XlsxWorkbook.open(src)
        wb.setFmt(0, 1, 1, NumFmt.PERCENT)   // B2 = 120 → 12000%   (the file already has a "0%" style)
        wb.setFmt(0, 2, 1, NumFmt.CURRENCY)  // B3 = 80 → ¥80.00     (the file already has that format code)
        wb.setFmt(0, 3, 1, NumFmt.DATE)      // B4                   (… and yyyy-mm-dd)
        wb.setFmt(0, 4, 1, NumFmt.OTHER)     // B5 → #,##0.00, the one format that has to be appended
        wb.setFmt(0, 1, 13, NumFmt.GENERAL)  // N2 back to General
        assertEquals("12000%", wb.cell(0, 1, 1).display)
        assertEquals(NumFmt.PERCENT, wb.cell(0, 1, 1).fmt)
        assertEquals("¥80.00", wb.cell(0, 2, 1).display)
        assertEquals("1900-02-09", wb.cell(0, 3, 1).display)
        assertEquals("25.00", wb.cell(0, 4, 1).display)
        assertEquals("26562.5", wb.cell(0, 1, 13).display)
        val saved = Fx.out("xlsx-sales-formats.xlsx", wb.save())
        assertEquals(setOf(Fx.SHEET1, Fx.STYLES), Fx.changedParts(src, saved))

        // styles: the count attribute and one appended <xf>, nothing else
        val sb = Fx.text(src, Fx.STYLES)
        assertEquals(
            sb.replace("<cellXfs count=\"8\">", "<cellXfs count=\"9\">").replace(
                "</cellXfs>",
                "<xf numFmtId=\"4\" fontId=\"0\" fillId=\"0\" borderId=\"0\" pivotButton=\"0\" quotePrefix=\"0\" xfId=\"0\" applyNumberFormat=\"1\"/></cellXfs>",
            ),
            Fx.text(saved, Fx.STYLES),
        )
        val b1 = Fx.text(src, Fx.SHEET1)
        val a1 = Fx.text(saved, Fx.SHEET1)
        assertEquals(Fx.skeleton(b1), Fx.skeleton(a1))
        assertEquals(setOf("B2", "B3", "B4", "B5", "N2"), Fx.changedCells(b1, a1))
        assertEquals("<c r=\"B2\" t=\"n\" s=\"4\"><v>120</v></c>", Fx.cells(a1).getValue("B2"))
        assertEquals("<c r=\"N2\" s=\"0\"><f>SUM(B2:M2)*12.5</f><v></v></c>", Fx.cells(a1).getValue("N2"))

        val re = XlsxWorkbook.open(saved)
        assertEquals(NumFmt.PERCENT, re.cell(0, 1, 1).fmt)
        assertEquals(NumFmt.CURRENCY, re.cell(0, 2, 1).fmt)
        assertEquals(NumFmt.DATE, re.cell(0, 3, 1).fmt)
        assertEquals(NumFmt.OTHER, re.cell(0, 4, 1).fmt)
        assertEquals(NumFmt.GENERAL, re.cell(0, 1, 13).fmt)

        // bold where no bold twin of the font exists yet: one <font> and one <xf> are appended
        re.setBold(0, 1, 0, true) // A2
        val bold = Fx.out("xlsx-sales-bold.xlsx", re.save())
        assertEquals(setOf(Fx.SHEET1, Fx.STYLES), Fx.changedParts(saved, bold))
        val st = Fx.text(bold, Fx.STYLES)
        assertTrue(st.contains("<fonts count=\"4\">"))
        assertTrue(st.contains("<font><b/><name val=\"Sans\"/><family val=\"2\"/><color theme=\"1\"/><sz val=\"11\"/><scheme val=\"minor\"/></font></fonts>"))
        assertTrue(st.contains("<cellXfs count=\"10\">"))
        assertEquals(setOf("A2"), Fx.changedCells(a1, Fx.text(bold, Fx.SHEET1)))
        val re2 = XlsxWorkbook.open(bold)
        assertTrue(re2.cell(0, 1, 0).bold)
        assertEquals("Headset", re2.cell(0, 1, 0).display)
        assertFalse(re2.cell(0, 2, 0).bold)
    }

    @Test fun typedPercentCurrencyAndDateGetAFormat() {
        val wb = XlsxWorkbook.blank()
        wb.setCell(0, 0, 0, "12%")
        wb.setCell(0, 1, 0, "¥1,200")
        wb.setCell(0, 2, 0, "1e3")
        wb.setCell(0, 3, 0, "true")
        wb.setCell(0, 4, 0, "2026-03-01")
        wb.setCell(0, 5, 0, "-$4.50")
        wb.setCell(0, 6, 0, " 1,234,567.5 ")
        wb.setCell(0, 7, 0, "1,23")
        wb.setCell(0, 8, 0, "2026-02-30")
        wb.setCell(0, 9, 0, "2026-03-01 18:30")
        wb.setCell(0, 10, 0, "07:30")
        wb.setCell(0, 11, 0, "25:00")
        val saved = Fx.out("xlsx-blank-typed.xlsx", wb.save())
        for (w in listOf(wb, XlsxWorkbook.open(saved))) {
            assertEquals(0.12, w.cell(0, 0, 0).number!!, 0.0)
            assertEquals("12%", w.cell(0, 0, 0).display)
            assertEquals("12%", w.cell(0, 0, 0).input)
            assertEquals(NumFmt.PERCENT, w.cell(0, 0, 0).fmt)
            assertEquals(1200.0, w.cell(0, 1, 0).number!!, 0.0)
            assertEquals("¥1,200", w.cell(0, 1, 0).display)
            assertEquals("1200", w.cell(0, 1, 0).input)
            assertEquals(NumFmt.CURRENCY, w.cell(0, 1, 0).fmt)
            assertEquals(1000.0, w.cell(0, 2, 0).number!!, 0.0)
            assertEquals(NumFmt.GENERAL, w.cell(0, 2, 0).fmt)
            assertEquals(CellKind.BOOL, w.cell(0, 3, 0).kind)
            assertEquals("TRUE", w.cell(0, 3, 0).display)
            assertEquals(46082.0, w.cell(0, 4, 0).number!!, 0.0)
            assertEquals("2026-03-01", w.cell(0, 4, 0).display)
            assertEquals(NumFmt.DATE, w.cell(0, 4, 0).fmt)
            assertEquals(-4.5, w.cell(0, 5, 0).number!!, 0.0)
            assertEquals("-$4.50", w.cell(0, 5, 0).display)
            assertEquals(1234567.5, w.cell(0, 6, 0).number!!, 0.0)
            assertEquals(CellKind.TEXT, w.cell(0, 7, 0).kind)
            assertEquals(CellKind.TEXT, w.cell(0, 8, 0).kind) // not a real date
            assertEquals("2026-03-01 18:30", w.cell(0, 9, 0).display)
            assertEquals(0.3125, w.cell(0, 10, 0).number!!, 0.0)
            assertEquals("07:30", w.cell(0, 10, 0).display)
            assertEquals("07:30", w.cell(0, 10, 0).input)
            assertEquals(NumFmt.DATE, w.cell(0, 10, 0).fmt)
            assertEquals(CellKind.TEXT, w.cell(0, 11, 0).kind)
        }
        // committing what the formula bar shows is never an edit, whatever the kind of cell
        val re = XlsxWorkbook.open(saved)
        for (r in 0 until re.rows(0)) re.setCell(0, r, 0, re.cell(0, r, 0).input)
        assertFalse(re.dirty)
        val c = Fx.cached()
        for (s in 0..1) for (r in 0 until c.rows(s)) for (col in 0 until c.cols(s)) c.setCell(s, r, col, c.cell(s, r, col).input)
        assertFalse(c.dirty)
        val sales = Fx.sales()
        for (s in 0..1) for (r in 0 until sales.rows(s)) for (col in 0 until sales.cols(s)) sales.setCell(s, r, col, sales.cell(s, r, col).input)
        assertFalse(sales.dirty)
    }

    @Test fun unknownFunctionKeepsCachedValueWhenItsInputsChange() {
        val src = Fx.bytes("xlsx-cached.xlsx")
        val wb = XlsxWorkbook.open(src)
        wb.setCell(0, 1, 1, "11") // B2, an input of STDEV(B2:B4) and of SUM(B2:B4)
        assertEquals(10.0, wb.cell(0, 4, 2).number!!, 0.0)
        assertEquals(61.0, wb.cell(0, 4, 1).number!!, 0.0)
        val saved = wb.save()
        val after = Fx.text(saved, Fx.SHEET1)
        assertTrue(after.contains("<c r=\"C5\"><f>STDEV(B2:B4)</f><v>10</v></c>"))
        assertTrue(after.contains("<c r=\"B5\"><f>SUM(B2:B4)</f><v>61</v></c>"))
        assertEquals(setOf("B2", "D2", "B5", "D5", "A7"), Fx.changedCells(Fx.text(src, Fx.SHEET1), after))
        // the other sheet depends on B5; the formula with a defined name keeps its cached value
        assertEquals(setOf("A1", "A2"), Fx.changedCells(Fx.text(src, Fx.SHEET2), Fx.text(saved, Fx.SHEET2)))
        // the calculation chain is still valid (no formula was removed), so it stays
        assertTrue(OoxmlPackage.read(saved).has("xl/calcChain.xml"))
        assertEquals(setOf(Fx.SHEET1, Fx.SHEET2), Fx.changedParts(src, saved))

        // a formula the evaluator does not know can be entered; it is stored as a formula without a cached value
        wb.setCell(0, 8, 0, "=vlookup(A1,A1:B4,2,0)")
        assertEquals("=VLOOKUP(A1,A1:B4,2,0)", wb.cell(0, 8, 0).input)
        assertEquals(CellKind.ERROR, wb.cell(0, 8, 0).kind)
        assertEquals("#NAME?", wb.cell(0, 8, 0).display)
        val again = Fx.out("xlsx-cached-unknown-fn.xlsx", wb.save())
        assertTrue(Fx.text(again, Fx.SHEET1).contains("<row r=\"9\"><c r=\"A9\"><f>VLOOKUP(A1,A1:B4,2,0)</f></c></row>"))
        assertEquals("=VLOOKUP(A1,A1:B4,2,0)", XlsxWorkbook.open(again).cell(0, 8, 0).input)
    }

    @Test fun replacingAFormulaDropsTheCalculationChainAndUnsharesFormulas() {
        val src = Fx.bytes("xlsx-cached.xlsx")
        val wb = XlsxWorkbook.open(src)
        wb.setCell(0, 2, 3, "7") // D3: master of the shared formula D3:D4
        assertEquals("=B4*C4", wb.cell(0, 3, 3).input)
        assertEquals(77.0, wb.cell(0, 4, 3).number!!, 0.0) // 25 + 7 + 45
        val saved = Fx.out("xlsx-cached-formula-removed.xlsx", wb.save())
        val pkg = OoxmlPackage.read(saved)
        assertFalse(pkg.has("xl/calcChain.xml"))
        assertFalse(pkg.text("[Content_Types].xml")!!.contains("calcChain"))
        assertFalse(pkg.text("xl/_rels/workbook.xml.rels")!!.contains("calcChain"))
        assertEquals(
            setOf(Fx.SHEET1, "xl/calcChain.xml", "[Content_Types].xml", "xl/_rels/workbook.xml.rels"),
            Fx.changedParts(src, saved),
        )
        val after = Fx.text(saved, Fx.SHEET1)
        assertEquals("<c r=\"D3\" s=\"2\"><v>7</v></c>", Fx.cells(after).getValue("D3"))
        assertEquals("<c r=\"D4\" s=\"2\"><f>B4*C4</f><v>45</v></c>", Fx.cells(after).getValue("D4"))
        assertEquals(setOf("D3", "D4", "D5"), Fx.changedCells(Fx.text(src, Fx.SHEET1), after))
        val re = XlsxWorkbook.open(saved)
        assertEquals("=B4*C4", re.cell(0, 3, 3).input)
        assertEquals(45.0, re.cell(0, 3, 3).number!!, 0.0)
        assertFalse(re.cell(0, 2, 3).isFormula)
    }

    @Test fun insertRowShiftsRowsFormulasRangesAndChartReferences() {
        val src = Fx.bytes("xlsx-sales.xlsx")
        val wb = XlsxWorkbook.open(src)
        wb.insertRowBelow(0, 2) // below "Controller" (row 3 on screen)
        assertEquals(9, wb.rows(0))
        assertEquals("Controller", wb.cell(0, 2, 0).display)
        assertEquals(CellKind.EMPTY, wb.cell(0, 3, 0).kind)
        assertEquals("Strap", wb.cell(0, 4, 0).display)
        assertEquals("Total", wb.cell(0, 6, 0).display)
        assertEquals("=SUM(B2:B6)", wb.cell(0, 6, 1).input)
        assertEquals(265.0, wb.cell(0, 6, 1).number!!, 0.0)
        assertEquals("=SUM(B5:M5)*12.5", wb.cell(0, 4, 13).input)
        assertEquals("=N5/\$N\$7", wb.cell(0, 4, 14).input)
        assertEquals("=N2/\$N\$7", wb.cell(0, 1, 14).input)
        assertEquals("=Sales!N7", wb.cell(1, 1, 5).input)
        assertEquals("=SUM(Sales!B2:M2)*2", wb.cell(1, 2, 5).input)
        assertEquals(56775.0, wb.cell(1, 1, 5).number!!, 1e-9)
        // live chart: the third series followed its row
        val bar = wb.charts()[0]
        assertEquals(listOf("Headset", "Controller", "Strap", "Case"), bar.series.map { it.name })
        assertEquals("'Sales'!\$B\$5:\$M\$5", bar.series[2].valuesRef)
        assertEquals(40.0, bar.series[2].values[0]!!, 0.0)

        wb.setCell(0, 3, 0, "Dock")
        wb.setCell(0, 3, 1, "10")
        assertEquals(275.0, wb.cell(0, 6, 1).number!!, 0.0)
        val notes = wb.compat().filter { it.code.startsWith("insert-row:") }.map { it.code }.toSet()
        assertEquals(setOf("insert-row:drawing-anchor", "insert-row:conditional-format-formula", "insert-row:data-validation-formula"), notes)

        val saved = Fx.out("xlsx-sales-insert-row.xlsx", wb.save())
        assertEquals(
            setOf(Fx.SHEET1, Fx.SHEET2, "xl/charts/chart1.xml", "xl/workbook.xml"),
            Fx.changedParts(src, saved),
        )
        val a1 = Fx.text(saved, Fx.SHEET1)
        val b1 = Fx.text(src, Fx.SHEET1)
        assertTrue(a1.contains("<mergeCell ref=\"A9:M9\"/>"))
        assertTrue(a1.contains("<conditionalFormatting sqref=\"B2:M6\">"))
        assertTrue(a1.contains("<dataValidation sqref=\"B2:M6\" "))
        assertTrue(a1.contains("<dimension ref=\"A1:O9\"/>"))
        assertTrue(a1.contains("<row r=\"4\"><c r=\"A4\" t=\"inlineStr\"><is><t>Dock</t></is></c><c r=\"B4\"><v>10</v></c></row>"))
        // rows above the insertion point: the header row is the file's own text; below it only the share formulas
        // (they point at the moved total) were rewritten
        assertTrue(a1.contains(b1.substring(b1.indexOf("<sheetData>"), b1.indexOf("<row r=\"2\">"))))
        val bc = Fx.cells(b1)
        val ac = Fx.cells(a1)
        assertEquals(setOf("O2", "O3"), bc.keys.filter { it.substring(1).toIntOrNull()?.let { n -> n in 2..3 } == true && bc[it] != ac[it] }.toSet())
        // moved cells keep their content, only the address changes
        assertEquals("<c r=\"A5\" t=\"inlineStr\"><is><t>Strap</t></is></c>", Fx.cells(a1).getValue("A5"))
        assertEquals("<c r=\"C5\" t=\"n\"><v>38</v></c>", Fx.cells(a1).getValue("C5"))
        assertTrue(Fx.text(saved, "xl/workbook.xml").contains("<definedName name=\"JanUnits\">Sales!\$B\$2:\$B\$6</definedName>"))
        assertTrue(Fx.text(saved, "xl/charts/chart1.xml").contains("<f>'Sales'!A6</f>"))

        val re = XlsxWorkbook.open(saved)
        assertEquals("Dock", re.cell(0, 3, 0).display)
        assertEquals(275.0, re.cell(0, 6, 1).number!!, 0.0)
        assertEquals("=SUM(B2:B6)", re.cell(0, 6, 1).input)
        assertEquals("FY2026 unit sales — 销售概览", re.cell(0, 8, 0).display)
        assertEquals(listOf("Headset", "Controller", "Strap", "Case"), re.charts()[0].series.map { it.name })
        assertNull(re.charts()[0].range) // the block now has a row that is not charted
    }

    @Test fun sortRefusesWhenFormulasWouldBreak() {
        val src = Fx.bytes("xlsx-sales.xlsx")
        val wb = XlsxWorkbook.open(src)
        // Notes!F3 = SUM(Sales!B2:M2) points at one row of the block
        assertFalse(wb.sortRows(0, 1, true, 1, 4))
        assertFalse(wb.dirty)
        assertEquals("Headset", wb.cell(0, 1, 0).display)
        // a block that includes the merged title
        assertFalse(wb.sortRows(0, 0, true, 6, 8))
        Fx.assertSameParts(src, wb.save())

        // running total: each row points at the row above
        val b = XlsxWorkbook.blank()
        b.setCell(0, 0, 0, "3"); b.setCell(0, 0, 1, "=A1")
        b.setCell(0, 1, 0, "1"); b.setCell(0, 1, 1, "=B1+A2")
        b.setCell(0, 2, 0, "2"); b.setCell(0, 2, 1, "=B2+A3")
        assertFalse(b.sortRows(0, 0, true, 0, 2))
        assertEquals(3.0, b.cell(0, 0, 0).number!!, 0.0)
        assertEquals(6.0, b.cell(0, 2, 1).number!!, 0.0)
    }

    @Test fun sortMovesWholeRowsAndTheirOwnRowFormulas() {
        val src = Fx.bytes("xlsx-cached.xlsx")
        val wb = XlsxWorkbook.open(src)
        assertTrue(wb.sortRows(0, 1, false, 1, 3)) // rows 2..4 by Qty, descending
        assertTrue(wb.dirty)
        assertEquals(listOf("Line\r\nbreak", "東京", "Rich text & more"), (1..3).map { wb.cell(0, it, 0).display })
        assertEquals(listOf(30.0, 20.0, 10.0), (1..3).map { wb.cell(0, it, 1).number })
        assertEquals(listOf("=B2*C2", "=B3*C3", "=B4*C4"), (1..3).map { wb.cell(0, it, 3).input })
        assertEquals(listOf(45.0, 80.0, 25.0), (1..3).map { wb.cell(0, it, 3).number })
        assertEquals(150.0, wb.cell(0, 4, 3).number!!, 0.0)
        val saved = Fx.out("xlsx-cached-sort.xlsx", wb.save())
        val pkg = OoxmlPackage.read(saved)
        assertFalse(pkg.has("xl/calcChain.xml"))
        assertArrayEquals(Fx.parts(src).getValue(Fx.SHARED), Fx.parts(saved).getValue(Fx.SHARED))
        assertArrayEquals(Fx.parts(src).getValue(Fx.STYLES), Fx.parts(saved).getValue(Fx.STYLES))
        assertArrayEquals(Fx.parts(src).getValue(Fx.SHEET2), Fx.parts(saved).getValue(Fx.SHEET2))
        val after = Fx.text(saved, Fx.SHEET1)
        val before = Fx.text(src, Fx.SHEET1)
        // everything outside <sheetData> and the rows outside the block are untouched; row attributes stay with the row
        assertEquals(before.substring(0, before.indexOf("<row r=\"2\"")), after.substring(0, after.indexOf("<row r=\"2\"")))
        assertEquals(before.substring(before.indexOf("<row r=\"5\"")), after.substring(after.indexOf("<row r=\"5\"")))
        assertTrue(after.contains("<row r=\"2\" ht=\"20\" customHeight=\"1\"><c r=\"A2\" t=\"s\"><v>4</v></c>"))
        val re = XlsxWorkbook.open(saved)
        assertEquals(listOf("Line\r\nbreak", "東京", "Rich text & more"), (1..3).map { re.cell(0, it, 0).display })
        assertEquals(listOf("=B2*C2", "=B3*C3", "=B4*C4"), (1..3).map { re.cell(0, it, 3).input })
        assertEquals(listOf(45.0, 80.0, 25.0), (1..3).map { re.cell(0, it, 3).number })

        // mixed kinds: numbers, then text, then blanks; descending keeps blanks last
        val b = XlsxWorkbook.blank()
        val vals = listOf("pear", "10", "", "Apple", "2")
        for ((i, v) in vals.withIndex()) { b.setCell(0, i, 0, v); b.setCell(0, i, 1, "r$i") }
        assertTrue(b.sortRows(0, 0, true, 0, 4))
        assertEquals(listOf("2", "10", "Apple", "pear", ""), (0..4).map { b.cell(0, it, 0).display })
        assertEquals(listOf("r4", "r1", "r3", "r0", "r2"), (0..4).map { b.cell(0, it, 1).display })
        assertTrue(b.sortRows(0, 0, false, 0, 4))
        assertEquals(listOf("pear", "Apple", "10", "2", ""), (0..4).map { b.cell(0, it, 0).display })
        val re2 = XlsxWorkbook.open(b.save())
        assertEquals(listOf("pear", "Apple", "10", "2", ""), (0..4).map { re2.cell(0, it, 0).display })
        assertEquals(listOf("r0", "r3", "r1", "r4", "r2"), (0..4).map { re2.cell(0, it, 1).display })
    }

    @Test fun autoSumAboveThenLeftThenNothing() {
        val wb = XlsxWorkbook.blank()
        wb.setCell(0, 0, 0, "Units")
        wb.setCell(0, 1, 0, "1")
        wb.setCell(0, 2, 0, "2")
        wb.setCell(0, 3, 0, "3.5")
        assertEquals("=SUM(A2:A4)", wb.autoSum(0, 4, 0))
        assertEquals(6.5, wb.cell(0, 4, 0).number!!, 0.0)
        assertTrue(wb.cell(0, 4, 0).isFormula)
        wb.setCell(0, 7, 1, "4")
        wb.setCell(0, 7, 2, "5")
        assertEquals("=SUM(B8:C8)", wb.autoSum(0, 7, 3))
        assertEquals(9.0, wb.cell(0, 7, 3).number!!, 0.0)
        assertNull(wb.autoSum(0, 10, 10))
        assertEquals(CellKind.EMPTY, wb.cell(0, 10, 10).kind)
        // on the sales sheet: below the Jan column including the existing total
        val sales = Fx.sales()
        assertEquals("=SUM(B2:B6)", sales.autoSum(0, 6, 1))
        assertEquals(530.0, sales.cell(0, 6, 1).number!!, 0.0)
    }

    @Test fun blankWorkbookEditSaveReopen() {
        val wb = XlsxWorkbook.blank()
        assertEquals(listOf("Sheet1"), wb.sheetNames)
        assertEquals(1, wb.rows(0))
        assertEquals(1, wb.cols(0))
        assertFalse(wb.dirty)
        val empty = wb.save()
        assertTrue(Fx.text(empty, "docProps/app.xml").contains("<Application>Spatial Office</Application>"))
        assertFalse(Fx.text(empty, "docProps/core.xml").contains("creator"))
        Fx.out("xlsx-blank.xlsx", empty)

        wb.setCell(0, 0, 0, "Item")
        wb.setCell(0, 0, 1, "Qty")
        wb.setBold(0, 0, 0, true)
        wb.setBold(0, 0, 1, true)
        wb.setCell(0, 1, 0, "Lens")
        wb.setCell(0, 1, 1, "4")
        wb.setCell(0, 2, 0, "Strap")
        wb.setCell(0, 2, 1, "6")
        wb.setCell(0, 3, 0, "Total")
        wb.setCell(0, 3, 1, "=sum(b2:b3)")
        wb.setFmt(0, 3, 1, NumFmt.CURRENCY)
        assertTrue(wb.dirty)
        val saved = Fx.out("xlsx-blank-edit.xlsx", wb.save())
        assertFalse(wb.dirty)
        assertEquals(setOf(Fx.SHEET1, Fx.STYLES), Fx.changedParts(empty, saved))
        val re = XlsxWorkbook.open(saved)
        assertEquals(4, re.rows(0))
        assertEquals(2, re.cols(0))
        assertTrue(re.cell(0, 0, 0).bold)
        assertEquals("=SUM(B2:B3)", re.cell(0, 3, 1).input)
        assertEquals(10.0, re.cell(0, 3, 1).number!!, 0.0)
        assertEquals("¥10.00", re.cell(0, 3, 1).display)
        assertEquals("Item,Qty\r\nLens,4\r\nStrap,6\r\nTotal,¥10.00\r\n", re.toCsv(0))
        assertNotNull(re.partBytes(Fx.SHEET1))
        Fx.assertSameParts(saved, re.save())
    }

    @Test fun csvOfTheSecondSalesSheetQuotesWhereNeeded() {
        val csv = Fx.sales().toCsv(1)
        val lines = csv.split("\r\n")
        assertEquals("Week,Visitors,Comment,Date,,Grand total", lines[0])
        assertEquals("1,310,\"R&D <alpha> \"\"quoted\"\"\",2026-03-01,,\"¥56,775.00\"", lines[1])
        assertEquals("5,388,\"  padded  \",2026-03-29,,430", lines[5])
        assertEquals(7, lines.size) // six records and the final line break
    }
}
