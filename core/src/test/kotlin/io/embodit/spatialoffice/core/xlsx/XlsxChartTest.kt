package io.embodit.spatialoffice.core.xlsx

import io.embodit.spatialoffice.core.pkg.OoxmlPackage
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class XlsxChartTest {
    private val months = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

    @Test fun readsBarAndScatterChartsThroughSheetDrawingRelationships() {
        val charts = Fx.sales().charts()
        assertEquals(2, charts.size)

        val bar = charts[0]
        assertEquals("xl/charts/chart1.xml", bar.part)
        assertEquals(0, bar.sheet)
        assertEquals("bar", bar.type)
        assertEquals("Units by month", bar.title)
        assertEquals("A1:M5", bar.range)
        assertEquals(listOf("Headset", "Controller", "Strap", "Case"), bar.series.map { it.name })
        for (s in bar.series) {
            assertEquals(months, s.categories)
            assertEquals(12, s.values.size)
            assertNull(s.xValues)
        }
        assertEquals(listOf(120.0, 135.0, 150.0), bar.series[0].values.take(3))
        assertEquals(62.0, bar.series[3].values[11]!!, 0.0)
        assertEquals("'Sales'!\$B\$2:\$M\$2", bar.series[0].valuesRef)

        val sc = charts[1]
        assertEquals("xl/charts/chart2.xml", sc.part)
        assertEquals(1, sc.sheet)
        assertEquals("scatter", sc.type)
        assertEquals("Visitors per week", sc.title)
        assertEquals("A1:B6", sc.range)
        assertEquals(1, sc.series.size)
        assertEquals("Visitors", sc.series[0].name)
        assertEquals(listOf(1.0, 2.0, 3.0, 4.0, 5.0), sc.series[0].xValues)
        assertEquals(listOf(310.0, 290.0, 355.0, 402.0, 388.0), sc.series[0].values)
        assertEquals(listOf("1", "2", "3", "4", "5"), sc.series[0].categories)
    }

    @Test fun chartValuesFollowEditsWithoutSaving() {
        val wb = Fx.sales()
        wb.setCell(0, 1, 1, "500")          // Sales!B2: first value of the first series
        wb.setCell(0, 2, 0, "Gamepad")      // a series name
        wb.setCell(0, 0, 2, "February")     // a category
        wb.setCell(0, 4, 12, "")            // a value cleared → gap
        wb.setCell(1, 1, 1, "=Sales!B2*2")  // scatter y value from a formula
        wb.setCell(1, 0, 1, "Guests")       // scatter series name
        val charts = wb.charts()
        assertEquals(500.0, charts[0].series[0].values[0]!!, 0.0)
        assertEquals("Gamepad", charts[0].series[1].name)
        assertEquals("February", charts[0].series[0].categories[1])
        assertNull(charts[0].series[3].values[11])
        assertEquals(1000.0, charts[1].series[0].values[0]!!, 0.0)
        assertEquals("Guests", charts[1].series[0].name)
        // the chart parts themselves are not rewritten by cell edits
        val src = Fx.bytes("xlsx-sales.xlsx")
        val saved = wb.save()
        assertEquals(setOf(Fx.SHEET1, Fx.SHEET2), Fx.changedParts(src, saved))
        assertEquals(500.0, XlsxWorkbook.open(saved).charts()[0].series[0].values[0]!!, 0.0)
    }

    @Test fun otherChartKindsAndCachedFallback() {
        // the same chart part with its type element renamed, and one whose references cannot be resolved
        fun variant(from: String, to: String): List<ChartInfo> {
            val pkg = OoxmlPackage.read(Fx.bytes("xlsx-sales.xlsx"))
            pkg.putText("xl/charts/chart1.xml", pkg.text("xl/charts/chart1.xml")!!.replace(from, to))
            return XlsxWorkbook.open(pkg.write()).charts()
        }
        assertEquals("line", variant("barChart>", "lineChart>")[0].type)
        assertEquals("pie", variant("barChart>", "pieChart>")[0].type)
        assertEquals("area", variant("barChart>", "areaChart>")[0].type)
        assertEquals("other", variant("barChart>", "radarChart>")[0].type)
        val lost = variant(
            "<f>'Sales'!\$B\$2:\$M\$2</f>",
            "<f>'Gone'!\$B\$2:\$D\$2</f><numCache><ptCount val=\"3\"/><pt idx=\"0\"><v>7</v></pt><pt idx=\"2\"><v>9.5</v></pt></numCache>",
        )[0]
        assertEquals(listOf(7.0, null, 9.5), lost.series[0].values)
        assertNull(lost.range)
        assertEquals(12, lost.series[1].values.size)
    }

    @Test fun addBarChartToBlankWorkbook() {
        val wb = XlsxWorkbook.blank()
        val header = listOf("", "Q1", "Q2", "Q3")
        val rows = listOf(listOf("North & <West>", "10", "12", "15"), listOf("南", "7", "", "9.5"))
        for ((c, v) in header.withIndex()) wb.setCell(0, 0, c, v)
        for ((r, row) in rows.withIndex()) for ((c, v) in row.withIndex()) wb.setCell(0, r + 1, c, v)
        val info = wb.addBarChart(0, "A1:D3", "Quarterly <units> & more")
        assertTrue(wb.dirty)
        assertEquals("xl/charts/chart1.xml", info.part)
        assertEquals(0, info.sheet)
        assertEquals("bar", info.type)
        assertEquals("Quarterly <units> & more", info.title)
        assertEquals("A1:D3", info.range)
        assertEquals(listOf("North & <West>", "南"), info.series.map { it.name })
        assertEquals(listOf("Q1", "Q2", "Q3"), info.series[0].categories)
        assertEquals(listOf(10.0, 12.0, 15.0), info.series[0].values)
        assertEquals(listOf(7.0, null, 9.5), info.series[1].values)
        assertEquals("Sheet1!\$B\$3:\$D\$3", info.series[1].valuesRef)

        val saved = Fx.out("xlsx-blank-chart.xlsx", wb.save())
        val pkg = OoxmlPackage.read(saved)
        assertTrue(pkg.has("xl/charts/chart1.xml"))
        assertTrue(pkg.has("xl/drawings/drawing1.xml"))
        assertTrue(pkg.text("xl/worksheets/_rels/sheet1.xml.rels")!!.contains("Target=\"../drawings/drawing1.xml\""))
        assertTrue(pkg.text("xl/drawings/_rels/drawing1.xml.rels")!!.contains("Target=\"../charts/chart1.xml\""))
        val ct = pkg.text("[Content_Types].xml")!!
        assertTrue(ct.contains("<Override PartName=\"/xl/charts/chart1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.drawingml.chart+xml\"/>"))
        assertTrue(ct.contains("<Override PartName=\"/xl/drawings/drawing1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.drawing+xml\"/>"))
        assertTrue(pkg.text(Fx.SHEET1)!!.endsWith("</sheetData><drawing r:id=\"rId1\"/></worksheet>"))

        val re = XlsxWorkbook.open(saved)
        assertEquals(1, re.charts().size)
        assertEquals(info, re.charts()[0])
        // live after reopening too
        re.setCell(0, 2, 2, "8")
        assertEquals(listOf(7.0, 8.0, 9.5), re.charts()[0].series[1].values)
        // a second chart goes into the same drawing
        val second = re.addBarChart(0, "A1:C2", "")
        assertEquals("xl/charts/chart2.xml", second.part)
        assertEquals("", second.title)
        val twice = Fx.out("xlsx-blank-two-charts.xlsx", re.save())
        assertEquals(2, XlsxWorkbook.open(twice).charts().size)
        assertFalse(OoxmlPackage.read(twice).has("xl/drawings/drawing2.xml"))
    }

    @Test fun addBarChartNextToAnExistingChartKeepsTheOldOneUntouched() {
        val src = Fx.bytes("xlsx-sales.xlsx")
        val wb = XlsxWorkbook.open(src)
        val info = wb.addBarChart(0, "A1:D5", "First quarter")
        assertEquals("xl/charts/chart3.xml", info.part)
        assertEquals(3, wb.charts().size)
        assertEquals(listOf(0, 0, 1), wb.charts().map { it.sheet }.sorted())
        assertEquals("A1:D5", info.range)
        assertEquals(listOf("Headset", "Controller", "Strap", "Case"), info.series.map { it.name })
        assertEquals(listOf(120.0, 135.0, 150.0), info.series[0].values)
        val saved = Fx.out("xlsx-sales-add-chart.xlsx", wb.save())
        assertEquals(
            setOf("[Content_Types].xml", "xl/charts/chart3.xml", "xl/drawings/drawing1.xml", "xl/drawings/_rels/drawing1.xml.rels"),
            Fx.changedParts(src, saved),
        )
        // the sheet already had a drawing, so the sheet part itself did not change; the old chart is the same bytes
        assertArrayEquals(Fx.parts(src).getValue("xl/charts/chart1.xml"), Fx.parts(saved).getValue("xl/charts/chart1.xml"))
        val before = Fx.text(src, "xl/drawings/drawing1.xml")
        val after = Fx.text(saved, "xl/drawings/drawing1.xml")
        assertTrue(after.startsWith(before.removeSuffix("</wsDr>")))
        assertTrue(after.endsWith("</xdr:twoCellAnchor></wsDr>"))
        val re = XlsxWorkbook.open(saved)
        assertEquals(3, re.charts().size)
        assertEquals("First quarter", re.charts().first { it.part == "xl/charts/chart3.xml" }.title)
    }

    @Test fun addBarChartRejectsRangesWithoutData() {
        val wb = XlsxWorkbook.blank()
        for (bad in listOf("A1", "A1:A5", "A1:D1", "nonsense")) {
            try {
                wb.addBarChart(0, bad, "x")
                throw AssertionError("accepted $bad")
            } catch (e: IllegalArgumentException) {
                // expected
            }
        }
        assertFalse(wb.dirty)
        assertTrue(wb.charts().isEmpty())
    }
}
