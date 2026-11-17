package io.embodit.spatialoffice.core.docx

import io.embodit.spatialoffice.core.pkg.ContentTypes
import io.embodit.spatialoffice.core.pkg.OoxmlPackage
import io.embodit.spatialoffice.core.pkg.Rels
import io.embodit.spatialoffice.core.pkg.Xml

/**
 * A word-processing document opened for patch-only editing.
 *
 * The main document part is held as text. Every body-level element is one block with a character range into that
 * text; an edit replaces exactly that range (or inserts / removes one), so everything else in the part — and every
 * other part of the package — stays byte-for-byte what the file had.
 */
class DocxDocument private constructor(private val pkg: OoxmlPackage, private var source: ByteArray?) {

    private class Entry(
        val id: Int,
        var start: Int,
        var end: Int,
        var block: DocBlock,
        /** `<w:rPr>` text of each original run ("" = none), as opened; what [Run.src] points into. */
        val srcRPr: List<String>,
        var isNew: Boolean,
    )

    private val docPart: String =
        Rels.of(pkg, "").firstOrNull { !it.external && it.type.endsWith("/officeDocument") }
            ?.let { OoxmlPackage.resolve("", it.target) }?.takeIf { pkg.has(it) } ?: "word/document.xml"

    private var xml: String = pkg.text(docPart) ?: throw IllegalArgumentException("not a document: no $docPart")
    private var xmlChanged = false
    private var auxChanged = false
    private val bodyStart: Int
    private val entries = ArrayList<Entry>()
    private var nextId = 0
    private var stylesCache: DocxStyles? = null
    private var numberingCache: DocxNumbering? = null
    private var blocksCache: List<DocBlock>? = null
    private var compatCache: List<CompatNote>? = null
    private var ownBulletNumId: Int? = null
    private val changedIds = HashSet<Int>()
    private var deletedCount = 0

    init {
        val root = Xml.root(xml)
        val body = root?.let { Xml.child(xml, it, "w:body") }
        bodyStart = if (body != null && !body.selfClosing) body.openEnd else -1
        if (body != null) {
            for (c in Xml.children(xml, body)) {
                when {
                    c.name == "w:p" -> entries.add(parseParagraph(c))
                    c.name == "w:tbl" -> entries.add(Entry(nextId, c.start, c.end, parseTable(nextId, c), emptyList(), false).also { nextId++ })
                    c.name == "w:sectPr" || isRangeMarker(c.name) -> {}
                    else -> entries.add(Entry(nextId, c.start, c.end, OpaqueBlock(nextId, "other"), emptyList(), false).also { nextId++ })
                }
            }
        }
    }

    // ------------------------------------------------------------------------------------------------ public API

    /** Blocks in body order; ids are stable while this object lives. */
    val blocks: List<DocBlock>
        get() = blocksCache ?: entries.map { it.block }.also { blocksCache = it }

    /** Current bytes of a package part (e.g. an [ImageBlock.part]); null when the part does not exist. */
    fun partBytes(part: String): ByteArray? {
        val n = part.trimStart('/')
        if (n == docPart && xmlChanged) return xml.toByteArray(Charsets.UTF_8)
        return pkg.bytes(n)
    }

    val dirty: Boolean get() = xmlChanged || auxChanged

    /** Blocks edited, inserted or deleted since the document was opened or last saved. */
    val changedBlockCount: Int get() = changedIds.size + deletedCount

    /** First non-empty paragraph text, trimmed; "" when there is none. */
    fun firstLine(): String {
        for (e in entries) {
            val p = e.block as? Paragraph ?: continue
            val t = p.text.trim()
            if (t.isNotEmpty()) return t
        }
        return ""
    }

    /** Paragraphs one per line, table rows as tab-separated lines; pictures and opaque blocks are skipped. */
    fun plainText(): String {
        val lines = ArrayList<String>()
        for (e in entries) {
            when (val b = e.block) {
                is Paragraph -> lines.add(b.text)
                is TableBlock -> b.rows.forEach { lines.add(it.joinToString("\t")) }
                else -> {}
            }
        }
        return lines.joinToString("\n")
    }

    /**
     * Replaces the text and style of an editable paragraph. The paragraph's start tag and bookmarks are kept, its
     * `w:pPr` is kept unless [style] differs from the current one, and each run keeps the `w:rPr` of the original
     * run its [Run.src] names, with only bold / italic / underline switched.
     * @throws IllegalStateException when the block is not an editable paragraph.
     */
    fun setParagraph(id: Int, runs: List<Run>, style: ParaStyle, align: Int? = null) {
        val idx = indexOf(id)
        val e = entries[idx]
        val old = e.block as? Paragraph ?: throw IllegalStateException("block $id is not a paragraph")
        if (!old.editable) throw IllegalStateException("paragraph $id is read-only (${old.readOnlyWhy})")
        val p = Xml.elemAt(xml, e.start) ?: throw IllegalStateException("paragraph $id is not readable")
        val kids = Xml.children(xml, p)
        val oldPPr = kids.firstOrNull { it.name == "w:pPr" }?.let { XmlEx.src(xml, it) } ?: ""
        var newPPr = if (style == old.style) oldPPr else restylePPr(oldPPr, style, idx - 1, idx + 1)
        val newAlign = align ?: old.align
        if (newAlign != alignOf(newPPr)) newPPr = withAlign(newPPr, newAlign)

        val firstRun = kids.indexOfFirst { it.name == "w:r" }
        val lead = StringBuilder()
        val trail = StringBuilder()
        kids.forEachIndexed { i, k ->
            if (k.name == "w:bookmarkStart" || k.name == "w:bookmarkEnd") {
                val before = if (firstRun >= 0) i < firstRun else k.name == "w:bookmarkStart"
                (if (before) lead else trail).append(xml, k.start, k.end)
            }
        }
        val clean = normRuns(runs)
        val newXml = XmlEx.openTag(xml, p) + newPPr + lead + runsXml(clean, e.srcRPr) + trail + "</w:p>"
        replaceEntry(idx, newXml)
        e.block = Paragraph(id, style, clean, true, null, newAlign)
        changedIds.add(id)
    }

    /** Inserts a picture (its own paragraph) after block [id]; [ext] is "png" / "jpeg" / "jpg" / "gif". Returns its id. */
    fun insertImageAfter(id: Int, bytes: ByteArray, ext: String, widthEmu: Long, heightEmu: Long): Int {
        if (bodyStart < 0) throw IllegalStateException("document has no body")
        val e = ext.lowercase().let { if (it == "jpg") "jpeg" else it }
        var n = 1
        while (pkg.has("word/media/image$n.$e") || pkg.has("word/media/image$n.${if (e == "jpeg") "jpg" else e}")) n++
        val part = "word/media/image$n.$e"
        pkg.put(part, bytes)
        ContentTypes.addDefault(pkg, e, "image/$e")
        val rid = Rels.add(pkg, docPart, DocxTemplates.REL + "/image", XmlEx.relativize(docPart, part))
        auxChanged = true
        val docPrId = (Regex("<wp:docPr\\b[^>]*\\bid=\"(\\d+)\"").findAll(xml).mapNotNull { it.groupValues[1].toIntOrNull() }.maxOrNull() ?: 0) + 1
        val newXml = "<w:p>" + DocxTemplates.inlinePicture(rid, docPrId, "Picture $docPrId", widthEmu, heightEmu) + "</w:p>"
        return insertRaw(id, newXml) { nid -> ImageBlock(nid, part, widthEmu, heightEmu) }
    }

    /** Inserts an empty table of [rows] × [cols] after block [id] and returns its id. */
    fun insertTableAfter(id: Int, rows: Int, cols: Int): Int {
        require(rows in 1..200 && cols in 1..30) { "table size $rows × $cols" }
        val newXml = DocxTemplates.table(rows, cols, 9000)
        val nid = insertRaw(id, newXml) { nid -> TableBlock(nid, List(rows) { List(cols) { "" } }) }
        // Word needs a paragraph between a table and whatever follows it only at the end of the body; add one when the table is last
        val idx = indexOf(nid)
        if (idx == entries.lastIndex) insertParagraphAfter(nid, emptyList(), ParaStyle.BODY)
        return nid
    }

    /**
     * Sets the text of one table cell ([col] counts grid columns, as [TableBlock.rows] does). The cell keeps its
     * properties and the first paragraph's properties and run formatting; its paragraphs become one per line of [text].
     */
    fun setTableCell(id: Int, row: Int, col: Int, text: String) {
        val idx = indexOf(id)
        val e = entries[idx]
        if (e.block !is TableBlock) throw IllegalStateException("block $id is not a table")
        val tbl = Xml.elemAt(xml, e.start) ?: throw IllegalStateException("table $id is not readable")
        val tr = Xml.children(xml, tbl, "w:tr").getOrNull(row) ?: throw IndexOutOfBoundsException("row $row")
        var grid = 0
        var tc: Xml.Elem? = null
        for (c in Xml.children(xml, tr, "w:tc")) {
            val span = Xml.child(xml, c, "w:tcPr")?.let { Xml.child(xml, it, "w:gridSpan") }?.let { Xml.attr(xml, it, "w:val")?.toIntOrNull() } ?: 1
            if (col >= grid && col < grid + span) { tc = c; break }
            grid += span
        }
        val cell = tc ?: throw IndexOutOfBoundsException("col $col")
        val kids = Xml.children(xml, cell)
        val tcPr = kids.firstOrNull { it.name == "w:tcPr" }?.let { XmlEx.src(xml, it) } ?: ""
        val firstP = kids.firstOrNull { it.name == "w:p" }
        val pPr = firstP?.let { Xml.child(xml, it, "w:pPr") }?.let { XmlEx.src(xml, it) } ?: ""
        val rPr = firstP?.let { p -> Xml.children(xml, p, "w:r").firstOrNull()?.let { Xml.child(xml, it, "w:rPr") } }?.let { XmlEx.src(xml, it) } ?: ""
        if (kids.any { it.name != "w:tcPr" && it.name != "w:p" }) throw IllegalStateException("cell holds more than text")
        val paras = text.replace("\r\n", "\n").split('\n').joinToString("") { line ->
            "<w:p>$pPr" + (if (line.isEmpty()) "" else runsXml(normRuns(listOf(Run(line))), listOf(rPr))) + "</w:p>"
        }
        val newCell = XmlEx.openTag(xml, cell) + tcPr + paras + "</w:tc>"
        val delta = newCell.length - (cell.end - cell.start)
        xml = xml.substring(0, cell.start) + newCell + xml.substring(cell.end)
        e.end += delta
        for (k in idx + 1 until entries.size) { entries[k].start += delta; entries[k].end += delta }
        touched()
        e.block = parseTable(id, Xml.elemAt(xml, e.start)!!)
        changedIds.add(id)
    }

    private fun insertRaw(id: Int, newXml: String, block: (Int) -> DocBlock): Int {
        val anchorIdx = if (id == -1) -1 else indexOf(id)
        val pos = if (anchorIdx < 0) bodyStart else entries[anchorIdx].end
        xml = xml.substring(0, pos) + newXml + xml.substring(pos)
        for (k in anchorIdx + 1 until entries.size) { entries[k].start += newXml.length; entries[k].end += newXml.length }
        val newId = nextId++
        entries.add(anchorIdx + 1, Entry(newId, pos, pos + newXml.length, block(newId), emptyList(), true))
        changedIds.add(newId)
        touched()
        return newId
    }

    private fun alignOf(pPr: String): Int {
        if (pPr.isEmpty()) return 0
        val e = XmlEx.childOf(pPr, "w:jc") ?: return 0
        return when (Xml.attr(pPr, e, "w:val")) { "center" -> 1; "right", "end" -> 2; "both", "distribute" -> 3; else -> 0 }
    }

    private fun withAlign(pPr: String, align: Int): String {
        var out = if (pPr.isEmpty()) "<w:pPr></w:pPr>" else XmlEx.removeChildren(pPr, setOf("w:jc"))
        val v = when (align) { 1 -> "center"; 2 -> "right"; 3 -> "both"; else -> null }
        if (v != null) out = XmlEx.insertChild(out, "<w:jc w:val=\"$v\"/>", "w:jc", PPR_ORDER)
        return if (XmlEx.isEmpty(out)) "" else out
    }

    /**
     * Inserts a new paragraph after block [id] (-1 = at the start of the body) and returns its id.
     * When the block before it is an editable paragraph of the same [style], the new paragraph continues it: it
     * copies that paragraph's `w:pPr` (so a list goes on) and the formatting of its last run.
     */
    fun insertParagraphAfter(id: Int, runs: List<Run>, style: ParaStyle): Int {
        if (bodyStart < 0) throw IllegalStateException("document has no body")
        val anchorIdx = if (id == -1) -1 else indexOf(id)
        val pos = if (anchorIdx < 0) bodyStart else entries[anchorIdx].end
        val anchor = entries.getOrNull(anchorIdx)
        val anchorPara = anchor?.block as? Paragraph
        var pPr = ""
        var srcRPr: List<String> = emptyList()
        if (anchor != null && anchorPara != null && anchorPara.editable && anchorPara.style == style) {
            val p = Xml.elemAt(xml, anchor.start)
            if (p != null) {
                val kids = Xml.children(xml, p)
                pPr = kids.firstOrNull { it.name == "w:pPr" }?.let { XmlEx.src(xml, it) } ?: ""
                kids.lastOrNull { it.name == "w:r" }?.let { r -> Xml.child(xml, r, "w:rPr") }?.let { srcRPr = listOf(XmlEx.src(xml, it)) }
            }
        } else if (style != ParaStyle.BODY) {
            pPr = restylePPr("", style, anchorIdx, anchorIdx + 1)
        }
        val clean = normRuns(runs)
        val newXml = "<w:p>" + pPr + runsXml(clean, srcRPr) + "</w:p>"
        xml = xml.substring(0, pos) + newXml + xml.substring(pos)
        for (k in anchorIdx + 1 until entries.size) { entries[k].start += newXml.length; entries[k].end += newXml.length }
        val newId = nextId++
        entries.add(anchorIdx + 1, Entry(newId, pos, pos + newXml.length, Paragraph(newId, style, clean, true, null), srcRPr, true))
        changedIds.add(newId)
        touched()
        return newId
    }

    /**
     * Removes an editable paragraph, a table or a picture.
     * @throws IllegalStateException for read-only paragraphs and opaque blocks, which this model cannot safely remove.
     */
    fun deleteBlock(id: Int) {
        val idx = indexOf(id)
        val e = entries[idx]
        when (val b = e.block) {
            is Paragraph -> if (!b.editable) throw IllegalStateException("paragraph $id is read-only (${b.readOnlyWhy})")
            is OpaqueBlock -> throw IllegalStateException("block $id (${b.kind}) is read-only")
            else -> {}
        }
        val len = e.end - e.start
        xml = xml.substring(0, e.start) + xml.substring(e.end)
        entries.removeAt(idx)
        for (k in idx until entries.size) { entries[k].start -= len; entries[k].end -= len }
        if (e.isNew) changedIds.remove(id) else { changedIds.remove(id); deletedCount++ }
        touched()
    }

    /** What is shown approximately or kept read-only. Counts are occurrences in the document. */
    fun compat(): List<CompatNote> {
        compatCache?.let { return it }
        val out = ArrayList<CompatNote>()
        fun add(kind: CompatKind, code: String, n: Int) { if (n > 0) out.add(CompatNote(kind, code, n)) }

        add(CompatKind.READ_ONLY, "tracked-changes", TRACKED_MARK.findAll(xml).count())
        val comments = relPart("/comments")?.let { pkg.text(it) }?.let { Regex("<w:comment[\\s>]").findAll(it).count() } ?: 0
        add(CompatKind.READ_ONLY, "comments", if (comments > 0) comments else Regex("<w:commentReference[\\s/>]").findAll(xml).count())

        val opaque = LinkedHashMap<String, Int>()
        var fields = 0
        var complex = 0
        for (e in entries) {
            when (val b = e.block) {
                is OpaqueBlock -> opaque[b.kind] = (opaque[b.kind] ?: 0) + 1
                is Paragraph -> if (!b.editable) { if (b.readOnlyWhy == "field") fields++ else if (b.readOnlyWhy == "complex") complex++ }
                else -> {}
            }
        }
        for (k in listOf("text-box", "smart-graphic", "chart", "section", "other")) add(CompatKind.READ_ONLY, k, opaque[k] ?: 0)
        add(CompatKind.READ_ONLY, "field", fields)
        add(CompatKind.READ_ONLY, "complex", complex)

        add(CompatKind.APPROX, "columns", Regex("<w:cols\\b[^>]*\\bw:num=\"(\\d+)\"").findAll(xml).count { (it.groupValues[1].toIntOrNull() ?: 1) > 1 })
        add(CompatKind.APPROX, "merged-cells", Regex("<w:(?:gridSpan|vMerge)[\\s/>]").findAll(xml).count())
        add(CompatKind.APPROX, "footnotes", Regex("<w:(?:footnoteReference|endnoteReference)[\\s/>]").findAll(xml).count())
        add(CompatKind.APPROX, "header-footer", Rels.of(pkg, docPart).count { it.type.endsWith("/header") || it.type.endsWith("/footer") })

        val fonts = LinkedHashMap<String, Int>()
        for (tag in Regex("<w:rFonts\\b[^>]*>").findAll(xml)) {
            val names = Regex("w:(?:ascii|hAnsi|eastAsia|cs)=\"([^\"]+)\"").findAll(tag.value).map { Xml.unescape(it.groupValues[1]) }.toSet()
            for (n in names) fonts[n] = (fonts[n] ?: 0) + 1
        }
        for ((n, c) in fonts) add(CompatKind.APPROX, "font:$n", c)
        compatCache = out
        return out
    }

    /**
     * The package as bytes. Untouched since open / the last save: exactly the bytes it was opened from. Otherwise
     * only the main document part (and, when an edit needed one, the numbering / styles part) differs.
     */
    fun save(): ByteArray {
        val src = source
        if (!dirty && src != null) return src.copyOf()
        if (xmlChanged) pkg.putText(docPart, xml)
        val out = pkg.write()
        source = out
        xmlChanged = false
        auxChanged = false
        changedIds.clear()
        deletedCount = 0
        entries.forEach { it.isNew = false }
        return out.copyOf()
    }

    // ------------------------------------------------------------------------------------------------ reading

    private fun indexOf(id: Int): Int {
        val i = entries.indexOfFirst { it.id == id }
        if (i < 0) throw IllegalArgumentException("no block with id $id")
        return i
    }

    private fun relPart(typeSuffix: String): String? =
        Rels.of(pkg, docPart).firstOrNull { !it.external && it.type.endsWith(typeSuffix) }
            ?.let { OoxmlPackage.resolve(docPart, it.target) }?.takeIf { pkg.has(it) }

    private fun styles(): DocxStyles = stylesCache ?: DocxStyles(relPart("/styles")?.let { pkg.text(it) }).also { stylesCache = it }

    private fun numbering(): DocxNumbering = numberingCache ?: DocxNumbering(relPart("/numbering")?.let { pkg.text(it) }).also { numberingCache = it }

    private fun isRangeMarker(name: String): Boolean =
        name == "w:proofErr" || name.endsWith("RangeStart") || name.endsWith("RangeEnd") ||
            name == "w:bookmarkStart" || name == "w:bookmarkEnd" || name == "w:permStart" || name == "w:permEnd"

    private fun pStyleOf(pPr: String): String? {
        if (pPr.isEmpty()) return null
        val e = XmlEx.childOf(pPr, "w:pStyle") ?: return null
        return Xml.attr(pPr, e, "w:val")
    }

    private fun styleOf(pPr: String): ParaStyle {
        var pStyle: String? = null
        var outline: Int? = null
        var directNum: Int? = null
        var directLvl: Int? = null
        val root = if (pPr.isEmpty()) null else Xml.root(pPr)
        if (root != null) {
            for (c in Xml.children(pPr, root)) {
                when (c.name) {
                    "w:pStyle" -> pStyle = Xml.attr(pPr, c, "w:val")
                    "w:outlineLvl" -> outline = Xml.attr(pPr, c, "w:val")?.toIntOrNull()
                    "w:numPr" -> {
                        directNum = Xml.child(pPr, c, "w:numId")?.let { Xml.attr(pPr, it, "w:val")?.toIntOrNull() }
                        directLvl = Xml.child(pPr, c, "w:ilvl")?.let { Xml.attr(pPr, it, "w:val")?.toIntOrNull() }
                    }
                }
            }
        }
        val st = styles()
        st.heading(pStyle)?.let { return it }
        outline?.let { if (it in 0..8) return DocxStyles.levelStyle(it) }
        val styleNum = st.num(pStyle)
        val numId = directNum ?: styleNum?.first
        if (numId != null) {
            if (numId == 0) return ParaStyle.BODY
            return when (numbering().isBullet(numId, directLvl ?: styleNum?.second ?: 0)) {
                true -> ParaStyle.BULLET
                false -> ParaStyle.NUMBERED
                null -> st.listHint(pStyle) ?: ParaStyle.NUMBERED
            }
        }
        return st.listHint(pStyle) ?: ParaStyle.BODY
    }

    /** Text of one run: tabs, line breaks and page breaks become `\t`, `\n`, `\u000C`. */
    private fun runText(r: Xml.Elem): String {
        val sb = StringBuilder()
        for (c in Xml.children(xml, r)) {
            when (c.name) {
                "w:t" -> sb.append(Xml.text(xml, c))
                "w:tab" -> sb.append('\t')
                "w:br" -> sb.append(if (Xml.attr(xml, c, "w:type") == "page") '\u000C' else '\n')
                "w:cr" -> sb.append('\n')
                "w:noBreakHyphen" -> sb.append('‑')
                "w:softHyphen" -> sb.append('­')
            }
        }
        return sb.toString()
    }

    private fun rPrOf(r: Xml.Elem): String = Xml.child(xml, r, "w:rPr")?.let { XmlEx.src(xml, it) } ?: ""

    /**
     * A run as the model shows it. Bold / italic / underline are the run's own formatting (set on the run or by
     * its character style); what the paragraph style adds on top is not a run flag — the style is.
     */
    private fun modelRun(r: Xml.Elem, src: Int): Run {
        val rPr = rPrOf(r)
        return Run(runText(r), runToggle(rPr, "w:b"), runToggle(rPr, "w:i"), runToggle(rPr, "w:u"), src, runToggle(rPr, "w:strike"))
    }

    private fun runToggle(rPr: String, prop: String): Boolean =
        DocxStyles.toggle(rPr, prop) ?: styles().charToggle(prop, DocxStyles.rStyle(rPr)) ?: false

    /** Runs that carry visible text, looking through insertions, hyperlinks, fields and inline containers. */
    private fun visibleRuns(within: Xml.Elem, out: MutableList<Xml.Elem>) {
        for (c in Xml.children(xml, within)) {
            when (c.name) {
                "w:r" -> out.add(c)
                "w:ins", "w:moveTo", "w:hyperlink", "w:smartTag", "w:fldSimple", "w:sdt", "w:sdtContent", "w:customXml", "w:dir", "w:bdo" -> visibleRuns(c, out)
            }
        }
    }

    private fun isSimple(kids: List<Xml.Elem>): Boolean {
        for (k in kids) {
            when (k.name) {
                "w:pPr", "w:proofErr", "w:bookmarkStart", "w:bookmarkEnd" -> {}
                "w:r" -> for (c in Xml.children(xml, k)) {
                    when (c.name) {
                        "w:rPr", "w:t", "w:tab", "w:cr", "w:noBreakHyphen", "w:softHyphen", "w:lastRenderedPageBreak" -> {}
                        "w:br" -> {
                            val a = Xml.attrs(xml, c)
                            if (!(a.isEmpty() || (a.size == 1 && a["w:type"] == "page"))) return false
                        }
                        else -> return false
                    }
                }
                else -> return false
            }
        }
        return true
    }

    private fun parseParagraph(p: Xml.Elem): Entry {
        val id = nextId++
        val kids = Xml.children(xml, p)
        val pPr = kids.firstOrNull { it.name == "w:pPr" }?.let { XmlEx.src(xml, it) } ?: ""
        val seg = xml.substring(p.start, p.end)
        val style = styleOf(pPr)
        val align = alignOf(pPr)

        val tracked = TRACKED.containsMatchIn(seg)
        val graphic = GRAPHIC.containsMatchIn(seg)
        val field = FIELD.containsMatchIn(seg)
        val section = pPr.isNotEmpty() && XmlEx.childOf(pPr, "w:sectPr") != null

        if (!tracked && !graphic && !field && !section && isSimple(kids)) {
            val runs = ArrayList<Run>()
            val srcRPr = ArrayList<String>()
            var i = 0
            for (k in kids) {
                if (k.name != "w:r") continue
                srcRPr.add(rPrOf(k))
                val r = modelRun(k, i)
                if (r.text.isNotEmpty()) runs.add(r)
                i++
            }
            return Entry(id, p.start, p.end, Paragraph(id, style, runs, true, null, align), srcRPr, false)
        }

        val found = ArrayList<Xml.Elem>()
        visibleRuns(p, found)
        val runs = found.mapIndexed { i, r -> modelRun(r, i) }.filter { it.text.isNotEmpty() }
        val blank = runs.all { it.text.isBlank() }
        val block: DocBlock = when {
            tracked -> Paragraph(id, style, runs, false, "tracked-changes")
            graphic && blank -> pictureOf(id, p, seg) ?: OpaqueBlock(id, graphicKind(seg))
            graphic -> Paragraph(id, style, runs, false, "complex")
            field -> Paragraph(id, style, runs, false, "field")
            section && blank -> OpaqueBlock(id, "section")
            else -> Paragraph(id, style, runs, false, "complex")
        }
        return Entry(id, p.start, p.end, block, emptyList(), false)
    }

    private fun graphicKind(seg: String): String = when {
        seg.contains("<w:txbxContent") -> "text-box"
        seg.contains("<c:chart") || seg.contains("/drawingml/2006/chart\"") -> "chart"
        seg.contains("<dgm:relIds") || seg.contains("/drawingml/2006/diagram\"") -> "smart-graphic"
        else -> "other"
    }

    /** A paragraph whose only content is one picture → [ImageBlock]; anything else → null. */
    private fun pictureOf(id: Int, p: Xml.Elem, seg: String): ImageBlock? {
        if (seg.contains("<w:pict") || seg.contains("<w:object") || seg.contains("<mc:AlternateContent") || seg.contains("<w:txbxContent")) return null
        val drawings = Xml.findAll(xml, p, "w:drawing")
        if (drawings.size != 1) return null
        val d = drawings[0]
        if (Xml.find(xml, d, "pic:pic") == null) return null
        val blip = Xml.find(xml, d, "a:blip") ?: return null
        val rid = Xml.attr(xml, blip, "r:embed") ?: return null
        val rel = Rels.of(pkg, docPart).firstOrNull { it.id == rid && !it.external } ?: return null
        val part = OoxmlPackage.resolve(docPart, rel.target)
        if (!pkg.has(part)) return null
        val ext = Xml.find(xml, d, "wp:extent")
        val w = ext?.let { Xml.attr(xml, it, "cx")?.toLongOrNull() } ?: 0L
        val h = ext?.let { Xml.attr(xml, it, "cy")?.toLongOrNull() } ?: 0L
        return ImageBlock(id, part, w, h)
    }

    private fun parseTable(id: Int, tbl: Xml.Elem): TableBlock {
        val rows = ArrayList<List<String>>()
        for (tr in Xml.children(xml, tbl, "w:tr")) {
            val row = ArrayList<String>()
            for (tc in Xml.children(xml, tr, "w:tc")) {
                val paras = Xml.findAll(xml, tc, "w:p").map { p ->
                    val found = ArrayList<Xml.Elem>()
                    visibleRuns(p, found)
                    found.joinToString("") { runText(it) }
                }
                row.add(paras.joinToString("\n"))
                val span = Xml.child(xml, tc, "w:tcPr")?.let { Xml.child(xml, it, "w:gridSpan") }?.let { Xml.attr(xml, it, "w:val")?.toIntOrNull() } ?: 1
                repeat(span - 1) { row.add("") }
            }
            rows.add(row)
        }
        return TableBlock(id, rows)
    }

    // ------------------------------------------------------------------------------------------------ writing

    private fun touched() {
        xmlChanged = true
        blocksCache = null
        compatCache = null
    }

    private fun replaceEntry(idx: Int, newXml: String) {
        val e = entries[idx]
        val delta = newXml.length - (e.end - e.start)
        xml = xml.substring(0, e.start) + newXml + xml.substring(e.end)
        e.end = e.start + newXml.length
        for (k in idx + 1 until entries.size) { entries[k].start += delta; entries[k].end += delta }
        touched()
    }

    /** Drops empty runs and characters the file format cannot carry; `\r\n` and `\r` become `\n`. */
    private fun normRuns(runs: List<Run>): List<Run> = runs.mapNotNull { r ->
        val raw = r.text.replace("\r\n", "\n").replace('\r', '\n')
        val sb = StringBuilder(raw.length)
        for (c in raw) if (c >= ' ' && c != '￾' && c != '￿' || c == '\t' || c == '\n' || c == '\u000C') sb.append(c)
        if (sb.isEmpty()) null else if (sb.length == r.text.length && raw == r.text) r else r.copy(text = sb.toString())
    }

    private fun runsXml(runs: List<Run>, srcRPr: List<String>): String {
        val sb = StringBuilder()
        for (r in runs) {
            var rPr = srcRPr.getOrNull(r.src) ?: srcRPr.firstOrNull() ?: ""
            rPr = withToggle(rPr, "w:b", r.bold)
            rPr = withToggle(rPr, "w:i", r.italic)
            rPr = withToggle(rPr, "w:u", r.underline)
            rPr = withToggle(rPr, "w:strike", r.strike)
            sb.append("<w:r>")
            if (rPr.isNotEmpty() && !XmlEx.isEmpty(rPr)) sb.append(rPr)
            val buf = StringBuilder()
            fun flush() {
                if (buf.isEmpty()) return
                val t = buf.toString()
                val keep = t.first().isWhitespace() || t.last().isWhitespace() || t.contains("  ")
                sb.append(if (keep) "<w:t xml:space=\"preserve\">" else "<w:t>").append(Xml.escape(t)).append("</w:t>")
                buf.setLength(0)
            }
            for (c in r.text) {
                when (c) {
                    '\t' -> { flush(); sb.append("<w:tab/>") }
                    '\n' -> { flush(); sb.append("<w:br/>") }
                    '\u000C' -> { flush(); sb.append("<w:br w:type=\"page\"/>") }
                    else -> buf.append(c)
                }
            }
            flush()
            sb.append("</w:r>")
        }
        return sb.toString()
    }

    /** Returns [rPrIn] with the run-level toggle [prop] made to read [want], touching nothing else in it. */
    private fun withToggle(rPrIn: String, prop: String, want: Boolean): String {
        var rPr = rPrIn
        if (runToggle(rPr, prop) == want) return rPr
        if (rPr.isNotEmpty()) {
            rPr = XmlEx.removeChildren(rPr, when (prop) { "w:b" -> setOf("w:b", "w:bCs"); "w:i" -> setOf("w:i", "w:iCs"); "w:strike" -> setOf("w:strike"); else -> setOf("w:u") })
        }
        if (runToggle(rPr, prop) == want) return rPr       // what is left (a character style, or nothing) already says so
        val el = if (prop == "w:u") (if (want) "<w:u w:val=\"single\"/>" else "<w:u w:val=\"none\"/>")
        else (if (want) "<$prop/>" else "<$prop w:val=\"0\"/>")
        if (rPr.isEmpty()) return "<w:rPr>$el</w:rPr>"
        return XmlEx.insertChild(rPr, el, prop, RPR_ORDER)
    }

    /**
     * `w:pPr` for a paragraph that changes to [target]: the old properties with only the style reference, list
     * membership and outline level replaced. [prevIdx] / [nextIdx] are the neighbouring blocks, used to join a list.
     */
    private fun restylePPr(oldPPr: String, target: ParaStyle, prevIdx: Int, nextIdx: Int): String {
        val st = styles()
        val oldStyle = pStyleOf(oldPPr)
        var pStyle: String? = oldStyle?.takeIf { st.isPlain(it) }
        var numPr: String? = null
        when (target) {
            ParaStyle.BODY -> {}
            ParaStyle.TITLE, ParaStyle.H1, ParaStyle.H2, ParaStyle.H3 -> pStyle = ensureParaStyle(target)
            ParaStyle.BULLET, ParaStyle.NUMBERED -> {
                val nb = listNeighbour(prevIdx, target) ?: listNeighbour(nextIdx, target)
                if (nb != null) {
                    if (nb.first != null) pStyle = nb.first
                    numPr = nb.second
                } else {
                    numPr = "<w:numPr><w:ilvl w:val=\"0\"/><w:numId w:val=\"${ownNumId(target == ParaStyle.BULLET)}\"/></w:numPr>"
                    styles().idForName("List Paragraph")?.let { pStyle = it }
                }
            }
        }
        var pPr = if (oldPPr.isEmpty()) "<w:pPr></w:pPr>" else XmlEx.removeChildren(oldPPr, setOf("w:pStyle", "w:numPr", "w:outlineLvl"))
        if (pStyle != null) pPr = XmlEx.insertChild(pPr, "<w:pStyle w:val=\"${Xml.escape(pStyle!!)}\"/>", "w:pStyle", PPR_ORDER)
        if (numPr != null) pPr = XmlEx.insertChild(pPr, numPr, "w:numPr", PPR_ORDER)
        return if (XmlEx.isEmpty(pPr)) "" else pPr
    }

    /** Style id and `w:numPr` of the block at [idx] when it is a paragraph of list style [target]. */
    private fun listNeighbour(idx: Int, target: ParaStyle): Pair<String?, String?>? {
        val e = entries.getOrNull(idx) ?: return null
        val b = e.block as? Paragraph ?: return null
        if (b.style != target) return null
        val p = Xml.elemAt(xml, e.start) ?: return null
        val pPrEl = Xml.child(xml, p, "w:pPr") ?: return null
        val pStyle = Xml.child(xml, pPrEl, "w:pStyle")?.let { Xml.attr(xml, it, "w:val") }
        val numPr = Xml.child(xml, pPrEl, "w:numPr")?.let { XmlEx.src(xml, it) }
        if (pStyle == null && numPr == null) return null
        return pStyle to numPr
    }

    /** Id of the title / heading style, adding the definition to the styles part when the file lacks it. */
    private fun ensureParaStyle(target: ParaStyle): String {
        styles().idFor(target)?.let { return it }
        var part = relPart("/styles")
        if (part == null) {
            part = OoxmlPackage.resolve(docPart, "styles.xml")
            pkg.putText(part, DocxTemplates.stylesXml())
            Rels.add(pkg, docPart, DocxTemplates.REL + "/styles", "styles.xml")
            ContentTypes.addOverride(pkg, part, DocxTemplates.CT_STYLES)
        } else {
            val sx = pkg.text(part)!!
            var id = when (target) { ParaStyle.TITLE -> "Title"; ParaStyle.H1 -> "Heading1"; ParaStyle.H2 -> "Heading2"; else -> "Heading3" }
            while (styles().byId.containsKey(id)) id += "S"
            pkg.putText(part, Xml.appendChild(sx, DocxTemplates.headingStyle(target, id, styles().defaultParaId)))
        }
        auxChanged = true
        stylesCache = null
        return styles().idFor(target) ?: throw IllegalStateException("could not add a style for $target")
    }

    /** numId of a list definition written by this module; bullets share one, every numbered list gets its own. */
    private fun ownNumId(bullet: Boolean): Int {
        if (bullet) ownBulletNumId?.let { return it }
        var part = relPart("/numbering")
        if (part == null) {
            part = OoxmlPackage.resolve(docPart, "numbering.xml")
            pkg.putText(part, DocxTemplates.numberingXml())
            Rels.add(pkg, docPart, DocxTemplates.REL + "/numbering", "numbering.xml")
            ContentTypes.addOverride(pkg, part, DocxTemplates.CT_NUMBERING)
        }
        val nx = pkg.text(part)!!
        val root = Xml.root(nx) ?: throw IllegalStateException("numbering part is not readable")
        val kids = Xml.children(nx, root)
        val absId = (kids.filter { it.name == "w:abstractNum" }.mapNotNull { Xml.attr(nx, it, "w:abstractNumId")?.toIntOrNull() }.maxOrNull() ?: -1) + 1
        val numId = (kids.filter { it.name == "w:num" }.mapNotNull { Xml.attr(nx, it, "w:numId")?.toIntOrNull() }.maxOrNull() ?: 0) + 1
        val absXml = DocxTemplates.abstractNum(absId, bullet)
        val numXml = "<w:num w:numId=\"$numId\"><w:abstractNumId w:val=\"$absId\"/></w:num>"
        val out = if (root.selfClosing) {
            Xml.appendInto(nx, root, absXml + numXml)
        } else {
            // schema order: every w:abstractNum before every w:num, both before w:numIdMacAtCleanup
            val tail = kids.firstOrNull { it.name == "w:numIdMacAtCleanup" }?.start ?: root.closeStart
            val absPos = kids.firstOrNull { it.name == "w:num" }?.start ?: tail
            val numPos = kids.lastOrNull { it.name == "w:num" }?.end ?: tail
            if (absPos == numPos) Xml.splice(nx, listOf(Triple(absPos, absPos, absXml + numXml)))
            else Xml.splice(nx, listOf(Triple(absPos, absPos, absXml), Triple(numPos, numPos, numXml)))
        }
        pkg.putText(part, out)
        auxChanged = true
        numberingCache = null
        if (bullet) ownBulletNumId = numId
        return numId
    }

    companion object {
        /** Opens an existing document. Nothing is modified until an edit method is called. */
        fun open(bytes: ByteArray): DocxDocument = DocxDocument(OoxmlPackage.read(bytes), bytes.copyOf())

        /** A new, empty document: one empty body paragraph, title / heading styles, A4 page. */
        fun blank(): DocxDocument = DocxDocument(DocxTemplates.blankPackage(), null)

        private val TRACKED = Regex("<w:(?:ins|del|moveFrom|moveTo|pPrChange|rPrChange|sectPrChange|tblPrChange|numberingChange)[\\s/>]")
        private val TRACKED_MARK = Regex("<w:(?:ins|del|moveFrom|moveTo)[\\s>]")
        private val GRAPHIC = Regex("<(?:w:drawing|w:pict|w:object|mc:AlternateContent)[\\s/>]")
        private val FIELD = Regex("<w:(?:fldSimple|fldChar|instrText)[\\s/>]")

        private val RPR_ORDER = listOf(
            "w:rStyle", "w:rFonts", "w:b", "w:bCs", "w:i", "w:iCs", "w:caps", "w:smallCaps", "w:strike", "w:dstrike",
            "w:outline", "w:shadow", "w:emboss", "w:imprint", "w:noProof", "w:snapToGrid", "w:vanish", "w:webHidden",
            "w:color", "w:spacing", "w:w", "w:kern", "w:position", "w:sz", "w:szCs", "w:highlight", "w:u", "w:effect",
            "w:bdr", "w:shd", "w:fitText", "w:vertAlign", "w:rtl", "w:cs", "w:em", "w:lang", "w:eastAsianLayout",
            "w:specVanish", "w:oMath", "w:rPrChange",
        )
        private val PPR_ORDER = listOf(
            "w:pStyle", "w:keepNext", "w:keepLines", "w:pageBreakBefore", "w:framePr", "w:widowControl", "w:numPr",
            "w:suppressLineNumbers", "w:pBdr", "w:shd", "w:tabs", "w:suppressAutoHyphens", "w:kinsoku", "w:wordWrap",
            "w:overflowPunct", "w:topLinePunct", "w:autoSpaceDE", "w:autoSpaceDN", "w:bidi", "w:adjustRightInd",
            "w:snapToGrid", "w:spacing", "w:ind", "w:contextualSpacing", "w:mirrorIndents", "w:suppressOverlap", "w:jc",
            "w:textDirection", "w:textAlignment", "w:textboxTightWrap", "w:outlineLvl", "w:divId", "w:cnfStyle",
            "w:rPr", "w:sectPr", "w:pPrChange",
        )
    }
}
