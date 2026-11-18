package io.embodit.spatialoffice.core.cloud

import io.embodit.spatialoffice.core.files.Naming
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.2.3, user 2026-11-17 "save a copy besides": when Feishu refuses the in-place version upload, the original is kept
 * untouched and the edited file goes beside it as a copy. Replays the save path the app runs (Accounts.write).
 */
class FeishuFallbackTest {
    /** A tiny Feishu folder: the uploads and deletions the app asks for. */
    private class Drive(val files: MutableMap<String, String> = linkedMapOf("orig" to "季度复盘.docx")) {
        val deleted = ArrayList<String>()
        private var n = 0
        fun upload(name: String): String { val t = "copy${++n}"; files[t] = name; return t }
        fun delete(token: String) { deleted.add(token); files.remove(token) }
    }

    /** One save after the in-place upload was refused; returns the token the editor continues with. */
    private fun save(d: Drive, token: String, own: MutableSet<String>, zh: Boolean = true): String {
        val name = d.files.getValue(token)
        val fb = FeishuDrive.fallback(name, token, zh, own)
        val t = d.upload(fb.uploadName)
        own.add(t)
        fb.replaceOwnCopy?.let { d.delete(it); own.remove(it) }
        return t
    }

    @Test fun refusedInPlaceUploadKeepsTheOriginalAndUploadsACopyBeside() {
        assertTrue(FeishuDrive.inPlaceUnsupported("upload_all failed (1061002) params error"))
        val d = Drive(); val own = HashSet<String>()
        val now = save(d, "orig", own)
        assertEquals("季度复盘.docx", d.files["orig"])          // original still there, same name
        assertTrue(d.deleted.isEmpty())                        // nothing deleted
        assertEquals("季度复盘 (空间办公 副本).docx", d.files[now])
        val fb = FeishuDrive.fallback("季度复盘.docx", "orig", true, emptySet())
        assertNull(fb.replaceOwnCopy)
    }

    @Test fun laterSavesReplaceOnlyTheAppsOwnCopyNeverTheOriginal() {
        val d = Drive(); val own = HashSet<String>()
        var cur = save(d, "orig", own)
        repeat(3) { cur = save(d, cur, own) }
        assertTrue("orig" in d.files)
        assertFalse("orig" in d.deleted)
        assertEquals(listOf("copy1", "copy2", "copy3"), d.deleted)  // only copies this app made
        assertEquals(2, d.files.size)                               // the original + one current copy
        assertEquals("季度复盘 (空间办公 副本).docx", d.files[cur])  // the copy keeps one tag, not a growing name
    }

    @Test fun aFileThatOnlyLooksLikeACopyIsStillTreatedAsAnOriginal() {
        // a user's own file named like our copy, opened in a new session: not in ownCopies, so never deleted
        val fb = FeishuDrive.fallback("季度复盘 (空间办公 副本).docx", "users-file", true, setOf("copy9"))
        assertNull(fb.replaceOwnCopy)
    }

    @Test fun englishCopyName() {
        assertEquals("report (Spatial Office copy).docx", Naming.appCopyName("report.docx", false))
        assertEquals("报表 (空间办公 副本).xlsx", Naming.appCopyName("报表.xlsx", true))
    }
}
