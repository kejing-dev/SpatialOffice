package io.embodit.spatialoffice.core.pptx

import io.embodit.spatialoffice.core.docx.PackageAsserts.assertConsistent
import io.embodit.spatialoffice.core.docx.PackageAsserts.changedParts
import io.embodit.spatialoffice.core.docx.PackageAsserts.fixture
import io.embodit.spatialoffice.core.docx.PackageAsserts.parts
import io.embodit.spatialoffice.core.docx.PackageAsserts.text
import io.embodit.spatialoffice.core.pkg.OoxmlPackage
import io.embodit.spatialoffice.core.pkg.Rels
import io.embodit.spatialoffice.core.pkg.Xml
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** v0.3.0 M1: p19 object copy / paste / duplicate, p20 drawing order. */
class PptxM1Test {
    private val src: ByteArray = fixture("pptx-deck.pptx")

    private fun ids(d: PptxDeck, slide: Int) = d.slides[slide].shapes.map { it.id }

    /** Every `p:cNvPr id` in a slide part (nested ones too). */
    private fun allCnvPrIds(xml: String): List<Int> =
        Regex("<p:cNvPr\\b[^>]*?\\sid=\"(\\d+)\"").findAll(xml).map { it.groupValues[1].toInt() }.toList()

    private fun slideXml(bytes: ByteArray, n: Int) = text(bytes, "ppt/slides/slide$n.xml")

    private fun deckWithShape(): Pair<PptxDeck, Int> {
        val d = PptxDeck.open(src)
        val id = d.addShape(0, "ellipse", 1_000_000, 1_000_000, 2_000_000, 1_000_000, 0x3366CC)
        return d to id
    }

    @Test fun drawnShapeCopiedToAnotherSlideKeepsGeometryWithAFreshId() {
        val (d, id) = deckWithShape()
        val clip = d.copyShape(0, id)
        val before = d.slides[1].shapes.size
        val newId = d.pasteShape(1, clip, 200_000, 100_000)
        val shapes = d.slides[1].shapes
        assertEquals(before + 1, shapes.size)
        val pasted = shapes.last()
        assertEquals("pasted on top", newId, pasted.id)
        assertEquals("ellipse", pasted.geom)
        assertEquals(0x3366CC, pasted.fillRgb)
        assertEquals(2_000_000L, pasted.w); assertEquals(1_000_000L, pasted.h)
        assertEquals(1_200_000L, pasted.x); assertEquals(1_100_000L, pasted.y)
        val bytes = d.save()
        assertConsistent(bytes)
        val all = allCnvPrIds(slideXml(bytes, 2))
        assertEquals("no id used twice on the slide", all.size, all.toSet().size)
        // reopened, same thing
        val r = PptxDeck.open(bytes)
        assertEquals("ellipse", r.slides[1].shapes.last().geom)
        assertEquals(newId, r.slides[1].shapes.last().id)
    }

    @Test fun pictureCopiedAcrossSlidesSharesTheImagePart() {
        val d = PptxDeck.open(src)
        val pic = d.slides[2].shapes.first { it.kind == ShapeKind.PICTURE }
        val clip = d.copyShape(2, pic.id)
        val newId = d.pasteShape(0, clip, 0, 0)
        val pasted = d.slides[0].shapes.first { it.id == newId }
        assertEquals(ShapeKind.PICTURE, pasted.kind)
        assertEquals(pic.imagePart, pasted.imagePart)
        assertArrayEquals(d.partBytes(pic.imagePart!!), d.partBytes(pasted.imagePart!!))
        val bytes = d.save()
        assertConsistent(bytes)
        val pkg = OoxmlPackage.read(bytes)
        val rel = Rels.of(pkg, "ppt/slides/slide1.xml").firstOrNull { it.type.endsWith("/image") }
        assertNotNull("slide 1 got an image relationship", rel)
        assertEquals(pic.imagePart, OoxmlPackage.resolve("ppt/slides/slide1.xml", rel!!.target))
        // the embed attribute names that relationship
        val sx = pkg.text("ppt/slides/slide1.xml")!!
        assertTrue(sx.contains("r:embed=\"${rel.id}\""))
        // nothing new under media
        assertEquals(parts(src).keys.count { it.startsWith("ppt/media/") }, pkg.names().count { it.startsWith("ppt/media/") })
        assertEquals(ShapeKind.PICTURE, PptxDeck.open(bytes).slides[0].shapes.first { it.id == newId }.kind)
    }

    @Test fun pictureDuplicatedOnItsOwnSlideReusesTheRelationship() {
        val d = PptxDeck.open(src)
        val pic = d.slides[2].shapes.first { it.kind == ShapeKind.PICTURE }
        val relsBefore = Rels.of(OoxmlPackage.read(src), "ppt/slides/slide3.xml").size
        val dup = d.duplicateShape(2, pic.id)
        val bytes = d.save()
        assertEquals(relsBefore, Rels.of(OoxmlPackage.read(bytes), "ppt/slides/slide3.xml").size)
        val copy = d.slides[2].shapes.first { it.id == dup }
        assertEquals(pic.imagePart, copy.imagePart)
        assertEquals(pic.w, copy.w)
        assertEquals(pic.x + d.widthEmu / 40, copy.x)
    }

    @Test fun duplicateWithinASlideIsOffsetAndOnTop() {
        val (d, id) = deckWithShape()
        val dup = d.duplicateShape(0, id)
        assertNotEquals(id, dup)
        assertEquals(dup, d.slides[0].shapes.last().id)
        val a = d.slides[0].shapes.first { it.id == id }
        val b = d.slides[0].shapes.first { it.id == dup }
        assertEquals(a.x + d.widthEmu / 40, b.x)
        assertEquals(a.y + d.widthEmu / 40, b.y)
        assertEquals(a.w, b.w)
        val all = allCnvPrIds(slideXml(d.save(), 1))
        assertEquals(all.size, all.toSet().size)
    }

    @Test fun pasteKeepsTheObjectOnTheSlide() {
        val (d, id) = deckWithShape()
        val clip = d.copyShape(0, id)
        val n = d.pasteShape(0, clip, d.widthEmu * 2, d.heightEmu * 2)
        val s = d.slides[0].shapes.first { it.id == n }
        assertEquals(d.widthEmu - s.w, s.x)
        assertEquals(d.heightEmu - s.h, s.y)
    }

    @Test fun placeholderIsPastedAsAPlainShapeWithItsBox() {
        val d = PptxDeck.open(src)
        val title = d.slides[0].shapes.first { it.kind == ShapeKind.TITLE }
        val n = d.pasteShape(1, d.copyShape(0, title.id), 0, 0)
        val s = d.slides[1].shapes.first { it.id == n }
        assertEquals(title.x, s.x); assertEquals(title.y, s.y); assertEquals(title.w, s.w); assertEquals(title.h, s.h)
        assertEquals(title.paras.map { it.text }, s.paras.map { it.text })
        val sx = slideXml(d.save(), 2)
        val tree = Xml.find(sx, Xml.root(sx)!!, "p:spTree")!!
        val el = Xml.children(sx, tree).last()
        assertEquals(null, Xml.find(sx, el, "p:ph"))
        assertNotNull(Xml.find(sx, el, "a:off"))
    }

    @Test fun clipSurvivesEditDeleteSaveAndAReopenedDeck() {
        val (d, id) = deckWithShape()
        val clip = d.copyShape(0, id)
        d.setShapeBox(0, id, 0, 0, 500_000, 500_000)
        d.deleteShape(0, id)
        val bytes = d.save()
        val n = d.pasteShape(3, clip, 0, 0)
        assertEquals(2_000_000L, d.slides[3].shapes.first { it.id == n }.w)
        // after an undo the app reopens the deck from bytes: the clip still pastes there
        val r = PptxDeck.open(bytes)
        val m = r.pasteShape(0, clip, 0, 0)
        assertEquals("ellipse", r.slides[0].shapes.first { it.id == m }.geom)
    }

    @Test fun objectsThatOwnAPartAreNotCopied() {
        val d = PptxDeck.open(src)
        val chart = d.slides[5].shapes.first { it.kind == ShapeKind.CHART }
        try { d.copyShape(5, chart.id); fail("a chart frame owns its chart part") } catch (e: IllegalStateException) { }
        assertFalse(d.dirty)
    }

    @Test fun pasteIntoADeckWithoutTheMediaIsRefused() {
        val d = PptxDeck.open(src)
        val pic = d.slides[2].shapes.first { it.kind == ShapeKind.PICTURE }
        val clip = d.copyShape(2, pic.id)
        val other = PptxDeck.blank()
        try { other.pasteShape(0, clip, 0, 0); fail("blank deck has no image") } catch (e: IllegalArgumentException) { }
    }

    @Test fun reorderMovesThroughTheDrawingOrder() {
        val d = PptxDeck.open(src)
        val a = d.addShape(0, "rect", 0, 0, 1_000_000, 1_000_000, 0xFF0000)
        val b = d.addShape(0, "rect", 500_000, 500_000, 1_000_000, 1_000_000, 0x00FF00)
        val start = ids(d, 0)
        assertEquals(listOf(a, b), start.takeLast(2))
        val first = start.first()

        assertFalse("already in front", d.reorderShape(0, b, ZOrder.FRONT))
        assertFalse("already in front", d.reorderShape(0, b, ZOrder.FORWARD))
        assertTrue(d.reorderShape(0, a, ZOrder.FRONT))
        assertEquals(listOf(b, a), ids(d, 0).takeLast(2))
        assertTrue(d.reorderShape(0, a, ZOrder.BACKWARD))
        assertEquals(listOf(a, b), ids(d, 0).takeLast(2))
        assertTrue(d.reorderShape(0, b, ZOrder.BACK))
        assertEquals(b, ids(d, 0).first())
        assertFalse("already at the back", d.reorderShape(0, b, ZOrder.BACK))
        assertFalse("already at the back", d.reorderShape(0, b, ZOrder.BACKWARD))
        assertTrue(d.reorderShape(0, b, ZOrder.FORWARD))
        assertEquals(listOf(first, b), ids(d, 0).take(2))
        assertEquals(start.toSet(), ids(d, 0).toSet())

        val bytes = d.save()
        assertConsistent(bytes)
        assertEquals(ids(d, 0), ids(PptxDeck.open(bytes), 0))
    }

    @Test fun reorderSplicesTheShapeTextUnchanged() {
        val d = PptxDeck.open(src)
        val before = slideXml(src, 3)
        val pic = d.slides[2].shapes.first { it.kind == ShapeKind.PICTURE }
        assertTrue(d.reorderShape(2, pic.id, ZOrder.FRONT))
        val bytes = d.save()
        val after = slideXml(bytes, 3)
        assertEquals(before.length, after.length)
        fun shapeSrc(xml: String): String {
            val tree = Xml.find(xml, Xml.root(xml)!!, "p:spTree")!!
            val e = Xml.children(xml, tree).first { it.name == "p:pic" }
            return xml.substring(e.start, e.end)
        }
        assertEquals(shapeSrc(before), shapeSrc(after))
        assertEquals(setOf("ppt/slides/slide3.xml"), changedParts(src, bytes))
    }

    @Test fun untouchedPartsStayByteIdentical() {
        val d = PptxDeck.open(src)
        val pic = d.slides[2].shapes.first { it.kind == ShapeKind.PICTURE }
        d.pasteShape(1, d.copyShape(2, pic.id), 100_000, 100_000)
        d.duplicateShape(1, d.slides[1].shapes.first().id)
        d.reorderShape(1, d.slides[1].shapes.last().id, ZOrder.BACK)
        val bytes = d.save()
        assertConsistent(bytes)
        assertEquals(setOf("ppt/slides/slide2.xml", "ppt/slides/_rels/slide2.xml.rels"), changedParts(src, bytes))
    }
}
