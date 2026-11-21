package io.embodit.spatialoffice.core.xlsx

enum class CellKind { EMPTY, NUMBER, TEXT, BOOL, ERROR }

enum class NumFmt { GENERAL, CURRENCY, PERCENT, DATE, OTHER }

/** input = what the formula bar shows ("=SUM(B2:B5)", "12.5", "text", "" when empty); display = formatted value. */
data class Cell(
    val input: String,
    val display: String,
    val kind: CellKind,
    val number: Double?,
    val isFormula: Boolean,
    val bold: Boolean,
    val fmt: NumFmt,
    /** Horizontal alignment from the cell format: "left", "center", "right", or null (general). */
    val hAlign: String? = null,
    /** Text wraps inside the cell (`wrapText`). */
    val wrap: Boolean = false,
    /** M2 (s23): effective font of the cell. [colorRgb] 0xRRGGBB (null = automatic or a theme colour); [fontPt] the font size, null when the font has none. */
    val italic: Boolean = false,
    val underline: Boolean = false,
    val strike: Boolean = false,
    val colorRgb: Int? = null,
    val fontPt: Float? = null,
)

/** One cell as copied: what the formula bar showed and its cell style index (meaningful within the same workbook). */
data class ClipCell(val input: String, val style: Int)

data class ChartSeries(
    val name: String,
    val categories: List<String>,
    val values: List<Double?>,
    val valuesRef: String?,
    val xValues: List<Double?>?,
)

data class ChartInfo(
    val part: String,
    val sheet: Int,
    val title: String,
    /** "bar" | "line" | "pie" | "scatter" | "area" | "other" */
    val type: String,
    val series: List<ChartSeries>,
    /** covering range like "A1:M5", when the series references form one rectangle */
    val range: String?,
)

/** Cell addressing helpers. Rows and columns are 0-based everywhere in this module; A1 text is 1-based as in the file. */
object A1 {
    const val MAX_ROW = 1_048_575
    const val MAX_COL = 16_383

    /** 0 → "A", 25 → "Z", 26 → "AA". */
    fun colName(col: Int): String {
        require(col >= 0) { "col" }
        var n = col + 1
        val sb = StringBuilder()
        while (n > 0) {
            val r = (n - 1) % 26
            sb.append('A' + r)
            n = (n - 1) / 26
        }
        return sb.reverse().toString()
    }

    /** (0, 0) → "A1". */
    fun name(row: Int, col: Int): String = colName(col) + (row + 1)

    /** "B3" or "$B$3" → (row 2, col 1); null when [ref] is not a single cell address. */
    fun parse(ref: String): Pair<Int, Int>? {
        var i = 0
        val n = ref.length
        if (i < n && ref[i] == '$') i++
        var col = 0
        var letters = 0
        while (i < n && ref[i].let { it in 'A'..'Z' || it in 'a'..'z' }) {
            col = col * 26 + (ref[i].uppercaseChar() - 'A' + 1)
            i++; letters++
            if (letters > 3) return null
        }
        if (letters == 0) return null
        if (i < n && ref[i] == '$') i++
        var row = 0L
        var digits = 0
        while (i < n && ref[i] in '0'..'9') {
            row = row * 10 + (ref[i] - '0')
            i++; digits++
            if (digits > 7) return null
        }
        if (digits == 0 || i != n) return null
        if (row < 1 || row - 1 > MAX_ROW || col - 1 > MAX_COL) return null
        return Pair((row - 1).toInt(), col - 1)
    }

    /** "A1:M5" (or a single cell, optionally sheet-qualified) → [r1, c1, r2, c2] with r1 ≤ r2 and c1 ≤ c2; null when unreadable. */
    fun parseRange(ref: String): IntArray? {
        val t = ref.substringAfterLast('!').trim()
        val parts = t.split(':')
        if (parts.size > 2) return null
        val a = parse(parts[0]) ?: return null
        val b = if (parts.size == 2) parse(parts[1]) ?: return null else a
        return intArrayOf(minOf(a.first, b.first), minOf(a.second, b.second), maxOf(a.first, b.first), maxOf(a.second, b.second))
    }

    fun rangeName(r1: Int, c1: Int, r2: Int, c2: Int): String =
        if (r1 == r2 && c1 == c2) name(r1, c1) else name(r1, c1) + ":" + name(r2, c2)
}
