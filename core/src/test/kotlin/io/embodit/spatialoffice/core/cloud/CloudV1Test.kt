package io.embodit.spatialoffice.core.cloud

import io.embodit.spatialoffice.core.layout.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** q1-7 (PRD §9.2 ③a scan, §9.3 conflict, §9.4 inbox) and the Feishu in-place save. Request shapes and answer parsing. */
class CloudV1Test {
    private val reg = AppRegistration("cli_a1", "sec", "http://127.0.0.1:53682/callback")

    @Test fun scanIsOfferedWhereTheServiceHasADeviceFlowForOurPermissions() {
        assertTrue(DeviceAuth.supported(Provider.FEISHU))
        assertTrue(DeviceAuth.supported(Provider.ONEDRIVE))
        assertFalse(DeviceAuth.supported(Provider.GDRIVE))   // device flow only allows drive.file
        assertFalse(DeviceAuth.supported(Provider.DROPBOX))
        assertEquals("https://accounts.feishu.cn/oauth/v1/device_authorization", DeviceAuth.startUrl(Provider.FEISHU))
        val q = Http.query("x?" + DeviceAuth.startBody(Provider.FEISHU, reg))
        assertEquals("cli_a1", q["client_id"]); assertEquals(Provider.FEISHU.scopes, q["scope"])
        val ms = Http.query("x?" + DeviceAuth.startBody(Provider.ONEDRIVE, reg))
        assertFalse(ms.containsKey("client_secret"))          // public client: no secret
    }

    @Test fun deviceCodeAnswerAndPolls() {
        val c = DeviceAuth.parseStart("""{"device_code":"D1","user_code":"ABCD-EFGH","verification_uri":"https://x/verify","verification_uri_complete":"https://x/verify?c=ABCD","expires_in":600,"interval":5}""", 1000)
        assertEquals("D1", c.deviceCode); assertEquals("ABCD-EFGH", c.userCode)
        assertEquals("https://x/verify?c=ABCD", c.qrText)
        assertEquals(1000 + 600_000L, c.expiresAt); assertEquals(5000L, c.intervalMs)
        // Microsoft gives no complete address: the QR holds the plain one
        val m = DeviceAuth.parseStart("""{"device_code":"D2","user_code":"XYZ","verification_uri":"https://microsoft.com/devicelogin","expires_in":900,"interval":5}""", 0)
        assertEquals("https://microsoft.com/devicelogin", m.qrText)
        val (type, body) = DeviceAuth.pollBody(Provider.FEISHU, reg, c)
        assertEquals("application/json", type)
        val jb = Json.parse(body) as Map<*, *>
        assertEquals("urn:ietf:params:oauth:grant-type:device_code", jb["grant_type"]); assertEquals("D1", jb["device_code"])
        assertEquals(DeviceAuth.Poll.Pending, DeviceAuth.parsePoll("""{"error":"authorization_pending"}""", 0))
        assertEquals(DeviceAuth.Poll.SlowDown, DeviceAuth.parsePoll("""{"error":"slow_down"}""", 0))
        assertTrue(DeviceAuth.parsePoll("""{"error":"access_denied"}""", 0) is DeviceAuth.Poll.Ended)
        assertTrue(DeviceAuth.parsePoll("""{"error":"expired_token"}""", 0) is DeviceAuth.Poll.Ended)
        val done = DeviceAuth.parsePoll("""{"access_token":"A","refresh_token":"R","expires_in":7200}""", 0)
        assertEquals(Tokens("A", "R", 7_200_000L), (done as DeviceAuth.Poll.Done).tokens)
    }

    @Test fun versionStampsOfEachServiceAndTheConflictRule() {
        assertEquals("17", CloudVersion.parse(Provider.FEISHU, "docx", """{"code":0,"data":{"document":{"document_id":"d","revision_id":17}}}"""))
        assertEquals("9", CloudVersion.parse(Provider.FEISHU, "sheet", """{"code":0,"data":{"properties":{"revision":9}}}"""))
        assertEquals("1700000000", CloudVersion.parse(Provider.FEISHU, "file", """{"code":0,"data":{"metas":[{"doc_token":"b","latest_modify_time":"1700000000"}]}}"""))
        assertEquals("42", CloudVersion.parse(Provider.GDRIVE, "file", """{"version":"42","modifiedTime":"2026-11-17T00:00:00Z"}"""))
        assertEquals("\"c:{X},3\"", CloudVersion.parse(Provider.ONEDRIVE, "file", """{"cTag":"\"c:{X},3\"","eTag":"e"}"""))
        assertEquals("015f", CloudVersion.parse(Provider.DROPBOX, "file", """{"rev":"015f"}"""))
        assertEquals("", CloudVersion.parse(Provider.DROPBOX, "file", "not json"))
        assertTrue(CloudVersion.changed("17", "18"))
        assertFalse(CloudVersion.changed("17", "17"))
        assertFalse(CloudVersion.changed(null, "18"))   // never read: nothing to compare, do not block
        assertFalse(CloudVersion.changed("17", ""))     // service gave no stamp: do not block
        assertTrue(CloudVersion.request(Provider.FEISHU, "boxcn1", "file").body!!.contains("\"doc_type\":\"file\""))
        assertTrue(CloudVersion.request(Provider.FEISHU, "dox1", "docx").url.endsWith("/docx/v1/documents/dox1"))
    }

    @Test fun feishuDriveFileIsWrittenInPlaceWithItsOwnToken() {
        val req = CloudApi.upload(Provider.FEISHU, CloudItem("boxcnOLD", "a.docx", false, 0, ""))
        assertEquals("POST", req.method)
        assertTrue(req.url.endsWith("/drive/v1/files/upload_all"))
        val body = String(FeishuDrive.inPlaceMultipart("a.docx", "fldX", "boxcnOLD", "hello".toByteArray()), Charsets.UTF_8)
        assertTrue(body.contains("name=\"file_token\"\r\n\r\nboxcnOLD\r\n"))
        assertTrue(body.contains("name=\"parent_node\"\r\n\r\nfldX\r\n"))
        assertTrue(body.contains("hello"))
        // a plain new upload carries no file_token
        assertFalse(String(CloudApi.feishuMultipart("a.docx", "fldX", ByteArray(1)), Charsets.UTF_8).contains("file_token"))
        assertTrue(FeishuDrive.inPlaceUnsupported("field validation failed (1061002)"))
        assertFalse(FeishuDrive.inPlaceUnsupported("HTTP 500 server busy"))
    }

    @Test fun inboxFoldersAndNewArrivals() {
        val req = FeishuDrive.createFolder("收件夹", "fldApp")
        val m = Json.parse(req.body!!) as Map<*, *>
        assertEquals("收件夹", m["name"]); assertEquals("fldApp", m["folder_token"])
        assertEquals("fldNew", FeishuDrive.parseCreated("""{"code":0,"data":{"token":"fldNew","url":"u"}}"""))
        val items = CloudApi.parseList(Provider.FEISHU, """{"code":0,"data":{"files":[
            {"token":"fldA","name":"空间办公","type":"folder"},
            {"token":"s1","name":"周报","type":"shortcut","shortcut_info":{"target_type":"docx","target_token":"docxT"}},
            {"token":"b1","name":"x.pptx","type":"file"}]}}""")
        assertEquals("fldA", FeishuDrive.find(items, "空间办公")!!.id)
        val fresh = FeishuDrive.newArrivals(items, setOf("b1"))
        assertEquals(listOf("docxT"), fresh.map { it.id })    // the shortcut points at the document itself
        assertEquals("docx", fresh[0].kind)
    }

    @Test fun conflictCopyGoesIntoTheSameFolder() {
        val od = CloudApi.createIn(Provider.ONEDRIVE, "a（副本）.docx", "ITEM1")
        assertTrue(od.url.contains("/items/ITEM1:/") && od.url.contains("conflictBehavior=rename"))
        val db = CloudApi.createIn(Provider.DROPBOX, "a (copy).docx", "/work")
        assertTrue(db.headers.getValue("Dropbox-API-Arg").contains("\"path\":\"/work/a (copy).docx\""))
        assertTrue(db.headers.getValue("Dropbox-API-Arg").contains("\"mode\":\"add\""))
        val g = String(CloudApi.googleMultipart("a.docx", ByteArray(0), "PARENT"), Charsets.UTF_8)
        assertTrue(g.contains("\"parents\":[\"PARENT\"]"))
    }
}
