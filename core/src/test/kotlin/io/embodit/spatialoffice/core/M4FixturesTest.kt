package io.embodit.spatialoffice.core

import io.embodit.spatialoffice.core.docx.DocxDocument
import io.embodit.spatialoffice.core.docx.ParaStyle
import io.embodit.spatialoffice.core.docx.Run
import io.embodit.spatialoffice.core.xlsx.XlsxWorkbook
import org.junit.Test
import java.io.File

/**
 * v0.6.0 (M4): writes the sample files the M4 acceptance points are checked on into core/build/m4-fixtures
 * (`adb push` them to /sdcard/Download): a report with chapters one to five for the outline (d25), four paragraphs
 * with bold text and a list for the selection across paragraphs (d23), and a table of departments and amounts for
 * the filter / sort / freeze / hide checks (s29–s31, s42).
 */
class M4FixturesTest {
    @Test fun writeSamples() {
        val out = File("build/m4-fixtures").apply { mkdirs() }

        val outline = DocxDocument.blank()
        var at = outline.blocks.first().id
        outline.setParagraph(at, listOf(Run("年度报告")), ParaStyle.TITLE)
        val chapters = listOf("第一章 背景", "第二章 目标", "第三章 方案", "第四章 排期", "第五章 风险")
        for ((i, ch) in chapters.withIndex()) {
            at = outline.insertParagraphAfter(at, listOf(Run(ch)), ParaStyle.H1)
            if (i == 2) at = outline.insertParagraphAfter(at, listOf(Run("3.1 做法")), ParaStyle.H2)
            for (k in 1..6) at = outline.insertParagraphAfter(at, listOf(Run("${ch.take(3)}的第 $k 段正文。这一段用来把文档撑长，大纲跳转时能看出页面滚动了。")), ParaStyle.BODY)
        }
        File(out, "outline-sample.docx").writeBytes(outline.save())

        val cross = DocxDocument.blank()
        val p1 = cross.blocks.first().id
        cross.setParagraph(p1, listOf(Run("第一段：普通文字。")), ParaStyle.BODY)
        val p2 = cross.insertParagraphAfter(p1, listOf(Run("第二段里有"), Run("加粗的词", bold = true), Run("，从这段中间开始选。")), ParaStyle.BODY)
        val p3 = cross.insertParagraphAfter(p2, listOf(Run("第三段是列表项")), ParaStyle.BULLET)
        val p4 = cross.insertParagraphAfter(p3, listOf(Run("第四段到这里"), Run("结束", italic = true), Run("，选到这段中间。")), ParaStyle.BODY)
        cross.insertParagraphAfter(p4, listOf(Run("第五段：粘贴到它后面。")), ParaStyle.BODY)
        File(out, "cross-select.docx").writeBytes(cross.save())

        val wb = XlsxWorkbook.blank()
        val depts = listOf("销售", "研发", "市场", "销售", "研发", "财务", "市场", "销售", "研发", "财务", "销售", "市场")
        val names = listOf("张三", "李四", "王五", "赵六", "钱七", "孙八", "周九", "吴十", "郑一", "冯二", "陈三", "褚四")
        val amounts = listOf(1200, 800, 1500, 3000, 950, 1000, 2200, 640, 1800, 400, 1300, 720)
        listOf("部门", "姓名", "金额", "备注").forEachIndexed { c, v -> wb.setCell(0, 0, c, v) }
        for (i in depts.indices) {
            wb.setCell(0, i + 1, 0, depts[i]); wb.setCell(0, i + 1, 1, names[i]); wb.setCell(0, i + 1, 2, amounts[i].toString()); wb.setCell(0, i + 1, 3, if (i % 3 == 0) "季度" else "")
        }
        File(out, "sales-m4.xlsx").writeBytes(wb.save())

        // d26 / p27: every template as [New] writes it, for the python-docx / python-pptx check
        val tpl = File(out, "templates").apply { mkdirs() }
        for (t in io.embodit.spatialoffice.core.templates.DocTemplate.values()) for (zh in listOf(true, false))
            File(tpl, "${t.name.lowercase()}-${if (zh) "zh" else "en"}.docx").writeBytes(io.embodit.spatialoffice.core.templates.Templates.doc(t, zh))
        for (t in io.embodit.spatialoffice.core.templates.DeckTemplate.values()) for (zh in listOf(true, false))
            File(tpl, "${t.name.lowercase()}-${if (zh) "zh" else "en"}.pptx").writeBytes(io.embodit.spatialoffice.core.templates.Templates.deck(t, zh))
    }
}
