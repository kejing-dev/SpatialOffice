package io.embodit.spatialoffice.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import io.embodit.spatialoffice.R
import io.embodit.spatialoffice.core.text.FontSizes

/**
 * M2: the colours and sizes the three editors' [Text ▾] / [Colour ▾] menus offer (one set for all three).
 * Text colours are ≥ 4.5:1 on white paper (ui-audit contrast table); highlights are Word's named marker colours.
 */
object TextPalette {
    /** Name → 0xRRGGBB. */
    val TEXT: List<Pair<Int, Int>> = listOf(
        R.string.cname_red to 0xC00000, R.string.cname_orange to 0xB45309, R.string.cname_green to 0x15803D,
        R.string.cname_blue to 0x0070C0, R.string.cname_purple to 0x7030A0, R.string.cname_gray to 0x595959,
    )
    /** Name → Word highlight name (documents only). */
    val HIGHLIGHT: List<Pair<Int, String>> = listOf(R.string.cname_yellow to "yellow", R.string.cname_green to "green", R.string.cname_cyan to "cyan")
    /** Shape fills and outlines (p22). */
    val SHAPE: List<Pair<Int, Int>> = listOf(
        R.string.cname_blue to 0x2563EB, R.string.cname_red to 0xDC2626, R.string.cname_orange to 0xEA580C, R.string.cname_green to 0x16A34A,
        R.string.cname_purple to 0x7C3AED, R.string.cname_gray to 0x6B7280, R.string.cname_white to 0xFFFFFF, R.string.cname_black to 0x111111,
    )
    /** Outline widths in points (p22). */
    val LINE_PT: List<Float> = listOf(1f, 2.25f, 4.5f)
    const val EMU_PER_PT = 12700L

    /** The colour a Word highlight name draws as (unknown names: light grey). */
    fun highlight(name: String?): Color? = when (name) {
        null -> null
        "yellow" -> Color(0xFFFFFF00); "green" -> Color(0xFF00FF00); "cyan" -> Color(0xFF00FFFF)
        "magenta" -> Color(0xFFFF00FF); "blue" -> Color(0xFF0000FF); "red" -> Color(0xFFFF0000)
        "darkYellow" -> Color(0xFF808000); "lightGray" -> Color(0xFFC0C0C0); "darkGray" -> Color(0xFF808080)
        else -> Color(0xFFD9D9D9)
    }

    fun rgb(v: Int): Color = Color(0xFF000000 or (v.toLong() and 0xFFFFFF))

    fun pt(v: Float): String = if (v == v.toInt().toFloat()) v.toInt().toString() else v.toString()
}

/**
 * Entries of a [Text ▾] menu: larger, smaller, the four fixed sizes, then [extra] (B / I / U, strikethrough, clear
 * formatting… as each editor has them). [sizeHp] = the selection's common size in half-points (null = mixed).
 */
@Composable
fun sizeEntries(tag: String, sizeHp: Int?, step: (Int) -> Unit, set: (Int) -> Unit): List<MenuEntry> =
    listOf(
        MenuEntry(tr(R.string.size_up), "$tag/size-up") { step(1) },
        MenuEntry(tr(R.string.size_down), "$tag/size-down") { step(-1) },
    ) + FontSizes.PRESETS_PT.map { pt -> MenuEntry(tr(R.string.size_pt, pt), "$tag/size-$pt", checked = sizeHp == pt * 2) { set(pt * 2) } }

/** Entries of a [Colour ▾] menu: six text colours, the highlights when [highlight] is given, clear colour. */
@Composable
fun colorEntries(tag: String, color: Int?, setColor: (Int?) -> Unit, hl: String? = null, highlight: ((String?) -> Unit)? = null): List<MenuEntry> =
    TextPalette.TEXT.map { (name, v) -> MenuEntry(tr(R.string.text_color, tr(name)), "$tag/color", checked = color == v) { setColor(v) } } +
        (if (highlight != null) TextPalette.HIGHLIGHT.map { (name, v) -> MenuEntry(tr(R.string.hl_color, tr(name)), "$tag/highlight", checked = hl == v) { highlight(v) } } else emptyList()) +
        listOf(MenuEntry(tr(R.string.color_clear), "$tag/color-clear") { setColor(null); highlight?.invoke(null) })
