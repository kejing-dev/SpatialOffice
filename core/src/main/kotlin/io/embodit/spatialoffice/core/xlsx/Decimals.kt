package io.embodit.spatialoffice.core.xlsx

/** Increase / decrease decimal places on a number format code, the way the toolbar buttons of a spreadsheet do. */
internal object Decimals {

    /**
     * [code] with one decimal place more ([delta] > 0) or fewer ([delta] < 0), repeated |[delta]| times. `General`
     * starts from the decimals [value] shows. Date / time and text (`@`) formats come back unchanged; prefixes,
     * suffixes, currency signs and percent signs stay. Every section (`;`) changes the same way.
     */
    fun adjust(code: String, delta: Int, value: Double?): String {
        if (delta == 0) return code
        if (code.equals("General", true) || code.isBlank()) {
            val shown = value?.let { decimalsShown(it) } ?: 0
            val n = (shown + delta).coerceAtLeast(0)
            return if (n == 0) "0" else "0." + "0".repeat(n)
        }
        if (NumFormat.of(code).kind == NumFmt.DATE || code.trim() == "@") return code
        var out = code
        repeat(kotlin.math.abs(delta)) { out = splitSections(out).joinToString(";") { step(it, delta > 0) } }
        return out
    }

    /** Decimals a General cell shows for [v] (up to 10). */
    private fun decimalsShown(v: Double): Int {
        val t = Num.general(v)
        if (t.contains('E') || t.contains('e')) return 0
        val dot = t.indexOf('.')
        return if (dot < 0) 0 else (t.length - dot - 1).coerceAtMost(10)
    }

    private fun splitSections(code: String): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        var q = false
        var br = false
        var i = 0
        while (i < code.length) {
            val c = code[i]
            when {
                c == '\\' && !q && i + 1 < code.length -> { sb.append(c).append(code[i + 1]); i += 2; continue }
                c == '"' -> q = !q
                c == '[' && !q -> br = true
                c == ']' && !q -> br = false
                c == ';' && !q && !br -> { out.add(sb.toString()); sb.clear(); i++; continue }
            }
            sb.append(c)
            i++
        }
        out.add(sb.toString())
        return out
    }

    /** Positions in [s] that are format characters (not inside quotes, brackets or after a backslash). */
    private fun live(s: String): BooleanArray {
        val live = BooleanArray(s.length)
        var q = false
        var br = false
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == '\\' && !q -> { i += 2; continue }
                c == '"' -> q = !q
                c == '[' && !q -> br = true
                c == ']' && !q -> br = false
                !q && !br -> live[i] = true
            }
            i++
        }
        return live
    }

    private fun step(sec: String, more: Boolean): String {
        if (sec.isEmpty()) return sec
        val live = live(sec)
        // the decimal point of the number: a live '.' followed by digit placeholders, or after the last integer placeholder
        val digits = "0#?"
        var dot = -1
        for (i in sec.indices) if (live[i] && sec[i] == '.') {
            if ((i > 0 && sec[i - 1] in digits) || (i + 1 < sec.length && sec[i + 1] in digits)) { dot = i; break }
        }
        if (dot >= 0) {
            var end = dot + 1
            while (end < sec.length && live[end] && sec[end] in digits) end++
            return if (more) sec.substring(0, end) + "0" + sec.substring(end)
            else if (end - dot - 1 <= 1) sec.substring(0, dot) + sec.substring(end) // last decimal goes with the point
            else sec.substring(0, end - 1) + sec.substring(end)
        }
        if (!more) return sec
        var last = -1
        for (i in sec.indices) if (live[i] && sec[i] in digits) last = i
        // a section with no placeholder at all (only text) is left as it is
        if (last < 0) return sec
        // stop at an exponent: "0E+00" → "0.0E+00"
        var k = last
        val e = (0 until sec.length).firstOrNull { live[it] && (sec[it] == 'E' || sec[it] == 'e') }
        if (e != null) { k = (0 until e).lastOrNull { live[it] && sec[it] in digits } ?: return sec }
        return sec.substring(0, k + 1) + ".0" + sec.substring(k + 1)
    }
}
