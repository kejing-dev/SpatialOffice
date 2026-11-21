package io.embodit.spatialoffice.core.xlsx

import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale

/** A spreadsheet error value such as `#DIV/0!`. */
internal data class ErrVal(val code: String) {
    override fun toString(): String = code

    companion object {
        val DIV0 = ErrVal("#DIV/0!")
        val VALUE = ErrVal("#VALUE!")
        val REF = ErrVal("#REF!")
        val NAME = ErrVal("#NAME?")
        val NUM = ErrVal("#NUM!")
        val NA = ErrVal("#N/A")
        val NULL = ErrVal("#NULL!")

        /** Result of a formula that depends on itself. Not a file-format error code, so it is never written as a cached value. */
        val CYCLE = ErrVal("#CYCLE!")

        val STANDARD = listOf("#DIV/0!", "#VALUE!", "#REF!", "#NAME?", "#NUM!", "#N/A", "#NULL!")
    }
}

/**
 * One reference inside a formula: a cell, a rectangle, whole columns or whole rows, optionally sheet-qualified.
 * Coordinates are 0-based. [s]..[e] is the character span of the area text (after any `Sheet!` prefix) in the formula.
 */
internal data class RefTok(
    val sheet: String?,
    val r1: Int, val c1: Int, val r2: Int, val c2: Int,
    val absR1: Boolean, val absC1: Boolean, val absR2: Boolean, val absC2: Boolean,
    val kind: Int,
    val s: Int, val e: Int,
) {
    fun areaText(): String = when (kind) {
        CELL -> cellText(r1, c1, absR1, absC1)
        AREA -> cellText(r1, c1, absR1, absC1) + ":" + cellText(r2, c2, absR2, absC2)
        COLS -> dollar(absC1) + A1.colName(c1) + ":" + dollar(absC2) + A1.colName(c2)
        else -> dollar(absR1) + (r1 + 1) + ":" + dollar(absR2) + (r2 + 1)
    }

    companion object {
        const val CELL = 0
        const val AREA = 1
        const val COLS = 2
        const val ROWS = 3
        private fun dollar(abs: Boolean) = if (abs) "$" else ""
        fun cellText(r: Int, c: Int, absR: Boolean, absC: Boolean): String = dollar(absC) + A1.colName(c) + dollar(absR) + (r + 1)
    }
}

internal enum class TT { NUM, STR, BOOL, ERR, OP, LP, RP, COMMA, FUNC, REF, NAME, BAD }

internal class Tok(val t: TT, val s: Int, val e: Int, val text: String, val num: Double = 0.0, val ref: RefTok? = null)

internal object Lexer {
    private val CELL = Regex("(\\$?)([A-Za-z]{1,3})(\\$?)([0-9]{1,7})")
    private val COLS = Regex("(\\$?)([A-Za-z]{1,3}):(\\$?)([A-Za-z]{1,3})")
    private val ROWS = Regex("(\\$?)([0-9]{1,7}):(\\$?)([0-9]{1,7})")
    private val NUMBER = Regex("(?:[0-9]+\\.?[0-9]*|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?")
    private val IDENT = Regex("[\\p{L}_\\\\][\\p{L}\\p{N}_.\\\\?]*")
    private val SHEET = Regex("([\\p{L}_][\\p{L}\\p{N}_.]*)!")
    private val QSHEET = Regex("'((?:[^']|'')+)'!")

    private fun isIdentChar(c: Char) = c.isLetterOrDigit() || c == '_' || c == '.'

    /** A reference must not run straight into more name characters, a call parenthesis or a sheet separator. */
    private fun boundary(src: String, end: Int): Boolean =
        end >= src.length || !(isIdentChar(src[end]) || src[end] == '(' || src[end] == '!')

    private class Pt(val row: Int, val col: Int, val absR: Boolean, val absC: Boolean)

    private fun colIndex(letters: String): Int {
        var c = 0
        for (ch in letters) c = c * 26 + (ch.uppercaseChar() - 'A' + 1)
        return c - 1
    }

    private fun cellOf(m: MatchResult): Pt? {
        val col = colIndex(m.groupValues[2])
        val row = m.groupValues[4].toLong() - 1
        if (col > A1.MAX_COL || row < 0 || row > A1.MAX_ROW) return null
        return Pt(row.toInt(), col, m.groupValues[3] == "$", m.groupValues[1] == "$")
    }

    private fun area(src: String, i: Int, sheet: String?, tokStart: Int): Tok? {
        val m = CELL.matchAt(src, i)
        if (m != null) {
            val a = cellOf(m)
            if (a != null) {
                val end = m.range.last + 1
                if (end < src.length && src[end] == ':') {
                    val m2 = CELL.matchAt(src, end + 1)
                    val b = m2?.let { cellOf(it) }
                    if (m2 != null && b != null && boundary(src, m2.range.last + 1)) {
                        val e2 = m2.range.last + 1
                        val rowsSwap = a.row > b.row
                        val colsSwap = a.col > b.col
                        val ref = RefTok(
                            sheet,
                            if (rowsSwap) b.row else a.row, if (colsSwap) b.col else a.col,
                            if (rowsSwap) a.row else b.row, if (colsSwap) a.col else b.col,
                            if (rowsSwap) b.absR else a.absR, if (colsSwap) b.absC else a.absC,
                            if (rowsSwap) a.absR else b.absR, if (colsSwap) a.absC else b.absC,
                            RefTok.AREA, i, e2,
                        )
                        return Tok(TT.REF, tokStart, e2, src.substring(tokStart, e2), ref = ref)
                    }
                }
                if (boundary(src, end)) {
                    val ref = RefTok(sheet, a.row, a.col, a.row, a.col, a.absR, a.absC, a.absR, a.absC, RefTok.CELL, i, end)
                    return Tok(TT.REF, tokStart, end, src.substring(tokStart, end), ref = ref)
                }
            }
        }
        val mc = COLS.matchAt(src, i)
        if (mc != null && boundary(src, mc.range.last + 1)) {
            val c1 = colIndex(mc.groupValues[2])
            val c2 = colIndex(mc.groupValues[4])
            if (c1 <= A1.MAX_COL && c2 <= A1.MAX_COL) {
                val end = mc.range.last + 1
                val swap = c1 > c2
                val a1 = mc.groupValues[1] == "$"
                val a2 = mc.groupValues[3] == "$"
                val ref = RefTok(
                    sheet, 0, minOf(c1, c2), A1.MAX_ROW, maxOf(c1, c2), true, if (swap) a2 else a1, true, if (swap) a1 else a2,
                    RefTok.COLS, i, end,
                )
                return Tok(TT.REF, tokStart, end, src.substring(tokStart, end), ref = ref)
            }
        }
        val mr = ROWS.matchAt(src, i)
        if (mr != null && boundary(src, mr.range.last + 1)) {
            val r1 = mr.groupValues[2].toLong() - 1
            val r2 = mr.groupValues[4].toLong() - 1
            if (r1 in 0..A1.MAX_ROW && r2 in 0..A1.MAX_ROW) {
                val end = mr.range.last + 1
                val swap = r1 > r2
                val a1 = mr.groupValues[1] == "$"
                val a2 = mr.groupValues[3] == "$"
                val ref = RefTok(
                    sheet, minOf(r1, r2).toInt(), 0, maxOf(r1, r2).toInt(), A1.MAX_COL, if (swap) a2 else a1, true, if (swap) a1 else a2, true,
                    RefTok.ROWS, i, end,
                )
                return Tok(TT.REF, tokStart, end, src.substring(tokStart, end), ref = ref)
            }
        }
        return null
    }

    fun tokenize(src: String): List<Tok> {
        val out = ArrayList<Tok>()
        var i = 0
        val n = src.length
        while (i < n) {
            val c = src[i]
            if (c.isWhitespace()) { i++; continue }
            if (c == '"') {
                val sb = StringBuilder()
                var j = i + 1
                var closed = false
                while (j < n) {
                    if (src[j] == '"') {
                        if (j + 1 < n && src[j + 1] == '"') { sb.append('"'); j += 2; continue }
                        closed = true; j++; break
                    }
                    sb.append(src[j]); j++
                }
                out.add(Tok(if (closed) TT.STR else TT.BAD, i, j, sb.toString()))
                i = j
                continue
            }
            if (c == '#') {
                val hit = ErrVal.STANDARD.firstOrNull { src.regionMatches(i, it, 0, it.length, ignoreCase = true) }
                if (hit != null) { out.add(Tok(TT.ERR, i, i + hit.length, hit)); i += hit.length } else { out.add(Tok(TT.BAD, i, i + 1, "#")); i++ }
                continue
            }
            if (c == '\'') {
                val m = QSHEET.matchAt(src, i)
                if (m != null) {
                    val after = m.range.last + 1
                    val t = area(src, after, m.groupValues[1].replace("''", "'"), i)
                    if (t != null) { out.add(t); i = t.e; continue }
                    out.add(Tok(TT.BAD, i, after, src.substring(i, after))); i = after
                    continue
                }
                out.add(Tok(TT.BAD, i, i + 1, "'")); i++
                continue
            }
            if (c in '0'..'9' || (c == '.' && i + 1 < n && src[i + 1] in '0'..'9')) {
                val r = area(src, i, null, i)
                if (r != null && r.ref!!.kind == RefTok.ROWS) { out.add(r); i = r.e; continue }
                val m = NUMBER.matchAt(src, i)
                if (m != null) {
                    val end = m.range.last + 1
                    out.add(Tok(TT.NUM, i, end, m.value, num = m.value.toDouble())); i = end
                    continue
                }
            }
            if (c == '$' || c.isLetter() || c == '_' || c == '\\') {
                if (c != '$') {
                    val ms = SHEET.matchAt(src, i)
                    if (ms != null) {
                        val after = ms.range.last + 1
                        val t = area(src, after, ms.groupValues[1], i)
                        if (t != null) { out.add(t); i = t.e; continue }
                        out.add(Tok(TT.BAD, i, after, src.substring(i, after))); i = after
                        continue
                    }
                }
                val t = area(src, i, null, i)
                if (t != null) { out.add(t); i = t.e; continue }
                val mi = IDENT.matchAt(src, i)
                if (mi != null) {
                    val end = mi.range.last + 1
                    var k = end
                    while (k < n && src[k].isWhitespace()) k++
                    val name = mi.value
                    val up = name.uppercase(Locale.ROOT)
                    when {
                        k < n && src[k] == '(' -> out.add(Tok(TT.FUNC, i, end, up))
                        up == "TRUE" || up == "FALSE" -> out.add(Tok(TT.BOOL, i, end, up))
                        else -> out.add(Tok(TT.NAME, i, end, name))
                    }
                    i = end
                    continue
                }
                out.add(Tok(TT.BAD, i, i + 1, c.toString())); i++
                continue
            }
            if (i + 1 < n) {
                val two = src.substring(i, i + 2)
                if (two == "<=" || two == ">=" || two == "<>") { out.add(Tok(TT.OP, i, i + 2, two)); i += 2; continue }
            }
            when (c) {
                '+', '-', '*', '/', '^', '&', '=', '<', '>', '%' -> out.add(Tok(TT.OP, i, i + 1, c.toString()))
                '(' -> out.add(Tok(TT.LP, i, i + 1, "("))
                ')' -> out.add(Tok(TT.RP, i, i + 1, ")"))
                ',' -> out.add(Tok(TT.COMMA, i, i + 1, ","))
                else -> out.add(Tok(TT.BAD, i, i + 1, c.toString()))
            }
            i++
        }
        return out
    }
}

internal sealed class Node {
    class Num(val v: Double) : Node()
    class Str(val v: String) : Node()
    class Bool(val v: Boolean) : Node()
    class Err(val v: ErrVal) : Node()
    class Ref(val ref: RefTok) : Node()
    class Un(val neg: Boolean, val a: Node) : Node()
    class Pct(val a: Node) : Node()
    class Bin(val op: String, val a: Node, val b: Node) : Node()
    class Fn(val name: String, val args: List<Node>) : Node()
    class Name(val name: String) : Node()
    object Missing : Node()
}

/** A tokenised formula. [root] is null when the text is not something this module can parse. */
internal class Parsed(val text: String, val toks: List<Tok>, val root: Node?, val supported: Boolean) {
    val refs: List<RefTok> by lazy { toks.mapNotNull { it.ref } }
}

internal object Formula {
    /** Functions the evaluator implements. A formula using anything else keeps the cached value from the file. */
    val KNOWN: Set<String> = setOf(
        "SUM", "AVERAGE", "MIN", "MAX", "COUNT", "COUNTA", "IF", "ROUND", "ABS", "AND", "OR", "NOT", "INT",
        "IFERROR", "MOD", "SQRT", "POWER", "ROUNDUP", "ROUNDDOWN", "PRODUCT", "LEN", "CONCATENATE", "CONCAT",
        "UPPER", "LOWER", "TRIM", "TRUE", "FALSE",
        "COUNTIF", "SUMIF", "AVERAGEIF", "COUNTBLANK", "MEDIAN", "LARGE", "SMALL", "LEFT", "RIGHT", "MID",
        "SIGN", "CEILING", "FLOOR", "VLOOKUP", "HLOOKUP", "EXACT", "REPT",
        // v0.5.0 (M3 s27)
        "SUMIFS", "COUNTIFS", "AVERAGEIFS", "MAXIFS", "MINIFS",
        "INDEX", "MATCH", "XLOOKUP", "CHOOSE", "ROW", "COLUMN", "ROWS", "COLUMNS",
        "TODAY", "NOW", "DATE", "YEAR", "MONTH", "DAY", "WEEKDAY", "DATEDIF", "EDATE", "EOMONTH",
        "HOUR", "MINUTE", "SECOND", "TIME", "DAYS",
        "TEXT", "FIND", "SEARCH", "SUBSTITUTE", "VALUE", "TEXTJOIN", "REPLACE", "PROPER", "CHAR", "CODE",
        "STDEV", "STDEV.S", "STDEV.P", "VAR", "VAR.S", "VAR.P", "RANK", "RANK.EQ", "SUMPRODUCT",
        "IFS", "SWITCH", "XOR", "IFNA", "ISBLANK", "ISNUMBER", "ISTEXT", "ISERROR", "ISNA", "NA",
        "PI", "EXP", "LN", "LOG", "LOG10", "TRUNC",
    )

    /**
     * Functions newer than the 2007 file format: the file stores them as `_xlfn.NAME(` (what every spreadsheet writes
     * and expects; without the prefix they read as #NAME?). The formula bar shows them without it.
     */
    val FUTURE: Set<String> = setOf(
        "XLOOKUP", "TEXTJOIN", "MAXIFS", "MINIFS", "IFS", "SWITCH", "CONCAT", "STDEV.S", "STDEV.P", "VAR.S", "VAR.P",
        "RANK.EQ", "DAYS", "XOR", "IFNA",
    )

    private val PREFIXES = listOf("_XLFN._XLWS.", "_XLFN.", "_XLWS.")

    /** `_XLFN.XLOOKUP` → `XLOOKUP` (upper-case name as the lexer gives it). */
    fun baseName(up: String): String {
        for (p in PREFIXES) if (up.startsWith(p)) return up.substring(p.length)
        return up
    }

    /** The formula as the formula bar shows it: `_xlfn.` / `_xlws.` prefixes dropped. */
    fun display(text: String): String {
        if (text.indexOf("_xl", ignoreCase = true) < 0) return text
        val toks = Lexer.tokenize(text)
        val edits = ArrayList<Triple<Int, Int, String>>()
        for (t in toks) if (t.t == TT.FUNC) { val b = baseName(t.text); if (b != t.text) edits.add(Triple(t.s, t.e, b)) }
        return splice(text, edits)
    }

    private class Bad : RuntimeException() {
        override fun fillInStackTrace(): Throwable = this
    }

    private class P(val toks: List<Tok>) {
        var i = 0
        var depth = 0
        fun peek(): Tok? = toks.getOrNull(i)

        fun all(): Node {
            val n = expr(0)
            if (i != toks.size) throw Bad()
            return n
        }

        fun expr(min: Int): Node {
            if (++depth > 200) throw Bad()
            var left = unary()
            while (true) {
                val t = peek() ?: break
                if (t.t != TT.OP) break
                val prec = when (t.text) {
                    "=", "<>", "<", ">", "<=", ">=" -> 1
                    "&" -> 2
                    "+", "-" -> 3
                    "*", "/" -> 4
                    "^" -> 5
                    else -> -1
                }
                if (prec < 0 || prec < min) break
                i++
                val right = expr(prec + 1)
                left = Node.Bin(t.text, left, right)
            }
            depth--
            return left
        }

        fun unary(): Node {
            val t = peek() ?: throw Bad()
            if (t.t == TT.OP && (t.text == "-" || t.text == "+")) {
                i++
                if (++depth > 200) throw Bad()
                val a = unary()
                depth--
                return Node.Un(t.text == "-", a)
            }
            var n = primary()
            while (true) {
                val p = peek() ?: break
                if (p.t == TT.OP && p.text == "%") { i++; n = Node.Pct(n) } else break
            }
            return n
        }

        fun primary(): Node {
            val t = peek() ?: throw Bad()
            i++
            return when (t.t) {
                TT.NUM -> Node.Num(t.num)
                TT.STR -> Node.Str(t.text)
                TT.BOOL -> Node.Bool(t.text == "TRUE")
                TT.ERR -> Node.Err(ErrVal(t.text))
                TT.REF -> Node.Ref(t.ref!!)
                TT.NAME -> Node.Name(t.text)
                TT.LP -> {
                    val n = expr(0)
                    if (peek()?.t != TT.RP) throw Bad()
                    i++
                    n
                }
                TT.FUNC -> {
                    if (peek()?.t != TT.LP) throw Bad()
                    i++
                    val args = ArrayList<Node>()
                    if (peek()?.t == TT.RP) { i++; return Node.Fn(baseName(t.text), args) }
                    while (true) {
                        val p = peek() ?: throw Bad()
                        if (p.t == TT.COMMA) { args.add(Node.Missing); i++; if (peek()?.t == TT.RP) { args.add(Node.Missing); i++; break }; continue }
                        args.add(expr(0))
                        val q = peek() ?: throw Bad()
                        i++
                        if (q.t == TT.RP) break
                        if (q.t != TT.COMMA) throw Bad()
                        if (peek()?.t == TT.RP) { args.add(Node.Missing); i++; break }
                    }
                    Node.Fn(baseName(t.text), args)
                }
                else -> throw Bad()
            }
        }
    }

    private fun supported(n: Node): Boolean = when (n) {
        is Node.Name -> false
        is Node.Fn -> n.name in KNOWN && n.args.all { supported(it) }
        is Node.Un -> supported(n.a)
        is Node.Pct -> supported(n.a)
        is Node.Bin -> supported(n.a) && supported(n.b)
        else -> true
    }

    /** [text] is the formula without its leading `=`. */
    fun parse(text: String): Parsed {
        val toks = Lexer.tokenize(text)
        if (toks.isEmpty() || toks.any { it.t == TT.BAD }) return Parsed(text, toks, null, false)
        val root = try { P(toks).all() } catch (e: Bad) { null }
        return Parsed(text, toks, root, root != null && supported(root))
    }

    /** Upper-cases function names, references and TRUE / FALSE the way spreadsheets store them; everything else is kept. */
    fun normalize(text: String): String {
        val toks = Lexer.tokenize(text)
        val edits = ArrayList<Triple<Int, Int, String>>()
        for (t in toks) when (t.t) {
            TT.FUNC -> {
                val b = baseName(t.text)
                val want = when {
                    b != t.text -> t.text.substring(0, t.text.length - b.length).lowercase(Locale.ROOT) + b
                    b in FUTURE -> "_xlfn.$b"
                    else -> b
                }
                if (text.substring(t.s, t.e) != want) edits.add(Triple(t.s, t.e, want))
            }
            TT.BOOL -> if (text.substring(t.s, t.e) != t.text) edits.add(Triple(t.s, t.e, t.text))
            TT.REF -> {
                val r = t.ref!!
                val cur = text.substring(r.s, r.e)
                val up = cur.uppercase(Locale.ROOT)
                if (cur != up) edits.add(Triple(r.s, r.e, up))
            }
            else -> {}
        }
        return splice(text, edits)
    }

    private fun splice(text: String, edits: List<Triple<Int, Int, String>>): String {
        if (edits.isEmpty()) return text
        val sb = StringBuilder(text.length + 8)
        var pos = 0
        for ((s, e, rep) in edits) { sb.append(text, pos, s).append(rep); pos = e }
        sb.append(text, pos, text.length)
        return sb.toString()
    }

    /** Replaces the area text of each reference for which [fn] returns non-null. */
    fun rewriteRefs(text: String, toks: List<Tok>, fn: (RefTok) -> String?): String {
        var edits: ArrayList<Triple<Int, Int, String>>? = null
        for (t in toks) {
            val r = t.ref ?: continue
            val rep = fn(r) ?: continue
            if (rep == text.substring(r.s, r.e)) continue
            if (edits == null) edits = ArrayList()
            edits.add(Triple(r.s, r.e, rep))
        }
        return if (edits == null) text else splice(text, edits)
    }

    /**
     * Adjusts references into sheet [target] after a row was inserted at 0-based index [at] (rows ≥ [at] moved down by one).
     * [own] is the sheet the formula lives on (used for unqualified references; null = only qualified references apply).
     */
    fun shiftRows(text: String, toks: List<Tok>, own: String?, target: String, at: Int): String = shift(text, toks, own, target, false, at, 1)

    /**
     * Adjusts references into sheet [target] after one row ([cols] = false) or column was inserted at 0-based index
     * [at] ([delta] = 1: everything from [at] on moves by one) or deleted ([delta] = -1: [at] goes away, later ones move
     * back by one). A reference that pointed only at the deleted line becomes `#REF!`.
     */
    fun shift(text: String, toks: List<Tok>, own: String?, target: String, cols: Boolean, at: Int, delta: Int): String = rewriteRefs(text, toks) { r ->
        val sheet = r.sheet ?: own
        val skipKind = if (cols) RefTok.ROWS else RefTok.COLS
        if (sheet == null || !sheet.equals(target, ignoreCase = true) || r.kind == skipKind) null
        else {
            val a1 = if (cols) r.c1 else r.r1
            val a2 = if (cols) r.c2 else r.r2
            val max = if (cols) A1.MAX_COL else A1.MAX_ROW
            var n1 = a1
            var n2 = a2
            var gone = false
            if (delta > 0) {
                if (a1 >= at && a1 < max) n1 = a1 + 1
                if (a2 >= at && a2 < max) n2 = a2 + 1
            } else {
                if (a1 == at && a2 == at) gone = true
                if (a1 > at) n1 = a1 - 1
                if (a2 >= at && a2 > a1 || a2 > at) n2 = a2 - 1
                if (n2 < n1) gone = true
            }
            when {
                gone -> "#REF!"
                n1 == a1 && n2 == a2 -> null
                cols -> r.copy(c1 = n1, c2 = if (r.kind == RefTok.CELL) n1 else n2).areaText()
                else -> r.copy(r1 = n1, r2 = if (r.kind == RefTok.CELL) n1 else n2).areaText()
            }
        }
    }

    /**
     * A formula copied ([dRow], [dCol]) cells away: relative row / column parts move, `$` parts stay, a reference pushed
     * off the sheet becomes `#REF!`. Works with or without the leading `=` and returns the same form.
     */
    fun offset(formula: String, dRow: Int, dCol: Int): String {
        val eq = formula.startsWith("=")
        val body = if (eq) formula.substring(1) else formula
        val out = translate(body, dRow, dCol)
        return if (eq) "=$out" else out
    }

    /**
     * Rewrites the sheet part of every reference qualified with sheet [old] (case-insensitive): to [newName] (quoted
     * as needed), or, when [newName] is null (the sheet was deleted), the whole reference becomes `#REF!`.
     */
    fun renameSheetRefs(text: String, old: String, newName: String?): String {
        val toks = Lexer.tokenize(text)
        val edits = ArrayList<Triple<Int, Int, String>>()
        for (t in toks) {
            val r = t.ref ?: continue
            if (r.sheet == null || !r.sheet.equals(old, ignoreCase = true)) continue
            if (newName == null) edits.add(Triple(t.s, r.e, "#REF!"))
            else edits.add(Triple(t.s, r.s, Charts.quoteSheet(newName) + "!"))
        }
        return splice(text, edits)
    }

    /** Expands a shared formula: relative coordinates move by ([dRow], [dCol]), absolute ones stay. */
    fun translate(text: String, dRow: Int, dCol: Int): String {
        if (dRow == 0 && dCol == 0) return text
        val toks = Lexer.tokenize(text)
        return rewriteRefs(text, toks) { r ->
            val rowsFixed = r.kind == RefTok.COLS
            val colsFixed = r.kind == RefTok.ROWS
            val n1 = if (rowsFixed || r.absR1) r.r1 else r.r1 + dRow
            val n2 = if (rowsFixed || r.absR2) r.r2 else r.r2 + dRow
            val m1 = if (colsFixed || r.absC1) r.c1 else r.c1 + dCol
            val m2 = if (colsFixed || r.absC2) r.c2 else r.c2 + dCol
            if (n1 < 0 || n2 < 0 || m1 < 0 || m2 < 0 || n1 > A1.MAX_ROW || n2 > A1.MAX_ROW || m1 > A1.MAX_COL || m2 > A1.MAX_COL) "#REF!"
            else r.copy(r1 = n1, r2 = n2, c1 = m1, c2 = m2).areaText()
        }
    }
}

internal interface CellHost {
    /** Index of the sheet called [name] (case-insensitive), or -1. */
    fun sheetIndex(name: String): Int
    fun cellValue(sheet: Int, row: Int, col: Int): Any?
    /** Values of the non-empty cells of a rectangle, row by row. */
    fun rangeValues(sheet: Int, r1: Int, c1: Int, r2: Int, c2: Int): List<Any?>
    /** Rows and columns in use on [sheet], so whole-column references are walked only as far as there is data. */
    fun used(sheet: Int): IntArray = intArrayOf(A1.MAX_ROW + 1, A1.MAX_COL + 1)
    /** The workbook counts dates from 1904 (date functions need it). */
    fun date1904(): Boolean = false
    /** Today's date and the time of day (TODAY / NOW); tests pin it. */
    fun now(): java.time.LocalDateTime = java.time.LocalDateTime.now()
}

internal class RangeV(val sheet: Int, val r1: Int, val c1: Int, val r2: Int, val c2: Int)

/**
 * Evaluates a parsed formula. Values are `null` (empty), `Double`, `String`, `Boolean` or [ErrVal]. Semantics follow
 * the common spreadsheet rules: `%` binds tightest, then unary minus, `^`, `* /`, `+ -`, `&`, comparisons; numbers
 * sort before text before booleans; in a range argument text and blanks are skipped by the numeric aggregates.
 */
internal class Evaluator(private val host: CellHost) {
    private class Ctx(val sheet: Int, val row: Int, val col: Int)

    fun evaluate(root: Node, sheet: Int, row: Int, col: Int): Any? {
        val x = Ctx(sheet, row, col)
        return when (val v = scalar(eval(root, x), x)) {
            null -> 0.0
            is Double -> clean(v)
            else -> v
        }
    }

    private fun clean(d: Double): Any = if (d.isNaN() || d.isInfinite()) ErrVal.NUM else if (d == 0.0) 0.0 else d

    private fun scalar(v: Any?, x: Ctx): Any? {
        if (v !is RangeV) return v
        if (v.r1 == v.r2 && v.c1 == v.c2) return host.cellValue(v.sheet, v.r1, v.c1)
        if (v.c1 == v.c2 && x.row in v.r1..v.r2) return host.cellValue(v.sheet, x.row, v.c1)
        if (v.r1 == v.r2 && x.col in v.c1..v.c2) return host.cellValue(v.sheet, v.r1, x.col)
        return ErrVal.VALUE
    }

    private fun value(n: Node, x: Ctx): Any? = scalar(eval(n, x), x)

    private fun eval(n: Node, x: Ctx): Any? = when (n) {
        is Node.Num -> n.v
        is Node.Str -> n.v
        is Node.Bool -> n.v
        is Node.Err -> n.v
        is Node.Missing -> null
        is Node.Name -> ErrVal.NAME
        is Node.Ref -> {
            val si = if (n.ref.sheet == null) x.sheet else host.sheetIndex(n.ref.sheet)
            if (si < 0) ErrVal.REF else RangeV(si, n.ref.r1, n.ref.c1, n.ref.r2, n.ref.c2)
        }
        is Node.Un -> when (val v = num(value(n.a, x))) {
            is ErrVal -> v
            else -> if (n.neg) -(v as Double) else v
        }
        is Node.Pct -> when (val v = num(value(n.a, x))) {
            is ErrVal -> v
            else -> (v as Double) / 100.0
        }
        is Node.Bin -> bin(n, x)
        is Node.Fn -> fn(n, x)
    }

    private fun num(v: Any?): Any = when (v) {
        null -> 0.0
        is Double -> v
        is Boolean -> if (v) 1.0 else 0.0
        is String -> Num.parse(v) ?: ErrVal.VALUE
        is ErrVal -> v
        else -> ErrVal.VALUE
    }

    private fun text(v: Any?): String = when (v) {
        null -> ""
        is Double -> Num.input(v)
        is Boolean -> if (v) "TRUE" else "FALSE"
        else -> v.toString()
    }

    private fun bool(v: Any?): Any = when (v) {
        null -> false
        is Boolean -> v
        is Double -> v != 0.0
        is String -> when (v.uppercase(Locale.ROOT)) { "TRUE" -> true; "FALSE" -> false; else -> ErrVal.VALUE }
        is ErrVal -> v
        else -> ErrVal.VALUE
    }

    private fun rank(v: Any?): Int = when (v) { is Double -> 0; is String -> 1; is Boolean -> 2; else -> 3 }

    private fun compare(a0: Any?, b0: Any?): Int {
        var a = a0
        var b = b0
        if (a == null) a = when (b) { is String -> ""; is Boolean -> false; else -> 0.0 }
        if (b == null) b = when (a) { is String -> ""; is Boolean -> false; else -> 0.0 }
        val ra = rank(a)
        val rb = rank(b)
        if (ra != rb) return ra.compareTo(rb)
        return when (a) {
            is Double -> a.compareTo(b as Double)
            is String -> a.compareTo(b as String, ignoreCase = true)
            is Boolean -> a.compareTo(b as Boolean)
            else -> 0
        }
    }

    private fun bin(n: Node.Bin, x: Ctx): Any? {
        val a = value(n.a, x)
        val b = value(n.b, x)
        when (n.op) {
            "&" -> {
                if (a is ErrVal) return a
                if (b is ErrVal) return b
                return text(a) + text(b)
            }
            "=", "<>", "<", ">", "<=", ">=" -> {
                if (a is ErrVal) return a
                if (b is ErrVal) return b
                val c = compare(a, b)
                return when (n.op) { "=" -> c == 0; "<>" -> c != 0; "<" -> c < 0; ">" -> c > 0; "<=" -> c <= 0; else -> c >= 0 }
            }
        }
        val p = num(a)
        if (p is ErrVal) return p
        val q = num(b)
        if (q is ErrVal) return q
        p as Double; q as Double
        return when (n.op) {
            "+" -> clean(p + q)
            "-" -> clean(p - q)
            "*" -> clean(p * q)
            "/" -> if (q == 0.0) ErrVal.DIV0 else clean(p / q)
            "^" -> if (p == 0.0 && q == 0.0) ErrVal.NUM else if (p == 0.0 && q < 0) ErrVal.DIV0 else clean(Math.pow(p, q))
            else -> ErrVal.VALUE
        }
    }

    /** Collects the numbers of aggregate arguments; returns the first error met, else null. */
    private fun nums(args: List<Node>, x: Ctx, out: ArrayList<Double>): ErrVal? {
        for (a in args) {
            if (a === Node.Missing) continue
            val v = eval(a, x)
            if (v is RangeV) {
                for (cv in host.rangeValues(v.sheet, v.r1, v.c1, v.r2, v.c2)) {
                    if (cv is Double) out.add(cv) else if (cv is ErrVal) return cv
                }
            } else when (v) {
                is Double -> out.add(v)
                is ErrVal -> return v
                is Boolean -> out.add(if (v) 1.0 else 0.0)
                is String -> out.add(Num.parse(v) ?: return ErrVal.VALUE)
                else -> {}
            }
        }
        return null
    }

    private fun round(d: Double, places: Double, mode: RoundingMode): Any {
        if (d.isNaN() || d.isInfinite()) return ErrVal.NUM
        val k = places.toInt().coerceIn(-300, 300)
        return clean(BigDecimal(d.toString()).setScale(k, mode).toDouble())
    }

    private fun fn(n: Node.Fn, x: Ctx): Any? {
        val a = n.args
        when (n.name) {
            "SUM", "AVERAGE", "MIN", "MAX", "PRODUCT" -> {
                val xs = ArrayList<Double>()
                nums(a, x, xs)?.let { return it }
                return when (n.name) {
                    "SUM" -> { var s = 0.0; for (d in xs) s += d; clean(s) }
                    "PRODUCT" -> { var s = 1.0; for (d in xs) s *= d; if (xs.isEmpty()) 0.0 else clean(s) }
                    "AVERAGE" -> if (xs.isEmpty()) ErrVal.DIV0 else { var s = 0.0; for (d in xs) s += d; clean(s / xs.size) }
                    "MIN" -> if (xs.isEmpty()) 0.0 else xs.min()
                    else -> if (xs.isEmpty()) 0.0 else xs.max()
                }
            }
            "COUNT", "COUNTA" -> {
                val all = n.name == "COUNTA"
                var c = 0
                for (arg in a) {
                    if (arg === Node.Missing) continue
                    val v = eval(arg, x)
                    if (v is RangeV) {
                        for (cv in host.rangeValues(v.sheet, v.r1, v.c1, v.r2, v.c2)) if (all || cv is Double) c++
                    } else if (all) {
                        if (v != null) c++
                    } else when (v) {
                        is Double, is Boolean -> c++
                        is String -> if (Num.parse(v) != null) c++
                        else -> {}
                    }
                }
                return c.toDouble()
            }
            "IF" -> {
                if (a.size < 2 || a.size > 3) return ErrVal.VALUE
                val c = bool(value(a[0], x))
                if (c is ErrVal) return c
                return if (c == true) value(a[1], x) ?: 0.0 else if (a.size == 3) value(a[2], x) ?: 0.0 else false
            }
            "IFERROR" -> {
                if (a.size != 2) return ErrVal.VALUE
                val v = value(a[0], x)
                return if (v is ErrVal) value(a[1], x) ?: "" else v
            }
            "AND", "OR" -> {
                val and = n.name == "AND"
                var seen = false
                var acc = and
                for (arg in a) {
                    if (arg === Node.Missing) continue
                    val v = eval(arg, x)
                    if (v is RangeV) {
                        for (cv in host.rangeValues(v.sheet, v.r1, v.c1, v.r2, v.c2)) {
                            val b = when (cv) { is Boolean -> cv; is Double -> cv != 0.0; is ErrVal -> return cv; else -> null } ?: continue
                            seen = true
                            acc = if (and) acc && b else acc || b
                        }
                    } else {
                        val b = bool(v)
                        if (b is ErrVal) return b
                        seen = true
                        acc = if (and) acc && (b as Boolean) else acc || (b as Boolean)
                    }
                }
                return if (seen) acc else ErrVal.VALUE
            }
            "NOT" -> {
                if (a.size != 1) return ErrVal.VALUE
                val b = bool(value(a[0], x))
                return if (b is ErrVal) b else !(b as Boolean)
            }
            "TRUE" -> return if (a.isEmpty()) true else ErrVal.VALUE
            "FALSE" -> return if (a.isEmpty()) false else ErrVal.VALUE
            "ABS", "INT", "SQRT" -> {
                if (a.size != 1) return ErrVal.VALUE
                val v = num(value(a[0], x))
                if (v is ErrVal) return v
                v as Double
                return when (n.name) {
                    "ABS" -> Math.abs(v)
                    "INT" -> clean(Math.floor(v))
                    else -> if (v < 0) ErrVal.NUM else Math.sqrt(v)
                }
            }
            "ROUND", "ROUNDUP", "ROUNDDOWN" -> {
                if (a.isEmpty() || a.size > 2) return ErrVal.VALUE
                val v = num(value(a[0], x))
                if (v is ErrVal) return v
                val p = if (a.size == 2) num(value(a[1], x)) else 0.0
                if (p is ErrVal) return p
                val mode = when (n.name) { "ROUND" -> RoundingMode.HALF_UP; "ROUNDUP" -> RoundingMode.UP; else -> RoundingMode.DOWN }
                return round(v as Double, p as Double, mode)
            }
            "MOD", "POWER" -> {
                if (a.size != 2) return ErrVal.VALUE
                val p = num(value(a[0], x))
                if (p is ErrVal) return p
                val q = num(value(a[1], x))
                if (q is ErrVal) return q
                p as Double; q as Double
                return if (n.name == "MOD") {
                    if (q == 0.0) ErrVal.DIV0 else clean(p - q * Math.floor(p / q))
                } else {
                    if (p == 0.0 && q == 0.0) ErrVal.NUM else if (p == 0.0 && q < 0) ErrVal.DIV0 else clean(Math.pow(p, q))
                }
            }
            "LEN", "UPPER", "LOWER", "TRIM" -> {
                if (a.size != 1) return ErrVal.VALUE
                val v = value(a[0], x)
                if (v is ErrVal) return v
                val s = text(v)
                return when (n.name) {
                    "LEN" -> s.length.toDouble()
                    "UPPER" -> s.uppercase(Locale.ROOT)
                    "LOWER" -> s.lowercase(Locale.ROOT)
                    else -> s.trim(' ').replace(Regex(" {2,}"), " ")
                }
            }
            "CONCATENATE", "CONCAT" -> {
                val sb = StringBuilder()
                for (arg in a) {
                    if (arg === Node.Missing) continue
                    val v = eval(arg, x)
                    if (v is RangeV && n.name == "CONCAT") {
                        for (cv in host.rangeValues(v.sheet, v.r1, v.c1, v.r2, v.c2)) { if (cv is ErrVal) return cv; sb.append(text(cv)) }
                    } else {
                        val s = scalar(v, x)
                        if (s is ErrVal) return s
                        sb.append(text(s))
                    }
                }
                return sb.toString()
            }
            "COUNTIF", "SUMIF", "AVERAGEIF" -> {
                val need = if (n.name == "COUNTIF") 2 else 2..3
                if ((need is Int && a.size != need) || (need is IntRange && a.size !in need)) return ErrVal.VALUE
                val rng = eval(a[0], x) as? RangeV ?: return ErrVal.VALUE
                val crit = value(a[1], x)
                if (crit is ErrVal) return crit
                val test = criterion(crit)
                val sumR = if (a.size == 3) (eval(a[2], x) as? RangeV ?: return ErrVal.VALUE) else rng
                var count = 0
                var sum = 0.0
                for ((dr, dc) in cellsOf(rng)) {
                    val v = host.cellValue(rng.sheet, rng.r1 + dr, rng.c1 + dc)
                    if (!test(v)) continue
                    if (n.name == "COUNTIF") { count++; continue }
                    val s = host.cellValue(sumR.sheet, sumR.r1 + dr, sumR.c1 + dc)
                    if (s is ErrVal) return s
                    if (s is Double) { sum += s; count++ }
                }
                return when (n.name) {
                    "COUNTIF" -> count.toDouble()
                    "SUMIF" -> clean(sum)
                    else -> if (count == 0) ErrVal.DIV0 else clean(sum / count)
                }
            }
            "COUNTBLANK" -> {
                if (a.size != 1) return ErrVal.VALUE
                val rng = eval(a[0], x) as? RangeV ?: return ErrVal.VALUE
                val total = (rng.r2 - rng.r1 + 1).toLong() * (rng.c2 - rng.c1 + 1)
                val filled = host.rangeValues(rng.sheet, rng.r1, rng.c1, rng.r2, rng.c2).count { it != "" }
                return (total - filled).toDouble()
            }
            "MEDIAN" -> {
                val xs = ArrayList<Double>()
                nums(a, x, xs)?.let { return it }
                if (xs.isEmpty()) return ErrVal.NUM
                xs.sort()
                val m = xs.size / 2
                return if (xs.size % 2 == 1) xs[m] else clean((xs[m - 1] + xs[m]) / 2)
            }
            "LARGE", "SMALL" -> {
                if (a.size != 2) return ErrVal.VALUE
                val xs = ArrayList<Double>()
                nums(listOf(a[0]), x, xs)?.let { return it }
                val k = num(value(a[1], x))
                if (k is ErrVal) return k
                val i = Math.ceil(k as Double).toInt()
                if (i < 1 || i > xs.size) return ErrVal.NUM
                xs.sort()
                return if (n.name == "SMALL") xs[i - 1] else xs[xs.size - i]
            }
            "LEFT", "RIGHT", "MID" -> {
                if (n.name == "MID" && a.size != 3) return ErrVal.VALUE
                if (n.name != "MID" && (a.isEmpty() || a.size > 2)) return ErrVal.VALUE
                val v = value(a[0], x)
                if (v is ErrVal) return v
                val s = text(v)
                if (n.name == "MID") {
                    val st = num(value(a[1], x)); if (st is ErrVal) return st
                    val ln = num(value(a[2], x)); if (ln is ErrVal) return ln
                    val from = (st as Double).toInt(); val len = (ln as Double).toInt()
                    if (from < 1 || len < 0) return ErrVal.VALUE
                    if (from > s.length) return ""
                    return s.substring(from - 1, minOf(s.length, from - 1 + len))
                }
                val k = if (a.size == 2) num(value(a[1], x)) else 1.0
                if (k is ErrVal) return k
                val c = (k as Double).toInt()
                if (c < 0) return ErrVal.VALUE
                return if (n.name == "LEFT") s.take(c) else s.takeLast(c)
            }
            "SIGN" -> {
                if (a.size != 1) return ErrVal.VALUE
                val v = num(value(a[0], x)); if (v is ErrVal) return v
                return Math.signum(v as Double).let { if (it == 0.0) 0.0 else it }
            }
            "CEILING", "FLOOR" -> {
                if (a.isEmpty() || a.size > 2) return ErrVal.VALUE
                val v = num(value(a[0], x)); if (v is ErrVal) return v
                val sig = if (a.size == 2) num(value(a[1], x)) else 1.0
                if (sig is ErrVal) return sig
                v as Double; sig as Double
                if (sig == 0.0) return if (n.name == "FLOOR") ErrVal.DIV0 else 0.0
                if (v > 0 && sig < 0) return ErrVal.NUM
                val q = v / sig
                val r = if (n.name == "CEILING") Math.ceil(q - 1e-12) else Math.floor(q + 1e-12)
                return clean(BigDecimal(r).multiply(BigDecimal(sig.toString())).toDouble())
            }
            "VLOOKUP", "HLOOKUP" -> {
                if (a.size < 3 || a.size > 4) return ErrVal.VALUE
                val key = value(a[0], x)
                if (key is ErrVal) return key
                val tbl = eval(a[1], x) as? RangeV ?: return ErrVal.VALUE
                val idx = num(value(a[2], x)); if (idx is ErrVal) return idx
                val k = (idx as Double).toInt()
                val approx = if (a.size == 4) bool(value(a[3], x)).let { if (it is ErrVal) return it; it as Boolean } else true
                val v = n.name == "VLOOKUP"
                val used = host.used(tbl.sheet)
                val lastLine = if (v) minOf(tbl.r2, used[0] - 1) else minOf(tbl.c2, used[1] - 1)
                val first = if (v) tbl.r1 else tbl.c1
                if (k < 1 || k > (if (v) tbl.c2 - tbl.c1 + 1 else tbl.r2 - tbl.r1 + 1)) return ErrVal.REF
                var hit = -1
                for (i in first..lastLine) {
                    val cv = if (v) host.cellValue(tbl.sheet, i, tbl.c1) else host.cellValue(tbl.sheet, tbl.r1, i)
                    if (cv == null) continue
                    val c = compare(cv, key)
                    if (!approx) { if (c == 0 && rank(cv) == rank(key)) { hit = i; break } }
                    else if (rank(cv) == rank(key)) { if (c <= 0) hit = i else break }
                }
                if (hit < 0) return ErrVal("#N/A")
                return (if (v) host.cellValue(tbl.sheet, hit, tbl.c1 + k - 1) else host.cellValue(tbl.sheet, tbl.r1 + k - 1, hit)) ?: 0.0
            }
            "EXACT" -> {
                if (a.size != 2) return ErrVal.VALUE
                val p = value(a[0], x); if (p is ErrVal) return p
                val q = value(a[1], x); if (q is ErrVal) return q
                return text(p) == text(q)
            }
            "REPT" -> {
                if (a.size != 2) return ErrVal.VALUE
                val p = value(a[0], x); if (p is ErrVal) return p
                val q = num(value(a[1], x)); if (q is ErrVal) return q
                val c = (q as Double).toInt()
                if (c < 0 || c.toLong() * text(p).length > 32767) return ErrVal.VALUE
                return text(p).repeat(c)
            }
        }
        return fn3(n, x)
    }

    // ---- v0.5.0 (M3 s27): conditional aggregates, lookups, dates, text, statistics, logic, maths ----

    private fun numArg(a: List<Node>, i: Int, x: Ctx, def: Double? = null): Any {
        if (i >= a.size || a[i] === Node.Missing) return def ?: ErrVal.VALUE
        return num(value(a[i], x))
    }

    private fun dims(r: RangeV): Pair<Int, Int> {
        val used = host.used(r.sheet)
        return Pair(minOf(r.r2, used[0] - 1) - r.r1 + 1, minOf(r.c2, used[1] - 1) - r.c1 + 1)
    }

    /** SUMIFS-style: (offset row, offset col) pairs of [first]'s shape for which every (range, criterion) pair holds. */
    private fun ifsHits(first: RangeV, pairs: List<Node>, x: Ctx): Any {
        if (pairs.isEmpty() || pairs.size % 2 != 0) return ErrVal.VALUE
        val tests = ArrayList<Pair<RangeV, (Any?) -> Boolean>>()
        for (k in pairs.indices step 2) {
            val r = eval(pairs[k], x) as? RangeV ?: return ErrVal.VALUE
            if (r.r2 - r.r1 != first.r2 - first.r1 || r.c2 - r.c1 != first.c2 - first.c1) return ErrVal.VALUE
            val c = value(pairs[k + 1], x)
            if (c is ErrVal) return c
            tests.add(Pair(r, criterion(c)))
        }
        // walk as far as any of the ranges has data
        var rows = 0; var cols = 0
        for (r in listOf(first) + tests.map { it.first }) { val (h, w) = dims(r); rows = maxOf(rows, h); cols = maxOf(cols, w) }
        rows = minOf(rows, first.r2 - first.r1 + 1); cols = minOf(cols, first.c2 - first.c1 + 1)
        val out = ArrayList<Pair<Int, Int>>()
        for (dr in 0 until rows) for (dc in 0 until cols) {
            if (tests.all { (r, t) -> t(host.cellValue(r.sheet, r.r1 + dr, r.c1 + dc)) }) out.add(Pair(dr, dc))
        }
        return out
    }

    private fun lookupList(r: RangeV): List<Any?> {
        val (h, w) = dims(r)
        val out = ArrayList<Any?>()
        if (r.r1 != r.r2) for (i in 0 until h) out.add(host.cellValue(r.sheet, r.r1 + i, r.c1))
        else for (i in 0 until w) out.add(host.cellValue(r.sheet, r.r1, r.c1 + i))
        return out
    }

    private fun wildcard(p: String): Regex = Regex(
        p.split('*').joinToString(".*") { part -> part.split('?').joinToString(".") { Regex.escape(it) } }, RegexOption.IGNORE_CASE,
    )

    /** Position of [key] in [list]: mode 0 exact, -1 exact or next smaller, 1 exact or next larger, 2 wildcard; [reverse] searches from the end. */
    private fun findIn(list: List<Any?>, key: Any?, mode: Int, reverse: Boolean): Int {
        val idx = if (reverse) list.indices.reversed() else list.indices
        val re = if (mode == 2 && key is String) wildcard(key) else null
        var best = -1
        for (i in idx) {
            val v = list[i] ?: continue
            if (re != null) { if (v is String && re.matches(v)) return i; continue }
            if (rank(v) != rank(key)) continue
            val c = compare(v, key)
            if (c == 0) return i
            if (mode == -1 && c < 0 && (best < 0 || compare(v, list[best]) > 0)) best = i
            if (mode == 1 && c > 0 && (best < 0 || compare(v, list[best]) < 0)) best = i
        }
        return best
    }

    private val d1904: Boolean get() = host.date1904()

    /** Serial date number → days since 1970-01-01 (the 1900 system's fake 29 February counts as 1 March). */
    private fun unixDays(serial: Double): Long {
        val s = Math.floor(serial).toLong()
        return if (d1904) s - 24107 else if (s < 61) s - 25568 else s - 25569
    }

    private fun serialOf(days: Long): Double {
        if (d1904) return (days + 24107).toDouble()
        val s = days + 25569
        return (if (s < 61) s - 1 else s).toDouble()
    }

    /** (year, month, day) of a serial; 60 in the 1900 system is the fake 1900-02-29. */
    private fun ymd(serial: Double): IntArray =
        if (!d1904 && Math.floor(serial) == 60.0) intArrayOf(1900, 2, 29) else Num.civil(unixDays(serial))

    private fun daysInMonth(y: Int, m: Int): Int = (Num.daysFromCivil(if (m == 12) y + 1 else y, if (m == 12) 1 else m + 1, 1) - Num.daysFromCivil(y, m, 1)).toInt()

    /** DATE(y, m, d) with months and days rolling over as in every spreadsheet. */
    private fun dateSerial(y0: Int, m0: Int, d0: Int): Any {
        var y = y0
        if (y in 0..1899) y += 1900
        if (y < 0 || y > 9999) return ErrVal.NUM
        val total = y.toLong() * 12 + (m0 - 1)
        val yy = Math.floorDiv(total, 12L).toInt()
        val mm = Math.floorMod(total, 12L).toInt() + 1
        val days = Num.daysFromCivil(yy, mm, 1) + (d0 - 1)
        val s = serialOf(days)
        return if (s < 0 || s > 2958465) ErrVal.NUM else s
    }

    private fun dateArg(a: List<Node>, i: Int, x: Ctx): Any {
        if (i >= a.size) return ErrVal.VALUE
        return when (val v = value(a[i], x)) {
            is String -> Num.parse(v) ?: parseDate(v) ?: ErrVal.VALUE
            else -> num(v).let { if (it is Double && it < 0) ErrVal.NUM else it }
        }
    }

    private fun parseDate(s: String): Double? {
        val m = Regex("([0-9]{4})[-/]([0-9]{1,2})[-/]([0-9]{1,2})").matchEntire(s.trim()) ?: return null
        return Num.serial(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt(), d1904)
    }

    private fun addMonths(serial: Double, months: Int, endOfMonth: Boolean): Any {
        val (y, m, d) = ymd(serial).let { Triple(it[0], it[1], it[2]) }
        val total = y.toLong() * 12 + (m - 1) + months
        val yy = Math.floorDiv(total, 12L).toInt()
        val mm = Math.floorMod(total, 12L).toInt() + 1
        if (yy < 1900 || yy > 9999) return ErrVal.NUM
        val dim = daysInMonth(yy, mm)
        return serialOf(Num.daysFromCivil(yy, mm, if (endOfMonth) dim else minOf(d, dim)))
    }

    private fun stats(args: List<Node>, x: Ctx): Any {
        val xs = ArrayList<Double>()
        nums(args, x, xs)?.let { return it }
        return xs
    }

    private fun variance(xs: List<Double>, sample: Boolean): Double? {
        val n = xs.size
        if (n == 0 || (sample && n < 2)) return null
        val mean = xs.sum() / n
        var ss = 0.0
        for (v in xs) ss += (v - mean) * (v - mean)
        return ss / (if (sample) n - 1 else n)
    }

    @Suppress("UNCHECKED_CAST")
    private fun fn3(n: Node.Fn, x: Ctx): Any? {
        val a = n.args
        when (n.name) {
            "SUMIFS", "AVERAGEIFS", "MAXIFS", "MINIFS" -> {
                val target = (if (a.isEmpty()) null else eval(a[0], x)) as? RangeV ?: return ErrVal.VALUE
                val hits = ifsHits(target, a.drop(1), x)
                if (hits is ErrVal) return hits
                var sum = 0.0; var cnt = 0; var mx = Double.NEGATIVE_INFINITY; var mn = Double.POSITIVE_INFINITY
                for ((dr, dc) in hits as List<Pair<Int, Int>>) {
                    val v = host.cellValue(target.sheet, target.r1 + dr, target.c1 + dc)
                    if (v is ErrVal) return v
                    if (v is Double) { sum += v; cnt++; mx = maxOf(mx, v); mn = minOf(mn, v) }
                }
                return when (n.name) {
                    "SUMIFS" -> clean(sum)
                    "AVERAGEIFS" -> if (cnt == 0) ErrVal.DIV0 else clean(sum / cnt)
                    "MAXIFS" -> if (cnt == 0) 0.0 else mx
                    else -> if (cnt == 0) 0.0 else mn
                }
            }
            "COUNTIFS" -> {
                val first = (if (a.isEmpty()) null else eval(a[0], x)) as? RangeV ?: return ErrVal.VALUE
                val hits = ifsHits(first, a, x)
                if (hits is ErrVal) return hits
                return (hits as List<*>).size.toDouble()
            }
            "INDEX" -> {
                if (a.size !in 2..3) return ErrVal.VALUE
                val r = eval(a[0], x) as? RangeV ?: return ErrVal.VALUE
                val p = numArg(a, 1, x, 0.0); if (p is ErrVal) return p
                val q = numArg(a, 2, x, 0.0); if (q is ErrVal) return q
                var ri = (p as Double).toInt(); var ci = (q as Double).toInt()
                val h = r.r2 - r.r1 + 1; val w = r.c2 - r.c1 + 1
                if (a.size == 2 && h == 1 && w > 1) { ci = ri; ri = 1 }
                if (ri < 0 || ci < 0 || ri > h || ci > w) return ErrVal.REF
                if (ri == 0 && ci == 0) return r
                if (ri == 0) return RangeV(r.sheet, r.r1, r.c1 + ci - 1, r.r2, r.c1 + ci - 1)
                if (ci == 0) return if (w == 1) RangeV(r.sheet, r.r1 + ri - 1, r.c1, r.r1 + ri - 1, r.c1) else RangeV(r.sheet, r.r1 + ri - 1, r.c1, r.r1 + ri - 1, r.c2)
                return RangeV(r.sheet, r.r1 + ri - 1, r.c1 + ci - 1, r.r1 + ri - 1, r.c1 + ci - 1)
            }
            "MATCH" -> {
                if (a.size !in 2..3) return ErrVal.VALUE
                val key = value(a[0], x); if (key is ErrVal) return key
                val r = eval(a[1], x) as? RangeV ?: return ErrVal.NA
                val t = numArg(a, 2, x, 1.0); if (t is ErrVal) return t
                val type = Math.signum(t as Double).toInt()
                val list = lookupList(r)
                val i = when (type) {
                    0 -> findIn(list, key, if (key is String && (key.contains('*') || key.contains('?'))) 2 else 0, false)
                    1 -> { var hit = -1; for ((k, v) in list.withIndex()) { if (v == null || rank(v) != rank(key)) continue; if (compare(v, key) <= 0) hit = k else break }; hit }
                    else -> { var hit = -1; for ((k, v) in list.withIndex()) { if (v == null || rank(v) != rank(key)) continue; if (compare(v, key) >= 0) hit = k else break }; hit }
                }
                return if (i < 0) ErrVal.NA else (i + 1).toDouble()
            }
            "XLOOKUP" -> {
                if (a.size !in 3..6) return ErrVal.VALUE
                val key = value(a[0], x); if (key is ErrVal) return key
                val look = eval(a[1], x) as? RangeV ?: return ErrVal.VALUE
                val ret = eval(a[2], x) as? RangeV ?: return ErrVal.VALUE
                val mode = numArg(a, 4, x, 0.0); if (mode is ErrVal) return mode
                val search = numArg(a, 5, x, 1.0); if (search is ErrVal) return search
                val vertical = look.r1 != look.r2
                val i = findIn(lookupList(look), key, (mode as Double).toInt(), (search as Double) < 0)
                if (i < 0) return if (a.size >= 4 && a[3] !== Node.Missing) value(a[3], x) ?: "" else ErrVal.NA
                return if (vertical) RangeV(ret.sheet, ret.r1 + i, ret.c1, ret.r1 + i, ret.c1) else RangeV(ret.sheet, ret.r1, ret.c1 + i, ret.r1, ret.c1 + i)
            }
            "CHOOSE" -> {
                if (a.size < 2) return ErrVal.VALUE
                val k = num(value(a[0], x)); if (k is ErrVal) return k
                val i = (k as Double).toInt()
                if (i < 1 || i >= a.size) return ErrVal.VALUE
                return eval(a[i], x)
            }
            "ROW", "COLUMN" -> {
                if (a.size > 1) return ErrVal.VALUE
                if (a.isEmpty() || a[0] === Node.Missing) return ((if (n.name == "ROW") x.row else x.col) + 1).toDouble()
                val r = eval(a[0], x) as? RangeV ?: return ErrVal.VALUE
                return ((if (n.name == "ROW") r.r1 else r.c1) + 1).toDouble()
            }
            "ROWS", "COLUMNS" -> {
                if (a.size != 1) return ErrVal.VALUE
                val r = eval(a[0], x) as? RangeV ?: return 1.0
                return (if (n.name == "ROWS") r.r2 - r.r1 + 1 else r.c2 - r.c1 + 1).toDouble()
            }
            "TODAY", "NOW" -> {
                if (a.isNotEmpty()) return ErrVal.VALUE
                val t = host.now()
                val day = serialOf(Num.daysFromCivil(t.year, t.monthValue, t.dayOfMonth))
                return if (n.name == "TODAY") day else day + (t.hour * 3600 + t.minute * 60 + t.second) / 86400.0
            }
            "DATE" -> {
                if (a.size != 3) return ErrVal.VALUE
                val y = numArg(a, 0, x); if (y is ErrVal) return y
                val m = numArg(a, 1, x); if (m is ErrVal) return m
                val d = numArg(a, 2, x); if (d is ErrVal) return d
                return dateSerial((y as Double).toInt(), (m as Double).toInt(), (d as Double).toInt())
            }
            "YEAR", "MONTH", "DAY" -> {
                if (a.size != 1) return ErrVal.VALUE
                val s = dateArg(a, 0, x); if (s is ErrVal) return s
                val c = ymd(s as Double)
                return (when (n.name) { "YEAR" -> c[0]; "MONTH" -> c[1]; else -> c[2] }).toDouble()
            }
            "WEEKDAY" -> {
                if (a.size !in 1..2) return ErrVal.VALUE
                val s = dateArg(a, 0, x); if (s is ErrVal) return s
                val t = numArg(a, 1, x, 1.0); if (t is ErrVal) return t
                val mon0 = Math.floorMod(unixDays(s as Double) + 3, 7L).toInt() // Monday = 0
                return when ((t as Double).toInt()) {
                    1, 17 -> ((mon0 + 1) % 7 + 1).toDouble()
                    2, 11 -> (mon0 + 1).toDouble()
                    3 -> mon0.toDouble()
                    else -> ErrVal.NUM
                }
            }
            "DATEDIF" -> {
                if (a.size != 3) return ErrVal.VALUE
                val s = dateArg(a, 0, x); if (s is ErrVal) return s
                val e = dateArg(a, 1, x); if (e is ErrVal) return e
                val u = value(a[2], x); if (u is ErrVal) return u
                s as Double; e as Double
                if (s > e) return ErrVal.NUM
                val p = ymd(s); val q = ymd(e)
                val months = (q[0] - p[0]) * 12 + (q[1] - p[1]) - (if (q[2] < p[2]) 1 else 0)
                return when (text(u).uppercase(Locale.ROOT)) {
                    "Y" -> (months / 12).toDouble()
                    "M" -> months.toDouble()
                    "D" -> (Math.floor(e) - Math.floor(s))
                    "YM" -> (months % 12).toDouble()
                    "MD" -> if (q[2] >= p[2]) (q[2] - p[2]).toDouble() else {
                        val pm = if (q[1] == 1) 12 else q[1] - 1
                        val py = if (q[1] == 1) q[0] - 1 else q[0]
                        (daysInMonth(py, pm) - p[2] + q[2]).toDouble().coerceAtLeast(0.0)
                    }
                    "YD" -> {
                        var y = q[0]
                        var start = Num.daysFromCivil(y, p[1], minOf(p[2], daysInMonth(y, p[1])))
                        val end = Num.daysFromCivil(q[0], q[1], q[2])
                        if (start > end) { y--; start = Num.daysFromCivil(y, p[1], minOf(p[2], daysInMonth(y, p[1]))) }
                        (end - start).toDouble()
                    }
                    else -> ErrVal.NUM
                }
            }
            "EDATE", "EOMONTH" -> {
                if (a.size != 2) return ErrVal.VALUE
                val s = dateArg(a, 0, x); if (s is ErrVal) return s
                val m = numArg(a, 1, x); if (m is ErrVal) return m
                return addMonths(s as Double, (m as Double).toInt(), n.name == "EOMONTH")
            }
            "HOUR", "MINUTE", "SECOND" -> {
                if (a.size != 1) return ErrVal.VALUE
                val s = dateArg(a, 0, x); if (s is ErrVal) return s
                s as Double
                val secs = Math.round((s - Math.floor(s)) * 86400.0) % 86400
                return (when (n.name) { "HOUR" -> secs / 3600; "MINUTE" -> secs / 60 % 60; else -> secs % 60 }).toDouble()
            }
            "TIME" -> {
                if (a.size != 3) return ErrVal.VALUE
                val h = numArg(a, 0, x); if (h is ErrVal) return h
                val m = numArg(a, 1, x); if (m is ErrVal) return m
                val s = numArg(a, 2, x); if (s is ErrVal) return s
                val total = (h as Double).toLong() * 3600 + (m as Double).toLong() * 60 + (s as Double).toLong()
                if (total < 0) return ErrVal.NUM
                return clean((total % 86400) / 86400.0)
            }
            "DAYS" -> {
                if (a.size != 2) return ErrVal.VALUE
                val e = dateArg(a, 0, x); if (e is ErrVal) return e
                val s = dateArg(a, 1, x); if (s is ErrVal) return s
                return clean(Math.floor(e as Double) - Math.floor(s as Double))
            }
            "TEXT" -> {
                if (a.size != 2) return ErrVal.VALUE
                val v = value(a[0], x); if (v is ErrVal) return v
                val f = value(a[1], x); if (f is ErrVal) return f
                val d = when (v) { is Double -> v; is Boolean -> return text(v); null -> 0.0; else -> Num.parse(v.toString()) ?: return v.toString() }
                return TextFormat.format(d, text(f), d1904)
            }
            "FIND", "SEARCH" -> {
                if (a.size !in 2..3) return ErrVal.VALUE
                val f = value(a[0], x); if (f is ErrVal) return f
                val w = value(a[1], x); if (w is ErrVal) return w
                val st = numArg(a, 2, x, 1.0); if (st is ErrVal) return st
                val from = (st as Double).toInt()
                val needle = text(f); val hay = text(w)
                if (from < 1 || from > hay.length + 1) return ErrVal.VALUE
                val i = if (n.name == "FIND") hay.indexOf(needle, from - 1)
                else if (needle.contains('*') || needle.contains('?')) wildcard(needle).find(hay, from - 1)?.range?.first ?: -1
                else hay.indexOf(needle, from - 1, ignoreCase = true)
                return if (i < 0) ErrVal.VALUE else (i + 1).toDouble()
            }
            "SUBSTITUTE" -> {
                if (a.size !in 3..4) return ErrVal.VALUE
                val t = value(a[0], x); if (t is ErrVal) return t
                val o = value(a[1], x); if (o is ErrVal) return o
                val nw = value(a[2], x); if (nw is ErrVal) return nw
                val s = text(t); val old = text(o); val rep = text(nw)
                if (old.isEmpty()) return s
                if (a.size == 3) return s.replace(old, rep)
                val k = numArg(a, 3, x); if (k is ErrVal) return k
                val nth = (k as Double).toInt()
                if (nth < 1) return ErrVal.VALUE
                var idx = -1
                repeat(nth) { idx = s.indexOf(old, idx + 1); if (idx < 0) return s }
                return s.substring(0, idx) + rep + s.substring(idx + old.length)
            }
            "VALUE" -> {
                if (a.size != 1) return ErrVal.VALUE
                return when (val v = value(a[0], x)) {
                    is ErrVal -> v
                    is Double -> v
                    null -> 0.0
                    else -> {
                        val t = text(v).trim().replace(",", "")
                        Num.parse(t) ?: (if (t.endsWith("%")) Num.parse(t.dropLast(1))?.div(100.0) else null) ?: parseDate(t) ?: ErrVal.VALUE
                    }
                }
            }
            "TEXTJOIN" -> {
                if (a.size < 3) return ErrVal.VALUE
                val dl = value(a[0], x); if (dl is ErrVal) return dl
                val ig = bool(value(a[1], x)); if (ig is ErrVal) return ig
                val parts = ArrayList<String>()
                for (arg in a.drop(2)) {
                    if (arg === Node.Missing) continue
                    val v = eval(arg, x)
                    if (v is RangeV) {
                        val (h, w) = dims(v)
                        for (dr in 0 until h) for (dc in 0 until w) {
                            val cv = host.cellValue(v.sheet, v.r1 + dr, v.c1 + dc)
                            if (cv is ErrVal) return cv
                            val s = text(cv)
                            if (s.isNotEmpty() || ig == false) parts.add(s)
                        }
                    } else {
                        if (v is ErrVal) return v
                        val s = text(v)
                        if (s.isNotEmpty() || ig == false) parts.add(s)
                    }
                }
                val out = parts.joinToString(text(dl))
                return if (out.length > 32767) ErrVal.VALUE else out
            }
            "REPLACE" -> {
                if (a.size != 4) return ErrVal.VALUE
                val t = value(a[0], x); if (t is ErrVal) return t
                val st = numArg(a, 1, x); if (st is ErrVal) return st
                val ln = numArg(a, 2, x); if (ln is ErrVal) return ln
                val nw = value(a[3], x); if (nw is ErrVal) return nw
                val s = text(t); val from = (st as Double).toInt(); val len = (ln as Double).toInt()
                if (from < 1 || len < 0) return ErrVal.VALUE
                val i = minOf(from - 1, s.length)
                return s.substring(0, i) + text(nw) + s.substring(minOf(s.length, i + len))
            }
            "PROPER" -> {
                if (a.size != 1) return ErrVal.VALUE
                val v = value(a[0], x); if (v is ErrVal) return v
                val sb = StringBuilder()
                var prevLetter = false
                for (ch in text(v)) { sb.append(if (prevLetter) ch.lowercaseChar() else ch.uppercaseChar()); prevLetter = ch.isLetter() }
                return sb.toString()
            }
            "CHAR" -> {
                val k = numArg(a, 0, x); if (k is ErrVal) return k
                val c = (k as Double).toInt()
                return if (a.size != 1 || c < 1 || c > 255) ErrVal.VALUE else c.toChar().toString()
            }
            "CODE" -> {
                if (a.size != 1) return ErrVal.VALUE
                val v = value(a[0], x); if (v is ErrVal) return v
                val s = text(v)
                return if (s.isEmpty()) ErrVal.VALUE else s.codePointAt(0).toDouble()
            }
            "STDEV", "STDEV.S", "STDEV.P", "VAR", "VAR.S", "VAR.P" -> {
                val xs = stats(a, x); if (xs is ErrVal) return xs
                val v = variance(xs as List<Double>, !n.name.endsWith(".P")) ?: return ErrVal.DIV0
                return clean(if (n.name.startsWith("STDEV")) Math.sqrt(v) else v)
            }
            "RANK", "RANK.EQ" -> {
                if (a.size !in 2..3) return ErrVal.VALUE
                val v = numArg(a, 0, x); if (v is ErrVal) return v
                val xs = stats(listOf(a[1]), x); if (xs is ErrVal) return xs
                val o = numArg(a, 2, x, 0.0); if (o is ErrVal) return o
                xs as List<Double>; v as Double
                if (xs.none { it == v }) return ErrVal.NA
                return (1 + (if ((o as Double) == 0.0) xs.count { it > v } else xs.count { it < v })).toDouble()
            }
            "SUMPRODUCT" -> {
                if (a.isEmpty()) return ErrVal.VALUE
                val rs = a.map { eval(it, x) }
                if (rs.any { it is ErrVal }) return rs.first { it is ErrVal }
                if (rs.all { it !is RangeV }) {
                    var p = 1.0
                    for (r in rs) { val d = num(r); if (d is ErrVal) return d; p *= d as Double }
                    return clean(p)
                }
                val ranges = rs.map { it as? RangeV ?: return ErrVal.VALUE }
                val h = ranges[0].r2 - ranges[0].r1; val w = ranges[0].c2 - ranges[0].c1
                if (ranges.any { it.r2 - it.r1 != h || it.c2 - it.c1 != w }) return ErrVal.VALUE
                var rows = 0; var cols = 0
                for (r in ranges) { val (hh, ww) = dims(r); rows = maxOf(rows, hh); cols = maxOf(cols, ww) }
                var sum = 0.0
                for (dr in 0 until minOf(rows, h + 1)) for (dc in 0 until minOf(cols, w + 1)) {
                    var p = 1.0
                    for (r in ranges) {
                        val cv = host.cellValue(r.sheet, r.r1 + dr, r.c1 + dc)
                        if (cv is ErrVal) return cv
                        p *= (cv as? Double) ?: 0.0
                    }
                    sum += p
                }
                return clean(sum)
            }
            "IFS" -> {
                if (a.isEmpty() || a.size % 2 != 0) return ErrVal.VALUE
                for (k in a.indices step 2) {
                    val c = bool(value(a[k], x)); if (c is ErrVal) return c
                    if (c == true) return value(a[k + 1], x) ?: 0.0
                }
                return ErrVal.NA
            }
            "SWITCH" -> {
                if (a.size < 3) return ErrVal.VALUE
                val v = value(a[0], x); if (v is ErrVal) return v
                var k = 1
                while (k + 1 < a.size) {
                    val c = value(a[k], x); if (c is ErrVal) return c
                    if (rank(c) == rank(v) && compare(c, v) == 0) return value(a[k + 1], x) ?: 0.0
                    k += 2
                }
                return if (k < a.size) value(a[k], x) ?: 0.0 else ErrVal.NA
            }
            "XOR" -> {
                var seen = false; var acc = false
                for (arg in a) {
                    if (arg === Node.Missing) continue
                    val v = eval(arg, x)
                    if (v is RangeV) {
                        for (cv in host.rangeValues(v.sheet, v.r1, v.c1, v.r2, v.c2)) {
                            val b = when (cv) { is Boolean -> cv; is Double -> cv != 0.0; is ErrVal -> return cv; else -> null } ?: continue
                            seen = true; acc = acc xor b
                        }
                    } else {
                        val b = bool(v); if (b is ErrVal) return b
                        seen = true; acc = acc xor (b as Boolean)
                    }
                }
                return if (seen) acc else ErrVal.VALUE
            }
            "IFNA" -> {
                if (a.size != 2) return ErrVal.VALUE
                val v = value(a[0], x)
                return if (v == ErrVal.NA) value(a[1], x) ?: "" else v ?: 0.0
            }
            "ISBLANK", "ISNUMBER", "ISTEXT", "ISERROR", "ISNA" -> {
                if (a.size != 1) return ErrVal.VALUE
                val v = value(a[0], x)
                return when (n.name) {
                    "ISBLANK" -> v == null
                    "ISNUMBER" -> v is Double
                    "ISTEXT" -> v is String
                    "ISERROR" -> v is ErrVal
                    else -> v == ErrVal.NA
                }
            }
            "NA" -> return if (a.isEmpty()) ErrVal.NA else ErrVal.VALUE
            "PI" -> return if (a.isEmpty()) Math.PI else ErrVal.VALUE
            "EXP", "LN", "LOG10" -> {
                if (a.size != 1) return ErrVal.VALUE
                val v = numArg(a, 0, x); if (v is ErrVal) return v
                v as Double
                return when (n.name) {
                    "EXP" -> clean(Math.exp(v))
                    "LN" -> if (v <= 0) ErrVal.NUM else Math.log(v)
                    else -> if (v <= 0) ErrVal.NUM else Math.log10(v)
                }
            }
            "LOG" -> {
                if (a.size !in 1..2) return ErrVal.VALUE
                val v = numArg(a, 0, x); if (v is ErrVal) return v
                val b = numArg(a, 1, x, 10.0); if (b is ErrVal) return b
                v as Double; b as Double
                if (v <= 0 || b <= 0) return ErrVal.NUM
                if (b == 1.0) return ErrVal.DIV0
                return clean(if (b == 10.0) Math.log10(v) else Math.log(v) / Math.log(b))
            }
            "TRUNC" -> {
                if (a.size !in 1..2) return ErrVal.VALUE
                val v = numArg(a, 0, x); if (v is ErrVal) return v
                val p = numArg(a, 1, x, 0.0); if (p is ErrVal) return p
                return round(v as Double, p as Double, RoundingMode.DOWN)
            }
        }
        return ErrVal.NAME
    }

    /** (row offset, col offset) of every cell in [r] that lies inside the sheet's used area. */
    private fun cellsOf(r: RangeV): Sequence<Pair<Int, Int>> {
        val used = host.used(r.sheet)
        val r2 = minOf(r.r2, used[0] - 1)
        val c2 = minOf(r.c2, used[1] - 1)
        return sequence { for (rr in r.r1..r2) for (cc in r.c1..c2) yield(Pair(rr - r.r1, cc - r.c1)) }
    }

    /** COUNTIF-style test: "&gt;5", "&lt;=3", "&lt;&gt;x", "=x", plain values, `*` and `?` wildcards in text. */
    private fun criterion(c: Any?): (Any?) -> Boolean {
        if (c is Double || c is Boolean) return { v -> v != null && rank(v) == rank(c) && compare(v, c) == 0 }
        val s = text(c)
        val op = listOf("<=", ">=", "<>", "<", ">", "=").firstOrNull { s.startsWith(it) } ?: ""
        val rest = s.substring(op.length)
        val numArg = Num.parse(rest)
        if (op.isEmpty() || op == "=" || op == "<>") {
            val eq: (Any?) -> Boolean
            if (numArg != null) eq = { v -> v is Double && v == numArg }
            else if (rest.isEmpty()) eq = { v -> v == null || v == "" }
            else if (rest.contains('*') || rest.contains('?')) {
                val pattern = rest.split('*').joinToString(".*") { part -> part.split('?').joinToString(".") { Regex.escape(it) } }
                val re = Regex(pattern, RegexOption.IGNORE_CASE)
                eq = { v -> v is String && re.matches(v) }
            } else eq = { v -> v is String && v.equals(rest, ignoreCase = true) }
            return if (op == "<>") { v -> !eq(v) } else eq
        }
        val target: Any = numArg ?: rest
        return { v ->
            if (v == null || rank(v) != rank(target)) false
            else { val k = compare(v, target); when (op) { "<" -> k < 0; ">" -> k > 0; "<=" -> k <= 0; else -> k >= 0 } }
        }
    }
}
