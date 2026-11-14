package io.embodit.spatialoffice.core

import io.embodit.spatialoffice.core.files.FileKind
import io.embodit.spatialoffice.core.files.Naming
import io.embodit.spatialoffice.core.layout.Arrangement
import io.embodit.spatialoffice.core.layout.Json
import io.embodit.spatialoffice.core.layout.LayoutRecord
import io.embodit.spatialoffice.core.layout.LayoutRules
import io.embodit.spatialoffice.core.layout.LevelGate
import io.embodit.spatialoffice.core.layout.Seat
import io.embodit.spatialoffice.core.text.Attr
import io.embodit.spatialoffice.core.text.RichText
import io.embodit.spatialoffice.core.text.Span
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

class RulesTest {
    // ---- naming (PRD §4.7, K1–K3) ----
    @Test fun untitledNamesAndKinds() {
        assertEquals("未命名文档.docx", Naming.untitled(FileKind.DOC, true))
        assertEquals("未命名表格.xlsx", Naming.untitled(FileKind.SHEET, true))
        assertEquals("Untitled presentation.pptx", Naming.untitled(FileKind.SLIDES, false))
        assertTrue(Naming.isUntitled("未命名文档 2.docx"))
        assertFalse(Naming.isUntitled("季度复盘.docx"))
        assertEquals(FileKind.SHEET, FileKind.ofName("a.CSV").editor)
        assertEquals(FileKind.DOC, FileKind.ofName("x.pdf").editor)
        assertEquals(FileKind.UNKNOWN, FileKind.ofName("x.zip"))
    }

    @Test fun firstLineBecomesTheName() {
        assertEquals("Q4 规划 下一步", Naming.baseFromText("  Q4 规划:\t下一步?  "))
        assertNull(Naming.baseFromText("  ///  "))
        assertEquals(40, Naming.baseFromText("x".repeat(90))!!.length)
        assertEquals("a b", Naming.baseFromText("a\nb."))
    }

    @Test fun uniqueBackupCopy() {
        assertEquals("报告.docx", Naming.unique(listOf("x.docx"), "报告", "docx"))
        assertEquals("报告 3.docx", Naming.unique(listOf("报告.docx", "报告 2.DOCX"), "报告", "docx"))
        assertEquals("季度复盘（原件）.docx", Naming.backupName("季度复盘.docx", true))
        assertEquals("report (original).xlsx", Naming.backupName("report.xlsx", false))
        assertEquals("销售数据（副本）.xlsx", Naming.copyName("销售数据.xlsx", true))
        assertEquals("a.b.csv", Naming.withExt("a.b.xlsx", "csv"))
    }

    // ---- layout (PRD §6, P / S groups) ----
    @Test fun focusedPieceIsStraightAheadInEveryArrangement() {
        for (kind in listOf(FileKind.DOC, FileKind.SHEET, FileKind.SLIDES)) for (arr in Arrangement.values()) for (focus in 0 until 6) {
            val p = LayoutRules.poses(kind, 6, arr, focus, 1.5f)[focus]
            assertEquals("$kind $arr $focus x", 0f, p.x, 1e-4f)
            assertEquals(0f, p.yawDeg, 1e-4f)
            assertTrue(p.near && p.scale == 1f && p.z < -1f)
        }
    }

    @Test fun arcPanelsFaceTheSeatAndDoNotOverlap() {
        for (kind in listOf(FileKind.DOC, FileKind.SHEET, FileKind.SLIDES)) for (r in LayoutRules.RADII) {
            val ps = LayoutRules.poses(kind, 7, Arrangement.ARC, 3, r)
            val ring = LayoutRules.radius(kind, r)
            for (p in ps) {
                assertEquals(ring, hypot(p.x, p.z), 1e-3f)
                val az = Math.toDegrees(atan2(p.x.toDouble(), -p.z.toDouble())).toFloat()
                assertEquals(-az, p.yawDeg, 1e-2f) // normal points at the seat
            }
            // flat panels tangent to the ring touch edge to edge when the angle between them is 2·atan(w / 2r)
            val need = Math.toDegrees(2.0 * kotlin.math.atan(ps[0].width / 2.0 / ring)).toFloat()
            for (i in 1 until ps.size) {
                val step = ps[i - 1].yawDeg - ps[i].yawDeg
                assertTrue("$kind r=$r neighbours overlap: $step < $need", step >= need - 1e-3f)
            }
        }
    }

    @Test fun rowIsOneStraightLineAndSheetBlocksTouch() {
        val doc = LayoutRules.poses(FileKind.DOC, 5, Arrangement.ROW, 2, 1.5f)
        assertTrue(doc.all { it.z == doc[0].z && it.yawDeg == 0f })
        assertEquals(0.64f, doc[3].x - doc[2].x, 1e-4f)
        val sheet = LayoutRules.poses(FileKind.SHEET, 5, Arrangement.ROW, 0, 1.5f)
        assertEquals(sheet[0].width, sheet[1].x - sheet[0].x, 1e-4f)
        assertEquals(-1.4f, sheet[0].z, 1e-4f)
    }

    @Test fun layersKeepNeighboursNearAndPushTheRestBack() {
        val ps = LayoutRules.poses(FileKind.DOC, 8, Arrangement.LAYERS, 4, 1.5f)
        assertEquals(listOf(3, 4, 5), ps.indices.filter { ps[it].near })
        val far = ps.filter { !it.near }
        assertTrue(far.all { it.y > 0.5f && it.scale > 1.3f && it.alpha < 1f && hypot(it.x, it.z) > 2.2f })
        // far panels stay inside a comfortable head turn and do not hide behind the near ones (they are raised above them)
        assertTrue(far.all { it.y - it.height * it.scale / 2 > ps[4].y + ps[4].height / 2 - 0.45f })
        assertEquals(1, LayoutRules.poses(FileKind.SLIDES, 1, Arrangement.LAYERS, 0, 1.5f).size)
        assertTrue(LayoutRules.poses(FileKind.DOC, 0, Arrangement.ARC, 0, 1.5f).isEmpty())
    }

    @Test fun barAndRehearsal() {
        val bar = LayoutRules.barPose(FileKind.DOC, 1.5f)
        assertTrue(bar.y < -0.5f && abs(bar.z) < 1.5f)
        val r = LayoutRules.rehearsal()
        assertEquals(3.2f, r.getValue("screen").width, 0f)
        assertTrue(r.getValue("notes").x < 0 && r.getValue("next").x > 0)
    }

    @Test fun layoutRecordRoundTrip() {
        val rec = LayoutRecord("Download/季度\"复盘\".docx", Arrangement.LAYERS, "heading:2", 1.8f, true, "写作", listOf("table:1", "chart:0"))
        val back = LayoutRecord.parse(rec.toJson())
        assertEquals(rec, back)
        assertTrue(rec.toJson().contains("\"schema\": \"spatial-layout/1.0\""))
        assertNull(LayoutRecord.parse("{\"schema\":\"spatial-layout/2.0\"}"))
        assertNull(LayoutRecord.parse("not json"))
        assertEquals(LayoutRecord("x"), LayoutRecord.parse("{\"schema\":\"spatial-layout/1.3\",\"file\":\"x\",\"future\":[1,{\"a\":null}]}"))
        assertEquals("{\"a\":[1,true,null,\"x\\n\"]}", Json.write(mapOf("a" to listOf(1, true, null, "x\n"))))
    }

    // ---- rich text ----
    private val para = listOf(Span("Hello ", src = 0), Span("bold", bold = true, src = 1), Span(" tail", src = 2))

    @Test fun typingKeepsTheFormatBeforeTheCaret() {
        val a = RichText.applyEdit(para, "Hello boldER tail")
        assertEquals(listOf(Span("Hello ", src = 0), Span("boldER", bold = true, src = 1), Span(" tail", src = 2)), a)
        val b = RichText.applyEdit(para, "XHello bold tail")
        assertEquals(Span("XHello ", src = 0), b[0])
        val c = RichText.applyEdit(para, "Hello  tail") // delete the bold word
        assertEquals("Hello  tail", RichText.text(c))
        assertTrue(c.none { it.bold })
        assertEquals(listOf(Span("新", src = -1)), RichText.applyEdit(emptyList(), "新"))
        assertEquals(para, RichText.applyEdit(para, "Hello bold tail"))
    }

    @Test fun replaceAcrossSpans() {
        val a = RichText.applyEdit(para, "HelXXail")
        assertEquals("HelXXail", RichText.text(a))
        assertEquals(Span("HelXX", src = 0), a[0])
        assertEquals(Span("ail", src = 2), a[1])
    }

    @Test fun toggleFormatOnARange() {
        val a = RichText.setAttr(para, 3, 8, Attr.ITALIC, true)
        assertEquals("Hello bold tail", RichText.text(a))
        assertEquals(listOf("Hel", "lo ", "bo", "ld", " tail"), a.map { it.text })
        assertTrue(RichText.hasAttr(a, 3, 8, Attr.ITALIC))
        assertFalse(RichText.hasAttr(a, 2, 8, Attr.ITALIC))
        assertTrue(RichText.hasAttr(para, 8, 8, Attr.BOLD))   // caret inside the bold word
        assertFalse(RichText.hasAttr(para, 3, 3, Attr.BOLD))
        assertEquals(para, RichText.setAttr(a, 0, 15, Attr.ITALIC, false))
        assertTrue(RichText.setAll(para, Attr.UNDERLINE, true).all { it.underline })
    }

    @Test fun surrogatePairsStayWhole() {
        val s = listOf(Span("a😀b"))
        assertEquals("a😁b", RichText.text(RichText.applyEdit(s, "a😁b")))
        assertEquals("a😀😀b", RichText.text(RichText.applyEdit(s, "a😀😀b")))
    }

    // ---- level gate + seat ----
    @Test fun levelGateWaitsForALevelHead() {
        val g = LevelGate()
        assertNull(g.step(0, 0f))                 // nothing asked
        g.ask("reopen", 1000)
        assertNull(g.step(1000, -30f))            // looking down at the bar
        assertNull(g.step(1500, -30f))
        assertNull(g.step(1600, 4f))              // level, dwell starts
        assertNull(g.step(1800, 4f))
        assertEquals("reopen", g.step(1900, 4f))  // 300 ms level
        assertFalse(g.waiting)
    }

    @Test fun levelGateDwellRestartsWhenTheHeadTiltsAgain() {
        val g = LevelGate()
        g.ask("a", 0)
        assertNull(g.step(0, 0f)); assertNull(g.step(200, 25f)); assertNull(g.step(300, 0f)); assertNull(g.step(500, 0f))
        assertEquals("a", g.step(600, 0f))
    }

    @Test fun levelGateFallbacks() {
        val g = LevelGate()
        g.ask("a", 0)
        assertNull(g.step(100, null)); assertNull(g.step(400, Float.NaN))
        assertEquals("a/nohead", g.step(700, null))  // no pose for 600 ms after the first frame without one
        g.ask("b", 1000)
        assertNull(g.step(2000, 40f))
        assertEquals("b/timeout", g.step(4000, 40f))
        g.ask("c", 5000); g.cancel()
        assertNull(g.step(9000, 0f))
    }

    @Test fun seatUsesYawOnly() {
        val ahead = Seat.fromHead(1f, 1.2f, 2f, 0f, -1f)
        assertEquals(0f, ahead.yawRad, 1e-5f)
        val p = ahead.toStage(0f, 0f, -1.5f)
        assertEquals(1f, p.first, 1e-4f); assertEquals(1.2f, p.second, 1e-4f); assertEquals(0.5f, p.third, 1e-4f)
        // facing +x (turned right 90°): "1.5 m ahead" lands at +x, "right" lands at +z
        val right = Seat.fromHead(0f, 1f, 0f, 1f, 0f)
        val a = right.toStage(0f, 0f, -1.5f)
        assertEquals(1.5f, a.first, 1e-4f); assertEquals(0f, a.third, 1e-4f)
        val r = right.toStage(1f, 0f, 0f)
        assertEquals(0f, r.first, 1e-4f); assertEquals(1f, r.third, 1e-4f)
        assertEquals(0f, Seat.fromHead(0f, 0f, 0f, 0f, 0f).yawRad, 0f)
    }
}
