package io.embodit.spatialoffice.platform

import io.embodit.spatialoffice.core.pptx.PptxDeck
import io.embodit.spatialoffice.core.pptx.SlideLayoutKind
import io.embodit.spatialoffice.core.pptx.TableCell
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** v0.5.0 M3 slides through the session calls the menus and the debug commands use: p26 background, p40 table rows / columns / merge. */
class M3SlidesTest {
    private fun deck(): DeckSession {
        val s = DeckSession(FileRef.Path(File("t.pptx")), PptxDeck.blank().save())
        s.addSlide(SlideLayoutKind.TITLE_BODY)
        s.addSlide(SlideLayoutKind.BLANK)
        return s
    }

    @Test fun backgroundOnThisSlideOrAllAndBackToTheLayouts() {
        val s = deck()
        s.current = 1
        s.setBackground(0x16A34A, all = false)
        assertEquals(0x16A34A, s.deck.slides[1].backgroundRgb)
        assertTrue(s.deck.slides[1].ownBackground)
        assertFalse(s.deck.slides[0].ownBackground)
        assertTrue(s.dirty)
        s.setBackground(0x111111, all = true)
        assertTrue(s.deck.slides.all { it.ownBackground && it.backgroundRgb == 0x111111 })
        s.setBackground(null, all = true)
        assertTrue(s.deck.slides.none { it.ownBackground })
        // one undo step per pick
        s.undo()
        assertTrue(s.deck.slides.all { it.backgroundRgb == 0x111111 })
    }

    @Test fun pictureBackgroundForAllSlidesIsOnePart() {
        val s = deck()
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 1, 2, 3)
        s.setBackgroundPicture(png, "png", all = true)
        val parts = s.deck.slides.map { it.backgroundImage }
        assertEquals(1, parts.toSet().size)
        assertTrue(parts.first() != null)
        val back = DeckSession(FileRef.Path(File("t.pptx")), s.serialize())
        assertTrue(back.deck.slides.all { it.backgroundImage == parts.first() })
    }

    @Test fun tableToolsWorkOnTheClickedCellAndFollowIt() {
        val s = deck()
        s.addTable(3, 3)
        val id = s.selected
        assertEquals(0 to 0, s.currentCell())
        s.pickCell(id, 1, 1)
        assertEquals(1 to 1, s.currentCell())
        assertTrue(s.tableRow("below"))
        assertEquals(2 to 1, s.currentCell()) // the new row
        assertEquals(4, s.pickedTable()!!.table!!.size)
        assertTrue(s.tableCol("right"))
        assertEquals(2 to 2, s.currentCell())
        assertEquals(4, s.pickedTable()!!.table!![0].size)
        val t = s.pickedTable()!!
        assertEquals(t.w, t.tableColW!!.sum())
        s.pickCell(id, 0, 0)
        val can = s.tableAbilities()
        assertTrue(can[2] && can[3])
        assertTrue(s.tableMerge(down = false))
        assertEquals(TableCell(gridSpan = 2), s.pickedTable()!!.tableCells!![0][0])
        // a click on the covered cell makes the area's first cell current
        s.pickCell(id, 0, 1)
        assertEquals(0 to 0, s.currentCell())
        assertFalse("the cell below covers one column", s.tableAbilities()[3])
        assertTrue(s.tableRow("delete"))
        assertTrue(s.tableCol("delete"))
        assertEquals(3, s.pickedTable()!!.table!!.size)
        assertEquals(3, s.pickedTable()!!.table!![0].size)
    }

    @Test fun noTablePickedNoTableTools() {
        val s = deck()
        s.selected = -1
        assertNull(s.currentCell())
        assertFalse(s.tableRow("below"))
        assertFalse(s.tableMerge(true))
    }
}
