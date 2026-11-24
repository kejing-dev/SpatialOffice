package io.embodit.spatialoffice.core.layout

import io.embodit.spatialoffice.core.files.FileKind
import io.embodit.spatialoffice.core.pptx.Chapters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.atan2

/** The storyline by chapters: how slides fall into chapters and where the chapter in front and the piles stand. */
class ChaptersTest {
    private fun az(p: PanelPose): Double = Math.toDegrees(atan2(p.x.toDouble(), -p.z.toDouble()))

    @Test
    fun chaptersFromTheSlidesThatStartOne() {
        val c = Chapters.ofStarts(10, listOf(4, 7), mapOf(4 to "Plan", 7 to "Next"), "titles")
        assertEquals(listOf(0 to 4, 4 to 3, 7 to 3), c.map { it.first to it.count })
        assertEquals(listOf("", "Plan", "Next"), c.map { it.name })
        assertEquals(1, Chapters.indexOf(c, 5)); assertEquals(2, Chapters.indexOf(c, 9)); assertEquals(0, Chapters.indexOf(c, 0))
        assertTrue(6 in c[1] && 7 !in c[1])
        assertEquals(listOf(0 to 5, 5 to 5, 10 to 2), Chapters.groups(12).map { it.first to it.count })
        assertEquals(emptyList<Any>(), Chapters.groups(0))
    }

    @Test
    fun theChapterInFrontIsOnTheArcTheOthersArePilesAtItsSides() {
        val chapters = listOf(0 to 3, 3 to 4, 7 to 2, 9 to 5)
        val p = LayoutRules.chapterPoses(FileKind.SLIDES, chapters, focus = 4, radiusSetting = 1.5f)
        // chapter 2 (slides 3..6) laid out, slide 4 straight ahead; piles = first slides of chapters 1, 3 and 4
        assertEquals(setOf(3, 4, 5, 6, 0, 7, 9), p.keys)
        assertEquals(0.0, az(p.getValue(4)), 1e-3)
        assertTrue(az(p.getValue(3)) < 0 && az(p.getValue(5)) > 0 && az(p.getValue(6)) > az(p.getValue(5)))
        for (i in 3..6) assertEquals(1f, p.getValue(i).scale, 0f)
        // earlier chapter: a pile on the left, beyond the chapter's first slide; later chapters on the right, in order, further out
        assertTrue(az(p.getValue(0)) < az(p.getValue(3)))
        assertTrue(az(p.getValue(7)) > az(p.getValue(6)) && az(p.getValue(9)) > az(p.getValue(7)))
        for (i in listOf(0, 7, 9)) { assertEquals(LayoutRules.PILE_SCALE, p.getValue(i).scale, 0f); assertTrue(p.getValue(i).y < 0f) }
        // a pile does not overlap the slide next to it: centres at least half of each width apart (on the circle)
        val r = LayoutRules.radius(FileKind.SLIDES, 1.5f); val w = LayoutRules.panelSize(FileKind.SLIDES).width
        val need = Math.toDegrees(2 * Math.atan(((w + w * LayoutRules.PILE_SCALE) / 4.0) / r))
        assertTrue(az(p.getValue(7)) - az(p.getValue(6)) > need)
        assertTrue(az(p.getValue(3)) - az(p.getValue(0)) > need)
        // every pose faces the seat: the same distance from it
        for (v in p.values) assertEquals(r.toDouble(), Math.hypot(v.x.toDouble(), v.z.toDouble()), 1e-3)
    }

    @Test
    fun aLongChapterShowsAWindowAroundTheSlideInFrontAndOnlyTheNearestPiles() {
        val chapters = listOf(0 to 2, 2 to 2, 4 to 2, 6 to 20, 26 to 2, 28 to 2, 30 to 2)
        val p = LayoutRules.chapterPoses(FileKind.SLIDES, chapters, focus = 15, radiusSetting = 1.5f)
        val laid = p.keys.filter { it in 6..25 }
        assertEquals(LayoutRules.CHAPTER_WINDOW, laid.size)
        assertEquals((12..18).toList(), laid.sorted())
        // two piles on each side at the most: chapters starting at 2 and 4, and at 26 and 28
        assertEquals(setOf(2, 4, 26, 28), p.keys - laid.toSet())
        // first slide of its chapter in front: the window starts there
        val q = LayoutRules.chapterPoses(FileKind.SLIDES, chapters, focus = 6, radiusSetting = 1.5f)
        assertEquals((6..12).toList(), q.keys.filter { it in 6..25 }.sorted())
        assertTrue(abs(az(q.getValue(6))) < 1e-3)
        assertEquals(emptyMap<Int, PanelPose>(), LayoutRules.chapterPoses(FileKind.SLIDES, emptyList(), 0, 1.5f))
    }
}

/** Where a deck's chapters come from: its own sections, else its title slides, else groups of five. */
class DeckChaptersTest {
    private fun deck(name: String) = io.embodit.spatialoffice.core.pptx.PptxDeck.open(
        checkNotNull(javaClass.getResourceAsStream("/fixtures/$name")) { name }.use { it.readBytes() })

    @Test
    fun theFilesOwnSectionsAreTheChapters() {
        val c = deck("chapters-sections.pptx").chapters()
        assertEquals(listOf(0 to 3, 3 to 5, 8 to 4), c.map { it.first to it.count })
        assertEquals(listOf("开场 / Opening", "数据 / Numbers", "下一步 / Next"), c.map { it.name })
        assertTrue(c.all { it.source == "sections" })
    }

    @Test
    fun withoutSectionsTitleSlidesStartTheChapters() {
        val c = deck("chapters-titles.pptx").chapters()
        assertEquals(listOf(0 to 4, 4 to 5, 9 to 5), c.map { it.first to it.count })
        assertEquals(listOf("空间办公 季度汇报 / Quarterly review", "增长 / Growth", "计划 / Plan"), c.map { it.name })
        assertTrue(c.all { it.source == "titles" })
    }

    @Test
    fun withNothingToGoByFiveSlidesMakeAChapter() {
        val c = deck("chapters-plain.pptx").chapters()
        assertEquals(listOf(0 to 5, 5 to 5, 10 to 2), c.map { it.first to it.count })
        assertTrue(c.all { it.source == "groups" && it.name.isEmpty() })
        // every slide is in exactly one chapter, in order
        assertEquals((0 until 12).toList(), c.flatMap { (it.first..it.last).toList() })
    }
}
