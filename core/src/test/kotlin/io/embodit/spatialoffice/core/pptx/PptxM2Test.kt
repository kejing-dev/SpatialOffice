package io.embodit.spatialoffice.core.pptx

import io.embodit.spatialoffice.core.docx.PackageAsserts.assertConsistent
import io.embodit.spatialoffice.core.docx.PackageAsserts.changedParts
import io.embodit.spatialoffice.core.docx.PackageAsserts.fixture
import io.embodit.spatialoffice.core.docx.PackageAsserts.text
import io.embodit.spatialoffice.core.pkg.OoxmlPackage
import io.embodit.spatialoffice.core.text.RichText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** v0.4.0 M2: p17 run formatting of selected characters, p18 paragraph alignment / bullets, p22 fill and line, p24 rotation. */
class PptxM2Test {
    private val part = "ppt/slides/slide5.xml"

    private val r1 = "<a:r><a:rPr lang=\"en-US\" sz=\"2000\"><a:latin typeface=\"Georgia\"/></a:rPr><a:t>Hello </a:t></a:r>"
    private val r2 = "<a:r><a:rPr lang=\"en-US\" sz=\"2000\" i=\"1\"/><a:t>brave new</a:t></a:r>"
    private val r3 = "<a:r><a:rPr lang=\"en-US\" sz=\"2000\"><a:solidFill><a:srgbClr val=\"C00000\"/></a:solidFill></a:rPr><a:t> world</a:t></a:r>"
    private val p2 = "<a:p><a:r><a:rPr lang=\"en-US\" b=\"1\"/><a:t>Second</a:t></a:r></a:p>"

    /** The fixture with a text box on slide 5 whose first paragraph has three runs of different formats; returns the bytes and the box id. */
    private fun mixedDeck(): Pair<ByteArray, Int> {
        val d = PptxDeck.open(fixture("pptx-deck.pptx"))
        val id = d.addTextBox(4, 1_000_000, 1_000_000, 6_000_000, 1_500_000, "PLACEHOLDER_M2")
        val pkg = OoxmlPackage.read(d.save())
        val x = pkg.text(part)!!
        val old = "<a:p><a:r><a:rPr lang=\"en-US\"/><a:t>PLACEHOLDER_M2</a:t></a:r></a:p>"
        assertTrue(x.contains(old))
        pkg.putText(part, x.replace(old, "<a:p>$r1$r2$r3</a:p>$p2"))
        return pkg.write() to id
    }

    private fun box(d: PptxDeck, id: Int) = d.slides[4].shapes.first { it.id == id }

    @Test fun readsRunsWithEffectiveFormatAndBodyWideSourceIndexes() {
        val (bytes, id) = mixedDeck()
        val sh = box(PptxDeck.open(bytes), id)
        val runs = sh.paras[0].runs!!
        assertEquals(listOf("Hello ", "brave new", " world"), runs.map { it.text })
        assertEquals(listOf(0, 1, 2), runs.map { it.src })
        assertEquals(listOf(40, 40, 40), runs.map { it.size })
        assertEquals(listOf(false, true, false), runs.map { it.italic })
        assertEquals(0xC00000, runs[2].color)
        assertEquals(0, sh.paras[0].align)
        val second = sh.paras[1].runs!!
        assertEquals(3, second.single().src)
        assertTrue(second.single().bold)
        assertNull(second.single().highlight)
    }

    @Test fun formattingAFewCharactersChangesOnlyTheirRun() {
        val (bytes, id) = mixedDeck()
        val d = PptxDeck.open(bytes)
        val paras = box(d, id).paras
        // "brave" = characters 6..11 of the first paragraph
        var runs = paras[0].runs!!
        runs = RichText.setAttr(runs, 6, 11, io.embodit.spatialoffice.core.text.Attr.BOLD, true)
        runs = RichText.setColor(runs, 6, 11, 0x0070C0)
        runs = RichText.setSize(runs, 6, 11, 48)
        d.setShapeText(4, id, listOf(paras[0].copy(runs = runs, align = 1, bullet = true), paras[1]))
        val out = d.save()
        assertEquals(setOf(part), changedParts(bytes, out))
        val x = text(out, part)
        val brave = "<a:r><a:rPr lang=\"en-US\" i=\"1\" b=\"1\" sz=\"2400\"><a:solidFill><a:srgbClr val=\"0070C0\"/></a:solidFill></a:rPr><a:t>brave</a:t></a:r>"
        val rest = "<a:r><a:rPr lang=\"en-US\" sz=\"2000\" i=\"1\"/><a:t> new</a:t></a:r>"
        assertTrue(x, x.contains("$r1$brave$rest$r3"))
        assertTrue(x, x.contains("$r3</a:p>$p2"))
        assertTrue(x, Regex("<a:pPr[^>]*algn=\"ctr\"[^>]*>(?:(?!</a:pPr>).)*<a:buChar ").containsMatchIn(x))

        val re = box(PptxDeck.open(out), id)
        val rr = re.paras[0].runs!!
        assertEquals(listOf("Hello ", "brave", " new", " world"), rr.map { it.text })
        assertEquals(listOf(false, true, false, false), rr.map { it.bold })
        assertEquals(listOf(40, 48, 40, 40), rr.map { it.size })
        assertEquals(0x0070C0, rr[1].color)
        assertEquals(1, re.paras[0].align)
        assertTrue(re.paras[0].bullet)
        // the second paragraph is the same; only its run's index moved (the first paragraph now has four runs)
        assertEquals(paras[1].runs!!.map { it.copy(src = 4) }, re.paras[1].runs)
        assertEquals(paras[1].plain(), re.paras[1].plain())
        assertConsistent(out)
    }

    @Test fun typingIntoOneRunOfAMixedBoxKeepsTheOtherRunsFormats() {
        val (bytes, id) = mixedDeck()
        val d = PptxDeck.open(bytes)
        val paras = box(d, id).paras
        val runs = RichText.applyEdit(paras[0].runs!!, "Hello braveX new world")
        d.setShapeText(4, id, listOf(paras[0].copy(text = "Hello braveX new world", runs = runs), paras[1]))
        val out = d.save()
        val x = text(out, part)
        assertTrue(x, x.contains(r1 + "<a:r><a:rPr lang=\"en-US\" sz=\"2000\" i=\"1\"/><a:t>braveX new</a:t></a:r>" + r3))
        // only text changed (runs stale): the edit is applied to the runs, so the formats still follow
        val d2 = PptxDeck.open(bytes)
        d2.setShapeText(4, id, listOf(paras[0].copy(text = "Hello brave new world!"), paras[1]))
        assertTrue(text(d2.save(), part).contains(r1 + r2 + "<a:r><a:rPr lang=\"en-US\" sz=\"2000\"><a:solidFill><a:srgbClr val=\"C00000\"/></a:solidFill></a:rPr><a:t> world!</a:t></a:r>"))
    }

    @Test fun clearingSizeAndColourRemovesOnlyTheRunsOwn() {
        val (bytes, id) = mixedDeck()
        val d = PptxDeck.open(bytes)
        val paras = box(d, id).paras
        val runs = RichText.clearFormat(paras[0].runs!!, 0, RichText.text(paras[0].runs!!).length)
        d.setShapeText(4, id, listOf(paras[0].copy(runs = runs), paras[1]))
        val x = text(d.save(), part)
        assertTrue(x, x.contains("<a:r><a:rPr lang=\"en-US\"><a:latin typeface=\"Georgia\"/></a:rPr><a:t>Hello </a:t></a:r><a:r><a:rPr lang=\"en-US\"/><a:t>brave new</a:t></a:r><a:r><a:rPr lang=\"en-US\"/><a:t> world</a:t></a:r>"))
    }

    @Test fun unchangedRunsKeepTheBoxByteIdentical() {
        val (bytes, id) = mixedDeck()
        val d = PptxDeck.open(bytes)
        d.setShapeText(4, id, box(d, id).paras)
        assertEquals(emptySet<String>(), changedParts(bytes, d.save()))
    }

    @Test fun fillLineAndRotationRoundTrip() {
        val src = fixture("pptx-deck.pptx")
        val d = PptxDeck.open(src)
        val id = d.addShape(0, "rect", 1_000_000, 1_000_000, 2_000_000, 1_000_000, 0x3366CC)
        val base = d.save()
        d.setShapeFill(0, id, 0xFF0000)
        d.setShapeLine(0, id, 0x00B050, 28575)
        d.setShapeRotation(0, id, 90)
        val out = d.save()
        assertEquals(setOf("ppt/slides/slide1.xml"), changedParts(base, out))
        val x = text(out, "ppt/slides/slide1.xml")
        assertTrue(x, x.contains("<a:solidFill><a:srgbClr val=\"FF0000\"/></a:solidFill><a:ln w=\"28575\"><a:solidFill><a:srgbClr val=\"00B050\"/></a:solidFill></a:ln>"))
        assertTrue(x, x.contains("rot=\"5400000\""))
        val sh = PptxDeck.open(out).slides[0].shapes.first { it.id == id }
        assertEquals(0xFF0000, sh.fillRgb)
        assertEquals(0x00B050, sh.lineRgb)
        assertEquals(28575L, sh.lineWidthEmu)
        assertEquals(90, sh.rotation)
        assertEquals(listOf(1_000_000L, 1_000_000L, 2_000_000L, 1_000_000L), listOf(sh.x, sh.y, sh.w, sh.h))
        assertConsistent(out)

        val d2 = PptxDeck.open(out)
        d2.setShapeFill(0, id, null)
        d2.setShapeLine(0, id, null, null)
        d2.setShapeRotation(0, id, -90)
        val sh2 = d2.slides[0].shapes.first { it.id == id }
        assertNull(sh2.fillRgb); assertNull(sh2.lineRgb); assertEquals(28575L, sh2.lineWidthEmu); assertEquals(270, sh2.rotation)
        val x2 = text(d2.save(), "ppt/slides/slide1.xml")
        assertTrue(x2, x2.contains("<a:noFill/><a:ln w=\"28575\"><a:noFill/></a:ln>"))
        d2.setShapeRotation(0, id, 0)
        assertFalse(text(d2.save(), "ppt/slides/slide1.xml").contains("rot=\""))
    }

    @Test fun rotatingAPlaceholderThatInheritsItsBoxGivesItOne() {
        val src = fixture("pptx-deck.pptx")
        val d = PptxDeck.open(src)
        val before = d.slides[1].shapes.first { it.id == 3 }
        d.setShapeRotation(1, 3, 45)
        val out = d.save()
        assertEquals(setOf("ppt/slides/slide2.xml"), changedParts(src, out))
        val sh = PptxDeck.open(out).slides[1].shapes.first { it.id == 3 }
        assertEquals(45, sh.rotation)
        assertEquals(listOf(before.x, before.y, before.w, before.h), listOf(sh.x, sh.y, sh.w, sh.h))
        assertEquals(before.paras, sh.paras)
    }
}
