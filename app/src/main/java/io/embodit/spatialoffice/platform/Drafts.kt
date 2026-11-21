package io.embodit.spatialoffice.platform

import android.content.Context
import android.util.Log
import java.io.File
import java.security.MessageDigest

/**
 * d27: autosaved drafts. Every [PERIOD_MS] each open file with unsaved changes that changed since its last draft is
 * written whole to app data (`files/drafts/<sha1 of the file key>.draft`, plus `.key` holding the key). Nothing is
 * written next to the user's file and the file itself is never touched. Opening a file that has a draft asks
 * "Recover unsaved changes?"; saving, discarding or declining deletes it. A crash or a force-stop leaves the draft.
 */
class Drafts(context: Context) {
    private val dir = File(context.filesDir, "drafts")

    private fun name(key: String): String =
        MessageDigest.getInstance("SHA-1").digest(key.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private fun file(key: String) = File(dir, name(key) + ".draft")

    fun has(key: String): Boolean = file(key).let { it.isFile && it.length() > 0 }

    fun read(key: String): ByteArray? = runCatching { file(key).takeIf { it.isFile }?.readBytes() }.getOrNull()

    /** Called on IO. Written to a temporary file first, so a crash mid-write never leaves half a draft. */
    fun write(key: String, bytes: ByteArray) {
        runCatching {
            dir.mkdirs()
            val f = file(key)
            val tmp = File(dir, f.name + ".tmp")
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
            File(dir, name(key) + ".key").writeText(key)
        }.onFailure { Log.w(TAG, "DRAFT write failed: $it") }
    }

    fun delete(key: String) {
        val f = file(key)
        val had = f.exists()
        f.delete(); File(dir, name(key) + ".key").delete()
        if (had) Log.i(TAG, "DRAFT deleted ${name(key).take(8)}")
    }

    fun count(): Int = dir.listFiles { f -> f.name.endsWith(".draft") }?.size ?: 0

    companion object {
        const val PERIOD_MS = 3000L
    }
}
