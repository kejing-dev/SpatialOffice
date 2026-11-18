package io.embodit.spatialoffice.core.layout

/**
 * Which files are open, each in a window of its own (v0.2.5, headset feedback 2026-11-17: "word 文档也不能多开").
 * Until v0.2.4 there was one window per kind, and opening a second document replaced the first. Now every open file
 * has an entry with the tag of its own window; opening a file that is already open answers [Open.Front] (bring that
 * window forward) instead of a second copy. At most [MAX_PER_KIND] files of one kind are open at a time.
 *
 * Pure. The app keeps the sessions and the windows; this decides.
 */
class OpenFiles(private val maxPerKind: Int = MAX_PER_KIND) {
    data class Entry(val tag: String, val kind: String, val key: String)

    sealed class Open {
        /** The file is open already: bring its window forward. */
        data class Front(val entry: Entry) : Open()
        /** A new window for this file; nothing that was open is closed. */
        data class New(val entry: Entry) : Open()
        /** [open] files of this kind are open, which is the most there is room for in view. */
        data class Full(val kind: String, val open: Int) : Open()
    }

    /** Oldest first; the last entry of a kind is the active one (the window used last). */
    private val list = ArrayList<Entry>()
    private var seq = 0

    val entries: List<Entry> get() = list.toList()

    fun byTag(tag: String): Entry? = list.firstOrNull { it.tag == tag }
    fun byKey(key: String): Entry? = list.firstOrNull { it.key == key }
    fun ofKind(kind: String): List<Entry> = list.filter { it.kind == kind }
    /** The entry of [kind] used last (toolbar-less commands such as keyboard shortcuts from the Stage act on it). */
    fun active(kind: String): Entry? = list.lastOrNull { it.kind == kind }

    fun open(kind: String, key: String): Open {
        byKey(key)?.let { activate(it.tag); return Open.Front(it) }
        val same = ofKind(kind)
        if (same.size >= maxPerKind) return Open.Full(kind, same.size)
        val e = Entry("f${++seq}", kind, key)
        list.add(e)
        return Open.New(e)
    }

    /** The window with [tag] was used: it becomes the active one of its kind. */
    fun activate(tag: String): Boolean {
        val i = list.indexOfFirst { it.tag == tag }
        if (i < 0) return false
        if (i != list.lastIndex) list.add(list.removeAt(i))
        return true
    }

    fun close(tag: String): Entry? {
        val i = list.indexOfFirst { it.tag == tag }
        return if (i < 0) null else list.removeAt(i)
    }

    /** The file was saved under another name (first save of a new file, cloud copy): same window, new key. */
    fun rekey(tag: String, key: String) {
        val i = list.indexOfFirst { it.tag == tag }
        if (i >= 0) list[i] = list[i].copy(key = key)
    }

    companion object {
        /** Most open files of one kind (each is a window with its whole file in memory). */
        const val MAX_PER_KIND = 5
    }
}
