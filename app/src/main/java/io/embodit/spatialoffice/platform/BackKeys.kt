package io.embodit.spatialoffice.platform

import android.app.Activity
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.Window

/** What BACK / controller B does (profile D60): never exit, never send the app to the background. */
enum class BackAct { CLOSE_MENU, CLOSE_OVERLAY, DROP_PAINTER, CLOSE_REVIEW, REOPEN_HOME, NOTHING }

/**
 * v0.5.1 (self-test on the 6.2.1 image, where the dropdown menus open for the first time): the open toolbar menus.
 * BACK / controller B was caught by [BackKeys] before the menu's own window saw it, so a menu stayed open; now BACK
 * closes the newest open menu first. Main thread only (menus open and keys arrive there).
 */
object OpenMenus {
    private val open = ArrayList<Pair<Any, () -> Unit>>()
    val any: Boolean get() = open.isNotEmpty()
    fun opened(key: Any, dismiss: () -> Unit) { open.removeAll { it.first === key }; open.add(key to dismiss) }
    fun closed(key: Any) { open.removeAll { it.first === key } }
    /** Closes the newest open menu; false when none is open. */
    fun closeNewest(): Boolean { val m = open.removeLastOrNull() ?: return false; m.second(); return true }
}

/**
 * Pure rule, unit-tested (BackKeysTest): a dialog or sheet open = close it; the home window closed = bring it back; else nothing.
 * M5: B closes the topmost layer, in this order: menu, dialog / sheet, a held format painter, the review pane.
 */
object BackPolicy {
    fun decide(overlayOpen: Boolean, stageUp: Boolean, homeOpen: Boolean, menuOpen: Boolean = false, painterHeld: Boolean = false, reviewOpen: Boolean = false): BackAct = when {
        menuOpen -> BackAct.CLOSE_MENU
        overlayOpen -> BackAct.CLOSE_OVERLAY
        painterHeld -> BackAct.DROP_PAINTER
        reviewOpen && !stageUp -> BackAct.CLOSE_REVIEW
        stageUp -> BackAct.NOTHING // the Stage has its own [Collapse]; B there does not leave it
        !homeOpen -> BackAct.REOPEN_HOME
        else -> BackAct.NOTHING
    }
}

/**
 * BACK and controller B (KEYCODE_BUTTON_B) are caught at the earliest layer of every window of the app and all go to
 * one handler ([handler], set by SpatialApplication → Graph.onBack). Ported from the factory's reference (glow-bench
 * v0.1.3 / mri-rehearsal BackKeys): an OnBackPressedCallback alone is not enough, after a tap on a system Toolbar /
 * menu window the key goes into that window and never reaches the activity's dispatcher. Layers:
 *  1. every Activity window: its Window.Callback is wrapped, dispatchKeyEvent sees BACK / B before any view;
 *  2. every other root view of the process (Toolbar, menus, sheets the SDK adds through WindowManager): an
 *     unhandled-key listener and, where reflection allows, the same callback wrapper; swept on activity events;
 *  3. an OnBackPressedCallback on every ComponentActivity stays as the last fallback (SpatialApplication).
 * Escape is NOT treated as BACK here: it cancels cell editing and closes menus in the editors (keyboard-first app).
 * DOWN is consumed silently, UP fires the handler once; a second layer seeing the same press (same downTime) is dropped.
 */
object BackKeys {
    @Volatile var handler: (String) -> Unit = {}

    private var lastFiredDownTime = -1L

    const val KEYCODE_BACK = 4
    const val KEYCODE_BUTTON_B = 97
    const val ACTION_UP = 1

    fun isBack(code: Int) = code == KEYCODE_BACK || code == KEYCODE_BUTTON_B

    fun route(e: KeyEvent, where: String): Boolean = route(e.keyCode, e.action, e.downTime, e.isCanceled, where)

    /** Same as [route] with the raw fields (unit-tested without android.view.KeyEvent). */
    fun route(code: Int, action: Int, downTime: Long, canceled: Boolean, where: String): Boolean {
        if (!isBack(code)) return false
        if (action == ACTION_UP && !canceled) fire(downTime, "$where ${if (code == KEYCODE_BACK) "BACK" else "BUTTON_B"}")
        return true
    }

    @Synchronized
    internal fun reset() { lastFiredDownTime = -1L }

    @Synchronized
    private fun fire(downTime: Long, where: String) {
        if (downTime == lastFiredDownTime) return
        lastFiredDownTime = downTime
        handler(where)
    }

    /** Layer 1: wrap the Activity window's callback (idempotent). */
    fun wrap(a: Activity) {
        val w = a.window ?: return
        val cb = w.callback ?: return
        if (cb is Wrapped) return
        w.callback = Wrapped(cb, a.javaClass.simpleName) { w.peekDecorView() }
        Log.i(TAG, "BACK keys wrapped on ${a.javaClass.simpleName}")
    }

    /** BACK / B go to [handler]; keys with no function here (A / X / Y, centre, Enter outside a typing target) are eaten ([KeyGuard]). */
    private class Wrapped(private val d: Window.Callback, private val name: String, private val root: () -> View?) : Window.Callback by d {
        override fun dispatchKeyEvent(event: KeyEvent): Boolean =
            route(event, "key:$name") || KeyGuard.eat(event, root(), "key:$name") || d.dispatchKeyEvent(event)
    }

    private val hooked = java.util.Collections.newSetFromMap(java.util.WeakHashMap<View, Boolean>())
    private val main by lazy { android.os.Handler(android.os.Looper.getMainLooper()) }
    @Volatile private var lastSweepMs = 0L

    /**
     * Layer 2 again from any thread (a dialog, sheet or menu just opened, v0.4.2): the new window is attached a frame or
     * two later, so sweep after 250 ms and again after 1 s. Hooking is idempotent.
     */
    fun sweepSoon(reason: String) {
        lastSweepMs = android.os.SystemClock.elapsedRealtime()
        main.postDelayed({ sweep(reason) }, 250L)
        main.postDelayed({ sweep(reason) }, 1_000L)
    }

    /** Layer 2: every root view of this process (WindowManagerGlobal), so a focused Toolbar window cannot swallow BACK. */
    fun sweep(reason: String) {
        val roots = runCatching {
            val c = Class.forName("android.view.WindowManagerGlobal")
            val g = c.getMethod("getInstance").invoke(null)
            @Suppress("UNCHECKED_CAST")
            val names = c.getMethod("getViewRootNames").invoke(g) as Array<String>
            val byName = names.mapNotNull { n -> runCatching { c.getMethod("getRootView", String::class.java).invoke(g, n) as? View }.getOrNull()?.let { n to it } }
            // v0.4.2: on this image getViewRootNames can come back empty; the list of attached root views is the fallback
            byName.ifEmpty {
                runCatching {
                    val f = c.getDeclaredField("mViews")
                    f.isAccessible = true
                    @Suppress("UNCHECKED_CAST")
                    (f.get(g) as? List<View>).orEmpty().toList().map { v -> (v.javaClass.simpleName + "@" + Integer.toHexString(System.identityHashCode(v))) to v }
                }.getOrElse { emptyList() }
            }
        }.getOrElse { Log.w(TAG, "BACK sweep failed: $it"); emptyList() }
        for ((n, v) in roots) hook(v, n, reason)
    }

    private fun hook(v: View, name: String, reason: String) {
        synchronized(hooked) { if (!hooked.add(v)) return }
        val short = name.substringAfterLast('/').take(48)
        v.addOnUnhandledKeyEventListener { _, e -> route(e, "unhandled:$short") || KeyGuard.eat(e, v, "unhandled:$short") }
        val wrapped = runCatching {
            val f = v.javaClass.getDeclaredField("mWindow")
            f.isAccessible = true
            val w = f.get(v) as? Window ?: return@runCatching "no window"
            val cb = w.callback ?: return@runCatching "no callback"
            if (cb is Wrapped) return@runCatching "already"
            w.callback = Wrapped(cb, "root:$short") { v }
            "wrapped ${cb.javaClass.simpleName}"
        }.getOrElse { "not wrapped (${it.javaClass.simpleName})" }
        Log.i(TAG, "BACK keys hooked root $short ${v.javaClass.simpleName} ($reason) callback $wrapped")
    }
}
