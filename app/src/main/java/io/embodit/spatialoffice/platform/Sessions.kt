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
import io.embodit.spatialoffice.core.text.HtmlText
import io.embodit.spatialoffice.core.text.PlainDoc
import io.embodit.spatialoffice.core.text.Span
import io.embodit.spatialoffice.core.xlsx.NumFmt
import io.embodit.spatialoffice.core.xlsx.XlsxWorkbook
import java.io.File

/** What the document window draws, whatever the file type underneath. */
sealed class UiBlock {
    abstract val id: Int
    data class Para(override val id: Int, val style: ParaStyle, val spans: List<Span>, val editable: Boolean, val why: String?, val align: Int = 0) : UiBlock()
    data class Table(override val id: Int, val rows: List<List<String>>) : UiBlock()
    data class Image(override val id: Int, val bitmap: Bitmap?) : UiBlock()
    data class Opaque(override val id: Int, val kind: String) : UiBlock()
    data class Page(override val id: Int, val bitmap: Bitmap) : UiBlock()
}

/** One open file. Edits go through the session so dirty state, undo and recomposition stay in one place. */
abstract class Session(ref: FileRef, val kind: FileKind) {
    var ref by mutableStateOf(ref)
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
                    is Paragraph -> UiBlock.Para(b.id, b.style, b.runs.map { Span(it.text, it.bold, it.italic, it.underline, it.src, it.strike) }, b.editable, b.readOnlyWhy, b.align)
                    is TableBlock -> UiBlock.Table(b.id, b.rows)
                    is ImageBlock -> UiBlock.Image(b.id, images.getOrPut(b.part) { d.partBytes(b.part)?.let { runCatching { BitmapFactory.decodeByteArray(it, 0, it.size) }.getOrNull() } })
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

    private fun runs(spans: List<Span>) = spans.map { Run(it.text, it.bold, it.italic, it.underline, it.src, it.strike) }

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

    fun insertTable(afterId: Int, rows: Int, cols: Int): Int {
        val d = docx ?: return -1
        mark("")
        val id = d.insertTableAfter(afterId, rows, cols)
        changed()
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

    /** Matches of [find] in editable paragraphs: (paragraph id, offset). */
    fun find(find: String): List<Pair<Int, Int>> = blocks().filterIsInstance<UiBlock.Para>().filter { it.editable }
        .flatMap { p -> io.embodit.spatialoffice.core.text.RichText.matches(io.embodit.spatialoffice.core.text.RichText.text(p.spans), find).map { p.id to it } }

    /** Replaces every match in the editable paragraphs as one undo step; returns how many were replaced. */
    fun replaceAll(find: String, replacement: String): Int {
        if (find.isEmpty() || readOnly) return 0
        var total = 0
        var marked = false
        for (p in blocks().filterIsInstance<UiBlock.Para>().filter { it.editable }) {
            val (out, n) = io.embodit.spatialoffice.core.text.RichText.replaceAll(p.spans, find, replacement)
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

    fun select(r: Int, c: Int) { row = r.coerceIn(0, 9999); col = c.coerceIn(0, 701) }

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
        runCatching { rowCount = book.rows(sheet); colCount = book.cols(sheet); sheetCount = book.sheetNames.size }
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

    fun setBold(bold: Boolean) { mark(""); book.setBold(sheet, row, col, bold); changed() }
    fun setFill(rgb: Int?) { if (isCsv) return; mark(""); book.setFill(sheet, row, col, rgb); changed() }
    fun deleteRow() { mark(""); book.deleteRow(sheet, row); csvEdits++; changed() }
    fun insertColumn() { mark(""); book.insertColumnRight(sheet, col); csvEdits++; changed() }
    fun deleteColumn() { mark(""); book.deleteColumn(sheet, col); csvEdits++; changed() }
    fun toggleFreeze(): Boolean {
        if (isCsv) return false
        mark(""); val on = book.frozenRows(sheet) == 0; book.setFrozenRows(sheet, if (on) 1 else 0); changed(); return on
    }
    val frozen: Int get() = if (isCsv) 0 else book.frozenRows(sheet)
    fun setFmt(fmt: NumFmt) { mark(""); book.setFmt(sheet, row, col, fmt); changed() }
    fun insertRowBelow() { mark(""); book.insertRowBelow(sheet, row); csvEdits++; changed() }
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

    companion object {
        const val COLS_PER_PIECE = 3
        private fun fromCsv(text: String): XlsxWorkbook {
            val b = XlsxWorkbook.blank()
            Csv.parse(text).forEachIndexed { r, line -> line.forEachIndexed { c, v -> if (v.isNotEmpty()) b.setCell(0, r, c, v) } }
            return b
        }
    }
}

class DeckSession(ref: FileRef, bytes: ByteArray) : Session(ref, FileKind.SLIDES) {
    var deck: PptxDeck = PptxDeck.open(bytes)
        private set
    var current by mutableIntStateOf(0)
    private var edits = 0
    private val images = HashMap<String, Bitmap?>()

    fun image(part: String): Bitmap? = images.getOrPut(part) { deck.partBytes(part)?.let { runCatching { BitmapFactory.decodeByteArray(it, 0, it.size) }.getOrNull() } }

    fun setText(slide: Int, shapeId: Int, text: String) {
        val old = deck.slides[slide].shapes.firstOrNull { it.id == shapeId } ?: return
        val lines = text.split('\n')
        val paras = lines.mapIndexed { i, t -> TextPara(t, old.paras.getOrNull(i)?.level ?: old.paras.lastOrNull()?.level ?: 0, old.paras.getOrNull(i)?.bullet ?: old.paras.lastOrNull()?.bullet ?: false) }
        if (paras == old.paras) return
        mark("s$slide.$shapeId")
        deck.setShapeText(slide, shapeId, paras); edits++
        changed()
    }

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
        deck.addTextBox(current, deck.widthEmu / 4, deck.heightEmu * 2 / 5, deck.widthEmu / 2, deck.heightEmu / 6, text); edits++; changed()
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
