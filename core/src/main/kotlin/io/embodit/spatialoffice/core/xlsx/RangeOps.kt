package io.embodit.spatialoffice.core.xlsx

import java.math.BigDecimal
import java.math.RoundingMode

/** A rectangle of cells, inclusive on both ends, always normalised (r1 ≤ r2, c1 ≤ c2). */
data class CellRect(val r1: Int, val c1: Int, val r2: Int, val c2: Int) {
    val rows: Int get() = r2 - r1 + 1
    val cols: Int get() = c2 - c1 + 1
    val size: Int get() = rows * cols
    fun contains(r: Int, c: Int) = r in r1..r2 && c in c1..c2
    /** `B2:D5`, or `B2` for one cell. */
    fun name(): String = if (r1 == r2 && c1 == c2) A1.name(r1, c1) else A1.name(r1, c1) + ":" + A1.name(r2, c2)

    companion object {
        fun of(ar: Int, ac: Int, br: Int, bc: Int) = CellRect(minOf(ar, br), minOf(ac, bc), maxOf(ar, br), maxOf(ac, bc))
    }
}

/** The status-bar figures of a selection (s17): sum and average of the numbers, count of the non-empty cells. */
data class RangeStats(val sum: Double, val average: Double?, val count: Int, val numbers: Int) {
    companion object {
        fun of(cells: List<Cell>): RangeStats {
            var sum = BigDecimal.ZERO
            var n = 0
            var count = 0
            for (c in cells) {
                if (c.input.isNotEmpty() || c.display.isNotEmpty()) count++
                val v = c.number
                if (v != null && v.isFinite() && c.kind != CellKind.TEXT) { sum = sum.add(BigDecimal(v.toString())); n++ }
            }
            val avg = if (n == 0) null else sum.divide(BigDecimal(n), 10, RoundingMode.HALF_UP).toDouble()
            return RangeStats(sum.toDouble(), avg, count, n)
        }

        /** A figure as the status bar shows it: up to 4 decimals, no trailing zeros, no exponent. */
        fun fmt(v: Double): String {
            if (!v.isFinite()) return v.toString()
            val bd = BigDecimal(v.toString()).setScale(4, RoundingMode.HALF_UP).stripTrailingZeros()
            return if (bd.signum() == 0) "0" else bd.toPlainString()
        }
    }
}

/** Ctrl+arrow (s17): where the selection jumps from (r, c) in direction (dr, dc), as every spreadsheet does it. */
object DataEdge {
    /**
     * From a filled cell whose neighbour is filled: to the last filled cell of that run. Otherwise: to the next filled
     * cell in that direction, or the edge ([maxRow] / [maxCol], or 0) when there is none.
     */
    fun jump(filled: (Int, Int) -> Boolean, r: Int, c: Int, dr: Int, dc: Int, maxRow: Int, maxCol: Int): Pair<Int, Int> {
        fun inside(rr: Int, cc: Int) = rr in 0..maxRow && cc in 0..maxCol
        if (!inside(r + dr, c + dc)) return r to c
        var rr = r; var cc = c
        if (filled(r, c) && filled(r + dr, c + dc)) {
            while (inside(rr + dr, cc + dc) && filled(rr + dr, cc + dc)) { rr += dr; cc += dc }
            return rr to cc
        }
        rr += dr; cc += dc
        while (inside(rr, cc) && !filled(rr, cc)) {
            if (!inside(rr + dr, cc + dc)) return rr to cc
            rr += dr; cc += dc
        }
        return rr to cc
    }
}

/** Tab-separated text as the system clipboard carries cells (s18): rows by line breaks, cells by tabs. */
object Tsv {
    fun write(rows: List<List<String>>): String = rows.joinToString("\n") { row -> row.joinToString("\t") { clean(it) } }

    private fun clean(v: String): String = if (v.any { it == '\t' || it == '\n' || it == '\r' || it == '"' }) "\"" + v.replace("\"", "\"\"") + "\"" else v

    /** Parses clipboard text; a trailing line break does not make an extra row. Quoted cells may hold tabs / breaks. */
    fun parse(text: String): List<List<String>> {
        val rows = ArrayList<List<String>>()
        var row = ArrayList<String>()
        val cell = StringBuilder()
        var i = 0
        var quoted = false
        var atStart = true
        while (i < text.length) {
            val ch = text[i]
            if (quoted) {
                if (ch == '"') {
                    if (i + 1 < text.length && text[i + 1] == '"') { cell.append('"'); i++ } else quoted = false
                } else cell.append(ch)
            } else when {
                ch == '"' && atStart -> { quoted = true; atStart = false }
                ch == '\t' -> { row.add(cell.toString()); cell.setLength(0); atStart = true }
                ch == '\r' -> {}
                ch == '\n' -> { row.add(cell.toString()); cell.setLength(0); rows.add(row); row = ArrayList(); atStart = true }
                else -> { cell.append(ch); atStart = false }
            }
            i++
        }
        if (cell.isNotEmpty() || row.isNotEmpty()) { row.add(cell.toString()); rows.add(row) }
        return rows
    }
}
