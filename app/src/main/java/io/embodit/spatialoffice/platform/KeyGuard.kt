package io.embodit.spatialoffice.platform

import android.util.Log
import android.view.KeyEvent
import android.view.View
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalView

/**
 * Controller / keyboard keys that must never press a window button (profile D60, QA #11 q11-1: A / Enter / centre on a
 * focused button added blank slides and answered "Recover unsaved changes?" with [No thanks]).
 *
 * Two layers, both needed:
 *  1. every button, menu entry, list row, switch and segment of the app is not focusable (`Modifier.noKeyFocus()` in
 *     ui/Common.kt), so a key has nothing to press even after the button was tapped;
 *  2. the window callback that already catches BACK / B ([BackKeys.Wrapped]) eats the keys that have no function here,
 *     before any view sees them: every gamepad button except B, D-pad centre, and Enter / Space unless a typing
 *     target of THAT window has focus (a paragraph, a cell, a text box, a find field, the sheet grid). Ctrl / Alt / Tab /
 *     arrows / letters pass untouched, so keyboard shortcuts work as before.
 * BACK and controller B stay with [BackKeys] (checked first).
 */
object KeyGuard {
    enum class Verdict { PASS, EAT }

    const val KEYCODE_DPAD_CENTER = 23
    const val KEYCODE_ENTER = 66
    const val KEYCODE_SPACE = 62
    const val KEYCODE_NUMPAD_ENTER = 160
    const val KEYCODE_BUTTON_A = 96
    const val KEYCODE_BUTTON_B = 97
    const val KEYCODE_BUTTON_X = 99
    const val KEYCODE_BUTTON_Y = 100

    /** Gamepad buttons (KeyEvent.isGamepadButton without android.view): BUTTON_A..BUTTON_MODE and BUTTON_1..BUTTON_16. */
    fun isGamepadButton(code: Int) = code in 96..110 || code in 188..203

    /** Enter-like keys: a focused button would take them as a click. */
    fun isClickKey(code: Int) = code == KEYCODE_ENTER || code == KEYCODE_NUMPAD_ENTER || code == KEYCODE_SPACE

    /** Pure rule (KeyGuardTest). [typingHere] = a typing target of the window the key goes to has focus. */
    fun decide(code: Int, typingHere: Boolean): Verdict = when {
        code == KEYCODE_BUTTON_B -> Verdict.PASS // BACK handler ([BackKeys]) owns it
        isGamepadButton(code) -> Verdict.EAT
        code == KEYCODE_DPAD_CENTER -> Verdict.EAT
        isClickKey(code) -> if (typingHere) Verdict.PASS else Verdict.EAT
        else -> Verdict.PASS
    }

    /** Called from the window callback; true = consumed (nothing else sees the key). */
    fun eat(e: KeyEvent, root: View?, where: String): Boolean {
        val typing = TypingFocus.inWindow(root)
        if (decide(e.keyCode, typing) == Verdict.PASS) return false
        if (e.action == KeyEvent.ACTION_UP) Log.i(TAG, "KEYGUARD ate ${KeyEvent.keyCodeToString(e.keyCode)} at $where typing=$typing")
        return true
    }
}

/**
 * Which typing targets have focus, by the root view of their window (each window container has its own). A key goes to
 * one window; Enter passes only when a typing target of that same window is focused.
 */
object TypingFocus {
    private val focused = java.util.concurrent.ConcurrentHashMap<Any, View>()

    fun set(id: Any, root: View?, on: Boolean) {
        if (on && root != null) focused[id] = root else focused.remove(id)
    }

    fun inWindow(root: View?): Boolean = root != null && focused.values.any { it === root || it.rootView === root }

    fun any(): Boolean = focused.isNotEmpty()
}

/** Marks a text field / key-catching surface as a typing target: Enter and Space reach it while it has focus. */
fun Modifier.typingTarget(): Modifier = composed {
    val id = remember { Any() }
    val view = LocalView.current
    DisposableEffect(id) { onDispose { TypingFocus.set(id, null, false) } }
    onFocusChanged { TypingFocus.set(id, view.rootView, it.isFocused) }
}
