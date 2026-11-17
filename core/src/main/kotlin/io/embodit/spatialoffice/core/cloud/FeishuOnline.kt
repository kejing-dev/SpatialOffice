package io.embodit.spatialoffice.core.cloud

import io.embodit.spatialoffice.core.docx.DocxDocument
import io.embodit.spatialoffice.core.docx.ParaStyle
import io.embodit.spatialoffice.core.docx.Paragraph
import io.embodit.spatialoffice.core.docx.Run
import io.embodit.spatialoffice.core.layout.Json
import io.embodit.spatialoffice.core.xlsx.A1
import io.embodit.spatialoffice.core.xlsx.XlsxWorkbook

/**
 * Feishu online documents and sheets (PRD §8 R5 / R6). They have no file: the app reads the document's blocks (or
 * the sheet's values), edits them as a .docx / .xlsx in memory, and on save writes back only the blocks / cells that
 * changed. Block kinds this version cannot edit (tables, images, embedded objects) are left alone.
 */
object FeishuDoc {
    data class Block(val id: String, val type: Int, val runs: List<Run>, val index: Int) {
        val style: ParaStyle get() = when (type) {
            3 -> ParaStyle.H1; 4 -> ParaStyle.H2; in 5..11 -> ParaStyle.H3; 12 -> ParaStyle.BULLET; 13 -> ParaStyle.NUMBERED
            else -> ParaStyle.BODY
        }
        val text: String get() = runs.joinToString("") { it.text }
    }

    private val KEYS = mapOf(
        2 to "text", 3 to "heading1", 4 to "heading2", 5 to "heading3", 6 to "heading4", 7 to "heading5", 8 to "heading6",
        9 to "heading7", 10 to "heading8", 11 to "heading9", 12 to "bullet", 13 to "ordered", 14 to "code", 15 to "quote", 17 to "todo",
    )

    fun blocksReq(doc: String, pageToken: String?): CloudApi.Req =
        CloudApi.Req("GET", "https://open.feishu.cn/open-apis/docx/v1/documents/$doc/blocks?page_size=500" + (pageToken?.let { "&page_token=$it" } ?: ""))

    /** Paragraph-like top-level blocks in document order, plus the next page token. */
    fun parseBlocks(doc: String, body: String): Pair<List<Block>, String?> {
        val m = Json.parse(body) as? Map<*, *> ?: return emptyList<Block>() to null
        val data = m["data"] as? Map<*, *> ?: return emptyList<Block>() to null
        val items = (data["items"] as? List<*> ?: emptyList<Any>()).mapNotNull { it as? Map<*, *> }
        val page = items.firstOrNull { (it["block_type"] as? Double)?.toInt() == 1 } ?: items.firstOrNull { it["block_id"] == doc }
        val order = (page?.get("children") as? List<*>)?.map { it.toString() } ?: items.map { it["block_id"].toString() }
        val byId = items.associateBy { it["block_id"].toString() }
        val out = ArrayList<Block>()
        order.forEachIndexed { i, id ->
            val b = byId[id] ?: return@forEachIndexed
            val type = (b["block_type"] as? Double)?.toInt() ?: return@forEachIndexed
            val key = KEYS[type] ?: return@forEachIndexed
            val elems = ((b[key] as? Map<*, *>)?.get("elements") as? List<*>) ?: emptyList<Any>()
            val runs = elems.mapNotNull { e ->
                val tr = (e as? Map<*, *>)?.get("text_run") as? Map<*, *> ?: return@mapNotNull null
                val st = tr["text_element_style"] as? Map<*, *> ?: emptyMap<String, Any>()
                Run(tr["content"]?.toString() ?: "", st["bold"] == true, st["italic"] == true, st["underline"] == true, -1, st["strikethrough"] == true)
            }.filter { it.text.isNotEmpty() }
            out.add(Block(id, type, runs, i))
        }
        val more = if (data["has_more"] == true) data["page_token"]?.toString() else null
        return out to more
    }

    /** The blocks as a .docx the editor opens. */
    fun toDocx(blocks: List<Block>): ByteArray {
        val d = DocxDocument.blank()
        val first = d.blocks.first().id
        if (blocks.isEmpty()) return d.save()
        d.setParagraph(first, blocks[0].runs, blocks[0].style)
        var last = first
        for (b in blocks.drop(1)) last = d.insertParagraphAfter(last, b.runs, b.style)
        return d.save()
    }

    sealed class Op {
        data class Update(val blockId: String, val runs: List<Run>) : Op()
        data class Insert(val index: Int, val style: ParaStyle, val runs: List<Run>) : Op()
        data class Delete(val start: Int, val end: Int) : Op()
    }

    /**
     * What to write back after editing: paragraphs at the same position whose text or format changed are updated in
     * place; extra paragraphs are added after the last original one; paragraphs removed from the end are deleted.
     */
    fun diff(original: List<Block>, edited: ByteArray): List<Op> {
        val now = DocxDocument.open(edited).blocks.filterIsInstance<Paragraph>()
        val ops = ArrayList<Op>()
        val n = minOf(original.size, now.size)
        for (i in 0 until n) if (norm(original[i].runs) != norm(now[i].runs)) ops.add(Op.Update(original[i].id, now[i].runs))
        if (now.size > original.size) {
            var at = (original.lastOrNull()?.index ?: -1) + 1
            for (p in now.drop(original.size)) { ops.add(Op.Insert(at, p.style, p.runs)); at++ }
        } else if (original.size > now.size) {
            // removed from the end, in reverse so indices stay valid
            for (b in original.drop(now.size).asReversed()) ops.add(Op.Delete(b.index, b.index + 1))
        }
        return ops
    }

    private fun norm(r: List<Run>): List<Run> = r.filter { it.text.isNotEmpty() }.map { it.copy(src = -1) }
        .fold(ArrayList<Run>()) { acc, x -> val l = acc.lastOrNull(); if (l != null && l.copy(text = "") == x.copy(text = "")) acc[acc.size - 1] = l.copy(text = l.text + x.text) else acc.add(x); acc }

    private fun elements(runs: List<Run>): List<Map<String, Any?>> = runs.filter { it.text.isNotEmpty() }.map { r ->
        mapOf("text_run" to mapOf("content" to r.text, "text_element_style" to mapOf("bold" to r.bold, "italic" to r.italic, "underline" to r.underline, "strikethrough" to r.strike)))
    }.ifEmpty { listOf(mapOf("text_run" to mapOf("content" to ""))) }

    fun request(doc: String, op: Op): CloudApi.Req = when (op) {
        is Op.Update -> CloudApi.Req("PATCH", "https://open.feishu.cn/open-apis/docx/v1/documents/$doc/blocks/${op.blockId}", "application/json",
            Json.write(mapOf("update_text_elements" to mapOf("elements" to elements(op.runs)))))
        is Op.Insert -> {
            val type = when (op.style) { ParaStyle.H1, ParaStyle.TITLE -> 3; ParaStyle.H2 -> 4; ParaStyle.H3 -> 5; ParaStyle.BULLET -> 12; ParaStyle.NUMBERED -> 13; else -> 2 }
            CloudApi.Req("POST", "https://open.feishu.cn/open-apis/docx/v1/documents/$doc/blocks/$doc/children", "application/json",
                Json.write(mapOf("index" to op.index, "children" to listOf(mapOf("block_type" to type, KEYS[type] to mapOf("elements" to elements(op.runs)))))))
        }
        is Op.Delete -> CloudApi.Req("DELETE", "https://open.feishu.cn/open-apis/docx/v1/documents/$doc/blocks/$doc/children/batch_delete", "application/json",
            Json.write(mapOf("start_index" to op.start, "end_index" to op.end)))
    }
}

object FeishuSheet {
    fun sheetsReq(token: String): CloudApi.Req = CloudApi.Req("GET", "https://open.feishu.cn/open-apis/sheets/v3/spreadsheets/$token/sheets/query")

    fun parseFirstSheet(body: String): String? =
        (((Json.parse(body) as? Map<*, *>)?.get("data") as? Map<*, *>)?.get("sheets") as? List<*>)?.firstOrNull()
            ?.let { (it as? Map<*, *>)?.get("sheet_id")?.toString() }

    fun valuesReq(token: String, sheetId: String): CloudApi.Req =
        CloudApi.Req("GET", "https://open.feishu.cn/open-apis/sheets/v2/spreadsheets/$token/values/$sheetId!A1:Z500?valueRenderOption=ToString")

    fun parseValues(body: String): List<List<String>> {
        val vr = (((Json.parse(body) as? Map<*, *>)?.get("data") as? Map<*, *>)?.get("valueRange") as? Map<*, *>) ?: return emptyList()
        return (vr["values"] as? List<*> ?: emptyList<Any>()).map { row ->
            (row as? List<*> ?: emptyList<Any>()).map { v ->
                when (v) {
                    null -> ""
                    is Double -> if (v == Math.floor(v) && Math.abs(v) < 1e15) v.toLong().toString() else v.toString()
                    is Map<*, *> -> (v["text"] ?: v["link"] ?: "").toString()
                    is List<*> -> v.joinToString("") { (it as? Map<*, *>)?.get("text")?.toString() ?: "" }
                    else -> v.toString()
                }
            }
        }
    }

    fun toXlsx(values: List<List<String>>): ByteArray {
        val b = XlsxWorkbook.blank()
        values.forEachIndexed { r, row -> row.forEachIndexed { c, v -> if (v.isNotEmpty()) b.setCell(0, r, c, v) } }
        return b.save()
    }

    /** One value range per changed cell (values as typed: numbers stay numbers, `=…` goes as a formula). */
    fun diffReq(token: String, sheetId: String, original: List<List<String>>, edited: ByteArray): CloudApi.Req? {
        val b = XlsxWorkbook.open(edited)
        val rows = maxOf(original.size, b.rows(0))
        val cols = maxOf(original.maxOfOrNull { it.size } ?: 0, b.cols(0))
        val ranges = ArrayList<Map<String, Any?>>()
        for (r in 0 until rows) for (c in 0 until cols) {
            val was = original.getOrNull(r)?.getOrNull(c) ?: ""
            val cell = b.cell(0, r, c)
            val now = if (cell.isFormula) cell.input else cell.display
            if (now == was || (cell.isFormula && cell.display == was)) continue
            val v: Any? = when {
                cell.isFormula -> mapOf("type" to "formula", "text" to cell.input)
                now.isEmpty() -> ""
                cell.number != null && cell.display == now -> cell.number
                else -> now
            }
            val ref = A1.name(r, c)
            ranges.add(mapOf("range" to "$sheetId!$ref:$ref", "values" to listOf(listOf(v))))
        }
        if (ranges.isEmpty()) return null
        return CloudApi.Req("POST", "https://open.feishu.cn/open-apis/sheets/v2/spreadsheets/$token/values_batch_update", "application/json", Json.write(mapOf("valueRanges" to ranges)))
    }
}
