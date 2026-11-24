package io.embodit.spatialoffice.platform

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.mutableStateMapOf
import com.pico.spatial.ui.platform.containers.SpatialNavigator
import com.pico.spatial.ui.platform.containers.StageStyle
import io.embodit.spatialoffice.core.layout.ChartOpenGate
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/** Container ids. HOME is the default container (manifest); the others are declared in Main.kt. */
object Containers {
    const val HOME = "SpatialOfficeHome"
    const val DOC = "SpatialOfficeDoc"
    const val SHEET = "SpatialOfficeSheet"
    const val SLIDES = "SpatialOfficeSlides"
    const val CHART = "SpatialOfficeChart"
    const val STAGE = "SpatialOfficeStage"
    const val TAG_MAIN = "main"
    /** Bundle key that tells an editor window which open file it shows. */
    const val ARG_TAG = "file-tag"

    fun id(win: Win): String = when (win) {
        Win.HOME -> HOME; Win.DOC -> DOC; Win.SHEET -> SHEET; Win.SLIDES -> SLIDES; Win.CHART -> CHART; Win.STAGE -> STAGE
    }

    /** Name of one window among the listed containers: the container id, plus the tag for editor windows. */
    fun key(id: String, tag: String?): String = if (tag.isNullOrEmpty() || tag == TAG_MAIN || id == HOME) id else "$id/$tag"
    fun key(ref: WinRef): String = key(id(ref.win), ref.tag)
}

/**
 * One window. Since v0.2.5 every open file has an editor window of its own: same container id, the file's tag
 * (`f1`, `f2`, …) as the container tag. The home window, the chart volume and the Stage exist once (tag "main").
 */
data class WinRef(val win: Win, val tag: String = Containers.TAG_MAIN) {
    val editor: Boolean get() = win == Win.DOC || win == Win.SHEET || win == Win.SLIDES
    override fun toString(): String = if (tag == Containers.TAG_MAIN) win.name else "${win.name}/$tag"
}

/**
 * What the placement of the editor window being opened needs (Main.kt reads it; the placement callback is only given
 * the list of containers): which file's window it is, its stagger slot, and the windows of the same kind already up.
 */
object Placing {
    /** [besideKey]: window key ([Containers.key]) of the window to stand beside, on [side] ("right" / "left"); null = in front. */
    data class Next(val tag: String, val besideKey: String? = null, val side: String = "right")
    val next = ConcurrentHashMap<String, Next>()
    /**
     * "arc" (default since v0.5.1: beside the windows already up, ArcRing) | "front" (every new window in front, the
     * v0.2.5–v0.5.0 rule) | "gap:<dp>" (right of the newest window of the kind, placement trials); debug `win-place`.
     */
    @Volatile var mode: String = "arc"
    /** Gap between two windows set out side by side (dp); v0.5.1: the system's own multi-window spacing (profile D8). */
    const val BESIDE_GAP_DP = 56.0
}

/**
 * Opens and closes the window containers and the Stage. No window is ever placed from the head pose. Entering the
 * Stage closes the app's windows (they would stand in the middle of the spread layout) and leaving reopens them.
 */
class WindowCtl(private val graph: Graph) {
    private val navigators = LinkedHashMap<WinRef, SpatialNavigator>()
    /** Lifecycle of each window as last reported by its content ("ON_RESUME", "ON_DESTROY", …). */
    val state = mutableStateMapOf<WinRef, String>()
    private val main = Handler(Looper.getMainLooper())
    var exiting = false
        private set

    private val wanted = LinkedHashSet<WinRef>()
    /** Editor windows asked for whose content has not said which file it shows yet (fallback when the bundle is lost). */
    private val unclaimed = HashMap<Win, ArrayDeque<String>>()
    /** Windows this controller closed in order to open them again (front, chart moving to another sheet). */
    private val reopen = HashSet<WinRef>()

    fun attach(ref: WinRef, nav: SpatialNavigator) {
        navigators[ref] = nav
        // a file handed over at launch asks for its window before any navigator exists
        if (wanted.isNotEmpty()) main.post { val w = wanted.toList(); wanted.clear(); w.forEach { open(it) } }
    }
    fun detach(ref: WinRef, nav: SpatialNavigator) { if (navigators[ref] === nav) navigators.remove(ref) }

    fun isOpen(ref: WinRef): Boolean = state[ref].let { it != null && it != "ON_DESTROY" }
    /** Any window of this kind. */
    fun isOpen(win: Win): Boolean = state.any { it.key.win == win && it.value != "ON_DESTROY" }
    fun reopening(ref: WinRef): Boolean = ref in reopen

    /** What the system says about each window: has the focus / is on stage / is in the user's sight. */
    private val seen = HashMap<WinRef, Triple<Boolean, Boolean, Boolean>>()
    fun seen(ref: WinRef, focused: Boolean, onstage: Boolean, sighted: Boolean) {
        seen[ref] = Triple(focused, onstage, sighted)
        // K1: the user got to the window the home window pointed at
        if (focused && ref.tag == graph.backHint?.tag && SystemClock.elapsedRealtime() - (graph.backHint?.at ?: 0L) > 3000) { Log.i(TAG, "BACKHINT done: $ref has the focus"); graph.backHint = null }
        Log.i(TAG, "WINDOW $ref focused=$focused onstage=$onstage sighted=$sighted")
        BackKeys.sweepSoon("window $ref") // v0.4.2: its toolbar / ornament windows attach after the activity resumes
    }

    /** The tag an editor window shows when its bundle did not carry one: the oldest window of [win] still waiting for content. */
    fun claim(win: Win): String? = unclaimed[win]?.removeFirstOrNull()
    fun claimed(win: Win, tag: String) { unclaimed[win]?.remove(tag) }

    private val home = WinRef(Win.HOME)
    private val chart = WinRef(Win.CHART)
    private val stage = WinRef(Win.STAGE)

    private fun nav(): SpatialNavigator? = navigators[home] ?: navigators.values.lastOrNull()

    /** When each window last reported ON_RESUME since it was created (cleared on ON_DESTROY), for [chartGate]. */
    private val resumedAt = HashMap<WinRef, Long>()
    /** When open() last asked the system for each window (a window being opened is not [isOpen] yet). */
    private val askedAt = HashMap<WinRef, Long>()
    /** When the Full Space Stage last went away (the chart waits for the Shared Space windows to settle). */
    private var stageDestroyedAt: Long? = null

    fun onLifecycle(ref: WinRef, event: String) {
        state[ref] = event
        when (event) {
            "ON_CREATE" -> reopen.remove(ref)
            "ON_RESUME" -> {
                resumedAt[ref] = SystemClock.elapsedRealtime()
                // q4-1: a sheet is up: make the 3D chart's materials / meshes in the background, before anyone asks
                if (ref.win == Win.SHEET) { io.embodit.spatialoffice.ui.chart.ChartResources.prewarmPalette(graph); io.embodit.spatialoffice.ui.chart.ChartResources.prewarm(graph) }
            }
            "ON_DESTROY" -> { resumedAt.remove(ref); askedAt.remove(ref); if (ref.win == Win.STAGE) stageDestroyedAt = SystemClock.elapsedRealtime() }
        }
        Log.i(TAG, "WINDOW $ref $event")
        BackKeys.sweepSoon("window $ref") // v0.4.2: its toolbar / ornament windows attach after the activity resumes
        if (event == "ON_DESTROY" && !exiting && ref.editor && ref !in closedForStage && ref !in reopen) { ring.remove(Containers.key(ref)); graph.windowClosed(ref) }
    }

    fun open(win: Win) = open(WinRef(win))

    fun open(ref: WinRef) {
        if (exiting || ref.win == Win.HOME || ref.win == Win.STAGE) return
        if (graph.stageMode != null) return // windows stay closed while the Stage is up
        val n = nav() ?: run { Log.i(TAG, "WINDOW open $ref: waiting for a navigator"); wanted.add(ref); return }
        if (ref.win == Win.CHART) { openChart(); return }
        if (isOpen(ref)) { runCatching { n.restoreWindowContainer(Containers.id(ref.win), ref.tag) }; return }
        openNow(ref, n)
    }

    private fun openNow(ref: WinRef, n: SpatialNavigator) {
        if (askedAt[ref]?.let { SystemClock.elapsedRealtime() - it < 1500 } == true) { Log.i(TAG, "WINDOW open $ref: already asked"); return }
        askedAt[ref] = SystemClock.elapsedRealtime()
        if (ref.editor) {
            // in front of the user unless [arrange] asked for a place beside another window (Main.kt editorPlacement)
            val beside = besideNext.remove(ref)
            val trial = if (beside == null && Placing.mode.startsWith("gap:")) graph.sessions.lastOrNull { graph.refOf(it) != ref && graph.winOf(it.kind) == ref.win && isOpen(graph.refOf(it)) }?.let { Containers.key(graph.refOf(it)) to "right" } else null
            // v0.5.1 (user 2026-11-21): a new editor window goes beside the ones already up, on the circle around
            // the user (ArcRing), not in front of them; the first one opens in front. Windows brought back to the
            // front ([front] → reopenInFront) and [arrange] keep their own placement.
            // v0.5.2 (q14-1): the home window has a place of its own in the row, so the first file goes beside it.
            val arc = if (beside == null && trial == null && Placing.mode == "arc" && ref !in reopen) ring.place(Containers.key(ref), hub = Containers.HOME) { k -> ringLive(k) } else null
            if (arc != null || (beside == null && trial == null && Placing.mode == "arc" && ref !in reopen)) Log.i(TAG, "ARC $ref -> ${arc?.let { "${it.second} of ${it.first}" } ?: "front"} row=${ring.order}")
            Placing.next[Containers.id(ref.win)] = Placing.Next(ref.tag, (beside ?: trial ?: arc)?.first, (beside ?: trial ?: arc)?.second ?: "right")
            unclaimed.getOrPut(ref.win) { ArrayDeque() }.let { if (ref.tag !in it) it.addLast(ref.tag) }
        }
        Log.i(TAG, "WINDOW open $ref")
        runCatching { n.openWindowContainer(Containers.id(ref.win), ref.tag, Bundle().apply { putString(Containers.ARG_TAG, ref.tag) }) }
            .onFailure { Log.e(TAG, "WINDOW open failed", it) }
    }

    /**
     * Brings an editor window to the front (the file was opened again from Recent / Browse while it is open, or
     * picked in the [Windows] menu). A window the system closed is opened again. One that is up is restored through
     * the system; but a window another one was opened over is off stage (the system hides it, emulator 2026-11-18)
     * and `restoreWindowContainer` answers false for it, so such a window is closed and opened again in front. A
     * window that is on stage and in sight stays where the user put it.
     */
    fun front(ref: WinRef) {
        if (exiting || graph.stageMode != null) return
        if (!isOpen(ref)) { open(ref); return }
        val n = navigators[ref] ?: nav() ?: return
        val r = runCatching { n.restoreWindowContainer(Containers.id(ref.win), ref.tag) }
        val s = seen[ref]
        // v0.5.2 (q14-1): a window on stage but out of sight stands in its own place of the row; reopening it in
        // front would put it on the spot of the window being looked at and take that one off stage. Only a window
        // the system took off stage is opened again.
        val hidden = s == null || !s.second
        Log.i(TAG, "WINDOW front $ref restore=${r.getOrNull() ?: r.exceptionOrNull()} onstage=${s?.second} sighted=${s?.third} mode=$frontMode")
        if (frontMode == "reopen" || (frontMode == "auto" && r.getOrNull() != true && hidden)) reopenInFront(ref)
    }

    /** "auto" (default) | "restore" (never reopen) | "reopen" (always close and open again in front); debug command `front-mode`. */
    @Volatile var frontMode: String = "auto"

    private fun reopenInFront(ref: WinRef, then: (() -> Unit)? = null) {
        val n = nav() ?: return
        reopen.add(ref)
        Log.i(TAG, "WINDOW $ref: closing to open it again ${besideNext[ref]?.let { "${it.second} of ${it.first}" } ?: "in front"}")
        runCatching { n.closeWindowContainer(Containers.id(ref.win), ref.tag) }
        var tries = 0
        val tick = object : Runnable {
            override fun run() {
                if (exiting || graph.stageMode != null) { reopen.remove(ref); return }
                if (isOpen(ref) && tries++ < 30) { main.postDelayed(this, 100); return }
                askedAt.remove(ref)
                if (graph.sessionByTag(ref.tag) != null) nav()?.let { openNow(ref, it) } else reopen.remove(ref)
                if (then != null) waitResumed(ref, then)
            }
        }
        main.postDelayed(tick, 150)
    }

    /** Runs [then] once [ref] has been up for 600 ms (so the system lists it for the next placement), or after 4 s. */
    private fun waitResumed(ref: WinRef, then: () -> Unit) {
        val t0 = SystemClock.elapsedRealtime()
        val tick = object : Runnable {
            override fun run() {
                val now = SystemClock.elapsedRealtime()
                val up = resumedAt[ref]?.let { isOpen(ref) && now - it >= 600 } ?: false
                if (up || now - t0 > 4000 || exiting) then() else main.postDelayed(this, 100)
            }
        }
        main.postDelayed(tick, 100)
    }

    /** v0.5.1: the row of editor windows on the circle around the user (left to right). */
    val ring = io.embodit.spatialoffice.core.layout.ArcRing()
    /** A window of the row is still there: open, or asked for in the last 3 s (not up yet). */
    private fun ringLive(key: String): Boolean {
        if (key == Containers.HOME) return isOpen(home) || (homeAskedAt?.let { SystemClock.elapsedRealtime() - it < 3000 } ?: false)
        val ref = (state.keys + askedAt.keys).firstOrNull { Containers.key(it) == key } ?: return false
        return isOpen(ref) || (askedAt[ref]?.let { SystemClock.elapsedRealtime() - it < 3000 } ?: false)
    }

    /** Where the next opening of a window goes when [arrange] set it beside another: window key and side. */
    private val besideNext = HashMap<WinRef, Pair<String, String>>()

    /**
     * [Windows] › [Side by side]: the other open files' windows are set out beside [center], to its right and left in
     * turn, each next to the one before it on that side. The system only places a window when it opens, so each one
     * is closed and opened again there (its file stays open; nothing is lost). They end up on the circle around the
     * user, outside the default view: this is something the user asks for, never done on its own.
     */
    fun arrange(center: WinRef) {
        if (exiting || graph.stageMode != null || !isOpen(center)) return
        val others = graph.sessions.map { graph.refOf(it) }.filter { it != center }
        // the 3D chart is placed over its sheet when it opens: when that sheet moves to the side, the chart is put
        // away first and opened again over the sheet's new place at the end
        val chartMoves = isOpen(chart) && chartOn != null && chartOn in others
        if (chartMoves) { reopen.add(chart); runCatching { nav()?.closeWindowContainer(Containers.CHART, Containers.TAG_MAIN) } }
        Log.i(TAG, "ARRANGE side by side around $center: $others chartMoves=$chartMoves")
        var right = center; var left = center
        val steps = ArrayList<() -> Unit>()
        others.forEachIndexed { i, ref ->
            val side = if (i % 2 == 0) "right" else "left"
            val anchor = if (side == "right") right else left
            if (side == "right") right = ref else left = ref
            steps.add {
                besideNext[ref] = Containers.key(anchor) to side
                val next: () -> Unit = { steps.getOrNull(i + 1)?.invoke() }
                if (isOpen(ref)) reopenInFront(ref, next) else { askedAt.remove(ref); nav()?.let { openNow(ref, it) }; waitResumed(ref, next) }
            }
        }
        // last, the window they stand around: back in front if another one had been opened over it
        steps.add {
            val s = seen[center]
            if (s == null || !s.second) { Log.i(TAG, "ARRANGE: $center back in front"); reopenInFront(center) }
            if (chartMoves) { Log.i(TAG, "ARRANGE: chart back over $chartOn"); openChartWhenSheetIsUp() }
        }
        steps.firstOrNull()?.invoke()
    }

    /**
     * The home window to the front. It is the app's default container; once an editor opened over it the system
     * keeps it off stage, so it is closed and opened again (the same way it comes back after the Stage).
     */
    fun frontHome() {
        if (exiting || graph.stageMode != null) return
        val n = navigators[home] ?: nav() ?: return
        val s = seen[home]
        val r = if (isOpen(home)) runCatching { n.restoreWindowContainer() }.getOrNull() else null
        Log.i(TAG, "WINDOW front HOME open=${isOpen(home)} restore=$r onstage=${s?.second} sighted=${s?.third} row=${ring.order}")
        // v0.5.2 (QA #14 q14-1): the home window stands in its own place of the row (ArcRing hub). While it is on
        // stage it stays there, also when it is out of sight: reopening it would put it in front of the user, on
        // the spot of the file window being looked at, and the system would take that file off stage.
        if (isOpen(home) && (r == true || (s != null && s.second))) return
        if (isOpen(home)) {
            // an editor's navigator closes and reopens it; the app always has that editor window meanwhile
            val other = navigators.entries.firstOrNull { it.key.editor && isOpen(it.key) }?.value ?: return
            reopen.add(home)
            runCatching { n.closeWindowContainer() }.onFailure { Log.w(TAG, "close home: $it") }
            var tries = 0
            val tick = object : Runnable {
                override fun run() {
                    if (exiting) return
                    if (isOpen(home) && tries++ < 30) { main.postDelayed(this, 100); return }
                    reopen.remove(home)
                    reopenHome(other)
                }
            }
            main.postDelayed(tick, 150)
        } else nav()?.let { reopenHome(it) }
    }

    /**
     * v0.2.3 (QA #3 q3-2): the chart volume is placed on top of its sheet window, and the system lists the sheet
     * among the placement containers only once it is up. After a rehearsal v0.2.2 opened the chart 90 ms after the
     * sheet and it landed in front of the user, over the grid. Every path (menu, debug command, reopen after the
     * Stage) now waits here: [ChartOpenGate] opens the chart when the sheet has been resumed for 800 ms (and the Stage
     * has been gone 2.5 s), opens the sheet first if it is closed, and drops the chart if the sheet never comes up.
     * v0.2.5: "the sheet" is the window of the spreadsheet the chart was asked from ([Graph.chartSheet]).
     */
    private val chartGate = ChartOpenGate()
    private fun chartSheetRef(): WinRef? = graph.chartSheet?.let { graph.refOf(it) }
    private val chartTick = object : Runnable {
        override fun run() {
            if (exiting || graph.stageMode != null) { Log.i(TAG, "CHART gate cancelled"); chartGate.step(Long.MAX_VALUE / 2, false, false, null); return }
            val now = SystemClock.elapsedRealtime()
            val sheet = chartSheetRef()
            // the volume is moving to another sheet: wait until the old one is gone
            if (isOpen(chart) && chart in reopen) { main.postDelayed(this, 100); return }
            val sheetOpen = sheet != null && (isOpen(sheet) || (askedAt[sheet]?.let { now - it < 3000 } ?: false))
            val stageGone = if (state[stage] == "ON_DESTROY") stageDestroyedAt else null
            when (val act = chartGate.step(now, sheet != null, sheetOpen, sheet?.let { r -> resumedAt[r].takeIf { isOpen(r) } }, stageGone)) {
                ChartOpenGate.Act.WAIT -> main.postDelayed(this, 100)
                ChartOpenGate.Act.OPEN_SHEET -> { Log.i(TAG, "CHART gate: sheet window not open, opening it first"); sheet?.let { open(it) }; main.postDelayed(this, 100) }
                ChartOpenGate.Act.OPEN_CHART -> {
                    Log.i(TAG, "CHART gate open: sheet $sheet resumed ${now - (resumedAt[sheet] ?: now)} ms ago")
                    reopen.remove(chart)
                    if (!isOpen(chart)) nav()?.let { openNow(chart, it) }
                }
                ChartOpenGate.Act.DROP -> { reopen.remove(chart); Log.w(TAG, "CHART gate drop: no sheet window (file=${sheet != null}); the chart is not opened in front of the user") }
            }
        }
    }

    /** v0.2.4 (q4-1): when the chart was last asked for (uptime ms), for the logged open time (asked → built). */
    @Volatile var chartAskedAt = 0L
        private set
    /** The sheet window the open chart volume stands on. */
    private var chartOn: WinRef? = null

    /** [3D chart] of the spreadsheet in [Graph.chartSheet]: opens the volume over that sheet's window, moving it there if it is over another. */
    fun openChart() {
        if (exiting || graph.stageMode != null) return
        val sheet = chartSheetRef() ?: return
        if (isOpen(chart) && chartOn == sheet) { runCatching { nav()?.restoreWindowContainer(Containers.CHART, Containers.TAG_MAIN) }; return }
        if (isOpen(chart)) {
            Log.i(TAG, "CHART moves from $chartOn to $sheet")
            reopen.add(chart)
            runCatching { nav()?.closeWindowContainer(Containers.CHART, Containers.TAG_MAIN) }
        }
        chartOn = sheet
        io.embodit.spatialoffice.ChartPlace.sheetKey = Containers.key(sheet)
        openChartWhenSheetIsUp()
    }

    private fun openChartWhenSheetIsUp() {
        if (chartOn == null) chartOn = chartSheetRef()
        chartOn?.let { io.embodit.spatialoffice.ChartPlace.sheetKey = Containers.key(it) }
        if (chartGate.waiting) { Log.i(TAG, "CHART gate already waiting"); return }
        chartAskedAt = SystemClock.uptimeMillis()
        // q4-1: make the chart's materials / meshes in the background while the gate and the system bring the volume up
        io.embodit.spatialoffice.ui.chart.ChartResources.prewarm(graph)
        val sheet = chartSheetRef()
        Log.i(TAG, "CHART gate ask sheet=$sheet ${sheet?.let { state[it] }} resumedAt=${sheet?.let { resumedAt[it] }}")
        chartGate.ask(SystemClock.elapsedRealtime())
        main.removeCallbacks(chartTick)
        main.post(chartTick)
    }

    /**
     * Gives [ref] the window focus back (the system Toolbar and dialogs are windows of their own: after a toolbar
     * menu the keyboard goes to the toolbar, not to the cell that just took the caret). Uses the system's restore.
     */
    fun focus(ref: WinRef) {
        if (!isOpen(ref)) return
        val n = navigators[ref] ?: nav() ?: return
        val r = runCatching { n.restoreWindowContainer(Containers.id(ref.win), ref.tag) }
        Log.i(TAG, "WINDOW focus $ref restore=${r.getOrNull() ?: r.exceptionOrNull()}")
    }

    fun close(ref: WinRef) {
        if (ref.win == Win.HOME || ref.win == Win.STAGE || !isOpen(ref)) return
        Log.i(TAG, "WINDOW close $ref")
        if (ref.win == Win.CHART) chartOn = null
        runCatching { nav()?.closeWindowContainer(Containers.id(ref.win), ref.tag) }.onFailure { Log.w(TAG, "WINDOW close: $it") }
    }

    /** Every editor window and the chart that is up, the chart first. */
    private fun openOnes(): List<WinRef> = state.filter { it.value != "ON_DESTROY" && (it.key.editor || it.key.win == Win.CHART) }.keys
        .sortedBy { if (it.win == Win.CHART) 0 else 1 }

    /**
     * Full Space keeps this app's own windows up, and they would stand in the middle of the spread layout. The SDK's
     * minimize is refused for them (emulator 6.3: returns false), so they are closed here and reopened on leaving.
     */
    fun enterStage() {
        val n = nav() ?: return
        closedForStage.clear()
        // marked before the system reports them destroyed, so their files are not taken for closed by the user
        closedForStage.addAll(openOnes())
        graph.scope.launch {
            val r = runCatching { n.openStage(Containers.STAGE, StageStyle.Mixed) }
            Log.i(TAG, "STAGE open result=${r.getOrNull() ?: r.exceptionOrNull()}")
            if (r.isFailure) { closedForStage.clear(); return@launch }
            // The windows stay up until the Stage has something to show (it takes the system 2–3 s to bring the Stage up and
            // bind its panels); closing them at once left an empty room for that long. They go when the pieces start to
            // deal out ([stageReady]), or after 3 s whatever happens.
            closeForStage = {
                for (w in closedForStage.toList()) runCatching { n.closeWindowContainer(Containers.id(w.win), w.tag) }
                if (isOpen(home)) { closedForStage.add(home); runCatching { (navigators[home] ?: n).closeWindowContainer() }.onFailure { Log.w(TAG, "close home: $it") } }
                Log.i(TAG, "STAGE entered, closed windows=$closedForStage")
            }
            main.postDelayed({ stageReady("3 s passed") }, 3000)
        }
    }

    private var closeForStage: (() -> Unit)? = null

    /** How long the Stage's panels take to gather before the home window comes back (ms); StageScene.EXIT_MS is the same span. */
    private val GATHER_MS = 300L

    /** The Stage's first panels are on their way (called from the scene's frame thread, or by the timeout): the windows behind them go now. */
    fun stageReady(why: String) {
        main.post {
            val close = closeForStage ?: return@post
            closeForStage = null
            Log.i(TAG, "STAGE ready ($why): closing the windows it replaces")
            close()
        }
    }

    private val closedForStage = LinkedHashSet<WinRef>()

    fun leaveStage(back: WinRef?) {
        val n = navigators[stage] ?: nav() ?: return
        // windows that never got closed (the Stage was left before it was ready) are not reopened twice
        closeForStage?.let { closeForStage = null; closedForStage.clear() }
        // 1. the panels gather into one stack (StageScene, GATHER_MS); 2. the home window comes back, so the app is never
        // left without a container; 3. the Stage closes. Reopening the home window at once made the gathering stutter.
        main.postDelayed({
            reopenHome(n)
            main.postDelayed({
                graph.scope.launch {
                    runCatching { n.closeStage() }.onFailure { Log.w(TAG, "STAGE close: $it") }
                    stageClosed(back)
                }
            }, 350)
        }, GATHER_MS)
    }

    private fun reopenHome(n: SpatialNavigator) {
        if (isOpen(home)) return
        Log.i(TAG, "WINDOW reopen HOME")
        homeAskedAt = SystemClock.elapsedRealtime()
        val ok = runCatching { n.openWindowContainer(Containers.HOME, homeTag, null) }.onFailure { Log.w(TAG, "reopen home by id failed: $it") }.isSuccess
        if (!ok) runCatching {
            val i = graph.app.packageManager.getLaunchIntentForPackage(graph.app.packageName)
            graph.app.startActivity(i?.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    /** When [reopenHome] last asked for the home window (it counts as up in the row for 3 s). */
    private var homeAskedAt: Long? = null

    /** Tag the system gave the default container (learned from the placement context; empty until then). */
    var homeTag: String = ""

    /**
     * Reopens what was closed for the Stage, one window after another. Every window opens on the same spot in front
     * and the system hides the one underneath, so the order decides what the user sees: the other files first, then
     * [back] (the file that was spread out / rehearsed), on top. When the 3D chart was up, its sheet goes last
     * instead, so the sheet is the one in view, and the chart comes back after it, over it (v0.2.3 q3-2, v0.2.4 q4-2;
     * with one window of each kind this is the order v0.2.4 had after a rehearsal: slides, sheet, chart). v0.3.0: the
     * windows open at once, the first one too (profile D9 / D10: a window never waits for the head to be level).
     */
    fun stageClosed(back: WinRef?) {
        val chartWas = chart in closedForStage
        val editors = closedForStage.filter { it.editor && graph.sessionByTag(it.tag) != null }
        // v0.6.0 (K1, QA #15): the windows come back on the sides of the home window they stood on, and the file just
        // rehearsed / spread out takes the place next to the home window (ArcRing.replan); before, they came back in
        // the order they had opened (sheet and document changed sides) and the rehearsed one ended up ~126° away.
        val before = ring.order
        val plan = if (Placing.mode == "arc") ring.replan(before, Containers.HOME, editors.map { Containers.key(it) }, back?.let { Containers.key(it) }) else emptyList()
        val byKey = editors.associateBy { Containers.key(it) }
        // windows without a place in the row open in front, after the row; the one just rehearsed / spread out last, on top
        val unplaced = editors.filter { e -> plan.none { it.first == Containers.key(e) } }
        val ordered = if (plan.isNotEmpty()) plan.mapNotNull { byKey[it.first] } + unplaced.filter { it != back } + unplaced.filter { it == back } else editors.filter { it != back } + listOfNotNull(back?.takeIf { it in editors })
        val order = ordered + if (chartWas) listOf(chart) else emptyList()
        Log.i(TAG, "STAGE closed, reopening windows closed=$closedForStage back=$back order=$order row-before=$before plan=$plan")
        // the home window says where the window just rehearsed went (it is beside it, not in front of the user)
        val backPlace = back?.let { b -> plan.firstOrNull { it.first == Containers.key(b) } }
        val backSession = back?.let { graph.sessionByTag(it.tag) }
        graph.backHint = if (backPlace != null && backSession != null) Graph.BackHint(back.tag, backSession.ref.name, backPlace.second.second == "right", SystemClock.elapsedRealtime()) else null
        if (graph.backHint != null) Log.i(TAG, "BACKHINT ${graph.backHint}")
        nav()?.let { reopenHome(it) }
        closedForStage.clear()
        main.postDelayed({
            val first = order.firstOrNull()
            if (first != null && first.editor && !isOpen(first)) { Log.i(TAG, "STAGE reopen $first"); open(first); reopenOthers(order.drop(1)) }
            else reopenOthers(order)
        }, 600)
    }

    /** The other windows, one after another (each opens over the one before it). */
    private fun reopenOthers(list: List<WinRef>) {
        list.forEachIndexed { i, w ->
            main.postDelayed({
                if (w.win == Win.CHART) { if (graph.chartSheet != null && !isOpen(w)) { Log.i(TAG, "STAGE reopen $w"); openChartWhenSheetIsUp() } }
                else if (graph.sessionByTag(w.tag) != null && !isOpen(w)) { Log.i(TAG, "STAGE reopen $w"); open(w) }
            }, 700L * (i + 1))
        }
    }

    /** Exit: every window container this app has up, then the home window (profile D44: none may stay in recents). */
    fun closeAll() {
        exiting = true
        val n = nav() ?: return
        if (graph.stageMode != null) graph.scope.launch { runCatching { n.closeStage() } }
        val all = (state.keys.filter { it.editor || it.win == Win.CHART } + askedAt.keys.filter { it.editor || it.win == Win.CHART }).distinct()
        for (w in all) runCatching { n.closeWindowContainer(Containers.id(w.win), w.tag) }.onFailure { Log.w(TAG, "EXIT close $w: $it") }
        runCatching { (navigators[home] ?: n).closeWindowContainer() }.onFailure { Log.w(TAG, "EXIT close home: $it") }
        Log.i(TAG, "EXIT closed all window containers: $all + HOME")
    }

    fun dump(): String = state.entries.sortedBy { it.key.toString() }.joinToString(",") { "${it.key}=${it.value}" }
}
