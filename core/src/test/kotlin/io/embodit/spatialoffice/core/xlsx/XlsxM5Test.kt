package io.embodit.spatialoffice.core.xlsx

import io.embodit.spatialoffice.core.pkg.ContentTypes
import io.embodit.spatialoffice.core.pkg.OoxmlPackage
import io.embodit.spatialoffice.core.pkg.Rels
import io.embodit.spatialoffice.core.pkg.Xml
import io.embodit.spatialoffice.core.review.CommentKind
import io.embodit.spatialoffice.core.review.ReviewComment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume
import org.junit.Test
import java.io.File

/**
 * M5 (v0.7.0) s35: comments (threads with replies and a resolved mark) and notes on cells.
 * Every file saved here also goes to `build/m5-xlsx/` with a `.json` list of what this module reads back from it;
 * `tools/verify/m5_xlsx_verify.py` reads the files on its own and compares.
 */
class XlsxM5Test {

    // ---- fixtures: built here, in the form the common desktop programs write ----

    private val T1 = "{11111111-1111-4111-8111-111111111111}"
    private val T2 = "{22222222-2222-4222-8222-222222222222}"
    private val T3 = "{33333333-3333-4333-8333-333333333333}"
    private val P1 = "{AAAAAAAA-AAAA-4AAA-8AAA-AAAAAAAAAAAA}"
    private val P2 = "{BBBBBBBB-BBBB-4BBB-8BBB-BBBBBBBBBBBB}"

    private val MAIN = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
    private val REL = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    private val DECL = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
    private val CT = OoxmlPackage.CONTENT_TYPES
    private val WB_RELS = "xl/_rels/workbook.xml.rels"
    private val RELS1 = "xl/worksheets/_rels/sheet1.xml.rels"
    private val RELS2 = "xl/worksheets/_rels/sheet2.xml.rels"
    private val COMMENTS1 = "xl/comments1.xml"
    private val COMMENTS2 = "xl/comments2.xml"
    private val VML1 = "xl/drawings/vmlDrawing1.vml"
    private val VML2 = "xl/drawings/vmlDrawing2.vml"
    private val THREADS1 = "xl/threadedComments/threadedComment1.xml"
    private val THREADS2 = "xl/threadedComments/threadedComment2.xml"
    private val PERSONS = "xl/persons/person.xml"

    private fun vmlShape(id: Int, z: Int, row: Int, col: Int, anchor: String): String = """ <v:shape id="_x0000_s$id" type="#_x0000_t202" style='position:absolute;
  margin-left:59.25pt;margin-top:1.5pt;width:108pt;height:59.25pt;z-index:$z;
  visibility:hidden' fillcolor="#ffffe1" o:insetmode="auto">
  <v:fill color2="#ffffe1"/>
  <v:shadow on="t" color="black" obscured="t"/>
  <v:path o:connecttype="none"/>
  <v:textbox style='mso-direction-alt:auto'>
   <div style='text-align:left'></div>
  </v:textbox>
  <x:ClientData ObjectType="Note">
   <x:MoveWithCells/>
   <x:SizeWithCells/>
   <x:Anchor>
    $anchor</x:Anchor>
   <x:AutoFill>False</x:AutoFill>
   <x:Row>$row</x:Row>
   <x:Column>$col</x:Column>
  </x:ClientData>
 </v:shape>
"""

    private fun vml(data: Int, shapes: String): String = """<xml xmlns:v="urn:schemas-microsoft-com:vml"
 xmlns:o="urn:schemas-microsoft-com:office:office"
 xmlns:x="urn:schemas-microsoft-com:office:excel">
 <o:shapelayout v:ext="edit">
  <o:idmap v:ext="edit" data="$data"/>
 </o:shapelayout><v:shapetype id="_x0000_t202" coordsize="21600,21600" o:spt="202"
  path="m,l,21600r21600,l21600,xe">
  <v:stroke joinstyle="miter"/>
  <v:path gradientshapeok="t" o:connecttype="rect"/>
 </v:shapetype>
$shapes</xml>"""

    private val NOTE_FONT = "<sz val=\"9\"/><color indexed=\"81\"/><rFont val=\"Tahoma\"/><family val=\"2\"/>"

    /** Notes on A1 (author heading in a bold run of its own, as most programs write it) and B3 (plain text). */
    private val NOTES_XML = DECL + "<comments xmlns=\"$MAIN\"><authors><author>Wang Lei</author><author>Ana</author></authors><commentList>" +
        "<comment ref=\"A1\" authorId=\"0\" shapeId=\"0\"><text><r><rPr><b/>$NOTE_FONT</rPr><t>Wang Lei:</t></r>" +
        "<r><rPr>$NOTE_FONT</rPr><t xml:space=\"preserve\">\nCheck the header wording</t></r></text></comment>" +
        "<comment ref=\"B3\" authorId=\"1\" shapeId=\"0\"><text><t>Plain note &amp; more</t></text></comment>" +
        "</commentList></comments>"

    private val THREADS_XML = DECL + "<ThreadedComments xmlns=\"${CommentStore.NS_TC}\" xmlns:x=\"$MAIN\">" +
        "<threadedComment ref=\"B2\" dT=\"2026-11-20T08:30:00.00\" personId=\"$P1\" id=\"$T1\"><text>Is this the final number?</text></threadedComment>" +
        "<threadedComment ref=\"B2\" dT=\"2026-11-20T09:00:00.00\" personId=\"$P2\" id=\"$T2\" parentId=\"$T1\"><text>Yes, confirmed.</text></threadedComment>" +
        "<threadedComment ref=\"D4\" dT=\"2026-11-21T12:00:00.00\" personId=\"$P2\" id=\"$T3\" done=\"1\"><text>Old question</text></threadedComment>" +
        "</ThreadedComments>"

    private val PERSONS_XML = DECL + "<personList xmlns=\"${CommentStore.NS_TC}\" xmlns:x=\"$MAIN\">" +
        "<person displayName=\"Li Na\" id=\"$P1\" userId=\"lina@example.com\" providerId=\"None\"/>" +
        "<person displayName=\"Chen Yu\" id=\"$P2\" userId=\"chenyu@example.com\" providerId=\"None\"/></personList>"

    /** The note-form copies of the two threads: author `tc={thread id}`, the thread as plain text. */
    private val SHADOWS_XML = DECL + "<comments xmlns=\"$MAIN\"><authors><author>tc=$T1</author><author>tc=$T3</author></authors><commentList>" +
        "<comment ref=\"B2\" authorId=\"0\" shapeId=\"0\"><text><t>[Threaded comment]\n\nComment:\n    Is this the final number?\nReply:\n    Yes, confirmed.</t></text></comment>" +
        "<comment ref=\"D4\" authorId=\"1\" shapeId=\"0\"><text><t>[Threaded comment]\n\nComment:\n    Old question</t></text></comment>" +
        "</commentList></comments>"

    private fun addLegacyDrawing(p: OoxmlPackage, sheetPart: String, rid: String) {
        val xml = p.text(sheetPart)!!
        val root = Xml.root(xml)!!
        p.putText(sheetPart, xml.substring(0, root.closeStart) + "<legacyDrawing r:id=\"$rid\"/>" + xml.substring(root.closeStart))
    }

    /** Two sheets ("Sheet1", "Data") with a few cells; notes on sheet 1 and / or threads on sheet 2. */
    private fun book(notes: Boolean, threads: Boolean): ByteArray {
        val wb = XlsxWorkbook.blank()
        val rows = listOf(listOf("Item", "Units", "Price"), listOf("Headset", "120", "2999"), listOf("Strap", "40", "199"), listOf("Case", "25", "149"))
        for ((r, line) in rows.withIndex()) for ((c, v) in line.withIndex()) wb.setCell(0, r, c, v)
        val d = wb.addSheet("Data")
        for (r in 0..5) for (c in 0..4) wb.setCell(d, r, c, ((r + 1) * (c + 2)).toString())
        wb.setCell(d, 6, 1, "=SUM(B1:B6)")
        val p = OoxmlPackage.read(wb.save())
        if (notes) {
            p.putText(COMMENTS1, NOTES_XML)
            p.putText(VML1, vml(1, vmlShape(1025, 1, 0, 0, "1, 15, 0, 2, 3, 15, 3, 16") + vmlShape(1026, 2, 2, 1, "2, 15, 1, 10, 4, 15, 5, 4")))
            ContentTypes.addDefault(p, "vml", CommentStore.CT_VML)
            ContentTypes.addOverride(p, COMMENTS1, CommentStore.CT_COMMENTS)
            Rels.add(p, Fx.SHEET1, CommentStore.REL_COMMENTS, "../comments1.xml")
            addLegacyDrawing(p, Fx.SHEET1, Rels.add(p, Fx.SHEET1, CommentStore.REL_VML, "../drawings/vmlDrawing1.vml"))
        }
        if (threads) {
            p.putText(THREADS1, THREADS_XML)
            p.putText(PERSONS, PERSONS_XML)
            p.putText(COMMENTS2, SHADOWS_XML)
            p.putText(VML2, vml(2, vmlShape(2049, 1, 1, 1, "2, 15, 0, 10, 4, 15, 4, 4") + vmlShape(2050, 2, 3, 3, "4, 15, 2, 10, 6, 15, 6, 4")))
            ContentTypes.addDefault(p, "vml", CommentStore.CT_VML)
            ContentTypes.addOverride(p, COMMENTS2, CommentStore.CT_COMMENTS)
            ContentTypes.addOverride(p, THREADS1, CommentStore.CT_THREADED)
            ContentTypes.addOverride(p, PERSONS, CommentStore.CT_PERSON)
            Rels.add(p, Fx.SHEET2, CommentStore.REL_COMMENTS, "../comments2.xml")
            addLegacyDrawing(p, Fx.SHEET2, Rels.add(p, Fx.SHEET2, CommentStore.REL_VML, "../drawings/vmlDrawing2.vml"))
            Rels.add(p, Fx.SHEET2, CommentStore.REL_THREADED, "../threadedComments/threadedComment1.xml")
            Rels.add(p, "xl/workbook.xml", CommentStore.REL_PERSON, "persons/person.xml")
        }
        return p.write()
    }

    /** A sheet that is a table: `<pageMargins>` and `<tableParts>` follow the sheet data. */
    private fun tableBook(): ByteArray {
        val wb = XlsxWorkbook.blank()
        wb.setCell(0, 0, 0, "H1"); wb.setCell(0, 0, 1, "H2")
        wb.setCell(0, 1, 0, "1"); wb.setCell(0, 1, 1, "2")
        wb.setCell(0, 2, 0, "3"); wb.setCell(0, 2, 1, "4")
        val p = OoxmlPackage.read(wb.save())
        p.putText("xl/tables/table1.xml", DECL + "<table xmlns=\"$MAIN\" id=\"1\" name=\"Table1\" displayName=\"Table1\" ref=\"A1:B3\">" +
            "<autoFilter ref=\"A1:B3\"/><tableColumns count=\"2\"><tableColumn id=\"1\" name=\"H1\"/><tableColumn id=\"2\" name=\"H2\"/></tableColumns>" +
            "<tableStyleInfo name=\"TableStyleMedium2\" showFirstColumn=\"0\" showLastColumn=\"0\" showRowStripes=\"1\" showColumnStripes=\"0\"/></table>")
        ContentTypes.addOverride(p, "xl/tables/table1.xml", "application/vnd.openxmlformats-officedocument.spreadsheetml.table+xml")
        val rid = Rels.add(p, Fx.SHEET1, "$REL/table", "../tables/table1.xml")
        val xml = p.text(Fx.SHEET1)!!
        val root = Xml.root(xml)!!
        p.putText(Fx.SHEET1, xml.substring(0, root.closeStart) +
            "<pageMargins left=\"0.7\" right=\"0.7\" top=\"0.75\" bottom=\"0.75\" header=\"0.3\" footer=\"0.3\"/>" +
            "<tableParts count=\"1\"><tablePart r:id=\"$rid\"/></tableParts>" + xml.substring(root.closeStart))
        return p.write()
    }

    // ---- helpers ----

    private fun js(s: String): String {
        val sb = StringBuilder("\"")
        for (ch in s) when {
            ch == '"' -> sb.append("\\\"")
            ch == '\\' -> sb.append("\\\\")
            ch == '\n' -> sb.append("\\n")
            ch == '\t' -> sb.append("\\t")
            ch < ' ' || ch > '~' -> sb.append(String.format("\\u%04x", ch.code))
            else -> sb.append(ch)
        }
        return sb.append('"').toString()
    }

    /**
     * Writes a saved file and, next to it, what a fresh open of it lists as comments. [relaxed]: the file is broken on
     * purpose (a missing person, an entry without a shape), so the verifier does not ask the two forms to agree.
     */
    private fun out(name: String, bytes: ByteArray, relaxed: Boolean = false): ByteArray {
        val dir = File("build/m5-xlsx")
        dir.mkdirs()
        File(dir, name).writeBytes(bytes)
        val wb = XlsxWorkbook.open(bytes)
        val sb = StringBuilder("{\"relaxed\": $relaxed, \"sheets\": [")
        for ((si, sn) in wb.sheetNames.withIndex()) {
            if (si > 0) sb.append(", ")
            sb.append("{\"name\": ").append(js(sn)).append(", \"comments\": [")
            sb.append(wb.comments(si).joinToString(", ") { c ->
                "{\"id\": ${js(c.id)}, \"cell\": ${js(c.cell)}, \"kind\": ${js(c.kind.name)}, \"author\": ${js(c.author)}, \"date\": ${js(c.date)}, " +
                    "\"text\": ${js(c.text)}, \"resolved\": ${c.resolved}, \"replies\": [" +
                    c.replies.joinToString(", ") { r -> "{\"id\": ${js(r.id)}, \"author\": ${js(r.author)}, \"date\": ${js(r.date)}, \"text\": ${js(r.text)}}" } + "]}"
            })
            sb.append("]}")
        }
        sb.append("]}\n")
        File(dir, "$name.json").writeText(sb.toString())
        return bytes
    }

    /**
     * What was taken out and what was put in, when two texts differ in one stretch. A plain insertion / removal is
     * reported from its `<` on (an inserted element shares its first character with the tag that follows it).
     */
    private fun diff(a: String, b: String): Pair<String, String> {
        var p = Fx.commonPrefix(a, b)
        var s = minOf(Fx.commonSuffix(a, b), a.length - p, b.length - p)
        val long = if (a.length - p - s == 0) b else if (b.length - p - s == 0) a else null
        if (long != null) {
            var k = 0
            while (p - k > 0 && long[p - k] != '<' && long[p - k - 1] == long[long.length - s - k - 1]) k++
            if (long[p - k] == '<') { p -= k; s += k }
        }
        return Pair(a.substring(p, a.length - s), b.substring(p, b.length - s))
    }

    private fun brief(c: ReviewComment): String =
        "${c.cell} ${c.kind} ${c.author}: ${c.text}" + (if (c.resolved) " [resolved]" else "") + c.replies.joinToString("") { " | ${it.author}: ${it.text}" }

    private fun briefs(wb: XlsxWorkbook, sheet: Int): List<String> = wb.comments(sheet).map { brief(it) }

    private fun relTargets(bytes: ByteArray, part: String): Map<String, String> {
        val p = OoxmlPackage.read(bytes)
        return Rels.of(p, part).associate { it.type.substringAfterLast('/') to OoxmlPackage.resolve(part, it.target) }
    }

    private fun shapeCells(vml: String): List<Pair<Int, Int>> {
        val rows = Regex("<x:Row>(\\d+)</x:Row>").findAll(vml).map { it.groupValues[1].toInt() }.toList()
        val cols = Regex("<x:Column>(\\d+)</x:Column>").findAll(vml).map { it.groupValues[1].toInt() }.toList()
        return rows.zip(cols)
    }

    private fun anchors(vml: String): List<String> = Regex("<x:Anchor>\\s*([^<]*)</x:Anchor>").findAll(vml).map { it.groupValues[1].trim() }.toList()

    private fun legacyRefs(xml: String): List<String> = Regex("<comment ref=\"([A-Z]+[0-9]+)\"").findAll(xml).map { it.groupValues[1] }.toList()

    private fun threadRefs(xml: String): List<String> = Regex("<threadedComment ref=\"([A-Z]+[0-9]+)\"").findAll(xml).map { it.groupValues[1] }.toList()

    // ---- the acceptance ----

    @Test fun acceptanceThreadWithReplyOnC2AndAnEditedNote() {
        val src = book(notes = true, threads = false)
        val wb = XlsxWorkbook.open(src)
        assertFalse(wb.dirty)
        val id = wb.addComment(0, "C2", "Li Na", "Please check this price", "2026-11-22T10:00:00Z")
        assertTrue(wb.dirty)
        val rid = wb.replyComment(id, "Chen Yu", "Checked: it is right", "2026-11-22T10:05:00Z")
        val note = wb.commentAt(0, "A1")!!
        assertEquals(CommentKind.NOTE, note.kind)
        assertEquals("Wang Lei", note.author)
        assertEquals("Check the header wording", note.text)
        wb.editComment(note.id, "Header wording agreed")
        val saved = out("m5-acceptance.xlsx", wb.save())
        assertFalse(wb.dirty)

        // parts: the thread and the people are new; the note parts were there and got the thread's entry for the SAME cell
        assertEquals(
            setOf(CT, WB_RELS, RELS1, COMMENTS1, VML1, THREADS1, PERSONS),
            Fx.changedParts(src, saved),
        )
        val threads = Fx.text(saved, THREADS1)
        assertTrue(threads, threads.contains("<threadedComment ref=\"C2\" dT=\"2026-11-22T10:00:00.00\" personId=\""))
        assertTrue(threads, threads.contains("id=\"$id\"><text>Please check this price</text></threadedComment>"))
        assertTrue(threads, threads.contains("id=\"$rid\" parentId=\"$id\"><text>Checked: it is right</text></threadedComment>"))
        assertEquals(listOf("C2", "C2"), threadRefs(threads))
        val persons = Fx.text(saved, PERSONS)
        val people = Regex("<person displayName=\"([^\"]*)\" id=\"(\\{[0-9A-F-]{36}})\" userId=\"[^\"]*\" providerId=\"None\"/>").findAll(persons).map { it.groupValues[1] to it.groupValues[2] }.toList()
        assertEquals(listOf("Li Na", "Chen Yu"), people.map { it.first })
        assertTrue(threads.contains("personId=\"${people[0].second}\" id=\"$id\""))
        assertTrue(threads.contains("personId=\"${people[1].second}\" id=\"$rid\""))
        val legacy = Fx.text(saved, COMMENTS1)
        assertEquals(listOf("A1", "B3", "C2"), legacyRefs(legacy))
        assertTrue(legacy, legacy.contains("<author>tc=$id</author>"))
        assertTrue(legacy, legacy.contains("<comment ref=\"C2\" authorId=\"2\" shapeId=\"0\"><text><t xml:space=\"preserve\">Li Na:\nPlease check this price\n\nChen Yu:\nChecked: it is right</t></text></comment>"))
        val vml = Fx.text(saved, VML1)
        assertEquals(listOf(0 to 0, 2 to 1, 1 to 2), shapeCells(vml))
        assertTrue(vml, vml.contains("id=\"_x0000_s1027\""))
        // the note: its text is new, the author heading and the other note are as they were
        assertTrue(legacy, legacy.contains("<r><rPr><b/>$NOTE_FONT</rPr><t>Wang Lei:</t></r><r><rPr>$NOTE_FONT</rPr><t xml:space=\"preserve\">\nHeader wording agreed</t></r>"))
        assertTrue(legacy.contains("<comment ref=\"B3\" authorId=\"1\" shapeId=\"0\"><text><t>Plain note &amp; more</t></text></comment>"))
        // relationships and content types
        val rels = relTargets(saved, Fx.SHEET1)
        assertEquals(COMMENTS1, rels["comments"]); assertEquals(VML1, rels["vmlDrawing"]); assertEquals(THREADS1, rels["threadedComment"])
        assertEquals(PERSONS, relTargets(saved, "xl/workbook.xml")["person"])
        val ct = Fx.text(saved, CT)
        assertTrue(ct.contains("<Override PartName=\"/$THREADS1\" ContentType=\"application/vnd.ms-excel.threadedcomments+xml\"/>"))
        assertTrue(ct.contains("<Override PartName=\"/$PERSONS\" ContentType=\"application/vnd.ms-excel.person+xml\"/>"))
        assertTrue(ct.contains("Extension=\"vml\""))
        // each changed part that was there before differs in what was added only
        assertEquals("", diff(Fx.text(src, CT), ct).first)
        assertEquals("", diff(Fx.text(src, RELS1), Fx.text(saved, RELS1)).first)
        assertEquals("", diff(Fx.text(src, WB_RELS), Fx.text(saved, WB_RELS)).first)
        assertEquals("", diff(Fx.text(src, VML1), vml).first)

        for (b in listOf(wb, XlsxWorkbook.open(saved))) {
            assertEquals(
                listOf("A1 NOTE Wang Lei: Header wording agreed", "C2 THREAD Li Na: Please check this price | Chen Yu: Checked: it is right", "B3 NOTE Ana: Plain note & more"),
                briefs(b, 0),
            )
            val c = b.commentAt(0, "C2")!!
            assertEquals(id, c.id)
            assertEquals("C2", c.quote); assertEquals(0, c.sheet); assertEquals(2, c.count)
            assertEquals("2026-11-22T10:00:00Z", c.date)
            assertEquals(rid, c.replies[0].id); assertEquals("2026-11-22T10:05:00Z", c.replies[0].date)
            assertEquals(mapOf("A1" to CommentKind.NOTE, "B3" to CommentKind.NOTE, "C2" to CommentKind.THREAD), b.commentCells(0))
            assertTrue(b.comments(1).isEmpty())
        }
        // saving again without a change gives the same parts
        Fx.assertSameParts(saved, wb.save())
    }

    // ---- reading ----

    @Test fun readsNotesOnSheetOneAndThreadsOnSheetTwoAndSavesThemUntouched() {
        val src = book(notes = true, threads = true)
        val wb = XlsxWorkbook.open(src)
        assertEquals(listOf("A1 NOTE Wang Lei: Check the header wording", "B3 NOTE Ana: Plain note & more"), briefs(wb, 0))
        assertEquals(
            listOf("B2 THREAD Li Na: Is this the final number? | Chen Yu: Yes, confirmed.", "D4 THREAD Chen Yu: Old question [resolved]"),
            briefs(wb, 1),
        )
        val all = wb.comments()
        assertEquals(listOf(0, 0, 1, 1), all.map { it.sheet })
        assertEquals(all.size, all.map { it.id }.toSet().size)
        assertEquals(listOf("note:0:A1", "note:0:B3", T1, T3), all.map { it.id })
        val t = wb.commentAt(1, "b2")!!
        assertEquals("2026-11-20T08:30:00Z", t.date)
        assertEquals(T2, t.replies[0].id)
        assertEquals(T1, wb.comment(T2)!!.id)
        assertNull(wb.commentAt(1, "A1"))
        assertNull(wb.commentAt(1, "not a cell"))
        assertNull(wb.comment("nothing"))
        assertEquals("", wb.commentAt(0, "A1")!!.date)
        assertEquals(mapOf("B2" to CommentKind.THREAD, "D4" to CommentKind.THREAD), wb.commentCells(1))
        // comments are editable now: no longer listed among what is kept but not edited
        assertTrue(wb.compat().none { it.code == "comments" })
        assertFalse(wb.dirty)
        Fx.assertSameParts(src, out("m5-untouched.xlsx", wb.save()))
        try { wb.comments(7); fail("no sheet 7") } catch (e: IndexOutOfBoundsException) { }
    }

    // ---- a workbook with no comments at all ----

    @Test fun firstCommentCreatesEveryPartAndPutsLegacyDrawingIntoTheSheet() {
        val start = XlsxWorkbook.blank()
        start.setCell(0, 0, 0, "Item"); start.setCell(0, 1, 2, "42")
        val src = start.save()
        val wb = XlsxWorkbook.open(src)
        assertTrue(wb.comments().isEmpty())
        assertTrue(wb.commentCells(0).isEmpty())
        val id = wb.addComment(0, "\$c\$2", "王磊", "数字对吗 <再核对> & 确认", "2026-11-22T18:00:00+08:00")
        assertTrue(Regex("\\{[0-9A-F]{8}-[0-9A-F]{4}-[0-9A-F]{4}-[0-9A-F]{4}-[0-9A-F]{12}}").matches(id))
        val saved = out("m5-blank-first-comment.xlsx", wb.save())
        assertEquals(
            setOf(CT, WB_RELS, RELS1, Fx.SHEET1, COMMENTS1, VML1, THREADS1, PERSONS),
            Fx.changedParts(src, saved),
        )
        // the sheet: one element added, in front of the end tag
        val (gone, added) = diff(Fx.text(src, Fx.SHEET1), Fx.text(saved, Fx.SHEET1))
        assertEquals("", gone)
        assertTrue(added, Regex("<legacyDrawing r:id=\"rId\\d+\"/>").matches(added))
        assertTrue(Fx.text(saved, Fx.SHEET1).endsWith("</sheetData>$added</worksheet>"))
        val rels = Rels.of(OoxmlPackage.read(saved), Fx.SHEET1)
        assertEquals(VML1, OoxmlPackage.resolve(Fx.SHEET1, rels.first { added.contains("\"${it.id}\"") }.target))
        assertEquals(setOf("threadedComment", "comments", "vmlDrawing"), rels.map { it.type.substringAfterLast('/') }.toSet())
        // the new parts
        assertTrue(Fx.text(saved, THREADS1).startsWith("$DECL<ThreadedComments xmlns=\"http://schemas.microsoft.com/office/spreadsheetml/2018/threadedcomments\""))
        assertTrue(Fx.text(saved, THREADS1).contains("dT=\"2026-11-22T10:00:00.00\""))
        assertTrue(Fx.text(saved, THREADS1).contains("<text>数字对吗 &lt;再核对&gt; &amp; 确认</text>"))
        assertTrue(Fx.text(saved, PERSONS).contains("<person displayName=\"王磊\""))
        val legacy = Fx.text(saved, COMMENTS1)
        assertTrue(legacy, legacy.startsWith("$DECL<comments xmlns=\"$MAIN\"><authors><author>tc=$id</author></authors><commentList><comment ref=\"C2\" authorId=\"0\" shapeId=\"0\">"))
        val vml = Fx.text(saved, VML1)
        assertTrue(vml, vml.contains("<o:idmap v:ext=\"edit\" data=\"1\"/>"))
        assertTrue(vml, vml.contains("<v:shapetype id=\"_x0000_t202\""))
        assertTrue(vml, vml.contains("<v:shape id=\"_x0000_s1025\" type=\"#_x0000_t202\""))
        assertTrue(vml, vml.contains("<x:ClientData ObjectType=\"Note\">"))
        assertEquals(listOf(1 to 2), shapeCells(vml))
        assertEquals(listOf("3, 15, 0, 10, 5, 15, 4, 4"), anchors(vml))
        val ct = Fx.text(saved, CT)
        assertTrue(ct, ct.contains("<Default Extension=\"vml\" ContentType=\"application/vnd.openxmlformats-officedocument.vmlDrawing\"/>"))
        assertTrue(ct, ct.contains("<Override PartName=\"/$COMMENTS1\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.comments+xml\"/>"))

        val re = XlsxWorkbook.open(saved)
        assertEquals(listOf("C2 THREAD 王磊: 数字对吗 <再核对> & 确认"), briefs(re, 0))
        assertEquals("2026-11-22T10:00:00Z", re.comments(0)[0].date)
        assertEquals("42", re.cell(0, 1, 2).input)
        // a second comment on the same sheet reuses every part: nothing new, the sheet stays as saved
        val id2 = re.addComment(0, "A1", "王磊", "second", "2026-11-22T10:10:00Z")
        val saved2 = out("m5-blank-second-comment.xlsx", re.save())
        assertEquals(setOf(COMMENTS1, VML1, THREADS1), Fx.changedParts(saved, saved2))
        assertEquals(1, Regex("<person ").findAll(Fx.text(saved2, PERSONS)).count())
        assertTrue(Fx.text(saved2, VML1).contains("id=\"_x0000_s1026\""))
        assertEquals(listOf(id2, id), XlsxWorkbook.open(saved2).comments(0).map { it.id })
    }

    @Test fun legacyDrawingGoesAfterDrawingAndBeforeTableParts() {
        // a sheet with a chart: <drawing> is the last element of the sheet
        val sales = Fx.bytes("xlsx-sales.xlsx")
        val wb = XlsxWorkbook.open(sales)
        wb.addComment(0, "B2", "Li Na", "Units look low", "2026-11-22T10:00:00Z")
        wb.addComment(1, "A1", "Li Na", "On the second sheet", "2026-11-22T10:01:00Z")
        val saved = out("m5-sales-with-drawing.xlsx", wb.save())
        for (part in listOf(Fx.SHEET1, Fx.SHEET2)) {
            val xml = Fx.text(saved, part)
            val (gone, added) = diff(Fx.text(sales, part), xml)
            assertEquals("", gone)
            assertTrue(added, Regex("<legacyDrawing xmlns:r=\"$REL\" r:id=\"rId\\d+\"/>").matches(added))
            assertTrue(xml.indexOf("<drawing ") in 0 until xml.indexOf("<legacyDrawing "))
            assertTrue(xml.endsWith("$added</worksheet>"))
            assertTrue(xml.indexOf("<pageMargins") in 0 until xml.indexOf("<drawing "))
        }
        assertEquals(1, XlsxWorkbook.open(saved).charts().count { it.sheet == 0 })

        // a sheet that is a table: between <pageMargins> and <tableParts>
        val tsrc = tableBook()
        val tb = XlsxWorkbook.open(tsrc)
        tb.addComment(0, "B2", "Li Na", "In a table", "2026-11-22T10:00:00Z")
        val tsaved = out("m5-table-sheet.xlsx", tb.save())
        val txml = Fx.text(tsaved, Fx.SHEET1)
        assertTrue(txml, Regex("</sheetData><pageMargins [^>]*/><legacyDrawing r:id=\"rId\\d+\"/><tableParts count=\"1\">").containsMatchIn(txml))
        assertEquals(setOf(CT, WB_RELS, RELS1, Fx.SHEET1, COMMENTS1, VML1, THREADS1, PERSONS), Fx.changedParts(tsrc, tsaved))

        // a chart and a comment added before one save: <drawing> first
        val both = XlsxWorkbook.blank()
        for ((r, line) in listOf(listOf("", "Q1", "Q2"), listOf("East", "1", "2"), listOf("West", "3", "4")).withIndex()) for ((c, v) in line.withIndex()) both.setCell(0, r, c, v)
        both.addChart(0, "A1:C3", "Chart", "bar")
        both.addComment(0, "B2", "Li Na", "With a chart", "2026-11-22T10:00:00Z")
        val bxml = Fx.text(out("m5-chart-and-comment.xlsx", both.save()), Fx.SHEET1)
        assertTrue(bxml, Regex("</sheetData><drawing r:id=\"rId\\d+\"/><legacyDrawing r:id=\"rId\\d+\"/></worksheet>").containsMatchIn(bxml))
    }

    @Test fun twoSheetsEachGetTheirOwnParts() {
        val src = book(notes = false, threads = false)
        val wb = XlsxWorkbook.open(src)
        val b = wb.addComment(1, "B2", "Li Na", "On Data", "2026-11-22T10:00:00Z")
        val a = wb.addComment(0, "A1", "Chen Yu", "On Sheet1", "2026-11-22T10:01:00Z")
        wb.replyComment(a, "Li Na", "Seen", "2026-11-22T10:02:00Z")
        val saved = out("m5-two-sheets.xlsx", wb.save())
        assertEquals(
            setOf(CT, WB_RELS, RELS1, RELS2, Fx.SHEET1, Fx.SHEET2, COMMENTS1, COMMENTS2, VML1, VML2, THREADS1, THREADS2, PERSONS),
            Fx.changedParts(src, saved),
        )
        // parts are numbered in the order they were made; each sheet's relationships lead to its own
        val r2 = relTargets(saved, Fx.SHEET2)
        assertEquals(listOf(THREADS1, COMMENTS1, VML1), listOf(r2["threadedComment"], r2["comments"], r2["vmlDrawing"]))
        val r1 = relTargets(saved, Fx.SHEET1)
        assertEquals(listOf(THREADS2, COMMENTS2, VML2), listOf(r1["threadedComment"], r1["comments"], r1["vmlDrawing"]))
        assertEquals(listOf("B2"), threadRefs(Fx.text(saved, THREADS1)))
        assertEquals(listOf("A1", "A1"), threadRefs(Fx.text(saved, THREADS2)))
        // one person list for the workbook; shape ids of the two drawings do not collide
        assertEquals(2, Regex("<person ").findAll(Fx.text(saved, PERSONS)).count())
        assertTrue(Fx.text(saved, VML1).contains("data=\"1\"") && Fx.text(saved, VML1).contains("_x0000_s1025"))
        assertTrue(Fx.text(saved, VML2).contains("data=\"2\"") && Fx.text(saved, VML2).contains("_x0000_s2049"))
        val re = XlsxWorkbook.open(saved)
        assertEquals(listOf("A1 THREAD Chen Yu: On Sheet1 | Li Na: Seen"), briefs(re, 0))
        assertEquals(listOf("B2 THREAD Li Na: On Data"), briefs(re, 1))
        assertEquals(listOf(a, b), re.comments().map { it.id })
    }

    // ---- resolve, edit, delete ----

    @Test fun resolveAndReopenTouchOnlyTheThreadPart() {
        val src = book(notes = true, threads = true)
        val wb = XlsxWorkbook.open(src)
        wb.setCommentResolved(T1, true)
        wb.setCommentResolved(T3, true) // already resolved: nothing
        assertTrue(wb.commentAt(1, "B2")!!.resolved)
        val saved = out("m5-resolved.xlsx", wb.save())
        assertEquals(setOf(THREADS1), Fx.changedParts(src, saved))
        assertEquals("" to " done=\"1\"", diff(Fx.text(src, THREADS1), Fx.text(saved, THREADS1)))
        val re = XlsxWorkbook.open(saved)
        assertEquals(listOf(true, true), re.comments(1).map { it.resolved })
        // through a reply's id; open again is written as done="0"
        re.setCommentResolved(T2, false)
        re.setCommentResolved(T3, false)
        val reopened = out("m5-reopened.xlsx", re.save())
        assertEquals(setOf(THREADS1), Fx.changedParts(saved, reopened))
        assertEquals(2, Regex(" done=\"0\"").findAll(Fx.text(reopened, THREADS1)).count())
        assertEquals(listOf(false, false), XlsxWorkbook.open(reopened).comments(1).map { it.resolved })
        // nothing to do = not dirty
        val again = XlsxWorkbook.open(reopened)
        again.setCommentResolved(T1, false)
        assertFalse(again.dirty)
    }

    @Test fun editRootReplyAndNote() {
        val src = book(notes = true, threads = true)
        val wb = XlsxWorkbook.open(src)
        wb.editComment(T1, "Is this the final number for Q4?")
        var saved = wb.save()
        assertEquals(setOf(THREADS1, COMMENTS2), Fx.changedParts(src, saved))
        assertEquals("" to " for Q4", diff(Fx.text(src, THREADS1), Fx.text(saved, THREADS1)))
        // the note-form copy of the thread carries the new text; the other entry is as it was
        val legacy = Fx.text(saved, COMMENTS2)
        assertTrue(legacy, legacy.contains("<comment ref=\"B2\" authorId=\"0\" shapeId=\"0\"><text><t xml:space=\"preserve\">Li Na:\nIs this the final number for Q4?\n\nChen Yu:\nYes, confirmed.</t></text></comment>"))
        assertTrue(legacy.contains("<comment ref=\"D4\" authorId=\"1\" shapeId=\"0\"><text><t>[Threaded comment]\n\nComment:\n    Old question</t></text></comment>"))

        wb.editComment(T2, "Yes.\nConfirmed by finance")
        saved = wb.save()
        assertTrue(Fx.text(saved, THREADS1).contains("parentId=\"$T1\"><text>Yes.\nConfirmed by finance</text>"))
        assertTrue(Fx.text(saved, COMMENTS2).contains("Chen Yu:\nYes.\nConfirmed by finance</t>"))

        val before = saved
        wb.editComment("note:0:B3", "Plain note, edited")
        wb.editComment("note:0:A1", "Check the header wording") // the same text: nothing
        saved = out("m5-edited.xlsx", wb.save())
        assertEquals(setOf(COMMENTS1), Fx.changedParts(before, saved))
        assertEquals(">Plain note &amp; more" to " xml:space=\"preserve\">Plain note, edited", diff(Fx.text(before, COMMENTS1), Fx.text(saved, COMMENTS1)))

        val re = XlsxWorkbook.open(saved)
        assertEquals(listOf("A1 NOTE Wang Lei: Check the header wording", "B3 NOTE Ana: Plain note, edited"), briefs(re, 0))
        assertEquals("B2 THREAD Li Na: Is this the final number for Q4? | Chen Yu: Yes.\nConfirmed by finance", briefs(re, 1)[0])
        // unchanged text = not dirty
        re.editComment(T1, "Is this the final number for Q4?")
        re.editComment(T2, "Yes.\nConfirmed by finance")
        assertFalse(re.dirty)
    }

    @Test fun deleteReplyRootAndNote() {
        val src = book(notes = true, threads = true)
        val wb = XlsxWorkbook.open(src)
        // a reply: only the reply goes; the thread's copy is brought in line
        wb.deleteComment(T2)
        var saved = wb.save()
        assertEquals(setOf(THREADS1, COMMENTS2), Fx.changedParts(src, saved))
        assertEquals(listOf("B2", "D4"), threadRefs(Fx.text(saved, THREADS1)))
        assertEquals(listOf("B2", "D4"), legacyRefs(Fx.text(saved, COMMENTS2)))
        assertTrue(Fx.text(saved, COMMENTS2).contains("<t xml:space=\"preserve\">Li Na:\nIs this the final number?</t>"))
        assertEquals("B2 THREAD Li Na: Is this the final number?", briefs(wb, 1)[0])

        // a thread: every message, its entry and its shape
        var before = saved
        wb.deleteComment(T1)
        saved = wb.save()
        assertEquals(setOf(THREADS1, COMMENTS2, VML2), Fx.changedParts(before, saved))
        assertEquals(listOf("D4"), threadRefs(Fx.text(saved, THREADS1)))
        assertEquals(listOf("D4"), legacyRefs(Fx.text(saved, COMMENTS2)))
        assertEquals(listOf(3 to 3), shapeCells(Fx.text(saved, VML2)))
        assertEquals(mapOf("D4" to CommentKind.THREAD), wb.commentCells(1))

        // a note: its entry and its shape; the other note stays
        before = saved
        wb.deleteComment("note:0:A1")
        saved = wb.save()
        assertEquals(setOf(COMMENTS1, VML1), Fx.changedParts(before, saved))
        assertEquals(listOf("B3"), legacyRefs(Fx.text(saved, COMMENTS1)))
        assertEquals(listOf(2 to 1), shapeCells(Fx.text(saved, VML1)))
        assertEquals("" , diff(Fx.text(before, VML1), Fx.text(saved, VML1)).second)

        // the last ones: the parts stay, empty and valid, with their relationships
        wb.deleteComment(T3)
        wb.deleteComment("note:0:B3")
        saved = out("m5-all-deleted.xlsx", wb.save())
        assertTrue(wb.comments().isEmpty())
        assertTrue(Fx.text(saved, THREADS1).endsWith("xmlns:x=\"$MAIN\"></ThreadedComments>"))
        assertTrue(Fx.text(saved, COMMENTS1).endsWith("<commentList></commentList></comments>"))
        assertTrue(shapeCells(Fx.text(saved, VML1)).isEmpty() && shapeCells(Fx.text(saved, VML2)).isEmpty())
        for (p in listOf(CT, WB_RELS, RELS1, RELS2, Fx.SHEET1, Fx.SHEET2, PERSONS)) assertEquals(p, Fx.text(src, p), Fx.text(saved, p))
        val re = XlsxWorkbook.open(saved)
        assertTrue(re.comments().isEmpty())
        // and a new comment goes into the parts that are there
        re.addComment(1, "B2", "Li Na", "again", "2026-11-22T10:00:00Z")
        val again = out("m5-deleted-then-added.xlsx", re.save())
        assertEquals(setOf(THREADS1, COMMENTS2, VML2), Fx.changedParts(saved, again))
        assertEquals(listOf("B2 THREAD Li Na: again"), briefs(XlsxWorkbook.open(again), 1))
    }

    // ---- what the UI is told through exceptions ----

    @Test fun aCellWithANoteRefusesAThreadAndNotesHaveNoRepliesOrResolve() {
        val wb = XlsxWorkbook.open(book(notes = true, threads = true))
        try { wb.addComment(0, "A1", "Li Na", "x", ""); fail("cell has a note") } catch (e: IllegalStateException) { assertEquals("cell has a note", e.message) }
        try { wb.replyComment("note:0:A1", "Li Na", "x", ""); fail("note") } catch (e: IllegalStateException) { }
        try { wb.setCommentResolved("note:0:A1", true); fail("note") } catch (e: IllegalStateException) { }
        try { wb.replyComment("{nope}", "Li Na", "x", ""); fail("unknown id") } catch (e: IllegalArgumentException) { }
        try { wb.editComment("{nope}", "x"); fail("unknown id") } catch (e: IllegalArgumentException) { }
        try { wb.deleteComment("{nope}"); fail("unknown id") } catch (e: IllegalArgumentException) { }
        try { wb.addComment(0, "nowhere", "Li Na", "x", ""); fail("not a cell") } catch (e: IllegalArgumentException) { }
        try { wb.addComment(9, "A1", "Li Na", "x", ""); fail("no sheet 9") } catch (e: IndexOutOfBoundsException) { }
        assertFalse(wb.dirty)
        // a cell that has a thread: addComment is a reply
        val rid = wb.addComment(1, "B2", "Wang Lei", "A third voice", "2026-11-22T10:00:00Z")
        assertNotEquals(T1, rid)
        assertEquals(listOf(T2, rid), wb.commentAt(1, "B2")!!.replies.map { it.id })
        // a reply to a reply's id lands in the same thread, after the thread's last message
        val rid2 = wb.replyComment(T2, "Li Na", "Thanks", "")
        val saved = out("m5-more-replies.xlsx", wb.save())
        val ids = Regex(" id=\"(\\{[^\"]+})\"").findAll(Fx.text(saved, THREADS1)).map { it.groupValues[1] }.toList()
        assertEquals(listOf(T1, T2, rid, rid2, T3), ids)
        // a blank date is now, in the file's form
        assertTrue(Regex("dT=\"20\\d\\d-\\d\\d-\\d\\dT\\d\\d:\\d\\d:\\d\\d\\.00\" personId=\"${Regex.escape(P1)}\" id=\"${Regex.escape(rid2)}\"").containsMatchIn(Fx.text(saved, THREADS1)))
        // Wang Lei was not in the list of people: added once, the others reused
        assertEquals(3, Regex("<person ").findAll(Fx.text(saved, PERSONS)).count())
    }

    @Test fun aCommentOnAMergedRangeSitsOnItsFirstCell() {
        val wb = XlsxWorkbook.open(book(notes = false, threads = false))
        wb.mergeCells(0, 1, 1, 2, 2)
        val id = wb.addComment(0, "C3", "Li Na", "On the merged cell", "2026-11-22T10:00:00Z")
        assertEquals("B2", wb.comment(id)!!.cell)
        assertEquals(mapOf("B2" to CommentKind.THREAD), wb.commentCells(0))
    }

    // ---- cells that move ----

    @Test fun insertedAndDeletedRowsAndColumnsMoveComments() {
        val src = book(notes = true, threads = true)
        val wb = XlsxWorkbook.open(src)
        val ids = wb.comments().map { it.id }
        wb.insertRowBelow(1, 0)       // sheet 2: B2 → B3, D4 → D5
        wb.insertColumnRight(1, 0)    // B3 → C3, D5 → E5
        wb.insertRowBelow(0, 1)       // sheet 1: A1 stays, B3 → B4
        assertEquals(listOf("A1", "B4", "C3", "E5"), wb.comments().map { it.cell })
        assertEquals("ids stay with their comments", ids, wb.comments().map { it.id })
        assertEquals("B4", wb.comment("note:0:B3")!!.cell)
        assertEquals(mapOf("C3" to CommentKind.THREAD, "E5" to CommentKind.THREAD), wb.commentCells(1))
        val saved = out("m5-rows-columns-inserted.xlsx", wb.save())
        assertEquals(listOf("C3", "C3", "E5"), threadRefs(Fx.text(saved, THREADS1)))
        assertEquals(listOf("C3", "E5"), legacyRefs(Fx.text(saved, COMMENTS2)))
        assertEquals(listOf(2 to 2, 4 to 4), shapeCells(Fx.text(saved, VML2)))
        assertEquals("the boxes moved with their cells", listOf("3, 15, 1, 10, 5, 15, 5, 4", "5, 15, 3, 10, 7, 15, 7, 4"), anchors(Fx.text(saved, VML2)))
        assertEquals(listOf("A1", "B4"), legacyRefs(Fx.text(saved, COMMENTS1)))
        assertEquals(listOf(0 to 0, 3 to 1), shapeCells(Fx.text(saved, VML1)))
        // only references changed in the comment parts; the people, relationships and content types are as they were
        assertEquals("3" to "4", diff(Fx.text(src, COMMENTS1), Fx.text(saved, COMMENTS1)))
        for (p in listOf(CT, WB_RELS, RELS1, RELS2, PERSONS)) assertEquals(p, Fx.text(src, p), Fx.text(saved, p))
        val re = XlsxWorkbook.open(saved)
        assertEquals(listOf("A1", "B4", "C3", "E5"), re.comments().map { it.cell })
        assertEquals("C3 THREAD Li Na: Is this the final number? | Chen Yu: Yes, confirmed.", briefs(re, 1)[0])

        // deleting: rows above / columns left of a comment pull it along; its own row / column takes it away
        re.deleteRow(1, 0)            // C3 → C2, E5 → E4
        re.deleteColumn(1, 0)         // C2 → B2, E4 → D4
        assertEquals(listOf("B2", "D4"), re.comments(1).map { it.cell })
        re.deleteRow(1, 1)            // the row of B2: the thread goes; D4 → D3
        assertEquals(listOf("D3 THREAD Chen Yu: Old question [resolved]"), briefs(re, 1))
        re.deleteColumn(0, 1)         // the column of note B4: it goes
        assertEquals(listOf("A1"), re.comments(0).map { it.cell })
        val saved2 = out("m5-rows-columns-deleted.xlsx", re.save())
        assertEquals(listOf("D3"), threadRefs(Fx.text(saved2, THREADS1)))
        assertEquals(listOf("D3"), legacyRefs(Fx.text(saved2, COMMENTS2)))
        assertEquals(listOf(2 to 3), shapeCells(Fx.text(saved2, VML2)))
        assertEquals(listOf("A1"), legacyRefs(Fx.text(saved2, COMMENTS1)))
        assertEquals(listOf(0 to 0), shapeCells(Fx.text(saved2, VML1)))
        assertEquals(listOf("A1", "D3"), XlsxWorkbook.open(saved2).comments().map { it.cell })
        // rows / columns after the comments: nothing in the comment parts changes
        val far = XlsxWorkbook.open(saved2)
        far.insertRowBelow(1, 20); far.insertColumnRight(1, 20); far.deleteRow(0, 30)
        val saved3 = far.save()
        assertTrue(Fx.changedParts(saved2, saved3).none { it.contains("omment") || it.endsWith(".vml") })
    }

    @Test fun sortedRowsTakeTheirCommentsAlong() {
        val wb = XlsxWorkbook.open(book(notes = false, threads = false))
        // sheet 1 rows 2..4: Headset 120, Strap 40, Case 25
        val a = wb.addComment(0, "B2", "Li Na", "about the headset", "2026-11-22T10:00:00Z")
        val b = wb.addComment(0, "A4", "Li Na", "about the case", "2026-11-22T10:01:00Z")
        assertTrue(wb.sortRows(0, 1, ascending = true, firstRow = 1, lastRow = 3))
        assertEquals("Case", wb.cell(0, 1, 0).display)
        assertEquals("B4", wb.comment(a)!!.cell)
        assertEquals("A2", wb.comment(b)!!.cell)
        val re = XlsxWorkbook.open(out("m5-sorted.xlsx", wb.save()))
        assertEquals(listOf("A2 THREAD Li Na: about the case", "B4 THREAD Li Na: about the headset"), briefs(re, 0))
    }

    @Test fun copyPasteAndClearLeaveCommentsWhereTheyAre() {
        val wb = XlsxWorkbook.open(book(notes = true, threads = false))
        val clip = wb.copyRange(0, 0, 0, 2, 1)
        wb.pasteRange(0, 5, 3, clip, 5, 3)
        wb.clearRange(0, 0, 0, 2, 1, styles = true)
        assertEquals(listOf("A1", "B3"), wb.comments(0).map { it.cell })
        assertEquals(mapOf("A1" to CommentKind.NOTE, "B3" to CommentKind.NOTE), wb.commentCells(0))
    }

    // ---- ids ----

    @Test fun idsAreUniqueAndStayWhileTheWorkbookIsOpen() {
        val wb = XlsxWorkbook.open(book(notes = true, threads = true))
        val first = wb.comments().map { it.id }
        val added = wb.addComment(0, "C2", "Li Na", "new", "2026-11-22T10:00:00Z")
        wb.save()
        wb.setCell(0, 7, 0, "more")
        wb.insertRowBelow(0, 0)
        wb.save()
        wb.addSheet("Third")
        wb.renameSheet(1, "Numbers")
        val now = wb.comments()
        assertEquals((first + added).toSet(), now.map { it.id }.toSet())
        assertEquals(now.size, now.map { it.id }.toSet().size)
        assertEquals("B4", wb.comment("note:0:B3")!!.cell)
        assertEquals("C3", wb.comment(added)!!.cell)
        // the note that moved keeps its id after more edits to it
        wb.editComment("note:0:B3", "edited after the move")
        assertEquals("edited after the move", wb.commentAt(0, "B4")!!.text)
        assertEquals("note:0:B3", wb.commentAt(0, "B4")!!.id)
        // a sheet in front goes: the sheet index of the others follows, their ids stay
        wb.deleteSheet(0)
        assertEquals(listOf(T1, T3), wb.comments().map { it.id })
        assertEquals(listOf(0, 0), wb.comments().map { it.sheet })
        val re = XlsxWorkbook.open(out("m5-ids.xlsx", wb.save()))
        assertEquals(listOf(T1, T3), re.comments(0).map { it.id })
    }

    // ---- files that are not as they should be ----

    @Test fun aThreadWhosePersonIsMissingStillReadsAndCanBeAnswered() {
        val p = OoxmlPackage.read(book(notes = false, threads = true))
        p.putText(PERSONS, PERSONS_XML.replace(Regex("<person displayName=\"Li Na\"[^>]*/>"), ""))
        val src = p.write()
        val wb = XlsxWorkbook.open(src)
        assertEquals("B2 THREAD : Is this the final number? | Chen Yu: Yes, confirmed.", briefs(wb, 1)[0])
        wb.replyComment(T1, "Li Na", "Who asked?", "2026-11-22T10:00:00Z")
        wb.editComment(T1, "Is this final?")
        val saved = out("m5-robust-missing-person.xlsx", wb.save(), relaxed = true)
        assertEquals(setOf(THREADS1, COMMENTS2, PERSONS), Fx.changedParts(src, saved))
        val re = XlsxWorkbook.open(saved)
        assertEquals("B2 THREAD : Is this final? | Chen Yu: Yes, confirmed. | Li Na: Who asked?", briefs(re, 1)[0])
        // the copy for programs that only know notes leaves the unknown name out
        assertTrue(Fx.text(saved, COMMENTS2).contains("<t xml:space=\"preserve\">Is this final?\n\nChen Yu:\nYes, confirmed.\n\nLi Na:\nWho asked?</t>"))
    }

    @Test fun aNoteWithoutAShapeReadsEditsMovesAndDeletes() {
        val p = OoxmlPackage.read(book(notes = true, threads = false))
        // the shape of B3 is not in the drawing
        p.putText(VML1, vml(1, vmlShape(1025, 1, 0, 0, "1, 15, 0, 2, 3, 15, 3, 16")))
        val src = p.write()
        val wb = XlsxWorkbook.open(src)
        assertEquals(listOf("A1", "B3"), wb.comments(0).map { it.cell })
        wb.editComment("note:0:B3", "still editable")
        var saved = out("m5-robust-no-shape.xlsx", wb.save(), relaxed = true)
        assertEquals(setOf(COMMENTS1), Fx.changedParts(src, saved))
        wb.insertRowBelow(0, 0)
        assertEquals(listOf("A1", "B4"), wb.comments(0).map { it.cell })
        wb.insertRowBelow(0, 2)
        assertEquals(listOf("A1", "B5"), wb.comments(0).map { it.cell })
        assertEquals(listOf("A1", "B5"), legacyRefs(Fx.text(wb.save(), COMMENTS1)))
        wb.deleteComment("note:0:B3")
        saved = wb.save()
        assertEquals(listOf("A1"), legacyRefs(Fx.text(saved, COMMENTS1)))
        assertEquals(listOf(0 to 0), shapeCells(Fx.text(saved, VML1)))
        // a thread without its note-form copy gets one (entry and shape) once it is changed
        val q = OoxmlPackage.read(book(notes = false, threads = true))
        q.putText(COMMENTS2, DECL + "<comments xmlns=\"$MAIN\"><authors><author>tc=$T3</author></authors><commentList>" +
            "<comment ref=\"D4\" authorId=\"0\" shapeId=\"0\"><text><t>[Threaded comment]\n\nComment:\n    Old question</t></text></comment></commentList></comments>")
        q.putText(VML2, vml(2, vmlShape(2049, 1, 3, 3, "4, 15, 2, 10, 6, 15, 6, 4")))
        val tsrc = q.write()
        val tb = XlsxWorkbook.open(tsrc)
        assertEquals(mapOf("B2" to CommentKind.THREAD, "D4" to CommentKind.THREAD), tb.commentCells(1))
        tb.replyComment(T1, "Li Na", "One more", "2026-11-22T10:00:00Z")
        val tsaved = out("m5-robust-thread-without-copy.xlsx", tb.save())
        assertEquals(setOf(THREADS1, COMMENTS2, VML2), Fx.changedParts(tsrc, tsaved))
        assertEquals(listOf("D4", "B2"), legacyRefs(Fx.text(tsaved, COMMENTS2)))
        assertTrue(Fx.text(tsaved, COMMENTS2).contains("<author>tc=$T3</author><author>tc=$T1</author>"))
        assertEquals(listOf(3 to 3, 1 to 1), shapeCells(Fx.text(tsaved, VML2)))
        assertTrue(Fx.text(tsaved, VML2).contains("_x0000_s2050"))
    }

    @Test fun anEmptyCommentsPartIsFilled() {
        for ((k, empty) in listOf("<comments xmlns=\"$MAIN\"/>", "<comments xmlns=\"$MAIN\"><authors/><commentList/></comments>", "<comments xmlns=\"$MAIN\"></comments>").withIndex()) {
            val p = OoxmlPackage.read(book(notes = false, threads = false))
            p.putText(COMMENTS1, DECL + empty)
            ContentTypes.addOverride(p, COMMENTS1, CommentStore.CT_COMMENTS)
            Rels.add(p, Fx.SHEET1, CommentStore.REL_COMMENTS, "../comments1.xml")
            val src = p.write()
            val wb = XlsxWorkbook.open(src)
            assertTrue(wb.comments().isEmpty())
            assertFalse(wb.dirty)
            Fx.assertSameParts(src, wb.save())
            val id = wb.addComment(0, "C2", "Li Na", "into the empty part", "2026-11-22T10:00:00Z")
            val saved = out("m5-robust-empty-comments-$k.xlsx", wb.save())
            // the part that was there is used: no second comments part
            assertEquals(setOf(CT, WB_RELS, RELS1, Fx.SHEET1, COMMENTS1, VML1, THREADS1, PERSONS), Fx.changedParts(src, saved))
            assertTrue(Fx.text(saved, COMMENTS1), Regex("<authors><author>tc=\\{[^}]+}</author></authors><commentList><comment ref=\"C2\" authorId=\"0\" shapeId=\"0\">").containsMatchIn(Fx.text(saved, COMMENTS1)))
            assertEquals(listOf(id), XlsxWorkbook.open(saved).comments(0).map { it.id })
        }
    }

    @Test fun brokenPartsDoNotStopTheFileFromOpening() {
        val p = OoxmlPackage.read(book(notes = true, threads = true))
        p.putText(THREADS1, "not xml at all")
        p.putText(COMMENTS1, DECL + "<comments xmlns=\"$MAIN\"><authors><author>Ana</author></authors><commentList>" +
            "<comment ref=\"A1:B2\" authorId=\"7\"><text><t>a range, an author that is not listed</t></text></comment>" +
            "<comment authorId=\"0\"/><comment ref=\"C5\" authorId=\"0\"/></commentList></comments>")
        val wb = XlsxWorkbook.open(p.write())
        // sheet 2: the thread part is unreadable, so the entries of the comments part are all there is
        assertEquals(listOf("B2", "D4"), wb.comments(1).map { it.cell })
        assertEquals(listOf(CommentKind.NOTE, CommentKind.NOTE), wb.comments(1).map { it.kind })
        assertEquals("the author of a thread's copy is not a name", "", wb.comments(1)[0].author)
        // sheet 1: only the entry with a cell reference; a note without text is empty
        assertEquals(listOf("C5 NOTE Ana: "), briefs(wb, 0))
        wb.editComment(wb.comments(0)[0].id, "now with text")
        assertEquals("now with text", XlsxWorkbook.open(wb.save()).commentAt(0, "C5")!!.text)
    }

    @Test fun aLegacyDrawingThatIsNotInTheFileIsReplacedNotDoubled() {
        val p = OoxmlPackage.read(book(notes = false, threads = false))
        addLegacyDrawing(p, Fx.SHEET1, "rId9") // names a relationship the sheet does not have
        val wb = XlsxWorkbook.open(p.write())
        wb.addComment(0, "C2", "Li Na", "x", "2026-11-22T10:00:00Z")
        val saved = out("m5-robust-dangling-legacy-drawing.xlsx", wb.save())
        val xml = Fx.text(saved, Fx.SHEET1)
        assertEquals(1, Regex("<legacyDrawing ").findAll(xml).count())
        val rid = Regex("<legacyDrawing r:id=\"(rId\\d+)\"/>").find(xml)!!.groupValues[1]
        assertEquals(VML1, OoxmlPackage.resolve(Fx.SHEET1, Rels.of(OoxmlPackage.read(saved), Fx.SHEET1).first { it.id == rid }.target))
    }

    @Test fun aNoteKeepsItsAuthorHeadingWhenItsTextChanges() {
        val p = OoxmlPackage.read(book(notes = true, threads = false))
        // the heading inside the one text element, and runs without a heading
        p.putText(COMMENTS1, DECL + "<comments xmlns=\"$MAIN\"><authors><author>Ana</author></authors><commentList>" +
            "<comment ref=\"A1\" authorId=\"0\" shapeId=\"0\"><text><t xml:space=\"preserve\">Ana:\nfirst</t></text></comment>" +
            "<comment ref=\"B3\" authorId=\"0\" shapeId=\"0\"><text><r><rPr><i/></rPr><t>second </t></r><r><t>in two runs</t></r></text></comment>" +
            "</commentList></comments>")
        val wb = XlsxWorkbook.open(p.write())
        assertEquals(listOf("A1 NOTE Ana: first", "B3 NOTE Ana: second in two runs"), briefs(wb, 0))
        wb.editComment("note:0:A1", "first, edited")
        wb.editComment("note:0:B3", "second _x0041_ <edited>")
        val saved = out("m5-note-headings.xlsx", wb.save())
        val xml = Fx.text(saved, COMMENTS1)
        assertTrue(xml, xml.contains("<comment ref=\"A1\" authorId=\"0\" shapeId=\"0\"><text><t xml:space=\"preserve\">Ana:\nfirst, edited</t></text></comment>"))
        assertTrue(xml, xml.contains("<text><r><rPr><i/></rPr><t xml:space=\"preserve\">second _x005F_x0041_ &lt;edited&gt;</t></r></text>"))
        assertEquals(listOf("A1 NOTE Ana: first, edited", "B3 NOTE Ana: second _x0041_ <edited>"), briefs(XlsxWorkbook.open(saved), 0))
    }

    // ---- dates ----

    @Test fun datesAreStoredInUniversalTimeWithoutAZoneMark() {
        assertEquals("2026-11-22T10:00:00.00", CommentDates.toFile("2026-11-22T10:00:00Z"))
        assertEquals("2026-11-22T10:00:00.12", CommentDates.toFile("2026-11-22T10:00:00.1234Z"))
        assertEquals("2026-11-22T10:00:00.00", CommentDates.toFile("2026-11-22T18:00:00+08:00"))
        assertEquals("2026-11-23T01:30:00.00", CommentDates.toFile("2026-11-22T20:30:00-0500"))
        assertEquals("2026-11-22T10:00:00.00", CommentDates.toFile("2026-11-22T10:00"))
        assertEquals("2026-11-22T00:00:00.00", CommentDates.toFile("2026-11-22"))
        assertEquals("yesterday", CommentDates.toFile(" yesterday "))
        assertEquals("2026-11-22T10:00:00Z", CommentDates.fromFile("2026-11-22T10:00:00.00"))
        assertEquals("2026-11-22T10:00:00Z", CommentDates.fromFile("2026-11-22T10:00:00Z"))
        assertEquals("2026-12-31T23:59:59Z", CommentDates.fromFile("2027-01-01T07:59:59.99+08:00"))
        assertEquals("", CommentDates.fromFile(""))
        assertEquals("sometime", CommentDates.fromFile("sometime"))
    }

    // ---- a file written by another library (made by tools/fixtures/m5_xlsx_make.py; skipped when it is not there) ----

    @Test fun notesWrittenByAnotherLibraryReadAndTakeAThread() {
        val f = File("build/m5-xlsx/in/m5-other-notes.xlsx")
        Assume.assumeTrue("run tools/fixtures/m5_xlsx_make.py first", f.exists())
        val src = f.readBytes()
        val wb = XlsxWorkbook.open(src)
        assertEquals(listOf("A1 NOTE Wang Lei: First note", "C3 NOTE Ana: Second note\nwith two lines"), briefs(wb, 0))
        assertEquals(listOf("B2 NOTE Ana: On the second sheet"), briefs(wb, 1))
        Fx.assertSameParts(src, wb.save())
        wb.editComment(wb.commentAt(0, "A1")!!.id, "First note, edited")
        val id = wb.addComment(0, "B5", "Li Na", "A thread next to the notes", "2026-11-22T10:00:00Z")
        wb.replyComment(id, "Ana", "Fine", "2026-11-22T10:01:00Z")
        wb.insertRowBelow(1, 0)
        val saved = out("m5-other-notes-edited.xlsx", wb.save())
        val re = XlsxWorkbook.open(saved)
        assertEquals(
            listOf("A1 NOTE Wang Lei: First note, edited", "C3 NOTE Ana: Second note\nwith two lines", "B5 THREAD Li Na: A thread next to the notes | Ana: Fine"),
            briefs(re, 0),
        )
        assertEquals(listOf("B3 NOTE Ana: On the second sheet"), briefs(re, 1))
        assertNotNull(re.commentAt(0, "B5"))
    }
}
