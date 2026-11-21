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
    /** Cell text of a table (a graphic frame holding `a:tbl`), row by row; null for everything else. */
    val table: List<List<String>>? = null,
    /** A plain table (no merged cells): its cells can be typed into ([editable] stays false: a table has no single text). */
    val cellsEditable: Boolean = false,
)

data class Slide(val index: Int, val shapes: List<Shape>, val notes: String, val backgroundRgb: Int?)

enum class SlideLayoutKind { TITLE, TITLE_BODY, TWO_COLUMN, BLANK }

/** Drawing-order moves of [PptxDeck.reorderShape]. FRONT / BACK: to the top / bottom; FORWARD / BACKWARD: one step. */
enum class ZOrder { FRONT, BACK, FORWARD, BACKWARD }

/**
 * A copied top-level slide object ([PptxDeck.copyShape]); opaque to callers. It holds the object's XML as text (a
 * placeholder already turned into a plain shape with a box of its own), the namespace bindings it was written with,
 * and the relationships its `r:` attributes use, so it stays valid after the source object is edited or deleted, after
 * an undo (a reopened deck) and across saves. Pasting needs the media parts it names to exist in the target deck.
 * [box] = x, y, w, h in EMU.
 */
class ShapeClip internal constructor(
    internal val xml: String,
    internal val sourcePart: String,
    internal val nsDecls: Map<String, String>,
    internal val rels: Map<String, ClipRel>,
    val box: LongArray,
)

/** A relationship a copied object uses: internal targets are absolute part names, external ones as written. */
internal class ClipRel(val type: String, val target: String, val external: Boolean)
