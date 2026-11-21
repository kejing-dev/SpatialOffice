package io.embodit.spatialoffice.platform

import android.content.Context
import android.util.Log
import java.io.File
import java.security.MessageDigest

/**
 * d28 (M2): local version history, the same app-data storage as the d27 drafts. Every save keeps the saved file
 * whole in `files/history/<sha1 of the file key>/<millis>.<kind>.v` ([KIND_SAVE]); restoring an older version first
 * keeps what the window held then ([KIND_BEFORE_RESTORE]), so nothing is lost by a restore. The newest [KEEP] stay.
 * Nothing is written next to the user's file.
 */
class History(private val root: File) {
    constructor(context: Context) : this(File(context.filesDir, "history"))

    data class Version(val file: File, val at: Long, val kind: String, val size: Long)

    private fun dirOf(key: String): File = File(root,
        MessageDigest.getInstance("SHA-1").digest(key.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) })

    /** Newest first. */
    fun list(key: String): List<Version> = (dirOf(key).listFiles { f -> f.name.endsWith(".v") } ?: emptyArray())
        .mapNotNull { f ->
            val parts = f.name.removeSuffix(".v").split('.')
            val at = parts.getOrNull(0)?.toLongOrNull() ?: return@mapNotNull null
            Version(f, at, parts.getOrNull(1) ?: KIND_SAVE, f.length())
        }
        .sortedByDescending { it.at }

    fun read(v: Version): ByteArray? = runCatching { v.file.readBytes() }.getOrNull()

    /**
     * Called on IO. A save identical to the newest version is not kept twice; a before-restore copy always is.
     * Returns the version written, or null.
     */
    fun add(key: String, bytes: ByteArray, kind: String = KIND_SAVE, now: Long = System.currentTimeMillis()): Version? = runCatching {
        val dir = dirOf(key).also { it.mkdirs() }
        val all = list(key)
        if (kind == KIND_SAVE && all.firstOrNull()?.let { read(it)?.contentEquals(bytes) } == true) return@runCatching null
        // strictly increasing times, so two versions in one millisecond keep their order
        val at = maxOf(now, (all.firstOrNull()?.at ?: 0L) + 1)
        val f = File(dir, "$at.$kind.v")
        val tmp = File(dir, f.name + ".tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        File(dir, "key").writeText(key)
        for (old in list(key).drop(KEEP)) old.file.delete()
        Log.i(TAG, "HISTORY kept ${dir.name.take(8)} $kind ${bytes.size} B (${list(key).size} versions)")
        Version(f, at, kind, f.length())
    }.onFailure { Log.w(TAG, "HISTORY write failed: $it") }.getOrNull()

    /**
     * [Restore] on [v] for the open file [s]: what the window holds now is kept first (always, as "before restore"), then
     * the old version comes back as an unsaved change, one undo step ([Session.recover]). False when it could not be read.
     */
    fun restore(s: Session, v: Version): Boolean {
        val bytes = read(v) ?: return false
        val now = runCatching { s.serialize() }.getOrNull() ?: return false
        add(s.ref.key, now, KIND_BEFORE_RESTORE)
        return bytes.contentEquals(now) || s.recover(bytes)
    }

    /** A file renamed by its first save takes its history along. */
    fun move(oldKey: String, newKey: String) {
        if (oldKey == newKey) return
        runCatching {
            val from = dirOf(oldKey); if (!from.isDirectory) return
            val to = dirOf(newKey); to.mkdirs()
            from.listFiles()?.forEach { f -> if (f.name.endsWith(".v")) f.renameTo(File(to, f.name)) }
            File(to, "key").writeText(newKey)
            from.deleteRecursively()
        }
    }

    companion object {
        const val KEEP = 10
        const val KIND_SAVE = "save"
        const val KIND_BEFORE_RESTORE = "restore"
    }
}
