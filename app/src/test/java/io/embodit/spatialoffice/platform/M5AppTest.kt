package io.embodit.spatialoffice.platform

import org.junit.Assert.assertEquals
import org.junit.Test

/** v0.7.0 (M5): rules of the review pane that do not need a device. */
class M5AppTest {

    // B / BACK closes the topmost layer: menu, dialog or sheet, a held format painter, the review pane, and only then the old rules
    @Test
    fun backClosesTheTopmostLayerFirst() {
        fun d(menu: Boolean, overlay: Boolean, painter: Boolean, review: Boolean, home: Boolean = true, stage: Boolean = false) =
            BackPolicy.decide(overlayOpen = overlay, stageUp = stage, homeOpen = home, menuOpen = menu, painterHeld = painter, reviewOpen = review)
        assertEquals(BackAct.CLOSE_MENU, d(menu = true, overlay = true, painter = true, review = true))
        assertEquals(BackAct.CLOSE_OVERLAY, d(menu = false, overlay = true, painter = true, review = true))
        assertEquals(BackAct.DROP_PAINTER, d(menu = false, overlay = false, painter = true, review = true))
        assertEquals(BackAct.CLOSE_REVIEW, d(menu = false, overlay = false, painter = false, review = true))
        assertEquals(BackAct.NOTHING, d(menu = false, overlay = false, painter = false, review = false))
        // the pane open and the home window closed: the pane goes first, the next press brings the home window back
        assertEquals(BackAct.CLOSE_REVIEW, d(menu = false, overlay = false, painter = false, review = true, home = false))
        assertEquals(BackAct.REOPEN_HOME, d(menu = false, overlay = false, painter = false, review = false, home = false))
        // in the Stage the pane is not on screen: B does nothing there, as before
        assertEquals(BackAct.NOTHING, d(menu = false, overlay = false, painter = false, review = true, stage = true))
    }
}

/** Motion: which transition the rehearsal screen plays for a slide change. */
class StageMotionTest {
    @Test
    fun theNeighbourSlidesTheOthersJumpBlackFades() {
        val k = io.embodit.spatialoffice.ui.space.StageMotion::kind
        assertEquals("axis", k(2, 3)); assertEquals("axis", k(3, 2))
        assertEquals("jump", k(0, 5)); assertEquals("jump", k(5, 0))
        assertEquals("fade", k(3, -1)); assertEquals("fade", k(-1, 3))
        // one slide fades over the other; nothing moves (the sideways slide read as a jolt on the headset)
        org.junit.Assert.assertTrue(io.embodit.spatialoffice.ui.space.StageMotion.CROSS_MS in 200..350)
    }
}

/** The layout bar's templates, and a window drawn smaller than it was designed for. */
class TemplatesTest {
    @Test
    fun sevenTemplatesPerKindEachWithAPictureInsideItsFrame() {
        for (deck in listOf(true, false)) {
            val t = io.embodit.spatialoffice.ui.space.templates(deck)
            assertEquals(7, t.size); assertEquals(7, t.toSet().size)
            org.junit.Assert.assertTrue((io.embodit.spatialoffice.core.layout.Arrangement.CHAPTERS in t) == deck)
            org.junit.Assert.assertTrue(t.containsAll(io.embodit.spatialoffice.core.layout.Arrangement.values().filter { it.front }))
        }
        for (a in io.embodit.spatialoffice.core.layout.Arrangement.values()) {
            val blocks = io.embodit.spatialoffice.ui.space.templatePicture(a)
            org.junit.Assert.assertTrue(blocks.size in 3..5)
            for (b in blocks) org.junit.Assert.assertTrue("$a ${b.toList()}", b[0] >= 0f && b[1] >= 0f && b[0] + b[2] <= 1.001f && b[1] + b[3] <= 1.001f)
        }
    }

    @Test
    fun aWindowBelowItsDesignSizeIsDrawnInProportion() {
        val z = { w: Float, h: Float, dw: Float, dh: Float -> io.embodit.spatialoffice.ui.WinFit.scale(w, h, dw, dh) }
        assertEquals(1f, z(1360f, 860f, 1300f, 820f), 0f)
        assertEquals(1f, z(1300f, 820f, 1300f, 820f), 0f)
        // the tighter side decides; steps of 2.5 %
        assertEquals(0.75f, z(980f, 820f, 1300f, 820f), 0.001f)
        assertEquals(0.6f, z(1300f, 500f, 1300f, 820f), 0.001f)
        assertEquals(io.embodit.spatialoffice.ui.WinFit.MIN_SCALE, z(300f, 200f, 1300f, 820f), 0f)
        // the Toolbar goes a little further down: its row of tools is wider than the narrowest window
        assertEquals(io.embodit.spatialoffice.ui.WinFit.TOOL_MIN_SCALE, io.embodit.spatialoffice.ui.WinFit.scale(560f, 720f, 1240f, 720f, io.embodit.spatialoffice.ui.WinFit.TOOL_MIN_SCALE), 0f)
    }
}

/** v0.7.0: a reply box closes, the formula bar takes the keyboard back and the input method empties it: the cell keeps its value. */
class M5CellEditTest {
    @Test
    fun anEmptyTextRightAfterTheBarTookFocusIsNotTyping() {
        val e = io.embodit.spatialoffice.ui.sheet.CellEdit()
        var now = 10_000L
        e.clock = { now }
        e.value = androidx.compose.ui.text.input.TextFieldValue("1200", androidx.compose.ui.text.TextRange(0, 4))
        e.focused()
        now += 181
        org.junit.Assert.assertFalse(e.accept(e.generation, androidx.compose.ui.text.input.TextFieldValue("")))
        org.junit.Assert.assertFalse(e.editing)
        assertEquals("1200", e.value.text)
        // later the same change is the user's Backspace on the selected value
        now += 2_000
        org.junit.Assert.assertTrue(e.accept(e.generation, androidx.compose.ui.text.input.TextFieldValue("")))
        org.junit.Assert.assertTrue(e.editing)
        // and typing right after focus is typing
        val f = io.embodit.spatialoffice.ui.sheet.CellEdit(); f.clock = { now }
        f.value = androidx.compose.ui.text.input.TextFieldValue("1200", androidx.compose.ui.text.TextRange(0, 4)); f.focused()
        org.junit.Assert.assertTrue(f.accept(f.generation, androidx.compose.ui.text.input.TextFieldValue("7")))
        org.junit.Assert.assertTrue(f.editing)
    }
}

/** The sample a colleague would send (tools/fixtures/m5_review_samples.py), through the session calls the pane uses. Skipped when the samples were not generated. */
class M5SampleTest {
    private val dir = java.io.File(System.getProperty("m5.samples") ?: "build/m5samples")

    private fun part(bytes: ByteArray, name: String): String {
        java.util.zip.ZipInputStream(java.io.ByteArrayInputStream(bytes)).use { z ->
            while (true) { val e = z.nextEntry ?: break; if (e.name == name) return String(z.readBytes(), Charsets.UTF_8) }
        }
        error("no $name")
    }

    @Test
    fun documentSampleReviewRoundTrip() {
        val f = java.io.File(dir, "review-sample-zh.docx")
        org.junit.Assume.assumeTrue(f.exists())
        val s = DocSession(FileRef.Path(f), io.embodit.spatialoffice.core.files.FileKind.DOC, f.readBytes(), java.io.File("build/tmp"))
        val c = s.comments()
        assertEquals(3, c.size)
        assertEquals(1, c.sumOf { it.replies.size })
        val r = s.revisions()
        assertEquals(listOf("DELETE", "INSERT", "DELETE", "INSERT", "INSERT", "DELETE", "FORMAT"), r.map { it.kind.name })
        assertEquals(3 + 6, s.reviewCount())
        // every comment has a place on the paper
        assertEquals(3, s.commentMarks().values.sumOf { it.size })
        // accept the first insertion's pair partner, reject a deletion: one undo step each
        org.junit.Assert.assertTrue(s.acceptRevision(r[1].id))
        org.junit.Assert.assertTrue(s.rejectRevision(s.revisions().first { it.kind.name == "DELETE" && it.text == "30" }.id))
        val doc = part(s.serialize(), "word/document.xml")
        org.junit.Assert.assertTrue(doc.contains("十二") && !doc.contains("w:id=\"102\""))
        org.junit.Assert.assertTrue(doc.contains(">30<") && !doc.contains("w:id=\"103\""))
        // a comment on the caret's paragraph, a reply, a resolve
        val p = s.blocks().filterIsInstance<UiBlock.Para>()[1]
        s.noteCaret(p.id, 0, 4)
        val id = s.addComment("张三", "数量确认过了吗？")!!
        org.junit.Assert.assertTrue(s.replyComment(id, "张三", "已经问了。"))
        org.junit.Assert.assertTrue(s.resolveComment(c[0].id, true))
        val out = s.serialize()
        org.junit.Assert.assertTrue(part(out, "word/comments.xml").contains("w:author=\"张三\""))
        org.junit.Assert.assertTrue(part(out, "word/commentsExtended.xml").contains("w15:done=\"1\""))
        val again = DocSession(FileRef.Path(f), io.embodit.spatialoffice.core.files.FileKind.DOC, out, java.io.File("build/tmp"))
        assertEquals(4, again.comments().size)
        assertEquals(1, again.comments().count { it.resolved })
        java.io.File(dir, "out").mkdirs(); java.io.File(dir, "out/review-sample-zh-edited.docx").writeBytes(out)
    }

    @Test
    fun trackedTypingKeepsTheDeletedWordAndSignsTheNewOne() {
        val f = java.io.File(dir, "review-format.docx")
        org.junit.Assume.assumeTrue(f.exists())
        val s = DocSession(FileRef.Path(f), io.embodit.spatialoffice.core.files.FileKind.DOC, f.readBytes(), java.io.File("build/tmp"))
        s.trackAuthor = "张三"; s.tracking = true
        val p = s.blocks().filterIsInstance<UiBlock.Para>()[2]
        val old = p.spans
        val text = io.embodit.spatialoffice.core.text.RichText.text(old)
        val plain = io.embodit.spatialoffice.core.text.RichText.applyEdit(old, text.replace("stays", "remains"))
        val (spans, caret) = s.trackedEdit(old, plain, 9, 11)!!
        s.setParagraph(p.id, spans, p.style)
        val doc = part(s.serialize(), "word/document.xml")
        org.junit.Assert.assertTrue(doc, Regex("<w:del [^>]*w:author=\"张三\"[^>]*w:date=\"20\\d\\d-[^\"]+\"").containsMatchIn(doc))
        org.junit.Assert.assertTrue(Regex("<w:ins [^>]*w:author=\"张三\"").containsMatchIn(doc))
        org.junit.Assert.assertTrue(caret > 0)
        // tracking off: the next edit leaves no new mark
        s.tracking = false
        org.junit.Assert.assertNull(s.trackedEdit(old, plain, 0, 0))
        // format painter: A's format onto B, every run of B then has A's run properties
        val paras = s.blocks().filterIsInstance<UiBlock.Para>()
        org.junit.Assert.assertTrue(s.pickPainter(paras[0].id, 2))
        org.junit.Assert.assertTrue(s.paint(paras[1].id, 0, 0))
        org.junit.Assert.assertNull(s.painter)
        val b = s.blocks().filterIsInstance<UiBlock.Para>()[1]
        org.junit.Assert.assertTrue(b.spans.all { it.bold && it.color == 0xC00000 && it.size == 32 })
        // header and footer
        org.junit.Assert.assertTrue(s.setHeaderFooter("内部 · 草稿", "第三季度"))
        val out = s.serialize()
        org.junit.Assert.assertTrue(part(out, "word/header1.xml").contains("内部 · 草稿"))
        org.junit.Assert.assertTrue(part(out, "word/footer1.xml").contains("第三季度"))
        assertEquals("内部 · 草稿", s.headerText())
    }

    @Test
    fun sheetAndDeckSamples() {
        val x = java.io.File(dir, "review-sample-zh.xlsx"); val p = java.io.File(dir, "review-sample-zh.pptx")
        org.junit.Assume.assumeTrue(x.exists() && p.exists())
        val sh = SheetSession(FileRef.Path(x), io.embodit.spatialoffice.core.files.FileKind.SHEET, x.readBytes())
        assertEquals(2, sh.comments().size)
        assertEquals(setOf("B3", "D5"), sh.commentCells().keys)
        sh.select(1, 2) // C2
        val id = sh.addComment("张三", "这个数对吗？")!!
        assertEquals("a comment leaves the cell's value alone", "1200", sh.book.cell(0, 1, 2).input)
        org.junit.Assert.assertTrue(sh.replyComment(id, "张三", "核过了。"))
        assertEquals("a reply leaves the cell's value alone", "1200", sh.book.cell(0, 1, 2).input)
        org.junit.Assert.assertTrue(sh.resolveComment(id, true)); org.junit.Assert.assertTrue(sh.resolveComment(id, false))
        assertEquals("1200", sh.book.cell(0, 1, 2).input)
        sh.select(2, 1) // B3 has an old-style note: no thread there, its card is picked
        org.junit.Assert.assertNull(sh.addComment("张三", "x"))
        assertEquals(io.embodit.spatialoffice.R.string.rv_note_here, sh.reviewHint)
        org.junit.Assert.assertTrue(sh.editComment(sh.comments().first { it.cell == "B3" }.id, "接手两个月了。"))
        val out = sh.serialize()
        assertEquals("1200", XlsxWorkbookProbe.input(out, 1, 2))
        org.junit.Assert.assertTrue(part(out, "xl/threadedComments/threadedComment1.xml").contains("这个数对吗？"))
        // the sample's notes part keeps the name its writer gave it; the thread's old-style copy for C2 is in a notes part too
        val names = ArrayList<String>()
        java.util.zip.ZipInputStream(java.io.ByteArrayInputStream(out)).use { z -> while (true) { val e = z.nextEntry ?: break; names.add(e.name) } }
        val notes = names.filter { it.startsWith("xl/comments") && it.endsWith(".xml") }.joinToString("\n") { part(out, it) }
        org.junit.Assert.assertTrue(notes.contains("接手两个月了。"))
        org.junit.Assert.assertTrue(notes.contains("ref=\"C2\""))
        org.junit.Assert.assertTrue(names.any { it.startsWith("xl/persons/") } && names.any { it.endsWith(".vml") })
        java.io.File(dir, "out").mkdirs(); java.io.File(dir, "out/review-sample-zh-edited.xlsx").writeBytes(out)

        val d = DeckSession(FileRef.Path(p), p.readBytes())
        assertEquals(0, d.comments().size)
        d.current = 1
        val cid = d.addComment("张三", "这页数字要更新")!!
        org.junit.Assert.assertTrue(d.replyComment(cid, "张三", "已更新"))
        assertEquals(1, d.comments().single().slide)
        val n = d.setFooter(all = true, number = true, footer = true, text = "季度复盘", hideOnTitle = true)
        assertEquals(3, n)
        org.junit.Assert.assertFalse(d.footerInfo(0).number)
        org.junit.Assert.assertTrue(d.footerInfo(2).number && d.footerInfo(2).footer)
        assertEquals("季度复盘", d.footerInfo(2).text)
        java.io.File(dir, "out/review-sample-zh-edited.pptx").writeBytes(d.serialize())
    }
}

private object XlsxWorkbookProbe {
    fun input(bytes: ByteArray, row: Int, col: Int): String = io.embodit.spatialoffice.core.xlsx.XlsxWorkbook.open(bytes).cell(0, row, col).input
}
