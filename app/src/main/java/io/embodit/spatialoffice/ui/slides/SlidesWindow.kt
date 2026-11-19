package io.embodit.spatialoffice.ui.slides

import android.util.Log
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.IntSize
import io.embodit.spatialoffice.core.layout.SlideFit
import io.embodit.spatialoffice.platform.TAG
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import io.embodit.spatialoffice.ui.tr
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Text
import com.pico.spatial.ui.design.TextArea
import com.pico.spatial.ui.design.menu.Menu
import com.pico.spatial.ui.design.menu.MenuItem
import com.pico.spatial.ui.design.menu.rememberMenuPositionProvider
import com.pico.spatial.ui.design.windows.Toolbar
import com.pico.spatial.ui.design.windows.popup.HorizontalPlacement
import com.pico.spatial.ui.design.windows.popup.VerticalPlacement
import io.embodit.spatialoffice.R
import io.embodit.spatialoffice.core.layout.BoxDrag
import io.embodit.spatialoffice.core.layout.Corner
import io.embodit.spatialoffice.core.layout.UnitBox
import io.embodit.spatialoffice.core.pptx.Shape
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import io.embodit.spatialoffice.ui.CornerDot
import io.embodit.spatialoffice.ui.MoveTab
import io.embodit.spatialoffice.ui.cornerHit
import io.embodit.spatialoffice.ui.dragTotal
import io.embodit.spatialoffice.ui.probe
import io.embodit.spatialoffice.core.pptx.ShapeKind
import io.embodit.spatialoffice.core.pptx.SlideLayoutKind
import io.embodit.spatialoffice.platform.DeckSession
import io.embodit.spatialoffice.platform.Graph
import io.embodit.spatialoffice.platform.Win
import io.embodit.spatialoffice.ui.EditorTitleBar
import io.embodit.spatialoffice.ui.MenuEntry
import io.embodit.spatialoffice.ui.ToolMenu
import io.embodit.spatialoffice.ui.FileTools
import io.embodit.spatialoffice.ui.Ink
import io.embodit.spatialoffice.ui.NoFile
import io.embodit.spatialoffice.ui.ToolButton
import io.embodit.spatialoffice.ui.ToolDivider
import io.embodit.spatialoffice.ui.ToolIcon
import io.embodit.spatialoffice.ui.ToolRow
import io.embodit.spatialoffice.ui.Txt
import io.embodit.spatialoffice.ui.UndoRedo
import io.embodit.spatialoffice.ui.WindowFrame
import io.embodit.spatialoffice.ui.commonKeys

/** W3 presentation (Planar 1360 × 860 dp): thumbnails, the slide edited in place, speaker notes, toolbar. */
@Composable
fun SlidesWindow(tag: String? = null) {
    io.embodit.spatialoffice.ui.EditorWindow(Win.SLIDES, tag) { graph, session ->
        val s = session as? DeckSession ?: return@EditorWindow
        Column(Modifier.fillMaxSize().onPreviewKeyEvent { e -> commonKeys(graph, s, e) || slideKeys(s, e) }) {
            EditorTitleBar(graph, Win.SLIDES, s) {
                ToolButton(tr(R.string.rehearse), "slides/rehearse", icon = R.drawable.ic_play, onClick = { graph.rehearse(s) })
            }
            Row(Modifier.fillMaxWidth().weight(1f).padding(start = 32.dp, end = 32.dp, bottom = 52.dp)) {
                Thumbnails(s, Modifier.width(208.dp).fillMaxHeight())
                Box(Modifier.width(20.dp))
                // q6-1 (v0.2.6): the canvas takes what the notes area leaves, never the other way round
                BoxWithConstraints(Modifier.weight(1f).fillMaxHeight()) {
                    s.version
                    val fit = SlideFit.editor(maxWidth.value, maxHeight.value, s.deck.widthEmu, s.deck.heightEmu)
                    val rowW = fit.rowW.dp
                    val report = remember(s) { FitReport() }
                    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                        SlideCanvas(s, s.current, fit.canvas.w.dp, editable = true, modifier = Modifier.onGloballyPositioned { report.canvas = it.boundsInWindow() })
                        Box(Modifier.height(SlideFit.GAP_CANVAS.dp))
                        Row(
                            Modifier.width(rowW).heightIn(min = SlideFit.LABEL_H.dp).onGloballyPositioned { report.label = it.boundsInWindow() },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Txt(tr(R.string.notes), "slides/notes-label", Modifier.weight(1f), style = PicoTheme.typography.labelLarge, color = PicoTheme.colorScheme.labelSecondary)
                            // what the corner dots and the round tab of the picked object are for
                            if (s.selected >= 0 && !s.readOnly) Txt(tr(R.string.obj_hint), "slides/obj-hint", style = PicoTheme.typography.labelLarge, color = PicoTheme.colorScheme.labelSecondary)
                        }
                        Box(Modifier.height(SlideFit.GAP_LABEL.dp))
                        val slide = s.deck.slides.getOrNull(s.current)
                        var notes by remember(s.current, s.deck) { mutableStateOf(slide?.notes ?: "") }
                        TextArea(
                            value = notes,
                            onValueChange = { notes = it; s.setNotes(s.current, it) },
                            modifier = Modifier.width(rowW).weight(1f).semantics { contentDescription = "notes-input" }
                                .onGloballyPositioned { report.notes(it.boundsInWindow(), it.findRootCoordinates().size, s, fit) },
                            placeholder = { Txt(tr(R.string.notes_ph), "slides/notes-ph", color = PicoTheme.colorScheme.labelTertiary) },
                        )
                    }
                }
            }
        }
        SlidesToolbar(graph, s)
    }
}

fun slideKeys(s: DeckSession, e: KeyEvent): Boolean {
    if (e.type != KeyEventType.KeyDown) return false
    return when (e.key) {
        Key.PageDown -> { s.current = (s.current + 1).coerceAtMost(s.slideCount - 1); true }
        Key.PageUp -> { s.current = (s.current - 1).coerceAtLeast(0); true }
        // Delete removes a picked picture or drawn shape (text boxes keep Delete for their text)
        Key.Delete -> {
            val sh = s.deck.slides.getOrNull(s.current)?.shapes?.firstOrNull { it.id == s.selected }
            if (sh != null && (sh.kind == ShapeKind.PICTURE || sh.geom != null && sh.geom != "rect" || sh.fillRgb != null)) s.deleteSelected() else false
        }
        else -> false
    }
}

@Composable
private fun Thumbnails(s: DeckSession, modifier: Modifier) {
    // the count is snapshot state: a new or copied slide is in the list in the same frame it is made
    val n = s.slideCount
    val state = rememberLazyListState()
    LaunchedEffect(s.current, n) { runCatching { state.animateScrollToItem(s.current.coerceIn(0, (n - 1).coerceAtLeast(0))) } }
    // long-press a thumbnail and drag it up or down to reorder; it lands where it is let go
    var dragFrom by remember { mutableIntStateOf(-1) }
    var dragDy by remember { mutableFloatStateOf(0f) }
    // a thumbnail is as high as the page's proportion makes it (4:3 is higher than 16:9, a tall page is narrower)
    val pageW = s.deck.widthEmu; val pageH = s.deck.heightEmu
    val thumb = SlideFit.thumb(pageW, pageH)
    val dpPx = with(androidx.compose.ui.platform.LocalDensity.current) { 1.dp.toPx() }
    LazyColumn(modifier, state = state, verticalArrangement = Arrangement.spacedBy(SlideFit.THUMB_SPACING.dp), contentPadding = PaddingValues(vertical = 2.dp)) {
        items(n, key = { it }) { i ->
            val on = i == s.current
            val lifted = i == dragFrom
            Row(
                Modifier.fillMaxWidth()
                    .graphicsLayer { if (lifted) { translationY = dragDy; scaleX = 1.04f; scaleY = 1.04f } }
                    .pointerInput(i, n) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = { dragFrom = i; dragDy = 0f; s.current = i },
                            onDrag = { change, dragAmount -> change.consume(); dragDy += dragAmount.y },
                            onDragEnd = {
                                val to = SlideFit.dropIndex(i, dragDy / dpPx, n, pageW, pageH)
                                dragFrom = -1; dragDy = 0f
                                s.moveSlideTo(i, to)
                            },
                            onDragCancel = { dragFrom = -1; dragDy = 0f },
                        )
                    }
                    .clickable { s.current = i; s.selected = -1 }.semantics { contentDescription = "slide-thumb-${i + 1}" },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Txt((i + 1).toString(), "slides/thumb-no", Modifier.width(26.dp), style = PicoTheme.typography.labelLarge, color = if (on) PicoTheme.colorScheme.labelPrimary else PicoTheme.colorScheme.labelSecondary)
                Box(Modifier.border(if (on || lifted) 3.dp else 1.dp, if (on || lifted) Ink.Select else Ink.Line, RoundedCornerShape(8.dp)).padding(3.dp)) {
                    SlideCanvas(s, i, thumb.w.dp, editable = false)
                }
            }
        }
    }
}

/**
 * Where the canvas, the notes label row and the notes field came out in the window, logged whenever it changes:
 * `SLIDEFIT page=… canvas=… label=… notes=… window=… inside=true|false` (px of the window; q6-1).
 */
private class FitReport {
    var canvas: Rect = Rect.Zero
    var label: Rect = Rect.Zero
    private var last = ""

    fun notes(notes: Rect, window: IntSize, s: DeckSession, fit: SlideFit.Editor) {
        fun r(b: Rect) = "${b.left.toInt()},${b.top.toInt()} ${b.width.toInt()}x${b.height.toInt()}"
        val inside = listOf(canvas, label, notes).all { it.width > 0f && it.height > 0f && it.left >= 0f && it.top >= 0f && it.right <= window.width && it.bottom <= window.height } &&
            label.top >= canvas.bottom && notes.top >= label.bottom
        val line = "SLIDEFIT page=${s.deck.widthEmu}x${s.deck.heightEmu} column=${fit.availW.toInt()}x${fit.availH.toInt()}dp canvas=${fit.canvas.w.toInt()}x${fit.canvas.h.toInt()}dp " +
            "notesMin=${SlideFit.NOTES_MIN_H.toInt()}dp px: canvas=${r(canvas)} label=${r(label)} notes=${r(notes)} window=${window.width}x${window.height} inside=$inside"
        if (line != last) { last = line; Log.i(TAG, line) }
    }
}

private val Triangle = GenericShape { size, _ -> moveTo(size.width / 2f, 0f); lineTo(size.width, size.height); lineTo(0f, size.height); close() }
private val Arrow = GenericShape { size, _ ->
    val w = size.width; val h = size.height
    moveTo(0f, h * 0.3f); lineTo(w * 0.62f, h * 0.3f); lineTo(w * 0.62f, 0f); lineTo(w, h / 2f); lineTo(w * 0.62f, h); lineTo(w * 0.62f, h * 0.7f); lineTo(0f, h * 0.7f); close()
}

/** Outline of a preset geometry this version draws; anything else is drawn as its bounding rectangle. */
fun geomShape(geom: String?, width: Dp): androidx.compose.ui.graphics.Shape = when (geom) {
    "ellipse" -> CircleShape
    "roundRect" -> RoundedCornerShape(width / 6)
    "triangle" -> Triangle
    "rightArrow" -> Arrow
    else -> RoundedCornerShape(0.dp)
}

/**
 * One slide drawn at [width]: background, pictures, text boxes at their real positions and proportional sizes.
 * With [editable]: every object can be picked, dragged to another place and resized by its corner dots (v0.2.5);
 * text is edited in place after a click. Objects whose content this version cannot draw show as a light frame and
 * move and resize like the rest.
 */
@Composable
fun SlideCanvas(s: DeckSession, index: Int, width: Dp, editable: Boolean, modifier: Modifier = Modifier) {
    s.version
    val deck = s.deck
    val slide = deck.slides.getOrNull(index) ?: return
    val height = width * (deck.heightEmu.toFloat() / deck.widthEmu)
    val perEmu = width.value / deck.widthEmu           // dp per EMU
    val perPt = perEmu * 12700f                        // dp per point
    val pxPerEmu = with(androidx.compose.ui.platform.LocalDensity.current) { width.toPx() } / deck.widthEmu
    val canDrag = editable && !s.readOnly
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    // the outer box is not clipped: the picked object's frame, corner dots and move tab may reach past the slide's edge
    Box(modifier.size(width, height)) {
        Box(
            Modifier.fillMaxSize().clip(RoundedCornerShape((width.value / 120f).dp)).background(slide.backgroundRgb?.let { Color(0xFF000000 or it.toLong()) } ?: Ink.Paper)
                // a click on the empty slide lets go of the picked object
                .then(if (editable) Modifier.pointerInput(index) { detectTapGestures(onTap = { s.selected = -1; focusManager.clearFocus() }) } else Modifier),
        ) {
            for (sh in slide.shapes) {
                val b = if (editable) s.boxOf(sh) else UnitBox(sh.x, sh.y, sh.w, sh.h)
                val w = (b.w * perEmu).coerceAtLeast(1f).dp
                var box = Modifier.offset((b.x * perEmu).dp, (b.y * perEmu).dp).size(w, (b.h * perEmu).coerceAtLeast(1f).dp)
                sh.fillRgb?.takeIf { sh.kind != ShapeKind.PICTURE }?.let { rgb -> box = box.background(Color(0xFF000000 or rgb.toLong()), geomShape(sh.geom, w)) }
                // press and drag anywhere on the object moves it; a click picks it
                val move = if (canDrag) Modifier.objectDrag(s, sh.id, pxPerEmu, null) else Modifier
                val pick = if (editable) Modifier.clickable(interactionSource = remember(sh.id) { MutableInteractionSource() }, indication = null) { s.selected = sh.id; focusManager.clearFocus() } else Modifier
                when {
                    sh.kind == ShapeKind.PICTURE -> sh.imagePart?.let { s.image(it) }?.let { bmp ->
                        Image(
                            bitmap = bmp.asImageBitmap(), contentDescription = null, contentScale = ContentScale.FillBounds,
                            modifier = if (editable) box.then(move).then(pick).semantics { contentDescription = "slide-picture-${sh.id}" } else box,
                        )
                    }
                    sh.table != null -> SlideTable(s, index, sh, box.then(move).then(pick), perPt, editable && sh.cellsEditable && !s.readOnly)
                    sh.kind == ShapeKind.TITLE || sh.kind == ShapeKind.BODY || sh.kind == ShapeKind.TEXT -> ShapeText(s, index, sh, box, move, perPt, editable, editable && sh.editable && !s.readOnly)
                    else -> Box(
                        box.then(move).then(pick).border((width.value / 600f).coerceAtLeast(0.5f).dp, Ink.Line, RoundedCornerShape(4.dp)).semantics { contentDescription = "slide-object-${sh.id}" },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (editable) Text(tr(R.string.slide_other), style = TextStyle(fontSize = 13.sp, color = Ink.Hint, textAlign = TextAlign.Center), modifier = Modifier.padding(6.dp))
                    }
                }
            }
        }
        val sel = if (editable) slide.shapes.firstOrNull { it.id == s.selected } else null
        if (sel != null) PickedFrame(s, sel, perEmu, pxPerEmu, height, canDrag)
    }
}

/** Drag of object [id]: its body / move tab ([corner] null) or one of its corners. Travel since the press, in EMU. */
private fun Modifier.objectDrag(s: DeckSession, id: Int, pxPerEmu: Float, corner: Corner?): Modifier = dragTotal(
    key = Triple(id, corner, pxPerEmu), name = "slide shape $id ${corner ?: "move"}",
    onStart = { s.beginBoxDrag(id) },
    onTotal = { x, y ->
        val dx = BoxDrag.units(x, pxPerEmu); val dy = BoxDrag.units(y, pxPerEmu)
        if (corner == null) s.moveBoxBy(dx, dy) else s.resizeBoxBy(corner, dx, dy)
    },
    onEnd = { s.endBoxDrag() },
    onCancel = { s.cancelBoxDrag() },
)

/** The picked object's frame: blue outline, a dot on each corner to resize by, and the round tab to move it by. */
@Composable
private fun PickedFrame(s: DeckSession, sh: Shape, perEmu: Float, pxPerEmu: Float, slideH: Dp, canDrag: Boolean) {
    val b = s.boxOf(sh)
    val x = (b.x * perEmu).dp; val y = (b.y * perEmu).dp
    val w = (b.w * perEmu).coerceAtLeast(1f).dp; val h = (b.h * perEmu).coerceAtLeast(1f).dp
    Box(Modifier.offset(x, y).size(w, h).probe("slide-${sh.id}-body").border(3.dp, Ink.Select, RoundedCornerShape(4.dp)))
    if (!canDrag) return
    val hit = cornerHit(w, h)
    val label = tr(R.string.obj_resize)
    for (c in Corner.values()) {
        CornerDot(c, if (c.left) x else x + w, if (c.top) y else y + h, hit, label, Modifier.objectDrag(s, sh.id, pxPerEmu, c), "slide-${sh.id}")
    }
    // above the object; below it when there is no room above
    val tabY = if (y >= 40.dp) y - 34.dp else minOf(y + h + 34.dp, slideH + 30.dp)
    MoveTab(x + w / 2, tabY, tr(R.string.obj_move), Modifier.objectDrag(s, sh.id, pxPerEmu, null), "slide-${sh.id}")
}

private fun shapeString(sh: Shape): String = sh.paras.joinToString("\n") { it.text }

/**
 * Text that is read until it is clicked, then typed into (v0.2.5). While it is only read, a press-and-drag on it
 * belongs to [whole] (moving the object it is part of); a text field would keep the drag for its own selection.
 */
@Composable
private fun TapEdit(
    key: Any, model: String, style: TextStyle, name: String, whole: Modifier, inner: Modifier, align: Alignment,
    hint: String?, editable: Boolean, onPick: () -> Unit, onChange: (String) -> Unit,
) {
    var editing by remember(key) { mutableStateOf(false) }
    var text by remember(key) { mutableStateOf(model) }
    var hadFocus by remember(key) { mutableStateOf(false) }
    if (!editing && text != model) text = model
    val requester = remember(key) { FocusRequester() }
    val outer = if (editing || !editable) whole
    else whole.clickable(interactionSource = remember(key) { MutableInteractionSource() }, indication = null) { onPick(); hadFocus = false; editing = true }.semantics { contentDescription = name }
    Box(outer.then(inner).border(1.dp, if (editing) Ink.Select else Color(0x22000000), RoundedCornerShape(4.dp)).padding(horizontal = 4.dp), contentAlignment = align) {
        if (text.isEmpty() && hint != null) Text(hint, style = style.copy(color = Ink.Hint), modifier = Modifier.fillMaxWidth())
        if (editing) {
            BasicTextField(
                value = text,
                onValueChange = { text = it; onChange(it) },
                textStyle = style,
                cursorBrush = SolidColor(Ink.Select),
                modifier = Modifier.fillMaxWidth().focusRequester(requester)
                    .onFocusChanged { if (it.isFocused) { hadFocus = true; onPick() } else if (hadFocus) editing = false }
                    .semantics { contentDescription = name },
            )
            LaunchedEffect(key) { repeat(5) { if (runCatching { requester.requestFocus() }.isSuccess) return@LaunchedEffect; kotlinx.coroutines.delay(60) } }
        } else Text(text, style = style, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun ShapeText(s: DeckSession, slide: Int, sh: Shape, box: Modifier, move: Modifier, perPt: Float, canPick: Boolean, editable: Boolean) {
    val size = ((sh.fontPt ?: if (sh.kind == ShapeKind.TITLE) 40f else 20f) * perPt)
    val style = TextStyle(
        fontSize = size.sp, lineHeight = (size * 1.25f).sp,
        color = sh.colorRgb?.let { Color(0xFF000000 or it.toLong()) } ?: Ink.Text,
        fontWeight = if (sh.bold || sh.kind == ShapeKind.TITLE) FontWeight.Bold else null,
        textAlign = when (sh.align) { 1 -> TextAlign.Center; 2 -> TextAlign.End; else -> TextAlign.Start },
    )
    val align = if (sh.kind == ShapeKind.TITLE) Alignment.CenterStart else Alignment.TopStart
    if (!editable) {
        // thumbnails, and text this version does not edit: still picked and moved like any object on the editing canvas
        val pick = if (canPick) move.clickable(interactionSource = remember(sh.id) { MutableInteractionSource() }, indication = null) { s.selected = sh.id } else Modifier
        Box(box.then(pick), contentAlignment = align) {
            Text(sh.paras.joinToString("\n") { (if (it.bullet) "  ".repeat(it.level) + "• " else "") + it.text }, style = style, modifier = Modifier.fillMaxWidth())
        }
        return
    }
    TapEdit(
        key = slide to sh.id, model = shapeString(sh), style = style, name = "slide-text-${sh.id}", whole = box.then(move), inner = Modifier, align = align,
        hint = if (sh.fillRgb == null) tr(if (sh.kind == ShapeKind.TITLE) R.string.slide_title_ph else R.string.slide_body_ph) else null,
        editable = true, onPick = { s.selected = sh.id }, onChange = { s.setText(slide, sh.id, it) },
    )
}

/** A table on the slide: its cells as a grid over the frame. On the editing canvas a click types into a cell; a drag moves the table. */
@Composable
private fun SlideTable(s: DeckSession, slide: Int, sh: Shape, box: Modifier, perPt: Float, editable: Boolean) {
    val rows = sh.table ?: return
    val size = 16f * perPt
    Column(box.background(Ink.Paper).border(1.dp, Ink.Line).semantics { contentDescription = "slide-table-${sh.id}" }) {
        rows.forEachIndexed { r, row ->
            Row(Modifier.fillMaxWidth().weight(1f).background(if (r == 0) Ink.Header else Ink.Paper)) {
                row.forEachIndexed { c, cell ->
                    val style = TextStyle(fontSize = size.sp, lineHeight = (size * 1.2f).sp, color = Ink.Text, fontWeight = if (r == 0) FontWeight.SemiBold else null)
                    val cellBox = Modifier.weight(1f).fillMaxHeight().border(0.5.dp, Ink.Line)
                    if (!editable) Box(cellBox.padding(horizontal = 4.dp), contentAlignment = Alignment.CenterStart) { Text(cell, style = style, modifier = Modifier.fillMaxWidth()) }
                    else TapEdit(
                        key = Triple(slide, sh.id, r * 100 + c), model = cell, style = style, name = "slide-table-${sh.id}-cell-$r-$c",
                        whole = cellBox, inner = Modifier, align = Alignment.CenterStart, hint = null, editable = true,
                        onPick = { s.selected = sh.id }, onChange = { s.setTableCell(slide, sh.id, r, c, it) },
                    )
                }
            }
        }
    }
}

/** Colour presets for [Theme]: background (lt1), text (dk1), second text / accent pair, accents. */
val THEMES: List<Pair<Int, Map<String, Int>>> = listOf(
    R.string.theme_light to mapOf("lt1" to 0xFFFFFF, "dk1" to 0x111827, "lt2" to 0xF3F4F6, "dk2" to 0x1F2937, "accent1" to 0x2563EB, "accent2" to 0xF97316),
    R.string.theme_dark to mapOf("lt1" to 0x111827, "dk1" to 0xF9FAFB, "lt2" to 0x1F2937, "dk2" to 0xE5E7EB, "accent1" to 0x60A5FA, "accent2" to 0xFBBF24),
    R.string.theme_warm to mapOf("lt1" to 0xFFF7ED, "dk1" to 0x431407, "lt2" to 0xFFEDD5, "dk2" to 0x7C2D12, "accent1" to 0xEA580C, "accent2" to 0x0D9488),
    R.string.theme_forest to mapOf("lt1" to 0xF0FDF4, "dk1" to 0x052E16, "lt2" to 0xDCFCE7, "dk2" to 0x14532D, "accent1" to 0x16A34A, "accent2" to 0x9333EA),
)

val SHAPE_FILL = 0x2563EB

@Composable
private fun SlidesToolbar(graph: Graph, s: DeckSession) {
    s.version
    val hasSel = s.selected >= 0
    Toolbar(
        supportingContent = { FileTools(graph, Win.SLIDES, s, spreadLabel = R.string.storyline) },
        content = {
            ToolRow {
                ToolMenu(tr(R.string.add_slide), "slides/add", icon = R.drawable.ic_plus, entries = listOf(
                    MenuEntry(tr(R.string.lay_title), "slides/lay-title") { s.addSlide(SlideLayoutKind.TITLE) },
                    MenuEntry(tr(R.string.lay_body), "slides/lay-body") { s.addSlide(SlideLayoutKind.TITLE_BODY) },
                    MenuEntry(tr(R.string.lay_two), "slides/lay-two") { s.addSlide(SlideLayoutKind.TWO_COLUMN) },
                    MenuEntry(tr(R.string.lay_blank), "slides/lay-blank") { s.addSlide(SlideLayoutKind.BLANK) },
                ))
                ToolMenu(tr(R.string.menu_page), "slides/page", entries = listOfNotNull(
                    MenuEntry(tr(R.string.dup_slide), "slides/dup") { s.duplicateSlide() },
                    if (s.current > 0) MenuEntry(tr(R.string.slide_up), "slides/up") { s.moveSlide(-1) } else null,
                    if (s.current < s.slideCount - 1) MenuEntry(tr(R.string.slide_down), "slides/down") { s.moveSlide(1) } else null,
                    MenuEntry(tr(R.string.delete_slide), "slides/delete") { if (!s.deleteSlide()) graph.say(s, graph.str(R.string.last_slide)) },
                ) + THEMES.map { (name, colors) -> MenuEntry(tr(R.string.theme_item, tr(name)), "slides/theme-item") { s.setColors(colors) } })
                ToolMenu(tr(R.string.menu_insert), "slides/insert", entries = listOf(
                    MenuEntry(tr(R.string.text_box), "slides/textbox") { s.addTextBox("") },
                    MenuEntry(tr(R.string.slide_table), "slides/table") { s.addTable() },
                    MenuEntry(tr(R.string.ins_picture), "slides/picture") { graph.pickImage(s) { b, e, w, h -> s.addPicture(b, e, w, h) } },
                    MenuEntry(tr(R.string.shape_rect), "slides/rect") { s.addShape("rect", SHAPE_FILL) },
                    MenuEntry(tr(R.string.shape_round), "slides/round") { s.addShape("roundRect", SHAPE_FILL) },
                    MenuEntry(tr(R.string.shape_ellipse), "slides/ellipse") { s.addShape("ellipse", SHAPE_FILL) },
                    MenuEntry(tr(R.string.shape_triangle), "slides/triangle") { s.addShape("triangle", SHAPE_FILL) },
                    MenuEntry(tr(R.string.shape_arrow), "slides/arrow") { s.addShape("rightArrow", SHAPE_FILL) },
                ))
                ToolMenu(tr(R.string.menu_object), "slides/object", enabled = hasSel, entries = listOf(
                    MenuEntry(tr(R.string.obj_left), "slides/obj-left") { s.nudge(-1, 0) },
                    MenuEntry(tr(R.string.obj_right), "slides/obj-right") { s.nudge(1, 0) },
                    MenuEntry(tr(R.string.obj_up), "slides/obj-up") { s.nudge(0, -1) },
                    MenuEntry(tr(R.string.obj_down), "slides/obj-down") { s.nudge(0, 1) },
                    MenuEntry(tr(R.string.obj_bigger), "slides/obj-bigger") { s.nudge(0, 0, 1.2f) },
                    MenuEntry(tr(R.string.obj_smaller), "slides/obj-smaller") { s.nudge(0, 0, 1f / 1.2f) },
                    MenuEntry(tr(R.string.obj_delete), "slides/obj-delete") { s.deleteSelected() },
                ))
                ToolDivider()
                UndoRedo(s)
            }
        },
    )
}
