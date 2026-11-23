package io.embodit.spatialoffice.ui.doc

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.layout.onSizeChanged
import io.embodit.spatialoffice.platform.typingTarget
import io.embodit.spatialoffice.ui.noKeyFocus
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
import androidx.compose.ui.input.key.isShiftPressed
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
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.em
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.text.TextLayoutResult
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
import io.embodit.spatialoffice.ui.probe
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
import io.embodit.spatialoffice.ui.colorEntries
import io.embodit.spatialoffice.ui.sizeEntries
import io.embodit.spatialoffice.ui.subMenu

/** Which paragraph has the caret and what is selected in it; shared by the paper and the toolbar. */
class DocFocus {
    var id by mutableIntStateOf(-1)
    var selection by mutableStateOf(TextRange.Zero)
    /** Paragraph that should take focus on the next composition, and where the caret goes (−1 = end). */
    var want by mutableIntStateOf(-1)
    var wantCaret = -1
    /** v0.6.0 (d23): a drag inside one paragraph selects in it: (paragraph, from, to); [dragTick] bumps on every change. */
    var dragSel: Triple<Int, Int, Int>? = null
    var dragTick by mutableIntStateOf(0)
    /** M5: the caret as last seen by the comment-marker check, so the same position does not pick a card twice. */
    var lastCaret: TextRange = TextRange.Zero
    /** M5 d24: a drag over text is under way (its release paints the dragged range; focus moving meanwhile must not paint). */
    var dragging = false
}

/**
 * v0.6.0 (d23): where the paragraphs being typed in are on the paper and how their text is laid out, so a drag can
 * run from the middle of one paragraph to the middle of another (each paragraph is a text field of its own; a
 * field's own drag stops at its edge).
 */
class ParaGeo {
    val paras = HashMap<Int, Pair<androidx.compose.ui.layout.LayoutCoordinates?, TextLayoutResult?>>()
    var paper: androidx.compose.ui.layout.LayoutCoordinates? = null
    fun coords(id: Int, c: androidx.compose.ui.layout.LayoutCoordinates) {
        paras[id] = c to paras[id]?.second
        // where each paragraph is in window pixels (for `adb shell input swipe` checks of the drag across paragraphs)
        val r = runCatching { c.boundsInWindow() }.getOrNull() ?: return
        val line = "${r.left.toInt()},${r.top.toInt()} ${r.width.toInt()}x${r.height.toInt()}"
        if (logged[id] != line) { logged[id] = line; android.util.Log.i(io.embodit.spatialoffice.platform.TAG, "DOCGEO para=$id $line") }
    }
    private val logged = HashMap<Int, String>()
    fun layout(id: Int, l: TextLayoutResult) { paras[id] = paras[id]?.first to l }

    /** The paragraph under (or nearest above / below) [pos] on the paper and the character offset there. */
    fun hit(pos: androidx.compose.ui.geometry.Offset): Pair<Int, Int>? {
        val p = paper?.takeIf { it.isAttached } ?: return null
        var best: Triple<Int, Float, androidx.compose.ui.geometry.Offset>? = null
        for ((id, pair) in paras) {
            val c = pair.first?.takeIf { it.isAttached } ?: continue
            if (pair.second == null) continue
            val o = p.localPositionOf(c, androidx.compose.ui.geometry.Offset.Zero)
            val top = o.y; val bottom = o.y + c.size.height
            val d = if (pos.y < top) top - pos.y else if (pos.y > bottom) pos.y - bottom else 0f
            if (best == null || d < best.second) best = Triple(id, d, pos - o)
        }
        val (id, _, local) = best ?: return null
        val l = paras[id]?.second ?: return null
        val x = local.x.coerceIn(0f, l.size.width.toFloat())
        val y = local.y.coerceIn(0f, (l.size.height - 1).toFloat().coerceAtLeast(0f))
        return id to l.getOffsetForPosition(androidx.compose.ui.geometry.Offset(x, y))
    }
}

val LocalParaGeo = androidx.compose.runtime.staticCompositionLocalOf<ParaGeo?> { null }

/** d23: the characters of a paragraph inside the selection across paragraphs, on a light blue ground. */
fun crossMarked(t: AnnotatedString, r: Pair<Int, Int>?): AnnotatedString {
    if (r == null || r.second <= r.first || r.first >= t.length) return t
    return AnnotatedString.Builder(t).apply { addStyle(androidx.compose.ui.text.SpanStyle(background = Ink.SelectFill), r.first, minOf(r.second, t.length)) }.toAnnotatedString()
}

/** d23: [text] on the system clipboard (plain text; the formats stay in [DocSession.docClip]). */
fun putClipboard(graph: Graph, text: String) {
    runCatching {
        val cm = graph.app.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("text", text))
    }.onFailure { android.util.Log.w(io.embodit.spatialoffice.platform.TAG, "CLIP set failed: $it") }
}

private fun clipboardText(graph: Graph): String? = runCatching {
    val cm = graph.app.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
    cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(graph.app)?.toString()
}.getOrNull()

/**
 * d23 Ctrl+C / Ctrl+X / [More ▸ Copy / Cut]: a selection across paragraphs is copied with its formats (and cut);
 * characters of one paragraph are kept with their formats too. [fromKey]: the key path, where a paragraph's own text
 * field copies / cuts plain text itself (false is returned so it does). True when handled here.
 */
fun docCopy(graph: Graph, s: DocSession, focus: DocFocus, cut: Boolean, fromKey: Boolean): Boolean {
    if (s.crossSel != null) {
        val clip = s.copyCross() ?: return false
        putClipboard(graph, clip.text)
        if (cut && !s.readOnly) s.deleteCross()?.let { (id, at) -> focus.want = id; focus.wantCaret = at }
        graph.say(s, graph.str(if (cut) R.string.doc_cut_n else R.string.doc_copied_n, clip.paras.size))
        return true
    }
    val p = focused(s, focus) ?: return false
    val a = focus.selection.min; val b = focus.selection.max
    if (b <= a) return false
    val clip = s.copyRange(p.id, a, b) ?: return false
    if (fromKey) return false
    putClipboard(graph, clip.text)
    if (cut && p.editable && !s.readOnly) {
        s.setParagraph(p.id, DocSession.cutSpans(p.spans, 0, a) + DocSession.cutSpans(p.spans, b, Int.MAX_VALUE), p.style)
        focus.want = p.id; focus.wantCaret = a
    }
    return true
}

/**
 * d23 Ctrl+V / [More ▸ Paste]: what was copied here goes in with its formats (paragraph styles and list levels kept)
 * when the clipboard still holds its text; other text from the clipboard goes in as plain paragraphs.
 */
fun docPaste(graph: Graph, s: DocSession, focus: DocFocus, fromKey: Boolean): Boolean {
    if (s.readOnly) return false
    val sys = clipboardText(graph)
    val own = DocSession.docClip?.takeIf { sys == null || sys == it.text }
    // a key with only outside text and no selection across paragraphs: the paragraph's own field pastes it
    if (own == null && fromKey && s.crossSel == null) return false
    val clip = own ?: sys?.takeIf { it.isNotEmpty() }?.let { t -> io.embodit.spatialoffice.platform.DocClip(t.replace("\r\n", "\n").split('\n').map { io.embodit.spatialoffice.platform.DocClipPara(listOf(Span(it)), ParaStyle.BODY, 0) }, t) } ?: return false
    var target = focused(s, focus)?.takeIf { it.editable }
    var a = focus.selection.min; var b = focus.selection.max
    if (s.crossSel != null) s.deleteCross()?.let { (id, at) -> target = s.blocks().firstOrNull { it.id == id } as? UiBlock.Para; a = at; b = at }
    val p = target ?: (s.blocks().lastOrNull { it is UiBlock.Para && it.editable } as? UiBlock.Para) ?: return false
    if (target == null) { a = RichText.text(p.spans).length; b = a }
    val caret = s.pasteClip(clip, p.id, a, b) ?: return false
    focus.want = caret.first; focus.wantCaret = caret.second
    graph.say(s, graph.str(R.string.doc_pasted_n, clip.paras.size))
    return true
}

fun paraStyle(style: ParaStyle): TextStyle = when (style) {
    ParaStyle.TITLE -> TextStyle(fontSize = 30.sp, lineHeight = 40.sp, fontWeight = FontWeight.Bold, color = Ink.Text)
    ParaStyle.H1 -> TextStyle(fontSize = 26.sp, lineHeight = 36.sp, fontWeight = FontWeight.Bold, color = Ink.Text)
    ParaStyle.H2 -> TextStyle(fontSize = 22.sp, lineHeight = 32.sp, fontWeight = FontWeight.Bold, color = Ink.Text)
    ParaStyle.H3 -> TextStyle(fontSize = 19.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold, color = Ink.Text)
    else -> TextStyle(fontSize = 17.sp, lineHeight = 28.sp, color = Ink.Text)
}

fun aligned(style: TextStyle, align: Int): TextStyle = if (align == 0) style else style.copy(textAlign = when (align) { 1 -> TextAlign.Center; 2 -> TextAlign.End; else -> TextAlign.Justify })

/**
 * Numbers of numbered-list paragraphs by block id: 1, 2, 3 … per run of consecutive list paragraphs, counted per level
 * (M2 d17: a deeper level starts again at 1 and a shallower one carries on).
 */
fun listNumbers(all: List<UiBlock>): Map<Int, Int> {
    val out = HashMap<Int, Int>()
    val n = IntArray(9)
    for (b in all) {
        if (b is UiBlock.Para && (b.style == ParaStyle.NUMBERED || b.style == ParaStyle.BULLET)) {
            val l = b.level.coerceIn(0, 8)
            for (k in l + 1 until 9) n[k] = 0
            if (b.style == ParaStyle.NUMBERED) { n[l]++; out[b.id] = n[l] }
        } else n.fill(0)
    }
    return out
}

/** The list marker of [b] at its level: • ◦ ▪ for bullets; 1. a. i. for numbers (cycling every three levels). */
fun listMarker(b: UiBlock.Para, numbers: Map<Int, Int>): String? = when (b.style) {
    ParaStyle.BULLET -> listOf("•", "◦", "▪")[b.level % 3]
    ParaStyle.NUMBERED -> {
        val n = numbers[b.id] ?: 1
        when (b.level % 3) { 0 -> "$n."; 1 -> ('a' + (n - 1) % 26) + "."; else -> roman(n) + "." }
    }
    else -> null
}

private fun roman(n: Int): String {
    var v = n.coerceIn(1, 3999); val sb = StringBuilder()
    for ((k, r) in listOf(1000 to "m", 900 to "cm", 500 to "d", 400 to "cd", 100 to "c", 90 to "xc", 50 to "l", 40 to "xl", 10 to "x", 9 to "ix", 5 to "v", 4 to "iv", 1 to "i")) while (v >= k) { sb.append(r); v -= k }
    return sb.toString()
}

/** Page dp per twip of indent: the 672 dp text column stands for the 6.27 in (9026 twips) of an A4 text width. */
const val DP_PER_TWIP = 672f / 9026f

/** d17 / d19: where the paragraph starts (list level, indent) and the room above and below it. */
fun paraPadding(b: UiBlock.Para): androidx.compose.foundation.layout.PaddingValues = androidx.compose.foundation.layout.PaddingValues(
    start = (b.level * 28 + b.indentTw * DP_PER_TWIP).dp,
    top = (4 + (b.spaceBefore ?: 0) * DP_PER_TWIP).dp,
    bottom = (4 + (b.spaceAfter ?: 0) * DP_PER_TWIP).dp,
)

/** The paragraph's text style: its style's size and line height, scaled for its largest text and by its line spacing (d16 / d19). */
fun paraTextStyle(b: UiBlock.Para): TextStyle {
    val base = aligned(paraStyle(b.style), b.align)
    val biggest = b.spans.maxOfOrNull { (it.size ?: b.baseSizeHp).toFloat() / b.baseSizeHp.coerceAtLeast(1) }?.coerceAtLeast(1f) ?: 1f
    val spacing = (b.lineSpacing ?: 240) / 240f
    return base.copy(lineHeight = (base.lineHeight.value * biggest * spacing).sp)
}

@Composable
private fun Marker(b: UiBlock.Para, numbers: Map<Int, Int>) {
    val m = listMarker(b, numbers) ?: return
    Text(m, style = paraTextStyle(b), modifier = Modifier.width(if (b.style == ParaStyle.BULLET) 26.dp else 34.dp))
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

/** [spans] styled: bold / italic / underline / strikethrough, and (M2) colour, highlight and size relative to [baseHp] at [baseSp]. */
fun annotated(spans: List<Span>, baseHp: Int = 22, baseSp: Float = 17f, me: String = ""): AnnotatedString = buildAnnotatedString {
    for (s in spans) {
        // M5 d30 / d36: tracked changes on the paper: added = underlined (purple; blue when it is mine), deleted = red, struck through
        val rev = s.rev
        if (rev != null) {
            val del = rev.kind == io.embodit.spatialoffice.core.review.RevisionKind.DELETE
            val ink = if (del) io.embodit.spatialoffice.ui.ReviewLook.Del else if (me.isNotBlank() && rev.author == me) io.embodit.spatialoffice.ui.ReviewLook.Mine else io.embodit.spatialoffice.ui.ReviewLook.Ins
            withStyle(SpanStyle(
                color = ink, fontWeight = if (s.bold) FontWeight.Bold else null, fontStyle = if (s.italic) FontStyle.Italic else null,
                fontSize = s.size?.let { (baseSp * it / baseHp.coerceAtLeast(1)).sp } ?: androidx.compose.ui.unit.TextUnit.Unspecified,
                textDecoration = if (del) TextDecoration.LineThrough else TextDecoration.Underline,
            )) { append(s.text) }
            continue
        }
        // M3 d20: a link from a file whose colour comes from its character style still looks like a link
        val color = s.color ?: s.link?.let { RichText.LINK_RGB }
        val underline = s.underline || s.link != null
        // M3 d22: superscript / subscript at 70 % of the size, raised or lowered
        val small = s.vertAlign != null
        withStyle(
            SpanStyle(
                color = color?.let { io.embodit.spatialoffice.ui.TextPalette.rgb(it) } ?: androidx.compose.ui.graphics.Color.Unspecified,
                background = io.embodit.spatialoffice.ui.TextPalette.highlight(s.highlight) ?: androidx.compose.ui.graphics.Color.Unspecified,
                fontSize = s.size?.let { (baseSp * it / baseHp.coerceAtLeast(1) * (if (small) 0.7f else 1f)).sp } ?: if (small) 0.7.em else androidx.compose.ui.unit.TextUnit.Unspecified,
                fontWeight = if (s.bold) FontWeight.Bold else null,
                fontStyle = if (s.italic) FontStyle.Italic else null,
                baselineShift = when (s.vertAlign) { "superscript" -> BaselineShift.Superscript; "subscript" -> BaselineShift.Subscript; else -> null },
                textDecoration = when {
                    underline && s.strike -> TextDecoration.combine(listOf(TextDecoration.Underline, TextDecoration.LineThrough))
                    underline -> TextDecoration.Underline
                    s.strike -> TextDecoration.LineThrough
                    else -> null
                },
            ),
        ) { append(s.text) }
    }
}

/** M3 d21: a 1.5 dp line along the bottom of the paragraph (its bottom border, Word's horizontal line). */
fun Modifier.ruleLine(on: Boolean): Modifier = if (!on) this else this.drawBehind {
    val y = size.height - 1.dp.toPx()
    drawLine(Ink.TextSoft, androidx.compose.ui.geometry.Offset(0f, y), androidx.compose.ui.geometry.Offset(size.width, y), strokeWidth = 1.5.dp.toPx())
}

/** W1 document (Planar 1160 × 860 dp; v0.2.1 wider so the English toolbar fits with its longest labels, q1-5): title bar, white paper edited in place, toolbar. */
@Composable
fun DocWindow(tag: String? = null) {
    io.embodit.spatialoffice.ui.EditorWindow(Win.DOC, tag) { graph, session ->
        val s = session as? DocSession ?: return@EditorWindow
        val focus = remember(s) { DocFocus() }
        Column(Modifier.fillMaxSize().onPreviewKeyEvent { e -> docKeys(graph, s, focus, e) }) {
            EditorTitleBar(graph, Win.DOC, s)
            // v0.5.0 (d43): the paper at the window's zoom (100 % = fits the window's width); wider than the window scrolls sideways
            // M5: the review pane stands inside the window, right of the paper (24 + paper 760 + 16 + pane 336 + 24 = the window's 1160 dp);
            // the window itself keeps its size and place
            val pane = s.reviewOpen && s.canReview
            Row(Modifier.fillMaxWidth().weight(1f).padding(start = if (pane) 24.dp else 32.dp, end = if (pane) 24.dp else 32.dp, bottom = 56.dp)) {
                BoxWithConstraints(Modifier.weight(1f).fillMaxHeight()) {
                    val paperW = 760.dp * (s.zoom / 100f)
                    val wide = paperW > maxWidth
                    Box(
                        Modifier.fillMaxSize().then(if (wide) Modifier.horizontalScroll(rememberScrollState()) else Modifier),
                        contentAlignment = Alignment.TopCenter,
                    ) {
                        io.embodit.spatialoffice.ui.Zoomed(s) {
                            Paper(s, focus, Modifier.width(760.dp).fillMaxHeight(), graph = graph) { graph.windows.focus(graph.refOf(s)) }
                        }
                    }
                }
                if (pane) {
                    Box(Modifier.width(16.dp))
                    io.embodit.spatialoffice.ui.ReviewPane(graph, s)
                }
            }
        }
        // M5 d24: while the format painter is in hand, a strip above the toolbar says what a tap does
        if (s.painter != null) Box(Modifier.fillMaxSize().padding(bottom = 64.dp), contentAlignment = Alignment.BottomCenter) { PainterChip() }
        DocToolbar(graph, s, focus)
    }
}

private fun docKeys(graph: Graph, s: DocSession, focus: DocFocus, e: androidx.compose.ui.input.key.KeyEvent): Boolean {
    if (commonKeys(graph, s, e)) return true
    if (e.type != KeyEventType.KeyDown) return false
    // d23: a selection across paragraphs answers Delete / Backspace (and Esc lets go of it)
    // M5: Esc puts the format painter down; Ctrl+Shift+C picks it up, Ctrl+Shift+E turns "Track my edits" on / off
    if (e.key == Key.Escape && s.painter != null) { s.dropPainter(); return true }
    if (e.isCtrlPressed && e.isShiftPressed && !e.nativeKeyEvent.isAltPressed) when (e.key) {
        Key.C -> { docPickPainter(graph, s, focus); return true }
        Key.E -> { docToggleTracking(graph, s); return true }
        else -> {}
    }
    if (!e.isCtrlPressed && s.crossSel != null) when (e.key) {
        Key.Delete, Key.Backspace -> { s.deleteCross()?.let { (id, at) -> focus.want = id; focus.wantCaret = at }; return true }
        Key.Escape -> { s.crossSel = null; return true }
        else -> {}
    }
    if (!e.isCtrlPressed) return false
    when (e.key) {
        Key.C -> return docCopy(graph, s, focus, cut = false, fromKey = true)
        Key.X -> return docCopy(graph, s, focus, cut = true, fromKey = true)
        Key.V -> return docPaste(graph, s, focus, fromKey = true)
        Key.B -> toggle(s, focus, Attr.BOLD)
        Key.I -> toggle(s, focus, Attr.ITALIC)
        Key.U -> toggle(s, focus, Attr.UNDERLINE)
        Key.F, Key.H -> if (!s.readOnly) graph.show(s, Overlay.Find(Win.DOC))
        Key.K -> docLinkDialog(graph, s, focus)
        else -> return false
    }
    return true
}

/**
 * M3 d20: [Insert ▸ Link…] / Ctrl+K: with the caret in a link, edit that link; with characters selected, link them;
 * with only a caret, type a new linked text there. False when there is no editable paragraph with the caret.
 */
fun docLinkDialog(graph: Graph, s: DocSession, focus: DocFocus): Boolean {
    if (!s.richText || s.readOnly) return false
    val p = focused(s, focus)?.takeIf { it.editable && !it.pageBreak } ?: return false
    val text = RichText.text(p.spans)
    val len = text.length
    val a = focus.selection.min.coerceIn(0, len)
    val b = focus.selection.max.coerceIn(0, len)
    val hit = RichText.linkAt(p.spans, a)
    val o = if (hit != null && (b == a || b <= hit.third)) Overlay.Link(Win.DOC, p.id, hit.second, hit.third, hit.first, text.substring(hit.second, hit.third), true)
        else Overlay.Link(Win.DOC, p.id, a, b, "", if (b > a) text.substring(a, b) else "", false)
    graph.show(s, o)
    return true
}

/** M3 d20: the link the caret of the focused paragraph is in (URL, range), if any. */
private fun caretLink(b: UiBlock.Para, sel: TextRange): Triple<String, Int, Int>? =
    if (!sel.collapsed) null else RichText.linkAt(b.spans, sel.start)

/** M3 d42: [op] on the table with the caret ("row-above" … "merge-down"); a merge with nothing to merge says so. */
fun docTableOp(graph: Graph?, s: DocSession, op: String): Boolean {
    val ok = s.tableOp(op)
    if (!ok && op.startsWith("merge") && graph != null) graph.say(s, graph.str(R.string.tbl_merge_none))
    return ok
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

/**
 * M2: [f] over the selection of the paragraph with the caret; with only a caret, over the whole paragraph (as the
 * B / I / U toggles do). [f] gets the spans, the range and the paragraph.
 */
fun formatDoc(s: DocSession, focus: DocFocus, f: (List<Span>, Int, Int, UiBlock.Para) -> List<Span>): Boolean {
    if (!s.richText) return false
    val p = focused(s, focus)?.takeIf { it.editable } ?: return false
    val sel = focus.selection
    val len = RichText.text(p.spans).length
    val (a, b) = if (sel.collapsed) 0 to len else sel.min.coerceIn(0, len) to sel.max.coerceIn(0, len)
    return s.formatRange(p.id, a, b, f)
}

/** Value shared by the selected characters (the whole paragraph with only a caret); null when mixed. */
fun <T> docCommon(s: DocSession, focus: DocFocus, get: (Span) -> T?): T? {
    val p = focused(s, focus) ?: return null
    val sel = focus.selection
    val len = RichText.text(p.spans).length
    return if (sel.collapsed) RichText.common(p.spans, 0, len, get) else RichText.common(p.spans, sel.min.coerceIn(0, len), sel.max.coerceIn(0, len), get)
}

fun docStepSize(s: DocSession, focus: DocFocus, steps: Int) = formatDoc(s, focus) { sp, a, b, p -> RichText.stepSize(sp, a, b, steps, p.baseSizeHp) }
fun docSetSize(s: DocSession, focus: DocFocus, hp: Int) = formatDoc(s, focus) { sp, a, b, _ -> RichText.setSize(sp, a, b, hp) }
fun docSetColor(s: DocSession, focus: DocFocus, rgb: Int?) = formatDoc(s, focus) { sp, a, b, _ -> RichText.setColor(sp, a, b, rgb) }
fun docSetHighlight(s: DocSession, focus: DocFocus, name: String?) = formatDoc(s, focus) { sp, a, b, _ -> RichText.setHighlight(sp, a, b, name) }
fun docClearFormat(s: DocSession, focus: DocFocus) = formatDoc(s, focus) { sp, a, b, _ -> RichText.clearFormat(sp, a, b) }
fun docToggle(s: DocSession, focus: DocFocus, attr: Attr) = toggle(s, focus, attr)

/** M3 d22: superscript / subscript on the selection ([v]); when all of it already is [v], back to the baseline. */
fun docVertAlign(s: DocSession, focus: DocFocus, v: String) = formatDoc(s, focus) { sp, a, b, _ ->
    RichText.setVertAlign(sp, a, b, if (RichText.common(sp, a, b) { it.vertAlign } == v) null else v)
}

/** M3 d21: Insert ▸ Horizontal line after the paragraph with the caret (else at the end); the caret goes into the new line's paragraph. */
fun docInsertRule(s: DocSession, focus: DocFocus): Int {
    val id = s.insertRule(insertPoint(s, focus))
    if (id >= 0) { focus.want = id; focus.wantCaret = 0 }
    return id
}

/** M3 d21: Insert ▸ Page break after the paragraph with the caret; the caret goes to the paragraph after the break. */
fun docInsertPageBreak(s: DocSession, focus: DocFocus): Int {
    val next = s.insertPageBreak(insertPoint(s, focus))
    if (next >= 0) { focus.want = next; focus.wantCaret = 0 }
    return next
}

private fun hasAttr(s: DocSession, focus: DocFocus, attr: Attr): Boolean {
    val p = focused(s, focus) ?: return false
    val sel = focus.selection
    val len = RichText.text(p.spans).length
    return if (sel.collapsed) len > 0 && RichText.hasAttr(p.spans, 0, len, attr) else RichText.hasAttr(p.spans, sel.min.coerceIn(0, len), sel.max.coerceIn(0, len), attr)
}

@Composable
fun Paper(s: DocSession, focus: DocFocus?, modifier: Modifier = Modifier, blocks: List<UiBlock>? = null, graph: Graph? = null, onWindowFocus: () -> Unit = {}) {
    s.version
    s.blockCount
    val all = blocks ?: s.blocks()
    val numbers = listNumbers(all)
    val list = androidx.compose.foundation.lazy.rememberLazyListState()
    // d25: a heading picked in the outline comes into view with the caret in it
    LaunchedEffect(s.scrollTick) {
        if (s.scrollTick == 0) return@LaunchedEffect
        val id = s.scrollTarget
        val i = all.indexOfFirst { it.id == id }
        if (i >= 0) { runCatching { list.scrollToItem(i) }; if (focus != null) { focus.want = id; focus.wantCaret = 0 } }
    }
    // d23: the selection across paragraphs (one paragraph: its own field's selection)
    val geo = remember(s) { ParaGeo() }
    val cross = remember(s.crossSel, s.version) { s.selectedParts().associate { it.first.id to (it.second to it.third) } }
    // M5 d29: where the open comments sit, by paragraph
    val marks = remember(s.version) { s.commentMarks() }
    // M5 d31: header / footer text of the file (the whole paper only, not the pieces of a spread)
    val hf = remember(s.version) { if (blocks == null) (s.headerText() ?: "") to (s.footerText() ?: "") else null }
    val hfTap = graph != null && focus != null && !s.readOnly
    val crossOn = focus != null && !s.readOnly && blocks == null
    val crossDrag = if (!crossOn) Modifier else Modifier.pointerInput(s, focus) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = androidx.compose.ui.input.pointer.PointerEventPass.Initial)
            val start = geo.hit(down.position) ?: return@awaitEachGesture
            var dragging = false
            val slop = viewConfiguration.touchSlop
            while (true) {
                val ev = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                if (!ch.pressed) {
                    if (dragging) { ch.consume(); android.util.Log.i(io.embodit.spatialoffice.platform.TAG, "DOCSEL drag ${s.crossSel ?: focus!!.dragSel}") }
                    else if (s.crossSel != null) s.crossSel = null // a click lets go of it
                    // M5 d24: with the format painter in hand, the text just tapped (its paragraph) or dragged over takes the format
                    if (s.painter != null) {
                        val done = if (!dragging) s.paint(start.first, 0, 0)
                        else s.crossSel?.let { s.paintParts() } ?: focus!!.dragSel?.let { d -> s.paint(d.first, d.second, d.third) } ?: false
                        if (done) graph?.say(s, graph.str(R.string.painter_done), graph.str(R.string.undo_short)) { s.undo() }
                    }
                    focus?.dragging = false
                    break
                }
                if (!dragging && (ch.position - down.position).getDistance() > slop) { dragging = true; focus?.dragging = true }
                if (!dragging) continue
                ch.consume()
                val cur = geo.hit(ch.position) ?: continue
                if (cur.first == start.first) {
                    if (s.crossSel != null) s.crossSel = null
                    focus!!.dragSel = Triple(start.first, minOf(start.second, cur.second), maxOf(start.second, cur.second)); focus.dragTick++
                } else {
                    val sel = io.embodit.spatialoffice.platform.DocSel(start.first, start.second, cur.first, cur.second)
                    if (sel != s.crossSel) s.crossSel = sel
                }
                // near the top / bottom edge the page scrolls on
                val edge = 48.dp.toPx()
                if (ch.position.y > size.height - edge) list.dispatchRawDelta(18f) else if (ch.position.y < edge) list.dispatchRawDelta(-18f)
            }
        }
    }
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
    androidx.compose.runtime.CompositionLocalProvider(LocalParaGeo provides (if (crossOn) geo else null)) {
    LazyColumn(
        modifier.clip(RoundedCornerShape(12.dp)).background(Ink.Paper)
            .then(if (crossOn) Modifier.onGloballyPositioned { geo.paper = it }.then(crossDrag) else Modifier)
            .padding(horizontal = 44.dp),
        state = list,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 36.dp),
    ) {
        if (all.isEmpty() && focus != null && !s.readOnly) item("empty") {
            Text(
                tr(R.string.doc_placeholder), style = paraStyle(ParaStyle.BODY).copy(color = Ink.Hint),
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).noKeyFocus().clickable { focus.want = s.insertAfter(-1, emptyList(), ParaStyle.BODY) },
            )
        }
        // M5 d31: the header at the top of the paper (the footer closes the list); grey, a tap opens the sheet
        if (hf != null && hf.first.isNotEmpty()) item("m5-header") { HeaderFooterLine(hf.first, "doc-header", if (hfTap) ({ graph!!.show(s, Overlay.HeaderFooter()) }) else null) }
        items(all, key = { it.id }) { b ->
            when (b) {
                is UiBlock.Para -> when {
                    b.pageBreak -> PageBreakView(s, b, focus != null && b.editable && !s.readOnly)
                    focus != null && b.editable && !s.readOnly -> ParaEditor(s, b, focus, all, numbers, graph, cross[b.id], marks[b.id])
                    else -> ParaView(b, numbers, s.findHit, graph, marks[b.id], s)
                }
                is UiBlock.Table -> TableView(b, if (focus != null && s.richText && !s.readOnly) s else null, s) { onWindowFocus() }
                is UiBlock.Image -> PictureView(s, b, focus != null && s.richText && !s.readOnly)
                is UiBlock.Page -> Image(bitmap = b.bitmap.asImageBitmap(), contentDescription = null, contentScale = ContentScale.FillWidth, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp).border(1.dp, Ink.Line))
                is UiBlock.Opaque -> OpaqueView(b)
            }
        }
        if (hf != null && hf.second.isNotEmpty()) item("m5-footer") { HeaderFooterLine(hf.second, "doc-footer", if (hfTap) ({ graph!!.show(s, Overlay.HeaderFooter()) }) else null) }
    }
    }
}

@Composable
private fun ParaView(b: UiBlock.Para, numbers: Map<Int, Int> = emptyMap(), hit: io.embodit.spatialoffice.platform.FindHit? = null, graph: Graph? = null, marks: List<io.embodit.spatialoffice.platform.CommentMark>? = null, s: DocSession? = null) {
    var layout by remember(b.id) { mutableStateOf<TextLayoutResult?>(null) }
    val links = graph != null && b.spans.any { it.link != null }
    Row(Modifier.fillMaxWidth().ruleLine(b.rule).revBar(b.spans.any { it.rev != null } || b.why == "tracked-changes").padding(paraPadding(b))) {
        Marker(b, numbers)
        Text(
            commentMarked(marked(annotatedOf(b, s?.trackAuthor ?: ""), hit, b.id), marks, s?.selComment), style = paraTextStyle(b), onTextLayout = { layout = it },
            // M3 d20: a click on a link opens it (read-only paragraphs; an editable one shows the link's [Open] under it)
            modifier = Modifier.weight(1f).then(if (links) Modifier.pointerInput(b.spans) {
                detectTapGestures { pos -> layout?.let { l -> RichText.linkAt(b.spans, l.getOffsetForPosition(pos))?.let { graph?.openLink(it.first) } } }
            } else Modifier),
        )
        if (b.why != null && b.why != "html") Txt(tr(R.string.ph_readonly), "doc/readonly", Modifier.padding(start = 8.dp), style = PicoTheme.typography.labelMedium, color = Ink.Hint)
    }
}

/** M3 d21: a page break: a line across the page with its name, and [Remove] where the document can be edited. */
@Composable
private fun PageBreakView(s: DocSession, b: UiBlock.Para, editable: Boolean) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(vertical = 8.dp).semantics { contentDescription = "pagebreak-${b.id}" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f).height(1.dp).background(Ink.Hint))
        Txt(tr(R.string.pagebreak_label), "doc/pagebreak", Modifier.padding(horizontal = 12.dp), style = PicoTheme.typography.labelLarge, color = Ink.TextSoft, maxLines = 2)
        Box(Modifier.weight(1f).height(1.dp).background(Ink.Hint))
        if (editable) io.embodit.spatialoffice.ui.SecondaryButton(tr(R.string.pagebreak_remove), "doc/pagebreak-remove", onClick = { s.delete(b.id) }, modifier = Modifier.padding(start = 12.dp))
    }
}

/** M3 d20: under a paragraph whose caret is in a link: the address, [Open] (system browser) and [Edit]. */
@Composable
private fun LinkChip(graph: Graph, s: DocSession, focus: DocFocus, link: Triple<String, Int, Int>) {
    Row(
        Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp).clip(RoundedCornerShape(10.dp)).background(Ink.Header).padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp),
    ) {
        Icon(painter = painterResource(R.drawable.ic_link), contentDescription = null, modifier = Modifier.size(20.dp), tint = Ink.TextSoft)
        Txt(link.first, "doc/link-url", Modifier.weight(1f), style = PicoTheme.typography.bodyMedium, color = Ink.Text, maxLines = 2)
        io.embodit.spatialoffice.ui.SecondaryButton(tr(R.string.link_open), "doc/link-open", onClick = { graph.openLink(link.first) })
        io.embodit.spatialoffice.ui.SecondaryButton(tr(R.string.link_edit), "doc/link-edit", onClick = { docLinkDialog(graph, s, focus) })
    }
}

fun annotatedOf(b: UiBlock.Para, me: String = ""): AnnotatedString = annotated(b.spans, b.baseSizeHp, paraStyle(b.style).fontSize.value, me)

/** M5 d29: the commented stretches of a paragraph get a yellow marker; the picked comment's a deeper one. */
fun commentMarked(text: AnnotatedString, marks: List<io.embodit.spatialoffice.platform.CommentMark>?, picked: String?): AnnotatedString {
    if (marks.isNullOrEmpty() || text.isEmpty()) return text
    return AnnotatedString.Builder(text).apply {
        // the picked one last, so it shows where two comments overlap
        for (m in marks.sortedBy { it.id == picked }) {
            // an empty range = the commented words were deleted and the comment kept: nothing to mark
            val a = m.start.coerceIn(0, text.length)
            val b = m.end.coerceIn(0, text.length)
            if (b > a) addStyle(SpanStyle(background = if (m.id == picked) io.embodit.spatialoffice.ui.ReviewLook.MarkFillOn else io.embodit.spatialoffice.ui.ReviewLook.MarkFill), a, b)
        }
    }.toAnnotatedString()
}

/** M5: a 3 dp line left of a paragraph that holds tracked changes. */
fun Modifier.revBar(on: Boolean): Modifier = if (!on) this else this.drawBehind {
    drawRect(io.embodit.spatialoffice.ui.ReviewLook.Ins, topLeft = androidx.compose.ui.geometry.Offset(-14.dp.toPx(), 2.dp.toPx()), size = androidx.compose.ui.geometry.Size(3.dp.toPx(), (size.height - 4.dp.toPx()).coerceAtLeast(0f)))
}

@Composable
private fun ParaEditor(s: DocSession, b: UiBlock.Para, focus: DocFocus, all: List<UiBlock>, numbers: Map<Int, Int>, graph: Graph? = null, cross: Pair<Int, Int>? = null, marks: List<io.embodit.spatialoffice.platform.CommentMark>? = null) {
    val requester = remember(b.id) { FocusRequester() }
    val me = s.trackAuthor
    var value by remember(b.id) { mutableStateOf(TextFieldValue(annotatedOf(b, me))) }
    // M5 d29: the session knows where the caret / selection is, so a comment written in the pane lands there
    if (focus.id == b.id) androidx.compose.runtime.SideEffect { s.noteCaret(b.id, focus.selection.min, focus.selection.max) }
    // model changed underneath (format toggle, undo, style): rebuild the styled text, keep the caret
    val text = RichText.text(b.spans)
    val styled = crossMarked(commentMarked(marked(annotatedOf(b, me), s.findHit, b.id), marks, s.selComment), cross)
    val geo = LocalParaGeo.current
    androidx.compose.runtime.DisposableEffect(b.id, geo) { onDispose { geo?.paras?.remove(b.id) } }
    // d23: a drag inside this paragraph (the paper takes drags so they can cross paragraphs) selects in it
    LaunchedEffect(focus.dragTick) {
        val d = focus.dragSel ?: return@LaunchedEffect
        if (d.first != b.id) return@LaunchedEffect
        runCatching { requester.requestFocus() }
        value = value.copy(selection = TextRange(d.second.coerceIn(0, text.length), d.third.coerceIn(0, text.length)))
        focus.selection = value.selection
    }
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
    val link = if (graph != null && focus.id == b.id) caretLink(b, focus.selection) else null
    Column(Modifier.fillMaxWidth()) {
    Row(Modifier.fillMaxWidth().ruleLine(b.rule).revBar(b.spans.any { it.rev != null }).padding(paraPadding(b))) {
        Marker(b, numbers)
        BasicTextField(
            value = value,
            onValueChange = { nv ->
                if (nv.text == value.text) {
                    value = nv; if (focus.id == b.id) focus.selection = nv.selection
                    // M5 d29: with the pane open, the caret going into a commented stretch picks that comment's card
                    if (s.reviewOpen && nv.selection.collapsed && nv.selection != focus.lastCaret) {
                        val at = nv.selection.start
                        val hit = marks?.firstOrNull { m -> m.end > m.start && at in m.start..m.end }
                        if (hit != null && hit.id != s.selComment) s.pickComment(hit.id)
                    }
                    focus.lastCaret = nv.selection
                    return@BasicTextField
                }
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
                    val plain = RichText.applyEdit(b.spans, nv.text)
                    // M5 d36: while "Track my edits" is on, what I delete stays on the paper struck through and what I type is marked as mine
                    val tracked = if (s.tracking) s.trackedEdit(b.spans, plain, value.selection.start, nv.selection.start) else null
                    val spans = tracked?.first ?: plain
                    s.setParagraph(b.id, spans, b.style)
                    val styledNow = annotated(spans, b.baseSizeHp, paraStyle(b.style).fontSize.value, me)
                    if (tracked != null && RichText.text(spans) != nv.text) {
                        // the paragraph kept text the field no longer has (a tracked deletion): the field takes the paragraph's text, the caret goes past the change
                        val c = tracked.second.coerceIn(0, styledNow.length)
                        value = TextFieldValue(styledNow, TextRange(c))
                        focus.selection = value.selection
                    } else {
                        // keep what the input method handed over (caret, composing range); only the styling is ours
                        value = nv.copy(annotatedString = styledNow)
                        focus.selection = nv.selection
                    }
                    if (s.findHit?.a == b.id) s.findHit = null
                }
            },
            textStyle = paraTextStyle(b),
            cursorBrush = SolidColor(Ink.Select),
            onTextLayout = { geo?.layout(b.id, it) },
            modifier = Modifier.weight(1f).heightIn(min = 32.dp).focusRequester(requester).typingTarget()
                .then(if (geo != null) Modifier.onGloballyPositioned { geo.coords(b.id, it) } else Modifier)
                .semantics { contentDescription = "paragraph-${b.id}" }
                .onFocusChanged {
                    if (it.isFocused) {
                        focus.id = b.id; s.caretBlock = b.id; focus.selection = value.selection; s.picked = -1
                        // M5 d24: the painter in hand and the caret arriving in another paragraph without a pointer press
                        // (a click through the accessibility layer, the keyboard): that paragraph takes the format
                        if (s.painter != null && !focus.dragging && s.painterFrom != b.id && s.paint(b.id, 0, 0)) graph?.say(s, graph.str(R.string.painter_done), graph.str(R.string.undo_short)) { s.undo() }
                    }
                }
                .then(if (s.painter != null) Modifier.probe("para-${b.id}") else Modifier)
                .onPreviewKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    val editable = all.filterIsInstance<UiBlock.Para>().filter { it.editable && !it.pageBreak }
                    val i = editable.indexOfFirst { it.id == b.id }
                    when {
                        // d17: Tab / Shift+Tab in a list item change its level (the caret stays where it is)
                        e.key == Key.Tab && (b.style == ParaStyle.BULLET || b.style == ParaStyle.NUMBERED) -> {
                            s.setListLevel(b.id, b.level + if (e.isShiftPressed) -1 else 1)
                            focus.want = b.id; focus.wantCaret = value.selection.start
                            true
                        }
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
    if (link != null && graph != null) LinkChip(graph, s, focus, link)
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
                    .then(if (editable) Modifier.noKeyFocus().clickable(interactionSource = remember(b.id) { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null) { s.picked = b.id } else Modifier)
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
            Row(Modifier.fillMaxWidth().height(androidx.compose.foundation.layout.IntrinsicSize.Min).background(if (r == 0) Ink.Header else Ink.Paper)) {
                var c = 0
                while (c < row.size) {
                    // M3 d42: a merged cell spans its columns; the rows it covers below show as one blank continuation
                    val m = b.merges.firstOrNull { it.covers(r, c) }
                    val span = if (m != null) m.cols - (c - m.col) else 1
                    val goesOn = m != null && r < m.row + m.rows - 1
                    val cellMod = Modifier.weight(span.toFloat()).fillMaxHeight().cellLines(bottom = !goesOn).heightIn(min = 48.dp).padding(horizontal = 10.dp, vertical = 12.dp)
                    if (m != null && m.row < r) {
                        Box(cellMod.semantics { contentDescription = "table-${b.id}-merged-$r-$c" })
                        c += span
                        continue
                    }
                    TableCell(b, edit, windowFocus, r, c, row[c], cellMod)
                    c += span
                }
            }
        }
    }
}

/** M3 d42: the right and (unless a merge goes on below) bottom edge of a table cell; the table's own border draws the outside. */
private fun Modifier.cellLines(bottom: Boolean): Modifier = this.drawBehind {
    val w = 1.dp.toPx()
    drawLine(Ink.Line, androidx.compose.ui.geometry.Offset(size.width - w / 2, 0f), androidx.compose.ui.geometry.Offset(size.width - w / 2, size.height), strokeWidth = w)
    if (bottom) drawLine(Ink.Line, androidx.compose.ui.geometry.Offset(0f, size.height - w / 2), androidx.compose.ui.geometry.Offset(size.width, size.height - w / 2), strokeWidth = w)
}

@Composable
private fun TableCell(b: UiBlock.Table, edit: DocSession?, windowFocus: () -> Unit, r: Int, c: Int, cell: String, cellMod: Modifier) {
                    val style = TextStyle(fontSize = 15.sp, lineHeight = 22.sp, color = Ink.Text, fontWeight = if (r == 0) FontWeight.SemiBold else null)
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
                            modifier = cellMod.focusRequester(requester).typingTarget()
                                .onFocusChanged {
                                    focused = it.isFocused
                                    if (it.isFocused) { edit.tableCaret = Triple(b.id, r, c); edit.caretBlock = b.id; edit.picked = b.id; text = text.copy(selection = TextRange(text.text.length)) }
                                }
                                .then(if (focused) Modifier.border(2.dp, Ink.Select, RoundedCornerShape(4.dp)) else Modifier)
                                .semantics { contentDescription = "table-${b.id}-cell-$r-$c" },
                        )
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
fun insertPoint(s: DocSession, focus: DocFocus): Int = focus.id.takeIf { id -> s.blocks().any { it.id == id } } ?: (s.blocks().lastOrNull()?.id ?: -1)

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
    LaunchedEffect(s.composeTick) { if (s.composing) { kotlinx.coroutines.delay(520); runCatching { keys.requestFocus() } } }
    Toolbar(
        supportingContent = { FileTools(graph, Win.DOC, s) },
        content = {
            if (!s.readOnly) Box(
                Modifier.focusRequester(keys).onPreviewKeyEvent { e -> toolbarKeys(graph, s, e) }.typingTarget().focusable(),
            ) { ToolRow {
                // q1-5: a short name on a fixed-width button (the menu keeps the long names), so [Save] never gets squeezed
                ToolMenu(styleShort(para?.style ?: ParaStyle.BODY), "doc/style", enabled = canEdit && s.styled, width = STYLE_BUTTON_W, entries = listOf(ParaStyle.BODY, ParaStyle.H1, ParaStyle.H2, ParaStyle.H3, ParaStyle.TITLE).map { st ->
                    MenuEntry(styleName(st), "doc/style-item", checked = para?.style == st) { restyle(st) }
                })
                ToolToggle(R.drawable.ic_text_bold, tr(R.string.bold), hasAttr(s, focus, Attr.BOLD), { toggle(s, focus, Attr.BOLD) }, enabled = canEdit && s.richText)
                ToolToggle(R.drawable.ic_text_italic, tr(R.string.italic), hasAttr(s, focus, Attr.ITALIC), { toggle(s, focus, Attr.ITALIC) }, enabled = canEdit && s.richText)
                ToolToggle(R.drawable.ic_text_underline, tr(R.string.underline), hasAttr(s, focus, Attr.UNDERLINE), { toggle(s, focus, Attr.UNDERLINE) }, enabled = canEdit && s.richText)
                // M2 d15 / d16 / d18: [Text ▾] sizes, strikethrough (moved from More), clear formatting; [Colour ▾] colours and highlights
                // one [Text ▾] (the document toolbar has no room for a second button: [Save] stays in view in English):
                // larger / smaller, then pages for the fixed sizes, colours and highlights
                ToolMenu(tr(R.string.menu_text), "doc/text", enabled = canEdit && s.richText, icon = if (s.painter != null) R.drawable.ic_format_paint else null, entries = sizeEntries("doc/text", null, { docStepSize(s, focus, it) }, {}).take(2) + listOf(
                    subMenu(tr(R.string.size_menu), "doc/size", sizeEntries("doc/text", docCommon(s, focus) { it.size ?: para?.baseSizeHp }, { docStepSize(s, focus, it) }, { docSetSize(s, focus, it) }).drop(2)),
                    subMenu(tr(R.string.menu_color), "doc/color", colorEntries("doc/color", docCommon(s, focus) { it.color }, { docSetColor(s, focus, it) })),
                    subMenu(tr(R.string.hl_menu), "doc/highlight", io.embodit.spatialoffice.ui.TextPalette.HIGHLIGHT.map { (name, v) ->
                        MenuEntry(tr(R.string.hl_color, tr(name)), "doc/highlight-item", checked = docCommon(s, focus) { it.highlight } == v) { docSetHighlight(s, focus, v) }
                    } + listOf(MenuEntry(tr(R.string.hl_none), "doc/highlight-none") { docSetHighlight(s, focus, null) })),
                    MenuEntry(tr(R.string.strike), "doc/strike", checked = hasAttr(s, focus, Attr.STRIKE)) { toggle(s, focus, Attr.STRIKE) },
                    // M3 d22: superscript / subscript (a second pick of the same one goes back to the baseline)
                    MenuEntry(tr(R.string.fmt_sup), "doc/sup", checked = docCommon(s, focus) { it.vertAlign } == "superscript") { docVertAlign(s, focus, "superscript") },
                    MenuEntry(tr(R.string.fmt_sub), "doc/sub", checked = docCommon(s, focus) { it.vertAlign } == "subscript") { docVertAlign(s, focus, "subscript") },
                    MenuEntry(tr(R.string.clear_format), "doc/clear-format") { docClearFormat(s, focus) },
                    // M5 d24: picks up the format at the caret; the next tap / drag over text puts it on (Ctrl+Shift+C)
                    MenuEntry(tr(R.string.painter_menu), "doc/painter", checked = s.painter != null) { docPickPainter(graph, s, focus) },
                ))
                ToolMenu(tr(R.string.menu_paragraph), "doc/paragraph", enabled = canEdit && s.styled, entries = listOfNotNull(
                    MenuEntry(tr(R.string.bullets), "doc/bullets", checked = para?.style == ParaStyle.BULLET) { restyle(if (para?.style == ParaStyle.BULLET) ParaStyle.BODY else ParaStyle.BULLET) },
                    MenuEntry(tr(R.string.numbering), "doc/numbering", checked = para?.style == ParaStyle.NUMBERED) { restyle(if (para?.style == ParaStyle.NUMBERED) ParaStyle.BODY else ParaStyle.NUMBERED) },
                ) + if (s.richText) listOf(
                    // d17 indent / list level, d19 line spacing and space around the paragraph
                    MenuEntry(tr(R.string.indent_more), "doc/indent-more") { if (para != null) { s.indent(para.id, true); focus.want = para.id } },
                    MenuEntry(tr(R.string.indent_less), "doc/indent-less") { if (para != null) { s.indent(para.id, false); focus.want = para.id } },
                    MenuEntry(tr(R.string.align_left), "doc/align-left", checked = para?.align == 0) { align(0) },
                    MenuEntry(tr(R.string.align_center), "doc/align-center", checked = para?.align == 1) { align(1) },
                    MenuEntry(tr(R.string.align_right), "doc/align-right", checked = para?.align == 2) { align(2) },
                    MenuEntry(tr(R.string.align_justify), "doc/align-justify", checked = para?.align == 3) { align(3) },
                ) + listOf(
                    io.embodit.spatialoffice.ui.subMenu(tr(R.string.line_spacing, LINE_SPACINGS.firstOrNull { it.second == (para?.lineSpacing ?: 240) }?.first ?: "…"), "doc/line", LINE_SPACINGS.map { (label, line) ->
                        MenuEntry(tr(R.string.line_spacing, label), "doc/line-$label", checked = (para?.lineSpacing ?: 240) == line) { if (para != null) { s.setLineSpacing(para.id, line); focus.want = para.id } }
                    }),
                    MenuEntry(tr(R.string.para_space), "doc/para-space", checked = para?.spaceBefore == 120 && para.spaceAfter == 120) {
                        if (para != null) { if (para.spaceBefore == 120 && para.spaceAfter == 120) s.setParaSpacing(para.id, null, null) else s.setParaSpacing(para.id, 120, 120); focus.want = para.id }
                    },
                ) else emptyList())
                // M3: link (d20), horizontal line and page break (d21); with the caret in a table, its rows & columns (d42)
                val inTable = s.caretCell()?.takeIf { it.first == s.caretBlock }
                ToolMenu(tr(R.string.menu_insert), "doc/insert", enabled = s.richText, entries = listOfNotNull(
                    if (inTable != null) subMenu(tr(R.string.tbl_menu), "doc/table-rc", listOf(
                        MenuEntry(tr(R.string.tbl_row_above), "doc/tbl-row-above") { docTableOp(graph, s, "row-above") },
                        MenuEntry(tr(R.string.tbl_row_below), "doc/tbl-row-below") { docTableOp(graph, s, "row-below") },
                        MenuEntry(tr(R.string.tbl_col_left), "doc/tbl-col-left") { docTableOp(graph, s, "col-left") },
                        MenuEntry(tr(R.string.tbl_col_right), "doc/tbl-col-right") { docTableOp(graph, s, "col-right") },
                        MenuEntry(tr(R.string.tbl_merge_right), "doc/tbl-merge-right") { docTableOp(graph, s, "merge-right") },
                        MenuEntry(tr(R.string.tbl_merge_down), "doc/tbl-merge-down") { docTableOp(graph, s, "merge-down") },
                        MenuEntry(tr(R.string.tbl_row_delete), "doc/tbl-row-delete") { docTableOp(graph, s, "row-delete") },
                        MenuEntry(tr(R.string.tbl_col_delete), "doc/tbl-col-delete") { docTableOp(graph, s, "col-delete") },
                    )) else null,
                    MenuEntry(tr(R.string.ins_link), "doc/link") { docLinkDialog(graph, s, focus) },
                    MenuEntry(tr(R.string.ins_picture), "doc/picture") {
                        val at = insertPoint(s, focus)
                        graph.pickImage(s) { b, e, w, h -> s.insertImage(at, b, e, w, h) }
                    },
                    MenuEntry(tr(R.string.ins_table), "doc/table") { graph.show(s, Overlay.InsertTable(Win.DOC)) },
                    MenuEntry(tr(R.string.ins_rule), "doc/rule") { docInsertRule(s, focus) },
                    MenuEntry(tr(R.string.ins_pagebreak), "doc/pagebreak") { docInsertPageBreak(s, focus) },
                    // M5 d31 header & footer; d29 a comment on the picked text / the caret's paragraph (Ctrl+Alt+M)
                    if (s.canReview && !s.readOnly) MenuEntry(tr(R.string.hf_menu), "doc/header-footer") { graph.show(s, Overlay.HeaderFooter()) } else null,
                    if (s.canReview && !s.readOnly) MenuEntry(tr(R.string.ins_comment), "doc/comment") { graph.startComment(s) } else null,
                ))
                ToolMenu(tr(R.string.more), "doc/more", entries = listOfNotNull(
                    // v0.6.0 (d25) the outline; (d23) copy / cut / paste with formats, the menu entries of Ctrl+C / X / V
                    MenuEntry(tr(R.string.outline), "doc/outline") { graph.show(s, Overlay.Outline(Win.DOC)) },
                    MenuEntry(tr(R.string.edit_copy), "doc/copy") { docCopy(graph, s, focus, cut = false, fromKey = false) },
                    MenuEntry(tr(R.string.edit_cut), "doc/cut") { docCopy(graph, s, focus, cut = true, fromKey = false) },
                    MenuEntry(tr(R.string.edit_paste), "doc/paste") { docPaste(graph, s, focus, fromKey = false) },
                    MenuEntry(tr(R.string.find_replace), "doc/find") { graph.show(s, Overlay.Find(Win.DOC)) },
                    MenuEntry(tr(R.string.word_count), "doc/wordcount") { graph.show(s, Overlay.WordCount(Win.DOC)) },
                    // M5 d36: text I add or delete is kept as a tracked change under my name (Ctrl+Shift+E)
                    if (s.hasRevisions && !s.readOnly) MenuEntry(tr(R.string.track_menu), "doc/track", checked = s.tracking) { docToggleTracking(graph, s) } else null,
                    // d28 version history
                    MenuEntry(tr(R.string.menu_history), "doc/history") { graph.show(s, Overlay.History(Win.DOC)) },
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
    // M5: Insert › Comment was just picked: what is typed goes to the comment being written
    if (io.embodit.spatialoffice.ui.composeKeys(graph, s, e)) return true
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

/** d19: the line spacings [Paragraph ▾] offers (label → w:line, 240 = single). */
val LINE_SPACINGS = listOf("1.0" to 240, "1.15" to 276, "1.5" to 360, "2.0" to 480)

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

/** M5 d24: [Text ▾ ▸ Format painter] / Ctrl+Shift+C: picks up the character format at the caret (a second pick puts it down). */
fun docPickPainter(graph: Graph, s: DocSession, focus: DocFocus): Boolean {
    if (s.painter != null) { s.dropPainter(); graph.say(s, graph.str(R.string.painter_dropped)); return true }
    val p = focused(s, focus)?.takeIf { it.editable && !it.pageBreak }
    if (p == null || !s.richText || s.readOnly) { graph.say(s, graph.str(R.string.painter_none)); return false }
    return s.pickPainter(p.id, focus.selection.min.coerceIn(0, RichText.text(p.spans).length))
}

/** M5 d36: [More ▸ Track my edits] / Ctrl+Shift+E / the switch in the review pane. */
fun docToggleTracking(graph: Graph, s: DocSession) {
    val on = !s.tracking
    if (graph.setTracking(s, on)) graph.say(s, graph.str(if (on) R.string.rv_track_on else R.string.rv_track_off))
}

/** M5 d24: the dark strip at the bottom of the content area while the format painter is in hand. */
@Composable
fun PainterChip(modifier: Modifier = Modifier) {
    Row(
        modifier.clip(RoundedCornerShape(24.dp)).background(androidx.compose.ui.graphics.Color(0xE6282828)).heightIn(min = 44.dp).padding(horizontal = 16.dp, vertical = 10.dp)
            .semantics { contentDescription = "painter-chip" },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
    ) {
        com.pico.spatial.ui.design.Icon(painter = androidx.compose.ui.res.painterResource(R.drawable.ic_format_paint), contentDescription = null, modifier = Modifier.size(20.dp), tint = androidx.compose.ui.graphics.Color(0xFFFFD28A))
        io.embodit.spatialoffice.ui.Txt(tr(R.string.painter_chip), "doc/painter-chip", style = PicoTheme.typography.labelLarge, color = androidx.compose.ui.graphics.Color.White, maxLines = 2)
    }
}

/** M5 d31: the header (top of the paper) / footer (bottom) as a grey line; a tap opens [Header & footer]. */
@Composable
private fun HeaderFooterLine(text: String, tag: String, onClick: (() -> Unit)?) {
    Text(
        text, style = paraStyle(ParaStyle.BODY).copy(color = Ink.Hint, fontSize = 13.sp), maxLines = 2,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).then(if (onClick != null) Modifier.noKeyFocus().clickable { onClick() } else Modifier)
            .padding(vertical = 12.dp).semantics { contentDescription = tag },
    )
}
