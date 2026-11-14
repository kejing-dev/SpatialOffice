package io.embodit.spatialoffice.platform

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.mutableStateMapOf
import com.pico.spatial.ui.platform.containers.SpatialNavigator
import com.pico.spatial.ui.platform.containers.StageStyle
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
 * minimizes the app's windows (they would stand in the middle of the spread layout) and leaving restores them.
 */
class WindowCtl(private val graph: Graph) {
    private val navigators = LinkedHashMap<Win, SpatialNavigator>()
    /** Lifecycle of each container as last reported by its content ("ON_RESUME", "ON_DESTROY", …). */
    val state = mutableStateMapOf<Win, String>()
    private val minimized = LinkedHashSet<Win>()
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

    fun onLifecycle(win: Win, event: String) {
        state[win] = event
        Log.i(TAG, "WINDOW ${win.name} $event")
        if (event == "ON_DESTROY" && !exiting) graph.windowClosed(win)
    }

    fun open(win: Win) {
        if (exiting || win == Win.HOME || win == Win.STAGE) return
        if (graph.stageMode != null && win != Win.CHART) return // editors stay minimized while the Stage is up
        val n = nav() ?: run { Log.i(TAG, "WINDOW open ${win.name}: waiting for a navigator"); wanted.add(win); return }
        if (isOpen(win)) { runCatching { n.restoreWindowContainer(Containers.id(win), Containers.TAG_MAIN) }; return }
        Log.i(TAG, "WINDOW open ${win.name}")
        runCatching { n.openWindowContainer(Containers.id(win), Containers.TAG_MAIN, null) }.onFailure { Log.e(TAG, "WINDOW open failed", it) }
    }

    fun close(win: Win) {
        if (win == Win.HOME || win == Win.STAGE || !isOpen(win)) return
        Log.i(TAG, "WINDOW close ${win.name}")
        runCatching { nav()?.closeWindowContainer(Containers.id(win), Containers.TAG_MAIN) }.onFailure { Log.w(TAG, "WINDOW close: $it") }
    }

    fun enterStage() {
        val n = nav() ?: return
        minimized.clear()
        for (w in listOf(Win.CHART, Win.DOC, Win.SHEET, Win.SLIDES)) if (isOpen(w)) {
            val ok = runCatching { n.minimizeWindowContainer(Containers.id(w), Containers.TAG_MAIN) }.getOrDefault(false)
            Log.i(TAG, "WINDOW minimize ${w.name} ok=$ok")
            if (ok) minimized.add(w) else runCatching { n.closeWindowContainer(Containers.id(w), Containers.TAG_MAIN) }
        }
        val homeMin = runCatching { n.minimizeWindowContainer() }.getOrDefault(false)
        Log.i(TAG, "WINDOW minimize HOME ok=$homeMin")
        graph.scope.launch {
            runCatching { n.openStage(Containers.STAGE, StageStyle.Mixed) }
                .onSuccess { Log.i(TAG, "STAGE open result=$it") }
                .onFailure { Log.e(TAG, "STAGE open failed", it) }
        }
    }

    fun leaveStage(back: Win) {
        val n = navigators[Win.STAGE] ?: nav() ?: return
        graph.scope.launch {
            runCatching { n.closeStage() }.onFailure { Log.w(TAG, "STAGE close: $it") }
            stageClosed(back)
        }
    }

    /** Restores what was minimized. An editor that had to be closed instead is reopened through the level gate. */
    fun stageClosed(back: Win) {
        Log.i(TAG, "STAGE closed, restoring windows")
        val home = navigators[Win.HOME] ?: nav() ?: return
        runCatching { home.restoreWindowContainer() }
        for (w in minimized) runCatching { home.restoreWindowContainer(Containers.id(w), Containers.TAG_MAIN) }
        minimized.clear()
        main.postDelayed({
            if (!isOpen(back) && graph.session(back) != null) {
                // a window opened while the head is pitched can come up tilted: wait for a level head (LevelGate),
                // at most 3 s, or 0.6 s when no head pose is available outside the Stage
                gate.ask("reopen-${back.name}", SystemClock.elapsedRealtime())
                gateWin = back
                main.post(gateTick)
            }
        }, 400)
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
