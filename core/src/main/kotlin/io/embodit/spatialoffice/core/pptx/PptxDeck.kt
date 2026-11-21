package io.embodit.spatialoffice.core.pptx

import io.embodit.spatialoffice.core.docx.CompatKind
import io.embodit.spatialoffice.core.docx.CompatNote
import io.embodit.spatialoffice.core.docx.XmlEx
import io.embodit.spatialoffice.core.pkg.ContentTypes
import io.embodit.spatialoffice.core.pkg.OoxmlPackage
import io.embodit.spatialoffice.core.pkg.Rels
import io.embodit.spatialoffice.core.pkg.Xml
import io.embodit.spatialoffice.core.text.RichText
import io.embodit.spatialoffice.core.text.Span

/**
 * A presentation opened for patch-only editing.
 *
 * Text edits replace only the paragraphs of the one shape inside its slide part. Adding, deleting and moving
 * slides touch the presentation part, its relationships and the content-type list, and add or remove whole parts;
 * every other part keeps the bytes it was read with.
 */
class PptxDeck private constructor(private val pkg: OoxmlPackage, private var source: ByteArray?) {

    private class SlideData(val shapes: List<Shape>, val notes: String, val backgroundRgb: Int?)

    /** A layout or master part with its placeholder shapes located. Such parts are never edited here. */
    private class PartDoc(val part: String, val xml: String, val root: Xml.Elem?, val phs: List<Ph>)
    private class Ph(val type: String, val idx: String, val sp: Xml.Elem)

    /** A container of `a:lvlNpPr` elements somewhere in the inheritance chain of a shape's text. */
    private class StyleSrc(val xml: String, val elem: Xml.Elem)

    private class OrigPara(val src: String, val pPr: String, val rPr: String, val endRPr: String, val model: TextPara)

    private val presPart: String =
        Rels.of(pkg, "").firstOrNull { !it.external && it.type.endsWith("/officeDocument") }
            ?.let { OoxmlPackage.resolve("", it.target) }?.takeIf { pkg.has(it) } ?: "ppt/presentation.xml"

    private var changed = false
    private var slidePartsCache: List<String>? = null
    private var slidesCache: List<Slide>? = null
    private val slideCache = HashMap<String, SlideData>()
    private val docCache = HashMap<String, PartDoc>()
    private val themeCache = HashMap<String, Map<String, Int>>()

    init {
        if (!pkg.has(presPart)) throw IllegalArgumentException("not a presentation: no $presPart")
    }

    // ------------------------------------------------------------------------------------------------ public API

    val widthEmu: Long get() = sldSz("cx") ?: 12192000L
    val heightEmu: Long get() = sldSz("cy") ?: 6858000L

    val slides: List<Slide>
        get() = slidesCache ?: slideParts().mapIndexed { i, part ->
            val d = slideCache.getOrPut(part) { readSlide(part) }
            Slide(i, d.shapes, d.notes, d.backgroundRgb)
        }.also { slidesCache = it }

    /** Current bytes of a package part (e.g. a [Shape.imagePart]); null when the part does not exist. */
    fun partBytes(part: String): ByteArray? = pkg.bytes(part)

    val dirty: Boolean get() = changed

    /** Text of the first non-empty title; when no slide has one, the first non-empty text of any shape; else "". */
    fun firstTitle(): String {
        val all = slides
        fun text(s: Shape) = s.paras.joinToString(" ") { it.text.replace('\n', ' ') }.trim()
        for (sl in all) for (s in sl.shapes) if (s.kind == ShapeKind.TITLE) text(s).let { if (it.isNotEmpty()) return it }
        for (sl in all) for (s in sl.shapes) if (s.paras.isNotEmpty()) text(s).let { if (it.isNotEmpty()) return it }
        return ""
    }

    /**
     * Replaces the paragraphs of an editable shape. `a:bodyPr`, `a:lstStyle`, each paragraph's `a:pPr` and its first
     * run's `a:rPr` are kept; a paragraph whose text, level and bullet are unchanged is kept exactly as it was.
     * @throws IllegalStateException when the shape is not editable.
     */
    fun setShapeText(slide: Int, shapeId: Int, paras: List<TextPara>) {
        val part = slidePart(slide)
        val shape = slides[slide].shapes.firstOrNull { it.id == shapeId } ?: throw IllegalArgumentException("slide $slide has no shape $shapeId")
        if (!shape.editable) throw IllegalStateException("shape $shapeId on slide $slide is read-only")
        val sx = pkg.text(part)!!
        val sp = topShapes(sx).firstOrNull { it.name == "p:sp" && shapeId(sx, it) == shapeId }
            ?: throw IllegalStateException("shape $shapeId on slide $slide is read-only")
        pkg.putText(part, replaceText(sx, sp, paras, styleChain(sx, sp, part), masterOf(part)))
        slideChanged(part)
    }

    /** Sets the speaker notes of a slide (one paragraph per line), creating the notes slide when there is none. */
    fun setNotes(slide: Int, text: String) {
        val part = slidePart(slide)
        var notes = notesPartOf(part)
        if (notes == null) {
            if (text.isEmpty()) return
            notes = createNotesSlide(part)
        }
        val paras = text.replace("\r\n", "\n").replace('\r', '\n').split('\n').map { TextPara(it) }
        var nx = pkg.text(notes)!!
        var body = notesBody(nx)
        if (body == null) {
            val tree = Xml.root(nx)?.let { Xml.find(nx, it, "p:spTree") } ?: throw IllegalStateException("notes slide is not readable")
            val id = (maxShapeId(nx) + 1).coerceAtLeast(2)
            nx = insertIntoTree(nx, tree, PptxTemplates.placeholder(id, "Notes Placeholder", "type=\"body\" idx=\"1\""))
            body = notesBody(nx) ?: throw IllegalStateException("notes slide is not readable")
        }
        pkg.putText(notes, replaceText(nx, body, paras, emptyList(), null))
        slideChanged(part)
    }

    /** Adds a slide after index [after] (-1 = first) using the deck's own title or title-and-content layout. */
    fun addSlide(after: Int, kind: SlideLayoutKind): Int {
        val parts = slideParts()
        if (after < -1 || after > parts.lastIndex) throw IndexOutOfBoundsException("after = $after, slides = ${parts.size}")
        val layout = pickLayout(kind) ?: throw IllegalStateException("the deck has no slide layout to build a slide from")
        val lType = layout.root?.let { Xml.attr(layout.xml, it, "type") }
        // decks without a two-column or blank layout get the title-and-content one, with two text boxes / nothing on it
        val twoBoxes = kind == SlideLayoutKind.TWO_COLUMN && lType != "twoObj" && lType != "twoTxTwoObj"
        val bare = kind == SlideLayoutKind.BLANK

        val shapes = StringBuilder()
        var id = 2
        var titleBox: LongArray? = null
        for (ph in layout.phs) {
            if (ph.type in FOOTER_TYPES) continue
            if (bare) continue
            if (twoBoxes && masterType(ph.type) != "title") continue
            if (masterType(ph.type) == "title") titleBox = boxOf(layout.xml, ph.sp)
            val phEl = Xml.find(layout.xml, ph.sp, "p:ph") ?: continue
            val attrs = layout.xml.substring(phEl.start + "<p:ph".length, phEl.openEnd).trimEnd('>').trimEnd('/').trim()
            val name = Xml.find(layout.xml, ph.sp, "p:cNvPr")?.let { Xml.attr(layout.xml, it, "name") } ?: "Placeholder $id"
            shapes.append(PptxTemplates.placeholder(id, name, attrs))
            id++
        }
        if (twoBoxes) {
            val w = widthEmu
            val h = heightEmu
            val top = titleBox?.let { it[1] + it[3] + h / 40 } ?: (h / 4)
            val margin = w / 16
            val gap = w / 32
            val colW = (w - 2 * margin - gap) / 2
            val colH = h - top - h / 10
            for (k in 0..1) {
                val x = margin + k * (colW + gap)
                shapes.append(PptxTemplates.textBox(id, x, top, colW, colH, "<a:p><a:endParaRPr lang=\"en-US\" sz=\"2000\"/></a:p>", ""))
                id++
            }
        }
        val dir = parts.firstOrNull()?.substringBeforeLast('/') ?: OoxmlPackage.resolve(presPart, "slides/x").substringBeforeLast('/')
        val part = freeName(dir, "slide", ".xml")
        pkg.putText(part, PptxTemplates.slide(shapes.toString()))
        pkg.putText(OoxmlPackage.relsOf(part), PptxTemplates.rels(PptxTemplates.REL + "/slideLayout" to XmlEx.relativize(part, layout.part)))
        ContentTypes.addOverride(pkg, part, PptxTemplates.CT_SLIDE)
        insertSlideEntry(part, after)
        structureChanged()
        return after + 1
    }

    /** Adds the presentation's relationship to [part] and its entry in the slide list after index [after] (-1 = first). */
    private fun insertSlideEntry(part: String, after: Int) {
        val rid = Rels.add(pkg, presPart, PptxTemplates.REL + "/slide", XmlEx.relativize(presPart, part))

        val px = pkg.text(presPart)!!
        val root = Xml.root(px) ?: throw IllegalStateException("presentation part is not readable")
        val lst = Xml.child(px, root, "p:sldIdLst")
        val listed = slideEntries(px).map { it.first }
        val all = lst?.let { Xml.children(px, it, "p:sldId") } ?: emptyList()
        val newId = maxOf(255L, all.mapNotNull { Xml.attr(px, it, "id")?.toLongOrNull() }.maxOrNull() ?: 255L) + 1
        val entry = "<p:sldId id=\"$newId\" r:id=\"$rid\"${rDecl(px, root)}/>"
        val out = when {
            lst == null -> {
                val prev = listOf("p:handoutMasterIdLst", "p:notesMasterIdLst", "p:sldMasterIdLst").firstNotNullOfOrNull { Xml.child(px, root, it) }
                val pos = prev?.end ?: root.openEnd
                px.substring(0, pos) + "<p:sldIdLst>$entry</p:sldIdLst>" + px.substring(pos)
            }
            lst.selfClosing -> Xml.appendInto(px, lst, entry)
            else -> {
                val pos = if (after < 0 || listed.isEmpty()) lst.openEnd else listed[minOf(after, listed.lastIndex)].end
                px.substring(0, pos) + entry + px.substring(pos)
            }
        }
        pkg.putText(presPart, out)
    }

    /**
     * Removes a slide: its entry in the slide list, the presentation's relationship to it, the slide part, and any
     * part only that slide used (its notes slide, pictures nothing else shows).
     */
    fun deleteSlide(index: Int) {
        val part = slidePart(index)
        val px = pkg.text(presPart)!!
        val root = Xml.root(px) ?: throw IllegalStateException("presentation part is not readable")
        val entry = slideEntries(px)[index].first
        val rid = ridOf(px, entry)
        val edits = ArrayList<Triple<Int, Int, String>>()
        edits.add(Triple(entry.start, entry.end, ""))
        // a section list (kept in the presentation's extension list) names slides by their numeric id
        Xml.attr(px, entry, "id")?.let { sid ->
            for (m in Regex("<p14:sldId\\s+id=\"" + Regex.escape(sid) + "\"\\s*/>").findAll(px)) edits.add(Triple(m.range.first, m.range.last + 1, ""))
        }
        // custom shows list slides by relationship id
        Xml.child(px, root, "p:custShowLst")?.let { shows ->
            for (sld in Xml.findAll(px, shows, "p:sld")) if (ridOf(px, sld) == rid) edits.add(Triple(sld.start, sld.end, ""))
        }
        pkg.putText(presPart, Xml.splice(px, edits))
        if (rid != null) XmlEx.removeRel(pkg, presPart, rid)

        // A slide another part still points at (other than its own notes slide) keeps its part, so nothing dangles.
        val notes = notesPartOf(part)
        val stillUsed = pkg.names().any { n ->
            val owner = XmlEx.ownerOfRels(n) ?: return@any false
            owner != part && owner != notes && Rels.parse(pkg.text(n) ?: "").any { !it.external && OoxmlPackage.resolve(owner, it.target) == part }
        }
        if (!stillUsed) removePartTree(part, notes)
        slideCache.remove(part)
        structureChanged()
    }

    /** Moves the slide at [from] so that it ends up at index [to]. Only the slide list is rewritten. */
    fun moveSlide(from: Int, to: Int) {
        val n = slideParts().size
        if (from !in 0 until n || to !in 0 until n) throw IndexOutOfBoundsException("move $from → $to, slides = $n")
        if (from == to) return
        val px = pkg.text(presPart)!!
        val ids = slideEntries(px).map { it.first }
        val moved = XmlEx.src(px, ids[from])
        // insertion point in original coordinates: before the element now at `to` when moving up, after it when moving down
        val pos = if (to < from) ids[to].start else ids[to].end
        pkg.putText(presPart, Xml.splice(px, listOf(Triple(ids[from].start, ids[from].end, ""), Triple(pos, pos, moved))))
        structureChanged()
    }

    /** Adds a free text box (one paragraph per line of [text]) on top of the slide's other shapes; returns its shape id. */
    fun addTextBox(slide: Int, x: Long, y: Long, w: Long, h: Long, text: String): Int {
        val part = slidePart(slide)
        val sx = pkg.text(part)!!
        val root = Xml.root(sx) ?: throw IllegalStateException("slide $slide is not readable")
        val tree = Xml.find(sx, root, "p:spTree") ?: throw IllegalStateException("slide $slide has no shape tree")
        val id = maxShapeId(sx) + 1
        val paras = text.replace("\r\n", "\n").replace('\r', '\n').split('\n').joinToString("") { line ->
            if (line.isEmpty()) "<a:p/>" else "<a:p><a:r><a:rPr lang=\"en-US\"/><a:t>${Xml.escape(cleanText(line))}</a:t></a:r></a:p>"
        }
        val ns = if (Xml.attr(sx, root, "xmlns:a") == PptxTemplates.NS_A) "" else " xmlns:a=\"${PptxTemplates.NS_A}\""
        pkg.putText(part, insertIntoTree(sx, tree, PptxTemplates.textBox(id, x, y, w, h, paras, ns)))
        slideChanged(part)
        return id
    }

    /**
     * Copies slide [index] (shapes, pictures, layout, speaker notes) and puts the copy right after it; returns the
     * copy's index. Parts the slide points at (pictures, layout) are shared, not copied.
     */
    fun duplicateSlide(index: Int): Int {
        val part = slidePart(index)
        val notesText = slides[index].notes
        val dir = part.substringBeforeLast('/')
        val copy = freeName(dir, "slide", ".xml")
        pkg.put(copy, pkg.bytes(part)!!)
        val rels = Rels.of(pkg, part).filter { !it.type.endsWith("/notesSlide") }
        val sb = StringBuilder(XmlEx.XML_DECL).append("<Relationships xmlns=\"${PptxTemplates.REL_PKG}\">")
        for (r in rels) {
            sb.append("<Relationship Id=\"").append(Xml.escape(r.id)).append("\" Type=\"").append(Xml.escape(r.type)).append("\" Target=\"")
                .append(Xml.escape(r.target)).append('"')
            if (r.external) sb.append(" TargetMode=\"External\"")
            sb.append("/>")
        }
        pkg.putText(OoxmlPackage.relsOf(copy), sb.append("</Relationships>").toString())
        ContentTypes.addOverride(pkg, copy, PptxTemplates.CT_SLIDE)
        insertSlideEntry(copy, index)
        structureChanged()
        if (notesText.isNotEmpty()) setNotes(index + 1, notesText)
        return index + 1
    }

    /** Adds a picture on top of the slide's shapes; returns its shape id. [ext] is "png" / "jpeg" / "jpg" / "gif". */
    fun addPicture(slide: Int, bytes: ByteArray, ext: String, x: Long, y: Long, w: Long, h: Long): Int {
        val part = slidePart(slide)
        val e = ext.lowercase().let { if (it == "jpg") "jpeg" else it }
        val base = OoxmlPackage.resolve(presPart, "media/x").substringBeforeLast('/')
        val media = freeName(base, "image", ".$e")
        pkg.put(media, bytes)
        ContentTypes.addDefault(pkg, e, "image/$e")
        val rid = Rels.add(pkg, part, PptxTemplates.REL + "/image", XmlEx.relativize(part, media))
        val sx = pkg.text(part)!!
        val root = Xml.root(sx) ?: throw IllegalStateException("slide $slide is not readable")
        val tree = Xml.find(sx, root, "p:spTree") ?: throw IllegalStateException("slide $slide has no shape tree")
        val id = maxShapeId(sx) + 1
        pkg.putText(part, insertIntoTree(sx, tree, PptxTemplates.picture(id, rid, x, y, w, h)))
        slideChanged(part)
        return id
    }

    /** Adds a filled preset shape ([geom]: "rect", "roundRect", "ellipse", "triangle", "rightArrow", …) that can hold text. */
    fun addShape(slide: Int, geom: String, x: Long, y: Long, w: Long, h: Long, fillRgb: Int): Int {
        val part = slidePart(slide)
        val sx = pkg.text(part)!!
        val root = Xml.root(sx) ?: throw IllegalStateException("slide $slide is not readable")
        val tree = Xml.find(sx, root, "p:spTree") ?: throw IllegalStateException("slide $slide has no shape tree")
        val id = maxShapeId(sx) + 1
        pkg.putText(part, insertIntoTree(sx, tree, PptxTemplates.shape(id, geom, x, y, w, h, fillRgb)))
        slideChanged(part)
        return id
    }

    /** Removes one top-level shape (text box, picture, drawn shape, placeholder) from a slide. */
    fun deleteShape(slide: Int, shapeId: Int) {
        val part = slidePart(slide)
        val sx = pkg.text(part)!!
        val e = topShapes(sx).firstOrNull { shapeId(sx, it) == shapeId } ?: throw IllegalArgumentException("slide $slide has no shape $shapeId")
        pkg.putText(part, sx.substring(0, e.start) + sx.substring(e.end))
        slideChanged(part)
    }

    // ---- M1: object clipboard and drawing order

    /**
     * Copies a top-level object of a slide. Nothing in the deck changes. A placeholder is copied as a plain shape: its
     * `p:ph` is dropped and, when it inherited its box from the layout, the resolved box is written into it.
     * @throws IllegalArgumentException when the slide has no such object.
     * @throws IllegalStateException for objects that own parts of their own (charts, diagrams, embedded objects).
     */
    fun copyShape(slide: Int, shapeId: Int): ShapeClip {
        val part = slidePart(slide)
        val sx = pkg.text(part)!!
        val root = Xml.root(sx) ?: throw IllegalStateException("slide $slide is not readable")
        val e = topShapes(sx).firstOrNull { shapeId(sx, it) == shapeId } ?: throw IllegalArgumentException("slide $slide has no shape $shapeId")
        val box = slides[slide].shapes.firstOrNull { it.id == shapeId }?.let { longArrayOf(it.x, it.y, it.w, it.h) } ?: boxOf(sx, e) ?: longArrayOf(0, 0, 0, 0)
        var xml = XmlEx.src(sx, e)
        if (e.name == "p:sp" || e.name == "p:pic" || e.name == "p:graphicFrame") xml = unplaceholder(xml, box)

        val nsDecls = LinkedHashMap<String, String>()
        for ((k, v) in Xml.attrs(sx, root)) if (k == "xmlns" || k.startsWith("xmlns:")) nsDecls[k] = v
        val rPrefixes = HashSet<String>()
        for ((k, v) in nsDecls) if (v == PptxTemplates.NS_R && k.startsWith("xmlns:")) rPrefixes.add(k.substringAfter(':'))
        for (m in NS_DECL.findAll(xml)) if (m.groupValues[2] == PptxTemplates.NS_R) rPrefixes.add(m.groupValues[1])

        val srcRels = Rels.of(pkg, part).associateBy { it.id }
        val rels = LinkedHashMap<String, ClipRel>()
        for (m in R_ATTR.findAll(xml)) {
            if (m.groupValues[1] !in rPrefixes) continue
            val rid = Xml.unescape(m.groupValues[3])
            if (rid.isEmpty() || rid in rels) continue
            val rel = srcRels[rid] ?: continue
            if (!COPYABLE_REL.any { rel.type.endsWith(it) }) throw IllegalStateException("shape $shapeId owns a part of its own (${rel.type.substringAfterLast('/')}) and cannot be copied")
            rels[rid] = ClipRel(rel.type, if (rel.external) rel.target else OoxmlPackage.resolve(part, rel.target), rel.external)
        }
        return ShapeClip(xml, part, nsDecls, rels, box)
    }

    /**
     * Puts a copied object on top of slide [slide], moved by ([dx], [dy]) EMU (kept on the slide). Every `p:cNvPr` in
     * it gets a fresh id unique on that slide; the relationships it uses are found or made in the slide's .rels, pointing
     * at the same parts (pictures are shared, not duplicated). Returns the new object's id.
     * @throws IllegalArgumentException when a part the copy points at is not in this deck.
     */
    fun pasteShape(slide: Int, clip: ShapeClip, dx: Long, dy: Long): Int {
        val part = slidePart(slide)
        for (r in clip.rels.values) if (!r.external && !pkg.has(r.target)) throw IllegalArgumentException("the copied object needs ${r.target}, which this deck does not have")
        val sx = pkg.text(part)!!
        val root = Xml.root(sx) ?: throw IllegalStateException("slide $slide is not readable")
        val tree = Xml.find(sx, root, "p:spTree") ?: throw IllegalStateException("slide $slide has no shape tree")
        var xml = clip.xml

        // ids: every object inside gets a fresh one; connections between them follow
        var next = maxShapeId(sx) + 1
        val idMap = HashMap<String, String>()
        xml = CNVPR_ID.replace(xml) { m -> val n = (next++).toString(); idMap[m.groupValues[2]] = n; m.groupValues[1] + n + "\"" }
        xml = CXN_ID.replace(xml) { m -> idMap[m.groupValues[2]]?.let { m.groupValues[1] + it + "\"" } ?: m.value }
        val newId = idMap.values.minOfOrNull { it.toInt() } ?: throw IllegalStateException("the copied object has no id")

        // relationships
        if (clip.rels.isNotEmpty()) {
            val ridMap = HashMap<String, String>()
            for ((old, r) in clip.rels) {
                val have = Rels.of(pkg, part)
                fun same(x: io.embodit.spatialoffice.core.pkg.Rel) = x.type == r.type && x.external == r.external &&
                    (if (r.external) x.target == r.target else OoxmlPackage.resolve(part, x.target) == r.target)
                val keep = if (part == clip.sourcePart) have.firstOrNull { it.id == old && same(it) } else null
                ridMap[old] = (keep ?: have.firstOrNull { same(it) })?.id ?: addRel(part, r.type, if (r.external) r.target else XmlEx.relativize(part, r.target), r.external)
            }
            val rPrefixes = HashSet<String>()
            for ((k, v) in clip.nsDecls) if (v == PptxTemplates.NS_R && k.startsWith("xmlns:")) rPrefixes.add(k.substringAfter(':'))
            for (m in NS_DECL.findAll(xml)) if (m.groupValues[2] == PptxTemplates.NS_R) rPrefixes.add(m.groupValues[1])
            xml = R_ATTR.replace(xml) { m ->
                val to = if (m.groupValues[1] in rPrefixes) ridMap[Xml.unescape(m.groupValues[3])] else null
                if (to == null) m.value else m.groupValues[1] + ":" + m.groupValues[2] + "=\"" + Xml.escape(to) + "\""
            }
        }

        // position, kept on the slide
        val el = Xml.root(xml) ?: throw IllegalStateException("the copied object is not readable")
        val off = offElem(xml, el)
        val b = clip.box
        val w = widthEmu; val h = heightEmu
        val nx = (b[0] + dx).coerceAtMost(w - b[2]).coerceAtLeast(0)
        val ny = (b[1] + dy).coerceAtMost(h - b[3]).coerceAtLeast(0)
        if (off != null && (dx != 0L || dy != 0L || nx != b[0] || ny != b[1])) {
            var tag = xml.substring(off.start, off.openEnd)
            tag = XmlEx.tagWithAttr(tag, "x", nx.toString())
            tag = XmlEx.tagWithAttr(tag, "y", ny.toString())
            xml = xml.substring(0, off.start) + tag + xml.substring(off.openEnd)
        }

        // namespace bindings the source slide gave it and this slide does not
        val here = Xml.attrs(sx, root)
        val top = Xml.root(xml)!!
        val ownTag = xml.substring(top.start, top.openEnd)
        val add = StringBuilder()
        for ((k, v) in clip.nsDecls) {
            if (here[k] == v) continue
            if (Regex("\\s" + Regex.escape(k) + "\\s*=").containsMatchIn(ownTag)) continue
            add.append(' ').append(k).append("=\"").append(Xml.escape(v)).append('"')
        }
        if (add.isNotEmpty()) {
            val at = top.start + 1 + top.name.length
            xml = xml.substring(0, at) + add + xml.substring(at)
        }

        pkg.putText(part, insertIntoTree(sx, tree, xml))
        slideChanged(part)
        return newId
    }

    /** Copies an object and pastes it on the same slide, a little down and to the right (1/40 of the slide width). */
    fun duplicateShape(slide: Int, shapeId: Int): Int {
        val d = widthEmu / 40
        return pasteShape(slide, copyShape(slide, shapeId), d, d)
    }

    /**
     * Changes where an object sits in the slide's drawing order (back to front = order in `p:spTree`). Only the slide
     * part changes and the object's own XML is moved as it is. Returns false when it is already there.
     */
    fun reorderShape(slide: Int, shapeId: Int, op: ZOrder): Boolean {
        val part = slidePart(slide)
        val sx = pkg.text(part)!!
        val shapes = topShapes(sx).filter { it.name != "p:extLst" }
        val i = shapes.indexOfFirst { shapeId(sx, it) == shapeId }
        if (i < 0) throw IllegalArgumentException("slide $slide has no shape $shapeId")
        val to = when (op) {
            ZOrder.FRONT -> shapes.lastIndex
            ZOrder.BACK -> 0
            ZOrder.FORWARD -> (i + 1).coerceAtMost(shapes.lastIndex)
            ZOrder.BACKWARD -> (i - 1).coerceAtLeast(0)
        }
        if (to == i) return false
        val order = shapes.indices.toMutableList()
        order.removeAt(i)
        order.add(to, i)
        // the elements change places; whatever lies between them stays where it was
        val sb = StringBuilder(sx.length)
        sb.append(sx, 0, shapes.first().start)
        for (k in shapes.indices) {
            sb.append(XmlEx.src(sx, shapes[order[k]]))
            if (k < shapes.lastIndex) sb.append(sx, shapes[k].end, shapes[k + 1].start)
        }
        sb.append(sx, shapes.last().end, sx.length)
        pkg.putText(part, sb.toString())
        slideChanged(part)
        return true
    }

    /** [xml] of a top-level `p:sp` / `p:pic` / `p:graphicFrame` without its `p:ph`, with [box] written in when it had none. */
    private fun unplaceholder(xml: String, box: LongArray): String {
        val el = Xml.root(xml) ?: return xml
        val ph = Xml.find(xml, el, "p:ph") ?: return xml
        val edits = ArrayList<Triple<Int, Int, String>>()
        edits.add(Triple(ph.start, ph.end, ""))
        if (offElem(xml, el) == null) {
            if (el.name == "p:graphicFrame") {
                val xf = Xml.child(xml, el, "p:xfrm")
                val inner = "<a:off x=\"${box[0]}\" y=\"${box[1]}\"/><a:ext cx=\"${box[2]}\" cy=\"${box[3]}\"/>"
                if (xf != null) edits.add(Triple(xf.start, xf.end, "<p:xfrm>$inner</p:xfrm>"))
                else Xml.child(xml, el, "p:nvGraphicFramePr")?.let { edits.add(Triple(it.end, it.end, "<p:xfrm>$inner</p:xfrm>")) }
            } else {
                val f = PptxTemplates.xfrm(box[0], box[1], box[2], box[3])
                val spPr = Xml.child(xml, el, "p:spPr")
                when {
                    spPr == null -> Xml.children(xml, el).firstOrNull { it.name.startsWith("p:nv") }?.let { edits.add(Triple(it.end, it.end, "<p:spPr>$f</p:spPr>")) }
                    spPr.selfClosing -> edits.add(Triple(spPr.start, spPr.end, "<p:spPr>$f</p:spPr>"))
                    else -> {
                        val old = Xml.child(xml, spPr, "a:xfrm")
                        if (old != null) edits.add(Triple(old.start, old.end, f)) else edits.add(Triple(spPr.openEnd, spPr.openEnd, f))
                    }
                }
            }
        }
        return Xml.splice(xml, edits)
    }

    /** The `a:off` of a top-level object's own box, null when it has none. */
    private fun offElem(xml: String, e: Xml.Elem): Xml.Elem? {
        val xf = when (e.name) {
            "p:sp", "p:pic", "p:cxnSp" -> Xml.child(xml, e, "p:spPr")?.let { Xml.child(xml, it, "a:xfrm") }
            "p:grpSp" -> Xml.child(xml, e, "p:grpSpPr")?.let { Xml.child(xml, it, "a:xfrm") }
            "p:graphicFrame" -> Xml.child(xml, e, "p:xfrm")
            else -> Xml.find(xml, e, "a:xfrm") ?: Xml.find(xml, e, "p:xfrm")
        } ?: return null
        return Xml.child(xml, xf, "a:off")
    }

    /** [Rels.add] that can also write an external target. */
    private fun addRel(part: String, type: String, target: String, external: Boolean): String {
        val id = Rels.add(pkg, part, type, target)
        if (!external) return id
        val relsName = OoxmlPackage.relsOf(part)
        val rx = pkg.text(relsName)!!
        val root = Xml.root(rx)!!
        val e = Xml.children(rx, root).last { Xml.attr(rx, it, "Id") == id }
        val tag = rx.substring(e.start, e.openEnd).let { if (it.endsWith("/>")) it.dropLast(2).trimEnd() + " TargetMode=\"External\"/>" else it.dropLast(1) + " TargetMode=\"External\">" }
        pkg.putText(relsName, rx.substring(0, e.start) + tag + rx.substring(e.openEnd))
        return id
    }

    /**
     * Moves / resizes a top-level shape: text box, placeholder, drawn shape, picture, connector, group (its children
     * keep their own coordinates and scale with it) or graphic frame (table, chart). A placeholder that inherited its
     * box gets one of its own.
     */
    fun setShapeBox(slide: Int, shapeId: Int, x: Long, y: Long, w: Long, h: Long) {
        val part = slidePart(slide)
        val sx = pkg.text(part)!!
        val e = topShapes(sx).firstOrNull { shapeId(sx, it) == shapeId } ?: throw IllegalArgumentException("slide $slide has no shape $shapeId")
        val cw = w.coerceAtLeast(1); val ch = h.coerceAtLeast(1)
        if (e.name == "p:graphicFrame") { setFrameBox(part, sx, e, x, y, cw, ch); return }
        val prName = when (e.name) { "p:sp", "p:pic", "p:cxnSp" -> "p:spPr"; "p:grpSp" -> "p:grpSpPr"; else -> throw IllegalStateException("shape $shapeId cannot be moved here") }
        val spPr = Xml.child(sx, e, prName) ?: throw IllegalStateException("shape $shapeId has no shape properties")
        val box = PptxTemplates.xfrm(x, y, cw, ch)
        val xf = Xml.child(sx, spPr, "a:xfrm")
        val out = when {
            xf != null -> {
                // keep rotation / flip attributes on the element, and a group's child coordinate space (chOff / chExt)
                val open = sx.substring(xf.start, xf.openEnd).let { if (it.endsWith("/>")) it.dropLast(2) + ">" else it }
                val rest = Xml.children(sx, xf).filter { it.name != "a:off" && it.name != "a:ext" }.joinToString("") { XmlEx.src(sx, it) }
                sx.substring(0, xf.start) + open + box.removePrefix("<a:xfrm>").removeSuffix("</a:xfrm>") + rest + "</a:xfrm>" + sx.substring(xf.end)
            }
            e.name == "p:grpSp" -> throw IllegalStateException("group $shapeId has no box of its own")
            spPr.selfClosing -> sx.substring(0, spPr.start) + "<p:spPr>$box</p:spPr>" + sx.substring(spPr.end)
            else -> sx.substring(0, spPr.openEnd) + box + sx.substring(spPr.openEnd)
        }
        pkg.putText(part, out)
        slideChanged(part)
    }

    /**
     * Moves / resizes a graphic frame (table, chart, diagram): its `p:xfrm`. A table also has its own column widths
     * and row heights, which other software lays it out by, so they are scaled with the frame.
     */
    private fun setFrameBox(part: String, sx: String, e: Xml.Elem, x: Long, y: Long, w: Long, h: Long) {
        val edits = ArrayList<Triple<Int, Int, String>>()
        val xf = Xml.child(sx, e, "p:xfrm")
        val old = boxOf(sx, e)
        val box = "<a:off x=\"$x\" y=\"$y\"/><a:ext cx=\"$w\" cy=\"$h\"/>"
        if (xf != null) {
            val open = sx.substring(xf.start, xf.openEnd).let { if (it.endsWith("/>")) it.dropLast(2) + ">" else it }
            edits.add(Triple(xf.start, xf.end, "$open$box</p:xfrm>"))
        } else {
            val after = Xml.child(sx, e, "p:nvGraphicFramePr")?.end ?: e.openEnd
            edits.add(Triple(after, after, "<p:xfrm>$box</p:xfrm>"))
        }
        val tbl = Xml.find(sx, e, "a:tbl")
        if (tbl != null && old != null && old[2] > 0 && old[3] > 0 && (old[2] != w || old[3] != h)) {
            val fw = w.toDouble() / old[2]; val fh = h.toDouble() / old[3]
            fun scaled(el: Xml.Elem, attr: String, f: Double) {
                val v = Xml.attr(sx, el, attr)?.toLongOrNull() ?: return
                val open = sx.substring(el.start, el.openEnd)
                val m = Regex("\\b" + attr + "=\"\\d+\"").find(open) ?: return
                edits.add(Triple(el.start + m.range.first, el.start + m.range.last + 1, attr + "=\"" + Math.round(v * f).coerceAtLeast(1) + "\""))
            }
            Xml.child(sx, tbl, "a:tblGrid")?.let { g -> for (c in Xml.children(sx, g, "a:gridCol")) scaled(c, "w", fw) }
            for (tr in Xml.children(sx, tbl, "a:tr")) scaled(tr, "h", fh)
        }
        pkg.putText(part, Xml.splice(sx, edits))
        slideChanged(part)
    }

    /** Adds an empty table of [rows] × [cols] cells on top of the slide's shapes; returns its shape id. */
    fun addTable(slide: Int, rows: Int, cols: Int, x: Long, y: Long, w: Long, h: Long): Int {
        require(rows in 1..40 && cols in 1..20) { "table size $rows × $cols" }
        val part = slidePart(slide)
        val sx = pkg.text(part)!!
        val root = Xml.root(sx) ?: throw IllegalStateException("slide $slide is not readable")
        val tree = Xml.find(sx, root, "p:spTree") ?: throw IllegalStateException("slide $slide has no shape tree")
        val id = maxShapeId(sx) + 1
        val ns = if (Xml.attr(sx, root, "xmlns:a") == PptxTemplates.NS_A) "" else " xmlns:a=\"${PptxTemplates.NS_A}\""
        pkg.putText(part, insertIntoTree(sx, tree, PptxTemplates.table(id, rows, cols, x, y, w, h, ns)))
        slideChanged(part)
        return id
    }

    /**
     * Sets the text of one cell of a plain table (one paragraph per line). The cell keeps its properties, its body
     * properties and the run formatting of its first paragraph.
     */
    fun setTableCell(slide: Int, shapeId: Int, row: Int, col: Int, text: String) {
        val part = slidePart(slide)
        val sx = pkg.text(part)!!
        val e = topShapes(sx).firstOrNull { shapeId(sx, it) == shapeId } ?: throw IllegalArgumentException("slide $slide has no shape $shapeId")
        if (tableOf(sx, e)?.second != true) throw IllegalStateException("shape $shapeId is not a plain table")
        val tbl = Xml.find(sx, e, "a:tbl")!!
        val tc = Xml.children(sx, tbl, "a:tr").getOrNull(row)?.let { Xml.children(sx, it, "a:tc").getOrNull(col) } ?: throw IndexOutOfBoundsException("cell $row,$col")
        val body = Xml.child(sx, tc, "a:txBody")
        val kids = body?.let { Xml.children(sx, it) } ?: emptyList()
        val bodyPr = kids.firstOrNull { it.name == "a:bodyPr" }?.let { XmlEx.src(sx, it) } ?: "<a:bodyPr/>"
        val lst = kids.firstOrNull { it.name == "a:lstStyle" }?.let { XmlEx.src(sx, it) } ?: "<a:lstStyle/>"
        val firstP = kids.firstOrNull { it.name == "a:p" }
        val pPr = firstP?.let { Xml.child(sx, it, "a:pPr") }?.let { XmlEx.src(sx, it) } ?: ""
        val rPrSrc = firstP?.let { p -> Xml.children(sx, p, "a:r").firstOrNull()?.let { Xml.child(sx, it, "a:rPr") } ?: Xml.child(sx, p, "a:endParaRPr") }
        val attrs = rPrSrc?.let { sx.substring(it.start + 1 + it.name.length, it.openEnd).trimEnd('>', '/') } ?: " lang=\"en-US\" sz=\"1600\""
        val inner = rPrSrc?.takeIf { !it.selfClosing }?.let { sx.substring(it.openEnd, it.closeStart) } ?: ""
        val paras = text.replace("\r\n", "\n").replace('\r', '\n').split('\n').joinToString("") { line ->
            if (line.isEmpty()) "<a:p>$pPr<a:endParaRPr$attrs>$inner</a:endParaRPr></a:p>"
            else "<a:p>$pPr<a:r><a:rPr$attrs>$inner</a:rPr><a:t>${Xml.escape(cleanText(line))}</a:t></a:r></a:p>"
        }
        val newBody = "<a:txBody>$bodyPr$lst$paras</a:txBody>"
        val out = if (body != null) sx.substring(0, body.start) + newBody + sx.substring(body.end)
        else sx.substring(0, tc.openEnd) + newBody + sx.substring(tc.openEnd)
        pkg.putText(part, out)
        slideChanged(part)
    }

    /** Rows of cell text of the table in graphic frame [e], and whether it is plain (no merged cells, text only). */
    private fun tableOf(sx: String, e: Xml.Elem): Pair<List<List<String>>, Boolean>? {
        if (e.name != "p:graphicFrame") return null
        val tbl = Xml.find(sx, e, "a:tbl") ?: return null
        var plain = true
        val rows = Xml.children(sx, tbl, "a:tr").map { tr ->
            Xml.children(sx, tr, "a:tc").map { tc ->
                val open = sx.substring(tc.start, tc.openEnd)
                if (open.contains("gridSpan=") || open.contains("rowSpan=") || open.contains("hMerge=") || open.contains("vMerge=") || tc.selfClosing) plain = false
                val body = Xml.child(sx, tc, "a:txBody")
                body?.let { b -> Xml.children(sx, b, "a:p").joinToString("\n") { paraText(sx, it) } } ?: ""
            }
        }
        if (rows.isEmpty() || rows.any { it.size != rows[0].size }) plain = false
        return rows to plain
    }

    /** Theme colours of the first slide master: dk1, lt1, dk2, lt2, accent1–6, hlink, folHlink → 0xRRGGBB. */
    fun colorScheme(): Map<String, Int> = firstMaster()?.let { themeColors(it) } ?: emptyMap()

    /**
     * Changes theme colours (same keys as [colorScheme]) in the theme of every slide master, so text, backgrounds and
     * shapes that use theme colours follow. Only the colour values inside `a:clrScheme` change.
     */
    fun setColorScheme(colors: Map<String, Int>) {
        val themes = LinkedHashSet<String>()
        for (p in slideParts()) relTarget(p, "/slideLayout")?.let { relTarget(it, "/slideMaster") }?.let { m -> relTarget(m, "/theme")?.let { themes.add(it) } }
        firstMaster()?.let { m -> relTarget(m, "/theme")?.let { themes.add(it) } }
        for (th in themes) {
            val tx = pkg.text(th) ?: continue
            val scheme = Xml.root(tx)?.let { Xml.find(tx, it, "a:clrScheme") } ?: continue
            val edits = ArrayList<Triple<Int, Int, String>>()
            for (c in Xml.children(tx, scheme)) {
                val rgb = colors[Xml.local(c.name)] ?: continue
                val v = Xml.children(tx, c).firstOrNull() ?: continue
                edits.add(Triple(v.start, v.end, "<a:srgbClr val=\"" + String.format("%06X", rgb and 0xFFFFFF) + "\"/>"))
            }
            if (edits.isNotEmpty()) pkg.putText(th, Xml.splice(tx, edits))
        }
        themeCache.clear()
        slideCache.clear()
        slidesCache = null
        changed = true
    }

    private fun firstMaster(): String? {
        val px = pkg.text(presPart) ?: return null
        val rels = Rels.of(pkg, presPart)
        return Xml.root(px)?.let { Xml.child(px, it, "p:sldMasterIdLst") }?.let { Xml.children(px, it).firstOrNull() }
            ?.let { e -> rels.firstOrNull { it.id == ridOf(px, e) } }?.let { OoxmlPackage.resolve(presPart, it.target) }
            ?: relTarget(presPart, "/slideMaster")
    }

    /** What is shown approximately or kept read-only. Counts are occurrences across the deck. */
    fun compat(): List<CompatNote> {
        val readOnly = LinkedHashMap<String, Int>()
        val approx = LinkedHashMap<String, Int>()
        val fonts = LinkedHashMap<String, Int>()
        for (part in slideParts()) {
            val sx = pkg.text(part) ?: continue
            for (e in topShapes(sx)) otherCode(sx, e)?.let { code ->
                // v0.2.5: a plain table is edited cell by cell, moved and resized; only merged / nested ones stay read-only
                if (code == "table" && tableOf(sx, e)?.second == true) return@let
                readOnly[code] = (readOnly[code] ?: 0) + 1
            }
            if (sx.contains("<a:fld ")) readOnly["field"] = (readOnly["field"] ?: 0) + Regex("<a:fld[\\s>]").findAll(sx).count()
            if (sx.contains("<p:transition")) approx["transition"] = (approx["transition"] ?: 0) + 1
            if (sx.contains("<p:timing")) approx["animation"] = (approx["animation"] ?: 0) + 1
            if (Regex("<p:bgPr>\\s*<a:(?:gradFill|blipFill|pattFill)").containsMatchIn(sx)) approx["background"] = (approx["background"] ?: 0) + 1
            for (m in Regex("<a:(?:latin|ea|cs|sym)\\b[^>]*\\btypeface=\"([^\"]+)\"").findAll(sx)) {
                val name = Xml.unescape(m.groupValues[1])
                if (!name.startsWith("+")) fonts[name] = (fonts[name] ?: 0) + 1
            }
        }
        val out = ArrayList<CompatNote>()
        for ((k, v) in readOnly) out.add(CompatNote(CompatKind.READ_ONLY, k, v))
        for ((k, v) in approx) out.add(CompatNote(CompatKind.APPROX, k, v))
        for ((k, v) in fonts) out.add(CompatNote(CompatKind.APPROX, "font:$k", v))
        return out
    }

    /**
     * The package as bytes. Untouched since open / the last save: exactly the bytes it was opened from. Otherwise
     * only the parts an edit had to change differ.
     */
    fun save(): ByteArray {
        val src = source
        if (!changed && src != null) return src.copyOf()
        val out = pkg.write()
        source = out
        changed = false
        return out.copyOf()
    }

    // ------------------------------------------------------------------------------------------------ structure

    private fun sldSz(attr: String): Long? {
        val px = pkg.text(presPart) ?: return null
        val root = Xml.root(px) ?: return null
        return Xml.child(px, root, "p:sldSz")?.let { Xml.attr(px, it, attr)?.toLongOrNull() }
    }

    /** Entries of the slide list that resolve to a slide part, in order: the `p:sldId` element and its part. */
    private fun slideEntries(px: String): List<Pair<Xml.Elem, String>> {
        val rels = Rels.of(pkg, presPart).associateBy { it.id }
        val out = ArrayList<Pair<Xml.Elem, String>>()
        val lst = Xml.root(px)?.let { Xml.child(px, it, "p:sldIdLst") } ?: return out
        for (e in Xml.children(px, lst, "p:sldId")) {
            val rel = rels[ridOf(px, e)] ?: continue
            val part = OoxmlPackage.resolve(presPart, rel.target)
            if (!rel.external && pkg.has(part)) out.add(e to part)
        }
        return out
    }

    private fun slideParts(): List<String> =
        slidePartsCache ?: slideEntries(pkg.text(presPart)!!).map { it.second }.also { slidePartsCache = it }

    /** Relationship id on [e], whatever prefix the file binds to the relationships namespace. */
    private fun ridOf(xml: String, e: Xml.Elem): String? {
        val a = Xml.attrs(xml, e)
        return a["r:id"] ?: a.entries.firstOrNull { it.key.endsWith(":id") }?.value
    }

    /** Namespace declaration to put on a new element that uses `r:id`, when the root does not bind `r` itself. */
    private fun rDecl(xml: String, root: Xml.Elem): String =
        if (Xml.attr(xml, root, "xmlns:r") == PptxTemplates.NS_R) "" else " xmlns:r=\"${PptxTemplates.NS_R}\""

    private fun slidePart(index: Int): String {
        val parts = slideParts()
        if (index !in parts.indices) throw IndexOutOfBoundsException("slide $index of ${parts.size}")
        return parts[index]
    }

    private fun relTarget(owner: String, typeSuffix: String): String? =
        Rels.of(pkg, owner).firstOrNull { !it.external && it.type.endsWith(typeSuffix) }
            ?.let { OoxmlPackage.resolve(owner, it.target) }?.takeIf { pkg.has(it) }

    private fun notesPartOf(slidePart: String): String? = relTarget(slidePart, "/notesSlide")

    private fun slideChanged(part: String) {
        slideCache.remove(part)
        slidesCache = null
        changed = true
    }

    private fun structureChanged() {
        slidePartsCache = null
        slidesCache = null
        changed = true
    }

    /** `dir/stemN.ext` with the smallest N above every N in use. */
    private fun freeName(dir: String, stem: String, ext: String): String {
        val re = Regex("^" + Regex.escape("$dir/$stem") + "(\\d+)" + Regex.escape(ext) + "$")
        val max = pkg.names().mapNotNull { re.find(it)?.groupValues?.get(1)?.toIntOrNull() }.maxOrNull() ?: 0
        return "$dir/$stem${max + 1}$ext"
    }

    /** Removes [part] and its relationships, then every part that only it (or what went with it) referred to. */
    private fun removePartTree(part: String, alsoOwned: String?) {
        val queue = ArrayList<String>()
        fun drop(p: String) {
            val targets = Rels.of(pkg, p).filter { !it.external }.map { OoxmlPackage.resolve(p, it.target) }
            pkg.remove(p)
            pkg.remove(OoxmlPackage.relsOf(p))
            ContentTypes.removeOverride(pkg, p)
            queue.addAll(targets)
        }
        drop(part)
        if (alsoOwned != null && pkg.has(alsoOwned)) {
            // the notes slide points back at its slide, so it would never look orphaned on its own
            val others = pkg.names().any { n ->
                val owner = XmlEx.ownerOfRels(n) ?: return@any false
                owner != alsoOwned && Rels.parse(pkg.text(n) ?: "").any { !it.external && OoxmlPackage.resolve(owner, it.target) == alsoOwned }
            }
            if (!others) drop(alsoOwned)
        }
        var i = 0
        while (i < queue.size) {
            val p = queue[i++]
            if (pkg.has(p) && p !in XmlEx.referencedParts(pkg)) drop(p)
        }
    }

    private fun pickLayout(kind: SlideLayoutKind): PartDoc? {
        val px = pkg.text(presPart)!!
        val presRels = Rels.of(pkg, presPart)
        val masterPart = Xml.root(px)?.let { Xml.child(px, it, "p:sldMasterIdLst") }?.let { Xml.children(px, it).firstOrNull() }
            ?.let { e -> presRels.firstOrNull { it.id == ridOf(px, e) } }?.let { OoxmlPackage.resolve(presPart, it.target) }
            ?: relTarget(presPart, "/slideMaster") ?: return null
        val mx = pkg.text(masterPart) ?: return null
        val mRels = Rels.of(pkg, masterPart)
        val ordered = ArrayList<String>()
        Xml.root(mx)?.let { Xml.child(mx, it, "p:sldLayoutIdLst") }?.let { lst ->
            for (e in Xml.children(mx, lst)) mRels.firstOrNull { it.id == ridOf(mx, e) }?.let { ordered.add(OoxmlPackage.resolve(masterPart, it.target)) }
        }
        for (r in mRels) if (r.type.endsWith("/slideLayout")) OoxmlPackage.resolve(masterPart, r.target).let { if (it !in ordered) ordered.add(it) }
        val docs = ordered.filter { pkg.has(it) }.map { doc(it) }
        if (docs.isEmpty()) return null
        fun type(d: PartDoc) = d.root?.let { Xml.attr(d.xml, it, "type") }
        fun has(d: PartDoc, vararg t: String) = d.phs.any { it.type in t }
        return when (kind) {
            SlideLayoutKind.TITLE ->
                docs.firstOrNull { type(it) == "title" } ?: docs.firstOrNull { has(it, "ctrTitle") } ?: docs.first()
            SlideLayoutKind.TITLE_BODY ->
                docs.firstOrNull { type(it) == "obj" } ?: docs.firstOrNull { type(it) == "tx" }
                    ?: docs.firstOrNull { has(it, "title") && has(it, "body", "obj") } ?: docs.first()
            SlideLayoutKind.TWO_COLUMN ->
                docs.firstOrNull { type(it) == "twoObj" } ?: docs.firstOrNull { type(it) == "twoTxTwoObj" } ?: pickLayout(SlideLayoutKind.TITLE_BODY)
            SlideLayoutKind.BLANK ->
                docs.firstOrNull { type(it) == "blank" } ?: pickLayout(SlideLayoutKind.TITLE_BODY)
        }
    }

    private fun createNotesSlide(slidePart: String): String {
        val master = relTarget(presPart, "/notesMaster") ?: createNotesMaster()
        val md = doc(master)
        fun phAttrs(type: String, fallback: String): String {
            val ph = md.phs.firstOrNull { it.type == type }?.let { Xml.find(md.xml, it.sp, "p:ph") } ?: return fallback
            return md.xml.substring(ph.start + "<p:ph".length, ph.openEnd).trimEnd('>').trimEnd('/').trim()
        }
        val base = OoxmlPackage.resolve(presPart, "notesSlides/x").substringBeforeLast('/')
        val part = freeName(base, "notesSlide", ".xml")
        pkg.putText(part, PptxTemplates.notesSlide(phAttrs("sldImg", "type=\"sldImg\""), phAttrs("body", "type=\"body\" idx=\"1\""), "<a:p/>"))
        pkg.putText(
            OoxmlPackage.relsOf(part),
            PptxTemplates.rels(PptxTemplates.REL + "/notesMaster" to XmlEx.relativize(part, master), PptxTemplates.REL + "/slide" to XmlEx.relativize(part, slidePart)),
        )
        ContentTypes.addOverride(pkg, part, PptxTemplates.CT_NOTES)
        Rels.add(pkg, slidePart, PptxTemplates.REL + "/notesSlide", XmlEx.relativize(slidePart, part))
        return part
    }

    /** Adds a notes master (with its own copy of the deck's theme) for decks that never had notes. */
    private fun createNotesMaster(): String {
        val base = OoxmlPackage.resolve(presPart, "x").substringBeforeLast('/', "")
        fun dir(sub: String) = if (base.isEmpty()) sub else "$base/$sub"
        val part = freeName(dir("notesMasters"), "notesMaster", ".xml")
        val themePart = freeName(dir("theme"), "theme", ".xml")
        val slideMaster = relTarget(presPart, "/slideMaster")
        val themeBytes = slideMaster?.let { relTarget(it, "/theme") }?.let { pkg.bytes(it) } ?: PptxTemplates.theme().toByteArray(Charsets.UTF_8)
        pkg.put(themePart, themeBytes)
        ContentTypes.addOverride(pkg, themePart, PptxTemplates.CT_THEME)
        pkg.putText(part, PptxTemplates.notesMaster())
        pkg.putText(OoxmlPackage.relsOf(part), PptxTemplates.rels(PptxTemplates.REL + "/theme" to XmlEx.relativize(part, themePart)))
        ContentTypes.addOverride(pkg, part, PptxTemplates.CT_NOTES_MASTER)
        val rid = Rels.add(pkg, presPart, PptxTemplates.REL + "/notesMaster", XmlEx.relativize(presPart, part))
        val px = pkg.text(presPart)!!
        val root = Xml.root(px) ?: throw IllegalStateException("presentation part is not readable")
        val pos = Xml.child(px, root, "p:sldMasterIdLst")?.end ?: root.openEnd
        val lst = "<p:notesMasterIdLst><p:notesMasterId r:id=\"$rid\"${rDecl(px, root)}/></p:notesMasterIdLst>"
        pkg.putText(presPart, px.substring(0, pos) + lst + px.substring(pos))
        return part
    }

    // ------------------------------------------------------------------------------------------------ reading

    private fun doc(part: String): PartDoc = docCache.getOrPut(part) {
        val xml = pkg.text(part) ?: ""
        val root = Xml.root(xml)
        val phs = ArrayList<Ph>()
        val tree = root?.let { Xml.find(xml, it, "p:spTree") }
        if (tree != null) {
            for (sp in Xml.children(xml, tree)) {
                if (sp.name != "p:sp" && sp.name != "p:pic" && sp.name != "p:graphicFrame") continue
                val ph = Xml.find(xml, sp, "p:ph") ?: continue
                phs.add(Ph(Xml.attr(xml, ph, "type") ?: "obj", Xml.attr(xml, ph, "idx") ?: "0", sp))
            }
        }
        PartDoc(part, xml, root, phs)
    }

    private fun topShapes(sx: String): List<Xml.Elem> {
        val tree = Xml.root(sx)?.let { Xml.find(sx, it, "p:spTree") } ?: return emptyList()
        return Xml.children(sx, tree).filter { it.name != "p:nvGrpSpPr" && it.name != "p:grpSpPr" && it.name != "p:extLst" }
    }

    private fun shapeId(sx: String, e: Xml.Elem): Int? = Xml.find(sx, e, "p:cNvPr")?.let { Xml.attr(sx, it, "id")?.toIntOrNull() }

    private fun maxShapeId(sx: String): Int =
        Regex("<p:cNvPr\\b[^>]*?\\sid=\"(\\d+)\"").findAll(sx).mapNotNull { it.groupValues[1].toIntOrNull() }.maxOrNull() ?: 1

    private fun masterType(t: String): String = when (t) {
        "title", "ctrTitle" -> "title"
        "dt", "ftr", "sldNum", "hdr", "sldImg" -> t
        else -> "body"
    }

    /** Layout and master placeholders a slide placeholder inherits from, nearest first. */
    private fun inheritedPhs(slidePart: String, type: String, idx: String): List<Pair<PartDoc, Ph>> {
        val out = ArrayList<Pair<PartDoc, Ph>>()
        val layoutPart = relTarget(slidePart, "/slideLayout") ?: return out
        val layout = doc(layoutPart)
        val lph = layout.phs.firstOrNull { it.idx == idx && masterType(it.type) == masterType(type) }
            ?: layout.phs.firstOrNull { it.type == type }
            ?: layout.phs.firstOrNull { masterType(it.type) == masterType(type) }
        if (lph != null) out.add(layout to lph)
        val masterPart = relTarget(layoutPart, "/slideMaster") ?: return out
        val master = doc(masterPart)
        master.phs.firstOrNull { it.type == masterType(type) }?.let { out.add(master to it) }
        return out
    }

    private fun masterOf(slidePart: String): PartDoc? =
        relTarget(slidePart, "/slideLayout")?.let { relTarget(it, "/slideMaster") }?.let { doc(it) }

    /** Where the text of [sp] takes inherited paragraph / run properties from, nearest first. */
    private fun styleChain(sx: String, sp: Xml.Elem, slidePart: String): List<StyleSrc> {
        val out = ArrayList<StyleSrc>()
        Xml.child(sx, sp, "p:txBody")?.let { tb -> Xml.child(sx, tb, "a:lstStyle")?.let { out.add(StyleSrc(sx, it)) } }
        val ph = Xml.find(sx, sp, "p:ph")
        val master = masterOf(slidePart)
        if (ph != null) {
            val type = Xml.attr(sx, ph, "type") ?: "obj"
            for ((d, p) in inheritedPhs(slidePart, type, Xml.attr(sx, ph, "idx") ?: "0")) {
                Xml.child(d.xml, p.sp, "p:txBody")?.let { tb -> Xml.child(d.xml, tb, "a:lstStyle")?.let { out.add(StyleSrc(d.xml, it)) } }
            }
            if (master?.root != null) {
                val name = when (masterType(type)) { "title" -> "p:titleStyle"; "body" -> "p:bodyStyle"; else -> "p:otherStyle" }
                Xml.child(master.xml, master.root, "p:txStyles")?.let { ts -> Xml.child(master.xml, ts, name)?.let { out.add(StyleSrc(master.xml, it)) } }
            }
        }
        val px = pkg.text(presPart)
        if (px != null) Xml.root(px)?.let { Xml.child(px, it, "p:defaultTextStyle") }?.let { out.add(StyleSrc(px, it)) }
        return out
    }

    private fun <T> fromChain(chain: List<StyleSrc>, level: Int, f: (String, Xml.Elem) -> T?): T? {
        val name = "a:lvl${level + 1}pPr"
        for (s in chain) {
            val lvl = Xml.child(s.xml, s.elem, name) ?: continue
            f(s.xml, lvl)?.let { return it }
        }
        return null
    }

    /** true / false when [pPr] itself turns a bullet on / off, null when it says nothing. */
    private fun explicitBullet(xml: String, pPr: Xml.Elem?): Boolean? {
        if (pPr == null) return null
        for (c in Xml.children(xml, pPr)) {
            when (c.name) {
                "a:buNone" -> return false
                "a:buChar", "a:buAutoNum", "a:buBlip" -> return true
            }
        }
        return null
    }

    private fun inheritedBullet(chain: List<StyleSrc>, level: Int): Boolean = fromChain(chain, level) { x, e -> explicitBullet(x, e) } ?: false

    private fun themeColors(masterPart: String): Map<String, Int> = themeCache.getOrPut(masterPart) {
        val out = HashMap<String, Int>()
        val tx = relTarget(masterPart, "/theme")?.let { pkg.text(it) }
        val scheme = tx?.let { t -> Xml.root(t)?.let { Xml.find(t, it, "a:clrScheme") } }
        if (tx != null && scheme != null) {
            for (c in Xml.children(tx, scheme)) {
                val v = Xml.children(tx, c).firstOrNull() ?: continue
                val hex = if (v.name == "a:sysClr") Xml.attr(tx, v, "lastClr") else Xml.attr(tx, v, "val")
                hex?.toIntOrNull(16)?.let { out[Xml.local(c.name)] = it }
            }
        }
        out
    }

    /** Colour of the `a:solidFill` directly inside [parent] (or of [parent] itself when it holds the colour element). */
    private fun colorOf(xml: String, parent: Xml.Elem, master: PartDoc?, direct: Boolean = false): Int? {
        val holder = if (direct) parent else Xml.child(xml, parent, "a:solidFill") ?: return null
        val c = Xml.children(xml, holder).firstOrNull() ?: return null
        return when (c.name) {
            "a:srgbClr" -> Xml.attr(xml, c, "val")?.toIntOrNull(16)
            "a:sysClr" -> Xml.attr(xml, c, "lastClr")?.toIntOrNull(16)
            "a:prstClr" -> when (Xml.attr(xml, c, "val")) { "black" -> 0x000000; "white" -> 0xFFFFFF; "red" -> 0xFF0000; "green" -> 0x008000; "blue" -> 0x0000FF; "gray", "grey" -> 0x808080; else -> null }
            "a:schemeClr" -> {
                val name = Xml.attr(xml, c, "val") ?: return null
                if (master?.root == null) return null
                val mapped = Xml.child(master.xml, master.root, "p:clrMap")?.let { Xml.attr(master.xml, it, name) } ?: name
                themeColors(master.part)[mapped]
            }
            else -> null
        }
    }

    /** null = the part sets no background; [NO_SOLID] = it sets one that is not a plain colour. */
    private fun backgroundOf(xml: String, root: Xml.Elem?, master: PartDoc?): Int? {
        val bg = root?.let { Xml.child(xml, it, "p:cSld") }?.let { Xml.child(xml, it, "p:bg") } ?: return null
        Xml.child(xml, bg, "p:bgPr")?.let { return colorOf(xml, it, master) ?: NO_SOLID }
        Xml.child(xml, bg, "p:bgRef")?.let { return colorOf(xml, it, master, direct = true) ?: NO_SOLID }
        return NO_SOLID
    }

    private fun paraText(xml: String, p: Xml.Elem): String {
        val sb = StringBuilder()
        for (c in Xml.children(xml, p)) {
            when (c.name) {
                "a:r", "a:fld" -> Xml.child(xml, c, "a:t")?.let { sb.append(Xml.text(xml, it)) }
                "a:br" -> sb.append('\n')
            }
        }
        return sb.toString()
    }

    /** Effective run formatting: what the run's own `a:rPr` says, else the style chain's `a:defRPr` at [level]. */
    private class RunFmt(val bold: Boolean, val italic: Boolean, val underline: Boolean, val strike: Boolean, val size: Int, val color: Int?)

    private fun runFmt(xml: String, rPr: Xml.Elem?, chain: List<StyleSrc>, level: Int, master: PartDoc?): RunFmt {
        fun attr(n: String): String? = rPr?.let { Xml.attr(xml, it, n) } ?: fromChain(chain, level) { x, l -> Xml.child(x, l, "a:defRPr")?.let { Xml.attr(x, it, n) } }
        fun on(v: String?) = v == "1" || v == "true"
        val u = attr("u"); val st = attr("strike")
        val sz = attr("sz")?.toFloatOrNull() ?: 1800f
        val color = rPr?.let { colorOf(xml, it, master) } ?: fromChain(chain, level) { x, l -> Xml.child(x, l, "a:defRPr")?.let { colorOf(x, it, master) } }
        return RunFmt(on(attr("b")), on(attr("i")), u != null && u != "none", st != null && st != "noStrike", Math.round(sz / 50f), color)
    }

    private fun spanOf(text: String, f: RunFmt, src: Int) = Span(text, f.bold, f.italic, f.underline, src, f.strike, f.color, null, f.size)

    private fun alignCode(v: String?): Int = when (v) { "ctr" -> 1; "r" -> 2; else -> 0 }

    private fun effectiveAlign(xml: String, pPr: Xml.Elem?, chain: List<StyleSrc>, level: Int): Int =
        alignCode(pPr?.let { Xml.attr(xml, it, "algn") } ?: fromChain(chain, level) { x, l -> Xml.attr(x, l, "algn") })

    /**
     * Paragraph [p] as the model shows it. [srcBase] = how many `a:r` of the same text body come before this paragraph,
     * so every run's [Span.src] counts all runs of the body.
     */
    private fun paraModel(xml: String, p: Xml.Elem, chain: List<StyleSrc>, master: PartDoc? = null, srcBase: Int = 0): TextPara {
        val pPr = Xml.child(xml, p, "a:pPr")
        val level = (pPr?.let { Xml.attr(xml, it, "lvl")?.toIntOrNull() } ?: 0).coerceIn(0, 8)
        val runs = ArrayList<Span>()
        var pending = StringBuilder()
        var n = srcBase
        for (c in Xml.children(xml, p)) {
            when (c.name) {
                "a:r", "a:fld" -> {
                    val t = Xml.child(xml, c, "a:t")?.let { Xml.text(xml, it) } ?: ""
                    val src = if (c.name == "a:r") n++ else -1
                    val text = pending.toString() + t
                    pending = StringBuilder()
                    if (text.isNotEmpty()) runs.add(spanOf(text, runFmt(xml, Xml.child(xml, c, "a:rPr"), chain, level, master), src))
                }
                "a:br" -> if (runs.isNotEmpty()) runs[runs.size - 1] = runs.last().let { it.copy(text = it.text + "\n") } else pending.append('\n')
            }
        }
        if (pending.isNotEmpty()) runs.add(spanOf(pending.toString(), runFmt(xml, Xml.child(xml, p, "a:endParaRPr"), chain, level, master), -1))
        return TextPara(paraText(xml, p), level, explicitBullet(xml, pPr) ?: inheritedBullet(chain, level), runs, effectiveAlign(xml, pPr, chain, level))
    }

    /** Paragraphs of a text body, with run indexes counted across the whole body. */
    private fun parasOf(xml: String, ps: List<Xml.Elem>, chain: List<StyleSrc>, master: PartDoc?): List<TextPara> {
        var base = 0
        return ps.map { p -> paraModel(xml, p, chain, master, base).also { base += Xml.children(xml, p, "a:r").size } }
    }

    private fun simpleText(xml: String, txBody: Xml.Elem): Boolean {
        for (p in Xml.children(xml, txBody, "a:p")) {
            for (c in Xml.children(xml, p)) {
                when (c.name) {
                    "a:pPr", "a:endParaRPr", "a:br" -> {}
                    "a:r" -> if (Xml.children(xml, c).any { it.name != "a:rPr" && it.name != "a:t" }) return false
                    else -> return false
                }
            }
        }
        return true
    }

    /** compat() code for a top-level element that is shown as a read-only placeholder; null for ordinary shapes. */
    private fun otherCode(sx: String, e: Xml.Elem): String? = when (e.name) {
        "p:grpSp" -> "group"
        "p:cxnSp" -> "connector"
        "p:graphicFrame" -> {
            val uri = Xml.find(sx, e, "a:graphicData")?.let { Xml.attr(sx, it, "uri") } ?: ""
            when {
                uri.endsWith("/chart") -> "chart"
                uri.endsWith("/table") -> "table"
                uri.endsWith("/diagram") -> "smart-graphic"
                uri.endsWith("/ole") -> "embedded-object"
                else -> "other"
            }
        }
        "p:pic" -> if (Xml.find(sx, e, "a:videoFile") != null || Xml.find(sx, e, "a:audioFile") != null) "media" else null
        "p:sp" -> null
        else -> "other"
    }

    private fun readSlide(part: String): SlideData {
        val sx = pkg.text(part) ?: return SlideData(emptyList(), "", null)
        val root = Xml.root(sx)
        val master = masterOf(part)
        val shapes = topShapes(sx).mapNotNull { readShape(sx, it, part, master) }

        var bg = backgroundOf(sx, root, master)
        if (bg == null) relTarget(part, "/slideLayout")?.let { doc(it) }?.let { bg = backgroundOf(it.xml, it.root, master) }
        if (bg == null && master != null) bg = backgroundOf(master.xml, master.root, master)

        val notes = notesPartOf(part)?.let { pkg.text(it) }?.let { nx ->
            notesBody(nx)?.let { sp -> Xml.child(nx, sp, "p:txBody") }?.let { tb -> Xml.children(nx, tb, "a:p").joinToString("\n") { paraText(nx, it) } }
        } ?: ""
        return SlideData(shapes, notes, bg?.takeIf { it != NO_SOLID })
    }

    private fun notesBody(nx: String): Xml.Elem? =
        topShapes(nx).firstOrNull { e -> e.name == "p:sp" && Xml.find(nx, e, "p:ph")?.let { Xml.attr(nx, it, "type") } == "body" }

    private fun boxOf(xml: String, e: Xml.Elem): LongArray? {
        val xf = when (e.name) {
            "p:sp", "p:pic", "p:cxnSp" -> Xml.child(xml, e, "p:spPr")?.let { Xml.child(xml, it, "a:xfrm") }
            "p:grpSp" -> Xml.child(xml, e, "p:grpSpPr")?.let { Xml.child(xml, it, "a:xfrm") }
            "p:graphicFrame" -> Xml.child(xml, e, "p:xfrm")
            else -> Xml.find(xml, e, "a:xfrm") ?: Xml.find(xml, e, "p:xfrm")
        } ?: return null
        val off = Xml.child(xml, xf, "a:off")
        val ext = Xml.child(xml, xf, "a:ext")
        return longArrayOf(
            off?.let { Xml.attr(xml, it, "x")?.toLongOrNull() } ?: 0L, off?.let { Xml.attr(xml, it, "y")?.toLongOrNull() } ?: 0L,
            ext?.let { Xml.attr(xml, it, "cx")?.toLongOrNull() } ?: 0L, ext?.let { Xml.attr(xml, it, "cy")?.toLongOrNull() } ?: 0L,
        )
    }

    private fun readShape(sx: String, e: Xml.Elem, part: String, master: PartDoc?): Shape? {
        val shape = readShapeBase(sx, e, part, master) ?: return null
        val ln = (if (e.name == "p:sp" || e.name == "p:pic" || e.name == "p:cxnSp") Xml.child(sx, e, "p:spPr") else null)?.let { Xml.child(sx, it, "a:ln") }
        val rot = ownXfrm(sx, e)?.let { Xml.attr(sx, it, "rot")?.toLongOrNull() } ?: 0L
        return shape.copy(
            lineRgb = ln?.let { colorOf(sx, it, master) }, lineWidthEmu = ln?.let { Xml.attr(sx, it, "w")?.toLongOrNull() },
            rotation = Math.floorMod(Math.round(rot / 60000.0).toInt(), 360),
        )
    }

    /** The `a:xfrm` / `p:xfrm` the top-level element [e] carries itself; null when it inherits its box. */
    private fun ownXfrm(sx: String, e: Xml.Elem): Xml.Elem? = when (e.name) {
        "p:sp", "p:pic", "p:cxnSp" -> Xml.child(sx, e, "p:spPr")?.let { Xml.child(sx, it, "a:xfrm") }
        "p:grpSp" -> Xml.child(sx, e, "p:grpSpPr")?.let { Xml.child(sx, it, "a:xfrm") }
        "p:graphicFrame" -> Xml.child(sx, e, "p:xfrm")
        else -> null
    }

    private fun readShapeBase(sx: String, e: Xml.Elem, part: String, master: PartDoc?): Shape? {
        val id = shapeId(sx, e) ?: return null
        val ph = if (e.name == "p:sp" || e.name == "p:pic" || e.name == "p:graphicFrame") Xml.find(sx, e, "p:ph") else null
        val phType = ph?.let { Xml.attr(sx, it, "type") ?: "obj" }
        val phIdx = ph?.let { Xml.attr(sx, it, "idx") ?: "0" }

        var box = boxOf(sx, e)
        if (box == null && phType != null) {
            for ((d, p) in inheritedPhs(part, phType, phIdx ?: "0")) { box = boxOf(d.xml, p.sp); if (box != null) break }
        }
        val b = box ?: longArrayOf(0, 0, 0, 0)

        val txBody = if (e.name == "p:sp") Xml.child(sx, e, "p:txBody") else null
        val spPr = if (e.name == "p:sp") Xml.child(sx, e, "p:spPr") else null
        val geom = spPr?.let { Xml.child(sx, it, "a:prstGeom") }?.let { Xml.attr(sx, it, "prst") }
        val fill = spPr?.let { colorOf(sx, it, master) }
        var imagePart: String? = null
        val kind: ShapeKind
        var editable = false
        when (e.name) {
            "p:sp" -> {
                kind = when {
                    phType == "title" || phType == "ctrTitle" -> ShapeKind.TITLE
                    phType == "body" || phType == "subTitle" || phType == "obj" -> ShapeKind.BODY
                    phType in FOOTER_TYPES -> ShapeKind.TEXT
                    phType != null -> ShapeKind.OTHER
                    txBody != null -> ShapeKind.TEXT
                    else -> ShapeKind.OTHER
                }
                editable = kind != ShapeKind.OTHER && (if (txBody != null) simpleText(sx, txBody) else kind != ShapeKind.TEXT)
            }
            "p:pic" -> {
                if (otherCode(sx, e) != null) kind = ShapeKind.OTHER else {
                    kind = ShapeKind.PICTURE
                    val rid = Xml.find(sx, e, "a:blip")?.let { Xml.attr(sx, it, "r:embed") }
                    imagePart = Rels.of(pkg, part).firstOrNull { it.id == rid && !it.external }?.let { OoxmlPackage.resolve(part, it.target) }?.takeIf { pkg.has(it) }
                }
            }
            "p:graphicFrame" -> kind = if (otherCode(sx, e) == "chart") ShapeKind.CHART else ShapeKind.OTHER
            else -> kind = ShapeKind.OTHER
        }
        val table = tableOf(sx, e)
        if (table != null) return Shape(id, kind, b[0], b[1], b[2], b[3], emptyList(), null, false, null, 0, null, false, null, null, table.first, table.second)

        if (txBody == null) {
            val isText = kind == ShapeKind.TITLE || kind == ShapeKind.BODY || kind == ShapeKind.TEXT
            val font = if (isText) (fromChain(styleChain(sx, e, part), 0) { x, l -> Xml.child(x, l, "a:defRPr")?.let { Xml.attr(x, it, "sz")?.toFloatOrNull() } } ?: 1800f) / 100f else null
            return Shape(id, kind, b[0], b[1], b[2], b[3], emptyList(), font, false, null, 0, imagePart, editable, geom, fill)
        }

        val chain = styleChain(sx, e, part)
        val ps = Xml.children(sx, txBody, "a:p")
        val paras = parasOf(sx, ps, chain, master)
        val first = ps.firstOrNull { p -> Xml.children(sx, p).any { it.name == "a:r" || it.name == "a:fld" } } ?: ps.firstOrNull()
        val pPr = first?.let { Xml.child(sx, it, "a:pPr") }
        val level = (pPr?.let { Xml.attr(sx, it, "lvl")?.toIntOrNull() } ?: 0).coerceIn(0, 8)
        val rPr = first?.let { p ->
            Xml.children(sx, p).firstOrNull { it.name == "a:r" || it.name == "a:fld" }?.let { Xml.child(sx, it, "a:rPr") } ?: Xml.child(sx, p, "a:endParaRPr")
        }
        fun def(x: String, l: Xml.Elem) = Xml.child(x, l, "a:defRPr")
        val sz = rPr?.let { Xml.attr(sx, it, "sz")?.toFloatOrNull() } ?: fromChain(chain, level) { x, l -> def(x, l)?.let { Xml.attr(x, it, "sz")?.toFloatOrNull() } } ?: 1800f
        val boldAttr = rPr?.let { Xml.attr(sx, it, "b") } ?: fromChain(chain, level) { x, l -> def(x, l)?.let { Xml.attr(x, it, "b") } }
        val color = rPr?.let { colorOf(sx, it, master) } ?: fromChain(chain, level) { x, l -> def(x, l)?.let { colorOf(x, it, master) } }
        val algn = pPr?.let { Xml.attr(sx, it, "algn") } ?: fromChain(chain, level) { x, l -> Xml.attr(x, l, "algn") }
        return Shape(
            id, kind, b[0], b[1], b[2], b[3], paras, sz / 100f, boldAttr == "1" || boldAttr == "true", color,
            when (algn) { "ctr" -> 1; "r" -> 2; else -> 0 }, imagePart, editable, geom, fill,
        )
    }

    // ------------------------------------------------------------------------------------------------ writing

    private fun cleanText(s: String): String = XmlEx.clean(s.replace("\r\n", "\n").replace('\r', '\n').replace('\u000B', '\n'))

    private fun insertIntoTree(xml: String, tree: Xml.Elem, shapeXml: String): String {
        if (tree.selfClosing) return Xml.appendInto(xml, tree, shapeXml)
        val pos = Xml.children(xml, tree).lastOrNull { it.name == "p:extLst" }?.start ?: tree.closeStart
        return xml.substring(0, pos) + shapeXml + xml.substring(pos)
    }

    /** True when [want] asks for nothing [orig] does not already have (null runs / align = as it was). */
    private fun sameAs(orig: TextPara, want: TextPara): Boolean =
        orig.text == want.text && orig.level == want.level && orig.bullet == want.bullet &&
            (want.runs == null || want.runs == orig.runs) && (want.align == null || want.align == orig.align)

    /** Returns [xml] with the paragraphs of shape [sp] replaced by [paras]; nothing outside them changes. */
    private fun replaceText(xml: String, sp: Xml.Elem, paras: List<TextPara>, chain: List<StyleSrc>, master: PartDoc?): String {
        val txBody = Xml.child(xml, sp, "p:txBody")
        val ps = txBody?.let { Xml.children(xml, it, "a:p") } ?: emptyList()
        val models = parasOf(xml, ps, chain, master)
        val orig = ps.mapIndexed { i, p ->
            val firstRun = Xml.children(xml, p).firstOrNull { it.name == "a:r" }
            OrigPara(
                src = XmlEx.src(xml, p),
                pPr = Xml.child(xml, p, "a:pPr")?.let { XmlEx.src(xml, it) } ?: "",
                rPr = firstRun?.let { Xml.child(xml, it, "a:rPr") }?.let { XmlEx.src(xml, it) } ?: "",
                endRPr = Xml.child(xml, p, "a:endParaRPr")?.let { XmlEx.src(xml, it) } ?: "",
                model = models[i],
            )
        }
        // the a:rPr of every run of the body, in order: what a span's src points at
        val runRPr = ps.flatMap { p -> Xml.children(xml, p, "a:r").map { r -> Xml.child(xml, r, "a:rPr")?.let { XmlEx.src(xml, it) } ?: "" } }
        val want = (if (paras.isEmpty()) listOf(TextPara("")) else paras).map { tp ->
            // runs whose text no longer matches the paragraph's (a caller changed only the text): the edit is applied to them, so the formatting follows
            val given = tp.runs?.let { r -> if (r.joinToString("") { it.text } == tp.text) r else RichText.applyEdit(r, tp.text) }
            val runs = given?.takeIf { it.isNotEmpty() || tp.text.isEmpty() }?.map { it.copy(text = cleanText(it.text)) }?.filter { it.text.isNotEmpty() }
            tp.copy(text = runs?.joinToString("") { it.text } ?: cleanText(tp.text), level = tp.level.coerceIn(0, 8), runs = runs)
        }
        val sb = StringBuilder()
        want.forEachIndexed { i, tp ->
            val same = orig.getOrNull(i)
            if (same != null && sameAs(same.model, tp)) { sb.append(same.src); return@forEachIndexed }
            val base = same ?: orig.lastOrNull()
            var pPr = withLevel(base?.pPr ?: "", tp.level)
            pPr = withBullet(pPr, tp.bullet, tp.level, chain)
            if (tp.align != null) pPr = withAlign(pPr, tp.align, tp.level, chain)
            val rPr = base?.rPr?.takeIf { it.isNotEmpty() } ?: base?.endRPr?.takeIf { it.isNotEmpty() }?.let { rename(it, "a:rPr") } ?: ""
            val endRPr = base?.endRPr?.takeIf { it.isNotEmpty() } ?: (if (tp.text.isEmpty() && rPr.isNotEmpty()) rename(rPr, "a:endParaRPr") else "")
            sb.append("<a:p>").append(pPr)
            val runs = tp.runs
            if (runs == null) {
                tp.text.split('\n').forEachIndexed { k, line ->
                    if (k > 0) sb.append("<a:br/>")
                    if (line.isNotEmpty()) sb.append("<a:r>").append(rPr).append("<a:t>").append(Xml.escape(line)).append("</a:t></a:r>")
                }
            } else {
                for (span in runs) {
                    val start = if (span.src >= 0) runRPr.getOrNull(span.src) ?: rPr else rPr
                    val r = reconcileRPr(start, span, chain, tp.level, master)
                    span.text.split('\n').forEachIndexed { k, line ->
                        if (k > 0) sb.append("<a:br/>")
                        if (line.isNotEmpty()) sb.append("<a:r>").append(r).append("<a:t>").append(Xml.escape(line)).append("</a:t></a:r>")
                    }
                }
            }
            sb.append(endRPr).append("</a:p>")
        }
        return when {
            txBody == null -> {
                // a placeholder that never held text: add the text body after the shape properties (and style)
                val after = (Xml.child(xml, sp, "p:style") ?: Xml.child(xml, sp, "p:spPr") ?: Xml.child(xml, sp, "p:nvSpPr"))?.end ?: sp.closeStart
                xml.substring(0, after) + "<p:txBody><a:bodyPr/><a:lstStyle/>$sb</p:txBody>" + xml.substring(after)
            }
            ps.isEmpty() -> Xml.appendInto(xml, txBody, sb.toString())
            else -> xml.substring(0, ps.first().start) + sb + xml.substring(ps.last().end)
        }
    }

    /**
     * [start] (an `a:rPr` as text, or "") changed only where [span] differs from what it gives at [level]: b, i, u,
     * strike, sz and the run's own solid fill. Everything else in it stays as written. A span size of null removes
     * the run's own size (the paragraph's applies); a colour of null removes the run's own fill.
     */
    private fun reconcileRPr(start: String, span: Span, chain: List<StyleSrc>, level: Int, master: PartDoc?): String {
        var r = start.ifEmpty { "<a:rPr/>" }
        if (Xml.root(r) == null) return start
        fun fmt(x: String) = runFmt(x, Xml.root(x), chain, level, master)
        fun withAttr(x: String, name: String, v: String?): String { val root = Xml.root(x)!!; return XmlEx.withAttr(x, root, name, v) + x.substring(root.openEnd) }
        // a value the run no longer matches: drop the run's own attribute first; set it only when what is inherited still differs
        fun toggle(name: String, want: Boolean, get: (RunFmt) -> Boolean, on: String, off: String) {
            if (get(fmt(r)) == want) return
            r = withAttr(r, name, null)
            if (get(fmt(r)) != want) r = withAttr(r, name, if (want) on else off)
        }
        toggle("b", span.bold, { it.bold }, "1", "0")
        toggle("i", span.italic, { it.italic }, "1", "0")
        toggle("u", span.underline, { it.underline }, "sng", "none")
        toggle("strike", span.strike, { it.strike }, "sngStrike", "noStrike")
        val sz = span.size
        if (sz == null) r = withAttr(r, "sz", null)
        else if (fmt(r).size != sz) { r = withAttr(r, "sz", null); if (fmt(r).size != sz) r = withAttr(r, "sz", (sz * 50).toString()) }
        if (span.color != fmt(r).color) {
            r = XmlEx.removeChildren(r, FILL_ELEMS)
            val c = span.color
            if (c != null && fmt(r).color != c) r = XmlEx.insertChild(r, "<a:solidFill><a:srgbClr val=\"${hex(c)}\"/></a:solidFill>", "a:solidFill", RPR_ORDER)
        }
        Xml.root(r)?.let { root -> if (!root.selfClosing && XmlEx.isEmpty(r)) r = r.substring(0, root.openEnd - 1).trimEnd() + "/>" }
        if (start.isEmpty() && r == "<a:rPr/>") return ""
        return r
    }

    private fun hex(rgb: Int) = "%06X".format(rgb and 0xFFFFFF)

    private fun withAlign(pPrIn: String, align: Int, level: Int, chain: List<StyleSrc>): String {
        val cur = effectiveAlign(pPrIn, if (pPrIn.isEmpty()) null else Xml.root(pPrIn), chain, level)
        if (cur == align) return pPrIn
        val pPr = pPrIn.ifEmpty { "<a:pPr/>" }
        val root = Xml.root(pPr) ?: return pPrIn
        return XmlEx.withAttr(pPr, root, "algn", when (align) { 1 -> "ctr"; 2 -> "r"; else -> "l" }) + pPr.substring(root.openEnd)
    }

    /** Solid fill of a drawn shape (p22); null = no fill. Only the shape's own `p:spPr` changes. */
    fun setShapeFill(slide: Int, shapeId: Int, rgb: Int?) {
        val part = slidePart(slide)
        val sx = pkg.text(part)!!
        val e = topShapes(sx).firstOrNull { shapeId(sx, it) == shapeId } ?: throw IllegalArgumentException("slide $slide has no shape $shapeId")
        if (e.name != "p:sp") throw IllegalStateException("shape $shapeId has no fill of its own")
        val spPr = Xml.child(sx, e, "p:spPr") ?: throw IllegalStateException("shape $shapeId has no shape properties")
        val fill = if (rgb == null) "<a:noFill/>" else "<a:solidFill><a:srgbClr val=\"${hex(rgb)}\"/></a:solidFill>"
        var pr = XmlEx.removeChildren(XmlEx.src(sx, spPr), FILL_ELEMS)
        pr = XmlEx.insertChild(pr, fill, if (rgb == null) "a:noFill" else "a:solidFill", SPPR_ORDER)
        pkg.putText(part, sx.substring(0, spPr.start) + pr + sx.substring(spPr.end))
        slideChanged(part)
    }

    /** Outline of a shape, connector or picture (p22): colour (null = no line) and width in EMU (null = keep the width it has). */
    fun setShapeLine(slide: Int, shapeId: Int, rgb: Int?, widthEmu: Long?) {
        val part = slidePart(slide)
        val sx = pkg.text(part)!!
        val e = topShapes(sx).firstOrNull { shapeId(sx, it) == shapeId } ?: throw IllegalArgumentException("slide $slide has no shape $shapeId")
        if (e.name != "p:sp" && e.name != "p:cxnSp" && e.name != "p:pic") throw IllegalStateException("shape $shapeId has no outline of its own")
        val spPr = Xml.child(sx, e, "p:spPr") ?: throw IllegalStateException("shape $shapeId has no shape properties")
        val prXml = XmlEx.src(sx, spPr)
        val fill = if (rgb == null) "<a:noFill/>" else "<a:solidFill><a:srgbClr val=\"${hex(rgb)}\"/></a:solidFill>"
        val lnEl = XmlEx.childOf(prXml, "a:ln")
        var ln = lnEl?.let { XmlEx.src(prXml, it) } ?: "<a:ln/>"
        ln = XmlEx.removeChildren(ln, FILL_ELEMS)
        ln = XmlEx.insertChild(ln, fill, if (rgb == null) "a:noFill" else "a:solidFill", LN_ORDER)
        if (widthEmu != null) {
            val root = Xml.root(ln)!!
            ln = XmlEx.withAttr(ln, root, "w", widthEmu.coerceIn(0L, 20116800L).toString()) + ln.substring(root.openEnd)
        }
        val pr = if (lnEl != null) prXml.substring(0, lnEl.start) + ln + prXml.substring(lnEl.end) else XmlEx.insertChild(prXml, ln, "a:ln", SPPR_ORDER)
        pkg.putText(part, sx.substring(0, spPr.start) + pr + sx.substring(spPr.end))
        slideChanged(part)
    }

    /**
     * Rotates a top-level object to [degrees] (normalised to 0..359; p24): the `rot` of its own transform, 0 removes it.
     * An object that inherits its box (a placeholder) is given one of its own first.
     */
    fun setShapeRotation(slide: Int, shapeId: Int, degrees: Int) {
        val part = slidePart(slide)
        val deg = Math.floorMod(degrees, 360)
        var sx = pkg.text(part)!!
        var e = topShapes(sx).firstOrNull { shapeId(sx, it) == shapeId } ?: throw IllegalArgumentException("slide $slide has no shape $shapeId")
        if (ownXfrm(sx, e) == null) {
            if (deg == 0) return
            val sh = slides[slide].shapes.firstOrNull { it.id == shapeId } ?: throw IllegalArgumentException("slide $slide has no shape $shapeId")
            setShapeBox(slide, shapeId, sh.x, sh.y, sh.w, sh.h)
            sx = pkg.text(part)!!
            e = topShapes(sx).first { shapeId(sx, it) == shapeId }
        }
        val xf = ownXfrm(sx, e) ?: throw IllegalStateException("shape $shapeId cannot be rotated")
        val tag = XmlEx.withAttr(sx, xf, "rot", if (deg == 0) null else (deg * 60000L).toString())
        if (tag == sx.substring(xf.start, xf.openEnd)) return
        pkg.putText(part, sx.substring(0, xf.start) + tag + sx.substring(xf.openEnd))
        slideChanged(part)
    }

    private fun rename(elemXml: String, name: String): String {
        val root = Xml.root(elemXml) ?: return ""
        val head = "<" + name + elemXml.substring(root.start + 1 + root.name.length, root.openEnd)
        return if (root.selfClosing) head else head + elemXml.substring(root.openEnd, root.closeStart) + "</" + name + ">"
    }

    private fun withLevel(pPr: String, level: Int): String {
        if (pPr.isEmpty()) return if (level == 0) "" else "<a:pPr lvl=\"$level\"/>"
        val root = Xml.root(pPr) ?: return pPr
        val cur = Xml.attr(pPr, root, "lvl")?.toIntOrNull() ?: 0
        if (cur == level) return pPr
        return XmlEx.withAttr(pPr, root, "lvl", if (level == 0) null else level.toString()) + pPr.substring(root.openEnd)
    }

    private fun withBullet(pPrIn: String, want: Boolean, level: Int, chain: List<StyleSrc>): String {
        var pPr = pPrIn
        val cur = (if (pPr.isEmpty()) null else explicitBullet(pPr, Xml.root(pPr))) ?: inheritedBullet(chain, level)
        if (cur == want) return pPr
        if (pPr.isNotEmpty()) pPr = XmlEx.removeChildren(pPr, BULLET_ELEMS)
        if (inheritedBullet(chain, level) == want) return pPr
        if (pPr.isEmpty()) pPr = "<a:pPr/>"
        pPr = XmlEx.insertChild(pPr, if (want) "<a:buChar char=\"•\"/>" else "<a:buNone/>", if (want) "a:buChar" else "a:buNone", APPR_ORDER)
        val root = Xml.root(pPr) ?: return pPr
        if (Xml.attr(pPr, root, "marL") == null && Xml.attr(pPr, root, "indent") == null) {
            var tag = pPr.substring(root.start, root.openEnd)
            tag = XmlEx.tagWithAttr(tag, "marL", if (want) (342900 + 457200 * level).toString() else "0")
            tag = XmlEx.tagWithAttr(tag, "indent", if (want) "-342900" else "0")
            pPr = tag + pPr.substring(root.openEnd)
        }
        return pPr
    }

    companion object {
        /** Opens an existing deck. Nothing is modified until an edit method is called. */
        fun open(bytes: ByteArray): PptxDeck = PptxDeck(OoxmlPackage.read(bytes), bytes.copyOf())

        /** A new 16:9 deck with one empty title slide and two layouts (title, title and content). */
        fun blank(): PptxDeck = PptxDeck(PptxTemplates.blankPackage(), null)

        private const val NO_SOLID = -1
        private val CNVPR_ID = Regex("(<p:cNvPr\\b[^>]*?\\sid=\")(\\d+)\"")
        private val CXN_ID = Regex("(<a:(?:stCxn|endCxn)\\b[^>]*?\\sid=\")(\\d+)\"")
        private val R_ATTR = Regex("(?<![\\w:.-])([A-Za-z_][\\w.-]*):(embed|link|id|pict|dm|lo|qs|cs)=\"([^\"]*)\"")
        private val NS_DECL = Regex("xmlns:([\\w.-]+)=\"([^\"]*)\"")
        /** Relationship types a copied object may share with its original (everything else is a part only it owns). */
        private val COPYABLE_REL = listOf("/image", "/hyperlink", "/media", "/video", "/audio", "/slide")
        private val FOOTER_TYPES = setOf("dt", "ftr", "sldNum", "hdr")
        private val BULLET_ELEMS = setOf("a:buNone", "a:buChar", "a:buAutoNum", "a:buBlip")
        private val FILL_ELEMS = setOf("a:noFill", "a:solidFill", "a:gradFill", "a:blipFill", "a:pattFill", "a:grpFill")
        private val RPR_ORDER = listOf(
            "a:ln", "a:noFill", "a:solidFill", "a:gradFill", "a:blipFill", "a:pattFill", "a:grpFill", "a:effectLst", "a:effectDag",
            "a:highlight", "a:uLnTx", "a:uLn", "a:uFillTx", "a:uFill", "a:latin", "a:ea", "a:cs", "a:sym", "a:hlinkClick",
            "a:hlinkMouseOver", "a:rtl", "a:extLst",
        )
        private val SPPR_ORDER = listOf(
            "a:xfrm", "a:custGeom", "a:prstGeom", "a:noFill", "a:solidFill", "a:gradFill", "a:blipFill", "a:pattFill", "a:grpFill",
            "a:ln", "a:effectLst", "a:effectDag", "a:scene3d", "a:sp3d", "a:extLst",
        )
        private val LN_ORDER = listOf(
            "a:noFill", "a:solidFill", "a:gradFill", "a:pattFill", "a:prstDash", "a:custDash", "a:round", "a:bevel", "a:miter",
            "a:headEnd", "a:tailEnd", "a:extLst",
        )
        private val APPR_ORDER = listOf(
            "a:lnSpc", "a:spcBef", "a:spcAft", "a:buClrTx", "a:buClr", "a:buSzTx", "a:buSzPct", "a:buSzPts", "a:buFontTx",
            "a:buFont", "a:buNone", "a:buAutoNum", "a:buChar", "a:buBlip", "a:tabLst", "a:defRPr", "a:extLst",
        )
    }
}
