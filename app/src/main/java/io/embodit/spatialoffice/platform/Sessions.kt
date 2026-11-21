package io.embodit.spatialoffice.platform

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.embodit.spatialoffice.core.csv.Csv
import io.embodit.spatialoffice.core.docx.CompatKind
import io.embodit.spatialoffice.core.docx.CompatNote
import io.embodit.spatialoffice.core.docx.DocxDocument
import io.embodit.spatialoffice.core.docx.ImageBlock
import io.embodit.spatialoffice.core.docx.OpaqueBlock
import io.embodit.spatialoffice.core.docx.ParaStyle
import io.embodit.spatialoffice.core.docx.Paragraph
import io.embodit.spatialoffice.core.docx.Run
import io.embodit.spatialoffice.core.docx.TableBlock
import io.embodit.spatialoffice.core.files.FileKind
import io.embodit.spatialoffice.core.pptx.PptxDeck
import io.embodit.spatialoffice.core.pptx.SlideLayoutKind
import io.embodit.spatialoffice.core.pptx.TextPara
import io.embodit.spatialoffice.core.pptx.Shape
import io.embodit.spatialoffice.core.pptx.ShapeKind
import io.embodit.spatialoffice.core.text.HtmlText
import io.embodit.spatialoffice.core.text.PlainDoc
import io.embodit.spatialoffice.core.text.RichText
import io.embodit.spatialoffice.core.text.Span
import io.embodit.spatialoffice.core.xlsx.CellRect
import io.embodit.spatialoffice.core.xlsx.ClipCell
import io.embodit.spatialoffice.core.xlsx.DataEdge
import io.embodit.spatialoffice.core.xlsx.NumFmt
import io.embodit.spatialoffice.core.xlsx.RangeStats
import io.embodit.spatialoffice.core.xlsx.Tsv
import io.embodit.spatialoffice.core.xlsx.XlsxWorkbook
import java.io.File

/** What the document window draws, whatever the file type underneath. */
sealed class UiBlock {
    abstract val id: Int
    /**
     * M2: [level] list level 0–8, [indentTw] left indent (twips), [lineSpacing] 240 = single (null = the file's), [spaceBefore] /
     * [spaceAfter] twips (null = the file's), [baseSizeHp] the size in half-points of text without a size of its own.
     */
    data class Para(
        override val id: Int, val style: ParaStyle, val spans: List<Span>, val editable: Boolean, val why: String?, val align: Int = 0,
        val level: Int = 0, val indentTw: Int = 0, val lineSpacing: Int? = null, val spaceBefore: Int? = null, val spaceAfter: Int? = null, val baseSizeHp: Int = 22,
    ) : UiBlock()
    /** [widthPct]: share of the text width, 10–100. */
    data class Table(override val id: Int, val rows: List<List<String>>, val widthPct: Int = 100) : UiBlock()
    /** [widthEmu] / [heightEmu]: the size the file gives the picture (0 = unknown, shown at the text width). */
    data class Image(override val id: Int, val bitmap: Bitmap?, val widthEmu: Long = 0, val heightEmu: Long = 0) : UiBlock()
    data class Opaque(override val id: Int, val kind: String) : UiBlock()
    data class Page(override val id: Int, val bitmap: Bitmap) : UiBlock()
}

/**
 * One hit of a find (d41 / s19 / p21), the same shape for all three kinds. Document: [a] = paragraph id. Spreadsheet:
 * [a] = sheet, [b] = row, [c] = column (of the cell's input). Presentation: [a] = slide, [b] = shape id or −1 for the
 * speaker notes. [start] / [len]: the characters matched in that text.
 */
data class FindHit(val a: Int, val b: Int = 0, val c: Int = 0, val start: Int, val len: Int)

/** d17: one step of [Increase indent] for a paragraph that is not a list item: 420 twips (two characters of 10.5 pt). */
const val INDENT_STEP_TW = 420

/** One open file. Edits go through the session so dirty state, undo and recomposition stay in one place. */
abstract class Session(ref: FileRef, val kind: FileKind) {
    var ref by mutableStateOf(ref)
    /** Tag of this file's own window (v0.2.5: every open file has a window of its own); set when the file is opened. */
    var tag: String = ""
    /** Bumped after every change to the model; composables read it to recompose. */
    var version by mutableIntStateOf(0)
        protected set
    var dirty by mutableStateOf(false)
    /** Created by New and not yet saved under a name of its own. */
    var isNew by mutableStateOf(false)
    /** Google's own documents (exported) and other cloud items that cannot be written back open read-only. */
    open val readOnly: Boolean get() = (ref as? FileRef.Cloud)?.readOnlyCloud == true
    val editor: FileKind get() = kind.editor

    private val undoStack = ArrayDeque<ByteArray>()
    private val redoStack = ArrayDeque<ByteArray>()
    private var lastTag = ""
    private var lastMark = 0L
    var canUndo by mutableStateOf(false)
        private set
    var canRedo by mutableStateOf(false)
        private set

    abstract fun serialize(): ByteArray
    protected abstract fun restore(bytes: ByteArray)
    abstract fun firstLine(): String
    abstract fun changedCount(): Int
    abstract fun compat(): List<CompatNote>
    /** Number of pieces the file splits into when spread out (sections, column blocks, slides). */
    abstract val pieceCount: Int

    /** Call before a change. Typing bursts with the same [tag] within a second share one undo step. */
    fun mark(tag: String) {
        if (readOnly) return
        val now = SystemClock.elapsedRealtime()
        if (tag.isNotEmpty() && tag == lastTag && now - lastMark < 1000 && undoStack.isNotEmpty()) { lastMark = now; return }
        lastTag = tag; lastMark = now
        runCatching { serialize() }.onSuccess {
            undoStack.addLast(it)
            while (undoStack.size > 40) undoStack.removeFirst()
            redoStack.clear()
            canUndo = true; canRedo = false
        }
    }

    protected fun changed() { dirty = true; version++; counted() }

    /** An undo step from bytes taken before a change that might not happen (pushed only once it did). */
    protected fun pushUndo(before: ByteArray) {
        if (readOnly) return
        undoStack.addLast(before); while (undoStack.size > 40) undoStack.removeFirst()
        redoStack.clear(); canUndo = true; canRedo = false; lastTag = ""
    }

    /** Refreshes the observable item counts (slides, blocks, rows) right after a change, so lists update in the same frame. */
    protected open fun counted() {}

    fun undo() {
        val prev = undoStack.removeLastOrNull() ?: return
        redoStack.addLast(serialize())
        restore(prev); lastTag = ""
        canUndo = undoStack.isNotEmpty(); canRedo = true
        dirty = true; version++; counted()
    }

    fun redo() {
        val next = redoStack.removeLastOrNull() ?: return
        undoStack.addLast(serialize())
        restore(next); lastTag = ""
        canUndo = true; canRedo = redoStack.isNotEmpty()
        dirty = true; version++; counted()
    }

    fun saved() { dirty = false; isNew = false; version++ }

    // ---- find (v0.3.0: one find sheet for all three kinds)

    /** The hit the find sheet points at: the window scrolls to it and highlights it ([findTick] bumps on every jump). */
    var findHit by mutableStateOf<FindHit?>(null)
    var findTick by mutableIntStateOf(0)
        private set
    /** Every match of [q], in reading order. [allSheets]: spreadsheets only, every worksheet instead of the current one. */
    open fun findHits(q: String, matchCase: Boolean, allSheets: Boolean = false): List<FindHit> = emptyList()
    /** The whole text a hit sits in (paragraph, cell input, text box or notes), for the find sheet's preview line. */
    open fun hitText(h: FindHit): String? = null
    /** Where a hit is, as the find sheet names it ("Sheet1!B3", "Slide 2 · notes"); null for documents. */
    open fun hitPlace(h: FindHit): String? = null
    /** Jumps to [h]: the page / sheet / slide that holds it comes up and the match is highlighted. */
    open fun showHit(h: FindHit) { findHit = h; findTick++ }
    /** [Replace]: only this one match, one undo step. False when the text there is no longer [q]. */
    open fun replaceHit(h: FindHit, q: String, repl: String, matchCase: Boolean): Boolean = false
    /** [Replace all]: every match, one undo step; returns how many were replaced. */
    open fun replaceAllHits(q: String, repl: String, matchCase: Boolean, allSheets: Boolean = false): Int = 0

    protected fun same(text: String, at: Int, q: String, matchCase: Boolean): Boolean =
        q.isNotEmpty() && at >= 0 && at + q.length <= text.length && text.regionMatches(at, q, 0, q.length, !matchCase)

    // ---- d27 drafts

    /** Takes back an autosaved draft found when the file was opened: one undo step returns to the file as it is on disk. */
    fun recover(bytes: ByteArray): Boolean {
        if (readOnly) return false
        val before = runCatching { serialize() }.getOrNull() ?: return false
        if (before.contentEquals(bytes)) return false
        val ok = runCatching { restore(bytes) }.onFailure { e -> android.util.Log.w(TAG, "DRAFT restore failed: $e"); runCatching { restore(before) } }.isSuccess
        if (!ok) return false
        undoStack.addLast(before); while (undoStack.size > 40) undoStack.removeFirst()
        redoStack.clear(); canUndo = true; canRedo = false; lastTag = ""
        dirty = true; version++; counted()
        return true
    }
}

class DocSession(ref: FileRef, kind: FileKind, bytes: ByteArray, cacheDir: File) : Session(ref, kind) {
    private var docx: DocxDocument? = null
    private var plain: PlainDoc? = null
    private var staticBlocks: List<UiBlock> = emptyList()
    private val images = HashMap<String, Bitmap?>()
    var pageCount = 0
        private set

    init {
        when (kind) {
            FileKind.DOC -> docx = DocxDocument.open(bytes)
            FileKind.TEXT, FileKind.MARKDOWN -> plain = PlainDoc.open(String(bytes, Charsets.UTF_8), kind == FileKind.MARKDOWN)
            FileKind.HTML -> staticBlocks = HtmlText.blocks(String(bytes, Charsets.UTF_8)).mapIndexed { i, (s, t) -> UiBlock.Para(i, s, listOf(Span(t)), false, "html") }
            FileKind.IMAGE -> staticBlocks = listOf(UiBlock.Image(0, BitmapFactory.decodeByteArray(bytes, 0, bytes.size)))
            FileKind.PDF -> staticBlocks = renderPdf(bytes, cacheDir)
            else -> error("not a document")
        }
    }

    override val readOnly: Boolean get() = docx == null && plain == null || (ref as? FileRef.Cloud)?.readOnlyCloud == true

    val styled: Boolean get() = docx != null || plain?.markdown == true
    val richText: Boolean get() = docx != null

    fun blocks(): List<UiBlock> {
        docx?.let { d ->
            return d.blocks.map { b ->
                when (b) {
                    is Paragraph -> UiBlock.Para(
                        b.id, b.style, b.runs.map { Span(it.text, it.bold, it.italic, it.underline, it.src, it.strike, it.color, it.highlight, it.size) }, b.editable, b.readOnlyWhy, b.align,
                        b.level, b.indentTw, b.lineSpacing, b.spaceBefore, b.spaceAfter, b.baseSizeHp,
                    )
                    is TableBlock -> UiBlock.Table(b.id, b.rows, b.widthPct)
                    is ImageBlock -> UiBlock.Image(b.id, images.getOrPut(b.part) { d.partBytes(b.part)?.let { runCatching { BitmapFactory.decodeByteArray(it, 0, it.size) }.getOrNull() } }, b.widthEmu, b.heightEmu)
                    is OpaqueBlock -> UiBlock.Opaque(b.id, b.kind)
                }
            }
        }
        plain?.let { p -> return p.blocks.map { UiBlock.Para(it.id, it.style, listOf(Span(it.text)), true, null) } }
        return staticBlocks
    }

    /** Block that last had the caret (where Insert puts things). */
    var caretBlock = -1

    /** Number of blocks, observable: a new paragraph, picture or table shows up in the same frame. */
    var blockCount by mutableIntStateOf(0)
        private set

    override fun counted() { blockCount = runCatching { blocks().size }.getOrDefault(0) }

    init { counted() }

    private fun runs(spans: List<Span>) = spans.map { Run(it.text, it.bold, it.italic, it.underline, it.src, it.strike, it.color, it.highlight, it.size) }

    private fun para(id: Int): Paragraph? = docx?.blocks?.firstOrNull { it.id == id } as? Paragraph

    /** M2: [f] over characters [a, b) of paragraph [id]: the [Text ▾] / [Colour ▾] tools and their debug commands. */
    fun formatRange(id: Int, a: Int, b: Int, f: (List<Span>, Int, Int, UiBlock.Para) -> List<Span>): Boolean {
        val p = blocks().firstOrNull { it.id == id } as? UiBlock.Para ?: return false
        if (!richText || !p.editable || b <= a) return false
        val next = f(p.spans, a, b, p)
        if (next == p.spans) return false
        setParagraph(p.id, next, p.style)
        android.util.Log.i(TAG, "DOCTEXT format para=${p.id} range=$a..$b")
        return true
    }

    /** d17: list level of a bulleted / numbered paragraph (Tab / Shift+Tab, Paragraph ▸ indent). False when nothing changed. */
    fun setListLevel(id: Int, level: Int): Boolean {
        val d = docx ?: return false
        val p = para(id)?.takeIf { it.editable && (it.style == ParaStyle.BULLET || it.style == ParaStyle.NUMBERED) } ?: return false
        val l = level.coerceIn(0, 8)
        if (l == p.level) return false
        mark(""); d.setListLevel(id, l); changed()
        android.util.Log.i(TAG, "PARA level $id ${p.level}->$l")
        return true
    }

    /** d17: [Increase / Decrease indent]: a list item changes level, any other paragraph moves by [INDENT_STEP_TW]. */
    fun indent(id: Int, more: Boolean): Boolean {
        val p = para(id)?.takeIf { it.editable } ?: return false
        if (p.style == ParaStyle.BULLET || p.style == ParaStyle.NUMBERED) return setListLevel(id, p.level + if (more) 1 else -1)
        val tw = (p.indentTw + if (more) INDENT_STEP_TW else -INDENT_STEP_TW).coerceIn(0, INDENT_STEP_TW * 10)
        if (tw == p.indentTw) return false
        mark(""); docx?.setIndent(id, tw); changed()
        android.util.Log.i(TAG, "PARA indent $id ${p.indentTw}->$tw")
        return true
    }

    /** d19: line spacing (240 = single, 360 = 1.5 lines; null = as the style has it). */
    fun setLineSpacing(id: Int, line: Int?): Boolean {
        val p = para(id)?.takeIf { it.editable } ?: return false
        if (p.lineSpacing == line) return false
        mark(""); docx?.setLineSpacing(id, line); changed()
        android.util.Log.i(TAG, "PARA line $id ${p.lineSpacing}->$line")
        return true
    }

    /** d19: space before and after the paragraph (twips; null = as the style has it). */
    fun setParaSpacing(id: Int, before: Int?, after: Int?): Boolean {
        val p = para(id)?.takeIf { it.editable } ?: return false
        if (p.spaceBefore == before && p.spaceAfter == after) return false
        mark(""); docx?.setParaSpacing(id, before, after); changed()
        android.util.Log.i(TAG, "PARA spacing $id $before/$after")
        return true
    }

    fun setParagraph(id: Int, spans: List<Span>, style: ParaStyle, align: Int? = null) {
        mark("p$id")
        docx?.setParagraph(id, runs(spans), style, align)
        plain?.setParagraph(id, spans.joinToString("") { it.text }, style)
        changed()
    }

    fun insertAfter(id: Int, spans: List<Span>, style: ParaStyle): Int {
        mark("")
        val n = docx?.insertParagraphAfter(id, runs(spans), style)
            ?: plain?.insertParagraphAfter(id, spans.joinToString("") { it.text }, style) ?: -1
        changed()
        return n
    }

    /** Inserts a picture after block [afterId] (-1 = at the top), at most the text width, keeping its proportions. */
    fun insertImage(afterId: Int, bytes: ByteArray, ext: String, widthPx: Int, heightPx: Int): Int {
        val d = docx ?: return -1
        mark("")
        val maxW = 5_486_400L // 6 in, about the text width of an A4 page
        val w = minOf(maxW, widthPx * 9525L).coerceAtLeast(9525L)
        val h = (w.toDouble() * heightPx / widthPx.coerceAtLeast(1)).toLong().coerceAtLeast(9525L)
        val id = d.insertImageAfter(afterId, bytes, ext, w, h)
        changed()
        return id
    }

    /**
     * Table cell that has (or should take) the caret: table id, row, column. A new table puts it in its first cell
     * (D57 "new things show at once and are selected"), so typing straight after Insert ▸ Table goes into the table.
     */
    var tableCaret by mutableStateOf<Triple<Int, Int, Int>?>(null)
    /** Bumped each time [tableCaret] is asked for again, so the same cell can take the focus a second time. */
    var tableCaretTick by mutableIntStateOf(0)
        private set

    fun focusCell(id: Int, row: Int, col: Int) { tableCaret = Triple(id, row, col); tableCaretTick++ }

    private fun caretTable(): Pair<Triple<Int, Int, Int>, io.embodit.spatialoffice.core.docx.TableBlock>? {
        val t = tableCaret ?: return null
        val tb = docx?.blocks?.firstOrNull { it.id == t.first } as? io.embodit.spatialoffice.core.docx.TableBlock ?: return null
        return t to tb
    }

    /**
     * Keys typed while the keyboard still belongs to the system Toolbar (after Insert ▸ Table the toolbar window keeps
     * the window focus and the app cannot take it back, see pico-cli-feedback): they go to the cell with the caret.
     * Returns false when there is no such cell.
     */
    fun typeInCell(text: String): Boolean {
        val (t, tb) = caretTable() ?: return false
        val cur = tb.rows.getOrNull(t.second)?.getOrNull(t.third) ?: return false
        setTableCell(t.first, t.second, t.third, cur + text); return true
    }

    fun backspaceInCell(): Boolean {
        val (t, tb) = caretTable() ?: return false
        val cur = tb.rows.getOrNull(t.second)?.getOrNull(t.third) ?: return false
        if (cur.isNotEmpty()) setTableCell(t.first, t.second, t.third, cur.dropLast(1)); return true
    }

    /** Tab / Enter in a cell: next cell (wrapping to the next row) or the cell below. */
    fun moveCell(down: Boolean): Boolean {
        val (t, tb) = caretTable() ?: return false
        val rows = tb.rows.size; val cols = tb.rows.firstOrNull()?.size ?: 0
        var r = t.second; var c = t.third
        if (down) r++ else { c++; if (c >= cols) { c = 0; r++ } }
        if (r >= rows) return true
        focusCell(t.first, r, c); return true
    }

    fun insertTable(afterId: Int, rows: Int, cols: Int): Int {
        val d = docx ?: return -1
        mark("")
        val id = d.insertTableAfter(afterId, rows, cols)
        changed()
        if (id >= 0) focusCell(id, 0, 0)
        return id
    }

    fun setTableCell(id: Int, row: Int, col: Int, text: String) {
        val d = docx ?: return
        val cur = (d.blocks.firstOrNull { it.id == id } as? io.embodit.spatialoffice.core.docx.TableBlock)?.rows?.getOrNull(row)?.getOrNull(col)
        if (cur == text) return
        mark("t$id.$row.$col")
        runCatching { d.setTableCell(id, row, col, text) }.onFailure { android.util.Log.w(TAG, "table cell not editable: ${it.message}") }
        changed()
    }

    /** Picture or table picked in the document (−1 = none): it shows its resize corners. */
    var picked by mutableIntStateOf(-1)

    /**
     * A picture being resized (block id → size in EMU) or a table (block id → width in percent, height unused): what
     * the block is drawn with until the pointer lets go; [endResize] then writes it to the file as one undo step.
     */
    var resizing by mutableStateOf<Pair<Int, io.embodit.spatialoffice.core.layout.UnitBox>?>(null)
        private set
    private var resizeStart: io.embodit.spatialoffice.core.layout.UnitBox? = null

    /** Press on a resize corner of picture / table [id]. Returns false when it cannot be resized. */
    fun beginResize(id: Int): Boolean {
        val d = docx ?: return false
        if (readOnly) return false
        val start = when (val b = d.blocks.firstOrNull { it.id == id }) {
            is ImageBlock -> io.embodit.spatialoffice.core.layout.UnitBox(0, 0, b.widthEmu.takeIf { it > 0 } ?: DocxDocument.TEXT_WIDTH_EMU, b.heightEmu.takeIf { it > 0 } ?: DocxDocument.TEXT_WIDTH_EMU * 3 / 4)
            is TableBlock -> io.embodit.spatialoffice.core.layout.UnitBox(0, 0, b.widthPct.toLong(), 0)
            else -> return false
        }
        picked = id; resizeStart = start; resizing = id to start
        return true
    }

    /**
     * The corner has travelled [dx], [dy] since the press (picture: EMU; table: percent of the text width in [dx]).
     * A picture keeps its proportions and stays within the text width; a table stays between 20 % and 100 %.
     */
    fun resizeBy(corner: io.embodit.spatialoffice.core.layout.Corner, dx: Long, dy: Long) {
        val (id, _) = resizing ?: return
        val start = resizeStart ?: return
        val d = docx ?: return
        resizing = id to when (d.blocks.firstOrNull { it.id == id }) {
            is ImageBlock -> io.embodit.spatialoffice.core.layout.BoxDrag.resize(start, corner, dx, dy, keepAspect = true, minSide = 457_200L, maxW = DocxDocument.TEXT_WIDTH_EMU)
            else -> start.copy(w = (if (corner.left) start.w - dx else start.w + dx).coerceIn(20, 100))
        }
    }

    /** The pointer let go: write the new size (one undo step). Returns true when something changed. */
    fun endResize(): Boolean {
        val (id, box) = resizing ?: return false
        val start = resizeStart
        resizing = null; resizeStart = null
        val d = docx ?: return false
        if (start == null || box == start) return false
        mark("")
        val ok = runCatching {
            when (d.blocks.firstOrNull { it.id == id }) {
                is ImageBlock -> d.setImageSize(id, box.w, box.h)
                is TableBlock -> d.setTableWidth(id, box.w.toInt())
                else -> error("not resizable")
            }
        }.onFailure { android.util.Log.w(TAG, "resize of block $id failed: ${it.message}") }.isSuccess
        if (ok) changed()
        return ok
    }

    fun cancelResize() { resizing = null; resizeStart = null }

    /** Matches of [find] in editable paragraphs: (paragraph id, offset). */
    fun find(find: String): List<Pair<Int, Int>> = blocks().filterIsInstance<UiBlock.Para>().filter { it.editable }
        .flatMap { p -> io.embodit.spatialoffice.core.text.RichText.matches(io.embodit.spatialoffice.core.text.RichText.text(p.spans), find).map { p.id to it } }

    override fun findHits(q: String, matchCase: Boolean, allSheets: Boolean): List<FindHit> = if (q.isEmpty()) emptyList()
        else blocks().filterIsInstance<UiBlock.Para>().filter { it.editable }.flatMap { p ->
            io.embodit.spatialoffice.core.text.RichText.matches(io.embodit.spatialoffice.core.text.RichText.text(p.spans), q, !matchCase).map { FindHit(p.id, start = it, len = q.length) }
        }

    override fun showHit(h: FindHit) { caretBlock = h.a; super.showHit(h) }
    override fun hitText(h: FindHit): String? = (blocks().firstOrNull { it.id == h.a } as? UiBlock.Para)?.let { io.embodit.spatialoffice.core.text.RichText.text(it.spans) }

    override fun replaceHit(h: FindHit, q: String, repl: String, matchCase: Boolean): Boolean {
        if (readOnly) return false
        val p = blocks().filterIsInstance<UiBlock.Para>().firstOrNull { it.id == h.a && it.editable } ?: return false
        if (!same(io.embodit.spatialoffice.core.text.RichText.text(p.spans), h.start, q, matchCase)) return false
        mark("")
        val out = io.embodit.spatialoffice.core.text.RichText.replaceAt(p.spans, h.start, q.length, repl)
        docx?.setParagraph(p.id, runs(out), p.style)
        plain?.setParagraph(p.id, out.joinToString("") { it.text }, p.style)
        changed()
        return true
    }

    override fun replaceAllHits(q: String, repl: String, matchCase: Boolean, allSheets: Boolean): Int = replaceAll(q, repl, matchCase)

    /** Replaces every match in the editable paragraphs as one undo step; returns how many were replaced. */
    fun replaceAll(find: String, replacement: String, matchCase: Boolean = false): Int {
        if (find.isEmpty() || readOnly) return 0
        var total = 0
        var marked = false
        for (p in blocks().filterIsInstance<UiBlock.Para>().filter { it.editable }) {
            val (out, n) = io.embodit.spatialoffice.core.text.RichText.replaceAll(p.spans, find, replacement, !matchCase)
            if (n == 0) continue
            if (!marked) { mark(""); marked = true }
            docx?.setParagraph(p.id, runs(out), p.style)
            plain?.setParagraph(p.id, out.joinToString("") { it.text }, p.style)
            total += n
        }
        if (total > 0) changed()
        return total
    }

    fun wordCount(): io.embodit.spatialoffice.core.text.WordCount.Result = io.embodit.spatialoffice.core.text.WordCount.count(plainText())

    fun delete(id: Int) {
        mark("")
        docx?.deleteBlock(id); plain?.deleteBlock(id)
        changed()
    }

    override fun serialize(): ByteArray = docx?.save() ?: plain?.save()?.toByteArray(Charsets.UTF_8) ?: ByteArray(0)

    override fun restore(bytes: ByteArray) {
        if (docx != null) { docx = DocxDocument.open(bytes); images.clear() }
        else if (plain != null) plain = PlainDoc.open(String(bytes, Charsets.UTF_8), plain!!.markdown)
    }

    override fun firstLine(): String = docx?.firstLine() ?: plain?.firstLine() ?: ""
    fun plainText(): String = docx?.plainText() ?: plain?.plainText() ?: staticBlocks.filterIsInstance<UiBlock.Para>().joinToString("\n") { b -> b.spans.joinToString("") { it.text } }
    override fun changedCount(): Int = docx?.changedBlockCount ?: plain?.changedCount ?: 0
    override fun compat(): List<CompatNote> = docx?.compat() ?: emptyList()

    /** Sections = runs of blocks starting at a heading; each becomes one panel when spread out. */
    fun sections(): List<List<UiBlock>> {
        val all = blocks()
        if (all.isEmpty()) return listOf(emptyList())
        if (all.all { it is UiBlock.Page || it is UiBlock.Image }) return all.map { listOf(it) }
        val out = ArrayList<MutableList<UiBlock>>()
        for (b in all) {
            val heading = b is UiBlock.Para && (b.style == ParaStyle.TITLE || b.style == ParaStyle.H1 || b.style == ParaStyle.H2)
            if (out.isEmpty() || (heading && out.last().isNotEmpty())) out.add(ArrayList())
            out.last().add(b)
        }
        // a long section without headings is cut so one panel stays readable
        return out.flatMap { s -> if (s.size > 14) s.chunked(12) else listOf(s) }
    }

    override val pieceCount: Int get() = sections().size

    private fun renderPdf(bytes: ByteArray, cacheDir: File): List<UiBlock> {
        val tmp = File(cacheDir, "open.pdf")
        tmp.writeBytes(bytes)
        val out = ArrayList<UiBlock>()
        runCatching {
            ParcelFileDescriptor.open(tmp, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                PdfRenderer(fd).use { r ->
                    pageCount = r.pageCount
                    for (i in 0 until minOf(r.pageCount, 40)) {
                        r.openPage(i).use { p ->
                            val w = 1100
                            val h = (w.toFloat() * p.height / p.width).toInt().coerceAtLeast(1)
                            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                            bmp.eraseColor(android.graphics.Color.WHITE)
                            p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            out.add(UiBlock.Page(i, bmp))
                        }
                    }
                }
            }
        }
        tmp.delete()
        return out
    }
}

class SheetSession(ref: FileRef, kind: FileKind, bytes: ByteArray) : Session(ref, kind) {
    var book: XlsxWorkbook = if (kind == FileKind.CSV) fromCsv(String(bytes, Charsets.UTF_8)) else XlsxWorkbook.open(bytes)
        private set
    var sheet by mutableIntStateOf(0)
    var row by mutableIntStateOf(0)
    var col by mutableIntStateOf(0)
    private var csvEdits = 0

    val isCsv: Boolean get() = kind == FileKind.CSV

    /** The other corner of the selected range (s17); equal to (row, col) when one cell is selected. */
    var anchorRow by mutableIntStateOf(0)
        private set
    var anchorCol by mutableIntStateOf(0)
        private set

    /** One cell: the range collapses onto it. */
    fun select(r: Int, c: Int) { row = r.coerceIn(0, MAX_R); col = c.coerceIn(0, MAX_C); anchorRow = row; anchorCol = col }
    /** Shift+arrow / drag: the active corner moves, the anchor stays. */
    fun extendTo(r: Int, c: Int) { row = r.coerceIn(0, MAX_R); col = c.coerceIn(0, MAX_C) }
    /** Click on a row number: the row's cells in use (at least A); the first cell is the active one. */
    fun selectRow(r: Int) { select(r, 0); anchorCol = (colCount - 1).coerceAtLeast(0) }
    /** Click on a column letter: the column's rows in use (at least row 1). */
    fun selectColumn(c: Int) { select(0, c); anchorRow = (rowCount - 1).coerceAtLeast(0) }
    fun selectAll() { select(0, 0); anchorRow = (rowCount - 1).coerceAtLeast(0); anchorCol = (colCount - 1).coerceAtLeast(0) }
    val selection: CellRect get() = CellRect.of(anchorRow, anchorCol, row, col)
    val isRange: Boolean get() = anchorRow != row || anchorCol != col

    /** Ctrl+arrow (Shift too = extend): to the edge of the data in that direction. */
    fun jump(dr: Int, dc: Int, extend: Boolean) {
        val maxR = maxOf(rowCount + 50, row + 1).coerceAtMost(MAX_R); val maxC = maxOf(colCount + 10, col + 1).coerceAtMost(MAX_C)
        val (r, c) = DataEdge.jump({ rr, cc -> book.cell(sheet, rr, cc).input.isNotEmpty() }, row, col, dr, dc, maxR, maxC)
        if (extend) extendTo(r, c) else select(r, c)
    }

    /** Sum / average / count of the selection (status line under the toolbar). */
    fun stats(): RangeStats {
        val r = selection
        if (r.size > 200_000) return RangeStats(0.0, null, 0, 0)
        val cells = ArrayList<io.embodit.spatialoffice.core.xlsx.Cell>()
        for (rr in r.r1..minOf(r.r2, rowCount)) for (cc in r.c1..minOf(r.c2, colCount)) cells.add(book.cell(sheet, rr, cc))
        return RangeStats.of(cells)
    }

    private inline fun eachSelected(f: (Int, Int) -> Unit) { val r = selection; for (rr in r.r1..r.r2) for (cc in r.c1..r.c2) f(rr, cc) }

    fun setCell(r: Int, c: Int, input: String) {
        if (book.cell(sheet, r, c).input == input) return
        mark("c$sheet.$r.$c")
        book.setCell(sheet, r, c, input)
        csvEdits++
        changed()
    }

    /** Rows / columns in use and the sheet count, observable so the grid and the sheet picker follow every change. */
    var rowCount by mutableIntStateOf(0)
        private set
    var colCount by mutableIntStateOf(0)
        private set
    var sheetCount by mutableIntStateOf(0)
        private set
    /** View filter (not saved): show only data rows whose value in [filterCol] is [filterValue]. */
    var filterCol by mutableIntStateOf(-1)
    var filterValue by mutableStateOf<String?>(null)

    override fun counted() {
        runCatching {
            sheetCount = book.sheetNames.size
            if (sheet >= sheetCount) sheet = (sheetCount - 1).coerceAtLeast(0)
            rowCount = book.rows(sheet); colCount = book.cols(sheet)
        }
    }

    init { counted() }

    /** Row indices the grid shows: all, or the header row plus the rows that pass the filter. */
    fun visibleRows(total: Int): List<Int> {
        val v = filterValue
        if (filterCol < 0 || v == null) return (0 until total).toList()
        return (0 until total).filter { r -> r == 0 || r >= rowCount || book.cell(sheet, r, filterCol).display == v }
    }

    /** Distinct values below the header in column [c] (filter choices). */
    fun columnValues(c: Int): List<String> = (1 until rowCount).map { book.cell(sheet, it, c).display }.distinct().take(30)

    // formats act on the whole selection (s17: "fill + bold on B2:D5 reads back on 12 cells")
    fun setBold(bold: Boolean) { if (isCsv) return; mark(""); eachSelected { r, c -> book.setBold(sheet, r, c, bold) }; changed() }
    fun setFill(rgb: Int?) { if (isCsv) return; mark(""); eachSelected { r, c -> book.setFill(sheet, r, c, rgb) }; changed() }
    // s23 (M2): font of the selection, all from Text ▾ / Colour ▾
    fun setItalic(on: Boolean) { if (isCsv) return; mark(""); eachSelected { r, c -> book.setItalic(sheet, r, c, on) }; changed() }
    fun setUnderline(on: Boolean) { if (isCsv) return; mark(""); eachSelected { r, c -> book.setUnderline(sheet, r, c, on) }; changed() }
    fun setStrike(on: Boolean) { if (isCsv) return; mark(""); eachSelected { r, c -> book.setStrike(sheet, r, c, on) }; changed() }
    fun setFontColor(rgb: Int?) { if (isCsv) return; mark(""); eachSelected { r, c -> book.setFontColor(sheet, r, c, rgb) }; changed() }
    fun setFontSize(pt: Float) { if (isCsv) return; mark(""); eachSelected { r, c -> book.setFontSize(sheet, r, c, pt) }; changed() }
    /** Larger / smaller: every selected cell one place along the size ladder from its own size. */
    fun stepFontSize(steps: Int) {
        if (isCsv) return
        mark("")
        val base = book.defaultFontPt()
        eachSelected { r, c ->
            val hp = Math.round((book.cell(sheet, r, c).fontPt ?: base) * 2)
            book.setFontSize(sheet, r, c, io.embodit.spatialoffice.core.text.FontSizes.step(hp, steps) / 2f)
        }
        changed()
    }
    fun clearFont() { if (isCsv) return; mark(""); eachSelected { r, c -> book.clearFont(sheet, r, c) }; changed() }
    /** True when every selected cell has it (the active cell when one is selected). */
    fun allSelected(test: (io.embodit.spatialoffice.core.xlsx.Cell) -> Boolean): Boolean { var all = true; eachSelected { r, c -> if (!test(book.cell(sheet, r, c))) all = false }; return all }
    /** s22: "left" / "center" / "right" / null (general). */
    fun setHAlign(align: String?) { if (isCsv) return; mark(""); eachSelected { r, c -> book.setHAlign(sheet, r, c, align) }; changed() }
    fun setWrap(wrap: Boolean) { if (isCsv) return; mark(""); eachSelected { r, c -> book.setWrap(sheet, r, c, wrap) }; changed() }
    fun adjustDecimals(delta: Int) { if (isCsv) return; mark(""); eachSelected { r, c -> book.adjustDecimals(sheet, r, c, delta) }; changed() }

    // ---- s20 column widths / row heights (the selected columns / rows)
    fun widen(deltaChars: Float) {
        if (isCsv) return
        mark(""); val r = selection
        for (c in r.c1..r.c2) book.setColWidth(sheet, c, (book.colWidthChars(sheet, c) + deltaChars).coerceIn(2f, 120f))
        changed()
    }
    fun autoFit() { if (isCsv) return; mark(""); val r = selection; for (c in r.c1..r.c2) book.autoFitColumn(sheet, c); changed() }
    fun taller(deltaPt: Float) {
        if (isCsv) return
        mark(""); val r = selection
        for (rr in r.r1..minOf(r.r2, r.r1 + 500)) book.setRowHeight(sheet, rr, (book.rowHeightPt(sheet, rr) + deltaPt).coerceIn(9f, 200f))
        changed()
    }

    // ---- s18 copy / cut / paste

    /** Copies (or cuts) the selection; returns the tab-separated text for the system clipboard. */
    fun copy(cut: Boolean): String {
        val r = selection
        val cells = book.copyRange(sheet, r.r1, r.c1, r.r2, r.c2)
        val text = Tsv.write((r.r1..r.r2).map { rr -> (r.c1..r.c2).map { cc -> book.cell(sheet, rr, cc).display } })
        clip = SheetClip(cells, r, sheet, book, cut, text)
        return text
    }

    /**
     * Pastes at the selected cell. When [systemText] is what this app put on the clipboard (or there is no text), the
     * cells are pasted with their formulas moved by the offset (and their formats, same file); a cut moves them and
     * empties the source. Other text is split at tabs and line breaks. The pasted range is selected.
     */
    fun paste(systemText: String?): CellRect? {
        if (readOnly) return null
        val c = clip
        val own = c != null && (systemText == null || systemText.replace("\r", "") == c.text)
        val h: Int
        val w: Int
        if (own && c != null) {
            h = c.cells.size; w = c.cells.firstOrNull()?.size ?: 0
            if (h == 0 || w == 0) return null
            mark("")
            if (c.cut && c.book === book) {
                // a move: the formulas stay as they were written (every spreadsheet does this on cut)
                book.clearRange(c.sheet, c.rect.r1, c.rect.c1, c.rect.r2, c.rect.c2, true)
                book.pasteRange(sheet, row, col, c.cells, 0, 0, true)
                clip = null
            } else book.pasteRange(sheet, row, col, c.cells, row - c.rect.r1, col - c.rect.c1, c.book === book && !isCsv)
        } else {
            val rows = Tsv.parse(systemText ?: return null)
            h = rows.size; w = rows.maxOfOrNull { it.size } ?: 0
            if (h == 0 || w == 0) return null
            mark("")
            rows.forEachIndexed { i, cells -> cells.forEachIndexed { j, v -> book.setCell(sheet, row + i, col + j, v) } }
        }
        anchorRow = (row + h - 1).coerceAtMost(MAX_R); anchorCol = (col + w - 1).coerceAtMost(MAX_C)
        csvEdits++; changed()
        return selection
    }

    /** Delete key on a range: empties the cells, keeps their formats. */
    fun clearSelection() { mark(""); val r = selection; book.clearRange(sheet, r.r1, r.c1, r.r2, r.c2, false); csvEdits++; changed() }

    // ---- s21 worksheets

    fun addSheet() { if (isCsv) return; mark(""); sheet = book.addSheet(); select(0, 0); changed() }
    /** Null when done, else why not ("empty", "long", "char", "taken"). */
    fun renameSheet(name: String): String? {
        if (isCsv) return "csv"
        val n = name.trim()
        val why = sheetNameProblem(book.sheetNames, sheet, n)
        if (why != null) return why
        if (n == book.sheetNames[sheet]) return null
        mark("")
        return runCatching { book.renameSheet(sheet, n) }.fold({ changed(); null }, { it.message ?: "invalid" })
    }
    fun copySheet() { if (isCsv) return; mark(""); sheet = book.copySheet(sheet); select(0, 0); changed() }
    fun deleteSheet(): Boolean {
        if (isCsv || book.sheetNames.size <= 1) return false
        mark(""); book.deleteSheet(sheet); sheet = (sheet - 1).coerceAtLeast(0); select(0, 0); changed(); return true
    }

    // ---- s19 find / replace (cell inputs: what was typed, formulas included)

    override fun findHits(q: String, matchCase: Boolean, allSheets: Boolean): List<FindHit> {
        if (q.isEmpty()) return emptyList()
        val out = ArrayList<FindHit>()
        val list = if (allSheets) book.sheetNames.indices.toList() else listOf(sheet)
        for (si in list) for (r in 0 until book.rows(si)) for (c in 0 until book.cols(si)) {
            val t = book.cell(si, r, c).input
            if (t.isEmpty()) continue
            for (at in io.embodit.spatialoffice.core.text.RichText.matches(t, q, !matchCase)) { out.add(FindHit(si, r, c, at, q.length)); if (out.size >= 5000) return out }
        }
        return out
    }

    override fun hitText(h: FindHit): String? = book.cell(h.a, h.b, h.c).input
    override fun hitPlace(h: FindHit): String? = book.sheetNames.getOrNull(h.a)?.let { it + "!" + io.embodit.spatialoffice.core.xlsx.A1.name(h.b, h.c) }

    override fun showHit(h: FindHit) {
        if (sheet != h.a) { sheet = h.a; counted() }
        select(h.b, h.c)
        super.showHit(h)
    }

    override fun replaceHit(h: FindHit, q: String, repl: String, matchCase: Boolean): Boolean {
        if (readOnly) return false
        val t = book.cell(h.a, h.b, h.c).input
        if (!same(t, h.start, q, matchCase)) return false
        mark("")
        book.setCell(h.a, h.b, h.c, t.substring(0, h.start) + repl + t.substring(h.start + q.length))
        csvEdits++; changed()
        return true
    }

    override fun replaceAllHits(q: String, repl: String, matchCase: Boolean, allSheets: Boolean): Int {
        if (readOnly) return 0
        val hits = findHits(q, matchCase, allSheets)
        if (hits.isEmpty()) return 0
        mark("")
        for (cell in hits.map { Triple(it.a, it.b, it.c) }.distinct()) {
            val t = book.cell(cell.first, cell.second, cell.third).input
            book.setCell(cell.first, cell.second, cell.third, t.replace(q, repl, ignoreCase = !matchCase))
        }
        csvEdits++; changed()
        return hits.size
    }
    fun deleteRow() { mark(""); book.deleteRow(sheet, row); csvEdits++; changed() }
    /** D57: the new column is picked at once (same row), so typing goes into it. */
    fun insertColumn() { mark(""); book.insertColumnRight(sheet, col); col = (col + 1).coerceAtMost(701); csvEdits++; changed() }
    fun deleteColumn() { mark(""); book.deleteColumn(sheet, col); csvEdits++; changed() }
    fun toggleFreeze(): Boolean {
        if (isCsv) return false
        mark(""); val on = book.frozenRows(sheet) == 0; book.setFrozenRows(sheet, if (on) 1 else 0); changed(); return on
    }
    val frozen: Int get() = if (isCsv) 0 else book.frozenRows(sheet)
    fun setFmt(fmt: NumFmt) { if (isCsv) return; mark(""); eachSelected { r, c -> book.setFmt(sheet, r, c, fmt) }; changed() }
    /** D57: the new row is picked at once (same column), so typing goes into it. */
    fun insertRowBelow() { mark(""); book.insertRowBelow(sheet, row); row = (row + 1).coerceAtMost(9999); csvEdits++; changed() }
    fun autoSum(): String? { mark(""); val f = book.autoSum(sheet, row, col); if (f != null) { csvEdits++; changed() }; return f }

    /** Sorts the data rows (everything below the first row down to the last row that has a value in this column). */
    fun sortByColumn(ascending: Boolean = true): Boolean {
        val last = (book.rows(sheet) - 1 downTo 1).firstOrNull { book.cell(sheet, it, col).input.isNotEmpty() && !book.cell(sheet, it, col).isFormula } ?: return false
        if (last < 2) return false
        mark("")
        val ok = book.sortRows(sheet, col, ascending, 1, last)
        if (ok) { csvEdits++; changed() }
        return ok
    }

    fun addChart(range: String, title: String, type: String = "bar"): Boolean {
        mark("")
        return runCatching { book.addChart(sheet, range, title, type) }.onSuccess { changed() }.isSuccess
    }

    override fun serialize(): ByteArray = if (isCsv) book.toCsv(0).toByteArray(Charsets.UTF_8) else book.save()
    override fun restore(bytes: ByteArray) { book = if (isCsv) fromCsv(String(bytes, Charsets.UTF_8)) else XlsxWorkbook.open(bytes) }
    override fun firstLine(): String = (0 until minOf(book.cols(0), 8)).map { book.cell(0, 0, it).display }.firstOrNull { it.isNotBlank() } ?: ""
    override fun changedCount(): Int = csvEdits
    override fun compat(): List<CompatNote> = if (isCsv) emptyList() else book.compat()

    /** Column blocks of the current sheet: three columns per panel. */
    override val pieceCount: Int get() = ((book.cols(sheet) + COLS_PER_PIECE - 1) / COLS_PER_PIECE).coerceAtLeast(1)

    /** What was copied or cut: the cells with their formats, where they came from, and the text put on the clipboard. */
    class SheetClip(val cells: List<List<ClipCell>>, val rect: CellRect, val sheet: Int, val book: XlsxWorkbook, val cut: Boolean, val text: String)

    companion object {
        const val MAX_R = 9999
        const val MAX_C = 701
        /** Shared by every sheet window, so cells copied in one file paste into another (values and formulas; formats only in the same file). */
        var clip: SheetClip? = null

        private const val BAD_CHARS = "[]:*?/" + "\\"

        /** Excel's rules for a worksheet name; [self] is the sheet being renamed. */
        fun sheetNameProblem(names: List<String>, self: Int, n: String): String? = when {
            n.isEmpty() -> "empty"
            n.length > 31 -> "long"
            n.any { it in BAD_CHARS } || n.startsWith("'") || n.endsWith("'") -> "char"
            names.withIndex().any { (i, o) -> i != self && o.equals(n, ignoreCase = true) } -> "taken"
            else -> null
        }

        const val COLS_PER_PIECE = 3
        private fun fromCsv(text: String): XlsxWorkbook {
            val b = XlsxWorkbook.blank()
            Csv.parse(text).forEachIndexed { r, line -> line.forEachIndexed { c, v -> if (v.isNotEmpty()) b.setCell(0, r, c, v) } }
            return b
        }
    }
}

/**
 * A line break inside a slide paragraph (PowerPoint's Shift+Enter, `a:br`) in the text a slide box is edited as;
 * "\n" there always starts a new paragraph. The slides editor shows it as a new line.
 */
const val SOFT_BREAK = ' '

/** An object copied on a slide, with the deck it belongs to (pasting is for the same file: the copy points at its pictures). */
class ObjClip(val from: DeckSession, val clip: io.embodit.spatialoffice.core.pptx.ShapeClip)

/** Shared by every slides window; only the window of the deck it came from pastes it. */
var objClip: ObjClip? = null

class DeckSession(ref: FileRef, bytes: ByteArray) : Session(ref, FileKind.SLIDES) {
    var deck: PptxDeck = PptxDeck.open(bytes)
        private set
    var current by mutableIntStateOf(0)
    private var edits = 0
    private val images = HashMap<String, Bitmap?>()

    fun image(part: String): Bitmap? = images.getOrPut(part) { deck.partBytes(part)?.let { runCatching { BitmapFactory.decodeByteArray(it, 0, it.size) }.getOrNull() } }

    /**
     * p17: a text box as one run of spans; every paragraph but the last ends with "\n", carried by its last span. A
     * line break inside a paragraph (a:br, "\n" in [TextPara.text]) is [SOFT_BREAK] here, so it stays a line break
     * through typing, find / replace and formatting (v0.4.1 q10-2).
     */
    fun spansOf(sh: Shape): List<Span> {
        val out = ArrayList<Span>()
        sh.paras.forEachIndexed { i, p ->
            val runs = (p.runs ?: listOf(Span(p.text))).filter { it.text.isNotEmpty() }
                .map { if (it.text.indexOf('\n') >= 0) it.copy(text = it.text.replace('\n', SOFT_BREAK)) else it }.toMutableList()
            if (i < sh.paras.size - 1) {
                if (runs.isEmpty()) runs.add((out.lastOrNull() ?: Span("")).copy(text = "\n"))
                else runs[runs.size - 1] = runs.last().copy(text = runs.last().text + "\n")
            }
            out.addAll(runs)
        }
        return out
    }

    /** Spans back into paragraphs (split at "\n"); paragraph i keeps level / bullet / alignment of the i-th one it replaces. */
    fun parasFrom(old: Shape, spans: List<Span>): List<TextPara> {
        val groups = ArrayList<List<Span>>()
        var cur = ArrayList<Span>()
        for (sp in spans) {
            var t = sp.text
            while (true) {
                val k = t.indexOf('\n')
                if (k < 0) { if (t.isNotEmpty()) cur.add(sp.copy(text = t)); break }
                if (k > 0) cur.add(sp.copy(text = t.substring(0, k)))
                groups.add(cur); cur = ArrayList(); t = t.substring(k + 1)
            }
        }
        groups.add(cur)
        return groups.mapIndexed { i, kept ->
            val runs = kept.map { if (it.text.indexOf(SOFT_BREAK) >= 0) it.copy(text = it.text.replace(SOFT_BREAK, '\n')) else it }
            val o = old.paras.getOrNull(i) ?: old.paras.lastOrNull()
            TextPara(runs.joinToString("") { it.text }, o?.level ?: 0, o?.bullet ?: false, runs, o?.align)
        }
    }

    fun setText(slide: Int, shapeId: Int, text: String) {
        val old = deck.slides[slide].shapes.firstOrNull { it.id == shapeId } ?: return
        val spans = spansOf(old)
        if (RichText.text(spans) == text) return
        mark("s$slide.$shapeId")
        deck.setShapeText(slide, shapeId, parasFrom(old, RichText.applyEdit(spans, text))); edits++
        changed()
    }

    // ---- p17 / p18 / p22 / p24 (M2)

    /** Characters selected in a slide text box while it is typed into ([end] exclusive). */
    data class TextSel(val slide: Int, val shape: Int, val start: Int, val end: Int)
    var textSel by mutableStateOf<TextSel?>(null)

    /** The picked object when it holds editable text. */
    fun pickedText(): Shape? = deck.slides.getOrNull(current)?.shapes?.firstOrNull {
        it.id == selected && it.editable && (it.kind == ShapeKind.TITLE || it.kind == ShapeKind.BODY || it.kind == ShapeKind.TEXT)
    }

    /** What the text tools act on: the characters selected in the picked box, or all of its text when nothing is selected. */
    fun textRange(sh: Shape): Pair<Int, Int> {
        val len = RichText.text(spansOf(sh)).length
        val t = textSel?.takeIf { it.slide == current && it.shape == sh.id }
        val a = t?.start?.coerceIn(0, len) ?: 0
        val b = t?.end?.coerceIn(0, len) ?: 0
        return if (b > a) a to b else 0 to len
    }

    /** p17: [f] over the selected characters of the picked text box; one undo step. */
    fun formatText(f: (List<Span>, Int, Int) -> List<Span>): Boolean {
        val sh = pickedText() ?: return false
        val spans = spansOf(sh)
        val (a, b) = textRange(sh)
        if (b <= a) return false
        val next = f(spans, a, b)
        if (next == spans) return false
        mark(""); deck.setShapeText(current, sh.id, parasFrom(sh, next)); edits++; changed()
        android.util.Log.i(TAG, "SLIDETEXT format slide=$current id=${sh.id} range=$a..$b")
        return true
    }

    /** Value shared by all selected characters (null when they differ or nothing is picked). */
    fun <T> textCommon(get: (Span) -> T?): T? {
        val sh = pickedText() ?: return null
        val (a, b) = textRange(sh)
        return RichText.common(spansOf(sh), a, b, get)
    }

    /** Size of the selected characters in half-points (what Larger / Smaller start from). */
    fun textBaseSize(): Int = textCommon { it.size } ?: pickedText()?.fontPt?.let { Math.round(it * 2) } ?: 36

    /** Indices of the paragraphs the selection touches (all of them with nothing selected). */
    private fun selParas(sh: Shape): IntRange {
        val text = RichText.text(spansOf(sh))
        val t = textSel?.takeIf { it.slide == current && it.shape == sh.id && it.end > it.start }
        // nothing selected: every paragraph, an empty last one included
        if (t == null) return 0..(text.count { it == '\n' })
        val (a, b) = textRange(sh)
        val first = text.substring(0, a).count { it == '\n' }
        val last = text.substring(0, (b - 1).coerceAtLeast(a)).count { it == '\n' }
        return first..maxOf(first, last)
    }

    private fun touched(sh: Shape): List<TextPara> { val r = selParas(sh); return sh.paras.filterIndexed { i, _ -> i in r } }

    /** p18: alignment (0 left, 1 centre, 2 right) and / or bullets of the paragraphs the selection touches. */
    fun setParaFormat(align: Int? = null, bullet: Boolean? = null): Boolean {
        val sh = pickedText() ?: return false
        val range = selParas(sh)
        val paras = parasFrom(sh, spansOf(sh)).mapIndexed { i, p -> if (i in range) p.copy(align = align ?: p.align, bullet = bullet ?: p.bullet) else p }
        if (paras.map { it.align to it.bullet } == sh.paras.map { it.align to it.bullet }) return false
        mark(""); deck.setShapeText(current, sh.id, paras); edits++; changed()
        android.util.Log.i(TAG, "SLIDETEXT para slide=$current id=${sh.id} paras=$range align=$align bullet=$bullet")
        return true
    }

    /** Alignment shared by the touched paragraphs (null when they differ). */
    fun paraAlign(): Int? = pickedText()?.let { sh -> touched(sh).map { it.align ?: sh.align }.distinct().singleOrNull() }
    fun paraBullets(): Boolean = pickedText()?.let { sh -> touched(sh).let { t -> t.isNotEmpty() && t.all { it.bullet } } } ?: false

    fun pickedShape(): Shape? = deck.slides.getOrNull(current)?.shapes?.firstOrNull { it.id == selected }

    /** p22: fill of the picked drawn shape or text box (null = no fill). */
    fun setFill(rgb: Int?): Boolean = shapeEdit("fill $rgb") { deck.setShapeFill(current, it.id, rgb) }
    /** p22: outline colour (null = none) and width of the picked object. */
    fun setLine(rgb: Int?, widthEmu: Long?): Boolean = shapeEdit("line $rgb $widthEmu") { deck.setShapeLine(current, it.id, rgb, widthEmu) }
    /** p24: turn the picked object by [deg] (90° steps from the menu). */
    fun rotateBy(deg: Int): Boolean = pickedShape()?.let { setRotation(it.rotation + deg) } ?: false
    fun setRotation(deg: Int): Boolean {
        val sh = pickedShape() ?: return false
        val d = io.embodit.spatialoffice.core.layout.Rotation.norm(deg)
        if (d == sh.rotation) return false
        return shapeEdit("rotate $d") { deck.setShapeRotation(current, it.id, d) }
    }

    private fun shapeEdit(what: String, f: (Shape) -> Unit): Boolean {
        val sh = pickedShape() ?: return false
        val before = runCatching { serialize() }.getOrNull() ?: return false
        val ok = runCatching { f(sh) }.onFailure { android.util.Log.i(TAG, "OBJSTYLE $what refused: ${it.message}") }.isSuccess
        if (!ok) return false
        pushUndo(before); edits++; changed()
        android.util.Log.i(TAG, "OBJSTYLE $what slide=$current id=${sh.id}")
        return true
    }

    /** While the rotation handle is held: the object id and the angle it is drawn at (written once on release). */
    var dragRot by mutableStateOf<Pair<Int, Int>?>(null)
    fun rotationOf(sh: Shape): Int = dragRot?.takeIf { it.first == sh.id }?.second ?: sh.rotation
    fun beginRotate(id: Int): Boolean {
        val sh = deck.slides.getOrNull(current)?.shapes?.firstOrNull { it.id == id } ?: return false
        selected = id; dragRot = id to sh.rotation; return true
    }
    fun rotateTo(deg: Int) { dragRot = dragRot?.let { it.first to io.embodit.spatialoffice.core.layout.Rotation.norm(deg) } }
    fun endRotate(): Boolean { val d = dragRot ?: return false; dragRot = null; return setRotation(d.second) }
    fun cancelRotate() { dragRot = null }

    fun setNotes(slide: Int, text: String) {
        if (deck.slides[slide].notes == text) return
        mark("n$slide"); deck.setNotes(slide, text); edits++; changed()
    }

    /** Observable slide count: a new, copied or deleted slide shows in the thumbnails in the same frame. */
    var slideCount by mutableIntStateOf(0)
        private set
    /** Shape picked on the current slide (−1 = none); the object tools act on it. */
    var selected by mutableIntStateOf(-1)

    override fun counted() {
        slideCount = deck.slides.size
        current = current.coerceIn(0, (slideCount - 1).coerceAtLeast(0))
        if (deck.slides.getOrNull(current)?.shapes?.none { it.id == selected } != false) selected = -1
    }

    init { counted() }

    fun addSlide(kind: SlideLayoutKind) { mark(""); current = deck.addSlide(current, kind); selected = -1; edits++; changed() }
    fun duplicateSlide() { mark(""); current = deck.duplicateSlide(current); selected = -1; edits++; changed() }
    fun moveSlideTo(from: Int, to: Int) {
        if (from == to || from !in deck.slides.indices || to !in deck.slides.indices) return
        mark(""); deck.moveSlide(from, to); current = to; edits++; changed()
    }

    /** A picture centred on the slide, at most half its width / two thirds of its height, proportions kept. */
    fun addPicture(bytes: ByteArray, ext: String, widthPx: Int, heightPx: Int) {
        mark("")
        val maxW = deck.widthEmu / 2
        val maxH = deck.heightEmu * 2 / 3
        val ratio = heightPx.toDouble() / widthPx.coerceAtLeast(1)
        var w = maxW; var h = (w * ratio).toLong()
        if (h > maxH) { h = maxH; w = (h / ratio).toLong() }
        selected = deck.addPicture(current, bytes, ext, (deck.widthEmu - w) / 2, (deck.heightEmu - h) / 2, w, h)
        edits++; changed()
    }

    fun addShape(geom: String, rgb: Int) {
        mark("")
        val s = deck.heightEmu / 4
        selected = deck.addShape(current, geom, (deck.widthEmu - s) / 2, (deck.heightEmu - s) / 2, if (geom == "rightArrow") s * 2 else s, s, rgb)
        edits++; changed()
    }

    fun deleteSelected(): Boolean {
        val id = selected.takeIf { it >= 0 } ?: return false
        mark(""); deck.deleteShape(current, id); selected = -1; edits++; changed(); return true
    }

    /** Moves the picked shape by a fraction of the slide ([dx], [dy] in 1/20 of the slide) or scales it by [scale]. */
    fun nudge(dx: Int, dy: Int, scale: Float = 1f): Boolean {
        val sh = deck.slides.getOrNull(current)?.shapes?.firstOrNull { it.id == selected } ?: return false
        val w = (sh.w * scale).toLong(); val h = (sh.h * scale).toLong()
        val x = (sh.x + dx * deck.widthEmu / 20 - (w - sh.w) / 2).coerceIn(-w / 2, deck.widthEmu - w / 2)
        val y = (sh.y + dy * deck.heightEmu / 20 - (h - sh.h) / 2).coerceIn(-h / 2, deck.heightEmu - h / 2)
        mark("box${sh.id}")
        runCatching { deck.setShapeBox(current, sh.id, x, y, w, h) }.onFailure { return false }
        edits++; changed(); return true
    }

    /**
     * The picked object while it is being dragged (shape id → box in EMU): what it is drawn with until the pointer
     * lets go; [endBoxDrag] then writes it to the file as one undo step. Every pointer event gives the travel since
     * the press ([io.embodit.spatialoffice.core.layout.DragTotal]), so the box never drifts (profile D53).
     */
    var dragBox by mutableStateOf<Pair<Int, io.embodit.spatialoffice.core.layout.UnitBox>?>(null)
        private set
    private var dragStart: io.embodit.spatialoffice.core.layout.UnitBox? = null

    /** The box [id] is drawn with right now (the drag in progress, else the file's). */
    fun boxOf(sh: io.embodit.spatialoffice.core.pptx.Shape): io.embodit.spatialoffice.core.layout.UnitBox =
        dragBox?.takeIf { it.first == sh.id }?.second ?: io.embodit.spatialoffice.core.layout.UnitBox(sh.x, sh.y, sh.w, sh.h)

    /** Press on object [id] of the current slide (its body, its move tab or a corner): it is picked and the drag starts. */
    fun beginBoxDrag(id: Int): Boolean {
        if (readOnly) return false
        val sh = deck.slides.getOrNull(current)?.shapes?.firstOrNull { it.id == id } ?: return false
        if (sh.w <= 0 || sh.h <= 0) return false
        selected = id
        dragStart = io.embodit.spatialoffice.core.layout.UnitBox(sh.x, sh.y, sh.w, sh.h); dragBox = id to dragStart!!
        return true
    }

    /** The pointer has travelled [dx], [dy] EMU since the press: the object follows. */
    fun moveBoxBy(dx: Long, dy: Long) {
        val (id, _) = dragBox ?: return
        val start = dragStart ?: return
        dragBox = id to io.embodit.spatialoffice.core.layout.BoxDrag.move(start, dx, dy, deck.widthEmu, deck.heightEmu)
    }

    /** A corner has travelled [dx], [dy] EMU since the press: the opposite corner stays; pictures keep their proportions. */
    fun resizeBoxBy(corner: io.embodit.spatialoffice.core.layout.Corner, dx: Long, dy: Long) {
        val (id, _) = dragBox ?: return
        val start = dragStart ?: return
        val picture = deck.slides.getOrNull(current)?.shapes?.firstOrNull { it.id == id }?.kind == io.embodit.spatialoffice.core.pptx.ShapeKind.PICTURE
        dragBox = id to io.embodit.spatialoffice.core.layout.BoxDrag.resize(start, corner, dx, dy, keepAspect = picture)
    }

    /** The pointer let go: the box goes into the file (one undo step). Returns true when the object moved or changed size. */
    fun endBoxDrag(): Boolean {
        val (id, box) = dragBox ?: return false
        val start = dragStart
        dragBox = null; dragStart = null
        if (start == null || box == start) return false
        mark("")
        val ok = runCatching { deck.setShapeBox(current, id, box.x, box.y, box.w, box.h) }
            .onFailure { android.util.Log.w(TAG, "shape $id cannot be moved: ${it.message}") }.isSuccess
        if (ok) { edits++; changed() }
        return ok
    }

    fun cancelBoxDrag() { dragBox = null; dragStart = null }

    /** A 3 × 3 (or [rows] × [cols]) table in the middle of the slide; it is picked at once (D57). */
    fun addTable(rows: Int = 3, cols: Int = 3) {
        mark("")
        val w = deck.widthEmu * 3 / 5; val h = deck.heightEmu / 3
        selected = deck.addTable(current, rows, cols, (deck.widthEmu - w) / 2, (deck.heightEmu - h) / 2, w, h)
        edits++; changed()
    }

    fun setTableCell(slide: Int, shapeId: Int, row: Int, col: Int, text: String) {
        val old = deck.slides.getOrNull(slide)?.shapes?.firstOrNull { it.id == shapeId }?.table?.getOrNull(row)?.getOrNull(col) ?: return
        if (old == text) return
        mark("tc$slide.$shapeId.$row.$col")
        runCatching { deck.setTableCell(slide, shapeId, row, col, text) }.onFailure { android.util.Log.w(TAG, "table cell not editable: ${it.message}"); return }
        edits++; changed()
    }

    // ---- M3 p40: rows, columns and merged cells of the picked table

    /** The current cell (shape id, row, col) of a table: the last cell clicked; the table tools act on it. */
    var tableCell by mutableStateOf<Triple<Int, Int, Int>?>(null)

    /** The picked object when it is a table whose cells can be worked on. */
    fun pickedTable(): io.embodit.spatialoffice.core.pptx.Shape? = pickedShape()?.takeIf { it.table != null && it.cellsEditable }

    /** The current cell of the picked table (row, col), the first cell when none was clicked yet; null without a table. */
    fun currentCell(): Pair<Int, Int>? {
        val t = pickedTable() ?: return null
        val rows = t.table!!.size; val cols = t.table!!.firstOrNull()?.size ?: 0
        val c = tableCell?.takeIf { it.first == t.id } ?: return 0 to 0
        return c.second.coerceIn(0, rows - 1) to c.third.coerceIn(0, cols - 1)
    }

    /** Sets the current cell of table [shapeId] (a click on the cell; the debug `stable-cell`). Covered cells name their area's first cell. */
    fun pickCell(shapeId: Int, row: Int, col: Int) {
        val t = deck.slides.getOrNull(current)?.shapes?.firstOrNull { it.id == shapeId } ?: return
        val a = runCatching { deck.tableArea(current, shapeId, row, col) }.getOrNull()
        tableCell = if (a != null && t.cellsEditable) Triple(shapeId, a[0], a[1]) else Triple(shapeId, row, col)
    }

    private fun tableOp(what: String, op: (Int, Int, Int) -> Boolean, next: (Int, Int) -> Pair<Int, Int>): Boolean {
        val t = pickedTable() ?: return false
        val (r, c) = currentCell() ?: return false
        mark("")
        val ok = runCatching { op(t.id, r, c) }.onFailure { android.util.Log.w(TAG, "SLIDETABLE $what failed: ${it.message}") }.getOrDefault(false)
        android.util.Log.i(TAG, "SLIDETABLE $what shape=${t.id} cell=$r,$c ok=$ok")
        if (!ok) return false
        val (nr, nc) = next(r, c)
        edits++; changed()
        pickedTable()?.let { nt ->
            val rows = nt.table!!.size; val cols = nt.table!!.firstOrNull()?.size ?: 1
            tableCell = Triple(nt.id, nr.coerceIn(0, rows - 1), nc.coerceIn(0, cols - 1))
            android.util.Log.i(TAG, "SLIDETABLE now rows=$rows cols=$cols cell=${tableCell?.second},${tableCell?.third} spans=${nt.tableCells?.flatten()?.count { it.gridSpan > 1 || it.rowSpan > 1 }}")
        }
        return true
    }

    /** Insert a row above / below the current cell, or delete its row ("above", "below", "delete"). */
    fun tableRow(op: String): Boolean = when (op) {
        "above" -> tableOp("row-above", { id, r, _ -> deck.tableInsertRow(current, id, r, below = false) }) { r, c -> r to c }
        "below" -> tableOp("row-below", { id, r, _ -> deck.tableInsertRow(current, id, r, below = true) }) { r, c -> (r + 1) to c }
        "delete" -> tableOp("row-delete", { id, r, _ -> deck.tableDeleteRow(current, id, r) }) { r, c -> r to c }
        else -> false
    }

    /** Insert a column left / right of the current cell, or delete its column ("left", "right", "delete"). */
    fun tableCol(op: String): Boolean = when (op) {
        "left" -> tableOp("col-left", { id, _, c -> deck.tableInsertCol(current, id, c, right = false) }) { r, c -> r to c }
        "right" -> tableOp("col-right", { id, _, c -> deck.tableInsertCol(current, id, c, right = true) }) { r, c -> r to (c + 1) }
        "delete" -> tableOp("col-delete", { id, _, c -> deck.tableDeleteCol(current, id, c) }) { r, c -> r to c }
        else -> false
    }

    /** Merge the current cell with the one to its right / below it. */
    fun tableMerge(down: Boolean): Boolean = tableOp(if (down) "merge-down" else "merge-right", { id, r, c -> deck.tableMerge(current, id, r, c, down) }) { r, c -> r to c }

    /** What the table rows & columns menu can do now: (can delete a row, can delete a column, can merge right, can merge down). */
    fun tableAbilities(): BooleanArray {
        val t = pickedTable() ?: return BooleanArray(4)
        val (r, c) = currentCell() ?: return BooleanArray(4)
        val rows = t.table!!.size; val cols = t.table!!.firstOrNull()?.size ?: 0
        val a = runCatching { deck.tableArea(current, t.id, r, c) }.getOrNull() ?: intArrayOf(r, c, r, c)
        fun fits(nr: Int, nc: Int, down: Boolean): Boolean {
            if (nr >= rows || nc >= cols) return false
            val b = runCatching { deck.tableArea(current, t.id, nr, nc) }.getOrNull() ?: return false
            return if (down) b[1] == a[1] && b[3] == a[3] else b[0] == a[0] && b[2] == a[2]
        }
        return booleanArrayOf(rows > 1, cols > 1, fits(a[0], a[3] + 1, false), fits(a[2] + 1, a[1], true))
    }

    // ---- M3 p26: page background

    /** A plain background colour on the current slide, or with [all] on every slide; null = back to the layout's. */
    fun setBackground(rgb: Int?, all: Boolean) {
        mark("")
        val which = if (all) deck.slides.indices.toList() else listOf(current)
        for (i in which) deck.setSlideBackground(i, rgb)
        android.util.Log.i(TAG, "SLIDEBG color=${rgb?.let { "%06X".format(it) } ?: "none"} slides=${which.joinToString(",")}")
        images.clear(); edits++; changed()
    }

    /** A picture background on the current slide, or with [all] on every slide (one picture part, shared). */
    fun setBackgroundPicture(bytes: ByteArray, ext: String, all: Boolean) {
        mark("")
        val media = deck.setSlideBackgroundPicture(current, bytes, ext)
        if (all) for (i in deck.slides.indices) if (i != current) deck.setSlideBackgroundPicturePart(i, media)
        android.util.Log.i(TAG, "SLIDEBG picture=$media bytes=${bytes.size} slides=${if (all) "all" else current.toString()}")
        images.clear(); edits++; changed()
    }
    fun setColors(colors: Map<String, Int>) { mark(""); deck.setColorScheme(colors); images.clear(); edits++; changed() }
    fun deleteSlide(): Boolean {
        if (deck.slides.size <= 1) return false
        mark(""); deck.deleteSlide(current); current = current.coerceAtMost(deck.slides.size - 1); edits++; changed(); return true
    }
    fun moveSlide(delta: Int) {
        val to = current + delta
        if (to !in deck.slides.indices) return
        mark(""); deck.moveSlide(current, to); current = to; edits++; changed()
    }
    fun addTextBox(text: String) {
        mark("")
        // D57: the new text box is picked at once, so the object tools act on it (as with shapes and pictures)
        selected = deck.addTextBox(current, deck.widthEmu / 4, deck.heightEmu * 2 / 5, deck.widthEmu / 2, deck.heightEmu / 6, text); edits++; changed()
    }

    // ---- p19 object copy / paste / duplicate, p20 layer order

    /** Bumped when text changes from outside the text fields (find / replace), so the notes field takes the new text. */
    var outsideEdits by mutableIntStateOf(0)
        private set

    /** A text field on the slide or the notes has the keyboard: Ctrl+C / V / D are its own, not the object's. */
    var textEditing by mutableStateOf(false)

    /** [Copy] on the picked object; false (with nothing kept) for a chart or diagram, which this version cannot copy. */
    fun copySelected(): Boolean {
        val id = selected.takeIf { it >= 0 } ?: return false
        val c = runCatching { deck.copyShape(current, id) }.onFailure { android.util.Log.i(TAG, "OBJCOPY refused: ${it.message}") }.getOrNull() ?: return false
        objClip = ObjClip(this, c)
        android.util.Log.i(TAG, "OBJCOPY slide=$current id=$id")
        return true
    }

    val canPaste: Boolean get() = objClip?.from === this

    /** [Paste] on the current slide: a little down and right of where it was copied, picked at once (D57). */
    fun pasteObject(): Boolean {
        val c = objClip?.takeIf { it.from === this }?.clip ?: return false
        val step = deck.widthEmu / 40
        val before = runCatching { serialize() }.getOrNull() ?: return false
        val id = runCatching { deck.pasteShape(current, c, step, step) }.onFailure { android.util.Log.w(TAG, "OBJPASTE refused: ${it.message}") }.getOrNull() ?: return false
        pushUndo(before); edits++; changed(); selected = id
        android.util.Log.i(TAG, "OBJPASTE slide=$current id=$id")
        return true
    }

    /** [Duplicate] (Ctrl+D): a copy beside the picked object on the same slide, picked at once. */
    fun duplicateSelected(): Boolean {
        val src = selected.takeIf { it >= 0 } ?: return false
        val before = runCatching { serialize() }.getOrNull() ?: return false
        val id = runCatching { deck.duplicateShape(current, src) }.onFailure { android.util.Log.w(TAG, "OBJDUP refused: ${it.message}") }.getOrNull() ?: return false
        pushUndo(before); edits++; changed(); selected = id
        return true
    }

    /** Whether the picked object can still move that way (the menu greys out the rest). */
    fun canReorder(op: io.embodit.spatialoffice.core.pptx.ZOrder): Boolean {
        val shapes = deck.slides.getOrNull(current)?.shapes ?: return false
        val i = shapes.indexOfFirst { it.id == selected }
        if (i < 0) return false
        return when (op) {
            io.embodit.spatialoffice.core.pptx.ZOrder.FRONT, io.embodit.spatialoffice.core.pptx.ZOrder.FORWARD -> i < shapes.size - 1
            else -> i > 0
        }
    }

    fun reorder(op: io.embodit.spatialoffice.core.pptx.ZOrder): Boolean {
        val id = selected.takeIf { it >= 0 } ?: return false
        if (!canReorder(op)) return false
        mark("")
        val ok = runCatching { deck.reorderShape(current, id, op) }.getOrDefault(false)
        if (ok) { edits++; changed(); selected = id }
        return ok
    }

    // ---- p21 find / replace over every slide, notes included

    /** The same text [spansOf] gives (paragraphs split by "\n", line breaks inside one as [SOFT_BREAK]), so hit offsets match. */
    private fun shapeText(sh: io.embodit.spatialoffice.core.pptx.Shape) = sh.paras.joinToString("\n") { it.text.replace('\n', SOFT_BREAK) }

    override fun findHits(q: String, matchCase: Boolean, allSheets: Boolean): List<FindHit> {
        if (q.isEmpty()) return emptyList()
        val out = ArrayList<FindHit>()
        deck.slides.forEachIndexed { i, sl ->
            for (sh in sl.shapes) if (sh.editable) for (at in io.embodit.spatialoffice.core.text.RichText.matches(shapeText(sh), q, !matchCase)) out.add(FindHit(i, sh.id, 0, at, q.length))
            for (at in io.embodit.spatialoffice.core.text.RichText.matches(sl.notes, q, !matchCase)) out.add(FindHit(i, -1, 0, at, q.length))
        }
        return out
    }

    override fun hitText(h: FindHit): String? = deck.slides.getOrNull(h.a)?.let { sl -> if (h.b < 0) sl.notes else sl.shapes.firstOrNull { it.id == h.b }?.let { shapeText(it) } }

    override fun showHit(h: FindHit) {
        current = h.a.coerceIn(0, (slideCount - 1).coerceAtLeast(0))
        selected = if (h.b >= 0) h.b else -1
        super.showHit(h)
    }

    /** Find / replace keeps each run's format (M2): the edit goes through the same span model as typing. */
    private fun parasFor(old: Shape, text: String): List<TextPara> = parasFrom(old, RichText.applyEdit(spansOf(old), text))

    override fun replaceHit(h: FindHit, q: String, repl: String, matchCase: Boolean): Boolean {
        val sl = deck.slides.getOrNull(h.a) ?: return false
        if (h.b < 0) {
            if (!same(sl.notes, h.start, q, matchCase)) return false
            mark(""); deck.setNotes(h.a, sl.notes.substring(0, h.start) + repl + sl.notes.substring(h.start + q.length)); edits++; outsideEdits++; changed(); return true
        }
        val sh = sl.shapes.firstOrNull { it.id == h.b && it.editable } ?: return false
        val t = shapeText(sh)
        if (!same(t, h.start, q, matchCase)) return false
        mark(""); deck.setShapeText(h.a, sh.id, parasFor(sh, t.substring(0, h.start) + repl + t.substring(h.start + q.length))); edits++; changed()
        return true
    }

    override fun replaceAllHits(q: String, repl: String, matchCase: Boolean, allSheets: Boolean): Int {
        val hits = findHits(q, matchCase)
        if (hits.isEmpty()) return 0
        mark("")
        for ((slide, shapeId) in hits.map { it.a to it.b }.distinct()) {
            val sl = deck.slides[slide]
            if (shapeId < 0) deck.setNotes(slide, sl.notes.replace(q, repl, ignoreCase = !matchCase))
            else sl.shapes.firstOrNull { it.id == shapeId }?.let { sh -> deck.setShapeText(slide, sh.id, parasFrom(sh, RichText.replaceAll(spansOf(sh), q, repl, !matchCase).first)) }
        }
        edits++; outsideEdits++; changed()
        return hits.size
    }

    override fun serialize(): ByteArray = deck.save()
    override fun restore(bytes: ByteArray) { deck = PptxDeck.open(bytes); images.clear(); current = current.coerceIn(0, deck.slides.size - 1); selected = -1 }
    override fun firstLine(): String = deck.firstTitle()
    override fun changedCount(): Int = edits
    override fun compat(): List<CompatNote> = deck.compat()
    override val pieceCount: Int get() = deck.slides.size
}

fun List<CompatNote>.approx(): Int = filter { it.kind == CompatKind.APPROX }.sumOf { it.count.coerceAtLeast(1) }
fun List<CompatNote>.held(): Int = filter { it.kind == CompatKind.READ_ONLY }.sumOf { it.count.coerceAtLeast(1) }
