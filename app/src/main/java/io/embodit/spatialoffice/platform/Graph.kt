package io.embodit.spatialoffice.platform

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.embodit.spatialoffice.R
import io.embodit.spatialoffice.core.docx.DocxDocument
import io.embodit.spatialoffice.core.files.FileKind
import io.embodit.spatialoffice.core.files.Naming
import io.embodit.spatialoffice.core.layout.Arrangement
import io.embodit.spatialoffice.core.layout.LayoutRecord
import io.embodit.spatialoffice.core.layout.LayoutRules
import io.embodit.spatialoffice.core.layout.OpenFiles
import io.embodit.spatialoffice.core.pptx.PptxDeck
import io.embodit.spatialoffice.core.xlsx.XlsxWorkbook
import io.embodit.spatialoffice.ui.localized
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.system.exitProcess

const val TAG = "SpatialOffice"

enum class HomePage { RECENT, BROWSE, NEW, ACCOUNT, SETTINGS }
enum class Win { HOME, DOC, SHEET, SLIDES, CHART, STAGE }
enum class StageMode { SPREAD, REHEARSAL }
enum class SaveAsTarget { COPY, PDF, CSV, TXT, PNG, CLOUD }

/** A dialog or sheet shown in one window. */
sealed class Overlay {
    abstract val win: Win
    /** Tag of the editor window that shows it (v0.2.5: several windows of one kind); stamped when it is shown. */
    var tag: String? = null
    data class SaveFirst(override val win: Win) : Overlay()
    data class SaveAs(override val win: Win) : Overlay()
    /** [then]: "close" | "exit". */
    data class Unsaved(override val win: Win, val then: String) : Overlay()
    data class Shortcuts(override val win: Win) : Overlay()
    data class Compat(override val win: Win) : Overlay()
    data class Exit(override val win: Win = Win.HOME) : Overlay()
    data class HowTo(override val win: Win = Win.HOME) : Overlay()
    /** Find and replace (v0.3.0: documents, spreadsheets and presentations alike). */
    data class Find(override val win: Win = Win.DOC) : Overlay()
    /** d27: the file has an autosaved draft with changes that were never saved: take them back? */
    data class Recover(override val win: Win) : Overlay()
    /** d28: the file's saved versions, newest first, each with [Restore]. */
    data class History(override val win: Win) : Overlay()
    /** s21: new name for the current worksheet. */
    data class SheetRename(override val win: Win = Win.SHEET) : Overlay()
    /** s21: delete the current worksheet (formulas that point at it turn into #REF!). */
    data class SheetDelete(override val win: Win = Win.SHEET) : Overlay()
    data class WordCount(override val win: Win = Win.DOC) : Overlay()
    /** v0.6.0 (s29): [Data ▸ Filter…] on column [col]. */
    data class FilterData(override val win: Win = Win.SHEET, val col: Int = 0) : Overlay()
    /** v0.6.0 (s31): [Data ▸ Sort…]. */
    data class SortData(override val win: Win = Win.SHEET) : Overlay()
    /** v0.6.0 (d25): [More ▸ Outline]. */
    data class Outline(override val win: Win = Win.DOC) : Overlay()
    data class InsertTable(override val win: Win = Win.DOC) : Overlay()
    /**
     * M3 d20: insert / edit a link. [para] and [a]..[b] are the characters it goes on (an empty range: [text] is typed
     * in at [a]); [existing] when the caret was in a link already ([Remove link] is offered).
     */
    data class Link(override val win: Win = Win.DOC, val para: Int, val a: Int, val b: Int, val url: String, val text: String, val existing: Boolean) : Overlay()
    /** M5 d31: [Insert ▸ Header & footer…]: the two texts shown at the top and bottom of every page. */
    data class HeaderFooter(override val win: Win = Win.DOC) : Overlay()
    /** M5 p32: [Slide ▸ Slide numbers & footer…]. */
    data class SlideFooter(override val win: Win = Win.SLIDES) : Overlay()
    /** Flat preview of the sheet's charts (bar / line / pie). */
    data class ChartView(override val win: Win = Win.SHEET, val index: Int = -1) : Overlay()
    /** Pick a picture for the document or the current slide. */
    data class ImagePick(override val win: Win) : Overlay()
    /** Confirm signing out of a cloud account. */
    data class SignOut(val provider: io.embodit.spatialoffice.core.cloud.Provider, override val win: Win = Win.HOME) : Overlay()
    /** [Connect] → choose a way: scan a code (recommended) or sign in in the headset browser (PRD §9.2 ②). */
    data class Connect(val provider: io.embodit.spatialoffice.core.cloud.Provider, override val win: Win = Win.HOME) : Overlay()
    /** QR code + short code, waiting for the other device (PRD §9.2 ③a). */
    data class Scan(val provider: io.embodit.spatialoffice.core.cloud.Provider, override val win: Win = Win.HOME) : Overlay()
    /** Connected: who, where the sign-in is kept, default folder and inbox (PRD §9.2 ⑤). */
    data class Connected(val provider: io.embodit.spatialoffice.core.cloud.Provider, override val win: Win = Win.HOME) : Overlay()
    /** The cloud copy changed since it was opened: overwrite / save a copy / cancel (PRD §9.3). */
    data class Conflict(override val win: Win) : Overlay()
    /** Feishu would not take a new version of [original]: it is untouched, the edits were saved as [copy] beside it (v0.2.3). */
    data class CopyBeside(override val win: Win, val original: String, val copy: String) : Overlay()
}

data class Toast(val id: Long, val win: Win, val text: String, val action: String? = null, val onAction: (() -> Unit)? = null, val tag: String? = null)

/**
 * Process-wide state shared by every window, the chart volume and the Stage. All mutations happen on the main
 * thread; file reads and writes run on IO and come back. The debug broadcast calls the same functions as the UI.
 */
class Graph(val app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val settings = SettingsStore(app)
    val storage = Storage(app)
    val recents = RecentStore(app)
    val layouts = LayoutStore(app, storage)
    /** M5: per file, review pane open / "Track my edits" on (app data, never in the file). */
    val review = ReviewStore(app)
    val windows = WindowCtl(this)
    val accounts = Accounts(app, this).also { storage.accounts = it }
    val drafts = Drafts(app)
    val history = History(app)

    var homePage by mutableStateOf(HomePage.RECENT)
    var browseDir by mutableStateOf<File?>(null)
    var browseLoc by mutableStateOf(0)
    /**
     * Every open file, each in a window of its own (v0.2.5, headset 2026-11-17: "word 文档也不能多开"; until v0.2.4
     * one window per kind, and opening a second document replaced the first). [files] decides (pure, unit-tested);
     * [byTag] holds the sessions by window tag; [openVersion] is bumped when the set or the active one changes.
     */
    private val files = OpenFiles()
    private val byTag = androidx.compose.runtime.mutableStateMapOf<String, Session>()
    private var openVersion by mutableStateOf(0)

    /** Open files, oldest use first (the last one of a kind is the active one). */
    val sessions: List<Session> get() { openVersion; return files.entries.mapNotNull { byTag[it.tag] } }
    fun sessionByTag(tag: String?): Session? = tag?.let { byTag[it] }
    private fun activeOf(kind: FileKind): Session? { openVersion; return files.ofKind(kind.name).asReversed().firstNotNullOfOrNull { byTag[it.tag] } }

    /** The document / spreadsheet / presentation whose window was used last (what window-less commands act on). */
    val doc: DocSession? get() = activeOf(FileKind.DOC) as? DocSession
    val sheet: SheetSession? get() = activeOf(FileKind.SHEET) as? SheetSession
    val deck: DeckSession? get() = activeOf(FileKind.SLIDES) as? DeckSession

    /** [s]'s window was used (pointer, key, toolbar): it is the active one of its kind from now on. */
    /** M5: the file whose window was used last (any kind): what BACK / controller B acts on (painter, review pane). */
    var lastUsed: Session? = null
        private set

    fun activate(s: Session) {
        if (byTag[s.tag] === s) lastUsed = s
        if (byTag[s.tag] !== s || files.active(s.editor.name)?.tag == s.tag) return
        if (files.activate(s.tag)) { openVersion++; Log.i(TAG, "ACTIVE ${s.editor} ${s.tag} ${s.ref.name}") }
    }

    private var overlayState by mutableStateOf<Overlay?>(null)
    /** The dialog or sheet on screen. An editor overlay belongs to one window: the active one of its kind unless [show] named it. */
    var overlay: Overlay?
        get() = overlayState
        set(v) {
            if (v != null && v.tag == null) v.tag = when (v.win) { Win.DOC -> doc?.tag; Win.SHEET -> sheet?.tag; Win.SLIDES -> deck?.tag; else -> null }
            overlayState = v
            if (v != null) BackKeys.sweepSoon("overlay ${v.javaClass.simpleName}")
        }

    /** Shows [o] in [s]'s own window. */
    fun show(s: Session, o: Overlay) { activate(s); o.tag = s.tag; overlayState = o; BackKeys.sweepSoon("overlay ${o.javaClass.simpleName}") }

    /** M3 d20: opens a document link in the system browser (the same ACTION_VIEW + BROWSABLE path as [Connect]). */
    fun openLink(url: String): Boolean {
        val u = DocSession.normalizeUrl(url)
        if (u.isEmpty()) return false
        val ok = accounts.launchBrowser(u)
        android.util.Log.i(TAG, "DOCLINK open $u started=$ok")
        // v0.5.1 (q13-2): never silent: a link nothing can open says so in the document window
        if (!ok) say(Win.DOC, str(R.string.link_cant_open, u))
        return ok
    }
    var toast by mutableStateOf<Toast?>(null)
        private set
    /** Bumped when the set of files on disk may have changed (new, save, rename), so lists re-read. */
    var filesVersion by mutableStateOf(0)
        private set

    // spread / rehearsal
    var stageMode by mutableStateOf<StageMode?>(null)
        private set
    var stageKind by mutableStateOf(FileKind.DOC)
        private set
    var arrangement by mutableStateOf(Arrangement.ARC)
        private set
    var focus by mutableStateOf(0)
        private set
    var recenterTick by mutableStateOf(0)
        private set
    var rehearsalStart by mutableStateOf(0L)
        private set

    /** The file that is spread out / rehearsed in the Stage. */
    var stageTag by mutableStateOf<String?>(null)
        private set
    val stageSession: Session? get() = sessionByTag(stageTag) ?: session(stageKind)

    // chart volume
    /** The spreadsheet whose chart the volume shows (its window is what the volume stands on). */
    var chartTag by mutableStateOf<String?>(null)
    val chartSheet: SheetSession? get() = (sessionByTag(chartTag) as? SheetSession) ?: sheet
    var chartIndex by mutableStateOf(0)
    var chartScatter by mutableStateOf(false)
    var chartYaw by mutableStateOf(io.embodit.spatialoffice.ui.chart.CHART_DEFAULT_YAW)

    /** What to do with the picture chosen in the picture picker (document or current slide). */
    var onImage: ((ByteArray, String, Int, Int) -> Unit)? = null
        private set

    fun pickImage(win: Win, then: (ByteArray, String, Int, Int) -> Unit) { onImage = then; overlay = Overlay.ImagePick(win) }
    fun pickImage(s: Session, then: (ByteArray, String, Int, Int) -> Unit) { onImage = then; show(s, Overlay.ImagePick(winOf(s.kind))) }

    /** Reads, measures and hands a picture file to the waiting action. */
    fun imageChosen(win: Win, ref: FileRef) {
        val then = onImage ?: return
        overlay = null; onImage = null
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    val bytes = storage.read(ref)
                    val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
                    if (o.outWidth <= 0) error("not a picture")
                    val ext = when (o.outMimeType) { "image/png" -> "png"; "image/gif" -> "gif"; else -> "jpeg" }
                    arrayOf(bytes, ext, o.outWidth, o.outHeight)
                }
            }
            r.onSuccess { (b, e, w, h) -> then(b as ByteArray, e as String, w as Int, h as Int); Log.i(TAG, "IMAGE inserted ${ref.name}") }
                .onFailure { say(win, str(R.string.image_failed, ref.name)) }
        }
    }

    /** Pictures the picker lists: the shared Pictures, Download, DCIM and Documents folders (newest first). */
    fun imageFiles(): List<java.io.File> {
        val dirs = listOf("Pictures", "Download", "DCIM", "DCIM/Camera", "Documents", "Documents/SpatialOffice").map { java.io.File(storage.shared, it) }
        return dirs.flatMap { d -> d.listFiles()?.filter { f -> f.isFile && f.name.substringAfterLast('.').lowercase() in setOf("png", "jpg", "jpeg", "gif", "webp") }.orEmpty() }
            .distinctBy { it.absolutePath }.sortedByDescending { it.lastModified() }.take(24)
    }

    private var toastSeq = 0L
    private val res get() = app.localized(settings.value.lang).resources

    fun str(id: Int, vararg args: Any): String = if (args.isEmpty()) res.getString(id) else res.getString(id, *args)

    /**
     * The file a command addressed to [win] acts on: the one whose window shows the open dialog, else the active one
     * of that kind. Window content never asks this way: each window is given its own session.
     */
    fun session(win: Win): Session? {
        val o = overlayState
        if (o != null && o.win == win) sessionByTag(o.tag)?.let { return it }
        return when (win) { Win.DOC -> doc; Win.SHEET -> sheet; Win.CHART -> chartSheet; Win.SLIDES -> deck; else -> null }
    }
    fun session(kind: FileKind): Session? = when (kind.editor) { FileKind.DOC -> doc; FileKind.SHEET -> sheet; FileKind.SLIDES -> deck; else -> null }
    fun winOf(kind: FileKind): Win = when (kind.editor) { FileKind.SHEET -> Win.SHEET; FileKind.SLIDES -> Win.SLIDES; else -> Win.DOC }
    fun refOf(s: Session): WinRef = WinRef(winOf(s.kind), s.tag)

    fun say(win: Win, text: String, action: String? = null, onAction: (() -> Unit)? = null) {
        Log.i(TAG, "TOAST ${win.name}: $text")
        val tag = if (win == Win.DOC || win == Win.SHEET || win == Win.SLIDES) session(win)?.tag else null
        toast = Toast(++toastSeq, if (stageMode != null && win != Win.HOME) Win.STAGE else win, text, action, onAction, tag)
    }

    /** A message in [s]'s own window. */
    fun say(s: Session, text: String, action: String? = null, onAction: (() -> Unit)? = null) {
        Log.i(TAG, "TOAST ${winOf(s.kind).name}/${s.tag}: $text")
        toast = Toast(++toastSeq, if (stageMode != null) Win.STAGE else winOf(s.kind), text, action, onAction, s.tag)
    }

    fun toastShown(id: Long) { if (toast?.id == id) toast = null }

    // ---------------------------------------------------------------- open / new

    fun openIntent(intent: Intent?) {
        intent?.data?.let { if (accounts.handleRedirect(it)) return }
        val ref = storage.fromIntent(intent) ?: return
        Log.i(TAG, "INTENT ${intent?.action} -> ${ref.name}")
        open(ref)
    }

    /**
     * Opens [file] in a window of its own. A file that is open already is not opened twice: its window comes to the
     * front. Nothing that was open is closed or replaced.
     */
    fun open(file: FileRef) {
        // /sdcard/… and /storage/emulated/0/… are the same file: use one spelling, so paths display and compare the same
        val ref = if (file is FileRef.Path) FileRef.Path(runCatching { file.file.canonicalFile }.getOrDefault(file.file)) else file
        val kind = FileKind.ofName(ref.name)
        if (kind == FileKind.UNKNOWN) { say(Win.HOME, str(R.string.open_unknown)); return }
        when (val r = files.open(kind.editor.name, ref.key)) {
            is OpenFiles.Open.Front -> { val s = byTag[r.entry.tag]; if (s != null) front(s) else Log.i(TAG, "OPEN ${ref.name}: still loading") }
            is OpenFiles.Open.Full -> { Log.i(TAG, "OPEN ${ref.name}: ${r.open} ${r.kind} files open already"); say(Win.HOME, str(R.string.too_many_open, r.open)) }
            is OpenFiles.Open.New -> scope.launch {
                val result = withContext(Dispatchers.IO) { runCatching { build(ref, kind, storage.read(ref)) } }
                result.onFailure { e ->
                    files.close(r.entry.tag)
                    Log.e(TAG, "OPEN failed ${ref.name}", e)
                    say(Win.HOME, str(R.string.open_failed, ref.name, e.message ?: e.javaClass.simpleName))
                    if (ref is FileRef.Path && !ref.file.exists()) recents.remove(ref.key)
                }.onSuccess { s -> adopt(s, r.entry, isNew = false) }
            }
        }
    }

    /** [s] is open already: its window comes to the front (reopened when the system had closed it) and says so. */
    fun front(s: Session, quiet: Boolean = false) {
        activate(s)
        chartFollows(s)
        Log.i(TAG, "OPEN ${s.ref.name}: already open in ${refOf(s)}, to the front")
        windows.front(refOf(s))
        if (!quiet) say(s, str(R.string.already_open, s.ref.name))
    }

    private fun build(ref: FileRef, kind: FileKind, bytes: ByteArray): Session = when (kind.editor) {
        FileKind.SHEET -> SheetSession(ref, kind, bytes)
        FileKind.SLIDES -> DeckSession(ref, bytes)
        else -> DocSession(ref, kind, bytes, app.cacheDir)
    }

    /** The 3D chart belongs to the spreadsheet it was asked from: when another spreadsheet comes to the front it is put away. */
    private fun chartFollows(s: Session) {
        if (s is SheetSession && chartTag != null && chartTag != s.tag) {
            Log.i(TAG, "CHART of $chartTag closed: ${s.ref.name} is in front now")
            windows.close(WinRef(Win.CHART)); chartTag = null
        }
    }

    private fun adopt(s: Session, entry: OpenFiles.Entry, isNew: Boolean) {
        chartFollows(s)
        s.isNew = isNew
        s.tag = entry.tag
        // M5: the pane and the "Track my edits" switch come back the way this file had them
        if (s.canReview && !isNew) { s.reviewOpen = review.open(s.ref.key); s.tracking = s.hasRevisions && review.tracking(s.ref.key) }
        s.trackAuthor = settings.value.myName
        byTag[s.tag] = s
        files.activate(s.tag)
        openVersion++
        recents.touch(s.ref, storage.display(s.ref))
        filesVersion++
        windows.open(refOf(s))
        Log.i(TAG, "OPEN ${s.ref.name} kind=${s.kind} new=$isNew pieces=${s.pieceCount} window=${refOf(s)} open=${files.ofKind(s.editor.name).size}/${files.entries.size}")
        // the windows that were up are behind the new one now: say so, and where to switch (title bar › Windows)
        val others = sessions.size - 1
        if (isNew) say(s, str(R.string.new_made, storage.display(s.ref)) + if (others > 0) " · " + str(R.string.others_behind, others) else "")
        else {
            val c = s.compat()
            if (c.isEmpty()) say(s, str(R.string.opened, s.ref.name) + if (others > 0) " · " + str(R.string.others_behind, others) else "")
            else say(s, str(R.string.opened, s.ref.name) + " · " + str(R.string.opened_sub, c.approx(), c.held()), str(R.string.view)) { show(s, Overlay.Compat(winOf(s.kind))) }
        }
        if (!isNew) offerDraft(s)
        val rec = layouts.load(s.ref)
        if (!isNew && (rec?.spread == true || settings.value.openMode == OpenMode.SPREAD)) spread(s)
    }

    /**
     * A new file in a window of its own; the files that are open stay open. v0.6.0 (d26 / p27): [template] = a
     * [io.embodit.spatialoffice.core.templates.DocTemplate] or [io.embodit.spatialoffice.core.templates.DeckTemplate]
     * to start from (in the app language), named after it.
     */
    fun newFile(kind: FileKind, template: Any? = null) {
        val open = files.ofKind(kind.editor.name).size
        if (open >= OpenFiles.MAX_PER_KIND) { say(Win.HOME, str(R.string.too_many_open, open)); return }
        scope.launch {
            val zh = settings.value.zh
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val bytes = when {
                        template is io.embodit.spatialoffice.core.templates.DocTemplate -> io.embodit.spatialoffice.core.templates.Templates.doc(template, zh)
                        template is io.embodit.spatialoffice.core.templates.DeckTemplate -> io.embodit.spatialoffice.core.templates.Templates.deck(template, zh)
                        kind == FileKind.SHEET -> XlsxWorkbook.blank().save()
                        kind == FileKind.SLIDES -> PptxDeck.blank().save()
                        else -> DocxDocument.blank().save()
                    }
                    val name = when (template) {
                        is io.embodit.spatialoffice.core.templates.DocTemplate -> template.title(zh) + ".docx"
                        is io.embodit.spatialoffice.core.templates.DeckTemplate -> template.title(zh) + ".pptx"
                        else -> Naming.untitled(kind, zh)
                    }
                    if (template != null) Log.i(TAG, "NEW from template $template (${bytes.size} bytes) as $name")
                    val file = storage.freeFile(storage.newDir, name.substringBeforeLast('.'), kind.ext)
                    file.writeBytes(bytes)
                    build(FileRef.Path(file), kind, bytes)
                }
            }
            result.onFailure { e -> Log.e(TAG, "NEW failed", e); say(Win.HOME, str(R.string.save_failed, e.message ?: "")) }
                .onSuccess { s ->
                    when (val r = files.open(kind.editor.name, s.ref.key)) {
                        is OpenFiles.Open.New -> adopt(s, r.entry, isNew = true)
                        else -> { (s.ref as? FileRef.Path)?.file?.delete(); say(Win.HOME, str(R.string.too_many_open, files.ofKind(kind.editor.name).size)) }
                    }
                }
        }
    }

    /** The file of [s] has another name / place now (first save, cloud copy): same window. */
    private fun moved(s: Session, ref: FileRef) { s.ref = ref; files.rekey(s.tag, ref.key) }

    /** A new file that was never touched is not kept (PRD §4.7). */
    private fun discardEmptyNew(s: Session?) {
        if (s == null || !s.isNew || s.dirty) return
        (s.ref as? FileRef.Path)?.file?.let { f ->
            if (f.delete()) { recents.remove(s.ref.key); filesVersion++; Log.i(TAG, "NEW empty file removed ${f.name}") }
        }
    }

    // ---------------------------------------------------------------- save

    /** [Save] / Ctrl+S. */
    fun save(win: Win) { session(win)?.let { save(it) } }

    fun save(s: Session) {
        activate(s)
        val win = winOf(s.kind)
        if (s.readOnly) { if (s.ref is FileRef.Cloud) say(s, str(R.string.cloud_readonly_save)); return }
        if (s.isNew) { saveNew(s, win); return }
        if (!s.dirty) { say(s, str(R.string.saved_to, storage.display(s.ref))); return }
        // a cloud drive keeps its own version history, so there is no local backup of cloud files
        if (settings.value.keepBackup && layouts.backupOf(s.ref.key) == null && s.ref !is FileRef.Cloud) { show(s, Overlay.SaveFirst(win)); return }
        write(s, win, backup = false)
    }

    /** From the first-save sheet. */
    fun confirmFirstSave(win: Win) {
        val s = session(win)
        overlay = null
        s?.let { write(it, win, backup = true) }
    }

    /** Answer to the cloud-conflict dialog: "overwrite" | "copy" | "cancel". The debug command calls this too. */
    fun resolveConflict(win: Win, choice: String) {
        val s = session(win) ?: return
        overlay = null
        Log.i(TAG, "CONFLICT $win $choice")
        when (choice) {
            "overwrite" -> write(s, win, backup = false, force = true)
            "copy" -> {
                val ref = s.ref as? FileRef.Cloud ?: return
                val copy = io.embodit.spatialoffice.core.files.Naming.copyName(ref.name, settings.value.zh)
                scope.launch {
                    val r = withContext(Dispatchers.IO) { runCatching { accounts.saveCopyBeside(ref, copy, s.serialize()) } }
                    r.onFailure { e -> Log.e(TAG, "CONFLICT copy failed", e); say(s, str(R.string.cloud_save_failed, e.message ?: "")) }
                        .onSuccess { where -> say(s, str(R.string.conflict_copied, where)) }
                }
            }
            else -> say(s, str(R.string.conflict_cancelled))
        }
    }

    private fun write(s: Session, win: Win, backup: Boolean, after: (() -> Unit)? = null, force: Boolean = false) {
        val zh = settings.value.zh
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    var backupFile: File? = null
                    if (backup) {
                        backupFile = storage.backup(s.ref, storage.read(s.ref), zh)
                        backupFile?.let { layouts.markBackedUp(s.ref.key, it.absolutePath) }
                    }
                    val ref = s.ref
                    if (ref is FileRef.Cloud) {
                        val now = accounts.write(ref, s.serialize(), force)
                        if (now != ref) withContext(Dispatchers.Main) { moved(s, now) }
                        // Feishu refused the in-place version: the original is untouched, the edits went to a copy beside it
                        accounts.lastCopyBeside?.let { (orig, copy) -> withContext(Dispatchers.Main) { show(s, Overlay.CopyBeside(win, orig, copy)) } }
                    } else storage.write(ref, s.serialize())
                    backupFile
                }
            }
            r.onFailure { e ->
                Log.e(TAG, "SAVE failed", e)
                if (e is io.embodit.spatialoffice.core.cloud.CloudConflictException) { show(s, Overlay.Conflict(win)); return@onFailure }
                if (s.ref is FileRef.Cloud) say(s, str(if (e is CloudAuthException) R.string.cloud_kept_local else R.string.cloud_save_failed, e.message ?: ""))
                else say(s, str(R.string.save_failed, e.message ?: str(R.string.perm_denied)))
            }
                .onSuccess { b ->
                    s.saved(); filesVersion++; dropDraft(s); keepVersion(s)
                    recents.touch(s.ref, storage.display(s.ref))
                    Log.i(TAG, "SAVE ${s.ref.name} backup=${b?.name}")
                    if (overlay !is Overlay.CopyBeside) say(s, if (b != null) str(R.string.saved_first, storage.display(s.ref)) else str(R.string.saved_to, storage.display(s.ref)))
                    after?.invoke()
                }
        }
    }

    /** First save of a new file: it takes its name from the first line (documents) or first slide title. No backup. */
    private fun saveNew(s: Session, win: Win, after: (() -> Unit)? = null) {
        val old = s.ref as? FileRef.Path ?: return write(s, win, false, after)
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    val base = if (s is SheetSession) null else Naming.baseFromText(s.firstLine())
                    val target = if (base != null && Naming.isUntitled(old.name)) {
                        val dir = old.file.parentFile!!
                        File(dir, Naming.unique((dir.list()?.toList() ?: emptyList()) - old.name, base, s.kind.ext))
                    } else old.file
                    target.writeBytes(s.serialize())
                    if (target != old.file) old.file.delete()
                    target
                }
            }
            r.onFailure { e -> Log.e(TAG, "SAVE new failed", e); say(s, str(R.string.save_failed, e.message ?: "")) }
                .onSuccess { f ->
                    val renamed = f != old.file
                    if (renamed) {
                        val ref = FileRef.Path(f)
                        layouts.move(old.key, ref.key); review.move(old.key, ref.key); recents.rename(old.key, ref, storage.display(ref)); moved(s, ref)
                    }
                    layouts.markBackedUp(s.ref.key, "")   // a file made here has no original to back up
                    if (renamed) history.move(old.key, s.ref.key)
                    s.saved(); filesVersion++; dropDraft(s, old.key); dropDraft(s); keepVersion(s)
                    recents.touch(s.ref, storage.display(s.ref))
                    Log.i(TAG, "SAVE new ${old.name} -> ${f.name}")
                    say(s, if (renamed) str(R.string.renamed, f.name) else str(R.string.saved_to, storage.display(s.ref)))
                    after?.invoke()
                }
        }
    }

    fun saveAs(win: Win, target: SaveAsTarget) {
        val s = session(win) ?: return
        // the cloud copy exists only with a drive to put it on: the dialog has no such row otherwise (v0.2.7, q7-1),
        // and the debug command that stands for the row does nothing either
        val drive = accounts.saveAsDrive
        if (target == SaveAsTarget.CLOUD && drive == null) { Log.i(TAG, "SAVEAS cloud ignored: no drive offered and signed in"); return }
        overlay = null
        val zh = settings.value.zh
        if (target == SaveAsTarget.CLOUD && drive != null) {
            scope.launch {
                val r = withContext(Dispatchers.IO) { runCatching { accounts.saveCopy(drive, s.ref.name, if (s.readOnly) storage.read(s.ref) else s.serialize()) } }
                r.onFailure { e -> Log.e(TAG, "SAVEAS cloud failed", e); say(s, str(R.string.cloud_save_failed, e.message ?: "")) }
                    .onSuccess { where -> Log.i(TAG, "SAVEAS cloud -> $where"); say(s, str(R.string.saved_as, where)) }
            }
            return
        }
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { Exporter.saveAs(this@Graph, s, target, zh) } }
            r.onFailure { e -> Log.e(TAG, "SAVEAS failed", e); say(s, str(R.string.save_failed, e.message ?: "")) }
                .onSuccess { f -> filesVersion++; Log.i(TAG, "SAVEAS $target -> ${f.absolutePath}"); say(s, str(R.string.saved_as, storage.display(f))) }
        }
    }

    // ---------------------------------------------------------------- close / unsaved

    /** Title-bar close button of [s]'s window. */
    fun requestClose(s: Session) {
        if (s.dirty) { show(s, Overlay.Unsaved(winOf(s.kind), "close")); return }
        closeNow(s)
    }

    fun requestClose(win: Win) { session(win)?.let { requestClose(it) } }

    private fun closeNow(s: Session) {
        val hadEmptyNew = s.isNew && !s.dirty
        if (!s.dirty) dropDraft(s) else { drafted.remove(s.tag); pendingRecover.remove(s.ref.key) }
        discardEmptyNew(s)
        val ref = refOf(s)
        byTag.remove(s.tag); files.close(s.tag); openVersion++
        if (s is SheetSession && chartTag == s.tag) { windows.close(WinRef(Win.CHART)); chartTag = null }
        if (overlay?.tag == s.tag) overlay = null
        windows.close(ref)
        Log.i(TAG, "CLOSE ${s.ref.name} window=$ref open=${files.entries.size}")
        if (hadEmptyNew) say(Win.HOME, str(R.string.empty_removed))
    }

    /** Answer to the "unsaved changes" dialog: "save" | "discard" | "cancel". */
    fun answerUnsaved(answer: String) {
        val o = overlay as? Overlay.Unsaved ?: return
        val s = session(o.win)
        overlay = null
        if (answer == "cancel" || s == null) return
        val go = {
            when (o.then) {
                "close" -> closeNow(s)
                "exit" -> exit()
            }
            Unit
        }
        if (answer == "discard") { dropDraft(s); s.dirty = false; if (s.isNew) (s.ref as? FileRef.Path)?.file?.delete()?.also { recents.remove(s.ref.key); s.isNew = false; filesVersion++ }; go() }
        else if (s.isNew) saveNew(s, o.win, go)
        else write(s, o.win, backup = settings.value.keepBackup && layouts.backupOf(s.ref.key) == null, after = go)
    }

    /**
     * The system closed a window (its × or a task switch). A file with unsaved changes keeps its session, so nothing
     * is lost and opening it again from Recent brings the window back as it was; a saved one is simply closed.
     */
    fun windowClosed(ref: WinRef) {
        Log.i(TAG, "WINDOW $ref closed by the system")
        val s = sessionByTag(ref.tag) ?: return
        if (refOf(s) != ref || s.dirty || stageMode != null) return
        // a window that is only being rebuilt comes straight back: wait before letting the file go
        Handler(Looper.getMainLooper()).postDelayed({
            if (byTag[s.tag] === s && !s.dirty && stageMode == null && !windows.isOpen(ref) && !windows.reopening(ref)) {
                discardEmptyNew(s)
                byTag.remove(s.tag); files.close(s.tag); openVersion++
                if (s is SheetSession && chartTag == s.tag) { windows.close(WinRef(Win.CHART)); chartTag = null }
                Log.i(TAG, "CLOSE ${s.ref.name} (window $ref closed by the system, nothing unsaved)")
            }
        }, 1500)
    }

    // ---------------------------------------------------------------- spread / rehearsal

    fun spread(kind: FileKind) { session(kind)?.let { spread(it) } }

    fun spread(s: Session) {
        activate(s)
        val kind = s.kind
        val rec = layouts.load(s.ref)
        stageTag = s.tag
        stageKind = kind.editor
        // a deck opens as its storyline by chapters; chapters are a deck's only
        arrangement = (rec?.arrangement ?: if (s is DeckSession) Arrangement.CHAPTERS else Arrangement.ARC)
            .let { if (it == Arrangement.CHAPTERS && s !is DeckSession) Arrangement.ARC else it }
        focus = pieceOf(s, rec?.focus).coerceIn(0, (s.pieceCount - 1).coerceAtLeast(0))
        stageMode = StageMode.SPREAD
        Log.i(TAG, "SPREAD enter kind=$stageKind arr=$arrangement focus=$focus pieces=${s.pieceCount}")
        windows.enterStage()
        say(Win.STAGE, str(R.string.full_space))
        remember()
    }

    /** p28: the rehearsal screen shows black (B / . toggles it; the speaker's panels stay). */
    var rehearsalBlack by mutableStateOf(false)
        private set
    /** p28: the rehearsal bar shows the slide numbers to jump to ([Go to…]). */
    var rehearsalPicking by mutableStateOf(false)
    /** p28: digits typed during the rehearsal; Enter goes to that slide. */
    var rehearsalTyped by mutableStateOf("")

    fun toggleBlack() { rehearsalBlack = !rehearsalBlack; Log.i(TAG, "REHEARSAL black=$rehearsalBlack") }

    /** p28 [Go to…] / number + Enter: slide [n] (1-based) of the rehearsed deck; the screen is shown again. */
    fun rehearsalGo(n: Int): Boolean {
        val d = stageSession as? DeckSession ?: return false
        if (n !in 1..d.deck.slides.size) { Log.i(TAG, "REHEARSAL go $n refused (1..${d.deck.slides.size})"); return false }
        d.current = n - 1; rehearsalBlack = false; rehearsalPicking = false; rehearsalTyped = ""
        Log.i(TAG, "REHEARSAL go slide=${d.current}")
        return true
    }

    /**
     * Watching (true) or rehearsing (false) on the big screen. Watching is for a deck someone sent: only the screen and a
     * small bar. Rehearsing adds the speaker's panels (notes, next slide, clock) on both sides.
     */
    var watching by mutableStateOf(false)
        private set

    /** Title bar [Watch]: the deck on the big screen, nothing else. */
    fun watch(d: DeckSession? = deck, fromStart: Boolean = false) { rehearse(d, fromStart, watch = true) }

    fun rehearse(d: DeckSession? = deck, fromStart: Boolean = false, watch: Boolean = false) {
        d ?: return
        if (fromStart) d.current = 0
        watching = watch
        rehearsalBlack = false; rehearsalPicking = false; rehearsalTyped = ""
        activate(d)
        stageTag = d.tag
        stageKind = FileKind.SLIDES
        stageMode = StageMode.REHEARSAL
        rehearsalStart = android.os.SystemClock.elapsedRealtime()
        Log.i(TAG, "${if (watch) "WATCH" else "REHEARSAL"} enter slide=${d.current}")
        windows.enterStage()
        if (watch) say(Win.STAGE, str(R.string.watch_entered))
    }

    /**
     * K1 (QA #15): after the Stage the home window is in front and the window just rehearsed / spread out stands next
     * to it on the circle around the user. The home window shows on which side, until the user looks at that window,
     * closes the note, or 20 s pass.
     */
    data class BackHint(val tag: String, val name: String, val right: Boolean, val at: Long)
    var backHint by mutableStateOf<BackHint?>(null)

    fun collapse() {
        val mode = stageMode ?: return
        if (mode == StageMode.SPREAD) remember(spreadNow = false)
        stageMode = null
        Log.i(TAG, "STAGE leave mode=$mode")
        windows.leaveStage(stageSession?.let { refOf(it) })
    }

    /** The Stage went away without [collapse] (the system closed it): bring the windows back. */
    fun stageGone() {
        if (stageMode == null) return
        Log.i(TAG, "STAGE closed by the system")
        stageMode = null
        windows.stageClosed(stageSession?.let { refOf(it) })
    }

    fun arrange(a: Arrangement) {
        if (a == Arrangement.CHAPTERS && stageSession !is DeckSession) return
        arrangement = a; remember(); Log.i(TAG, "SPREAD arr=$a")
    }

    /** Storyline by chapters: the chapter before / after the one in front comes to the front (its first slide). */
    fun chapterBy(delta: Int) {
        val d = stageSession as? DeckSession ?: return
        val ch = d.chapters()
        val to = ch.getOrNull(io.embodit.spatialoffice.core.pptx.Chapters.indexOf(ch, focus) + delta) ?: return
        focusOn(to.first)
    }

    fun focusBy(delta: Int) = focusOn(focus + delta)

    fun focusOn(i: Int) {
        val n = stageSession?.pieceCount ?: return
        val f = i.coerceIn(0, (n - 1).coerceAtLeast(0))
        if (f == focus) return
        focus = f
        (stageSession as? DeckSession)?.current = f
        remember(); Log.i(TAG, "SPREAD focus=$f")
    }

    fun recenter() { recenterTick++; Log.i(TAG, "SPREAD recenter") }

    val radiusSetting: Float get() = LayoutRules.RADII[settings.value.radius]

    private fun anchor(s: Session, piece: Int): String = when (s) { is SheetSession -> "sheet:${s.sheet}/block:$piece"; is DeckSession -> "slide:$piece"; else -> "section:$piece" }
    private fun pieceOf(s: Session, anchor: String?): Int = anchor?.substringAfterLast(':')?.toIntOrNull() ?: ((s as? DeckSession)?.current ?: 0)

    /** Writes the layout record: app data always, the sidecar when the user chose "with the file". */
    private fun remember(spreadNow: Boolean = true) {
        val s = stageSession ?: return
        val keepSpread = spreadNow && settings.value.openMode == OpenMode.SPREAD
        val rec = LayoutRecord(storage.display(s.ref), arrangement, anchor(s, focus), radiusSetting, keepSpread)
        layouts.save(s.ref, rec, settings.value.layoutStore == LayoutStoreMode.FILE)
    }

    /** Menu: keep the layout with the file → writes the sidecar now. */
    fun layoutWithFile(win: Win) { session(win)?.let { layoutWithFile(it) } }

    fun layoutWithFile(s: Session) {
        val rec = layouts.load(s.ref) ?: LayoutRecord(storage.display(s.ref), arrangement, anchor(s, 0), radiusSetting)
        val ok = layouts.save(s.ref, rec, withFile = true)
        filesVersion++
        say(s, if (ok) str(R.string.layout_with_file, s.ref.name + LayoutRecord.SIDECAR_SUFFIX) else str(R.string.layout_with_file_fail))
    }

    fun openBackup(s: Session) {
        val p = layouts.backupOf(s.ref.key)?.takeIf { it.isNotEmpty() }?.let { File(it) }?.takeIf { it.exists() }
        if (p == null) say(s, str(R.string.no_backup)) else open(FileRef.Path(p))
    }

    // ---------------------------------------------------------------- exit

    fun requestExit() {
        overlay = Overlay.Exit(Win.HOME)
        homePage = HomePage.SETTINGS
    }

    val dirtyNames: List<String> get() = sessions.filter { it.dirty }.map { it.ref.name }

    /** Closes every window container and the Stage, removes the task, ends the process (nothing stays in recents). */
    fun exit() {
        Log.i(TAG, "EXIT close all containers + finishAndRemoveTask")
        overlay = null
        sessions.forEach { discardEmptyNew(it) }
        windows.closeAll()
        Handler(Looper.getMainLooper()).postDelayed({
            runCatching {
                val am = app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                am.appTasks.forEach { runCatching { it.finishAndRemoveTask() } }
            }
            Handler(Looper.getMainLooper()).postDelayed({ exitProcess(0) }, 500)
        }, 400)
    }

    // ---------------------------------------------------------------- d27 drafts

    /** Window tag → (file key, session version) of the last draft written. */
    private val drafted = HashMap<String, Pair<String, Int>>()
    /** Files whose draft is waiting for the "recover?" answer: their draft is not overwritten or deleted meanwhile. */
    private val pendingRecover = HashSet<String>()

    init {
        scope.launch {
            while (true) {
                kotlinx.coroutines.delay(Drafts.PERIOD_MS)
                runCatching { autosave() }.onFailure { Log.w(TAG, "DRAFT round failed: $it") }
            }
        }
    }

    /** One autosave round (every 3 s; the debug command `draft-now` runs one at once). */
    fun autosave() {
        for (s in sessions) {
            val key = s.ref.key
            val last = drafted[s.tag]
            if (last != null && last.first != key) { drafts.delete(last.first); drafted.remove(s.tag) }
            if (key in pendingRecover || s.readOnly) continue
            if (!s.dirty) { if (drafted.remove(s.tag) != null) drafts.delete(key); continue }
            if (last?.first == key && last.second == s.version) continue
            val bytes = runCatching { s.serialize() }.getOrNull() ?: continue
            drafted[s.tag] = key to s.version
            scope.launch(Dispatchers.IO) { drafts.write(key, bytes) }
            Log.i(TAG, "DRAFT saved ${s.ref.name} v${s.version} ${bytes.size} B")
        }
    }

    /** Saved, discarded or closed without changes: the draft has nothing left to give back. */
    private fun dropDraft(s: Session, key: String = s.ref.key) {
        drafted.remove(s.tag); pendingRecover.remove(key)
        scope.launch(Dispatchers.IO) { drafts.delete(key) }
    }

    /** Opening a file that has a draft: the existing confirm dialog asks whether to take the changes back. */
    private fun offerDraft(s: Session) {
        if (s.readOnly || !drafts.has(s.ref.key)) return
        pendingRecover.add(s.ref.key)
        Log.i(TAG, "DRAFT found for ${s.ref.name}: asking")
        show(s, Overlay.Recover(winOf(s.kind)))
    }

    /** Answer to "Recover unsaved changes?": [yes] takes the draft back (one undo step), no deletes it. */
    fun answerRecover(yes: Boolean) {
        val o = overlay as? Overlay.Recover ?: return
        val s = session(o.win)
        overlay = null
        s ?: return
        val key = s.ref.key
        pendingRecover.remove(key)
        if (!yes) { dropDraft(s, key); Log.i(TAG, "DRAFT declined ${s.ref.name}"); return }
        val bytes = drafts.read(key)
        val ok = bytes != null && s.recover(bytes)
        Log.i(TAG, "DRAFT recovered ${s.ref.name} ok=$ok dirty=${s.dirty}")
        if (ok) { drafted[s.tag] = key to s.version; say(s, str(R.string.draft_back)) }
        else { dropDraft(s, key); say(s, str(R.string.draft_failed)) }
    }

    // ---------------------------------------------------------------- d28 version history

    /** Bumped when a version is kept, so an open history list follows. */
    var historyVersion by mutableIntStateOf(0)
        private set

    /** After a save: the saved bytes become the newest version (the newest [History.KEEP] are kept). */
    private fun keepVersion(s: Session) {
        if (s.readOnly) return
        val key = s.ref.key
        val bytes = runCatching { s.serialize() }.getOrNull() ?: return
        scope.launch(Dispatchers.IO) { history.add(key, bytes); withContext(Dispatchers.Main) { historyVersion++ } }
    }

    fun versions(s: Session): List<History.Version> = history.list(s.ref.key)

    /**
     * [Restore] in the history list: what the window holds now is kept as a version first ("before restore"), then the
     * old version comes back as an unsaved change (one undo step; saving makes it the file).
     */
    fun restoreVersion(s: Session, v: History.Version): Boolean {
        val ok = history.restore(s, v)
        historyVersion++
        Log.i(TAG, "HISTORY restore ${s.ref.name} at=${v.at} ok=$ok dirty=${s.dirty} versions=${history.list(s.ref.key).size}")
        if (ok) say(s, str(R.string.hist_restored))
        return ok
    }

    // ---------------------------------------------------------------- M5 review

    /** Title bar [Review n]: opens / closes the pane inside [s]'s window. The window is not moved or resized. */
    fun setReview(s: Session, open: Boolean) {
        if (!s.canReview || s.reviewOpen == open) return
        s.reviewOpen = open
        if (!open) { s.selComment = null; s.composing = false }
        review.setOpen(s.ref.key, open)
        Log.i(TAG, "REVIEW pane ${if (open) "open" else "closed"} ${s.kind} ${s.tag} ${s.ref.name} count=${s.reviewCount()}")
    }

    fun toggleReview(s: Session) = setReview(s, !s.reviewOpen)

    /** Ctrl+Alt+M, Insert › Comment (documents, slides), Edit › Comment (sheets): the pane opens on its comment list, the text box takes the caret. */
    fun startComment(s: Session) {
        if (!s.canReview || s.readOnly) return
        setReview(s, true)
        s.reviewTab = 0; s.selComment = null; s.composing = true; s.composeTick++
        Log.i(TAG, "REVIEW compose ${s.kind} ${s.tag} onSelection=${s.commentOnSelection}")
    }

    /** "Track my edits" (documents): needs a name; without one the pane asks for it first. */
    fun setTracking(s: Session, on: Boolean): Boolean {
        if (!s.hasRevisions || s.readOnly) return false
        if (on && settings.value.myName.isBlank()) { setReview(s, true); s.reviewTab = 1; return false }
        s.trackAuthor = settings.value.myName
        s.tracking = on
        review.setTracking(s.ref.key, on)
        Log.i(TAG, "REVIEW tracking=${s.tracking} author=${s.trackAuthor} ${s.ref.name}")
        return true
    }

    /** Settings › My name, and the pane's one-time question: the name on my comments and tracked changes. */
    fun setMyName(name: String) {
        val n = name.trim().take(40)
        settings.update { it.copy(myName = n) }
        sessions.forEach { it.trackAuthor = n }
        Log.i(TAG, "REVIEW myName set len=${n.length}")
    }

    // ---------------------------------------------------------------- BACK / controller B (profile D60)

    fun onBack(where: String) {
        val used = lastUsed?.takeIf { byTag[it.tag] === it }
        val act = BackPolicy.decide(overlay != null, stageMode != null, windows.isOpen(Win.HOME), OpenMenus.any, (used as? DocSession)?.painter != null, used?.reviewOpen == true)
        Log.i(TAG, "BACK $where -> $act")
        when (act) {
            BackAct.CLOSE_MENU -> OpenMenus.closeNewest()
            BackAct.DROP_PAINTER -> (used as? DocSession)?.dropPainter()
            BackAct.CLOSE_REVIEW -> used?.let { setReview(it, false) }
            BackAct.CLOSE_OVERLAY -> when (overlay) {
                is Overlay.Unsaved -> answerUnsaved("cancel")
                else -> overlay = null
            }
            BackAct.REOPEN_HOME -> windows.frontHome()
            BackAct.NOTHING -> {}
        }
    }

    /** M5: the review state of every open file, for the DUMP line. */
    private fun reviewDump(): String = " review=[" + sessions.joinToString(",") { s ->
        val c = runCatching { s.comments() }.getOrDefault(emptyList())
        "${s.tag}:${if (s.reviewOpen) "open" else "closed"}:tab${s.reviewTab}:comments=${c.size}(open ${c.count { !it.resolved }}, replies ${c.sumOf { it.replies.size }}):revisions=${runCatching { s.revisions().size }.getOrDefault(0)}:sel=${s.selComment}:track=${s.tracking}"
    } + "] myNameLen=${settings.value.myName.length} painter=${(doc as? DocSession)?.painter != null}"

    fun dump(): String = dumpBase() + reviewDump()

    private fun dumpBase(): String =
        "DUMP page=$homePage lang=${settings.value.lang} doc=${doc?.ref?.name}:${doc?.dirty} sheet=${sheet?.ref?.name}:${sheet?.dirty}@${sheet?.row},${sheet?.col} " +
            "deck=${deck?.ref?.name}:${deck?.dirty}#${deck?.current} overlay=${overlay?.javaClass?.simpleName} stage=$stageMode/$stageKind/$arrangement/$focus " +
            "windows=${windows.dump()} allFiles=${storage.hasAllFiles} blocks=${doc?.blockCount} slides=${deck?.slideCount} sel=${deck?.selected} " +
            "tableCaret=${doc?.tableCaret} cols=${sheet?.colCount} rows=${sheet?.rowCount} frozen=${sheet?.frozen} filter=${sheet?.filter} fcols=${sheet?.frozenCols} hidden=${sheet?.let { s -> runCatching { s.book.hiddenRows(s.sheet).toString() + s.book.hiddenCols(s.sheet) }.getOrNull() }} multi=${deck?.multiSet()} outline=${doc?.outline()?.size} xsel=${doc?.crossSel} accounts=" +
            io.embodit.spatialoffice.core.cloud.Provider.values().joinToString(",") { it.id + ":" + accounts.state(it) } +
            " open=[" + files.entries.joinToString(",") { e -> "${e.tag}:${e.kind}:${byTag[e.tag]?.ref?.name}:${windows.state[WinRef(winOf(FileKind.valueOf(e.kind)), e.tag)] ?: "-"}" } + "]" +
            " chartOf=$chartTag objBox=${deck?.dragBox} sel=${deck?.selected} docPicked=${doc?.picked}" +
            " range=${sheet?.selection?.name()} sheets=${sheet?.book?.sheetNames} find=${(doc ?: sheet ?: deck)?.findHit} drafts=${drafts.count()} pending=${pendingRecover.size}" +
            " textSel=${deck?.textSel} rot=${deck?.pickedShape()?.rotation} hist=${listOfNotNull(doc, sheet, deck).joinToString(",") { "${it.kind}:${history.list(it.ref.key).size}" }}" +
            " zoom=${listOfNotNull(doc, sheet, deck).joinToString(",") { "${it.kind}:${it.zoom}" }} merges=${sheet?.takeIf { !it.isCsv }?.let { s -> runCatching { s.book.merges(s.sheet).joinToString("+") { io.embodit.spatialoffice.core.xlsx.A1.rangeName(it[0], it[1], it[2], it[3]) } }.getOrNull() }}"

    companion object {
        fun from(context: Context): Graph = (context.applicationContext as SpatialApplication).graph
    }
}
