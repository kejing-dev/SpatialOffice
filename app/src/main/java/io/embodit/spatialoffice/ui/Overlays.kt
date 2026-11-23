package io.embodit.spatialoffice.ui

import androidx.compose.foundation.background
import io.embodit.spatialoffice.platform.typingTarget
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.Icon
import com.pico.spatial.ui.design.IconButton
import com.pico.spatial.ui.design.ListItem
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.windows.AlertDialog
import com.pico.spatial.ui.design.windows.Sheet
import io.embodit.spatialoffice.R
import io.embodit.spatialoffice.core.docx.CompatKind
import io.embodit.spatialoffice.core.docx.CompatNote
import io.embodit.spatialoffice.core.files.FileKind
import io.embodit.spatialoffice.platform.Exporter
import io.embodit.spatialoffice.platform.Graph
import io.embodit.spatialoffice.platform.Overlay
import io.embodit.spatialoffice.platform.SaveAsTarget
import io.embodit.spatialoffice.platform.Session
import io.embodit.spatialoffice.platform.DocSession
import io.embodit.spatialoffice.platform.SheetSession
import io.embodit.spatialoffice.platform.Win
import io.embodit.spatialoffice.platform.approx
import io.embodit.spatialoffice.platform.held

/** Dialogs and sheets every window can show; each renders only in the window that owns the current overlay. */
@Composable
fun SharedOverlays(graph: Graph, win: Win, tag: String? = null) {
    val o = graph.overlay ?: return
    if (o.win != win || (o.tag != null && tag != null && o.tag != tag)) return
    val s = graph.session(win)
    when (o) {
        is Overlay.Unsaved -> if (s != null) UnsavedDialog(graph, s)
        is Overlay.SaveFirst -> if (s != null) SaveFirstSheet(graph, win, s)
        is Overlay.SaveAs -> if (s != null) SaveAsSheet(graph, win, s)
        is Overlay.Shortcuts -> ShortcutsSheet(graph)
        is Overlay.Compat -> if (s != null) CompatPanel(graph, s)
        is Overlay.Find -> if (s != null) FindSheet(graph, s)
        is Overlay.Recover -> if (s != null) RecoverDialog(graph, s)
        is Overlay.History -> if (s != null) HistorySheet(graph, s)
        is Overlay.SheetRename -> (s as? SheetSession)?.let { SheetRenameSheet(graph, it) }
        is Overlay.SheetDelete -> (s as? SheetSession)?.let { SheetDeleteDialog(graph, it) }
        is Overlay.WordCount -> (s as? DocSession)?.let { WordCountDialog(graph, it) }
        is Overlay.FilterData -> (s as? SheetSession)?.let { FilterSheet(graph, it, o.col) }
        is Overlay.SortData -> (s as? SheetSession)?.let { SortSheet(graph, it) }
        is Overlay.Outline -> (s as? DocSession)?.let { OutlineSheet(graph, it) }
        is Overlay.InsertTable -> (s as? DocSession)?.let { InsertTableDialog(graph, it) }
        is Overlay.Link -> (s as? DocSession)?.let { LinkSheet(graph, it, o) }
        is Overlay.ImagePick -> ImagePickSheet(graph, win)
        is Overlay.HeaderFooter -> (s as? DocSession)?.let { HeaderFooterSheet(graph, it) }
        is Overlay.SlideFooter -> (s as? io.embodit.spatialoffice.platform.DeckSession)?.let { SlideFooterSheet(graph, it) }
        is Overlay.SignOut -> SignOutDialog(graph, o.provider)
        is Overlay.ChartView -> (s as? SheetSession)?.let { ChartSheet(graph, it, o.index) }
        else -> {}
    }
}

@Composable
private fun UnsavedDialog(graph: Graph, s: Session) {
    AlertDialog(
        onDismissRequest = { graph.answerUnsaved("cancel") },
        title = { Txt(tr(R.string.unsaved_title, s.ref.name), "unsaved/title", style = PicoTheme.typography.headlineSmall, weight = FontWeight.Bold, maxLines = 3) },
        content = { Txt(tr(R.string.unsaved_msg), "unsaved/msg", style = PicoTheme.typography.bodyLarge, maxLines = 3) },
        buttons = {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.End)) {
                SecondaryButton(tr(R.string.cancel), "unsaved/cancel", onClick = { graph.answerUnsaved("cancel") })
                SecondaryButton(tr(R.string.discard), "unsaved/discard", onClick = { graph.answerUnsaved("discard") })
                PrimaryButton(tr(R.string.save), "unsaved/save", onClick = { graph.answerUnsaved("save") })
            }
        },
    )
}

/** First save of an existing file (PRD §7): what is written, what stays untouched, what never enters the file, and where the backup goes. */
@Composable
private fun SaveFirstSheet(graph: Graph, win: Win, s: Session) {
    val zh = graph.settings.value.zh
    val (backup, beside) = remember(s.ref.key, zh) { graph.storage.backupTarget(s.ref, zh) }
    Sheet(
        modifier = Modifier.width(680.dp),
        onDismissRequest = { graph.overlay = null },
        leadingAction = { SheetClose { graph.overlay = null } },
        title = { Txt(tr(R.string.save_first_title, s.ref.name), "savefirst/title", style = PicoTheme.typography.titleLarge, weight = FontWeight.Bold, maxLines = 2) },
        bottom = {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.End)) {
                SecondaryButton(tr(R.string.save_copy), "savefirst/copy", onClick = { graph.saveAs(win, SaveAsTarget.COPY) })
                PrimaryButton(tr(R.string.backup_and_save), "savefirst/go", onClick = { graph.confirmFirstSave(win) })
            }
        },
    ) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Txt(tr(R.string.save_first_lead), "savefirst/lead", style = PicoTheme.typography.bodyLarge, maxLines = 3)
            FateRow(R.drawable.ic_check_circle, tr(R.string.sf_changed, s.changedCount().coerceAtLeast(1)), tr(R.string.sf_changed_sub), tr(R.string.tag_written))
            FateRow(R.drawable.ic_lock, tr(R.string.sf_rest), tr(R.string.sf_rest_sub), tr(R.string.tag_untouched))
            FateRow(R.drawable.ic_spread, tr(R.string.sf_layout), tr(R.string.sf_layout_sub), tr(R.string.tag_notinfile))
            ListItem(
                headlineContent = { Txt(tr(R.string.backup_to), "savefirst/backup-label", style = PicoTheme.typography.titleMedium) },
                supportingContent = {
                    Txt(if (beside) graph.storage.display(backup) else tr(R.string.backup_private, backup.name), "savefirst/backup-path", style = PicoTheme.typography.bodyMedium, color = PicoTheme.colorScheme.labelSecondary, maxLines = 3)
                },
                leadingContent = { Icon(painter = painterResource(R.drawable.ic_folder), contentDescription = null, modifier = Modifier.size(24.dp)) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun FateRow(icon: Int, head: String, sub: String, tag: String) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(painter = painterResource(icon), contentDescription = null, modifier = Modifier.size(22.dp))
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Txt(head, "fate/head", style = PicoTheme.typography.titleMedium, maxLines = 2)
            Txt(sub, "fate/sub", style = PicoTheme.typography.bodyMedium, color = PicoTheme.colorScheme.labelSecondary, maxLines = 3)
        }
        Tag(tag, "fate")
    }
}

/**
 * Save as (PRD §7): pick one target; the file being edited is never touched. The rows are [Exporter.rows]: the local
 * targets, and a copy to a cloud drive only while one is signed in (v0.2.7, QA #7 q7-1: the dialog always drew a grey
 * "Save to a cloud drive · connect one under Accounts & drives first" row that did nothing, in a build without an
 * Accounts page). Every row that is drawn can be picked.
 */
@Composable
private fun SaveAsSheet(graph: Graph, win: Win, s: Session) {
    val zh = graph.settings.value.zh
    val drive = graph.accounts.saveAsDrive
    val rows = remember(s.kind, drive) { Exporter.rows(s, drive) }
    var choice by remember { mutableStateOf(rows.first()) }
    // signed out while the dialog is open: the cloud row goes, and so does a pick of it
    val picked = choice.takeIf { it in rows } ?: rows.first()
    val dest = remember(picked, s.ref.key, graph.filesVersion) { Exporter.destination(graph, s, picked, zh) }
    LaunchedEffect(rows) { android.util.Log.i(io.embodit.spatialoffice.platform.TAG, "SAVEAS rows=${rows.joinToString(",")} drive=${drive?.id ?: "none"} offered=${graph.accounts.shown.map { it.id }}") }
    Sheet(
        modifier = Modifier.width(680.dp),
        onDismissRequest = { graph.overlay = null },
        leadingAction = { SheetClose { graph.overlay = null } },
        title = { Txt(tr(R.string.saveas_title, s.ref.name), "saveas/title", style = PicoTheme.typography.titleLarge, weight = FontWeight.Bold, maxLines = 2) },
        bottom = {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Txt(tr(R.string.saveas_note), "saveas/note", Modifier.weight(1f), style = PicoTheme.typography.bodyMedium, color = PicoTheme.colorScheme.labelSecondary, maxLines = 2)
                PrimaryButton(tr(R.string.saveas_go), "saveas/go", onClick = { graph.saveAs(win, picked) })
            }
        },
    ) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Txt(tr(R.string.saveas_label), "saveas/label", style = PicoTheme.typography.labelLarge, color = PicoTheme.colorScheme.labelSecondary)
            rows.forEach { t ->
                val (head, sub) = when (t) {
                    SaveAsTarget.COPY -> tr(R.string.sa_copy, s.ref.name.substringAfterLast('.')) to tr(R.string.sa_copy_sub)
                    SaveAsTarget.PDF -> tr(R.string.sa_pdf) to tr(R.string.sa_pdf_sub)
                    SaveAsTarget.CSV -> tr(R.string.sa_csv) to tr(R.string.sa_csv_sub)
                    SaveAsTarget.TXT -> tr(R.string.sa_txt) to tr(R.string.sa_txt_sub)
                    SaveAsTarget.PNG -> tr(R.string.sa_png) to tr(R.string.sa_png_sub)
                    // in [rows] only with a drive
                    SaveAsTarget.CLOUD -> tr(R.string.sa_cloud_to, drive?.let { tr(io.embodit.spatialoffice.platform.Accounts.nameRes(it)) } ?: "") to tr(R.string.sa_cloud_to_sub)
                }
                val on = t == picked
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 64.dp).clip(RoundedCornerShape(16.dp))
                        .background(if (on) PicoTheme.colorScheme.fillSecondary else PicoTheme.colorScheme.fillLight)
                        .noKeyFocus().clickable { choice = t }.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (t == SaveAsTarget.CLOUD) Icon(painter = painterResource(R.drawable.ic_cloud), contentDescription = null, modifier = Modifier.size(20.dp), tint = if (on) PicoTheme.colorScheme.interaction else PicoTheme.colorScheme.labelPrimary)
                    else Box(Modifier.size(20.dp).clip(RoundedCornerShape(10.dp)).background(if (on) PicoTheme.colorScheme.interaction else PicoTheme.colorScheme.fillTertiary))
                    Column(Modifier.weight(1f).padding(start = 14.dp)) {
                        Txt(head, if (t == SaveAsTarget.CLOUD) "saveas/cloud" else "saveas/target", style = PicoTheme.typography.titleMedium, maxLines = 2)
                        Txt(sub, if (t == SaveAsTarget.CLOUD) "saveas/cloud-sub" else "saveas/target-sub", style = PicoTheme.typography.bodyMedium, color = PicoTheme.colorScheme.labelSecondary, maxLines = 2)
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Icon(painter = painterResource(R.drawable.ic_folder), contentDescription = null, modifier = Modifier.size(22.dp))
                Column(Modifier.padding(start = 12.dp)) {
                    Txt(tr(R.string.save_to), "saveas/dest-label", style = PicoTheme.typography.titleMedium)
                    Txt(if (picked == SaveAsTarget.CLOUD) tr(R.string.sa_cloud_dest, s.ref.name) else graph.storage.display(dest), "saveas/dest", style = PicoTheme.typography.bodyMedium, color = PicoTheme.colorScheme.labelSecondary, maxLines = 3)
                }
            }
        }
    }
}

@Composable
private fun ShortcutsSheet(graph: Graph) {
    val rows = listOf(
        R.string.key_save to "Ctrl+S",
        R.string.key_undo to "Ctrl+Z / Ctrl+Y",
        R.string.key_bold to "Ctrl+B / Ctrl+I / Ctrl+U",
        R.string.key_move to "← ↑ → ↓ · Tab",
        R.string.key_edit to "F2 · Enter · Esc",
        R.string.key_sum to "Alt+=",
        // M5: comment (all three kinds); track my edits and the format painter (documents)
        R.string.key_comment to "Ctrl+Alt+M",
        R.string.key_track to "Ctrl+Shift+E · Ctrl+Shift+C",
        R.string.key_slide to "PageUp / PageDown",
        R.string.key_focus to "Ctrl+Alt+← / →",
        R.string.key_arr to "Ctrl+Alt+1 / 2 / 3",
        R.string.key_collapse to "Esc",
    )
    Sheet(
        modifier = Modifier.width(560.dp),
        onDismissRequest = { graph.overlay = null },
        leadingAction = { SheetClose { graph.overlay = null } },
        title = { Txt(tr(R.string.shortcuts), "keys/title", style = PicoTheme.typography.titleLarge, weight = FontWeight.Bold) },
    ) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            rows.forEach { (label, keys) ->
                Row(Modifier.fillMaxWidth().heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
                    Txt(tr(label), "keys/label", Modifier.weight(1f).padding(end = 12.dp), style = PicoTheme.typography.bodyLarge, maxLines = 2)
                    Tag(keys, "keys")
                }
            }
        }
    }
}

/**
 * "Compatibility": what is shown approximately or read-only here. Nothing in the file is ever dropped.
 * v0.2.2 (QA #2 q2-1): a Sheet over the editor window, like Save as. The Subwindow it used to be hung off the
 * window's right edge, 36°–48° to the right of the gaze, mostly out of view (WindowFit.compatSheet / WindowFitTest).
 */
@Composable
private fun CompatPanel(graph: Graph, s: Session) {
    val notes = remember(s.version) { s.compat() }
    Sheet(
        modifier = Modifier.width(680.dp),
        onDismissRequest = { graph.overlay = null },
        leadingAction = { SheetClose { graph.overlay = null } },
        title = { Txt(tr(R.string.compat_title), "compat/title", style = PicoTheme.typography.titleLarge, weight = FontWeight.Bold) },
        bottom = {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                PrimaryButton(tr(R.string.done), "compat/done", onClick = { graph.overlay = null })
            }
        },
    ) {
        Column(Modifier.fillMaxWidth()) {
            Txt(tr(R.string.compat_src, s.ref.name), "compat/src", style = PicoTheme.typography.bodyMedium, color = PicoTheme.colorScheme.labelSecondary, maxLines = 3)
            Txt(
                if (notes.isEmpty()) tr(R.string.compat_none) else tr(R.string.compat_sum, notes.approx(), notes.held()),
                "compat/sum", Modifier.padding(top = 4.dp, bottom = 12.dp), style = PicoTheme.typography.titleMedium, maxLines = 3,
            )
            Column(Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                notes.forEach { n -> CompatRow(n, s.kind) }
            }
        }
    }
}

/** The close button of every sheet, labelled in the app language (the built-in one reads the system language). */
@Composable
fun SheetClose(onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.noKeyFocus().size(48.dp)) {
        Icon(painter = painterResource(R.drawable.ic_cross), contentDescription = tr(R.string.close), modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun CompatRow(n: CompatNote, kind: FileKind) {
    val (head, sub) = when {
        n.code == "columns" -> tr(R.string.c_columns) to tr(R.string.c_columns_s)
        n.code == "text-box" -> tr(R.string.c_text_box) to tr(R.string.c_text_box_s)
        n.code.startsWith("font") -> tr(R.string.c_font) to tr(R.string.c_font_s)
        n.code == "smart-graphic" -> tr(R.string.c_smart, n.count) to tr(R.string.c_smart_s)
        n.code == "tracked-changes" -> tr(R.string.c_tracked, n.count) to tr(R.string.c_tracked_s)
        n.code == "comments" -> tr(R.string.c_comments, n.count) to tr(R.string.c_comments_s)
        n.code == "chart" && kind.editor != FileKind.SHEET -> tr(R.string.c_chart, n.count) to tr(R.string.c_chart_s)
        n.code == "table" -> tr(R.string.c_table, n.count) to tr(R.string.c_table_s)
        else -> tr(R.string.c_other, n.count) + " · " + n.code to tr(R.string.c_other_s)
    }
    Column(Modifier.fillMaxWidth()) {
        Tag(tr(if (n.kind == CompatKind.APPROX) R.string.k_approx else R.string.k_hold), "compat")
        Txt(head, "compat/head", Modifier.padding(top = 4.dp), style = PicoTheme.typography.titleMedium, maxLines = 3)
        Txt(sub, "compat/sub", style = PicoTheme.typography.bodyMedium, color = PicoTheme.colorScheme.labelSecondary, maxLines = 4)
    }
}

/** What the find sheet remembers between openings (per process), so [Find next] after closing it goes on from there. */
object FindMemo {
    var query by mutableStateOf("")
    var replacement by mutableStateOf("")
    var matchCase by mutableStateOf(false)
    var allSheets by mutableStateOf(false)
}

/**
 * v0.3.0 (d41 / s19 / p21): one find sheet for documents, spreadsheets and presentations. [Previous] / [Next] jump to a
 * match and highlight it (page scrolled to it, cell selected, slide shown with the box or the notes picked); [Replace]
 * changes only the current match, [Replace all] every one in one undo step. Match case; a spreadsheet can search the
 * current sheet or all of them.
 */
@Composable
private fun FindSheet(graph: Graph, s: Session) {
    val m = FindMemo
    var index by remember { mutableStateOf(-1) }
    val hits = remember(m.query, m.matchCase, m.allSheets, s.version) { runCatching { s.findHits(m.query, m.matchCase, m.allSheets) }.getOrDefault(emptyList()) }
    fun go(i: Int) {
        if (hits.isEmpty()) { index = -1; return }
        index = Math.floorMod(i, hits.size)
        s.showHit(hits[index])
        android.util.Log.i(io.embodit.spatialoffice.platform.TAG, "FIND ${index + 1}/${hits.size} ${hits[index]}")
    }
    // the hit list changed under the sheet (typing in the field, a replace): keep the position, drop a stale highlight
    LaunchedEffect(hits) {
        if (hits.isEmpty()) { index = -1; s.findHit = null } else if (index >= hits.size) index = hits.size - 1
        if (index >= 0 && hits.isNotEmpty()) s.findHit = hits[index]
    }
    val close = { s.findHit = null; graph.overlay = null }
    Sheet(
        modifier = Modifier.width(760.dp),
        onDismissRequest = close,
        leadingAction = { SheetClose(close) },
        title = { Txt(tr(R.string.find_replace), "find/title", style = PicoTheme.typography.titleLarge, weight = FontWeight.Bold) },
        bottom = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Txt(
                        when { m.query.isEmpty() -> ""; hits.isEmpty() -> tr(R.string.find_none); index < 0 -> tr(R.string.find_hits, hits.size); else -> tr(R.string.find_pos, index + 1, hits.size) },
                        "find/hits", Modifier.weight(1f), style = PicoTheme.typography.bodyLarge, color = PicoTheme.colorScheme.labelSecondary,
                    )
                    SecondaryButton(tr(R.string.find_prev), "find/prev", enabled = hits.isNotEmpty(), onClick = { go(if (index < 0) hits.size - 1 else index - 1) })
                    SecondaryButton(tr(R.string.find_next), "find/next", enabled = hits.isNotEmpty(), onClick = { go(index + 1) })
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.End)) {
                    SecondaryButton(tr(R.string.replace_one), "find/replace-one", enabled = hits.isNotEmpty() && !s.readOnly, onClick = {
                        if (index < 0) { go(0); return@SecondaryButton }
                        val h = hits[index]
                        if (s.replaceHit(h, m.query, m.replacement, m.matchCase)) {
                            graph.say(s, graph.str(R.string.replaced_one))
                            // the next match is now at the same position in the list (this one is gone, or later when the replacement still matches)
                            val left = s.findHits(m.query, m.matchCase, m.allSheets)
                            val next = left.indexOfFirst { after(it, h) }.let { if (it < 0) 0 else it }
                            if (left.isNotEmpty()) { index = next; s.showHit(left[next]) } else { index = -1; s.findHit = null }
                        }
                    })
                    PrimaryButton(tr(R.string.replace_all), "find/replace", enabled = hits.isNotEmpty() && !s.readOnly, onClick = {
                        val n = s.replaceAllHits(m.query, m.replacement, m.matchCase, m.allSheets)
                        index = -1; s.findHit = null
                        graph.say(s, graph.str(R.string.replaced_n, n), graph.str(R.string.undo_short)) { s.undo() }
                    })
                }
            }
        },
    ) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            LabeledField(tr(R.string.find_label), m.query, "find/find") { m.query = it; index = -1 }
            LabeledField(tr(R.string.replace_label), m.replacement, "find/replace-with") { m.replacement = it }
            // the sheet stands in front of the page: the current hit is shown here too, marked, with what is around it
            val cur = hits.getOrNull(index)
            val text = cur?.let { runCatching { s.hitText(it) }.getOrNull() }
            if (cur != null && text != null && cur.start + cur.len <= text.length) {
                val from = (cur.start - 28).coerceAtLeast(0); val to = (cur.start + cur.len + 28).coerceAtMost(text.length)
                val place = if (s is io.embodit.spatialoffice.platform.DeckSession) tr(R.string.find_slide_n, cur.a + 1) + (if (cur.b < 0) " · " + tr(R.string.notes_short) else "") else s.hitPlace(cur)
                val lead = (place?.let { "$it  " } ?: "") + (if (from > 0) "…" else "")
                val shown = lead + text.substring(from, to).replace('\n', ' ').replace(io.embodit.spatialoffice.platform.SOFT_BREAK, ' ') + (if (to < text.length) "…" else "")
                val at = lead.length + cur.start - from
                com.pico.spatial.ui.design.Text(
                    io.embodit.spatialoffice.ui.doc.underlined(shown, at, cur.len, PicoTheme.colorScheme.labelPrimary),
                    style = PicoTheme.typography.bodyLarge, color = PicoTheme.colorScheme.labelPrimary, maxLines = 2,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(PicoTheme.colorScheme.fillSecondary)
                        .padding(horizontal = 16.dp, vertical = 12.dp).semantics { contentDescription = "find/preview" },
                )
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                CheckRow(tr(R.string.match_case), m.matchCase, "find/case") { m.matchCase = it; index = -1 }
                if (s is SheetSession) Segments(
                    listOf(false to tr(R.string.find_this_sheet), true to tr(R.string.find_all_sheets)), m.allSheets, "find/scope", itemWidth = 150.dp,
                ) { m.allSheets = it; index = -1 }
            }
        }
    }
}

/** True when [h] comes after [ref] in reading order (same text: a later offset). */
private fun after(h: io.embodit.spatialoffice.platform.FindHit, ref: io.embodit.spatialoffice.platform.FindHit): Boolean =
    compareValuesBy(h, ref, { it.a }, { it.b }, { it.c }, { it.start }) >= 0

/** A whole row that toggles (the bare switch is far below 48 dp). */
@Composable
fun CheckRow(label: String, on: Boolean, tag: String, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.heightIn(min = 56.dp).clip(RoundedCornerShape(16.dp))
            .noKeyFocus().toggleable(value = on, role = androidx.compose.ui.semantics.Role.Switch) { onChange(it) }.padding(horizontal = 12.dp)
            .semantics { contentDescription = tag },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Txt(label, "$tag/label", Modifier.padding(end = 12.dp), style = PicoTheme.typography.bodyLarge)
        com.pico.spatial.ui.design.Switch(checked = on, onCheckedChange = null)
    }
}

/** d27, the existing confirm dialog: the file has changes from an autosaved draft that were never saved. */
@Composable
private fun RecoverDialog(graph: Graph, s: Session) {
    AlertDialog(
        onDismissRequest = { graph.overlay = null },
        title = { Txt(tr(R.string.recover_title), "recover/title", style = PicoTheme.typography.headlineSmall, weight = FontWeight.Bold, maxLines = 2) },
        content = { Txt(tr(R.string.recover_msg, s.ref.name), "recover/msg", style = PicoTheme.typography.bodyLarge, maxLines = 4) },
        buttons = {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.End)) {
                SecondaryButton(tr(R.string.recover_no), "recover/no", onClick = { graph.answerRecover(false) })
                PrimaryButton(tr(R.string.recover_yes), "recover/yes", onClick = { graph.answerRecover(true) })
            }
        },
    )
}

/**
 * d28: the file's saved versions, newest first (time, kind, size), each with [Restore]. Restoring keeps what the window
 * holds now as a version first, then brings the old one back as an unsaved change (one undo step).
 */
@Composable
private fun HistorySheet(graph: Graph, s: Session) {
    val list = remember(s.ref.key, graph.historyVersion) { graph.versions(s) }
    val fmt = remember { java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.ROOT) }
    LaunchedEffect(list.size) { android.util.Log.i(io.embodit.spatialoffice.platform.TAG, "HISTORY list ${s.ref.name} n=${list.size} kinds=${list.joinToString(",") { it.kind }}") }
    Sheet(
        modifier = Modifier.width(680.dp),
        onDismissRequest = { graph.overlay = null },
        leadingAction = { SheetClose { graph.overlay = null } },
        title = { Txt(tr(R.string.hist_title, s.ref.name), "hist/title", style = PicoTheme.typography.titleLarge, weight = FontWeight.Bold, maxLines = 2) },
        bottom = {
            Txt(tr(R.string.hist_note, io.embodit.spatialoffice.platform.History.KEEP), "hist/note", Modifier.fillMaxWidth(), style = PicoTheme.typography.bodyMedium, color = PicoTheme.colorScheme.labelSecondary, maxLines = 2)
        },
    ) {
        Column(Modifier.fillMaxWidth().heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (list.isEmpty()) Txt(tr(R.string.hist_empty), "hist/empty", Modifier.fillMaxWidth().padding(vertical = 24.dp), style = PicoTheme.typography.bodyLarge, maxLines = 3)
            list.forEachIndexed { i, v ->
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 64.dp).clip(RoundedCornerShape(16.dp)).background(PicoTheme.colorScheme.fillLight).padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Txt(fmt.format(java.util.Date(v.at)) + (if (i == 0) "  " + tr(R.string.hist_newest) else ""), "hist/time", style = PicoTheme.typography.titleMedium, maxLines = 1)
                        Txt(tr(if (v.kind == io.embodit.spatialoffice.platform.History.KIND_BEFORE_RESTORE) R.string.hist_kind_restore else R.string.hist_kind_save, sizeLabel(v.size)), "hist/kind", style = PicoTheme.typography.bodyMedium, color = PicoTheme.colorScheme.labelSecondary, maxLines = 1)
                    }
                    SecondaryButton(tr(R.string.hist_restore), "hist/restore", onClick = { graph.overlay = null; graph.restoreVersion(s, v) })
                }
            }
        }
    }
}

private fun sizeLabel(b: Long): String = if (b < 1024) "$b B" else if (b < 1024 * 1024) "${(b + 512) / 1024} KB" else String.format(java.util.Locale.ROOT, "%.1f MB", b / 1048576.0)

@Composable
private fun SheetRenameSheet(graph: Graph, s: SheetSession) {
    var name by remember(s.sheet) { mutableStateOf(s.book.sheetNames.getOrElse(s.sheet) { "" }) }
    var why by remember { mutableStateOf<String?>(null) }
    val done = {
        val r = s.renameSheet(name)
        if (r == null) { graph.overlay = null; graph.say(s, graph.str(R.string.sheet_renamed, name.trim())) } else why = r
    }
    Sheet(
        modifier = Modifier.width(620.dp),
        onDismissRequest = { graph.overlay = null },
        leadingAction = { SheetClose { graph.overlay = null } },
        title = { Txt(tr(R.string.sheet_rename), "sheetname/title", style = PicoTheme.typography.titleLarge, weight = FontWeight.Bold) },
        bottom = {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Txt(
                    when (why) { null -> ""; "empty" -> tr(R.string.sn_empty); "long" -> tr(R.string.sn_long); "char" -> tr(R.string.sn_char); "taken" -> tr(R.string.sn_taken); else -> tr(R.string.sn_char) },
                    "sheetname/why", Modifier.weight(1f), style = PicoTheme.typography.bodyMedium, color = PicoTheme.colorScheme.error, maxLines = 2,
                )
                SecondaryButton(tr(R.string.cancel), "sheetname/cancel", onClick = { graph.overlay = null })
                PrimaryButton(tr(R.string.ok), "sheetname/ok", onClick = done)
            }
        },
    ) {
        LabeledField(tr(R.string.sheet_name), name, "sheetname/name") { name = it; why = null }
    }
}

@Composable
private fun SheetDeleteDialog(graph: Graph, s: SheetSession) {
    val name = s.book.sheetNames.getOrElse(s.sheet) { "" }
    AlertDialog(
        onDismissRequest = { graph.overlay = null },
        title = { Txt(tr(R.string.sheet_delete_title, name), "sheetdel/title", style = PicoTheme.typography.headlineSmall, weight = FontWeight.Bold, maxLines = 2) },
        content = { Txt(tr(R.string.sheet_delete_msg), "sheetdel/msg", style = PicoTheme.typography.bodyLarge, maxLines = 4) },
        buttons = {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.End)) {
                SecondaryButton(tr(R.string.cancel), "sheetdel/cancel", onClick = { graph.overlay = null })
                SecondaryButton(tr(R.string.delete), "sheetdel/ok", danger = true, onClick = {
                    graph.overlay = null
                    if (s.deleteSheet()) graph.say(s, graph.str(R.string.sheet_deleted, name), graph.str(R.string.undo_short)) { s.undo() }
                })
            }
        },
    )
}

@Composable
fun LabeledField(label: String, value: String, tag: String, keyboard: androidx.compose.foundation.text.KeyboardOptions = androidx.compose.foundation.text.KeyboardOptions.Default, onChange: (String) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Txt(label, "$tag/label", style = PicoTheme.typography.labelLarge, color = PicoTheme.colorScheme.labelSecondary)
        Spacer(Modifier.height(6.dp))
        androidx.compose.foundation.text.BasicTextField(
            value = value, onValueChange = onChange, singleLine = true, keyboardOptions = keyboard,
            textStyle = androidx.compose.ui.text.TextStyle(fontSize = androidx.compose.ui.unit.TextUnit(18f, androidx.compose.ui.unit.TextUnitType.Sp), color = PicoTheme.colorScheme.labelPrimary),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(PicoTheme.colorScheme.interaction),
            modifier = Modifier.typingTarget().fillMaxWidth().heightIn(min = 56.dp).clip(RoundedCornerShape(14.dp)).background(PicoTheme.colorScheme.fillSecondary)
                .padding(horizontal = 16.dp, vertical = 16.dp).androidSemantics(tag),
        )
    }
}

private fun Modifier.androidSemantics(tag: String): Modifier = this.then(Modifier.semantics { contentDescription = tag })

@Composable
private fun WordCountDialog(graph: Graph, s: DocSession) {
    val r = remember(s.version) { s.wordCount() }
    AlertDialog(
        onDismissRequest = { graph.overlay = null },
        title = { Txt(tr(R.string.word_count), "wc/title", style = PicoTheme.typography.headlineSmall, weight = FontWeight.Bold) },
        content = {
            // gate:allow button-gap four lines of counts, no buttons
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Txt(tr(R.string.wc_words, r.words), "wc/words", style = PicoTheme.typography.bodyLarge)
                Txt(tr(R.string.wc_chars, r.charsNoSpaces), "wc/chars", style = PicoTheme.typography.bodyLarge)
                Txt(tr(R.string.wc_chars_sp, r.chars), "wc/chars-sp", style = PicoTheme.typography.bodyLarge)
                Txt(tr(R.string.wc_paras, r.paragraphs), "wc/paras", style = PicoTheme.typography.bodyLarge)
            }
        },
        buttons = { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { PrimaryButton(tr(R.string.done), "wc/done", onClick = { graph.overlay = null }) } },
    )
}

@Composable
private fun InsertTableDialog(graph: Graph, s: DocSession) {
    var rows by remember { mutableStateOf(3) }
    var cols by remember { mutableStateOf(3) }
    AlertDialog(
        onDismissRequest = { graph.overlay = null },
        title = { Txt(tr(R.string.ins_table).trimEnd('…', '.'), "table/title", style = PicoTheme.typography.headlineSmall, weight = FontWeight.Bold) },
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Stepper(tr(R.string.rows_n, rows), "table/rows", { rows = (rows - 1).coerceAtLeast(1) }, { rows = (rows + 1).coerceAtMost(30) })
                Stepper(tr(R.string.cols_n, cols), "table/cols", { cols = (cols - 1).coerceAtLeast(1) }, { cols = (cols + 1).coerceAtMost(8) })
            }
        },
        buttons = {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.End)) {
                SecondaryButton(tr(R.string.cancel), "table/cancel", onClick = { graph.overlay = null })
                PrimaryButton(tr(R.string.insert), "table/insert", onClick = { graph.overlay = null; io.embodit.spatialoffice.ui.doc.docInsertTable(s, rows, cols) })
            }
        },
    )
}

/**
 * M3 d20: add or edit a link. The address is required; with nothing selected the text to show is typed in at the
 * caret (the address itself when left blank). An existing link offers [Remove link].
 */
/** v0.5.1 (QA #13 q13-2): addresses: the URI keyboard, no automatic capital ("Https://"), no suggestions. */
val UrlKeyboard = androidx.compose.foundation.text.KeyboardOptions(
    capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.None,
    autoCorrect = false,
    keyboardType = androidx.compose.ui.text.input.KeyboardType.Uri,
)

@Composable
private fun LinkSheet(graph: Graph, s: DocSession, o: Overlay.Link) {
    var url by remember(o) { mutableStateOf(o.url) }
    var text by remember(o) { mutableStateOf(o.text) }
    var why by remember(o) { mutableStateOf(false) }
    val typeText = o.b <= o.a
    val done: () -> Unit = {
        if (url.isBlank()) why = true
        else {
            graph.overlay = null
            if (typeText) s.insertLink(o.para, o.a, text, url) else s.setLink(o.para, o.a, o.b, url)
        }
    }
    Sheet(
        modifier = Modifier.width(640.dp),
        onDismissRequest = { graph.overlay = null },
        leadingAction = { SheetClose { graph.overlay = null } },
        title = { Txt(tr(if (o.existing) R.string.link_edit_title else R.string.link_title), "link/title", style = PicoTheme.typography.titleLarge, weight = FontWeight.Bold) },
        bottom = {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Txt(if (why) tr(R.string.link_need_url) else "", "link/why", Modifier.weight(1f), style = PicoTheme.typography.bodyMedium, color = PicoTheme.colorScheme.error, maxLines = 2)
                if (o.existing) SecondaryButton(tr(R.string.link_remove), "link/remove", onClick = { graph.overlay = null; s.setLink(o.para, o.a, o.b, null) })
                SecondaryButton(tr(R.string.cancel), "link/cancel", onClick = { graph.overlay = null })
                PrimaryButton(tr(R.string.ok), "link/ok", onClick = done)
            }
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            LabeledField(tr(R.string.link_url), url, "link/url", UrlKeyboard) { url = it; why = false }
            if (typeText) LabeledField(tr(R.string.link_text), text, "link/text") { text = it }
            else Txt(o.text, "link/shown", style = PicoTheme.typography.bodyLarge, color = PicoTheme.colorScheme.labelSecondary, maxLines = 3)
        }
    }
}

@Composable
private fun Stepper(label: String, tag: String, minus: () -> Unit, plus: () -> Unit) {
    Row(Modifier.width(420.dp).heightIn(min = 64.dp), verticalAlignment = Alignment.CenterVertically) {
        Txt(label, "$tag/label", Modifier.weight(1f), style = PicoTheme.typography.titleMedium)
        SecondaryButton("−", "$tag/minus", onClick = minus)
        Spacer(Modifier.width(12.dp))
        SecondaryButton("+", "$tag/plus", onClick = plus)
    }
}

@Composable
private fun ImagePickSheet(graph: Graph, win: Win) {
    val files = remember(graph.filesVersion) { graph.imageFiles() }
    Sheet(
        modifier = Modifier.width(720.dp),
        onDismissRequest = { graph.overlay = null },
        leadingAction = { SheetClose { graph.overlay = null } },
        title = { Txt(tr(R.string.pick_picture), "img/title", style = PicoTheme.typography.titleLarge, weight = FontWeight.Bold) },
        bottom = {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Txt(tr(R.string.pick_picture_note), "img/note", Modifier.weight(1f), style = PicoTheme.typography.bodyMedium, color = PicoTheme.colorScheme.labelSecondary, maxLines = 2)
                SecondaryButton(tr(R.string.pick_system), "img/system", onClick = {
                    graph.overlay = null
                    graph.app.startActivity(android.content.Intent(graph.app, io.embodit.spatialoffice.platform.PickerActivity::class.java)
                        .putExtra(io.embodit.spatialoffice.platform.PickerActivity.EXTRA_IMAGE, win.name).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                })
            }
        },
    ) {
        Column(Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (files.isEmpty()) Txt(tr(R.string.pick_picture_none), "img/none", style = PicoTheme.typography.bodyLarge, maxLines = 3)
            files.forEach { f ->
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 64.dp).clip(RoundedCornerShape(14.dp)).background(PicoTheme.colorScheme.fillLight)
                        .noKeyFocus().clickable { graph.imageChosen(win, io.embodit.spatialoffice.platform.FileRef.Path(f)) }.padding(horizontal = 16.dp, vertical = 8.dp)
                        .semantics { contentDescription = "img-" + f.name },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(painter = painterResource(R.drawable.ic_image_unavailable), contentDescription = null, modifier = Modifier.size(22.dp))
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Txt(f.name, "img/name", style = PicoTheme.typography.titleMedium, maxLines = 2)
                        Txt(graph.storage.display(f), "img/path", style = PicoTheme.typography.bodySmall, color = PicoTheme.colorScheme.labelSecondary, maxLines = 2)
                    }
                }
            }
        }
    }
}

@Composable
private fun SignOutDialog(graph: Graph, p: io.embodit.spatialoffice.core.cloud.Provider) {
    val name = tr(io.embodit.spatialoffice.platform.Accounts.nameRes(p))
    AlertDialog(
        onDismissRequest = { graph.overlay = null },
        title = { Txt(tr(R.string.signout_title, name), "signout/title", style = PicoTheme.typography.headlineSmall, weight = FontWeight.Bold, maxLines = 2) },
        content = { Txt(tr(R.string.signout_msg), "signout/msg", style = PicoTheme.typography.bodyLarge, maxLines = 4) },
        buttons = {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.End)) {
                SecondaryButton(tr(R.string.cancel), "signout/cancel", onClick = { graph.overlay = null })
                SecondaryButton(tr(R.string.acct_signout), "signout/go", danger = true, onClick = { graph.overlay = null; graph.accounts.signOut(p) })
            }
        },
    )
}

@Composable
private fun ChartSheet(graph: Graph, s: SheetSession, index: Int) {
    val charts = remember(s.version) { runCatching { s.book.charts() }.getOrDefault(emptyList()) }
    if (charts.isEmpty()) { graph.overlay = null; return }
    var i by remember(index, charts.size) { mutableStateOf(if (index in charts.indices) index else charts.lastIndex) }
    val c = charts[i.coerceIn(0, charts.lastIndex)]
    Sheet(
        modifier = Modifier.width(860.dp),
        onDismissRequest = { graph.overlay = null },
        leadingAction = { SheetClose { graph.overlay = null } },
        title = { Txt(c.title.ifEmpty { tr(R.string.chart_untitled) } + " · " + tr(chartTypeName(c.type)), "chartview/title", style = PicoTheme.typography.titleLarge, weight = FontWeight.Bold, maxLines = 2) },
        bottom = {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Txt(tr(R.string.chart_n_of, i + 1, charts.size), "chartview/count", Modifier.weight(1f), style = PicoTheme.typography.bodyLarge, color = PicoTheme.colorScheme.labelSecondary)
                SecondaryButton(tr(R.string.prev), "chartview/prev", enabled = i > 0, onClick = { i-- })
                SecondaryButton(tr(R.string.next), "chartview/next", enabled = i < charts.lastIndex, onClick = { i++ })
                PrimaryButton(tr(R.string.done), "chartview/done", onClick = { graph.overlay = null })
            }
        },
    ) {
        io.embodit.spatialoffice.ui.chart.FlatChart(c, Modifier.fillMaxWidth().height(380.dp))
    }
}

fun chartTypeName(type: String): Int = when (type) { "line" -> R.string.chart_line; "pie" -> R.string.chart_pie; "scatter" -> R.string.chart_scatter; "bar" -> R.string.chart_bar; else -> R.string.chart_other }
