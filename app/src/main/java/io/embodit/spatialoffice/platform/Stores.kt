package io.embodit.spatialoffice.platform

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.embodit.spatialoffice.core.files.FileKind
import io.embodit.spatialoffice.core.layout.Json
import io.embodit.spatialoffice.core.layout.LayoutRecord
import java.io.File
import java.util.Locale

enum class Lang { ZH, EN }
enum class OpenMode { WINDOW, SPREAD }
enum class LayoutStoreMode { DEVICE, FILE }

data class Settings(
    val lang: Lang = if (Locale.getDefault().language == "zh") Lang.ZH else Lang.EN,
    val openMode: OpenMode = OpenMode.WINDOW,
    val layoutStore: LayoutStoreMode = LayoutStoreMode.DEVICE,
    val keepBackup: Boolean = true,
    val radius: Int = 1,
    val hintSeen: Boolean = false,
) {
    val zh: Boolean get() = lang == Lang.ZH
}

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    var value by mutableStateOf(load())
        private set

    private fun load(): Settings {
        val d = Settings()
        return Settings(
            lang = prefs.getString("lang", null)?.let { n -> Lang.values().firstOrNull { it.name == n } } ?: d.lang,
            openMode = prefs.getString("open", null)?.let { n -> OpenMode.values().firstOrNull { it.name == n } } ?: d.openMode,
            layoutStore = prefs.getString("store", null)?.let { n -> LayoutStoreMode.values().firstOrNull { it.name == n } } ?: d.layoutStore,
            keepBackup = prefs.getBoolean("keep", d.keepBackup),
            radius = prefs.getInt("radius", d.radius).coerceIn(0, 2),
            hintSeen = prefs.getBoolean("hint", false),
        )
    }

    fun update(f: (Settings) -> Settings) {
        val s = f(value)
        value = s
        prefs.edit().putString("lang", s.lang.name).putString("open", s.openMode.name).putString("store", s.layoutStore.name)
            .putBoolean("keep", s.keepBackup).putInt("radius", s.radius).putBoolean("hint", s.hintSeen).apply()
    }
}

data class Recent(val key: String, val name: String, val display: String, val time: Long, val isUri: Boolean, val cloud: String? = null) {
    val kind: FileKind get() = FileKind.ofName(name)
    fun ref(): FileRef = cloud?.let { FileRef.Cloud.fromJson(it) } ?: if (isUri) FileRef.Content(Uri.parse(key), name) else FileRef.Path(File(key))
}

/** Recently opened files, newest first, at most 20. Entries whose file is gone are dropped when listed. */
class RecentStore(context: Context) {
    private val prefs = context.getSharedPreferences("recents", Context.MODE_PRIVATE)
    var items by mutableStateOf(load())
        private set

    private fun load(): List<Recent> = runCatching {
        (Json.parse(prefs.getString("list", "[]") ?: "[]") as List<*>).mapNotNull { e ->
            val m = e as? Map<*, *> ?: return@mapNotNull null
            Recent(m["key"] as String, m["name"] as String, m["display"] as? String ?: "", (m["time"] as Double).toLong(), m["uri"] as? Boolean ?: false, m["cloud"] as? String)
        }
    }.getOrDefault(emptyList())

    private fun store(list: List<Recent>) {
        items = list
        prefs.edit().putString("list", Json.write(list.map { mapOf("key" to it.key, "name" to it.name, "display" to it.display, "time" to it.time, "uri" to it.isUri, "cloud" to it.cloud) })).apply()
    }

    fun touch(ref: FileRef, display: String) {
        val r = Recent(ref.key, ref.name, display, System.currentTimeMillis(), ref !is FileRef.Path, (ref as? FileRef.Cloud)?.toJson())
        store((listOf(r) + items.filter { it.key != ref.key }).take(20))
    }

    fun remove(key: String) = store(items.filter { it.key != key })

    fun rename(oldKey: String, ref: FileRef, display: String) {
        store(items.map { if (it.key == oldKey) it.copy(key = ref.key, name = ref.name, display = display) else it })
    }

    /** Drops path entries whose file no longer exists. */
    fun prune() {
        val kept = items.filter { it.isUri || File(it.key).exists() }
        if (kept.size != items.size) store(kept)
    }
}

/**
 * Layout records (PRD §3, §6): never inside the document. Kept in app data by file key; when the user chose
 * "with the file" and the folder is writable, also as a sidecar `<file>.spatial.json`, which wins on load.
 */
class LayoutStore(context: Context, private val storage: Storage) {
    private val prefs = context.getSharedPreferences("layouts", Context.MODE_PRIVATE)
    private val backups = context.getSharedPreferences("backups", Context.MODE_PRIVATE)

    fun load(ref: FileRef): LayoutRecord? {
        storage.sidecar(ref)?.takeIf { it.exists() }?.let { f -> runCatching { LayoutRecord.parse(f.readText()) }.getOrNull()?.let { return it } }
        return prefs.getString(ref.key, null)?.let { LayoutRecord.parse(it) }
    }

    /** Returns true when a sidecar was written. */
    fun save(ref: FileRef, record: LayoutRecord, withFile: Boolean): Boolean {
        prefs.edit().putString(ref.key, record.toJson()).apply()
        val side = storage.sidecar(ref) ?: return false
        if (withFile || side.exists()) return runCatching { side.writeText(record.toJson()); true }.getOrDefault(false)
        return false
    }

    fun move(oldKey: String, newKey: String) {
        prefs.getString(oldKey, null)?.let { prefs.edit().remove(oldKey).putString(newKey, it).apply() }
        backups.getString(oldKey, null)?.let { backups.edit().remove(oldKey).putString(newKey, it).apply() }
    }

    /** Path of the backup made before the first save of this file, if any. */
    fun backupOf(key: String): String? = backups.getString(key, null)
    fun markBackedUp(key: String, backupPath: String) = backups.edit().putString(key, backupPath).apply()
}
