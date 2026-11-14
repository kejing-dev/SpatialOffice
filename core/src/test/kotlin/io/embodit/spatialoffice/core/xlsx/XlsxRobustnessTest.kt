package io.embodit.spatialoffice.core.xlsx

import io.embodit.spatialoffice.core.pkg.OoxmlPackage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Files that are valid but written differently from the fixtures: prefixed namespaces, white space, implicit addresses. */
class XlsxRobustnessTest {
    private val main = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"

    /** Puts every element of the default namespace under the prefix `x:`. */
    private fun prefixed(xml: String): String =
        xml.replace(Regex("<(/?)([A-Za-z][A-Za-z0-9]*)([\\s>/])")) { m -> "<${m.groupValues[1]}x:${m.groupValues[2]}${m.groupValues[3]}" }
            .replaceFirst("xmlns=\"$main\"", "xmlns:x=\"$main\"")

    private fun prefixedFixture(): ByteArray {
        val pkg = OoxmlPackage.read(Fx.bytes("xlsx-cached.xlsx"))
        for (part in listOf(Fx.SHEET1, Fx.SHEET2, Fx.STYLES, Fx.SHARED, "xl/workbook.xml")) pkg.putText(part, prefixed(pkg.text(part)!!))
        return pkg.write()
    }

    @Test fun prefixedNamespacesAreReadAndPatchedWithTheSamePrefix() {
        val src = prefixedFixture()
        assertTrue(Fx.text(src, Fx.SHEET1).contains("<x:c r=\"C2\" s=\"2\"><x:v>2.5</x:v></x:c>"))
        val wb = XlsxWorkbook.open(src)
        assertEquals(listOf("Data", "Other Sheet"), wb.sheetNames)
        assertEquals("Rich text & more", wb.cell(0, 1, 0).display)
        assertEquals("Line total", wb.cell(0, 0, 3).display)
        assertTrue(wb.cell(0, 0, 0).bold)
        assertEquals("¥25.00", wb.cell(0, 1, 3).display)
        assertEquals("=B4*C4", wb.cell(0, 3, 3).input)
        assertEquals("2026-03-01", wb.cell(0, 5, 4).display)
        assertEquals(22.5f, wb.colWidthChars(0, 0), 0.001f)
        assertEquals(61.0, wb.cell(1, 0, 0).number!!, 0.0)
        Fx.assertSameParts(src, wb.save())

        wb.setCell(0, 1, 2, "3")              // C2
        wb.setCell(0, 9, 0, "new")            // A10, a new row
        wb.setBold(0, 1, 2, true)             // C2: appended cell format
        wb.setFmt(0, 2, 1, NumFmt.PERCENT)    // B3: appended cell format
        wb.setCell(0, 6, 2, "07:15")          // C7: appended number format
        val saved = Fx.out("xlsx-prefixed-edit.xlsx", wb.save())
        assertEquals(setOf(Fx.SHEET1, Fx.STYLES), Fx.changedParts(src, saved))
        val x = Fx.text(saved, Fx.SHEET1)
        assertTrue(x.contains("<x:c r=\"C2\" s=\"7\"><x:v>3</x:v></x:c>"))
        assertTrue(x.contains("<x:c r=\"D2\" s=\"2\"><x:f>B2*C2</x:f><x:v>30</x:v></x:c>"))
        assertTrue(x.contains("<x:row r=\"10\"><x:c r=\"A10\" t=\"inlineStr\"><x:is><x:t>new</x:t></x:is></x:c></x:row></x:sheetData>"))
        assertTrue(x.contains("<x:c r=\"B3\" s=\"8\"><x:v>20</x:v></x:c>"))
        val st = Fx.text(saved, Fx.STYLES)
        assertTrue(st.contains("<x:numFmts count=\"3\">"))
        assertTrue(st.contains("<x:numFmt numFmtId=\"166\" formatCode=\"hh:mm\"/></x:numFmts>"))
        assertTrue(st.contains("<x:cellXfs count=\"10\">"))
        assertFalse(st.contains("<xf ") || st.contains("<numFmt ") || x.contains("<c ") || x.contains("<row "))

        val re = XlsxWorkbook.open(saved)
        assertEquals("¥3.00", re.cell(0, 1, 2).display)
        assertTrue(re.cell(0, 1, 2).bold)
        assertEquals("2000%", re.cell(0, 2, 1).display)
        assertEquals("07:15", re.cell(0, 6, 2).display)
        assertEquals("new", re.cell(0, 9, 0).display)
        assertEquals(155.0, re.cell(0, 4, 3).number!!, 0.0)

        // structural edits on the prefixed sheet
        re.insertRowBelow(0, 0)
        assertTrue(re.sortRows(0, 1, false, 2, 4))
        val moved = Fx.out("xlsx-prefixed-structural.xlsx", re.save())
        val y = Fx.text(moved, Fx.SHEET1)
        assertFalse(y.contains("<c ") || y.contains("<row ") || y.contains("<f>") || y.contains("<v>"))
        val re2 = XlsxWorkbook.open(moved)
        assertEquals("Item", re2.cell(0, 0, 0).display)
        assertEquals(CellKind.EMPTY, re2.cell(0, 1, 0).kind)
        assertEquals(listOf(30.0, 20.0, 10.0), (2..4).map { re2.cell(0, it, 1).number })
        assertEquals(listOf("=B3*C3", "=B4*C4", "=B5*C5"), (2..4).map { re2.cell(0, it, 3).input })
        assertEquals("=SUM(B3:B5)", re2.cell(0, 5, 1).input)
        assertEquals(60.0, re2.cell(0, 5, 1).number!!, 0.0)
        assertEquals("=Data!B6+1", re2.cell(1, 0, 0).input)
        assertTrue(Fx.text(moved, "xl/workbook.xml").contains(">Data!\$B\$3:\$B\$5</x:definedName>"))
    }

    @Test fun whiteSpaceAndCommentsBetweenElementsAreKept() {
        val pkg = OoxmlPackage.read(Fx.bytes("xlsx-cached.xlsx"))
        val pretty = pkg.text(Fx.SHEET1)!!
            .replace("<row ", "\n  <row ").replace("<c ", "\n    <c ").replace("</row>", "\n  </row>")
            .replace("<sheetData>", "<sheetData><!-- <row r=\"99\"> is only a comment -->")
            .replace("</sheetData>", "\n</sheetData>\n<!-- end of data -->\n")
        pkg.putText(Fx.SHEET1, pretty)
        val src = pkg.write()
        val wb = XlsxWorkbook.open(src)
        assertEquals(7, wb.rows(0))
        assertEquals(150.0, wb.cell(0, 4, 3).number!!, 0.0)
        Fx.assertSameParts(src, wb.save())

        wb.setCell(0, 1, 2, "3")   // C2
        wb.setCell(0, 0, 5, "x")   // F1, new cell at the end of a row
        wb.setCell(0, 9, 2, "42")  // C10, new row
        val saved = Fx.out("xlsx-pretty-edit.xlsx", wb.save())
        assertEquals(setOf(Fx.SHEET1), Fx.changedParts(src, saved))
        val row1End = pretty.indexOf("\n  </row>")
        val expected = (pretty.substring(0, row1End) + "\n  <c r=\"F1\" t=\"inlineStr\"><is><t>x</t></is></c></row>" + pretty.substring(row1End + "\n  </row>".length))
            .replace("<row r=\"1\" spans=\"1:4\">", "<row r=\"1\" spans=\"1:6\">")
            .replace("<c r=\"C2\" s=\"2\"><v>2.5</v></c>", "<c r=\"C2\" s=\"2\"><v>3</v></c>")
            .replace("<c r=\"D2\" s=\"2\"><f>B2*C2</f><v>25</v></c>", "<c r=\"D2\" s=\"2\"><f>B2*C2</f><v>30</v></c>")
            .replace("<c r=\"D5\" s=\"2\"><f>SUM(D2:D4)</f><v>150</v></c>", "<c r=\"D5\" s=\"2\"><f>SUM(D2:D4)</f><v>155</v></c>")
            .replace("<dimension ref=\"A1:F7\"/>", "<dimension ref=\"A1:F10\"/>")
            .replace("\n</sheetData>", "\n<row r=\"10\"><c r=\"C10\"><v>42</v></c></row></sheetData>")
        assertEquals(expected, Fx.text(saved, Fx.SHEET1))
        val re = XlsxWorkbook.open(saved)
        assertEquals("x", re.cell(0, 0, 5).display)
        assertEquals(42.0, re.cell(0, 9, 2).number!!, 0.0)
        assertEquals(155.0, re.cell(0, 4, 3).number!!, 0.0)
        // a structural edit regenerates the rows; everything outside <sheetData> keeps its text
        re.insertRowBelow(0, 6)
        val moved = re.save()
        val a = Fx.text(moved, Fx.SHEET1)
        val b = Fx.text(saved, Fx.SHEET1)
        assertEquals(b.substring(b.indexOf("</sheetData>")), a.substring(a.indexOf("</sheetData>")))
        assertEquals(b.substring(0, b.indexOf("<dimension")), a.substring(0, a.indexOf("<dimension")))
        assertEquals(42.0, XlsxWorkbook.open(moved).cell(0, 10, 2).number!!, 0.0)
    }

    @Test fun rowsAndCellsWithoutAddressesAndCdataText() {
        val pkg = OoxmlPackage.read(XlsxWorkbook.blank().save())
        pkg.putText(
            Fx.SHEET1,
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<worksheet xmlns=\"$main\"><sheetData>" +
                "<row><c><v>1</v></c><c t=\"inlineStr\"><is><t><![CDATA[a<b & c]]></t></is></c></row>" +
                "<row><c><f>A1+1</f></c><c/><c t=\"str\"><f>B1&amp;\"!\"</f><v>stale</v></c></row>" +
                "<row r=\"5\"><c r=\"C5\"><v>7</v></c><c><v>8</v></c></row>" +
                "</sheetData></worksheet>",
        )
        val src = pkg.write()
        val wb = XlsxWorkbook.open(src)
        assertEquals(1.0, wb.cell(0, 0, 0).number!!, 0.0)
        assertEquals("a<b & c", wb.cell(0, 0, 1).display)
        assertEquals(2.0, wb.cell(0, 1, 0).number!!, 0.0)
        assertEquals("a<b & c!", wb.cell(0, 1, 2).display)
        assertEquals(7.0, wb.cell(0, 4, 2).number!!, 0.0)
        assertEquals(8.0, wb.cell(0, 4, 3).number!!, 0.0)
        assertEquals(5, wb.rows(0))
        assertEquals(4, wb.cols(0))
        Fx.assertSameParts(src, wb.save())

        wb.setCell(0, 0, 0, "5")
        wb.setCell(0, 1, 3, "end")
        wb.setCell(0, 2, 0, "between")
        val saved = wb.save()
        assertEquals(
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<worksheet xmlns=\"$main\"><sheetData>" +
                "<row><c r=\"A1\"><v>5</v></c><c t=\"inlineStr\"><is><t><![CDATA[a<b & c]]></t></is></c></row>" +
                "<row><c r=\"A2\"><f>A1+1</f><v>6</v></c><c/><c t=\"str\"><f>B1&amp;\"!\"</f><v>stale</v></c><c r=\"D2\" t=\"inlineStr\"><is><t>end</t></is></c></row>" +
                "<row r=\"3\"><c r=\"A3\" t=\"inlineStr\"><is><t>between</t></is></c></row>" +
                "<row r=\"5\"><c r=\"C5\"><v>7</v></c><c><v>8</v></c></row>" +
                "</sheetData></worksheet>",
            Fx.text(saved, Fx.SHEET1),
        )
        val re = XlsxWorkbook.open(saved)
        assertEquals(6.0, re.cell(0, 1, 0).number!!, 0.0)
        assertEquals("end", re.cell(0, 1, 3).display)
        assertEquals("between", re.cell(0, 2, 0).display)
        assertEquals(8.0, re.cell(0, 4, 3).number!!, 0.0)
    }

    @Test fun workbookWithoutStylesPartGetsOneWhenAFormatIsNeeded() {
        val base = OoxmlPackage.read(XlsxWorkbook.blank().save())
        base.remove("xl/styles.xml")
        base.putText("xl/_rels/workbook.xml.rels", base.text("xl/_rels/workbook.xml.rels")!!.replace(Regex("<Relationship Id=\"rId2\"[^>]*/>"), ""))
        base.putText("[Content_Types].xml", base.text("[Content_Types].xml")!!.replace(Regex("<Override PartName=\"/xl/styles.xml\"[^>]*/>"), ""))
        val src = base.write()
        val wb = XlsxWorkbook.open(src)
        wb.setCell(0, 0, 0, "plain")
        val plain = wb.save()
        assertFalse(OoxmlPackage.read(plain).has("xl/styles.xml"))
        wb.setBold(0, 0, 0, true)
        wb.setCell(0, 1, 0, "50%")
        val saved = Fx.out("xlsx-nostyles-edit.xlsx", wb.save())
        val pkg = OoxmlPackage.read(saved)
        assertTrue(pkg.has("xl/styles.xml"))
        assertTrue(pkg.text("xl/_rels/workbook.xml.rels")!!.contains("Target=\"styles.xml\""))
        assertTrue(pkg.text("[Content_Types].xml")!!.contains("PartName=\"/xl/styles.xml\""))
        val re = XlsxWorkbook.open(saved)
        assertTrue(re.cell(0, 0, 0).bold)
        assertEquals("50%", re.cell(0, 1, 0).display)
    }
}
