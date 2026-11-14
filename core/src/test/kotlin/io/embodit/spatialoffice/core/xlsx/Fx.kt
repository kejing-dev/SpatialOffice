package io.embodit.spatialoffice.core.xlsx

import io.embodit.spatialoffice.core.pkg.OoxmlPackage
import io.embodit.spatialoffice.core.pkg.Xml
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import java.io.File

/** Shared helpers for the spreadsheet tests: fixtures, part comparison, cell-level XML diffing. */
internal object Fx {
    const val SHEET1 = "xl/worksheets/sheet1.xml"
    const val SHEET2 = "xl/worksheets/sheet2.xml"
    const val STYLES = "xl/styles.xml"
    const val SHARED = "xl/sharedStrings.xml"

    fun bytes(name: String): ByteArray =
        Fx::class.java.getResourceAsStream("/fixtures/$name")?.use { it.readBytes() } ?: error("missing fixture $name")

    fun sales(): XlsxWorkbook = XlsxWorkbook.open(bytes("xlsx-sales.xlsx"))
    fun cached(): XlsxWorkbook = XlsxWorkbook.open(bytes("xlsx-cached.xlsx"))

    fun parts(bytes: ByteArray): Map<String, ByteArray> {
        val p = OoxmlPackage.read(bytes)
        return p.names().associateWith { p.bytes(it)!! }
    }

    fun text(bytes: ByteArray, part: String): String = OoxmlPackage.read(bytes).text(part) ?: error("no part $part")

    /** Writes a saved file where the independent verifier picks it up. */
    fun out(name: String, bytes: ByteArray): ByteArray {
        val dir = File("build/roundtrip")
        dir.mkdirs()
        File(dir, name).writeBytes(bytes)
        return bytes
    }

    /** Names of parts that differ (added, removed or changed) between two packages. */
    fun changedParts(before: ByteArray, after: ByteArray): Set<String> {
        val a = parts(before)
        val b = parts(after)
        return (a.keys + b.keys).filter { k -> a[k] == null || b[k] == null || !a[k]!!.contentEquals(b[k]!!) }.toSortedSet()
    }

    fun assertSameParts(before: ByteArray, after: ByteArray) {
        val a = OoxmlPackage.read(before)
        val b = OoxmlPackage.read(after)
        // same parts; the package writer always puts the content-types part first, so entry order is not compared
        assertEquals(a.names().sorted(), b.names().sorted())
        for (n in a.names()) assertArrayEquals("part $n", a.bytes(n), b.bytes(n))
    }

    /** ref → raw text of every `<c>` in a sheet part. */
    fun cells(xml: String): LinkedHashMap<String, String> {
        val out = LinkedHashMap<String, String>()
        val root = Xml.root(xml)!!
        val sd = Xml.children(xml, root).first { Xml.local(it.name) == "sheetData" }
        for (row in Xml.children(xml, sd)) for (c in Xml.children(xml, row)) {
            out[Xml.attr(xml, c, "r")!!] = xml.substring(c.start, c.end)
        }
        return out
    }

    /** The sheet part with every `<c>` cut out: what must stay identical when only cells were edited. */
    fun skeleton(xml: String): String {
        val root = Xml.root(xml)!!
        val sd = Xml.children(xml, root).first { Xml.local(it.name) == "sheetData" }
        val edits = ArrayList<Triple<Int, Int, String>>()
        for (row in Xml.children(xml, sd)) for (c in Xml.children(xml, row)) edits.add(Triple(c.start, c.end, ""))
        return Xml.splice(xml, edits)
    }

    /** Refs of the cells whose raw XML differs between two versions of a sheet part. */
    fun changedCells(before: String, after: String): Set<String> {
        val a = cells(before)
        val b = cells(after)
        return (a.keys + b.keys).filter { a[it] != b[it] }.toSortedSet()
    }

    fun commonPrefix(a: String, b: String): Int {
        var i = 0
        while (i < a.length && i < b.length && a[i] == b[i]) i++
        return i
    }

    fun commonSuffix(a: String, b: String): Int {
        var i = 0
        while (i < a.length && i < b.length && a[a.length - 1 - i] == b[b.length - 1 - i]) i++
        return i
    }
}
