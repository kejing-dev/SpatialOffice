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
import io.embodit.spatialoffice.core.docx.toRun
import io.embodit.spatialoffice.core.docx.toSpan
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
        /** M3 d21: drawn with a horizontal line under it (the file's bottom border). */
        val rule: Boolean = false,
    ) : UiBlock() {
        /** M3 d21: nothing but page breaks: drawn as a page-break marker, not as text. */
        val pageBreak: Boolean get() = spans.isNotEmpty() && spans.all { sp -> sp.text.all { it == PAGE_BREAK } }
    }
    /** [widthPct]: share of the text width, 10–100. */
    data class Table(
        override val id: Int, val rows: List<List<String>>, val widthPct: Int = 100,
        /** M3 d42: merged cells (anchors); a covered cell is drawn as part of its anchor. */
        val merges: List<io.embodit.spatialoffice.core.docx.CellMerge> = emptyList(),
    ) : UiBlock() {
        fun covered(r: Int, c: Int): Boolean = merges.any { it.covers(r, c) && !(it.row == r && it.col == c) }
        fun mergeAt(r: Int, c: Int): io.embodit.spatialoffice.core.docx.CellMerge? = merges.firstOrNull { it.row == r && it.col == c }
    }
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
/** M5: one comment's stretch inside a paragraph ([start] == [end]: its words were deleted, nothing is marked). */
data class CommentMark(val id: String, val start: Int, val end: Int)

data class FindHit(val a: Int, val b: Int = 0, val c: Int = 0, val start: Int, val len: Int)

/** v0.6.0 (d25): one line of a document's outline: paragraph id, level (0 title, 1–3 headings), text. */
data class OutlineItem(val id: Int, val level: Int, val text: String)

/** v0.6.0 (d23): a selection across paragraphs, from (paragraph [aId], offset [aOff]) to ([bId], [bOff]); either order. */
data class DocSel(val aId: Int, val aOff: Int, val bId: Int, val bOff: Int)

/** v0.6.0 (d23): one copied paragraph (or part of one) with its formats, style and list level. */
data class DocClipPara(val spans: List<io.embodit.spatialoffice.core.text.Span>, val style: io.embodit.spatialoffice.core.docx.ParaStyle, val level: Int)

/** v0.6.0 (d23): document text copied with its formats; [text] is what went on the system clipboard. */
class DocClip(val paras: List<DocClipPara>, val text: String)

/** The character a page break is held as in paragraph text (`w:br w:type="page"`). */
const val PAGE_BREAK = '\u000C'

/** v0.5.0: the zoom levels Ctrl+= / Ctrl+- step through. */
val ZOOM_STEPS = listOf(50, 67, 75, 80, 90, 100, 110, 125, 150, 175, 200)

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

    /**
     * v0.5.0 (d43 / s41 / p41): view zoom in percent, display only (never written to the file). 100 = the editor's
     * normal view: the document fits the window's width, the sheet at 100 %, the slide fits the window.
     */
    var zoom by mutableIntStateOf(100)
        private set

    /** Ctrl+= / Ctrl+- one step along [ZOOM_STEPS]; [reset] (Ctrl+0) back to 100. */
    fun zoomBy(steps: Int) {
        zoom = if (steps > 0) ZOOM_STEPS.firstOrNull { it > zoom } ?: ZOOM_STEPS.last() else ZOOM_STEPS.lastOrNull { it < zoom } ?: ZOOM_STEPS.first()
        android.util.Log.i(TAG, "ZOOM ${kind.name} -> $zoom%")
    }

    fun setZoomPct(pct: Int) { zoom = pct.coerceIn(ZOOM_STEPS.first(), ZOOM_STEPS.last()); android.util.Log.i(TAG, "ZOOM ${kind.name} -> $zoom%") }

    fun zoomReset() = setZoomPct(100)

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

    // ---- M5 (v0.7.0) review: comments for all three kinds, tracked changes for documents ----

    /** The file kind has comments (docx / xlsx / pptx): the title bar shows [Review]. */
    open val canReview: Boolean get() = false
    /** The review pane has a second list, tracked changes (documents). */
    open val hasRevisions: Boolean get() = false
    /** The review pane of this file's window is open. Kept per file in app data ([ReviewStore]), never in the file. */
    var reviewOpen by mutableStateOf(false)
    /** 0 = comments, 1 = tracked changes. */
    var reviewTab by mutableIntStateOf(0)
    /** The comment whose card is picked in the pane (and whose place is marked in the content). */
    var selComment by mutableStateOf<String?>(null)
    /** "Track my edits" (d36): while on, text I add or delete in a document is stored as a tracked change under my name. */
    var tracking by mutableStateOf(false)
    /** Name written on the tracked changes made while [tracking]; set by the window from Settings › My name. */
    var trackAuthor: String = ""

    open fun comments(): List<io.embodit.spatialoffice.core.review.ReviewComment> = emptyList()
    open fun revisions(): List<io.embodit.spatialoffice.core.review.Revision> = emptyList()
    /** True when a new comment would hang on picked text / a picked object; false = on the caret's paragraph, the current cell or the slide. */
    open val commentOnSelection: Boolean get() = false
    /** Adds a comment at the current place; returns its id, or null when it could not be added there. */
    open fun addComment(author: String, text: String): String? = null
    open fun replyComment(id: String, author: String, text: String): Boolean = false
    open fun resolveComment(id: String, resolved: Boolean): Boolean = false
    open fun editComment(id: String, text: String): Boolean = false
    open fun deleteComment(id: String): Boolean = false
    /** Brings the comment's place into view and marks it (paragraph, cell, slide). */
    open fun showComment(c: io.embodit.spatialoffice.core.review.ReviewComment) {}
    open fun acceptRevision(id: String): Boolean = false
    open fun rejectRevision(id: String): Boolean = false
    open fun acceptAllRevisions(): Int = 0
    open fun rejectAllRevisions(): Int = 0
    open fun showRevision(r: io.embodit.spatialoffice.core.review.Revision) {}

    /** Bumped by Ctrl+Alt+M / Insert › Comment: the pane's text box takes the caret. */
    var composeTick by mutableIntStateOf(0)
    /** Why the last [addComment] could not be done here (a string resource), shown instead of the general message. */
    var reviewHint: Int? = null
    /** The card's quote line for a sheet comment ("C2", or "Sheet2 · C2" on another sheet). */
    open fun commentPlace(c: io.embodit.spatialoffice.core.review.ReviewComment): String = c.quote
    /** What a new comment would hang on, by name (the current cell). */
    open fun commentTargetName(): String = ""
    /** The revision picked in the pane: its paragraph is brought into view. */
    var selRevision by mutableStateOf<String?>(null)

    /** Number on the [Review n] button: open comment threads plus tracked changes that can still be accepted or rejected. */
    fun reviewCount(): Int = runCatching { comments().count { !it.resolved } + revisions().count { it.canResolve } }.getOrDefault(0)

    /** Picks the card of [id] (opening the pane's comment list); null clears the pick. */
    fun pickComment(id: String?) {
        selComment = id
        if (id != null) reviewTab = 0
    }

    /** ISO-8601 time written on new comments and tracked changes. */
    fun nowIso(): String = java.time.Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString()

    /** One review change as one undo step; false (and nothing recorded) when [f] changed nothing or failed. */
    protected fun reviewEdit(what: String, f: () -> Boolean): Boolean {
        if (readOnly) return false
        val before = runCatching { serialize() }.getOrNull() ?: return false
        // a change that threw half-way is rolled back, so the file in memory never holds half an edit
        val ok = runCatching { f() }.onFailure { android.util.Log.w(TAG, "REVIEW $what failed: $it"); runCatching { restore(before) } }.getOrDefault(false)
        if (!ok) return false
        pushUndo(before); changed()
        android.util.Log.i(TAG, "REVIEW $what ok comments=${runCatching { comments().size }.getOrDefault(-1)} revisions=${runCatching { revisions().size }.getOrDefault(-1)}")
        return true
    }

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

    // ---- M5 review (documents): comments d29, tracked changes d30 / d36, header & footer d31, format painter d24 ----
    override val canReview: Boolean get() = docx != null
    override val hasRevisions: Boolean get() = docx != null
    override fun comments(): List<io.embodit.spatialoffice.core.review.ReviewComment> = docx?.comments() ?: emptyList()
    override fun revisions(): List<io.embodit.spatialoffice.core.review.Revision> = docx?.revisions() ?: emptyList()

    /** Paragraph with the caret and the characters picked in it, as the paragraph editor last reported them. */
    var caretRange by mutableStateOf(0 to 0)
        private set
    fun noteCaret(id: Int, a: Int, b: Int) { caretBlock = id; if (caretRange != (a to b)) caretRange = a to b }
    override val commentOnSelection: Boolean get() = caretRange.second > caretRange.first && para(caretBlock) != null

    /** Open comments by the paragraph they start in: the yellow markers on the paper. */
    fun commentMarks(): Map<Int, List<CommentMark>> =
        comments().filter { !it.resolved && it.block >= 0 }.groupBy({ it.block }, { CommentMark(it.id, it.start, it.end) })

    /** On the picked characters; with only a caret, on its whole paragraph; with no caret yet, on the first paragraph. */
    override fun addComment(author: String, text: String): String? {
        val d = docx ?: return null
        val target = para(caretBlock) ?: d.blocks.firstOrNull { it is Paragraph && it.text.isNotBlank() } as? Paragraph ?: d.blocks.firstOrNull { it is Paragraph } as? Paragraph
        if (target == null) { reviewHint = null; return null }
        val len = target.text.length
        val (a, b) = if (target.id == caretBlock) caretRange.first.coerceIn(0, len) to caretRange.second.coerceIn(0, len) else 0 to 0
        var id: String? = null
        reviewHint = null
        reviewEdit("add para=${target.id} $a..$b") { id = d.addComment(target.id, if (b > a) a else 0, if (b > a) b else 0, author, text, nowIso()); true }
        return id
    }
    override fun replyComment(id: String, author: String, text: String): Boolean = reviewEdit("reply") { docx!!.replyComment(id, author, text, nowIso()); true }
    override fun resolveComment(id: String, resolved: Boolean): Boolean = reviewEdit("resolve=$resolved") { docx!!.setCommentResolved(id, resolved); true }
    override fun editComment(id: String, text: String): Boolean = reviewEdit("edit") { docx!!.editComment(id, text); true }
    override fun deleteComment(id: String): Boolean = reviewEdit("delete") { docx!!.deleteComment(id); true }
    override fun showComment(c: io.embodit.spatialoffice.core.review.ReviewComment) { if (c.block >= 0) { scrollTarget = c.block; scrollTick++ } }

    override fun acceptRevision(id: String): Boolean = reviewEdit("accept") { docx!!.acceptRevision(id) }
    override fun rejectRevision(id: String): Boolean = reviewEdit("reject") { docx!!.rejectRevision(id) }
    override fun acceptAllRevisions(): Int { var n = 0; reviewEdit("accept all") { n = docx!!.acceptAll(); n > 0 }; return n }
    override fun rejectAllRevisions(): Int { var n = 0; reviewEdit("reject all") { n = docx!!.rejectAll(); n > 0 }; return n }
    override fun showRevision(r: io.embodit.spatialoffice.core.review.Revision) { selRevision = r.id; if (r.block >= 0) { scrollTarget = r.block; scrollTick++ } }

    /**
     * d36: [plain] is what the editor made of [old] without tracking. Returns the spans to store (what I removed kept as a
     * tracked deletion, what I typed as my tracked insertion) and where the caret goes in their text; null when not tracking.
     * [caretBefore] / [caretAfter]: the field's caret before and after the edit (the same = forward Delete: the caret steps past the struck text).
     */
    fun trackedEdit(old: List<Span>, plain: List<Span>, caretBefore: Int, caretAfter: Int): Pair<List<Span>, Int>? {
        if (docx == null || !tracking || trackAuthor.isBlank()) return null
        val r = io.embodit.spatialoffice.core.docx.Tracking.mergeAt(old.map { it.toRun() }, plain.map { it.toRun() }, trackAuthor, nowIso())
        val caret = if (r.caret < r.deletedEnd && caretBefore == caretAfter) r.deletedEnd else r.caret
        return r.runs.map { it.toSpan() } to caret
    }

    fun headerText(): String? = docx?.headerText()
    fun footerText(): String? = docx?.footerText()
    val headerEditable: Boolean get() = docx?.headerEditable == true
    val footerEditable: Boolean get() = docx?.footerEditable == true
    /** null = leave as it is. True when something changed. */
    fun setHeaderFooter(header: String?, footer: String?): Boolean {
        val d = docx ?: return false
        val h = header?.takeIf { headerEditable && it != (d.headerText() ?: "") }
        val f = footer?.takeIf { footerEditable && it != (d.footerText() ?: "") }
        if (h == null && f == null) return false
        return reviewEdit("header/footer") { d.setHeaderFooter(h, f); true }.also { android.util.Log.i(TAG, "HEADERFOOTER header='${d.headerText()}' footer='${d.footerText()}' ok=$it") }
    }

    /** The character format in hand (d24): an opaque token of the document, put on the next text that is tapped or dragged over; null = none. */
    var painter by mutableStateOf<DocxDocument.RunFormat?>(null)
        private set
    fun pickPainter(id: Int, offset: Int): Boolean {
        val d = docx ?: return false
        painter = runCatching { d.runFormatAt(id, offset) }.onFailure { android.util.Log.w(TAG, "PAINTER pick failed: $it") }.getOrNull()
        android.util.Log.i(TAG, "PAINTER picked para=$id offset=$offset ok=${painter != null}")
        return painter != null
    }
    fun dropPainter() { if (painter != null) { painter = null; android.util.Log.i(TAG, "PAINTER dropped") } }
    /** Puts the format in hand on characters [a, b) of paragraph [id] (a == b: the whole paragraph), then lets go of it. */
    fun paint(id: Int, a: Int, b: Int): Boolean {
        val d = docx ?: return false
        val token = painter ?: return false
        val p = para(id)?.takeIf { it.editable } ?: return false
        val ok = reviewEdit("paint para=$id $a..$b") { d.paintFormat(token, p.id, a, b); true }
        painter = null
        android.util.Log.i(TAG, "PAINTER applied para=$id range=$a..$b ok=$ok")
        return ok
    }
    /** The same over a selection that crosses paragraphs: every part takes the format, as one undo step. */
    fun paintParts(): Boolean {
        val d = docx ?: return false
        val token = painter ?: return false
        val parts = selectedParts().filter { it.first.editable && it.third > it.second }
        if (parts.isEmpty()) return false
        val ok = reviewEdit("paint parts=${parts.size}") { parts.forEach { (p, a, b) -> d.paintFormat(token, p.id, a, b) }; true }
        painter = null; crossSel = null
        return ok
    }

    fun blocks(): List<UiBlock> {
        docx?.let { d ->
            return d.blocks.map { b ->
                when (b) {
                    is Paragraph -> UiBlock.Para(
                        b.id, b.style, b.runs.map { it.toSpan() }, b.editable, b.readOnlyWhy, b.align,
                        b.level, b.indentTw, b.lineSpacing, b.spaceBefore, b.spaceAfter, b.baseSizeHp, b.rule,
                    )
                    is TableBlock -> UiBlock.Table(b.id, b.rows, b.widthPct, b.merges)
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

    private fun runs(spans: List<Span>) = spans.map { it.toRun() }

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

    // ---- M3 d20: links ----

    /**
     * Makes characters [a, b) of paragraph [id] a link to [url] (null takes the link away). One undo step; false when
     * nothing changed or the paragraph cannot be edited.
     */
    fun setLink(id: Int, a: Int, b: Int, url: String?): Boolean {
        val p = blocks().firstOrNull { it.id == id } as? UiBlock.Para ?: return false
        if (!richText || !p.editable || readOnly) return false
        val len = RichText.text(p.spans).length
        val from = a.coerceIn(0, len); val to = b.coerceIn(0, len)
        if (to <= from) return false
        val u = url?.let { normalizeUrl(it) }?.takeIf { it.isNotEmpty() }
        if (url != null && u == null) return false
        val next = RichText.setLink(p.spans, from, to, u)
        if (next == p.spans) return false
        // its own undo step (setParagraph would join it to typing in the same paragraph)
        mark(""); docx?.setParagraph(p.id, runs(next), p.style); changed()
        android.util.Log.i(TAG, "DOCLINK ${if (u == null) "remove" else "set"} para=$id range=$from..$to url=$u")
        return true
    }

    /** Puts [text] (the address itself when blank) at [at] in paragraph [id] as a link to [url]; returns the range it took. */
    fun insertLink(id: Int, at: Int, text: String, url: String): IntRange? {
        val p = blocks().firstOrNull { it.id == id } as? UiBlock.Para ?: return null
        if (!richText || !p.editable || readOnly) return null
        val u = normalizeUrl(url)
        val shown = text.ifBlank { url.trim() }
        if (shown.isEmpty() || u.isEmpty()) return null
        val old = RichText.text(p.spans)
        val pos = at.coerceIn(0, old.length)
        val typed = RichText.applyEdit(p.spans, old.substring(0, pos) + shown + old.substring(pos))
        mark(""); docx?.setParagraph(p.id, runs(RichText.setLink(typed, pos, pos + shown.length, u)), p.style); changed()
        android.util.Log.i(TAG, "DOCLINK insert para=$id at=$pos len=${shown.length} url=$u")
        return pos until pos + shown.length
    }

    /** The links of paragraph [id]: URL and character range, in order. */
    fun linksOf(id: Int): List<Triple<String, Int, Int>> = (blocks().firstOrNull { it.id == id } as? UiBlock.Para)?.let { RichText.links(it.spans) } ?: emptyList()

    // ---- M3 d21: horizontal line, page break ----

    /** Inserts a horizontal line (an empty paragraph with a bottom border) after block [afterId]; returns its id. */
    fun insertRule(afterId: Int): Int {
        val d = docx ?: return -1
        if (readOnly) return -1
        mark("")
        val id = d.insertRuleAfter(afterId)
        changed()
        android.util.Log.i(TAG, "DOCINSERT rule after=$afterId id=$id")
        return id
    }

    /**
     * Inserts a page break after block [afterId] and returns the paragraph to type in after it: the next editable
     * paragraph, or a new empty one when there is none.
     */
    fun insertPageBreak(afterId: Int): Int {
        val d = docx ?: return -1
        if (readOnly) return -1
        mark("")
        val id = d.insertPageBreakAfter(afterId)
        val all = d.blocks
        val i = all.indexOfFirst { it.id == id }
        val next = all.getOrNull(i + 1) as? Paragraph
        val typeIn = if (next != null && next.editable && !next.pageBreak) next.id else d.insertParagraphAfter(id, emptyList(), ParaStyle.BODY)
        changed()
        android.util.Log.i(TAG, "DOCINSERT pagebreak after=$afterId id=$id next=$typeIn")
        return typeIn
    }

    // ---- M3 d42: table rows and columns, merging ----

    /** The table with the caret and the caret cell (table id, row, grid column), if the caret is in a table that exists. */
    fun caretCell(): Triple<Int, Int, Int>? = caretTable()?.first

    /**
     * [op] on the table with the caret: "row-above", "row-below", "row-delete", "col-left", "col-right", "col-delete",
     * "merge-right", "merge-down". One undo step; the caret stays on the same cell (or the nearest one left). False when
     * nothing changed (no caret cell, nothing to merge with).
     */
    fun tableOp(op: String): Boolean {
        val d = docx ?: return false
        if (readOnly) return false
        val (t, _) = caretTable() ?: return false
        val (id, r, c) = t
        val before = runCatching { serialize() }.getOrNull() ?: return false
        val ok = runCatching {
            when (op) {
                "row-above" -> d.insertTableRow(id, r, below = false)
                "row-below" -> d.insertTableRow(id, r, below = true)
                "row-delete" -> d.deleteTableRow(id, r)
                "col-left" -> d.insertTableColumn(id, c, right = false)
                "col-right" -> d.insertTableColumn(id, c, right = true)
                "col-delete" -> d.deleteTableColumn(id, c)
                "merge-right" -> d.mergeTableRight(id, r, c)
                "merge-down" -> d.mergeTableDown(id, r, c)
                else -> false
            }
        }.onFailure { android.util.Log.w(TAG, "DOCTABLE $op failed: ${it.message}") }.getOrDefault(false)
        if (!ok) { android.util.Log.i(TAG, "DOCTABLE $op table=$id cell=$r,$c changed=false"); return false }
        pushUndo(before)
        val now = d.blocks.firstOrNull { it.id == id } as? TableBlock
        if (now == null) { tableCaret = null; picked = -1 }
        else {
            val nr = when (op) { "row-above" -> r + 1; "row-delete" -> r.coerceAtMost(now.rows.size - 1); else -> r }
            val cols = now.rows.maxOfOrNull { it.size } ?: 1
            val nc = when (op) { "col-left" -> c + 1; "col-delete" -> c.coerceAtMost(cols - 1); else -> c }
            // a caret left under a merge goes to the merged cell's top-left
            val m = now.merges.firstOrNull { it.covers(nr, nc) }
            focusCell(id, m?.row ?: nr, m?.col ?: nc)
        }
        changed()
        android.util.Log.i(TAG, "DOCTABLE $op table=$id cell=$r,$c -> rows=${now?.rows?.size ?: 0} cols=${now?.rows?.maxOfOrNull { it.size } ?: 0} merges=${now?.merges ?: emptyList<Any>()} caret=$tableCaret")
        return true
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

    companion object {
        /** d23: what was copied with formats, shared by every document window (pasting into another file keeps them too). */
        var docClip: DocClip? = null

        /** Characters [from, to) of [spans] as spans. */
        fun cutSpans(spans: List<Span>, from: Int, to: Int): List<Span> {
            val out = ArrayList<Span>()
            var pos = 0
            for (sp in spans) {
                val a = maxOf(from, pos); val b = minOf(to, pos + sp.text.length)
                if (b > a) out.add(sp.copy(text = sp.text.substring(a - pos, b - pos)))
                pos += sp.text.length
            }
            return out
        }
    

        private val SCHEME = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:")
        private val EMAIL = Regex("^[^@\\s/]+@[^@\\s/]+\\.[^@\\s/]+$")

        /** A typed address without a scheme gets one: "example.com" → "https://example.com"; "x@y.z" → "mailto:x@y.z". */
        fun normalizeUrl(url: String): String {
            val u = url.trim()
            if (u.isEmpty()) return u
            // v0.5.1 (q13-2): schemes are case-insensitive (RFC 3986) but Android matches them exactly: "Https://…"
            // found no browser. The scheme is written in lower case; the rest stays as typed.
            SCHEME.find(u)?.let { m -> return m.value.lowercase() + u.substring(m.value.length) }
            if (EMAIL.matches(u)) return "mailto:$u"
            return "https://$u"
        }
    }

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

    // ---- v0.6.0 (M4 d25): outline

    /** The title and headings of the document, in order: [More ▸ Outline] lists them and jumps there. */
    fun outline(): List<OutlineItem> = blocks().filterIsInstance<UiBlock.Para>().mapNotNull { p ->
        val level = when (p.style) { ParaStyle.TITLE -> 0; ParaStyle.H1 -> 1; ParaStyle.H2 -> 2; ParaStyle.H3 -> 3; else -> return@mapNotNull null }
        val t = io.embodit.spatialoffice.core.text.RichText.text(p.spans).trim()
        if (t.isEmpty()) null else OutlineItem(p.id, level, t)
    }

    /** Paragraph the page should scroll to (and put the caret in); [scrollTick] bumps on every jump. */
    var scrollTarget = -1
        private set
    var scrollTick by mutableIntStateOf(0)
        private set
    fun jumpTo(id: Int) {
        scrollTarget = id; caretBlock = id; scrollTick++
        android.util.Log.i(TAG, "OUTLINE jump to $id '${outline().firstOrNull { it.id == id }?.text}'")
    }

    // ---- v0.6.0 (M4 d23): a selection across paragraphs; copy / cut / paste keep the formats

    /** The selection across paragraphs (null = none; inside one paragraph its own text field has the selection). */
    var crossSel by mutableStateOf<DocSel?>(null)

    /** [crossSel] in reading order (start before end). */
    fun orderedSel(): DocSel? {
        val s = crossSel ?: return null
        val order = blocks().map { it.id }
        val ia = order.indexOf(s.aId); val ib = order.indexOf(s.bId)
        if (ia < 0 || ib < 0) return null
        return if (ia < ib || (ia == ib && s.aOff <= s.bOff)) s else DocSel(s.bId, s.bOff, s.aId, s.aOff)
    }

    /** The editable paragraphs [crossSel] runs through, each with the characters of it that are selected [a, b). */
    fun selectedParts(): List<Triple<UiBlock.Para, Int, Int>> {
        val s = orderedSel() ?: return emptyList()
        val all = blocks()
        val ia = all.indexOfFirst { it.id == s.aId }; val ib = all.indexOfFirst { it.id == s.bId }
        val out = ArrayList<Triple<UiBlock.Para, Int, Int>>()
        for (i in ia..ib) {
            val p = all[i] as? UiBlock.Para ?: continue
            if (!p.editable || p.pageBreak) continue
            val len = io.embodit.spatialoffice.core.text.RichText.text(p.spans).length
            val a = if (i == ia) s.aOff.coerceIn(0, len) else 0
            val b = if (i == ib) s.bOff.coerceIn(0, len) else len
            out.add(Triple(p, a, maxOf(a, b)))
        }
        return out
    }

    private fun clipOf(parts: List<Triple<UiBlock.Para, Int, Int>>): DocClip? {
        if (parts.isEmpty()) return null
        // pasted text takes its formats with it, not the run it came from (src -1: written from the span's own formats)
        val paras = parts.map { (p, a, b) -> DocClipPara(cutSpans(p.spans, a, b).map { it.copy(src = -1) }, p.style, p.level) }
        if (paras.size == 1 && paras[0].spans.isEmpty()) return null
        return DocClip(paras, paras.joinToString("\n") { cp -> cp.spans.joinToString("") { it.text } })
    }

    /** Ctrl+C on [crossSel]: kept for pasting with formats; returns it (its text also goes on the system clipboard). */
    fun copyCross(): DocClip? = clipOf(selectedParts())?.also { docClip = it; android.util.Log.i(TAG, "DOCCLIP copy paras=${it.paras.size} chars=${it.text.length} styles=${it.paras.map { p -> p.style }}") }

    /** Ctrl+C with characters [a, b) of one paragraph selected: the same, for one paragraph. */
    fun copyRange(id: Int, a: Int, b: Int): DocClip? {
        val p = blocks().firstOrNull { it.id == id } as? UiBlock.Para ?: return null
        if (b <= a) return null
        return clipOf(listOf(Triple(p, a, b)))?.also { docClip = it; android.util.Log.i(TAG, "DOCCLIP copy para=$id $a..$b") }
    }

    /** Delete / Backspace / Ctrl+X on [crossSel]: one undo step; returns where the caret goes (paragraph, offset). */
    fun deleteCross(): Pair<Int, Int>? {
        val parts = selectedParts()
        if (parts.isEmpty() || readOnly) return null
        val before = runCatching { serialize() }.getOrNull() ?: return null
        val (first, a, _) = parts.first()
        val (last, _, b) = parts.last()
        val joined = io.embodit.spatialoffice.core.text.RichText.normalize(cutSpans(first.spans, 0, a) + cutSpans(last.spans, b, Int.MAX_VALUE))
        rawSetParagraph(first.id, joined, first.style)
        for ((p, _, _) in parts.drop(1)) { docx?.deleteBlock(p.id); plain?.deleteBlock(p.id) }
        crossSel = null
        pushUndo(before); changed()
        android.util.Log.i(TAG, "DOCCLIP delete paras=${parts.size}")
        return first.id to a
    }

    /**
     * Ctrl+V of [clip] into paragraph [id] over characters [a, b): one paragraph goes into the text there; more than one
     * splits the paragraph: the first part joins what is before the caret, the ones in between become paragraphs of
     * their own (style and list level kept: a list item stays a list item), the last one is followed by what was after
     * the caret. One undo step. Returns where the caret goes (paragraph, offset).
     */
    fun pasteClip(clip: DocClip, id: Int, a: Int, b: Int): Pair<Int, Int>? {
        if (readOnly) return null
        val p = blocks().firstOrNull { it.id == id } as? UiBlock.Para ?: return null
        if (!p.editable) return null
        val before = runCatching { serialize() }.getOrNull() ?: return null
        val len = io.embodit.spatialoffice.core.text.RichText.text(p.spans).length
        val lo = a.coerceIn(0, len); val hi = b.coerceIn(lo, len)
        val left = cutSpans(p.spans, 0, lo)
        val right = cutSpans(p.spans, hi, len)
        val ps = clip.paras
        val caret: Pair<Int, Int>
        if (ps.size == 1) {
            rawSetParagraph(id, io.embodit.spatialoffice.core.text.RichText.normalize(left + ps[0].spans + right), p.style)
            caret = id to (lo + ps[0].spans.sumOf { it.text.length })
        } else {
            // a caret at the start of an empty paragraph takes the first pasted paragraph's style too
            val firstStyle = if (left.isEmpty() && richText) ps[0].style else p.style
            rawSetParagraph(id, io.embodit.spatialoffice.core.text.RichText.normalize(left + ps[0].spans), firstStyle)
            if (firstStyle != p.style) rawLevel(id, ps[0].level)
            var at = id
            for (cp in ps.subList(1, ps.size - 1)) { at = rawInsertAfter(at, cp.spans, cp.style); rawLevel(at, cp.level) }
            val lastP = ps.last()
            at = rawInsertAfter(at, io.embodit.spatialoffice.core.text.RichText.normalize(lastP.spans + right), lastP.style)
            rawLevel(at, lastP.level)
            caret = at to lastP.spans.sumOf { it.text.length }
        }
        crossSel = null
        pushUndo(before); changed()
        android.util.Log.i(TAG, "DOCCLIP paste into $id at $lo..$hi paras=${ps.size} -> caret=$caret")
        return caret
    }

    private fun rawSetParagraph(id: Int, spans: List<Span>, style: ParaStyle) {
        docx?.setParagraph(id, runs(spans), style)
        plain?.setParagraph(id, spans.joinToString("") { it.text }, style)
    }
    private fun rawInsertAfter(id: Int, spans: List<Span>, style: ParaStyle): Int =
        docx?.insertParagraphAfter(id, runs(spans), if (richText || plain?.markdown == true) style else ParaStyle.BODY)
            ?: plain?.insertParagraphAfter(id, spans.joinToString("") { it.text }, style) ?: -1
    private fun rawLevel(id: Int, level: Int) {
        val d = docx ?: return
        val p = para(id) ?: return
        if ((p.style == ParaStyle.BULLET || p.style == ParaStyle.NUMBERED) && p.level != level) d.setListLevel(id, level.coerceIn(0, 8))
    }

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
    /**
     * View filter (not saved): the header row plus the data rows that pass [filter]. v0.6.0 (s29): a condition (one of
     * several values, greater / less than, contains) instead of one value.
     */
    var filter by mutableStateOf<io.embodit.spatialoffice.core.xlsx.FilterRule?>(null)
        private set
    val filterCol: Int get() = filter?.col ?: -1
    fun applyFilter(rule: io.embodit.spatialoffice.core.xlsx.FilterRule?) {
        filter = rule
        android.util.Log.i(TAG, "FILTER ${rule?.let { "${io.embodit.spatialoffice.core.xlsx.A1.colName(it.col)} ${it.op} '${it.text}' ${it.values.sorted()}" } ?: "off"} rows=${visibleRows(rowCount).count { it in 1 until rowCount }}")
    }

    override fun counted() {
        runCatching {
            sheetCount = book.sheetNames.size
            if (sheet >= sheetCount) sheet = (sheetCount - 1).coerceAtLeast(0)
            rowCount = book.rows(sheet); colCount = book.cols(sheet)
        }
    }

    init { counted() }

    /** Row indices the grid shows: all but the hidden rows (s42), and with a filter the header row plus the rows that pass it. */
    fun visibleRows(total: Int): List<Int> {
        val f = filter
        val hidden = if (isCsv) emptySet() else runCatching { book.hiddenRows(sheet).toHashSet() }.getOrDefault(HashSet())
        return (0 until total).filter { r -> r !in hidden && (f == null || r == 0 || r >= rowCount || f.pass(book.cell(sheet, r, f.col))) }
    }

    /** Distinct values below the header in column [c] (filter choices). */
    fun columnValues(c: Int): List<String> = (1 until rowCount).map { book.cell(sheet, it, c).display }.distinct().take(60)

    /** s42: columns hidden on this sheet (the grid leaves them out). */
    fun hiddenCols(): Set<Int> = if (isCsv) emptySet() else runCatching { book.hiddenCols(sheet).toSet() }.getOrDefault(emptySet())
    val anyHidden: Boolean get() = !isCsv && (book.hiddenRows(sheet).isNotEmpty() || book.hiddenCols(sheet).isNotEmpty())

    /** s42 [Rows & columns ▸ Hide rows / columns]: the selected rows / columns. Returns how many were hidden. */
    fun hide(cols: Boolean): Int {
        if (isCsv) return 0
        val r = selection
        mark("")
        val n = if (cols) book.setColsHidden(sheet, r.c1, r.c2, true) else book.setRowsHidden(sheet, r.r1, r.r2, true)
        if (n > 0) changed()
        android.util.Log.i(TAG, "HIDE ${if (cols) "cols" else "rows"} ${r.name()} n=$n hidden=${book.hiddenRows(sheet)}/${book.hiddenCols(sheet)}")
        return n
    }

    /** s42 [Unhide]: the hidden rows / columns inside the selection, or all of them on the sheet when it holds none. */
    fun unhide(): Int {
        if (isCsv) return 0
        val r = selection
        val inside = book.hiddenRows(sheet).any { it in r.r1..r.r2 } || book.hiddenCols(sheet).any { it in r.c1..r.c2 }
        mark("")
        val n = if (inside) book.setRowsHidden(sheet, r.r1, r.r2, false) + book.setColsHidden(sheet, r.c1, r.c2, false)
            else book.setRowsHidden(sheet, 0, MAX_R, false) + book.setColsHidden(sheet, 0, MAX_C, false)
        if (n > 0) changed()
        android.util.Log.i(TAG, "UNHIDE ${r.name()} inside=$inside n=$n")
        return n
    }

    /** s30 [Data ▸ Freeze here]: the rows above and the columns left of the selected cell stay (B2 → row 1 and column A); at A1 it unfreezes. */
    fun freezeHere(): Pair<Int, Int> {
        if (isCsv) return 0 to 0
        mark(""); book.setFreeze(sheet, row, col); changed()
        android.util.Log.i(TAG, "FREEZE here ${io.embodit.spatialoffice.core.xlsx.A1.name(row, col)} -> rows=${book.frozenRows(sheet)} cols=${book.frozenCols(sheet)}")
        return book.frozenRows(sheet) to book.frozenCols(sheet)
    }
    fun unfreeze() { if (isCsv) return; mark(""); book.setFreeze(sheet, 0, 0); changed() }
    val frozenCols: Int get() = if (isCsv) 0 else book.frozenCols(sheet)

    /**
     * s31 [Data ▸ Sort…]: the block [rect] sorted by [keys] (column, ascending) in order; with [header] its first row
     * stays on top. False (nothing changed) when the sort would break formulas or merged cells.
     */
    fun sortBy(rect: io.embodit.spatialoffice.core.xlsx.CellRect, keys: List<Pair<Int, Boolean>>, header: Boolean): Boolean {
        if (keys.isEmpty()) return false
        val first = rect.r1 + if (header) 1 else 0
        if (rect.r2 <= first) return false
        mark("")
        val ok = book.sortRowsBy(sheet, keys, first, rect.r2)
        android.util.Log.i(TAG, "SORT ${rect.name()} header=$header keys=${keys.map { io.embodit.spatialoffice.core.xlsx.A1.colName(it.first) + if (it.second) "↑" else "↓" }} ok=$ok")
        if (ok) { csvEdits++; changed() }
        return ok
    }

    /** s31: the block the sort dialog works on: the selection when it is a range, else the data around the selected cell. */
    fun sortBlock(): io.embodit.spatialoffice.core.xlsx.CellRect? {
        if (isRange) return selection
        val b = book
        fun filled(r: Int, c: Int) = r >= 0 && c >= 0 && b.cell(sheet, r, c).input.isNotEmpty()
        if (!filled(row, col)) return null
        var r0 = row; var r1 = row; var c0 = col; var c1 = col
        var grew = true
        while (grew) {
            grew = false
            if (r0 > 0 && (c0..c1).any { filled(r0 - 1, it) }) { r0--; grew = true }
            if ((c0..c1).any { filled(r1 + 1, it) }) { r1++; grew = true }
            if (c0 > 0 && (r0..r1).any { filled(it, c0 - 1) }) { c0--; grew = true }
            if ((r0..r1).any { filled(it, c1 + 1) }) { c1++; grew = true }
            if (r1 - r0 > 5000 || c1 - c0 > 60) break
        }
        return io.embodit.spatialoffice.core.xlsx.CellRect(r0, c0, r1, c1)
    }

    /** s31: whether the block's first row reads as headings (the sort dialog's [My data has headers] starts from this). */
    fun blockHasHeader(rect: io.embodit.spatialoffice.core.xlsx.CellRect): Boolean =
        io.embodit.spatialoffice.core.xlsx.SortPlan.looksLikeHeader((rect.c1..rect.c2).map { book.cell(sheet, rect.r1, it) }, (rect.c1..rect.c2).map { book.cell(sheet, rect.r1 + 1, it) })

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

    // ---- M3 (v0.5.0): s24 fill, s25 merge, s26 borders

    /** s24 Ctrl+D / Ctrl+R: first row / column of the selection into the rest (one row / column: from above / the left). */
    fun fill(down: Boolean): Int {
        mark("")
        val r = selection
        val n = book.fill(sheet, r.r1, r.c1, r.r2, r.c2, down)
        android.util.Log.i(TAG, "FILL ${if (down) "down" else "right"} ${r.name()} cells=$n")
        if (n > 0) { csvEdits++; changed() }
        return n
    }

    /** s24 Fill series: 1, 2 → 3, 4…; a date counts on by days; "Q1" → "Q2". */
    fun fillSeries(down: Boolean): Int {
        mark("")
        val r = selection
        val n = book.fillSeries(sheet, r.r1, r.c1, r.r2, r.c2, down)
        android.util.Log.i(TAG, "FILL series-${if (down) "down" else "right"} ${r.name()} cells=$n")
        if (n > 0) { csvEdits++; changed() }
        return n
    }

    /** s25: the merged range under the active cell, or null. */
    fun mergeHere(): IntArray? = if (isCsv) null else book.mergeAt(sheet, row, col)

    /** s25 [Rows & columns ▸ Merge cells]: false for one cell. The selection becomes the merged cell. */
    fun merge(): Boolean {
        if (isCsv) return false
        val r = selection
        if (r.size < 2) return false
        mark("")
        val ok = book.mergeCells(sheet, r.r1, r.c1, r.r2, r.c2)
        android.util.Log.i(TAG, "MERGE ${r.name()} ok=$ok merges=${book.merges(sheet).joinToString(",") { io.embodit.spatialoffice.core.xlsx.A1.rangeName(it[0], it[1], it[2], it[3]) }}")
        if (ok) { book.mergeAt(sheet, r.r1, r.c1)?.let { m -> select(m[0], m[1]); extendTo(m[2], m[3]) }; changed() }
        return ok
    }

    fun unmerge(): Boolean {
        if (isCsv) return false
        val r = selection
        mark("")
        val ok = book.unmergeCells(sheet, r.r1, r.c1, r.r2, r.c2)
        android.util.Log.i(TAG, "MERGE split ${r.name()} ok=$ok")
        if (ok) changed()
        return ok
    }

    /** s26 [Colour ▾ ▸ Borders]: "all" every side of every cell, "outline" the edge of the selection, "bottom" its bottom edge, "none" removes all. */
    fun setBorders(kind: String) {
        if (isCsv) return
        val r = selection
        mark("")
        val L = io.embodit.spatialoffice.core.xlsx.Borders.LEFT; val R = io.embodit.spatialoffice.core.xlsx.Borders.RIGHT
        val T = io.embodit.spatialoffice.core.xlsx.Borders.TOP; val B = io.embodit.spatialoffice.core.xlsx.Borders.BOTTOM
        eachSelected { rr, cc ->
            val cur = book.borderMask(sheet, rr, cc)
            val edge = (if (cc == r.c1) L else 0) or (if (cc == r.c2) R else 0) or (if (rr == r.r1) T else 0) or (if (rr == r.r2) B else 0)
            val m = when (kind) {
                "all" -> L or R or T or B
                "outline" -> cur or edge
                "bottom" -> if (rr == r.r2) cur or B else cur
                else -> 0
            }
            book.setBorders(sheet, rr, cc, m)
        }
        android.util.Log.i(TAG, "BORDER $kind ${r.name()}")
        changed()
    }

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
        mark(""); val on = book.frozenRows(sheet) == 0 && book.frozenCols(sheet) == 0; book.setFreeze(sheet, if (on) 1 else 0, 0); changed(); return on
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

    // ---- M5 review (s35): comments (threads) and old-style notes on cells ----
    override val canReview: Boolean get() = !isCsv
    override fun comments(): List<io.embodit.spatialoffice.core.review.ReviewComment> = if (isCsv) emptyList() else book.comments()
    /** A1 → kind of every commented cell of the sheet on screen (the corner markers of the grid). */
    fun commentCells(): Map<String, io.embodit.spatialoffice.core.review.CommentKind> = if (isCsv) emptyMap() else runCatching { book.commentCells(sheet) }.getOrDefault(emptyMap())
    override fun commentPlace(c: io.embodit.spatialoffice.core.review.ReviewComment): String =
        if (c.sheet < 0 || c.sheet == sheet) c.cell else (book.sheetNames.getOrNull(c.sheet) ?: "") + " · " + c.cell
    override fun commentTargetName(): String = io.embodit.spatialoffice.core.xlsx.A1.name(row, col)
    override fun addComment(author: String, text: String): String? {
        val ref = io.embodit.spatialoffice.core.xlsx.A1.name(row, col)
        val at = runCatching { book.commentAt(sheet, ref) }.getOrNull()
        // an old-style note cannot take replies: its card is picked and says so
        if (at != null && at.kind == io.embodit.spatialoffice.core.review.CommentKind.NOTE) { reviewHint = io.embodit.spatialoffice.R.string.rv_note_here; pickComment(at.id); return null }
        reviewHint = null
        var id: String? = null
        // a cell that has a thread already: what is written becomes a reply in it
        reviewEdit("add $ref") { id = if (at != null) { book.replyComment(at.id, author, text, nowIso()); at.id } else book.addComment(sheet, ref, author, text, nowIso()); true }
        return id
    }
    override fun replyComment(id: String, author: String, text: String): Boolean = reviewEdit("reply") { book.replyComment(id, author, text, nowIso()); true }
    override fun resolveComment(id: String, resolved: Boolean): Boolean = reviewEdit("resolve=$resolved") { book.setCommentResolved(id, resolved); true }
    override fun editComment(id: String, text: String): Boolean = reviewEdit("edit") { book.editComment(id, text); true }
    override fun deleteComment(id: String): Boolean = reviewEdit("delete") { book.deleteComment(id); true }
    override fun showComment(c: io.embodit.spatialoffice.core.review.ReviewComment) {
        val rc = io.embodit.spatialoffice.core.xlsx.A1.parse(c.cell) ?: return
        if (c.sheet in 0 until sheetCount && c.sheet != sheet) { sheet = c.sheet; counted() }
        select(rc.first, rc.second); showTick++
    }
    /** Bumped when a comment card asks for its cell: the grid scrolls it into view. */
    var showTick by mutableIntStateOf(0)
    /** The comment on the selected cell, picked in the pane when the pane is open (a tap on a marked cell). */
    fun commentHere(): io.embodit.spatialoffice.core.review.ReviewComment? = if (isCsv) null else runCatching { book.commentAt(sheet, io.embodit.spatialoffice.core.xlsx.A1.name(row, col)) }.getOrNull()

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
/** p25: a dragged slide object snaps to lines within 1/[SNAP_PART] of the slide width. */
const val SNAP_PART = 120L

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

    /** Objects added with [Object ▸ Add to selection] (or Shift+click) on the current slide; with the picked one, what align / group act on. */
    var multi by mutableStateOf<List<Int>>(emptyList())
        private set

    /** The slide [multi] belongs to (another slide shown empties it). */
    private var multiSlide = 0

    override fun counted() {
        slideCount = deck.slides.size
        current = current.coerceIn(0, (slideCount - 1).coerceAtLeast(0))
        val shapes = deck.slides.getOrNull(current)?.shapes
        if (shapes?.none { it.id == selected } != false) selected = -1
        if (multi.isNotEmpty() && (multiSlide != current || shapes == null || multi.any { id -> shapes.none { it.id == id } })) multi = multi.filter { id -> multiSlide == current && shapes?.any { it.id == id } == true }
        multiSlide = current
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

    /** The pointer has travelled [dx], [dy] EMU since the press: the object follows, snapping to the edges and centres of the others and of the slide (p25). */
    fun moveBoxBy(dx: Long, dy: Long) {
        val (id, _) = dragBox ?: return
        val start = dragStart ?: return
        val free = io.embodit.spatialoffice.core.layout.BoxDrag.move(start, dx, dy, deck.widthEmu, deck.heightEmu)
        val others = deck.slides.getOrNull(current)?.shapes?.filter { it.id != id && it.w > 0 && it.h > 0 }?.map { io.embodit.spatialoffice.core.layout.UnitBox(it.x, it.y, it.w, it.h) } ?: emptyList()
        val snapped = if (!snap) io.embodit.spatialoffice.core.layout.Arrange.Snapped(free, emptyList()) else io.embodit.spatialoffice.core.layout.Arrange.snap(free, others, deck.widthEmu, deck.heightEmu, deck.widthEmu / SNAP_PART)
        dragBox = id to snapped.box
        if (snapped.guides != guides) guides = snapped.guides
    }

    /** p25: dragging snaps to guide lines (on; the travel-exact drag tests turn it off). */
    var snap = true

    /** p25: the guide lines a dragged object has snapped to (drawn as thin lines while the pointer is down). */
    var guides by mutableStateOf<List<io.embodit.spatialoffice.core.layout.Arrange.Guide>>(emptyList())
        private set

    // ---- v0.6.0 (M4 p29 / p25): several objects picked, groups, align and distribute

    /** The objects the multi-object tools act on: [multi] plus the picked one, in drawing order. */
    fun multiSet(): List<Int> {
        val shapes = deck.slides.getOrNull(current)?.shapes ?: return emptyList()
        val want = (multi + listOfNotNull(selected.takeIf { it >= 0 })).toSet()
        return shapes.filter { it.id in want }.map { it.id }
    }

    /** [Object ▸ Add to selection]: the picked object joins (Shift+click on one toggles it). */
    fun addToMulti(id: Int = selected): Boolean {
        if (id < 0 || deck.slides.getOrNull(current)?.shapes?.none { it.id == id } != false) return false
        multi = if (id in multi) multi else multi + id
        android.util.Log.i(TAG, "MULTI add $id -> ${multiSet()}")
        return true
    }
    fun toggleMulti(id: Int) {
        multi = if (id in multi) multi - id else multi + id
        if (selected < 0 || selected == id && id !in multi) selected = multi.lastOrNull() ?: -1
        android.util.Log.i(TAG, "MULTI toggle $id -> ${multiSet()}")
    }
    fun clearMulti() { if (multi.isNotEmpty()) { multi = emptyList(); android.util.Log.i(TAG, "MULTI clear") } }

    private fun boxesOf(ids: List<Int>): List<Pair<Int, io.embodit.spatialoffice.core.layout.UnitBox>> {
        val shapes = deck.slides.getOrNull(current)?.shapes ?: return emptyList()
        return ids.mapNotNull { id -> shapes.firstOrNull { it.id == id }?.let { id to io.embodit.spatialoffice.core.layout.UnitBox(it.x, it.y, it.w, it.h) } }
    }

    private fun applyBoxes(what: String, moved: List<Pair<Int, io.embodit.spatialoffice.core.layout.UnitBox>>, old: List<Pair<Int, io.embodit.spatialoffice.core.layout.UnitBox>>): Boolean {
        val changes = moved.filterIndexed { i, (_, b) -> b != old[i].second }
        if (changes.isEmpty()) { android.util.Log.i(TAG, "ARRANGE $what: nothing moves"); return false }
        val before = runCatching { serialize() }.getOrNull() ?: return false
        var ok = true
        for ((id, b) in changes) ok = ok && runCatching { deck.setShapeBox(current, id, b.x, b.y, b.w, b.h) }.isSuccess
        pushUndo(before); edits++; changed()
        android.util.Log.i(TAG, "ARRANGE $what ${moved.joinToString { "${it.first}@${it.second.x},${it.second.y}" }} ok=$ok")
        return ok
    }

    /** p25 [Object ▸ Align ›] on [multiSet] (at least two). */
    fun align(how: io.embodit.spatialoffice.core.layout.Arrange.Align): Boolean {
        val old = boxesOf(multiSet())
        if (old.size < 2) return false
        val out = io.embodit.spatialoffice.core.layout.Arrange.align(old.map { it.second }, how)
        return applyBoxes("align $how", old.mapIndexed { i, (id, _) -> id to out[i] }, old)
    }

    /** p25 [Object ▸ Distribute ›] on [multiSet] (at least three). */
    fun distribute(horizontal: Boolean): Boolean {
        val old = boxesOf(multiSet())
        if (old.size < 3) return false
        val out = io.embodit.spatialoffice.core.layout.Arrange.distribute(old.map { it.second }, horizontal)
        return applyBoxes("distribute ${if (horizontal) "across" else "down"}", old.mapIndexed { i, (id, _) -> id to out[i] }, old)
    }

    /** p29 [Object ▸ Group]: [multiSet] (at least two) becomes one group, picked at once. */
    fun group(): Int {
        val ids = multiSet()
        if (ids.size < 2) return -1
        val before = runCatching { serialize() }.getOrNull() ?: return -1
        val gid = runCatching { deck.groupShapes(current, ids) }.onFailure { android.util.Log.w(TAG, "GROUP refused: ${it.message}") }.getOrNull() ?: return -1
        pushUndo(before); edits++; changed()
        multi = emptyList(); selected = gid
        android.util.Log.i(TAG, "GROUP slide=$current ids=$ids -> $gid")
        return gid
    }

    /** p29 [Object ▸ Ungroup] on the picked group: its objects are picked together afterwards. */
    fun ungroup(): Boolean {
        val g = pickedShape()?.takeIf { it.children != null } ?: return false
        val before = runCatching { serialize() }.getOrNull() ?: return false
        val ids = runCatching { deck.ungroupShape(current, g.id) }.onFailure { android.util.Log.w(TAG, "UNGROUP refused: ${it.message}") }.getOrNull() ?: return false
        pushUndo(before); edits++; changed()
        multi = ids.dropLast(1); selected = ids.lastOrNull() ?: -1
        android.util.Log.i(TAG, "UNGROUP slide=$current $g.id -> $ids")
        return true
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
        if (guides.isNotEmpty()) android.util.Log.i(TAG, "SNAP shape $id guides=$guides")
        guides = emptyList()
        if (start == null || box == start) return false
        mark("")
        val ok = runCatching { deck.setShapeBox(current, id, box.x, box.y, box.w, box.h) }
            .onFailure { android.util.Log.w(TAG, "shape $id cannot be moved: ${it.message}") }.isSuccess
        if (ok) { edits++; changed() }
        return ok
    }

    fun cancelBoxDrag() { dragBox = null; dragStart = null; guides = emptyList() }

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

    // ---- M5 review (p33): comments on slides; (p32) slide number and footer ----
    override val canReview: Boolean get() = true
    override fun comments(): List<io.embodit.spatialoffice.core.review.ReviewComment> = deck.comments()
    override val commentOnSelection: Boolean get() = pickedShape() != null
    /** Where a new comment goes: the picked object's top-right corner, else the slide's top-left. */
    private fun commentSpot(): Pair<Float, Float> {
        val sh = pickedShape() ?: return 0.02f to 0.03f
        val w = deck.widthEmu.coerceAtLeast(1L).toFloat(); val h = deck.heightEmu.coerceAtLeast(1L).toFloat()
        return ((sh.x + sh.w) / w).coerceIn(0f, 0.97f) to (sh.y / h).coerceIn(0f, 0.95f)
    }
    override fun addComment(author: String, text: String): String? {
        val (x, y) = commentSpot()
        var id: String? = null
        reviewEdit("add slide=$current") { id = deck.addComment(current, x, y, author, text, nowIso()); true }
        return id
    }
    override fun replyComment(id: String, author: String, text: String): Boolean = reviewEdit("reply") { deck.replyComment(id, author, text, nowIso()); true }
    override fun resolveComment(id: String, resolved: Boolean): Boolean = reviewEdit("resolve=$resolved") { deck.setCommentResolved(id, resolved); true }
    override fun editComment(id: String, text: String): Boolean = reviewEdit("edit") { deck.editComment(id, text); true }
    override fun deleteComment(id: String): Boolean = reviewEdit("delete") { deck.deleteComment(id); true }
    override fun showComment(c: io.embodit.spatialoffice.core.review.ReviewComment) {
        if (c.slide in 0 until slideCount && c.slide != current) { current = c.slide; selected = -1 }
    }
    fun footerInfo(slide: Int = current): io.embodit.spatialoffice.core.pptx.SlideFooter = runCatching { deck.footerInfo(slide) }.getOrDefault(io.embodit.spatialoffice.core.pptx.SlideFooter(false, false, ""))
    /** [Slide ▾] › Slide numbers & footer: on this slide ([all] = false) or on every slide; returns how many slides changed. */
    fun setFooter(all: Boolean, number: Boolean, footer: Boolean, text: String, hideOnTitle: Boolean): Int {
        var n = 0
        reviewEdit("footer all=$all number=$number footer=$footer hideOnTitle=$hideOnTitle") { n = deck.setFooter(if (all) null else listOf(current), number, footer, text, hideOnTitle); n > 0 }
        if (n > 0) edits++
        android.util.Log.i(TAG, "FOOTER slides=$n number=$number footer=$footer text='${text.take(24)}' hideOnTitle=$hideOnTitle")
        return n
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
