package io.embodit.spatialoffice.core.docx

import io.embodit.spatialoffice.core.pkg.OoxmlPackage
import io.embodit.spatialoffice.core.pkg.Rels
import io.embodit.spatialoffice.core.pkg.Xml
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import java.io.File

/** Assertions shared by the docx and pptx tests: part-level comparison and package consistency. */
object PackageAsserts {

    fun fixture(name: String): ByteArray =
        PackageAsserts::class.java.getResourceAsStream("/fixtures/$name")?.readBytes() ?: throw IllegalStateException("missing fixture $name")

    fun parts(bytes: ByteArray): Map<String, ByteArray> {
        val p = OoxmlPackage.read(bytes)
        return p.names().associateWith { p.bytes(it)!! }
    }

    fun text(bytes: ByteArray, part: String): String = String(parts(bytes).getValue(part), Charsets.UTF_8)

    /** Every part of [before] is byte-identical in [after], same names, same order. */
    fun assertAllPartsIdentical(before: ByteArray, after: ByteArray) {
        assertEquals(OoxmlPackage.read(before).names(), OoxmlPackage.read(after).names())
        val a = parts(before)
        val b = parts(after)
        for ((name, data) in a) assertArrayEquals("part $name changed", data, b[name])
    }

    /** The set of parts that differ (changed, added or removed) between two packages. */
    fun changedParts(before: ByteArray, after: ByteArray): Set<String> {
        val a = parts(before)
        val b = parts(after)
        return (a.keys + b.keys).filter { !(a[it] contentEquals b[it]) }.toSortedSet()
    }

    private infix fun ByteArray?.contentEquals(other: ByteArray?): Boolean =
        if (this == null || other == null) this == null && other == null else java.util.Arrays.equals(this, other)

    /**
     * [after] equals [before] except inside the element that occupied [start, end) in [before]: the text before
     * it and the text after it are untouched. Returns the replacement text.
     */
    fun assertOnlyRangeChanged(before: String, after: String, start: Int, end: Int): String {
        val prefix = before.substring(0, start)
        val suffix = before.substring(end)
        assertTrue("text before the edited element changed", after.startsWith(prefix))
        assertTrue("text after the edited element changed", after.endsWith(suffix))
        assertTrue("edited part is shorter than its untouched prefix + suffix", after.length >= prefix.length + suffix.length)
        return after.substring(prefix.length, after.length - suffix.length)
    }

    /**
     * Package-level consistency: every internal relationship target exists, every part has a content type, every
     * content-type override names an existing part, and no relationship id is used twice in one .rels part.
     */
    fun assertConsistent(bytes: ByteArray) {
        val pkg = OoxmlPackage.read(bytes)
        val names = pkg.names().toSet()
        assertEquals("duplicate part names", pkg.names().size, names.size)
        val ct = pkg.text(OoxmlPackage.CONTENT_TYPES)!!
        val root = Xml.root(ct)!!
        val defaults = HashSet<String>()
        val overrides = HashSet<String>()
        for (c in Xml.children(ct, root)) {
            when (c.name) {
                "Default" -> defaults.add(Xml.attr(ct, c, "Extension")!!.lowercase())
                "Override" -> {
                    val pn = Xml.attr(ct, c, "PartName")!!.trimStart('/')
                    assertTrue("content-type override listed twice: $pn", overrides.add(pn))
                    assertTrue("content-type override for a part that does not exist: $pn", pn in names)
                }
            }
        }
        for (n in names) {
            if (n == OoxmlPackage.CONTENT_TYPES) continue
            assertTrue("no content type for $n", n in overrides || n.substringAfterLast('.', "").lowercase() in defaults)
            val owner = XmlEx.ownerOfRels(n) ?: continue
            if (owner.isNotEmpty()) assertTrue("relationships part $n has no owner part $owner", owner in names)
            val rels = Rels.parse(pkg.text(n)!!)
            assertEquals("duplicate relationship ids in $n", rels.size, rels.map { it.id }.toSet().size)
            for (r in rels) {
                if (r.external) continue
                val target = OoxmlPackage.resolve(owner, r.target)
                if (target !in names) fail("$n: ${r.id} points at missing part $target")
            }
        }
    }

    /** Writes a saved file where `tools/verify/verify_docx_pptx.py` picks it up. */
    fun writeRoundTrip(name: String, bytes: ByteArray) {
        val dir = File("build/roundtrip")
        dir.mkdirs()
        File(dir, name).writeBytes(bytes)
    }
}
