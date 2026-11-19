package io.embodit.spatialoffice.core.layout

/**
 * How big a slide is drawn in the space it is given, for any page proportion (16:9, 4:3, 16:10, a tall custom page).
 *
 * v0.2.5 drew the editing canvas 960 dp wide whatever the page: a 4:3 page then came out 720 dp high, the whole
 * height of the column, and the speaker-notes label, the notes field and the picked-object hint under it were left
 * with no height at all (QA #6 q6-1). Now both the width and the height limit the canvas (the smaller ratio decides)
 * and the notes area keeps a height of its own that the canvas cannot take.
 *
 * All lengths are dp. Pure numbers, so the same function lays the window out and is checked by `SlideFitTest`.
 */
object SlideFit {
    data class Size(val w: Float, val h: Float)

    /** Largest [slideW] : [slideH] rectangle inside [availW] × [availH]; the smaller of the two ratios decides. */
    fun fit(availW: Float, availH: Float, slideW: Long, slideH: Long): Size {
        val sw = slideW.coerceAtLeast(1L).toFloat(); val sh = slideH.coerceAtLeast(1L).toFloat()
        val k = minOf(availW.coerceAtLeast(1f) / sw, availH.coerceAtLeast(1f) / sh)
        return Size(sw * k, sh * k)
    }

    /** The editing canvas is never wider than this (what a 16:9 page has had since v0.1). */
    const val CANVAS_MAX_W = 960f
    /** Canvas → notes label row. */
    const val GAP_CANVAS = 12f
    /** The row with the "Speaker notes" label and the picked-object hint. */
    const val LABEL_H = 28f
    /** Label row → notes field. */
    const val GAP_LABEL = 6f
    /** The notes field is at least this high (three lines of text), whatever the page proportion. */
    const val NOTES_MIN_H = 108f
    /** Height under the canvas that the canvas may not take. */
    const val RESERVED_H = GAP_CANVAS + LABEL_H + GAP_LABEL + NOTES_MIN_H

    /**
     * The editor column, top to bottom: canvas (centred), gap, label row, gap, notes field down to the column's
     * bottom. [rowW] is the width of the label row and the notes field: the column's, not the canvas's, so a narrow
     * page does not squeeze the label, the hint and the field.
     */
    data class Editor(val availW: Float, val availH: Float, val canvas: Size, val rowW: Float) {
        val canvasX: Float get() = (availW - canvas.w) / 2f
        val rowX: Float get() = (availW - rowW) / 2f
        val labelY: Float get() = canvas.h + GAP_CANVAS
        val notesY: Float get() = labelY + LABEL_H + GAP_LABEL
        val notesH: Float get() = availH - notesY
        /** Canvas, label row and notes field all inside the column, none over another, the field at its least height or more. */
        val fits: Boolean
            get() = canvas.w <= availW + EPS && canvasX >= -EPS && rowW <= availW + EPS &&
                labelY >= canvas.h && notesY >= labelY + LABEL_H && notesH >= NOTES_MIN_H - EPS && notesY + notesH <= availH + EPS
    }

    fun editor(availW: Float, availH: Float, slideW: Long, slideH: Long): Editor {
        val rowW = minOf(CANVAS_MAX_W, availW)
        val canvas = fit(rowW, (availH - RESERVED_H).coerceAtLeast(1f), slideW, slideH)
        return Editor(availW, availH, canvas, rowW)
    }

    /** What v0.2.5 did (960 dp wide, the height following): kept so the test shows the notes field's height it left. */
    fun editorV025(availW: Float, availH: Float, slideW: Long, slideH: Long): Editor =
        Editor(availW, availH, Size(CANVAS_MAX_W, CANVAS_MAX_W * slideH / slideW.coerceAtLeast(1L)), CANVAS_MAX_W)

    /** Thumbnail in the left list: 170 dp wide, and no higher than a 4:3 page is at that width. */
    const val THUMB_W = 170f
    const val THUMB_MAX_H = 128f
    /** Border and padding around a thumbnail (3 dp each side) and the space between two rows of the list. */
    const val THUMB_FRAME = 6f
    const val THUMB_SPACING = 16f

    fun thumb(slideW: Long, slideH: Long): Size = fit(THUMB_W, THUMB_MAX_H, slideW, slideH)

    /** Top of one thumbnail row to the top of the next: one step of the reorder drag. */
    fun thumbStep(slideW: Long, slideH: Long): Float = thumb(slideW, slideH).h + THUMB_FRAME + THUMB_SPACING

    /** The row the reorder drag lets go over, having started on row [from] and travelled [dy] dp. */
    fun dropIndex(from: Int, dy: Float, count: Int, slideW: Long, slideH: Long): Int =
        (from + Math.round(dy / thumbStep(slideW, slideH))).coerceIn(0, (count - 1).coerceAtLeast(0))

    private const val EPS = 0.01f
}
