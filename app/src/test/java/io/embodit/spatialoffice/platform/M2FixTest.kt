package io.embodit.spatialoffice.platform

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import io.embodit.spatialoffice.core.pptx.PptxDeck
import io.embodit.spatialoffice.core.pptx.Shape
import io.embodit.spatialoffice.core.pptx.TextPara
import io.embodit.spatialoffice.core.text.RichText
import io.embodit.spatialoffice.ui.slides.BulletMarks
import io.embodit.spatialoffice.ui.slides.richShapeText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipInputStream

/**
 * v0.4.1 (M2 fix, QA #10): q10-1 decks with bullets on two or more paragraphs open and draw (BulletMarks no longer
 * lays overlapping paragraph styles), bullets on / off across paragraphs; q10-2 a line break inside a paragraph
 * (a:br) stays one through find / replace, typing and formatting.
 */
class M2FixTest {

    private fun res(name: String): ByteArray = javaClass.classLoader!!.getResourceAsStream("qa/$name")!!.readBytes()

    private fun part(bytes: ByteArray, name: String): String {
        ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
            while (true) { val e = z.nextEntry ?: break; if (e.name == name) return String(z.readBytes(), Charsets.UTF_8) }
        }
        error("no $name")
    }

    /** What the slides editor draws for a box (editing canvas and thumbnails): styled text through the bullet marks. */
    private fun draw(s: DeckSession, sh: Shape): AnnotatedString {
        val rich = richShapeText(s.spansOf(sh), sh.paras, 1f, TextStyle.Default)
        val shown = BulletMarks(sh.paras).filter(rich).text
        assertNoOverlap(shown)
        return shown
    }

    private fun assertNoOverlap(a: AnnotatedString) {
        val ps = a.paragraphStyles.sortedBy { it.start }
        for (k in 1 until ps.size) assertTrue("paragraph styles overlap: ${ps[k - 1].start}..${ps[k - 1].end} / ${ps[k].start}..${ps[k].end}", ps[k].start >= ps[k - 1].end)
        ps.forEach { assertTrue(it.start <= it.end && it.end <= a.length) }
    }

    private fun drawAll(s: DeckSession) {
        s.deck.slides.forEach { sl -> sl.shapes.filter { it.paras.isNotEmpty() }.forEach { draw(s, it) } }
    }

    /** A box with [n] paragraphs, all bulleted, read back from the saved file (every paragraph then has an alignment). */
    private fun bulletedBox(vararg lines: String): Pair<DeckSession, Int> {
        val s = DeckSession(FileRef.Path(File("t.pptx")), PptxDeck.blank().save())
        s.addTextBox(lines.joinToString("\n"))
        val id = s.selected
        assertTrue(s.setParaFormat(bullet = true))
        val r = DeckSession(FileRef.Path(File("t.pptx")), s.serialize()); r.selected = id
        return r to id
    }

    private fun shape(s: DeckSession, id: Int) = s.deck.slides[s.current].shapes.first { it.id == id }

    // ---- q10-1

    @Test fun fiveBulletedParagraphsWithAlignmentDraw() {
        val (s, id) = bulletedBox("Revenue up", "Costs flat", "Hiring two", "Ship M2", "Plan M3")
        val sh = shape(s, id)
        assertEquals(5, sh.paras.size)
        assertTrue(sh.paras.all { it.bullet && it.align != null })
        val shown = draw(s, sh).text
        assertEquals(5, Regex("• ").findAll(shown).count())
        assertEquals("• Revenue up\n• Costs flat\n• Hiring two\n• Ship M2\n• Plan M3", shown)
        assertEquals(5, BulletMarks(sh.paras).filter(richShapeText(s.spansOf(sh), sh.paras, 1f, TextStyle.Default)).text.paragraphStyles.size)
    }

    @Test fun caretMapsAroundBullets() {
        val (s, id) = bulletedBox("ab", "cd")
        val sh = shape(s, id)
        val tt = BulletMarks(sh.paras).filter(richShapeText(s.spansOf(sh), sh.paras, 1f, TextStyle.Default))
        val m = tt.offsetMapping
        assertEquals(2, m.originalToTransformed(0))   // after "• "
        assertEquals(7, m.originalToTransformed(3))   // "c" after "• ab\n• "
        for (o in 0..5) assertEquals(o, m.transformedToOriginal(m.originalToTransformed(o)))
    }

    @Test fun qaDecksOpenAndEverySlideDraws() {
        for (name in listOf("QA9-deck.pptx", "QA10-mixed.pptx", "QA10-runs2.pptx")) {
            val s = DeckSession(FileRef.Path(File(name)), res(name))
            drawAll(s)
        }
    }

    @Test fun qa10MixedBodyBulletedAcrossParagraphsAndBack() {
        val s = DeckSession(FileRef.Path(File("QA10-mixed.pptx")), res("QA10-mixed.pptx"))
        s.current = 1
        val body = s.deck.slides[1].shapes.first { it.paras.size >= 5 }
        s.selected = body.id
        val text = RichText.text(s.spansOf(body))
        // the body inherits bullets from the master: all five paragraphs are bulleted as opened, and draw
        assertTrue(body.paras.all { it.bullet })
        assertEquals(5, Regex("• ").findAll(draw(s, body).text).count())
        // a selection from the middle of paragraph 1 into paragraph 2 (what QA did through [Text ▾] › Bullets): off, then on
        s.textSel = DeckSession.TextSel(1, body.id, 5, text.indexOf('\n') + 4)
        assertTrue(s.setParaFormat(bullet = false))
        val cut = shape(s, body.id)
        assertEquals(listOf(false, false, true, true, true), cut.paras.map { it.bullet })
        draw(s, cut)
        assertTrue(s.setParaFormat(bullet = true))
        val on = shape(s, body.id)
        assertTrue(on.paras.all { it.bullet })
        draw(s, on)
        // off and on again gives the layout's bullet back as it was: no zero margin / indent left behind
        val firstP = Regex("<a:p>.*?</a:p>").findAll(part(s.serialize(), "ppt/slides/slide2.xml")).map { it.value }.first { it.contains("Grow") }
        assertFalse(firstP, firstP.contains("marL=\"0\"") || firstP.contains("buNone"))
        // everything bulleted, then all off again
        s.textSel = null
        s.setParaFormat(bullet = true)
        val all = shape(s, body.id)
        assertTrue(all.paras.all { it.bullet })
        assertEquals(all.paras.size, Regex("• ").findAll(draw(s, all).text).count())
        assertTrue(s.setParaFormat(bullet = false))
        val off = shape(s, body.id)
        assertTrue(off.paras.none { it.bullet })
        assertFalse(draw(s, off).text.contains("• "))
        // runs untouched through all of it
        val back = PptxDeck.open(s.serialize()).slides[1].shapes.first { it.id == body.id }
        assertTrue(back.paras[0].runs!!.first { it.text.startsWith("Grow") }.bold)
        assertEquals("降低流失率 budget", back.paras[3].text)
    }

    @Test fun emptyParagraphsAndLastParagraphWithoutBreak() {
        // an empty paragraph in the middle and at the end, bulleted
        val (s, id) = bulletedBox("one", "", "three", "")
        val sh = shape(s, id)
        assertEquals(4, sh.paras.size)
        val shown = draw(s, sh).text
        assertEquals("• one\n• \n• three\n• ", shown)
        // the last paragraph has no "\n" after it: its alignment still ends at the text's end
        val (s2, id2) = bulletedBox("alpha", "beta")
        val sh2 = shape(s2, id2)
        val t2 = BulletMarks(sh2.paras).filter(richShapeText(s2.spansOf(sh2), sh2.paras, 1f, TextStyle.Default)).text
        assertEquals(t2.length, t2.paragraphStyles.maxOf { it.end })
        assertEquals("• alpha\n".length, t2.paragraphStyles.sortedBy { it.start }[1].start)
    }

    @Test fun overlappingInputStylesAreClipped() {
        // paragraph styles that already overlap or start mid-paragraph never reach Compose overlapping
        val paras = listOf(TextPara("ab", bullet = true), TextPara("cd", bullet = true))
        val src = AnnotatedString.Builder().apply {
            append("ab\ncd")
            addStyle(androidx.compose.ui.text.ParagraphStyle(), 0, 5)
            addStyle(androidx.compose.ui.text.ParagraphStyle(), 3, 5)
        }
        val shown = runCatching { src.toAnnotatedString() }.getOrNull() ?: return  // Compose itself refuses this input
        assertNoOverlap(BulletMarks(paras).filter(shown).text)
    }

    @Test fun typingEnterInBulletedBoxAddsABulletedParagraph() {
        val (s, id) = bulletedBox("first", "second")
        s.setText(s.current, id, "first\nnew\nsecond")
        val sh = shape(s, id)
        assertEquals(listOf("first", "new", "second"), sh.paras.map { it.text })
        draw(s, sh)
    }

    // ---- q10-2

    private fun titleOf(s: DeckSession) = s.deck.slides[1].shapes.first { it.paras.any { p -> p.text.contains("quarter") } }

    @Test fun replaceInTitleKeepsTheLineBreak() {
        val s = DeckSession(FileRef.Path(File("QA10-mixed.pptx")), res("QA10-mixed.pptx"))
        val t0 = titleOf(s)
        assertEquals(1, t0.paras.size)
        assertEquals("Goals for\nnext quarter", t0.paras[0].text)
        val hit = s.findHits("Goals", matchCase = true).first { it.a == 1 && it.b == t0.id }
        assertTrue(s.replaceHit(hit, "Goals", "Aims", matchCase = true))
        val t1 = titleOf(s)
        assertEquals(1, t1.paras.size)
        assertEquals("Aims for\nnext quarter", t1.paras[0].text)
        val xml = part(s.serialize(), "ppt/slides/slide2.xml")
        val title = Regex("<p:txBody>.*?</p:txBody>").find(xml)!!.value
        assertEquals(1, Regex("<a:p>").findAll(title).count())
        assertTrue(title, title.contains("<a:br><a:rPr lang=\"en-US\" dirty=\"0\"/></a:br>"))
        assertTrue(title.contains("<a:t>Aims for</a:t>") && title.contains("<a:t>next quarter</a:t>"))
        // and back from disk it is still one paragraph with a line break
        val back = PptxDeck.open(s.serialize()).slides[1].shapes.first { it.id == t0.id }
        assertEquals(listOf("Aims for\nnext quarter"), back.paras.map { it.text })
    }

    @Test fun replaceAllAcrossTheBreakAndHitOffsets() {
        val s = DeckSession(FileRef.Path(File("QA10-mixed.pptx")), res("QA10-mixed.pptx"))
        val t0 = titleOf(s)
        // the hit after the break points at the same characters the editor shows
        val hit = s.findHits("next", matchCase = true).first { it.b == t0.id }
        val shown = RichText.text(s.spansOf(t0))
        assertEquals("next", shown.substring(hit.start, hit.start + hit.len))
        assertEquals(1, shown.count { it == SOFT_BREAK }); assertFalse(shown.contains('\n'))
        assertTrue(s.replaceAllHits("quarter", "year", matchCase = true) >= 1)
        assertEquals(listOf("Goals for\nnext year"), s.deck.slides[1].shapes.first { it.id == t0.id }.paras.map { it.text })
    }

    @Test fun typingAndFormattingKeepTheLineBreak() {
        val s = DeckSession(FileRef.Path(File("QA10-mixed.pptx")), res("QA10-mixed.pptx"))
        s.current = 1
        val t0 = titleOf(s)
        s.selected = t0.id
        // typing as the field reports it (its text holds the soft break)
        val typed = RichText.text(s.spansOf(t0)).replace("Goals", "Big goals")
        s.setText(1, t0.id, typed)
        assertEquals(listOf("Big goals for\nnext quarter"), titleOf(s).paras.map { it.text })
        // bold over the whole title: still one paragraph
        s.textSel = null
        assertTrue(s.formatText { sp, a, b -> RichText.setAttr(sp, a, b, io.embodit.spatialoffice.core.text.Attr.BOLD, true) })
        assertEquals(1, titleOf(s).paras.size)
        assertEquals(1, PptxDeck.open(s.serialize()).slides[1].shapes.first { it.id == t0.id }.paras.size)
        // drawn: the break is a new line, no bullet in front of it
        val shown = draw(s, titleOf(s)).text
        assertEquals("Big goals for\nnext quarter", shown)
    }

    @Test fun paragraphsAndBreaksTogether() {
        // two paragraphs, the first with a line break: Enter makes paragraphs, the break stays inside paragraph 1
        val s = DeckSession(FileRef.Path(File("t.pptx")), PptxDeck.blank().save())
        s.addTextBox("x")
        val id = s.selected
        s.deck.setShapeText(0, id, listOf(TextPara("a\nb", bullet = true), TextPara("c", bullet = true)))
        val sh = shape(s, id)
        val flat = RichText.text(s.spansOf(sh))
        assertEquals("a${SOFT_BREAK}b\nc", flat)
        assertEquals(sh.paras.map { it.text to it.bullet }, s.parasFrom(sh, s.spansOf(sh)).map { it.text to it.bullet })
        assertEquals("• a\nb\n• c", draw(s, sh).text)
    }
}
