package io.embodit.spatialoffice.core

import io.embodit.spatialoffice.core.cloud.AppRegistration
import io.embodit.spatialoffice.core.cloud.BrowserSignIn
import io.embodit.spatialoffice.core.cloud.CloudCatalog
import io.embodit.spatialoffice.core.cloud.Http
import io.embodit.spatialoffice.core.cloud.Pkce
import io.embodit.spatialoffice.core.cloud.Provider
import io.embodit.spatialoffice.core.docx.DocxDocument
import io.embodit.spatialoffice.core.docx.ImageBlock
import io.embodit.spatialoffice.core.docx.PackageAsserts.assertConsistent
import io.embodit.spatialoffice.core.docx.PackageAsserts.changedParts
import io.embodit.spatialoffice.core.docx.PackageAsserts.fixture
import io.embodit.spatialoffice.core.docx.PackageAsserts.writeRoundTrip
import io.embodit.spatialoffice.core.docx.TableBlock
import io.embodit.spatialoffice.core.layout.BoxDrag
import io.embodit.spatialoffice.core.layout.Corner
import io.embodit.spatialoffice.core.layout.DragTotal
import io.embodit.spatialoffice.core.layout.OpenFiles
import io.embodit.spatialoffice.core.layout.UnitBox
import io.embodit.spatialoffice.core.layout.WindowFit
import io.embodit.spatialoffice.core.pptx.PptxDeck
import io.embodit.spatialoffice.core.pptx.ShapeKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * v0.2.5, headset feedback 2026-11-17: "现在 PPT 插入的东西移动不了。然后 word 文档也不能多开" and "应该点了连接弹出浏览器登录飞书吧".
 */
class V025Test {
    private val png: ByteArray = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==")

    // ------------------------------------------------------------------ 1. move / resize what was inserted

    @Test fun sixtyStepsOfTwoAddUpToOneHundredAndTwenty() {
        // the headset pattern (profile D53): 60 pointer events of 2 mm each are 12 cm of travel, not 2 mm
        val t = DragTotal()
        repeat(60) { t.add(2f, 0f) }
        assertEquals(120f, t.x, 0.001f); assertEquals(0f, t.y, 0.001f); assertEquals(60, t.events)
        t.reset(); assertEquals(0f, t.x, 0f); assertEquals(0, t.events)
    }

    @Test fun anObjectFollowsTheTravelSinceThePressNotTheLastStep() {
        val slideW = 12_192_000L; val slideH = 6_858_000L
        val pxPerEmu = 1920f / slideW                       // the 960 dp canvas at density 2
        val start = UnitBox(3_048_000, 2_743_200, 6_096_000, 1_143_000)
        val t = DragTotal()
        var box = start
        repeat(60) { t.add(2f, 1f); box = BoxDrag.move(start, BoxDrag.units(t.x, pxPerEmu), BoxDrag.units(t.y, pxPerEmu), slideW, slideH) }
        assertEquals(start.x + 762_000, box.x)              // 120 px of 1920 = 1/16 of the slide width
        assertEquals(start.y + 381_000, box.y)
        assertEquals(start.w, box.w); assertEquals(start.h, box.h)
        // what v0.2.4-style handlers did with a step taken for the total: the object stays within one step of its start
        val pinned = BoxDrag.move(start, BoxDrag.units(2f, pxPerEmu), BoxDrag.units(1f, pxPerEmu), slideW, slideH)
        assertTrue(pinned.x - start.x < 20_000)
    }

    @Test fun aMovedObjectKeepsHalfOfItselfOnTheSlide() {
        val start = UnitBox(1000, 1000, 4000, 2000)
        assertEquals(UnitBox(-2000, -1000, 4000, 2000), BoxDrag.move(start, -99_999, -99_999, 10_000, 8_000))
        assertEquals(UnitBox(8000, 7000, 4000, 2000), BoxDrag.move(start, 99_999, 99_999, 10_000, 8_000))
    }

    @Test fun everyCornerResizesAgainstTheOppositeCorner() {
        val s = UnitBox(1_000_000, 1_000_000, 4_000_000, 2_000_000)
        val right = s.x + s.w; val bottom = s.y + s.h
        val br = BoxDrag.resize(s, Corner.BOTTOM_RIGHT, 500_000, 250_000, false)
        assertEquals(UnitBox(s.x, s.y, 4_500_000, 2_250_000), br)
        val tl = BoxDrag.resize(s, Corner.TOP_LEFT, 500_000, 250_000, false)
        assertEquals(right, tl.x + tl.w); assertEquals(bottom, tl.y + tl.h); assertEquals(3_500_000, tl.w); assertEquals(1_750_000, tl.h)
        val tr = BoxDrag.resize(s, Corner.TOP_RIGHT, 500_000, -250_000, false)
        assertEquals(s.x, tr.x); assertEquals(bottom, tr.y + tr.h); assertEquals(4_500_000, tr.w); assertEquals(2_250_000, tr.h)
        val bl = BoxDrag.resize(s, Corner.BOTTOM_LEFT, -500_000, 250_000, false)
        assertEquals(right, bl.x + bl.w); assertEquals(s.y, bl.y); assertEquals(4_500_000, bl.w); assertEquals(2_250_000, bl.h)
        // never smaller than a quarter inch, however far the corner is pulled in
        val tiny = BoxDrag.resize(s, Corner.BOTTOM_RIGHT, -9_000_000, -9_000_000, false)
        assertEquals(BoxDrag.MIN_SIDE, tiny.w); assertEquals(BoxDrag.MIN_SIDE, tiny.h)
    }

    @Test fun aPictureKeepsItsProportionsAndTheTextWidth() {
        val s = UnitBox(0, 0, 4_000_000, 2_000_000)
        val bigger = BoxDrag.resize(s, Corner.BOTTOM_RIGHT, 1_000_000, 0, keepAspect = true)
        assertEquals(5_000_000, bigger.w); assertEquals(2_500_000, bigger.h)
        val byHeight = BoxDrag.resize(s, Corner.BOTTOM_RIGHT, 0, -1_000_000, keepAspect = true)
        assertEquals(2_000_000, byHeight.w); assertEquals(1_000_000, byHeight.h)
        val capped = BoxDrag.resize(s, Corner.BOTTOM_RIGHT, 9_000_000, 0, keepAspect = true, maxW = DocxDocument.TEXT_WIDTH_EMU)
        assertEquals(DocxDocument.TEXT_WIDTH_EMU, capped.w); assertEquals(DocxDocument.TEXT_WIDTH_EMU / 2, capped.h)
        val small = BoxDrag.resize(s, Corner.TOP_LEFT, 9_000_000, 9_000_000, keepAspect = true)
        assertEquals(2.0, small.w.toDouble() / small.h, 0.01); assertTrue(small.h >= BoxDrag.MIN_SIDE)
        assertEquals(s.x + s.w, small.x + small.w); assertEquals(s.y + s.h, small.y + small.h)
    }

    @Test fun insertedTextBoxPictureShapeAndTableAllMoveAndResizeAndSurviveSaving() {
        val d = PptxDeck.blank()
        val w = d.widthEmu; val h = d.heightEmu
        val text = d.addTextBox(0, w / 4, h / 4, w / 2, h / 6, "moved text")
        val pic = d.addPicture(0, png, "png", w / 4, h / 4, w / 4, h / 4)
        val shape = d.addShape(0, "ellipse", w / 3, h / 3, h / 4, h / 4, 0x2563EB)
        val table = d.addTable(0, 3, 3, w / 5, h / 3, w * 3 / 5, h / 3)
        val want = HashMap<Int, UnitBox>()
        for ((k, id) in listOf(text, pic, shape, table).withIndex()) {
            val sh = d.slides[0].shapes.first { it.id == id }
            val start = UnitBox(sh.x, sh.y, sh.w, sh.h)
            val moved = BoxDrag.move(start, 400_000L * (k + 1), -200_000L * (k + 1), w, h)
            val sized = BoxDrag.resize(moved, Corner.BOTTOM_RIGHT, 300_000, 150_000, keepAspect = sh.kind == ShapeKind.PICTURE)
            d.setShapeBox(0, id, sized.x, sized.y, sized.w, sized.h)
            want[id] = sized
            assertNotEquals(start, sized)
        }
        val saved = d.save()
        assertConsistent(saved)
        // also checked with python-pptx: tools/verify/verify_v025.py
        writeRoundTrip("v025-objects.pptx", saved)
        java.io.File("build/roundtrip/v025-objects.txt").writeText(want.entries.joinToString("\n") { (id, b) -> "$id ${b.x} ${b.y} ${b.w} ${b.h}" })
        val back = PptxDeck.open(saved)
        for ((id, box) in want) {
            val sh = back.slides[0].shapes.first { it.id == id }
            assertEquals("shape $id", box, UnitBox(sh.x, sh.y, sh.w, sh.h))
        }
        assertEquals("moved text", back.slides[0].shapes.first { it.id == text }.paras.joinToString { it.text })
        assertEquals(ShapeKind.PICTURE, back.slides[0].shapes.first { it.id == pic }.kind)
        assertEquals("ellipse", back.slides[0].shapes.first { it.id == shape }.geom)
        assertEquals(3, back.slides[0].shapes.first { it.id == table }.table!!.size)
    }

    @Test fun aTableOnASlideIsTypedIntoAndItsColumnsScaleWithItsFrame() {
        val d = PptxDeck.blank()
        val id = d.addTable(0, 2, 3, 1_000_000, 1_000_000, 6_000_000, 1_200_000)
        d.setTableCell(0, id, 0, 0, "Q1"); d.setTableCell(0, id, 1, 2, "a & b\nsecond line")
        var t = d.slides[0].shapes.first { it.id == id }
        assertTrue(t.cellsEditable)
        assertEquals(listOf(listOf("Q1", "", ""), listOf("", "", "a & b\nsecond line")), t.table)
        assertTrue("an editable table is not listed as read-only", d.compat().none { it.code == "table" })
        d.setShapeBox(0, id, 500_000, 800_000, 9_000_000, 2_400_000)
        val bytes = d.save()
        assertConsistent(bytes)
        writeRoundTrip("v025-table.pptx", bytes)
        val back = PptxDeck.open(bytes)
        t = back.slides[0].shapes.first { it.id == id }
        assertEquals(UnitBox(500_000, 800_000, 9_000_000, 2_400_000), UnitBox(t.x, t.y, t.w, t.h))
        assertEquals("Q1", t.table!![0][0]); assertEquals("a & b\nsecond line", t.table!![1][2])
        val xml = io.embodit.spatialoffice.core.docx.PackageAsserts.text(bytes, "ppt/slides/slide1.xml")
        // other software lays a table out by its own column widths and row heights: they follow the frame
        assertEquals(listOf("3000000", "3000000", "3000000"), Regex("<a:gridCol w=\"(\\d+)\"").findAll(xml).map { it.groupValues[1] }.toList())
        assertEquals(listOf("1200000", "1200000"), Regex("<a:tr h=\"(\\d+)\"").findAll(xml).map { it.groupValues[1] }.toList())
    }

    @Test fun objectsOfAnOpenedFileMoveTooAndNothingElseInTheFileChanges() {
        val src = fixture("pptx-deck.pptx")
        val d = PptxDeck.open(src)
        // slide 5: a table in a graphic frame; slide 6: a group and a chart frame; slide 3: a picture
        val moved = ArrayList<Triple<Int, Int, UnitBox>>()
        for (slide in listOf(2, 4, 5)) for (sh in d.slides[slide].shapes.filter { it.kind != ShapeKind.TITLE && it.w > 0 }) {
            val box = UnitBox(sh.x + 123_456, sh.y + 65_432, sh.w + 200_000, sh.h + 100_000)
            d.setShapeBox(slide, sh.id, box.x, box.y, box.w, box.h)
            moved.add(Triple(slide, sh.id, box))
        }
        assertTrue(moved.size >= 5)
        val out = d.save()
        assertConsistent(out)
        assertEquals(setOf("ppt/slides/slide3.xml", "ppt/slides/slide5.xml", "ppt/slides/slide6.xml"), changedParts(src, out))
        val back = PptxDeck.open(out)
        for ((slide, id, box) in moved) {
            val sh = back.slides[slide].shapes.first { it.id == id }
            assertEquals("slide ${slide + 1} shape $id", box, UnitBox(sh.x, sh.y, sh.w, sh.h))
        }
        // the group keeps its children's own coordinate space, so they scale with it instead of jumping
        val g = io.embodit.spatialoffice.core.docx.PackageAsserts.text(out, "ppt/slides/slide6.xml")
        if (g.contains("<p:grpSpPr>")) assertTrue(g.substringAfter("<p:grpSpPr>").substringBefore("</p:grpSpPr>").contains("<a:chOff"))
    }

    @Test fun aDocumentPictureAndTableAreResizedInTheFile() {
        val d = DocxDocument.blank()
        val first = d.blocks.first().id
        val pic = d.insertImageAfter(first, png, "png", 3_000_000, 1_500_000)
        val table = d.insertTableAfter(pic, 2, 3)
        d.setTableCell(table, 0, 0, "kept")
        assertEquals(100, (d.blocks.first { it.id == table } as TableBlock).widthPct)
        val sized = BoxDrag.resize(UnitBox(0, 0, 3_000_000, 1_500_000), Corner.BOTTOM_RIGHT, -1_000_000, 0, keepAspect = true, maxW = DocxDocument.TEXT_WIDTH_EMU)
        d.setImageSize(pic, sized.w, sized.h)
        d.setTableWidth(table, 60)
        val bytes = d.save()
        assertConsistent(bytes)
        writeRoundTrip("v025-resized.docx", bytes)
        val back = DocxDocument.open(bytes)
        val img = back.blocks.filterIsInstance<ImageBlock>().single()
        assertEquals(2_000_000, img.widthEmu); assertEquals(1_000_000, img.heightEmu)
        val tb = back.blocks.filterIsInstance<TableBlock>().single()
        assertEquals(60, tb.widthPct); assertEquals("kept", tb.rows[0][0])
        val xml = io.embodit.spatialoffice.core.docx.PackageAsserts.text(bytes, "word/document.xml")
        assertTrue(xml.contains("<wp:extent cx=\"2000000\" cy=\"1000000\"")); assertTrue(xml.contains("<a:ext cx=\"2000000\" cy=\"1000000\""))
        assertTrue(xml.contains("<w:tblW w:w=\"3000\" w:type=\"pct\"/>"))
        // a table of an opened file: only the document part changes
        val src = fixture("docx-report.docx")
        val r = DocxDocument.open(src)
        val t = r.blocks.filterIsInstance<TableBlock>().firstOrNull()
        if (t != null) {
            r.setTableWidth(t.id, 50)
            val out = r.save()
            assertConsistent(out)
            assertEquals(setOf("word/document.xml"), changedParts(src, out))
            assertEquals(50, DocxDocument.open(out).blocks.filterIsInstance<TableBlock>().first().widthPct)
            assertEquals(t.rows, DocxDocument.open(out).blocks.filterIsInstance<TableBlock>().first().rows)
        }
    }

    // ------------------------------------------------------------------ 2. several files of one kind

    @Test fun aSecondDocumentOpensInItsOwnWindowAndTheFirstStaysOpen() {
        val f = OpenFiles()
        val a = f.open("DOC", "/sdcard/Documents/a.docx") as OpenFiles.Open.New
        val b = f.open("DOC", "/sdcard/Documents/b.docx") as OpenFiles.Open.New
        assertNotEquals(a.entry.tag, b.entry.tag)
        assertEquals(listOf("/sdcard/Documents/a.docx", "/sdcard/Documents/b.docx"), f.ofKind("DOC").map { it.key })
        // the same for slides and sheets, each kind counted on its own
        val p1 = f.open("SLIDES", "p1.pptx") as OpenFiles.Open.New; val p2 = f.open("SLIDES", "p2.pptx") as OpenFiles.Open.New
        val x1 = f.open("SHEET", "x1.xlsx") as OpenFiles.Open.New; val x2 = f.open("SHEET", "x2.xlsx") as OpenFiles.Open.New
        assertEquals(listOf("p1.pptx", "p2.pptx"), f.ofKind("SLIDES").map { it.key }); assertEquals(listOf("x1.xlsx", "x2.xlsx"), f.ofKind("SHEET").map { it.key })
        assertEquals(4, setOf(p1, p2, x1, x2).map { it.entry.tag }.toSet().size)
        assertEquals(6, f.entries.size)
        assertEquals(6, f.entries.map { it.tag }.toSet().size)
    }

    @Test fun openingAFileThatIsOpenBringsItsWindowForwardInsteadOfASecondCopy() {
        val f = OpenFiles()
        val a = (f.open("DOC", "a.docx") as OpenFiles.Open.New).entry
        val b = (f.open("DOC", "b.docx") as OpenFiles.Open.New).entry
        assertEquals(b.tag, f.active("DOC")!!.tag)
        val again = f.open("DOC", "a.docx")
        assertTrue(again is OpenFiles.Open.Front)
        assertEquals(a.tag, (again as OpenFiles.Open.Front).entry.tag)
        assertEquals(2, f.entries.size)
        assertEquals("the window called forward is the active one", a.tag, f.active("DOC")!!.tag)
    }

    @Test fun closingOneLeavesTheOthersOpen() {
        val f = OpenFiles()
        val tags = (1..3).map { (f.open("DOC", "d$it.docx") as OpenFiles.Open.New).entry }
        assertNotNull(f.close(tags[1].tag))
        assertEquals(listOf("d1.docx", "d3.docx"), f.ofKind("DOC").map { it.key })
        assertEquals("the one used before it is the active one again", tags[2].tag, f.active("DOC")!!.tag)
        val d4 = (f.open("DOC", "d4.docx") as OpenFiles.Open.New).entry
        assertTrue("a tag is never used twice", d4.tag !in tags.map { it.tag })
        assertNull(f.close("no-such-window"))
        // a file saved under a new name stays in its window
        f.rekey(tags[0].tag, "renamed.docx")
        assertTrue(f.open("DOC", "renamed.docx") is OpenFiles.Open.Front)
        assertTrue(f.open("DOC", "d1.docx") is OpenFiles.Open.New)
    }

    @Test fun noMoreThanFiveFilesOfAKindAtOnce() {
        val f = OpenFiles()
        repeat(OpenFiles.MAX_PER_KIND) { assertTrue(f.open("SLIDES", "p$it.pptx") is OpenFiles.Open.New) }
        val full = f.open("SLIDES", "one-more.pptx")
        assertTrue(full is OpenFiles.Open.Full); assertEquals(OpenFiles.MAX_PER_KIND, (full as OpenFiles.Open.Full).open)
        assertEquals(OpenFiles.MAX_PER_KIND, f.entries.size)
        assertTrue("another kind still opens", f.open("DOC", "d.docx") is OpenFiles.Open.New)
        assertTrue("and an open one still comes forward", f.open("SLIDES", "p0.pptx") is OpenFiles.Open.Front)
    }

    @Test fun aNewWindowOpensInFrontBecauseBesideIsOutOfView() {
        // the editor windows as declared in Main.kt
        val sizes = mapOf("doc" to (1160.0 to 860.0), "sheet" to (1280.0 to 800.0), "slides" to (1360.0 to 860.0))
        for ((name, wh) in sizes) for (scale in listOf(WindowFit.M_PER_DP, WindowFit.M_PER_DP_BRIEF)) {
            // in front: all 8 corners inside ±39° (the second, third … window of a kind lands on this same spot)
            val front = WindowFit.Box(0.0, -WindowFit.EYE_DROP, -WindowFit.DISTANCE, wh.first * scale, wh.second * scale)
            assertTrue("$name in front at $scale m/dp", WindowFit.fit(front).fits)
            // beside a window of the same size (what the system offers besides "in front"): its far edge is well outside
            val (near, far) = WindowFit.besideYaw(wh.first, wh.first, 24.0, scale)
            assertTrue("$name beside starts at $near°", near > 20.0)
            assertTrue("$name beside ends at $far°, outside ±39°", far > WindowFit.HALF_FOV + 10)
            assertEquals(WindowFit.Second.FRONT, WindowFit.secondWindow(wh.first, wh.first))
        }
        // the model for a 1160 dp document beside another with a 24 dp gap (emulator 2026-11-18: it stood on the circle
        // around the user, turned to face them, see build/v0.2.5/placement-trials.md)
        val (near, far) = WindowFit.besideYaw(1160.0, 1160.0, 24.0)
        assertEquals(31.3, near, 1.0); assertEquals(91.3, far, 1.5)
        // only a window narrow enough to end inside ±39° would go beside (none of the editors is)
        assertEquals(WindowFit.Second.BESIDE, WindowFit.secondWindow(400.0, 300.0))
    }

    // ------------------------------------------------------------------ 3. connect = the browser; no client id, no row

    @Test fun aServiceWithoutAClientIdIsNotListed() {
        val none = Provider.values().associateWith { AppRegistration("", "", "http://127.0.0.1:53682/callback") }
        assertEquals(emptyList<Provider>(), CloudCatalog.shown(none))
        val placeholder = none + (Provider.GDRIVE to AppRegistration("REPLACE_WITH_GOOGLE_CLIENT_ID", "", "x"))
        assertEquals(emptyList<Provider>(), CloudCatalog.shown(placeholder))
        val feishu = none + (Provider.FEISHU to AppRegistration("cli_a1b2c3", "", "http://127.0.0.1:53682/callback"))
        assertEquals(listOf(Provider.FEISHU), CloudCatalog.shown(feishu))
        val two = feishu + (Provider.DROPBOX to AppRegistration("key", "", "http://localhost:53682/callback"))
        assertEquals(listOf(Provider.FEISHU, Provider.DROPBOX), CloudCatalog.shown(two))
    }

    @Test fun connectWithoutAClientIdSendsNothing() {
        val asked = ArrayList<String>()
        val r = BrowserSignIn { asked.add(it); true }.start(Provider.FEISHU, AppRegistration("", "", "http://127.0.0.1:53682/callback"))
        assertTrue(r is BrowserSignIn.Result.NotOffered)
        assertTrue(asked.isEmpty())
    }

    @Test fun connectWithAClientIdAsksTheBrowserForTheServicesSignInPage() {
        for (p in Provider.values()) {
            val reg = AppRegistration("client-${p.id}", "", "http://127.0.0.1:53682/callback")
            val asked = ArrayList<String>()
            val r = BrowserSignIn { asked.add(it); true }.start(p, reg)
            assertEquals("exactly one page handed to the browser", 1, asked.size)
            val url = asked[0]
            assertTrue(url, url.startsWith(p.authUrl + "?"))                  // the service's own page, https
            assertTrue(url.startsWith("https://"))
            val q = Http.query(url)
            assertEquals("client-${p.id}", q["client_id"]); assertEquals(reg.redirectUri, q["redirect_uri"]); assertEquals("code", q["response_type"])
            assertEquals("S256", q["code_challenge_method"])
            val started = r as BrowserSignIn.Result.Started
            assertEquals(Pkce.challenge(started.request.verifier), q["code_challenge"])   // PKCE: only the challenge leaves the device
            assertFalse(url.contains(started.request.verifier))
            // the callback with this state gives the code; any other state is not ours
            assertEquals("the-code", started.request.parseCallback(mapOf("state" to q.getValue("state"), "code" to "the-code"))!!.getOrThrow())
            assertNull(started.request.parseCallback(mapOf("state" to "someone-else", "code" to "x")))
        }
    }

    @Test fun noBrowserOnTheDeviceIsReportedNotSwallowed() {
        val r = BrowserSignIn { false }.start(Provider.ONEDRIVE, AppRegistration("id", "", "spatialoffice://oauth/onedrive"))
        assertTrue(r is BrowserSignIn.Result.NoBrowser)
    }
}
