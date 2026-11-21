package io.embodit.spatialoffice.core.pptx

import io.embodit.spatialoffice.core.docx.PackageAsserts.assertConsistent
import io.embodit.spatialoffice.core.docx.PackageAsserts.changedParts
import io.embodit.spatialoffice.core.docx.PackageAsserts.fixture
import io.embodit.spatialoffice.core.docx.PackageAsserts.text
import io.embodit.spatialoffice.core.docx.PackageAsserts.writeRoundTrip
import io.embodit.spatialoffice.core.pkg.OoxmlPackage
import io.embodit.spatialoffice.core.pkg.Rels
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** v0.5.0 M3: p26 slide background (colour / picture, one slide / all), p40 table rows, columns and merged cells. */
class PptxM3Test {
    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3, 4)

    private fun bgOf(xml: String): String? = Regex("<p:bg>.*?</p:bg>").find(xml)?.value

    // ---------------------------------------------------------------------------------------------- p26

    @Test fun solidBackgroundOnOneSlideIsTheFirstChildOfCSldAndOnlyThatPartChanges() {
        val before = fixture("pptx-deck.pptx")
        val d = PptxDeck.open(before)
        val part = "ppt/slides/slide2.xml"
        val old = text(before, part)
        d.setSlideBackground(1, 0x1E3A8A)
        val after = d.save()
        assertEquals(setOf(part), changedParts(before, after))
        val x = text(after, part)
        assertTrue(x.contains("<p:cSld><p:bg><p:bgPr><a:solidFill><a:srgbClr val=\"1E3A8A\"/></a:solidFill><a:effectLst/></p:bgPr></p:bg>") ||
            Regex("<p:cSld[^>]*><p:bg><p:bgPr><a:solidFill><a:srgbClr val=\"1E3A8A\"/>").containsMatchIn(x))
        // nothing but the inserted background differs
        assertEquals(old, x.replace(bgOf(x)!!, ""))
        val back = PptxDeck.open(after)
        assertEquals(0x1E3A8A, back.slides[1].backgroundRgb)
        assertTrue(back.slides[1].ownBackground)
        assertFalse(back.slides[0].ownBackground)
        assertConsistent(after)
    }

    @Test fun backgroundOnAllSlidesThenRemoveGivesTheOriginalBack() {
        val before = fixture("pptx-deck.pptx")
        val d = PptxDeck.open(before)
        val n = d.slides.size
        for (i in 0 until n) d.setSlideBackground(i, 0xFFF7ED)
        val all = d.save()
        val back = PptxDeck.open(all)
        assertTrue(back.slides.all { it.backgroundRgb == 0xFFF7ED && it.ownBackground })
        for (i in 0 until n) back.setSlideBackground(i, null)
        val removed = back.save()
        // slides that had no background of their own are byte-identical again
        val orig = OoxmlPackage.read(before)
        for (p in OoxmlPackage.read(removed).names().filter { it.startsWith("ppt/slides/slide") && it.endsWith(".xml") }) {
            if (bgOf(orig.text(p)!!) == null) assertEquals(p, orig.text(p), text(removed, p))
        }
        assertConsistent(removed)
    }

    @Test fun pictureBackgroundSharedByEverySlideAndLetGoWhenRemoved() {
        val before = fixture("pptx-deck.pptx")
        val d = PptxDeck.open(before)
        val media = d.setSlideBackgroundPicture(0, png, "png")
        for (i in 1 until d.slides.size) d.setSlideBackgroundPicturePart(i, media)
        val saved = d.save()
        assertConsistent(saved)
        val pkg = OoxmlPackage.read(saved)
        assertArrayEquals(png, pkg.bytes(media))
        val back = PptxDeck.open(saved)
        assertTrue(back.slides.all { it.backgroundImage == media })
        val x = pkg.text("ppt/slides/slide1.xml")!!
        val rid = Regex("<a:blip(?: [^>]*)? r:embed=\"(rId\\d+)\"").find(bgOf(x)!!)!!.groupValues[1]
        assertEquals(media, Rels.of(pkg, "ppt/slides/slide1.xml").first { it.id == rid }.let { OoxmlPackage.resolve("ppt/slides/slide1.xml", it.target) })
        // one picture part only
        assertEquals(1, pkg.names().count { pkg.bytes(it)?.contentEquals(png) == true })
        // removing it from every slide drops the relationships and the picture
        for (i in back.slides.indices) back.setSlideBackground(i, null)
        val gone = back.save()
        assertConsistent(gone)
        assertNull(OoxmlPackage.read(gone).bytes(media))
        assertTrue(PptxDeck.open(gone).slides.none { it.backgroundImage != null })
    }

    @Test fun aColourReplacesAPictureBackground() {
        val d = PptxDeck.open(fixture("pptx-deck.pptx"))
        val media = d.setSlideBackgroundPicture(2, png, "png")
        d.setSlideBackground(2, 0x111111)
        val saved = d.save()
        assertConsistent(saved)
        val back = PptxDeck.open(saved)
        assertNull(back.slides[2].backgroundImage)
        assertEquals(0x111111, back.slides[2].backgroundRgb)
        assertNull(OoxmlPackage.read(saved).bytes(media))
    }

    // ---------------------------------------------------------------------------------------------- p40

    private fun tableDeck(rows: Int = 3, cols: Int = 3): Triple<PptxDeck, Int, ByteArray> {
        val d = PptxDeck.open(fixture("pptx-deck.pptx"))
        val id = d.addTable(0, rows, cols, 1_000_000, 1_000_000, 6_000_000, 1_500_000)
        for (r in 0 until rows) for (c in 0 until cols) d.setTableCell(0, id, r, c, "r${r}c$c")
        val bytes = d.save()
        return Triple(PptxDeck.open(bytes), id, bytes)
    }

    private fun tblXml(bytes: ByteArray): String = Regex("<a:tbl>.*?</a:tbl>", RegexOption.DOT_MATCHES_ALL).find(text(bytes, "ppt/slides/slide1.xml"))!!.value
    private fun gridW(t: String) = Regex("<a:gridCol w=\"(\\d+)\"").findAll(t).map { it.groupValues[1].toLong() }.toList()
    private fun frameCx(bytes: ByteArray, id: Int) = PptxDeck.open(bytes).slides[0].shapes.first { it.id == id }.w

    @Test fun insertAndDeleteRowsKeepTheFrameHeightInStepAndOnlyTheSlideChanges() {
        val (d, id, before) = tableDeck()
        val h0 = d.slides[0].shapes.first { it.id == id }.h
        assertTrue(d.tableInsertRow(0, id, 0, below = true))
        val one = d.save()
        assertEquals(setOf("ppt/slides/slide1.xml"), changedParts(before, one))
        val t = PptxDeck.open(one).slides[0].shapes.first { it.id == id }
        assertEquals(4, t.table!!.size)
        assertEquals(listOf("", "", ""), t.table!![1])
        assertEquals("r1c0", t.table!![2][0])
        assertEquals(h0 + 500_000, t.h)
        assertEquals(4, Regex("<a:tr ").findAll(tblXml(one)).count())
        // above row 0, then delete it again
        assertTrue(d.tableInsertRow(0, id, 0, below = false))
        assertEquals(listOf("", "", ""), d.slides[0].shapes.first { it.id == id }.table!![0])
        assertTrue(d.tableDeleteRow(0, id, 0))
        assertTrue(d.tableDeleteRow(0, id, 1))
        val back = d.save()
        val t2 = PptxDeck.open(back).slides[0].shapes.first { it.id == id }
        assertEquals(listOf(listOf("r0c0", "r0c1", "r0c2"), listOf("r1c0", "r1c1", "r1c2"), listOf("r2c0", "r2c1", "r2c2")), t2.table)
        assertEquals(h0, t2.h)
        assertConsistent(back)
    }

    @Test fun insertAndDeleteColumnsKeepTheWidthsSummingToTheFrame() {
        val (d, id, _) = tableDeck()
        assertTrue(d.tableInsertCol(0, id, 1, right = true))
        var saved = d.save()
        var t = tblXml(saved)
        assertEquals(4, gridW(t).size)
        assertEquals(frameCx(saved, id), gridW(t).sum())
        assertEquals(listOf("r0c0", "r0c1", "", "r0c2"), PptxDeck.open(saved).slides[0].shapes.first { it.id == id }.table!![0])
        assertTrue(d.tableInsertCol(0, id, 0, right = false))
        assertTrue(d.tableDeleteCol(0, id, 3))
        saved = d.save()
        t = tblXml(saved)
        assertEquals(4, gridW(t).size)
        assertEquals(frameCx(saved, id), gridW(t).sum())
        assertEquals(listOf("", "r0c0", "r0c1", "r0c2"), PptxDeck.open(saved).slides[0].shapes.first { it.id == id }.table!![0])
        // every row still has one cell per column
        assertTrue(PptxDeck.open(saved).slides[0].shapes.first { it.id == id }.cellsEditable)
        assertConsistent(saved)
    }

    @Test fun theLastRowAndColumnStay() {
        val (d, id, _) = tableDeck(1, 1)
        assertFalse(d.tableDeleteRow(0, id, 0))
        assertFalse(d.tableDeleteCol(0, id, 0))
    }

    @Test fun mergeRightWritesGridSpanAndHMergeAndJoinsTheText() {
        val (d, id, _) = tableDeck()
        assertTrue(d.tableMerge(0, id, 1, 0, down = false))
        val saved = d.save()
        val t = tblXml(saved)
        assertTrue(t.contains("<a:tc gridSpan=\"2\">"))
        assertTrue(t.contains("<a:tc hMerge=\"1\">"))
        val sh = PptxDeck.open(saved).slides[0].shapes.first { it.id == id }
        assertEquals(TableCell(gridSpan = 2), sh.tableCells!![1][0])
        assertEquals(TableCell(hMerge = true), sh.tableCells!![1][1])
        assertEquals("r1c0\nr1c1", sh.table!![1][0])
        assertEquals("", sh.table!![1][1])
        assertTrue(sh.cellsEditable)
        // the covered cell takes no text; the area's first cell does
        val d2 = PptxDeck.open(saved)
        assertTrue(runCatching { d2.setTableCell(0, id, 1, 1, "x") }.isFailure)
        d2.setTableCell(0, id, 1, 0, "joined")
        assertEquals("joined", d2.slides[0].shapes.first { it.id == id }.table!![1][0])
        // merging again takes the third column in
        assertTrue(d2.tableMerge(0, id, 1, 1, down = false))
        assertArrayEquals(intArrayOf(1, 0, 1, 2), d2.tableArea(0, id, 1, 2))
        assertFalse("nothing to the right", d2.tableMerge(0, id, 1, 0, down = false))
        assertConsistent(d2.save())
    }

    @Test fun mergeDownWritesRowSpanAndVMerge() {
        val (d, id, _) = tableDeck()
        assertTrue(d.tableMerge(0, id, 0, 2, down = true))
        val saved = d.save()
        val t = tblXml(saved)
        assertTrue(t.contains("<a:tc rowSpan=\"2\">"))
        assertTrue(t.contains("<a:tc vMerge=\"1\">"))
        val sh = PptxDeck.open(saved).slides[0].shapes.first { it.id == id }
        assertEquals(TableCell(rowSpan = 2), sh.tableCells!![0][2])
        assertEquals(TableCell(vMerge = true), sh.tableCells!![1][2])
        assertEquals("r0c2\nr1c2", sh.table!![0][2])
        // a block 2 × 2: merge right, then down
        val d2 = PptxDeck.open(fixture("pptx-deck.pptx"))
        val id2 = d2.addTable(0, 3, 3, 1_000_000, 1_000_000, 6_000_000, 1_500_000)
        assertTrue(d2.tableMerge(0, id2, 0, 0, down = false))
        assertFalse("the row below covers one column, not two", d2.tableMerge(0, id2, 0, 0, down = true))
        assertTrue(d2.tableMerge(0, id2, 1, 0, down = false))
        assertTrue(d2.tableMerge(0, id2, 0, 0, down = true))
        val cells = d2.slides[0].shapes.first { it.id == id2 }.tableCells!!
        assertEquals(TableCell(gridSpan = 2, rowSpan = 2), cells[0][0])
        assertEquals(TableCell(hMerge = true, vMerge = true), cells[1][1])
        assertEquals(TableCell(vMerge = true), cells[1][0])
        assertConsistent(d2.save())
    }

    @Test fun mergeOnlyWithAnAreaOfTheSameHeight() {
        val (d, id, _) = tableDeck()
        assertTrue(d.tableMerge(0, id, 0, 1, down = true)) // (0,1)-(1,1)
        assertFalse(d.tableMerge(0, id, 0, 0, down = false)) // (0,0) is one row, its right neighbour two
        assertTrue(d.tableMerge(0, id, 0, 0, down = true))
        assertTrue(d.tableMerge(0, id, 0, 0, down = false))
        assertArrayEquals(intArrayOf(0, 0, 1, 1), d.tableArea(0, id, 1, 1))
    }

    @Test fun rowsInsertedInsideAMergedAreaJoinItAndDeletingItsFirstRowMovesIt() {
        val (d, id, _) = tableDeck()
        assertTrue(d.tableMerge(0, id, 0, 0, down = true))
        assertTrue(d.tableInsertRow(0, id, 0, below = true)) // between the two merged rows
        assertArrayEquals(intArrayOf(0, 0, 2, 0), d.tableArea(0, id, 1, 0))
        assertEquals(TableCell(rowSpan = 3), d.slides[0].shapes.first { it.id == id }.tableCells!![0][0])
        assertTrue(d.tableDeleteRow(0, id, 0))
        val sh = d.slides[0].shapes.first { it.id == id }
        assertEquals(TableCell(rowSpan = 2), sh.tableCells!![0][0])
        assertEquals("r0c0\nr1c0", sh.table!![0][0])
        assertTrue(d.tableInsertCol(0, id, 0, right = true))
        assertEquals(TableCell(rowSpan = 2), d.slides[0].shapes.first { it.id == id }.tableCells!![0][0])
        val saved = d.save()
        assertConsistent(saved)
        writeRoundTrip("m3-slide-table.pptx", saved)
    }

    @Test fun untouchedTableCellsKeepTheirText() {
        val (d, id, before) = tableDeck()
        val oldT = tblXml(before)
        val cell = Regex("<a:tc>.*?</a:tc>", RegexOption.DOT_MATCHES_ALL).findAll(oldT).map { it.value }.toList()
        assertTrue(d.tableInsertRow(0, id, 2, below = true))
        val newT = tblXml(d.save())
        for (c in cell) assertTrue("cell kept verbatim", newT.contains(c))
        assertNotNull(newT)
    }
}
