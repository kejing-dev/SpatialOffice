package io.embodit.spatialoffice.core.text

import io.embodit.spatialoffice.core.docx.ParaStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlainDocTest {
    private val md = "# 标题\n\n第一段 **粗体** 原样。\n\n- 甲\n- 乙\n\n## 第二节\n正文紧跟标题\n"

    @Test fun untouchedSaveIsIdentical() {
        for (src in listOf(md, "a\r\nb\r\n\r\nc", "", "one line", "x\n\n\n")) {
            assertEquals(src, PlainDoc.open(src, true).save())
            assertEquals(src, PlainDoc.open(src, false).save())
        }
    }

    @Test fun markdownBlocks() {
        val d = PlainDoc.open(md, true)
        assertEquals(listOf(ParaStyle.H1, ParaStyle.BODY, ParaStyle.BULLET, ParaStyle.BULLET, ParaStyle.H2, ParaStyle.BODY), d.blocks.map { it.style })
        assertEquals("标题", d.firstLine())
        assertEquals("第一段 **粗体** 原样。", d.blocks[1].text)
        assertFalse(d.dirty)
    }

    @Test fun editingOneLineChangesOnlyThatLine() {
        val d = PlainDoc.open(md, true)
        d.setParagraph(d.blocks[2].id, "甲改", ParaStyle.BULLET)
        assertEquals(md.replace("- 甲\n", "- 甲改\n"), d.save())
        assertTrue(d.dirty); assertEquals(1, d.changedCount)
        val t = PlainDoc.open("a\nb\nc", false)
        t.setParagraph(t.blocks[1].id, "B", ParaStyle.H1) // plain text has no styles
        assertEquals("a\nB\nc", t.save())
        assertEquals(ParaStyle.BODY, t.blocks[1].style)
    }

    @Test fun insertDeleteAndStyle() {
        val d = PlainDoc.open("# T\n\nbody\n", true)
        val id = d.insertParagraphAfter(d.blocks[0].id, "new", ParaStyle.H2)
        assertEquals("# T\n\n## new\n\nbody\n", d.save())
        d.deleteBlock(id)
        assertEquals("# T\n\nbody\n", d.save())
        d.setParagraph(d.blocks[1].id, "body", ParaStyle.BULLET)
        assertEquals("# T\n\n- body\n", d.save())
        val b = PlainDoc.blank(false)
        val first = b.insertParagraphAfter(-1, "first", ParaStyle.BODY)
        assertEquals("first\n", b.save()); assertEquals("first", b.firstLine())
        b.insertParagraphAfter(first, "second", ParaStyle.BODY)
        assertEquals("first\nsecond\n", b.save())
        // a file without a final newline keeps that property for untouched lines and gets one when a line is added after it
        val t = PlainDoc.open("a\nb", false)
        t.insertParagraphAfter(t.blocks[1].id, "c", ParaStyle.BODY)
        assertEquals("a\nb\nc", t.save())
    }

    @Test fun htmlBlocksInReadingOrder() {
        val html = "<html><head><title>T</title><style>p{color:red}</style></head><body><h1>产品 &amp; 说明</h1><script>var a='<p>x</p>'</script>" +
            "<p>第一段<br>第二行 <b>粗</b></p><ul><li>甲</li><li>乙</li></ul><h2>二</h2><p></p></body></html>"
        val b = HtmlText.blocks(html)
        assertEquals(listOf(ParaStyle.H1 to "产品 & 说明", ParaStyle.BODY to "第一段\n第二行 粗", ParaStyle.BULLET to "甲", ParaStyle.BULLET to "乙", ParaStyle.H2 to "二"), b)
        assertEquals(listOf(ParaStyle.BODY to "plain"), HtmlText.blocks("plain"))
    }
}
