package io.embodit.spatialoffice.platform

import androidx.compose.runtime.snapshots.Snapshot
import io.embodit.spatialoffice.core.docx.DocxDocument
import io.embodit.spatialoffice.core.files.FileKind
import io.embodit.spatialoffice.core.layout.BoxDrag
import io.embodit.spatialoffice.core.layout.Corner
import io.embodit.spatialoffice.core.layout.DragTotal
import io.embodit.spatialoffice.core.layout.UnitBox
import io.embodit.spatialoffice.core.pptx.PptxDeck
import io.embodit.spatialoffice.core.pptx.ShapeKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Base64

/**
 * v0.2.5 (headset 2026-11-17: "现在 PPT 插入的东西移动不了"): the session calls the pointer drag makes, in the order it
 * makes them — press, one call per pointer event with the travel since the press, release. The hand, the controller
 * ray and gaze + pinch all arrive as that one pointer; the debug command `objdrag` replays the same calls.
 */
class MoveResizeTest {
    private val png: ByteArray = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAIAAAABCAYAAAD0In+KAAAAC0lEQVR42mNgQAcAABIAAeRVjecAAAAASUVORK5CYII=")

    private fun deck() = DeckSession(FileRef.Path(File("t.pptx")), PptxDeck.blank().save())

    private fun box(s: DeckSession, id: Int): UnitBox = s.deck.slides[s.current].shapes.first { it.id == id }.let { UnitBox(it.x, it.y, it.w, it.h) }

    /** True when [edit] changes snapshot state that [read] read (= what is drawn from it is redrawn). */
    private fun invalidates(read: () -> Unit, edit: () -> Unit): Boolean {
        val reads = HashSet<Any>()
        val snap = Snapshot.takeMutableSnapshot(readObserver = { reads.add(it) })
        try { snap.enter { read() } } finally { snap.dispose() }
        var hit = false
        val h = Snapshot.registerApplyObserver { changed, _ -> if (changed.any { it in reads }) hit = true }
        try { edit(); Snapshot.sendApplyNotifications() } finally { h.dispose() }
        return hit
    }

    /** Sixty pointer events of 2 px right and 1 px down on the 960 dp canvas at density 2 (1920 px wide). */
    private fun dragSixty(s: DeckSession, id: Int, corner: Corner?): Pair<Long, Long> {
        val pxPerEmu = 1920f / s.deck.widthEmu
        val total = DragTotal()
        assertTrue(s.beginBoxDrag(id))
        repeat(60) {
            total.add(2f, 1f)
            val dx = BoxDrag.units(total.x, pxPerEmu); val dy = BoxDrag.units(total.y, pxPerEmu)
            if (corner == null) s.moveBoxBy(dx, dy) else s.resizeBoxBy(corner, dx, dy)
        }
        return BoxDrag.units(120f, pxPerEmu) to BoxDrag.units(60f, pxPerEmu)
    }

    @Test fun everyInsertedKindIsDraggedToAnotherPlace() {
        val s = deck()
        s.snap = false // v0.6.0 p25: exact travel (snapping has its own test, M4AppTest)
        s.addTextBox("box"); val text = s.selected
        s.addPicture(png, "png", 200, 100); val pic = s.selected
        s.addShape("ellipse", 0x2563EB); val shape = s.selected
        s.addTable(); val table = s.selected
        assertEquals(4, setOf(text, pic, shape, table).size)
        for (id in listOf(text, pic, shape, table)) {
            val start = box(s, id)
            val (dx, dy) = dragSixty(s, id, null)
            // while the pointer is down the object is drawn at the dragged place and the file is not written yet
            assertEquals(UnitBox(start.x + dx, start.y + dy, start.w, start.h), s.dragBox!!.second)
            assertEquals(start, box(s, id))
            assertTrue(s.endBoxDrag())
            assertNull(s.dragBox)
            assertEquals("shape $id moved by the travel since the press", UnitBox(start.x + dx, start.y + dy, start.w, start.h), box(s, id))
            assertEquals("the dragged object is the picked one", id, s.selected)
            assertTrue(s.dirty)
            // one drag is one undo step
            s.undo(); assertEquals(start, box(s, id)); s.redo(); assertEquals(start.x + dx, box(s, id).x)
        }
        // and the moved objects are in the saved file
        val back = PptxDeck.open(s.serialize())
        assertEquals(s.deck.slides[0].shapes.map { listOf(it.x, it.y, it.w, it.h) }, back.slides[0].shapes.map { listOf(it.x, it.y, it.w, it.h) })
    }

    @Test fun everyInsertedKindIsResizedByACorner() {
        val s = deck()
        s.addTextBox("box"); val text = s.selected
        s.addPicture(png, "png", 200, 100); val pic = s.selected
        s.addShape("roundRect", 0x2563EB); val shape = s.selected
        s.addTable(2, 2); val table = s.selected
        for (id in listOf(text, shape, table)) {
            val start = box(s, id)
            val (dx, dy) = dragSixty(s, id, Corner.BOTTOM_RIGHT)
            assertTrue(s.endBoxDrag())
            assertEquals("shape $id: the top-left corner stays, the dragged one follows", UnitBox(start.x, start.y, start.w + dx, start.h + dy), box(s, id))
        }
        // a picture keeps its proportions; the opposite corner stays where it was
        val start = box(s, pic)
        dragSixty(s, pic, Corner.TOP_LEFT)
        assertTrue(s.endBoxDrag())
        val now = box(s, pic)
        assertTrue(now.w < start.w && now.h < start.h)
        assertEquals(start.w.toDouble() / start.h, now.w.toDouble() / now.h, 0.01)
        assertEquals(start.x + start.w, now.x + now.w); assertEquals(start.y + start.h, now.y + now.h)
        assertEquals(ShapeKind.PICTURE, s.deck.slides[0].shapes.first { it.id == pic }.kind)
    }

    @Test fun theDragIsDrawnInTheSameFrameAndAPressWithoutTravelChangesNothing() {
        val s = deck()
        s.snap = false // v0.6.0 p25: a centred shape would stay on the centre line for a 10 000 EMU step
        s.addShape("rect", 0x2563EB); val id = s.selected
        s.selected = -1
        assertTrue("picking on press redraws the frame", invalidates({ s.selected; s.dragBox }) { s.beginBoxDrag(id) })
        assertTrue("every step redraws the object", invalidates({ s.dragBox }) { s.moveBoxBy(10_000, 0) })
        s.cancelBoxDrag()
        val before = s.version
        val start = box(s, id)
        s.dirty = false
        assertTrue(s.beginBoxDrag(id)); s.moveBoxBy(0, 0)
        assertFalse("no travel, nothing written", s.endBoxDrag())
        assertEquals(start, box(s, id)); assertEquals(before, s.version); assertFalse(s.dirty)
        // a drag that is cancelled (pointer lost) leaves the object where it was
        assertTrue(s.beginBoxDrag(id)); s.moveBoxBy(900_000, 900_000); s.cancelBoxDrag()
        assertEquals(start, box(s, id)); assertNull(s.dragBox)
        assertFalse("nothing on this slide has that id", s.beginBoxDrag(9999))
    }

    @Test fun aTableOnASlideTakesTextCellByCell() {
        val s = deck()
        assertTrue(invalidates({ s.selected; s.version }) { s.addTable() })
        val id = s.selected
        assertTrue(id > 0)
        s.setTableCell(0, id, 0, 1, "Plan"); s.setTableCell(0, id, 2, 2, "42")
        val t = s.deck.slides[0].shapes.first { it.id == id }
        assertEquals("Plan", t.table!![0][1]); assertEquals("42", t.table!![2][2])
        assertEquals("42", PptxDeck.open(s.serialize()).slides[0].shapes.first { it.id == id }.table!![2][2])
    }

    @Test fun aDocumentPictureAndTableChangeSize() {
        val s = DocSession(FileRef.Path(File("t.docx")), FileKind.DOC, DocxDocument.blank().save(), File("."))
        val first = s.blocks().first().id
        val pic = s.insertImage(first, png, "png", 400, 200)
        val table = s.insertTable(pic, 2, 3)
        val p0 = s.blocks().first { it.id == pic } as UiBlock.Image
        assertTrue(p0.widthEmu > 0 && p0.heightEmu > 0)
        // a corner of the picture dragged outwards: bigger, same proportions, never wider than the text
        assertTrue(invalidates({ s.picked; s.resizing }) { s.beginResize(pic) })
        assertEquals(pic, s.picked)
        assertTrue(invalidates({ s.resizing }) { s.resizeBy(Corner.BOTTOM_RIGHT, 800_000, 0) })
        assertEquals(p0.widthEmu, (s.blocks().first { it.id == pic } as UiBlock.Image).widthEmu)   // not written until release
        assertTrue(s.endResize())
        val p1 = s.blocks().first { it.id == pic } as UiBlock.Image
        assertEquals(p0.widthEmu + 800_000, p1.widthEmu)
        assertEquals(p0.widthEmu.toDouble() / p0.heightEmu, p1.widthEmu.toDouble() / p1.heightEmu, 0.01)
        assertTrue(s.beginResize(pic)); s.resizeBy(Corner.BOTTOM_RIGHT, 99_000_000, 0); assertTrue(s.endResize())
        assertEquals(DocxDocument.TEXT_WIDTH_EMU, (s.blocks().first { it.id == pic } as UiBlock.Image).widthEmu)
        s.undo(); assertEquals(p1.widthEmu, (s.blocks().first { it.id == pic } as UiBlock.Image).widthEmu)
        // the table: 100 % of the text width, its corner dragged in by 40 %
        assertEquals(100, (s.blocks().first { it.id == table } as UiBlock.Table).widthPct)
        assertTrue(s.beginResize(table)); s.resizeBy(Corner.BOTTOM_RIGHT, -40, 0); assertTrue(s.endResize())
        assertEquals(60, (s.blocks().first { it.id == table } as UiBlock.Table).widthPct)
        assertTrue(s.beginResize(table)); s.resizeBy(Corner.BOTTOM_RIGHT, -99, 0); assertTrue(s.endResize())
        assertEquals("never narrower than a fifth of the text", 20, (s.blocks().first { it.id == table } as UiBlock.Table).widthPct)
        // saved and opened again
        val back = DocxDocument.open(s.serialize())
        assertEquals(20, back.blocks.filterIsInstance<io.embodit.spatialoffice.core.docx.TableBlock>().single().widthPct)
        assertNotEquals(p0.widthEmu, back.blocks.filterIsInstance<io.embodit.spatialoffice.core.docx.ImageBlock>().single().widthEmu)
        // a paragraph is not something to resize
        assertFalse(s.beginResize(first))
    }
}
