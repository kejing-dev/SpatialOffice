package io.embodit.spatialoffice.core.xlsx

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** M2 (v0.4.0, s23): italic / underline / strikethrough / font colour / size / clear on cells. */
class XlsxM2Test {

    private fun fontsXml(bytes: ByteArray): String {
        val st = Fx.text(bytes, Fx.STYLES)
        return st.substring(st.indexOf("<fonts"), st.indexOf("</fonts>") + 8)
    }

    @Test fun rangeItalicRedReadsBackAfterSave() {
        val src = Fx.bytes("xlsx-sales.xlsx")
        val wb = XlsxWorkbook.open(src)
        val before = (0..3).flatMap { r -> (0..3).map { c -> Triple(r, c, wb.cell(0, r, c)) } }
        for (r in 1..2) for (c in 1..3) { wb.setItalic(0, r, c, true); wb.setFontColor(0, r, c, 0xFF0000) }
        for (r in 1..2) for (c in 1..3) {
            val cell = wb.cell(0, r, c)
            assertTrue(cell.italic); assertEquals(0xFF0000, cell.colorRgb)
        }
        val out = Fx.out("m2-italic-red.xlsx", wb.save())
        assertEquals(setOf(Fx.SHEET1, Fx.STYLES), Fx.changedParts(src, out))
        val fonts = fontsXml(out)
        assertTrue(fonts, Regex("<font>(?:(?!</font>).)*<i/>(?:(?!</font>).)*<color rgb=\"FFFF0000\"/>").containsMatchIn(fonts))
        val re = XlsxWorkbook.open(out)
        for (r in 1..2) for (c in 1..3) {
            val cell = re.cell(0, r, c)
            assertTrue("italic $r,$c", cell.italic)
            assertEquals(0xFF0000, cell.colorRgb)
            // the value and the other format attributes are those of the original cell
            val orig = before.first { it.first == r && it.second == c }.third
            assertEquals(orig.display, cell.display)
            assertEquals(orig.bold, cell.bold)
            assertEquals(orig.fmt, cell.fmt)
        }
        // cells outside the range keep their font
        for ((r, c, orig) in before) if (r !in 1..2 || c !in 1..3) {
            val cell = re.cell(0, r, c)
            assertEquals(orig.italic, cell.italic); assertEquals(orig.colorRgb, cell.colorRgb); assertEquals(orig.bold, cell.bold)
        }
    }

    @Test fun underlineStrikeSizeAndClearRoundTrip() {
        val wb = Fx.sales()
        wb.setUnderline(0, 1, 1, true)
        wb.setStrike(0, 1, 1, true)
        wb.setFontSize(0, 1, 1, 18f)
        wb.setBold(0, 1, 1, true)
        wb.setFontColor(0, 1, 1, 0x0070C0)
        var re = XlsxWorkbook.open(wb.save())
        var c = re.cell(0, 1, 1)
        assertTrue(c.underline); assertTrue(c.strike); assertTrue(c.bold)
        assertEquals(18f, c.fontPt)
        assertEquals(0x0070C0, c.colorRgb)
        re.clearFont(0, 1, 1)
        re = XlsxWorkbook.open(re.save())
        c = re.cell(0, 1, 1)
        assertFalse(c.underline); assertFalse(c.strike); assertFalse(c.bold); assertFalse(c.italic)
        assertNull(c.colorRgb)
        assertEquals(re.defaultFontPt(), c.fontPt)
    }

    @Test fun childOrderFollowsTheSchema() {
        val wb = XlsxWorkbook.blank()
        wb.setCell(0, 0, 0, "x")
        wb.setFontColor(0, 0, 0, 0x112233)
        wb.setStrike(0, 0, 0, true)
        wb.setItalic(0, 0, 0, true)
        wb.setUnderline(0, 0, 0, true)
        val font = Regex("<font>((?:(?!</font>).)*)</font>").findAll(fontsXml(wb.save())).last().groupValues[1]
        val order = Regex("<(\\w+)").findAll(font).map { it.groupValues[1] }.toList()
        assertEquals(listOf("i", "strike", "u", "sz", "color"), order)
    }

    @Test fun noOpEditsLeaveTheFileAlone() {
        val src = Fx.bytes("xlsx-sales.xlsx")
        val wb = XlsxWorkbook.open(src)
        wb.setItalic(0, 1, 1, false)
        wb.setStrike(0, 1, 1, false)
        wb.setUnderline(0, 1, 1, false)
        assertTrue(Fx.changedParts(src, wb.save()).isEmpty())
        // a font without a colour element: "automatic" changes nothing either
        val blank = XlsxWorkbook.blank()
        blank.setCell(0, 0, 0, "x")
        val b0 = blank.save()
        blank.setFontColor(0, 0, 0, null)
        assertTrue(Fx.changedParts(b0, blank.save()).isEmpty())
    }

    @Test fun emptyCellGetsAStyleAndTogglingBackReusesFonts() {
        val wb = XlsxWorkbook.blank()
        wb.setItalic(0, 4, 4, true)
        assertTrue(wb.cell(0, 4, 4).italic)
        wb.setItalic(0, 4, 4, false)
        wb.setItalic(0, 5, 5, true)
        val fonts = fontsXml(wb.save())
        assertEquals(2, Regex("<font>").findAll(fonts).count())
        assertEquals(11f, wb.defaultFontPt())
    }
}
