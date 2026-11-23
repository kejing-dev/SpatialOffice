package io.embodit.spatialoffice.core.docx

import io.embodit.spatialoffice.core.docx.PackageAsserts.assertAllPartsIdentical
import io.embodit.spatialoffice.core.docx.PackageAsserts.assertConsistent
import io.embodit.spatialoffice.core.docx.PackageAsserts.assertOnlyRangeChanged
import io.embodit.spatialoffice.core.docx.PackageAsserts.changedParts
import io.embodit.spatialoffice.core.docx.PackageAsserts.fixture
import io.embodit.spatialoffice.core.docx.PackageAsserts.text
import io.embodit.spatialoffice.core.pkg.OoxmlPackage
import io.embodit.spatialoffice.core.pkg.Rels
import io.embodit.spatialoffice.core.pkg.Xml
import io.embodit.spatialoffice.core.review.CommentKind
import io.embodit.spatialoffice.core.review.ReviewComment
import io.embodit.spatialoffice.core.review.RevisionKind
import io.embodit.spatialoffice.core.text.RichText
import io.embodit.spatialoffice.core.text.RunRev
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * M5 (v0.7.0) "Review": comments (d29), tracked changes shown / accepted / rejected (d30), recording my own changes
 * (d36), header and footer (d31), format painter (d24).
 * Every saved file also goes to `build/m5-docx/` for `tools/verify/m5_docx_verify.py`.
 */
class DocxM5Test {
    private val doc = "word/document.xml"
    private val rels = "word/_rels/document.xml.rels"
    private val ct = "[Content_Types].xml"
    private val cPart = "word/comments.xml"
    private val exPart = "word/commentsExtended.xml"
    private val src: ByteArray = fixture("docx-report.docx")

    private val nsW = "xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\""
    private val nsR = "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\""
    private val nsW14 = "xmlns:w14=\"http://schemas.microsoft.com/office/word/2010/wordml\""
    private val relBase = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    private val ctBase = "application/vnd.openxmlformats-officedocument.wordprocessingml"

    private fun out(name: String, bytes: ByteArray): ByteArray {
        val dir = File("build/m5-docx")
        dir.mkdirs()
        File(dir, name).writeBytes(bytes)
        return bytes
    }

    /** One extra part of a hand-made package: name under `word/`, relationship type, content type, text. */
    private class Extra(val name: String, val relType: String, val contentType: String, val xml: String)

    /** A package with a document body and the given extra parts, each related from the main part. */
    private fun handMade(body: String, extras: List<Extra> = emptyList(), sectPr: String = "<w:sectPr/>"): ByteArray {
        val p = OoxmlPackage.empty()
        p.putText(
            ct,
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
                "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
                "<Override PartName=\"/word/document.xml\" ContentType=\"$ctBase.document.main+xml\"/>" +
                extras.joinToString("") { "<Override PartName=\"/word/${it.name}\" ContentType=\"${it.contentType}\"/>" } + "</Types>",
        )
        p.putText("_rels/.rels", "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"$relBase/officeDocument\" Target=\"word/document.xml\"/></Relationships>")
        p.putText(doc, "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<w:document $nsW $nsR><w:body>$body$sectPr</w:body></w:document>")
        if (extras.isNotEmpty()) p.putText(
            rels,
            "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
                extras.withIndex().joinToString("") { (i, e) -> "<Relationship Id=\"rId${i + 1}\" Type=\"${e.relType}\" Target=\"${e.name}\"/>" } + "</Relationships>",
        )
        for (e in extras) p.putText("word/${e.name}", e.xml)
        return p.write()
    }

    private fun commentsPart(inner: String) = Extra("comments.xml", "$relBase/comments", "$ctBase.comments+xml", "<w:comments $nsW $nsW14>$inner</w:comments>")
    private fun extendedPart(inner: String) = Extra(
        "commentsExtended.xml", "http://schemas.microsoft.com/office/2011/relationships/commentsExtended", "$ctBase.commentsExtended+xml",
        "<w15:commentsEx xmlns:w15=\"http://schemas.microsoft.com/office/word/2012/wordml\">$inner</w15:commentsEx>",
    )
    private fun idsPart(inner: String) = Extra(
        "commentsIds.xml", "http://schemas.microsoft.com/office/2016/09/relationships/commentsIds", "$ctBase.commentsIds+xml",
        "<w16cid:commentsIds xmlns:w16cid=\"http://schemas.microsoft.com/office/word/2016/wordml/cid\">$inner</w16cid:commentsIds>",
    )
    private fun extensiblePart(inner: String) = Extra(
        "commentsExtensible.xml", "http://schemas.microsoft.com/office/2018/08/relationships/commentsExtensible", "$ctBase.commentsExtensible+xml",
        "<w16cex:commentsExtensible xmlns:w16cex=\"http://schemas.microsoft.com/office/word/2018/wordml/cex\">$inner</w16cex:commentsExtensible>",
    )

    private fun comment(id: Int, author: String, text: String, paraId: String? = null, date: String = "2026-03-01T08:00:00Z") =
        "<w:comment w:id=\"$id\" w:author=\"$author\" w:date=\"$date\" w:initials=\"${author.take(1)}\"><w:p" + (if (paraId != null) " w14:paraId=\"$paraId\"" else "") +
            "><w:r><w:annotationRef/></w:r><w:r><w:t>$text</w:t></w:r></w:p></w:comment>"

    private fun cStart(id: Int) = "<w:commentRangeStart w:id=\"$id\"/>"
    private fun cEnd(id: Int) = "<w:commentRangeEnd w:id=\"$id\"/>"
    private fun cRef(id: Int) = "<w:r><w:commentReference w:id=\"$id\"/></w:r>"
    private fun r(t: String, rPr: String = "") = "<w:r>$rPr<w:t xml:space=\"preserve\">$t</w:t></w:r>"

    private fun para(d: DocxDocument, id: Int) = d.blocks.first { it.id == id } as Paragraph
    private fun count(hay: String, needle: String) = hay.split(needle).size - 1
    private fun bodyChild(xml: String, n: Int): Xml.Elem {
        val body = Xml.child(xml, Xml.root(xml)!!, "w:body")!!
        return Xml.children(xml, body)[n]
    }

    /** The app's way of typing: the text field says "the text is now [newText]"; spans are edited, runs stored. */
    private fun type(d: DocxDocument, id: Int, newText: String) {
        val p = para(d, id)
        d.setParagraph(id, RichText.applyEdit(p.runs.map { it.toSpan() }, newText).map { it.toRun() }, p.style)
    }

    /** The same with change tracking on. */
    private fun typeTracked(d: DocxDocument, id: Int, newText: String, author: String, date: String) {
        val p = para(d, id)
        val edited = RichText.applyEdit(p.runs.map { it.toSpan() }, newText).map { it.toRun() }
        d.setParagraph(id, Tracking.merge(p.runs, edited, author, date), p.style)
    }

    /** A new document with the given body paragraphs, saved and reopened (ids 0, 1, …). */
    private fun docOf(vararg paragraphs: String): ByteArray {
        val d = DocxDocument.blank()
        val first = d.blocks.first().id
        d.setParagraph(first, listOf(Run(paragraphs[0])), ParaStyle.BODY)
        var last = first
        for (t in paragraphs.drop(1)) last = d.insertParagraphAfter(last, listOf(Run(t)), ParaStyle.BODY)
        return d.save()
    }

    // ================================================================================================ A. comments

    @Test fun addReplyResolveWritesEveryPartTheSuitesRead() {
        val base = docOf("The quarter closed ahead of plan.", "Costs were flat.", "Untouched paragraph.")
        val before = text(base, doc)
        val d = DocxDocument.open(base)
        assertTrue(d.comments().isEmpty())
        val c1 = d.addComment(0, 4, 11, "王小明", "这个数字需要核对", "2026-11-22T10:00:00Z")
        val r1 = d.replyComment(c1, "Li Lei", "Checked: it is right.", "2026-11-22T10:05:00Z")
        val c2 = d.addComment(1, 0, 0, "Li Lei", "Whole paragraph.\nSecond line.", "2026-11-22T10:06:00Z")
        d.setCommentResolved(c2, true)
        assertEquals(listOf(c1, c2), d.comments().map { it.id })
        val saved = out("m5-comments.docx", d.save())
        assertConsistent(saved)
        assertEquals(setOf(doc, rels, ct, cPart, exPart), changedParts(base, saved))

        // the comments part: author, date, initials as given
        val cx = text(saved, cPart)
        assertTrue(cx, cx.contains("<w:comment w:id=\"$c1\" w:author=\"王小明\" w:date=\"2026-11-22T10:00:00Z\" w:initials=\"王\">"))
        assertTrue(cx, cx.contains("<w:comment w:id=\"$r1\" w:author=\"Li Lei\" w:date=\"2026-11-22T10:05:00Z\" w:initials=\"LL\">"))
        assertTrue(cx, cx.contains("<w:t xml:space=\"preserve\">这个数字需要核对</w:t>"))
        assertEquals(3, count(cx, "<w:comment "))
        // the extended part: the reply names its parent, the resolved one is done
        val ex = text(saved, exPart)
        val recs = DocxComments.parse(cx).associateBy { it.id.toString() }
        val exs = DocxComments.parseEx(ex).associateBy { it.paraId }
        assertEquals(3, exs.size)
        assertEquals(recs.getValue(c1).paraId, exs.getValue(recs.getValue(r1).paraId!!).parent)
        assertNull(exs.getValue(recs.getValue(c1).paraId!!).parent)
        assertFalse(exs.getValue(recs.getValue(c1).paraId!!).done)
        assertTrue(exs.getValue(recs.getValue(c2).paraId!!).done)
        assertTrue(ex, ex.contains("w15:done=\"1\""))
        assertTrue(ex, ex.contains("w15:paraIdParent=\"${recs.getValue(c1).paraId}\""))
        // the text: one range start, one range end and one reference for each of the three
        val x = text(saved, doc)
        for (id in listOf(c1, r1, c2)) {
            assertEquals(1, count(x, "<w:commentRangeStart w:id=\"$id\"/>"))
            assertEquals(1, count(x, "<w:commentRangeEnd w:id=\"$id\"/>"))
            assertEquals(1, count(x, "<w:commentReference w:id=\"$id\"/>"))
        }
        assertTrue(x, x.contains("<w:r><w:t xml:space=\"preserve\">The </w:t></w:r>${cStart(c1.toInt())}${cStart(r1.toInt())}<w:r><w:t>quarter</w:t></w:r>${cEnd(c1.toInt())}${cRef(c1.toInt())}${cEnd(r1.toInt())}${cRef(r1.toInt())}<w:r><w:t xml:space=\"preserve\"> closed ahead of plan.</w:t></w:r></w:p>"))
        assertTrue(x, x.contains("<w:p>${cStart(c2.toInt())}<w:r><w:t>Costs were flat.</w:t></w:r>${cEnd(c2.toInt())}${cRef(c2.toInt())}</w:p>"))
        // the third paragraph and everything after it is untouched
        val third = bodyChild(before, 2)
        assertTrue(x.endsWith(before.substring(third.start)))
        // relationships and content types
        val pkg = OoxmlPackage.read(saved)
        val rs = Rels.of(pkg, doc)
        assertEquals("comments.xml", rs.single { it.type == "$relBase/comments" }.target)
        assertEquals("commentsExtended.xml", rs.single { it.type.endsWith("/commentsExtended") }.target)
        assertEquals("$ctBase.comments+xml", XmlEx.contentType(pkg, cPart))
        assertEquals("$ctBase.commentsExtended+xml", XmlEx.contentType(pkg, exPart))

        // read back
        val re = DocxDocument.open(saved)
        assertEquals(d.comments(), re.comments())
        val (a, b) = re.comments()
        assertEquals(ReviewComment(c1, CommentKind.THREAD, "王小明", "2026-11-22T10:00:00Z", "这个数字需要核对", "quarter", false, a.replies, 0, 4, 11), a)
        assertEquals(listOf(r1 to "Checked: it is right."), a.replies.map { it.id to it.text })
        assertEquals("Li Lei", a.replies[0].author)
        assertEquals("Whole paragraph.\nSecond line.", b.text)
        assertEquals("Costs were flat.", b.quote)
        assertTrue(b.resolved)
        assertEquals(Triple(1, 0, 16), Triple(b.block, b.start, b.end))
        assertEquals(2, a.count)
        // nothing more to save: the same bytes again
        assertArrayEquals(saved, re.save())
    }

    @Test fun resolveReopenEditAndDelete() {
        val d = DocxDocument.open(docOf("Alpha beta gamma", "Second"))
        val c = d.addComment(0, 6, 10, "Ann", "first", "2026-11-22T10:00:00Z")
        val r1 = d.replyComment(c, "Bob", "reply one", "2026-11-22T10:01:00Z")
        val r2 = d.replyComment(r1, "Ann", "reply two", "2026-11-22T10:02:00Z")   // a reply id answers the thread
        val other = d.addComment(1, 0, 3, "Bob", "other", "2026-11-22T10:03:00Z")
        assertEquals(listOf(r1, r2), d.comments()[0].replies.map { it.id })
        d.setCommentResolved(r1, true)                                            // a reply id resolves the thread
        assertEquals(listOf(true, false), d.comments().map { it.resolved })
        d.setCommentResolved(c, false)
        assertEquals(listOf(false, false), d.comments().map { it.resolved })
        d.editComment(c, "first, edited")
        d.editComment(r2, "reply two\nwith a second line")
        var re = DocxDocument.open(out("m5-comments-edited.docx", d.save()))
        assertConsistent(re.save())
        assertEquals("first, edited", re.comments()[0].text)
        assertEquals("reply two\nwith a second line", re.comments()[0].replies[1].text)
        assertEquals("Ann", re.comments()[0].author)
        // the edited reply is still the thread's: its paragraph id did not change, and no second paragraph took it too
        assertEquals(2, re.comments()[0].replies.size)
        val pids = DocxComments.parse(text(re.save(), cPart)).flatMap { it.paraIds }
        assertEquals(pids.toString(), pids.size, pids.toSet().size)

        // delete a reply: only that reply and its anchors go
        re.deleteComment(r1)
        assertEquals(listOf(r2), re.comments()[0].replies.map { it.id })
        var x = text(re.save(), doc)
        assertEquals(0, count(x, "w:id=\"$r1\"/>"))
        assertEquals(3, count(x, "w:id=\"$r2\"/>"))
        // delete the first comment: the whole thread goes, the other comment stays
        re.deleteComment(c)
        assertEquals(listOf(other), re.comments().map { it.id })
        val saved = out("m5-comments-deleted.docx", re.save())
        assertConsistent(saved)
        x = text(saved, doc)
        assertEquals(1, count(x, "<w:commentRangeStart"))
        assertEquals(1, count(x, "<w:commentReference"))
        assertTrue(x, x.contains("<w:p><w:r><w:t xml:space=\"preserve\">Alpha </w:t></w:r><w:r><w:t>beta</w:t></w:r><w:r><w:t xml:space=\"preserve\"> gamma</w:t></w:r></w:p>"))
        assertEquals(1, DocxComments.parse(text(saved, cPart)).size)
        assertEquals(1, DocxComments.parseEx(text(saved, exPart)).size)
        re = DocxDocument.open(saved)
        assertEquals("Sec", re.comments().single().quote)
        re.deleteComment("999")   // unknown: nothing happens
        assertFalse(re.dirty)
        try { re.replyComment("999", "a", "b", ""); fail() } catch (e: IllegalArgumentException) { /* expected */ }
    }

    @Test fun anchorsFollowTheTextWhileTheParagraphIsEdited() {
        val base = docOf("Alpha beta gamma")
        fun fresh(): Pair<DocxDocument, String> {
            val d = DocxDocument.open(base)
            return d to d.addComment(0, 6, 10, "Ann", "about beta", "2026-11-22T10:00:00Z")
        }
        fun at(d: DocxDocument) = d.comments().single().let { Triple(it.start, it.end, it.quote) }
        fun reopened(d: DocxDocument) = DocxDocument.open(d.save())

        // typing before the range moves it along
        fresh().let { (d, _) -> type(d, 0, "XAlpha beta gamma"); assertEquals(Triple(7, 11, "beta"), at(d)); assertEquals(Triple(7, 11, "beta"), at(reopened(d))) }
        // typing right in front of it stays outside
        fresh().let { (d, _) -> type(d, 0, "Alpha Xbeta gamma"); assertEquals(Triple(7, 11, "beta"), at(d)) }
        // typing inside makes it longer
        fresh().let { (d, _) -> type(d, 0, "Alpha beXta gamma"); assertEquals(Triple(6, 11, "beXta"), at(d)); assertEquals(Triple(6, 11, "beXta"), at(reopened(d))) }
        // typing right behind it stays outside
        fresh().let { (d, _) -> type(d, 0, "Alpha betaX gamma"); assertEquals(Triple(6, 10, "beta"), at(d)) }
        // typing after it leaves it where it is
        fresh().let { (d, _) -> type(d, 0, "Alpha beta gamma!"); assertEquals(Triple(6, 10, "beta"), at(d)) }
        // deleting part of it makes it shorter
        fresh().let { (d, _) -> type(d, 0, "Alpha ba gamma"); assertEquals(Triple(6, 8, "ba"), at(d)) }
        // replacing exactly the commented word: the comment is on the new word
        fresh().let { (d, _) -> type(d, 0, "Alpha delta gamma"); assertEquals(Triple(6, 11, "delta"), at(d)) }
        // deleting from before the range into it
        fresh().let { (d, _) -> type(d, 0, "Alpta gamma"); assertEquals(Triple(3, 5, "ta"), at(d)) }
        // a format change does not move it
        fresh().let { (d, _) ->
            val p = para(d, 0)
            d.setParagraph(0, RichText.setAttr(p.runs.map { it.toSpan() }, 3, 8, io.embodit.spatialoffice.core.text.Attr.BOLD, true).map { it.toRun() }, p.style)
            assertEquals(Triple(6, 10, "beta"), at(d))
            val re = reopened(d)
            assertEquals(Triple(6, 10, "beta"), at(re))
            // (where an anchor sits between two characters, the run is written in two)
            assertEquals(listOf("Alp", "ha ", "be", "ta", " gamma"), para(re, 0).runs.map { it.text })
            assertEquals(listOf(false, true, true, false, false), para(re, 0).runs.map { it.bold })
        }

        // deleting the commented text entirely: the comment stays, on an empty range at that place
        val (d, c) = fresh()
        type(d, 0, "Alpha  gamma")
        assertEquals(Triple(6, 6, ""), at(d))
        val saved = out("m5-comment-text-deleted.docx", d.save())
        assertConsistent(saved)
        val x = text(saved, doc)
        assertTrue(x, x.contains("<w:r><w:t xml:space=\"preserve\">Alpha </w:t></w:r>${cStart(c.toInt())}${cEnd(c.toInt())}${cRef(c.toInt())}<w:r><w:t xml:space=\"preserve\"> gamma</w:t></w:r>"))
        val re = DocxDocument.open(saved)
        assertEquals(Triple(6, 6, ""), at(re))
        // and it still takes a reply; typing there afterwards keeps it
        re.replyComment(c, "Bob", "gone?", "")
        type(re, 0, "Alpha new gamma")
        assertEquals(1, re.comments().single().replies.size)
        assertConsistent(re.save())
        // emptying the paragraph and typing again: still one comment, range and reference in order
        type(re, 0, "")
        type(re, 0, "fresh text")
        val y = text(out("m5-comment-retyped.docx", re.save()), doc)
        assertTrue(y, y.indexOf(cStart(c.toInt())) < y.indexOf(cEnd(c.toInt())))
        assertTrue(y, y.indexOf(cEnd(c.toInt())) < y.indexOf("<w:commentReference w:id=\"$c\"/>"))
        assertEquals(1, DocxDocument.open(re.save()).comments().size)
    }

    @Test fun splittingAndJoiningParagraphsKeepsEveryComment() {
        // Enter in the middle of a commented paragraph, as the app does it: this paragraph keeps the left part, a new one takes the rest
        val d = DocxDocument.open(docOf("Alpha beta gamma", "Tail paragraph"))
        val c = d.addComment(0, 6, 10, "Ann", "about beta", "")
        val c2 = d.addComment(1, 0, 4, "Ann", "about Tail", "")
        val p = para(d, 0)
        d.setParagraph(0, Tracking.slice(p.runs, 0, 8), p.style)
        val right = d.insertParagraphAfter(0, Tracking.slice(p.runs, 8, 16), ParaStyle.BODY)
        assertEquals("ta gamma", para(d, right).text)
        assertEquals(Triple(0, 6, 8), d.comments()[0].let { Triple(it.block, it.start, it.end) })
        assertEquals("be", d.comments()[0].quote)
        // Backspace joins: the first keeps both texts, the second paragraph is deleted — its comment moves over
        val tail = d.blocks.last().id
        val joined = para(d, right).runs + para(d, tail).runs
        d.setParagraph(right, joined, ParaStyle.BODY)
        d.deleteBlock(tail)
        assertEquals(listOf(c, c2), d.comments().map { it.id })
        val moved = d.comments()[1]
        assertEquals(right, moved.block)
        assertEquals("ta gammaTail paragraph".length, moved.start)
        val saved = out("m5-comments-split-join.docx", d.save())
        assertConsistent(saved)
        val x = text(saved, doc)
        for (id in listOf(c, c2)) assertEquals(3, count(x, "w:id=\"$id\"/>"))
        assertEquals(2, DocxDocument.open(saved).comments().size)

        // with no paragraph to move to, the comment goes with its block
        val solo = DocxDocument.open(handMade("<w:p>${cStart(0)}${r("only")}${cEnd(0)}${cRef(0)}</w:p><w:tbl><w:tr><w:tc><w:p/></w:tc></w:tr></w:tbl>", listOf(commentsPart(comment(0, "A", "x")))))
        solo.deleteBlock(0)
        assertTrue(solo.comments().isEmpty())
        assertEquals(0, DocxComments.parse(text(solo.save(), cPart)).size)
    }

    @Test fun existingThreadsWithAllFourCommentPartsAreReadAndKeptInStep() {
        val body = "<w:p>${cStart(1)}${cStart(2)}${r("Reviewed sentence.")}${cEnd(1)}${cRef(1)}${cEnd(2)}${cRef(2)}</w:p>" +
            "<w:p>${r("Plain ")}${cStart(5)}${r("second")}${cEnd(5)}${cRef(5)}</w:p>"
        val base = handMade(
            body,
            listOf(
                commentsPart(comment(1, "Ann", "Is this right?", "0A000001") + comment(2, "Bob", "Yes.", "0A000002") + comment(5, "Ann", "Done already", "0A000005")),
                extendedPart("<w15:commentEx w15:paraId=\"0A000001\" w15:done=\"0\"/><w15:commentEx w15:paraId=\"0A000002\" w15:paraIdParent=\"0A000001\" w15:done=\"0\"/><w15:commentEx w15:paraId=\"0A000005\" w15:done=\"1\"/>"),
                idsPart("<w16cid:commentId w16cid:paraId=\"0A000001\" w16cid:durableId=\"1B000001\"/><w16cid:commentId w16cid:paraId=\"0A000002\" w16cid:durableId=\"1B000002\"/><w16cid:commentId w16cid:paraId=\"0A000005\" w16cid:durableId=\"1B000005\"/>"),
                extensiblePart("<w16cex:commentExtensible w16cex:durableId=\"1B000001\" w16cex:dateUtc=\"2026-03-01T08:00:00Z\"/><w16cex:commentExtensible w16cex:durableId=\"1B000002\" w16cex:dateUtc=\"2026-03-01T08:00:00Z\"/><w16cex:commentExtensible w16cex:durableId=\"1B000005\" w16cex:dateUtc=\"2026-03-01T08:00:00Z\"/>"),
            ),
        )
        val d = DocxDocument.open(base)
        val cs = d.comments()
        assertEquals(listOf("1", "5"), cs.map { it.id })
        assertEquals(listOf("2" to "Yes."), cs[0].replies.map { it.id to it.text })
        assertEquals(listOf(false, true), cs.map { it.resolved })
        assertEquals(Triple(0, 0, 18), Triple(cs[0].block, cs[0].start, cs[0].end))
        assertEquals("second", cs[1].quote)
        assertEquals(Triple(1, 6, 12), Triple(cs[1].block, cs[1].start, cs[1].end))
        assertTrue("both paragraphs are editable", d.blocks.all { (it as Paragraph).editable })
        assertFalse(d.dirty)
        assertAllPartsIdentical(base, d.save())

        // a reply: one more entry in each of the four parts, the same paragraph id in three of them
        val rid = d.replyComment("1", "Cid", "Agreed.", "2026-11-22T10:00:00Z")
        assertEquals("6", rid)
        val saved = out("m5-comments-four-parts.docx", d.save())
        assertConsistent(saved)
        assertEquals(setOf(doc, cPart, exPart, "word/commentsIds.xml", "word/commentsExtensible.xml"), changedParts(base, saved))
        val pid = DocxComments.parse(text(saved, cPart)).single { it.id == 6 }.paraId!!
        assertTrue(text(saved, exPart).contains("<w15:commentEx w15:paraId=\"$pid\" w15:paraIdParent=\"0A000001\" w15:done=\"0\"/>"))
        val durable = Regex("w16cid:paraId=\"$pid\" w16cid:durableId=\"([0-9A-F]{8})\"").find(text(saved, "word/commentsIds.xml"))!!.groupValues[1]
        assertTrue(text(saved, "word/commentsExtensible.xml").contains("<w16cex:commentExtensible w16cex:durableId=\"$durable\" w16cex:dateUtc=\"2026-11-22T10:00:00Z\"/>"))
        // its anchors sit after the thread's last ones; the second paragraph is byte-identical
        val x = text(saved, doc)
        assertTrue(x, x.contains("<w:p>${cStart(1)}${cStart(2)}${cStart(6)}${r("Reviewed sentence.")}${cEnd(1)}${cRef(1)}${cEnd(2)}${cRef(2)}${cEnd(6)}${cRef(6)}</w:p>"))
        val second = bodyChild(text(base, doc), 1)
        assertTrue(x.endsWith(text(base, doc).substring(second.start)))
        assertEquals(listOf("2", "6"), DocxDocument.open(saved).comments()[0].replies.map { it.id })

        // deleting the thread takes its entries out of all four parts and leaves the other comment's alone
        val re = DocxDocument.open(saved)
        re.deleteComment("1")
        val after = out("m5-comments-four-parts-deleted.docx", re.save())
        assertConsistent(after)
        assertEquals(listOf("5"), DocxDocument.open(after).comments().map { it.id })
        assertEquals("<w15:commentEx w15:paraId=\"0A000005\" w15:done=\"1\"/>", Regex("<w15:commentEx [^>]*/>").findAll(text(after, exPart)).joinToString("") { it.value })
        assertEquals(1, count(text(after, "word/commentsIds.xml"), "<w16cid:commentId "))
        assertEquals(1, count(text(after, "word/commentsExtensible.xml"), "<w16cex:commentExtensible "))
        assertTrue(text(after, doc).contains("<w:p>${r("Reviewed sentence.")}</w:p><w:p>${r("Plain ")}${cStart(5)}"))
    }

    @Test fun fileWithCommentsButNoExtendedPart() {
        val d = DocxDocument.open(src)
        val before = text(src, doc)
        val c = d.comments().single()
        assertEquals(ReviewComment("0", CommentKind.THREAD, "Reviewer", "2026-11-14T10:11:34Z", "Please double-check this number.", "This paragraph carries a review comment that must survive every save.", false, emptyList(), 3, 0, 69), c)
        assertTrue(para(d, 3).editable)
        assertFalse(d.dirty)
        assertAllPartsIdentical(src, d.save())

        // editing the commented paragraph keeps the anchors, and nothing else in the file changes
        type(d, 3, "This paragraph carries a comment that must survive every save.")
        val edited = d.save()
        assertEquals(setOf(doc), changedParts(src, edited))
        val el = bodyChild(before, 3)
        val seg = assertOnlyRangeChanged(before, text(edited, doc), el.start, el.end)
        assertEquals("<w:p>${cStart(0)}<w:r><w:t>This paragraph carries a comment that must survive every save.</w:t></w:r>${cEnd(0)}<w:r><w:rPr><w:rStyle w:val=\"CommentReference\"/></w:rPr><w:commentReference w:id=\"0\"/></w:r></w:p>", seg)
        assertEquals(62, DocxDocument.open(edited).comments().single().end)

        // a reply needs the extended part: it is added, and the old comment's paragraph gets an id to be answered by
        val rid = d.replyComment("0", "Me", "Checked.", "2026-11-22T12:00:00Z")
        d.setCommentResolved("0", true)
        val saved = out("m5-comments-legacy-reply.docx", d.save())
        assertConsistent(saved)
        assertEquals(setOf(doc, cPart, exPart, rels, ct), changedParts(src, saved))
        val recs = DocxComments.parse(text(saved, cPart))
        val exs = DocxComments.parseEx(text(saved, exPart))
        assertEquals(2, exs.size)
        assertEquals(recs[0].paraId, exs.single { it.paraId == recs[1].paraId }.parent)
        assertTrue(exs.all { it.done })
        // the old comment itself is as it was, but for the id on its paragraph
        assertTrue(text(saved, cPart).contains("<w:comment w:id=\"0\" w:author=\"Reviewer\" w:initials=\"RV\" w:date=\"2026-11-14T10:11:34Z\"><w:p w14:paraId=\"${recs[0].paraId}\"><w:pPr><w:pStyle w:val=\"CommentText\"/></w:pPr>"))
        val re = DocxDocument.open(saved).comments().single()
        assertTrue(re.resolved)
        assertEquals(listOf(rid), re.replies.map { it.id })
        // every other paragraph of the document is byte-identical
        assertOnlyRangeChanged(before, text(saved, doc), el.start, el.end)
    }

    @Test fun commentsInOddPlacesAreListedAndCanBeAnsweredResolvedAndDeleted() {
        val body =
            "<w:p>${cStart(1)}${r("one ")}${cStart(2)}${r("two ")}${cEnd(1)}${cRef(1)}${r("three")}${cEnd(2)}${cRef(2)}</w:p>" +                      // 0 overlapping
                "<w:p>${cStart(3)}${cStart(4)}${r("nested ")}${cEnd(4)}${cRef(4)}${r("outer")}${cEnd(3)}${cRef(3)}</w:p>" +                            // 1 nested
                "<w:tbl><w:tr><w:tc><w:p>${cStart(5)}${r("cell text")}${cEnd(5)}${cRef(5)}</w:p></w:tc><w:tc><w:p>${r("b")}</w:p></w:tc></w:tr>" +
                "<w:tr><w:tc><w:p>${cStart(8)}${r("row two")}${cEnd(8)}${cRef(8)}</w:p></w:tc><w:tc><w:p/></w:tc></w:tr></w:tbl>" +                  // 2 table
                "<w:p>${cStart(6)}${r("spans ")}</w:p><w:p>${r("two paragraphs")}${cEnd(6)}${cRef(6)}${r(" and more")}</w:p>" +                        // 3, 4 across paragraphs
                "<w:p>${r("see ")}<w:r><w:fldChar w:fldCharType=\"begin\"/></w:r><w:r><w:instrText> DATE </w:instrText></w:r><w:r><w:fldChar w:fldCharType=\"end\"/></w:r>${cStart(9)}${r("field")}${cEnd(9)}${cRef(9)}</w:p>"  // 5 read-only
        val base = handMade(body, listOf(commentsPart((1..9).joinToString("") { comment(it, "A$it", "c$it") })))   // 7 has no anchor at all
        val d = DocxDocument.open(base)
        val cs = d.comments().associateBy { it.id }
        assertEquals((1..9).map { it.toString() }, d.comments().map { it.id })
        assertEquals(Triple(0, 0, 8), cs.getValue("1").let { Triple(it.block, it.start, it.end) }); assertEquals("one two ", cs.getValue("1").quote)
        assertEquals(Triple(0, 4, 13), cs.getValue("2").let { Triple(it.block, it.start, it.end) }); assertEquals("two three", cs.getValue("2").quote)
        assertEquals("nested outer", cs.getValue("3").quote); assertEquals("nested ", cs.getValue("4").quote)
        assertEquals(-1, cs.getValue("5").block); assertEquals("cell text", cs.getValue("5").quote)
        assertEquals(Triple(3, 0, 6), cs.getValue("6").let { Triple(it.block, it.start, it.end) }); assertEquals("spans \ntwo paragraphs", cs.getValue("6").quote)
        assertEquals(-1, cs.getValue("7").block); assertEquals("", cs.getValue("7").quote)
        assertEquals(Triple(5, 4, 9), cs.getValue("9").let { Triple(it.block, it.start, it.end) }); assertEquals("field", cs.getValue("9").quote)
        assertFalse(para(d, 5).editable)
        assertAllPartsIdentical(base, d.save())

        // overlapping and nested ranges survive typing in the paragraph
        type(d, 0, "one 2 three")
        assertEquals("one 2 ", d.comments()[0].quote); assertEquals("2 three", d.comments()[1].quote)
        type(d, 1, "nested and outer")
        assertEquals("nested and outer", d.comments()[2].quote); assertEquals("nested ", d.comments()[3].quote)
        // the range across two paragraphs survives edits in both
        type(d, 3, "it spans ")
        type(d, 4, "these two paragraphs and more")
        assertEquals(Triple(3, 3, 9), d.comments()[5].let { Triple(it.block, it.start, it.end) }); assertEquals("spans \nthese two paragraphs", d.comments()[5].quote)

        // the comment in the table cell, the one without an anchor and the one in a read-only paragraph take replies and resolve
        val inCell = d.replyComment("5", "Me", "in the cell", "")
        val orphan = d.replyComment("7", "Me", "nowhere", "")
        val inField = d.replyComment("9", "Me", "by the field", "")
        d.setCommentResolved("7", true)
        d.setCommentResolved("5", true)
        val whole = d.addComment(5, 1, 2, "Me", "a read-only paragraph is commented as a whole", "")
        try { d.addComment(2, 0, 0, "Me", "tables are not", ""); fail() } catch (e: IllegalStateException) { /* expected */ }
        val saved = out("m5-comments-odd-places.docx", d.save())
        assertConsistent(saved)
        val x = text(saved, doc)
        assertTrue(x, x.contains("<w:tc><w:p>${cStart(5)}${cStart(inCell.toInt())}${r("cell text")}${cEnd(5)}${cRef(5)}${cEnd(inCell.toInt())}${cRef(inCell.toInt())}</w:p></w:tc>"))
        assertEquals("a reply to a comment without an anchor has none either", 0, count(x, "w:id=\"$orphan\"/>"))
        assertTrue(x, x.contains("${cEnd(9)}${cRef(9)}${cEnd(inField.toInt())}${cRef(inField.toInt())}${cEnd(whole.toInt())}${cRef(whole.toInt())}</w:p>"))
        val re = DocxDocument.open(saved)
        val rs = re.comments().associateBy { it.id }
        assertEquals(listOf(inCell), rs.getValue("5").replies.map { it.id }); assertTrue(rs.getValue("5").resolved)
        assertEquals(listOf(orphan), rs.getValue("7").replies.map { it.id }); assertTrue(rs.getValue("7").resolved)
        assertEquals("see field", rs.getValue(whole).quote)

        // typing in the commented cell keeps its comment (and the reply); deleting the other row takes that row's comment along
        re.setTableCell(2, 0, 0, "new cell text")
        assertEquals("new cell text", re.comments().first { it.id == "5" }.quote)
        assertTrue(re.deleteTableRow(2, 1))
        assertNull(re.comments().firstOrNull { it.id == "8" })
        re.deleteComment("7")
        re.deleteComment("5")
        val after = out("m5-comments-odd-places-2.docx", re.save())
        assertConsistent(after)
        val y = text(after, doc)
        assertTrue(y, y.contains("<w:tbl><w:tr><w:tc><w:p>${"<w:r><w:t>new cell text</w:t></w:r>"}</w:p></w:tc>"))
        assertFalse(DocxComments.parse(text(after, cPart)).any { it.id in setOf(5, 7, 8, inCell.toInt(), orphan.toInt()) })
    }

    // ================================================================================================ B. tracked changes

    private val twoAuthors =
        "<w:p>${r("Costs were ")}<w:del w:id=\"11\" w:author=\"Ann\" w:date=\"2026-01-05T09:00:00Z\"><w:r><w:delText>flat</w:delText></w:r></w:del>" +
            "<w:ins w:id=\"12\" w:author=\"Ann\" w:date=\"2026-01-05T09:00:00Z\"><w:r><w:t>slightly </w:t></w:r></w:ins><w:ins w:id=\"13\" w:author=\"Ann\" w:date=\"2026-01-05T09:01:00Z\"><w:r><w:rPr><w:b/></w:rPr><w:t>lower</w:t></w:r></w:ins>" +
            "<w:ins w:id=\"14\" w:author=\"Bob\" w:date=\"2026-01-06T09:00:00Z\"><w:r><w:t xml:space=\"preserve\"> by far</w:t></w:r></w:ins>${r(" than forecast.")}</w:p>" +                       // 0
            "<w:p>${r("Sales ")}<w:del w:id=\"15\" w:author=\"Bob\" w:date=\"2026-01-06T09:02:00Z\"><w:r><w:rPr><w:i/></w:rPr><w:delText xml:space=\"preserve\">really </w:delText></w:r></w:del>${r("grew.")}</w:p>" +  // 1
            "<w:p>${r("An untouched paragraph with ")}<w:ins w:id=\"16\" w:author=\"Ann\"><w:r><w:t>its own change</w:t></w:r></w:ins>${r(".")}</w:p>" +                                                          // 2
            "<w:p><w:pPr><w:pPrChange w:id=\"17\" w:author=\"Ann\"><w:pPr><w:jc w:val=\"center\"/></w:pPr></w:pPrChange></w:pPr><w:r><w:rPr><w:b/><w:rPrChange w:id=\"18\" w:author=\"Ann\"><w:rPr/></w:rPrChange></w:rPr><w:t>formatted</w:t></w:r></w:p>" + // 3
            "<w:p><w:moveFrom w:id=\"19\" w:author=\"Bob\"><w:r><w:t>moved text</w:t></w:r></w:moveFrom></w:p>" +                                                                                              // 4
            "<w:p><w:pPr><w:rPr><w:ins w:id=\"20\" w:author=\"Bob\"/></w:rPr></w:pPr>${r("tracked paragraph mark")}</w:p>" +                                                                                 // 5
            "<w:p><w:ins w:id=\"21\" w:author=\"Ann\"><w:r><w:t>kept </w:t></w:r><w:del w:id=\"22\" w:author=\"Bob\"><w:r><w:delText>nested</w:delText></w:r></w:del></w:ins></w:p>"                                // 6

    @Test fun tractedTextIsInTheModelAndTheParagraphStaysEditable() {
        val d = DocxDocument.open(src)
        val p = para(d, 18)
        assertTrue(p.editable)
        val rev = RunRev(RevisionKind.DELETE, "Reviewer", "2026-01-05T09:00:00Z")
        assertEquals(listOf("Costs were ", "flat", "slightly lower", " than forecast."), p.runs.map { it.text })
        assertEquals(listOf(null, rev, rev.copy(kind = RevisionKind.INSERT), null), p.runs.map { it.rev })
        assertEquals("Costs were slightly lower than forecast.", p.cleanText)
        assertTrue(d.plainText().contains("Costs were slightly lower than forecast."))
        assertFalse(d.plainText().contains("flat"))
        // typing elsewhere in the paragraph keeps both changes, with their ids
        type(d, 18, "Costs were flatslightly lower than the forecast.")
        val saved = d.save()
        val before = text(src, doc)
        val el = bodyChild(before, 18)
        val seg = assertOnlyRangeChanged(before, text(saved, doc), el.start, el.end)
        assertTrue(seg, seg.contains("<w:del w:id=\"101\" w:author=\"Reviewer\" w:date=\"2026-01-05T09:00:00Z\"><w:r><w:delText>flat</w:delText></w:r></w:del><w:ins w:id=\"102\" w:author=\"Reviewer\" w:date=\"2026-01-05T09:00:00Z\"><w:r><w:t>slightly lower</w:t></w:r></w:ins><w:r><w:t xml:space=\"preserve\"> than the forecast.</w:t></w:r></w:p>"))
        assertEquals(p.runs.map { it.rev }, para(DocxDocument.open(saved), 18).runs.map { it.rev })
    }

    @Test fun revisionsAreListedInOrderGroupedByAuthor() {
        val base = handMade(twoAuthors, sectPr = "<w:sectPr><w:pgSz w:w=\"11906\" w:h=\"16838\"/><w:sectPrChange w:id=\"30\" w:author=\"Ann\"><w:sectPr/></w:sectPrChange></w:sectPr>")
        val d = DocxDocument.open(base)
        val rs = d.revisions()
        assertEquals(
            listOf(
                "r11|DELETE|Ann|flat|0", "r12|INSERT|Ann|slightly lower|0", "r14|INSERT|Bob| by far|0", "r15|DELETE|Bob|really |1", "r16|INSERT|Ann|its own change|2",
                "r17|FORMAT|Ann||3", "r19|MOVE|Bob|moved text|4", "r20|FORMAT|Bob||5", "r21|INSERT|Ann|kept |6", "r22|DELETE|Bob|nested|6", "r30|FORMAT|Ann||-1",
            ),
            rs.map { "${it.id}|${it.kind}|${it.author}|${it.text}|${it.block}" },
        )
        assertEquals("2026-01-05T09:00:00Z", rs[0].date)
        assertEquals(listOf(true, true, true, true, true, false, false, false, true, true, false), rs.map { it.canResolve })
        // what is modelled is editable; a tracked format change, move or paragraph mark keeps its paragraph read-only, as before
        assertEquals(listOf(true, true, true, false, false, false, false), d.blocks.map { (it as Paragraph).editable })
        assertEquals(listOf("tracked-changes", "tracked-changes", "tracked-changes", "tracked-changes"), d.blocks.drop(3).map { (it as Paragraph).readOnlyWhy })
        assertEquals(CompatNote(CompatKind.READ_ONLY, "tracked-changes", 4), d.compat().single { it.code == "tracked-changes" })
        assertEquals("Ann", para(d, 0).runs[2].rev!!.author); assertEquals("Bob", para(d, 0).runs[4].rev!!.author)
        assertEquals("Costs were slightly lower by far than forecast.", para(d, 0).cleanText)
        assertFalse(d.dirty)
        assertAllPartsIdentical(base, d.save())
        // FORMAT / MOVE and unknown ids cannot be resolved
        for (id in listOf("r17", "r19", "r20", "r30", "nope")) { assertFalse(d.acceptRevision(id)); assertFalse(d.rejectRevision(id)) }
        assertFalse(d.dirty)
    }

    @Test fun acceptOneInsertionRejectOneDeletion() {
        val base = handMade(twoAuthors)
        val before = text(base, doc)
        val d = DocxDocument.open(base)
        assertTrue(d.acceptRevision("r12"))   // Ann's insertion "slightly lower" (two neighbouring w:ins)
        assertTrue(d.rejectRevision("r15"))   // Bob's deletion "really "
        assertEquals(listOf("r11", "r14", "r16", "r17", "r19", "r20", "r21", "r22"), d.revisions().map { it.id })
        val saved = out("m5-revisions-accept-reject.docx", d.save())
        assertConsistent(saved)
        assertEquals(setOf(doc), changedParts(base, saved))
        val x = text(saved, doc)
        // the accepted insertion is body text (its runs as they were), the other changes of the paragraph are untouched
        val p0 = bodyChild(before, 0)
        val p1 = bodyChild(before, 1)
        assertEquals(
            "<w:p>${r("Costs were ")}<w:del w:id=\"11\" w:author=\"Ann\" w:date=\"2026-01-05T09:00:00Z\"><w:r><w:delText>flat</w:delText></w:r></w:del>" +
                "<w:r><w:t>slightly </w:t></w:r><w:r><w:rPr><w:b/></w:rPr><w:t>lower</w:t></w:r>" +
                "<w:ins w:id=\"14\" w:author=\"Bob\" w:date=\"2026-01-06T09:00:00Z\"><w:r><w:t xml:space=\"preserve\"> by far</w:t></w:r></w:ins>${r(" than forecast.")}</w:p>",
            x.substring(p0.start, x.indexOf("</w:p>") + 6),
        )
        // the rejected deletion's text is body text again
        assertTrue(x, x.contains("<w:p>${r("Sales ")}<w:r><w:rPr><w:i/></w:rPr><w:t xml:space=\"preserve\">really </w:t></w:r>${r("grew.")}</w:p>"))
        // every other paragraph is byte-identical
        assertTrue(x.startsWith(before.substring(0, p0.start)))
        assertTrue(x.endsWith(before.substring(p1.end)))
        val re = DocxDocument.open(saved)
        assertEquals("Sales really grew.", para(re, 1).text)
        assertTrue(para(re, 1).runs.all { it.rev == null })
        assertEquals(listOf(null, RevisionKind.DELETE, null, null, RevisionKind.INSERT, null), para(re, 0).runs.map { it.rev?.kind })
        assertEquals(d.revisions(), re.revisions())

        // the other two directions
        val d2 = DocxDocument.open(base)
        assertTrue(d2.rejectRevision("r12"))
        assertTrue(d2.acceptRevision("r15"))
        assertEquals("Costs were flat by far than forecast.", para(d2, 0).text)
        assertEquals("Sales grew.", para(d2, 1).text)
        val y = text(out("m5-revisions-reject-accept.docx", d2.save()), doc)
        assertFalse(y.contains("slightly")); assertFalse(y.contains("really"))
        // nested: accepting the insertion leaves the deletion inside it standing on its own; rejecting it takes both
        assertTrue(d2.acceptRevision("r21"))
        assertTrue(text(d2.save(), doc).contains("<w:p><w:r><w:t>kept </w:t></w:r><w:del w:id=\"22\" w:author=\"Bob\"><w:r><w:delText>nested</w:delText></w:r></w:del></w:p>"))
        assertTrue(para(d2, 6).editable)
        val d3 = DocxDocument.open(base)
        assertTrue(d3.acceptRevision("r22"))
        assertTrue(text(d3.save(), doc).contains("<w:p><w:ins w:id=\"21\" w:author=\"Ann\"><w:r><w:t>kept </w:t></w:r></w:ins></w:p>"))
    }

    @Test fun acceptAllAndRejectAllSkipFormatAndMove() {
        val base = handMade(twoAuthors)
        val a = DocxDocument.open(base)
        assertEquals(7, a.acceptAll())
        assertEquals(listOf("r17", "r19", "r20"), a.revisions().map { it.id })
        assertEquals(listOf("Costs were slightly lower by far than forecast.", "Sales grew.", "An untouched paragraph with its own change.", "formatted", "", "tracked paragraph mark", "kept "), a.blocks.map { (it as Paragraph).text })
        assertEquals(0, a.acceptAll())
        val sa = out("m5-revisions-accept-all.docx", a.save())
        assertConsistent(sa)
        val before = text(base, doc)
        // the paragraphs with only FORMAT / MOVE changes are byte-identical
        val keep = before.substring(bodyChild(before, 3).start, bodyChild(before, 5).end)
        assertTrue(text(sa, doc).contains(keep))
        assertFalse(text(sa, doc).contains("<w:ins w:id=\"1")); assertFalse(text(sa, doc).contains("w:delText"))

        val r = DocxDocument.open(base)
        assertEquals(7, r.rejectAll())
        assertEquals(listOf("Costs were flat than forecast.", "Sales really grew.", "An untouched paragraph with .", "formatted", "", "tracked paragraph mark", ""), r.blocks.map { (it as Paragraph).text })
        val sr = out("m5-revisions-reject-all.docx", r.save())
        assertConsistent(sr)
        assertTrue(text(sr, doc).contains(keep))
        assertEquals(3, DocxDocument.open(sr).revisions().size)
    }

    @Test fun aCommentOnRejectedTextKeepsItsAnchors() {
        val base = handMade(
            "<w:p>${r("Before ")}<w:ins w:id=\"40\" w:author=\"Ann\">${cStart(0)}<w:r><w:t>inserted</w:t></w:r>${cEnd(0)}${cRef(0)}</w:ins>${r(" after")}</w:p>",
            listOf(commentsPart(comment(0, "Bob", "why?"))),
        )
        val d = DocxDocument.open(base)
        assertEquals("inserted", d.comments().single().quote)
        assertTrue(d.rejectRevision("r40"))
        assertEquals(Triple(7, 7, ""), d.comments().single().let { Triple(it.start, it.end, it.quote) })
        val saved = out("m5-revision-with-comment.docx", d.save())
        assertTrue(text(saved, doc).contains("<w:p>${r("Before ")}${cStart(0)}${cEnd(0)}${cRef(0)}${r(" after")}</w:p>"))
        assertConsistent(saved)
    }

    // ================================================================================================ C. recording my own changes

    private val me = "王小明"
    private val now = "2026-11-22T10:00:00Z"
    private fun ins(a: String = me, d: String = now) = RunRev(RevisionKind.INSERT, a, d)
    private fun del(a: String = me, d: String = now) = RunRev(RevisionKind.DELETE, a, d)

    @Test fun mergeMarksWhatWasRemovedAndWhatIsNew() {
        val old = listOf(Run("The quick fox", bold = true, src = 0))
        // replace one word
        val m = Tracking.mergeAt(old, listOf(Run("The slow fox", bold = true, src = 0)), me, now)
        assertEquals(listOf(Run("The ", bold = true, src = 0), Run("quick", bold = true, src = 0, rev = del()), Run("slow", bold = true, src = 0, rev = ins()), Run(" fox", bold = true, src = 0)), m.runs)
        assertEquals("The quick".length + 4, m.caret)
        assertEquals("The quick".length, m.deletedEnd)
        // only removing: the caret goes in front of what is now struck through
        val m2 = Tracking.mergeAt(old, listOf(Run("The fox", bold = true, src = 0)), me, now)
        assertEquals("The quick fox", Tracking.text(m2.runs))
        assertEquals(listOf(null, del(), null), m2.runs.map { it.rev })
        assertEquals(4, m2.caret); assertEquals(10, m2.deletedEnd)
        // typing inside my own insertion extends it (same mark, same date)
        val later = "2026-11-22T11:00:00Z"
        val m3 = Tracking.merge(m.runs, RichText.applyEdit(m.runs.map { it.toSpan() }, "The quickslXow fox").map { it.toRun() }, me, later)
        assertEquals(listOf("The ", "quick", "slXow", " fox"), m3.map { it.text })
        assertEquals(listOf(null, del(), ins(), null), m3.map { it.rev })
        // typing right after it extends it too
        val m4 = Tracking.merge(m.runs, RichText.applyEdit(m.runs.map { it.toSpan() }, "The quickslowly fox").map { it.toRun() }, me, later)
        assertEquals(listOf("The ", "quick", "slowly", " fox"), m4.map { it.text })
        // deleting my own insertion really removes it; what was already deleted stays as it is
        val m5 = Tracking.merge(m.runs, RichText.applyEdit(m.runs.map { it.toSpan() }, "The quick fox").map { it.toRun() }, me, later)
        assertEquals(listOf(Run("The ", bold = true, src = 0), Run("quick", bold = true, src = 0, rev = del()), Run(" fox", bold = true, src = 0)), m5)
        // somebody else's insertion is not mine: deleting it is a tracked deletion by me, typing next to it starts my own insertion
        val theirs = listOf(Run("a "), Run("big ", rev = ins("Ann", "2026-01-01T00:00:00Z")), Run("cat"))
        assertEquals(listOf(null, del(), null), Tracking.merge(theirs, listOf(Run("a cat")), me, now).map { it.rev })
        val m6 = Tracking.merge(theirs, RichText.applyEdit(theirs.map { it.toSpan() }, "a big old cat").map { it.toRun() }, me, now)
        assertEquals(listOf("a ", "big ", "old ", "cat"), m6.map { it.text })
        assertEquals(listOf(null, ins("Ann", "2026-01-01T00:00:00Z"), ins(), null), m6.map { it.rev })
        // a format-only change is applied as it is
        val bolded = listOf(Run("The "), Run("quick", italic = true), Run(" fox"))
        assertEquals(bolded, Tracking.merge(old, bolded, me, now))
        // the editor never hands a tracked mark on to typed text (tracking off: nothing is marked)
        val typed = RichText.applyEdit(m.runs.map { it.toSpan() }, "The quickslow!! fox")
        assertEquals(listOf(null, del(), ins(), null), typed.map { it.rev })
        assertEquals("!! fox", typed.last().text)
        assertEquals(m.runs, Tracking.mergeSpans(old.map { it.toSpan() }, listOf(Run("The slow fox", bold = true, src = 0).toSpan()), me, now).map { it.toRun() })
    }

    @Test fun trackedEditsAreWrittenAsInsAndDelWithUniqueIds() {
        val base = docOf("The quick fox jumps.", "Second paragraph here.", "Third stays.")
        val before = text(base, doc)
        val d = DocxDocument.open(base)
        typeTracked(d, 0, "The slow fox jumps.", me, now)
        typeTracked(d, 1, "Second paragraph.", me, now)
        var saved = d.save()
        var x = text(saved, doc)
        assertEquals(
            "<w:p><w:r><w:t xml:space=\"preserve\">The </w:t></w:r><w:del w:id=\"1\" w:author=\"$me\" w:date=\"$now\"><w:r><w:delText>quick</w:delText></w:r></w:del>" +
                "<w:ins w:id=\"2\" w:author=\"$me\" w:date=\"$now\"><w:r><w:t>slow</w:t></w:r></w:ins><w:r><w:t xml:space=\"preserve\"> fox jumps.</w:t></w:r></w:p>",
            x.substring(bodyChild(x, 0).start, bodyChild(x, 0).end),
        )
        assertTrue(x, x.contains("<w:p><w:r><w:t>Second paragraph</w:t></w:r><w:del w:id=\"3\" w:author=\"$me\" w:date=\"$now\"><w:r><w:delText xml:space=\"preserve\"> here</w:delText></w:r></w:del><w:r><w:t>.</w:t></w:r></w:p>"))
        assertEquals(setOf(doc), changedParts(base, saved))
        assertTrue(x.endsWith(before.substring(bodyChild(before, 2).start)))
        assertEquals(listOf("r1|DELETE|quick", "r2|INSERT|slow", "r3|DELETE| here"), d.revisions().map { "${it.id}|${it.kind}|${it.text}" })
        assertEquals(me, d.revisions()[0].author); assertEquals(now, d.revisions()[0].date)

        // typing on: my insertion grows, ids stay; a second, separate change gets a new id
        typeTracked(d, 0, "The quickslower fox jumps.", me, "2026-11-22T10:30:00Z")
        typeTracked(d, 0, "The quickslower fox LEAPT.", me, "2026-11-22T10:31:00Z")
        saved = out("m5-tracked-typing.docx", d.save())
        assertConsistent(saved)
        x = text(saved, doc)
        assertTrue(x, x.contains("<w:ins w:id=\"2\" w:author=\"$me\" w:date=\"$now\"><w:r><w:t>slower</w:t></w:r></w:ins>"))
        val ids = Regex("<w:(?:ins|del) w:id=\"(\\d+)\"").findAll(x).map { it.groupValues[1] }.toList()
        assertEquals(ids.size, ids.toSet().size)
        assertEquals(5, ids.size)
        val re = DocxDocument.open(saved)
        assertEquals("The quickslower fox jumpsLEAPT.", para(re, 0).text)
        assertEquals("The slower fox LEAPT.", para(re, 0).cleanText)
        assertEquals(listOf(null, del(), ins(), null, del(d = "2026-11-22T10:31:00Z"), ins(d = "2026-11-22T10:31:00Z"), null), para(re, 0).runs.map { it.rev })
        // deleting my own insertion again removes it
        typeTracked(re, 0, "The quick fox jumpsLEAPT.", me, now)
        assertFalse(text(re.save(), doc).contains("slower"))
        // rejecting everything gives the original text back, accepting everything the new one
        val rj = DocxDocument.open(saved); rj.rejectAll()
        assertEquals(listOf("The quick fox jumps.", "Second paragraph here.", "Third stays."), rj.blocks.map { (it as Paragraph).text })
        val ac = DocxDocument.open(saved); ac.acceptAll()
        assertEquals(listOf("The slower fox LEAPT.", "Second paragraph.", "Third stays."), ac.blocks.map { (it as Paragraph).text })

        // with tracking off the same edit marks nothing
        val off = DocxDocument.open(base)
        type(off, 0, "The slow fox jumps.")
        assertFalse(text(off.save(), doc).contains("<w:ins")); assertFalse(text(off.save(), doc).contains("<w:del"))
        assertTrue(off.revisions().isEmpty())
    }

    @Test fun newIdsAvoidEveryIdInTheDocumentAndSplittingATrackedParagraphWorks() {
        val base = handMade("<w:p><w:bookmarkStart w:id=\"40\" w:name=\"b\"/>${r("Hello brave world")}<w:bookmarkEnd w:id=\"40\"/></w:p><w:p>${r("x")}<w:ins w:id=\"7\" w:author=\"Ann\"><w:r><w:t>y</w:t></w:r></w:ins></w:p>")
        val d = DocxDocument.open(base)
        typeTracked(d, 0, "Hello new world", me, now)
        var x = text(d.save(), doc)
        assertTrue(x, x.contains("<w:del w:id=\"41\"")); assertTrue(x, x.contains("<w:ins w:id=\"42\""))
        assertTrue("the bookmark is still around the text", x.contains("<w:p><w:bookmarkStart w:id=\"40\" w:name=\"b\"/><w:r>") && x.contains("</w:r><w:bookmarkEnd w:id=\"40\"/></w:p>"))
        // Enter inside the tracked paragraph, as the app does it: the split itself is not tracked, the marks go with their text
        val p = para(d, 0)
        assertEquals("Hello bravenew world", p.text)
        d.setParagraph(0, Tracking.slice(p.runs, 0, 9), p.style)
        val right = d.insertParagraphAfter(0, Tracking.slice(p.runs, 9, p.text.length), ParaStyle.BODY)
        assertEquals(listOf(null, del()), para(d, 0).runs.map { it.rev })
        assertEquals(listOf(del(), ins(), null), para(d, right).runs.map { it.rev })
        val saved = out("m5-tracked-split.docx", d.save())
        assertConsistent(saved)
        x = text(saved, doc)
        val ids = Regex("<w:(?:ins|del|bookmarkStart) w:id=\"(\\d+)\"").findAll(x).map { it.groupValues[1] }.toList()
        assertEquals(ids.toString(), ids.size, ids.toSet().size)
        val re = DocxDocument.open(saved)
        assertEquals(listOf("Hello bra", "venew world", "xy"), re.blocks.map { (it as Paragraph).text })
        assertEquals(listOf("Hello ", "new world", "xy"), re.blocks.map { (it as Paragraph).cleanText })
        // joining again (Backspace at the start of the second): text and marks are back in one paragraph
        re.setParagraph(0, para(re, 0).runs + para(re, 1).runs, ParaStyle.BODY)
        re.deleteBlock(1)
        assertEquals("Hello bravenew world", para(re, 0).text)
        assertEquals("Hello new world", para(DocxDocument.open(re.save()), 0).cleanText)
    }

    // ================================================================================================ D. header and footer

    @Test fun headerAndFooterAreCreatedWithTheirPartsAndReferences() {
        val base = docOf("Body text.")
        val d = DocxDocument.open(base)
        assertNull(d.headerText()); assertNull(d.footerText())
        assertTrue(d.headerEditable); assertTrue(d.footerEditable)
        assertFalse("\"\" does not create a part", d.setHeaderFooter("", ""))
        assertFalse(d.dirty)
        assertTrue(d.setHeaderFooter("内部 · 草稿", "第三季度"))
        assertEquals("内部 · 草稿", d.headerText()); assertEquals("第三季度", d.footerText())
        val saved = out("m5-header-footer.docx", d.save())
        assertConsistent(saved)
        assertEquals(setOf(doc, rels, ct, "word/header1.xml", "word/footer1.xml"), changedParts(base, saved))
        assertTrue(text(saved, "word/header1.xml").contains("<w:p><w:r><w:t>内部 · 草稿</w:t></w:r></w:p></w:hdr>"))
        assertTrue(text(saved, "word/footer1.xml").contains("<w:p><w:r><w:t>第三季度</w:t></w:r></w:p></w:ftr>"))
        val pkg = OoxmlPackage.read(saved)
        val h = Rels.of(pkg, doc).single { it.type == "$relBase/header" }
        val f = Rels.of(pkg, doc).single { it.type == "$relBase/footer" }
        assertEquals("header1.xml", h.target); assertEquals("footer1.xml", f.target)
        assertEquals("$ctBase.header+xml", XmlEx.contentType(pkg, "word/header1.xml"))
        assertEquals("$ctBase.footer+xml", XmlEx.contentType(pkg, "word/footer1.xml"))
        // the references come first in the section properties; the rest of the document part is untouched
        val x = text(saved, doc)
        val before = text(base, doc)
        val at = before.indexOf("<w:sectPr>") + "<w:sectPr>".length
        assertEquals(before.substring(0, at) + "<w:headerReference w:type=\"default\" r:id=\"${h.id}\"/><w:footerReference w:type=\"default\" r:id=\"${f.id}\"/>" + before.substring(at), x)
        val re = DocxDocument.open(saved)
        assertEquals("内部 · 草稿", re.headerText()); assertEquals("第三季度", re.footerText())
        assertFalse("the same text again changes nothing", re.setHeaderFooter("内部 · 草稿", "第三季度"))
        assertFalse(re.dirty)

        // changing an existing plain header: only its part changes, null leaves the footer alone
        assertTrue(re.setHeaderFooter("内部 · 定稿\t第 2 版", null))
        val two = out("m5-header-footer-edited.docx", re.save())
        assertEquals(setOf("word/header1.xml"), changedParts(saved, two))
        assertTrue(text(two, "word/header1.xml").contains("<w:p><w:r><w:t>内部 · 定稿</w:t><w:tab/><w:t>第 2 版</w:t></w:r></w:p>"))
        assertEquals("内部 · 定稿\t第 2 版", DocxDocument.open(two).headerText())
        // "" keeps the part and empties it
        val re2 = DocxDocument.open(two)
        assertTrue(re2.setHeaderFooter(null, ""))
        val three = re2.save()
        assertEquals(setOf("word/footer1.xml"), changedParts(two, three))
        assertEquals("", DocxDocument.open(three).footerText())
        assertTrue(text(three, "word/footer1.xml").contains("<w:ftr $nsW $nsR><w:p></w:p></w:ftr>"))
        assertConsistent(three)
    }

    @Test fun existingHeadersKeepTheirFormattingOrAreLeftAlone() {
        val hdr = Extra("header7.xml", "$relBase/header", "$ctBase.header+xml", "<w:hdr $nsW><w:p w:rsidR=\"00AA\"><w:pPr><w:pStyle w:val=\"Header\"/><w:jc w:val=\"right\"/></w:pPr><w:r><w:rPr><w:b/><w:color w:val=\"FF0000\"/></w:rPr><w:t>Old header</w:t></w:r><w:r><w:t xml:space=\"preserve\"> text</w:t></w:r></w:p></w:hdr>")
        val ftr = Extra("footer7.xml", "$relBase/footer", "$ctBase.footer+xml", "<w:ftr $nsW><w:p><w:r><w:t xml:space=\"preserve\">Page </w:t></w:r><w:r><w:fldChar w:fldCharType=\"begin\"/></w:r><w:r><w:instrText> PAGE </w:instrText></w:r><w:r><w:fldChar w:fldCharType=\"separate\"/></w:r><w:r><w:t>1</w:t></w:r><w:r><w:fldChar w:fldCharType=\"end\"/></w:r></w:p></w:ftr>")
        val first = Extra("header8.xml", "$relBase/header", "$ctBase.header+xml", "<w:hdr $nsW><w:p><w:r><w:t>First page only</w:t></w:r></w:p></w:hdr>")
        val base = handMade(
            "<w:p>${r("Body")}</w:p>", listOf(hdr, ftr, first),
            sectPr = "<w:sectPr><w:headerReference w:type=\"first\" r:id=\"rId3\"/><w:headerReference w:type=\"default\" r:id=\"rId1\"/><w:footerReference w:type=\"default\" r:id=\"rId2\"/><w:pgSz w:w=\"11906\" w:h=\"16838\"/><w:titlePg/></w:sectPr>",
        )
        val d = DocxDocument.open(base)
        assertEquals("Old header text", d.headerText()); assertTrue(d.headerEditable)
        assertEquals("Page 1", d.footerText()); assertFalse("a page-number field: shown, not changed", d.footerEditable)
        assertTrue(d.setHeaderFooter("New header", "ignored"))
        assertEquals("Page 1", d.footerText())
        val saved = out("m5-header-existing.docx", d.save())
        assertConsistent(saved)
        assertEquals(setOf("word/header7.xml"), changedParts(base, saved))
        assertEquals("<w:hdr $nsW><w:p w:rsidR=\"00AA\"><w:pPr><w:pStyle w:val=\"Header\"/><w:jc w:val=\"right\"/></w:pPr><w:r><w:rPr><w:b/><w:color w:val=\"FF0000\"/></w:rPr><w:t>New header</w:t></w:r></w:p></w:hdr>", text(saved, "word/header7.xml"))
        assertFalse(DocxDocument.open(saved).setHeaderFooter(null, "still ignored"))

        // a body without section properties gets them, with only the reference in them
        val bare = handMade("<w:p>${r("Body")}</w:p>", sectPr = "")
        val b = DocxDocument.open(bare)
        assertTrue(b.setHeaderFooter(null, "Footer only"))
        val bs = out("m5-footer-no-sectpr.docx", b.save())
        assertConsistent(bs)
        assertTrue(text(bs, doc).endsWith("<w:p>${r("Body")}</w:p><w:sectPr><w:footerReference w:type=\"default\" r:id=\"rId1\"/></w:sectPr></w:body></w:document>"))
        assertEquals("Footer only", DocxDocument.open(bs).footerText())
        assertNull(DocxDocument.open(bs).headerText())
        // a header added later goes in front of the footer reference
        val b2 = DocxDocument.open(bs)
        assertTrue(b2.setHeaderFooter("Header later", null))
        assertTrue(text(b2.save(), doc).contains("<w:sectPr><w:headerReference w:type=\"default\" r:id=\"rId2\"/><w:footerReference w:type=\"default\" r:id=\"rId1\"/></w:sectPr>"))
        assertConsistent(b2.save())
    }

    @Test fun headerOnTheReportFixtureLeavesEveryOtherPartAlone() {
        val d = DocxDocument.open(src)
        val had = d.headerText()
        assertTrue(d.setHeaderFooter((had ?: "") + "内部", null))
        val saved = out("m5-header-on-report.docx", d.save())
        assertConsistent(saved)
        val changed = changedParts(src, saved)
        assertTrue(changed.toString(), changed.all { it == doc || it == rels || it == ct || it.startsWith("word/header") })
        assertEquals((had ?: "") + "内部", DocxDocument.open(saved).headerText())
        assertEquals(DocxDocument.open(src).blocks, DocxDocument.open(saved).blocks)
    }

    // ================================================================================================ E. format painter

    @Test fun paintCopiesTheWholeRunFormatting() {
        val rPr = "<w:rPr><w:rFonts w:ascii=\"Georgia\" w:hAnsi=\"Georgia\"/><w:b/><w:color w:val=\"FF0000\"/><w:sz w:val=\"32\"/><w:szCs w:val=\"32\"/></w:rPr>"
        val base = handMade("<w:p>${r("Source A", rPr)}</w:p><w:p>${r("Target ")}${r("B has", "<w:rPr><w:i/></w:rPr>")}${r(" three runs")}</w:p><w:p>${r("Untouched")}</w:p>")
        val before = text(base, doc)
        val d = DocxDocument.open(base)
        val fmt = d.runFormatAt(0, 3)
        assertTrue(fmt.bold); assertFalse(fmt.italic); assertEquals(0xFF0000, fmt.color); assertEquals(32, fmt.size)
        assertTrue(d.paintFormat(fmt, 1, 0, 0))   // start == end: the whole paragraph
        val saved = out("m5-format-painter.docx", d.save())
        assertEquals(setOf(doc), changedParts(base, saved))
        val el = bodyChild(before, 1)
        val seg = assertOnlyRangeChanged(before, text(saved, doc), el.start, el.end)
        assertEquals("<w:p><w:r>$rPr<w:t>Target B has three runs</w:t></w:r></w:p>", seg)
        for (dd in listOf(d, DocxDocument.open(saved))) {
            val p = para(dd, 1)
            assertEquals("Target B has three runs", p.text)
            assertTrue(p.runs.all { it.bold && !it.italic && it.color == 0xFF0000 && it.size == 32 })
        }

        // a part of a paragraph, several times, after the source changed: the picked-up format is a copy
        val d2 = DocxDocument.open(base)
        val f2 = d2.runFormatAt(0, 0)
        d2.setParagraph(0, listOf(Run("Source now plain")), ParaStyle.BODY)
        assertTrue(d2.paintFormat(f2, 1, 7, 12))
        assertTrue(d2.paintFormat(f2, 2, 2, 5))
        val s2 = out("m5-format-painter-range.docx", d2.save())
        val x = text(s2, doc)
        assertTrue(x, x.contains("<w:p>${r("Target ")}<w:r>$rPr<w:t>B has</w:t></w:r>${r(" three runs")}</w:p>"))
        assertTrue(x, x.contains("<w:p><w:r><w:t>Un</w:t></w:r><w:r>$rPr<w:t>tou</w:t></w:r><w:r><w:t>ched</w:t></w:r></w:p>"))
        val re = DocxDocument.open(s2)
        assertEquals(listOf(false, true, false), para(re, 1).runs.map { it.bold })
        assertEquals(listOf(false, false, false), para(re, 1).runs.map { it.italic })
        assertEquals(listOf(null, 32, null), para(re, 2).runs.map { it.size })
        // the painted paragraph is edited on like any other; the format stays with its characters
        type(re, 2, "Untouched!")
        type(d2, 2, "Untouched!")
        assertEquals(text(re.save(), doc), text(d2.save(), doc))
        // painting plain formatting takes the formatting away; one call does pick up and paint
        assertTrue(re.paintFormat(2, 0, 1, 0, 0))
        assertTrue(text(re.save(), doc).contains("<w:p><w:r><w:t>Target B has three runs</w:t></w:r></w:p>"))
        assertFalse("an empty paragraph has nothing to paint", DocxDocument.blank().let { it.paintFormat(f2, it.blocks.first().id, 0, 0) })
    }

    @Test fun paintKeepsCommentsLinksAndTrackedMarksOfTheTarget() {
        val d = DocxDocument.open(docOf("Bold source", "Alpha beta gamma"))
        val p0 = para(d, 0)
        d.setParagraph(0, listOf(p0.runs[0].copy(bold = true, color = 0x00AA00)), p0.style)
        val c = d.addComment(1, 6, 10, "Ann", "about beta", "")
        typeTracked(d, 1, "Alpha beta DELTA", me, now)
        assertTrue(d.paintFormat(d.runFormatAt(0, 0), 1, 3, 13))
        val re = DocxDocument.open(out("m5-format-painter-marks.docx", d.save()))
        assertConsistent(re.save())
        val p = para(re, 1)
        assertEquals("Alpha beta gammaDELTA", p.text)
        assertEquals(Triple(6, 10, "beta"), re.comments().single { it.id == c }.let { Triple(it.start, it.end, it.quote) })
        assertEquals(listOf("Alp", "ha ", "beta", " ", "ga", "mma", "DELTA"), p.runs.map { it.text })
        assertEquals(listOf(false, true, true, true, true, false, false), p.runs.map { it.bold })
        assertEquals(listOf(null, null, null, null, del(), del(), ins()), p.runs.map { it.rev })
    }

    // ================================================================================================ the save rule

    @Test fun readingReviewDataNeverChangesAFile() {
        for (bytes in listOf(src, handMade(twoAuthors), docOf("a", "b"))) {
            val d = DocxDocument.open(bytes)
            d.comments(); d.revisions(); d.headerText(); d.footerText(); d.headerEditable; d.footerEditable
            d.blocks.filterIsInstance<Paragraph>().firstOrNull()?.let { d.runFormatAt(it.id, 0) }
            assertFalse(d.dirty)
            assertArrayEquals(bytes, d.save())
        }
    }

    @Test fun everyOperationLeavesUntouchedPartsByteIdentical() {
        val heavy = setOf("word/styles.xml", "word/numbering.xml", "word/settings.xml", "word/theme/theme1.xml", "word/media/image1.png", "word/fontTable.xml", "docProps/core.xml", "customXml/item1.xml")
        fun check(name: String, allowed: Set<String>, op: (DocxDocument) -> Unit) {
            val d = DocxDocument.open(src)
            op(d)
            val saved = out(name, d.save())
            assertConsistent(saved)
            val changed = changedParts(src, saved)
            assertTrue("$name changed $changed", allowed.containsAll(changed))
            assertTrue(name, changed.none { it in heavy })
            // and what was saved opens again and saves to the same bytes
            assertArrayEquals(saved, DocxDocument.open(saved).save())
        }
        check("m5-report-comment.docx", setOf(doc, cPart, exPart, rels, ct)) { it.addComment(13, 0, 4, me, "新批注", now) }
        check("m5-report-reply.docx", setOf(doc, cPart, exPart, rels, ct)) { it.replyComment("0", me, "回复", now) }
        check("m5-report-resolve.docx", setOf(cPart, exPart, rels, ct)) { it.setCommentResolved("0", true) }
        check("m5-report-edit-comment.docx", setOf(cPart)) { it.editComment("0", "Checked.") }
        check("m5-report-delete-comment.docx", setOf(doc, cPart)) { it.deleteComment("0") }
        check("m5-report-accept.docx", setOf(doc)) { d -> assertTrue(d.acceptRevision(d.revisions().first { it.kind == RevisionKind.INSERT }.id)) }
        check("m5-report-reject.docx", setOf(doc)) { d -> assertTrue(d.rejectRevision(d.revisions().first { it.kind == RevisionKind.DELETE }.id)) }
        check("m5-report-tracked.docx", setOf(doc)) { typeTracked(it, 13, "tracked " + para(it, 13).text, me, now) }
        check("m5-report-paint.docx", setOf(doc)) { it.paintFormat(1, 0, 13, 0, 0) }

        // on the report: accept the insertion, reject the deletion — only that paragraph's text differs
        val d = DocxDocument.open(src)
        assertEquals(listOf("r101|DELETE|flat|18", "r102|INSERT|slightly lower|18"), d.revisions().map { "${it.id}|${it.kind}|${it.text}|${it.block}" })
        assertTrue(d.acceptRevision("r102")); assertTrue(d.rejectRevision("r101"))
        val before = text(src, doc)
        val el = bodyChild(before, 18)
        val seg = assertOnlyRangeChanged(before, text(d.save(), doc), el.start, el.end)
        assertTrue(seg, seg.endsWith("<w:r><w:t xml:space=\"preserve\">Costs were </w:t></w:r><w:r><w:t>flat</w:t></w:r><w:r><w:t>slightly lower</w:t></w:r><w:r><w:t xml:space=\"preserve\"> than forecast.</w:t></w:r></w:p>"))
        assertTrue(d.revisions().isEmpty())
    }
}
