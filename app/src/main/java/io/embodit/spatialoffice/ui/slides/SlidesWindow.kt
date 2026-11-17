package io.embodit.spatialoffice.ui.slides

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
import io.embodit.spatialoffice.core.pptx.Shape
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
fun SlidesWindow() {
    WindowFrame(Win.SLIDES) { graph ->
        val s = graph.deck
        if (s == null) { NoFile(graph, Win.SLIDES); return@WindowFrame }
        Column(Modifier.fillMaxSize().onPreviewKeyEvent { e -> commonKeys(graph, Win.SLIDES, e) || slideKeys(s, e) }) {
            EditorTitleBar(graph, Win.SLIDES, s) {
                ToolButton(tr(R.string.rehearse), "slides/rehearse", icon = R.drawable.ic_play, onClick = { graph.rehearse() })
            }
            Row(Modifier.fillMaxWidth().weight(1f).padding(start = 32.dp, end = 32.dp, bottom = 52.dp)) {
                Thumbnails(s, Modifier.width(208.dp).fillMaxHeight())
                Box(Modifier.width(20.dp))
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    s.version
                    val w = 960.dp
                    SlideCanvas(s, s.current, w, editable = true, modifier = Modifier.align(Alignment.CenterHorizontally))
                    Box(Modifier.height(12.dp))
                    Txt(tr(R.string.notes), "slides/notes-label", style = PicoTheme.typography.labelLarge, color = PicoTheme.colorScheme.labelSecondary)
                    Box(Modifier.height(6.dp))
                    val slide = s.deck.slides.getOrNull(s.current)
                    var notes by remember(s.current, s.deck) { mutableStateOf(slide?.notes ?: "") }
                    TextArea(
                        value = notes,
                        onValueChange = { notes = it; s.setNotes(s.current, it) },
                        modifier = Modifier.width(w).weight(1f).semantics { contentDescription = "notes-input" },
                        placeholder = { Txt(tr(R.string.notes_ph), "slides/notes-ph", color = PicoTheme.colorScheme.labelTertiary) },
                    )
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
    val stepPx = with(androidx.compose.ui.platform.LocalDensity.current) { (THUMB_H + 16.dp).toPx() }
    LazyColumn(modifier, state = state, verticalArrangement = Arrangement.spacedBy(16.dp), contentPadding = PaddingValues(vertical = 2.dp)) {
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
                                val to = (i + Math.round(dragDy / stepPx)).coerceIn(0, n - 1)
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
                    SlideCanvas(s, i, 170.dp, editable = false)
                }
            }
        }
    }
}

private val THUMB_H = 170.dp * 9f / 16f + 6.dp

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
 * With [editable], text boxes are edited in place; objects this version cannot draw show as a light frame.
 */
@Composable
fun SlideCanvas(s: DeckSession, index: Int, width: Dp, editable: Boolean, modifier: Modifier = Modifier) {
    s.version
    val deck = s.deck
    val slide = deck.slides.getOrNull(index) ?: return
    val height = width * (deck.heightEmu.toFloat() / deck.widthEmu)
    val perEmu = width.value / deck.widthEmu           // dp per EMU
    val perPt = perEmu * 12700f                        // dp per point
    Box(modifier.size(width, height).clip(RoundedCornerShape((width.value / 120f).dp)).background(slide.backgroundRgb?.let { Color(0xFF000000 or it.toLong()) } ?: Ink.Paper)) {
        for (sh in slide.shapes) {
            val w = (sh.w * perEmu).coerceAtLeast(1f).dp
            var box = Modifier.offset((sh.x * perEmu).dp, (sh.y * perEmu).dp).size(w, (sh.h * perEmu).coerceAtLeast(1f).dp)
            sh.fillRgb?.takeIf { sh.kind != ShapeKind.PICTURE }?.let { rgb -> box = box.background(Color(0xFF000000 or rgb.toLong()), geomShape(sh.geom, w)) }
            if (editable && s.selected == sh.id) box = box.border(3.dp, Ink.Select, RoundedCornerShape(4.dp))
            when (sh.kind) {
                ShapeKind.PICTURE -> sh.imagePart?.let { s.image(it) }?.let { bmp ->
                    Image(
                        bitmap = bmp.asImageBitmap(), contentDescription = null, contentScale = ContentScale.FillBounds,
                        modifier = if (editable) box.clickable { s.selected = sh.id }.semantics { contentDescription = "slide-picture-${sh.id}" } else box,
                    )
                }
                ShapeKind.TITLE, ShapeKind.BODY, ShapeKind.TEXT -> ShapeText(s, index, sh, box, perPt, editable && sh.editable)
                else -> Box(box.border((width.value / 600f).coerceAtLeast(0.5f).dp, Ink.Line, RoundedCornerShape(4.dp)), contentAlignment = Alignment.Center) {
                    if (editable) Text(tr(R.string.slide_other), style = TextStyle(fontSize = 13.sp, color = Ink.Hint, textAlign = TextAlign.Center), modifier = Modifier.padding(6.dp))
                }
            }
        }
    }
}

private fun shapeString(sh: Shape): String = sh.paras.joinToString("\n") { it.text }

@Composable
private fun ShapeText(s: DeckSession, slide: Int, sh: Shape, box: Modifier, perPt: Float, editable: Boolean) {
    val size = ((sh.fontPt ?: if (sh.kind == ShapeKind.TITLE) 40f else 20f) * perPt)
    val style = TextStyle(
        fontSize = size.sp, lineHeight = (size * 1.25f).sp,
        color = sh.colorRgb?.let { Color(0xFF000000 or it.toLong()) } ?: Ink.Text,
        fontWeight = if (sh.bold || sh.kind == ShapeKind.TITLE) FontWeight.Bold else null,
        textAlign = when (sh.align) { 1 -> TextAlign.Center; 2 -> TextAlign.End; else -> TextAlign.Start },
    )
    val align = if (sh.kind == ShapeKind.TITLE) Alignment.CenterStart else Alignment.TopStart
    if (!editable) {
        Box(box, contentAlignment = align) {
            Text(sh.paras.joinToString("\n") { (if (it.bullet) "  ".repeat(it.level) + "• " else "") + it.text }, style = style, modifier = Modifier.fillMaxWidth())
        }
        return
    }
    val model = shapeString(sh)
    var text by remember(slide, sh.id) { mutableStateOf(model) }
    var focused by remember(slide, sh.id) { mutableStateOf(false) }
    if (!focused && text != model) text = model
    Box(box.border(1.dp, if (focused) Ink.Select else Color(0x22000000), RoundedCornerShape(4.dp)).padding(horizontal = 4.dp), contentAlignment = align) {
        if (text.isEmpty()) Text(
            tr(if (sh.kind == ShapeKind.TITLE) R.string.slide_title_ph else R.string.slide_body_ph),
            style = style.copy(color = Ink.Hint), modifier = Modifier.fillMaxWidth(),
        )
        BasicTextField(
            value = text,
            onValueChange = { text = it; s.setText(slide, sh.id, it) },
            textStyle = style,
            cursorBrush = SolidColor(Ink.Select),
            modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused; if (it.isFocused) s.selected = sh.id }.semantics { contentDescription = "slide-text-${sh.id}" },
        )
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
                    MenuEntry(tr(R.string.delete_slide), "slides/delete") { if (!s.deleteSlide()) graph.say(Win.SLIDES, graph.str(R.string.last_slide)) },
                ))
                ToolMenu(tr(R.string.menu_insert), "slides/insert", entries = listOf(
                    MenuEntry(tr(R.string.text_box), "slides/textbox") { s.addTextBox("") },
                    MenuEntry(tr(R.string.ins_picture), "slides/picture") { graph.pickImage(Win.SLIDES) { b, e, w, h -> s.addPicture(b, e, w, h) } },
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
                ToolMenu(tr(R.string.menu_theme), "slides/theme", entries = THEMES.map { (name, colors) ->
                    MenuEntry(tr(name), "slides/theme-item") { s.setColors(colors) }
                })
                ToolDivider()
                UndoRedo(s)
            }
        },
    )
}
