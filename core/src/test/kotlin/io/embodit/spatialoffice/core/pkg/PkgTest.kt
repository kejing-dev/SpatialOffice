package io.embodit.spatialoffice.core.pkg

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PkgTest {
    private val ct = "<?xml version=\"1.0\"?><Types xmlns=\"x\"><Default Extension=\"xml\" ContentType=\"application/xml\"/></Types>"

    private fun sample(): ByteArray {
        val p = OoxmlPackage.empty()
        p.putText(OoxmlPackage.CONTENT_TYPES, ct)
        p.putText("word/document.xml", "<w:document><w:body><w:p a=\"1&amp;2\"><w:r><w:t>Hi &lt;you&gt;</w:t></w:r></w:p><w:p/></w:body></w:document>")
        p.put("word/media/a.bin", ByteArray(300) { (it * 7).toByte() })
        return p.write()
    }

    @Test fun roundTripKeepsUntouchedPartsAndOrder() {
        val a = OoxmlPackage.read(sample())
        assertTrue(a.dirtyParts.isEmpty())
        a.putText("word/document.xml", "<w:document/>")
        val b = OoxmlPackage.read(a.write())
        assertEquals(a.names(), b.names())
        assertArrayEquals(OoxmlPackage.read(sample()).bytes("word/media/a.bin"), b.bytes("word/media/a.bin"))
        assertEquals(ct, b.text(OoxmlPackage.CONTENT_TYPES))
        assertEquals("<w:document/>", b.text("word/document.xml"))
        assertEquals(setOf("word/document.xml"), a.dirtyParts)
    }

    @Test fun pathHelpers() {
        assertEquals("ppt/media/a.png", OoxmlPackage.resolve("ppt/slides/slide1.xml", "../media/a.png"))
        assertEquals("word/media/a.png", OoxmlPackage.resolve("word/document.xml", "media/a.png"))
        assertEquals("xl/x.xml", OoxmlPackage.resolve("word/document.xml", "/xl/x.xml"))
        assertEquals("word/_rels/document.xml.rels", OoxmlPackage.relsOf("word/document.xml"))
        assertEquals("_rels/.rels", OoxmlPackage.relsOf(""))
    }

    @Test fun scanRangesAndAttrs() {
        val xml = OoxmlPackage.read(sample()).text("word/document.xml")!!
        val root = Xml.root(xml)!!
        assertEquals("w:document", root.name)
        val body = Xml.child(xml, root, "w:body")!!
        val ps = Xml.children(xml, body, "w:p")
        assertEquals(2, ps.size)
        assertEquals("1&2", Xml.attr(xml, ps[0], "a"))
        assertEquals("Hi <you>", Xml.text(xml, ps[0]))
        assertTrue(ps[1].selfClosing)
        assertEquals("<w:p/>", xml.substring(ps[1].start, ps[1].end))
        assertEquals("w:t", Xml.find(xml, root, "w:t")!!.name)
        assertNull(Xml.find(xml, root, "w:tbl"))
    }

    @Test fun nestedSameNameAndQuotedGt() {
        val xml = "<a><b x=\"1>2\"><b>in</b></b><!-- <b> --><b/><![CDATA[<b>]]></a>"
        val root = Xml.root(xml)!!
        val bs = Xml.children(xml, root, "b")
        assertEquals(2, bs.size)
        assertEquals("1>2", Xml.attr(xml, bs[0], "x"))
        assertEquals("in", Xml.text(xml, bs[0]))
        assertEquals(1, Xml.findAll(xml, bs[0], "b").size)
    }

    @Test fun spliceAndAppend() {
        val xml = "<r><a>1</a><b/></r>"
        val root = Xml.root(xml)!!
        val a = Xml.child(xml, root, "a")!!
        val b = Xml.child(xml, root, "b")!!
        assertEquals("<r><a>9</a><b><c/></b></r>", Xml.splice(xml, listOf(Triple(b.start, b.end, "<b><c/></b>"), Triple(a.start, a.end, "<a>9</a>"))))
        assertEquals("<r><a>1</a><b/><z/></r>", Xml.appendChild(xml, "<z/>"))
        assertEquals("<r><z/></r>", Xml.appendChild("<r/>", "<z/>"))
        assertEquals("a&amp;b&lt;c&gt;&quot;", Xml.escape("a&b<c>\""))
        assertEquals("a&b<c>\"'A中", Xml.unescape("a&amp;b&lt;c&gt;&quot;&apos;&#65;&#x4E2D;"))
    }

    @Test fun relsAndContentTypes() {
        val p = OoxmlPackage.read(sample())
        val id = Rels.add(p, "word/document.xml", "t:image", "media/a.bin")
        assertEquals("rId1", id)
        assertEquals("rId2", Rels.add(p, "word/document.xml", "t:x", "x.xml"))
        val rels = Rels.of(p, "word/document.xml")
        assertEquals(listOf("media/a.bin", "x.xml"), rels.map { it.target })
        ContentTypes.addOverride(p, "word/x.xml", "application/x")
        ContentTypes.addOverride(p, "word/x.xml", "application/x")
        ContentTypes.addDefault(p, "png", "image/png")
        val t = p.text(OoxmlPackage.CONTENT_TYPES)!!
        assertEquals(1, Regex("PartName=\"/word/x.xml\"").findAll(t).count())
        assertTrue(t.contains("Extension=\"png\""))
        ContentTypes.removeOverride(p, "word/x.xml")
        assertTrue(!p.text(OoxmlPackage.CONTENT_TYPES)!!.contains("/word/x.xml"))
    }
}
