package io.embodit.spatialoffice.ui.space

import androidx.compose.foundation.background
import io.embodit.spatialoffice.ui.noKeyFocus
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
                    AttachmentPanel(id) { Pooled(graph, scene, id) { PiecePanel(graph, scene.assigned[slot]) } }
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
        else -> return false
    }
    return true
}

private fun rehearsalStep(graph: Graph, delta: Int) {
    val d = graph.stageSession as? DeckSession ?: return
    d.current = (d.current + delta).coerceIn(0, d.deck.slides.size - 1)
    graph.rehearsalTyped = ""
}

/** One piece of the file. The focused piece is edited in place; any other piece comes to the front when clicked. */
@Composable
private fun PiecePanel(graph: Graph, index: Int?) {
    index ?: return
    val s = graph.stageSession ?: return
    s.version
    val focused = index == graph.focus
    val (w, h) = StagePanels.pieceDp(graph.stageKind)
    val far = graph.arrangement == Arr.LAYERS && kotlin.math.abs(index - graph.focus) > 1
    Box(
        Modifier.size(w.dp, h.dp).alpha(if (far) 0.72f else 1f).clip(RoundedCornerShape(20.dp)).background(Ink.Paper)
            .border(if (focused) 4.dp else 1.dp, if (focused) Ink.Select else Ink.Line, RoundedCornerShape(20.dp))
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
        if (!focused) Box(Modifier.fillMaxSize().noKeyFocus().clickable { graph.focusOn(index) })
    }
}

@Composable
private fun Glass(width: Int, height: Int, content: @Composable () -> Unit) {
    Box(Modifier.size(width.dp, height.dp).clip(RoundedCornerShape(36.dp)).backgroundMaterial(enable = true, style = Material.Regular), contentAlignment = Alignment.Center) { content() }
}

/** Layout bar under the focused piece: arrangement, previous / next piece, bring back to front, collapse. */
@Composable
private fun LayoutBar(graph: Graph) {
    val count = graph.stageSession?.pieceCount ?: 0
    Glass(1000, 112) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Segments(
                listOf(Arr.ROW to tr(R.string.arr_row), Arr.ARC to tr(R.string.arr_arc), Arr.LAYERS to tr(R.string.arr_layers)),
                graph.arrangement, "stage/arr", itemWidth = 92.dp,
            ) { graph.arrange(it) }
            ToolIcon(R.drawable.ic_arrow_left, tr(R.string.focus_prev), onClick = { graph.focusBy(-1) }, enabled = graph.focus > 0)
            Txt(tr(R.string.piece_n, graph.focus + 1, count), "stage/piece-n", Modifier.width(72.dp), style = PicoTheme.typography.titleMedium, align = androidx.compose.ui.text.style.TextAlign.Center)
            ToolIcon(R.drawable.ic_arrow_right, tr(R.string.focus_next), onClick = { graph.focusBy(1) }, enabled = graph.focus < count - 1)
            Box(Modifier.weight(1f))
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
            Txt(elapsedLabel(graph.rehearsalStart), "rehearsal/timer", Modifier.width(88.dp), style = PicoTheme.typography.titleLarge, align = androidx.compose.ui.text.style.TextAlign.Center)
            Box(Modifier.weight(1f))
            // p28: jump to a slide, black screen
            SecondaryButton(tr(R.string.rh_goto), "rehearsal/goto", onClick = { graph.rehearsalPicking = true })
            SecondaryButton(tr(if (graph.rehearsalBlack) R.string.rh_unblack else R.string.rh_black), "rehearsal/black", onClick = { graph.toggleBlack() })
            SecondaryButton(tr(R.string.recenter), "rehearsal/recenter", icon = R.drawable.ic_rotate, onClick = { graph.recenter() })
            PrimaryButton(tr(R.string.end_rehearsal), "rehearsal/end", onClick = { graph.collapse() })
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
        // p28: black screen (the speaker's notes and next slide stay)
        if (graph.rehearsalBlack) Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black).semantics { contentDescription = "rehearsal/blackout" })
        else SlideCanvas(d, d.current, SlideFit.fit(960f, 540f, d.deck.widthEmu, d.deck.heightEmu).w.dp, editable = false)
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
            Txt(notes.ifBlank { tr(R.string.rh_notes_empty) }, "rehearsal/notes", style = PicoTheme.typography.headlineSmall, maxLines = 40)
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
                SlideCanvas(d, d.current + 1, SlideFit.fit(maxWidth.value, maxHeight.value, d.deck.widthEmu, d.deck.heightEmu).w.dp, editable = false)
            }
        }
    }
}
