package io.embodit.spatialoffice.core.xlsx

import io.embodit.spatialoffice.core.docx.CompatKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class XlsxReadTest {

    @Test fun a1Helpers() {
        assertEquals("A", A1.colName(0))
        assertEquals("Z", A1.colName(25))
        assertEquals("AA", A1.colName(26))
        assertEquals("AZ", A1.colName(51))
        assertEquals("XFD", A1.colName(16383))
        assertEquals("A1", A1.name(0, 0))
        assertEquals("M5", A1.name(4, 12))
        assertEquals(Pair(4, 12), A1.parse("M5"))
        assertEquals(Pair(2, 1), A1.parse("\$B\$3"))
        assertEquals(Pair(0, 26), A1.parse("aa1"))
        assertEquals(Pair(1048575, 16383), A1.parse("XFD1048576"))
        assertNull(A1.parse("A0"))
        assertNull(A1.parse("A"))
        assertNull(A1.parse("12"))
        assertNull(A1.parse("A1:B2"))
        assertNull(A1.parse("XFE1"))
        assertNull(A1.parse(""))
        for (col in listOf(0, 1, 25, 26, 27, 701, 702, 16383)) assertEquals(col, A1.parse(A1.colName(col) + "7")!!.second)
    }

    @Test fun salesSheetValuesFormatsAndUsedRange() {
        val wb = Fx.sales()
        assertEquals(listOf("Sales", "Notes"), wb.sheetNames)
        assertFalse(wb.dirty)
        assertEquals(8, wb.rows(0))
        assertEquals(15, wb.cols(0))
        assertEquals(6, wb.rows(1))
        assertEquals(6, wb.cols(1))

        val a1 = wb.cell(0, 0, 0)
        assertEquals("Product", a1.display)
        assertEquals(CellKind.TEXT, a1.kind)
        assertTrue(a1.bold)
        assertEquals("Dec", wb.cell(0, 0, 12).display)

        val b2 = wb.cell(0, 1, 1)
        assertEquals(CellKind.NUMBER, b2.kind)
        assertEquals(120.0, b2.number!!, 0.0)
        assertEquals("120", b2.display)
        assertEquals("120", b2.input)
        assertFalse(b2.bold)
        assertFalse(b2.isFormula)
        assertEquals(NumFmt.GENERAL, b2.fmt)

        // the generating library writes formulas without cached values: they are evaluated on open
        val b6 = wb.cell(0, 5, 1)
        assertTrue(b6.isFormula)
        assertEquals("=SUM(B2:B5)", b6.input)
        assertEquals(265.0, b6.number!!, 0.0)
        assertEquals("265", b6.display)

        val n2 = wb.cell(0, 1, 13)
        assertEquals("=SUM(B2:M2)*12.5", n2.input)
        assertEquals(26562.5, n2.number!!, 1e-9)
        assertEquals("¥26,562.50", n2.display)
        assertEquals(NumFmt.CURRENCY, n2.fmt)
        assertEquals("¥56,775.00", wb.cell(0, 5, 13).display)

        val o2 = wb.cell(0, 1, 14)
        assertEquals(NumFmt.PERCENT, o2.fmt)
        assertEquals("46.8%", o2.display)
        assertEquals("100%", wb.cell(0, 5, 14).display)

        assertEquals("FY2026 unit sales — 销售概览", wb.cell(0, 7, 0).display)
        assertTrue(wb.cell(0, 7, 0).bold)
        assertEquals(CellKind.EMPTY, wb.cell(0, 6, 0).kind)
        assertEquals(CellKind.EMPTY, wb.cell(0, 500, 500).kind)
        assertEquals("", wb.cell(0, 500, 500).input)

        assertEquals(18f, wb.colWidthChars(0, 0), 0.001f)
        assertEquals(14.5f, wb.colWidthChars(0, 13), 0.001f)
        assertEquals(8.43f, wb.colWidthChars(0, 3), 0.001f)
    }

    @Test fun secondSheetTextDatesAndCrossSheetFormulas() {
        val wb = Fx.sales()
        assertEquals("R&D <alpha> \"quoted\"", wb.cell(1, 1, 2).display)
        assertEquals("日本語 テスト", wb.cell(1, 2, 2).display)
        assertEquals("naïve café", wb.cell(1, 3, 2).display)
        assertEquals("  padded  ", wb.cell(1, 5, 2).display)

        val d2 = wb.cell(1, 1, 3)
        assertEquals(NumFmt.DATE, d2.fmt)
        assertEquals(CellKind.NUMBER, d2.kind)
        assertEquals("2026-03-01", d2.display)
        assertEquals("2026-03-01", d2.input)
        assertEquals(46082.0, d2.number!!, 0.0)
        // a built-in date format (id 14) is a date too, shown in ISO form
        assertEquals(NumFmt.DATE, wb.cell(1, 5, 3).fmt)
        assertEquals("2026-03-29", wb.cell(1, 5, 3).display)

        val f2 = wb.cell(1, 1, 5)
        assertEquals("=Sales!N6", f2.input)
        assertEquals(56775.0, f2.number!!, 1e-9)
        assertEquals("¥56,775.00", f2.display)
        assertEquals(4250.0, wb.cell(1, 2, 5).number!!, 1e-9)
        val f4 = wb.cell(1, 3, 5)
        assertEquals(CellKind.TEXT, f4.kind)
        assertEquals("big", f4.display)
        assertTrue(f4.isFormula)
        assertEquals(349.0, wb.cell(1, 4, 5).number!!, 1e-9)
        assertEquals(430.0, wb.cell(1, 5, 5).number!!, 1e-9)
    }

    @Test fun handMadeFileSharedStringsCachedValuesAndStyles() {
        val wb = Fx.cached()
        assertEquals(listOf("Data", "Other Sheet"), wb.sheetNames)
        assertEquals(7, wb.rows(0))
        assertEquals(6, wb.cols(0))

        assertEquals("Item", wb.cell(0, 0, 0).display)
        assertTrue(wb.cell(0, 0, 0).bold)
        assertEquals("Price", wb.cell(0, 0, 2).display)            // inline string
        assertEquals("Line total", wb.cell(0, 0, 3).display)       // inline rich text
        assertEquals("Rich text & more", wb.cell(0, 1, 0).display) // rich-text shared string
        assertEquals("東京", wb.cell(0, 2, 0).display)              // phonetic run is not part of the text
        assertEquals("Line\r\nbreak", wb.cell(0, 3, 0).display)    // _xHHHH_ escapes

        val d2 = wb.cell(0, 1, 3)
        assertEquals("=B2*C2", d2.input)
        assertEquals("¥25.00", d2.display)
        assertEquals(NumFmt.CURRENCY, d2.fmt)
        val d4 = wb.cell(0, 3, 3)                                   // dependent cell of a shared formula
        assertEquals("=B4*C4", d4.input)
        assertEquals(45.0, d4.number!!, 0.0)
        assertEquals(60.0, wb.cell(0, 4, 1).number!!, 0.0)
        assertEquals(150.0, wb.cell(0, 4, 3).number!!, 0.0)

        // unknown function: the cached value from the file is kept, the formula text stays visible
        val c5 = wb.cell(0, 4, 2)
        assertEquals("=STDEV(B2:B4)", c5.input)
        assertTrue(c5.isFormula)
        assertEquals(CellKind.NUMBER, c5.kind)
        assertEquals(10.0, c5.number!!, 0.0)

        val a6 = wb.cell(0, 5, 0)
        assertEquals(CellKind.BOOL, a6.kind)
        assertEquals("TRUE", a6.display)
        val b6 = wb.cell(0, 5, 1)
        assertEquals(CellKind.ERROR, b6.kind)
        assertEquals("#DIV/0!", b6.display)
        assertEquals("=1/0", b6.input)
        val c6 = wb.cell(0, 5, 2)
        assertEquals(CellKind.TEXT, c6.kind)
        assertEquals("Item!", c6.display)

        val d6 = wb.cell(0, 5, 3)
        assertEquals(NumFmt.PERCENT, d6.fmt)
        assertEquals("12.50%", d6.display)
        assertEquals("12.5%", d6.input)
        val e6 = wb.cell(0, 5, 4)
        assertEquals(NumFmt.DATE, e6.fmt)
        assertEquals("2026-03-01", e6.display)
        assertEquals("2026-03-01 18:00", wb.cell(0, 5, 5).display)
        val b7 = wb.cell(0, 6, 1)
        assertEquals(NumFmt.OTHER, b7.fmt)
        assertEquals("1,234,567.89", b7.display)
        assertEquals("1234567.891", b7.input)

        // a stale cached value is replaced by the evaluated one in the view (the file itself is not touched)
        assertEquals(120.0, wb.cell(0, 6, 0).number!!, 0.0)

        assertEquals(22.5f, wb.colWidthChars(0, 0), 0.001f)
        assertEquals(12f, wb.colWidthChars(0, 3), 0.001f)

        // second sheet: cross-sheet reference, a defined name (not evaluated: cached value kept), a quoted sheet name
        assertEquals(61.0, wb.cell(1, 0, 0).number!!, 0.0)
        val b1 = wb.cell(1, 0, 1)
        assertEquals("=SUM(Quantities)", b1.input)
        assertEquals(60.0, b1.number!!, 0.0)
        assertEquals(122.0, wb.cell(1, 1, 0).number!!, 0.0)
    }

    @Test fun compatListsWhatIsKeptButNotEdited() {
        val sales = Fx.sales().compat().associateBy { it.code }
        assertEquals(1, sales.getValue("merged-cells").count)
        assertEquals(CompatKind.READ_ONLY, sales.getValue("merged-cells").kind)
        assertEquals(1, sales.getValue("conditional-format").count)
        assertEquals(1, sales.getValue("data-validation").count)
        assertEquals(1, sales.getValue("defined-names").count)
        assertFalse(sales.containsKey("formula"))

        val cached = Fx.cached().compat().associateBy { it.code }
        assertEquals(1, cached.getValue("defined-names").count)
        assertEquals(2, cached.getValue("formula").count) // STDEV(…) and SUM(<name>)
        assertEquals(CompatKind.APPROX, cached.getValue("formula").kind)
    }

    @Test fun formattingAndParsingDoNotDependOnTheDefaultLocale() {
        val saved = Locale.getDefault()
        try {
            for (tag in listOf("de-DE", "tr-TR", "ar-SA", "fr-FR", "hi-IN", "th-TH-u-nu-thai")) {
                Locale.setDefault(Locale.forLanguageTag(tag))
                val wb = Fx.sales()
                assertEquals(tag, "¥26,562.50", wb.cell(0, 1, 13).display)
                assertEquals(tag, "46.8%", wb.cell(0, 1, 14).display)
                assertEquals(tag, "2026-03-01", wb.cell(1, 1, 3).display)
                assertEquals(tag, "1.23E+04", NumFormat.of("0.00E+00").format(12345.0, false))
                assertEquals(tag, "1,234,567.89", NumFormat.of("#,##0.00").format(1234567.891, false))
                wb.setCell(0, 20, 0, "=if(1.5>1,sum(b2:b3)/8,0)")
                assertEquals(tag, "=IF(1.5>1,SUM(B2:B3)/8,0)", wb.cell(0, 20, 0).input)
                assertEquals(tag, 25.0, wb.cell(0, 20, 0).number!!, 0.0)
                wb.setCell(0, 21, 0, "1,234.5")
                wb.setCell(0, 22, 0, "true")
                wb.setCell(0, 23, 0, "bell\u0007")
                assertEquals(tag, 1234.5, wb.cell(0, 21, 0).number!!, 0.0)
                assertEquals(tag, "1234.5", wb.cell(0, 21, 0).input)
                assertEquals(tag, CellKind.BOOL, wb.cell(0, 22, 0).kind)
                val xml = Fx.text(wb.save(), Fx.SHEET1)
                assertTrue(tag, xml.contains("<row r=\"21\"><c r=\"A21\"><f>IF(1.5&gt;1,SUM(B2:B3)/8,0)</f><v>25</v></c></row>"))
                assertTrue(tag, xml.contains("<row r=\"22\"><c r=\"A22\"><v>1234.5</v></c></row>"))
                assertTrue(tag, xml.contains("<c r=\"A24\" t=\"inlineStr\"><is><t>bell_x0007_</t></is></c>"))
                assertTrue(tag, xml.contains("<dimension ref=\"A1:O24\"/>"))
            }
        } finally {
            Locale.setDefault(saved)
        }
    }

    @Test fun numberFormatCodes() {
        fun f(code: String, v: Double) = NumFormat.of(code).format(v, false)
        assertEquals("1,234.50", f("#,##0.00", 1234.5))
        assertEquals("-1,234.50", f("#,##0.00", -1234.5))
        assertEquals("0.13", f("0.00", 0.125))
        assertEquals("1.01", f("0.00", 1.005))
        assertEquals("13%", f("0%", 0.125))
        assertEquals("12.5%", f("0.0%", 0.125))
        assertEquals("$1,200", f("\"$\"#,##0", 1200.0))
        assertEquals("($1,200.00)", f(NumFormat.builtin(7)!!, -1200.0))
        assertEquals("€ 5.00", f("[$€-407] 0.00", 5.0))
        assertEquals("1.23E+04", f("0.00E+00", 12345.0))
        assertEquals("1,235", f("#,##0,", 1234567.0))
        assertEquals("005", f("000", 5.0))
        assertEquals("zero", f("0;-0;\"zero\"", 0.0))
        assertEquals("1234.5", f("General", 1234.5))
        assertEquals("0.1", Num.general(0.1))
        assertEquals("0.3", Num.general(0.1 + 0.2))
        assertEquals("1E+15", Num.general(1e15))
        assertEquals("2026-03-01", f("d\\-mmm\\-yy", 46082.0))
        assertEquals("06:00", f("h:mm", 0.25))
        assertEquals("1900-02-28", f("yyyy-mm-dd", 59.0))
        assertEquals("1900-03-01", f("yyyy-mm-dd", 61.0))
        assertEquals("1904-01-02", NumFormat.of("yyyy-mm-dd").format(1.0, true))

        assertEquals(NumFmt.GENERAL, NumFormat.of("General").kind)
        assertEquals(NumFmt.CURRENCY, NumFormat.of("\"¥\"#,##0.00").kind)
        assertEquals(NumFmt.CURRENCY, NumFormat.of("[$€-407] 0.00").kind)
        assertEquals(NumFmt.CURRENCY, NumFormat.of(NumFormat.builtin(44)!!).kind)
        assertEquals(NumFmt.PERCENT, NumFormat.of("0.00%").kind)
        assertEquals(NumFmt.DATE, NumFormat.of("[$-409]d\\-mmm\\-yyyy;@").kind)
        assertEquals(NumFmt.DATE, NumFormat.of("[h]:mm:ss").kind)
        assertEquals(NumFmt.OTHER, NumFormat.of("#,##0.00").kind)
        assertEquals(NumFmt.OTHER, NumFormat.of("[Red]0.00").kind)
    }
}
