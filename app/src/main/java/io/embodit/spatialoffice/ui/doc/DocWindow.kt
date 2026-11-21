package io.embodit.spatialoffice.ui.doc

import androidx.compose.ui.layout.onSizeChanged
import io.embodit.spatialoffice.ui.dragTotal
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import io.embodit.spatialoffice.ui.tr
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pico.spatial.ui.design.Icon
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Text
import com.pico.spatial.ui.design.menu.Menu
import com.pico.spatial.ui.design.menu.MenuItem
import com.pico.spatial.ui.design.menu.rememberMenuPositionProvider
import com.pico.spatial.ui.design.windows.Toolbar
import com.pico.spatial.ui.design.windows.popup.HorizontalPlacement
import com.pico.spatial.ui.design.windows.popup.VerticalPlacement
import io.embodit.spatialoffice.R
import io.embodit.spatialoffice.core.docx.ParaStyle
import io.embodit.spatialoffice.core.text.Attr
import io.embodit.spatialoffice.core.text.RichText
import io.embodit.spatialoffice.core.text.Span
import io.embodit.spatialoffice.platform.DocSession
import io.embodit.spatialoffice.platform.Graph
import io.embodit.spatialoffice.platform.UiBlock
import io.embodit.spatialoffice.platform.Win
import io.embodit.spatialoffice.ui.EditorTitleBar
import io.embodit.spatialoffice.ui.MenuEntry
import io.embodit.spatialoffice.ui.ToolMenu
import io.embodit.spatialoffice.platform.Overlay
import androidx.compose.ui.text.style.TextAlign
import io.embodit.spatialoffice.ui.FileTools
import io.embodit.spatialoffice.ui.Ink
import io.embodit.spatialoffice.ui.NoFile
import io.embodit.spatialoffice.ui.ToolButton
import io.embodit.spatialoffice.ui.ToolDivider
import io.embodit.spatialoffice.ui.ToolRow
import io.embodit.spatialoffice.ui.ToolToggle
import io.embodit.spatialoffice.ui.Txt
import io.embodit.spatialoffice.ui.UndoRedo
import io.embodit.spatialoffice.ui.WindowFrame
import io.embodit.spatialoffice.ui.commonKeys

/** Which paragraph has the caret and what is selected in it; shared by the paper and the toolbar. */
class DocFocus {
    var id by mutableIntStateOf(-1)
    var selection by mutableStateOf(TextRange.Zero)
    /** Paragraph that should take focus on the next composition, and where the caret goes (−1 = end). */
    var want by mutableIntStateOf(-1)
    var wantCaret = -1
}

fun paraStyle(style: ParaStyle): TextStyle = when (style) {
    ParaStyle.TITLE -> TextStyle(fontSize = 30.sp, lineHeight = 40.sp, fontWeight = FontWeight.Bold, color = Ink.Text)
    ParaStyle.H1 -> TextStyle(fontSize = 26.sp, lineHeight = 36.sp, fontWeight = FontWeight.Bold, color = Ink.Text)
    ParaStyle.H2 -> TextStyle(fontSize = 22.sp, lineHeight = 32.sp, fontWeight = FontWeight.Bold, color = Ink.Text)
    ParaStyle.H3 -> TextStyle(fontSize = 19.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold, color = Ink.Text)
    else -> TextStyle(fontSize = 17.sp, lineHeight = 28.sp, color = Ink.Text)
}

fun aligned(style: TextStyle, align: Int): TextStyle = if (align == 0) style else style.copy(textAlign = when (align) { 1 -> TextAlign.Center; 2 -> TextAlign.End; else -> TextAlign.Justify })

/** Numbers of numbered-list paragraphs (1, 2, 3 … per run of consecutive numbered paragraphs), by block id. */
fun listNumbers(all: List<UiBlock>): Map<Int, Int> {
    val out = HashMap<Int, Int>()
    var n = 0
    for (b in all) {
        if (b is UiBlock.Para && b.style == ParaStyle.NUMBERED) { n++; out[b.id] = n } else n = 0
    }
    return out
}

@Composable
private fun Marker(b: UiBlock.Para, numbers: Map<Int, Int>) {
    when (b.style) {
        ParaStyle.BULLET -> Text("•", style = paraStyle(b.style), modifier = Modifier.width(26.dp))
        ParaStyle.NUMBERED -> Text("${numbers[b.id] ?: 1}.", style = paraStyle(b.style), modifier = Modifier.width(30.dp))
        else -> {}
    }
}

/** Colour of a find hit (d41): a yellow marker, black text stays ≥ 4.5:1 on it. */
val HitMark = androidx.compose.ui.graphics.Color(0xFFFFE066)

/** [text] with the find hit of paragraph [id] marked, if the find sheet points into it. */
fun marked(text: AnnotatedString, hit: io.embodit.spatialoffice.platform.FindHit?, id: Int): AnnotatedString {
    if (hit == null || hit.a != id || hit.start < 0 || hit.start + hit.len > text.length) return text
    return AnnotatedString.Builder(text).apply { addStyle(SpanStyle(background = HitMark), hit.start, hit.start + hit.len) }.toAnnotatedString()
}

/** A hit inside a sheet or a label (the find sheet's preview, the notes line): bold, underlined, in [ink]; a background colour there draws black. */
fun underlined(text: String, start: Int, len: Int, ink: androidx.compose.ui.graphics.Color): AnnotatedString {
    if (start < 0 || start + len > text.length) return AnnotatedString(text)
    return AnnotatedString.Builder(text).apply { addStyle(SpanStyle(color = ink, fontWeight = FontWeight.Bold, textDecoration = TextDecoration.Underline), start, start + len) }.toAnnotatedString()
}

fun annotated(spans: List<Span>): AnnotatedString = buildAnnotatedString {
    for (s in spans) withStyle(
        SpanStyle(
            fontWeight = if (s.bold) FontWeight.Bold else null,
            fontStyle = if (s.italic) FontStyle.Italic else null,
            textDecoration = when {
                s.underline && s.strike -> TextDecoration.combine(listOf(TextDecoration.Underline, TextDecoration.LineThrough))
                s.underline -> TextDecoration.Underline
                s.strike -> TextDecoration.LineThrough
                else -> null
            },
        ),
    ) { append(s.text) }
}

/** W1 document (Planar 1160 × 860 dp; v0.2.1 wider so the English toolbar fits with its longest labels, q1-5): title bar, white paper edited in place, toolbar. */
@Composable
fun DocWindow(tag: String? = null) {
    io.embodit.spatialoffice.ui.EditorWindow(Win.DOC, tag) { graph, session ->
        val s = session as? DocSession ?: return@EditorWindow
        val focus = remember(s) { DocFocus() }
        Column(Modifier.fillMaxSize().onPreviewKeyEvent { e -> docKeys(graph, s, focus, e) }) {
            EditorTitleBar(graph, Win.DOC, s)
            Box(Modifier.fillMaxWidth().weight(1f).padding(start = 32.dp, end = 32.dp, bottom = 56.dp), contentAlignment = Alignment.TopCenter) {
                Paper(s, focus, Modifier.width(760.dp).fillMaxSize()) { graph.windows.focus(graph.refOf(s)) }
            }
        }
        DocToolbar(graph, s, focus)
    }
}

private fun docKeys(graph: Graph, s: DocSession, focus: DocFocus, e: androidx.compose.ui.input.key.KeyEvent): Boolean {
    if (commonKeys(graph, s, e)) return true
    if (e.type != KeyEventType.KeyDown || !e.isCtrlPressed) return false
    when (e.key) {
        Key.B -> toggle(s, focus, Attr.BOLD)
        Key.I -> toggle(s, focus, Attr.ITALIC)
        Key.U -> toggle(s, focus, Attr.UNDERLINE)
        Key.F, Key.H -> if (!s.readOnly) graph.show(s, Overlay.Find(Win.DOC))
        else -> return false
    }
    return true
}

private fun focused(s: DocSession, focus: DocFocus): UiBlock.Para? = s.blocks().firstOrNull { it.id == focus.id } as? UiBlock.Para

/** Applies or clears a format on the selection; with only a caret, on the whole paragraph. */
private fun toggle(s: DocSession, focus: DocFocus, attr: Attr) {
    if (!s.richText) return
    val p = focused(s, focus)?.takeIf { it.editable } ?: return
    val sel = focus.selection
    val len = RichText.text(p.spans).length
    val (a, b) = if (sel.collapsed) 0 to len else sel.min.coerceIn(0, len) to sel.max.coerceIn(0, len)
    if (b <= a) return
    val on = !RichText.hasAttr(p.spans, a, b, attr)
    s.setParagraph(p.id, RichText.setAttr(p.spans, a, b, attr, on), p.style)
}

private fun hasAttr(s: DocSession, focus: DocFocus, attr: Attr): Boolean {
    val p = focused(s, focus) ?: return false
    val sel = focus.selection
    val len = RichText.text(p.spans).length
    return if (sel.collapsed) len > 0 && RichText.hasAttr(p.spans, 0, len, attr) else RichText.hasAttr(p.spans, sel.min.coerceIn(0, len), sel.max.coerceIn(0, len), attr)
}

@Composable
fun Paper(s: DocSession, focus: DocFocus?, modifier: Modifier = Modifier, blocks: List<UiBlock>? = null, onWindowFocus: () -> Unit = {}) {
    s.version
    s.blockCount
    val all = blocks ?: s.blocks()
    val numbers = listNumbers(all)
    val list = androidx.compose.foundation.lazy.rememberLazyListState()
    // a new table takes the caret in its first cell: bring it into view first, so the cell exists to take focus
    val caret = s.tableCaret
    LaunchedEffect(s.tableCaretTick) {
        val id = caret?.first ?: return@LaunchedEffect
        val i = all.indexOfFirst { it.id == id }
        if (i >= 0 && list.layoutInfo.visibleItemsInfo.none { it.key == id }) runCatching { list.scrollToItem(i) }
    }
    // d41: [Next] / [Previous] bring the paragraph with the hit into view
    LaunchedEffect(s.findTick) {
        val id = s.findHit?.a ?: return@LaunchedEffect
        val i = all.indexOfFirst { it.id == id }
        if (i >= 0) runCatching { list.scrollToItem((i - 1).coerceAtLeast(0) + if (all.isEmpty() && focus != null) 1 else 0) }
    }
    LazyColumn(
        modifier.clip(RoundedCornerShape(12.dp)).background(Ink.Paper).padding(horizontal = 44.dp),
        state = list,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 36.dp),
    ) {
        if (all.isEmpty() && focus != null && !s.readOnly) item("empty") {
            Text(
                tr(R.string.doc_placeholder), style = paraStyle(ParaStyle.BODY).copy(color = Ink.Hint),
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { focus.want = s.insertAfter(-1, emptyList(), ParaStyle.BODY) },
            )
        }
        items(all, key = { it.id }) { b ->
            when (b) {
                is UiBlock.Para -> if (focus != null && b.editable && !s.readOnly) ParaEditor(s, b, focus, all, numbers) else ParaView(b, numbers, s.findHit)
                is UiBlock.Table -> TableView(b, if (focus != null && s.richText && !s.readOnly) s else null, s) { onWindowFocus() }
                is UiBlock.Image -> PictureView(s, b, focus != null && s.richText && !s.readOnly)
                is UiBlock.Page -> Image(bitmap = b.bitmap.asImageBitmap(), contentDescription = null, contentScale = ContentScale.FillWidth, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp).border(1.dp, Ink.Line))
                is UiBlock.Opaque -> OpaqueView(b)
            }
        }
    }
}

@Composable
private fun ParaView(b: UiBlock.Para, numbers: Map<Int, Int> = emptyMap(), hit: io.embodit.spatialoffice.platform.FindHit? = null) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Marker(b, numbers)
        Text(marked(annotated(b.spans), hit, b.id), style = aligned(paraStyle(b.style), b.align), modifier = Modifier.weight(1f))
        if (b.why != null && b.why != "html") Txt(tr(R.string.ph_readonly), "doc/readonly", Modifier.padding(start = 8.dp), style = PicoTheme.typography.labelMedium, color = Ink.Hint)
    }
}

@Composable
private fun ParaEditor(s: DocSession, b: UiBlock.Para, focus: DocFocus, all: List<UiBlock>, numbers: Map<Int, Int>) {
    val requester = remember(b.id) { FocusRequester() }
    var value by remember(b.id) { mutableStateOf(TextFieldValue(annotated(b.spans))) }
    // model changed underneath (format toggle, undo, style): rebuild the styled text, keep the caret
    val text = RichText.text(b.spans)
    val styled = marked(annotated(b.spans), s.findHit, b.id)
    if (value.annotatedString != styled) {
        // same text: only the styling moved, so the caret and the input method's composing range stay as they are
        value = if (value.text == text) value.copy(annotatedString = styled) else TextFieldValue(styled, TextRange(text.length))
    }
    LaunchedEffect(focus.want) {
        if (focus.want == b.id) {
            runCatching { requester.requestFocus() }
            val c = if (focus.wantCaret < 0) text.length else focus.wantCaret.coerceIn(0, text.length)
            value = value.copy(selection = TextRange(c))
            focus.want = -1; focus.wantCaret = -1
        }
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Marker(b, numbers)
        BasicTextField(
            value = value,
            onValueChange = { nv ->
                if (nv.text == value.text) { value = nv; if (focus.id == b.id) focus.selection = nv.selection; return@BasicTextField }
                val nl = nv.text.indexOf('\n')
                if (nl >= 0) {
                    // Enter: this paragraph keeps what is before the break, a new one takes the rest
                    val whole = RichText.applyEdit(b.spans, nv.text.replace("\n", ""))
                    val left = cut(whole, 0, nl)
                    val right = cut(whole, nl, RichText.text(whole).length)
                    s.setParagraph(b.id, left, b.style)
                    val next = if (b.style == ParaStyle.BULLET || b.style == ParaStyle.NUMBERED) b.style else ParaStyle.BODY
                    val id = s.insertAfter(b.id, right, next)
                    focus.want = id; focus.wantCaret = 0
                } else {
                    val spans = RichText.applyEdit(b.spans, nv.text)
                    s.setParagraph(b.id, spans, b.style)
                    // keep what the input method handed over (caret, composing range); only the styling is ours
                    value = nv.copy(annotatedString = annotated(spans))
                    if (s.findHit?.a == b.id) s.findHit = null
                    focus.selection = nv.selection
                }
            },
            textStyle = aligned(paraStyle(b.style), b.align),
            cursorBrush = SolidColor(Ink.Select),
            modifier = Modifier.weight(1f).heightIn(min = 32.dp).focusRequester(requester)
                .semantics { contentDescription = "paragraph-${b.id}" }
                .onFocusChanged { if (it.isFocused) { focus.id = b.id; s.caretBlock = b.id; focus.selection = value.selection; s.picked = -1 } }
                .onPreviewKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    val editable = all.filterIsInstance<UiBlock.Para>().filter { it.editable }
                    val i = editable.indexOfFirst { it.id == b.id }
                    when {
                        // Backspace at the very start of an empty paragraph removes it and goes to the previous one
                        e.key == Key.Backspace && value.text.isEmpty() && editable.size > 1 -> {
                            val prev = editable.getOrNull(i - 1) ?: editable.getOrNull(i + 1)
                            s.delete(b.id)
                            if (prev != null) { focus.want = prev.id; focus.wantCaret = -1 }
                            true
                        }
                        e.key == Key.DirectionUp && value.selection.collapsed && value.selection.start == 0 && i > 0 -> { focus.want = editable[i - 1].id; focus.wantCaret = -1; true }
                        e.key == Key.DirectionDown && value.selection.collapsed && value.selection.start == value.text.length && i < editable.size - 1 -> { focus.want = editable[i + 1].id; focus.wantCaret = 0; true }
                        else -> false
                    }
                },
            decorationBox = { inner ->
                // full width, so centred / right / justified paragraphs line up with the page
                Box(Modifier.fillMaxWidth(), propagateMinConstraints = true) {
                    if (value.text.isEmpty() && all.size == 1) Text(tr(R.string.doc_placeholder), style = paraStyle(b.style).copy(color = Ink.Hint))
                    inner()
                }
            },
        )
    }
}

/** Characters [from, to) of [spans] as spans. */
private fun cut(spans: List<Span>, from: Int, to: Int): List<Span> {
    val out = ArrayList<Span>()
    var pos = 0
    for (sp in spans) {
        val a = maxOf(from, pos); val b = minOf(to, pos + sp.text.length)
        if (b > a) out.add(sp.copy(text = sp.text.substring(a - pos, b - pos)))
        pos += sp.text.length
    }
    return out
}

/**
 * A picture at the size the file gives it (share of the text width). Clicked, it shows a dot on each corner; dragging
 * one makes it larger or smaller, proportions kept, never wider than the text (v0.2.5).
 */
@Composable
private fun PictureView(s: DocSession, b: UiBlock.Image, editable: Boolean) {
    val bmp = b.bitmap ?: return
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        val full = maxWidth
        val fullPx = with(androidx.compose.ui.platform.LocalDensity.current) { full.toPx() }
        val live = s.resizing?.takeIf { it.first == b.id }?.second
        val text = io.embodit.spatialoffice.core.docx.DocxDocument.TEXT_WIDTH_EMU
        val wEmu = live?.w ?: b.widthEmu.takeIf { it > 0 } ?: text
        val w = full * (wEmu.toFloat() / text).coerceIn(0.05f, 1f)
        val h = w * (bmp.height.toFloat() / bmp.width.coerceAtLeast(1))
        val picked = editable && s.picked == b.id
        Box(Modifier.size(w, h)) {
            Image(
                bitmap = bmp.asImageBitmap(), contentDescription = null, contentScale = ContentScale.FillBounds,
                modifier = Modifier.fillMaxSize()
                    .then(if (editable) Modifier.clickable(interactionSource = remember(b.id) { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null) { s.picked = b.id } else Modifier)
                    .then(if (picked) Modifier.border(3.dp, Ink.Select) else Modifier)
                    .semantics { contentDescription = "doc-picture-${b.id}" },
            )
            if (picked) {
                val hit = io.embodit.spatialoffice.ui.cornerHit(w, h)
                val pxPerEmu = fullPx / text
                for (c in io.embodit.spatialoffice.core.layout.Corner.values()) io.embodit.spatialoffice.ui.CornerDot(
                    c, if (c.left) 0.dp else w, if (c.top) 0.dp else h, hit, tr(R.string.obj_resize),
                    Modifier.dragTotal(
                        key = Triple(b.id, c, pxPerEmu), name = "doc picture ${b.id} $c",
                        onStart = { s.beginResize(b.id) },
                        onTotal = { x, y -> s.resizeBy(c, io.embodit.spatialoffice.core.layout.BoxDrag.units(x, pxPerEmu), io.embodit.spatialoffice.core.layout.BoxDrag.units(y, pxPerEmu)) },
                        onEnd = { s.endResize() }, onCancel = { s.cancelResize() },
                    ),
                    "docpic-${b.id}",
                )
            }
        }
    }
}

/**
 * A table. v0.2.5: it takes its share of the text width, and with the caret in it (or after a click on it) the two
 * corners on its free side carry dots; dragging one makes the table narrower or wider (20–100 % of the text width).
 */
@Composable
private fun TableView(b: UiBlock.Table, edit: DocSession?, s: DocSession? = edit, windowFocus: () -> Unit = {}) {
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        val full = maxWidth
        val fullPx = with(androidx.compose.ui.platform.LocalDensity.current) { full.toPx() }
        val live = s?.resizing?.takeIf { it.first == b.id }?.second
        val pct = (live?.w?.toInt() ?: b.widthPct).coerceIn(10, 100)
        val picked = edit != null && edit.picked == b.id
        var tableH by remember(b.id) { mutableStateOf(0.dp) }
        val density = androidx.compose.ui.platform.LocalDensity.current
        Box(Modifier.width(full * (pct / 100f)).onSizeChanged { tableH = with(density) { it.height.toDp() } }) {
            TableGrid(b, edit, windowFocus)
            if (picked) Box(Modifier.matchParentSize().border(3.dp, Ink.Select))
        }
        if (picked && edit != null) {
            val w = full * (pct / 100f)
            val hit = io.embodit.spatialoffice.ui.cornerHit(w, tableH.coerceAtLeast(48.dp))
            for (c in listOf(io.embodit.spatialoffice.core.layout.Corner.TOP_RIGHT, io.embodit.spatialoffice.core.layout.Corner.BOTTOM_RIGHT)) io.embodit.spatialoffice.ui.CornerDot(
                c, w, if (c.top) 0.dp else tableH, hit, tr(R.string.obj_resize),
                Modifier.dragTotal(
                    key = Triple(b.id, c, fullPx), name = "doc table ${b.id} $c",
                    onStart = { edit.beginResize(b.id) },
                    // a table is resized in percent of the text width: the travel across the page, as a share of it
                    onTotal = { x, _ -> edit.resizeBy(c, Math.round(x * 100f / fullPx.coerceAtLeast(1f)).toLong(), 0) },
                    onEnd = { edit.endResize() }, onCancel = { edit.cancelResize() },
                ),
                "doctable-${b.id}",
            )
        }
    }
}

@Composable
private fun TableGrid(b: UiBlock.Table, edit: DocSession?, windowFocus: () -> Unit = {}) {
    Column(Modifier.fillMaxWidth().border(1.dp, Ink.Line).semantics { contentDescription = "table-${b.id}" }) {
        b.rows.forEachIndexed { r, row ->
            Row(Modifier.fillMaxWidth().background(if (r == 0) Ink.Header else Ink.Paper)) {
                row.forEachIndexed { c, cell ->
                    val style = TextStyle(fontSize = 15.sp, lineHeight = 22.sp, color = Ink.Text, fontWeight = if (r == 0) FontWeight.SemiBold else null)
                    val cellMod = Modifier.weight(1f).border(0.5.dp, Ink.Line).heightIn(min = 48.dp).padding(horizontal = 10.dp, vertical = 12.dp)
                    if (edit == null) Text(cell, style = style, modifier = cellMod)
                    else {
                        var text by remember(b.id, r, c) { mutableStateOf(TextFieldValue(cell)) }
                        var focused by remember(b.id, r, c) { mutableStateOf(false) }
                        // the model changed from outside this field (typing forwarded from the toolbar, undo): follow it
                        if (text.text != cell) text = TextFieldValue(cell, TextRange(cell.length))
                        val requester = remember(b.id, r, c) { FocusRequester() }
                        val want = edit.tableCaret
                        val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
                        val hostView = androidx.compose.ui.platform.LocalView.current
                        LaunchedEffect(edit.tableCaretTick) {
                            if (want != null && want.first == b.id && want.second == r && want.third == c) {
                                // Insert ▸ Table comes from a dialog: wait until it has closed and the window has its
                                // focus back, then take the caret, so the input session starts in this cell
                                kotlinx.coroutines.delay(450)
                                windowFocus()
                                // the toolbar ornament is a view tree of its own and keeps the keyboard after its menu:
                                // hand the view focus back to this window's content first
                                val viewFocus = runCatching { hostView.requestFocus() }.getOrDefault(false)
                                android.util.Log.i("SpatialOffice", "TABLE caret -> cell ${b.id},$r,$c hostFocus=$viewFocus hasWindowFocus=${hostView.hasWindowFocus()}")
                                repeat(5) {
                                    if (runCatching { requester.requestFocus() }.isSuccess) { keyboard?.show(); return@LaunchedEffect }
                                    kotlinx.coroutines.delay(80)
                                }
                            }
                        }
                        BasicTextField(
                            value = text,
                            onValueChange = { text = it; edit.setTableCell(b.id, r, c, it.text) },
                            textStyle = style,
                            cursorBrush = SolidColor(Ink.Select),
                            modifier = cellMod.focusRequester(requester)
                                .onFocusChanged {
                                    focused = it.isFocused
                                    if (it.isFocused) { edit.tableCaret = Triple(b.id, r, c); edit.picked = b.id; text = text.copy(selection = TextRange(text.text.length)) }
                                }
                                .then(if (focused) Modifier.border(2.dp, Ink.Select, RoundedCornerShape(4.dp)) else Modifier)
                                .semantics { contentDescription = "table-${b.id}-cell-$r-$c" },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun OpaqueView(b: UiBlock.Opaque) {
    val name = tr(
        when (b.kind) { "smart-graphic" -> R.string.ph_smart; "chart" -> R.string.ph_chart; "text-box" -> R.string.ph_text_box; else -> R.string.ph_other },
    )
    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp).clip(RoundedCornerShape(10.dp)).background(Ink.Opaque).border(1.dp, Ink.Line, RoundedCornerShape(10.dp)).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painter = painterResource(R.drawable.ic_image_unavailable), contentDescription = null, modifier = Modifier.size(24.dp), tint = Ink.TextSoft)
        Column(Modifier.padding(start = 12.dp)) {
            Text(name + tr(R.string.ph_readonly), style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Ink.Text))
            Text(tr(R.string.ph_sub), style = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, color = Ink.TextSoft))
        }
    }
}

/** Where an inserted picture or table goes: after the paragraph with the caret, else at the end. */
private fun insertPoint(s: DocSession, focus: DocFocus): Int = focus.id.takeIf { id -> s.blocks().any { it.id == id } } ?: (s.blocks().lastOrNull()?.id ?: -1)

@Composable
private fun DocToolbar(graph: Graph, s: DocSession, focus: DocFocus) {
    s.version
    val para = focused(s, focus)
    val canEdit = !s.readOnly && para?.editable == true
    fun restyle(st: ParaStyle) { if (para != null) { s.setParagraph(para.id, para.spans, st); focus.want = para.id } }
    fun align(a: Int) { if (para != null) { s.setParagraph(para.id, para.spans, para.style, a); focus.want = para.id } }
    // After a toolbar menu the system Toolbar window keeps the keyboard and the app cannot hand it back (restore
    // returns false, the content view has no window focus). So the toolbar takes the keys itself and passes them to
    // the cell with the caret: Insert ▸ Table, then typing, lands in the first cell (q1-3).
    val keys = remember(s) { FocusRequester() }
    LaunchedEffect(s.tableCaretTick) { if (s.tableCaret != null) { kotlinx.coroutines.delay(520); runCatching { keys.requestFocus() } } }
    Toolbar(
        supportingContent = { FileTools(graph, Win.DOC, s) },
        content = {
            if (!s.readOnly) Box(
                Modifier.focusRequester(keys).onPreviewKeyEvent { e -> toolbarKeys(graph, s, e) }.focusable(),
            ) { ToolRow {
                // q1-5: a short name on a fixed-width button (the menu keeps the long names), so [Save] never gets squeezed
                ToolMenu(styleShort(para?.style ?: ParaStyle.BODY), "doc/style", enabled = canEdit && s.styled, width = STYLE_BUTTON_W, entries = listOf(ParaStyle.BODY, ParaStyle.H1, ParaStyle.H2, ParaStyle.H3, ParaStyle.TITLE).map { st ->
                    MenuEntry(styleName(st), "doc/style-item", checked = para?.style == st) { restyle(st) }
                })
                ToolToggle(R.drawable.ic_text_bold, tr(R.string.bold), hasAttr(s, focus, Attr.BOLD), { toggle(s, focus, Attr.BOLD) }, enabled = canEdit && s.richText)
                ToolToggle(R.drawable.ic_text_italic, tr(R.string.italic), hasAttr(s, focus, Attr.ITALIC), { toggle(s, focus, Attr.ITALIC) }, enabled = canEdit && s.richText)
                ToolToggle(R.drawable.ic_text_underline, tr(R.string.underline), hasAttr(s, focus, Attr.UNDERLINE), { toggle(s, focus, Attr.UNDERLINE) }, enabled = canEdit && s.richText)
                ToolMenu(tr(R.string.menu_paragraph), "doc/paragraph", enabled = canEdit && s.styled, entries = listOfNotNull(
                    MenuEntry(tr(R.string.bullets), "doc/bullets", checked = para?.style == ParaStyle.BULLET) { restyle(if (para?.style == ParaStyle.BULLET) ParaStyle.BODY else ParaStyle.BULLET) },
                    MenuEntry(tr(R.string.numbering), "doc/numbering", checked = para?.style == ParaStyle.NUMBERED) { restyle(if (para?.style == ParaStyle.NUMBERED) ParaStyle.BODY else ParaStyle.NUMBERED) },
                ) + if (s.richText) listOf(
                    MenuEntry(tr(R.string.align_left), "doc/align-left", checked = para?.align == 0) { align(0) },
                    MenuEntry(tr(R.string.align_center), "doc/align-center", checked = para?.align == 1) { align(1) },
                    MenuEntry(tr(R.string.align_right), "doc/align-right", checked = para?.align == 2) { align(2) },
                    MenuEntry(tr(R.string.align_justify), "doc/align-justify", checked = para?.align == 3) { align(3) },
                ) else emptyList())
                ToolMenu(tr(R.string.menu_insert), "doc/insert", enabled = s.richText, entries = listOf(
                    MenuEntry(tr(R.string.ins_picture), "doc/picture") {
                        val at = insertPoint(s, focus)
                        graph.pickImage(s) { b, e, w, h -> s.insertImage(at, b, e, w, h) }
                    },
                    MenuEntry(tr(R.string.ins_table), "doc/table") { graph.show(s, Overlay.InsertTable(Win.DOC)) },
                ))
                ToolMenu(tr(R.string.more), "doc/more", entries = listOfNotNull(
                    if (s.richText && canEdit) MenuEntry(tr(R.string.strike), "doc/strike", checked = hasAttr(s, focus, Attr.STRIKE)) { toggle(s, focus, Attr.STRIKE) } else null,
                    MenuEntry(tr(R.string.find_replace), "doc/find") { graph.show(s, Overlay.Find(Win.DOC)) },
                    MenuEntry(tr(R.string.word_count), "doc/wordcount") { graph.show(s, Overlay.WordCount(Win.DOC)) },
                ))
                ToolDivider()
                UndoRedo(s)
            } } else Row(verticalAlignment = Alignment.CenterVertically) {
                Txt(tr(R.string.readonly_note), "doc/readonly-note", Modifier.padding(horizontal = 12.dp), style = PicoTheme.typography.bodyMedium)
                ToolButton(tr(R.string.word_count), "doc/wordcount-ro", onClick = { graph.show(s, Overlay.WordCount(Win.DOC)) })
            }
        },
    )
}

/** Keys that reach the toolbar window: shortcuts as everywhere, typing into the cell with the caret. */
private fun toolbarKeys(graph: Graph, s: DocSession, e: androidx.compose.ui.input.key.KeyEvent): Boolean {
    if (commonKeys(graph, s, e)) return true
    if (e.type != KeyEventType.KeyDown || e.isCtrlPressed || s.tableCaret == null) return false
    return when (e.key) {
        Key.Backspace -> s.backspaceInCell()
        Key.Tab -> s.moveCell(down = false)
        Key.Enter, Key.NumPadEnter -> s.moveCell(down = true)
        else -> {
            val cp = e.nativeKeyEvent.unicodeChar
            if (cp >= 32) s.typeInCell(String(Character.toChars(cp))) else false
        }
    }
}

/** Paragraph id after which [DocWindow]'s Insert ▸ Table puts the table (debug and dialog share it). */
fun docInsertTable(s: DocSession, rows: Int, cols: Int, afterId: Int? = null): Int =
    s.insertTable(afterId ?: s.caretBlock.takeIf { id -> s.blocks().any { it.id == id } } ?: (s.blocks().lastOrNull()?.id ?: -1), rows, cols)

@Composable
private fun styleName(st: ParaStyle): String = tr(
    when (st) {
        ParaStyle.TITLE -> R.string.style_title
        ParaStyle.H1 -> R.string.style_h1
        ParaStyle.H2 -> R.string.style_h2
        ParaStyle.H3 -> R.string.style_h3
        ParaStyle.BULLET, ParaStyle.NUMBERED -> R.string.style_bullet
        ParaStyle.BODY -> R.string.style_body
    },
)

/** Width of the paragraph-style button: fits the longest short name in both languages ("Heading 3", "Numbered"). */
val STYLE_BUTTON_W = 120.dp

/** Short paragraph-style name for the toolbar button (one word or two; numbered lists are no longer called bullets). */
@Composable
private fun styleShort(st: ParaStyle): String = tr(
    when (st) {
        ParaStyle.TITLE -> R.string.style_title
        ParaStyle.H1 -> R.string.style_h1
        ParaStyle.H2 -> R.string.style_h2_short
        ParaStyle.H3 -> R.string.style_h3
        ParaStyle.BULLET -> R.string.style_bullet_short
        ParaStyle.NUMBERED -> R.string.style_numbered_short
        ParaStyle.BODY -> R.string.style_body
    },
)
