package io.embodit.spatialoffice.core.docx

enum class ParaStyle { BODY, TITLE, H1, H2, H3, BULLET, NUMBERED }

/**
 * One stretch of text with uniform bold / italic / underline. The three flags are the run's own formatting (set
 * on the run or by its character style); a heading that is bold because of its paragraph style has `bold = false`.
 * [src] = index of the original run (in this paragraph as opened) whose remaining formatting (font, size, colour…)
 * this run keeps; -1 = take it from the paragraph's first original run, or none.
 * Inside [text]: `\t` is a tab, `\n` a line break inside the paragraph, `\u000C` a page break.
 */
data class Run(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val src: Int = -1,
)

sealed class DocBlock { abstract val id: Int }

/** [readOnlyWhy] is null when [editable], otherwise "tracked-changes" | "field" | "complex". */
data class Paragraph(
    override val id: Int,
    val style: ParaStyle,
    val runs: List<Run>,
    val editable: Boolean,
    val readOnlyWhy: String?,
) : DocBlock() {
    val text: String get() = runs.joinToString("") { it.text }
}

/** Read-only in v0.1. A horizontally merged cell is followed by empty strings so every row lines up with the grid. */
data class TableBlock(override val id: Int, val rows: List<List<String>>) : DocBlock()

data class ImageBlock(override val id: Int, val part: String, val widthEmu: Long, val heightEmu: Long) : DocBlock()

/** [kind]: "smart-graphic" | "chart" | "text-box" | "section" | "other". */
data class OpaqueBlock(override val id: Int, val kind: String) : DocBlock()

enum class CompatKind { APPROX, READ_ONLY }

/** code examples: "columns", "text-box", "font:<name>", "smart-graphic", "tracked-changes", "comments", "chart" */
data class CompatNote(val kind: CompatKind, val code: String, val count: Int)
