package io.embodit.spatialoffice.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.windows.Sheet
import io.embodit.spatialoffice.R
import io.embodit.spatialoffice.core.xlsx.A1
import io.embodit.spatialoffice.core.xlsx.FilterRule
import io.embodit.spatialoffice.platform.DocSession
import io.embodit.spatialoffice.platform.Graph
import io.embodit.spatialoffice.platform.SheetSession

/**
 * v0.6.0 (M4 s29) [Data ▸ Filter…]: the existing sheet (dialog) style. Pick values from the column's list, or a
 * condition: greater than / less than a number, contains a text. The header row always stays; the filter is a view of
 * the data (not saved in the file, as the one-value filter before it).
 */
@Composable
fun FilterSheet(graph: Graph, s: SheetSession, col: Int) {
    val old = s.filter?.takeIf { it.col == col }
    val values = remember(s.version, s.sheet, col) { s.columnValues(col) }
    var op by remember(col) { mutableStateOf(old?.op?.let { if (it == FilterRule.Op.GE || it == FilterRule.Op.LE) FilterRule.Op.GT else it } ?: FilterRule.Op.VALUES) }
    var text by remember(col) { mutableStateOf(old?.text ?: "") }
    var picked by remember(col) { mutableStateOf(old?.values?.takeIf { old.op == FilterRule.Op.VALUES } ?: values.toSet()) }
    val head = s.book.cell(s.sheet, 0, col).display
    val rule = when (op) {
        FilterRule.Op.VALUES -> FilterRule(col, op, values = picked)
        else -> FilterRule(col, op, text.trim())
    }
    val numeric = op == FilterRule.Op.GT || op == FilterRule.Op.LT
    val bad = numeric && rule.number == null
    val shown = if (bad) 0 else (1 until s.rowCount).count { r -> !s.book.isRowHidden(s.sheet, r) && rule.pass(s.book.cell(s.sheet, r, col)) }
    val close = { graph.overlay = null }
    Sheet(
        modifier = Modifier.width(760.dp),
        onDismissRequest = close,
        leadingAction = { SheetClose(close) },
        title = { Txt(tr(R.string.filter_title, A1.colName(col), head), "filter/title", style = PicoTheme.typography.titleLarge, weight = FontWeight.Bold, maxLines = 2) },
        bottom = {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Txt(if (bad) tr(R.string.filter_need_number) else tr(R.string.filter_shown, shown), "filter/count", Modifier.weight(1f),
                    style = PicoTheme.typography.bodyLarge, color = if (bad) PicoTheme.colorScheme.error else PicoTheme.colorScheme.labelSecondary, maxLines = 2)
                if (s.filter != null) SecondaryButton(tr(R.string.filter_clear), "filter/clear", onClick = { s.applyFilter(null); close() })
                PrimaryButton(tr(R.string.filter_apply), "filter/apply", enabled = !bad && (op != FilterRule.Op.VALUES || picked.isNotEmpty()), onClick = {
                    s.applyFilter(rule)
                    graph.say(s, graph.str(R.string.filter_rule_on, A1.colName(col), rule.summary(graph.str(R.string.filter_values_word), graph.str(R.string.filter_op_contains)), shown))
                    close()
                })
            }
        },
    ) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            // gate:allow tabs-segment a choice inside this dialog (condition type / sort column), not switching pages
            Segments(
                listOf(FilterRule.Op.VALUES to tr(R.string.filter_op_values), FilterRule.Op.GT to tr(R.string.filter_op_gt), FilterRule.Op.LT to tr(R.string.filter_op_lt), FilterRule.Op.CONTAINS to tr(R.string.filter_op_contains)),
                op, "filter/op", itemWidth = 150.dp,
            ) { op = it }
            if (op == FilterRule.Op.VALUES) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    SecondaryButton(tr(R.string.filter_all), "filter/all", onClick = { picked = values.toSet() })
                    SecondaryButton(tr(R.string.filter_none), "filter/none", onClick = { picked = emptySet() })
                }
                Column(Modifier.fillMaxWidth().heightIn(max = 300.dp).verticalScroll(rememberScrollState())) {
                    values.forEach { v ->
                        CheckRow(v.ifEmpty { tr(R.string.filter_blank) }, v in picked, "filter/value") { on -> picked = if (on) picked + v else picked - v }
                    }
                }
            } else {
                LabeledField(tr(if (numeric) R.string.filter_number else R.string.filter_text), text, "filter/value-field",
                    keyboard = if (numeric) androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal) else androidx.compose.foundation.text.KeyboardOptions.Default) { text = it }
            }
        }
    }
}

/**
 * v0.6.0 (M4 s31) [Data ▸ Sort…]: the block around the selection (or the selected range), by up to three columns in
 * order, each ascending or descending; with [My data has headers] (guessed from the first row) the headings stay on
 * top and the columns are named by them.
 */
@Composable
fun SortSheet(graph: Graph, s: SheetSession) {
    val block = remember(s.version, s.sheet) { s.sortBlock() }
    val close = { graph.overlay = null }
    if (block == null || block.rows < 2) {
        androidx.compose.runtime.LaunchedEffect(Unit) { graph.say(s, graph.str(R.string.sort_no_block)); close() }
        return
    }
    var header by remember(block) { mutableStateOf(s.blockHasHeader(block)) }
    var levels by remember(block) { mutableStateOf(listOf(s.col.coerceIn(block.c1, block.c2) to true)) }
    fun name(c: Int): String = if (header) A1.colName(c) + " · " + s.book.cell(s.sheet, block.r1, c).display.ifEmpty { "—" } else A1.colName(c)
    Sheet(
        modifier = Modifier.width(860.dp),
        onDismissRequest = close,
        leadingAction = { SheetClose(close) },
        title = { Txt(tr(R.string.sort_title, block.name()), "sort/title", style = PicoTheme.typography.titleLarge, weight = FontWeight.Bold) },
        bottom = {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                CheckRow(tr(R.string.sort_header), header, "sort/header") { header = it }
                Box(Modifier.weight(1f))
                PrimaryButton(tr(R.string.sort_ok), "sort/ok", onClick = {
                    val ok = s.sortBy(block, levels, header)
                    graph.say(s, if (ok) graph.str(R.string.sorted_by, levels.joinToString(" → ") { name(it.first) + (if (it.second) " ↑" else " ↓") }) else graph.str(R.string.sort_refused))
                    close()
                })
            }
        },
    ) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            levels.forEachIndexed { i, (c, asc) ->
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Txt(tr(if (i == 0) R.string.sort_by else R.string.sort_then), "sort/level", Modifier.width(120.dp), style = PicoTheme.typography.titleMedium, weight = FontWeight.Bold)
                        Segments(listOf(true to tr(R.string.sort_up), false to tr(R.string.sort_down)), asc, "sort/dir-${i + 1}", itemWidth = 120.dp) { v ->
                            levels = levels.mapIndexed { k, l -> if (k == i) l.first to v else l }
                        }
                        Box(Modifier.weight(1f))
                        if (i > 0) SecondaryButton(tr(R.string.sort_remove), "sort/remove-${i + 1}", onClick = { levels = levels.filterIndexed { k, _ -> k != i } })
                    }
                    Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                        // gate:allow tabs-segment a choice inside this dialog (condition type / sort column), not switching pages
                        Segments((block.c1..block.c2).map { it to name(it) }, c, "sort/col-${i + 1}", itemWidth = if (header) 168.dp else 72.dp) { v ->
                            levels = levels.mapIndexed { k, l -> if (k == i) v to l.second else l }
                        }
                    }
                }
            }
            // a further column to sort by (up to three)
            if (levels.size < 3) SecondaryButton(tr(R.string.sort_add), "sort/add", icon = R.drawable.ic_plus, onClick = {
                val next = (block.c1..block.c2).firstOrNull { c -> levels.none { it.first == c } } ?: block.c1
                levels = levels + (next to true)
            })
        }
    }
}

/** v0.6.0 (M4 d25) [More ▸ Outline]: the title and headings, indented by level; a click scrolls the page there. */
@Composable
fun OutlineSheet(graph: Graph, s: DocSession) {
    val items = remember(s.version) { s.outline() }
    val close = { graph.overlay = null }
    Sheet(
        modifier = Modifier.width(680.dp),
        onDismissRequest = close,
        leadingAction = { SheetClose(close) },
        title = { Txt(tr(R.string.outline), "outline/title", style = PicoTheme.typography.titleLarge, weight = FontWeight.Bold) },
    ) {
        if (items.isEmpty()) Txt(tr(R.string.outline_empty), "outline/empty", style = PicoTheme.typography.bodyLarge, color = PicoTheme.colorScheme.labelSecondary, maxLines = 3)
        else Column(Modifier.fillMaxWidth().heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            items.forEach { it ->
                Box(
                    Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(RoundedCornerShape(12.dp))
                        .noKeyFocus().clickable { s.jumpTo(it.id); close() }
                        .padding(start = (16 + 28 * maxOf(0, it.level - 1)).dp, end = 16.dp).semantics { contentDescription = "outline/item-${it.id}" },
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Txt(it.text, "outline/item", Modifier.widthIn(max = 600.dp),
                        style = if (it.level <= 1) PicoTheme.typography.titleMedium else PicoTheme.typography.bodyLarge,
                        weight = if (it.level <= 1) FontWeight.Bold else null, maxLines = 2)
                }
            }
        }
    }
}
