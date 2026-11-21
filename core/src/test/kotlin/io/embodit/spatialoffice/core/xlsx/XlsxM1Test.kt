package io.embodit.spatialoffice.core.xlsx

import io.embodit.spatialoffice.core.pkg.OoxmlPackage
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** M1 (v0.3.0): ranges, paste with relative references, column widths / row heights, worksheets, alignment, decimals. */
class XlsxM1Test {

    private fun assertOthersUntouched(before: ByteArray, after: ByteArray, allowed: Set<String>) {
        val a = OoxmlPackage.read(before)
        val b = OoxmlPackage.read(after)
        for (n in a.names()) if (n !in allowed && b.has(n)) assertArrayEquals("part $n", a.bytes(n), b.bytes(n))
    }

    // ---- Formula.offset ----

    @Test fun offsetMovesRelativePartsOnly() {
        assertEquals("=D1*2", Formula.offset("=A1*2", 0, 3))
        assertEquals("=\$A\$1+C3", Formula.offset("=\$A\$1+B2", 1, 1))
        assertEquals("=#REF!", Formula.offset("=A1", -1, 0))
        assertEquals("=SUM(D1:E3)", Formula.offset("=SUM(A1:B3)", 0, 3))
        assertEquals("=Sheet2!B2", Formula.offset("=Sheet2!A1", 1, 1))
        assertEquals("=SUM(\$A2:B\$3)", Formula.offset("=SUM(\$A1:A\$3)", 1, 1))
        assertEquals("A2+1", Formula.offset("A1+1", 1, 0))
        assertEquals("=SUM(A:A)", Formula.offset("=SUM(A:A)", 5, 0))
    }

    @Test fun renameSheetRefsQuotesAndDeletes() {
        assertEquals("'汇总'!N6+1", Formula.renameSheetRefs("Sales!N6+1", "sales", "汇总"))
        assertEquals("'My Sheet'!A1:B2", Formula.renameSheetRefs("'Old one'!A1:B2", "Old one", "My Sheet"))
        assertEquals("#REF!+1", Formula.renameSheetRefs("Sales!N6+1", "Sales", null))
        assertEquals("SUM(#REF!)*2", Formula.renameSheetRefs("SUM(Sales!B2:M2)*2", "Sales", null))
        assertEquals("A1+Other!B1", Formula.renameSheetRefs("A1+Other!B1", "Sales", "X"))
    }

    // ---- alignment / wrap / decimals ----

    @Test fun alignWrapDecimalsReadBackAfterSave() {
        val src = Fx.bytes("xlsx-sales.xlsx")
        val wb = XlsxWorkbook.open(src)
        wb.setHAlign(0, 1, 1, "center")
        wb.setWrap(0, 1, 1, true)
        wb.adjustDecimals(0, 1, 1, 1) // 120 General → 0.0
        assertEquals("center", wb.cell(0, 1, 1).hAlign)
        assertTrue(wb.cell(0, 1, 1).wrap)
        assertEquals("120.0", wb.cell(0, 1, 1).display)
        wb.adjustDecimals(0, 1, 13, 1) // N2 currency "¥"#,##0.00 → three decimals
        val out = wb.save()
        assertEquals(setOf(Fx.SHEET1, Fx.STYLES), Fx.changedParts(src, out))
        val re = XlsxWorkbook.open(out)
        val b2 = re.cell(0, 1, 1)
        assertEquals("center", b2.hAlign)
        assertTrue(b2.wrap)
        assertEquals("120.0", b2.display)
        assertTrue(re.cell(0, 1, 13).display.matches(Regex("¥[0-9,]+\\.[0-9]{3}")))
        val styles = Fx.text(out, Fx.STYLES)
        assertTrue(styles.contains("applyAlignment=\"1\""))
        assertTrue(styles.contains("<alignment horizontal=\"center\" wrapText=\"1\"/>"))
        // align alone keeps wrap, back to general drops the attribute
        re.setHAlign(0, 1, 1, null)
        assertNull(re.cell(0, 1, 1).hAlign)
        assertTrue(re.cell(0, 1, 1).wrap)
        re.setWrap(0, 1, 1, false)
        assertFalse(re.cell(0, 1, 1).wrap)
        re.adjustDecimals(0, 1, 1, -1)
        assertEquals("120", re.cell(0, 1, 1).display)
        // unchanged requests are not edits
        val wb2 = XlsxWorkbook.open(src)
        wb2.setHAlign(0, 1, 1, null); wb2.setWrap(0, 1, 1, false)
        assertFalse(wb2.dirty)
    }

    @Test fun decimalCodes() {
        assertEquals("0.000", Decimals.adjust("0.00", 1, 1.0))
        assertEquals("#,##0.0", Decimals.adjust("#,##0", 1, 1.0))
        assertEquals("#,##0", Decimals.adjust("#,##0.0", -1, 1.0))
        assertEquals("\"¥\"#,##0.000", Decimals.adjust("\"¥\"#,##0.00", 1, 1.0))
        assertEquals("0.0%", Decimals.adjust("0%", 1, 0.5))
        assertEquals("0%", Decimals.adjust("0.0%", -1, 0.5))
        assertEquals("0", Decimals.adjust("0", -1, 1.0))
        assertEquals("0.0", Decimals.adjust("General", 1, 12.0))
        assertEquals("0.000", Decimals.adjust("General", 1, 3.25))
        assertEquals("0.0", Decimals.adjust("General", -1, 3.25))
        assertEquals("yyyy-mm-dd", Decimals.adjust("yyyy-mm-dd", 1, 46000.0))
        assertEquals("#,##0.0;[Red]-#,##0.0", Decimals.adjust("#,##0;[Red]-#,##0", 1, 1.0))
        assertEquals("0.0E+00", Decimals.adjust("0E+00", 1, 1.0))
    }

    // ---- copy / paste / clear ----

    @Test fun pasteShiftsFormulasAndCutClears() {
        val wb = XlsxWorkbook.blank()
        wb.setCell(0, 0, 0, "3")
        wb.setCell(0, 0, 1, "=A1*2")
        wb.setCell(0, 1, 0, "4")
        wb.setCell(0, 1, 1, "=A2*2")
        wb.setCell(0, 2, 0, "text")
        wb.setCell(0, 2, 1, "=\$A\$1+A3")
        wb.setBold(0, 0, 0, true)
        val clip = wb.copyRange(0, 0, 0, 2, 1) // A1:B3
        assertEquals(3, clip.size); assertEquals(2, clip[0].size)
        assertEquals("=A1*2", clip[0][1].input)
        wb.pasteRange(0, 0, 3, clip, 0, 3) // to D1
        assertEquals("3", wb.cell(0, 0, 3).input)
        assertTrue(wb.cell(0, 0, 3).bold)
        assertEquals("=D1*2", wb.cell(0, 0, 4).input)
        assertEquals("6", wb.cell(0, 0, 4).display)
        assertEquals("=D2*2", wb.cell(0, 1, 4).input)
        assertEquals("=\$A\$1+D3", wb.cell(0, 2, 4).input)
        // cut A1:B3 to A10: the source empties, formulas move with the paste offset
        val cut = wb.copyRange(0, 0, 0, 2, 1)
        wb.clearRange(0, 0, 0, 2, 1, styles = true)
        assertEquals("", wb.cell(0, 0, 0).input)
        assertFalse(wb.cell(0, 0, 0).bold)
        assertEquals("", wb.cell(0, 2, 1).input)
        wb.pasteRange(0, 9, 0, cut, 9, 0)
        assertEquals("=A10*2", wb.cell(0, 9, 1).input)
        assertEquals("6", wb.cell(0, 9, 1).display)
        // off the sheet → #REF!
        wb.pasteRange(0, 0, 6, listOf(listOf(ClipCell("=A1", 0))), -1, 0)
        assertEquals("#REF!", wb.cell(0, 0, 6).display)
        // round trip
        val re = XlsxWorkbook.open(wb.save())
        assertEquals("=D1*2", re.cell(0, 0, 4).input)
        assertEquals("", re.cell(0, 0, 0).input)
        assertEquals("=A10*2", re.cell(0, 9, 1).input)
    }

    @Test fun clearKeepsOtherPartsByteIdentical() {
        val src = Fx.bytes("xlsx-sales.xlsx")
        val wb = XlsxWorkbook.open(src)
        wb.clearRange(1, 1, 0, 2, 1, styles = false)
        val out = wb.save()
        assertEquals(setOf(Fx.SHEET2), Fx.changedParts(src, out))
        val re = XlsxWorkbook.open(out)
        assertEquals("", re.cell(1, 1, 0).input)
        assertEquals("", re.cell(1, 2, 1).input)
        assertEquals("290", XlsxWorkbook.open(src).cell(1, 2, 1).input)
    }

    // ---- column widths / row heights ----

    @Test fun columnWidthSplitsSpansAndSurvivesSave() {
        val src = Fx.bytes("xlsx-sales.xlsx")
        val wb = XlsxWorkbook.open(src)
        assertEquals(18f, wb.colWidthChars(0, 0))
        wb.setColWidth(0, 0, 24f)
        wb.setColWidth(0, 3, 12.5f) // D: no span yet
        assertEquals(24f, wb.colWidthChars(0, 0))
        assertEquals(12.5f, wb.colWidthChars(0, 3))
        val out = wb.save()
        assertEquals(setOf(Fx.SHEET1), Fx.changedParts(src, out))
        val xml = Fx.text(out, Fx.SHEET1)
        assertTrue(xml.contains("<cols><col width=\"24\" customWidth=\"1\" min=\"1\" max=\"1\"/><col min=\"4\" max=\"4\" width=\"12.5\" customWidth=\"1\"/><col width=\"14.5\" customWidth=\"1\" min=\"14\" max=\"14\"/></cols>"))
        // everything but <cols> is as it was
        val before = Fx.text(src, Fx.SHEET1)
        assertEquals(before.replace(Regex("<cols>.*?</cols>"), ""), xml.replace(Regex("<cols>.*?</cols>"), ""))
        val re = XlsxWorkbook.open(out)
        assertEquals(24f, re.colWidthChars(0, 0))
        assertEquals(12.5f, re.colWidthChars(0, 3))
        assertEquals(14.5f, re.colWidthChars(0, 13))

        // a sheet without <cols> gets one in front of the sheet data
        val wb2 = XlsxWorkbook.blank()
        wb2.setColWidth(0, 1, 10f)
        wb2.setColWidth(0, 1, 10f)
        assertTrue(Fx.text(wb2.save(), Fx.SHEET1).contains("<cols><col min=\"2\" max=\"2\" width=\"10\" customWidth=\"1\"/></cols><sheetData"))
    }

    @Test fun columnSpanSplit() {
        val wb = XlsxWorkbook.blank()
        wb.setCell(0, 0, 0, "x")
        val first = wb.save()
        // put a 3-column span in by hand: B:D width 20
        val p = OoxmlPackage.read(first)
        p.putText(Fx.SHEET1, p.text(Fx.SHEET1)!!.replace("<sheetData>", "<cols><col min=\"2\" max=\"4\" width=\"20\" customWidth=\"1\"/></cols><sheetData>"))
        val wb2 = XlsxWorkbook.open(p.write())
        wb2.setColWidth(0, 2, 30f) // C
        assertEquals(20f, wb2.colWidthChars(0, 1))
        assertEquals(30f, wb2.colWidthChars(0, 2))
        assertEquals(20f, wb2.colWidthChars(0, 3))
        val xml = Fx.text(wb2.save(), Fx.SHEET1)
        assertTrue(xml, xml.contains("<cols><col min=\"2\" max=\"2\" width=\"20\" customWidth=\"1\"/><col min=\"3\" max=\"3\" width=\"30\" customWidth=\"1\"/><col min=\"4\" max=\"4\" width=\"20\" customWidth=\"1\"/></cols>"))
    }

    @Test fun autoFitUsesLongestText() {
        val wb = XlsxWorkbook.blank()
        wb.setCell(0, 0, 0, "Hello world long text")
        wb.setCell(0, 1, 0, "short")
        assertEquals(23f, wb.autoFitColumn(0, 0))
        assertEquals(23f, wb.colWidthChars(0, 0))
        wb.setCell(0, 0, 1, "汇总表格")
        assertEquals(10f, wb.autoFitColumn(0, 1)) // 4 wide characters = 8 + 2
        assertEquals(4f, wb.autoFitColumn(0, 5))
        val re = XlsxWorkbook.open(wb.save())
        assertEquals(23f, re.colWidthChars(0, 0))
    }

    @Test fun rowHeightReadWrite() {
        val src = Fx.bytes("xlsx-sales.xlsx")
        val wb = XlsxWorkbook.open(src)
        assertEquals(15f, wb.rowHeightPt(0, 1))
        wb.setRowHeight(0, 1, 30f)
        wb.setRowHeight(0, 6, 22.5f) // row 7 does not exist in the file
        wb.setCell(0, 1, 15, "new") // P2 inserted in the same row
        val out = wb.save()
        assertEquals(setOf(Fx.SHEET1), Fx.changedParts(src, out))
        val xml = Fx.text(out, Fx.SHEET1)
        assertTrue(xml.contains("<row r=\"2\" ht=\"30\" customHeight=\"1\">"))
        assertTrue(xml.contains("<row r=\"7\" ht=\"22.5\" customHeight=\"1\"></row>"))
        val re = XlsxWorkbook.open(out)
        assertEquals(30f, re.rowHeightPt(0, 1))
        assertEquals(22.5f, re.rowHeightPt(0, 6))
        assertEquals("new", re.cell(0, 1, 15).input)
        // after a structural change too
        re.insertRowBelow(0, 0)
        re.setRowHeight(0, 0, 40f)
        val re2 = XlsxWorkbook.open(re.save())
        assertEquals(40f, re2.rowHeightPt(0, 0))
        assertEquals(30f, re2.rowHeightPt(0, 2))
    }

    // ---- worksheets ----

    @Test fun addSheetAppearsAndRoundTrips() {
        val src = Fx.bytes("xlsx-sales.xlsx")
        val wb = XlsxWorkbook.open(src)
        val i = wb.addSheet()
        assertEquals(2, i)
        assertEquals(listOf("Sales", "Notes", "Sheet3"), wb.sheetNames)
        wb.setCell(i, 0, 0, "=Sales!B2+1")
        assertEquals("121", wb.cell(i, 0, 0).display)
        val out = wb.save()
        assertOthersUntouched(src, out, setOf("xl/workbook.xml", "xl/_rels/workbook.xml.rels", "[Content_Types].xml"))
        val re = XlsxWorkbook.open(out)
        assertEquals(listOf("Sales", "Notes", "Sheet3"), re.sheetNames)
        assertEquals("121", re.cell(2, 0, 0).display)
        assertTrue(Fx.text(out, "[Content_Types].xml").contains("/xl/worksheets/sheet3.xml"))
        val named = re.addSheet("汇总")
        assertEquals("汇总", re.sheetNames[named])
        try { re.addSheet("sales"); fail("duplicate name accepted") } catch (e: IllegalArgumentException) {}
    }

    @Test fun renameRewritesReferencesEverywhere() {
        val src = Fx.bytes("xlsx-sales.xlsx")
        val wb = XlsxWorkbook.open(src)
        val before = wb.cell(1, 1, 5).display
        wb.renameSheet(0, "汇总")
        assertEquals(listOf("汇总", "Notes"), wb.sheetNames)
        assertEquals("='汇总'!N6", wb.cell(1, 1, 5).input)
        assertEquals(before, wb.cell(1, 1, 5).display)
        val out = wb.save()
        val re = XlsxWorkbook.open(out)
        assertEquals(listOf("汇总", "Notes"), re.sheetNames)
        assertEquals("=SUM('汇总'!B2:M2)*2", re.cell(1, 2, 5).input)
        assertEquals(before, re.cell(1, 1, 5).display)
        assertTrue(Fx.text(out, "xl/workbook.xml").contains("<definedName name=\"JanUnits\">'汇总'!\$B\$2:\$B\$5</definedName>"))
        assertTrue(Fx.text(out, "xl/charts/chart1.xml").contains("'汇总'!"))
        assertFalse(Fx.text(out, "xl/charts/chart1.xml").contains("<c:f>Sales!"))
        assertTrue(re.charts().isNotEmpty())
        assertTrue(re.charts().first().series.first().values.any { it != null })
        for (bad in listOf("", "Notes", "notes", "a/b", "x".repeat(32), "a[1]")) {
            try { re.renameSheet(0, bad); fail("accepted '$bad'") } catch (e: IllegalArgumentException) {}
        }
        // drawings, theme and styles untouched
        assertOthersUntouched(src, out, setOf("xl/workbook.xml", Fx.SHEET2, "xl/charts/chart1.xml", "xl/charts/chart2.xml"))
    }

    @Test fun copySheetInsertsAfterWithoutDrawings() {
        val src = Fx.bytes("xlsx-sales.xlsx")
        val wb = XlsxWorkbook.open(src)
        val i = wb.copySheet(0)
        assertEquals(1, i)
        assertEquals(listOf("Sales", "Sales (2)", "Notes"), wb.sheetNames)
        assertEquals(wb.cell(0, 5, 13).display, wb.cell(1, 5, 13).display)
        assertEquals("=SUM(B2:B5)", wb.cell(1, 5, 1).input)
        assertEquals(18f, wb.colWidthChars(1, 0))
        assertEquals("=Sales!N6", wb.cell(2, 1, 5).input) // the Notes sheet still points at the original
        wb.setCell(1, 1, 1, "1000")
        assertEquals("120", wb.cell(0, 1, 1).input)
        val out = wb.save()
        val re = XlsxWorkbook.open(out)
        assertEquals(listOf("Sales", "Sales (2)", "Notes"), re.sheetNames)
        assertEquals("1000", re.cell(1, 1, 1).input)
        assertEquals("120", re.cell(0, 1, 1).input)
        val copyPart = OoxmlPackage.read(out).names().first { it.startsWith("xl/worksheets/sheet") && it.endsWith(".xml") && it !in setOf(Fx.SHEET1, Fx.SHEET2) }
        val cx = Fx.text(out, copyPart)
        assertFalse(cx.contains("<drawing"))
        assertFalse(OoxmlPackage.read(out).has(OoxmlPackage.relsOf(copyPart)))
        assertEquals(2, re.charts().size) // charts stay with the original only
        assertEquals(listOf("Sales (2)", "Sales (3)"), wb.let { w -> w.copySheet(1); w.sheetNames.subList(1, 3) })
        assertOthersUntouched(src, out, setOf("xl/workbook.xml", "xl/_rels/workbook.xml.rels", "[Content_Types].xml"))
    }

    @Test fun deleteSheetTurnsReferencesIntoRefErrors() {
        val src = Fx.bytes("xlsx-sales.xlsx")
        val wb = XlsxWorkbook.open(src)
        try { XlsxWorkbook.blank().deleteSheet(0); fail("deleted the only sheet") } catch (e: IllegalStateException) {}
        wb.deleteSheet(0)
        assertEquals(listOf("Notes"), wb.sheetNames)
        assertEquals("=#REF!", wb.cell(0, 1, 5).input)
        assertEquals("#REF!", wb.cell(0, 1, 5).display)
        assertEquals("=SUM(#REF!)*2", wb.cell(0, 2, 5).input)
        assertEquals("310", wb.cell(0, 1, 1).input) // the other sheet's data is unchanged
        val out = wb.save()
        val p = OoxmlPackage.read(out)
        assertFalse(p.has(Fx.SHEET1))
        assertFalse(p.has("xl/worksheets/_rels/sheet1.xml.rels"))
        assertFalse(Fx.text(out, "[Content_Types].xml").contains("/xl/worksheets/sheet1.xml"))
        assertFalse(Fx.text(out, "xl/_rels/workbook.xml.rels").contains("sheet1.xml"))
        val wbx = Fx.text(out, "xl/workbook.xml")
        assertTrue(wbx.contains("<definedName name=\"JanUnits\">#REF!</definedName>"))
        assertTrue(wbx.contains("activeTab=\"0\""))
        val re = XlsxWorkbook.open(out)
        assertEquals(listOf("Notes"), re.sheetNames)
        assertEquals("#REF!", re.cell(0, 1, 5).display)
        assertTrue(Fx.cells(Fx.text(out, Fx.SHEET2)).getValue("F2").contains("<v>#REF!</v>"))
    }

    @Test fun deleteMiddleSheetShiftsLocalNamesAndActiveTab() {
        val wb = XlsxWorkbook.blank()
        wb.addSheet(); wb.addSheet()
        val bytes = wb.save()
        val p = OoxmlPackage.read(bytes)
        p.putText("xl/workbook.xml", p.text("xl/workbook.xml")!!
            .replace("<sheets>", "<bookViews><workbookView activeTab=\"2\"/></bookViews><sheets>")
            .replace("</sheets>", "</sheets><definedNames><definedName name=\"a\" localSheetId=\"1\">Sheet2!\$A\$1</definedName><definedName name=\"b\" localSheetId=\"2\">Sheet3!\$A\$1</definedName></definedNames>"))
        val w2 = XlsxWorkbook.open(p.write())
        w2.deleteSheet(1)
        val x = Fx.text(w2.save(), "xl/workbook.xml")
        assertFalse(x.contains("name=\"a\""))
        assertTrue(x, x.contains("<definedName name=\"b\" localSheetId=\"1\">Sheet3!\$A\$1</definedName>"))
        assertTrue(x.contains("activeTab=\"1\""))
        assertEquals(listOf("Sheet1", "Sheet3"), w2.sheetNames)
    }

    @Test fun pendingEditsSurviveSheetOperations() {
        val wb = XlsxWorkbook.open(Fx.bytes("xlsx-sales.xlsx"))
        wb.setCell(0, 1, 1, "999")
        wb.setColWidth(0, 2, 30f)
        wb.addSheet()
        assertEquals("999", wb.cell(0, 1, 1).input)
        assertEquals(30f, wb.colWidthChars(0, 2))
        assertTrue(wb.dirty)
        val re = XlsxWorkbook.open(wb.save())
        assertEquals("999", re.cell(0, 1, 1).input)
        assertEquals(30f, re.colWidthChars(0, 2))
        assertEquals(3, re.sheetNames.size)
    }
}
