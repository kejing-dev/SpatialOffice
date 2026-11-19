package io.embodit.spatialoffice.core

import io.embodit.spatialoffice.core.layout.SlideFit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * QA #6 q6-1: a 4:3 .pptx left the speaker-notes label, the notes field and the picked-object hint no height in the
 * slides window. The editor column is laid out by [SlideFit.editor]; here its notes area is checked for every page
 * proportion, inside the window as it opens (1360 × 860 dp) and at the smallest size the window can be given.
 */
class SlideFitTest {
    // EMU page sizes
    private val wide = 12192000L to 6858000L       // 16:9, 13.33 × 7.5 in
    private val classic = 9144000L to 6858000L     // 4:3, 10 × 7.5 in (the QA6-A / QA6-B files)
    private val sixteenTen = 9144000L to 5715000L  // 16:10
    private val tall = 6858000L to 9144000L        // 3:4, 7.5 × 10 in
    private val phone = 6858000L to 12192000L      // 9:16
    private val banner = 12192000L to 3048000L     // 4:1
    private val pages = mapOf("16:9" to wide, "4:3" to classic, "16:10" to sixteenTen, "3:4 tall" to tall, "9:16 tall" to phone, "4:1 banner" to banner)

    /** The slides window in dp: title bar 95 (measured on QA #6 screenshots), side padding 32, bottom 52, thumbnails 208 + 20. */
    private data class Window(val w: Float, val h: Float) {
        val columnX get() = 32f + 208f + 20f
        val columnY get() = TITLE_BAR
        val columnW get() = w - 32f - 32f - 208f - 20f
        val columnH get() = h - TITLE_BAR - 52f
    }
    private val asOpened = Window(1360f, 860f)
    private val smallest = Window(1300f, 820f)
    private val windows = mapOf("as opened" to asOpened, "smallest" to smallest, "enlarged" to Window(1800f, 1100f))

    private fun layout(win: Window, page: Pair<Long, Long>) = SlideFit.editor(win.columnW, win.columnH, page.first, page.second)

    @Test fun fourToThreeSixteenToNineAndTallPagesKeepTheNotesAreaInsideTheWindow() {
        for ((wn, win) in windows) for ((pn, page) in pages) {
            val e = layout(win, page)
            val what = "$pn, window $wn: $e notesY=${e.notesY} notesH=${e.notesH}"
            assertTrue(what, e.fits)
            // bounds in the window
            val canvasBottom = win.columnY + e.canvas.h
            val labelTop = win.columnY + e.labelY
            val notesTop = win.columnY + e.notesY
            val notesBottom = notesTop + e.notesH
            val notesLeft = win.columnX + e.rowX
            val notesRight = notesLeft + e.rowW
            assertTrue(what, labelTop >= canvasBottom + SlideFit.GAP_CANVAS - 0.01f)
            assertTrue(what, notesTop >= labelTop + SlideFit.LABEL_H)
            assertTrue(what, e.notesH >= SlideFit.NOTES_MIN_H - 0.01f)
            assertTrue(what, notesBottom <= win.h - 52f + 0.01f)
            assertTrue(what, notesLeft >= 0f && notesRight <= win.w - 32f + 0.01f)
            // the canvas is inside the column too, at the page's own proportion
            assertTrue(what, e.canvasX >= -0.01f && e.canvasX + e.canvas.w <= win.columnW + 0.01f)
            assertEquals(what, page.first.toFloat() / page.second, e.canvas.w / e.canvas.h, 0.001f)
        }
    }

    @Test fun theSmallerOfTheTwoRatiosDecides() {
        // 4:3 in the window as opened: the height is the limit, not the 960 dp width
        val c = layout(asOpened, classic)
        assertTrue("${c.canvas}", c.canvas.w < SlideFit.CANVAS_MAX_W - 100f)
        assertEquals(asOpened.columnH - SlideFit.RESERVED_H, c.canvas.h, 0.01f)
        // a banner: the width is the limit, the notes field takes the height left over
        val b = layout(asOpened, banner)
        assertEquals(SlideFit.CANVAS_MAX_W, b.canvas.w, 0.01f)
        assertTrue(b.notesH > 300f)
        // generic: never larger than the space on either side, and touching one of them
        for ((_, page) in pages) for ((aw, ah) in listOf(700f to 394f, 512f to 520f, 960f to 540f, 170f to 128f)) {
            val s = SlideFit.fit(aw, ah, page.first, page.second)
            assertTrue(s.w <= aw + 0.01f && s.h <= ah + 0.01f)
            assertTrue(kotlin.math.abs(s.w - aw) < 0.01f || kotlin.math.abs(s.h - ah) < 0.01f)
        }
    }

    @Test fun sixteenToNineLooksAsItDidInV025() {
        // 960 × 540 canvas, the notes field takes the rest: nothing moved for the files that were right before
        val e = layout(asOpened, wide)
        assertEquals(960f, e.canvas.w, 0.01f)
        assertEquals(540f, e.canvas.h, 0.01f)
        val old = SlideFit.editorV025(asOpened.columnW, asOpened.columnH, wide.first, wide.second)
        assertEquals(old.canvas, e.canvas)
        assertEquals(old.notesH, e.notesH, 0.01f)
    }

    @Test fun theV025RuleLeftA4to3PageNoNotesFieldThatIsWhatQaSaw() {
        val old = SlideFit.editorV025(asOpened.columnW, asOpened.columnH, classic.first, classic.second)
        assertEquals(720f, old.canvas.h, 0.01f)
        assertTrue("notes field height under the 960-wide rule: ${old.notesH}", old.notesH <= 0f)
        assertFalse(old.fits)
        assertTrue(layout(asOpened, classic).fits)
    }

    @Test fun rowWithTheLabelAndTheHintIsAsWideForANarrowPageAsForAWideOne() {
        // "Speaker notes (…)" and "Picked: drag to move, …" share one row; a tall page must not squeeze it
        val a = layout(asOpened, wide); val b = layout(asOpened, phone)
        assertEquals(a.rowW, b.rowW, 0.01f)
        assertEquals(960f, b.rowW, 0.01f)
        assertTrue(b.canvas.w < 400f)
    }

    @Test fun thumbnailsFollowThePageAndTheReorderDragStepsByTheirRealHeight() {
        assertEquals(170f * 9f / 16f, SlideFit.thumb(wide.first, wide.second).h, 0.01f)
        assertEquals(127.5f, SlideFit.thumb(classic.first, classic.second).h, 0.01f)
        for ((pn, page) in pages) {
            val t = SlideFit.thumb(page.first, page.second)
            assertTrue("$pn $t", t.w <= SlideFit.THUMB_W + 0.01f && t.h <= SlideFit.THUMB_MAX_H + 0.01f)
            val step = SlideFit.thumbStep(page.first, page.second)
            // dragging by exactly k rows lands k rows on, in both directions; less than half a row stays
            for (k in -3..3) assertEquals("$pn k=$k", (4 + k), SlideFit.dropIndex(4, k * step, 9, page.first, page.second))
            assertEquals(4, SlideFit.dropIndex(4, step * 0.45f, 9, page.first, page.second))
            assertEquals(0, SlideFit.dropIndex(1, -10 * step, 9, page.first, page.second))
            assertEquals(8, SlideFit.dropIndex(7, 10 * step, 9, page.first, page.second))
        }
        // v0.2.5 stepped by the 16:9 height for every file: two 4:3 rows down came out as three
        val oldStep = 170f * 9f / 16f + 6f + 16f
        assertEquals(3, 0 + Math.round(2 * SlideFit.thumbStep(classic.first, classic.second) / oldStep))
    }

    @Test fun stagePieceRehearsalScreenAndNextSlideKeepAnyPageWhole() {
        for ((pn, page) in pages) {
            val piece = SlideFit.fit(700f, 394f, page.first, page.second)
            assertTrue("$pn piece $piece", piece.w <= 700.01f && piece.h <= 394.01f)
            val screen = SlideFit.fit(960f, 540f, page.first, page.second)
            assertTrue("$pn screen $screen", screen.w <= 960.01f && screen.h <= 540.01f)
        }
        // 16:9 as before
        assertEquals(700f, SlideFit.fit(700f, 394f, wide.first, wide.second).w, 0.01f)
        assertEquals(960f, SlideFit.fit(960f, 540f, wide.first, wide.second).w, 0.01f)
    }

    private companion object { const val TITLE_BAR = 95f }
}
