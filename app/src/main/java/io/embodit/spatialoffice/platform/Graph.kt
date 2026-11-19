package io.embodit.spatialoffice.platform

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.getValue
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
    /** Find and replace in the document. */
    data class Find(override val win: Win = Win.DOC) : Overlay()
    data class WordCount(override val win: Win = Win.DOC) : Overlay()
    data class InsertTable(override val win: Win = Win.DOC) : Overlay()
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
    val windows = WindowCtl(this)
    val accounts = Accounts(app, this).also { storage.accounts = it }

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
    fun activate(s: Session) {
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
        }

    /** Shows [o] in [s]'s own window. */
    fun show(s: Session, o: Overlay) { activate(s); o.tag = s.tag; overlayState = o }
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
        val rec = layouts.load(s.ref)
        if (!isNew && (rec?.spread == true || settings.value.openMode == OpenMode.SPREAD)) spread(s)
    }

    /** A new, empty file in a window of its own; the files that are open stay open. */
    fun newFile(kind: FileKind) {
        val open = files.ofKind(kind.editor.name).size
        if (open >= OpenFiles.MAX_PER_KIND) { say(Win.HOME, str(R.string.too_many_open, open)); return }
        scope.launch {
            val zh = settings.value.zh
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val bytes = when (kind) {
                        FileKind.SHEET -> XlsxWorkbook.blank().save()
                        FileKind.SLIDES -> PptxDeck.blank().save()
                        else -> DocxDocument.blank().save()
                    }
                    val name = Naming.untitled(kind, zh)
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
                    s.saved(); filesVersion++
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
                        layouts.move(old.key, ref.key); recents.rename(old.key, ref, storage.display(ref)); moved(s, ref)
                    }
                    layouts.markBackedUp(s.ref.key, "")   // a file made here has no original to back up
                    s.saved(); filesVersion++
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
        if (answer == "discard") { s.dirty = false; if (s.isNew) (s.ref as? FileRef.Path)?.file?.delete()?.also { recents.remove(s.ref.key); s.isNew = false; filesVersion++ }; go() }
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
        arrangement = rec?.arrangement ?: Arrangement.ARC
        focus = pieceOf(s, rec?.focus).coerceIn(0, (s.pieceCount - 1).coerceAtLeast(0))
        stageMode = StageMode.SPREAD
        Log.i(TAG, "SPREAD enter kind=$stageKind arr=$arrangement focus=$focus pieces=${s.pieceCount}")
        windows.enterStage()
        say(Win.STAGE, str(R.string.full_space))
        remember()
    }

    fun rehearse(d: DeckSession? = deck) {
        d ?: return
        activate(d)
        stageTag = d.tag
        stageKind = FileKind.SLIDES
        stageMode = StageMode.REHEARSAL
        rehearsalStart = android.os.SystemClock.elapsedRealtime()
        Log.i(TAG, "REHEARSAL enter slide=${d.current}")
        windows.enterStage()
    }

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

    fun arrange(a: Arrangement) { arrangement = a; remember(); Log.i(TAG, "SPREAD arr=$a") }

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

    fun dump(): String =
        "DUMP page=$homePage lang=${settings.value.lang} doc=${doc?.ref?.name}:${doc?.dirty} sheet=${sheet?.ref?.name}:${sheet?.dirty}@${sheet?.row},${sheet?.col} " +
            "deck=${deck?.ref?.name}:${deck?.dirty}#${deck?.current} overlay=${overlay?.javaClass?.simpleName} stage=$stageMode/$stageKind/$arrangement/$focus " +
            "windows=${windows.dump()} allFiles=${storage.hasAllFiles} blocks=${doc?.blockCount} slides=${deck?.slideCount} sel=${deck?.selected} " +
            "tableCaret=${doc?.tableCaret} cols=${sheet?.colCount} rows=${sheet?.rowCount} frozen=${sheet?.frozen} filter=${sheet?.filterCol}:${sheet?.filterValue} accounts=" +
            io.embodit.spatialoffice.core.cloud.Provider.values().joinToString(",") { it.id + ":" + accounts.state(it) } +
            " open=[" + files.entries.joinToString(",") { e -> "${e.tag}:${e.kind}:${byTag[e.tag]?.ref?.name}:${windows.state[WinRef(winOf(FileKind.valueOf(e.kind)), e.tag)] ?: "-"}" } + "]" +
            " chartOf=$chartTag objBox=${deck?.dragBox} sel=${deck?.selected} docPicked=${doc?.picked}"

    companion object {
        fun from(context: Context): Graph = (context.applicationContext as SpatialApplication).graph
    }
}
