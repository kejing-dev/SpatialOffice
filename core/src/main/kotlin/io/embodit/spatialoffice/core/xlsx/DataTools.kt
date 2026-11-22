package io.embodit.spatialoffice.core.xlsx

import java.util.Locale

/**
 * v0.6.0 (M4 s29): a condition on one column of the data (the view filter of the sheet window; not saved). Rows whose
 * cell in [col] fails it are left out of the grid; the header row always stays.
 *  - [Op.VALUES]: the shown text is one of [values] (the multi-select list);
 *  - [Op.GT] / [Op.LT] / [Op.GE] / [Op.LE]: the cell's number compared with [text] read as a number (cells that are
 *    not numbers fail);
 *  - [Op.CONTAINS]: the shown text contains [text], ignoring case.
 */
data class FilterRule(val col: Int, val op: Op, val text: String = "", val values: Set<String> = emptySet()) {
    enum class Op { VALUES, GT, LT, GE, LE, CONTAINS }

    /** [text] as a number ("1,000", "1000.5", "¥1000", "12%" are understood); null when it is not one. */
    val number: Double? get() = parseNumber(text)

    fun pass(cell: Cell): Boolean = when (op) {
        Op.VALUES -> cell.display in values
        Op.CONTAINS -> text.isEmpty() || cell.display.lowercase(Locale.ROOT).contains(text.lowercase(Locale.ROOT))
        else -> {
            val v = cell.number
            val n = number
            if (v == null || n == null) false else when (op) {
                Op.GT -> v > n
                Op.LT -> v < n
                Op.GE -> v >= n
                else -> v <= n
            }
        }
    }

    /** Short text for the column header / status line: "> 1000", "contains 北京", "3 values". */
    fun summary(valuesWord: String = "values", containsWord: String = "contains"): String = when (op) {
        Op.VALUES -> if (values.size == 1) values.first() else "${values.size} $valuesWord"
        Op.GT -> "> $text"
        Op.LT -> "< $text"
        Op.GE -> "≥ $text"
        Op.LE -> "≤ $text"
        Op.CONTAINS -> "$containsWord $text"
    }

    companion object {
        fun parseNumber(s: String): Double? {
            var t = s.trim().replace(",", "").replace("，", "")
            if (t.isEmpty()) return null
            val pct = t.endsWith("%")
            if (pct) t = t.dropLast(1)
            t = t.trimStart('¥', '$', '€', '£', '￥').trim()
            val v = t.toDoubleOrNull() ?: return null
            return if (pct) v / 100.0 else v
        }
    }
}

/** v0.6.0 (M4 s31): what the sort dialog needs to know about a block of data. */
object SortPlan {
    /**
     * Whether the first row of a block reads as column headings: every filled cell of it is text, and either one of
     * them is bold or the row below has a number in a column where the first row has text (Excel's own guess).
     */
    fun looksLikeHeader(first: List<Cell>, second: List<Cell>): Boolean {
        val filled = first.filter { it.kind != CellKind.EMPTY }
        if (filled.isEmpty() || filled.any { it.kind != CellKind.TEXT }) return false
        if (filled.any { it.bold }) return true
        return first.indices.any { i -> first[i].kind == CellKind.TEXT && second.getOrNull(i)?.kind.let { it == CellKind.NUMBER || it == CellKind.BOOL } }
    }
}

/**
 * v0.6.0 (M4 s28): function hints for the formula bar. While a formula is typed, the name being typed is completed
 * from the functions the evaluator knows ([suggest], Tab takes the first: [complete]), and inside a function's
 * brackets its arguments are shown with the one being typed marked ([argHint]).
 */
object FormulaHints {
    /** Argument lists (Excel's names; optional ones in brackets) of every function [Formula.KNOWN] holds. */
    val SIGNATURES: Map<String, String> = linkedMapOf(
        "SUM" to "number1, [number2], …", "AVERAGE" to "number1, [number2], …", "MIN" to "number1, [number2], …",
        "MAX" to "number1, [number2], …", "COUNT" to "value1, [value2], …", "COUNTA" to "value1, [value2], …",
        "IF" to "logical_test, [value_if_true], [value_if_false]", "ROUND" to "number, num_digits", "ABS" to "number",
        "AND" to "logical1, [logical2], …", "OR" to "logical1, [logical2], …", "NOT" to "logical", "INT" to "number",
        "IFERROR" to "value, value_if_error", "MOD" to "number, divisor", "SQRT" to "number", "POWER" to "number, power",
        "ROUNDUP" to "number, num_digits", "ROUNDDOWN" to "number, num_digits", "PRODUCT" to "number1, [number2], …",
        "LEN" to "text", "CONCATENATE" to "text1, [text2], …", "CONCAT" to "text1, [text2], …", "UPPER" to "text",
        "LOWER" to "text", "TRIM" to "text", "TRUE" to "", "FALSE" to "",
        "COUNTIF" to "range, criteria", "SUMIF" to "range, criteria, [sum_range]", "AVERAGEIF" to "range, criteria, [average_range]",
        "COUNTBLANK" to "range", "MEDIAN" to "number1, [number2], …", "LARGE" to "array, k", "SMALL" to "array, k",
        "LEFT" to "text, [num_chars]", "RIGHT" to "text, [num_chars]", "MID" to "text, start_num, num_chars", "SIGN" to "number",
        "CEILING" to "number, significance", "FLOOR" to "number, significance",
        "VLOOKUP" to "lookup_value, table_array, col_index_num, [range_lookup]",
        "HLOOKUP" to "lookup_value, table_array, row_index_num, [range_lookup]", "EXACT" to "text1, text2", "REPT" to "text, number_times",
        "SUMIFS" to "sum_range, criteria_range1, criteria1, …", "COUNTIFS" to "criteria_range1, criteria1, …",
        "AVERAGEIFS" to "average_range, criteria_range1, criteria1, …", "MAXIFS" to "max_range, criteria_range1, criteria1, …",
        "MINIFS" to "min_range, criteria_range1, criteria1, …", "INDEX" to "array, row_num, [column_num]",
        "MATCH" to "lookup_value, lookup_array, [match_type]",
        "XLOOKUP" to "lookup_value, lookup_array, return_array, [if_not_found], [match_mode], [search_mode]",
        "CHOOSE" to "index_num, value1, [value2], …", "ROW" to "[reference]", "COLUMN" to "[reference]", "ROWS" to "array",
        "COLUMNS" to "array", "TODAY" to "", "NOW" to "", "DATE" to "year, month, day", "YEAR" to "serial_number",
        "MONTH" to "serial_number", "DAY" to "serial_number", "WEEKDAY" to "serial_number, [return_type]",
        "DATEDIF" to "start_date, end_date, unit", "EDATE" to "start_date, months", "EOMONTH" to "start_date, months",
        "HOUR" to "serial_number", "MINUTE" to "serial_number", "SECOND" to "serial_number", "TIME" to "hour, minute, second",
        "DAYS" to "end_date, start_date", "TEXT" to "value, format_text", "FIND" to "find_text, within_text, [start_num]",
        "SEARCH" to "find_text, within_text, [start_num]", "SUBSTITUTE" to "text, old_text, new_text, [instance_num]",
        "VALUE" to "text", "TEXTJOIN" to "delimiter, ignore_empty, text1, [text2], …",
        "REPLACE" to "old_text, start_num, num_chars, new_text", "PROPER" to "text", "CHAR" to "number", "CODE" to "text",
        "STDEV" to "number1, [number2], …", "STDEV.S" to "number1, [number2], …", "STDEV.P" to "number1, [number2], …",
        "VAR" to "number1, [number2], …", "VAR.S" to "number1, [number2], …", "VAR.P" to "number1, [number2], …",
        "RANK" to "number, ref, [order]", "RANK.EQ" to "number, ref, [order]", "SUMPRODUCT" to "array1, [array2], …",
        "IFS" to "logical_test1, value_if_true1, …", "SWITCH" to "expression, value1, result1, [default_or_value2], …",
        "XOR" to "logical1, [logical2], …", "IFNA" to "value, value_if_na", "ISBLANK" to "value", "ISNUMBER" to "value",
        "ISTEXT" to "value", "ISERROR" to "value", "ISNA" to "value", "NA" to "", "PI" to "", "EXP" to "number",
        "LN" to "number", "LOG" to "number, [base]", "LOG10" to "number", "TRUNC" to "number, [num_digits]",
    )

    /** Most used first: what is offered before the alphabet when several names match. */
    private val COMMON = listOf("SUM", "SUMIF", "SUMIFS", "SUMPRODUCT", "AVERAGE", "COUNT", "COUNTIF", "IF", "IFERROR", "MAX", "MIN", "ROUND", "VLOOKUP", "XLOOKUP", "INDEX", "MATCH", "TEXT", "TODAY", "LEFT", "LEN")

    /** One function and its arguments as shown: `SUMIF(range, criteria, [sum_range])`. */
    fun signature(name: String): String = name + "(" + (SIGNATURES[name] ?: "…") + ")"

    /** The function name being typed right before [caret] (start offset, text), or null. Only inside a formula and outside quotes. */
    fun nameAt(input: String, caret: Int): Pair<Int, String>? {
        if (!input.startsWith("=")) return null
        val c = caret.coerceIn(0, input.length)
        if (inQuotes(input, c)) return null
        var a = c
        while (a > 1 && (input[a - 1].isLetterOrDigit() || input[a - 1] == '.' || input[a - 1] == '_')) a--
        val tok = input.substring(a, c)
        if (tok.isEmpty() || !tok[0].isLetter()) return null
        // a cell reference (B2, AA10) or a sheet name before "!" is not a function name
        if (Regex("^[A-Za-z]{1,3}\\d+$").matches(tok)) return null
        if (a > 0 && (input[a - 1] == '!' || input[a - 1] == '$' || input[a - 1] == ':')) return null
        return a to tok
    }

    /** Functions whose name starts with what is typed before [caret] (most used first), at most [limit]. */
    fun suggest(input: String, caret: Int, limit: Int = 6): List<String> {
        val (_, tok) = nameAt(input, caret) ?: return emptyList()
        val p = tok.uppercase(Locale.ROOT)
        val all = Formula.KNOWN.filter { it.startsWith(p) }
        if (all.size == 1 && all[0] == p) return emptyList() // typed in full: the argument hint takes over
        return all.sortedWith(compareBy({ COMMON.indexOf(it).let { i -> if (i < 0) Int.MAX_VALUE else i } }, { it.length }, { it })).take(limit)
    }

    /** The text after taking function [name] for the name being typed: `=SU` → `=SUM(`, caret after the bracket. */
    fun complete(input: String, caret: Int, name: String): Pair<String, Int>? {
        val (a, _) = nameAt(input, caret) ?: return null
        val c = caret.coerceIn(0, input.length)
        val rest = input.substring(c)
        val bracket = !rest.startsWith("(")
        val text = input.substring(0, a) + name + (if (bracket) "(" else "") + rest
        return text to (a + name.length + 1)
    }

    /** Inside a function's brackets: (function name, index of the argument the caret is in), innermost call; else null. */
    fun argHint(input: String, caret: Int): Pair<String, Int>? {
        if (!input.startsWith("=")) return null
        val c = caret.coerceIn(0, input.length)
        // walk back from the caret: count commas at depth 0 until the unmatched "("
        var depth = 0
        var commas = 0
        var i = c - 1
        var quote = false
        while (i >= 1) {
            val ch = input[i]
            if (ch == '"') quote = !quote
            else if (!quote) when (ch) {
                ')' -> depth++
                '(' -> if (depth == 0) {
                    var a = i
                    while (a > 1 && (input[a - 1].isLetterOrDigit() || input[a - 1] == '.' || input[a - 1] == '_')) a--
                    val name = input.substring(a, i).uppercase(Locale.ROOT).removePrefix("_XLFN.")
                    return if (name in Formula.KNOWN) name to commas else null
                } else depth--
                ',' -> if (depth == 0) commas++
            }
            i--
        }
        return null
    }

    /** The argument names of [name] split for marking the [index]-th (a trailing "…" repeats the last named one). */
    fun argParts(name: String): List<String> = (SIGNATURES[name] ?: "").split(", ").filter { it.isNotEmpty() }

    private fun inQuotes(s: String, end: Int): Boolean { var q = false; for (i in 0 until end) if (s[i] == '"') q = !q; return q }
}
