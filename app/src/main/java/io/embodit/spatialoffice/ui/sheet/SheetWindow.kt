package io.embodit.spatialoffice.ui.sheet

import androidx.compose.foundation.ScrollState
import io.embodit.spatialoffice.platform.typingTarget
import io.embodit.spatialoffice.ui.noKeyFocus
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import io.embodit.spatialoffice.ui.tr
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Text
import com.pico.spatial.ui.design.menu.Menu
import com.pico.spatial.ui.design.menu.MenuItem
import com.pico.spatial.ui.design.menu.rememberMenuPositionProvider
import com.pico.spatial.ui.design.windows.Toolbar
import com.pico.spatial.ui.design.windows.popup.HorizontalPlacement
import com.pico.spatial.ui.design.windows.popup.VerticalPlacement
import io.embodit.spatialoffice.R
import io.embodit.spatialoffice.core.xlsx.A1
import io.embodit.spatialoffice.core.xlsx.CellKind
import io.embodit.spatialoffice.core.xlsx.NumFmt
import io.embodit.spatialoffice.core.xlsx.RangeStats
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import io.embodit.spatialoffice.platform.Graph
import io.embodit.spatialoffice.platform.SheetSession
import io.embodit.spatialoffice.platform.Win
import io.embodit.spatialoffice.ui.EditorTitleBar
import io.embodit.spatialoffice.ui.MenuEntry
import io.embodit.spatialoffice.ui.ToolMenu
import io.embodit.spatialoffice.platform.Overlay
import io.embodit.spatialoffice.ui.FileTools
import io.embodit.spatialoffice.ui.Ink
import io.embodit.spatialoffice.ui.NoFile
import io.embodit.spatialoffice.ui.Segments
import io.embodit.spatialoffice.ui.ToolButton
import io.embodit.spatialoffice.ui.ToolDivider
import io.embodit.spatialoffice.ui.ToolIcon
import io.embodit.spatialoffice.ui.ToolRow
import io.embodit.spatialoffice.ui.ToolToggle
import io.embodit.spatialoffice.ui.Txt
import io.embodit.spatialoffice.ui.UndoRedo
import io.embodit.spatialoffice.ui.colorEntries
import io.embodit.spatialoffice.ui.sizeEntries
import io.embodit.spatialoffice.ui.WindowFrame
import io.embodit.spatialoffice.ui.commonKeys

/** Text being typed for the selected cell. One instance per sheet surface (window, or the focused block when spread out). */
class CellEdit {
    var value by mutableStateOf(TextFieldValue(""))
    var editing by mutableStateOf(false)
    val focus = FocusRequester()
    /**
     * v0.5.1 (QA #13 q13-1): bumped every time typed text is committed. The formula bar's text field is keyed on it,
     * so the input method gets a fresh connection for the next cell. Before, the keyboard still held the committed
     * word as its composing text and wrote it into the next cell ("abc⏎ de⏎" saved Abc / Abcde). Text that arrives
     * for an older generation ([accept]) is dropped.
     */
    var generation by mutableStateOf(0)
        private set

    fun load(s: SheetSession) {
        val t = s.book.cell(s.sheet, s.row, s.col).input
        // the whole value is selected: typing replaces it (as in every spreadsheet); F2 or a click in the bar edits it
        value = TextFieldValue(t, TextRange(0, t.length)); editing = false
    }

    /** Writes the typed text into the cell; the buffer is emptied and the input connection renewed. True when there was typed text. */
    fun commit(s: SheetSession): Boolean {
        val typed = editing
        if (typed) {
            val t = value.text.replace("\n", "")
            s.setCell(s.row, s.col, t)
            value = TextFieldValue("")
            generation++
            lastCommitted = t; committedAt = clock()
        }
        editing = false
        return typed
    }

    /** What the last commit wrote and when (ms); a keyboard re-sending that word right after is not typing. */
    private var lastCommitted = ""
    private var committedAt = Long.MIN_VALUE / 2
    var clock: () -> Long = { android.os.SystemClock.uptimeMillis() }

    /**
     * The new cell's first text: when, within [ECHO_MS] of a commit, it starts with the whole committed word (an input
     * method finishing its old composing text into the new connection), that word is taken off again.
     */
    private fun dropEcho(nv: TextFieldValue): TextFieldValue {
        if (editing || nv.text == value.text || lastCommitted.length < 2 || clock() - committedAt > ECHO_MS) return nv
        if (!nv.text.startsWith(lastCommitted)) return nv
        val rest = nv.text.substring(lastCommitted.length)
        android.util.Log.i("SpatialOffice", "CELLEDIT dropped echo of the last cell (${lastCommitted.length} chars)")
        lastCommitted = ""
        return TextFieldValue(rest, TextRange(rest.length))
    }

    /** Enter / Tab / arrows: commit, move the selection by (dr, dc), show the new cell's value. */
    fun advance(s: SheetSession, dr: Int, dc: Int) {
        commit(s); s.select(s.row + dr, s.col + dc); load(s)
    }

    /** Text from the field of generation [gen]; false (dropped) when that field belongs to a cell already committed. */
    fun accept(gen: Int, nv0: TextFieldValue): Boolean {
        if (gen != generation) return false
        // v0.7.0: the bar just took the keyboard back (a reply box or dialog closed) and the input method, finishing what it
        // was doing there, replaces the selected value with nothing. Nobody typed that: the cell keeps its value.
        if (!editing && nv0.text.isEmpty() && value.text.isNotEmpty() && clock() - focusedAt < REFOCUS_MS) {
            android.util.Log.i(io.embodit.spatialoffice.platform.TAG, "CELLEDIT dropped an empty text ${clock() - focusedAt} ms after the bar took focus (value kept)")
            return false
        }
        val nv = dropEcho(nv0)
        if (nv.text != value.text) editing = true
        value = nv
        return true
    }

    /** When the formula bar last took the keyboard (ms, [clock]). */
    var focusedAt = 0L
    fun focused() { focusedAt = clock() }

    companion object { const val ECHO_MS = 800L; const val REFOCUS_MS = 700L }
}

/** v0.5.1 (q13-1): cell input: no automatic capital letter, no suggestions (no composing word carried between cells). */
val CellKeyboard = KeyboardOptions(
    capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.None,
    autoCorrect = false,
    keyboardType = androidx.compose.ui.text.input.KeyboardType.Text,
    imeAction = ImeAction.Done,
)

private val ROW_H = 36.dp
private val HEAD_W = 52.dp

/** s20: the file's column width (characters) on screen; wide enough to widen and narrow visibly (v0.2.7 clamped 76–240 dp). */
fun colWidth(s: SheetSession, c: Int): Dp = (s.book.colWidthChars(s.sheet, c) * 9f + 14f).coerceIn(40f, 720f).dp

/** s20: the file's row height (points; 15 pt = the 36 dp row v0.2.7 always drew). */
fun rowHeight(s: SheetSession, r: Int): Dp = if (s.isCsv) ROW_H else (s.book.rowHeightPt(s.sheet, r) * 2.4f).coerceIn(24f, 480f).dp

/** Rectangle (r0, c0, r1, c1) of the first chart's data on the current sheet, if it has one. */
fun chartRange(s: SheetSession): IntArray? {
    val range = s.book.charts().firstOrNull { it.sheet == s.sheet }?.range ?: return null
    val parts = range.replace("$", "").split(':')
    val a = A1.parse(parts[0]) ?: return null
    val b = A1.parse(parts.getOrElse(1) { parts[0] }) ?: return null
    return intArrayOf(minOf(a.first, b.first), minOf(a.second, b.second), maxOf(a.first, b.first), maxOf(a.second, b.second))
}

/** W2 spreadsheet (Planar 1280 × 800 dp): title bar, formula bar, grid, sheet picker, toolbar. */
@Composable
fun SheetWindow(tag: String? = null) {
    io.embodit.spatialoffice.ui.EditorWindow(Win.SHEET, tag) { graph, session ->
        val s = session as? SheetSession ?: return@EditorWindow
        val edit = remember(s) { CellEdit() }
        Column(Modifier.fillMaxSize().onPreviewKeyEvent { e -> commonKeys(graph, s, e) || sheetKeys(graph, s, edit, e) }) {
            EditorTitleBar(graph, Win.SHEET, s)
            // M5: the review pane inside the window, right of the grid (the grid area goes from 1216 to 880 dp while it is open)
            val pane = s.reviewOpen && s.canReview
            Row(Modifier.fillMaxWidth().weight(1f).padding(start = if (pane) 24.dp else 32.dp, end = if (pane) 24.dp else 32.dp, bottom = 52.dp)) {
            Column(Modifier.weight(1f).fillMaxHeight()) {
                FormulaBar(s, edit, Modifier.fillMaxWidth())
                Box(Modifier.height(10.dp))
                // v0.5.0 (s41): the grid at the window's zoom (frozen rows, headers and hit-testing scale with it)
                Box(Modifier.fillMaxWidth().weight(1f)) {
                    io.embodit.spatialoffice.ui.Zoomed(s) { SheetGrid(s, edit, 0, null, Modifier.fillMaxSize()) }
                    // v0.6.0 (s28): function names / arguments while a formula is typed, over the top of the grid
                    FormulaHint(edit, Modifier.align(if (s.row < 8) Alignment.BottomStart else Alignment.TopStart).padding(start = 94.dp, top = 48.dp, bottom = 12.dp))
                }
                Box(Modifier.height(10.dp))
                SheetPicker(graph, s, edit)
            }
            if (pane) {
                Box(Modifier.width(16.dp))
                io.embodit.spatialoffice.ui.ReviewPane(graph, s)
            }
            }
        }
        SheetToolbar(graph, s, edit)
    }
}

/**
 * Keys of the grid: arrows / Tab / Enter move, Shift+arrows extend the range, Ctrl+arrows jump to the edge of the data
 * (Shift too = extend), Ctrl+A all, Ctrl+C / X / V copy / cut / paste cells, Ctrl+F find, Enter commits, Esc cancels,
 * F2 edits, Alt+= sums. Every one of them also has a menu entry (rays, gaze + pinch and the emulator have no keyboard).
 */
fun sheetKeys(graph: Graph, s: SheetSession, edit: CellEdit, e: KeyEvent): Boolean {
    if (e.type != KeyEventType.KeyDown) return false
    fun move(dr: Int, dc: Int) = edit.advance(s, dr, dc)
    fun extend(dr: Int, dc: Int) { edit.commit(s); s.extendTo(s.row + dr, s.col + dc); edit.load(s) }
    val arrow = when (e.key) { Key.DirectionUp -> -1 to 0; Key.DirectionDown -> 1 to 0; Key.DirectionLeft -> 0 to -1; Key.DirectionRight -> 0 to 1; else -> null }
    return when {
        // v0.6.0 (s28): Tab takes the first function offered while a formula name is typed
        e.key == Key.Tab && !e.isShiftPressed && edit.editing && completeFunction(edit) -> true
        e.isAltPressed && e.key == Key.Equals -> { edit.commit(s); if (s.autoSum() == null) graph.say(s, graph.str(R.string.no_num)); edit.load(s); true }
        e.isCtrlPressed && e.key == Key.B -> { s.setBold(!s.book.cell(s.sheet, s.row, s.col).bold); true }
        e.isCtrlPressed && e.key == Key.I -> { s.setItalic(!s.book.cell(s.sheet, s.row, s.col).italic); true }
        e.isCtrlPressed && e.key == Key.U -> { s.setUnderline(!s.book.cell(s.sheet, s.row, s.col).underline); true }
        e.isCtrlPressed && (e.key == Key.F || e.key == Key.H) -> { edit.commit(s); graph.show(s, Overlay.Find(Win.SHEET)); true }
        e.isCtrlPressed && !edit.editing && e.key == Key.C -> { copyCells(graph, s, cut = false); true }
        e.isCtrlPressed && !edit.editing && e.key == Key.X -> { copyCells(graph, s, cut = true); true }
        e.isCtrlPressed && !edit.editing && e.key == Key.V -> { pasteCells(graph, s); edit.load(s); true }
        e.isCtrlPressed && !edit.editing && e.key == Key.A -> { s.selectAll(); edit.load(s); true }
        // v0.5.0 (s24): Ctrl+D fill down, Ctrl+R fill right ([Edit ▾ ▸ Fill ›] has both)
        e.isCtrlPressed && !edit.editing && (e.key == Key.D || e.key == Key.R) -> { fillCells(graph, s, e.key == Key.D); edit.load(s); true }
        e.isCtrlPressed && !edit.editing && arrow != null -> { edit.commit(s); s.jump(arrow.first, arrow.second, e.isShiftPressed); edit.load(s); true }
        e.isCtrlPressed || e.isAltPressed -> false
        e.key == Key.Enter || e.key == Key.NumPadEnter -> { move(if (e.isShiftPressed) -1 else 1, 0); true }
        e.key == Key.Tab -> { move(0, if (e.isShiftPressed) -1 else 1); true }
        e.key == Key.Escape -> { if (edit.editing) { edit.load(s); true } else false }
        e.key == Key.F2 -> { edit.editing = true; edit.value = edit.value.copy(selection = TextRange(edit.value.text.length)); true }
        e.key == Key.Delete && !edit.editing -> { if (s.isRange) s.clearSelection() else s.setCell(s.row, s.col, ""); edit.load(s); true }
        edit.editing -> false // arrows move the caret while typing
        e.isShiftPressed && arrow != null -> { extend(arrow.first, arrow.second); true }
        e.key == Key.DirectionUp -> { move(-1, 0); true }
        e.key == Key.DirectionDown -> { move(1, 0); true }
        e.key == Key.DirectionLeft -> { move(0, -1); true }
        e.key == Key.DirectionRight -> { move(0, 1); true }
        else -> false
    }
}

/** s28: the name being typed replaced by [name] (default: the first one offered) and "(": `=SU` → `=SUM(`. False when nothing is offered. */
fun completeFunction(edit: CellEdit, name: String? = null): Boolean {
    val v = edit.value
    val pick = name ?: io.embodit.spatialoffice.core.xlsx.FormulaHints.suggest(v.text, v.selection.start).firstOrNull() ?: return false
    val (text, caret) = io.embodit.spatialoffice.core.xlsx.FormulaHints.complete(v.text, v.selection.start, pick) ?: return false
    edit.editing = true
    edit.value = TextFieldValue(text, TextRange(caret))
    android.util.Log.i(io.embodit.spatialoffice.platform.TAG, "FNHINT complete $pick -> $text")
    runCatching { edit.focus.requestFocus() }
    return true
}

/**
 * s28: while a formula is typed, the functions whose name starts with what is typed (click one, or Tab for the first),
 * then, inside a function's brackets, its arguments with the one being typed in bold.
 */
@Composable
fun FormulaHint(edit: CellEdit, modifier: Modifier = Modifier) {
    val v = edit.value
    if (!edit.editing || !v.text.startsWith("=")) return
    val caret = v.selection.start
    val names = io.embodit.spatialoffice.core.xlsx.FormulaHints.suggest(v.text, caret)
    val arg = if (names.isEmpty()) io.embodit.spatialoffice.core.xlsx.FormulaHints.argHint(v.text, caret) else null
    val line = names.joinToString(",") + "|" + arg
    LaunchedEffect(line) { if (names.isNotEmpty() || arg != null) android.util.Log.i(io.embodit.spatialoffice.platform.TAG, "FNHINT '${v.text}' names=$names arg=$arg") }
    if (names.isEmpty() && arg == null) return
    Column(
        modifier.widthIn(max = 760.dp).clip(RoundedCornerShape(14.dp)).background(Ink.Paper).border(1.5.dp, Ink.Select, RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp).semantics { contentDescription = "formula-hint" },
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
    ) {
        if (names.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(16.dp)) {
                names.forEachIndexed { i, n ->
                    Box(
                        Modifier.height(48.dp).clip(RoundedCornerShape(10.dp)).background(if (i == 0) Ink.SelectFill else Ink.Header)
                            .noKeyFocus().clickable { completeFunction(edit, n) }.padding(horizontal = 12.dp).semantics { contentDescription = "fn-$n" },
                        contentAlignment = Alignment.Center,
                    ) { Text(n, style = TextStyle(fontSize = 16.sp, fontWeight = if (i == 0) FontWeight.Bold else null, color = Ink.Text)) }
                }
                Txt(tr(R.string.fn_tab), "sheet/fn-tab", style = PicoTheme.typography.labelMedium, color = Ink.TextSoft)
            }
            Text(io.embodit.spatialoffice.core.xlsx.FormulaHints.signature(names[0]), style = TextStyle(fontSize = 16.sp, color = Ink.TextSoft))
        } else if (arg != null) {
            val parts = io.embodit.spatialoffice.core.xlsx.FormulaHints.argParts(arg.first)
            val at = if (parts.isEmpty()) -1 else minOf(arg.second, parts.lastIndex).let { if (parts[it] == "…" && it > 0) it - 1 else it }
            Text(
                androidx.compose.ui.text.buildAnnotatedString {
                    append(arg.first + "(")
                    parts.forEachIndexed { i, p ->
                        if (i > 0) append(", ")
                        if (i == at) withStyle(androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.Bold, color = Ink.Select)) { append(p) } else append(p)
                    }
                    append(")")
                },
                style = TextStyle(fontSize = 17.sp, color = Ink.Text),
                modifier = Modifier.semantics { contentDescription = "fn-args" },
            )
        }
    }
}

@Composable
fun FormulaBar(s: SheetSession, edit: CellEdit, modifier: Modifier = Modifier) {
    LaunchedEffect(s.sheet, s.row, s.col, s.version) { if (!edit.editing) edit.load(s) }
    // the keyboard belongs to the selected cell: take focus when the surface appears (the window may not be ready at once)
    LaunchedEffect(s) { repeat(3) { runCatching { edit.focus.requestFocus() }; kotlinx.coroutines.delay(400) } }
    Row(modifier.height(52.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(84.dp).height(52.dp).clip(RoundedCornerShape(12.dp)).background(Ink.Header), contentAlignment = Alignment.Center) {
            Text(A1.name(s.row, s.col), style = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Ink.Text))
        }
        Box(Modifier.width(10.dp))
        Box(Modifier.weight(1f).height(52.dp).clip(RoundedCornerShape(12.dp)).background(Ink.Paper).border(1.5.dp, if (edit.editing) Ink.Select else Ink.Line, RoundedCornerShape(12.dp)).padding(horizontal = 14.dp), contentAlignment = Alignment.CenterStart) {
            if (edit.value.text.isEmpty()) Text(tr(R.string.formula_ph), style = TextStyle(fontSize = 17.sp, color = Ink.Hint))
            // v0.5.1 (q13-1): a new field (and input connection) after every commit; no auto capital, no suggestions
            val gen = edit.generation
            androidx.compose.runtime.key(gen) {
                BasicTextField(
                    value = edit.value,
                    onValueChange = { nv ->
                        if (!edit.accept(gen, nv)) return@BasicTextField // the keyboard finishing a word of the last cell
                        if (nv.text.contains('\n')) edit.advance(s, 1, 0) // Enter arriving as text
                    },
                    singleLine = true,
                    keyboardOptions = CellKeyboard,
                    // Enter from a keyboard that the system routes through the input method
                    keyboardActions = KeyboardActions(onDone = { if (gen == edit.generation) edit.advance(s, 1, 0) }),
                    textStyle = TextStyle(fontSize = 17.sp, color = Ink.Text),
                    cursorBrush = SolidColor(Ink.Select),
                    modifier = Modifier.fillMaxWidth().focusRequester(edit.focus).typingTarget().onFocusChanged { if (it.isFocused) edit.focused() }.semantics { contentDescription = "formula-input" },
                )
            }
            // the new field takes the keyboard back at once
            LaunchedEffect(gen) { if (gen > 0) { kotlinx.coroutines.delay(30); runCatching { edit.focus.requestFocus() } } }
        }
    }
}

/**
 * The grid. [firstCol] / [colCount] limit it to a block of columns (a spread-out panel); null = all columns with
 * horizontal scrolling. Clicking a cell selects it and returns the keyboard to the formula bar.
 * v0.3.0 (s17): a press-and-drag across cells selects a range (blue box + light blue fill); a click on a row number or a
 * column letter selects the row / column. Dragging on the row numbers scrolls (the cells are for selecting).
 */
@Composable
fun SheetGrid(
    s: SheetSession, edit: CellEdit?, firstCol: Int, colCount: Int?, modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(), hScroll: ScrollState = rememberScrollState(),
) {
    s.version
    val rows = maxOf(s.rowCount + 12, 24)
    val hiddenCols = remember(s.version, s.sheet) { s.hiddenCols() }
    // v0.6.0 (s30): frozen left columns stay put while the rest scrolls sideways (the full grid only, not a spread-out panel)
    val fc = if (colCount == null) s.frozenCols else 0
    val lastCol = if (colCount != null) firstCol + colCount else maxOf(s.colCount + 4, s.col + 2, s.anchorCol + 2, 14, fc + 6)
    // s42: hidden columns are left out
    val fixedCols = (0 until fc).filter { it !in hiddenCols }
    val cols = (maxOf(firstCol, fc) until lastCol).filter { it !in hiddenCols }.ifEmpty { listOf(lastCol) }
    val range = remember(s.version, s.sheet) { runCatching { chartRange(s) }.getOrNull() }
    val scroll = if (colCount == null) Modifier.horizontalScroll(hScroll) else Modifier
    val frozen = s.frozen.coerceAtMost(rows)
    val shown = s.visibleRows(maxOf(rows, s.row + 2, s.anchorRow + 2)).filter { it >= frozen }
    val sel = s.selection
    // the active cell stays in view when the keyboard / a find / a paste moves it
    LaunchedEffect(s.row, s.col, s.sheet, s.findTick) {
        val i = shown.indexOf(s.row)
        if (i >= 0 && listState.layoutInfo.visibleItemsInfo.none { it.key == s.row }) runCatching { listState.scrollToItem((i - 3).coerceAtLeast(0)) }
    }
    val density = androidx.compose.ui.platform.LocalDensity.current
    val headH = with(density) { ROW_H.toPx() }
    val headW = with(density) { HEAD_W.toPx() }
    val colPx = cols.map { with(density) { colWidth(s, it).toPx() } }
    val fixedPx = fixedCols.map { with(density) { colWidth(s, it).toPx() } }
    val fixedW = fixedPx.sum()
    val frozenPx = (0 until frozen).sumOf { with(density) { rowHeight(s, it).toPx() }.toDouble() }.toFloat() + if (frozen > 0) with(density) { 2.dp.toPx() } else 0f
    val frozenRowPx = (0 until frozen).map { with(density) { rowHeight(s, it).toPx() } }

    /** The cell under a point of the grid (null over the headers or outside the cells). */
    fun cellAt(p: androidx.compose.ui.geometry.Offset): Pair<Int, Int>? {
        if (p.x < headW || p.y < headH) return null
        var c = -1
        if (p.x < headW + fixedW) {
            var fx = p.x - headW
            for ((i, w) in fixedPx.withIndex()) { if (fx < w) { c = fixedCols[i]; break }; fx -= w }
        }
        if (c < 0) {
            var x = p.x - headW - fixedW + (if (colCount == null) hScroll.value.toFloat() else 0f)
            for ((i, w) in colPx.withIndex()) { if (x < w) { c = cols[i]; break }; x -= w }
            if (c < 0) c = cols.last()
        }
        var y = p.y - headH
        if (y < frozenPx) {
            for ((i, h) in frozenRowPx.withIndex()) { if (y < h) return i to c; y -= h }
            return null
        }
        y -= frozenPx
        val items = listState.layoutInfo.visibleItemsInfo
        val hit = items.firstOrNull { y >= it.offset && y < it.offset + it.size } ?: (if (items.isNotEmpty() && y >= items.last().offset) items.last() else items.firstOrNull())
        val r = hit?.key as? Int ?: return null
        return r to c
    }

    val dragSelect = Modifier.pointerInput(s, cols, fixedCols, frozen, colCount) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val start = cellAt(down.position) ?: return@awaitEachGesture
            var dragging = false
            val slop = viewConfiguration.touchSlop
            while (true) {
                val ev = awaitPointerEvent(PointerEventPass.Initial)
                val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                if (!ch.pressed) { if (dragging) { ch.consume(); android.util.Log.i(io.embodit.spatialoffice.platform.TAG, "RANGE drag ${s.selection.name()}") }; break }
                if (!dragging && (ch.position - down.position).getDistance() > slop) {
                    dragging = true
                    edit?.commit(s); s.select(start.first, start.second)
                }
                if (dragging) {
                    ch.consume()
                    cellAt(ch.position)?.let { (r, c) -> if (r != s.row || c != s.col) { s.extendTo(r, c); edit?.load(s) } }
                }
            }
            if (dragging) runCatching { edit?.focus?.requestFocus() }
        }
    }

    Column(modifier.clip(RoundedCornerShape(12.dp)).background(Ink.Paper).border(1.dp, Ink.Line, RoundedCornerShape(12.dp)).then(dragSelect)) {
        Row(Modifier.fillMaxWidth().height(ROW_H).background(Ink.Header)) {
            Box(Modifier.width(HEAD_W).height(ROW_H).noKeyFocus().clickable { edit?.commit(s); s.selectAll(); edit?.load(s) }.semantics { contentDescription = "sheet-all" })
            @Composable fun head(c: Int) {
                // s42: a mark on the left edge of a column whose left neighbour is hidden; s29: the filtered column says so
                HeadCell(A1.colName(c) + (if (c == s.filterCol) " ▾" else ""), colWidth(s, c), ROW_H, c in sel.c1..sel.c2, "col-" + A1.colName(c), hiddenBefore = c - 1 in hiddenCols) {
                    edit?.commit(s); s.selectColumn(c); edit?.load(s)
                }
            }
            fixedCols.forEach { head(it) }
            if (fixedCols.isNotEmpty()) FreezeLine()
            Row(scroll) { cols.forEach { head(it) } }
        }
        // frozen top rows stay above the scrolling part
        for (r in 0 until frozen) GridRow(s, edit, r, fixedCols, cols, range, scroll, Modifier.background(Ink.Header.copy(alpha = 0.35f)), hiddenCols)
        if (frozen > 0) Box(Modifier.fillMaxWidth().height(2.dp).background(Ink.Select.copy(alpha = 0.6f)))
        LazyColumn(Modifier.fillMaxWidth().weight(1f), state = listState) {
            items(shown.size, key = { shown[it] }) { i -> GridRow(s, edit, shown[i], fixedCols, cols, range, scroll, Modifier, hiddenCols) }
        }
    }
}

/** s30: the line between frozen columns and the ones that scroll. */
@Composable
private fun FreezeLine() { Box(Modifier.width(2.dp).fillMaxHeight().background(Ink.Select.copy(alpha = 0.6f))) }

@Composable
private fun GridRow(s: SheetSession, edit: CellEdit?, r: Int, fixedCols: List<Int>, cols: List<Int>, range: IntArray?, scroll: Modifier, modifier: Modifier, hiddenCols: Set<Int> = emptySet()) {
    val sel = s.selection
    val h = rowHeight(s, r)
    Row(modifier.height(h)) {
        // s42: a mark on the top edge of a row whose upper neighbour is hidden
        HeadCell((r + 1).toString(), HEAD_W, h, r in sel.r1..sel.r2, "row-${r + 1}", hiddenAbove = r > 0 && !s.isCsv && s.book.isRowHidden(s.sheet, r - 1)) { edit?.commit(s); s.selectRow(r); edit?.load(s) }
        if (fixedCols.isNotEmpty()) { CellsRow(s, edit, r, fixedCols, range, h); FreezeLine() }
        Row(scroll) { CellsRow(s, edit, r, cols, range, h) }
    }
}

/** The cells of row [r] in [cols] (one stretch: the frozen columns, or the ones that scroll). */
@Composable
private fun CellsRow(s: SheetSession, edit: CellEdit?, r: Int, cols: List<Int>, range: IntArray?, h: Dp) {
    // M5 s35: cells with a comment (blue corner) or an old-style note (grey corner); the workbook keeps the map between changes
    val commented = remember(s.version, s.sheet) { s.commentCells() }
    val book = s.book
    val sel = s.selection
    run {
        // v0.5.0 (s25): a merged range is one box across its columns (drawn at its first column in view); its value is
        // the top-left cell's and shows in its top row; the rows below continue the box without inner lines
        val merges = remember(s.version, s.sheet) { if (s.isCsv) emptyList() else runCatching { book.merges(s.sheet) }.getOrDefault(emptyList()) }
        val segs = remember(r, cols, merges) {
            val out = ArrayList<Pair<List<Int>, IntArray?>>()
            var i = 0
            while (i < cols.size) {
                val c = cols[i]
                val m = merges.firstOrNull { r in it[0]..it[2] && c in it[1]..it[3] }
                val span = if (m == null) listOf(c) else cols.subList(i, cols.size).takeWhile { it <= m[3] }
                out.add(span to m)
                i += span.size
            }
            out
        }
        Row {
            segs.forEach { (span, m) ->
                val c = span.first()
                val tRow = m?.get(0) ?: r
                val tCol = m?.get(1) ?: c
                val cell = book.cell(s.sheet, tRow, tCol)
                val active = if (m != null) s.row in m[0]..m[2] && s.col in m[1]..m[3] else r == s.row && c == s.col
                val inSel = sel.contains(r, c) || (m != null && span.any { sel.contains(r, it) })
                val inRange = range != null && r >= range[0] && r <= range[2] && c >= range[1] && c <= range[3]
                val fill = if (s.isCsv) null else book.fillRgb(s.sheet, tRow, tCol)
                val base = if (fill != null) Color(0xFF000000 or fill.toLong()) else if (inRange) Ink.Range else Color.Transparent
                val last = span.last()
                val edges = if (inSel && s.isRange) booleanArrayOf(r == sel.r1, last >= sel.c2, r == sel.r2, c <= sel.c1) else null
                val alignEnd = when (cell.hAlign) { "right" -> true; "left", "center" -> false; else -> cell.kind == CellKind.NUMBER }
                val lines = if (m == null) null else booleanArrayOf(r == m[0], true, r == m[2], true)
                val bd = io.embodit.spatialoffice.core.xlsx.Borders
                // a merged box draws the file's lines only on its outer edge (the cells inside may carry their own)
                val inner = if (m == null) 0 else (if (r != m[0]) bd.TOP else 0) or (if (r != m[2]) bd.BOTTOM else 0)
                val ruled = if (s.isCsv) 0 else ((book.borderMask(s.sheet, r, c) and (bd.LEFT or bd.TOP or bd.BOTTOM)) or
                    (book.borderMask(s.sheet, r, last) and bd.RIGHT)) and inner.inv()
                val showText = m == null || r == m[0]
                Box(
                    Modifier.width(span.fold(0.dp) { acc, cc -> acc + colWidth(s, cc) }).height(h)
                        .background(base)
                        .background(if (inSel && (s.isRange || active)) Ink.SelectFill else Color.Transparent)
                        .then(if (lines == null || active) Modifier.border(if (active) 2.dp else 0.5.dp, if (active) Ink.Select else Ink.Line) else Modifier.gridLines(lines))
                        .fileBorders(ruled)
                        .rangeEdges(edges)
                        .noKeyFocus().clickable {
                            edit?.commit(s)
                            if (m != null) { s.select(m[0], m[1]); s.extendTo(m[2], m[3]) } else s.select(r, c)
                            edit?.load(s)
                            runCatching { edit?.focus?.requestFocus() }
                            // M5: with the review pane open, a tap on a commented cell picks its card (and lets go of the pick elsewhere)
                            if (s.reviewOpen) s.pickComment(s.commentHere()?.id)
                        }
                        .commentCorner(commented[A1.name(r, c)])
                        .semantics { contentDescription = "cell-" + A1.name(r, c) }
                        .padding(horizontal = 8.dp),
                    contentAlignment = when { cell.hAlign == "center" -> Alignment.Center; alignEnd -> Alignment.CenterEnd; else -> Alignment.CenterStart },
                ) {
                    // a cell shows what fits, like every spreadsheet; the full value is in the formula bar (wrapped cells: as many lines as the row holds)
                    if (showText) Text(
                        if (active && edit?.editing == true) edit.value.text else cell.display,
                        style = cellTextStyle(cell, s.book.defaultFontPt()),
                        maxLines = if (cell.wrap) 20 else 1, softWrap = cell.wrap,
                        textAlign = when { cell.hAlign == "center" -> TextAlign.Center; alignEnd -> TextAlign.End; else -> TextAlign.Start },
                    )
                }
            }
        }
    }
}

/** A cell's text (s23): bold, italic, underline, strikethrough, its font colour, its size relative to the workbook's default (15 sp). */
fun cellTextStyle(cell: io.embodit.spatialoffice.core.xlsx.Cell, defaultPt: Float): TextStyle {
    val scale = ((cell.fontPt ?: defaultPt) / defaultPt.coerceAtLeast(1f)).coerceIn(0.5f, 4f)
    return TextStyle(
        fontSize = (15f * scale).sp, lineHeight = (19f * scale).sp,
        color = if (cell.kind == CellKind.ERROR) Color(0xFFB3261E) else cell.colorRgb?.let { io.embodit.spatialoffice.ui.TextPalette.rgb(it) } ?: Ink.Text,
        fontWeight = if (cell.bold) FontWeight.Bold else null,
        fontStyle = if (cell.italic) androidx.compose.ui.text.font.FontStyle.Italic else null,
        textDecoration = when {
            cell.underline && cell.strike -> androidx.compose.ui.text.style.TextDecoration.combine(listOf(androidx.compose.ui.text.style.TextDecoration.Underline, androidx.compose.ui.text.style.TextDecoration.LineThrough))
            cell.underline -> androidx.compose.ui.text.style.TextDecoration.Underline
            cell.strike -> androidx.compose.ui.text.style.TextDecoration.LineThrough
            else -> null
        },
    )
}

/** The thin grid line of a merged box: only the sides that are its outer edge (top, right, bottom, left). */
private fun Modifier.gridLines(sides: BooleanArray): Modifier = drawWithContent {
    drawContent()
    val w = 0.5.dp.toPx()
    val c = Ink.Line
    if (sides[0]) drawRect(c, size = androidx.compose.ui.geometry.Size(size.width, w))
    if (sides[1]) drawRect(c, topLeft = androidx.compose.ui.geometry.Offset(size.width - w, 0f), size = androidx.compose.ui.geometry.Size(w, size.height))
    if (sides[2]) drawRect(c, topLeft = androidx.compose.ui.geometry.Offset(0f, size.height - w), size = androidx.compose.ui.geometry.Size(size.width, w))
    if (sides[3]) drawRect(c, size = androidx.compose.ui.geometry.Size(w, size.height))
}

/** s26: the file's own cell borders, drawn as dark 1.5 dp lines on the sides in [mask] ([io.embodit.spatialoffice.core.xlsx.Borders] bits). */
private fun Modifier.fileBorders(mask: Int): Modifier = if (mask == 0) this else drawWithContent {
    drawContent()
    val w = 1.5.dp.toPx()
    val c = Ink.Text
    val b = io.embodit.spatialoffice.core.xlsx.Borders
    if (mask and b.TOP != 0) drawRect(c, size = androidx.compose.ui.geometry.Size(size.width, w))
    if (mask and b.RIGHT != 0) drawRect(c, topLeft = androidx.compose.ui.geometry.Offset(size.width - w, 0f), size = androidx.compose.ui.geometry.Size(w, size.height))
    if (mask and b.BOTTOM != 0) drawRect(c, topLeft = androidx.compose.ui.geometry.Offset(0f, size.height - w), size = androidx.compose.ui.geometry.Size(size.width, w))
    if (mask and b.LEFT != 0) drawRect(c, size = androidx.compose.ui.geometry.Size(w, size.height))
}

/** The 2 dp blue box around a selected range: each cell on its edge draws that side (top, right, bottom, left). */
private fun Modifier.rangeEdges(edges: BooleanArray?): Modifier = if (edges == null) this else drawWithContent {
    drawContent()
    val w = 2.dp.toPx()
    val c = Ink.Select
    if (edges[0]) drawRect(c, size = androidx.compose.ui.geometry.Size(size.width, w))
    if (edges[1]) drawRect(c, topLeft = androidx.compose.ui.geometry.Offset(size.width - w, 0f), size = androidx.compose.ui.geometry.Size(w, size.height))
    if (edges[2]) drawRect(c, topLeft = androidx.compose.ui.geometry.Offset(0f, size.height - w), size = androidx.compose.ui.geometry.Size(size.width, w))
    if (edges[3]) drawRect(c, size = androidx.compose.ui.geometry.Size(w, size.height))
}

@Composable
private fun HeadCell(label: String, width: Dp, height: Dp, active: Boolean, name: String, hiddenBefore: Boolean = false, hiddenAbove: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier.width(width).height(height).background(if (active) Ink.Range else Ink.Header).border(0.5.dp, Ink.Line)
            .then(if (hiddenBefore || hiddenAbove) Modifier.drawWithContent {
                drawContent()
                val w = 4.dp.toPx()
                if (hiddenBefore) drawRect(Ink.Select, size = androidx.compose.ui.geometry.Size(w, size.height))
                if (hiddenAbove) drawRect(Ink.Select, size = androidx.compose.ui.geometry.Size(size.width, w))
            } else Modifier)
            .noKeyFocus().clickable(onClick = onClick).semantics { contentDescription = name + if (hiddenBefore || hiddenAbove) "-after-hidden" else "" },
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = TextStyle(fontSize = 14.sp, color = Ink.TextSoft, fontWeight = if (active) FontWeight.Bold else null), maxLines = 1)
    }
}

@Composable
private fun SheetPicker(graph: Graph, s: SheetSession, edit: CellEdit) {
    s.version
    s.sheetCount
    val names = s.book.sheetNames
    val chart = remember(s.version, s.sheet) { runCatching { s.book.charts().firstOrNull { it.sheet == s.sheet } }.getOrNull() }
    val sel = s.selection
    val stats = remember(s.version, s.sheet, sel) { if (s.isRange) runCatching { s.stats() }.getOrNull() else null }
    val tabs = rememberScrollState()
    Row(Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically) {
        Txt(tr(R.string.sheet_label), "sheet/picker-label", Modifier.padding(end = 12.dp), style = PicoTheme.typography.labelLarge, color = PicoTheme.colorScheme.labelSecondary)
        // every worksheet, scrolled sideways when there are many (s21: a new one is picked and in view at once)
        LaunchedEffect(s.sheet, names.size) { runCatching { tabs.animateScrollTo((s.sheet * 132 * 2 - 200).coerceAtLeast(0)) } }
        Box(Modifier.weight(1f, fill = false).widthIn(max = 560.dp).horizontalScroll(tabs)) {
            Segments(names.mapIndexed { i, n -> i to n }, s.sheet, "sheet/picker", itemWidth = 132.dp) { i ->
                edit.commit(s); s.sheet = i; s.select(0, 0); edit.load(s)
            }
        }
        Box(Modifier.width(8.dp))
        ToolMenu("⋯", "sheet/tabs-menu", enabled = !s.isCsv && !s.readOnly, width = 56.dp, entries = listOfNotNull(
            MenuEntry(tr(R.string.sheet_new), "sheet/tab-new") { edit.commit(s); s.addSheet(); edit.load(s); graph.say(s, graph.str(R.string.sheet_added, s.book.sheetNames[s.sheet])) },
            MenuEntry(tr(R.string.sheet_rename), "sheet/tab-rename") { edit.commit(s); graph.show(s, Overlay.SheetRename(Win.SHEET)) },
            MenuEntry(tr(R.string.sheet_copy), "sheet/tab-copy") { edit.commit(s); s.copySheet(); edit.load(s); graph.say(s, graph.str(R.string.sheet_added, s.book.sheetNames[s.sheet])) },
            if (names.size > 1) MenuEntry(tr(R.string.sheet_delete), "sheet/tab-delete") { edit.commit(s); graph.show(s, Overlay.SheetDelete(Win.SHEET)) } else null,
        ))
        if (stats != null) Txt(
            tr(R.string.range_stats, RangeStats.fmt(stats.sum), stats.average?.let { RangeStats.fmt(it) } ?: "—", stats.count),
            "sheet/stats", Modifier.weight(1f).padding(start = 16.dp), style = PicoTheme.typography.bodyMedium, color = PicoTheme.colorScheme.labelPrimary,
            align = TextAlign.End,
        )
        else if (chart?.range != null) Txt(
            tr(R.string.range_hint, chart.title.ifEmpty { tr(R.string.chart_3d) }, chart.range ?: "") +
                // v0.2.3: the chart's "click a bar" hint lives here, under the volume (its panel keeps to value + controls)
                (if (graph.windows.isOpen(Win.CHART)) "  ·  " + tr(if (graph.chartScatter) R.string.tip_dot else R.string.tip_bar) else ""), "sheet/range-hint",
            Modifier.weight(1f).padding(start = 16.dp), style = PicoTheme.typography.bodySmall, color = PicoTheme.colorScheme.labelSecondary, maxLines = 2,
        )
    }
}

/** Ctrl+C / Ctrl+X / [Edit ▸ Copy / Cut]: the selection goes to the system clipboard as tab-separated text too. */
fun copyCells(graph: Graph, s: SheetSession, cut: Boolean) {
    val text = s.copy(cut)
    runCatching {
        val cm = graph.app.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("cells", text))
    }.onFailure { android.util.Log.w(io.embodit.spatialoffice.platform.TAG, "CLIP set failed: $it") }
    android.util.Log.i(io.embodit.spatialoffice.platform.TAG, "CLIP ${if (cut) "cut" else "copy"} ${s.selection.name()} ${text.length} chars")
    graph.say(s, graph.str(if (cut) R.string.cells_cut else R.string.cells_copied, s.selection.name()))
}

/** s24 Ctrl+D / Ctrl+R / [Edit ▸ Fill ›]: copies the first row / column of the selection into the rest. */
fun fillCells(graph: Graph, s: SheetSession, down: Boolean) {
    val n = s.fill(down)
    graph.say(s, if (n == 0) graph.str(R.string.fill_nothing) else graph.str(R.string.fill_done, n))
}

/** s24 [Edit ▸ Fill › Series]: down when the selection is taller than wide, else right. */
fun fillSeries(graph: Graph, s: SheetSession) {
    val r = s.selection
    val n = s.fillSeries(down = r.rows >= r.cols)
    graph.say(s, if (n == 0) graph.str(R.string.series_nothing) else graph.str(R.string.fill_done, n))
}

/** Ctrl+V / [Edit ▸ Paste]: cells copied here (formulas moved by the offset), or text from another app split at tabs and line breaks. */
fun pasteCells(graph: Graph, s: SheetSession) {
    val text = runCatching {
        val cm = graph.app.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(graph.app)?.toString()
    }.getOrNull()
    val r = s.paste(text)
    android.util.Log.i(io.embodit.spatialoffice.platform.TAG, "CLIP paste -> ${r?.name()} clipText=${text?.length}")
    graph.say(s, if (r == null) graph.str(R.string.nothing_to_paste) else graph.str(R.string.cells_pasted, r.name()))
}

/** The connected block of non-empty cells around the selection, as an A1 range; null when it is smaller than 2 × 2. */
fun currentRegion(s: SheetSession): String? {
    val b = s.book
    fun filled(r: Int, c: Int) = r >= 0 && c >= 0 && b.cell(s.sheet, r, c).input.isNotEmpty()
    if (!filled(s.row, s.col)) return null
    var r0 = s.row; var r1 = s.row; var c0 = s.col; var c1 = s.col
    var grew = true
    while (grew) {
        grew = false
        if (r0 > 0 && (c0..c1).any { filled(r0 - 1, it) }) { r0--; grew = true }
        if ((c0..c1).any { filled(r1 + 1, it) }) { r1++; grew = true }
        if (c0 > 0 && (r0..r1).any { filled(it, c0 - 1) }) { c0--; grew = true }
        if ((r0..r1).any { filled(it, c1 + 1) }) { c1++; grew = true }
        if (r1 - r0 > 200 || c1 - c0 > 60) break
    }
    return if (r1 > r0 && c1 > c0) A1.name(r0, c0) + ":" + A1.name(r1, c1) else null
}

/** [3D chart]: shows the sheet's chart in the volume; when the sheet has none, makes a standard chart from the data around the selection. */
fun openChart(graph: Graph, s: SheetSession) {
    val charts = runCatching { s.book.charts() }.getOrDefault(emptyList())
    var index = charts.indexOfFirst { it.sheet == s.sheet }
    if (index < 0) {
        val region = currentRegion(s)
        val title = s.book.sheetNames.getOrElse(s.sheet) { "" }
        if (region == null || !s.addChart(region, title)) { graph.say(s, graph.str(R.string.chart_fail)); return }
        index = s.book.charts().indexOfFirst { it.sheet == s.sheet }
        graph.say(s, graph.str(R.string.chart_made, title))
    }
    graph.activate(s)
    graph.chartTag = s.tag
    graph.chartIndex = index.coerceAtLeast(0)
    graph.chartYaw = io.embodit.spatialoffice.ui.chart.CHART_DEFAULT_YAW
    graph.windows.openChart()
}

/** Fill colours offered in the toolbar: light tints that keep black text readable (≥ 4.5:1). */
val FILLS: List<Pair<Int, Int?>> = listOf(
    R.string.fill_none to null, R.string.fill_yellow to 0xFFF3B0, R.string.fill_green to 0xD4F4DD,
    R.string.fill_blue to 0xDBEAFE, R.string.fill_red to 0xFDE2E1, R.string.fill_gray to 0xE5E7EB,
)

/** Makes a chart of [type] ("bar" / "line" / "pie") from the data around the selection and shows it. */
fun makeChart(graph: Graph, s: SheetSession, type: String) {
    val region = currentRegion(s)
    val title = s.book.sheetNames.getOrElse(s.sheet) { "" }
    if (region == null || !s.addChart(region, title, type)) { graph.say(s, graph.str(R.string.chart_fail)); return }
    graph.say(s, graph.str(R.string.chart_made, title))
    graph.show(s, Overlay.ChartView(Win.SHEET, s.book.charts().lastIndex))
}

@Composable
private fun SheetToolbar(graph: Graph, s: SheetSession, edit: CellEdit) {
    s.version
    val cell = s.book.cell(s.sheet, s.row, s.col)
    val hasCharts = remember(s.version) { runCatching { s.book.charts().isNotEmpty() }.getOrDefault(false) }
    // after a menu action the keyboard goes back to the selected cell (a new row / column is selected, D57)
    // The system Toolbar window keeps the keyboard after its menus (the app cannot hand the window focus back), so
    // the toolbar itself takes the keys and treats them as the grid would: typing goes into the selected cell.
    val keys = remember(s) { FocusRequester() }
    // M5: Edit › Comment was just picked: the toolbar takes the keys and passes them to the comment being written
    LaunchedEffect(s.composeTick) { if (s.composing) { kotlinx.coroutines.delay(520); runCatching { keys.requestFocus() } } }
    fun run(f: () -> Unit) { edit.commit(s); f(); edit.load(s); runCatching { edit.focus.requestFocus() }; runCatching { keys.requestFocus() } }
    Toolbar(
        supportingContent = { FileTools(graph, Win.SHEET, s) },
        content = { io.embodit.spatialoffice.ui.ToolbarScaled {
            Box(Modifier.focusRequester(keys).onPreviewKeyEvent { e -> io.embodit.spatialoffice.ui.composeKeys(graph, s, e) || commonKeys(graph, s, e) || sheetKeys(graph, s, edit, e) || typeIntoCell(s, edit, e) }.typingTarget().focusable()) {
            ToolRow {
                // M2 s23: [Text ▾] takes the B button's place (B / I / U / strikethrough, sizes, clear formatting); the groups stay 8
                ToolMenu(tr(R.string.menu_text), "sheet/text", enabled = !s.isCsv, entries = listOf(
                    MenuEntry(tr(R.string.bold), "sheet/bold", checked = cell.bold) { run { s.setBold(!s.allSelected { it.bold }) } },
                    MenuEntry(tr(R.string.italic), "sheet/italic", checked = cell.italic) { run { s.setItalic(!s.allSelected { it.italic }) } },
                    MenuEntry(tr(R.string.underline), "sheet/underline", checked = cell.underline) { run { s.setUnderline(!s.allSelected { it.underline }) } },
                    MenuEntry(tr(R.string.strike), "sheet/strike", checked = cell.strike) { run { s.setStrike(!s.allSelected { it.strike }) } },
                ) + sizeEntries("sheet/text", Math.round((cell.fontPt ?: s.book.defaultFontPt()) * 2), { n -> run { s.stepFontSize(n) } }, { hp -> run { s.setFontSize(hp / 2f) } }) + listOf(
                    MenuEntry(tr(R.string.clear_format), "sheet/clear-format") { run { s.clearFont() } },
                ))
                // v0.3.0: [Edit ▾] copy / cut / paste / find (each shortcut has its entry here)
                ToolMenu(tr(R.string.menu_edit), "sheet/edit", entries = listOf(
                    MenuEntry(tr(R.string.edit_copy), "sheet/copy") { edit.commit(s); copyCells(graph, s, cut = false) },
                    MenuEntry(tr(R.string.edit_cut), "sheet/cut") { edit.commit(s); copyCells(graph, s, cut = true) },
                    MenuEntry(tr(R.string.edit_paste), "sheet/paste") { run { pasteCells(graph, s) } },
                    MenuEntry(tr(R.string.edit_find), "sheet/find") { edit.commit(s); graph.show(s, Overlay.Find(Win.SHEET)) },
                    // M5 s35: a comment on the current cell (Ctrl+Alt+M)
                    MenuEntry(tr(R.string.ins_comment), "sheet/comment") { edit.commit(s); graph.startComment(s) },
                    // v0.5.0 (s24): Ctrl+D / Ctrl+R and series fill
                    io.embodit.spatialoffice.ui.subMenu(tr(R.string.menu_fill), "sheet/fill", listOf(
                        MenuEntry(tr(R.string.fill_down), "sheet/fill-down") { run { fillCells(graph, s, true) } },
                        MenuEntry(tr(R.string.fill_right), "sheet/fill-right") { run { fillCells(graph, s, false) } },
                        MenuEntry(tr(R.string.fill_series), "sheet/fill-series") { run { fillSeries(graph, s) } },
                    )),
                    MenuEntry(tr(R.string.menu_history), "sheet/history") { edit.commit(s); graph.show(s, Overlay.History(Win.SHEET)) },
                ))
                ToolMenu(
                    tr(when (cell.fmt) { NumFmt.CURRENCY -> R.string.fmt_currency; NumFmt.PERCENT -> R.string.fmt_percent; NumFmt.DATE -> R.string.fmt_date; NumFmt.OTHER -> R.string.fmt_number; else -> R.string.fmt_general }),
                    "sheet/fmt", enabled = !s.isCsv,
                    entries = listOf(NumFmt.GENERAL to R.string.fmt_general, NumFmt.OTHER to R.string.fmt_number, NumFmt.CURRENCY to R.string.fmt_currency, NumFmt.PERCENT to R.string.fmt_percent, NumFmt.DATE to R.string.fmt_date).map { (f, label) ->
                        MenuEntry(tr(label), "sheet/fmt-item", checked = cell.fmt == f) { run { s.setFmt(f) } }
                    } + listOf(
                        // s22: alignment, wrap and decimal places live in the same [Format ▾]
                        MenuEntry(tr(R.string.align_left), "sheet/align-left", checked = cell.hAlign == "left") { run { s.setHAlign("left") } },
                        MenuEntry(tr(R.string.align_center), "sheet/align-center", checked = cell.hAlign == "center") { run { s.setHAlign("center") } },
                        MenuEntry(tr(R.string.align_right), "sheet/align-right", checked = cell.hAlign == "right") { run { s.setHAlign("right") } },
                        MenuEntry(tr(R.string.wrap_text), "sheet/wrap", checked = cell.wrap) { run { s.setWrap(!cell.wrap) } },
                        MenuEntry(tr(R.string.dec_more), "sheet/dec-more") { run { s.adjustDecimals(1) } },
                        MenuEntry(tr(R.string.dec_less), "sheet/dec-less") { run { s.adjustDecimals(-1) } },
                    ),
                )
                // M2: [Colour ▾] = text colours (s23) and the cell fills that were [Fill ▾]
                ToolMenu(tr(R.string.menu_color), "sheet/color", enabled = !s.isCsv, entries =
                    colorEntries("sheet/color", cell.colorRgb, { rgb -> run { s.setFontColor(rgb) } }) + FILLS.map { (label, rgb) ->
                        MenuEntry(if (rgb == null) tr(label) else tr(R.string.fill_color, tr(label)), "sheet/fill-item") { run { s.setFill(rgb) } }
                    } + listOf(
                        // v0.5.0 (s26): borders in the lower part of the fill menu, no new button
                        io.embodit.spatialoffice.ui.subMenu(tr(R.string.menu_borders), "sheet/borders", listOf(
                            MenuEntry(tr(R.string.border_all), "sheet/border-all") { run { s.setBorders("all") } },
                            MenuEntry(tr(R.string.border_outline), "sheet/border-outline") { run { s.setBorders("outline") } },
                            MenuEntry(tr(R.string.border_bottom), "sheet/border-bottom") { run { s.setBorders("bottom") } },
                            MenuEntry(tr(R.string.border_none), "sheet/border-none") { run { s.setBorders("none") } },
                        )),
                    ))
                ToolIcon(R.drawable.ic_sigma, tr(R.string.autosum), onClick = {
                    edit.commit(s); if (s.autoSum() == null) graph.say(s, graph.str(R.string.no_num)); edit.load(s)
                })
                ToolMenu(tr(R.string.menu_data), "sheet/data", icon = R.drawable.ic_list_sort_down, entries = listOfNotNull(
                    MenuEntry(tr(R.string.sort_asc), "sheet/sort-asc") { run { graph.say(s, if (s.sortByColumn(true)) graph.str(R.string.sorted, A1.colName(s.col)) else graph.str(R.string.sort_refused)) } },
                    MenuEntry(tr(R.string.sort_desc), "sheet/sort-desc") { run { graph.say(s, if (s.sortByColumn(false)) graph.str(R.string.sorted_desc, A1.colName(s.col)) else graph.str(R.string.sort_refused)) } },
                    // v0.6.0 (s31): several columns, headings kept on top
                    MenuEntry(tr(R.string.data_sort), "sheet/sort-dialog") { edit.commit(s); graph.show(s, Overlay.SortData(Win.SHEET)) },
                    MenuEntry(tr(R.string.filter_by, cell.display.ifEmpty { tr(R.string.filter_blank) }), "sheet/filter") { run { sheetFilter(graph, s) } },
                    // v0.6.0 (s29): greater / less than, contains, several values
                    MenuEntry(tr(R.string.data_filter, A1.colName(s.col)), "sheet/filter-dialog") { edit.commit(s); graph.show(s, Overlay.FilterData(Win.SHEET, s.col)) },
                    if (s.filterCol >= 0) MenuEntry(tr(R.string.filter_clear), "sheet/filter-clear") { s.applyFilter(null) } else null,
                    if (!s.isCsv) MenuEntry(tr(R.string.freeze_top), "sheet/freeze", checked = s.frozen == 1 && s.frozenCols == 0) { run { s.toggleFreeze() } } else null,
                    // v0.6.0 (s30): the rows above and the columns left of the selected cell
                    if (!s.isCsv && (s.row > 0 || s.col > 0)) MenuEntry(tr(R.string.freeze_here, A1.name(s.row, s.col)), "sheet/freeze-here", checked = s.frozen == s.row && s.frozenCols == s.col) {
                        run { val (fr, fcn) = s.freezeHere(); graph.say(s, graph.str(R.string.frozen_at, fr, fcn)) }
                    } else null,
                    if (!s.isCsv && (s.frozen > 0 || s.frozenCols > 0)) MenuEntry(tr(R.string.freeze_off), "sheet/unfreeze") { run { s.unfreeze() } } else null,
                ))
                ToolMenu(tr(R.string.menu_rows), "sheet/rows", entries = listOf(
                    MenuEntry(tr(R.string.insert_row), "sheet/insert-row") { run { s.insertRowBelow() } },
                    MenuEntry(tr(R.string.delete_row), "sheet/delete-row") { run { s.deleteRow() } },
                    MenuEntry(tr(R.string.insert_col), "sheet/insert-col") { run { s.insertColumn() } },
                    MenuEntry(tr(R.string.delete_col), "sheet/delete-col") { run { s.deleteColumn() } },
                ) + if (s.isCsv) emptyList() else listOfNotNull(
                    // v0.5.0 (s25): merge the selection / split the merged cell under it
                    if (s.isRange) MenuEntry(tr(R.string.merge_cells), "sheet/merge") { run { if (!s.merge()) graph.say(s, graph.str(R.string.merge_refused)) else graph.say(s, graph.str(R.string.merged, s.selection.name())) } } else null,
                    if (s.mergeHere() != null || (s.isRange && s.book.merges(s.sheet).any { m -> m[0] <= s.selection.r2 && m[2] >= s.selection.r1 && m[1] <= s.selection.c2 && m[3] >= s.selection.c1 }))
                        MenuEntry(tr(R.string.unmerge_cells), "sheet/unmerge") { run { s.unmerge() } } else null,
                    // s20: widths and heights of the selected columns / rows
                    MenuEntry(tr(R.string.col_wider), "sheet/col-wider") { run { s.widen(2f) } },
                    MenuEntry(tr(R.string.col_narrower), "sheet/col-narrower") { run { s.widen(-2f) } },
                    MenuEntry(tr(R.string.col_autofit), "sheet/col-autofit") { run { s.autoFit() } },
                    MenuEntry(tr(R.string.row_taller), "sheet/row-taller") { run { s.taller(6f) } },
                    MenuEntry(tr(R.string.row_shorter), "sheet/row-shorter") { run { s.taller(-6f) } },
                    // v0.6.0 (s42): hide the selected rows / columns, show hidden ones again
                    io.embodit.spatialoffice.ui.subMenu(tr(R.string.hide_menu), "sheet/hide", listOfNotNull(
                        MenuEntry(tr(R.string.hide_rows), "sheet/hide-rows") { run { if (s.hide(cols = false) > 0) graph.say(s, graph.str(R.string.hidden_now, s.selection.name())) } },
                        MenuEntry(tr(R.string.hide_cols), "sheet/hide-cols") { run { if (s.hide(cols = true) > 0) graph.say(s, graph.str(R.string.hidden_now, s.selection.name())) } },
                        if (s.anyHidden) MenuEntry(tr(R.string.unhide), "sheet/unhide") { run { graph.say(s, graph.str(R.string.unhidden_n, s.unhide())) } } else null,
                    )),
                ))
                ToolMenu(tr(R.string.menu_chart), "sheet/chart", icon = R.drawable.ic_chart_3d, enabled = !s.isCsv, entries = listOfNotNull(
                    MenuEntry(tr(R.string.chart_bar), "sheet/chart-bar") { run { makeChart(graph, s, "bar") } },
                    MenuEntry(tr(R.string.chart_line), "sheet/chart-line") { run { makeChart(graph, s, "line") } },
                    MenuEntry(tr(R.string.chart_pie), "sheet/chart-pie") { run { makeChart(graph, s, "pie") } },
                    if (hasCharts) MenuEntry(tr(R.string.chart_view), "sheet/chart-view") { graph.show(s, Overlay.ChartView(Win.SHEET)) } else null,
                    MenuEntry(tr(R.string.chart_3d), "sheet/chart-3d") { run { openChart(graph, s) } },
                ))
                ToolDivider()
                UndoRedo(s)
            }
            }
        } },
    )
}

/** Data ▸ Filter by this value: shows only the rows whose value in this column equals the selected cell's. */
fun sheetFilter(graph: Graph, s: SheetSession) {
    if (s.row == 0) { graph.say(s, graph.str(R.string.filter_header)); return }
    val v = s.book.cell(s.sheet, s.row, s.col).display
    s.applyFilter(io.embodit.spatialoffice.core.xlsx.FilterRule(s.col, io.embodit.spatialoffice.core.xlsx.FilterRule.Op.VALUES, values = setOf(v)))
    val n = s.visibleRows(s.rowCount).count { it in 1 until s.rowCount }
    graph.say(s, graph.str(R.string.filter_on, A1.colName(s.col), v, n))
}

/** A printable key arriving at the toolbar: starts editing the selected cell with it, as typing on the grid does. */
private fun typeIntoCell(s: SheetSession, edit: CellEdit, e: KeyEvent): Boolean {
    if (e.type != KeyEventType.KeyDown || e.isCtrlPressed || e.isAltPressed) return false
    if (e.key == Key.Backspace) {
        if (!edit.editing) { edit.editing = true; edit.value = TextFieldValue("") }
        else edit.value = TextFieldValue(edit.value.text.dropLast(1), TextRange(edit.value.text.length - 1))
        return true
    }
    val cp = e.nativeKeyEvent.unicodeChar
    if (cp < 32) return false
    val ch = String(Character.toChars(cp))
    val base = if (edit.editing) edit.value.text else ""
    edit.editing = true
    edit.value = TextFieldValue(base + ch, TextRange(base.length + ch.length))
    return true
}

/** M5 s35: the small triangle in a cell's top-right corner: blue = a comment (thread), grey = an old-style note. */
private fun Modifier.commentCorner(kind: io.embodit.spatialoffice.core.review.CommentKind?): Modifier = if (kind == null) this else this.drawWithContent {
    drawContent()
    val k = 9.dp.toPx()
    val path = androidx.compose.ui.graphics.Path().apply { moveTo(size.width - k, 0f); lineTo(size.width, 0f); lineTo(size.width, k); close() }
    drawPath(path, if (kind == io.embodit.spatialoffice.core.review.CommentKind.THREAD) io.embodit.spatialoffice.ui.ReviewLook.CellThread else io.embodit.spatialoffice.ui.ReviewLook.CellNote)
}
