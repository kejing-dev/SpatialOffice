package io.embodit.spatialoffice.core.text

/**
 * A stretch of text with one format. [src] = index of the run in the opened file whose other formatting it keeps (−1 = none).
 * M2 (v0.4.0): [color] text colour 0xRRGGBB (null = automatic / inherited), [highlight] marker colour by its Word name
 * ("yellow", "green", "cyan"; null = none; documents only), [size] font size in half-points (null = the paragraph's).
 */
data class Span(
    val text: String, val bold: Boolean = false, val italic: Boolean = false, val underline: Boolean = false, val src: Int = -1, val strike: Boolean = false,
    val color: Int? = null, val highlight: String? = null, val size: Int? = null,
)

enum class Attr { BOLD, ITALIC, UNDERLINE, STRIKE }

/**
 * Editing a paragraph as formatted spans while the text field only reports "the text is now X". Typing keeps the
 * format of the character before the caret; toggling a format touches only the selected range.
 */
object RichText {
    fun text(spans: List<Span>): String = spans.joinToString("") { it.text }

    /** Merges neighbours with the same format and drops empty spans. */
    fun normalize(spans: List<Span>): List<Span> {
        val out = ArrayList<Span>()
        for (s in spans) {
            if (s.text.isEmpty()) continue
            val last = out.lastOrNull()
            if (last != null && sameFormat(last, s)) out[out.size - 1] = last.copy(text = last.text + s.text) else out.add(s)
        }
        return out
    }

    /**
     * Result of replacing the paragraph's text by [newText]: the changed middle (between the common prefix and
     * suffix) is rewritten, taking the format of the character just before it (or just after, at the very start).
     */
    fun applyEdit(spans: List<Span>, newText: String): List<Span> {
        val old = text(spans)
        if (old == newText) return spans
        var p = 0
        val max = minOf(old.length, newText.length)
        while (p < max && old[p] == newText[p]) p++
        var s = 0
        while (s < max - p && old[old.length - 1 - s] == newText[newText.length - 1 - s]) s++
        // never split a surrogate pair
        if (p > 0 && Character.isHighSurrogate(old[p - 1])) p--
        if (s > 0 && Character.isLowSurrogate(old[old.length - s])) s--
        val inserted = newText.substring(p, newText.length - s)
        val fmt = formatAt(spans, if (p > 0) p - 1 else 0)
        val (left, rest) = split(spans, p)
        val (_, right) = split(rest, old.length - s - p)
        val mid = if (inserted.isEmpty()) emptyList() else listOf((fmt ?: Span("")).copy(text = inserted))
        return normalize(left + mid + right)
    }

    /** Sets or clears [attr] on characters [start, end). */
    fun setAttr(spans: List<Span>, start: Int, end: Int, attr: Attr, on: Boolean): List<Span> {
        if (end <= start) return spans
        val (left, rest) = split(spans, start)
        val (mid, right) = split(rest, end - start)
        return normalize(left + mid.map { with(it, attr, on) } + right)
    }

    /** True when every character in [start, end) has [attr]; for an empty range, the character before the caret. */
    fun hasAttr(spans: List<Span>, start: Int, end: Int, attr: Attr): Boolean {
        if (end <= start) return formatAt(spans, if (start > 0) start - 1 else 0)?.let { get(it, attr) } ?: false
        var pos = 0
        var any = false
        for (s in spans) {
            val a = pos
            val b = pos + s.text.length
            pos = b
            if (b <= start || a >= end || s.text.isEmpty()) continue
            any = true
            if (!get(s, attr)) return false
        }
        return any
    }

    /** Sets [attr] on the whole paragraph. */
    fun setAll(spans: List<Span>, attr: Attr, on: Boolean): List<Span> = normalize(spans.map { with(it, attr, on) })

    private fun formatAt(spans: List<Span>, index: Int): Span? {
        var pos = 0
        for (s in spans) {
            if (s.text.isEmpty()) continue
            if (index < pos + s.text.length) return s
            pos += s.text.length
        }
        return spans.lastOrNull { it.text.isNotEmpty() } ?: spans.lastOrNull()
    }

    private fun split(spans: List<Span>, at: Int): Pair<List<Span>, List<Span>> {
        val left = ArrayList<Span>()
        val right = ArrayList<Span>()
        var pos = 0
        for (s in spans) {
            val b = pos + s.text.length
            when {
                b <= at -> left.add(s)
                pos >= at -> right.add(s)
                else -> { left.add(s.copy(text = s.text.substring(0, at - pos))); right.add(s.copy(text = s.text.substring(at - pos))) }
            }
            pos = b
        }
        return left to right
    }

    /** Applies [f] to every span over characters [start, end) (spans are split at the ends). */
    fun restyle(spans: List<Span>, start: Int, end: Int, f: (Span) -> Span): List<Span> {
        if (end <= start) return spans
        val (left, rest) = split(spans, start)
        val (mid, right) = split(rest, end - start)
        return normalize(left + mid.map(f) + right)
    }

    fun setColor(spans: List<Span>, start: Int, end: Int, rgb: Int?): List<Span> = restyle(spans, start, end) { it.copy(color = rgb) }
    fun setHighlight(spans: List<Span>, start: Int, end: Int, name: String?): List<Span> = restyle(spans, start, end) { it.copy(highlight = name) }
    fun setSize(spans: List<Span>, start: Int, end: Int, halfPoints: Int?): List<Span> = restyle(spans, start, end) { it.copy(size = halfPoints) }

    /** d16 / p17: one step up ([steps] > 0) or down the size ladder for each span; a span without a size starts from [baseHp]. */
    fun stepSize(spans: List<Span>, start: Int, end: Int, steps: Int, baseHp: Int): List<Span> =
        restyle(spans, start, end) { it.copy(size = FontSizes.step(it.size ?: baseHp, steps)) }

    /** d18: bold, italic, underline, strikethrough, colour, highlight and size all back to the paragraph's own. */
    fun clearFormat(spans: List<Span>, start: Int, end: Int): List<Span> =
        restyle(spans, start, end) { it.copy(bold = false, italic = false, underline = false, strike = false, color = null, highlight = null, size = null) }

    /** The value of [get] when all characters in [start, end) share it, else null; an empty range reads the character before the caret. */
    fun <T> common(spans: List<Span>, start: Int, end: Int, get: (Span) -> T?): T? {
        if (end <= start) return formatAt(spans, if (start > 0) start - 1 else 0)?.let(get)
        var pos = 0
        var first = true
        var v: T? = null
        for (s in spans) {
            val a = pos; val b = pos + s.text.length; pos = b
            if (b <= start || a >= end || s.text.isEmpty()) continue
            val x = get(s)
            if (first) { v = x; first = false } else if (x != v) return null
        }
        return v
    }

    private fun sameFormat(a: Span, b: Span) = a.bold == b.bold && a.italic == b.italic && a.underline == b.underline && a.strike == b.strike && a.src == b.src &&
        a.color == b.color && a.highlight == b.highlight && a.size == b.size
    private fun get(s: Span, attr: Attr) = when (attr) { Attr.BOLD -> s.bold; Attr.ITALIC -> s.italic; Attr.UNDERLINE -> s.underline; Attr.STRIKE -> s.strike }
    private fun with(s: Span, attr: Attr, on: Boolean) = when (attr) {
        Attr.BOLD -> s.copy(bold = on); Attr.ITALIC -> s.copy(italic = on); Attr.UNDERLINE -> s.copy(underline = on); Attr.STRIKE -> s.copy(strike = on)
    }

    /** Offsets of every match of [find] in [text] (non-overlapping, left to right). */
    fun matches(text: String, find: String, ignoreCase: Boolean = true): List<Int> {
        if (find.isEmpty()) return emptyList()
        val out = ArrayList<Int>()
        var i = text.indexOf(find, 0, ignoreCase)
        while (i >= 0) { out.add(i); i = text.indexOf(find, i + find.length, ignoreCase) }
        return out
    }

    /**
     * Replaces every match of [find] by [replacement]; the new text takes the format of the first character it
     * replaces. Returns the spans and the number of replacements.
     */
    fun replaceAll(spans: List<Span>, find: String, replacement: String, ignoreCase: Boolean = true): Pair<List<Span>, Int> {
        val hits = matches(text(spans), find, ignoreCase)
        if (hits.isEmpty()) return spans to 0
        var cur = spans
        for (at in hits.asReversed()) {
            val fmt = formatAt(cur, at) ?: Span("")
            val (left, rest) = split(cur, at)
            val (_, right) = split(rest, find.length)
            val mid = if (replacement.isEmpty()) emptyList() else listOf(fmt.copy(text = replacement))
            cur = normalize(left + mid + right)
        }
        return cur to hits.size
    }

    /** Replaces the [len] characters at [at] by [replacement] (one hit of a find); the new text takes the format of the first replaced character. */
    fun replaceAt(spans: List<Span>, at: Int, len: Int, replacement: String): List<Span> {
        val total = text(spans).length
        if (at < 0 || len < 0 || at + len > total) return spans
        val fmt = formatAt(spans, at) ?: spans.lastOrNull()?.copy(text = "") ?: Span("")
        val (left, rest) = split(spans, at)
        val (_, right) = split(rest, len)
        val mid = if (replacement.isEmpty()) emptyList() else listOf(fmt.copy(text = replacement))
        return normalize(left + mid + right)
    }
}

/** Font size ladder in half-points (8 … 72 pt, Word's list): [step] moves along it. */
object FontSizes {
    val LADDER = intArrayOf(16, 18, 20, 21, 22, 24, 28, 32, 36, 40, 44, 48, 52, 56, 72, 96, 144)
    /** The menu's fixed sizes (pt): 12, 14, 18, 24. */
    val PRESETS_PT = intArrayOf(12, 14, 18, 24)

    /** [hp] moved [steps] places along the ladder; a size between two places counts from the nearer one in the direction of travel. */
    fun step(hp: Int, steps: Int): Int {
        if (steps == 0) return hp
        var i = if (steps > 0) LADDER.indexOfLast { it <= hp } else LADDER.indexOfFirst { it >= hp }
        if (i < 0) i = if (steps > 0) -1 else LADDER.size
        return LADDER[(i + steps).coerceIn(0, LADDER.size - 1)]
    }
}
