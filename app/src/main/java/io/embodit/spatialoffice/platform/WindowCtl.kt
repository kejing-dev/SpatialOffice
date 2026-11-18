package io.embodit.spatialoffice.platform

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.mutableStateMapOf
import com.pico.spatial.ui.platform.containers.SpatialNavigator
import com.pico.spatial.ui.platform.containers.StageStyle
import io.embodit.spatialoffice.core.layout.ChartOpenGate
import io.embodit.spatialoffice.core.layout.LevelGate
import kotlinx.coroutines.launch

/** Container ids. HOME is the default container (manifest); the others are declared in Main.kt. */
object Containers {
    const val HOME = "SpatialOfficeHome"
    const val DOC = "SpatialOfficeDoc"
    const val SHEET = "SpatialOfficeSheet"
    const val SLIDES = "SpatialOfficeSlides"
    const val CHART = "SpatialOfficeChart"
    const val STAGE = "SpatialOfficeStage"
    const val TAG_MAIN = "main"

    fun id(win: Win): String = when (win) {
        Win.HOME -> HOME; Win.DOC -> DOC; Win.SHEET -> SHEET; Win.SLIDES -> SLIDES; Win.CHART -> CHART; Win.STAGE -> STAGE
    }
}

/**
 * Opens and closes the window containers and the Stage. Every editor window is declared with a placement beside
 * a window that already exists (Main.kt), so no window is ever placed from the head pose. Entering the Stage
 * closes the app's windows (they would stand in the middle of the spread layout) and leaving reopens them.
 */
class WindowCtl(private val graph: Graph) {
    private val navigators = LinkedHashMap<Win, SpatialNavigator>()
    /** Lifecycle of each container as last reported by its content ("ON_RESUME", "ON_DESTROY", …). */
    val state = mutableStateMapOf<Win, String>()
    private val main = Handler(Looper.getMainLooper())
    var exiting = false
        private set

    private val wanted = LinkedHashSet<Win>()

    fun attach(win: Win, nav: SpatialNavigator) {
        navigators[win] = nav
        // a file handed over at launch asks for its window before any navigator exists
        if (wanted.isNotEmpty()) main.post { val w = wanted.toList(); wanted.clear(); w.forEach { open(it) } }
    }
    fun detach(win: Win, nav: SpatialNavigator) { if (navigators[win] === nav) navigators.remove(win) }

    fun isOpen(win: Win): Boolean = state[win].let { it != null && it != "ON_DESTROY" }

    private fun nav(): SpatialNavigator? = navigators[Win.HOME] ?: navigators.values.lastOrNull()

    /** When each window last reported ON_RESUME since it was created (cleared on ON_DESTROY), for [chartGate]. */
    private val resumedAt = HashMap<Win, Long>()
    /** When open() last asked the system for each window (a window being opened is not [isOpen] yet). */
    private val askedAt = HashMap<Win, Long>()
    /** When the Full Space Stage last went away (the chart waits for the Shared Space windows to settle). */
    private var stageDestroyedAt: Long? = null

    fun onLifecycle(win: Win, event: String) {
        state[win] = event
        when (event) {
            "ON_RESUME" -> resumedAt[win] = SystemClock.elapsedRealtime()
            "ON_DESTROY" -> { resumedAt.remove(win); askedAt.remove(win); if (win == Win.STAGE) stageDestroyedAt = SystemClock.elapsedRealtime() }
        }
        Log.i(TAG, "WINDOW ${win.name} $event")
        if (event == "ON_DESTROY" && !exiting) graph.windowClosed(win)
    }

    fun open(win: Win) {
        if (exiting || win == Win.HOME || win == Win.STAGE) return
        if (graph.stageMode != null) return // windows stay closed while the Stage is up
        val n = nav() ?: run { Log.i(TAG, "WINDOW open ${win.name}: waiting for a navigator"); wanted.add(win); return }
        if (isOpen(win)) { runCatching { n.restoreWindowContainer(Containers.id(win), Containers.TAG_MAIN) }; return }
        if (win == Win.CHART) { openChartWhenSheetIsUp(); return }
        openNow(win, n)
    }

    private fun openNow(win: Win, n: SpatialNavigator) {
        askedAt[win] = SystemClock.elapsedRealtime()
        Log.i(TAG, "WINDOW open ${win.name}")
        runCatching { n.openWindowContainer(Containers.id(win), Containers.TAG_MAIN, null) }.onFailure { Log.e(TAG, "WINDOW open failed", it) }
    }

    /**
     * v0.2.3 (QA #3 q3-2): the chart volume is placed on top of the sheet window, and the system lists the sheet
     * among the placement containers only once it is up. After a rehearsal v0.2.2 opened the chart 90 ms after the
     * sheet and it landed in front of the user, over the grid. Every path (menu, debug command, reopen after the
     * Stage) now waits here: [ChartOpenGate] opens the chart when the sheet has been resumed for 800 ms (and the Stage
     * has been gone 2.5 s), opens the
     * sheet first if it is closed, and drops the chart if the sheet never comes up.
     */
    private val chartGate = ChartOpenGate()
    private val chartTick = object : Runnable {
        override fun run() {
            if (exiting || graph.stageMode != null) { Log.i(TAG, "CHART gate cancelled"); chartGate.step(Long.MAX_VALUE / 2, false, false, null); return }
            val now = SystemClock.elapsedRealtime()
            val sheetOpen = isOpen(Win.SHEET) || (askedAt[Win.SHEET]?.let { now - it < 3000 } ?: false)
            val stageGone = if (state[Win.STAGE] == "ON_DESTROY") stageDestroyedAt else null
            when (val act = chartGate.step(now, graph.sheet != null, sheetOpen, resumedAt[Win.SHEET].takeIf { isOpen(Win.SHEET) }, stageGone)) {
                ChartOpenGate.Act.WAIT -> main.postDelayed(this, 100)
                ChartOpenGate.Act.OPEN_SHEET -> { Log.i(TAG, "CHART gate: sheet window not open, opening it first"); open(Win.SHEET); main.postDelayed(this, 100) }
                ChartOpenGate.Act.OPEN_CHART -> {
                    Log.i(TAG, "CHART gate open: sheet resumed ${now - (resumedAt[Win.SHEET] ?: now)} ms ago")
                    if (!isOpen(Win.CHART)) nav()?.let { openNow(Win.CHART, it) }
                }
                ChartOpenGate.Act.DROP -> Log.w(TAG, "CHART gate drop: no sheet window (file=${graph.sheet != null}); the chart is not opened in front of the user")
            }
        }
    }

    private fun openChartWhenSheetIsUp() {
        if (chartGate.waiting) { Log.i(TAG, "CHART gate already waiting"); return }
        Log.i(TAG, "CHART gate ask sheet=${state[Win.SHEET]} resumedAt=${resumedAt[Win.SHEET]}")
        chartGate.ask(SystemClock.elapsedRealtime())
        main.removeCallbacks(chartTick)
        main.post(chartTick)
    }

    /**
     * Gives [win] the window focus back (the system Toolbar and dialogs are windows of their own: after a toolbar
     * menu the keyboard goes to the toolbar, not to the cell that just took the caret). Uses the system's restore.
     */
    fun focus(win: Win) {
        if (!isOpen(win)) return
        val n = navigators[win] ?: nav() ?: return
        val r = runCatching { n.restoreWindowContainer(Containers.id(win), Containers.TAG_MAIN) }
        Log.i(TAG, "WINDOW focus ${win.name} restore=${r.getOrNull() ?: r.exceptionOrNull()}")
    }

    fun close(win: Win) {
        if (win == Win.HOME || win == Win.STAGE || !isOpen(win)) return
        Log.i(TAG, "WINDOW close ${win.name}")
        runCatching { nav()?.closeWindowContainer(Containers.id(win), Containers.TAG_MAIN) }.onFailure { Log.w(TAG, "WINDOW close: $it") }
    }

    /**
     * Full Space keeps this app's own windows up, and they would stand in the middle of the spread layout. The SDK's
     * minimize is refused for them (emulator 6.3: returns false), so they are closed here and reopened on leaving.
     */
    fun enterStage() {
        val n = nav() ?: return
        closedForStage.clear()
        graph.scope.launch {
            val r = runCatching { n.openStage(Containers.STAGE, StageStyle.Mixed) }
            Log.i(TAG, "STAGE open result=${r.getOrNull() ?: r.exceptionOrNull()}")
            if (r.isFailure) return@launch
            for (w in listOf(Win.CHART, Win.DOC, Win.SHEET, Win.SLIDES)) if (isOpen(w)) {
                closedForStage.add(w)
                runCatching { n.closeWindowContainer(Containers.id(w), Containers.TAG_MAIN) }
            }
            if (isOpen(Win.HOME)) { closedForStage.add(Win.HOME); runCatching { (navigators[Win.HOME] ?: n).closeWindowContainer() }.onFailure { Log.w(TAG, "close home: $it") } }
            Log.i(TAG, "STAGE entered, closed windows=$closedForStage")
        }
    }

    private val closedForStage = LinkedHashSet<Win>()

    fun leaveStage(back: Win) {
        val n = navigators[Win.STAGE] ?: nav() ?: return
        // the home window comes back first, so the app is never left without a container
        reopenHome(n)
        main.postDelayed({
            graph.scope.launch {
                runCatching { n.closeStage() }.onFailure { Log.w(TAG, "STAGE close: $it") }
                stageClosed(back)
            }
        }, 350)
    }

    private fun reopenHome(n: SpatialNavigator) {
        if (isOpen(Win.HOME)) return
        Log.i(TAG, "WINDOW reopen HOME")
        val ok = runCatching { n.openWindowContainer(Containers.HOME, homeTag, null) }.onFailure { Log.w(TAG, "reopen home by id failed: $it") }.isSuccess
        if (!ok) runCatching {
            val i = graph.app.packageManager.getLaunchIntentForPackage(graph.app.packageName)
            graph.app.startActivity(i?.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    /** Tag the system gave the default container (learned from the placement context; empty until then). */
    var homeTag: String = ""

    /**
     * Reopens what was closed for the Stage: [back] first (through the level gate), then every other editor window
     * that was open before (v0.2.2; v0.2.1 brought back only [back], QA #2 known issue). The chart volume comes back
     * after the sheet, so its placement below the sheet has a sheet to stand under.
     */
    fun stageClosed(back: Win) {
        val others = closedForStage.filter { it != Win.HOME && it != back }
            .sortedBy { if (it == Win.CHART) 1 else 0 }
        Log.i(TAG, "STAGE closed, reopening windows closed=$closedForStage back=$back others=$others")
        nav()?.let { reopenHome(it) }
        closedForStage.clear()
        main.postDelayed({
            if (!isOpen(back) && graph.session(back) != null) {
                // a window opened while the head is pitched can come up tilted: wait for a level head (LevelGate),
                // at most 3 s, or 0.6 s when no head pose is available outside the Stage
                gate.ask("reopen-${back.name}", SystemClock.elapsedRealtime())
                gateWin = back
                gateThen = others
                main.post(gateTick)
            } else reopenOthers(others)
        }, 600)
    }

    private var gateThen: List<Win> = emptyList()

    /** The other windows, one after another (the system places each against those already open). */
    private fun reopenOthers(list: List<Win>) {
        list.forEachIndexed { i, w ->
            main.postDelayed({
                val wanted = w == Win.CHART && graph.sheet != null || w != Win.CHART && graph.session(w) != null
                if (wanted && !isOpen(w)) { Log.i(TAG, "STAGE reopen ${w.name}"); open(w) }
            }, 400L * (i + 1))
        }
    }

    private val gate = LevelGate()
    private var gateWin: Win? = null
    private val gateTick = object : Runnable {
        override fun run() {
            val why = gate.step(SystemClock.elapsedRealtime(), Head.sample()?.pitchDeg)
            if (why == null) { main.postDelayed(this, 50); return }
            Log.i(TAG, "LEVEL gate open why=$why")
            gateWin?.let { open(it) }
            gateWin = null
            val then = gateThen; gateThen = emptyList()
            reopenOthers(then)
        }
    }

    fun closeAll() {
        exiting = true
        val n = nav() ?: return
        if (graph.stageMode != null) graph.scope.launch { runCatching { n.closeStage() } }
        for (w in listOf(Win.CHART, Win.DOC, Win.SHEET, Win.SLIDES)) runCatching { n.closeWindowContainer(Containers.id(w), Containers.TAG_MAIN) }.onFailure { Log.w(TAG, "EXIT close ${w.name}: $it") }
        runCatching { (navigators[Win.HOME] ?: n).closeWindowContainer() }.onFailure { Log.w(TAG, "EXIT close home: $it") }
        Log.i(TAG, "EXIT closed all window containers")
    }

    fun dump(): String = Win.values().joinToString(",") { "${it.name}=${state[it] ?: "-"}" }
}
