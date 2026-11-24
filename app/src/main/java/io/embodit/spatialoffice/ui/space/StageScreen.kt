package io.embodit.spatialoffice.ui.space

import androidx.compose.ui.draw.clipToBounds
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import io.embodit.spatialoffice.ui.noKeyFocus
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.focusRequester
import io.embodit.spatialoffice.platform.typingTarget
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import io.embodit.spatialoffice.ui.tr
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.foundation.content.SpatialView
import com.pico.spatial.ui.foundation.dsl.registerSystem
import com.pico.spatial.ui.foundation.dsl.unregisterSystem
import com.pico.spatial.ui.foundation.material.backgroundMaterial
import com.pico.spatial.ui.platform.Material
import io.embodit.spatialoffice.R
import io.embodit.spatialoffice.core.layout.Arrangement as Arr
import io.embodit.spatialoffice.platform.DeckSession
import io.embodit.spatialoffice.platform.DocSession
import io.embodit.spatialoffice.platform.Graph
import io.embodit.spatialoffice.platform.Head
import io.embodit.spatialoffice.platform.SheetSession
import io.embodit.spatialoffice.platform.StageMode
import io.embodit.spatialoffice.platform.Win
import io.embodit.spatialoffice.ui.Ink
import io.embodit.spatialoffice.ui.PrimaryButton
import io.embodit.spatialoffice.ui.SecondaryButton
import io.embodit.spatialoffice.ui.Segments
import io.embodit.spatialoffice.ui.ToolIcon
import io.embodit.spatialoffice.ui.Txt
import io.embodit.spatialoffice.ui.WindowFrame
import io.embodit.spatialoffice.ui.commonKeys
import io.embodit.spatialoffice.ui.doc.DocFocus
import io.embodit.spatialoffice.ui.doc.Paper
import io.embodit.spatialoffice.ui.localized
import io.embodit.spatialoffice.ui.sheet.CellEdit
import io.embodit.spatialoffice.ui.sheet.FormulaBar
import io.embodit.spatialoffice.ui.sheet.SheetGrid
import io.embodit.spatialoffice.ui.sheet.sheetKeys
import io.embodit.spatialoffice.ui.slides.SlideCanvas
import io.embodit.spatialoffice.core.layout.SlideFit
import io.embodit.spatialoffice.core.pptx.Chapter
import io.embodit.spatialoffice.core.pptx.Chapters
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.BoxWithConstraints
import kotlinx.coroutines.delay

/** S1 / S2: the Stage (Mixed). Spread layout of one file, or slide rehearsal. */
@Composable
fun StageScreen() {
    WindowFrame(Win.STAGE) { graph ->
        val scene = remember(graph) { StageScene(graph) }
        DisposableEffect(scene) {
            Head.start()
            registerSystem<StageFrameSystem>()
            onDispose {
                unregisterSystem<StageFrameSystem>()
                scene.detach()
                Head.stop()
                graph.stageGone()
            }
        }
        SpatialView(
            modifier = Modifier.fillMaxSize(),
            attachments = {
                for (slot in 0 until StagePanels.MAX_PIECES) {
                    val id = StagePanels.piece(slot)
                    AttachmentPanel(id) { Pooled(graph, scene, id) { PiecePanel(graph, scene.assigned[slot], scene.frozen[id]) } }
                }
                AttachmentPanel(StagePanels.BAR) { Pooled(graph, scene, StagePanels.BAR) { if (graph.stageMode == StageMode.REHEARSAL) RehearsalBar(graph) else LayoutBar(graph) } }
                AttachmentPanel(StagePanels.SCREEN) { Pooled(graph, scene, StagePanels.SCREEN) { RehearsalScreen(graph) } }
                AttachmentPanel(StagePanels.NOTES) { Pooled(graph, scene, StagePanels.NOTES) { RehearsalNotes(graph) } }
                AttachmentPanel(StagePanels.NEXT) { Pooled(graph, scene, StagePanels.NEXT) { RehearsalNext(graph) } }
            },
            update = { _, attachments -> scene.bind(attachments) },
            initial = { content, attachments -> scene.attach(content, attachments) },
        )
    }
}

/**
 * A pooled panel composes its content only while shown, in the app language and theme, and is recomposed shortly
 * after it appears (an idle emulator can keep a half-drawn first frame of an attachment panel).
 */
@Composable
private fun Pooled(graph: Graph, scene: StageScene, id: String, content: @Composable () -> Unit) {
    val visible = scene.shown[id] == true
    var gen by remember { mutableIntStateOf(0) }
    LaunchedEffect(visible) {
        if (!visible) return@LaunchedEffect
        for (w in longArrayOf(1_200L, 2_500L)) { delay(w); gen++ }
    }
    if (!visible) return
    val base = LocalContext.current
    val lang = graph.settings.value.lang
    val localized = remember(base, lang) { base.localized(lang) }
    CompositionLocalProvider(LocalContext provides localized, io.embodit.spatialoffice.ui.LocalRes provides localized.resources) {
        key(gen) { PicoTheme { io.embodit.spatialoffice.ui.SafeClickSound { Box(Modifier.onPreviewKeyEvent { e -> stageKeys(graph, e) }) { content() } } } }
    }
}

/** Keys that work on every Stage panel: change the piece in front, change arrangement, collapse / end. */
fun stageKeys(graph: Graph, e: KeyEvent): Boolean {
    if (e.type != KeyEventType.KeyDown) return false
    val rehearsal = graph.stageMode == StageMode.REHEARSAL
    if (e.key == Key.Escape) { graph.collapse(); return true }
    if (rehearsal) {
        // p28: a slide number then Enter goes there (PowerPoint's way); B or "." shows black / the slide again
        val digit = when (e.key) {
            Key.Zero, Key.NumPad0 -> 0; Key.One, Key.NumPad1 -> 1; Key.Two, Key.NumPad2 -> 2; Key.Three, Key.NumPad3 -> 3; Key.Four, Key.NumPad4 -> 4
            Key.Five, Key.NumPad5 -> 5; Key.Six, Key.NumPad6 -> 6; Key.Seven, Key.NumPad7 -> 7; Key.Eight, Key.NumPad8 -> 8; Key.Nine, Key.NumPad9 -> 9
            else -> -1
        }
        if (digit >= 0 && !e.isCtrlPressed && !e.isAltPressed) { graph.rehearsalTyped = (graph.rehearsalTyped + digit).takeLast(3); android.util.Log.i(io.embodit.spatialoffice.platform.TAG, "REHEARSAL typed ${graph.rehearsalTyped}"); return true }
        return when (e.key) {
            Key.Enter, Key.NumPadEnter -> { val n = graph.rehearsalTyped.toIntOrNull(); if (n != null) graph.rehearsalGo(n) else rehearsalStep(graph, 1); graph.rehearsalTyped = ""; true }
            Key.B, Key.Period -> { graph.toggleBlack(); true }
            Key.Backspace -> { graph.rehearsalTyped = graph.rehearsalTyped.dropLast(1); true }
            Key.DirectionRight, Key.Spacebar, Key.PageDown -> { rehearsalStep(graph, 1); true }
            Key.DirectionLeft, Key.PageUp -> { rehearsalStep(graph, -1); true }
            else -> false
        }
    }
    if (!(e.isCtrlPressed && e.isAltPressed)) return false
    when (e.key) {
        Key.DirectionLeft -> graph.focusBy(-1)
        Key.DirectionRight -> graph.focusBy(1)
        Key.One -> graph.arrange(Arr.ROW)
        Key.Two -> graph.arrange(Arr.ARC)
        Key.Three -> graph.arrange(Arr.LAYERS)
        Key.Four -> graph.arrange(Arr.CHAPTERS)
        Key.Five -> graph.arrange(Arr.TRIPLE)
        Key.Six -> graph.arrange(Arr.GRID)
        Key.Seven -> graph.arrange(Arr.CROWN)
        Key.Eight -> graph.arrange(Arr.MAIN)
        Key.PageUp -> graph.chapterBy(-1)
        Key.PageDown -> graph.chapterBy(1)
        else -> return false
    }
    return true
}

private fun rehearsalStep(graph: Graph, delta: Int) {
    val d = graph.stageSession as? DeckSession ?: return
    d.current = (d.current + delta).coerceIn(0, d.deck.slides.size - 1)
    graph.rehearsalTyped = ""
}

/**
 * One piece of the file. The focused piece is edited in place unless the spread is read-only ([Graph.readOnly]); any
 * other piece comes to the front when clicked. [frozen] = the panel is on its way out of the layout: it keeps showing
 * what it showed (true = its chapter's pile, false = the plain piece) and takes no clicks.
 */
@Composable
private fun PiecePanel(graph: Graph, index: Int?, frozen: Boolean? = null) {
    index ?: return
    val s = graph.stageSession ?: return
    s.version
    val inFront = index == graph.focus && frozen == null
    // read-only: the piece in front is looked at, never edited (a controller's ray clicks where one did not mean to)
    val focused = inFront && !graph.readOnly
    val (w, h) = StagePanels.pieceDp(graph.stageKind)
    val far = graph.arrangement == Arr.LAYERS && kotlin.math.abs(index - graph.focus) > 1
    // storyline by chapters: a slide of another chapter stands for that whole chapter (its pile)
    val chapters = if (graph.arrangement == Arr.CHAPTERS && s is DeckSession) s.chapters() else emptyList()
    val home = if (chapters.isEmpty()) -1 else Chapters.indexOf(chapters, index)
    val pile = frozen ?: (home >= 0 && home != Chapters.indexOf(chapters, graph.focus))
    if (pile && home >= 0) { PilePanel(graph, s as DeckSession, chapters[home], home, w, h); return }
    Box(
        Modifier.size(w.dp, h.dp).alpha(if (far) 0.72f else 1f).clip(RoundedCornerShape(20.dp)).background(Ink.Paper)
            .border(if (inFront) 4.dp else 1.dp, if (inFront) Ink.Select else Ink.Line, RoundedCornerShape(20.dp))
            .semantics { contentDescription = "piece-${index + 1}" },
    ) {
        when (s) {
            is DocSession -> {
                val focus = remember(s) { DocFocus() }
                val section = s.sections().getOrNull(index) ?: emptyList()
                Paper(s, if (focused) focus else null, Modifier.fillMaxSize().onPreviewKeyEvent { e -> focused && commonKeys(graph, s, e) }, blocks = section)
            }
            is SheetSession -> {
                val edit = remember(s) { CellEdit() }
                Column(Modifier.fillMaxSize().padding(12.dp).onPreviewKeyEvent { e -> focused && (commonKeys(graph, s, e) || sheetKeys(graph, s, edit, e)) }) {
                    if (focused) { FormulaBar(s, edit, Modifier.fillMaxWidth()); Box(Modifier.height(8.dp)) }
                    SheetGrid(s, if (focused) edit else null, index * SheetSession.COLS_PER_PIECE, SheetSession.COLS_PER_PIECE, Modifier.fillMaxWidth().weight(1f))
                }
            }
            is DeckSession -> Box(Modifier.fillMaxSize().onPreviewKeyEvent { e -> focused && commonKeys(graph, s, e) }, contentAlignment = Alignment.Center) {
                // any page proportion stays whole inside the piece (a 4:3 page at the full width was cut at the bottom)
                SlideCanvas(s, index, SlideFit.fit(w.toFloat(), h.toFloat(), s.deck.widthEmu, s.deck.heightEmu).w.dp, editable = focused)
            }
            else -> {}
        }
        // a piece that is not in front is one big target: click it and it turns to the front
        if (frozen != null) return@Box
        if (!inFront) Box(Modifier.fillMaxSize().dragToStep(graph, index) { graph.focusOn(index) }.noKeyFocus().clickable { graph.focusOn(index) }.semantics { contentDescription = "to-front-${index + 1}" })
        // read-only: a slide in front takes a sideways drag like the others and a click does nothing (documents and
        // sheets keep their own scrolling: they are drawn without a caret, so nothing in them can be changed)
        else if (graph.readOnly && s is DeckSession) Box(Modifier.fillMaxSize().dragToStep(graph, index) {}.noKeyFocus().clickable {}.semantics { contentDescription = "read-only-${index + 1}" })
    }
}

/**
 * A chapter that is not in front: its first slide with two sheets showing behind it, and a caption (name, slide count)
 * big enough to read at the pile's small size. Click it and the chapter comes to the front.
 */
@Composable
private fun PilePanel(graph: Graph, s: DeckSession, chapter: Chapter, number: Int, w: Int, h: Int) {
    val name = chapter.name.ifBlank { tr(R.string.chapter_n, number + 1) }
    val shape = RoundedCornerShape(20.dp)
    Box(Modifier.size(w.dp, h.dp)) {
        if (chapter.count > 2) Box(Modifier.align(Alignment.TopCenter).padding(horizontal = 44.dp).fillMaxWidth().height(60.dp).clip(shape).background(Ink.Header).border(1.dp, Ink.Line, shape))
        if (chapter.count > 1) Box(Modifier.align(Alignment.TopCenter).padding(top = 14.dp, start = 22.dp, end = 22.dp).fillMaxWidth().height(60.dp).clip(shape).background(Ink.Opaque).border(1.dp, Ink.Line, shape))
        Box(Modifier.padding(top = 28.dp).fillMaxSize().clip(shape).background(Ink.Paper).border(1.dp, Ink.Line, shape), contentAlignment = Alignment.Center) {
            SlideCanvas(s, chapter.first, SlideFit.fit(w.toFloat(), (h - 28).toFloat(), s.deck.widthEmu, s.deck.heightEmu).w.dp, editable = false)
            Row(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(96.dp).background(Ink.Text.copy(alpha = 0.86f)).padding(horizontal = 28.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                val big = PicoTheme.typography.headlineLarge.copy(fontSize = 40.sp, fontWeight = FontWeight.SemiBold)
                // the name is the file's own text: as much of it as fits; the slide count always shows
                com.pico.spatial.ui.design.Text(text = name, modifier = Modifier.weight(1f), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, style = big, color = Ink.Paper)
                Txt(tr(R.string.chapter_count, chapter.count), "stage/chapter-${number + 1}", style = big, color = Ink.Paper.copy(alpha = 0.82f))
            }
        }
        Box(Modifier.fillMaxSize().dragToStep(graph, chapter.first) { graph.focusOn(chapter.first) }.noKeyFocus().clickable { graph.focusOn(chapter.first) }.semantics { contentDescription = "chapter-${number + 1}" })
    }
}

@Composable
private fun Glass(width: Int, height: Int, content: @Composable () -> Unit) {
    Box(Modifier.size(width.dp, height.dp).clip(RoundedCornerShape(36.dp)).backgroundMaterial(enable = true, style = Material.Regular), contentAlignment = Alignment.Center) { content() }
}

/**
 * A press that moves sideways on a piece that is not in front pushes the row along like a hand of cards: to the left
 * brings the next piece to the front, to the right the one before; every [STEP_DP] of travel is one piece. A press
 * that hardly moves is still a click ([onTap]); a pinch is never perfectly still.
 */
private fun Modifier.dragToStep(graph: Graph, key: Int, onTap: () -> Unit): Modifier = pointerInput(key) {
    var travel = 0f
    var stepped = false
    val step = STEP_DP.dp.toPx()
    detectHorizontalDragGestures(
        onDragStart = { travel = 0f; stepped = false },
        onDragEnd = { if (!stepped) onTap() },
        onDragCancel = {},
        onHorizontalDrag = { change, dx ->
            change.consume()
            travel += dx
            while (travel <= -step) { travel += step; stepped = true; graph.focusBy(1); android.util.Log.i(io.embodit.spatialoffice.platform.TAG, "SPREAD drag -> next") }
            while (travel >= step) { travel -= step; stepped = true; graph.focusBy(-1); android.util.Log.i(io.embodit.spatialoffice.platform.TAG, "SPREAD drag -> previous") }
        },
    )
}

private const val STEP_DP = 120

/** The templates the bar offers for this kind of file (seven: B31). A deck has its storyline by chapters where the others have the row. */
internal fun templates(deck: Boolean): List<Arr> = listOf(if (deck) Arr.CHAPTERS else Arr.ROW, Arr.TRIPLE, Arr.GRID, Arr.CROWN, Arr.MAIN, Arr.ARC, Arr.LAYERS)

internal fun templateName(a: Arr): Int = when (a) {
    Arr.ROW -> R.string.arr_row; Arr.ARC -> R.string.arr_arc; Arr.LAYERS -> R.string.arr_layers; Arr.CHAPTERS -> R.string.arr_chapters
    Arr.TRIPLE -> R.string.arr_triple; Arr.GRID -> R.string.arr_grid; Arr.CROWN -> R.string.arr_crown; Arr.MAIN -> R.string.arr_main
}

/** A template drawn small: its panels as blocks, x / y / width / height in parts of the picture. */
internal fun templatePicture(a: Arr): List<FloatArray> = when (a) {
    Arr.ROW -> List(5) { floatArrayOf(0.02f + it * 0.194f, 0.2f, 0.184f, 0.6f) }
    Arr.ARC -> listOf(floatArrayOf(0f, 0.34f, 0.12f, 0.5f), floatArrayOf(0.15f, 0.24f, 0.16f, 0.56f), floatArrayOf(0.34f, 0.18f, 0.32f, 0.64f), floatArrayOf(0.69f, 0.24f, 0.16f, 0.56f), floatArrayOf(0.88f, 0.34f, 0.12f, 0.5f))
    Arr.LAYERS -> listOf(floatArrayOf(0.04f, 0f, 0.28f, 0.36f), floatArrayOf(0.36f, 0f, 0.28f, 0.36f), floatArrayOf(0.68f, 0f, 0.28f, 0.36f), floatArrayOf(0.2f, 0.46f, 0.6f, 0.54f))
    Arr.CHAPTERS -> listOf(floatArrayOf(0f, 0.36f, 0.13f, 0.34f), floatArrayOf(0.17f, 0.2f, 0.2f, 0.6f), floatArrayOf(0.4f, 0.2f, 0.2f, 0.6f), floatArrayOf(0.63f, 0.2f, 0.2f, 0.6f), floatArrayOf(0.87f, 0.36f, 0.13f, 0.34f))
    Arr.TRIPLE -> List(3) { floatArrayOf(it * 0.345f, 0.18f, 0.31f, 0.64f) }
    Arr.GRID -> List(4) { floatArrayOf(0.14f + (it % 2) * 0.38f, (it / 2) * 0.53f, 0.34f, 0.47f) }
    Arr.CROWN -> List(3) { floatArrayOf(it * 0.345f, 0.46f, 0.31f, 0.54f) } + listOf(floatArrayOf(0.2f, 0f, 0.26f, 0.38f), floatArrayOf(0.54f, 0f, 0.26f, 0.38f))
    Arr.MAIN -> listOf(floatArrayOf(0.22f, 0.08f, 0.56f, 0.84f), floatArrayOf(0f, 0.12f, 0.18f, 0.34f), floatArrayOf(0f, 0.54f, 0.18f, 0.34f), floatArrayOf(0.82f, 0.12f, 0.18f, 0.34f), floatArrayOf(0.82f, 0.54f, 0.18f, 0.34f))
}

@Composable
private fun TemplatePicture(a: Arr, color: androidx.compose.ui.graphics.Color, modifier: Modifier) {
    val blocks = remember(a) { templatePicture(a) }
    androidx.compose.foundation.Canvas(modifier) {
        val round = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx())
        for (b in blocks) drawRoundRect(color, androidx.compose.ui.geometry.Offset(b[0] * size.width, b[1] * size.height), androidx.compose.ui.geometry.Size(b[2] * size.width, b[3] * size.height), round)
    }
}

/** One template on the bar's picker: its picture and its name; the one in use is filled. */
@Composable
private fun TemplateTile(a: Arr, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val fg = if (selected) androidx.compose.ui.graphics.Color.White else PicoTheme.colorScheme.labelPrimary
    Column(
        modifier.height(88.dp).clip(RoundedCornerShape(20.dp)).background(if (selected) Ink.Select else PicoTheme.colorScheme.fillSecondary)
            .noKeyFocus().clickable(onClick = onClick).semantics { contentDescription = "stage/arr-${a.name.lowercase()}" }.padding(horizontal = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        TemplatePicture(a, fg, Modifier.size(52.dp, 30.dp))
        Box(Modifier.height(4.dp))
        Txt(tr(templateName(a)), "stage/arr-name", style = PicoTheme.typography.titleMedium, color = fg, align = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

/**
 * Layout bar under the pieces in front: the template in use (press it and the bar shows all seven to choose from),
 * previous / next piece, read-only (on: nothing in the spread can be edited), bring back to front, collapse.
 */
@Composable
private fun LayoutBar(graph: Graph) {
    val count = graph.stageSession?.pieceCount ?: 0
    val deck = graph.stageSession is DeckSession
    Glass(1000, 112) {
        if (graph.arrangePicking) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                // seven templates and nothing else: pressing one (the one in use too) goes back to the bar
                for (a in templates(deck)) TemplateTile(a, a == graph.arrangement, Modifier.weight(1f)) { graph.arrange(a) }
            }
            return@Glass
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(
                Modifier.height(64.dp).clip(RoundedCornerShape(32.dp)).background(PicoTheme.colorScheme.fillSecondary)
                    .noKeyFocus().clickable { graph.arrangePicking = true }.semantics { contentDescription = "stage/arr" }.padding(horizontal = 22.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                TemplatePicture(graph.arrangement, PicoTheme.colorScheme.labelPrimary, Modifier.size(44.dp, 26.dp))
                Txt(tr(templateName(graph.arrangement)), "stage/arr-now", style = PicoTheme.typography.titleMedium)
                com.pico.spatial.ui.design.Icon(painter = androidx.compose.ui.res.painterResource(R.drawable.ic_arrow_down), contentDescription = null, modifier = Modifier.size(18.dp), tint = PicoTheme.colorScheme.labelSecondary)
            }
            ToolIcon(R.drawable.ic_arrow_left, tr(R.string.focus_prev), onClick = { graph.focusBy(-1) }, enabled = graph.focus > 0)
            Txt(tr(R.string.piece_n, graph.focus + 1, count), "stage/piece-n", Modifier.width(72.dp), style = PicoTheme.typography.titleMedium, align = androidx.compose.ui.text.style.TextAlign.Center)
            ToolIcon(R.drawable.ic_arrow_right, tr(R.string.focus_next), onClick = { graph.focusBy(1) }, enabled = graph.focus < count - 1)
            Box(Modifier.weight(1f))
            // read-only on: filled, like a switch that is on. Six things to press on the bar (B31: seven at most).
            val locked = graph.readOnly
            Row(
                Modifier.height(64.dp).clip(RoundedCornerShape(32.dp)).background(if (locked) Ink.Select else PicoTheme.colorScheme.fillSecondary)
                    .noKeyFocus().clickable { graph.toggleReadOnly() }.semantics { contentDescription = if (locked) "stage/read-only-on" else "stage/read-only-off" }.padding(horizontal = 22.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                val fg = if (locked) androidx.compose.ui.graphics.Color.White else PicoTheme.colorScheme.labelPrimary
                com.pico.spatial.ui.design.Icon(painter = androidx.compose.ui.res.painterResource(R.drawable.ic_lock), contentDescription = null, modifier = Modifier.size(22.dp), tint = fg)
                Txt(tr(R.string.read_only), "stage/read-only", style = PicoTheme.typography.titleMedium, color = fg)
            }
            SecondaryButton(tr(R.string.recenter), "stage/recenter", icon = R.drawable.ic_rotate, onClick = { graph.recenter() })
            PrimaryButton(tr(R.string.collapse), "stage/collapse", icon = R.drawable.ic_collapse, onClick = { graph.collapse() })
        }
    }
}

@Composable
private fun elapsedLabel(startMs: Long): String {
    var now by remember { mutableLongStateOf(android.os.SystemClock.elapsedRealtime()) }
    LaunchedEffect(startMs) { while (true) { delay(500); now = android.os.SystemClock.elapsedRealtime() } }
    val sec = ((now - startMs) / 1000).coerceAtLeast(0)
    return String.format(java.util.Locale.ROOT, "%02d:%02d", sec / 60, sec % 60)
}

@Composable
private fun RehearsalBar(graph: Graph) {
    val d = graph.stageSession as? DeckSession ?: return
    d.version
    val n = d.deck.slides.size
    // p28: the bar takes the keys (number + Enter, B, arrows); it is a typing target, so Enter reaches it (KeyGuard) while no button can take focus
    val keys = remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(Unit) { repeat(4) { runCatching { keys.requestFocus() }; delay(400) } }
    Box(Modifier.focusRequester(keys).typingTarget().focusable()) {
    Glass(1120, 112) {
        if (graph.rehearsalPicking) {
            // p28 [Go to…]: every slide number on one row (scrolls sideways with many); a click goes there
            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                SecondaryButton(tr(R.string.rh_pick_back), "rehearsal/goto-back", icon = R.drawable.ic_arrow_left, onClick = { graph.rehearsalPicking = false })
                Txt(tr(R.string.rh_goto_label), "rehearsal/goto-label", style = PicoTheme.typography.titleMedium)
                Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    for (i in 1..n) Box(
                        Modifier.size(64.dp).clip(RoundedCornerShape(16.dp)).background(if (i == d.current + 1) Ink.Select else PicoTheme.colorScheme.fillSecondary)
                            .noKeyFocus().clickable { graph.rehearsalGo(i) }.semantics { contentDescription = "rehearsal/goto-$i" },
                        contentAlignment = Alignment.Center,
                    ) { Txt(i.toString(), "rehearsal/goto-n", style = PicoTheme.typography.titleLarge, weight = FontWeight.Bold, color = if (i == d.current + 1) androidx.compose.ui.graphics.Color.White else PicoTheme.colorScheme.labelPrimary) }
                }
            }
            return@Glass
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            ToolIcon(R.drawable.ic_arrow_left, tr(R.string.rh_prev), onClick = { rehearsalStep(graph, -1) }, enabled = d.current > 0)
            Txt(if (graph.rehearsalTyped.isNotEmpty()) tr(R.string.rh_typed, graph.rehearsalTyped) else tr(R.string.piece_n, d.current + 1, n), "rehearsal/page", Modifier.width(96.dp), style = PicoTheme.typography.titleLarge, weight = FontWeight.Bold, align = androidx.compose.ui.text.style.TextAlign.Center)
            ToolIcon(R.drawable.ic_arrow_right, tr(R.string.rh_next), onClick = { rehearsalStep(graph, 1) }, enabled = d.current < n - 1)
            // the clock and the black screen are a speaker's tools: not shown while watching
            if (!graph.watching) Txt(elapsedLabel(graph.rehearsalStart), "rehearsal/timer", Modifier.width(88.dp), style = PicoTheme.typography.titleLarge, align = androidx.compose.ui.text.style.TextAlign.Center)
            Box(Modifier.weight(1f))
            // p28: jump to a slide, black screen
            SecondaryButton(tr(R.string.rh_goto), "rehearsal/goto", onClick = { graph.rehearsalPicking = true })
            if (!graph.watching) SecondaryButton(tr(if (graph.rehearsalBlack) R.string.rh_unblack else R.string.rh_black), "rehearsal/black", onClick = { graph.toggleBlack() })
            SecondaryButton(tr(R.string.recenter), "rehearsal/recenter", icon = R.drawable.ic_rotate, onClick = { graph.recenter() })
            PrimaryButton(tr(if (graph.watching) R.string.end_watch else R.string.end_rehearsal), "rehearsal/end", onClick = { graph.collapse() })
        }
    }
    }
}

@Composable
private fun RehearsalScreen(graph: Graph) {
    val d = graph.stageSession as? DeckSession ?: return
    d.version
    // The screen panel is always 960 × 540 dp (the scene scales that to the screen's place); a page of another
    // proportion is drawn whole in its middle, so the panel keeps one size from slide to slide and file to file.
    Box(Modifier.size(960.dp, 540.dp), contentAlignment = Alignment.Center) {
        // p28: black screen (the speaker's notes and next slide stay). −1 = black.
        val showing = if (graph.rehearsalBlack) -1 else d.current
        LaunchedEffect(showing) { android.util.Log.i(io.embodit.spatialoffice.platform.TAG, "REHEARSAL screen shows ${if (showing < 0) "black" else "slide ${showing + 1}"}") }
        // Motion: one slide fades into the next; nothing moves on a 3.2 m screen in front of the eyes.
        // an opaque sheet the size of the page stays behind the two slides while one fades into the other: the room never shows through
        val fit = SlideFit.fit(960f, 540f, d.deck.widthEmu, d.deck.heightEmu)
        Box(Modifier.size(fit.w.dp, fit.h.dp).background(io.embodit.spatialoffice.ui.Ink.Paper), contentAlignment = Alignment.Center) {
            androidx.compose.animation.AnimatedContent(
                targetState = showing,
                transitionSpec = { StageMotion.slideChange(initialState, targetState) },
                modifier = Modifier.fillMaxSize().clipToBounds(), contentAlignment = Alignment.Center, label = "rehearsal-slide",
            ) { i ->
                if (i < 0) Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black).semantics { contentDescription = "rehearsal/blackout" })
                else SlideCanvas(d, i, fit.w.dp, editable = false)
            }
        }
    }
}

/** How the content of the rehearsal's panels changes (Compose side; where the panels are is StageScene's business). */
internal object StageMotion {
    /** Fast out of the way, slow into place. */
    val Ease = androidx.compose.animation.core.CubicBezierEasing(0.2f, 0f, 0f, 1f)
    const val CROSS_MS = 300
    const val FADE_MS = 240

    /** "axis" = one slide on (or back); "fade" = to / from black; "jump" = any other slide. All three fade; the name is for the log. */
    fun kind(from: Int, to: Int): String = when {
        from < 0 || to < 0 -> "fade"
        kotlin.math.abs(to - from) == 1 -> "axis"
        else -> "jump"
    }

    /**
     * One slide into another on the big screen: the new slide fades in over the old one, which stays whole until it
     * is covered. Nothing moves: slides of one deck share their background, and a background that slides an eighth of
     * the screen and back reads as the screen jolting (user on the headset, 2026-11-24).
     */
    fun slideChange(from: Int, to: Int): androidx.compose.animation.ContentTransform {
        val ms = if (kind(from, to) == "fade") FADE_MS else CROSS_MS
        return androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(ms, easing = androidx.compose.animation.core.LinearOutSlowInEasing)) togetherWith
            androidx.compose.animation.fadeOut(androidx.compose.animation.core.snap(delayMillis = ms))
    }

    /** Notes and the next-slide preview: a short fade, the new text rising a little. */
    fun textChange(): androidx.compose.animation.ContentTransform {
        val move = androidx.compose.animation.core.tween<androidx.compose.ui.unit.IntOffset>(FADE_MS, easing = Ease)
        return (androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(FADE_MS)) + androidx.compose.animation.slideInVertically(move) { h -> h / 24 }) togetherWith
            androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(FADE_MS / 2))
    }
}

@Composable
private fun RehearsalNotes(graph: Graph) {
    val d = graph.stageSession as? DeckSession ?: return
    d.version
    val notes = d.deck.slides.getOrNull(d.current)?.notes.orEmpty()
    Box(Modifier.size(568.dp, 704.dp).clip(RoundedCornerShape(28.dp)).backgroundMaterial(enable = true, style = Material.Regular)) {
        Column(Modifier.fillMaxSize().padding(28.dp).verticalScroll(rememberScrollState())) {
            Txt(tr(R.string.notes), "rehearsal/notes-label", style = PicoTheme.typography.labelLarge, color = PicoTheme.colorScheme.labelSecondary, maxLines = 2)
            Box(Modifier.height(12.dp))
            androidx.compose.animation.AnimatedContent(targetState = d.current to notes, transitionSpec = { StageMotion.textChange() }, label = "rehearsal-notes") { (_, text) ->
                Txt(text.ifBlank { tr(R.string.rh_notes_empty) }, "rehearsal/notes", style = PicoTheme.typography.headlineSmall, maxLines = 40)
            }
        }
    }
}

@Composable
private fun RehearsalNext(graph: Graph) {
    val d = graph.stageSession as? DeckSession ?: return
    d.version
    val last = d.current >= d.deck.slides.size - 1
    Box(Modifier.size(568.dp, 704.dp).clip(RoundedCornerShape(28.dp)).backgroundMaterial(enable = true, style = Material.Regular)) {
        Column(Modifier.fillMaxSize().padding(28.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Txt(elapsedLabel(graph.rehearsalStart), "rehearsal/clock", style = PicoTheme.typography.displaySmall, weight = FontWeight.Bold)
            Txt(tr(if (last) R.string.rh_last else R.string.rh_next_label), "rehearsal/next-label", style = PicoTheme.typography.labelLarge, color = PicoTheme.colorScheme.labelSecondary)
            if (!last) BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
                val w = SlideFit.fit(maxWidth.value, maxHeight.value, d.deck.widthEmu, d.deck.heightEmu).w.dp
                androidx.compose.animation.AnimatedContent(targetState = d.current + 1, transitionSpec = { StageMotion.textChange() }, label = "rehearsal-next") { i ->
                    SlideCanvas(d, i, w, editable = false)
                }
            }
        }
    }
}
