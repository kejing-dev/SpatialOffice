package io.embodit.spatialoffice.core.xlsx

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/** Locale-independent number ⇄ text helpers. */
internal object Num {
    private val NUMERIC = Regex("[+-]?(?:[0-9]+\\.?[0-9]*|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?")

    /** Text → number the way a formula coerces it ("12", " 3.5 ", "1e3"); null when it is not numeric. */
    fun parse(s: String): Double? {
        val t = s.trim()
        if (t.isEmpty() || !NUMERIC.matches(t)) return null
        return t.toDoubleOrNull()
    }

    /** Display of a number in a cell with the General format (about ten significant digits). */
    fun general(d: Double): String {
        if (d.isNaN() || d.isInfinite()) return "#NUM!"
        if (d == 0.0) return "0"
        val a = Math.abs(d)
        if (d == Math.rint(d) && a < 1e11) return d.toLong().toString()
        if (a >= 1e11 || a < 1e-9) return sci(d, 5, 2)
        return BigDecimal(d.toString()).round(MathContext(10, RoundingMode.HALF_UP)).stripTrailingZeros().toPlainString()
    }

    /** What the formula bar shows for a number: up to 15 significant digits, no grouping. */
    fun input(d: Double): String {
        if (d.isNaN() || d.isInfinite()) return "#NUM!"
        if (d == 0.0) return "0"
        val a = Math.abs(d)
        if (d == Math.rint(d) && a < 1e15) return d.toLong().toString()
        if (a >= 1e15 || a < 1e-7) return sci(d, 14, 2)
        return BigDecimal(d.toString()).round(MathContext(15, RoundingMode.HALF_UP)).stripTrailingZeros().toPlainString()
    }

    /** Lexical form for a `<v>` element. */
    fun xml(d: Double): String {
        if (d == 0.0) return "0"
        val a = Math.abs(d)
        if (d == Math.rint(d) && a < 1e15) return d.toLong().toString()
        if (a >= 1e-7 && a < 1e15) return BigDecimal(d.toString()).toPlainString()
        return d.toString()
    }

    /** Scientific notation with up to [maxDec] mantissa decimals (trailing zeros dropped) and at least [expDigits] exponent digits. */
    fun sci(d: Double, maxDec: Int, expDigits: Int, keepZeros: Boolean = false): String {
        val s = String.format(Locale.ROOT, "%." + maxDec + "E", d)
        val k = s.indexOf('E')
        var mant = s.substring(0, k)
        if (!keepZeros && mant.indexOf('.') >= 0) mant = mant.trimEnd('0').trimEnd('.')
        val sign = if (s[k + 1] == '-') "-" else "+"
        val digits = s.substring(k + 2).trimStart('0')
        return mant + "E" + sign + digits.padStart(expDigits, '0')
    }

    // ---- serial dates (1900 system unless the workbook says 1904) ----

    /** Days since 1970-01-01 → (year, month, day). */
    fun civil(z0: Long): IntArray {
        val z = z0 + 719468
        val era = Math.floorDiv(z, 146097L)
        val doe = z - era * 146097
        val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
        val y = yoe + era * 400
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val d = doy - (153 * mp + 2) / 5 + 1
        val m = if (mp < 10) mp + 3 else mp - 9
        return intArrayOf((if (m <= 2) y + 1 else y).toInt(), m.toInt(), d.toInt())
    }

    /** (year, month, day) → days since 1970-01-01. */
    fun daysFromCivil(y0: Int, m: Int, d: Int): Long {
        val y = (if (m <= 2) y0 - 1 else y0).toLong()
        val era = Math.floorDiv(y, 400L)
        val yoe = y - era * 400
        val doy = (153 * (if (m > 2) m - 3 else m + 9) + 2) / 5 + d - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146097 + doe - 719468
    }

    private const val EPOCH_1900 = 25569L // serial of 1970-01-01 in the 1900 system
    private const val EPOCH_1904 = 24107L

    /** Serial number of a calendar date, or null when the date does not exist. */
    fun serial(y: Int, m: Int, d: Int, date1904: Boolean): Double? {
        if (m !in 1..12 || d !in 1..31 || y !in 1900..9999) return null
        val days = daysFromCivil(y, m, d)
        val back = civil(days)
        if (back[0] != y || back[1] != m || back[2] != d) return null
        if (date1904) { val s = days + EPOCH_1904; return if (s < 0) null else s.toDouble() }
        val s = days + EPOCH_1900
        return (if (s < 61) s - 1 else s).takeIf { it >= 1 }?.toDouble()
    }

    private fun two(n: Long): String = if (n < 10) "0$n" else n.toString()

    /** ISO text for a serial date: `yyyy-MM-dd`, `HH:mm[:ss]`, or both. */
    fun isoDate(serial: Double, date1904: Boolean, showDate: Boolean, showTime: Boolean, showSeconds: Boolean, elapsed: Boolean): String {
        if (serial < 0 || serial.isNaN() || serial > 2958465.99999) return general(serial)
        var days = Math.floor(serial).toLong()
        var secs = Math.round((serial - days) * 86400.0)
        if (secs >= 86400) { days++; secs = 0 }
        val time = if (elapsed) {
            val total = days * 86400 + secs
            (total / 3600).toString() + ":" + two(total / 60 % 60) + (if (showSeconds) ":" + two(total % 60) else "")
        } else two(secs / 3600) + ":" + two(secs / 60 % 60) + (if (showSeconds) ":" + two(secs % 60) else "")
        if (!showDate) return time
        val date = if (!date1904 && days == 60L) "1900-02-29" else {
            val unix = if (date1904) days - EPOCH_1904 else if (days < 60) days - EPOCH_1900 + 1 else days - EPOCH_1900
            val c = civil(unix)
            c[0].toString().padStart(4, '0') + "-" + two(c[1].toLong()) + "-" + two(c[2].toLong())
        }
        return if (showTime) "$date $time" else date
    }
}

/**
 * A number-format code (`#,##0.00`, `0%`, `"¥"#,##0.00`, `yyyy-mm-dd`, …) parsed once. Covers digit placeholders,
 * grouping, scaling commas, percent, literals, sections (positive;negative;zero), currency tags and scientific
 * notation. Date and time codes are not rendered in their own pattern: they display in ISO form.
 */
internal class NumFormat private constructor(val code: String) {

    private class Section {
        val prefix = StringBuilder()
        val suffix = StringBuilder()
        var intZeros = 0
        var decZeros = 0
        var decMax = 0
        var group = false
        var scale = 0
        var pct = 0
        var general = false
        var sci = false
        var expDigits = 0
        var fraction = false
        var placeholders = 0
        var currency = false
        var hasY = false
        var hasD = false
        var hasM = false
        var hasH = false
        var hasS = false
        var elapsed = false
        val isDate: Boolean get() = hasY || hasD || hasM || hasH || hasS
    }

    private val sections: List<Section> = split(code).map { parseSection(it) }
    private val first: Section = sections.firstOrNull() ?: Section().also { it.general = true }

    val kind: NumFmt = when {
        first.isDate -> NumFmt.DATE
        sections.any { it.pct > 0 } -> NumFmt.PERCENT
        sections.any { it.currency } -> NumFmt.CURRENCY
        first.general && first.prefix.isEmpty() && first.suffix.isEmpty() -> NumFmt.GENERAL
        else -> NumFmt.OTHER
    }

    fun format(d: Double, date1904: Boolean): String {
        if (d.isNaN() || d.isInfinite()) return "#NUM!"
        if (first.isDate) {
            val showTime = first.hasH || first.hasS
            val showDate = first.hasY || first.hasD || (first.hasM && !showTime)
            return Num.isoDate(d, date1904, showDate, showTime, first.hasS, first.elapsed)
        }
        val sec: Section
        var minus = false
        var v = d
        when {
            sections.size >= 3 && d == 0.0 -> sec = sections[2]
            sections.size >= 2 && d < 0 -> { sec = sections[1]; v = -d }
            else -> { sec = first; if (d < 0) { minus = true; v = -d } }
        }
        val body: String = when {
            sec.general || sec.fraction -> Num.general(v)
            sec.placeholders == 0 -> ""
            sec.sci -> Num.sci(v, sec.decMax, maxOf(1, sec.expDigits), keepZeros = true)
            else -> fixed(v, sec)
        }
        if (minus && body.none { it in '1'..'9' }) minus = false
        return (if (minus) "-" else "") + sec.prefix + body + sec.suffix
    }

    private fun fixed(v0: Double, sec: Section): String {
        var bd = BigDecimal(v0.toString())
        repeat(sec.pct) { bd = bd.movePointRight(2) }
        repeat(sec.scale) { bd = bd.movePointLeft(3) }
        bd = bd.setScale(sec.decMax, RoundingMode.HALF_UP)
        val plain = bd.toPlainString()
        val dot = plain.indexOf('.')
        var ip = if (dot < 0) plain else plain.substring(0, dot)
        var fp = if (dot < 0) "" else plain.substring(dot + 1)
        while (fp.length > sec.decZeros && fp.endsWith("0")) fp = fp.substring(0, fp.length - 1)
        if (ip == "0" && sec.intZeros == 0) ip = ""
        ip = ip.padStart(sec.intZeros, '0')
        if (sec.group && ip.length > 3) {
            val sb = StringBuilder()
            val lead = ip.length % 3
            if (lead > 0) sb.append(ip, 0, lead)
            var k = lead
            while (k < ip.length) { if (sb.isNotEmpty()) sb.append(','); sb.append(ip, k, k + 3); k += 3 }
            ip = sb.toString()
        }
        return if (fp.isEmpty()) ip else "$ip.$fp"
    }

    companion object {
        private val cache = ConcurrentHashMap<String, NumFormat>()
        val GENERAL: NumFormat = NumFormat("General")

        fun of(code: String): NumFormat = cache.getOrPut(code) { NumFormat(code) }

        /** Format code of a built-in number-format id; null when the id has no fixed code. */
        fun builtin(id: Int): String? = when (id) {
            0 -> "General"
            1 -> "0"
            2 -> "0.00"
            3 -> "#,##0"
            4 -> "#,##0.00"
            5 -> "\"$\"#,##0_);\\(\"$\"#,##0\\)"
            6 -> "\"$\"#,##0_);[Red]\\(\"$\"#,##0\\)"
            7 -> "\"$\"#,##0.00_);\\(\"$\"#,##0.00\\)"
            8 -> "\"$\"#,##0.00_);[Red]\\(\"$\"#,##0.00\\)"
            9 -> "0%"
            10 -> "0.00%"
            11 -> "0.00E+00"
            12 -> "# ?/?"
            13 -> "# ??/??"
            14 -> "mm-dd-yy"
            15 -> "d-mmm-yy"
            16 -> "d-mmm"
            17 -> "mmm-yy"
            18 -> "h:mm AM/PM"
            19 -> "h:mm:ss AM/PM"
            20 -> "h:mm"
            21 -> "h:mm:ss"
            22 -> "m/d/yy h:mm"
            in 27..31, in 50..58 -> "yyyy-mm-dd"
            32, 33 -> "h:mm"
            34, 35 -> "h:mm:ss"
            36 -> "yyyy-mm-dd"
            37 -> "#,##0_);\\(#,##0\\)"
            38 -> "#,##0_);[Red]\\(#,##0\\)"
            39 -> "#,##0.00_);\\(#,##0.00\\)"
            40 -> "#,##0.00_);[Red]\\(#,##0.00\\)"
            41 -> "_(* #,##0_);_(* \\(#,##0\\);_(* \"-\"_);_(@_)"
            42 -> "_(\"$\"* #,##0_);_(\"$\"* \\(#,##0\\);_(\"$\"* \"-\"_);_(@_)"
            43 -> "_(* #,##0.00_);_(* \\(#,##0.00\\);_(* \"-\"??_);_(@_)"
            44 -> "_(\"$\"* #,##0.00_);_(\"$\"* \\(#,##0.00\\);_(\"$\"* \"-\"??_);_(@_)"
            45 -> "mm:ss"
            46 -> "[h]:mm:ss"
            47 -> "mmss.0"
            48 -> "##0.0E+0"
            49 -> "@"
            else -> null
        }

        private const val CURRENCY_SIGNS = "\$¥€£₩₹￥₽₫฿"

        /** Splits a code into its `;` sections, ignoring separators inside quotes, brackets and escapes. */
        private fun split(code: String): List<String> {
            val out = ArrayList<String>()
            val sb = StringBuilder()
            var i = 0
            while (i < code.length) {
                val c = code[i]
                when {
                    c == '"' -> { val j = code.indexOf('"', i + 1).let { if (it < 0) code.length - 1 else it }; sb.append(code, i, j + 1); i = j }
                    c == '[' -> { val j = code.indexOf(']', i + 1).let { if (it < 0) code.length - 1 else it }; sb.append(code, i, j + 1); i = j }
                    (c == '\\' || c == '_' || c == '*') && i + 1 < code.length -> { sb.append(c).append(code[i + 1]); i++ }
                    c == ';' -> { out.add(sb.toString()); sb.setLength(0) }
                    else -> sb.append(c)
                }
                i++
            }
            out.add(sb.toString())
            return out
        }

        private fun parseSection(s: String): Section {
            val sec = Section()
            var seenDot = false
            fun lit(text: String) {
                if (text.any { it in CURRENCY_SIGNS }) sec.currency = true
                if (sec.placeholders == 0 && !sec.general) sec.prefix.append(text) else sec.suffix.append(text)
            }
            var i = 0
            val n = s.length
            while (i < n) {
                val c = s[i]
                when {
                    c == '"' -> { val j = s.indexOf('"', i + 1).let { if (it < 0) n else it }; lit(s.substring(i + 1, j)); i = j }
                    c == '\\' -> { if (i + 1 < n) lit(s[i + 1].toString()); i++ }
                    c == '_' -> { if (i + 1 < n) lit(" "); i++ }
                    c == '*' -> i++
                    c == '[' -> {
                        val j = s.indexOf(']', i + 1).let { if (it < 0) n else it }
                        val inner = s.substring(i + 1, j)
                        val low = inner.lowercase(Locale.ROOT)
                        when {
                            inner.startsWith("$") -> {
                                val sym = inner.substring(1).substringBefore('-')
                                if (sym.isNotEmpty()) { sec.currency = true; lit(sym) }
                            }
                            low == "h" || low == "hh" -> { sec.hasH = true; sec.elapsed = true }
                            low == "m" || low == "mm" -> { sec.hasM = true; sec.hasH = true; sec.elapsed = true }
                            low == "s" || low == "ss" -> { sec.hasS = true; sec.elapsed = true }
                        }
                        i = j
                    }
                    c == '0' || c == '#' || c == '?' -> {
                        sec.placeholders++
                        if (sec.sci) { if (c == '0') sec.expDigits++ }
                        else if (seenDot) { sec.decMax++; if (c == '0') sec.decZeros++ }
                        else if (c == '0') sec.intZeros++
                    }
                    c == '.' -> if (!seenDot && !sec.isDate) seenDot = true else lit(".")
                    c == ',' -> {
                        val prevPh = i > 0 && s[i - 1] in "0#?,"
                        val nextPh = i + 1 < n && s[i + 1] in "0#?"
                        if (prevPh && nextPh && !seenDot) sec.group = true
                        else if (prevPh && sec.placeholders > 0) sec.scale++
                        else lit(",")
                    }
                    c == '%' -> { sec.pct++; lit("%") }
                    (c == 'E' || c == 'e') && i + 1 < n && (s[i + 1] == '+' || s[i + 1] == '-') && sec.placeholders > 0 -> { sec.sci = true; i++ }
                    c == '/' -> if (sec.placeholders > 0 && i + 1 < n && s[i + 1] in "0#?123456789") sec.fraction = true else lit("/")
                    c == '@' -> {}
                    s.regionMatches(i, "General", 0, 7, ignoreCase = true) -> { sec.general = true; i += 6 }
                    s.regionMatches(i, "AM/PM", 0, 5, ignoreCase = true) -> { sec.hasH = true; i += 4 }
                    s.regionMatches(i, "A/P", 0, 3, ignoreCase = true) -> { sec.hasH = true; i += 2 }
                    c == 'y' || c == 'Y' -> sec.hasY = true
                    c == 'd' || c == 'D' -> sec.hasD = true
                    c == 'm' || c == 'M' -> sec.hasM = true
                    c == 'h' || c == 'H' -> sec.hasH = true
                    c == 's' || c == 'S' -> sec.hasS = true
                    else -> lit(c.toString())
                }
                i++
            }
            return sec
        }
    }
}
