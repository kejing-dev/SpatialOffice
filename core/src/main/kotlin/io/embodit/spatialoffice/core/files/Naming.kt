package io.embodit.spatialoffice.core.files

/** What a file is opened as. */
enum class FileKind(val ext: String) {
    DOC("docx"), SHEET("xlsx"), SLIDES("pptx"), TEXT("txt"), MARKDOWN("md"), CSV("csv"), PDF("pdf"), IMAGE("png"), HTML("html"), UNKNOWN("");

    /** The editor window that shows it. */
    val editor: FileKind
        get() = when (this) {
            SHEET, CSV -> SHEET
            SLIDES -> SLIDES
            UNKNOWN -> UNKNOWN
            else -> DOC
        }

    companion object {
        fun ofName(name: String): FileKind = when (name.substringAfterLast('.', "").lowercase()) {
            "docx" -> DOC
            "xlsx" -> SHEET
            "pptx" -> SLIDES
            "txt" -> TEXT
            "md", "markdown" -> MARKDOWN
            "csv" -> CSV
            "pdf" -> PDF
            "png", "jpg", "jpeg", "webp" -> IMAGE
            "html", "htm" -> HTML
            else -> UNKNOWN
        }
    }
}

/** File-name rules for new files, first saves, backups and copies. Pure functions; the app supplies directory listings. */
object Naming {
    const val NEW_DIR = "Documents/SpatialOffice"
    private const val MAX_BASE = 40
    private val illegal = Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]")

    /** Name a new, still empty file gets. */
    fun untitled(kind: FileKind, zh: Boolean): String = when (kind) {
        FileKind.SHEET -> if (zh) "未命名表格" else "Untitled spreadsheet"
        FileKind.SLIDES -> if (zh) "未命名演示" else "Untitled presentation"
        else -> if (zh) "未命名文档" else "Untitled document"
    } + "." + kind.ext

    fun isUntitled(name: String): Boolean {
        val base = name.substringBeforeLast('.').replace(Regex(" \\d+$"), "")
        return base in setOf("未命名文档", "未命名表格", "未命名演示", "Untitled document", "Untitled spreadsheet", "Untitled presentation")
    }

    /** File-name base made from the first line of a document or the first slide's title; null when nothing usable is left. */
    fun baseFromText(firstLine: String): String? {
        val s = illegal.replace(firstLine, " ").replace(Regex("\\s+"), " ").trim().trim('.')
        if (s.isEmpty()) return null
        return (if (s.length > MAX_BASE) s.substring(0, MAX_BASE).trimEnd() else s)
    }

    /** `base.ext`, or `base 2.ext`, `base 3.ext`… when taken. [existing] is compared case-insensitively. */
    fun unique(existing: Collection<String>, base: String, ext: String): String {
        val taken = existing.map { it.lowercase() }.toSet()
        val first = "$base.$ext"
        if (first.lowercase() !in taken) return first
        var n = 2
        while ("$base $n.$ext".lowercase() in taken) n++
        return "$base $n.$ext"
    }

    /** Backup written beside a file before its first save: `季度复盘（原件）.docx` / `report (original).docx`. */
    fun backupName(name: String, zh: Boolean): String = tagged(name, if (zh) "（原件）" else " (original)")

    /** Default name for "save as a copy". */
    fun copyName(name: String, zh: Boolean): String = tagged(name, if (zh) "（副本）" else " (copy)")

    /**
     * Name of the copy uploaded beside a Feishu Drive file when the in-place version upload is refused (v0.2.3, user
     * 2026-11-17: "save a copy besides"): `季度复盘 (空间办公 副本).docx` / `report (Spatial Office copy).docx`.
     */
    fun appCopyName(name: String, zh: Boolean): String = tagged(name, if (zh) " (空间办公 副本)" else " (Spatial Office copy)")

    fun withExt(name: String, ext: String): String = name.substringBeforeLast('.', name) + "." + ext

    private fun tagged(name: String, tag: String): String {
        val dot = name.lastIndexOf('.')
        return if (dot <= 0) name + tag else name.substring(0, dot) + tag + name.substring(dot)
    }
}
