package io.embodit.spatialoffice.ui.sheet

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
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
import io.embodit.spatialoffice.platform.Graph
import io.embodit.spatialoffice.platform.SheetSession
import io.embodit.spatialoffice.platform.Win
import io.embodit.spatialoffice.ui.EditorTitleBar
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
import io.embodit.spatialoffice.ui.WindowFrame
import io.embodit.spatialoffice.ui.commonKeys

/** Text being typed for the selected cell. One instance per sheet surface (window, or the focused block when spread out). */
class CellEdit {
    var value by mutableStateOf(TextFieldValue(""))
    var editing by mutableStateOf(false)
    val focus = FocusRequester()

    fun load(s: SheetSession) {
        val t = s.book.cell(s.sheet, s.row, s.col).input
        // the whole value is selected: typing replaces it (as in every spreadsheet); F2 or a click in the bar edits it
        value = TextFieldValue(t, TextRange(0, t.length)); editing = false
    }

    fun commit(s: SheetSession) {
        if (editing) s.setCell(s.row, s.col, value.text)
        editing = false
    }
}

private val ROW_H = 36.dp
private val HEAD_W = 52.dp

fun colWidth(s: SheetSession, c: Int): Dp = (s.book.colWidthChars(s.sheet, c) * 9f + 14f).coerceIn(76f, 240f).dp

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
fun SheetWindow() {
    WindowFrame(Win.SHEET) { graph ->
        val s = graph.sheet
        if (s == null) { NoFile(graph, Win.SHEET); return@WindowFrame }
        val edit = remember(s) { CellEdit() }
        Column(Modifier.fillMaxSize().onPreviewKeyEvent { e -> commonKeys(graph, Win.SHEET, e) || sheetKeys(graph, s, edit, e) }) {
            EditorTitleBar(graph, Win.SHEET, s)
            Column(Modifier.fillMaxWidth().weight(1f).padding(start = 32.dp, end = 32.dp, bottom = 52.dp)) {
                FormulaBar(s, edit, Modifier.fillMaxWidth())
                Box(Modifier.height(10.dp))
                SheetGrid(s, edit, 0, null, Modifier.fillMaxWidth().weight(1f))
                Box(Modifier.height(10.dp))
                SheetPicker(graph, s, edit)
            }
        }
        SheetToolbar(graph, s, edit)
    }
}

/** Keys of the grid: arrows / Tab / Enter move, Enter commits, Esc cancels, F2 edits, Alt+= sums. */
fun sheetKeys(graph: Graph, s: SheetSession, edit: CellEdit, e: KeyEvent): Boolean {
    if (e.type != KeyEventType.KeyDown) return false
    fun move(dr: Int, dc: Int) { edit.commit(s); s.select(s.row + dr, s.col + dc); edit.load(s) }
    return when {
        e.isAltPressed && e.key == Key.Equals -> { edit.commit(s); if (s.autoSum() == null) graph.say(Win.SHEET, graph.str(R.string.no_num)); edit.load(s); true }
        e.isCtrlPressed && e.key == Key.B -> { s.setBold(!s.book.cell(s.sheet, s.row, s.col).bold); true }
        e.isCtrlPressed || e.isAltPressed -> false
        e.key == Key.Enter || e.key == Key.NumPadEnter -> { move(if (e.isShiftPressed) -1 else 1, 0); true }
        e.key == Key.Tab -> { move(0, if (e.isShiftPressed) -1 else 1); true }
        e.key == Key.Escape -> { if (edit.editing) { edit.load(s); true } else false }
        e.key == Key.F2 -> { edit.editing = true; edit.value = edit.value.copy(selection = TextRange(edit.value.text.length)); true }
        e.key == Key.Delete && !edit.editing -> { s.setCell(s.row, s.col, ""); edit.load(s); true }
        edit.editing -> false // arrows move the caret while typing
        e.key == Key.DirectionUp -> { move(-1, 0); true }
        e.key == Key.DirectionDown -> { move(1, 0); true }
        e.key == Key.DirectionLeft -> { move(0, -1); true }
        e.key == Key.DirectionRight -> { move(0, 1); true }
        else -> false
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
            BasicTextField(
                value = edit.value,
                onValueChange = { nv ->
                    if (nv.text.contains('\n')) { edit.commit(s); s.select(s.row + 1, s.col); edit.load(s) } // Enter arriving as text
                    else { if (nv.text != edit.value.text) edit.editing = true; edit.value = nv }
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                // Enter from a keyboard that the system routes through the input method
                keyboardActions = KeyboardActions(onDone = { edit.commit(s); s.select(s.row + 1, s.col); edit.load(s) }),
                textStyle = TextStyle(fontSize = 17.sp, color = Ink.Text),
                cursorBrush = SolidColor(Ink.Select),
                modifier = Modifier.fillMaxWidth().focusRequester(edit.focus).semantics { contentDescription = "formula-input" },
            )
        }
    }
}

/**
 * The grid. [firstCol] / [colCount] limit it to a block of columns (a spread-out panel); null = all columns with
 * horizontal scrolling. Clicking a cell selects it and returns the keyboard to the formula bar.
 */
@Composable
fun SheetGrid(
    s: SheetSession, edit: CellEdit?, firstCol: Int, colCount: Int?, modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(), hScroll: ScrollState = rememberScrollState(),
) {
    s.version
    val book = s.book
    val rows = maxOf(book.rows(s.sheet) + 12, 24)
    val lastCol = if (colCount != null) firstCol + colCount else maxOf(book.cols(s.sheet) + 4, 14)
    val cols = (firstCol until lastCol).toList()
    val range = remember(s.version, s.sheet) { runCatching { chartRange(s) }.getOrNull() }
    val scroll = if (colCount == null) Modifier.horizontalScroll(hScroll) else Modifier
    Column(modifier.clip(RoundedCornerShape(12.dp)).background(Ink.Paper).border(1.dp, Ink.Line, RoundedCornerShape(12.dp))) {
        Row(Modifier.fillMaxWidth().height(ROW_H).background(Ink.Header)) {
            Box(Modifier.width(HEAD_W).height(ROW_H))
            Row(scroll) {
                cols.forEach { c -> HeadCell(A1.colName(c), colWidth(s, c), c == s.col) }
            }
        }
        LazyColumn(Modifier.fillMaxWidth().weight(1f), state = listState) {
            items(rows) { r ->
                Row(Modifier.height(ROW_H)) {
                    HeadCell((r + 1).toString(), HEAD_W, r == s.row)
                    Row(scroll) {
                        cols.forEach { c ->
                            val cell = book.cell(s.sheet, r, c)
                            val selected = r == s.row && c == s.col
                            val inRange = range != null && r >= range[0] && r <= range[2] && c >= range[1] && c <= range[3]
                            Box(
                                Modifier.width(colWidth(s, c)).height(ROW_H)
                                    .background(if (selected) Ink.SelectFill else if (inRange) Ink.Range else Color.Transparent)
                                    .border(if (selected) 2.dp else 0.5.dp, if (selected) Ink.Select else Ink.Line)
                                    .clickable {
                                        edit?.commit(s); s.select(r, c); edit?.load(s)
                                        runCatching { edit?.focus?.requestFocus() }
                                    }
                                    .semantics { contentDescription = "cell-" + A1.name(r, c) }
                                    .padding(horizontal = 8.dp),
                                contentAlignment = if (cell.kind == CellKind.NUMBER) Alignment.CenterEnd else Alignment.CenterStart,
                            ) {
                                // a cell shows what fits, like every spreadsheet; the full value is in the formula bar
                                Text(
                                    if (selected && edit?.editing == true) edit.value.text else cell.display,
                                    style = TextStyle(fontSize = 15.sp, color = if (cell.kind == CellKind.ERROR) Color(0xFFB3261E) else Ink.Text, fontWeight = if (cell.bold) FontWeight.Bold else null),
                                    maxLines = 1, softWrap = false,
                                    textAlign = if (cell.kind == CellKind.NUMBER) TextAlign.End else TextAlign.Start,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HeadCell(label: String, width: Dp, active: Boolean) {
    Box(Modifier.width(width).height(ROW_H).background(if (active) Ink.Range else Ink.Header).border(0.5.dp, Ink.Line), contentAlignment = Alignment.Center) {
        Text(label, style = TextStyle(fontSize = 14.sp, color = Ink.TextSoft, fontWeight = if (active) FontWeight.Bold else null), maxLines = 1)
    }
}

@Composable
private fun SheetPicker(graph: Graph, s: SheetSession, edit: CellEdit) {
    s.version
    val names = s.book.sheetNames
    val chart = remember(s.version, s.sheet) { runCatching { s.book.charts().firstOrNull { it.sheet == s.sheet } }.getOrNull() }
    Row(Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically) {
        Txt(tr(R.string.sheet_label), "sheet/picker-label", Modifier.padding(end = 12.dp), style = PicoTheme.typography.labelLarge, color = PicoTheme.colorScheme.labelSecondary)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Segments(names.take(6).mapIndexed { i, n -> i to n }, s.sheet, "sheet/picker", itemWidth = 132.dp) { i ->
                edit.commit(s); s.sheet = i; s.select(0, 0); edit.load(s)
            }
        }
        if (chart?.range != null) Txt(
            tr(R.string.range_hint, chart.title.ifEmpty { tr(R.string.chart_3d) }, chart.range ?: ""), "sheet/range-hint",
            Modifier.weight(1f).padding(start = 16.dp), style = PicoTheme.typography.bodySmall, color = PicoTheme.colorScheme.labelSecondary, maxLines = 2,
        )
    }
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
        if (region == null || !s.addChart(region, title)) { graph.say(Win.SHEET, graph.str(R.string.chart_fail)); return }
        index = s.book.charts().indexOfFirst { it.sheet == s.sheet }
        graph.say(Win.SHEET, graph.str(R.string.chart_made, title))
    }
    graph.chartIndex = index.coerceAtLeast(0)
    graph.chartYaw = 0f
    graph.windows.open(Win.CHART)
}

@Composable
private fun SheetToolbar(graph: Graph, s: SheetSession, edit: CellEdit) {
    s.version
    var fmtMenu by remember { mutableStateOf(false) }
    val cell = s.book.cell(s.sheet, s.row, s.col)
    Toolbar(
        supportingContent = { FileTools(graph, Win.SHEET, s) },
        content = {
            ToolRow {
                ToolToggle(R.drawable.ic_text_bold, tr(R.string.bold), cell.bold, { on -> edit.commit(s); s.setBold(on) }, enabled = !s.isCsv)
                Box {
                    ToolButton(
                        tr(when (cell.fmt) { NumFmt.CURRENCY -> R.string.fmt_currency; NumFmt.PERCENT -> R.string.fmt_percent; else -> R.string.fmt_general }),
                        "sheet/fmt", onClick = { fmtMenu = !fmtMenu }, enabled = !s.isCsv,
                    )
                    if (fmtMenu) {
                        Menu(
                            onDismissRequest = { fmtMenu = false },
                            positionProvider = rememberMenuPositionProvider(horizontalPlacement = HorizontalPlacement.alignStart(), verticalPlacement = VerticalPlacement.above(offset = (-12).dp)),
                        ) {
                            listOf(NumFmt.GENERAL to R.string.fmt_general, NumFmt.CURRENCY to R.string.fmt_currency, NumFmt.PERCENT to R.string.fmt_percent).forEach { (f, label) ->
                                MenuItem(title = { Txt(tr(label), "sheet/fmt-item") }, onClick = { fmtMenu = false; edit.commit(s); s.setFmt(f) })
                            }
                        }
                    }
                }
                ToolIcon(R.drawable.ic_sigma, tr(R.string.autosum), onClick = {
                    edit.commit(s); if (s.autoSum() == null) graph.say(Win.SHEET, graph.str(R.string.no_num)); edit.load(s)
                })
                ToolIcon(R.drawable.ic_list_sort_down, tr(R.string.sort_asc), onClick = {
                    edit.commit(s)
                    graph.say(Win.SHEET, if (s.sortByColumn()) graph.str(R.string.sorted, A1.colName(s.col)) else graph.str(R.string.sort_refused))
                    edit.load(s)
                })
                ToolButton(tr(R.string.insert_row), "sheet/insert-row", onClick = { edit.commit(s); s.insertRowBelow(); edit.load(s) })
                ToolButton(tr(R.string.chart_3d), "sheet/chart", icon = R.drawable.ic_chart_3d, onClick = { edit.commit(s); openChart(graph, s) }, enabled = !s.isCsv)
                ToolDivider()
                UndoRedo(s)
            }
        },
    )
}
