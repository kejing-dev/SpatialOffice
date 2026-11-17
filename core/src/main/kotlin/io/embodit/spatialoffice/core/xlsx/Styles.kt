package io.embodit.spatialoffice.core.xlsx

import io.embodit.spatialoffice.core.pkg.Xml

/** Small additions to the shared [Xml] scanner: prefix-agnostic lookups and start-tag rewriting. */
internal object XmlX {
    class AttrSpan(val name: String, val start: Int, val valStart: Int, val valEnd: Int, val end: Int)

    /** `x:row` → `x:`, `row` → empty. */
    fun prefixOf(name: String): String = if (name.indexOf(':') >= 0) name.substringBefore(':') + ":" else ""

    fun kid(xml: String, parent: Xml.Elem, local: String): Xml.Elem? = Xml.children(xml, parent).firstOrNull { Xml.local(it.name) == local }

    fun kids(xml: String, parent: Xml.Elem, local: String): List<Xml.Elem> = Xml.children(xml, parent).filter { Xml.local(it.name) == local }

    /** Follows a path of local names from [from]; null when a step is missing. */
    fun path(xml: String, from: Xml.Elem, vararg locals: String): Xml.Elem? {
        var e = from
        for (l in locals) e = kid(xml, e, l) ?: return null
        return e
    }

    /** All descendants with the given local name, document order, including nested matches. */
    fun findAllLocal(xml: String, within: Xml.Elem, local: String): List<Xml.Elem> {
        val out = ArrayList<Xml.Elem>()
        fun walk(e: Xml.Elem) {
            for (c in Xml.children(xml, e)) {
                if (Xml.local(c.name) == local) out.add(c)
                walk(c)
            }
        }
        walk(within)
        return out
    }

    /** Value of the first attribute whose local name is [local] and that carries a prefix (e.g. `r:id`). */
    fun prefixedAttr(xml: String, e: Xml.Elem, local: String): String? =
        Xml.attrs(xml, e).entries.firstOrNull { it.key.indexOf(':') >= 0 && it.key.substringAfter(':') == local }?.value

    /** Character spans of the attributes of [e]'s start tag. */
    fun attrSpans(xml: String, e: Xml.Elem): List<AttrSpan> {
        val out = ArrayList<AttrSpan>()
        var i = e.start + 1 + e.name.length
        val stop = e.openEnd - 1
        while (i < stop) {
            while (i < stop && (xml[i].isWhitespace() || xml[i] == '/')) i++
            if (i >= stop) break
            val eq = xml.indexOf('=', i)
            if (eq < 0 || eq >= stop) break
            val key = xml.substring(i, eq).trim()
            var q = eq + 1
            while (q < stop && xml[q].isWhitespace()) q++
            if (q >= stop) break
            val quote = xml[q]
            if (quote != '"' && quote != '\'') break
            val close = xml.indexOf(quote, q + 1)
            if (close < 0) break
            out.add(AttrSpan(key, i, q + 1, close, close + 1))
            i = close + 1
        }
        return out
    }

    /** Span (start, endExclusive) of the raw value of attribute [name], or null. */
    fun attrValueSpan(xml: String, e: Xml.Elem, name: String): Pair<Int, Int>? =
        attrSpans(xml, e).firstOrNull { it.name == name }?.let { Pair(it.valStart, it.valEnd) }

    /**
     * The start tag of [e] with some attributes set (value) or removed (null). Attributes not named in [set] keep their
     * original text and order; new ones are appended.
     */
    fun startTag(xml: String, e: Xml.Elem, set: Map<String, String?>, selfClose: Boolean): String {
        val sb = StringBuilder("<").append(e.name)
        val seen = HashSet<String>()
        for (a in attrSpans(xml, e)) {
            seen.add(a.name)
            if (set.containsKey(a.name)) {
                val v = set[a.name] ?: continue
                sb.append(' ').append(a.name).append("=\"").append(Xml.escape(v)).append('"')
            } else sb.append(' ').append(xml, a.start, a.end)
        }
        for ((k, v) in set) if (k !in seen && v != null) sb.append(' ').append(k).append("=\"").append(Xml.escape(v)).append('"')
        sb.append(if (selfClose) "/>" else ">")
        return sb.toString()
    }
}

/**
 * The parts of `styles.xml` the grid needs (number format and bold per cell style), plus appending new cell styles.
 * Nothing existing is rewritten: a bold or number-format change appends one `<xf>` (and a `<font>` / `<numFmt>` when
 * no existing one fits) and bumps the matching `count` attribute.
 */
internal class Styles(xml0: String) {
    var xml: String = xml0
        private set
    var changed: Boolean = false
        private set

    private class Xf(val numFmtId: Int, val fontId: Int, val fillId: Int, val raw: String)

    private val xfs = ArrayList<Xf>()
    private val fonts = ArrayList<String>()
    private val fontBold = ArrayList<Boolean>()
    private val fills = ArrayList<String>()
    private val fillRgb = ArrayList<Int?>()
    private val customFmts = LinkedHashMap<Int, String>()
    private var pfx = ""

    init { parse() }

    private fun parse() {
        xfs.clear(); fonts.clear(); fontBold.clear(); customFmts.clear(); fills.clear(); fillRgb.clear()
        val root = Xml.root(xml) ?: return
        pfx = XmlX.prefixOf(root.name)
        for (k in Xml.children(xml, root)) when (Xml.local(k.name)) {
            "numFmts" -> for (f in Xml.children(xml, k)) {
                val a = Xml.attrs(xml, f)
                val id = a["numFmtId"]?.toIntOrNull() ?: continue
                customFmts[id] = a["formatCode"] ?: "General"
            }
            "fonts" -> for (f in Xml.children(xml, k)) {
                val raw = xml.substring(f.start, f.end)
                fonts.add(raw)
                fontBold.add(isBold(raw))
            }
            "fills" -> for (f in Xml.children(xml, k)) {
                val raw = xml.substring(f.start, f.end)
                fills.add(raw)
                fillRgb.add(solidRgb(raw))
            }
            "cellXfs" -> for (x in Xml.children(xml, k)) {
                val a = Xml.attrs(xml, x)
                xfs.add(Xf(a["numFmtId"]?.toIntOrNull() ?: 0, a["fontId"]?.toIntOrNull() ?: 0, a["fillId"]?.toIntOrNull() ?: 0, xml.substring(x.start, x.end)))
            }
        }
    }

    private fun isBold(fontRaw: String): Boolean {
        val f = Xml.root(fontRaw) ?: return false
        val b = XmlX.kid(fontRaw, f, "b") ?: return false
        val v = Xml.attr(fontRaw, b, "val")
        return v == null || !(v == "0" || v == "false")
    }

    /** 0xRRGGBB of a solid pattern fill given as an RGB colour; null for no fill, patterns and theme colours. */
    private fun solidRgb(fillRaw: String): Int? {
        val f = Xml.root(fillRaw) ?: return null
        val pf = XmlX.kid(fillRaw, f, "patternFill") ?: return null
        if (Xml.attr(fillRaw, pf, "patternType") != "solid") return null
        val fg = XmlX.kid(fillRaw, pf, "fgColor") ?: return null
        val rgb = Xml.attr(fillRaw, fg, "rgb") ?: return null
        return rgb.takeLast(6).toIntOrNull(16)
    }

    val styleCount: Int get() = xfs.size

    fun fill(style: Int): Int? = xfs.getOrNull(style)?.let { fillRgb.getOrNull(it.fillId) }

    /** Style index equal to [style] but with a solid fill of [rgb] (null = no fill). */
    fun withFill(style: Int, rgb: Int?): Int {
        if (fill(style) == rgb) return style
        val xf = baseXf(style)
        val fillId = if (rgb == null) 0 else {
            val hex = String.format("FF%06X", rgb and 0xFFFFFF)
            val raw = "<${pfx}fill><${pfx}patternFill patternType=\"solid\"><${pfx}fgColor rgb=\"$hex\"/><${pfx}bgColor indexed=\"64\"/></${pfx}patternFill></${pfx}fill>"
            var id = fills.indexOf(raw)
            if (id < 0) {
                val root = Xml.root(xml)!!
                if (XmlX.kid(xml, root, "fills") == null) throw UnsupportedOperationException("styles part has no fills")
                append("fills", raw)
                id = fills.size - 1
            }
            id
        }
        return xfWith(xf, mapOf("fillId" to fillId.toString(), "applyFill" to "1"), "applyFill")
    }

    fun bold(style: Int): Boolean = xfs.getOrNull(style)?.let { fontBold.getOrNull(it.fontId) } ?: false

    fun format(style: Int): NumFormat {
        val id = xfs.getOrNull(style)?.numFmtId ?: return NumFormat.GENERAL
        if (id == 0) return NumFormat.GENERAL
        return NumFormat.of(customFmts[id] ?: NumFormat.builtin(id) ?: "General")
    }

    private fun baseXf(style: Int): Xf =
        xfs.getOrNull(style) ?: xfs.firstOrNull() ?: throw UnsupportedOperationException("styles part has no cell formats")

    /** Style index equal to [style] but with the font bold or not; appended when no such style exists yet. */
    fun withBold(style: Int, bold: Boolean): Int {
        if (bold(style) == bold) return style
        val xf = baseXf(style)
        val fontRaw = fonts.getOrNull(xf.fontId) ?: throw UnsupportedOperationException("styles part has no fonts")
        val f = Xml.root(fontRaw)!!
        val wanted: String = if (bold) {
            if (f.selfClosing) "<${f.name}><${pfx}b/></${f.name}>"
            else {
                // an explicit <b val="0"/> has to go before a plain <b/> is added
                val old = XmlX.kid(fontRaw, f, "b")
                val cleaned = if (old != null) fontRaw.substring(0, old.start) + fontRaw.substring(old.end) else fontRaw
                val g = Xml.root(cleaned)!!
                cleaned.substring(0, g.openEnd) + "<${pfx}b/>" + cleaned.substring(g.openEnd)
            }
        } else {
            val old = XmlX.kid(fontRaw, f, "b")!!
            fontRaw.substring(0, old.start) + fontRaw.substring(old.end)
        }
        var fontId = fonts.indexOf(wanted)
        if (fontId < 0) {
            append("fonts", wanted)
            fontId = fonts.size - 1
        }
        return xfWith(xf, mapOf("fontId" to fontId.toString(), "applyFont" to "1"), "applyFont")
    }

    /** Style index equal to [style] but with number format [code]. */
    fun withNumFmt(style: Int, code: String): Int {
        val xf = baseXf(style)
        val inRange = style in xfs.indices
        var id = BUILTIN_BY_CODE[code] ?: customFmts.entries.firstOrNull { it.value == code }?.key ?: -1
        if (id < 0) {
            id = maxOf(163, customFmts.keys.maxOrNull() ?: 0) + 1
            val tag = "<${pfx}numFmt numFmtId=\"$id\" formatCode=\"${Xml.escape(code)}\"/>"
            val root = Xml.root(xml)!!
            if (XmlX.kid(xml, root, "numFmts") == null) {
                xml = xml.substring(0, root.openEnd) + "<${pfx}numFmts count=\"1\">$tag</${pfx}numFmts>" + xml.substring(root.openEnd)
                changed = true
                parse()
            } else append("numFmts", tag)
        }
        if (xf.numFmtId == id && inRange) return style
        return xfWith(xf, mapOf("numFmtId" to id.toString(), "applyNumberFormat" to "1"), "applyNumberFormat")
    }

    /** What a cell format says, apart from one `apply…` flag: an existing format that differs only in that flag is reused. */
    private fun xfKey(raw: String, ignore: String): String {
        val e = Xml.root(raw) ?: return raw
        val attrs = Xml.attrs(raw, e).filterKeys { it != ignore }.toSortedMap()
        return attrs.toString() + "|" + (if (e.selfClosing) "" else raw.substring(e.openEnd, e.closeStart))
    }

    private fun xfWith(base: Xf, set: Map<String, String>, applyFlag: String): Int {
        val e = Xml.root(base.raw)!!
        val raw = XmlX.startTag(base.raw, e, set, e.selfClosing) + base.raw.substring(e.openEnd)
        val key = xfKey(raw, applyFlag)
        val hit = xfs.indexOfFirst { xfKey(it.raw, applyFlag) == key }
        if (hit >= 0) return hit
        append("cellXfs", raw)
        return xfs.size - 1
    }

    /** Appends [childXml] to the container with local name [container] and updates its `count`. */
    private fun append(container: String, childXml: String) {
        val root = Xml.root(xml)!!
        val el = XmlX.kid(xml, root, container) ?: throw UnsupportedOperationException("styles part has no $container")
        val count = Xml.children(xml, el).size + 1
        val inner = if (el.selfClosing) "" else xml.substring(el.openEnd, el.closeStart)
        val rebuilt = XmlX.startTag(xml, el, mapOf("count" to count.toString()), false) + inner + childXml + "</" + el.name + ">"
        xml = xml.substring(0, el.start) + rebuilt + xml.substring(el.end)
        changed = true
        parse()
    }

    companion object {
        private val BUILTIN_BY_CODE = mapOf("General" to 0, "0" to 1, "0.00" to 2, "#,##0" to 3, "#,##0.00" to 4, "0%" to 9, "0.00%" to 10)

        /** A minimal standard styles part: one font, the two required fills, one border, one cell format. */
        const val DEFAULT = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n" +
            "<styleSheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">" +
            "<fonts count=\"1\"><font><sz val=\"11\"/></font></fonts>" +
            "<fills count=\"2\"><fill><patternFill patternType=\"none\"/></fill><fill><patternFill patternType=\"gray125\"/></fill></fills>" +
            "<borders count=\"1\"><border><left/><right/><top/><bottom/><diagonal/></border></borders>" +
            "<cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs>" +
            "<cellXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/></cellXfs>" +
            "<cellStyles count=\"1\"><cellStyle name=\"Normal\" xfId=\"0\" builtinId=\"0\"/></cellStyles>" +
            "</styleSheet>"
    }
}
