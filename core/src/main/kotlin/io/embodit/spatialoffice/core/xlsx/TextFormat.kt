package io.embodit.spatialoffice.core.xlsx

import java.util.Locale

/**
 * The TEXT function's formatting (v0.5.0, s27). Number codes go through [NumFormat]; date and time codes are rendered
 * in their own pattern here (yyyy yy mmmm mmm mm m dd d dddd ddd hh h mm ss AM/PM, quoted literals, `\` escapes),
 * unlike a cell's display, which shows dates in ISO form.
 */
internal object TextFormat {
    private val MONTHS = listOf("January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December")
    private val DAYS = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")

    private fun isDate(code: String): Boolean {
        var i = 0
        while (i < code.length) {
            val c = code[i]
            when {
                c == '"' -> { val j = code.indexOf('"', i + 1); i = if (j < 0) code.length else j }
                c == '\\' -> i++
                c == '[' -> { val j = code.indexOf(']', i + 1); i = if (j < 0) code.length else j }
                c.lowercaseChar() in "ydhs" -> return true
                c.lowercaseChar() == 'm' && !code.contains('0') && !code.contains('#') -> return true
            }
            i++
        }
        return false
    }

    fun format(v: Double, code: String, date1904: Boolean): String {
        if (code.isEmpty()) return ""
        if (!isDate(code)) return NumFormat.of(code).format(v, date1904)
        if (v < 0 || v > 2958465.99999) return "#VALUE!"
        var days = Math.floor(v).toLong()
        var secs = Math.round((v - days) * 86400.0)
        if (secs >= 86400) { days++; secs = 0 }
        val ymd = if (!date1904 && days == 60L) intArrayOf(1900, 2, 29) else {
            val unix = if (date1904) days - 24107 else if (days < 61) days - 25568 else days - 25569
            Num.civil(unix)
        }
        val unix = if (date1904) days - 24107 else if (days < 61) days - 25568 else days - 25569
        val dow = Math.floorMod(unix + 3, 7L).toInt()
        val ampm = Regex("AM/PM|A/P", RegexOption.IGNORE_CASE).containsMatchIn(code)
        val h24 = (secs / 3600).toInt()
        val mi = (secs / 60 % 60).toInt()
        val se = (secs % 60).toInt()
        // tokens first, so "m" can be told apart: minutes right after an hour or right before seconds
        val toks = ArrayList<String>()
        var i = 0
        while (i < code.length) {
            val c = code[i]
            when {
                c == '"' -> { val j = code.indexOf('"', i + 1).let { if (it < 0) code.length else it }; toks.add("\u0000" + code.substring(i + 1, j)); i = j + 1 }
                c == '\\' -> { if (i + 1 < code.length) toks.add("\u0000" + code[i + 1]); i += 2 }
                c == '[' -> { val j = code.indexOf(']', i + 1).let { if (it < 0) code.length else it }; i = j + 1 }
                code.regionMatches(i, "AM/PM", 0, 5, ignoreCase = true) -> { toks.add("AMPM"); i += 5 }
                code.regionMatches(i, "A/P", 0, 3, ignoreCase = true) -> { toks.add("AP"); i += 3 }
                c.lowercaseChar() in "ymdhs" -> {
                    var j = i
                    while (j < code.length && code[j].lowercaseChar() == c.lowercaseChar()) j++
                    toks.add(code.substring(i, j).lowercase(Locale.ROOT)); i = j
                }
                else -> { toks.add("\u0000" + c); i++ }
            }
        }
        val sb = StringBuilder()
        fun two(n: Int) = if (n < 10) "0$n" else n.toString()
        for ((k, t) in toks.withIndex()) {
            if (t.startsWith("\u0000")) { sb.append(t, 1, t.length); continue }
            val prevH = toks.subList(0, k).lastOrNull { !it.startsWith("\u0000") }?.startsWith("h") == true
            val nextS = toks.subList(k + 1, toks.size).firstOrNull { !it.startsWith("\u0000") }?.startsWith("s") == true
            when {
                t == "AMPM" -> sb.append(if (h24 < 12) "AM" else "PM")
                t == "AP" -> sb.append(if (h24 < 12) "A" else "P")
                t.startsWith("y") -> sb.append(if (t.length <= 2) two(ymd[0] % 100) else ymd[0].toString().padStart(4, '0'))
                t.startsWith("m") && (prevH || nextS) && t.length <= 2 -> sb.append(if (t.length == 1) mi.toString() else two(mi))
                t.startsWith("m") -> sb.append(when (t.length) { 1 -> ymd[1].toString(); 2 -> two(ymd[1]); 3 -> MONTHS[ymd[1] - 1].take(3); 5 -> MONTHS[ymd[1] - 1].take(1); else -> MONTHS[ymd[1] - 1] })
                t.startsWith("d") -> sb.append(when (t.length) { 1 -> ymd[2].toString(); 2 -> two(ymd[2]); 3 -> DAYS[dow].take(3); else -> DAYS[dow] })
                t.startsWith("h") -> { val h = if (ampm) (h24 % 12).let { if (it == 0) 12 else it } else h24; sb.append(if (t.length == 1) h.toString() else two(h)) }
                t.startsWith("s") -> sb.append(if (t.length == 1) se.toString() else two(se))
            }
        }
        return sb.toString()
    }
}
