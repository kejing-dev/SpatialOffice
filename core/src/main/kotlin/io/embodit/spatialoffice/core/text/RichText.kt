package io.embodit.spatialoffice.core.text

/** A stretch of text with one format. [src] = index of the run in the opened file whose other formatting it keeps (−1 = none). */
data class Span(val text: String, val bold: Boolean = false, val italic: Boolean = false, val underline: Boolean = false, val src: Int = -1, val strike: Boolean = false)

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

    private fun sameFormat(a: Span, b: Span) = a.bold == b.bold && a.italic == b.italic && a.underline == b.underline && a.strike == b.strike && a.src == b.src
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
}
