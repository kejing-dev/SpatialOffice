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
enum class SaveAsTarget { COPY, PDF, CSV, TXT, PNG }

/** A dialog or sheet shown in one window. */
sealed class Overlay {
    abstract val win: Win
    data class SaveFirst(override val win: Win) : Overlay()
    data class SaveAs(override val win: Win) : Overlay()
    /** [then]: "close" | "exit" | "open" | "new". */
    data class Unsaved(override val win: Win, val then: String) : Overlay()
    data class Shortcuts(override val win: Win) : Overlay()
    data class Compat(override val win: Win) : Overlay()
    data class Exit(override val win: Win = Win.HOME) : Overlay()
    data class HowTo(override val win: Win = Win.HOME) : Overlay()
}

data class Toast(val id: Long, val win: Win, val text: String, val action: String? = null, val onAction: (() -> Unit)? = null)

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

    var homePage by mutableStateOf(HomePage.RECENT)
    var browseDir by mutableStateOf<File?>(null)
    var browseLoc by mutableStateOf(0)
    var doc by mutableStateOf<DocSession?>(null)
        private set
    var sheet by mutableStateOf<SheetSession?>(null)
        private set
    var deck by mutableStateOf<DeckSession?>(null)
        private set
    var overlay by mutableStateOf<Overlay?>(null)
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

    // chart volume
    var chartIndex by mutableStateOf(0)
    var chartScatter by mutableStateOf(false)
    var chartYaw by mutableStateOf(0f)

    private var pendingOpen: FileRef? = null
    private var pendingNew: FileKind? = null
    private var toastSeq = 0L
    private val res get() = app.localized(settings.value.lang).resources

    fun str(id: Int, vararg args: Any): String = if (args.isEmpty()) res.getString(id) else res.getString(id, *args)

    fun session(win: Win): Session? = when (win) { Win.DOC -> doc; Win.SHEET, Win.CHART -> sheet; Win.SLIDES -> deck; else -> null }
    fun session(kind: FileKind): Session? = when (kind.editor) { FileKind.DOC -> doc; FileKind.SHEET -> sheet; FileKind.SLIDES -> deck; else -> null }
    fun winOf(kind: FileKind): Win = when (kind.editor) { FileKind.SHEET -> Win.SHEET; FileKind.SLIDES -> Win.SLIDES; else -> Win.DOC }

    fun say(win: Win, text: String, action: String? = null, onAction: (() -> Unit)? = null) {
        Log.i(TAG, "TOAST ${win.name}: $text")
        toast = Toast(++toastSeq, if (stageMode != null && win != Win.HOME) Win.STAGE else win, text, action, onAction)
    }

    fun toastShown(id: Long) { if (toast?.id == id) toast = null }

    // ---------------------------------------------------------------- open / new

    fun openIntent(intent: Intent?) {
        val ref = storage.fromIntent(intent) ?: return
        Log.i(TAG, "INTENT ${intent?.action} -> ${ref.name}")
        open(ref)
    }

    fun open(file: FileRef) {
        // /sdcard/… and /storage/emulated/0/… are the same file: use one spelling, so paths display and compare the same
        val ref = if (file is FileRef.Path) FileRef.Path(runCatching { file.file.canonicalFile }.getOrDefault(file.file)) else file
        val kind = FileKind.ofName(ref.name)
        if (kind == FileKind.UNKNOWN) { say(Win.HOME, str(R.string.open_unknown)); return }
        val cur = session(kind)
        if (cur != null && cur.ref.key == ref.key) { windows.open(winOf(kind)); return }
        if (cur != null && cur.dirty) { pendingOpen = ref; pendingNew = null; overlay = Overlay.Unsaved(winOf(kind), "open"); windows.open(winOf(kind)); return }
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { build(ref, kind, storage.read(ref)) } }
            result.onFailure { e ->
                Log.e(TAG, "OPEN failed ${ref.name}", e)
                say(Win.HOME, str(R.string.open_failed, ref.name, e.message ?: e.javaClass.simpleName))
                if (ref is FileRef.Path && !ref.file.exists()) recents.remove(ref.key)
            }.onSuccess { s -> adopt(s, isNew = false) }
        }
    }

    private fun build(ref: FileRef, kind: FileKind, bytes: ByteArray): Session = when (kind.editor) {
        FileKind.SHEET -> SheetSession(ref, kind, bytes)
        FileKind.SLIDES -> DeckSession(ref, bytes)
        else -> DocSession(ref, kind, bytes, app.cacheDir)
    }

    private fun adopt(s: Session, isNew: Boolean) {
        discardEmptyNew(session(s.kind))
        s.isNew = isNew
        when (s) { is DocSession -> doc = s; is SheetSession -> { sheet = s; chartIndex = 0 }; is DeckSession -> deck = s }
        recents.touch(s.ref, storage.display(s.ref))
        filesVersion++
        val win = winOf(s.kind)
        windows.open(win)
        Log.i(TAG, "OPEN ${s.ref.name} kind=${s.kind} new=$isNew pieces=${s.pieceCount}")
        if (isNew) say(win, str(R.string.new_made, storage.display(s.ref)))
        else {
            val c = s.compat()
            if (c.isEmpty()) say(win, str(R.string.opened, s.ref.name))
            else say(win, str(R.string.opened, s.ref.name) + " · " + str(R.string.opened_sub, c.approx(), c.held()), str(R.string.view)) { overlay = Overlay.Compat(win) }
        }
        val rec = layouts.load(s.ref)
        if (!isNew && (rec?.spread == true || settings.value.openMode == OpenMode.SPREAD)) spread(s.kind)
    }

    fun newFile(kind: FileKind) {
        val cur = session(kind)
        if (cur != null && cur.dirty) { pendingNew = kind; pendingOpen = null; overlay = Overlay.Unsaved(winOf(kind), "new"); windows.open(winOf(kind)); return }
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
                .onSuccess { adopt(it, isNew = true) }
        }
    }

    /** A new file that was never touched is not kept (PRD §4.7). */
    private fun discardEmptyNew(s: Session?) {
        if (s == null || !s.isNew || s.dirty) return
        (s.ref as? FileRef.Path)?.file?.let { f ->
            if (f.delete()) { recents.remove(s.ref.key); filesVersion++; Log.i(TAG, "NEW empty file removed ${f.name}") }
        }
    }

    // ---------------------------------------------------------------- save

    /** [Save] / Ctrl+S. */
    fun save(win: Win) {
        val s = session(win) ?: return
        if (s.readOnly) return
        if (s.isNew) { saveNew(s, win); return }
        if (!s.dirty) { say(win, str(R.string.saved_to, storage.display(s.ref))); return }
        if (settings.value.keepBackup && layouts.backupOf(s.ref.key) == null) { overlay = Overlay.SaveFirst(win); return }
        write(s, win, backup = false)
    }

    /** From the first-save sheet. */
    fun confirmFirstSave(win: Win) {
        overlay = null
        session(win)?.let { write(it, win, backup = true) }
    }

    private fun write(s: Session, win: Win, backup: Boolean, after: (() -> Unit)? = null) {
        val zh = settings.value.zh
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    var backupFile: File? = null
                    if (backup) {
                        backupFile = storage.backup(s.ref, storage.read(s.ref), zh)
                        backupFile?.let { layouts.markBackedUp(s.ref.key, it.absolutePath) }
                    }
                    storage.write(s.ref, s.serialize())
                    backupFile
                }
            }
            r.onFailure { e -> Log.e(TAG, "SAVE failed", e); say(win, str(R.string.save_failed, e.message ?: str(R.string.perm_denied))) }
                .onSuccess { b ->
                    s.saved(); filesVersion++
                    recents.touch(s.ref, storage.display(s.ref))
                    Log.i(TAG, "SAVE ${s.ref.name} backup=${b?.name}")
                    say(win, if (b != null) str(R.string.saved_first, storage.display(s.ref)) else str(R.string.saved_to, storage.display(s.ref)))
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
            r.onFailure { e -> Log.e(TAG, "SAVE new failed", e); say(win, str(R.string.save_failed, e.message ?: "")) }
                .onSuccess { f ->
                    val renamed = f != old.file
                    if (renamed) {
                        val ref = FileRef.Path(f)
                        layouts.move(old.key, ref.key); recents.rename(old.key, ref, storage.display(ref)); s.ref = ref
                    }
                    layouts.markBackedUp(s.ref.key, "")   // a file made here has no original to back up
                    s.saved(); filesVersion++
                    recents.touch(s.ref, storage.display(s.ref))
                    Log.i(TAG, "SAVE new ${old.name} -> ${f.name}")
                    say(win, if (renamed) str(R.string.renamed, f.name) else str(R.string.saved_to, storage.display(s.ref)))
                    after?.invoke()
                }
        }
    }

    fun saveAs(win: Win, target: SaveAsTarget) {
        val s = session(win) ?: return
        overlay = null
        val zh = settings.value.zh
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { Exporter.saveAs(this@Graph, s, target, zh) } }
            r.onFailure { e -> Log.e(TAG, "SAVEAS failed", e); say(win, str(R.string.save_failed, e.message ?: "")) }
                .onSuccess { f -> filesVersion++; Log.i(TAG, "SAVEAS $target -> ${f.absolutePath}"); say(win, str(R.string.saved_as, storage.display(f))) }
        }
    }

    // ---------------------------------------------------------------- close / unsaved

    /** Title-bar close button or the system × (through [windowClosed]). */
    fun requestClose(win: Win) {
        val s = session(win)
        if (s != null && s.dirty) { overlay = Overlay.Unsaved(win, "close"); return }
        closeNow(win)
    }

    private fun closeNow(win: Win) {
        val s = session(win)
        val hadEmptyNew = s != null && s.isNew && !s.dirty
        discardEmptyNew(s)
        when (win) { Win.DOC -> doc = null; Win.SHEET -> { sheet = null; windows.close(Win.CHART) }; Win.SLIDES -> deck = null; else -> {} }
        if (overlay?.win == win) overlay = null
        windows.close(win)
        if (hadEmptyNew) say(Win.HOME, str(R.string.empty_removed))
    }

    /** Answer to the "unsaved changes" dialog: "save" | "discard" | "cancel". */
    fun answerUnsaved(answer: String) {
        val o = overlay as? Overlay.Unsaved ?: return
        overlay = null
        if (answer == "cancel") { pendingOpen = null; pendingNew = null; return }
        val s = session(o.win) ?: return
        val go = {
            when (o.then) {
                "close" -> closeNow(o.win)
                "exit" -> exit()
                "open" -> pendingOpen?.let { pendingOpen = null; open(it) }
                "new" -> pendingNew?.let { pendingNew = null; newFile(it) }
            }
            Unit
        }
        if (answer == "discard") { s.dirty = false; if (s.isNew) (s.ref as? FileRef.Path)?.file?.delete()?.also { recents.remove(s.ref.key); s.isNew = false; filesVersion++ }; go() }
        else if (s.isNew) saveNew(s, o.win, go)
        else write(s, o.win, backup = settings.value.keepBackup && layouts.backupOf(s.ref.key) == null, after = go)
    }

    /** The system closed a window (its × or a task switch). An editor closed that way keeps its session so nothing is lost. */
    fun windowClosed(win: Win) {
        Log.i(TAG, "WINDOW ${win.name} closed by the system")
    }

    // ---------------------------------------------------------------- spread / rehearsal

    fun spread(kind: FileKind) {
        val s = session(kind) ?: return
        val rec = layouts.load(s.ref)
        stageKind = kind.editor
        arrangement = rec?.arrangement ?: Arrangement.ARC
        focus = pieceOf(s, rec?.focus).coerceIn(0, (s.pieceCount - 1).coerceAtLeast(0))
        stageMode = StageMode.SPREAD
        Log.i(TAG, "SPREAD enter kind=$stageKind arr=$arrangement focus=$focus pieces=${s.pieceCount}")
        windows.enterStage()
        say(Win.STAGE, str(R.string.full_space))
        remember()
    }

    fun rehearse() {
        deck ?: return
        stageKind = FileKind.SLIDES
        stageMode = StageMode.REHEARSAL
        rehearsalStart = android.os.SystemClock.elapsedRealtime()
        Log.i(TAG, "REHEARSAL enter slide=${deck?.current}")
        windows.enterStage()
    }

    fun collapse() {
        val mode = stageMode ?: return
        if (mode == StageMode.SPREAD) remember(spreadNow = false)
        stageMode = null
        Log.i(TAG, "STAGE leave mode=$mode")
        windows.leaveStage(winOf(stageKind))
    }

    /** The Stage went away without [collapse] (the system closed it): bring the windows back. */
    fun stageGone() {
        if (stageMode == null) return
        Log.i(TAG, "STAGE closed by the system")
        stageMode = null
        windows.stageClosed(winOf(stageKind))
    }

    fun arrange(a: Arrangement) { arrangement = a; remember(); Log.i(TAG, "SPREAD arr=$a") }

    fun focusBy(delta: Int) = focusOn(focus + delta)

    fun focusOn(i: Int) {
        val n = session(stageKind)?.pieceCount ?: return
        val f = i.coerceIn(0, (n - 1).coerceAtLeast(0))
        if (f == focus) return
        focus = f
        (session(stageKind) as? DeckSession)?.current = f
        remember(); Log.i(TAG, "SPREAD focus=$f")
    }

    fun recenter() { recenterTick++; Log.i(TAG, "SPREAD recenter") }

    val radiusSetting: Float get() = LayoutRules.RADII[settings.value.radius]

    private fun anchor(s: Session, piece: Int): String = when (s) { is SheetSession -> "sheet:${s.sheet}/block:$piece"; is DeckSession -> "slide:$piece"; else -> "section:$piece" }
    private fun pieceOf(s: Session, anchor: String?): Int = anchor?.substringAfterLast(':')?.toIntOrNull() ?: ((s as? DeckSession)?.current ?: 0)

    /** Writes the layout record: app data always, the sidecar when the user chose "with the file". */
    private fun remember(spreadNow: Boolean = true) {
        val s = session(stageKind) ?: return
        val keepSpread = spreadNow && settings.value.openMode == OpenMode.SPREAD
        val rec = LayoutRecord(storage.display(s.ref), arrangement, anchor(s, focus), radiusSetting, keepSpread)
        layouts.save(s.ref, rec, settings.value.layoutStore == LayoutStoreMode.FILE)
    }

    /** Menu: keep the layout with the file → writes the sidecar now. */
    fun layoutWithFile(win: Win) {
        val s = session(win) ?: return
        val rec = layouts.load(s.ref) ?: LayoutRecord(storage.display(s.ref), arrangement, anchor(s, 0), radiusSetting)
        val ok = layouts.save(s.ref, rec, withFile = true)
        filesVersion++
        say(win, if (ok) str(R.string.layout_with_file, s.ref.name + LayoutRecord.SIDECAR_SUFFIX) else str(R.string.layout_with_file_fail))
    }

    fun openBackup(win: Win) {
        val s = session(win) ?: return
        val p = layouts.backupOf(s.ref.key)?.takeIf { it.isNotEmpty() }?.let { File(it) }?.takeIf { it.exists() }
        if (p == null) say(win, str(R.string.no_backup)) else open(FileRef.Path(p))
    }

    // ---------------------------------------------------------------- exit

    fun requestExit() {
        overlay = Overlay.Exit(Win.HOME)
        homePage = HomePage.SETTINGS
    }

    val dirtyNames: List<String> get() = listOfNotNull(doc, sheet, deck).filter { it.dirty }.map { it.ref.name }

    /** Closes every window container and the Stage, removes the task, ends the process (nothing stays in recents). */
    fun exit() {
        Log.i(TAG, "EXIT close all containers + finishAndRemoveTask")
        overlay = null
        listOfNotNull(doc, sheet, deck).forEach { discardEmptyNew(it) }
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
            "windows=${windows.dump()} allFiles=${storage.hasAllFiles}"

    companion object {
        fun from(context: Context): Graph = (context.applicationContext as SpatialApplication).graph
    }
}
