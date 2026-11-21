package io.embodit.spatialoffice.ui.slides

import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import io.embodit.spatialoffice.core.layout.Rotation
import io.embodit.spatialoffice.core.pptx.TextPara
import io.embodit.spatialoffice.core.text.Attr
import io.embodit.spatialoffice.core.text.RichText
import io.embodit.spatialoffice.core.text.Span
import io.embodit.spatialoffice.ui.TextPalette
import io.embodit.spatialoffice.ui.colorEntries
import io.embodit.spatialoffice.ui.sizeEntries
import io.embodit.spatialoffice.ui.subMenu
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
import io.embodit.spatialoffice.core.pptx.ZOrder
import androidx.compose.ui.input.key.isCtrlPressed
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
        Column(Modifier.fillMaxSize().onPreviewKeyEvent { e -> commonKeys(graph, s, e) || slideKeys(graph, s, e) }) {
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
                            val noteHit = s.findHit?.takeIf { it.b < 0 && it.a == s.current }
                            val noteText = s.deck.slides.getOrNull(s.current)?.notes ?: ""
                            if (noteHit != null && noteHit.start + noteHit.len <= noteText.length) {
                                // p21: a hit in the notes is shown here, marked, with a little of the text around it
                                val from = (noteHit.start - 16).coerceAtLeast(0); val to = (noteHit.start + noteHit.len + 16).coerceAtMost(noteText.length)
                                val shown = (if (from > 0) "…" else "") + noteText.substring(from, to).replace('\n', ' ') + (if (to < noteText.length) "…" else "")
                                val at = noteHit.start - from + (if (from > 0) 1 else 0)
                                Text(
                                    io.embodit.spatialoffice.ui.doc.underlined(tr(R.string.find_in_notes) + "  " + shown, at + tr(R.string.find_in_notes).length + 2, noteHit.len, PicoTheme.colorScheme.labelPrimary),
                                    style = PicoTheme.typography.labelLarge, color = PicoTheme.colorScheme.labelPrimary, maxLines = 1,
                                    modifier = Modifier.semantics { contentDescription = "slides/notes-hit" },
                                )
                            } else if (s.selected >= 0 && !s.readOnly) Txt(tr(R.string.obj_hint), "slides/obj-hint", style = PicoTheme.typography.labelLarge, color = PicoTheme.colorScheme.labelSecondary)
                        }
                        Box(Modifier.height(SlideFit.GAP_LABEL.dp))
                        val slide = s.deck.slides.getOrNull(s.current)
                        var notes by remember(s.current, s.deck, s.outsideEdits) { mutableStateOf(slide?.notes ?: "") }
                        TextArea(
                            value = notes,
                            onValueChange = { notes = it; s.setNotes(s.current, it) },
                            modifier = Modifier.width(rowW).weight(1f).semantics { contentDescription = "notes-input" }
                                .onFocusChanged { s.textEditing = it.isFocused }
                                .then(if (s.findHit?.let { it.b < 0 && it.a == s.current } == true) Modifier.border(3.dp, Ink.Select, RoundedCornerShape(12.dp)) else Modifier)
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

fun slideKeys(graph: io.embodit.spatialoffice.platform.Graph, s: DeckSession, e: KeyEvent): Boolean {
    if (e.type != KeyEventType.KeyDown) return false
    if (e.isCtrlPressed) return when {
        e.key == Key.F || e.key == Key.H -> { graph.show(s, io.embodit.spatialoffice.platform.Overlay.Find(Win.SLIDES)); true }
        // p17: B / I / U on the selected characters of the picked text box (all its text when nothing is selected)
        e.key == Key.B && s.pickedText() != null -> { slideToggle(s, Attr.BOLD); true }
        e.key == Key.I && s.pickedText() != null -> { slideToggle(s, Attr.ITALIC); true }
        e.key == Key.U && s.pickedText() != null -> { slideToggle(s, Attr.UNDERLINE); true }
        // p19: the picked object's shortcuts; a text field that has the keyboard keeps Ctrl+C / V for its text
        s.textEditing -> false
        e.key == Key.C && s.selected >= 0 -> { objCopy(graph, s); true }
        e.key == Key.V && s.canPaste -> { objPaste(graph, s); true }
        e.key == Key.D && s.selected >= 0 -> { s.duplicateSelected(); true }
        else -> false
    }
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
                // p24: the object turned about its centre (while the handle is held, at the angle it is dragged to)
                val rot = if (editable) s.rotationOf(sh) else sh.rotation
                if (rot != 0) box = box.rotate(rot.toFloat())
                // p22: its outline
                sh.lineRgb?.let { rgb -> val lw = ((sh.lineWidthEmu ?: 12700L) * perEmu).coerceAtLeast(0.5f).dp; box = box.border(lw, TextPalette.rgb(rgb), geomShape(sh.geom, w)) }
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
    // p24: the rotation handle beside the move tab; the angle of the pointer about the object's centre, 15° steps
    val density = androidx.compose.ui.platform.LocalDensity.current
    val hx = x + w / 2 + 60.dp
    val (px0, py0) = with(density) { (hx - (x + w / 2)).toPx() to (tabY - (y + h / 2)).toPx() }
    RotateTab(hx, tabY, tr(R.string.obj_rotate), Modifier.dragTotal(
        key = Triple(sh.id, "rotate", px0 to py0), name = "slide shape ${sh.id} rotate",
        onStart = { s.beginRotate(sh.id) },
        onTotal = { dx, dy ->
            val start = s.deck.slides.getOrNull(s.current)?.shapes?.firstOrNull { it.id == sh.id }?.rotation ?: 0
            val turn = Rotation.angle(0f, 0f, px0 + dx, py0 + dy) - Rotation.angle(0f, 0f, px0, py0)
            s.rotateTo(Rotation.snap(start + turn))
        },
        onEnd = { s.endRotate() }, onCancel = { s.cancelRotate() },
    ), "slide-${sh.id}")
}

@Composable
private fun RotateTab(x: Dp, y: Dp, label: String, drag: Modifier, probe: String? = null) {
    Box(
        Modifier.offset(x - 24.dp, y - 24.dp).size(48.dp).probe(probe?.let { "$it-rotate" }).clip(androidx.compose.foundation.shape.CircleShape).background(Ink.Select).then(drag).semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        com.pico.spatial.ui.design.Icon(painter = androidx.compose.ui.res.painterResource(R.drawable.ic_rotate), contentDescription = null, modifier = Modifier.size(26.dp), tint = Color.White)
    }
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
    onEditing: (Boolean) -> Unit = {}, hit: io.embodit.spatialoffice.platform.FindHit? = null,
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
                    .onFocusChanged { if (it.isFocused) { hadFocus = true; onPick(); onEditing(true) } else if (hadFocus) { editing = false; onEditing(false) } }
                    .semantics { contentDescription = name },
            )
            LaunchedEffect(key) { repeat(5) { if (runCatching { requester.requestFocus() }.isSuccess) return@LaunchedEffect; kotlinx.coroutines.delay(60) } }
        } else if (hit != null && hit.start + hit.len <= text.length) Text(io.embodit.spatialoffice.ui.doc.marked(androidx.compose.ui.text.AnnotatedString(text), hit, hit.a), style = style, modifier = Modifier.fillMaxWidth())
        else Text(text, style = style, modifier = Modifier.fillMaxWidth())
    }
}

/** p17 / p18: the text of a box as styled text: each span's size, colour, B / I / U / strikethrough, each paragraph's alignment. */
fun richShapeText(spans: List<Span>, paras: List<TextPara>, perPt: Float, base: TextStyle): AnnotatedString = buildAnnotatedString {
    val text = RichText.text(spans)
    var pStart = 0
    var pi = 0
    // paragraph styles first (their ranges include the "\n" that ends them), then the spans
    while (pStart <= text.length) {
        val nl = text.indexOf('\n', pStart).let { if (it < 0) text.length else it + 1 }
        val align = paras.getOrNull(pi)?.align
        if (align != null && nl > pStart) addStyle(ParagraphStyle(textAlign = when (align) { 1 -> TextAlign.Center; 2 -> TextAlign.End; else -> TextAlign.Start }), pStart, nl)
        pi++
        if (nl >= text.length) break
        pStart = nl
    }
    for (sp in spans) withStyle(
        SpanStyle(
            fontSize = sp.size?.let { (it / 2f * perPt).sp } ?: TextUnit.Unspecified,
            color = sp.color?.let { TextPalette.rgb(it) } ?: Color.Unspecified,
            fontWeight = if (sp.bold) FontWeight.Bold else null,
            fontStyle = if (sp.italic) FontStyle.Italic else null,
            textDecoration = when {
                sp.underline && sp.strike -> TextDecoration.combine(listOf(TextDecoration.Underline, TextDecoration.LineThrough))
                sp.underline -> TextDecoration.Underline
                sp.strike -> TextDecoration.LineThrough
                else -> null
            },
        ),
    ) { append(sp.text) }
}

/**
 * p18: bullets drawn in front of bulleted paragraphs ("• ", indented by level) without being part of the text: the
 * field and the caret map around them.
 */
class BulletMarks(private val paras: List<TextPara>) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val t = text.text
        val starts = ArrayList<Int>()
        var i = 0
        starts.add(0)
        while (true) { val k = t.indexOf('\n', i); if (k < 0) break; starts.add(k + 1); i = k + 1 }
        val prefix = starts.indices.map { pi -> paras.getOrNull(pi)?.takeIf { it.bullet }?.let { "  ".repeat(it.level) + "• " } ?: "" }
        if (prefix.all { it.isEmpty() }) return TransformedText(text, OffsetMapping.Identity)
        val out = AnnotatedString.Builder()
        val o2t = IntArray(t.length + 1)
        var added = 0
        for ((pi, st) in starts.withIndex()) {
            val en = starts.getOrNull(pi + 1) ?: t.length
            if (prefix[pi].isNotEmpty()) {
                val style = text.spanStyles.firstOrNull { it.start <= st && it.end > st }?.item
                if (style != null) out.withStyle(style.copy(textDecoration = null)) { append(prefix[pi]) } else out.append(prefix[pi])
                added += prefix[pi].length
            }
            for (o in st until en) o2t[o] = o + added
            // the characters with their span styles only: the paragraph styles are laid over again below, prefix included
            if (en > st) text.subSequence(st, en).let { sub -> out.append(AnnotatedString(sub.text, sub.spanStyles)) }
        }
        o2t[t.length] = t.length + added
        val total = t.length + added
        // a paragraph's alignment covers its bullet too (a bullet outside it would be a paragraph of its own)
        for (ps in text.paragraphStyles) {
            val pi = starts.indexOf(ps.start)
            val from = o2t[ps.start.coerceIn(0, t.length)] - (if (pi >= 0) prefix[pi].length else 0)
            out.addStyle(ps.item, from.coerceAtLeast(0), o2t[ps.end.coerceIn(0, t.length)])
        }
        return TransformedText(out.toAnnotatedString(), object : OffsetMapping {
            override fun originalToTransformed(offset: Int): Int = o2t[offset.coerceIn(0, t.length)]
            override fun transformedToOriginal(offset: Int): Int {
                val tt = offset.coerceIn(0, total)
                var lo = 0
                for (o in 0..t.length) if (o2t[o] <= tt) lo = o else break
                return lo
            }
        })
    }
}

@Composable
private fun ShapeText(s: DeckSession, slide: Int, sh: Shape, box: Modifier, move: Modifier, perPt: Float, canPick: Boolean, editable: Boolean) {
    val spans = remember(s.version, sh) { s.spansOf(sh) }
    val biggest = spans.maxOfOrNull { it.size ?: 0 }?.takeIf { it > 0 }?.let { it / 2f } ?: (sh.fontPt ?: if (sh.kind == ShapeKind.TITLE) 40f else 20f)
    val size = ((sh.fontPt ?: if (sh.kind == ShapeKind.TITLE) 40f else 20f) * perPt)
    val style = TextStyle(
        fontSize = size.sp, lineHeight = (biggest * perPt * 1.25f).sp,
        color = sh.colorRgb?.let { Color(0xFF000000 or it.toLong()) } ?: Ink.Text,
        fontWeight = if (sh.bold || sh.kind == ShapeKind.TITLE) FontWeight.Bold else null,
        textAlign = when (sh.align) { 1 -> TextAlign.Center; 2 -> TextAlign.End; else -> TextAlign.Start },
    )
    val align = if (sh.kind == ShapeKind.TITLE) Alignment.CenterStart else Alignment.TopStart
    val rich = remember(spans, sh.paras, perPt) { richShapeText(spans, sh.paras, perPt, style) }
    val marks = remember(sh.paras) { BulletMarks(sh.paras) }
    if (!editable) {
        // thumbnails, and text this version does not edit: still picked and moved like any object on the editing canvas
        val pick = if (canPick) move.clickable(interactionSource = remember(sh.id) { MutableInteractionSource() }, indication = null) { s.selected = sh.id } else Modifier
        Box(box.then(pick), contentAlignment = align) {
            Text(marks.filter(rich).text, style = style, modifier = Modifier.fillMaxWidth())
        }
        return
    }
    RichTapEdit(
        s = s, slide = slide, sh = sh, rich = rich, marks = marks, style = style, whole = box.then(move), align = align, perPt = perPt,
        hint = if (sh.fillRgb == null) tr(if (sh.kind == ShapeKind.TITLE) R.string.slide_title_ph else R.string.slide_body_ph) else null,
        hit = s.findHit?.takeIf { it.a == slide && it.b == sh.id },
    )
}

/**
 * [TapEdit] for a text box with formatting (p17): read until clicked, then typed into; the field holds the styled text,
 * reports its selection to the session (the [Text ▾] / [Colour ▾] tools act on it) and every edit goes through the
 * span model, so a run keeps its format while the text around it changes.
 */
@Composable
private fun RichTapEdit(
    s: DeckSession, slide: Int, sh: Shape, rich: AnnotatedString, marks: BulletMarks, style: TextStyle, whole: Modifier, align: Alignment, perPt: Float,
    hint: String?, hit: io.embodit.spatialoffice.platform.FindHit?,
) {
    val key = slide to sh.id
    val name = "slide-text-${sh.id}"
    var editing by remember(key) { mutableStateOf(false) }
    var value by remember(key) { mutableStateOf(TextFieldValue(rich)) }
    var hadFocus by remember(key) { mutableStateOf(false) }
    if (value.annotatedString != rich) {
        // the model changed (format, undo, find): same text keeps the caret and selection
        value = if (value.text == rich.text) value.copy(annotatedString = rich) else TextFieldValue(rich, TextRange(rich.length))
    }
    val requester = remember(key) { FocusRequester() }
    val outer = if (editing) whole
    else whole.clickable(interactionSource = remember(key) { MutableInteractionSource() }, indication = null) { s.selected = sh.id; hadFocus = false; editing = true }.semantics { contentDescription = name }
    Box(outer.border(1.dp, if (editing) Ink.Select else Color(0x22000000), RoundedCornerShape(4.dp)).padding(horizontal = 4.dp), contentAlignment = align) {
        if (value.text.isEmpty() && hint != null) Text(hint, style = style.copy(color = Ink.Hint), modifier = Modifier.fillMaxWidth())
        if (editing) {
            BasicTextField(
                value = value,
                onValueChange = { nv ->
                    if (nv.text != value.text) s.setText(slide, sh.id, nv.text)
                    // the styling comes back from the model on the next composition (same text: caret and composing range kept)
                    value = if (nv.text == value.text) nv.copy(annotatedString = value.annotatedString) else nv
                    s.textSel = DeckSession.TextSel(slide, sh.id, nv.selection.min, nv.selection.max)
                },
                textStyle = style,
                cursorBrush = SolidColor(Ink.Select),
                visualTransformation = marks,
                modifier = Modifier.fillMaxWidth().focusRequester(requester)
                    .onFocusChanged {
                        if (it.isFocused) { hadFocus = true; s.selected = sh.id; s.textEditing = true; s.textSel = DeckSession.TextSel(slide, sh.id, value.selection.min, value.selection.max) }
                        else if (hadFocus) { editing = false; s.textEditing = false }
                    }
                    .semantics { contentDescription = name },
            )
            LaunchedEffect(key) { repeat(5) { if (runCatching { requester.requestFocus() }.isSuccess) return@LaunchedEffect; kotlinx.coroutines.delay(60) } }
        } else {
            val shown = if (hit != null && hit.start + hit.len <= rich.length) io.embodit.spatialoffice.ui.doc.marked(rich, hit, hit.a) else rich
            Text(marks.filter(shown).text, style = style, modifier = Modifier.fillMaxWidth())
        }
    }
}

/** p17: B / I / U / strikethrough on the picked text box's selected characters (on when not all of them have it). */
fun slideToggle(s: DeckSession, attr: Attr): Boolean {
    val on = s.textCommon { sp -> when (attr) { Attr.BOLD -> sp.bold; Attr.ITALIC -> sp.italic; Attr.UNDERLINE -> sp.underline; Attr.STRIKE -> sp.strike } } != true
    return s.formatText { sp, a, b -> RichText.setAttr(sp, a, b, attr, on) }
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

/** p22 / p24: [Object ▾] fill ›, line ›, and the 90° turns (fill only for drawn shapes and text boxes). */
@Composable
private fun objectStyleEntries(s: DeckSession): List<MenuEntry> {
    val sh = s.pickedShape() ?: return emptyList()
    val canFill = sh.kind != ShapeKind.PICTURE && sh.table == null && sh.kind != ShapeKind.CHART && sh.kind != ShapeKind.OTHER
    val fill = if (canFill) subMenu(tr(R.string.fill_color, sh.fillRgb?.let { v -> TextPalette.SHAPE.firstOrNull { it.second == v }?.let { tr(it.first) } } ?: if (sh.fillRgb == null) tr(R.string.fill_none) else "…"), "slides/fill",
        TextPalette.SHAPE.map { (name, v) -> MenuEntry(tr(R.string.fill_color, tr(name)), "slides/fill-item", checked = sh.fillRgb == v) { s.setFill(v) } } +
            listOf(MenuEntry(tr(R.string.fill_none), "slides/fill-none", checked = sh.fillRgb == null) { s.setFill(null) })) else null
    val line = if (sh.kind != ShapeKind.CHART && sh.table == null && sh.kind != ShapeKind.OTHER) subMenu(tr(R.string.line_color, sh.lineRgb?.let { v -> TextPalette.SHAPE.firstOrNull { it.second == v }?.let { tr(it.first) } } ?: tr(R.string.line_none)), "slides/line",
        TextPalette.SHAPE.map { (name, v) -> MenuEntry(tr(R.string.line_color, tr(name)), "slides/line-item", checked = sh.lineRgb == v) { s.setLine(v, sh.lineWidthEmu ?: TextPalette.EMU_PER_PT) } } +
            listOf(MenuEntry(tr(R.string.line_none), "slides/line-none", checked = sh.lineRgb == null) { s.setLine(null, null) }) +
            TextPalette.LINE_PT.map { pt -> val emu = Math.round(pt * TextPalette.EMU_PER_PT).toLong(); MenuEntry(tr(R.string.line_width, TextPalette.pt(pt)), "slides/line-w", checked = sh.lineWidthEmu == emu) { s.setLine(sh.lineRgb ?: 0x111111, emu) } }) else null
    return listOfNotNull(
        fill, line,
        MenuEntry(tr(R.string.rot_right), "slides/rot-right") { s.rotateBy(90) },
        MenuEntry(tr(R.string.rot_left), "slides/rot-left") { s.rotateBy(-90) },
        if (sh.rotation != 0) MenuEntry(tr(R.string.rot_reset), "slides/rot-reset") { s.setRotation(0) } else null,
    )
}

/** Colour presets for [Theme]: background (lt1), text (dk1), second text / accent pair, accents. */
val THEMES: List<Pair<Int, Map<String, Int>>> = listOf(
    R.string.theme_light to mapOf("lt1" to 0xFFFFFF, "dk1" to 0x111827, "lt2" to 0xF3F4F6, "dk2" to 0x1F2937, "accent1" to 0x2563EB, "accent2" to 0xF97316),
    R.string.theme_dark to mapOf("lt1" to 0x111827, "dk1" to 0xF9FAFB, "lt2" to 0x1F2937, "dk2" to 0xE5E7EB, "accent1" to 0x60A5FA, "accent2" to 0xFBBF24),
    R.string.theme_warm to mapOf("lt1" to 0xFFF7ED, "dk1" to 0x431407, "lt2" to 0xFFEDD5, "dk2" to 0x7C2D12, "accent1" to 0xEA580C, "accent2" to 0x0D9488),
    R.string.theme_forest to mapOf("lt1" to 0xF0FDF4, "dk1" to 0x052E16, "lt2" to 0xDCFCE7, "dk2" to 0x14532D, "accent1" to 0x16A34A, "accent2" to 0x9333EA),
)

val SHAPE_FILL = 0x2563EB

/** [Object ▸ Copy] / Ctrl+C on a picked object. */
fun objCopy(graph: io.embodit.spatialoffice.platform.Graph, s: DeckSession) {
    graph.say(s, graph.str(if (s.copySelected()) R.string.obj_copied else R.string.obj_copy_refused))
}

/** [Insert ▸ Paste] / Ctrl+V: on the current slide, picked at once. */
fun objPaste(graph: io.embodit.spatialoffice.platform.Graph, s: DeckSession) {
    if (!s.pasteObject()) graph.say(s, graph.str(R.string.nothing_to_paste))
}

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
                    // d28 version history (the slides toolbar has no More)
                    MenuEntry(tr(R.string.menu_history), "slides/history") { graph.show(s, io.embodit.spatialoffice.platform.Overlay.History(Win.SLIDES)) },
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
                ) + listOfNotNull(
                    // p19: what was copied goes on the current slide, picked at once
                    if (s.canPaste) MenuEntry(tr(R.string.obj_paste), "slides/paste") { objPaste(graph, s) } else null,
                    MenuEntry(tr(R.string.edit_find), "slides/find") { graph.show(s, io.embodit.spatialoffice.platform.Overlay.Find(Win.SLIDES)) },
                ))
                // M2 p17 / p18: [Text ▾] B / I / U / strikethrough, sizes, alignment, bullets, clear formatting; [Colour ▾] text colours
                val textOn = s.pickedText() != null && !s.readOnly
                ToolMenu(tr(R.string.menu_text), "slides/text", enabled = textOn, entries = listOf(
                    MenuEntry(tr(R.string.bold), "slides/bold", checked = s.textCommon { it.bold } == true) { slideToggle(s, Attr.BOLD) },
                    MenuEntry(tr(R.string.italic), "slides/italic", checked = s.textCommon { it.italic } == true) { slideToggle(s, Attr.ITALIC) },
                    MenuEntry(tr(R.string.underline), "slides/underline", checked = s.textCommon { it.underline } == true) { slideToggle(s, Attr.UNDERLINE) },
                    MenuEntry(tr(R.string.strike), "slides/strike", checked = s.textCommon { it.strike } == true) { slideToggle(s, Attr.STRIKE) },
                    subMenu(tr(R.string.size_pt, Math.round(s.textBaseSize() / 2f)), "slides/size", sizeEntries("slides/text", s.textCommon { it.size },
                        { n -> val base = s.textBaseSize(); s.formatText { sp, a, b -> RichText.stepSize(sp, a, b, n, base) } },
                        { hp -> s.formatText { sp, a, b -> RichText.setSize(sp, a, b, hp) } })),
                    MenuEntry(tr(R.string.align_left), "slides/align-left", checked = s.paraAlign() == 0) { s.setParaFormat(align = 0) },
                    MenuEntry(tr(R.string.align_center), "slides/align-center", checked = s.paraAlign() == 1) { s.setParaFormat(align = 1) },
                    MenuEntry(tr(R.string.align_right), "slides/align-right", checked = s.paraAlign() == 2) { s.setParaFormat(align = 2) },
                    MenuEntry(tr(R.string.bullets), "slides/bullets", checked = s.paraBullets()) { s.setParaFormat(bullet = !s.paraBullets()) },
                    MenuEntry(tr(R.string.clear_format), "slides/clear-format") { s.formatText { sp, a, b -> RichText.clearFormat(sp, a, b) } },
                ))
                ToolMenu(tr(R.string.menu_color), "slides/color", enabled = textOn, entries =
                    colorEntries("slides/color", s.textCommon { it.color }, { rgb -> s.formatText { sp, a, b -> RichText.setColor(sp, a, b, rgb) } }))
                ToolMenu(tr(R.string.menu_object), "slides/object", enabled = hasSel, entries = listOf(
                    MenuEntry(tr(R.string.obj_left), "slides/obj-left") { s.nudge(-1, 0) },
                    MenuEntry(tr(R.string.obj_right), "slides/obj-right") { s.nudge(1, 0) },
                    MenuEntry(tr(R.string.obj_up), "slides/obj-up") { s.nudge(0, -1) },
                    MenuEntry(tr(R.string.obj_down), "slides/obj-down") { s.nudge(0, 1) },
                    MenuEntry(tr(R.string.obj_bigger), "slides/obj-bigger") { s.nudge(0, 0, 1.2f) },
                    MenuEntry(tr(R.string.obj_smaller), "slides/obj-smaller") { s.nudge(0, 0, 1f / 1.2f) },
                    MenuEntry(tr(R.string.obj_delete), "slides/obj-delete") { s.deleteSelected() },
                    // p19 copy / paste / duplicate, p20 layer order (only the moves that can still happen)
                    MenuEntry(tr(R.string.obj_copy), "slides/obj-copy") { objCopy(graph, s) },
                    MenuEntry(tr(R.string.obj_duplicate), "slides/obj-dup") { s.duplicateSelected() },
                ) + objectStyleEntries(s) + listOf(
                    ZOrder.FRONT to R.string.z_front, ZOrder.FORWARD to R.string.z_forward, ZOrder.BACKWARD to R.string.z_backward, ZOrder.BACK to R.string.z_back,
                ).filter { s.canReorder(it.first) }.map { (op, label) -> MenuEntry(tr(label), "slides/z-" + op.name.lowercase()) { s.reorder(op) } })
                ToolDivider()
                UndoRedo(s)
            }
        },
    )
}
