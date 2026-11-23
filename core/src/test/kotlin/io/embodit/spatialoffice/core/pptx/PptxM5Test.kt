package io.embodit.spatialoffice.core.pptx

import io.embodit.spatialoffice.core.docx.PackageAsserts.assertAllPartsIdentical
import io.embodit.spatialoffice.core.docx.PackageAsserts.assertConsistent
import io.embodit.spatialoffice.core.docx.PackageAsserts.assertOnlyRangeChanged
import io.embodit.spatialoffice.core.docx.PackageAsserts.changedParts
import io.embodit.spatialoffice.core.docx.PackageAsserts.fixture
import io.embodit.spatialoffice.core.docx.PackageAsserts.parts
import io.embodit.spatialoffice.core.docx.PackageAsserts.text
import io.embodit.spatialoffice.core.pkg.ContentTypes
import io.embodit.spatialoffice.core.pkg.OoxmlPackage
import io.embodit.spatialoffice.core.pkg.Rels
import io.embodit.spatialoffice.core.pkg.Xml
import io.embodit.spatialoffice.core.review.CommentKind
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * v0.7.0 M5: p33 comments on slides (threaded comments written, old-style comments kept), p32 slide number and footer.
 * Saved files go to core/build/m5-pptx/ for tools/verify/m5_pptx_verify.py.
 */
class PptxM5Test {
    private val types = "[Content_Types].xml"
    private val presRels = "ppt/_rels/presentation.xml.rels"
    private val authors = "ppt/authors.xml"
    private val oldAuthors = "ppt/commentAuthors.xml"
    private val oldPart = "ppt/comments/comment1.xml"
    private val relOld = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    private val ns = "xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\""
    private val decl = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
    private val day = "2026-11-22T10:00:00Z"

    private fun write(name: String, bytes: ByteArray) {
        val dir = File("build/m5-pptx").apply { mkdirs() }
        File(dir, name).writeBytes(bytes)
    }

    private fun slidePart(i: Int) = "ppt/slides/slide$i.xml"
    private fun slideRels(i: Int) = "ppt/slides/_rels/slide$i.xml.rels"

    /** The comment parts (resolved names) each slide part points at. */
    private fun commentPartsBySlide(bytes: ByteArray): Map<String, List<String>> {
        val pkg = OoxmlPackage.read(bytes)
        return pkg.names().filter { Regex("ppt/slides/slide\\d+\\.xml").matches(it) }.associateWith { s ->
            Rels.of(pkg, s).filter { it.type.endsWith("/comments") }.map { OoxmlPackage.resolve(s, it.target) }
        }
    }

    private fun assertNoSharedCommentPart(bytes: ByteArray) {
        val all = commentPartsBySlide(bytes).values.flatten()
        assertEquals("a comment part belongs to one slide", all.size, all.toSet().size)
    }

    /** The deck fixture with two old-style comments (two authors) on slide 2, the way older files carry them. */
    private fun withOldComments(): ByteArray {
        val pkg = OoxmlPackage.read(fixture("pptx-deck.pptx"))
        pkg.putText(
            oldAuthors,
            "$decl<p:cmAuthorLst $ns xmlns:p=\"http://schemas.openxmlformats.org/presentationml/2006/main\">" +
                "<p:cmAuthor id=\"0\" name=\"Li Na\" initials=\"LN\" lastIdx=\"1\" clrIdx=\"0\"/><p:cmAuthor id=\"1\" name=\"陈晨\" initials=\"陈\" lastIdx=\"1\" clrIdx=\"1\"/></p:cmAuthorLst>",
        )
        pkg.putText(
            oldPart,
            "$decl<p:cmLst $ns xmlns:p=\"http://schemas.openxmlformats.org/presentationml/2006/main\">" +
                "<p:cm authorId=\"0\" dt=\"2024-03-05T09:14:16.444\" idx=\"1\"><p:pos x=\"2880\" y=\"1620\"/><p:text>Check the totals &amp; units</p:text></p:cm>" +
                "<p:cm authorId=\"1\" dt=\"2024-03-06T10:00:00.000\" idx=\"1\"><p:pos x=\"10\" y=\"10\"/><p:text>图表需要更新</p:text></p:cm></p:cmLst>",
        )
        Rels.add(pkg, "ppt/presentation.xml", "$relOld/commentAuthors", "commentAuthors.xml")
        Rels.add(pkg, slidePart(2), "$relOld/comments", "../comments/comment1.xml")
        ContentTypes.addOverride(pkg, oldAuthors, "application/vnd.openxmlformats-officedocument.presentationml.commentAuthors+xml")
        ContentTypes.addOverride(pkg, oldPart, "application/vnd.openxmlformats-officedocument.presentationml.comments+xml")
        return pkg.write()
    }

    /** The old-style authors part with one author (id 0), for fixtures that carry old-style comments. */
    private fun addOldAuthor(pkg: OoxmlPackage) {
        pkg.putText(oldAuthors, "$decl<p:cmAuthorLst $ns xmlns:p=\"http://schemas.openxmlformats.org/presentationml/2006/main\"><p:cmAuthor id=\"0\" name=\"Li Na\" initials=\"LN\" lastIdx=\"1\" clrIdx=\"0\"/></p:cmAuthorLst>")
        Rels.add(pkg, "ppt/presentation.xml", "$relOld/commentAuthors", "commentAuthors.xml")
        ContentTypes.addOverride(pkg, oldAuthors, "application/vnd.openxmlformats-officedocument.presentationml.commentAuthors+xml")
    }

    private val a1 = "{AAAAAAAA-0000-4000-8000-000000000001}"
    private val a2 = "{BBBBBBBB-0000-4000-8000-000000000002}"
    private val t1 = "{11111111-1111-4111-8111-111111111111}"
    private val t2 = "{22222222-2222-4222-8222-222222222222}"
    private val r1 = "{33333333-3333-4333-8333-333333333331}"
    private val r2 = "{33333333-3333-4333-8333-333333333332}"
    private val modernPart = "ppt/comments/modernComment_102_0.xml"

    /**
     * The deck fixture with threaded comments on slide 3, written by hand the way a desktop program stores them: two
     * authors, an open thread pinned to the slide with two replies, and a resolved thread anchored to an object.
     */
    private fun withThreads(): ByteArray {
        val src = fixture("pptx-deck.pptx")
        val shape = PptxDeck.open(src).slides[2].shapes.first()
        val pkg = OoxmlPackage.read(src)
        val p188 = SlideComments.NS_P188
        val pc = "xmlns:pc=\"${SlideComments.NS_PC}\""
        fun body(vararg lines: String) = "<p188:txBody><a:bodyPr/><a:lstStyle/>" + lines.joinToString("") { "<a:p><a:r><a:rPr lang=\"en-US\" dirty=\"0\"/><a:t>$it</a:t></a:r></a:p>" } + "</p188:txBody>"
        pkg.putText(
            authors,
            "$decl<p188:authorLst $ns xmlns:p188=\"$p188\">\n  <p188:author id=\"$a1\" name=\"Zhang Wei\" initials=\"ZW\" userId=\"S::zhang@example.com::1\" providerId=\"AD\"/>\n" +
                "  <p188:author id=\"$a2\" name=\"刘洋\" initials=\"刘\" userId=\"刘洋\" providerId=\"None\"/>\n</p188:authorLst>",
        )
        pkg.putText(
            modernPart,
            "$decl<p188:cmLst $ns xmlns:p188=\"$p188\">\n" +
                "<p188:cm id=\"$t1\" authorId=\"$a1\" created=\"2025-01-02T03:04:05.678\"><pc:sldMkLst $pc><pc:docMk/><pc:sldMk cId=\"0\" sldId=\"258\"/></pc:sldMkLst>" +
                "<p188:pos x=\"6096000\" y=\"1714500\"/><p188:replyLst>" +
                "<p188:reply id=\"$r1\" authorId=\"$a2\" created=\"2025-01-02T04:00:00.000\">${body("同意")}</p188:reply>" +
                "<p188:reply id=\"$r2\" authorId=\"$a1\" created=\"2025-01-03T08:30:00.000\">${body("Will fix")}</p188:reply>" +
                "</p188:replyLst>${body("Is this the final picture?", "Second line")}</p188:cm>\n" +
                "<p188:cm id=\"$t2\" authorId=\"$a2\" created=\"2025-01-05T00:00:00.000\" status=\"resolved\">" +
                "<ac:deMkLst xmlns:ac=\"http://schemas.microsoft.com/office/drawing/2013/main/command\"><pc:docMk $pc/><pc:sldMk $pc cId=\"0\" sldId=\"258\"/><ac:spMk id=\"${shape.id}\" creationId=\"{00000000-0000-0000-0000-000000000000}\"/></ac:deMkLst>" +
                "<p188:pos x=\"121920\" y=\"68580\"/>${body("已处理")}</p188:cm>\n</p188:cmLst>",
        )
        Rels.add(pkg, "ppt/presentation.xml", SlideComments.REL_AUTHORS, "authors.xml")
        val rid = Rels.add(pkg, slidePart(3), SlideComments.REL_COMMENTS, "../comments/modernComment_102_0.xml")
        ContentTypes.addOverride(pkg, authors, SlideComments.CT_AUTHORS)
        ContentTypes.addOverride(pkg, modernPart, SlideComments.CT_COMMENTS)
        val sx = pkg.text(slidePart(3))!!
        pkg.putText(
            slidePart(3),
            sx.replace("</p:sld>", "<p:extLst><p:ext uri=\"${SlideComments.EXT_COMMENT_REL}\"><p188:commentRel xmlns:p188=\"$p188\" r:id=\"$rid\"/></p:ext></p:extLst></p:sld>"),
        )
        return pkg.write()
    }

    // ---------------------------------------------------------------------------------------------- p33 comments

    @Test fun acceptanceCommentAndReplyOnSlideTwoBesideOldStyleComments() {
        val before = withOldComments()
        assertConsistent(before)
        write("acceptance-comments-before.pptx", before)
        val d = PptxDeck.open(before)
        assertEquals(2, d.comments().size)
        val id = d.addComment(1, 0.5f, 0.25f, "王伟", "请核对第二季度的数据", day)
        val rid = d.replyComment(id, "Li Na", "Done, see the new chart", "2026-11-22T18:30:00+08:00")
        val after = d.save()
        write("acceptance-comments.pptx", after)
        assertConsistent(after)

        val part = "ppt/comments/modernComment_101_0.xml"
        assertEquals(setOf(types, presRels, authors, part, slideRels(2), slidePart(2)), changedParts(before, after))
        // the old-style comments and their authors are byte-identical
        assertArrayEquals(parts(before).getValue(oldPart), parts(after).getValue(oldPart))
        assertArrayEquals(parts(before).getValue(oldAuthors), parts(after).getValue(oldAuthors))

        val cx = text(after, part)
        assertTrue(cx.startsWith("$decl<p188:cmLst "))
        assertTrue(cx.contains("<p188:cm id=\"$id\""))
        assertTrue(cx.contains("created=\"2026-11-22T10:00:00.000\""))
        assertTrue(cx.contains("<pc:sldMk cId=\"0\" sldId=\"257\"/>"))
        assertTrue(cx.contains("<p188:pos x=\"6096000\" y=\"1714500\"/>"))
        assertTrue(cx.contains("<a:t>请核对第二季度的数据</a:t>"))
        assertTrue(cx.contains("<p188:reply id=\"$rid\""))
        assertTrue(cx.contains("created=\"2026-11-22T10:30:00.000\"")) // 18:30 +08:00 as UTC
        assertTrue(cx.contains("<a:t>Done, see the new chart</a:t>"))
        // schema order inside the comment: anchor, position, replies, text
        val cm = Xml.children(cx, Xml.root(cx)!!).single()
        assertEquals(listOf("pc:sldMkLst", "p188:pos", "p188:replyLst", "p188:txBody"), Xml.children(cx, cm).map { it.name })

        val ax = text(after, authors)
        assertTrue(ax.contains("name=\"王伟\" initials=\"王\" userId=\"王伟\" providerId=\"None\""))
        assertTrue(ax.contains("name=\"Li Na\" initials=\"LN\""))
        val pkg = OoxmlPackage.read(after)
        val rel = Rels.of(pkg, slidePart(2)).single { it.type == SlideComments.REL_COMMENTS }
        assertEquals(part, OoxmlPackage.resolve(slidePart(2), rel.target))
        assertTrue(Rels.of(pkg, slidePart(2)).any { it.type == "$relOld/comments" && it.target == "../comments/comment1.xml" })
        assertEquals(authors, Rels.of(pkg, "ppt/presentation.xml").single { it.type == SlideComments.REL_AUTHORS }.let { OoxmlPackage.resolve("ppt/presentation.xml", it.target) })
        val ct = text(after, types)
        assertTrue(ct.contains("<Override PartName=\"/$part\" ContentType=\"application/vnd.ms-powerpoint.comments+xml\"/>"))
        assertTrue(ct.contains("<Override PartName=\"/$authors\" ContentType=\"application/vnd.ms-powerpoint.authors+xml\"/>"))
        // the slide: only the extension-list entry naming the comment part was added, at the very end
        val sx = text(after, slidePart(2))
        val ext = "<p:extLst><p:ext uri=\"{6950BFC3-D8DA-4A85-94F7-54DA5524770B}\"><p188:commentRel xmlns:p188=\"${SlideComments.NS_P188}\" r:id=\"${rel.id}\"/></p:ext></p:extLst>"
        assertEquals(text(before, slidePart(2)).replace("</p:sld>", "$ext</p:sld>"), sx)

        val back = PptxDeck.open(after)
        val on2 = back.comments(1)
        assertEquals(listOf(CommentKind.NOTE, CommentKind.NOTE, CommentKind.THREAD), on2.map { it.kind })
        val th = on2.last()
        assertEquals(id, th.id)
        assertEquals("王伟", th.author)
        assertEquals("请核对第二季度的数据", th.text)
        assertEquals(day, th.date)
        assertEquals(1, th.slide)
        assertEquals(0.5f, th.x, 0.0001f)
        assertEquals(0.25f, th.y, 0.0001f)
        assertEquals("", th.quote)
        assertFalse(th.resolved)
        assertEquals(listOf(rid), th.replies.map { it.id })
        assertEquals("Li Na", th.replies[0].author)
        assertEquals("2026-11-22T10:30:00Z", th.replies[0].date)
        assertEquals(2, th.count)
        assertEquals(on2, back.comments())
        assertAllPartsIdentical(after, back.save())
    }

    @Test fun aDeckWithoutCommentsGetsItsPartsFreshAndOnlyOnce() {
        val before = fixture("pptx-deck.pptx")
        val d = PptxDeck.open(before)
        assertTrue(d.comments().isEmpty())
        assertTrue(d.comments(3).isEmpty())
        assertFalse(d.dirty)
        assertArrayEquals(before, d.save())

        val first = d.addComment(0, 0f, 0f, "Ana", "One", day)
        assertTrue(d.dirty)
        val s1 = d.save()
        assertConsistent(s1)
        val part = "ppt/comments/modernComment_100_0.xml"
        assertEquals(setOf(types, presRels, authors, part, slideRels(1), slidePart(1)), changedParts(before, s1))

        // a second comment by the same author on the same slide: only the comment part changes, and only by the new comment
        val second = d.addComment(0, 1f, 1f, "Ana", "Two\nlines", day)
        val s2 = d.save()
        assertEquals(setOf(part), changedParts(s1, s2))
        val x1 = text(s1, part); val x2 = text(s2, part)
        val added = assertOnlyRangeChanged(x1, x2, x1.length - "</p188:cmLst>".length, x1.length - "</p188:cmLst>".length)
        assertTrue(added.startsWith("<p188:cm id=\"$second\""))
        assertTrue(added.contains("<a:p><a:r><a:t>Two</a:t></a:r></a:p><a:p><a:r><a:t>lines</a:t></a:r></a:p>"))
        assertTrue(added.contains("<p188:pos x=\"12192000\" y=\"6858000\"/>"))

        // another author, another slide: the authors part gains one entry; slide 1 and its comment part stay as they are
        val third = d.addComment(4, 0.1f, 0.9f, "Bo & Co <x>", "Three", day)
        val s3 = d.save()
        assertConsistent(s3)
        assertEquals(setOf(types, authors, "ppt/comments/modernComment_104_0.xml", slideRels(5), slidePart(5)), changedParts(s2, s3))
        assertTrue(text(s3, authors).contains("name=\"Bo &amp; Co &lt;x&gt;\" initials=\"B&amp;C\""))
        assertEquals(2, Regex("<p188:author ").findAll(text(s3, authors)).count())

        val back = PptxDeck.open(s3)
        assertEquals(listOf(first, second, third), back.comments().map { it.id })
        assertEquals(listOf(0, 0, 4), back.comments().map { it.slide })
        assertEquals("Two\nlines", back.comments()[1].text)
        assertEquals("Bo & Co <x>", back.comments()[2].author)
        assertNoSharedCommentPart(s3)
        write("comments-fresh.pptx", s3)
    }

    @Test fun aNewDeckTakesComments() {
        val d = PptxDeck.blank()
        val id = d.addComment(0, 0.3f, 0.6f, "Ana", "First", day)
        d.replyComment(id, "Ana", "Second", day)
        d.setCommentResolved(id, true)
        val saved = d.save()
        assertConsistent(saved)
        val c = PptxDeck.open(saved).comments().single()
        assertTrue(c.resolved)
        assertEquals("Second", c.replies.single().text)
        write("comments-new-deck.pptx", saved)
    }

    @Test fun oldStyleCommentsAreReadEditedAndDeletedButNeverThreaded() {
        val before = withOldComments()
        val d = PptxDeck.open(before)
        val (c1, c2) = d.comments()
        assertEquals("note:0:1", c1.id)
        assertEquals("note:1:1", c2.id)
        assertEquals(CommentKind.NOTE, c1.kind)
        assertEquals("Li Na", c1.author)
        assertEquals("陈晨", c2.author)
        assertEquals("Check the totals & units", c1.text)
        assertEquals("2024-03-05T09:14:16.444Z", c1.date)
        assertEquals("2024-03-06T10:00:00Z", c2.date)
        assertEquals(1, c1.slide)
        // 2880 × 1620 of 576 units to the inch = 5 in × 2.8125 in on a 13.33 in × 7.5 in slide
        assertEquals(0.375f, c1.x, 0.0001f)
        assertEquals(0.375f, c1.y, 0.0001f)
        assertTrue(c1.replies.isEmpty())
        assertFalse(c1.resolved)
        assertFalse(d.dirty)
        assertArrayEquals(before, d.save())

        for (op in listOf<() -> Unit>({ d.replyComment(c1.id, "x", "y", day) }, { d.setCommentResolved(c1.id, true) })) {
            try { op(); fail("old-style comments take no replies and cannot be resolved") } catch (_: IllegalStateException) {}
        }
        try { d.editComment("nope", "x"); fail() } catch (_: IllegalArgumentException) {}
        assertFalse(d.dirty)

        // editing: only the text of that comment
        d.editComment(c2.id, "图表已更新 <v2>")
        val edited = d.save()
        assertEquals(setOf(oldPart), changedParts(before, edited))
        val o = text(before, oldPart)
        val at = o.indexOf("<p:text>图表需要更新</p:text>")
        assertEquals("<p:text>图表已更新 &lt;v2&gt;</p:text>", assertOnlyRangeChanged(o, text(edited, oldPart), at, at + "<p:text>图表需要更新</p:text>".length))
        d.editComment(c2.id, "图表已更新 <v2>") // the same text again: nothing to save
        assertFalse(d.dirty)

        // deleting one of two: the other stays as it was
        d.deleteComment(c1.id)
        val one = d.save()
        assertEquals(setOf(oldPart), changedParts(edited, one))
        assertEquals(text(edited, oldPart).replace(Regex("<p:cm authorId=\"0\".*?</p:cm>"), ""), text(one, oldPart))
        assertEquals(listOf(c2.id), PptxDeck.open(one).comments().map { it.id })

        // deleting the last: the part, the slide's relationship and the content type go; the authors list stays
        d.deleteComment(c2.id)
        val none = d.save()
        assertConsistent(none)
        assertNull(OoxmlPackage.read(none).bytes(oldPart))
        assertFalse(text(none, types).contains("comment1.xml"))
        assertEquals(text(fixture("pptx-deck.pptx"), slideRels(2)), text(none, slideRels(2)))
        assertArrayEquals(parts(before).getValue(oldAuthors), parts(none).getValue(oldAuthors))
        assertArrayEquals(parts(before).getValue(slidePart(2)), parts(none).getValue(slidePart(2)))
        assertTrue(PptxDeck.open(none).comments().isEmpty())
    }

    @Test fun anOldStyleAnswerGoesWithTheCommentItAnswers() {
        val pkg = OoxmlPackage.read(withOldComments())
        val x = pkg.text(oldPart)!!
        val answer = "<p:cm authorId=\"1\" dt=\"2024-03-07T08:00:00.000\" idx=\"2\"><p:pos x=\"2880\" y=\"1620\"/><p:text>Totals fixed</p:text><p:extLst>" +
            "<p:ext uri=\"{C676402C-5697-4E1C-873F-D02D1690AC5C}\"><p15:threadingInfo xmlns:p15=\"http://schemas.microsoft.com/office/powerpoint/2012/main\" timeZoneBias=\"-480\">" +
            "<p15:parentCm authorId=\"0\" idx=\"1\"/></p15:threadingInfo></p:ext></p:extLst></p:cm>"
        pkg.putText(oldPart, x.replace("</p:cmLst>", "$answer</p:cmLst>"))
        val before = pkg.write()
        val d = PptxDeck.open(before)
        assertEquals(listOf("note:0:1", "note:1:1", "note:1:2"), d.comments().map { it.id })
        assertTrue(d.comments().all { it.kind == CommentKind.NOTE && it.replies.isEmpty() })
        // editing the answer keeps its extension list
        d.editComment("note:1:2", "Totals fixed twice")
        assertEquals(text(before, oldPart).replace("Totals fixed", "Totals fixed twice"), text(d.save(), oldPart))
        d.deleteComment("note:0:1")
        assertEquals(listOf("note:1:1"), d.comments().map { it.id })
        assertEquals(x.replace(Regex("<p:cm authorId=\"0\".*?</p:cm>"), ""), text(d.save(), oldPart))
        assertConsistent(d.save())
    }

    @Test fun threadsOfTwoAuthorsWithRepliesAndAResolvedOneAreRead() {
        val before = withThreads()
        assertConsistent(before)
        val d = PptxDeck.open(before)
        val all = d.comments()
        assertEquals(listOf(t1, t2), all.map { it.id })
        val (open, done) = all
        assertEquals(CommentKind.THREAD, open.kind)
        assertEquals("Zhang Wei", open.author)
        assertEquals("2025-01-02T03:04:05.678Z", open.date)
        assertEquals("Is this the final picture?\nSecond line", open.text)
        assertFalse(open.resolved)
        assertEquals(2, open.slide)
        assertEquals(0.5f, open.x, 0.0001f)
        assertEquals(0.25f, open.y, 0.0001f)
        assertEquals(listOf(r1, r2), open.replies.map { it.id })
        assertEquals(listOf("刘洋", "Zhang Wei"), open.replies.map { it.author })
        assertEquals(listOf("同意", "Will fix"), open.replies.map { it.text })
        assertEquals("2025-01-02T04:00:00Z", open.replies[0].date)
        assertEquals(3, open.count)

        assertTrue(done.resolved)
        assertEquals("刘洋", done.author)
        assertEquals("已处理", done.text)
        // anchored to an object: the position counts from that object's top-left corner
        val sh = d.slides[2].shapes.first()
        assertEquals((sh.x + 121920).toFloat() / d.widthEmu, done.x, 0.0001f)
        assertEquals((sh.y + 68580).toFloat() / d.heightEmu, done.y, 0.0001f)

        assertEquals(all, d.comments(2))
        assertTrue(d.comments(1).isEmpty())
        assertFalse(d.dirty)
        assertArrayEquals(before, d.save())
        write("comments-threads-untouched.pptx", d.save())
    }

    @Test fun resolveReopenEditAndReplyChangeOnlyTheirOwnRange() {
        val before = withThreads()
        val o = text(before, modernPart)
        val d = PptxDeck.open(before)

        // resolve: one attribute on the thread's cm; asked through a reply id it is the same thread
        d.setCommentResolved(r1, true)
        val resolved = d.save()
        assertEquals(setOf(modernPart), changedParts(before, resolved))
        val tag = "<p188:cm id=\"$t1\" authorId=\"$a1\" created=\"2025-01-02T03:04:05.678\">"
        val at = o.indexOf(tag)
        assertEquals(tag.dropLast(1) + " status=\"resolved\">", assertOnlyRangeChanged(o, text(resolved, modernPart), at, at + tag.length))
        assertTrue(PptxDeck.open(resolved).comments().all { it.resolved })
        d.setCommentResolved(t1, true) // already so
        assertFalse(d.dirty)
        // reopen: the original bytes are back
        d.setCommentResolved(t1, false)
        assertArrayEquals(parts(before).getValue(modernPart), parts(d.save()).getValue(modernPart))
        d.setCommentResolved(t2, false)
        val reopened = d.save()
        assertEquals(o.replace(" status=\"resolved\"", ""), text(reopened, modernPart))
        assertTrue(PptxDeck.open(reopened).comments().none { it.resolved })

        // edit the root: its paragraphs only, the first run's formatting kept; the replies are untouched
        val d2 = PptxDeck.open(before)
        d2.editComment(t1, "Final picture? <yes>")
        val e1 = d2.save()
        assertEquals(setOf(modernPart), changedParts(before, e1))
        val oldParas = "<a:p><a:r><a:rPr lang=\"en-US\" dirty=\"0\"/><a:t>Is this the final picture?</a:t></a:r></a:p><a:p><a:r><a:rPr lang=\"en-US\" dirty=\"0\"/><a:t>Second line</a:t></a:r></a:p>"
        val pAt = o.indexOf(oldParas)
        assertTrue(pAt > 0)
        assertEquals(
            "<a:p><a:r><a:rPr lang=\"en-US\" dirty=\"0\"/><a:t>Final picture? &lt;yes&gt;</a:t></a:r></a:p>",
            assertOnlyRangeChanged(o, text(e1, modernPart), pAt, pAt + oldParas.length),
        )
        // edit a reply
        d2.editComment(r2, "已修复")
        val e2 = d2.save()
        val x1 = text(e1, modernPart)
        val rAt = x1.indexOf("<a:t>Will fix</a:t>")
        assertEquals("<a:t>已修复</a:t>", assertOnlyRangeChanged(x1, text(e2, modernPart), rAt, rAt + "<a:t>Will fix</a:t>".length))
        d2.editComment(r2, "已修复")
        assertFalse(d2.dirty)
        // a reply to the resolved thread (it has no reply list yet): the list goes between the position and the text
        val nr = d2.replyComment(t2, "刘洋", "补充说明", day)
        val e3 = d2.save()
        assertEquals(setOf(modernPart), changedParts(e2, e3)) // the author is known: the authors part is untouched
        val x3 = text(e3, modernPart)
        val cm2 = Xml.children(x3, Xml.root(x3)!!)[1]
        assertEquals(listOf("ac:deMkLst", "p188:pos", "p188:replyLst", "p188:txBody"), Xml.children(x3, cm2).map { it.name })
        val back = PptxDeck.open(e3).comments()
        assertEquals("Final picture? <yes>", back[0].text)
        assertEquals(listOf("同意", "已修复"), back[0].replies.map { it.text })
        assertEquals(listOf(nr), back[1].replies.map { it.id })
        assertEquals("补充说明", back[1].replies[0].text)
        assertTrue(back[1].resolved)
        assertConsistent(e3)
        write("comments-threads-edited.pptx", e3)
    }

    @Test fun deletingARepyAThreadAndTheLastThread() {
        val before = withThreads()
        val o = text(before, modernPart)
        val d = PptxDeck.open(before)

        d.deleteComment(r1)
        val s1 = d.save()
        assertEquals(setOf(modernPart), changedParts(before, s1))
        assertEquals(o.replace(Regex("<p188:reply id=\"" + Regex.escape(r1) + "\".*?</p188:reply>"), ""), text(s1, modernPart))
        assertEquals(listOf(r2), d.comments()[0].replies.map { it.id })
        // the last reply takes the reply list with it
        d.deleteComment(r2)
        val s2 = d.save()
        assertEquals(o.replace(Regex("<p188:replyLst>.*?</p188:replyLst>"), ""), text(s2, modernPart))
        assertTrue(d.comments()[0].replies.isEmpty())

        // a thread goes with its replies; the other thread stays byte for byte
        val d2 = PptxDeck.open(before)
        d2.deleteComment(t1)
        val s3 = d2.save()
        assertEquals(setOf(modernPart), changedParts(before, s3))
        assertEquals(o.replace(Regex("<p188:cm id=\"" + Regex.escape(t1) + "\".*?</p188:cm>"), ""), text(s3, modernPart))
        assertEquals(listOf(t2), PptxDeck.open(s3).comments().map { it.id })
        try { d2.editComment(r1, "x"); fail("the replies went with the thread") } catch (_: IllegalArgumentException) {}

        // the last thread: the part, its relationship, its content type and the slide's extension entry go; the slide is as it was without comments
        d2.deleteComment(t2)
        val s4 = d2.save()
        assertConsistent(s4)
        val plain = fixture("pptx-deck.pptx")
        assertNull(OoxmlPackage.read(s4).bytes(modernPart))
        assertFalse(text(s4, types).contains("modernComment"))
        assertEquals(text(plain, slidePart(3)), text(s4, slidePart(3)))
        assertEquals(text(plain, slideRels(3)), text(s4, slideRels(3)))
        assertArrayEquals(parts(before).getValue(authors), parts(s4).getValue(authors))
        assertTrue(PptxDeck.open(s4).comments().isEmpty())
        write("comments-all-deleted.pptx", s4)
    }

    @Test fun idsAreUniqueAndTheSameAfterSaveAndReopen() {
        val pkg = OoxmlPackage.read(withThreads())
        // old-style comments on the same slide and on another one, with the same author / index pair twice
        val old = "$decl<p:cmLst $ns xmlns:p=\"http://schemas.openxmlformats.org/presentationml/2006/main\"><p:cm authorId=\"0\" dt=\"2024-03-05T09:14:16.444\" idx=\"1\"><p:pos x=\"10\" y=\"10\"/><p:text>A</p:text></p:cm></p:cmLst>"
        addOldAuthor(pkg)
        for ((n, slide) in listOf(1 to 3, 2 to 5)) {
            pkg.putText("ppt/comments/comment$n.xml", old)
            Rels.add(pkg, slidePart(slide), "$relOld/comments", "../comments/comment$n.xml")
            ContentTypes.addOverride(pkg, "ppt/comments/comment$n.xml", "application/vnd.openxmlformats-officedocument.presentationml.comments+xml")
        }
        val before = pkg.write()
        val d = PptxDeck.open(before)
        val id = d.addComment(2, 0.2f, 0.2f, "Zhang Wei", "one more", day)
        fun ids(x: PptxDeck) = x.comments().flatMap { c -> listOf(c.id) + c.replies.map { it.id } }
        val live = ids(d)
        assertEquals(live.size, live.toSet().size)
        assertEquals(7, live.size)
        assertTrue(id in live)
        assertEquals(listOf("note:0:1", "note:0:1~2"), d.comments().filter { it.kind == CommentKind.NOTE }.map { it.id })
        // slide 3 keeps both generations side by side
        assertEquals(listOf(CommentKind.THREAD, CommentKind.THREAD, CommentKind.THREAD, CommentKind.NOTE), d.comments(2).map { it.kind })
        val saved = d.save()
        assertEquals(live, ids(PptxDeck.open(saved)))
        assertArrayEquals(parts(before).getValue("ppt/comments/comment1.xml"), parts(saved).getValue("ppt/comments/comment1.xml"))
        assertEquals(setOf(modernPart), changedParts(before, saved)) // author and part were there
        assertConsistent(saved)
        write("comments-both-kinds.pptx", saved)
    }

    @Test fun movingDuplicatingAndDeletingSlidesKeepsCommentsConsistent() {
        val pkg = OoxmlPackage.read(withThreads())
        pkg.putText(oldPart, "$decl<p:cmLst $ns xmlns:p=\"http://schemas.openxmlformats.org/presentationml/2006/main\"><p:cm authorId=\"0\" dt=\"2024-03-05T09:14:16.444\" idx=\"1\"><p:pos x=\"10\" y=\"10\"/><p:text>Old one</p:text></p:cm></p:cmLst>")
        Rels.add(pkg, slidePart(3), "$relOld/comments", "../comments/comment1.xml")
        ContentTypes.addOverride(pkg, oldPart, "application/vnd.openxmlformats-officedocument.presentationml.comments+xml")
        addOldAuthor(pkg)
        val before = pkg.write()
        assertConsistent(before)
        val d = PptxDeck.open(before)
        assertEquals(listOf(2, 2, 2), d.comments().map { it.slide })

        // move: the comments go with their slide, no comment part is touched
        d.moveSlide(2, 0)
        assertEquals(listOf(0, 0, 0), d.comments().map { it.slide })
        assertEquals(3, d.comments(0).size)
        assertTrue(d.comments(2).isEmpty())
        val moved = d.save()
        assertEquals(setOf("ppt/presentation.xml"), changedParts(before, moved))
        assertEquals(listOf(0, 0, 0), PptxDeck.open(moved).comments().map { it.slide })

        // duplicate: the copy has no comments, no relationship to the original's comment parts, no extension entry
        val copy = d.duplicateSlide(0)
        assertEquals(1, copy)
        assertEquals(3, d.comments().size)
        assertTrue(d.comments(copy).isEmpty())
        val dup = d.save()
        assertConsistent(dup)
        assertNoSharedCommentPart(dup)
        val bySlide = commentPartsBySlide(dup)
        assertEquals(listOf(modernPart, oldPart), bySlide.getValue(slidePart(3)))
        val copyPart = bySlide.keys.single { it !in parts(before).keys }
        assertTrue(bySlide.getValue(copyPart).isEmpty())
        assertFalse(text(dup, copyPart).contains("commentRel"))
        assertEquals(text(fixture("pptx-deck.pptx"), slidePart(3)), text(dup, copyPart)) // the slide as it is without its comment entry
        assertArrayEquals(parts(before).getValue(slidePart(3)), parts(dup).getValue(slidePart(3)))
        assertArrayEquals(parts(before).getValue(modernPart), parts(dup).getValue(modernPart))
        // a comment on the copy gets a part of its own
        val onCopy = d.addComment(copy, 0.5f, 0.5f, "刘洋", "copy", day)
        val dup2 = d.save()
        assertConsistent(dup2)
        assertNoSharedCommentPart(dup2)
        assertEquals(listOf(onCopy), PptxDeck.open(dup2).comments(copy).map { it.id })
        write("comments-slide-ops.pptx", dup2)

        // delete the original: both of its comment parts, their content types and relationships go; the copy keeps its own
        d.deleteSlide(0)
        val gone = d.save()
        assertConsistent(gone)
        val left = OoxmlPackage.read(gone)
        assertNull(left.bytes(modernPart))
        assertNull(left.bytes(oldPart))
        assertNull(left.bytes(slideRels(3)))
        assertFalse(text(gone, types).contains("modernComment_102_0") || text(gone, types).contains("comment1.xml"))
        assertNotNull(left.bytes(authors))
        assertEquals(listOf(onCopy), d.comments().map { it.id })
        assertEquals(listOf(0), d.comments().map { it.slide })
        assertEquals(listOf(onCopy), PptxDeck.open(gone).comments().map { it.id })
        write("comments-slide-deleted.pptx", gone)
    }

    @Test fun commentDatesGoToTheFileFormAndBack() {
        assertEquals("2026-11-22T10:00:00.000", CommentDates.toFile("2026-11-22T10:00:00Z"))
        assertEquals("2026-11-22T02:00:00.250", CommentDates.toFile("2026-11-22T10:00:00.25+08:00"))
        assertEquals("2026-11-22T10:00:00.000", CommentDates.toFile("2026-11-22T10:00:00"))
        assertTrue(Regex("\\d{4}-\\d\\d-\\d\\dT\\d\\d:\\d\\d:\\d\\d\\.\\d{3}").matches(CommentDates.toFile("")))
        assertEquals("2026-11-22T10:00:00Z", CommentDates.fromFile("2026-11-22T10:00:00.000"))
        assertEquals("2026-11-22T10:00:00.25Z", CommentDates.fromFile("2026-11-22T10:00:00.250"))
        assertEquals("2026-11-22T10:00:00Z", CommentDates.fromFile("2026-11-22T10:00"))
        assertEquals("2026-11-22T02:00:00Z", CommentDates.fromFile("2026-11-22T10:00:00+08:00"))
        assertEquals("", CommentDates.fromFile(null))
        assertEquals("yesterday", CommentDates.fromFile("yesterday"))
    }

    // ---------------------------------------------------------------------------------------------- p32 number and footer

    private fun phTypes(xml: String): List<String> = Regex("<p:ph\\b[^>]*?type=\"(\\w+)\"").findAll(xml).map { it.groupValues[1] }.toList()

    @Test fun acceptanceNumberAndFooterOnEverySlideButTheTitleSlide() {
        val before = fixture("pptx-deck.pptx")
        val d = PptxDeck.open(before)
        assertEquals(SlideFooter(false, false, ""), d.footerInfo(1))
        val n = d.setFooter(null, number = true, footer = true, text = "季度复盘", hideOnTitle = true)
        assertEquals(6, n) // 7 slides, the first is on the title layout
        val after = d.save()
        write("acceptance-footer.pptx", after)
        assertConsistent(after)
        assertEquals((2..7).flatMap { listOf(slidePart(it)) }.toSet(), changedParts(before, after))

        val back = PptxDeck.open(after)
        assertEquals(SlideFooter(false, false, ""), back.footerInfo(0))
        for (i in 1..6) {
            assertEquals(SlideFooter(true, true, "季度复盘"), back.footerInfo(i))
            val x = text(after, slidePart(i + 1))
            // the two shapes were appended to the shape tree; everything else of the slide is as it was
            val o = text(before, slidePart(i + 1))
            val at = o.indexOf("</p:spTree>")
            val added = assertOnlyRangeChanged(o, x, at, at)
            val id = Regex("<p:cNvPr id=\"(\\d+)\"").findAll(o).maxOf { it.groupValues[1].toInt() } + 1
            assertTrue(added.startsWith("<p:sp><p:nvSpPr><p:cNvPr id=\"$id\" name=\"Footer Placeholder ${id - 1}\"/><p:cNvSpPr><a:spLocks noGrp=\"1\"/></p:cNvSpPr><p:nvPr><p:ph type=\"ftr\" sz=\"quarter\" idx=\"11\"/></p:nvPr></p:nvSpPr><p:spPr/>"))
            assertTrue(added.contains("<a:p><a:r><a:rPr lang=\"en-US\"/><a:t>季度复盘</a:t></a:r></a:p>"))
            assertTrue(added.contains("<p:cNvPr id=\"${id + 1}\" name=\"Slide Number Placeholder $id\"/>"))
            assertTrue(added.contains("<p:ph type=\"sldNum\" sz=\"quarter\" idx=\"12\"/></p:nvPr></p:nvSpPr><p:spPr/>"))
            assertTrue(Regex("<a:fld id=\"\\{[0-9A-F-]{36}\\}\" type=\"slidenum\"><a:rPr lang=\"en-US\"/><a:t>‹#›</a:t></a:fld>").containsMatchIn(added))
            assertEquals(2, Regex("<p:sp>").findAll(added).count())

            // the model: drawn as text shapes in the layout's boxes, marked, never the slide's title or body
            val shapes = back.slides[i].shapes
            val num = shapes.single { it.footerKind == FooterKind.NUMBER }
            val ftr = shapes.single { it.footerKind == FooterKind.FOOTER }
            assertEquals(ShapeKind.TEXT, num.kind)
            assertEquals(ShapeKind.TEXT, ftr.kind)
            assertEquals((i + 1).toString(), num.paras.single().text)
            assertEquals((i + 1).toString(), num.paras.single().runs!!.joinToString("") { it.text })
            assertFalse(num.editable)
            assertEquals("季度复盘", ftr.paras.single().text)
            assertTrue(num.w > 0 && num.h > 0 && ftr.w > 0 && ftr.h > 0)
            assertTrue("the footer row is at the bottom", num.y > back.heightEmu * 3 / 4 && ftr.y > back.heightEmu * 3 / 4)
            assertTrue("the number is right of the footer", num.x > ftr.x)
            assertEquals(shapes.filter { it.footerKind == null }.map { it.kind }, PptxDeck.open(before).slides[i].shapes.map { it.kind })
        }
        assertTrue(back.slides[0].shapes.none { it.footerKind != null })
        assertEquals(PptxDeck.open(before).firstTitle(), back.firstTitle())
        // asking again changes nothing and leaves nothing to save
        assertEquals(0, back.setFooter(null, true, true, "季度复盘", true))
        assertFalse(back.dirty)
        assertArrayEquals(after, back.save())
    }

    @Test fun layoutsWithoutThosePlaceholdersGetBoxesAtTheBottomOfTheSlide() {
        val d = PptxDeck.blank()
        d.addSlide(0, SlideLayoutKind.TITLE_BODY)
        d.addSlide(1, SlideLayoutKind.BLANK)
        val before = d.save()
        val o = PptxDeck.open(before)
        assertEquals(2, o.setFooter(null, number = true, footer = true, text = "Quarterly review\n第二行", hideOnTitle = true))
        val after = o.save()
        write("footer-fallback.pptx", after)
        assertConsistent(after)
        assertEquals(setOf(slidePart(2), slidePart(3)), changedParts(before, after))
        val back = PptxDeck.open(after)
        val w = back.widthEmu; val h = back.heightEmu
        for (i in 1..2) {
            val x = text(after, slidePart(i + 1))
            assertTrue(x.contains("<p:ph type=\"ftr\" sz=\"quarter\" idx=\"11\"/>"))
            assertTrue(x.contains("<p:ph type=\"sldNum\" sz=\"quarter\" idx=\"12\"/>"))
            val num = back.slides[i].shapes.single { it.footerKind == FooterKind.NUMBER }
            val ftr = back.slides[i].shapes.single { it.footerKind == FooterKind.FOOTER }
            // bottom centre and bottom right, inside the slide
            assertEquals(listOf(4038600L, 6356350L, 4114800L, 365125L), listOf(ftr.x, ftr.y, ftr.w, ftr.h))
            assertEquals(listOf(8610600L, 6356350L, 2743200L, 365125L), listOf(num.x, num.y, num.w, num.h))
            assertTrue(num.x + num.w <= w && num.y + num.h <= h)
            assertEquals(1, ftr.align)
            assertEquals(2, num.align)
            assertEquals(12f, ftr.fontPt)
            assertEquals(12f, num.fontPt)
            assertEquals(listOf("Quarterly review", "第二行"), ftr.paras.map { it.text })
            assertEquals((i + 1).toString(), num.paras.single().text)
            assertEquals(SlideFooter(true, true, "Quarterly review\n第二行"), back.footerInfo(i))
        }
        // the title slide, when it is not left out, gets them too
        assertEquals(1, back.setFooter(listOf(0), number = true, footer = false, text = "", hideOnTitle = false))
        assertEquals(SlideFooter(true, false, ""), back.footerInfo(0))
        assertEquals("1", back.slides[0].shapes.single { it.footerKind == FooterKind.NUMBER }.paras.single().text)
        // hiding on the title slide takes them away again
        assertEquals(1, back.setFooter(null, number = true, footer = true, text = "Quarterly review\n第二行", hideOnTitle = true))
        assertEquals(SlideFooter(false, false, ""), back.footerInfo(0))
        assertEquals(text(after, slidePart(1)), text(back.save(), slidePart(1)))
        assertConsistent(back.save())
    }

    @Test fun aLayoutWithoutItsOwnBoxAndAMasterPlaceholderAreFollowed() {
        // a master with a footer placeholder that the slide's layout does not repeat: the master's box is written into the shape
        val pkg = OoxmlPackage.read(PptxDeck.blank().save())
        val m = "ppt/slideMasters/slideMaster1.xml"
        val mx = pkg.text(m)!!
        val ph = PptxTemplates.placeholder(9, "Footer Placeholder 8", "type=\"ftr\" sz=\"quarter\" idx=\"3\"", xfrm = PptxTemplates.xfrm(100, 200, 300, 400))
        pkg.putText(m, mx.replace("</p:spTree>", "$ph</p:spTree>"))
        val d = PptxDeck.open(pkg.write())
        assertEquals(1, d.setFooter(null, number = false, footer = true, text = "F", hideOnTitle = false))
        val ftr = PptxDeck.open(d.save()).slides[0].shapes.single { it.footerKind == FooterKind.FOOTER }
        assertEquals(listOf(100L, 200L, 300L, 400L), listOf(ftr.x, ftr.y, ftr.w, ftr.h))
        assertTrue(text(d.save(), slidePart(1)).contains("<a:xfrm><a:off x=\"100\" y=\"200\"/><a:ext cx=\"300\" cy=\"400\"/></a:xfrm>"))
    }

    @Test fun turningThemOffAgainGivesTheOriginalSlidesBack() {
        val before = fixture("pptx-deck.pptx")
        val d = PptxDeck.open(before)
        d.setFooter(null, number = true, footer = true, text = "Draft", hideOnTitle = false)
        assertEquals(SlideFooter(true, true, "Draft"), d.footerInfo(0))
        val on = d.save()
        assertEquals((1..7).map { slidePart(it) }.toSet(), changedParts(before, on))
        // off in a reopened deck: every slide part is byte-identical to the original again
        val back = PptxDeck.open(on)
        assertEquals(7, back.setFooter(null, number = false, footer = false, text = "", hideOnTitle = false))
        val off = back.save()
        assertTrue(changedParts(before, off).isEmpty())
        assertAllPartsIdentical(before, off)
        assertTrue(back.slides.all { s -> s.shapes.none { it.footerKind != null } })
        assertEquals(0, back.setFooter(null, false, false, "ignored", true))
    }

    @Test fun onlyTheTextOrOnlyOneOfTheTwoChanges() {
        val src = fixture("pptx-deck.pptx")
        val d = PptxDeck.open(src)
        d.setFooter(listOf(1, 2), number = true, footer = true, text = "v1", hideOnTitle = true)
        val v1 = d.save()
        assertEquals(setOf(slidePart(2), slidePart(3)), changedParts(src, v1))

        // another text: only the text of the footer shapes
        assertEquals(2, d.setFooter(listOf(1, 2), number = true, footer = true, text = "版本 <2>", hideOnTitle = true))
        val v2 = d.save()
        assertEquals(setOf(slidePart(2), slidePart(3)), changedParts(v1, v2))
        for (i in 2..3) {
            val o = text(v1, slidePart(i))
            val at = o.indexOf("<a:p><a:r><a:rPr lang=\"en-US\"/><a:t>v1</a:t></a:r></a:p>")
            assertEquals(
                "<a:p><a:r><a:rPr lang=\"en-US\"/><a:t>版本 &lt;2&gt;</a:t></a:r></a:p>",
                assertOnlyRangeChanged(o, text(v2, slidePart(i)), at, at + "<a:p><a:r><a:rPr lang=\"en-US\"/><a:t>v1</a:t></a:r></a:p>".length),
            )
        }
        // the number off on one slide: its footer stays, the other slide is untouched
        assertEquals(1, d.setFooter(listOf(2), number = false, footer = true, text = "版本 <2>", hideOnTitle = true))
        val v3 = d.save()
        assertEquals(setOf(slidePart(3)), changedParts(v2, v3))
        assertEquals(listOf("ftr"), phTypes(text(v3, slidePart(3))).filter { it == "ftr" || it == "sldNum" })
        assertEquals(SlideFooter(false, true, "版本 <2>"), d.footerInfo(2))
        assertEquals(SlideFooter(true, true, "版本 <2>"), d.footerInfo(1))
        // and back on: appended after the footer
        assertEquals(1, d.setFooter(listOf(2), number = true, footer = true, text = "版本 <2>", hideOnTitle = true))
        assertEquals(listOf("ftr", "sldNum"), phTypes(text(d.save(), slidePart(3))).filter { it == "ftr" || it == "sldNum" })
        assertConsistent(d.save())
        try { d.setFooter(listOf(99), true, true, "", false); fail() } catch (_: IndexOutOfBoundsException) {}
    }

    @Test fun theNumberFollowsTheSlideAndTheFooterIsNeverTheDecksTitle() {
        val d = PptxDeck.open(fixture("pptx-deck.pptx"))
        d.setFooter(null, number = true, footer = true, text = "Footer", hideOnTitle = false)
        fun numbers() = d.slides.map { s -> s.shapes.single { it.footerKind == FooterKind.NUMBER }.paras.single().text }
        assertEquals((1..7).map { it.toString() }, numbers())
        d.moveSlide(6, 0)
        assertEquals((1..7).map { it.toString() }, numbers())
        val copy = d.duplicateSlide(2)
        assertEquals((1..8).map { it.toString() }, numbers())
        assertEquals(SlideFooter(true, true, "Footer"), d.footerInfo(copy))
        d.deleteSlide(0)
        assertEquals((1..7).map { it.toString() }, numbers())
        assertConsistent(d.save())

        // a deck whose only texts are footers has no title
        val b = PptxDeck.blank()
        b.setFooter(null, number = true, footer = true, text = "Only a footer", hideOnTitle = false)
        assertEquals("", b.firstTitle())
        // the footer is an ordinary text shape for typing: editing it through the text API keeps the placeholder
        val ftr = b.slides[0].shapes.single { it.footerKind == FooterKind.FOOTER }
        assertTrue(ftr.editable)
        b.setShapeText(0, ftr.id, listOf(TextPara("Typed")))
        assertEquals(SlideFooter(true, true, "Typed"), b.footerInfo(0))

        // a presentation that starts counting elsewhere
        val pkg = OoxmlPackage.read(d.save())
        pkg.putText("ppt/presentation.xml", pkg.text("ppt/presentation.xml")!!.replace(" saveSubsetFonts=\"1\"", " firstSlideNum=\"5\" saveSubsetFonts=\"1\""))
        val five = PptxDeck.open(pkg.write())
        assertEquals("5", five.slides[0].shapes.single { it.footerKind == FooterKind.NUMBER }.paras.single().text)
        assertEquals("11", five.slides[6].shapes.single { it.footerKind == FooterKind.NUMBER }.paras.single().text)
    }

    @Test fun footersAndCommentsTogetherKeepTheSlideOrderOfElements() {
        val d = PptxDeck.open(withThreads())
        d.setFooter(null, number = true, footer = true, text = "季度复盘", hideOnTitle = true)
        d.addComment(1, 0.4f, 0.4f, "王伟", "看这里", day)
        val saved = d.save()
        assertConsistent(saved)
        for (i in 2..3) {
            val x = text(saved, slidePart(i))
            val root = Xml.root(x)!!
            assertEquals(listOf("p:cSld", "p:clrMapOvr", "p:extLst"), Xml.children(x, root).map { it.name })
            val tree = Xml.find(x, root, "p:spTree")!!
            val kinds = Xml.children(x, tree).map { it.name }
            assertEquals(listOf("p:nvGrpSpPr", "p:grpSpPr"), kinds.take(2))
            assertEquals(listOf("ftr", "sldNum"), phTypes(x).takeLast(2))
        }
        assertEquals(3, PptxDeck.open(saved).comments().size)
        write("footer-and-comments.pptx", saved)
    }
}
