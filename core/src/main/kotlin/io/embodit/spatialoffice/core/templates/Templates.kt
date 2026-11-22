package io.embodit.spatialoffice.core.templates

import io.embodit.spatialoffice.core.docx.DocxDocument
import io.embodit.spatialoffice.core.docx.ParaStyle
import io.embodit.spatialoffice.core.docx.Run
import io.embodit.spatialoffice.core.pptx.PptxDeck
import io.embodit.spatialoffice.core.pptx.ShapeKind
import io.embodit.spatialoffice.core.pptx.SlideLayoutKind
import io.embodit.spatialoffice.core.pptx.TextPara
import io.embodit.spatialoffice.core.pptx.ZOrder

/**
 * v0.6.0 (M4 d26 / p27): starting documents for [New]. Each one is built with the same editing calls the editors use
 * (so it is an ordinary .docx / .pptx: standard styles, lists, tables, theme colours; nothing of this app's own),
 * in the app language. The names are the file names a new file from them gets.
 */
enum class DocTemplate(val zh: String, val en: String) {
    MEETING("会议纪要", "Meeting notes"), WEEKLY("周报", "Weekly report"), PROPOSAL("方案", "Proposal"), RESUME("简历", "Résumé");
    fun title(zhLang: Boolean) = if (zhLang) zh else en
}

enum class DeckTemplate(val zh: String, val en: String) {
    REPORT("工作汇报", "Work report"), PITCH("产品介绍", "Product pitch"), DARK("极简深色", "Minimal dark");
    fun title(zhLang: Boolean) = if (zhLang) zh else en
}

object Templates {
    private class DocBuilder(val d: DocxDocument) {
        var last: Int = -1
        fun p(style: ParaStyle, vararg runs: Run): Int {
            last = d.insertParagraphAfter(last, runs.toList(), style)
            return last
        }
        fun p(style: ParaStyle, text: String) = p(style, Run(text))
        fun table(rows: List<List<String>>) {
            val id = d.insertTableAfter(last, rows.size, rows[0].size)
            rows.forEachIndexed { r, row -> row.forEachIndexed { c, t -> if (t.isNotEmpty()) d.setTableCell(id, r, c, t) } }
            // the paragraph Word needs after a table at the end of the body is already there; write after it
            last = d.blocks.getOrNull(d.blocks.indexOfFirst { it.id == id } + 1)?.id ?: id
        }
    }

    /** The template as .docx bytes, in Chinese ([zh]) or English. */
    fun doc(t: DocTemplate, zh: Boolean): ByteArray {
        val d = DocxDocument.blank()
        val first = d.blocks.first().id
        val b = DocBuilder(d)
        fun L(c: String, e: String) = if (zh) c else e
        when (t) {
            DocTemplate.MEETING -> {
                d.setParagraph(first, listOf(Run(L("会议纪要", "Meeting notes"))), ParaStyle.TITLE); b.last = first
                b.table(listOf(
                    listOf(L("时间", "Date"), L("2026 年 月 日 14:00", "      , 2:00 pm")),
                    listOf(L("地点", "Place"), L("会议室 / 线上", "Room / online")),
                    listOf(L("主持", "Chair"), ""),
                    listOf(L("参会人", "Attendees"), ""),
                ))
                b.p(ParaStyle.H1, L("议题", "Agenda"))
                b.p(ParaStyle.NUMBERED, L("上周待办回顾", "Review of last week's actions"))
                b.p(ParaStyle.NUMBERED, L("本周重点", "This week's focus"))
                b.p(ParaStyle.NUMBERED, L("其他事项", "Any other business"))
                b.p(ParaStyle.H1, L("讨论要点", "Discussion"))
                b.p(ParaStyle.BULLET, L("要点一：", "Point one: "))
                b.p(ParaStyle.BULLET, L("要点二：", "Point two: "))
                b.p(ParaStyle.H1, L("决议", "Decisions"))
                b.p(ParaStyle.BULLET, Run(L("决定：", "Decided: "), bold = true), Run(L("……", "…")))
                b.p(ParaStyle.H1, L("待办", "Actions"))
                b.table(listOf(
                    listOf(L("事项", "Action"), L("负责人", "Owner"), L("截止", "Due")),
                    listOf("", "", ""), listOf("", "", ""),
                ))
            }
            DocTemplate.WEEKLY -> {
                d.setParagraph(first, listOf(Run(L("周报 · 第  周", "Weekly report · week  "))), ParaStyle.TITLE); b.last = first
                b.p(ParaStyle.BODY, Run(L("姓名：", "Name: "), bold = true), Run(L("        部门：", "        Team: ")))
                b.p(ParaStyle.H1, L("本周完成", "Done this week"))
                b.p(ParaStyle.BULLET, L("完成了……，结果是……", "Finished …, which led to …"))
                b.p(ParaStyle.BULLET, L("推进了……", "Moved … forward"))
                b.p(ParaStyle.H1, L("进行中", "In progress"))
                b.p(ParaStyle.BULLET, L("……（进度 50%）", "… (50 % done)"))
                b.p(ParaStyle.H1, L("遇到的问题", "Problems"))
                b.p(ParaStyle.BULLET, L("问题：……  需要：……", "Problem: …  Need: …"))
                b.p(ParaStyle.H1, L("下周计划", "Next week"))
                b.p(ParaStyle.NUMBERED, L("……", "…"))
                b.p(ParaStyle.NUMBERED, L("……", "…"))
            }
            DocTemplate.PROPOSAL -> {
                d.setParagraph(first, listOf(Run(L("方案名称", "Proposal title"))), ParaStyle.TITLE); b.last = first
                b.p(ParaStyle.BODY, Run(L("提交人 · 日期", "Author · date"), italic = true))
                b.p(ParaStyle.H1, L("一、背景", "1. Background"))
                b.p(ParaStyle.BODY, L("现在的情况和要解决的问题。", "Where things stand and the problem to solve."))
                b.p(ParaStyle.H1, L("二、目标", "2. Goals"))
                b.p(ParaStyle.BULLET, L("目标一（可衡量）", "Goal one (measurable)"))
                b.p(ParaStyle.BULLET, L("目标二", "Goal two"))
                b.p(ParaStyle.H1, L("三、方案", "3. Approach"))
                b.p(ParaStyle.H2, L("3.1 做法", "3.1 How"))
                b.p(ParaStyle.BODY, L("具体怎么做。", "What will be done."))
                b.p(ParaStyle.H2, L("3.2 资源", "3.2 Resources"))
                b.p(ParaStyle.BODY, L("人员、预算、工具。", "People, budget, tools."))
                b.p(ParaStyle.H1, L("四、排期", "4. Timeline"))
                b.table(listOf(
                    listOf(L("阶段", "Phase"), L("内容", "Work"), L("时间", "When")),
                    listOf(L("一", "1"), "", ""), listOf(L("二", "2"), "", ""), listOf(L("三", "3"), "", ""),
                ))
                b.p(ParaStyle.H1, L("五、风险", "5. Risks"))
                b.p(ParaStyle.BULLET, L("风险：……  应对：……", "Risk: …  Response: …"))
            }
            DocTemplate.RESUME -> {
                d.setParagraph(first, listOf(Run(L("你的名字", "Your Name"))), ParaStyle.TITLE); b.last = first
                b.p(ParaStyle.BODY, L("电话 · 邮箱 · 城市", "Phone · email · city"))
                b.p(ParaStyle.H1, L("个人简介", "Profile"))
                b.p(ParaStyle.BODY, L("两三句话说清你是谁、擅长什么。", "Two or three sentences on who you are and what you do best."))
                b.p(ParaStyle.H1, L("工作经历", "Experience"))
                b.p(ParaStyle.H2, L("公司名称 · 职位 · 2023–至今", "Company · role · 2023–now"))
                b.p(ParaStyle.BULLET, L("做成了什么，用数字说明。", "What you achieved, with numbers."))
                b.p(ParaStyle.BULLET, L("负责的项目和结果。", "Projects you led and how they ended."))
                b.p(ParaStyle.H1, L("教育经历", "Education"))
                b.p(ParaStyle.BODY, Run(L("学校 · 专业 · 学位", "School · subject · degree"), bold = true), Run(L("  2019–2023", "  2019–2023")))
                b.p(ParaStyle.H1, L("技能", "Skills"))
                b.p(ParaStyle.BULLET, L("技能一", "Skill one"))
                b.p(ParaStyle.BULLET, L("技能二", "Skill two"))
            }
        }
        return d.save()
    }

    /** Colours of a deck template: background (lt1), text (dk1), second background / text, accents. */
    private val COLORS: Map<DeckTemplate, Map<String, Int>> = mapOf(
        DeckTemplate.REPORT to mapOf("lt1" to 0xFFFFFF, "dk1" to 0x1E293B, "lt2" to 0xEFF6FF, "dk2" to 0x1E3A8A, "accent1" to 0x2563EB, "accent2" to 0x0EA5E9),
        DeckTemplate.PITCH to mapOf("lt1" to 0xFFFBF5, "dk1" to 0x3B1F0E, "lt2" to 0xFFEDD5, "dk2" to 0x7C2D12, "accent1" to 0xEA580C, "accent2" to 0x0D9488),
        DeckTemplate.DARK to mapOf("lt1" to 0x0F172A, "dk1" to 0xF8FAFC, "lt2" to 0x1E293B, "dk2" to 0xE2E8F0, "accent1" to 0x38BDF8, "accent2" to 0xA78BFA),
    )

    fun deckColors(t: DeckTemplate): Map<String, Int> = COLORS.getValue(t)

    /** The template as .pptx bytes: theme colours, an accent band on each slide, and four to five slides of placeholders. */
    fun deck(t: DeckTemplate, zh: Boolean): ByteArray {
        val d = PptxDeck.blank()
        d.setColorScheme(COLORS.getValue(t))
        fun L(c: String, e: String) = if (zh) c else e
        val accent = COLORS.getValue(t).getValue("accent1")
        val second = COLORS.getValue(t).getValue("accent2")
        val w = d.widthEmu; val h = d.heightEmu
        fun texts(slide: Int, vararg paras: List<TextPara>) {
            val boxes = d.slides[slide].shapes.filter { it.kind == ShapeKind.TITLE || it.kind == ShapeKind.BODY || it.kind == ShapeKind.TEXT }
            boxes.forEachIndexed { i, sh -> paras.getOrNull(i)?.let { d.setShapeText(slide, sh.id, it) } }
        }
        fun line(s: String) = listOf(TextPara(s))
        fun bullets(vararg s: String) = s.map { TextPara(it, bullet = true) }
        fun band(slide: Int, x: Long, y: Long, bw: Long, bh: Long, rgb: Int) { val id = d.addShape(slide, "rect", x, y, bw, bh, rgb); d.reorderShape(slide, id, ZOrder.BACK) }

        val slides: List<Pair<SlideLayoutKind, Array<List<TextPara>>>> = when (t) {
            DeckTemplate.REPORT -> listOf(
                SlideLayoutKind.TITLE to arrayOf(line(L("工作汇报", "Work report")), line(L("汇报人 · 日期", "Presenter · date"))),
                SlideLayoutKind.TITLE_BODY to arrayOf(line(L("目录", "Contents")), bullets(L("工作回顾", "Review"), L("关键成果", "Key results"), L("问题与对策", "Issues and actions"), L("下一步计划", "Next steps"))),
                SlideLayoutKind.TWO_COLUMN to arrayOf(line(L("关键成果", "Key results")), bullets(L("指标一：+20%", "Metric one: +20 %"), L("指标二：按期上线", "Metric two: shipped on time")), bullets(L("亮点：……", "Highlight: …"), L("经验：……", "Lesson: …"))),
                SlideLayoutKind.TITLE_BODY to arrayOf(line(L("下一步计划", "Next steps")), bullets(L("……（负责人 · 时间）", "… (owner · date)"), L("……", "…"))),
            )
            DeckTemplate.PITCH -> listOf(
                SlideLayoutKind.TITLE to arrayOf(line(L("产品名称", "Product name")), line(L("一句话说清它是什么", "What it is, in one line"))),
                SlideLayoutKind.TWO_COLUMN to arrayOf(line(L("问题和方案", "Problem and solution")), bullets(L("用户现在的痛点", "What hurts today"), L("为什么还没被解决", "Why it is still unsolved")), bullets(L("我们的做法", "Our answer"), L("和别人不同在哪", "What makes it different"))),
                SlideLayoutKind.TITLE_BODY to arrayOf(line(L("核心功能", "Key features")), bullets(L("功能一", "Feature one"), L("功能二", "Feature two"), L("功能三", "Feature three"))),
                SlideLayoutKind.TITLE_BODY to arrayOf(line(L("市场与数据", "Market and numbers")), bullets(L("目标用户：……", "Who it is for: …"), L("规模：……", "Size: …"))),
                SlideLayoutKind.TITLE to arrayOf(line(L("谢谢", "Thank you")), line(L("联系方式", "Contact"))),
            )
            DeckTemplate.DARK -> listOf(
                SlideLayoutKind.TITLE to arrayOf(line(L("标题", "Title")), line(L("副标题", "Subtitle"))),
                SlideLayoutKind.TITLE to arrayOf(line(L("第一部分", "Part one")), line(L("这一部分讲什么", "What this part is about"))),
                SlideLayoutKind.TITLE_BODY to arrayOf(line(L("要点", "Key points")), bullets(L("第一点", "First point"), L("第二点", "Second point"), L("第三点", "Third point"))),
                SlideLayoutKind.TITLE to arrayOf(line(L("谢谢", "Thank you")), line(L("问答", "Questions"))),
            )
        }
        // slide 1 of the blank deck is the title slide; the rest are added after it
        slides.forEachIndexed { i, (kind, paras) ->
            val idx = if (i == 0) 0 else d.addSlide(i - 1, kind)
            texts(idx, *paras)
            when (t) {
                DeckTemplate.REPORT -> if (kind == SlideLayoutKind.TITLE) band(idx, 0, h * 4 / 5, w, h / 5, accent) else band(idx, 0, 0, w / 60, h, accent)
                DeckTemplate.PITCH -> { band(idx, 0, h - h / 24, w, h / 24, accent); if (kind == SlideLayoutKind.TITLE) band(idx, w * 7 / 8, 0, w / 8, h - h / 24, second) }
                DeckTemplate.DARK -> band(idx, w / 12, if (kind == SlideLayoutKind.TITLE) h * 3 / 5 else h / 5, w / 8, h / 120, accent)
            }
        }
        return d.save()
    }
}
