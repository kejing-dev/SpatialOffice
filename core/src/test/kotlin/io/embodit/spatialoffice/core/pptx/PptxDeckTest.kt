package io.embodit.spatialoffice.core.pptx

import io.embodit.spatialoffice.core.docx.CompatKind
import io.embodit.spatialoffice.core.docx.CompatNote
import io.embodit.spatialoffice.core.docx.PackageAsserts.assertAllPartsIdentical
import io.embodit.spatialoffice.core.docx.PackageAsserts.assertConsistent
import io.embodit.spatialoffice.core.docx.PackageAsserts.assertOnlyRangeChanged
import io.embodit.spatialoffice.core.docx.PackageAsserts.changedParts
import io.embodit.spatialoffice.core.docx.PackageAsserts.fixture
import io.embodit.spatialoffice.core.docx.PackageAsserts.parts
import io.embodit.spatialoffice.core.docx.PackageAsserts.text
import io.embodit.spatialoffice.core.docx.PackageAsserts.writeRoundTrip
import io.embodit.spatialoffice.core.pkg.OoxmlPackage
import io.embodit.spatialoffice.core.pkg.Rels
import io.embodit.spatialoffice.core.pkg.Xml
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class PptxDeckTest {
    private val pres = "ppt/presentation.xml"
    private val presRels = "ppt/_rels/presentation.xml.rels"
    private val types = "[Content_Types].xml"
    private val src: ByteArray = fixture("pptx-deck.pptx")

    private fun open() = PptxDeck.open(src)

    private fun titles(d: PptxDeck) = d.slides.map { s -> s.shapes.firstOrNull { it.kind == ShapeKind.TITLE }?.paras?.joinToString(" ") { it.text } ?: "" }

    private fun shape(d: PptxDeck, slide: Int, id: Int) = d.slides[slide].shapes.first { it.id == id }

    /** Range of the top-level shape with the given id in a slide part. */
    private fun shapeRange(xml: String, id: Int): Xml.Elem {
        val tree = Xml.find(xml, Xml.root(xml)!!, "p:spTree")!!
        return Xml.children(xml, tree).first { e -> Xml.find(xml, e, "p:cNvPr")?.let { Xml.attr(xml, it, "id") } == id.toString() }
    }

    /** Slide list consistency on top of the package checks: ids unique and in range, each entry resolves to a slide part once. */
    private fun assertDeckConsistent(bytes: ByteArray): List<String> {
        assertConsistent(bytes)
        val pkg = OoxmlPackage.read(bytes)
        val px = pkg.text(pres)!!
        val lst = Xml.child(px, Xml.root(px)!!, "p:sldIdLst")!!
        val rels = Rels.of(pkg, pres).associateBy { it.id }
        val ids = HashSet<Long>()
        val slideParts = ArrayList<String>()
        for (e in Xml.children(px, lst)) {
            val id = Xml.attr(px, e, "id")!!.toLong()
            assertTrue("slide id $id out of range", id in 256 until 2147483648L)
            assertTrue("slide id $id used twice", ids.add(id))
            val rel = rels[Xml.attr(px, e, "r:id")] ?: throw AssertionError("slide list entry without relationship")
            assertTrue(rel.type.endsWith("/slide"))
            val part = OoxmlPackage.resolve(pres, rel.target)
            assertTrue("slide $part listed twice", part !in slideParts)
            slideParts.add(part)
            assertTrue(text(bytes, types).contains("<Override PartName=\"/$part\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.slide+xml\"/>"))
            assertTrue("slide $part has no layout", Rels.of(pkg, part).any { it.type.endsWith("/slideLayout") })
        }
        val slideRels = rels.values.filter { it.type.endsWith("/slide") }.map { OoxmlPackage.resolve(pres, it.target) }
        assertEquals("every slide relationship of the presentation is in the slide list", slideParts.sorted(), slideRels.sorted())
        val onDisk = pkg.names().filter { Regex("ppt/slides/[^/]+\\.xml").matches(it) }
        assertEquals("no slide part is left unlisted", slideParts.sorted(), onDisk.sorted())
        return slideParts
    }

    private fun expectReadOnly(block: () -> Unit) {
        try { block(); fail("expected IllegalStateException") } catch (e: IllegalStateException) { /* expected */ }
    }

    // ---- open / untouched save

    @Test fun untouchedSaveKeepsEveryPartByteIdentical() {
        val d = open()
        assertFalse(d.dirty)
        d.slides; d.compat(); d.firstTitle()
        val out = d.save()
        assertAllPartsIdentical(src, out)
        assertArrayEquals(src, out)
        assertDeckConsistent(out)
        writeRoundTrip("pptx-untouched.pptx", out)
    }

    @Test fun readsSlidesShapesAndInheritedGeometry() {
        val d = open()
        assertEquals(12192000L, d.widthEmu); assertEquals(6858000L, d.heightEmu)
        val s = d.slides
        assertEquals(7, s.size)
        assertEquals((0..6).toList(), s.map { it.index })
        assertEquals(listOf("Product Review 产品评审", "Goals", "Prototype", "Timeline", "Budget", "Architecture", "Thank you"), titles(d))
        assertEquals("Product Review 产品评审", d.firstTitle())

        // title slide: both placeholders take their box and font size from the layout / master
        val title = s[0].shapes[0]
        assertEquals(ShapeKind.TITLE, title.kind); assertTrue(title.editable)
        assertEquals(listOf(685800L, 2130425L, 7772400L, 1470025L), listOf(title.x, title.y, title.w, title.h))
        assertEquals(44f, title.fontPt); assertEquals(1, title.align); assertEquals(0x000000, title.colorRgb)
        val sub = s[0].shapes[1]
        assertEquals(ShapeKind.BODY, sub.kind)
        assertEquals(listOf(TextPara("Spring planning & <draft> agenda", 0, false)), sub.paras.map { it.plain() })
        assertEquals(0xFFFFFF, s[0].backgroundRgb)

        // multi-level bullets
        val body = s[1].shapes[1]
        assertEquals(ShapeKind.BODY, body.kind)
        assertEquals(
            listOf(TextPara("Grow the active user base", 0, true), TextPara("Improve onboarding", 1, true), TextPara("Shorten the first-run flow", 2, true), TextPara("降低流失率", 1, true), TextPara("Keep costs flat", 0, true)),
            body.paras.map { it.plain() },
        )
        assertEquals(listOf(457200L, 1600200L, 8229600L, 4525963L), listOf(body.x, body.y, body.w, body.h))
        assertEquals(32f, body.fontPt); assertEquals(0, body.align)

        // picture and free text box
        val pic = s[2].shapes[1]
        assertEquals(ShapeKind.PICTURE, pic.kind); assertFalse(pic.editable)
        assertEquals("ppt/media/image1.png", pic.imagePart)
        assertEquals(0x89.toByte(), d.partBytes(pic.imagePart!!)!![0])
        assertEquals(listOf(914400L, 1645920L, 3657600L, 2057400L), listOf(pic.x, pic.y, pic.w, pic.h))
        val box = s[2].shapes[2]
        assertEquals(ShapeKind.TEXT, box.kind); assertTrue(box.editable); assertEquals(18f, box.fontPt)
        assertEquals(listOf(TextPara("Photo of the current build")), box.paras.map { it.plain() })

        assertEquals("Mention the dependency on the hardware team.", s[3].notes)
        assertEquals("", s[0].notes)

        // table, group → OTHER with their box; chart → CHART; none editable
        val table = s[4].shapes[1]
        assertEquals(ShapeKind.OTHER, table.kind); assertFalse(table.editable)
        assertEquals(listOf(914400L, 1828800L, 7315200L, 1828800L), listOf(table.x, table.y, table.w, table.h))
        val group = s[5].shapes[1]
        assertEquals(ShapeKind.OTHER, group.kind); assertFalse(group.editable); assertEquals(4114800L, group.w)
        val chart = s[5].shapes[2]
        assertEquals(ShapeKind.CHART, chart.kind); assertFalse(chart.editable); assertNull(chart.fontPt)

        // explicit run formatting and a solid slide background
        val styled = s[6].shapes[1]
        assertEquals(32f, styled.fontPt); assertTrue(styled.bold); assertEquals(0x1F4E79, styled.colorRgb); assertEquals(1, styled.align)
        assertEquals(0xF4F6F7, s[6].backgroundRgb)
        assertNull(d.partBytes("ppt/media/none.png"))
    }

    @Test fun compatListsReadOnlyContent() {
        val notes = open().compat()
        // v0.2.5: a plain table is typed into, moved and resized, so it is no longer listed as read-only
        assertFalse(notes.any { it.code == "table" })
        assertTrue(notes.contains(CompatNote(CompatKind.READ_ONLY, "group", 1)))
        assertTrue(notes.contains(CompatNote(CompatKind.READ_ONLY, "chart", 1)))
    }

    @Test fun readOnlyShapesRefuseEdits() {
        val d = open()
        expectReadOnly { d.setShapeText(2, 3, listOf(TextPara("x"))) }   // picture
        expectReadOnly { d.setShapeText(4, 3, listOf(TextPara("x"))) }   // table
        expectReadOnly { d.setShapeText(5, 3, listOf(TextPara("x"))) }   // group
        expectReadOnly { d.setShapeText(5, 6, listOf(TextPara("x"))) }   // chart
        try { d.setShapeText(0, 99, listOf(TextPara("x"))); fail() } catch (e: IllegalArgumentException) { /* no such shape */ }
        try { d.setShapeText(7, 2, listOf(TextPara("x"))); fail() } catch (e: IndexOutOfBoundsException) { /* no such slide */ }
        assertFalse(d.dirty)
        assertAllPartsIdentical(src, d.save())
    }

    // ---- patch-only text edits

    @Test fun editingOneShapeChangesOnlyItsRangeInItsSlide() {
        val d = open()
        val part = "ppt/slides/slide2.xml"
        val before = text(src, part)
        val paras = d.slides[1].shapes[1].paras.toMutableList()
        paras[1] = paras[1].copy(text = "改进新手引导 & <onboarding> \"flow\"")
        d.setShapeText(1, 3, paras)
        assertTrue(d.dirty)
        val out = d.save()

        assertEquals("only the slide that owns the shape may differ", setOf(part), changedParts(src, out))
        val el = shapeRange(before, 3)
        val replaced = assertOnlyRangeChanged(before, text(out, part), el.start, el.end)
        // inside the shape, everything but the one paragraph is what it was: bodyPr, lstStyle and the other four paragraphs
        val original = before.substring(el.start, el.end)
        assertEquals(original.replace("<a:t>Improve onboarding</a:t>", "<a:t>改进新手引导 &amp; &lt;onboarding&gt; &quot;flow&quot;</a:t>"), replaced)

        val re = PptxDeck.open(out)
        assertEquals(paras.map { it.plain() }, re.slides[1].shapes[1].paras.map { it.plain() })
        assertEquals(open().slides.filter { it.index != 1 }, re.slides.filter { it.index != 1 })
        assertEquals(open().slides[1].shapes[0], re.slides[1].shapes[0])
    }

    @Test fun setShapeTextKeepsParagraphAndRunProperties() {
        val d = open()
        val part = "ppt/slides/slide7.xml"
        d.setShapeText(6, 3, listOf(TextPara("欢迎提问", 0, true), TextPara("Second line", 1, true), TextPara("No bullet here", 0, false)))
        val out = d.save()
        assertEquals(setOf(part), changedParts(src, out))
        val x = text(out, part)
        val rPr = "<a:rPr sz=\"3200\" b=\"1\"><a:solidFill><a:srgbClr val=\"1F4E79\"/></a:solidFill></a:rPr>"
        assertTrue(x, x.contains("<a:bodyPr/><a:lstStyle/><a:p><a:pPr algn=\"ctr\"/><a:r>$rPr<a:t>欢迎提问</a:t></a:r></a:p>" +
            "<a:p><a:pPr algn=\"ctr\" lvl=\"1\"/><a:r>$rPr<a:t>Second line</a:t></a:r></a:p>" +
            "<a:p><a:pPr algn=\"ctr\" marL=\"0\" indent=\"0\"><a:buNone/></a:pPr><a:r>$rPr<a:t>No bullet here</a:t></a:r></a:p></p:txBody>"))
        val s = PptxDeck.open(out).slides[6].shapes[1]
        assertEquals(listOf(TextPara("欢迎提问", 0, true), TextPara("Second line", 1, true), TextPara("No bullet here", 0, false)), s.paras.map { it.plain() })
        assertEquals(32f, s.fontPt); assertTrue(s.bold); assertEquals(0x1F4E79, s.colorRgb); assertEquals(1, s.align)
    }

    @Test fun levelsBulletsLineBreaksAndEmptyText() {
        val d = open()
        // title: a line break inside the paragraph; body: fewer paragraphs, a bullet turned on in a text box
        d.setShapeText(1, 2, listOf(TextPara("Goals\nfor spring")))
        d.setShapeText(1, 3, listOf(TextPara("Only one point left", 2, true)))
        d.setShapeText(2, 4, listOf(TextPara("Caption", 0, true), TextPara("")))
        val out = d.save()
        assertEquals(setOf("ppt/slides/slide2.xml", "ppt/slides/slide3.xml"), changedParts(src, out))
        assertTrue(text(out, "ppt/slides/slide2.xml").contains("<a:p><a:r><a:t>Goals</a:t></a:r><a:br/><a:r><a:t>for spring</a:t></a:r></a:p>"))
        val re = PptxDeck.open(out)
        assertEquals(listOf(TextPara("Goals\nfor spring")), re.slides[1].shapes[0].paras.map { it.plain() })
        assertEquals(listOf(TextPara("Only one point left", 2, true)), re.slides[1].shapes[1].paras.map { it.plain() })
        assertEquals(listOf(TextPara("Caption", 0, true), TextPara("", 0, false)), re.slides[2].shapes[2].paras.map { it.plain() })
        assertEquals("Goals\nfor spring", titles(re)[1])
        // clearing a shape leaves one empty paragraph, which the format requires
        val d2 = open()
        d2.setShapeText(0, 3, emptyList())
        val x = text(d2.save(), "ppt/slides/slide1.xml")
        assertTrue(x.contains("<p:ph type=\"subTitle\" idx=\"1\"/></p:nvPr></p:nvSpPr><p:spPr/><p:txBody><a:bodyPr/><a:lstStyle/><a:p></a:p></p:txBody>"))
    }

    @Test fun nonAsciiAndSpecialCharactersRoundTrip() {
        val d = open()
        val t = "标题：性能 & 稳定性 <2026> \"引号\" 'apostrophe' — 😀"
        d.setShapeText(0, 2, listOf(TextPara(t)))
        val out = d.save()
        assertEquals(t, PptxDeck.open(out).firstTitle())
        assertTrue(text(out, "ppt/slides/slide1.xml").contains("性能 &amp; 稳定性 &lt;2026&gt; &quot;引号&quot;"))
        val d2 = open()
        d2.setShapeText(0, 2, listOf(TextPara("a\u0001b")))
        assertEquals("ab", PptxDeck.open(d2.save()).firstTitle())
    }

    // ---- notes

    @Test fun editingExistingNotesChangesOnlyTheNotesPart() {
        val d = open()
        val part = "ppt/notesSlides/notesSlide1.xml"
        val before = text(src, part)
        d.setNotes(3, "先讲硬件依赖。\nThen show the <timeline> & risks.")
        val out = d.save()
        assertEquals(setOf(part), changedParts(src, out))
        val el = shapeRange(before, 3)
        val replaced = assertOnlyRangeChanged(before, text(out, part), el.start, el.end)
        assertTrue(replaced.contains("<p:ph type=\"body\" idx=\"3\" sz=\"quarter\"/>"))
        assertTrue(replaced.endsWith("<a:p><a:r><a:t>先讲硬件依赖。</a:t></a:r></a:p><a:p><a:r><a:t>Then show the &lt;timeline&gt; &amp; risks.</a:t></a:r></a:p></p:txBody></p:sp>"))
        assertEquals("先讲硬件依赖。\nThen show the <timeline> & risks.", PptxDeck.open(out).slides[3].notes)
    }

    @Test fun settingNotesCreatesANotesSlideWhenThereIsNone() {
        val d = open()
        d.setNotes(0, "Welcome everyone.")
        d.setNotes(1, "")                       // nothing to create for empty notes
        val out = d.save()
        val newPart = "ppt/notesSlides/notesSlide2.xml"
        assertEquals(
            setOf(types, newPart, "ppt/notesSlides/_rels/notesSlide2.xml.rels", "ppt/slides/_rels/slide1.xml.rels"),
            changedParts(src, out),
        )
        assertDeckConsistent(out)
        val pkg = OoxmlPackage.read(out)
        assertEquals(listOf("ppt/notesMasters/notesMaster1.xml", "ppt/slides/slide1.xml"), Rels.of(pkg, newPart).map { OoxmlPackage.resolve(newPart, it.target) })
        assertTrue(Rels.of(pkg, "ppt/slides/slide1.xml").any { it.type.endsWith("/notesSlide") && OoxmlPackage.resolve("ppt/slides/slide1.xml", it.target) == newPart })
        assertTrue(text(out, types).contains("<Override PartName=\"/$newPart\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.notesSlide+xml\"/>"))
        val re = PptxDeck.open(out)
        assertEquals("Welcome everyone.", re.slides[0].notes)
        assertEquals("", re.slides[1].notes)
        assertEquals("Mention the dependency on the hardware team.", re.slides[3].notes)
    }

    // ---- slide structure

    @Test fun addSlideKeepsThePackageConsistent() {
        val d = open()
        assertEquals(0, d.addSlide(-1, SlideLayoutKind.TITLE))
        assertEquals(8, d.addSlide(7, SlideLayoutKind.TITLE_BODY))
        assertEquals(4, d.addSlide(3, SlideLayoutKind.TITLE_BODY))
        assertEquals(10, d.slides.size)
        d.setShapeText(0, 2, listOf(TextPara("Cover")))
        d.setShapeText(4, 2, listOf(TextPara("Inserted")))
        d.setShapeText(4, 3, listOf(TextPara("First point", 0, true), TextPara("Detail", 1, true)))
        val out = d.save()

        assertEquals(
            setOf(types, pres, presRels) + (8..10).flatMap { listOf("ppt/slides/slide$it.xml", "ppt/slides/_rels/slide$it.xml.rels") },
            changedParts(src, out),
        )
        val order = assertDeckConsistent(out)
        assertEquals(listOf(8, 1, 2, 3, 10, 4, 5, 6, 7, 9).map { "ppt/slides/slide$it.xml" }, order)
        // presentation part: nothing but the three new entries in the slide list
        val before = text(src, pres)
        val lst = Xml.child(before, Xml.root(before)!!, "p:sldIdLst")!!
        val after = text(out, pres)
        val newList = assertOnlyRangeChanged(before, after, lst.start, lst.end)
        assertEquals(3, Regex("<p:sldId ").findAll(newList).count() - Regex("<p:sldId ").findAll(before).count())
        for (old in Xml.children(before, lst)) assertTrue(newList.contains(before.substring(old.start, old.end)))

        val re = PptxDeck.open(out)
        assertEquals(listOf("Cover", "Product Review 产品评审", "Goals", "Prototype", "Inserted", "Timeline", "Budget", "Architecture", "Thank you", ""), titles(re))
        val cover = re.slides[0].shapes
        assertEquals(listOf(ShapeKind.TITLE, ShapeKind.BODY), cover.map { it.kind })
        assertEquals("the new title slide takes its geometry from the title layout", listOf(685800L, 2130425L, 7772400L, 1470025L), listOf(cover[0].x, cover[0].y, cover[0].w, cover[0].h))
        val inserted = re.slides[4].shapes
        assertEquals(listOf(TextPara("First point", 0, true), TextPara("Detail", 1, true)), inserted[1].paras.map { it.plain() })
        assertEquals(listOf(457200L, 1600200L, 8229600L, 4525963L), listOf(inserted[1].x, inserted[1].y, inserted[1].w, inserted[1].h))
        assertTrue(re.slides.all { s -> s.shapes.filter { it.kind == ShapeKind.TITLE || it.kind == ShapeKind.BODY }.all { it.editable } })
        // the original slides are untouched parts
        for (n in 1..7) assertArrayEquals(parts(src)["ppt/slides/slide$n.xml"], parts(out)["ppt/slides/slide$n.xml"])
    }

    @Test fun deleteSlideRemovesItsPartsAndNothingElse() {
        val d = open()
        d.deleteSlide(3)                         // the slide with speaker notes
        val out = d.save()
        assertEquals(
            setOf(types, pres, presRels, "ppt/slides/slide4.xml", "ppt/slides/_rels/slide4.xml.rels", "ppt/notesSlides/notesSlide1.xml", "ppt/notesSlides/_rels/notesSlide1.xml.rels"),
            changedParts(src, out),
        )
        val order = assertDeckConsistent(out)
        assertEquals(listOf(1, 2, 3, 5, 6, 7).map { "ppt/slides/slide$it.xml" }, order)
        val names = parts(out).keys
        assertFalse("ppt/slides/slide4.xml" in names); assertFalse("ppt/notesSlides/notesSlide1.xml" in names)
        assertTrue("the notes master stays: the presentation still refers to it", "ppt/notesMasters/notesMaster1.xml" in names)
        assertFalse(text(out, types).contains("slide4.xml")); assertFalse(text(out, types).contains("notesSlide1.xml"))
        assertFalse(text(out, presRels).contains("slides/slide4.xml"))
        assertEquals(listOf("Product Review 产品评审", "Goals", "Prototype", "Budget", "Architecture", "Thank you"), titles(PptxDeck.open(out)))

        // a slide whose picture and chart nothing else uses takes them along; shared layouts stay
        val d2 = open()
        d2.deleteSlide(5)                        // group + chart
        d2.deleteSlide(2)                        // picture
        val out2 = d2.save()
        assertDeckConsistent(out2)
        val names2 = parts(out2).keys
        for (gone in listOf("ppt/slides/slide6.xml", "ppt/charts/chart1.xml", "ppt/charts/_rels/chart1.xml.rels", "ppt/embeddings/workbook1.xlsx", "ppt/slides/slide3.xml", "ppt/media/image1.png")) {
            assertFalse("$gone should be gone", gone in names2)
        }
        assertEquals(parts(src).keys.count { it.startsWith("ppt/slideLayouts/") }, names2.count { it.startsWith("ppt/slideLayouts/") })
        assertEquals(listOf("Product Review 产品评审", "Goals", "Timeline", "Budget", "Thank you"), titles(PptxDeck.open(out2)))
    }

    @Test fun deletingEverySlideLeavesAValidEmptyDeck() {
        val d = open()
        repeat(7) { d.deleteSlide(0) }
        val out = d.save()
        assertEquals(emptyList<String>(), assertDeckConsistent(out))
        val re = PptxDeck.open(out)
        assertTrue(re.slides.isEmpty()); assertEquals("", re.firstTitle())
        assertEquals(0, re.addSlide(-1, SlideLayoutKind.TITLE))
        assertEquals(listOf("ppt/slides/slide1.xml"), assertDeckConsistent(re.save()))
    }

    @Test fun moveSlideRewritesOnlyTheSlideList() {
        val d = open()
        d.moveSlide(6, 0)                        // last → first
        d.moveSlide(2, 5)                        // "Goals" (now at 2) → index 5
        d.moveSlide(3, 3)                        // no-op
        assertEquals(listOf("Thank you", "Product Review 产品评审", "Prototype", "Timeline", "Budget", "Goals", "Architecture"), titles(d))
        val out = d.save()
        assertEquals(setOf(pres), changedParts(src, out))
        assertDeckConsistent(out)
        val before = text(src, pres)
        val lst = Xml.child(before, Xml.root(before)!!, "p:sldIdLst")!!
        val newList = assertOnlyRangeChanged(before, text(out, pres), lst.start, lst.end)
        assertEquals(
            "<p:sldIdLst><p:sldId id=\"262\" r:id=\"rId14\"/><p:sldId id=\"256\" r:id=\"rId7\"/><p:sldId id=\"258\" r:id=\"rId9\"/><p:sldId id=\"259\" r:id=\"rId10\"/>" +
                "<p:sldId id=\"260\" r:id=\"rId12\"/><p:sldId id=\"257\" r:id=\"rId8\"/><p:sldId id=\"261\" r:id=\"rId13\"/></p:sldIdLst>",
            newList,
        )
        val re = PptxDeck.open(out)
        assertEquals(titles(d), titles(re))
        assertEquals("notes travel with their slide", "Mention the dependency on the hardware team.", re.slides[3].notes)
        try { d.moveSlide(0, 7); fail() } catch (e: IndexOutOfBoundsException) { /* expected */ }
    }

    @Test fun addTextBoxAppendsOneShape() {
        val d = open()
        val part = "ppt/slides/slide5.xml"
        val before = text(src, part)
        val id = d.addTextBox(4, 1000000L, 4200000L, 5000000L, 800000L, "备注：数据截至五月 & <draft>\nSecond line")
        assertEquals(4, id)
        val out = d.save()
        assertEquals(setOf(part), changedParts(src, out))
        val tree = Xml.find(before, Xml.root(before)!!, "p:spTree")!!
        val added = assertOnlyRangeChanged(before, text(out, part), tree.closeStart, tree.closeStart)
        assertTrue(added.startsWith("<p:sp><p:nvSpPr><p:cNvPr id=\"4\" name=\"TextBox 4\"/><p:cNvSpPr txBox=\"1\"/>"))
        val s = shape(PptxDeck.open(out), 4, id)
        assertEquals(ShapeKind.TEXT, s.kind); assertTrue(s.editable)
        assertEquals(listOf(1000000L, 4200000L, 5000000L, 800000L), listOf(s.x, s.y, s.w, s.h))
        assertEquals(listOf(TextPara("备注：数据截至五月 & <draft>"), TextPara("Second line")), s.paras.map { it.plain() })
        assertEquals(18f, s.fontPt)
        // and it can be edited like any other text shape
        val d2 = PptxDeck.open(out)
        d2.setShapeText(4, id, listOf(TextPara("Edited box")))
        assertEquals(listOf(TextPara("Edited box")), shape(PptxDeck.open(d2.save()), 4, id).paras.map { it.plain() })
    }

    @Test fun saveClearsDirtyAndEditingCanContinue() {
        val d = open()
        d.setShapeText(0, 2, listOf(TextPara("First edit")))
        val one = d.save()
        assertFalse(d.dirty)
        assertArrayEquals(one, d.save())
        d.setNotes(3, "Second edit")
        assertTrue(d.dirty)
        val two = d.save()
        assertEquals(setOf("ppt/slides/slide1.xml", "ppt/notesSlides/notesSlide1.xml"), changedParts(src, two))
        val re = PptxDeck.open(two)
        assertEquals("First edit", re.firstTitle()); assertEquals("Second edit", re.slides[3].notes)
    }

    @Test fun rewritingEveryEditableShape() {
        // with its own paragraphs: every paragraph is recognised as unchanged and kept verbatim
        val d = open()
        val before = d.slides
        var count = 0
        for (s in before) for (sh in s.shapes) if (sh.editable) { d.setShapeText(s.index, sh.id, sh.paras); count++ }
        assertEquals(12, count)
        assertTrue(d.dirty)
        val same = d.save()
        assertEquals(emptySet<String>(), changedParts(src, same))
        assertEquals(before, PptxDeck.open(same).slides)

        // with every paragraph's text changed: structure, levels, bullets and formatting stay
        val d2 = open()
        for (s in before) for (sh in s.shapes) if (sh.editable) d2.setShapeText(s.index, sh.id, sh.paras.map { it.copy(text = it.text + "！") })
        val out = d2.save()
        assertEquals((1..7).map { "ppt/slides/slide$it.xml" }.toSet(), changedParts(src, out))
        val expected = before.map { s -> s.copy(shapes = s.shapes.map { sh -> if (sh.editable) sh.copy(paras = sh.paras.map { it.copy(text = it.text + "！") }) else sh }) }
        assertEquals(plainSlides(expected), plainSlides(PptxDeck.open(out).slides))
        assertDeckConsistent(out)
        writeRoundTrip("pptx-rewritten.pptx", out)
    }

    // ---- hand-altered decks: XML the generated fixture does not contain

    /** The fixture with one part's text rewritten. */
    private fun altered(part: String, change: (String) -> String): ByteArray {
        val pkg = OoxmlPackage.read(src)
        pkg.putText(part, change(pkg.text(part)!!))
        return pkg.write()
    }

    @Test fun deletingASlideAlsoDropsItFromSectionAndCustomShowLists() {
        val sections = "<p:extLst><p:ext uri=\"{521415D9-36F7-43E2-AB2F-B90AF26B5E84}\"><p14:sectionLst xmlns:p14=\"urn:example:sections\">" +
            "<p14:section name=\"Intro\" id=\"{A}\"><p14:sldIdLst><p14:sldId id=\"256\"/><p14:sldId id=\"257\"/></p14:sldIdLst></p14:section>" +
            "<p14:section name=\"Rest\" id=\"{B}\"><p14:sldIdLst><p14:sldId id=\"258\"/><p14:sldId id=\"259\"/><p14:sldId id=\"260\"/><p14:sldId id=\"261\"/><p14:sldId id=\"262\"/></p14:sldIdLst></p14:section>" +
            "</p14:sectionLst></p:ext></p:extLst>"
        val shows = "<p:custShowLst><p:custShow name=\"Short\" id=\"0\"><p:sldLst><p:sld r:id=\"rId7\"/><p:sld r:id=\"rId8\"/><p:sld r:id=\"rId14\"/></p:sldLst></p:custShow></p:custShowLst>"
        val bytes = altered(pres) { it.replace("<p:defaultTextStyle>", "$shows<p:defaultTextStyle>").replace("</p:presentation>", "$sections</p:presentation>") }
        val d = PptxDeck.open(bytes)
        assertEquals(7, d.slides.size)
        assertAllPartsIdentical(bytes, d.save())
        d.deleteSlide(1)                         // id 257, rId8
        val out = d.save()
        assertDeckConsistent(out)
        val px = text(out, pres)
        assertFalse(px.contains("id=\"257\"")); assertFalse(px.contains("rId8\""))
        assertTrue(px.contains("<p14:sldIdLst><p14:sldId id=\"256\"/></p14:sldIdLst>"))
        assertTrue(px.contains("<p:sldLst><p:sld r:id=\"rId7\"/><p:sld r:id=\"rId14\"/></p:sldLst>"))
        assertTrue(px.contains("<p14:sldId id=\"258\"/><p14:sldId id=\"259\"/><p14:sldId id=\"260\"/><p14:sldId id=\"261\"/><p14:sldId id=\"262\"/>"))
    }

    @Test fun placeholderWithoutTextBodyAndShapesWithFieldsOrNoText() {
        val empty = "<p:sp><p:nvSpPr><p:cNvPr id=\"3\" name=\"Subtitle 2\"/><p:cNvSpPr><a:spLocks noGrp=\"1\"/></p:cNvSpPr><p:nvPr><p:ph type=\"subTitle\" idx=\"1\"/></p:nvPr></p:nvSpPr><p:spPr/></p:sp>"
        val extra = "<p:sp><p:nvSpPr><p:cNvPr id=\"7\" name=\"Slide Number\"/><p:cNvSpPr/><p:nvPr><p:ph type=\"sldNum\" idx=\"12\"/></p:nvPr></p:nvSpPr><p:spPr/><p:txBody><a:bodyPr/><a:lstStyle/><a:p><a:fld id=\"{1}\" type=\"slidenum\"><a:rPr lang=\"en-US\"/><a:t>1</a:t></a:fld></a:p></p:txBody></p:sp>" +
            "<p:sp><p:nvSpPr><p:cNvPr id=\"8\" name=\"Oval\"/><p:cNvSpPr/><p:nvPr/></p:nvSpPr><p:spPr><a:xfrm><a:off x=\"10\" y=\"20\"/><a:ext cx=\"30\" cy=\"40\"/></a:xfrm><a:prstGeom prst=\"ellipse\"><a:avLst/></a:prstGeom></p:spPr></p:sp>" +
            "<p:cxnSp><p:nvCxnSpPr><p:cNvPr id=\"9\" name=\"Line\"/><p:cNvCxnSpPr/><p:nvPr/></p:nvCxnSpPr><p:spPr><a:xfrm><a:off x=\"1\" y=\"2\"/><a:ext cx=\"3\" cy=\"4\"/></a:xfrm><a:prstGeom prst=\"line\"><a:avLst/></a:prstGeom></p:spPr></p:cxnSp>"
        val part = "ppt/slides/slide1.xml"
        val bytes = altered(part) { x ->
            val sub = shapeRange(x, 3)
            (x.substring(0, sub.start) + empty + x.substring(sub.end)).replace("</p:spTree>", "$extra</p:spTree>")
        }
        val d = PptxDeck.open(bytes)
        val shapes = d.slides[0].shapes
        assertEquals(listOf(2, 3, 7, 8, 9), shapes.map { it.id })
        assertTrue(shapes[1].editable); assertTrue(shapes[1].paras.isEmpty()); assertEquals(32f, shapes[1].fontPt)
        assertEquals(ShapeKind.TEXT, shapes[2].kind); assertFalse("a field cannot be retyped", shapes[2].editable); assertEquals("1", shapes[2].paras[0].text)
        assertEquals(ShapeKind.OTHER, shapes[3].kind); assertFalse(shapes[3].editable)
        assertEquals(listOf(10L, 20L, 30L, 40L), listOf(shapes[3].x, shapes[3].y, shapes[3].w, shapes[3].h))
        assertEquals(ShapeKind.OTHER, shapes[4].kind)
        assertTrue(d.compat().contains(CompatNote(CompatKind.READ_ONLY, "connector", 1)))
        assertTrue(d.compat().contains(CompatNote(CompatKind.READ_ONLY, "field", 1)))
        expectReadOnly { d.setShapeText(0, 7, listOf(TextPara("2"))) }
        expectReadOnly { d.setShapeText(0, 8, listOf(TextPara("x"))) }

        val before = text(bytes, part)
        d.setShapeText(0, 3, listOf(TextPara("Now it has text")))
        val out = d.save()
        assertEquals(setOf(part), changedParts(bytes, out))
        val el = shapeRange(before, 3)
        val replaced = assertOnlyRangeChanged(before, text(out, part), el.start, el.end)
        assertEquals(empty.replace("<p:spPr/></p:sp>", "<p:spPr/><p:txBody><a:bodyPr/><a:lstStyle/><a:p><a:r><a:t>Now it has text</a:t></a:r></a:p></p:txBody></p:sp>"), replaced)
        assertEquals(listOf(TextPara("Now it has text")), PptxDeck.open(out).slides[0].shapes[1].paras.map { it.plain() })
        assertEquals("the new shape id is one above the highest id on the slide", 10, d.addTextBox(0, 0L, 0L, 100L, 100L, "x"))
    }

    // ---- new files

    @Test fun blankDeckIsValidAndCarriesNoAuthor() {
        val out = PptxDeck.blank().save()
        assertEquals(listOf("ppt/slides/slide1.xml"), assertDeckConsistent(out))
        assertTrue(text(out, "docProps/app.xml").contains("<Application>Spatial Office</Application>"))
        val core = text(out, "docProps/core.xml")
        assertFalse(core.contains("creator")); assertFalse(core.contains("lastModifiedBy"))
        val d = PptxDeck.open(out)
        assertEquals(12192000L, d.widthEmu); assertEquals(6858000L, d.heightEmu)
        assertEquals(1, d.slides.size)
        val shapes = d.slides[0].shapes
        assertEquals(listOf(ShapeKind.TITLE, ShapeKind.BODY), shapes.map { it.kind })
        assertTrue(shapes.all { it.editable && it.w > 0 && it.h > 0 })
        assertEquals(60f, shapes[0].fontPt); assertEquals(1, shapes[0].align); assertEquals(24f, shapes[1].fontPt)
        assertEquals(0xFFFFFF, d.slides[0].backgroundRgb)
        assertEquals("", d.firstTitle())
        assertTrue(d.compat().isEmpty())
        assertAllPartsIdentical(out, d.save())
    }

    @Test fun blankEditSaveReopen() {
        val d = PptxDeck.blank()
        assertFalse(d.dirty)
        d.setShapeText(0, 2, listOf(TextPara("空间办公 Spatial Review")))
        d.setShapeText(0, 3, listOf(TextPara("Q2 planning & <notes>")))
        assertEquals(1, d.addSlide(0, SlideLayoutKind.TITLE_BODY))
        d.setShapeText(1, 2, listOf(TextPara("Agenda")))
        d.setShapeText(1, 3, listOf(TextPara("Status", 0, true), TextPara("Risks", 1, true), TextPara("下一步", 1, true), TextPara("Summary", 0, false)))
        d.setNotes(1, "Keep this short.")          // the new deck has no notes master yet: one is created
        val boxId = d.addTextBox(1, 8000000L, 5600000L, 3000000L, 500000L, "Confidential")
        assertEquals(2, d.addSlide(1, SlideLayoutKind.TITLE))
        d.setShapeText(2, 2, listOf(TextPara("Thanks")))
        val out = d.save()
        assertDeckConsistent(out)
        writeRoundTrip("pptx-blank.pptx", out)

        val names = parts(out).keys
        assertTrue("ppt/notesMasters/notesMaster1.xml" in names); assertTrue("ppt/theme/theme2.xml" in names); assertTrue("ppt/notesSlides/notesSlide1.xml" in names)
        assertTrue(text(out, pres).contains("</p:sldMasterIdLst><p:notesMasterIdLst><p:notesMasterId r:id=\"rId5\"/></p:notesMasterIdLst><p:sldIdLst>"))
        val re = PptxDeck.open(out)
        assertEquals(listOf("空间办公 Spatial Review", "Agenda", "Thanks"), titles(re))
        assertEquals("空间办公 Spatial Review", re.firstTitle())
        assertEquals(listOf(TextPara("Q2 planning & <notes>")), re.slides[0].shapes[1].paras.map { it.plain() })
        assertEquals(listOf(TextPara("Status", 0, true), TextPara("Risks", 1, true), TextPara("下一步", 1, true), TextPara("Summary", 0, false)), re.slides[1].shapes[1].paras.map { it.plain() })
        assertEquals("Keep this short.", re.slides[1].notes)
        assertEquals(listOf(TextPara("Confidential")), shape(re, 1, boxId).paras.map { it.plain() })
        assertEquals(28f, re.slides[1].shapes[1].fontPt)
        assertNotNull(re.slides[2].shapes.firstOrNull { it.kind == ShapeKind.BODY })
        assertAllPartsIdentical(out, re.save())
    }

    // ---- file for the independent verifier (tools/verify/verify_docx_pptx.py)

    @Test fun writesEditedFixtureForTheVerifier() {
        val d = open()
        d.setShapeText(0, 2, listOf(TextPara("产品评审 2026 & <final>")))
        val paras = d.slides[1].shapes[1].paras.toMutableList()
        paras[1] = paras[1].copy(text = "改进新手引导")
        paras.add(TextPara("Added point", 1, true))
        d.setShapeText(1, 3, paras)
        d.setNotes(3, "Updated note.\nSecond line.")
        d.setNotes(0, "New note on the title slide.")
        d.addTextBox(2, 5029200L, 3200400L, 3200400L, 600000L, "Added text box")
        val idx = d.addSlide(6, SlideLayoutKind.TITLE_BODY)
        d.setShapeText(idx, 2, listOf(TextPara("Appendix")))
        d.setShapeText(idx, 3, listOf(TextPara("Extra data", 0, true), TextPara("Sources", 1, true)))
        d.deleteSlide(4)                         // "Budget" (the table slide)
        d.moveSlide(5, 1)                        // "Thank you" → second
        val out = d.save()
        assertDeckConsistent(out)
        assertEquals(listOf("产品评审 2026 & <final>", "Thank you", "Goals", "Prototype", "Timeline", "Architecture", "Appendix"), titles(PptxDeck.open(out)))
        writeRoundTrip("pptx-edited.pptx", out)
    }

    /** Slides with every paragraph reduced to text, level and bullet (M2 added run formatting and alignment to the model). */
    private fun plainSlides(l: List<Slide>) = l.map { s -> s.copy(shapes = s.shapes.map { it.copy(paras = it.paras.map { p -> p.plain() }) }) }
}
