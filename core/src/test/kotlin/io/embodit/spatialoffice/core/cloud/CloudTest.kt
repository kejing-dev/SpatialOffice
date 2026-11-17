package io.embodit.spatialoffice.core.cloud

import io.embodit.spatialoffice.core.docx.DocxDocument
import io.embodit.spatialoffice.core.docx.Paragraph
import io.embodit.spatialoffice.core.docx.Run
import io.embodit.spatialoffice.core.layout.Json
import io.embodit.spatialoffice.core.xlsx.XlsxWorkbook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest
import java.util.Base64

class CloudTest {
    private val reg = AppRegistration("client-123", "", "http://127.0.0.1:53682/callback")

    @Test fun pkceChallengeIsBase64UrlSha256() {
        // known answer computed independently (Python hashlib + base64.urlsafe_b64encode, padding stripped)
        assertEquals("Gfj4sfow1MnXABtYeCKrjn6dIqBbjj5OXTUjAkdRHvE", Pkce.challenge("dBjftJeZ4CVP-mJ92K9-tYjB2-yiKfo_6Pzz3_t04iY"))
        val v = Pkce.verifier()
        assertTrue(v.length in 43..128)
        assertTrue(v.all { it.isLetterOrDigit() || it in "-._~" })
    }

    @Test fun unregisteredAppsAreRecognised() {
        assertFalse(AppRegistration("", "", "x").registered)
        assertFalse(AppRegistration("REPLACE_WITH_GOOGLE_CLIENT_ID", "", "x").registered)
        assertTrue(reg.registered)
    }

    @Test fun browserUrlCarriesPkceAndState() {
        for (p in Provider.values()) {
            val a = AuthRequest.create(p, reg)
            val url = a.browserUrl()
            assertTrue(url, url.startsWith(p.authUrl + "?"))
            val q = Http.query(url)
            assertEquals("client-123", q["client_id"])
            assertEquals(reg.redirectUri, q["redirect_uri"])
            assertEquals("S256", q["code_challenge_method"])
            assertEquals(a.challenge, q["code_challenge"])
            assertEquals(a.state, q["state"])
            assertEquals("code", q["response_type"])
            // the code comes back only for our state
            assertNull(a.parseCallback(mapOf("state" to "other", "code" to "x")))
            assertEquals("abc", a.parseCallback(mapOf("state" to a.state, "code" to "abc"))!!.getOrNull())
            assertTrue(a.parseCallback(mapOf("state" to a.state, "error" to "access_denied"))!!.isFailure)
            val (type, body) = a.tokenBody("abc")
            assertTrue(body.contains(a.verifier))
            if (p == Provider.FEISHU) assertEquals("application/json", type) else assertTrue(body.contains("grant_type=authorization_code"))
        }
    }

    @Test fun tokensParseAndPersist() {
        val t = Tokens.parse("{\"access_token\":\"A\",\"refresh_token\":\"R\",\"expires_in\":3600}", 1000L)
        assertEquals(Tokens("A", "R", 1000L + 3_600_000L), t)
        assertEquals(t, Tokens.fromJson(t.toJson()))
        val f = Tokens.parse("{\"code\":0,\"data\":{\"access_token\":\"F\",\"expires_in\":7200}}", 0L)
        assertEquals("F", f.access)
    }

    @Test fun listsParseForEveryService() {
        val feishu = CloudApi.parseList(Provider.FEISHU, "{\"code\":0,\"data\":{\"files\":[{\"token\":\"d1\",\"name\":\"周报\",\"type\":\"docx\",\"url\":\"u\"},{\"token\":\"f1\",\"name\":\"项目\",\"type\":\"folder\"},{\"token\":\"s1\",\"name\":\"x\",\"type\":\"shortcut\",\"shortcut_info\":{\"target_type\":\"sheet\",\"target_token\":\"t9\"}}]}}")
        assertEquals(listOf("项目", "x", "周报"), feishu.map { it.name })
        assertTrue(feishu[0].folder)
        assertEquals("t9", feishu[1].id); assertTrue(feishu[1].feishuOnline)
        val g = CloudApi.parseList(Provider.GDRIVE, "{\"files\":[{\"id\":\"1\",\"name\":\"a.docx\",\"mimeType\":\"application/vnd.openxmlformats-officedocument.wordprocessingml.document\",\"size\":\"120\"},{\"id\":\"2\",\"name\":\"Plan\",\"mimeType\":\"application/vnd.google-apps.document\"},{\"id\":\"3\",\"name\":\"Folder\",\"mimeType\":\"application/vnd.google-apps.folder\"}]}")
        assertEquals("Folder", g[0].name); assertEquals(120L, g[1].size); assertTrue(g[2].readOnly)
        assertEquals("Plan.docx", CloudApi.exportName(g[2]))
        assertTrue(CloudApi.download(Provider.GDRIVE, g[2]).url.contains("/export?"))
        val o = CloudApi.parseList(Provider.ONEDRIVE, "{\"value\":[{\"id\":\"A!1\",\"name\":\"b.xlsx\",\"size\":5,\"file\":{}},{\"id\":\"A!2\",\"name\":\"Docs\",\"folder\":{\"childCount\":2}}]}")
        assertEquals(listOf(true, false), o.map { it.folder })
        val d = CloudApi.parseList(Provider.DROPBOX, "{\"entries\":[{\".tag\":\"file\",\"id\":\"id:x\",\"name\":\"c.pptx\",\"path_lower\":\"/c.pptx\",\"size\":9}],\"has_more\":false}")
        assertEquals("/c.pptx", d[0].path)
        assertEquals("Ann", CloudApi.parseMe(Provider.DROPBOX, "{\"name\":{\"display_name\":\"Ann\"}}"))
        assertEquals("Bo", CloudApi.parseMe(Provider.ONEDRIVE, "{\"displayName\":\"Bo\"}"))
        assertEquals("飞", CloudApi.parseMe(Provider.FEISHU, "{\"code\":0,\"data\":{\"name\":\"飞\"}}"))
        assertEquals("bad (99991663)", CloudApi.feishuError("{\"code\":99991663,\"msg\":\"bad\"}"))
        assertNull(CloudApi.feishuError("{\"code\":0}"))
        // Dropbox list body is valid JSON with an integer limit
        assertEquals(200.0, (Json.parse(CloudApi.list(Provider.DROPBOX, null).body!!) as Map<*, *>)["limit"])
    }

    @Test fun feishuDocRoundTripWritesOnlyChangedBlocks() {
        val body = """{"code":0,"data":{"has_more":false,"items":[
            {"block_id":"doc","block_type":1,"children":["b1","b2","img","b3"]},
            {"block_id":"b1","block_type":3,"heading1":{"elements":[{"text_run":{"content":"标题"}}]}},
            {"block_id":"b2","block_type":2,"text":{"elements":[{"text_run":{"content":"第一段 "}},{"text_run":{"content":"加粗","text_element_style":{"bold":true}}}]}},
            {"block_id":"img","block_type":27,"image":{}},
            {"block_id":"b3","block_type":12,"bullet":{"elements":[{"text_run":{"content":"要点"}}]}}]}}"""
        val (blocks, more) = FeishuDoc.parseBlocks("doc", body)
        assertNull(more)
        assertEquals(listOf("标题", "第一段 加粗", "要点"), blocks.map { it.text })
        assertEquals(listOf(0, 1, 3), blocks.map { it.index })
        val bytes = FeishuDoc.toDocx(blocks)
        assertTrue(FeishuDoc.diff(blocks, bytes).isEmpty())

        val d = DocxDocument.open(bytes)
        val ps = d.blocks.filterIsInstance<Paragraph>()
        d.setParagraph(ps[1].id, listOf(Run("改过的一段")), ps[1].style)
        d.insertParagraphAfter(ps[2].id, listOf(Run("新加的")), ps[2].style)
        val ops = FeishuDoc.diff(blocks, d.save())
        assertEquals(2, ops.size)
        assertEquals("b2", (ops[0] as FeishuDoc.Op.Update).blockId)
        assertEquals(4, (ops[1] as FeishuDoc.Op.Insert).index)
        val req = FeishuDoc.request("doc", ops[0])
        assertEquals("PATCH", req.method)
        assertTrue(req.body!!.contains("改过的一段"))
        assertNotNull(Json.parse(FeishuDoc.request("doc", ops[1]).body!!))
    }

    @Test fun feishuSheetDiffSendsChangedCellsOnly() {
        val values = FeishuSheet.parseValues("{\"code\":0,\"data\":{\"valueRange\":{\"values\":[[\"名称\",\"数量\"],[\"苹果\",3],[null,\"x\"]]}}}")
        assertEquals(listOf(listOf("名称", "数量"), listOf("苹果", "3"), listOf("", "x")), values)
        val bytes = FeishuSheet.toXlsx(values)
        assertNull(FeishuSheet.diffReq("tok", "s1", values, bytes))
        val wb = XlsxWorkbook.open(bytes)
        wb.setCell(0, 1, 1, "5")
        wb.setCell(0, 3, 1, "=SUM(B2:B2)")
        val req = FeishuSheet.diffReq("tok", "s1", values, wb.save())!!
        val ranges = (Json.parse(req.body!!) as Map<*, *>)["valueRanges"] as List<*>
        assertEquals(listOf("s1!B2:B2", "s1!B4:B4"), ranges.map { (it as Map<*, *>)["range"] })
    }

    @Test fun googleMultipartHasMetadataThenFile() {
        val body = String(CloudApi.googleMultipart("a.docx", "XYZ".toByteArray()))
        assertTrue(body.indexOf("\"name\":\"a.docx\"") < body.indexOf("XYZ"))
        assertTrue(body.endsWith("--${CloudApi.BOUNDARY}--\r\n"))
        // sanity: the challenge is the base64url SHA-256 of the verifier
        val v = "abc"
        assertEquals(Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(v.toByteArray())), Pkce.challenge(v))
    }
}
