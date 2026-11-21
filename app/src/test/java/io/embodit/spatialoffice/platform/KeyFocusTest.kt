package io.embodit.spatialoffice.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v0.4.2 (QA #11 q11-1, profile D60): controller A / X / Y, D-pad centre and Enter never press a window button.
 * KeyGuard eats the keys with no function at the window callback; every button-like control is not focusable.
 */
class KeyFocusTest {

    @Test
    fun gamepadButtonsAndCentreAreEatenEverywhere() {
        for (typing in listOf(false, true)) {
            for (code in listOf(KeyGuard.KEYCODE_BUTTON_A, KeyGuard.KEYCODE_BUTTON_X, KeyGuard.KEYCODE_BUTTON_Y, KeyGuard.KEYCODE_DPAD_CENTER, 102, 103, 108, 109, 188, 203)) {
                assertEquals("code $code typing=$typing", KeyGuard.Verdict.EAT, KeyGuard.decide(code, typing))
            }
        }
    }

    @Test
    fun enterOnlyReachesATypingTarget() {
        for (code in listOf(KeyGuard.KEYCODE_ENTER, KeyGuard.KEYCODE_NUMPAD_ENTER, KeyGuard.KEYCODE_SPACE)) {
            assertEquals(KeyGuard.Verdict.EAT, KeyGuard.decide(code, typingHere = false))
            assertEquals(KeyGuard.Verdict.PASS, KeyGuard.decide(code, typingHere = true))
        }
    }

    @Test
    fun backAndShortcutsPass() {
        // B belongs to BackKeys (checked first); Tab, arrows, letters, Ctrl / Shift, Escape, Delete are untouched
        for (code in listOf(KeyGuard.KEYCODE_BUTTON_B, 4, 61, 19, 20, 21, 22, 29, 31, 47, 52, 111, 112, 113, 59, 67)) {
            assertEquals("code $code", KeyGuard.Verdict.PASS, KeyGuard.decide(code, typingHere = false))
        }
    }

    @Test
    fun aThreeTimesAfterATapStillDoesNothing() {
        // the press sequence QA uses: A / X / Y / ENTER / CENTRE three times each on a window with no typing target
        var reached = 0
        repeat(3) {
            for (code in listOf(96, 99, 100, 66, 23)) if (KeyGuard.decide(code, typingHere = false) == KeyGuard.Verdict.PASS) reached++
        }
        assertEquals(0, reached)
    }

    private val src = File("src/main/java").takeIf { it.isDirectory } ?: File("app/src/main/java")

    private fun sources() = src.walkTopDown().filter { it.extension == "kt" }.toList()

    @Test
    fun everyClickableAndToggleableIsNotFocusable() {
        val bad = mutableListOf<String>()
        for (f in sources()) f.readLines().forEachIndexed { i, line ->
            Regex("""\.(clickable|toggleable|selectable|combinedClickable)\s*[({]""").findAll(line).forEach { m ->
                if (!line.substring(0, m.range.first).endsWith("noKeyFocus()")) bad += "${f.name}:${i + 1} ${line.trim()}"
            }
        }
        assertTrue("clickables without noKeyFocus(): $bad", bad.isEmpty())
    }

    @Test
    fun everyButtonMenuItemAndSegmentIsNotFocusable() {
        val bad = mutableListOf<String>()
        val call = Regex("""(?<![\w.])(Button|IconButton|ToggleIconButton|SegmentItem|MenuItem|menu\.MenuItem)\(""")
        for (f in sources()) {
            val t = f.readText()
            call.findAll(t).forEach { m ->
                if (t.substring(maxOf(0, m.range.first - 12), m.range.first).contains("fun ")) return@forEach
                // the call's own arguments (up to the matching parenthesis) must carry noKeyFocus()
                var depth = 0
                var end = m.range.last
                while (end < t.length) {
                    when (t[end]) { '(' -> depth++; ')' -> { depth--; if (depth == 0) break } }
                    end++
                }
                val args = t.substring(m.range.last, end)
                val head = args.substringBefore("{\n") // the modifier is given before the content lambda
                if (!args.contains("noKeyFocus()") && !head.contains("noKeyFocus()")) {
                    val line = t.substring(0, m.range.first).count { it == '\n' } + 1
                    bad += "${f.name}:$line ${m.value}"
                }
            }
        }
        assertTrue("buttons without noKeyFocus(): $bad", bad.isEmpty())
    }

    @Test
    fun windowCallbackRunsTheGuard() {
        val back = sources().first { it.name == "BackKeys.kt" }.readText()
        assertTrue(back.contains("KeyGuard.eat(event, root()"))
        assertTrue(back.contains("KeyGuard.eat(e, v,"))
    }
}
