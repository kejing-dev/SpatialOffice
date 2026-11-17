package io.embodit.spatialoffice.core.pptx

enum class ShapeKind { TITLE, BODY, TEXT, PICTURE, CHART, OTHER }

/** One paragraph of a shape. A `\n` inside [text] is a line break within the paragraph. */
data class TextPara(val text: String, val level: Int = 0, val bullet: Boolean = false)

/**
 * One top-level shape of a slide. x / y / w / h in EMU, resolved through layout and master placeholders when the
 * slide omits `a:xfrm`. [fontPt], [bold], [colorRgb] and [align] describe the first text run / paragraph.
 * [align]: 0 left, 1 centre, 2 right. [colorRgb] is 0xRRGGBB.
 */
data class Shape(
    val id: Int, val kind: ShapeKind, val x: Long, val y: Long, val w: Long, val h: Long,
    val paras: List<TextPara>, val fontPt: Float?, val bold: Boolean, val colorRgb: Int?,
    val align: Int, val imagePart: String?, val editable: Boolean,
    /** Preset geometry of a drawn shape ("rect", "ellipse", "roundRect", "triangle", "rightArrow" …); null for none / text boxes. */
    val geom: String? = null,
    /** Solid fill as 0xRRGGBB; null when the shape has no plain fill. */
    val fillRgb: Int? = null,
)

data class Slide(val index: Int, val shapes: List<Shape>, val notes: String, val backgroundRgb: Int?)

enum class SlideLayoutKind { TITLE, TITLE_BODY, TWO_COLUMN, BLANK }
