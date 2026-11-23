package io.embodit.spatialoffice.core.xlsx

import io.embodit.spatialoffice.core.pkg.ContentTypes
import io.embodit.spatialoffice.core.pkg.OoxmlPackage
import io.embodit.spatialoffice.core.pkg.Rels
import io.embodit.spatialoffice.core.pkg.Xml
import io.embodit.spatialoffice.core.review.CommentKind
import io.embodit.spatialoffice.core.review.ReviewComment
import io.embodit.spatialoffice.core.review.ReviewReply
import java.util.Locale
import java.util.UUID

/**
 * M5 (s35): comments and notes on cells.
 *
 * A spreadsheet file carries two kinds:
 *  - **threads** in a threaded-comments part per sheet (one item per message; replies name their first message with
 *    `parentId`; `done="1"` on the first message = resolved) with the authors in one person list for the workbook;
 *  - **notes** in the sheet's `comments` part (text only) with one shape each in the sheet's legacy drawing.
 * So that a program which only knows notes still shows something, every thread also has an entry in the `comments`
 * part on the same cell (author `tc={id of the thread}`, text = the thread as plain text) and a shape for it.
 *
 * The package is the only store: every change is spliced into the text of the part it belongs to at once (only the
 * element that changed), and the lists handed out are read back from the parts and cached until the next change.
 * Parts are created only when the first comment of their kind is added to a sheet. The one thing that cannot be
 * written at once is the sheet's `<legacyDrawing>` element (the cell model holds positions into the sheet text): its
 * relationship id is handed to the workbook, which writes the element on save.
 */
internal class CommentStore(private val pkg: OoxmlPackage, private val host: Host) {

    interface Host {
        fun workbookPart(): String
        /** Parts of the sheets, by sheet index. */
        fun sheetParts(): List<String>
        /** Relationship id of the sheet's `<legacyDrawing>` (in the file or waiting to be written), or null. */
        fun legacyDrawingRid(sheet: Int): String?
        /** A legacy drawing was made for the sheet: write `<legacyDrawing r:id="[rid]"/>` into it on save. */
        fun pendingLegacyDrawing(sheet: Int, rid: String)
        fun changed()
    }

    // ---- what the parts hold, as ranges into their text ----

    private class TItem(
        val e: Xml.Elem, val id: String, val pos: Pair<Int, Int>?, val parent: String?, val personId: String?,
        val dT: String, val done: Boolean, val text: String,
    )

    private class LItem(val e: Xml.Elem, val pos: Pair<Int, Int>?, val authorId: Int, val textEl: Xml.Elem?)

    private class Legacy(val root: Xml.Elem, val authors: List<String>, val items: List<LItem>)

    private class VShape(val e: Xml.Elem, val row: Int, val col: Int, val rowEl: Xml.Elem, val colEl: Xml.Elem, val anchorEl: Xml.Elem?)

    private class NoteText(val body: String, val heading: String, val headingRun: Xml.Elem?)

    private class SheetModel(val list: List<ReviewComment>, val cells: Map<String, CommentKind>)

    private class Loc(val sheet: Int, val root: ReviewComment, val reply: ReviewReply?)

    private class Built(val sheets: List<SheetModel>, val byId: Map<String, Loc>)

    private var built: Built? = null

    /** Ids of notes: sheet part → (cell reference now → id). A note keeps its id while its cell moves. */
    private val noteIds = HashMap<String, HashMap<String, String>>()
    private val usedNoteIds = HashSet<String>()

    /** The lists are read again from the parts on the next question (after a change, or when the sheets changed). */
    fun invalidate() { built = null }

    // ---- finding the parts ----

    private fun relPart(owner: String, typeEnd: String): String? =
        Rels.of(pkg, owner).firstOrNull { !it.external && it.type.endsWith(typeEnd) }
            ?.let { OoxmlPackage.resolve(owner, it.target) }?.takeIf { pkg.has(it) }

    private fun threadPartOf(sheetPart: String): String? = relPart(sheetPart, "/threadedComment")
    private fun legacyPartOf(sheetPart: String): String? = relPart(sheetPart, "/comments")
    private fun personsPart(): String? = relPart(host.workbookPart(), "/person")

    private fun vmlPartOf(sheet: Int, sheetPart: String): String? {
        val rid = host.legacyDrawingRid(sheet) ?: return null
        return Rels.of(pkg, sheetPart).firstOrNull { it.id == rid && !it.external }
            ?.let { OoxmlPackage.resolve(sheetPart, it.target) }?.takeIf { pkg.has(it) }
    }

    // ---- reading ----

    private fun threadItems(xml: String): List<TItem> {
        val root = Xml.root(xml) ?: return emptyList()
        val out = ArrayList<TItem>()
        for (e in Xml.children(xml, root)) {
            if (Xml.local(e.name) != "threadedComment") continue
            val a = Xml.attrs(xml, e)
            val id = a["id"]?.takeIf { it.isNotEmpty() } ?: continue
            val text = XmlX.kid(xml, e, "text")?.let { decodeEscapes(Xml.text(xml, it)) } ?: ""
            out.add(TItem(e, id, a["ref"]?.let { A1.parse(it.trim()) }, a["parentId"]?.takeIf { it.isNotEmpty() }, a["personId"],
                a["dT"] ?: "", a["done"].let { it == "1" || it == "true" }, text))
        }
        return out
    }

    private fun legacy(xml: String): Legacy? {
        val root = Xml.root(xml) ?: return null
        val authors = XmlX.kid(xml, root, "authors")?.let { a -> XmlX.kids(xml, a, "author").map { decodeEscapes(Xml.text(xml, it)) } } ?: emptyList()
        val items = ArrayList<LItem>()
        XmlX.kid(xml, root, "commentList")?.let { list ->
            for (e in XmlX.kids(xml, list, "comment")) {
                val a = Xml.attrs(xml, e)
                items.add(LItem(e, a["ref"]?.let { A1.parse(it.trim()) }, a["authorId"]?.trim()?.toIntOrNull() ?: -1, XmlX.kid(xml, e, "text")))
            }
        }
        return Legacy(root, authors, items)
    }

    private fun richText(xml: String, el: Xml.Elem): String {
        val sb = StringBuilder()
        for (k in Xml.children(xml, el)) when (Xml.local(k.name)) {
            "t" -> sb.append(Xml.text(xml, k))
            "r" -> for (t in Xml.children(xml, k)) if (Xml.local(t.name) == "t") sb.append(Xml.text(xml, t))
        }
        return decodeEscapes(sb.toString())
    }

    /**
     * The text of a note without the heading most programs put in front of it (the author's name and a colon, usually
     * a bold run of its own, then a line break): the review pane shows the author separately.
     */
    private fun noteText(xml: String, textEl: Xml.Elem?, author: String): NoteText {
        if (textEl == null) return NoteText("", "", null)
        val full = richText(xml, textEl)
        if (author.isBlank()) return NoteText(full, "", null)
        val kids = Xml.children(xml, textEl)
        val first = kids.firstOrNull()
        if (first != null && Xml.local(first.name) == "r" && richText(xml, textEl.copy(openEnd = first.start, closeStart = first.end)).trim() == "$author:") {
            val rest = richText(xml, textEl.copy(openEnd = first.end))
            return NoteText(rest.removePrefix("\r").removePrefix("\n"), "", first)
        }
        for (h in listOf("$author:\r\n", "$author:\n")) if (full.startsWith(h)) return NoteText(full.substring(h.length), h, null)
        return NoteText(full, "", null)
    }

    private fun noteShapes(xml: String): List<VShape> {
        val root = Xml.root(xml) ?: return emptyList()
        val out = ArrayList<VShape>()
        for (e in Xml.children(xml, root)) {
            if (Xml.local(e.name) != "shape") continue
            val cd = XmlX.kid(xml, e, "ClientData") ?: continue
            if (Xml.attr(xml, cd, "ObjectType") != "Note") continue
            val rowEl = XmlX.kid(xml, cd, "Row") ?: continue
            val colEl = XmlX.kid(xml, cd, "Column") ?: continue
            val row = Xml.text(xml, rowEl).trim().toIntOrNull() ?: continue
            val col = Xml.text(xml, colEl).trim().toIntOrNull() ?: continue
            out.add(VShape(e, row, col, rowEl, colEl, XmlX.kid(xml, cd, "Anchor")))
        }
        return out
    }

    private fun persons(): Map<String, String> {
        val xml = personsPart()?.let { pkg.text(it) } ?: return emptyMap()
        val root = Xml.root(xml) ?: return emptyMap()
        val out = HashMap<String, String>()
        for (p in XmlX.kids(xml, root, "person")) {
            val a = Xml.attrs(xml, p)
            out[a["id"] ?: continue] = a["displayName"] ?: ""
        }
        return out
    }

    private fun noteId(sheetPart: String, sheet: Int, ref: String): String {
        val m = noteIds.getOrPut(sheetPart) { HashMap() }
        m[ref]?.let { return it }
        var id = "note:$sheet:$ref"
        var k = 2
        while (!usedNoteIds.add(id)) id = "note:$sheet:$ref#${k++}"
        m[ref] = id
        return id
    }

    private fun model(): Built {
        built?.let { return it }
        val people = persons()
        val models = ArrayList<SheetModel>()
        val byId = HashMap<String, Loc>()
        for ((si, sp) in host.sheetParts().withIndex()) {
            val list = ArrayList<Pair<Pair<Int, Int>, ReviewComment>>()
            val cells = HashMap<String, CommentKind>()
            threadPartOf(sp)?.let { pkg.text(it) }?.let { xml ->
                val items = threadItems(xml)
                val replies = items.filter { it.parent != null }.groupBy { it.parent!! }
                for (r in items) {
                    if (r.parent != null) continue
                    val pos = r.pos ?: continue
                    val ref = A1.name(pos.first, pos.second)
                    val c = ReviewComment(
                        id = r.id, kind = CommentKind.THREAD, author = people[r.personId] ?: "", date = CommentDates.fromFile(r.dT), text = r.text,
                        quote = ref, resolved = r.done, sheet = si, cell = ref,
                        replies = (replies[r.id] ?: emptyList()).map { ReviewReply(it.id, people[it.personId] ?: "", CommentDates.fromFile(it.dT), it.text) },
                    )
                    list.add(Pair(pos, c))
                    cells.putIfAbsent(ref, CommentKind.THREAD)
                }
            }
            legacyPartOf(sp)?.let { pkg.text(it) }?.let { xml ->
                val lg = legacy(xml) ?: return@let
                for (e in lg.items) {
                    val pos = e.pos ?: continue
                    val ref = A1.name(pos.first, pos.second)
                    if (cells.containsKey(ref)) continue // the note-form copy of a thread (or a second entry on one cell)
                    val raw = lg.authors.getOrNull(e.authorId) ?: ""
                    val author = if (TC_AUTHOR.matches(raw)) "" else raw
                    val c = ReviewComment(
                        id = noteId(sp, si, ref), kind = CommentKind.NOTE, author = author, date = "", text = noteText(xml, e.textEl, author).body,
                        quote = ref, sheet = si, cell = ref,
                    )
                    list.add(Pair(pos, c))
                    cells[ref] = CommentKind.NOTE
                }
            }
            list.sortWith(compareBy({ it.first.first }, { it.first.second }))
            for ((_, c) in list) {
                byId.putIfAbsent(c.id, Loc(si, c, null))
                for (r in c.replies) byId.putIfAbsent(r.id, Loc(si, c, r))
            }
            models.add(SheetModel(list.map { it.second }, cells))
        }
        return Built(models, byId).also { built = it }
    }

    fun comments(sheet: Int): List<ReviewComment> = model().sheets.getOrNull(sheet)?.list ?: emptyList()

    fun comments(): List<ReviewComment> = model().sheets.flatMap { it.list }

    fun commentCells(sheet: Int): Map<String, CommentKind> = model().sheets.getOrNull(sheet)?.cells ?: emptyMap()

    fun commentAt(sheet: Int, cell: String): ReviewComment? {
        val pos = A1.parse(cell.trim()) ?: return null
        val ref = A1.name(pos.first, pos.second)
        return comments(sheet).firstOrNull { it.cell == ref }
    }

    /** The comment with this id, or the thread that holds the reply with this id. */
    fun comment(id: String): ReviewComment? = model().byId[id]?.root

    private fun locate(id: String): Loc = model().byId[id] ?: throw IllegalArgumentException("no comment with id $id")

    // ---- changing: small tools ----

    private fun put(part: String, xml: String) { pkg.putText(part, xml) }

    private fun freePart(prefix: String, suffix: String, bare: Boolean = false): String {
        if (bare && !pkg.has(prefix + suffix)) return prefix + suffix
        var n = 1
        while (pkg.has("$prefix$n$suffix")) n++
        return "$prefix$n$suffix"
    }

    private fun newGuid(): String {
        val used = model().byId.keys
        while (true) {
            val g = "{" + UUID.randomUUID().toString().uppercase(Locale.ROOT) + "}"
            if (g !in used) return g
        }
    }

    private fun ensureThreadPart(sheetPart: String): String {
        threadPartOf(sheetPart)?.let { return it }
        val part = freePart("xl/threadedComments/threadedComment", ".xml")
        put(part, "$DECL<ThreadedComments xmlns=\"$NS_TC\" xmlns:x=\"$NS_MAIN\"></ThreadedComments>")
        ContentTypes.addOverride(pkg, part, CT_THREADED)
        Rels.add(pkg, sheetPart, REL_THREADED, relTarget(sheetPart, part))
        return part
    }

    /** Id of the person called [author] in the workbook's person list; added (with the list, when there is none) if new. */
    private fun personId(author: String): String {
        val wb = host.workbookPart()
        val part = personsPart() ?: run {
            val p = freePart("xl/persons/person", ".xml", bare = true)
            put(p, "$DECL<personList xmlns=\"$NS_TC\" xmlns:x=\"$NS_MAIN\"></personList>")
            ContentTypes.addOverride(pkg, p, CT_PERSON)
            Rels.add(pkg, wb, REL_PERSON, relTarget(wb, p))
            p
        }
        val xml = pkg.text(part)!!
        val root = Xml.root(xml) ?: throw IllegalStateException("the person list is not readable")
        val used = HashSet<String>()
        for (p in XmlX.kids(xml, root, "person")) {
            val a = Xml.attrs(xml, p)
            val id = a["id"] ?: continue
            if (a["displayName"] == author) return id
            used.add(id)
        }
        var id: String
        do { id = "{" + UUID.randomUUID().toString().uppercase(Locale.ROOT) + "}" } while (id in used)
        val p = XmlX.prefixOf(root.name)
        val name = Xml.escape(author)
        put(part, Xml.appendInto(xml, root, "<${p}person displayName=\"$name\" id=\"$id\" userId=\"$name\" providerId=\"None\"/>"))
        return id
    }

    private fun ensureLegacyPart(sheetPart: String): String {
        legacyPartOf(sheetPart)?.let { return it }
        val part = freePart("xl/comments", ".xml")
        put(part, "$DECL<comments xmlns=\"$NS_MAIN\"><authors></authors><commentList></commentList></comments>")
        ContentTypes.addOverride(pkg, part, CT_COMMENTS)
        Rels.add(pkg, sheetPart, REL_COMMENTS, relTarget(sheetPart, part))
        return part
    }

    /** The `comments` part text with an author list and a comment list (a part that came without them gets them). */
    private fun withLists(xml0: String): String {
        var xml = xml0
        var root = Xml.root(xml) ?: throw IllegalStateException("the comments part is not readable")
        val p = XmlX.prefixOf(root.name)
        if (XmlX.kid(xml, root, "authors") == null) {
            val tag = "<${p}authors></${p}authors>"
            xml = if (root.selfClosing) Xml.appendInto(xml, root, tag) else xml.substring(0, root.openEnd) + tag + xml.substring(root.openEnd)
            root = Xml.root(xml)!!
        }
        if (XmlX.kid(xml, root, "commentList") == null) {
            val at = XmlX.kid(xml, root, "authors")!!.end
            xml = xml.substring(0, at) + "<${p}commentList></${p}commentList>" + xml.substring(at)
        }
        return xml
    }

    private fun vmlIdmaps(): Set<Int> {
        val out = HashSet<Int>()
        for (n in pkg.names()) {
            if (!n.endsWith(".vml", ignoreCase = true)) continue
            val xml = pkg.text(n) ?: continue
            for (m in IDMAP.findAll(xml)) for (t in m.groupValues[1].split(',')) t.trim().toIntOrNull()?.let { out.add(it) }
        }
        return out
    }

    private fun ensureVml(sheet: Int, sheetPart: String): String {
        vmlPartOf(sheet, sheetPart)?.let { return it }
        val used = vmlIdmaps()
        var data = 1
        while (data in used) data++
        val part = freePart("xl/drawings/vmlDrawing", ".vml")
        put(part, "<xml xmlns:v=\"$NS_V\" xmlns:o=\"$NS_O\" xmlns:x=\"$NS_X\">" +
            "<o:shapelayout v:ext=\"edit\"><o:idmap v:ext=\"edit\" data=\"$data\"/></o:shapelayout>$SHAPETYPE</xml>")
        ContentTypes.addDefault(pkg, "vml", CT_VML)
        host.pendingLegacyDrawing(sheet, Rels.add(pkg, sheetPart, REL_VML, relTarget(sheetPart, part)))
        return part
    }

    /** Adds the (hidden) note shape of a cell to the sheet's legacy drawing unless the cell has one. */
    private fun ensureShape(sheet: Int, sheetPart: String, row: Int, col: Int) {
        val part = ensureVml(sheet, sheetPart)
        val xml = pkg.text(part)!!
        val root = Xml.root(xml) ?: return // a drawing that cannot be read is left alone
        if (noteShapes(xml).any { it.row == row && it.col == col }) return
        val ra = Xml.attrs(xml, root)
        val ns = if (ra["xmlns:v"] == NS_V && ra["xmlns:o"] == NS_O && ra["xmlns:x"] == NS_X) "" else " xmlns:v=\"$NS_V\" xmlns:o=\"$NS_O\" xmlns:x=\"$NS_X\""
        val ids = SHAPE_ID.findAll(xml).mapNotNull { it.groupValues[1].toIntOrNull() }.toList()
        val id = if (ids.isNotEmpty()) ids.maxOrNull()!! + 1 else (IDMAP.find(xml)?.groupValues?.get(1)?.split(',')?.firstOrNull()?.trim()?.toIntOrNull() ?: 1) * 1024 + 1
        val z = Xml.children(xml, root).count { Xml.local(it.name) == "shape" } + 1
        val top = maxOf(0, row - 1)
        val left = minOf(col + 1, A1.MAX_COL)
        val anchor = "$left, 15, $top, ${if (row == 0) 2 else 10}, ${minOf(left + 2, A1.MAX_COL)}, 15, ${top + 4}, 4"
        val type = if (xml.contains("id=\"_x0000_t202\"")) "" else SHAPETYPE.replace("<v:shapetype", "<v:shapetype$ns")
        val shape = "<v:shape$ns id=\"_x0000_s$id\" type=\"#_x0000_t202\" " +
            "style=\"position:absolute;margin-left:59.25pt;margin-top:1.5pt;width:108pt;height:59.25pt;z-index:$z;visibility:hidden\" " +
            "fillcolor=\"#ffffe1\" o:insetmode=\"auto\"><v:fill color2=\"#ffffe1\"/><v:shadow on=\"t\" color=\"black\" obscured=\"t\"/>" +
            "<v:path o:connecttype=\"none\"/><v:textbox style=\"mso-direction-alt:auto\"><div style=\"text-align:left\"></div></v:textbox>" +
            "<x:ClientData ObjectType=\"Note\"><x:MoveWithCells/><x:SizeWithCells/><x:Anchor>$anchor</x:Anchor>" +
            "<x:AutoFill>False</x:AutoFill><x:Row>$row</x:Row><x:Column>$col</x:Column></x:ClientData></v:shape>"
        put(part, Xml.appendInto(xml, root, type + shape))
    }

    private fun removeShape(sheet: Int, sheetPart: String, row: Int, col: Int) {
        val part = vmlPartOf(sheet, sheetPart) ?: return
        val xml = pkg.text(part) ?: return
        val edits = noteShapes(xml).filter { it.row == row && it.col == col }.map { Triple(it.e.start, it.e.end, "") }
        if (edits.isNotEmpty()) put(part, Xml.splice(xml, edits))
    }

    private fun removeLegacyEntry(sheetPart: String, pos: Pair<Int, Int>) {
        val part = legacyPartOf(sheetPart) ?: return
        val xml = pkg.text(part) ?: return
        val edits = (legacy(xml)?.items ?: emptyList()).filter { it.pos == pos }.map { Triple(it.e.start, it.e.end, "") }
        if (edits.isNotEmpty()) put(part, Xml.splice(xml, edits))
    }

    /** The thread as a program that only knows notes shows it: each message under its author's name. */
    private fun plain(c: ReviewComment): String =
        (listOf(c.author to c.text) + c.replies.map { it.author to it.text }).joinToString("\n\n") { (a, t) -> if (a.isEmpty()) t else "$a:\n$t" }

    /**
     * Brings the note-form copy of a thread in line with it: the entry of the thread's cell in the `comments` part
     * gets the thread's text; a thread without one gets the entry (author `tc={id}`) and its shape.
     */
    private fun syncLegacy(sheet: Int, rootId: String) {
        invalidate()
        val root = model().byId[rootId]?.root ?: return
        val pos = A1.parse(root.cell) ?: return
        val sp = host.sheetParts()[sheet]
        val part = ensureLegacyPart(sp)
        var xml = pkg.text(part)!!
        val entry = legacy(xml)?.items?.firstOrNull { it.pos == pos }
        if (entry != null) {
            val p = XmlX.prefixOf(entry.e.name)
            val text = "<${p}text><${p}t xml:space=\"preserve\">${textXml(plain(root))}</${p}t></${p}text>"
            val te = entry.textEl
            val out = when {
                te != null -> if (xml.substring(te.start, te.end) == text) xml else Xml.replace(xml, te, text)
                entry.e.selfClosing -> Xml.appendInto(xml, entry.e, text)
                else -> xml.substring(0, entry.e.openEnd) + text + xml.substring(entry.e.openEnd)
            }
            if (out != xml) put(part, out)
        } else {
            xml = withLists(xml)
            var lg = legacy(xml)!!
            val p = XmlX.prefixOf(lg.root.name)
            val authorName = "tc=$rootId"
            var authorId = lg.authors.indexOf(authorName)
            if (authorId < 0) {
                authorId = lg.authors.size
                xml = Xml.appendInto(xml, XmlX.kid(xml, lg.root, "authors")!!, "<${p}author>${Xml.escape(authorName)}</${p}author>")
                lg = legacy(xml)!!
            }
            val tag = "<${p}comment ref=\"${root.cell}\" authorId=\"$authorId\" shapeId=\"0\"><${p}text><${p}t xml:space=\"preserve\">" +
                textXml(plain(root)) + "</${p}t></${p}text></${p}comment>"
            put(part, Xml.appendInto(xml, XmlX.kid(xml, lg.root, "commentList")!!, tag))
            ensureShape(sheet, sp, pos.first, pos.second)
        }
        invalidate()
    }

    private fun threadItemXml(p: String, ref: String, dT: String, personId: String, id: String, parentId: String?, text: String): String =
        "<${p}threadedComment ref=\"$ref\" dT=\"${Xml.escape(dT)}\" personId=\"${Xml.escape(personId)}\" id=\"$id\"" +
            (if (parentId != null) " parentId=\"${Xml.escape(parentId)}\"" else "") + "><${p}text>${textXml(text)}</${p}text></${p}threadedComment>"

    // ---- changing: what the workbook offers ----

    /**
     * Starts a thread on the cell and returns its id; a cell that has a thread gets a reply instead (the reply's id is
     * returned). Throws [IllegalStateException] ("cell has a note") when the cell holds a note.
     */
    fun add(sheet: Int, row: Int, col: Int, author: String, text: String, dateIso: String): String {
        val ref = A1.name(row, col)
        val at = comments(sheet).firstOrNull { it.cell == ref }
        if (at != null) {
            if (at.kind == CommentKind.NOTE) throw IllegalStateException("cell has a note")
            return reply(at.id, author, text, dateIso)
        }
        val sp = host.sheetParts()[sheet]
        val id = newGuid()
        val part = ensureThreadPart(sp)
        val person = personId(author)
        val xml = pkg.text(part)!!
        val root = Xml.root(xml) ?: throw IllegalStateException("the threaded-comments part is not readable")
        put(part, Xml.appendInto(xml, root, threadItemXml(XmlX.prefixOf(root.name), ref, CommentDates.toFile(dateIso), person, id, null, text)))
        syncLegacy(sheet, id)
        host.changed()
        return id
    }

    /** Adds a reply to the thread [id] names (its first message or any reply of it) and returns the reply's id. */
    fun reply(id: String, author: String, text: String, dateIso: String): String {
        val loc = locate(id)
        if (loc.root.kind == CommentKind.NOTE) throw IllegalStateException("a note has no replies")
        val sp = host.sheetParts()[loc.sheet]
        val part = threadPartOf(sp) ?: throw IllegalStateException("the thread's part is gone")
        val newId = newGuid()
        val person = personId(author)
        val xml = pkg.text(part)!!
        val root = Xml.root(xml)!!
        val last = threadItems(xml).last { it.id == loc.root.id || it.parent == loc.root.id }
        val tag = threadItemXml(XmlX.prefixOf(root.name), loc.root.cell, CommentDates.toFile(dateIso), person, newId, loc.root.id, text)
        put(part, xml.substring(0, last.e.end) + tag + xml.substring(last.e.end))
        syncLegacy(loc.sheet, loc.root.id)
        host.changed()
        return newId
    }

    /** Marks the thread [id] names resolved (`done="1"` on its first message) or open again. */
    fun setResolved(id: String, resolved: Boolean) {
        val loc = locate(id)
        if (loc.root.kind == CommentKind.NOTE) throw IllegalStateException("a note cannot be resolved")
        if (loc.root.resolved == resolved) return
        val part = threadPartOf(host.sheetParts()[loc.sheet]) ?: return
        val xml = pkg.text(part)!!
        val e = threadItems(xml).first { it.id == loc.root.id }.e
        val span = XmlX.attrValueSpan(xml, e, "done")
        val out = when {
            span != null -> xml.substring(0, span.first) + (if (resolved) "1" else "0") + xml.substring(span.second)
            else -> xml.substring(0, e.start) + XmlX.startTag(xml, e, mapOf("done" to "1"), e.selfClosing) + xml.substring(e.openEnd)
        }
        put(part, out)
        invalidate()
        host.changed()
    }

    /** New text for a first message, a reply or a note. */
    fun edit(id: String, text: String) {
        val loc = locate(id)
        val sp = host.sheetParts()[loc.sheet]
        if (loc.root.kind == CommentKind.NOTE) {
            if (loc.root.text == text) return
            editNote(sp, loc.root, text)
        } else {
            if ((loc.reply?.text ?: loc.root.text) == text) return
            val part = threadPartOf(sp) ?: return
            val xml = pkg.text(part)!!
            val e = threadItems(xml).first { it.id == id }.e
            val p = XmlX.prefixOf(e.name)
            val tag = "<${p}text>${textXml(text)}</${p}text>"
            val edits = ArrayList<Triple<Int, Int, String>>()
            val te = XmlX.kid(xml, e, "text")
            when {
                te != null -> edits.add(Triple(te.start, te.end, tag))
                e.selfClosing -> edits.add(Triple(e.start, e.end, XmlX.startTag(xml, e, emptyMap(), false) + tag + "</" + e.name + ">"))
                else -> edits.add(Triple(e.openEnd, e.openEnd, tag))
            }
            // mentions point at character positions of the old text
            XmlX.kid(xml, e, "mentions")?.let { edits.add(Triple(it.start, it.end, "")) }
            put(part, Xml.splice(xml, edits))
            syncLegacy(loc.sheet, loc.root.id)
        }
        invalidate()
        host.changed()
    }

    private fun editNote(sheetPart: String, note: ReviewComment, text: String) {
        val part = legacyPartOf(sheetPart) ?: return
        val xml = pkg.text(part)!!
        val pos = A1.parse(note.cell)
        val entry = legacy(xml)?.items?.firstOrNull { it.pos == pos } ?: return
        val p = XmlX.prefixOf(entry.e.name)
        val te = entry.textEl
        val nt = noteText(xml, te, note.author)
        val kids = if (te != null) Xml.children(xml, te) else emptyList()
        fun rPr(run: Xml.Elem?): String = run?.let { XmlX.kid(xml, it, "rPr") }?.let { xml.substring(it.start, it.end) } ?: ""
        val runs = kids.filter { Xml.local(it.name) == "r" }
        val inner = when {
            nt.headingRun != null -> xml.substring(nt.headingRun.start, nt.headingRun.end) +
                "<${p}r>${rPr(runs.getOrNull(1))}<${p}t xml:space=\"preserve\">${textXml("\n" + text)}</${p}t></${p}r>"
            runs.isNotEmpty() -> "<${p}r>${rPr(runs[0])}<${p}t xml:space=\"preserve\">${textXml(nt.heading + text)}</${p}t></${p}r>"
            else -> "<${p}t xml:space=\"preserve\">${textXml(nt.heading + text)}</${p}t>"
        }
        val out = when {
            te != null && !te.selfClosing -> xml.substring(0, te.openEnd) + inner + xml.substring(te.closeStart)
            te != null -> Xml.replace(xml, te, "<${te.name}>$inner</${te.name}>")
            entry.e.selfClosing -> Xml.appendInto(xml, entry.e, "<${p}text>$inner</${p}text>")
            else -> xml.substring(0, entry.e.openEnd) + "<${p}text>$inner</${p}text>" + xml.substring(entry.e.openEnd)
        }
        put(part, out)
    }

    /**
     * A first message: the whole thread, its note-form copy and the shape go. A reply: only the reply. A note: its
     * entry and its shape. Parts that end up empty stay (empty and valid) with their relationships.
     */
    fun delete(id: String) {
        val loc = locate(id)
        val sp = host.sheetParts()[loc.sheet]
        val pos = A1.parse(loc.root.cell)!!
        if (loc.root.kind == CommentKind.NOTE) {
            removeLegacyEntry(sp, pos)
            removeShape(loc.sheet, sp, pos.first, pos.second)
            noteIds[sp]?.remove(loc.root.cell)
        } else {
            val part = threadPartOf(sp)
            val xml = part?.let { pkg.text(it) }
            if (xml != null) {
                val gone = if (loc.reply != null) setOf(loc.reply.id) else setOf(loc.root.id)
                val edits = threadItems(xml).filter { it.id in gone || (loc.reply == null && it.parent == loc.root.id) }.map { Triple(it.e.start, it.e.end, "") }
                if (edits.isNotEmpty()) put(part, Xml.splice(xml, edits))
            }
            if (loc.reply != null) syncLegacy(loc.sheet, loc.root.id)
            else {
                removeLegacyEntry(sp, pos)
                removeShape(loc.sheet, sp, pos.first, pos.second)
            }
        }
        invalidate()
        host.changed()
    }

    /**
     * Cells of a sheet moved: [f] gives the new place of (row, column), or null when that cell is gone (its comment
     * goes with it). Every place a comment names its cell follows: thread items, entries of the `comments` part, and
     * the shapes' row, column and anchor. Returns true when a part changed.
     */
    fun remap(sheet: Int, f: (Int, Int) -> Pair<Int, Int>?): Boolean {
        val sp = host.sheetParts().getOrNull(sheet) ?: return false
        var any = false
        fun move(pos: Pair<Int, Int>?): Pair<Int, Int>? {
            if (pos == null) return null
            val n = f(pos.first, pos.second) ?: return null
            return if (n.first < 0 || n.first > A1.MAX_ROW || n.second < 0 || n.second > A1.MAX_COL) null else n
        }
        fun refEdits(xml: String, elems: List<Pair<Xml.Elem, Pair<Int, Int>?>>): String? {
            val edits = ArrayList<Triple<Int, Int, String>>()
            for ((e, pos) in elems) {
                if (pos == null) continue // a reference this module cannot read stays as it is
                val n = move(pos)
                if (n == null) edits.add(Triple(e.start, e.end, ""))
                else if (n != pos) XmlX.attrValueSpan(xml, e, "ref")?.let { edits.add(Triple(it.first, it.second, A1.name(n.first, n.second))) }
            }
            return if (edits.isEmpty()) null else Xml.splice(xml, edits)
        }
        threadPartOf(sp)?.let { part ->
            val xml = pkg.text(part) ?: return@let
            refEdits(xml, threadItems(xml).map { Pair(it.e, it.pos) })?.let { put(part, it); any = true }
        }
        legacyPartOf(sp)?.let { part ->
            val xml = pkg.text(part) ?: return@let
            refEdits(xml, (legacy(xml)?.items ?: emptyList()).map { Pair(it.e, it.pos) })?.let { put(part, it); any = true }
        }
        vmlPartOf(sheet, sp)?.let { part ->
            val xml = pkg.text(part) ?: return@let
            val edits = ArrayList<Triple<Int, Int, String>>()
            for (s in noteShapes(xml)) {
                val n = move(Pair(s.row, s.col))
                if (n == null) { edits.add(Triple(s.e.start, s.e.end, "")); continue }
                if (n.first == s.row && n.second == s.col) continue
                edits.add(Triple(s.rowEl.openEnd, s.rowEl.closeStart, n.first.toString()))
                edits.add(Triple(s.colEl.openEnd, s.colEl.closeStart, n.second.toString()))
                val an = s.anchorEl
                if (an != null && !an.selfClosing) {
                    val raw = xml.substring(an.openEnd, an.closeStart)
                    val v = raw.split(',').map { it.trim().toIntOrNull() }
                    if (v.size == 8 && v.all { it != null }) {
                        val a = v.map { it!! }.toIntArray()
                        // the box keeps its size and its distance from the cell
                        val dc = maxOf(n.second - s.col, -a[0]).let { if (a[4] + it > A1.MAX_COL) A1.MAX_COL - a[4] else it }
                        val dr = maxOf(n.first - s.row, -a[2]).let { if (a[6] + it > A1.MAX_ROW) A1.MAX_ROW - a[6] else it }
                        a[0] += dc; a[4] += dc; a[2] += dr; a[6] += dr
                        val lead = raw.substring(0, raw.length - raw.trimStart().length)
                        edits.add(Triple(an.openEnd, an.closeStart, lead + a.joinToString(", ")))
                    }
                }
            }
            if (edits.isNotEmpty()) { put(part, Xml.splice(xml, edits)); any = true }
        }
        noteIds[sp]?.let { old ->
            val next = HashMap<String, String>()
            for ((ref, id) in old) move(A1.parse(ref))?.let { next[A1.name(it.first, it.second)] = id }
            noteIds[sp] = next
        }
        if (any) { invalidate(); host.changed() }
        return any
    }

    /** Rows ([cols] false) or columns were inserted ([delta] 1) or deleted ([delta] −1) at index [at]. */
    fun shift(sheet: Int, cols: Boolean, at: Int, delta: Int): Boolean = remap(sheet) { r, c ->
        val v = if (cols) c else r
        val nv = when {
            delta > 0 -> if (v >= at) v + delta else v
            v == at -> -1
            v > at -> v - 1
            else -> v
        }
        if (nv < 0) null else if (cols) Pair(r, nv) else Pair(nv, c)
    }

    companion object {
        const val NS_MAIN = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
        const val NS_TC = "http://schemas.microsoft.com/office/spreadsheetml/2018/threadedcomments"
        const val NS_V = "urn:schemas-microsoft-com:vml"
        const val NS_O = "urn:schemas-microsoft-com:office:office"
        const val NS_X = "urn:schemas-microsoft-com:office:excel"
        private const val REL_NS = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
        const val REL_COMMENTS = "$REL_NS/comments"
        const val REL_VML = "$REL_NS/vmlDrawing"
        const val REL_THREADED = "http://schemas.microsoft.com/office/2017/10/relationships/threadedComment"
        const val REL_PERSON = "http://schemas.microsoft.com/office/2017/10/relationships/person"
        const val CT_COMMENTS = "application/vnd.openxmlformats-officedocument.spreadsheetml.comments+xml"
        const val CT_THREADED = "application/vnd.ms-excel.threadedcomments+xml"
        const val CT_PERSON = "application/vnd.ms-excel.person+xml"
        const val CT_VML = "application/vnd.openxmlformats-officedocument.vmlDrawing"
        private const val DECL = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"

        /** The text-box shape type every note shape refers to. */
        private const val SHAPETYPE = "<v:shapetype id=\"_x0000_t202\" coordsize=\"21600,21600\" o:spt=\"202\" path=\"m,l,21600r21600,l21600,xe\">" +
            "<v:stroke joinstyle=\"miter\"/><v:path gradientshapeok=\"t\" o:connecttype=\"rect\"/></v:shapetype>"

        private val TC_AUTHOR = Regex("tc=\\{[0-9A-Fa-f-]+\\}")
        private val IDMAP = Regex("<(?:[A-Za-z0-9_]+:)?idmap\\b[^>]*\\bdata=\"([^\"]*)\"")
        private val SHAPE_ID = Regex("\\bid=\"_x0000_s(\\d+)\"")
        private val ESCAPE = Regex("_x([0-9A-Fa-f]{4})_")

        private fun relTarget(owner: String, part: String): String {
            val from = owner.split('/').dropLast(1)
            val to = part.split('/')
            var k = 0
            while (k < from.size && k < to.size - 1 && from[k] == to[k]) k++
            return "../".repeat(from.size - k) + to.subList(k, to.size).joinToString("/")
        }

        private fun decodeEscapes(s: String): String =
            if (s.indexOf("_x") < 0) s else ESCAPE.replace(s) { m -> m.groupValues[1].toInt(16).toChar().toString() }

        /** Text for a `<t>` / `<text>` element: markup characters escaped, characters XML cannot carry left out. */
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
                    ch == '\r' -> {}
                    ch < ' ' && ch != '\n' && ch != '\t' -> {}
                    ch == '\uFFFE' || ch == '\uFFFF' -> {}
                    else -> sb.append(ch)
                }
                i++
            }
            return sb.toString()
        }
    }
}

/**
 * Dates of thread messages. The file holds `2026-11-22T10:00:00.00`: universal time, hundredths of a second, no zone
 * mark (the form the common desktop programs write and read). The review model holds ISO-8601 with `Z`.
 */
internal object CommentDates {
    private val RE = Regex("(\\d{4})-(\\d{2})-(\\d{2})(?:[Tt ](\\d{2}):(\\d{2})(?::(\\d{2})(?:[.,](\\d+))?)?)?\\s*(Z|z|[+-]\\d{2}(?::?\\d{2})?)?")

    /** (seconds since 1970 in universal time, fraction digits) of an ISO date-time; without a zone it is taken as universal time. */
    private fun parse(s: String): Pair<Long, String>? {
        val g = RE.matchEntire(s.trim())?.groupValues ?: return null
        val y = g[1].toInt(); val mo = g[2].toInt(); val d = g[3].toInt()
        val h = g[4].ifEmpty { "0" }.toInt(); val mi = g[5].ifEmpty { "0" }.toInt(); val sec = g[6].ifEmpty { "0" }.toInt()
        if (mo !in 1..12 || d !in 1..31 || h > 23 || mi > 59 || sec > 60) return null
        var t = Num.daysFromCivil(y, mo, d) * 86400L + h * 3600L + mi * 60L + sec
        val z = g[8]
        if (z.isNotEmpty() && z != "Z" && z != "z") {
            val digits = z.substring(1).replace(":", "")
            val off = digits.substring(0, 2).toInt() * 3600L + (if (digits.length >= 4) digits.substring(2, 4).toInt() * 60L else 0L)
            t -= if (z[0] == '-') -off else off
        }
        return Pair(t, g[7])
    }

    private fun two(n: Long): String = if (n < 10) "0$n" else n.toString()

    private fun stamp(t: Long): String {
        val c = Num.civil(Math.floorDiv(t, 86400L))
        val s = Math.floorMod(t, 86400L)
        return c[0].toString().padStart(4, '0') + "-" + two(c[1].toLong()) + "-" + two(c[2].toLong()) + "T" + two(s / 3600) + ":" + two(s / 60 % 60) + ":" + two(s % 60)
    }

    /** ISO-8601 (with `Z`, an offset, or nothing) → the form the file holds. Blank → now. Text that is not a date-time is kept as it is. */
    fun toFile(iso: String): String {
        if (iso.isBlank()) return stamp(Math.floorDiv(System.currentTimeMillis(), 1000L)) + ".00"
        val (t, frac) = parse(iso) ?: return iso.trim()
        return stamp(t) + "." + (frac + "00").substring(0, 2)
    }

    /** What the file holds → `2026-11-22T10:00:00Z`. Text that is not a date-time is given as it is. */
    fun fromFile(dT: String): String {
        if (dT.isBlank()) return ""
        val (t, _) = parse(dT) ?: return dT.trim()
        return stamp(t) + "Z"
    }
}
