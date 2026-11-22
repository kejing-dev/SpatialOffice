package io.embodit.spatialoffice.core.xlsx

import io.embodit.spatialoffice.core.csv.Csv
import io.embodit.spatialoffice.core.docx.CompatKind
import io.embodit.spatialoffice.core.docx.CompatNote
import io.embodit.spatialoffice.core.pkg.ContentTypes
import io.embodit.spatialoffice.core.pkg.OoxmlPackage
import io.embodit.spatialoffice.core.pkg.Rels
import io.embodit.spatialoffice.core.pkg.Xml
import java.math.BigDecimal
import java.util.BitSet
import java.util.Locale
import java.util.TreeMap

/**
 * A spreadsheet file opened for viewing and editing.
 *
 * The file's XML is never re-serialised. Each sheet keeps the text it was read from plus a cell model that remembers
 * where every `<row>` and `<c>` sits in that text; [save] splices new text only into the ranges of cells that changed
 * (or inserts a cell / row that did not exist) and leaves every other character and every other part as it was.
 * Edited text is written as an inline string, so the shared-strings part is never rewritten. After [save] the saved
 * state becomes the new baseline and [dirty] is false again.
 *
 * Not thread-safe: use one instance from one thread at a time.
 */
class XlsxWorkbook private constructor(private val pkg: OoxmlPackage) {

    // ---- model ----

    private class CellRec(val sheet: Int, var row: Int, var col: Int, val src: Xml.Elem?) {
        val origRow = row
        val origCol = col
        var style = 0
        /** Literal value, or for a formula its evaluated (or cached) result. null = empty. */
        var value: Any? = null
        /** Formula text without the leading `=`. */
        var formula: String? = null
        var parsed: Parsed? = null
        var precs: Array<IntArray>? = null
        /** The formula is not evaluated here (unknown function, array formula, unreadable): [value] is the file's cached result. */
        var opaque = false
        var forcedOpaque = false
        var hadCache = false
        var state = DONE
        /** Value right after loading; a formula's cached `<v>` is rewritten only when its value differs from this. */
        var baseValue: Any? = null
        var contentChanged = false
        var styleChanged = false
        var sharedSi = -1
        var sharedMaster = false
        var fSrc: Xml.Elem? = null
    }

    private class RowRec(var index: Int, val src: Xml.Elem?) {
        val origIndex = index
        val cells = TreeMap<Int, CellRec>()
        var style = 0
        var touched = false
        /** Row height in points as read (`ht`); [htChanged] when set here. */
        var ht: Float? = null
        var htChanged = false
        /** v0.6.0 (s42): `hidden` as read / set here; [hiddenChanged] when set here. */
        var hidden = false
        var hiddenChanged = false
    }

    /** One `<col>` span; [attrs] = all its attributes as read (or set here), in order, for writing it back. */
    private class ColInfo(val min: Int, val max: Int, val width: Float?, val style: Int, val attrs: LinkedHashMap<String, String> = LinkedHashMap()) {
        /** v0.6.0 (s42): the span is hidden (`hidden="1"`). */
        val hidden: Boolean get() = attrs["hidden"].let { it == "1" || it == "true" }
    }

    private class Sheet(val index: Int, val name: String, val part: String, val xml: String) {
        val rows = TreeMap<Int, RowRec>()
        var root: Xml.Elem? = null
        var rootKids: List<Xml.Elem> = emptyList()
        var sheetData: Xml.Elem? = null
        var pfx = ""
        val cols = ArrayList<ColInfo>()
        var defaultWidth = 8.43f
        var defaultHeight = 15f
        /** Column widths were changed here: `<cols>` is rewritten from [cols] on save. */
        var colsChanged = false
        /** Rows were inserted or reordered: the whole `<sheetData>` content is regenerated on save. */
        var structural = false
        /** Row / column insertions and deletions since load, in order: [axis (0 rows, 1 columns), at, delta]. */
        val ops = ArrayList<IntArray>()
        /** Frozen top rows as read from the file; [freezeSet] when changed here. */
        var frozen = 0
        /** v0.6.0 (s30): frozen left columns as read (`xSplit` of a frozen pane). */
        var frozenCols = 0
        var freezeSet = false
        var pendingDrawingRid: String? = null
        var usedRows = -1
        var usedCols = -1
        /** v0.5.0 (s25): merged ranges once merged / unmerged here (null = as in the file, after [ops]); written on save when [mergesChanged]. */
        var merges: ArrayList<IntArray>? = null
        var mergesChanged = false
    }

    private class Area(val sheet: Int, val r1: Int, val c1: Int, val r2: Int, val c2: Int)

    private class Defer(val sheet: Int, val cell: CellRec) : RuntimeException() {
        override fun fillInStackTrace(): Throwable = this
    }

    private var sheets: List<Sheet> = emptyList()
    private var sheetIndexByName: Map<String, Int> = emptyMap()
    private var shared: List<String> = emptyList()
    private var styles: Styles? = null
    private var stylesPart: String? = null
    private var wbPart = "xl/workbook.xml"
    private var date1904 = false
    private var chartDefs: List<ChartDef>? = null
    private var formulaLayoutChanged = false
    private val editNotes = LinkedHashMap<String, Int>()
    private var depth = 0

    /** Every cell that holds a formula, in sheet order (later additions at the end). */
    private val formulaCells = LinkedHashSet<CellRec>()

    /** True when there are edits that [save] has not written yet. */
    var dirty: Boolean = false
        private set

    /** Currency sign used by [setFmt] with [NumFmt.CURRENCY]. A sign typed into [setCell] ("$12.50") wins for that cell. */
    var currencySymbol: String = "¥"

    private val host = object : CellHost {
        override fun sheetIndex(name: String): Int = sheetIndexByName[name.lowercase(Locale.ROOT)] ?: -1

        override fun cellValue(sheet: Int, row: Int, col: Int): Any? {
            val c = sheets.getOrNull(sheet)?.rows?.get(row)?.cells?.get(col) ?: return null
            return valueOf(sheet, c)
        }

        override fun used(sheet: Int): IntArray =
            if (sheet in sheets.indices) intArrayOf(rows(sheet), cols(sheet)) else intArrayOf(0, 0)

        override fun date1904(): Boolean = this@XlsxWorkbook.date1904

        override fun rangeValues(sheet: Int, r1: Int, c1: Int, r2: Int, c2: Int): List<Any?> {
            val out = ArrayList<Any?>()
            val s = sheets.getOrNull(sheet) ?: return out
            for (r in s.rows.subMap(r1, true, r2, true).values) {
                for (c in r.cells.subMap(c1, true, c2, true).values) {
                    val v = valueOf(sheet, c)
                    if (v != null) out.add(v)
                }
            }
            return out
        }
    }
    private val evaluator = Evaluator(host)

    init { load() }

    // ---- loading ----

    private fun load() {
        chartDefs = null
        formulaLayoutChanged = false
        val rootRels = Rels.of(pkg, "")
        wbPart = rootRels.firstOrNull { it.type.endsWith("/officeDocument") }?.let { OoxmlPackage.resolve("", it.target) } ?: "xl/workbook.xml"
        val wbXml = pkg.text(wbPart) ?: throw IllegalArgumentException("not a spreadsheet file: no workbook part")
        val wbRoot = Xml.root(wbXml) ?: throw IllegalArgumentException("not a spreadsheet file: empty workbook part")
        val rels = Rels.of(pkg, wbPart)
        val byId = rels.associateBy { it.id }

        date1904 = XmlX.kid(wbXml, wbRoot, "workbookPr")?.let { Xml.attr(wbXml, it, "date1904") }.let { it == "1" || it == "true" }
        shared = rels.firstOrNull { it.type.endsWith("/sharedStrings") }?.let { pkg.text(OoxmlPackage.resolve(wbPart, it.target)) }?.let { loadShared(it) } ?: emptyList()
        stylesPart = rels.firstOrNull { it.type.endsWith("/styles") }?.let { OoxmlPackage.resolve(wbPart, it.target) }?.takeIf { pkg.has(it) }
        styles = stylesPart?.let { Styles(pkg.text(it)!!) }

        val list = ArrayList<Sheet>()
        val sheetsEl = XmlX.kid(wbXml, wbRoot, "sheets")
        if (sheetsEl != null) for (e in Xml.children(wbXml, sheetsEl)) {
            if (Xml.local(e.name) != "sheet") continue
            val name = Xml.attr(wbXml, e, "name") ?: continue
            val rel = XmlX.prefixedAttr(wbXml, e, "id")?.let { byId[it] } ?: continue
            if (rel.external || !rel.type.endsWith("/worksheet")) continue
            val part = OoxmlPackage.resolve(wbPart, rel.target)
            val xml = pkg.text(part) ?: continue
            list.add(parseSheet(list.size, name, part, xml))
        }
        if (list.isEmpty()) throw IllegalArgumentException("not a spreadsheet file: no worksheets")
        sheets = list
        sheetIndexByName = list.withIndex().associate { it.value.name.lowercase(Locale.ROOT) to it.index }

        formulaCells.clear()
        for (s in sheets) for (r in s.rows.values) for (c in r.cells.values) {
            if (c.formula == null) continue
            formulaCells.add(c)
            prepare(c)
            if (c.opaque) {
                c.state = DONE
                if (!c.hadCache) c.value = ErrVal.NAME
            } else c.state = TODO
        }
        evaluateAll()
        for (c in formulaCells) c.baseValue = c.value
    }

    private fun loadShared(xml: String): List<String> {
        val root = Xml.root(xml) ?: return emptyList()
        return Xml.children(xml, root).filter { Xml.local(it.name) == "si" }.map { richText(xml, it) }
    }

    /** Text of an `<si>` / `<is>`: plain `<t>` or the runs of rich text; phonetic runs are skipped. */
    private fun richText(xml: String, si: Xml.Elem): String {
        val sb = StringBuilder()
        for (k in Xml.children(xml, si)) when (Xml.local(k.name)) {
            "t" -> sb.append(Xml.text(xml, k))
            "r" -> for (t in Xml.children(xml, k)) if (Xml.local(t.name) == "t") sb.append(Xml.text(xml, t))
        }
        return decodeEscapes(sb.toString())
    }

    private fun parseSheet(index: Int, name: String, part: String, xml: String): Sheet {
        val s = Sheet(index, name, part, xml)
        val root = Xml.root(xml) ?: return s
        s.root = root
        s.rootKids = Xml.children(xml, root)
        for (k in s.rootKids) when (Xml.local(k.name)) {
            "sheetFormatPr" -> {
                Xml.attr(xml, k, "defaultColWidth")?.toFloatOrNull()?.let { s.defaultWidth = it }
                Xml.attr(xml, k, "defaultRowHeight")?.toFloatOrNull()?.let { s.defaultHeight = it }
            }
            "cols" -> for (c in Xml.children(xml, k)) {
                val a = Xml.attrs(xml, c)
                val min = a["min"]?.toIntOrNull() ?: continue
                val max = a["max"]?.toIntOrNull() ?: min
                val raw = LinkedHashMap<String, String>()
                for (sp in XmlX.attrSpans(xml, c)) raw[sp.name] = Xml.unescape(xml.substring(sp.valStart, sp.valEnd))
                s.cols.add(ColInfo(min - 1, max - 1, a["width"]?.toFloatOrNull(), a["style"]?.toIntOrNull() ?: 0, raw))
            }
            "sheetData" -> if (s.sheetData == null) { s.sheetData = k; s.pfx = XmlX.prefixOf(k.name); parseRows(s, k) }
            "sheetViews" -> XmlX.kid(xml, k, "sheetView")?.let { v -> XmlX.kid(xml, v, "pane") }?.let { pane ->
                val a = Xml.attrs(xml, pane)
                if (a["state"] == "frozen" || a["state"] == "frozenSplit") {
                    s.frozen = a["ySplit"]?.toDoubleOrNull()?.toInt() ?: 0
                    s.frozenCols = a["xSplit"]?.toDoubleOrNull()?.toInt() ?: 0
                }
            }
        }
        return s
    }

    private fun parseRows(s: Sheet, sd: Xml.Elem) {
        val xml = s.xml
        var nextRow = 0
        val masters = HashMap<Int, CellRec>()
        val dependents = ArrayList<CellRec>()
        for (re in Xml.children(xml, sd)) {
            if (Xml.local(re.name) != "row") continue
            val ra = Xml.attrs(xml, re)
            val idx = ra["r"]?.trim()?.toIntOrNull()?.minus(1) ?: nextRow
            if (idx < 0 || idx > A1.MAX_ROW || s.rows.containsKey(idx)) continue
            nextRow = idx + 1
            val row = RowRec(idx, re)
            if (ra["customFormat"].let { it == "1" || it == "true" }) row.style = ra["s"]?.toIntOrNull() ?: 0
            row.ht = ra["ht"]?.toFloatOrNull()
            row.hidden = ra["hidden"].let { it == "1" || it == "true" }
            s.rows[idx] = row
            var nextCol = 0
            for (ce in Xml.children(xml, re)) {
                if (Xml.local(ce.name) != "c") continue
                val ca = Xml.attrs(xml, ce)
                val col = ca["r"]?.let { A1.parse(it) }?.second ?: nextCol
                nextCol = col + 1
                if (col > A1.MAX_COL || row.cells.containsKey(col)) continue
                val c = CellRec(s.index, idx, col, ce)
                c.style = ca["s"]?.toIntOrNull() ?: 0
                var vText: String? = null
                var isText: String? = null
                if (!ce.selfClosing) for (k in Xml.children(xml, ce)) when (Xml.local(k.name)) {
                    "v" -> vText = Xml.text(xml, k)
                    "is" -> isText = richText(xml, k)
                    "f" -> {
                        c.fSrc = k
                        val fa = Xml.attrs(xml, k)
                        val text = Xml.text(xml, k)
                        when (fa["t"]) {
                            "shared" -> {
                                c.sharedSi = fa["si"]?.toIntOrNull() ?: -1
                                if (text.isNotEmpty()) {
                                    c.formula = text
                                    if (c.sharedSi >= 0) { c.sharedMaster = true; masters[c.sharedSi] = c }
                                } else if (c.sharedSi >= 0) dependents.add(c)
                            }
                            "array", "dataTable" -> if (text.isNotEmpty()) { c.formula = text; c.forcedOpaque = true }
                            else -> if (text.isNotEmpty()) c.formula = text
                        }
                    }
                }
                c.value = when (ca["t"]) {
                    "s" -> vText?.trim()?.toIntOrNull()?.let { shared.getOrNull(it) }
                    "str" -> vText?.let { decodeEscapes(it) }
                    "inlineStr" -> isText ?: vText
                    "b" -> vText?.trim()?.let { it == "1" || it.equals("true", true) }
                    "e" -> vText?.trim()?.takeIf { it.isNotEmpty() }?.let { ErrVal(it) }
                    "d" -> vText
                    else -> vText?.trim()?.toDoubleOrNull()
                }
                c.hadCache = c.value != null
                row.cells[col] = c
            }
        }
        for (c in dependents) {
            val m = masters[c.sharedSi]
            if (m != null) c.formula = Formula.translate(m.formula!!, c.row - m.row, c.col - m.col) else c.sharedSi = -1
        }
    }

    private fun prepare(c: CellRec) {
        val p = Formula.parse(c.formula!!)
        c.parsed = p
        c.precs = null
        c.opaque = c.forcedOpaque || p.root == null || !p.supported
    }

    // ---- evaluation ----

    private fun valueOf(si: Int, c: CellRec): Any? {
        if (c.formula == null || c.state == DONE) return c.value
        return if (depth == 0) drive(si, c) else evalRec(si, c)
    }

    private fun evalRec(si: Int, c: CellRec): Any? {
        if (c.state == DONE) return c.value
        if (c.state == BUSY) return ErrVal.CYCLE
        if (depth >= MAX_DEPTH) throw Defer(si, c)
        c.state = BUSY
        depth++
        try {
            val v = evaluator.evaluate(c.parsed!!.root!!, si, c.row, c.col)
            c.value = v
            c.state = DONE
            return v
        } catch (d: Defer) {
            c.state = TODO
            throw d
        } catch (e: RuntimeException) {
            c.value = ErrVal.VALUE
            c.state = DONE
            return c.value
        } finally {
            depth--
        }
    }

    /**
     * Evaluates [c] without deep recursion: when a dependency chain gets longer than [MAX_DEPTH] cells, the cell at the
     * bottom is evaluated first on this explicit stack and the chain is retried.
     */
    private fun drive(si: Int, c: CellRec): Any? {
        val stack = ArrayList<Pair<Int, CellRec>>()
        val pending = HashSet<CellRec>()
        stack.add(Pair(si, c)); pending.add(c)
        while (stack.isNotEmpty()) {
            val (ts, top) = stack[stack.size - 1]
            if (top.state == DONE) { stack.removeAt(stack.size - 1); pending.remove(top); continue }
            try {
                evalRec(ts, top)
            } catch (d: Defer) {
                if (d.cell in pending) { d.cell.value = ErrVal.CYCLE; d.cell.state = DONE } // a cycle longer than the depth limit
                else { stack.add(Pair(d.sheet, d.cell)); pending.add(d.cell) }
            }
        }
        return c.value
    }

    private fun evaluateAll() {
        for (c in formulaCells) if (c.state != DONE) valueOf(c.sheet, c)
    }

    private fun precedents(si: Int, c: CellRec): Array<IntArray> {
        c.precs?.let { return it }
        val out = ArrayList<IntArray>()
        for (r in c.parsed?.refs ?: emptyList()) {
            val t = if (r.sheet == null) si else host.sheetIndex(r.sheet)
            if (t >= 0) out.add(intArrayOf(t, r.r1, r.c1, r.r2, r.c2))
        }
        return out.toTypedArray().also { c.precs = it }
    }

    private fun cellKey(sheet: Int, row: Int, col: Int): Long = (sheet.toLong() shl 36) or (row.toLong() shl 15) or col.toLong()

    /**
     * Marks every formula that depends, directly or through other formulas, on the given cell for re-evaluation.
     * Sweeps the formulas in sheet order until nothing new is found, so the usual top-to-bottom chains settle in one
     * pass; when the search gets too expensive on a very large sheet, everything is recomputed instead.
     */
    private fun invalidateDependents(si: Int, row: Int, col: Int) {
        val formulas = ArrayList<Pair<Int, CellRec>>()
        for (c in formulaCells) if (!c.opaque && c.state == DONE) formulas.add(Pair(c.sheet, c))
        if (formulas.isEmpty()) return
        val changedSet = HashSet<Long>()
        val changedList = ArrayList<IntArray>()
        changedSet.add(cellKey(si, row, col))
        changedList.add(intArrayOf(si, row, col))
        var budget = 40_000_000L
        var again = true
        while (again) {
            again = false
            for ((fs, fc) in formulas) {
                if (fc.state != DONE) continue
                var hit = false
                for (p in precedents(fs, fc)) {
                    val area = (p[3] - p[1] + 1).toLong() * (p[4] - p[2] + 1)
                    if (area <= changedList.size) {
                        budget -= area
                        loop@ for (r in p[1]..p[3]) for (c in p[2]..p[4]) if (cellKey(p[0], r, c) in changedSet) { hit = true; break@loop }
                    } else {
                        budget -= changedList.size
                        for (f in changedList) if (f[0] == p[0] && f[1] >= p[1] && f[1] <= p[3] && f[2] >= p[2] && f[2] <= p[4]) { hit = true; break }
                    }
                    if (hit) break
                }
                if (hit) {
                    fc.state = TODO
                    changedSet.add(cellKey(fs, fc.row, fc.col))
                    changedList.add(intArrayOf(fs, fc.row, fc.col))
                    again = true
                }
                if (budget < 0) {
                    for ((_, other) in formulas) other.state = TODO
                    return
                }
            }
        }
    }

    // ---- reading ----

    val sheetNames: List<String> get() = sheets.map { it.name }

    private fun sheetAt(sheet: Int): Sheet = sheets.getOrNull(sheet) ?: throw IndexOutOfBoundsException("sheet $sheet")

    private fun computeUsed(s: Sheet) {
        var mr = 0
        var mc = 0
        for (r in s.rows.values) {
            for (c in r.cells.descendingMap().values) {
                if (c.value != null || c.formula != null) {
                    if (c.col + 1 > mc) mc = c.col + 1
                    mr = r.index + 1
                    break
                }
            }
        }
        s.usedRows = maxOf(1, mr)
        s.usedCols = maxOf(1, mc)
    }

    /** Number of rows in the used range (at least 1). */
    fun rows(sheet: Int): Int {
        val s = sheetAt(sheet)
        if (s.usedRows < 0) computeUsed(s)
        return s.usedRows
    }

    /** Number of columns in the used range (at least 1). */
    fun cols(sheet: Int): Int {
        val s = sheetAt(sheet)
        if (s.usedCols < 0) computeUsed(s)
        return s.usedCols
    }

    private fun fmtOf(style: Int): NumFormat = styles?.format(style) ?: NumFormat.GENERAL

    /** 0-based; EMPTY outside the used range. */
    fun cell(sheet: Int, row: Int, col: Int): Cell {
        val s = sheetAt(sheet)
        val c = s.rows[row]?.cells?.get(col) ?: return EMPTY_CELL
        val v = valueOf(sheet, c)
        val fmt = fmtOf(c.style)
        val bold = styles?.bold(c.style) ?: false
        val formula = c.formula
        val display: String
        val kind: CellKind
        var number: Double? = null
        var input: String
        when (v) {
            null -> { kind = CellKind.EMPTY; display = ""; input = "" }
            is Double -> {
                kind = CellKind.NUMBER; number = v
                display = fmt.format(v, date1904)
                input = when (fmt.kind) {
                    NumFmt.PERCENT -> Num.input(BigDecimal(v.toString()).movePointRight(2).toDouble()) + "%"
                    NumFmt.DATE -> display
                    else -> Num.input(v)
                }
            }
            is Boolean -> { kind = CellKind.BOOL; display = if (v) "TRUE" else "FALSE"; input = display }
            is ErrVal -> { kind = CellKind.ERROR; display = v.code; input = display }
            else -> { kind = CellKind.TEXT; display = v.toString(); input = display }
        }
        if (formula != null) input = "=" + Formula.display(formula)
        val (h, wrap) = styles?.alignment(c.style) ?: Pair(null, false)
        val font = styles?.font(c.style)
        return Cell(input, display, kind, number, formula != null, bold, fmt.kind, h, wrap,
            font?.italic ?: false, font?.underline ?: false, font?.strike ?: false, font?.rgb, font?.pt)
    }

    /** Column width in characters of the default font, as stored in the file (default 8.43). */
    fun colWidthChars(sheet: Int, col: Int): Float {
        val s = sheetAt(sheet)
        for (ci in s.cols) if (col >= ci.min && col <= ci.max && ci.width != null) return ci.width
        return s.defaultWidth
    }

    /** Bytes of a package part as currently held (edits to sheets appear here after [save]). */
    fun partBytes(part: String): ByteArray? = pkg.bytes(part)

    // ---- editing ----

    private fun checkPos(row: Int, col: Int) {
        if (row < 0 || row > A1.MAX_ROW || col < 0 || col > A1.MAX_COL) throw IndexOutOfBoundsException("cell $row,$col")
    }

    private fun inheritedStyle(s: Sheet, row: Int, col: Int): Int {
        s.rows[row]?.let { if (it.style != 0) return it.style }
        for (ci in s.cols) if (col >= ci.min && col <= ci.max) return ci.style
        return 0
    }

    private fun createCell(s: Sheet, row: Int, col: Int): CellRec {
        val r = s.rows[row] ?: RowRec(row, null).also { s.rows[row] = it }
        val c = CellRec(s.index, row, col, null)
        c.style = inheritedStyle(s, row, col)
        if (c.style != 0) c.styleChanged = true
        r.cells[col] = c
        return c
    }

    private class Input(val formula: String?, val value: Any?, val fmtCode: String?, val fmtKind: NumFmt?)

    private fun parseInput(input: String): Input {
        if (input.isEmpty()) return Input(null, null, null, null)
        if (input.length > 1 && input[0] == '=') {
            val text = Formula.normalize(input.substring(1))
            // text that does not parse as a formula is kept as plain text rather than written as a broken formula
            return if (Formula.parse(text).root != null) Input(text, null, null, null) else Input(null, input, null, null)
        }
        val t = input.trim()
        if (t.equals("TRUE", true)) return Input(null, true, null, null)
        if (t.equals("FALSE", true)) return Input(null, false, null, null)
        NUMBER_INPUT.matchEntire(t)?.let { m ->
            val neg = (m.groupValues[1] == "-") != (m.groupValues[3] == "-")
            val sym = m.groupValues[2]
            val digits = m.groupValues[4].replace(",", "")
            val pct = m.groupValues[6] == "%"
            var bd = try { BigDecimal(digits + m.groupValues[5]) } catch (e: NumberFormatException) { return Input(null, input, null, null) }
            if (pct) bd = bd.movePointLeft(2)
            var d = bd.toDouble()
            if (d.isInfinite() || d.isNaN()) return Input(null, input, null, null)
            if (neg) d = -d
            return when {
                pct -> Input(null, d, "0%", NumFmt.PERCENT)
                sym.isNotEmpty() -> Input(null, d, currencyCode(if (sym == "￥") "¥" else sym, digits.contains('.')), NumFmt.CURRENCY)
                else -> Input(null, d, null, null)
            }
        }
        DATE_INPUT.matchEntire(t)?.let { m ->
            val g = m.groupValues
            val day = Num.serial(g[1].toInt(), g[2].toInt(), g[3].toInt(), date1904)
            if (day != null) {
                if (g[4].isEmpty()) return Input(null, day, "yyyy-mm-dd", NumFmt.DATE)
                val h = g[4].toInt()
                val mi = g[5].toInt()
                val sec = if (g[6].isEmpty()) 0 else g[6].toInt()
                if (h < 24 && mi < 60 && sec < 60) {
                    val frac = (h * 3600 + mi * 60 + sec) / 86400.0
                    return Input(null, day + frac, if (g[6].isEmpty()) "yyyy-mm-dd hh:mm" else "yyyy-mm-dd hh:mm:ss", NumFmt.DATE)
                }
            }
        }
        TIME_INPUT.matchEntire(t)?.let { m ->
            val g = m.groupValues
            val h = g[1].toInt()
            val mi = g[2].toInt()
            val sec = if (g[3].isEmpty()) 0 else g[3].toInt()
            if (h < 24 && mi < 60 && sec < 60) {
                return Input(null, (h * 3600 + mi * 60 + sec) / 86400.0, if (g[3].isEmpty()) "hh:mm" else "hh:mm:ss", NumFmt.DATE)
            }
        }
        return Input(null, input, null, null)
    }

    private fun currencyCode(symbol: String, decimals: Boolean): String = "\"" + symbol.replace("\"", "") + "\"" + (if (decimals) "#,##0.00" else "#,##0")

    private fun same(a: Any?, b: Any?): Boolean = if (a == null || b == null) a == null && b == null else a == b

    /** Detaches a cell from its shared-formula group; when it is the group's master every member gets its own formula text. */
    private fun unshare(s: Sheet, c: CellRec) {
        val si = c.sharedSi
        if (si < 0) return
        if (c.sharedMaster) {
            for (r in s.rows.values) for (k in r.cells.values) if (k.sharedSi == si) { k.sharedSi = -1; k.sharedMaster = false; if (k.formula != null) k.contentChanged = true }
        } else {
            c.sharedSi = -1
            c.contentChanged = true
        }
    }

    private fun setFormulaText(s: Sheet, c: CellRec, text: String) {
        unshare(s, c)
        c.formula = text
        c.contentChanged = true
        prepare(c)
    }

    /**
     * "=…" formula; number ("12%", "¥1,200", "1e3"); date or time ("2026-03-01", "2026-03-01 18:30", "18:30");
     * TRUE / FALSE; text; "" clears. A typed percent sign, currency sign, date or time also gives a General cell the
     * matching number format. Text that starts with "=" but does not parse as a formula is stored as text.
     */
    fun setCell(sheet: Int, row: Int, col: Int, input: String) {
        val s = sheetAt(sheet)
        checkPos(row, col)
        val existing = s.rows[row]?.cells?.get(col)
        // committing exactly what the formula bar showed changes nothing (a text "123" stays text, a rounded display
        // of a number does not overwrite the stored value)
        if (existing != null && input == cell(sheet, row, col).input) return
        val p = parseInput(input)
        val curKind = fmtOf(existing?.style ?: inheritedStyle(s, row, col)).kind
        val applyFmt = p.fmtCode != null && curKind == NumFmt.GENERAL
        if (existing == null && p.formula == null && p.value == null) return
        if (existing != null && !applyFmt) {
            if (p.formula != null && existing.formula == p.formula) return
            if (p.formula == null && existing.formula == null && same(existing.value, p.value)) return
        }
        val c = existing ?: createCell(s, row, col)
        if (c.formula != null) {
            unshare(s, c)
            if (p.formula == null) formulaLayoutChanged = true
        }
        c.formula = p.formula
        c.parsed = null
        c.precs = null
        c.opaque = false
        c.forcedOpaque = false
        c.hadCache = false
        c.contentChanged = true
        if (p.formula != null) formulaCells.add(c) else formulaCells.remove(c)
        if (p.formula != null) {
            prepare(c)
            if (c.opaque) { c.value = ErrVal.NAME; c.state = DONE } else { c.value = null; c.state = TODO }
        } else {
            c.value = p.value
            c.state = DONE
        }
        if (applyFmt) {
            val ns = ensureStyles().withNumFmt(c.style, p.fmtCode!!)
            if (ns != c.style) { c.style = ns; c.styleChanged = true }
        }
        s.usedRows = -1; s.usedCols = -1
        invalidateDependents(sheet, row, col)
        dirty = true
    }

    private fun ensureStyles(): Styles {
        styles?.let { return it }
        var part = "xl/styles.xml"
        var n = 1
        while (pkg.has(part)) part = "xl/styles${++n}.xml"
        pkg.putText(part, Styles.DEFAULT)
        ContentTypes.addOverride(pkg, part, CT_STYLES)
        Rels.add(pkg, wbPart, "$REL/styles", relTarget(wbPart, part))
        stylesPart = part
        dirty = true
        return Styles(Styles.DEFAULT).also { styles = it }
    }

    fun setBold(sheet: Int, row: Int, col: Int, bold: Boolean) {
        val s = sheetAt(sheet)
        checkPos(row, col)
        val existing = s.rows[row]?.cells?.get(col)
        val cur = existing?.style ?: inheritedStyle(s, row, col)
        if ((styles?.bold(cur) ?: false) == bold) return
        val ns = ensureStyles().withBold(cur, bold)
        val c = existing ?: createCell(s, row, col)
        if (c.style == ns) return
        c.style = ns
        c.styleChanged = true
        dirty = true
    }

    /**
     * Applies a number format of the given kind, unless the cell already has one of that kind.
     * GENERAL → `General`, CURRENCY → [currencySymbol] with grouping and two decimals, PERCENT → `0%`,
     * DATE → `yyyy-mm-dd`, OTHER → `#,##0.00`.
     */
    fun setFmt(sheet: Int, row: Int, col: Int, fmt: NumFmt) {
        val s = sheetAt(sheet)
        checkPos(row, col)
        val existing = s.rows[row]?.cells?.get(col)
        val cur = existing?.style ?: inheritedStyle(s, row, col)
        if (fmtOf(cur).kind == fmt) return
        val code = when (fmt) {
            NumFmt.GENERAL -> "General"
            NumFmt.CURRENCY -> currencyCode(currencySymbol, true)
            NumFmt.PERCENT -> "0%"
            NumFmt.DATE -> "yyyy-mm-dd"
            NumFmt.OTHER -> "#,##0.00"
        }
        val ns = ensureStyles().withNumFmt(cur, code)
        val c = existing ?: createCell(s, row, col)
        if (c.style == ns) return
        c.style = ns
        c.styleChanged = true
        dirty = true
    }

    private fun note(code: String) { editNotes[code] = (editNotes[code] ?: 0) + 1 }

    /**
     * Inserts an empty row below [row]. Rows further down move by one; references to them are adjusted in the formulas
     * of every sheet, in merged ranges, conditional-format and validation ranges of this sheet, in chart series
     * references and in defined names. What is not adjusted (tables, filters, links, anchors of drawings, formulas inside
     * conditional formats and validations) is reported through [compat] with an `insert-row:` code.
     */
    fun insertRowBelow(sheet: Int, row: Int) {
        if (row < 0 || row >= A1.MAX_ROW) throw IndexOutOfBoundsException("row $row")
        restructure(sheet, cols = false, at = row + 1, delta = 1)
    }

    /** Deletes row [row]; rows below move up. References to the deleted row become `#REF!` (as in every spreadsheet). */
    fun deleteRow(sheet: Int, row: Int) {
        if (row < 0 || row > A1.MAX_ROW) throw IndexOutOfBoundsException("row $row")
        restructure(sheet, cols = false, at = row, delta = -1)
    }

    /** Inserts an empty column to the right of [col]; columns further right move by one. */
    fun insertColumnRight(sheet: Int, col: Int) {
        if (col < 0 || col >= A1.MAX_COL) throw IndexOutOfBoundsException("col $col")
        restructure(sheet, cols = true, at = col + 1, delta = 1)
    }

    /** Deletes column [col]; columns to its right move left. */
    fun deleteColumn(sheet: Int, col: Int) {
        if (col < 0 || col > A1.MAX_COL) throw IndexOutOfBoundsException("col $col")
        restructure(sheet, cols = true, at = col, delta = -1)
    }

    private fun restructure(sheet: Int, cols: Boolean, at: Int, delta: Int) {
        val s = sheetAt(sheet)
        evaluateAll()
        val what = (if (delta > 0) "insert-" else "delete-") + (if (cols) "col" else "row")
        if (!cols) {
            if (delta > 0) {
                val moved = ArrayList(s.rows.tailMap(at, true).values)
                if (moved.isNotEmpty() && moved[moved.size - 1].index >= A1.MAX_ROW) throw IllegalStateException("sheet is full")
                for (r in moved) s.rows.remove(r.index)
                for (r in moved) { r.index++; for (c in r.cells.values) c.row = r.index; s.rows[r.index] = r }
            } else {
                s.rows.remove(at)?.let { gone -> for (c in gone.cells.values) formulaCells.remove(c) }
                val moved = ArrayList(s.rows.tailMap(at + 1, true).values)
                for (r in moved) s.rows.remove(r.index)
                for (r in moved) { r.index--; for (c in r.cells.values) c.row = r.index; s.rows[r.index] = r }
            }
        } else {
            for (r in s.rows.values) {
                val tail = ArrayList(r.cells.tailMap(at, true).values)
                if (tail.isEmpty()) continue
                if (delta > 0 && tail[tail.size - 1].col >= A1.MAX_COL) throw IllegalStateException("sheet is full")
                for (c in tail) r.cells.remove(c.col)
                for (c in tail) {
                    if (delta < 0 && c.col == at) { formulaCells.remove(c); continue }
                    c.col += delta
                    r.cells[c.col] = c
                }
                r.touched = true
            }
            // column widths and styles follow their columns
            val shifted = ArrayList<ColInfo>()
            for (ci in s.cols) {
                val rg = applyOps(intArrayOf(0, ci.min, 0, ci.max), listOf(intArrayOf(1, at, delta))) ?: continue
                shifted.add(ColInfo(rg[1], rg[3], ci.width, ci.style, LinkedHashMap(ci.attrs).also { it["min"] = (rg[1] + 1).toString(); it["max"] = (rg[3] + 1).toString() }))
            }
            s.cols.clear(); s.cols.addAll(shifted)
        }
        s.structural = true
        s.ops.add(intArrayOf(if (cols) 1 else 0, at, delta))
        s.merges?.let { list -> val op = listOf(intArrayOf(if (cols) 1 else 0, at, delta)); s.merges = ArrayList(list.mapNotNull { applyOps(it, op) }) }
        for (r in s.rows.values) for (c in r.cells.values) {
            if (c.sharedSi >= 0) { c.sharedSi = -1; c.sharedMaster = false; if (c.formula != null) c.contentChanged = true }
            if (c.forcedOpaque && (c.row != c.origRow || c.col != c.origCol)) note("$what:array-formula")
        }
        for (c in ArrayList(formulaCells)) {
            val f = c.formula ?: continue
            val t = sheets[c.sheet]
            val nf = Formula.shift(f, c.parsed!!.toks, t.name, s.name, cols, at, delta)
            if (nf != f) setFormulaText(t, c, nf)
        }
        // chart series references and defined names live in other parts
        for (part in pkg.names()) {
            if (!part.startsWith("xl/charts/") || !part.endsWith(".xml") || part.contains("/_rels/")) continue
            val xml = pkg.text(part) ?: continue
            val root = Xml.root(xml) ?: continue
            if (Xml.local(root.name) != "chartSpace") continue
            shiftFormulaElements(xml, XmlX.findAllLocal(xml, root, "f"), s.name, cols, at, delta)?.let { pkg.putText(part, it) }
        }
        pkg.text(wbPart)?.let { wbXml ->
            val names = Xml.root(wbXml)?.let { XmlX.kid(wbXml, it, "definedNames") }
            if (names != null) shiftFormulaElements(wbXml, Xml.children(wbXml, names), s.name, cols, at, delta)?.let { pkg.putText(wbPart, it) }
        }
        for (k in s.rootKids) when (Xml.local(k.name)) {
            "tableParts" -> note("$what:table")
            "autoFilter" -> note("$what:filter")
            "hyperlinks" -> note("$what:links")
            "drawing" -> note("$what:drawing-anchor")
            "legacyDrawing" -> note("$what:comments")
            "conditionalFormatting" -> if (XmlX.findAllLocal(s.xml, k, "formula").isNotEmpty()) note("$what:conditional-format-formula")
            "dataValidations" -> if (XmlX.findAllLocal(s.xml, k, "formula1").isNotEmpty()) note("$what:data-validation-formula")
        }
        for (c in formulaCells) if (!c.opaque) { c.state = TODO; c.precs = null }
        chartDefs = null
        formulaLayoutChanged = true
        s.usedRows = -1; s.usedCols = -1
        dirty = true
    }

    /** Number of frozen top rows (0 = none). */
    fun frozenRows(sheet: Int): Int = sheetAt(sheet).frozen

    /** Freezes the top [rows] rows (0 unfreezes); frozen columns stay as they are. Written to the sheet's view settings on save. */
    fun setFrozenRows(sheet: Int, rows: Int) = setFreeze(sheet, rows, sheetAt(sheet).frozenCols)

    /** v0.6.0 (s30): number of frozen left columns (0 = none). */
    fun frozenCols(sheet: Int): Int = sheetAt(sheet).frozenCols

    /**
     * v0.6.0 (s30): freezes the top [rows] rows and the left [cols] columns (both 0 unfreezes). "Freeze at B2" is
     * (1, 1): everything above and left of the cell stays. Written as the first sheet view's frozen pane on save.
     */
    fun setFreeze(sheet: Int, rows: Int, cols: Int) {
        val s = sheetAt(sheet)
        val r = rows.coerceIn(0, 100)
        val c = cols.coerceIn(0, 50)
        if (s.frozen == r && s.frozenCols == c) return
        s.frozen = r
        s.frozenCols = c
        s.freezeSet = true
        dirty = true
    }

    // ---- v0.6.0 (s42): hidden rows and columns

    fun isRowHidden(sheet: Int, row: Int): Boolean = sheetAt(sheet).rows[row]?.hidden == true
    fun isColHidden(sheet: Int, col: Int): Boolean = sheetAt(sheet).cols.any { col in it.min..it.max && it.hidden }

    /** Rows hidden on the sheet (sorted). */
    fun hiddenRows(sheet: Int): List<Int> = sheetAt(sheet).rows.values.filter { it.hidden }.map { it.index }

    /** Columns hidden on the sheet (sorted), within the first [limit] columns. */
    fun hiddenCols(sheet: Int, limit: Int = 702): List<Int> {
        val s = sheetAt(sheet)
        val out = ArrayList<Int>()
        for (ci in s.cols) if (ci.hidden) for (c in ci.min..minOf(ci.max, limit - 1)) out.add(c)
        return out.distinct().sorted()
    }

    /** Hides / shows rows [r1]..[r2] (`hidden="1"` on each row; the cells stay as they are). Returns how many changed. */
    fun setRowsHidden(sheet: Int, r1: Int, r2: Int, hidden: Boolean): Int {
        val s = sheetAt(sheet)
        var n = 0
        for (row in maxOf(0, r1)..minOf(r2, A1.MAX_ROW)) {
            val r = s.rows[row] ?: if (!hidden) continue else RowRec(row, null).also { s.rows[row] = it }
            if (r.hidden == hidden) continue
            r.hidden = hidden; r.hiddenChanged = true; r.touched = true; n++
        }
        if (n > 0) dirty = true
        return n
    }

    /** Hides / shows columns [c1]..[c2] (`hidden="1"` on their `<col>` spans, split where needed). Returns how many changed. */
    fun setColsHidden(sheet: Int, c1: Int, c2: Int, hidden: Boolean): Int {
        val s = sheetAt(sheet)
        var n = 0
        for (col in maxOf(0, c1)..minOf(c2, A1.MAX_COL)) {
            if (isColHidden(sheet, col) == hidden) continue
            // a column without a span of its own gets one with its present width (Excel writes a width on every <col>)
            val hasWidth = s.cols.any { col in it.min..it.max && it.width != null }
            updateCol(s, col, if (hasWidth) null else colWidthChars(sheet, col), mapOf("hidden" to if (hidden) "1" else null))
            n++
        }
        if (n > 0) { s.colsChanged = true; s.usedCols = -1; dirty = true }
        return n
    }

    /** One column's `<col>`: its span split out of a wider one, [width] (null = keep) and attributes set / removed (null value). */
    private fun updateCol(s: Sheet, col: Int, width: Float?, set: Map<String, String?>) {
        fun info(min: Int, max: Int, base: ColInfo?, w: Float?, extra: Map<String, String?>): ColInfo {
            val a = if (base != null) LinkedHashMap(base.attrs) else LinkedHashMap()
            a["min"] = (min + 1).toString(); a["max"] = (max + 1).toString()
            if (w != null) { a["width"] = fmtNum(w); a["customWidth"] = "1" }
            for ((k, v) in extra) if (v == null) a.remove(k) else a[k] = v
            return ColInfo(min, max, w ?: base?.width, base?.style ?: 0, a)
        }
        val out = ArrayList<ColInfo>()
        var hit = false
        for (ci in s.cols) {
            if (col < ci.min || col > ci.max) { out.add(ci); continue }
            hit = true
            if (ci.min < col) out.add(info(ci.min, col - 1, ci, null, emptyMap()))
            out.add(info(col, col, ci, width, set))
            if (ci.max > col) out.add(info(col + 1, ci.max, ci, null, emptyMap()))
        }
        if (!hit) out.add(info(col, col, null, width ?: s.defaultWidth, set))
        out.sortBy { it.min }
        s.cols.clear(); s.cols.addAll(out)
    }

    /** Fill colour of a cell as 0xRRGGBB, null when it has none. */
    fun fillRgb(sheet: Int, row: Int, col: Int): Int? {
        val s = sheetAt(sheet)
        val c = s.rows[row]?.cells?.get(col)
        return styles?.fill(c?.style ?: inheritedStyle(s, row, col))
    }

    /** Sets a solid fill colour (0xRRGGBB) on a cell, or clears it with null. */
    fun setFill(sheet: Int, row: Int, col: Int, rgb: Int?) {
        val s = sheetAt(sheet)
        checkPos(row, col)
        val existing = s.rows[row]?.cells?.get(col)
        val cur = existing?.style ?: inheritedStyle(s, row, col)
        if ((styles?.fill(cur)) == rgb) return
        val ns = ensureStyles().withFill(cur, rgb)
        val c = existing ?: createCell(s, row, col)
        if (c.style == ns) return
        c.style = ns
        c.styleChanged = true
        dirty = true
    }

    // ---- alignment, wrap, decimals (M1) ----

    private fun restyle(sheet: Int, row: Int, col: Int, f: (Styles, Int) -> Int) {
        val s = sheetAt(sheet)
        checkPos(row, col)
        val existing = s.rows[row]?.cells?.get(col)
        val cur = existing?.style ?: inheritedStyle(s, row, col)
        val ns = f(ensureStyles(), cur)
        if (ns == cur && (existing == null || existing.style == ns)) return
        val c = existing ?: createCell(s, row, col)
        if (c.style == ns) return
        c.style = ns
        c.styleChanged = true
        dirty = true
    }

    // ---- M2 (s23): font of a cell; each finds or adds a font that differs only in that property ----

    private fun fontOf(sheet: Int, row: Int, col: Int): Styles.FontInfo? {
        val s = sheetAt(sheet)
        return styles?.font(s.rows[row]?.cells?.get(col)?.style ?: inheritedStyle(s, row, col))
    }

    fun setItalic(sheet: Int, row: Int, col: Int, on: Boolean) {
        if ((fontOf(sheet, row, col)?.italic ?: false) == on) return
        restyle(sheet, row, col) { st, style -> st.withItalic(style, on) }
    }

    fun setUnderline(sheet: Int, row: Int, col: Int, on: Boolean) {
        if ((fontOf(sheet, row, col)?.underline ?: false) == on) return
        restyle(sheet, row, col) { st, style -> st.withUnderline(style, on) }
    }

    fun setStrike(sheet: Int, row: Int, col: Int, on: Boolean) {
        if ((fontOf(sheet, row, col)?.strike ?: false) == on) return
        restyle(sheet, row, col) { st, style -> st.withStrike(style, on) }
    }

    /** Font colour 0xRRGGBB; null removes the colour (automatic). */
    fun setFontColor(sheet: Int, row: Int, col: Int, rgb: Int?) {
        if (rgb == null && styles == null) return
        if (rgb != null && fontOf(sheet, row, col)?.rgb == (rgb and 0xFFFFFF)) return
        restyle(sheet, row, col) { st, style -> st.withFontColor(style, rgb) }
    }

    fun setFontSize(sheet: Int, row: Int, col: Int, pt: Float) {
        require(pt > 0f && pt <= 409f) { "font size $pt" }
        if (fontOf(sheet, row, col)?.pt == pt) return
        restyle(sheet, row, col) { st, style -> st.withFontSize(style, pt) }
    }

    /** Bold / italic / underline / strikethrough off, colour removed, size back to the default font's (font 0). */
    fun clearFont(sheet: Int, row: Int, col: Int) {
        if (styles == null) return
        restyle(sheet, row, col) { st, style -> st.withClearedFont(style) }
    }

    /** Size of the workbook's default font (font 0), 11 when unknown. */
    fun defaultFontPt(): Float = styles?.defaultFontPt() ?: 11f

    /** Horizontal alignment of a cell: "left", "center", "right", or null for general. Wrap is kept. */
    fun setHAlign(sheet: Int, row: Int, col: Int, align: String?) {
        val a = align?.lowercase(Locale.ROOT)?.takeIf { it != "general" }
        val s = sheetAt(sheet)
        val cur = s.rows[row]?.cells?.get(col)?.style ?: inheritedStyle(s, row, col)
        if ((styles?.alignment(cur)?.first) == a) return
        restyle(sheet, row, col) { st, style -> st.withAlignment(style, a, st.alignment(style).second) }
    }

    /** Text wrapping inside a cell (`wrapText`). Horizontal alignment is kept. */
    fun setWrap(sheet: Int, row: Int, col: Int, wrap: Boolean) {
        val s = sheetAt(sheet)
        val cur = s.rows[row]?.cells?.get(col)?.style ?: inheritedStyle(s, row, col)
        if ((styles?.alignment(cur)?.second ?: false) == wrap) return
        restyle(sheet, row, col) { st, style -> st.withAlignment(style, st.alignment(style).first, wrap) }
    }

    /** One decimal place more ([delta] > 0) or fewer ([delta] < 0) in the cell's number format (date and text formats stay). */
    fun adjustDecimals(sheet: Int, row: Int, col: Int, delta: Int) {
        if (delta == 0) return
        val s = sheetAt(sheet)
        checkPos(row, col)
        val rec = s.rows[row]?.cells?.get(col)
        val cur = rec?.style ?: inheritedStyle(s, row, col)
        val code = styles?.formatCode(cur) ?: "General"
        val v = rec?.let { valueOf(sheet, it) } as? Double
        val next = Decimals.adjust(code, delta, v)
        if (next == code) return
        restyle(sheet, row, col) { st, style -> st.withNumFmt(style, next) }
    }

    // ---- borders, merged cells, fill (M3) ----

    /** s26: sides of a cell that have a border line ([Styles.LEFT] / RIGHT / TOP / BOTTOM bits). */
    fun borderMask(sheet: Int, row: Int, col: Int): Int {
        val s = sheetAt(sheet)
        return styles?.borderMask(s.rows[row]?.cells?.get(col)?.style ?: inheritedStyle(s, row, col)) ?: 0
    }

    /** s26: thin lines on exactly the sides in [mask] (0 removes the border). */
    fun setBorders(sheet: Int, row: Int, col: Int, mask: Int) {
        if (mask == 0 && styles == null) return
        if (borderMask(sheet, row, col) == mask) return
        restyle(sheet, row, col) { st, style -> st.withBorders(style, mask) }
    }

    /** Sheet's merged ranges [r1, c1, r2, c2] as they are now (after this session's row / column edits and merges). */
    fun merges(sheet: Int): List<IntArray> {
        val s = sheetAt(sheet)
        return (s.merges ?: mergedRanges(s)).map { it.copyOf() }
    }

    /** The merged range that covers ([row], [col]), or null. */
    fun mergeAt(sheet: Int, row: Int, col: Int): IntArray? = merges(sheet).firstOrNull { row in it[0]..it[2] && col in it[1]..it[3] }

    private fun overlaps(m: IntArray, r1: Int, c1: Int, r2: Int, c2: Int) = m[0] <= r2 && m[2] >= r1 && m[1] <= c2 && m[3] >= c1

    /**
     * s25: merges the rectangle into one cell. Merged ranges it touches are taken in (the result covers them all);
     * every cell but the top-left one is emptied (its value stays, as in every spreadsheet). False for a single cell.
     */
    fun mergeCells(sheet: Int, r1: Int, c1: Int, r2: Int, c2: Int): Boolean {
        val s = sheetAt(sheet)
        var a = intArrayOf(minOf(r1, r2), minOf(c1, c2), maxOf(r1, r2), maxOf(c1, c2))
        val cur = ArrayList(merges(sheet))
        var grew = true
        while (grew) {
            grew = false
            for (m in cur) if (overlaps(m, a[0], a[1], a[2], a[3]) && !(m[0] >= a[0] && m[1] >= a[1] && m[2] <= a[2] && m[3] <= a[3])) {
                a = intArrayOf(minOf(a[0], m[0]), minOf(a[1], m[1]), maxOf(a[2], m[2]), maxOf(a[3], m[3])); grew = true
            }
        }
        if (a[0] == a[2] && a[1] == a[3]) return false
        val keep = cur.filter { !overlaps(it, a[0], a[1], a[2], a[3]) }
        for (r in ArrayList(s.rows.subMap(a[0], true, a[2], true).values)) for (c in ArrayList(r.cells.subMap(a[1], true, a[3], true).values)) {
            if (r.index == a[0] && c.col == a[1]) continue
            if (c.value != null || c.formula != null) setCell(sheet, r.index, c.col, "")
        }
        s.merges = ArrayList(keep + listOf(a))
        s.mergesChanged = true
        dirty = true
        return true
    }

    /** s25: splits every merged range that touches the rectangle; false when there was none. */
    fun unmergeCells(sheet: Int, r1: Int, c1: Int, r2: Int, c2: Int): Boolean {
        val s = sheetAt(sheet)
        val cur = merges(sheet)
        val keep = cur.filter { !overlaps(it, minOf(r1, r2), minOf(c1, c2), maxOf(r1, r2), maxOf(c1, c2)) }
        if (keep.size == cur.size) return false
        s.merges = ArrayList(keep)
        s.mergesChanged = true
        dirty = true
        return true
    }

    /**
     * s24 Ctrl+D / Ctrl+R: the first row (down) or first column (right) of the rectangle is copied into the rest of it,
     * formulas moved like a paste, styles copied. A rectangle one row high (down) / one column wide (right) takes the
     * row above / column to the left instead, as every spreadsheet does. Returns the number of cells written.
     */
    fun fill(sheet: Int, r1: Int, c1: Int, r2: Int, c2: Int, down: Boolean): Int {
        var ra = minOf(r1, r2); val rb = maxOf(r1, r2); var ca = minOf(c1, c2); val cb = maxOf(c1, c2)
        if (down && ra == rb) { if (ra == 0) return 0; ra-- }
        if (!down && ca == cb) { if (ca == 0) return 0; ca-- }
        var n = 0
        if (down) {
            val src = copyRange(sheet, ra, ca, ra, cb)
            for (r in ra + 1..rb) { pasteRange(sheet, r, ca, src, r - ra, 0); n += cb - ca + 1 }
        } else {
            val src = copyRange(sheet, ra, ca, rb, ca)
            for (c in ca + 1..cb) { pasteRange(sheet, ra, c, src, 0, c - ca); n += rb - ra + 1 }
        }
        return n
    }

    /**
     * s24 Fill series: in each column (down) / row (right) of the rectangle the leading filled cells are the seed: one
     * number (dates are numbers) counts up by 1 (a day), two or more continue their step; text ending in a number
     * ("Q1", "第 3 周") counts that number up; anything else (and formulas) is copied as [fill] does. The seed's
     * format goes with it. Returns the number of cells written.
     */
    fun fillSeries(sheet: Int, r1: Int, c1: Int, r2: Int, c2: Int, down: Boolean): Int {
        val s = sheetAt(sheet)
        val ra = minOf(r1, r2); val rb = maxOf(r1, r2); val ca = minOf(c1, c2); val cb = maxOf(c1, c2)
        var n = 0
        val lines = if (down) ca..cb else ra..rb
        val len = if (down) rb - ra + 1 else cb - ca + 1
        for (line in lines) {
            fun at(k: Int): Pair<Int, Int> = if (down) Pair(ra + k, line) else Pair(line, ca + k)
            var seed = 0
            while (seed < len) { val (r, c) = at(seed); if (cell(sheet, r, c).input.isEmpty()) break; seed++ }
            if (seed == 0 || seed == len) continue
            val cells = (0 until seed).map { k -> at(k).let { (r, c) -> cell(sheet, r, c) } }
            val (lr, lc) = at(seed - 1)
            val style = s.rows[lr]?.cells?.get(lc)?.style ?: inheritedStyle(s, lr, lc)
            val nums = cells.map { if (!it.isFormula && it.kind == CellKind.NUMBER) it.number else null }
            val textNum = cells.map { if (!it.isFormula && it.kind == CellKind.TEXT) TRAILING_NUM.matchEntire(it.input) else null }
            for (k in seed until len) {
                val (r, c) = at(k)
                val input: String = when {
                    nums.all { it != null } -> {
                        val step = if (seed == 1) 1.0 else (nums[seed - 1]!! - nums[0]!!) / (seed - 1)
                        Num.input(BigDecimal((nums[seed - 1]!!).toString()).add(BigDecimal(step.toString()).multiply(BigDecimal(k - seed + 1))).toDouble())
                    }
                    textNum.all { it != null } && textNum.map { it!!.groupValues[1] }.distinct().size == 1 -> {
                        val vals = textNum.map { it!!.groupValues[2].toLong() }
                        val step = if (seed == 1) 1L else (vals[seed - 1] - vals[0]) / (seed - 1)
                        textNum[0]!!.groupValues[1] + (vals[seed - 1] + step * (k - seed + 1)).toString().padStart(textNum[seed - 1]!!.groupValues[2].length, '0') + textNum[0]!!.groupValues[3]
                    }
                    else -> {
                        val srcK = (k - seed) % seed
                        val (sr, sc) = at(srcK)
                        val inp = cell(sheet, sr, sc).input
                        if (inp.length > 1 && inp[0] == '=' && Formula.parse(inp.substring(1)).root != null) Formula.offset(inp, r - sr, c - sc) else inp
                    }
                }
                setCell(sheet, r, c, input)
                val rec = s.rows[r]?.cells?.get(c) ?: if (style != 0) createCell(s, r, c) else null
                if (rec != null && rec.style != style) { rec.style = style; rec.styleChanged = true; dirty = true }
                n++
            }
        }
        return n
    }

    // ---- ranges: copy, paste, clear (M1) ----

    /** The cells of a rectangle as the formula bar shows them, with their style indices. */
    fun copyRange(sheet: Int, r1: Int, c1: Int, r2: Int, c2: Int): List<List<ClipCell>> {
        val s = sheetAt(sheet)
        val rows = ArrayList<List<ClipCell>>()
        for (r in minOf(r1, r2)..maxOf(r1, r2)) {
            val line = ArrayList<ClipCell>()
            for (c in minOf(c1, c2)..maxOf(c1, c2)) {
                val rec = s.rows[r]?.cells?.get(c)
                line.add(ClipCell(cell(sheet, r, c).input, rec?.style ?: inheritedStyle(s, r, c)))
            }
            rows.add(line)
        }
        return rows
    }

    /**
     * Writes [cells] with their top-left at ([row], [col]). Formulas are moved by ([dRow], [dCol]) (relative parts
     * shift, `$` parts stay, references pushed off the sheet become `#REF!`); an empty input clears the cell. With
     * [withStyles] each cell also takes the copied style index (only meaningful within the same workbook). Cells that
     * would land outside the sheet are skipped.
     */
    fun pasteRange(sheet: Int, row: Int, col: Int, cells: List<List<ClipCell>>, dRow: Int, dCol: Int, withStyles: Boolean = true) {
        val s = sheetAt(sheet)
        val styleCount = styles?.styleCount ?: 1
        for ((i, line) in cells.withIndex()) for ((j, cc) in line.withIndex()) {
            val r = row + i
            val c = col + j
            if (r < 0 || r > A1.MAX_ROW || c < 0 || c > A1.MAX_COL) continue
            var input = cc.input
            if (input.length > 1 && input[0] == '=' && Formula.parse(input.substring(1)).root != null) input = Formula.offset(input, dRow, dCol)
            setCell(sheet, r, c, input)
            if (withStyles && cc.style in 0 until styleCount) {
                val rec = s.rows[r]?.cells?.get(c)
                val cur = rec?.style ?: inheritedStyle(s, r, c)
                if (cur != cc.style || (rec != null && rec.style != cc.style)) {
                    val k = rec ?: createCell(s, r, c)
                    if (k.style != cc.style) { k.style = cc.style; k.styleChanged = true; dirty = true }
                }
            }
        }
    }

    /** Clears the contents of a rectangle; with [styles] its cell formats too (cut, Delete). */
    fun clearRange(sheet: Int, r1: Int, c1: Int, r2: Int, c2: Int, styles: Boolean) {
        val s = sheetAt(sheet)
        val ra = minOf(r1, r2); val rb = maxOf(r1, r2)
        val ca = minOf(c1, c2); val cb = maxOf(c1, c2)
        for (r in ArrayList(s.rows.subMap(ra, true, rb, true).values)) {
            for (c in ArrayList(r.cells.subMap(ca, true, cb, true).values)) {
                if (c.value != null || c.formula != null) setCell(sheet, r.index, c.col, "")
                if (styles && c.style != 0) { c.style = 0; c.styleChanged = true; dirty = true }
            }
        }
    }

    // ---- column widths, row heights (M1) ----

    /** Sets the width of one column in characters (1–255), splitting a `<col>` span that covers more columns. */
    fun setColWidth(sheet: Int, col: Int, widthChars: Float) {
        val s = sheetAt(sheet)
        if (col < 0 || col > A1.MAX_COL) throw IndexOutOfBoundsException("col $col")
        val w = widthChars.coerceIn(1f, 255f)
        if (colWidthChars(sheet, col) == w && s.cols.any { col in it.min..it.max && it.width == w }) return
        fun info(min: Int, max: Int, base: ColInfo?, width: Float?): ColInfo {
            val a = if (base != null) LinkedHashMap(base.attrs) else LinkedHashMap()
            a["min"] = (min + 1).toString(); a["max"] = (max + 1).toString()
            if (width != null) { a["width"] = fmtNum(width); a["customWidth"] = "1" }
            return ColInfo(min, max, width ?: base?.width, base?.style ?: 0, a)
        }
        val out = ArrayList<ColInfo>()
        var hit = false
        for (ci in s.cols) {
            if (col < ci.min || col > ci.max) { out.add(ci); continue }
            hit = true
            if (ci.min < col) out.add(info(ci.min, col - 1, ci, null))
            out.add(info(col, col, ci, w))
            if (ci.max > col) out.add(info(col + 1, ci.max, ci, null))
        }
        if (!hit) out.add(info(col, col, null, w))
        out.sortBy { it.min }
        s.cols.clear(); s.cols.addAll(out)
        s.colsChanged = true
        dirty = true
    }

    /** Row height in points: the row's own, else the sheet's default (15 when the sheet does not say). */
    fun rowHeightPt(sheet: Int, row: Int): Float {
        val s = sheetAt(sheet)
        return s.rows[row]?.ht ?: s.defaultHeight
    }

    /** Sets a row's height in points (6–409). */
    fun setRowHeight(sheet: Int, row: Int, pt: Float) {
        val s = sheetAt(sheet)
        if (row < 0 || row > A1.MAX_ROW) throw IndexOutOfBoundsException("row $row")
        val h = pt.coerceIn(6f, 409f)
        val r = s.rows[row] ?: RowRec(row, null).also { s.rows[row] = it }
        if (r.ht == h) return
        r.ht = h
        r.htChanged = true
        r.touched = true
        dirty = true
    }

    /** Fits a column to its longest shown text (full-width characters count twice), 4–120 characters; returns the width. */
    fun autoFitColumn(sheet: Int, col: Int): Float {
        val s = sheetAt(sheet)
        var longest = 0
        for (r in s.rows.values) {
            if (r.cells[col] == null) continue
            val d = cell(sheet, r.index, col).display
            var n = 0
            for (line in d.split('\n')) n = maxOf(n, textWidth(line))
            if (n > longest) longest = n
        }
        val w = (longest + 2).toFloat().coerceIn(4f, 120f)
        setColWidth(sheet, col, w)
        return w
    }

    private fun textWidth(t: String): Int {
        var n = 0
        var i = 0
        while (i < t.length) {
            val cp = t.codePointAt(i)
            n += if (wide(cp)) 2 else 1
            i += Character.charCount(cp)
        }
        return n
    }

    private fun wide(cp: Int): Boolean {
        if (cp in 0x1100..0x115F || cp in 0x2E80..0xA4CF || cp in 0xAC00..0xD7A3 || cp in 0xF900..0xFAFF || cp in 0xFE30..0xFE4F ||
            cp in 0xFF00..0xFF60 || cp in 0xFFE0..0xFFE6 || cp in 0x20000..0x3FFFD) return true
        return false
    }

    // ---- worksheets (M1) ----

    private fun wbSheetElems(wbXml: String): List<Xml.Elem> {
        val root = Xml.root(wbXml) ?: return emptyList()
        val sheetsEl = XmlX.kid(wbXml, root, "sheets") ?: return emptyList()
        return Xml.children(wbXml, sheetsEl).filter { Xml.local(it.name) == "sheet" }
    }

    /** The `<sheet>` element of workbook.xml that belongs to sheet [index] (matched through its relationship). */
    private fun wbSheetElem(wbXml: String, index: Int): Xml.Elem {
        val part = sheetAt(index).part
        val byId = Rels.of(pkg, wbPart).associateBy { it.id }
        return wbSheetElems(wbXml).firstOrNull { e ->
            val rel = XmlX.prefixedAttr(wbXml, e, "id")?.let { byId[it] }
            rel != null && !rel.external && OoxmlPackage.resolve(wbPart, rel.target) == part
        } ?: throw IllegalStateException("workbook has no entry for sheet $index")
    }

    private fun validName(name: String, except: Int): String {
        val n = name.trim()
        require(n.isNotEmpty()) { "sheet name is empty" }
        require(n.length <= 31) { "sheet name is longer than 31 characters" }
        require(n.none { it in "[]:*?/\\" }) { "sheet name has a character that is not allowed" }
        require(!n.startsWith("'") && !n.endsWith("'")) { "sheet name starts or ends with an apostrophe" }
        require(sheets.withIndex().none { it.index != except && it.value.name.equals(n, ignoreCase = true) }) { "a sheet called $n exists" }
        return n
    }

    private fun freeName(base: String): String {
        fun taken(n: String) = sheets.any { it.name.equals(n, ignoreCase = true) }
        val stem = Regex(" \\(\\d+\\)$").find(base)?.let { base.substring(0, it.range.first) } ?: base
        var k = 2
        while (true) {
            val suffix = " ($k)"
            val n = stem.take(31 - suffix.length) + suffix
            if (!taken(n)) return n
            k++
        }
    }

    private fun newSheetPart(): String {
        var n = 1
        while (pkg.has("xl/worksheets/sheet$n.xml")) n++
        return "xl/worksheets/sheet$n.xml"
    }

    /** Adds a `<sheet>` entry for [part] to workbook.xml, after entry [afterIndex] (−1 = at the end). */
    private fun addWbEntry(name: String, part: String, afterIndex: Int) {
        val rid = Rels.add(pkg, wbPart, "$REL/worksheet", relTarget(wbPart, part))
        ContentTypes.addOverride(pkg, part, CT_SHEET)
        val wbXml = pkg.text(wbPart)!!
        val root = Xml.root(wbXml)!!
        val all = wbSheetElems(wbXml)
        val ids = all.mapNotNull { Xml.attr(wbXml, it, "sheetId")?.toIntOrNull() }
        val sheetId = (ids.maxOrNull() ?: 0) + 1
        val proto = all.firstOrNull()
        val elName = proto?.name ?: "sheet"
        val idKey = proto?.let { p -> Xml.attrs(wbXml, p).keys.firstOrNull { it.indexOf(':') >= 0 && it.substringAfter(':') == "id" } } ?: "r:id"
        val nsPfx = idKey.substringBefore(':')
        val declared = Xml.attr(wbXml, root, "xmlns:$nsPfx") == REL
        val tag = "<$elName" + (if (declared) "" else " xmlns:$nsPfx=\"$REL\"") +
            " name=\"${Xml.escape(name)}\" sheetId=\"$sheetId\" $idKey=\"$rid\"/>"
        val out = if (afterIndex >= 0) {
            val at = wbSheetElem(wbXml, afterIndex)
            wbXml.substring(0, at.end) + tag + wbXml.substring(at.end)
        } else {
            val sheetsEl = XmlX.kid(wbXml, root, "sheets") ?: throw IllegalStateException("workbook has no sheet list")
            if (sheetsEl.selfClosing) wbXml.substring(0, sheetsEl.start) + XmlX.startTag(wbXml, sheetsEl, emptyMap(), false) + tag + "</" + sheetsEl.name + ">" + wbXml.substring(sheetsEl.end)
            else wbXml.substring(0, sheetsEl.closeStart) + tag + wbXml.substring(sheetsEl.closeStart)
        }
        pkg.putText(wbPart, out)
    }

    /**
     * Moves sheet-index numbers in workbook.xml after a sheet was inserted at [at] ([delta] = 1) or deleted at [at]
     * ([delta] = −1): `localSheetId` of defined names (a name local to a deleted sheet goes) and the active / first tab.
     */
    private fun shiftWbIndices(at: Int, delta: Int, count: Int) {
        var wbXml = pkg.text(wbPart) ?: return
        val root = Xml.root(wbXml) ?: return
        val edits = ArrayList<Triple<Int, Int, String>>()
        XmlX.kid(wbXml, root, "definedNames")?.let { dn ->
            for (n in Xml.children(wbXml, dn)) {
                val id = Xml.attr(wbXml, n, "localSheetId")?.toIntOrNull() ?: continue
                if (delta < 0 && id == at) { edits.add(Triple(n.start, n.end, "")); continue }
                if (id >= at && !(delta < 0 && id == at)) {
                    val span = XmlX.attrValueSpan(wbXml, n, "localSheetId") ?: continue
                    val nid = if (delta > 0) id + 1 else id - 1
                    edits.add(Triple(span.first, span.second, nid.toString()))
                }
            }
        }
        XmlX.kid(wbXml, root, "bookViews")?.let { bv ->
            for (v in Xml.children(wbXml, bv)) for (attr in listOf("activeTab", "firstSheet")) {
                val cur = Xml.attr(wbXml, v, attr)?.toIntOrNull() ?: continue
                var nv = if (cur >= at && delta > 0) cur + 1 else if (cur > at && delta < 0) cur - 1 else cur
                nv = nv.coerceIn(0, (count - 1).coerceAtLeast(0))
                if (nv != cur) XmlX.attrValueSpan(wbXml, v, attr)?.let { edits.add(Triple(it.first, it.second, nv.toString())) }
            }
        }
        if (edits.isNotEmpty()) { wbXml = Xml.splice(wbXml, edits); pkg.putText(wbPart, wbXml) }
    }

    /** Applies [f] to the text of every chart `c:f` and every defined name. */
    private fun rewriteFormulaParts(f: (String) -> String) {
        for (part in pkg.names()) {
            if (!part.startsWith("xl/charts/") || !part.endsWith(".xml") || part.contains("/_rels/")) continue
            val xml = pkg.text(part) ?: continue
            val root = Xml.root(xml) ?: continue
            if (Xml.local(root.name) != "chartSpace") continue
            textEdits(xml, XmlX.findAllLocal(xml, root, "f"), f)?.let { pkg.putText(part, it) }
        }
        pkg.text(wbPart)?.let { wbXml ->
            val names = Xml.root(wbXml)?.let { XmlX.kid(wbXml, it, "definedNames") }
            if (names != null) textEdits(wbXml, Xml.children(wbXml, names), f)?.let { pkg.putText(wbPart, it) }
        }
    }

    private fun textEdits(xml: String, elems: List<Xml.Elem>, f: (String) -> String): String? {
        val edits = ArrayList<Triple<Int, Int, String>>()
        for (e in elems) {
            if (e.selfClosing) continue
            val text = Xml.text(xml, e)
            val nt = f(text)
            if (nt != text) edits.add(Triple(e.openEnd, e.closeStart, Xml.escape(nt)))
        }
        return if (edits.isEmpty()) null else Xml.splice(xml, edits)
    }

    /** Rewrites every formula cell for which [f] gives new text, then puts everything into the parts. */
    private fun rewriteFormulaCells(skipSheet: Int, f: (String) -> String) {
        var any = false
        for (c in ArrayList(formulaCells)) {
            if (c.sheet == skipSheet) continue
            val old = c.formula ?: continue
            val nf = f(old)
            if (nf != old) { setFormulaText(sheets[c.sheet], c, nf); any = true }
        }
        if (any) {
            formulaLayoutChanged = true
            for (c in formulaCells) if (!c.opaque) { c.state = TODO; c.precs = null }
            dirty = true
        }
    }

    /** Adds a blank sheet at the end, called [name] or the first free "SheetN"; returns its index. */
    fun addSheet(name: String? = null): Int {
        flush()
        val n = if (name != null) validName(name, -1) else {
            var k = sheets.size + 1
            while (sheets.any { it.name.equals("Sheet$k", ignoreCase = true) }) k++
            "Sheet$k"
        }
        val part = newSheetPart()
        pkg.putText(part, DECL + "<worksheet xmlns=\"$MAIN\" xmlns:r=\"$REL\"><sheetData/></worksheet>")
        addWbEntry(n, part, -1)
        load()
        dirty = true
        return sheets.indexOfFirst { it.part == part }
    }

    /**
     * Renames a sheet. Throws [IllegalArgumentException] for an empty name, one longer than 31 characters, one with
     * `[ ] : * ? / \`, or one another sheet has (any case). References to the sheet in formulas of every sheet, in chart
     * series and in defined names follow the new name.
     */
    fun renameSheet(index: Int, name: String) {
        val old = sheetAt(index).name
        val n = validName(name, index)
        if (n == old) return
        flush()
        rewriteFormulaCells(-1) { Formula.renameSheetRefs(it, old, n) }
        // the new name resolves while caches are recomputed before writing
        sheetIndexByName = sheetIndexByName + (n.lowercase(Locale.ROOT) to index)
        commitToPackage()
        rewriteFormulaParts { Formula.renameSheetRefs(it, old, n) }
        val wbXml = pkg.text(wbPart)!!
        val e = wbSheetElem(wbXml, index)
        val span = XmlX.attrValueSpan(wbXml, e, "name")!!
        pkg.putText(wbPart, wbXml.substring(0, span.first) + Xml.escape(n) + wbXml.substring(span.second))
        load()
        dirty = true
    }

    /**
     * Inserts a copy of a sheet right after it, called "Name (2)" (first free); returns its index. Cells, styles,
     * column widths and row heights are copied, formulas unchanged. Drawings, charts, tables, comments and links of
     * the source are not copied.
     */
    fun copySheet(index: Int): Int {
        val src = sheetAt(index)
        flush()
        val name = freeName(sheets[index].name)
        val xml = pkg.text(src.part) ?: throw IllegalStateException("sheet $index has no part")
        val root = Xml.root(xml) ?: throw IllegalStateException("sheet $index is not readable")
        val edits = ArrayList<Triple<Int, Int, String>>()
        for (k in Xml.children(xml, root)) when (Xml.local(k.name)) {
            "drawing", "legacyDrawing", "legacyDrawingHF", "drawingHF", "picture", "oleObjects", "controls", "tableParts", "hyperlinks" ->
                edits.add(Triple(k.start, k.end, ""))
            "pageSetup" -> XmlX.attrSpans(xml, k).firstOrNull { it.name.indexOf(':') >= 0 && it.name.substringAfter(':') == "id" }?.let { a ->
                edits.add(Triple(a.start - 1, a.end, ""))
            }
            "sheetViews" -> for (v in Xml.children(xml, k)) XmlX.attrSpans(xml, v).firstOrNull { it.name == "tabSelected" }?.let { a ->
                edits.add(Triple(a.start - 1, a.end, ""))
            }
        }
        val part = newSheetPart()
        pkg.putText(part, Xml.splice(xml, edits))
        addWbEntry(name, part, index)
        shiftWbIndices(index + 1, 1, sheets.size + 1)
        load()
        dirty = true
        return sheets.indexOfFirst { it.part == part }
    }

    /**
     * Deletes a sheet; refuses ([IllegalStateException]) the only one. References to it from other sheets, charts and
     * defined names become `#REF!`; names local to it go; the active tab stays in range.
     */
    fun deleteSheet(index: Int) {
        val gone = sheetAt(index)
        if (sheets.size <= 1) throw IllegalStateException("a workbook keeps at least one sheet")
        flush()
        val name = gone.name
        rewriteFormulaCells(index) { Formula.renameSheetRefs(it, name, null) }
        commitToPackage()
        rewriteFormulaParts { Formula.renameSheetRefs(it, name, null) }
        val wbXml = pkg.text(wbPart)!!
        val e = wbSheetElem(wbXml, index)
        val rid = XmlX.prefixedAttr(wbXml, e, "id")
        pkg.putText(wbPart, wbXml.substring(0, e.start) + wbXml.substring(e.end))
        shiftWbIndices(index, -1, sheets.size - 1)
        // the relationship, the part, its own relationships and its content-type entry
        val relsName = OoxmlPackage.relsOf(wbPart)
        pkg.text(relsName)?.let { rx ->
            val rr = Xml.root(rx)
            val hit = rr?.let { r -> Xml.children(rx, r).firstOrNull { Xml.attr(rx, it, "Id") == rid } }
            if (hit != null) pkg.putText(relsName, rx.substring(0, hit.start) + rx.substring(hit.end))
        }
        pkg.remove(gone.part)
        OoxmlPackage.relsOf(gone.part).let { if (pkg.has(it)) pkg.remove(it) }
        ContentTypes.removeOverride(pkg, gone.part)
        formulaLayoutChanged = true
        dropCalcChain()
        load()
        dirty = true
    }

    private fun shiftFormulaElements(xml: String, elems: List<Xml.Elem>, target: String, cols: Boolean, at: Int, delta: Int): String? {
        val edits = ArrayList<Triple<Int, Int, String>>()
        for (f in elems) {
            if (f.selfClosing) continue
            val text = Xml.text(xml, f)
            val nt = Formula.shift(text, Lexer.tokenize(text), null, target, cols, at, delta)
            if (nt != text) edits.add(Triple(f.openEnd, f.closeStart, Xml.escape(nt)))
        }
        return if (edits.isEmpty()) null else Xml.splice(xml, edits)
    }

    /** A rectangle (r1, c1, r2, c2) after [ops]; null when a deletion removed all of it. */
    private fun applyOps(rg: IntArray, ops: List<IntArray>): IntArray? {
        val out = rg.copyOf()
        for (op in ops) {
            val lo = if (op[0] == 0) 0 else 1
            val hi = lo + 2
            val at = op[1]
            if (op[2] > 0) {
                if (out[lo] >= at) out[lo]++
                if (out[hi] >= at) out[hi]++
            } else {
                if (out[lo] == at && out[hi] == at) return null
                val a = out[lo]; val b = out[hi]
                if (a > at) out[lo] = a - 1
                if (b > at || (b == at && b > a)) out[hi] = b - 1
            }
        }
        return out
    }

    /** A `ref` / `sqref` value (space-separated ranges) after the sheet's row / column insertions and deletions. */
    private fun shiftRefList(value: String, ops: List<IntArray>): String = value.split(' ').mapNotNull { tok ->
        val rg = A1.parseRange(tok)
        if (rg == null || tok.indexOf('$') >= 0) tok
        else {
            val n = applyOps(rg, ops)
            when {
                n == null -> null
                tok.indexOf(':') >= 0 -> A1.name(n[0], n[1]) + ":" + A1.name(n[2], n[3])
                else -> A1.name(n[0], n[1])
            }
        }
    }.joinToString(" ")

    private fun mergedRanges(s: Sheet): List<IntArray> {
        val out = ArrayList<IntArray>()
        for (k in s.rootKids) if (Xml.local(k.name) == "mergeCells") for (m in Xml.children(s.xml, k)) {
            val rg = Xml.attr(s.xml, m, "ref")?.let { A1.parseRange(it) } ?: continue
            applyOps(rg, s.ops)?.let { out.add(it) }
        }
        return out
    }

    private fun sortRank(v: Any?): Int = when (v) { is Double -> 0; is String -> 1; is Boolean -> 2; is ErrVal -> 3; else -> 4 }

    private fun sortCompare(a: Any?, b: Any?, ascending: Boolean): Int {
        val ra = sortRank(a)
        val rb = sortRank(b)
        if (ra == 4 || rb == 4) return ra.compareTo(rb) // blanks always last
        val c = if (ra != rb) ra.compareTo(rb) else when (a) {
            is Double -> a.compareTo(b as Double)
            // v0.6.0 (s31): text in dictionary order (Chinese by pinyin, as Excel / WPS sort it; Latin ignoring case)
            is String -> TEXT_ORDER.compare(a, b as String).let { if (it != 0) it else a.compareTo(b, ignoreCase = true) }
            is Boolean -> a.compareTo(b as Boolean)
            else -> 0
        }
        return if (ascending) c else -c
    }

    /**
     * Sorts the rows [firstRow]..[lastRow] by the value in [col] (numbers, then text, then booleans, then errors; blanks
     * last; stable). Whole rows of cells move; row heights stay. Formulas inside the block that point at their own row
     * follow the row. Returns false and changes nothing when the sort would break something: a merged range in the
     * block, a formula in the block that points at another row of the block, or a formula elsewhere (any sheet) that
     * points at part of the block.
     */
    fun sortRows(sheet: Int, col: Int, ascending: Boolean, firstRow: Int, lastRow: Int): Boolean =
        sortRowsBy(sheet, listOf(col to ascending), firstRow, lastRow)

    /**
     * v0.6.0 (s31): sorts rows [firstRow]..[lastRow] by several columns: [keys] = (column, ascending) in order of
     * importance; rows equal on the first key are ordered by the second, and so on (stable). Same rules and refusals
     * as [sortRows].
     */
    fun sortRowsBy(sheet: Int, keys: List<Pair<Int, Boolean>>, firstRow: Int, lastRow: Int): Boolean {
        val s = sheetAt(sheet)
        if (keys.isEmpty() || keys.any { it.first < 0 || it.first > A1.MAX_COL } || firstRow < 0 || lastRow > A1.MAX_ROW) return false
        if (lastRow <= firstRow) return true
        evaluateAll()
        val n = lastRow - firstRow + 1
        val values = Array(keys.size) { k -> arrayOfNulls<Any?>(n).also { arr -> for (i in 0 until n) arr[i] = s.rows[firstRow + i]?.cells?.get(keys[k].first)?.let { valueOf(sheet, it) } } }
        val order = (0 until n).sortedWith { a, b ->
            var c = 0
            for (k in keys.indices) { c = sortCompare(values[k][a], values[k][b], keys[k].second); if (c != 0) break }
            c
        }
        if (order.indices.all { order[it] == it }) return true

        if (mergedRanges(s).any { it[0] <= lastRow && it[2] >= firstRow }) return false
        for (c in formulaCells) {
            val ti = c.sheet
            val p = c.parsed!!
            val inside = ti == sheet && c.row in firstRow..lastRow
            if (inside && (c.forcedOpaque || p.root == null || p.toks.any { it.t == TT.NAME })) return false
            for (ref in p.refs) {
                val target = if (ref.sheet == null) ti else host.sheetIndex(ref.sheet)
                if (target != sheet || ref.kind == RefTok.COLS) continue
                if (ref.r2 < firstRow || ref.r1 > lastRow) continue
                if (ref.r1 <= firstRow && ref.r2 >= lastRow) continue
                if (inside && ref.r1 == c.row && ref.r2 == c.row && !ref.absR1 && !ref.absR2) continue
                return false
            }
        }

        val oldCells = ArrayList<List<CellRec>>(n)
        for (i in 0 until n) {
            val r = s.rows[firstRow + i]
            oldCells.add(r?.cells?.values?.toList() ?: emptyList())
            if (r != null) { r.cells.clear(); r.touched = true }
        }
        for (i in 0 until n) {
            val cells = oldCells[order[i]]
            if (cells.isEmpty()) continue
            val destIdx = firstRow + i
            val dest = s.rows[destIdx] ?: RowRec(destIdx, null).also { s.rows[destIdx] = it }
            dest.touched = true
            for (c in cells) {
                val oldRow = c.row
                c.row = destIdx
                dest.cells[c.col] = c
                val f = c.formula
                if (f != null && oldRow != destIdx) {
                    val nf = Formula.rewriteRefs(f, c.parsed!!.toks) { ref ->
                        val own = ref.sheet == null || host.sheetIndex(ref.sheet) == sheet
                        if (own && ref.kind != RefTok.COLS && ref.r1 == oldRow && ref.r2 == oldRow && !ref.absR1 && !ref.absR2) ref.copy(r1 = destIdx, r2 = destIdx).areaText() else null
                    }
                    if (nf != f) setFormulaText(s, c, nf)
                }
            }
        }
        s.structural = true
        for (r in s.rows.values) for (c in r.cells.values) {
            if (c.sharedSi >= 0) { c.sharedSi = -1; c.sharedMaster = false; if (c.formula != null) c.contentChanged = true }
        }
        for (c in formulaCells) if (!c.opaque) { c.state = TODO; c.precs = null }
        formulaLayoutChanged = true
        s.usedRows = -1; s.usedCols = -1
        dirty = true
        return true
    }

    /** Sets `=SUM(…)` over the run of numbers directly above the cell (else directly to its left) and returns the formula; null when there is none. */
    fun autoSum(sheet: Int, row: Int, col: Int): String? {
        val s = sheetAt(sheet)
        checkPos(row, col)
        fun isNum(r: Int, c: Int): Boolean = s.rows[r]?.cells?.get(c)?.let { valueOf(sheet, it) is Double } ?: false
        var r = row - 1
        while (r >= 0 && isNum(r, col)) r--
        var formula: String? = null
        if (r < row - 1) formula = "=SUM(" + A1.name(r + 1, col) + ":" + A1.name(row - 1, col) + ")"
        else {
            var c = col - 1
            while (c >= 0 && isNum(row, c)) c--
            if (c < col - 1) formula = "=SUM(" + A1.name(row, c + 1) + ":" + A1.name(row, col - 1) + ")"
        }
        if (formula != null) setCell(sheet, row, col, formula)
        return formula
    }

    // ---- charts ----

    private fun resolveArea(text: String?): Area? {
        if (text.isNullOrBlank()) return null
        val toks = Lexer.tokenize(text).filter { it.t != TT.LP && it.t != TT.RP }
        if (toks.size != 1) return null
        val r = toks[0].ref ?: return null
        val si = r.sheet?.let { host.sheetIndex(it) } ?: return null
        if (si < 0) return null
        val r2 = if (r.kind == RefTok.COLS) maxOf(r.r1, minOf(r.r2, rows(si) - 1)) else r.r2
        val c2 = if (r.kind == RefTok.ROWS) maxOf(r.c1, minOf(r.c2, cols(si) - 1)) else r.c2
        if ((r2 - r.r1 + 1).toLong() * (c2 - r.c1 + 1) > 1_000_000L) return null
        return Area(si, r.r1, r.c1, r2, c2)
    }

    private fun cellsOf(a: Area): List<Cell> {
        val out = ArrayList<Cell>()
        for (r in a.r1..a.r2) for (c in a.c1..a.c2) out.add(cell(a.sheet, r, c))
        return out
    }

    private fun chartRange(def: ChartDef): String? {
        val areas = ArrayList<Area>()
        for (sd in def.series) for (ref in listOf(sd.nameRef, sd.catRef, sd.valRef)) {
            if (ref == null) continue
            areas.add(resolveArea(ref) ?: return null)
        }
        if (areas.isEmpty()) return null
        val si = areas[0].sheet
        if (areas.any { it.sheet != si }) return null
        val r1 = areas.minOf { it.r1 }
        val c1 = areas.minOf { it.c1 }
        val r2 = areas.maxOf { it.r2 }
        val c2 = areas.maxOf { it.c2 }
        val w = c2 - c1 + 1
        val total = w.toLong() * (r2 - r1 + 1)
        if (total > 2_000_000L) return null
        val seen = BitSet(total.toInt())
        for (a in areas) for (r in a.r1..a.r2) for (c in a.c1..a.c2) seen.set((r - r1) * w + (c - c1))
        val covered = seen.cardinality().toLong()
        // a header row plus a name column leaves the top-left corner unreferenced: still one rectangle
        if (!(covered == total || (covered == total - 1 && !seen.get(0)))) return null
        val text = A1.rangeName(r1, c1, r2, c2)
        return if (si == def.sheet) text else Charts.quoteSheet(sheets[si].name) + "!" + text
    }

    /** Charts anchored on the sheets. Names, categories and values come from the cells as they are now, so they follow edits. */
    fun charts(): List<ChartInfo> {
        val defs = chartDefs ?: Charts.read(pkg, sheets.map { it.part }).also { chartDefs = it }
        return defs.map { def ->
            val series = def.series.mapIndexed { i, sd ->
                val name = resolveArea(sd.nameRef)?.let { a -> cellsOf(a).joinToString(" ") { it.display }.trim() } ?: sd.nameLit ?: "Series ${i + 1}"
                val values = resolveArea(sd.valRef)?.let { a -> cellsOf(a).map { it.number } } ?: sd.valCache ?: emptyList()
                val catCells = resolveArea(sd.catRef)?.let { cellsOf(it) }
                val cats = catCells?.map { it.display } ?: sd.catCache ?: List(values.size) { (it + 1).toString() }
                val xs = if (def.type == "scatter") {
                    catCells?.map { it.number } ?: sd.catCache?.map { it.toDoubleOrNull() } ?: List<Double?>(values.size) { (it + 1).toDouble() }
                } else null
                ChartSeries(name, cats, values, sd.valRef, xs)
            }
            val title = resolveArea(def.titleRef)?.let { a -> cell(a.sheet, a.r1, a.c1).display } ?: def.titleLit
            ChartInfo(def.part, def.sheet, title, def.type, series, chartRange(def))
        }
    }

    /**
     * Adds a clustered bar chart over [range] (first row = categories, first column = series names, one series per
     * further row), anchored to the right of the sheet's data.
     */
    fun addBarChart(sheet: Int, range: String, title: String): ChartInfo = addChart(sheet, range, title, "bar")

    /** Like [addBarChart]; [type] is "bar" (columns), "line" or "pie" (first data row only). */
    fun addChart(sheet: Int, range: String, title: String, type: String): ChartInfo {
        val s = sheetAt(sheet)
        val rg = A1.parseRange(range) ?: throw IllegalArgumentException("range: $range")
        val r1 = rg[0]
        val c1 = rg[1]
        val r2 = rg[2]
        val c2 = rg[3]
        require(r2 > r1 && c2 > c1) { "range needs a header row, a name column and data: $range" }
        val names = (r1 + 1..r2).map { cell(sheet, it, c1).display }
        val cats = (c1 + 1..c2).map { cell(sheet, r1, it).display }
        val values = (r1 + 1..r2).map { r -> (c1 + 1..c2).map { c -> cell(sheet, r, c).number } }
        val anchorCol = cols(sheet) + 1

        var n = 1
        while (pkg.has("xl/charts/chart$n.xml")) n++
        val chartPart = "xl/charts/chart$n.xml"
        pkg.putText(chartPart, Charts.chartXml(type, title, s.name, r1, c1, r2, c2, names, cats, values))
        ContentTypes.addOverride(pkg, chartPart, Charts.CT_CHART)

        var drawing = Charts.drawingOf(pkg, s.part)?.takeIf { pkg.has(it) }
        if (drawing == null) {
            var d = 1
            while (pkg.has("xl/drawings/drawing$d.xml")) d++
            drawing = "xl/drawings/drawing$d.xml"
            pkg.putText(drawing, Charts.emptyDrawingXml())
            ContentTypes.addOverride(pkg, drawing, Charts.CT_DRAWING)
            s.pendingDrawingRid = Rels.add(pkg, s.part, Charts.REL_DRAWING, relTarget(s.part, drawing))
        }
        val dx = pkg.text(drawing)!!
        val stats = Charts.drawingStats(dx)
        val rid = Rels.add(pkg, drawing, Charts.REL_CHART, relTarget(drawing, chartPart))
        val anchor = Charts.anchorXml(anchorCol, stats.second + 2, 8, 16, stats.first + 1, "Chart $n", rid)
        pkg.putText(drawing, Xml.appendChild(dx, anchor))
        chartDefs = null
        dirty = true
        return charts().first { it.part == chartPart }
    }

    // ---- other outputs ----

    /** Things in the file this module keeps untouched but does not edit (READ_ONLY) or shows approximately (APPROX). */
    fun compat(): List<CompatNote> {
        val ro = LinkedHashMap<String, Int>()
        val approx = LinkedHashMap<String, Int>()
        fun add(m: MutableMap<String, Int>, code: String, n: Int) { if (n > 0) m[code] = (m[code] ?: 0) + n }
        for (s in sheets) {
            for (k in s.rootKids) when (Xml.local(k.name)) {
                "conditionalFormatting" -> add(ro, "conditional-format", 1)
                "dataValidations" -> add(ro, "data-validation", XmlX.kids(s.xml, k, "dataValidation").size)
                "tableParts" -> add(ro, "table", Xml.children(s.xml, k).size)
            }
        }
        val names = pkg.names()
        add(ro, "pivot-table", names.count { it.startsWith("xl/pivotTables/") && it.endsWith(".xml") && !it.contains("/_rels/") })
        add(ro, "comments", names.count { (it.startsWith("xl/comments") || it.startsWith("xl/threadedComments/")) && it.endsWith(".xml") })
        add(ro, "image", names.count { it.startsWith("xl/media/") })
        add(ro, "macros", names.count { it.endsWith("vbaProject.bin") })
        pkg.text(wbPart)?.let { wbXml ->
            Xml.root(wbXml)?.let { XmlX.kid(wbXml, it, "definedNames") }?.let { add(ro, "defined-names", Xml.children(wbXml, it).size) }
        }
        add(ro, "chart:other", charts().count { it.type == "other" })
        add(approx, "formula", formulaCells.count { it.opaque })
        for ((k, v) in editNotes) add(approx, k, v)
        return ro.map { CompatNote(CompatKind.READ_ONLY, it.key, it.value) } + approx.map { CompatNote(CompatKind.APPROX, it.key, it.value) }
    }

    /** The used range as CSV text (RFC 4180), cells as displayed. */
    fun toCsv(sheet: Int): String {
        val nr = rows(sheet)
        val nc = cols(sheet)
        return Csv.write((0 until nr).map { r -> (0 until nc).map { c -> cell(sheet, r, c).display } })
    }

    // ---- saving ----

    private fun cacheChanged(c: CellRec): Boolean = c.formula != null && !c.opaque && !same(c.value, c.baseValue)

    private fun needsRewrite(c: CellRec): Boolean = c.contentChanged || c.styleChanged || cacheChanged(c)

    private fun materialises(c: CellRec): Boolean = c.value != null || c.formula != null || c.style != 0

    private fun moved(c: CellRec): Boolean = c.row != c.origRow || c.col != c.origCol

    private fun cellXml(s: Sheet, c: CellRec): String {
        val xml = s.xml
        val p = s.pfx
        val ref = A1.name(c.row, c.col)
        val src = c.src
        val content = c.contentChanged || cacheChanged(c)
        if (src != null && !content) {
            // style and / or position only: everything inside the element is kept
            val set = LinkedHashMap<String, String?>()
            set["r"] = ref
            if (c.styleChanged) set["s"] = c.style.toString()
            return XmlX.startTag(xml, src, set, src.selfClosing) + xml.substring(src.openEnd, src.end)
        }
        val inner = StringBuilder()
        var type: String? = null
        val formula = c.formula
        if (formula != null) {
            val fSrc = c.fSrc
            if (!c.contentChanged && fSrc != null) inner.append(xml, fSrc.start, fSrc.end)
            else inner.append("<").append(p).append("f>").append(Xml.escape(formula)).append("</").append(p).append("f>")
        }
        val writeValue = !(formula != null && c.opaque && !c.hadCache)
        if (writeValue) when (val v = c.value) {
            null -> {}
            is Double -> inner.append("<${p}v>").append(Num.xml(v)).append("</${p}v>")
            is Boolean -> { type = "b"; inner.append("<${p}v>").append(if (v) "1" else "0").append("</${p}v>") }
            is ErrVal -> if (v.code in ErrVal.STANDARD) { type = "e"; inner.append("<${p}v>").append(Xml.escape(v.code)).append("</${p}v>") }
            else -> {
                val t = v.toString()
                val space = if (needsPreserve(t)) " xml:space=\"preserve\"" else ""
                if (formula != null) { type = "str"; inner.append("<${p}v$space>").append(textXml(t)).append("</${p}v>") }
                else { type = "inlineStr"; inner.append("<${p}is><${p}t$space>").append(textXml(t)).append("</${p}t></${p}is>") }
            }
        }
        if (src != null) {
            if (!c.contentChanged && !src.selfClosing) {
                for (k in Xml.children(xml, src)) {
                    val l = Xml.local(k.name)
                    if (l != "f" && l != "v" && l != "is") inner.append(xml, k.start, k.end)
                }
            }
            val set = LinkedHashMap<String, String?>()
            set["r"] = ref
            if (c.style != 0 || Xml.attr(xml, src, "s") != null) set["s"] = c.style.toString()
            set["t"] = type
            if (c.contentChanged) { set["cm"] = null; set["vm"] = null }
            val open = XmlX.startTag(xml, src, set, inner.isEmpty())
            return if (inner.isEmpty()) open else open + inner + "</" + src.name + ">"
        }
        val sb = StringBuilder("<").append(p).append("c r=\"").append(ref).append('"')
        if (c.style != 0) sb.append(" s=\"").append(c.style).append('"')
        if (type != null) sb.append(" t=\"").append(type).append('"')
        return if (inner.isEmpty()) sb.append("/>").toString() else sb.append('>').append(inner).append("</").append(p).append("c>").toString()
    }

    private fun inPlaceEdits(s: Sheet, edits: MutableList<Triple<Int, Int, String>>) {
        val xml = s.xml
        val sd = s.sheetData
        val p = s.pfx
        var minR = Int.MAX_VALUE
        var maxR = -1
        var minC = Int.MAX_VALUE
        var maxC = -1
        fun extent(c: CellRec) {
            if (c.row < minR) minR = c.row
            if (c.row > maxR) maxR = c.row
            if (c.col < minC) minC = c.col
            if (c.col > maxC) maxC = c.col
        }
        // where a new row goes: in front of the next row that exists in the file, else at the end of the sheet data
        val rowPos = HashMap<RowRec, Int>()
        var next = sd?.closeStart ?: -1
        for (r in s.rows.descendingMap().values) { if (r.src != null) next = r.src.start else rowPos[r] = next }
        val detached = StringBuilder()
        for (r in s.rows.values) {
            val src = r.src
            if (src == null) {
                val sb = StringBuilder()
                for (c in r.cells.values) if (materialises(c)) { sb.append(cellXml(s, c)); extent(c) }
                if (sb.isEmpty() && !rowNeeded(r)) continue
                val rowXml = "<${p}row r=\"${r.index + 1}\"" + htAttrs(r) + ">$sb</${p}row>"
                if (sd == null || sd.selfClosing) detached.append(rowXml) else edits.add(Triple(rowPos.getValue(r), rowPos.getValue(r), rowXml))
                continue
            }
            var nextCell = src.closeStart
            val inserts = ArrayList<Pair<Int, String>>()
            var addMin = Int.MAX_VALUE
            var addMax = -1
            for (c in r.cells.descendingMap().values) {
                val cs = c.src
                if (cs != null) {
                    nextCell = cs.start
                    if (needsRewrite(c)) edits.add(Triple(cs.start, cs.end, cellXml(s, c)))
                } else if (materialises(c)) {
                    inserts.add(Pair(nextCell, cellXml(s, c)))
                    extent(c)
                    if (c.col < addMin) addMin = c.col
                    if (c.col > addMax) addMax = c.col
                }
            }
            val htSet: Map<String, String?> = rowSet(r)
            if (inserts.isEmpty()) {
                if (htSet.isNotEmpty()) edits.add(Triple(src.start, src.openEnd, XmlX.startTag(xml, src, htSet, src.selfClosing)))
                continue
            }
            // keep the optional spans hint true when a cell lands outside it
            var spans: String? = null
            Xml.attr(xml, src, "spans")?.let { cur ->
                val a = cur.substringBefore(':').trim().toIntOrNull()
                val b = cur.substringAfter(':').trim().toIntOrNull()
                if (a != null && b != null && (addMin + 1 < a || addMax + 1 > b)) spans = "${minOf(a, addMin + 1)}:${maxOf(b, addMax + 1)}"
            }
            val set = LinkedHashMap<String, String?>(htSet)
            if (spans != null) set["spans"] = spans
            if (src.selfClosing) {
                val body = inserts.asReversed().joinToString("") { it.second }
                edits.add(Triple(src.start, src.end, XmlX.startTag(xml, src, set, false) + body + "</" + src.name + ">"))
            } else {
                if (set.isNotEmpty()) edits.add(Triple(src.start, src.openEnd, XmlX.startTag(xml, src, set, false)))
                for (ins in inserts.asReversed()) edits.add(Triple(ins.first, ins.first, ins.second))
            }
        }
        if (detached.isNotEmpty()) {
            if (sd == null) throw IllegalStateException("sheet part has no sheet data")
            edits.add(Triple(sd.start, sd.end, "<" + sd.name + ">" + detached + "</" + sd.name + ">"))
        }
        if (maxR >= 0) {
            val dim = s.rootKids.firstOrNull { Xml.local(it.name) == "dimension" }
            val cur = dim?.let { Xml.attr(xml, it, "ref") }?.let { A1.parseRange(it) }
            val span = dim?.let { XmlX.attrValueSpan(xml, it, "ref") }
            if (cur != null && span != null && (minR < cur[0] || minC < cur[1] || maxR > cur[2] || maxC > cur[3])) {
                edits.add(Triple(span.first, span.second, A1.rangeName(minOf(cur[0], minR), minOf(cur[1], minC), maxOf(cur[2], maxR), maxOf(cur[3], maxC))))
            }
        }
    }

    private fun structuralEdits(s: Sheet, edits: MutableList<Triple<Int, Int, String>>) {
        val xml = s.xml
        val sd = s.sheetData ?: throw IllegalStateException("sheet part has no sheet data")
        val p = s.pfx
        val sb = StringBuilder()
        var minR = Int.MAX_VALUE
        var maxR = -1
        var minC = Int.MAX_VALUE
        var maxC = -1
        for (r in s.rows.values) {
            val src = r.src
            val cells = r.cells.values.filter { it.src != null || materialises(it) }
            for (c in cells) if (c.value != null || c.formula != null) {
                if (c.row < minR) minR = c.row
                if (c.row > maxR) maxR = c.row
                if (c.col < minC) minC = c.col
                if (c.col > maxC) maxC = c.col
            }
            if (src != null && r.index == r.origIndex && !r.touched && cells.all { it.src != null && !needsRewrite(it) && !moved(it) }) {
                sb.append(xml, src.start, src.end)
                continue
            }
            if (src == null && cells.isEmpty() && !rowNeeded(r)) continue
            val attrSet = LinkedHashMap<String, String?>()
            attrSet["r"] = (r.index + 1).toString(); attrSet["spans"] = null
            attrSet.putAll(rowSet(r))
            val rowSet = attrSet
            if (src != null) sb.append(XmlX.startTag(xml, src, rowSet, cells.isEmpty()))
            else sb.append("<${p}row r=\"${r.index + 1}\"" + htAttrs(r) + ">")
            if (src != null && cells.isEmpty()) continue
            if (src == null && cells.isEmpty()) { sb.append("</${p}row>"); continue }
            for (c in cells) {
                val cs = c.src
                if (cs != null && !needsRewrite(c) && !moved(c)) sb.append(xml, cs.start, cs.end) else sb.append(cellXml(s, c))
            }
            sb.append("</").append(src?.name ?: "${p}row").append(">")
        }
        if (sd.selfClosing) edits.add(Triple(sd.start, sd.end, "<" + sd.name + ">" + sb + "</" + sd.name + ">"))
        else edits.add(Triple(sd.openEnd, sd.closeStart, sb.toString()))

        fun shiftAttr(e: Xml.Elem, name: String) {
            val span = XmlX.attrValueSpan(xml, e, name) ?: return
            val old = xml.substring(span.first, span.second)
            val new = shiftRefList(old, s.ops)
            // a range that was deleted entirely takes its element with it
            if (new.isEmpty()) edits.add(Triple(e.start, e.end, ""))
            else if (new != old) edits.add(Triple(span.first, span.second, new))
        }
        val colOps = s.ops.filter { it[0] == 1 }
        for (k in s.rootKids) when (Xml.local(k.name)) {
            "dimension" -> XmlX.attrValueSpan(xml, k, "ref")?.let { span ->
                val new = if (maxR < 0) "A1" else A1.rangeName(minR, minC, maxR, maxC)
                if (new != xml.substring(span.first, span.second)) edits.add(Triple(span.first, span.second, new))
            }
            "mergeCells" -> if (!s.mergesChanged) for (m in Xml.children(xml, k)) shiftAttr(m, "ref")
            "conditionalFormatting" -> shiftAttr(k, "sqref")
            "dataValidations" -> for (m in Xml.children(xml, k)) shiftAttr(m, "sqref")
            "cols" -> if (colOps.isNotEmpty() && !s.colsChanged) for (m in Xml.children(xml, k)) {
                val a = Xml.attrs(xml, m)
                val min = a["min"]?.toIntOrNull() ?: continue
                val max = a["max"]?.toIntOrNull() ?: min
                val n = applyOps(intArrayOf(0, min - 1, 0, max - 1), colOps)
                if (n == null) edits.add(Triple(m.start, m.end, ""))
                else if (n[1] != min - 1 || n[3] != max - 1) edits.add(Triple(m.start, m.openEnd, XmlX.startTag(xml, m, mapOf("min" to (n[1] + 1).toString(), "max" to (n[3] + 1).toString()), m.selfClosing)))
            }
        }
    }

    private fun sheetXmlForSave(s: Sheet): String? {
        val edits = ArrayList<Triple<Int, Int, String>>()
        if (s.structural) structuralEdits(s, edits) else inPlaceEdits(s, edits)
        if (s.freezeSet) freezeEdits(s, edits)
        if (s.colsChanged) colsEdits(s, edits)
        if (s.mergesChanged) mergeEdits(s, edits)
        val rid = s.pendingDrawingRid
        val root = s.root
        if (rid != null && root != null) {
            // the drawing element has a fixed place in the sheet's child order
            val pos = s.rootKids.firstOrNull { Xml.local(it.name) in AFTER_DRAWING }?.start ?: root.closeStart
            val declared = Xml.attr(s.xml, root, "xmlns:r") == REL
            edits.add(Triple(pos, pos, "<${s.pfx}drawing " + (if (declared) "" else "xmlns:r=\"$REL\" ") + "r:id=\"$rid\"/>"))
        }
        return if (edits.isEmpty()) null else Xml.splice(s.xml, edits)
    }

    private fun htAttrs(r: RowRec): String = (if (r.htChanged && r.ht != null) " ht=\"${fmtNum(r.ht!!)}\" customHeight=\"1\"" else "") +
        (if (r.hidden) " hidden=\"1\"" else "")

    /** Row attributes changed here (height, s42 hidden): name → new value, null = remove. */
    private fun rowSet(r: RowRec): Map<String, String?> {
        val m = LinkedHashMap<String, String?>()
        if (r.htChanged) { m["ht"] = r.ht?.let { fmtNum(it) }; m["customHeight"] = if (r.ht != null) "1" else null }
        if (r.hiddenChanged) m["hidden"] = if (r.hidden) "1" else null
        return m
    }

    /** A row element is needed for this row even without cells (height or hidden set here). */
    private fun rowNeeded(r: RowRec): Boolean = (r.htChanged && r.ht != null) || r.hidden

    /** Rewrites `<cols>` from the sheet's column list (created in front of the sheet data when the sheet had none). */
    private fun colsEdits(s: Sheet, edits: MutableList<Triple<Int, Int, String>>) {
        val xml = s.xml
        val p = s.pfx
        val old = s.rootKids.firstOrNull { Xml.local(it.name) == "cols" }
        val block = if (s.cols.isEmpty()) "" else s.cols.sortedBy { it.min }.joinToString("", "<${p}cols>", "</${p}cols>") { ci ->
            "<${p}col" + ci.attrs.entries.joinToString("") { " ${it.key}=\"${Xml.escape(it.value)}\"" } + "/>"
        }
        if (old != null) edits.add(Triple(old.start, old.end, block))
        else if (block.isNotEmpty()) {
            val pos = s.sheetData?.start ?: s.root?.closeStart ?: return
            edits.add(Triple(pos, pos, block))
        }
    }

    /** s25: rewrites `<mergeCells>` from [Sheet.merges] (put in its schema place when the sheet had none; removed when empty). */
    private fun mergeEdits(s: Sheet, edits: MutableList<Triple<Int, Int, String>>) {
        val p = s.pfx
        val list = s.merges ?: return
        val old = s.rootKids.firstOrNull { Xml.local(it.name) == "mergeCells" }
        val block = if (list.isEmpty()) "" else list.joinToString("", "<${p}mergeCells count=\"${list.size}\">", "</${p}mergeCells>") {
            "<${p}mergeCell ref=\"" + A1.rangeName(it[0], it[1], it[2], it[3]) + "\"/>"
        }
        if (old != null) { edits.add(Triple(old.start, old.end, block)); return }
        if (block.isEmpty()) return
        val pos = s.rootKids.firstOrNull { Xml.local(it.name) in AFTER_MERGE }?.start ?: s.root?.closeStart ?: return
        edits.add(Triple(pos, pos, block))
    }

    /** Writes the frozen-rows pane into the first sheet view, creating the view list when the sheet has none. */
    private fun freezeEdits(s: Sheet, edits: MutableList<Triple<Int, Int, String>>) {
        val xml = s.xml
        val p = s.pfx
        val root = s.root ?: return
        val fr = s.frozen
        val fc = s.frozenCols
        val pane = when {
            fr > 0 && fc > 0 -> "<${p}pane xSplit=\"$fc\" ySplit=\"$fr\" topLeftCell=\"${A1.name(fr, fc)}\" activePane=\"bottomRight\" state=\"frozen\"/>"
            fr > 0 -> "<${p}pane ySplit=\"$fr\" topLeftCell=\"A${fr + 1}\" activePane=\"bottomLeft\" state=\"frozen\"/>"
            fc > 0 -> "<${p}pane xSplit=\"$fc\" topLeftCell=\"${A1.name(0, fc)}\" activePane=\"topRight\" state=\"frozen\"/>"
            else -> ""
        }
        val views = s.rootKids.firstOrNull { Xml.local(it.name) == "sheetViews" }
        val view = views?.let { XmlX.kid(xml, it, "sheetView") }
        if (view == null) {
            if (pane.isEmpty()) return
            val pos = s.rootKids.firstOrNull { Xml.local(it.name) in AFTER_VIEWS }?.start ?: root.closeStart
            val block = "<${p}sheetViews><${p}sheetView workbookViewId=\"0\">$pane</${p}sheetView></${p}sheetViews>"
            if (views != null) edits.add(Triple(views.start, views.end, block)) else edits.add(Triple(pos, pos, block))
            return
        }
        val old = XmlX.kid(xml, view, "pane")
        val sel = Xml.children(xml, view).filter { Xml.local(it.name) == "selection" }
        // the old selections name panes that may no longer exist: drop them with the pane
        if (old != null) edits.add(Triple(old.start, old.end, pane))
        else if (view.selfClosing) edits.add(Triple(view.start, view.end, XmlX.startTag(xml, view, emptyMap(), false) + pane + "</" + view.name + ">"))
        else edits.add(Triple(view.openEnd, view.openEnd, pane))
        if (old != null) for (e in sel) edits.add(Triple(e.start, e.end, ""))
    }

    /** Stale calculation-chain entries make some applications report the file as damaged; the part is optional and is rebuilt by them. */
    private fun dropCalcChain() {
        val rel = Rels.of(pkg, wbPart).firstOrNull { it.type.endsWith("/calcChain") } ?: return
        val part = OoxmlPackage.resolve(wbPart, rel.target)
        val relsName = OoxmlPackage.relsOf(wbPart)
        val xml = pkg.text(relsName) ?: return
        val root = Xml.root(xml) ?: return
        val hit = Xml.children(xml, root).firstOrNull { Xml.attr(xml, it, "Id") == rel.id } ?: return
        pkg.putText(relsName, xml.substring(0, hit.start) + xml.substring(hit.end))
        if (pkg.has(part)) { pkg.remove(part); ContentTypes.removeOverride(pkg, part) }
    }

    /**
     * The file as bytes. Untouched: every part is byte-identical to what was opened. Edited: only the sheet parts that
     * hold a changed cell are rewritten, and inside them only the changed cells (see the class comment); `styles.xml`
     * only when a cell style was added.
     */
    fun save(): ByteArray {
        if (!dirty) return pkg.write()
        commitToPackage()
        val bytes = pkg.write()
        load()
        dirty = false
        return bytes
    }

    /** Writes every pending cell / style edit into the package parts (what [save] does before zipping). */
    private fun commitToPackage() {
        evaluateAll()
        for (s in sheets) sheetXmlForSave(s)?.let { pkg.putText(s.part, it) }
        val st = styles
        val sp = stylesPart
        if (st != null && sp != null && st.changed) pkg.putText(sp, st.xml)
        if (formulaLayoutChanged) dropCalcChain()
    }

    /** Puts pending edits into the parts and re-reads them, so whole-sheet operations work on the parts' text. Stays dirty. */
    private fun flush() {
        if (dirty) { commitToPackage(); load() }
    }

    companion object {
        private const val DONE = 0
        private const val TODO = 1
        private const val BUSY = 2
        private const val MAX_DEPTH = 64

        private const val MAIN = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
        private const val REL = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
        private const val REL_PKG = "http://schemas.openxmlformats.org/package/2006/relationships"
        private const val CT_STYLES = "application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"
        private const val CT_SHEET = "application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"

        /** A width / height for the file: at most two decimals, no trailing zeros ("23", "14.5"). */
        private fun fmtNum(f: Float): String = BigDecimal(f.toString()).setScale(2, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
        private const val DECL = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
        private const val APP_NAME = "Spatial Office"

        private val EMPTY_CELL = Cell("", "", CellKind.EMPTY, null, false, false, NumFmt.GENERAL)
        private val TEXT_ORDER: java.text.Collator = java.text.Collator.getInstance(java.util.Locale.CHINA).apply { strength = java.text.Collator.SECONDARY }
        private val AFTER_VIEWS = setOf("sheetFormatPr", "cols", "sheetData")
        private val AFTER_MERGE = setOf(
            "phoneticPr", "conditionalFormatting", "dataValidations", "hyperlinks", "printOptions", "pageMargins", "pageSetup",
            "headerFooter", "rowBreaks", "colBreaks", "customProperties", "cellWatches", "ignoredErrors", "smartTags", "drawing",
            "legacyDrawing", "legacyDrawingHF", "drawingHF", "picture", "oleObjects", "controls", "webPublishItems", "tableParts", "extLst",
        )
        private val TRAILING_NUM = Regex("(.*?)([0-9]+)([^0-9]*)", RegexOption.DOT_MATCHES_ALL)
        private val AFTER_DRAWING = setOf("legacyDrawing", "legacyDrawingHF", "drawingHF", "picture", "oleObjects", "controls", "webPublishItems", "tableParts", "extLst")
        private val NUMBER_INPUT = Regex("([+-]?)([\\$¥€£￥₩₹]?)([+-]?)((?:[0-9]{1,3}(?:,[0-9]{3})+|[0-9]+)(?:\\.[0-9]*)?|\\.[0-9]+)([eE][+-]?[0-9]+)?\\s*(%?)")
        private val DATE_INPUT = Regex("([0-9]{4})[-/]([0-9]{1,2})[-/]([0-9]{1,2})(?:[ T]([0-9]{1,2}):([0-9]{2})(?::([0-9]{2}))?)?")
        private val TIME_INPUT = Regex("([0-9]{1,2}):([0-9]{2})(?::([0-9]{2}))?")
        private val ESCAPE = Regex("_x([0-9A-Fa-f]{4})_")

        fun open(bytes: ByteArray): XlsxWorkbook = XlsxWorkbook(OoxmlPackage.read(bytes))

        /** A new workbook with one empty sheet. */
        fun blank(): XlsxWorkbook {
            val p = OoxmlPackage.empty()
            p.putText(
                OoxmlPackage.CONTENT_TYPES,
                DECL + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
                    "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>" +
                    "<Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
                    "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>" +
                    "<Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>" +
                    "<Override PartName=\"/xl/styles.xml\" ContentType=\"$CT_STYLES\"/>" +
                    "<Override PartName=\"/docProps/core.xml\" ContentType=\"application/vnd.openxmlformats-package.core-properties+xml\"/>" +
                    "<Override PartName=\"/docProps/app.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.extended-properties+xml\"/>" +
                    "</Types>",
            )
            p.putText(
                "_rels/.rels",
                DECL + "<Relationships xmlns=\"$REL_PKG\">" +
                    "<Relationship Id=\"rId1\" Type=\"$REL/officeDocument\" Target=\"xl/workbook.xml\"/>" +
                    "<Relationship Id=\"rId2\" Type=\"$REL_PKG/metadata/core-properties\" Target=\"docProps/core.xml\"/>" +
                    "<Relationship Id=\"rId3\" Type=\"$REL/extended-properties\" Target=\"docProps/app.xml\"/>" +
                    "</Relationships>",
            )
            p.putText(
                "docProps/app.xml",
                DECL + "<Properties xmlns=\"http://schemas.openxmlformats.org/officeDocument/2006/extended-properties\">" +
                    "<Application>$APP_NAME</Application></Properties>",
            )
            val now = isoNow()
            p.putText(
                "docProps/core.xml",
                DECL + "<cp:coreProperties xmlns:cp=\"http://schemas.openxmlformats.org/package/2006/metadata/core-properties\" " +
                    "xmlns:dc=\"http://purl.org/dc/elements/1.1/\" xmlns:dcterms=\"http://purl.org/dc/terms/\" " +
                    "xmlns:dcmitype=\"http://purl.org/dc/dcmitype/\" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\">" +
                    "<dcterms:created xsi:type=\"dcterms:W3CDTF\">$now</dcterms:created>" +
                    "<dcterms:modified xsi:type=\"dcterms:W3CDTF\">$now</dcterms:modified></cp:coreProperties>",
            )
            p.putText(
                "xl/workbook.xml",
                DECL + "<workbook xmlns=\"$MAIN\" xmlns:r=\"$REL\"><sheets><sheet name=\"Sheet1\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>",
            )
            p.putText(
                "xl/_rels/workbook.xml.rels",
                DECL + "<Relationships xmlns=\"$REL_PKG\">" +
                    "<Relationship Id=\"rId1\" Type=\"$REL/worksheet\" Target=\"worksheets/sheet1.xml\"/>" +
                    "<Relationship Id=\"rId2\" Type=\"$REL/styles\" Target=\"styles.xml\"/>" +
                    "</Relationships>",
            )
            p.putText("xl/worksheets/sheet1.xml", DECL + "<worksheet xmlns=\"$MAIN\" xmlns:r=\"$REL\"><sheetData/></worksheet>")
            p.putText("xl/styles.xml", Styles.DEFAULT)
            return XlsxWorkbook(OoxmlPackage.read(p.write()))
        }

        private fun isoNow(): String {
            val secs = Math.floorDiv(System.currentTimeMillis(), 1000L)
            val c = Num.civil(Math.floorDiv(secs, 86400L))
            val t = Math.floorMod(secs, 86400L)
            fun two(n: Long) = if (n < 10) "0$n" else n.toString()
            return c[0].toString().padStart(4, '0') + "-" + two(c[1].toLong()) + "-" + two(c[2].toLong()) +
                "T" + two(t / 3600) + ":" + two(t / 60 % 60) + ":" + two(t % 60) + "Z"
        }

        /** Relationship target for [part] relative to the folder of [owner]. */
        private fun relTarget(owner: String, part: String): String {
            val from = owner.split('/').dropLast(1)
            val to = part.split('/')
            var k = 0
            while (k < from.size && k < to.size - 1 && from[k] == to[k]) k++
            return "../".repeat(from.size - k) + to.subList(k, to.size).joinToString("/")
        }

        private fun decodeEscapes(s: String): String =
            if (s.indexOf("_x") < 0) s else ESCAPE.replace(s) { m -> m.groupValues[1].toInt(16).toChar().toString() }

        private fun needsPreserve(t: String): Boolean =
            t.isNotEmpty() && (t[0].isWhitespace() || t[t.length - 1].isWhitespace() || t.indexOf('\n') >= 0 || t.indexOf('\t') >= 0 || t.indexOf("  ") >= 0)

        /** Escapes text for a `<t>` / `<v>` element, including the `_xHHHH_` form for characters XML cannot carry. */
        private fun textXml(v: String): String {
            val sb = StringBuilder(v.length + 16)
            var i = 0
            while (i < v.length) {
                val ch = v[i]
                when {
                    ch == '_' && ESCAPE.matchAt(v, i) != null -> sb.append("_x005F_")
                    ch == '&' -> sb.append("&amp;")
                    ch == '<' -> sb.append("&lt;")
                    ch == '>' -> sb.append("&gt;")
                    ch == '\r' -> sb.append("_x000D_")
                    ch < ' ' && ch != '\n' && ch != '\t' -> sb.append("_x").append(String.format(Locale.ROOT, "%04X", ch.code)).append('_')
                    ch == '￾' || ch == '￿' -> {}
                    else -> sb.append(ch)
                }
                i++
            }
            return sb.toString()
        }
    }
}
