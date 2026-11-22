package io.embodit.spatialoffice.platform

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import io.embodit.spatialoffice.core.files.FileKind
import io.embodit.spatialoffice.core.xlsx.XlsxWorkbook
import io.embodit.spatialoffice.ui.UrlKeyboard
import io.embodit.spatialoffice.ui.sheet.CellEdit
import io.embodit.spatialoffice.ui.sheet.CellKeyboard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v0.5.1 (QA #13): q13-1 a word typed into a cell is not carried into the next cell after Enter; q13-2 a link typed
 * with a capital scheme ("Https://...") opens, the address field has the URI keyboard. Also profile B28: web
 * addresses go to PICO Browser by package.
 */
class M3FixTest {
    private var now = 1_000L
    private fun sheet() = SheetSession(FileRef.Path(File("t.xlsx")), FileKind.SHEET, XlsxWorkbook.blank().save())
    private fun edit(s: SheetSession) = CellEdit().also { it.clock = { now }; it.load(s) }

    /** What typing one key does: the field reports its whole new text, as an input method does. */
    private fun type(e: CellEdit, text: String) {
        for (ch in text) {
            val t = (if (e.editing) e.value.text else "") + ch
            e.accept(e.generation, TextFieldValue(t, TextRange(t.length)))
            now += 120
        }
    }

    @Test fun enterCommitsAndTheNextCellStartsEmpty() {
        val s = sheet(); val e = edit(s)
        s.select(0, 8); e.load(s)
        for (w in listOf("abc", "de", "f")) { type(e, w); e.advance(s, 1, 0); now += 300 }
        assertEquals("abc", s.book.cell(0, 0, 8).input)
        assertEquals("de", s.book.cell(0, 1, 8).input)
        assertEquals("f", s.book.cell(0, 2, 8).input)
        assertEquals(3, s.row)
        assertEquals("", e.value.text)
        assertFalse(e.editing)
    }

    @Test fun anInputMethodFinishingTheOldWordIsDropped() {
        val s = sheet(); val e = edit(s)
        type(e, "abc")
        val old = e.generation
        e.advance(s, 1, 0)
        assertEquals(old + 1, e.generation)
        // the old field's connection writes its composing word once more: ignored
        assertFalse(e.accept(old, TextFieldValue("abc")))
        // the keyboard re-sends the whole word into the new connection right away, then the next key: only "d" stays
        assertTrue(e.accept(e.generation, TextFieldValue("abcd", TextRange(4))))
        assertEquals("d", e.value.text)
        e.advance(s, 1, 0)
        assertEquals("abc", s.book.cell(0, 0, 0).input)
        assertEquals("d", s.book.cell(0, 1, 0).input)
    }

    @Test fun theSameWordTypedLaterIsKept() {
        val s = sheet(); val e = edit(s)
        type(e, "abc"); e.advance(s, 1, 0)
        now += 2_000
        type(e, "abc"); e.advance(s, 1, 0)
        assertEquals("abc", s.book.cell(0, 1, 0).input)
    }

    @Test fun movingOntoAFilledCellDoesNotEatItsValue() {
        val s = sheet(); s.setCell(1, 0, "abc"); val e = edit(s)
        type(e, "abc"); e.advance(s, 1, 0)
        assertEquals("abc", e.value.text) // loaded, selected
        e.accept(e.generation, e.value.copy(selection = TextRange(3))) // a click puts the caret at the end
        assertEquals("abc", e.value.text)
    }

    @Test fun enterArrivingAsTextCommitsWithoutTheLineBreak() {
        val s = sheet(); val e = edit(s)
        type(e, "xyz")
        e.accept(e.generation, TextFieldValue("xyz\n"))
        e.advance(s, 1, 0)
        assertEquals("xyz", s.book.cell(0, 0, 0).input)
    }

    @Test fun cellAndAddressKeyboardsDoNotCapitalise() {
        assertEquals(KeyboardCapitalization.None, CellKeyboard.capitalization)
        assertFalse(CellKeyboard.autoCorrect)
        assertEquals(KeyboardCapitalization.None, UrlKeyboard.capitalization)
        assertEquals(KeyboardType.Uri, UrlKeyboard.keyboardType)
        assertFalse(UrlKeyboard.autoCorrect)
    }

    @Test fun schemesAreWrittenInLowerCase() {
        assertEquals("https://example.com/Project", DocSession.normalizeUrl("Https://example.com/Project"))
        assertEquals("http://Example.com", DocSession.normalizeUrl(" HTTP://Example.com "))
        assertEquals("mailto:a@b.c", DocSession.normalizeUrl("MAILTO:a@b.c"))
        assertEquals("https://www.example.com", DocSession.normalizeUrl("www.example.com"))
        assertEquals("mailto:x@y.z", DocSession.normalizeUrl("x@y.z"))
        assertEquals("", DocSession.normalizeUrl("  "))
    }

    @Test fun webAddressesGoToPicoBrowser() {
        val installed = setOf(BrowserPick.PICO_BROWSER, "com.android.webview")
        assertEquals(BrowserPick.PICO_BROWSER, BrowserPick.packageFor("https://accounts.example.com/authorize?x=1") { it in installed })
        assertEquals(BrowserPick.PICO_BROWSER, BrowserPick.packageFor("Https://example.com") { it in installed })
        assertNull(BrowserPick.packageFor("mailto:a@b.c") { it in installed })
        assertNull(BrowserPick.packageFor("https://example.com") { false }) // no PICO Browser: the system's choice
    }

    @Test fun backClosesTheNewestOpenMenuFirst() {
        assertEquals(BackAct.CLOSE_MENU, BackPolicy.decide(overlayOpen = true, stageUp = false, homeOpen = true, menuOpen = true))
        assertEquals(BackAct.CLOSE_OVERLAY, BackPolicy.decide(overlayOpen = true, stageUp = false, homeOpen = true, menuOpen = false))
        val closed = ArrayList<String>()
        val a = Any(); val b = Any()
        OpenMenus.opened(a) { closed += "a" }
        OpenMenus.opened(b) { closed += "b" }
        assertTrue(OpenMenus.any)
        assertTrue(OpenMenus.closeNewest())
        OpenMenus.closed(a)
        assertFalse(OpenMenus.any)
        assertFalse(OpenMenus.closeNewest())
        assertEquals(listOf("b"), closed)
    }
}
