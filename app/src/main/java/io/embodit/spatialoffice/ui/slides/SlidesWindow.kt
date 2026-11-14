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
import androidx.compose.ui.res.stringResource
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
                ToolButton(stringResource(R.string.rehearse), "slides/rehearse", icon = R.drawable.ic_play, onClick = { graph.rehearse() })
            }
            Row(Modifier.fillMaxWidth().weight(1f).padding(start = 32.dp, end = 32.dp, bottom = 52.dp)) {
                Thumbnails(s, Modifier.width(208.dp).fillMaxHeight())
                Box(Modifier.width(20.dp))
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    s.version
                    val w = 960.dp
                    SlideCanvas(s, s.current, w, editable = true, modifier = Modifier.align(Alignment.CenterHorizontally))
                    Box(Modifier.height(12.dp))
                    Txt(stringResource(R.string.notes), "slides/notes-label", style = PicoTheme.typography.labelLarge, color = PicoTheme.colorScheme.labelSecondary)
                    Box(Modifier.height(6.dp))
                    val slide = s.deck.slides.getOrNull(s.current)
                    var notes by remember(s.current, s.deck) { mutableStateOf(slide?.notes ?: "") }
                    TextArea(
                        value = notes,
                        onValueChange = { notes = it; s.setNotes(s.current, it) },
                        modifier = Modifier.width(w).weight(1f).semantics { contentDescription = "notes-input" },
                        placeholder = { Txt(stringResource(R.string.notes_ph), "slides/notes-ph", color = PicoTheme.colorScheme.labelTertiary) },
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
        Key.PageDown -> { s.current = (s.current + 1).coerceAtMost(s.deck.slides.size - 1); true }
        Key.PageUp -> { s.current = (s.current - 1).coerceAtLeast(0); true }
        else -> false
    }
}

@Composable
private fun Thumbnails(s: DeckSession, modifier: Modifier) {
    s.version
    val state = rememberLazyListState()
    LaunchedEffect(s.current) { runCatching { state.animateScrollToItem(s.current.coerceAtLeast(0)) } }
    LazyColumn(modifier, state = state, verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(vertical = 2.dp)) {
        items(s.deck.slides.size) { i ->
            val on = i == s.current
            Row(Modifier.fillMaxWidth().clickable { s.current = i }.semantics { contentDescription = "slide-thumb-${i + 1}" }, verticalAlignment = Alignment.CenterVertically) {
                Txt((i + 1).toString(), "slides/thumb-no", Modifier.width(26.dp), style = PicoTheme.typography.labelLarge, color = if (on) PicoTheme.colorScheme.labelPrimary else PicoTheme.colorScheme.labelSecondary)
                Box(Modifier.border(if (on) 3.dp else 1.dp, if (on) Ink.Select else Ink.Line, RoundedCornerShape(8.dp)).padding(3.dp)) {
                    SlideCanvas(s, i, 170.dp, editable = false)
                }
            }
        }
    }
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
            val box = Modifier.offset((sh.x * perEmu).dp, (sh.y * perEmu).dp).size((sh.w * perEmu).coerceAtLeast(1f).dp, (sh.h * perEmu).coerceAtLeast(1f).dp)
            when (sh.kind) {
                ShapeKind.PICTURE -> sh.imagePart?.let { s.image(it) }?.let { Image(bitmap = it.asImageBitmap(), contentDescription = null, contentScale = ContentScale.FillBounds, modifier = box) }
                ShapeKind.TITLE, ShapeKind.BODY, ShapeKind.TEXT -> ShapeText(s, index, sh, box, perPt, editable && sh.editable)
                else -> Box(box.border((width.value / 600f).coerceAtLeast(0.5f).dp, Ink.Line, RoundedCornerShape(4.dp)), contentAlignment = Alignment.Center) {
                    if (editable) Text(stringResource(R.string.slide_other), style = TextStyle(fontSize = 13.sp, color = Ink.Hint, textAlign = TextAlign.Center), modifier = Modifier.padding(6.dp))
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
            stringResource(if (sh.kind == ShapeKind.TITLE) R.string.slide_title_ph else R.string.slide_body_ph),
            style = style.copy(color = Ink.Hint), modifier = Modifier.fillMaxWidth(),
        )
        BasicTextField(
            value = text,
            onValueChange = { text = it; s.setText(slide, sh.id, it) },
            textStyle = style,
            cursorBrush = SolidColor(Ink.Select),
            modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused }.semantics { contentDescription = "slide-text-${sh.id}" },
        )
    }
}

@Composable
private fun SlidesToolbar(graph: Graph, s: DeckSession) {
    s.version
    var layouts by remember { mutableStateOf(false) }
    Toolbar(
        supportingContent = { FileTools(graph, Win.SLIDES, s, spreadLabel = R.string.storyline) },
        content = {
            ToolRow {
                Box {
                    ToolButton(stringResource(R.string.add_slide), "slides/add", icon = R.drawable.ic_plus, onClick = { layouts = !layouts })
                    if (layouts) {
                        Menu(
                            onDismissRequest = { layouts = false },
                            positionProvider = rememberMenuPositionProvider(horizontalPlacement = HorizontalPlacement.alignStart(), verticalPlacement = VerticalPlacement.above(offset = (-12).dp)),
                        ) {
                            MenuItem(title = { Txt(stringResource(R.string.lay_title), "slides/layout-item") }, onClick = { layouts = false; s.addSlide(SlideLayoutKind.TITLE) })
                            MenuItem(title = { Txt(stringResource(R.string.lay_body), "slides/layout-item") }, onClick = { layouts = false; s.addSlide(SlideLayoutKind.TITLE_BODY) })
                        }
                    }
                }
                ToolIcon(R.drawable.ic_text, stringResource(R.string.text_box), onClick = { s.addTextBox("") })
                ToolIcon(R.drawable.ic_arrow_up, stringResource(R.string.slide_up), onClick = { s.moveSlide(-1) }, enabled = s.current > 0)
                ToolIcon(R.drawable.ic_arrow_down, stringResource(R.string.slide_down), onClick = { s.moveSlide(1) }, enabled = s.current < s.deck.slides.size - 1)
                ToolIcon(R.drawable.ic_delete, stringResource(R.string.delete_slide), onClick = { if (!s.deleteSlide()) graph.say(Win.SLIDES, graph.str(R.string.last_slide)) })
                ToolDivider()
                UndoRedo(s)
            }
        },
    )
}
