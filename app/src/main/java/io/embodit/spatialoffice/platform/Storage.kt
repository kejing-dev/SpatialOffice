package io.embodit.spatialoffice.platform

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.OpenableColumns
import android.provider.Settings
import io.embodit.spatialoffice.core.files.FileKind
import io.embodit.spatialoffice.core.files.Naming
import io.embodit.spatialoffice.core.layout.LayoutRecord
import java.io.File

/** A file the app has open: a plain path, or a content URI handed over by the system picker / Share / Open with. */
sealed class FileRef {
    abstract val name: String
    /** Stable key for recents, backups and layout records. */
    abstract val key: String

    data class Path(val file: File) : FileRef() {
        override val name: String get() = file.name
        override val key: String get() = file.absolutePath
    }

    data class Content(val uri: Uri, override val name: String) : FileRef() {
        override val key: String get() = uri.toString()
    }
}

data class DirEntry(val file: File, val isDir: Boolean, val size: Long, val modified: Long) {
    val kind: FileKind get() = if (isDir) FileKind.UNKNOWN else FileKind.ofName(file.name)
}

/**
 * File access. New files and anything this app created live in shared storage (`Documents/SpatialOffice`) and
 * need no permission. Files other apps put in Download / Documents are visible only with "All files access";
 * without it the system picker or Share / Open with still hands single files over as content URIs.
 */
class Storage(private val context: Context) {
    val shared: File get() = Environment.getExternalStorageDirectory()
    val newDir: File get() = File(shared, Naming.NEW_DIR)
    val downloadDir: File get() = File(shared, Environment.DIRECTORY_DOWNLOADS)
    val documentsDir: File get() = File(shared, Environment.DIRECTORY_DOCUMENTS)
    /** Always writable; used for backups of files whose folder cannot be written. */
    val privateDir: File get() = File(context.getExternalFilesDir(null) ?: context.filesDir, "backups").apply { mkdirs() }

    val hasAllFiles: Boolean get() = runCatching { Environment.isExternalStorageManager() }.getOrDefault(false)

    /** Opens the system page where the user can allow "All files access". False when the device has no such page. */
    fun requestAllFiles(): Boolean {
        val pkg = Uri.parse("package:${context.packageName}")
        for (action in listOf(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) {
            val i = Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (action == Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION) i.data = pkg
            if (i.resolveActivity(context.packageManager) != null && runCatching { context.startActivity(i) }.isSuccess) return true
        }
        return false
    }

    fun read(ref: FileRef): ByteArray = when (ref) {
        is FileRef.Path -> ref.file.readBytes()
        is FileRef.Content -> context.contentResolver.openInputStream(ref.uri)?.use { it.readBytes() } ?: error("cannot read")
    }

    fun write(ref: FileRef, bytes: ByteArray) {
        when (ref) {
            is FileRef.Path -> {
                ref.file.parentFile?.mkdirs()
                // write beside, then swap in, so a failed write never leaves half a file
                val tmp = File(ref.file.parentFile, ".${ref.file.name}.tmp")
                try {
                    tmp.writeBytes(bytes)
                    if (!tmp.renameTo(ref.file)) { ref.file.writeBytes(bytes); tmp.delete() }
                } catch (e: Exception) {
                    tmp.delete()
                    ref.file.writeBytes(bytes)
                }
            }
            is FileRef.Content -> context.contentResolver.openOutputStream(ref.uri, "wt")?.use { it.write(bytes) } ?: error("cannot write")
        }
    }

    fun canWrite(ref: FileRef): Boolean = when (ref) {
        is FileRef.Path -> if (ref.file.exists()) ref.file.canWrite() else ref.file.parentFile?.let { it.canWrite() || it.mkdirs() } ?: false
        is FileRef.Content -> runCatching { context.contentResolver.openOutputStream(ref.uri, "wa")?.close(); true }.getOrDefault(false)
    }

    /** `Download/季度复盘.docx` for shared storage, the bare name for content URIs. */
    fun display(ref: FileRef): String = when (ref) {
        is FileRef.Path -> display(ref.file)
        is FileRef.Content -> ref.name
    }

    fun display(file: File): String {
        val root = shared.absolutePath
        val p = file.absolutePath
        return if (p.startsWith("$root/")) p.substring(root.length + 1) else if (p == root) "/" else file.name
    }

    /** Folders and openable files of [dir], folders first, hidden entries skipped. */
    fun list(dir: File): List<DirEntry> = (dir.listFiles() ?: emptyArray())
        .filter { !it.name.startsWith(".") && !it.name.endsWith(LayoutRecord.SIDECAR_SUFFIX) }
        .filter { it.isDirectory || FileKind.ofName(it.name) != FileKind.UNKNOWN }
        .map { DirEntry(it, it.isDirectory, if (it.isDirectory) 0 else it.length(), it.lastModified()) }
        .sortedWith(compareBy<DirEntry>({ !it.isDir }, { it.file.name.lowercase() }))

    /** Creates an empty placeholder with a free name in [dir] and returns it. */
    fun freeFile(dir: File, base: String, ext: String): File {
        dir.mkdirs()
        val taken = dir.list()?.toList() ?: emptyList()
        return File(dir, Naming.unique(taken, base, ext))
    }

    /**
     * Copies the current bytes of [ref] to a backup beside it (`name（原件）.ext`), or into the app's own folder when the
     * place cannot be written. Returns the backup file, or null when nothing could be written.
     */
    fun backup(ref: FileRef, original: ByteArray, zh: Boolean): File? {
        val name = Naming.backupName(ref.name, zh)
        if (ref is FileRef.Path) {
            val beside = File(ref.file.parentFile, name)
            if (runCatching { beside.writeBytes(original) }.isSuccess) return beside
        }
        val own = File(privateDir, name)
        return if (runCatching { own.writeBytes(original) }.isSuccess) own else null
    }

    /** Where [backup] would put the backup, without writing. */
    fun backupTarget(ref: FileRef, zh: Boolean): Pair<File, Boolean> {
        val name = Naming.backupName(ref.name, zh)
        if (ref is FileRef.Path) {
            val dir = ref.file.parentFile
            if (dir != null && dir.canWrite()) return File(dir, name) to true
        }
        return File(privateDir, name) to false
    }

    /** Resolves an incoming VIEW / SEND intent to a file reference. */
    fun fromIntent(intent: Intent?): FileRef? {
        intent ?: return null
        val uri: Uri = when (intent.action) {
            Intent.ACTION_VIEW, Intent.ACTION_EDIT -> intent.data
            Intent.ACTION_SEND -> @Suppress("DEPRECATION") (intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri)
            else -> null
        } ?: return null
        if (uri.scheme == "file") return uri.path?.let { FileRef.Path(File(it)) }
        if (uri.scheme != "content") return null
        runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
        return FileRef.Content(uri, queryName(uri) ?: uri.lastPathSegment?.substringAfterLast('/') ?: "file")
    }

    private fun queryName(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()

    fun sidecar(ref: FileRef): File? = (ref as? FileRef.Path)?.let { File(it.file.parentFile, it.file.name + LayoutRecord.SIDECAR_SUFFIX) }
}
