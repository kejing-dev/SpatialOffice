package io.embodit.spatialoffice.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.Icon
import com.pico.spatial.ui.design.IconButton
import com.pico.spatial.ui.design.ListItem
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.windows.AlertDialog
import com.pico.spatial.ui.design.windows.Sheet
import com.pico.spatial.ui.design.windows.Subwindow
import io.embodit.spatialoffice.R
import io.embodit.spatialoffice.core.docx.CompatKind
import io.embodit.spatialoffice.core.docx.CompatNote
import io.embodit.spatialoffice.core.files.FileKind
import io.embodit.spatialoffice.platform.Exporter
import io.embodit.spatialoffice.platform.Graph
import io.embodit.spatialoffice.platform.Overlay
import io.embodit.spatialoffice.platform.SaveAsTarget
import io.embodit.spatialoffice.platform.Session
import io.embodit.spatialoffice.platform.Win
import io.embodit.spatialoffice.platform.approx
import io.embodit.spatialoffice.platform.held

/** Dialogs and sheets every window can show; each renders only in the window that owns the current overlay. */
@Composable
fun SharedOverlays(graph: Graph, win: Win) {
    val o = graph.overlay ?: return
    if (o.win != win) return
    val s = graph.session(win)
    when (o) {
        is Overlay.Unsaved -> if (s != null) UnsavedDialog(graph, s)
        is Overlay.SaveFirst -> if (s != null) SaveFirstSheet(graph, win, s)
        is Overlay.SaveAs -> if (s != null) SaveAsSheet(graph, win, s)
        is Overlay.Shortcuts -> ShortcutsSheet(graph)
        is Overlay.Compat -> if (s != null) CompatPanel(graph, s)
        else -> {}
    }
}

@Composable
private fun UnsavedDialog(graph: Graph, s: Session) {
    AlertDialog(
        onDismissRequest = { graph.answerUnsaved("cancel") },
        title = { Txt(stringResource(R.string.unsaved_title, s.ref.name), "unsaved/title", style = PicoTheme.typography.headlineSmall, weight = FontWeight.Bold, maxLines = 3) },
        content = { Txt(stringResource(R.string.unsaved_msg), "unsaved/msg", style = PicoTheme.typography.bodyLarge, maxLines = 3) },
        buttons = {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.End)) {
                SecondaryButton(stringResource(R.string.cancel), "unsaved/cancel", onClick = { graph.answerUnsaved("cancel") })
                SecondaryButton(stringResource(R.string.discard), "unsaved/discard", onClick = { graph.answerUnsaved("discard") })
                PrimaryButton(stringResource(R.string.save), "unsaved/save", onClick = { graph.answerUnsaved("save") })
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
        title = { Txt(stringResource(R.string.save_first_title, s.ref.name), "savefirst/title", style = PicoTheme.typography.titleLarge, weight = FontWeight.Bold, maxLines = 2) },
        bottom = {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.End)) {
                SecondaryButton(stringResource(R.string.save_copy), "savefirst/copy", onClick = { graph.saveAs(win, SaveAsTarget.COPY) })
                PrimaryButton(stringResource(R.string.backup_and_save), "savefirst/go", onClick = { graph.confirmFirstSave(win) })
            }
        },
    ) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Txt(stringResource(R.string.save_first_lead), "savefirst/lead", style = PicoTheme.typography.bodyLarge, maxLines = 3)
            FateRow(R.drawable.ic_check_circle, stringResource(R.string.sf_changed, s.changedCount().coerceAtLeast(1)), stringResource(R.string.sf_changed_sub), stringResource(R.string.tag_written))
            FateRow(R.drawable.ic_lock, stringResource(R.string.sf_rest), stringResource(R.string.sf_rest_sub), stringResource(R.string.tag_untouched))
            FateRow(R.drawable.ic_spread, stringResource(R.string.sf_layout), stringResource(R.string.sf_layout_sub), stringResource(R.string.tag_notinfile))
            ListItem(
                headlineContent = { Txt(stringResource(R.string.backup_to), "savefirst/backup-label", style = PicoTheme.typography.titleMedium) },
                supportingContent = {
                    Txt(if (beside) graph.storage.display(backup) else stringResource(R.string.backup_private, backup.name), "savefirst/backup-path", style = PicoTheme.typography.bodyMedium, color = PicoTheme.colorScheme.labelSecondary, maxLines = 3)
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

/** Save as (PRD §7): pick one target; the file being edited is never touched. */
@Composable
private fun SaveAsSheet(graph: Graph, win: Win, s: Session) {
    val zh = graph.settings.value.zh
    val targets = remember(s.kind) { Exporter.targets(s) }
    var picked by remember { mutableStateOf(targets.first()) }
    val dest = remember(picked, s.ref.key, graph.filesVersion) { Exporter.destination(graph, s, picked, zh) }
    Sheet(
        modifier = Modifier.width(680.dp),
        onDismissRequest = { graph.overlay = null },
        title = { Txt(stringResource(R.string.saveas_title, s.ref.name), "saveas/title", style = PicoTheme.typography.titleLarge, weight = FontWeight.Bold, maxLines = 2) },
        bottom = {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Txt(stringResource(R.string.saveas_note), "saveas/note", Modifier.weight(1f), style = PicoTheme.typography.bodyMedium, color = PicoTheme.colorScheme.labelSecondary, maxLines = 2)
                PrimaryButton(stringResource(R.string.saveas_go), "saveas/go", onClick = { graph.saveAs(win, picked) })
            }
        },
    ) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Txt(stringResource(R.string.saveas_label), "saveas/label", style = PicoTheme.typography.labelLarge, color = PicoTheme.colorScheme.labelSecondary)
            targets.forEach { t ->
                val (head, sub) = when (t) {
                    SaveAsTarget.COPY -> stringResource(R.string.sa_copy, s.ref.name.substringAfterLast('.')) to stringResource(R.string.sa_copy_sub)
                    SaveAsTarget.PDF -> stringResource(R.string.sa_pdf) to stringResource(R.string.sa_pdf_sub)
                    SaveAsTarget.CSV -> stringResource(R.string.sa_csv) to stringResource(R.string.sa_csv_sub)
                    SaveAsTarget.TXT -> stringResource(R.string.sa_txt) to stringResource(R.string.sa_txt_sub)
                    SaveAsTarget.PNG -> stringResource(R.string.sa_png) to stringResource(R.string.sa_png_sub)
                }
                val on = t == picked
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 64.dp).clip(RoundedCornerShape(16.dp))
                        .background(if (on) PicoTheme.colorScheme.fillSecondary else PicoTheme.colorScheme.fillLight)
                        .clickable { picked = t }.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(20.dp).clip(RoundedCornerShape(10.dp)).background(if (on) PicoTheme.colorScheme.interaction else PicoTheme.colorScheme.fillTertiary))
                    Column(Modifier.weight(1f).padding(start = 14.dp)) {
                        Txt(head, "saveas/target", style = PicoTheme.typography.titleMedium, maxLines = 2)
                        Txt(sub, "saveas/target-sub", style = PicoTheme.typography.bodyMedium, color = PicoTheme.colorScheme.labelSecondary, maxLines = 2)
                    }
                }
            }
            // cloud targets are listed so the place is known, but they cannot be picked in this version
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(painter = painterResource(R.drawable.ic_cloud), contentDescription = null, modifier = Modifier.size(20.dp), tint = PicoTheme.colorScheme.labelTertiary)
                Column(Modifier.weight(1f).padding(start = 14.dp)) {
                    Txt(stringResource(R.string.sa_cloud), "saveas/cloud", style = PicoTheme.typography.titleMedium, color = PicoTheme.colorScheme.labelTertiary)
                    Txt(stringResource(R.string.sa_cloud_sub), "saveas/cloud-sub", style = PicoTheme.typography.bodyMedium, color = PicoTheme.colorScheme.labelTertiary)
                }
            }
            Spacer(Modifier.height(4.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Icon(painter = painterResource(R.drawable.ic_folder), contentDescription = null, modifier = Modifier.size(22.dp))
                Column(Modifier.padding(start = 12.dp)) {
                    Txt(stringResource(R.string.save_to), "saveas/dest-label", style = PicoTheme.typography.titleMedium)
                    Txt(graph.storage.display(dest), "saveas/dest", style = PicoTheme.typography.bodyMedium, color = PicoTheme.colorScheme.labelSecondary, maxLines = 3)
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
        R.string.key_slide to "PageUp / PageDown",
        R.string.key_focus to "Ctrl+Alt+← / →",
        R.string.key_arr to "Ctrl+Alt+1 / 2 / 3",
        R.string.key_collapse to "Esc",
    )
    Sheet(
        modifier = Modifier.width(560.dp),
        onDismissRequest = { graph.overlay = null },
        title = { Txt(stringResource(R.string.shortcuts), "keys/title", style = PicoTheme.typography.titleLarge, weight = FontWeight.Bold) },
    ) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            rows.forEach { (label, keys) ->
                Row(Modifier.fillMaxWidth().heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
                    Txt(stringResource(label), "keys/label", Modifier.weight(1f).padding(end = 12.dp), style = PicoTheme.typography.bodyLarge, maxLines = 2)
                    Tag(keys, "keys")
                }
            }
        }
    }
}

/** "Compatibility": what is shown approximately or read-only here. Nothing in the file is ever dropped. */
@Composable
private fun CompatPanel(graph: Graph, s: Session) {
    val notes = remember(s.version) { s.compat() }
    Subwindow {
        Column(Modifier.fillMaxSize().padding(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 24.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { graph.overlay = null }, modifier = Modifier.size(48.dp)) {
                    Icon(painter = painterResource(R.drawable.ic_cross), contentDescription = stringResource(R.string.close), modifier = Modifier.size(20.dp))
                }
                Txt(stringResource(R.string.compat_title), "compat/title", Modifier.padding(start = 8.dp), style = PicoTheme.typography.titleLarge, weight = FontWeight.Bold)
            }
            Spacer(Modifier.height(8.dp))
            Txt(stringResource(R.string.compat_src, s.ref.name), "compat/src", style = PicoTheme.typography.bodyMedium, color = PicoTheme.colorScheme.labelSecondary, maxLines = 3)
            Txt(
                if (notes.isEmpty()) stringResource(R.string.compat_none) else stringResource(R.string.compat_sum, notes.approx(), notes.held()),
                "compat/sum", Modifier.padding(top = 4.dp, bottom = 12.dp), style = PicoTheme.typography.titleMedium, maxLines = 3,
            )
            Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                notes.forEach { n -> CompatRow(n, s.kind) }
            }
        }
    }
}

@Composable
private fun CompatRow(n: CompatNote, kind: FileKind) {
    val (head, sub) = when {
        n.code == "columns" -> stringResource(R.string.c_columns) to stringResource(R.string.c_columns_s)
        n.code == "text-box" -> stringResource(R.string.c_text_box) to stringResource(R.string.c_text_box_s)
        n.code.startsWith("font") -> stringResource(R.string.c_font) to stringResource(R.string.c_font_s)
        n.code == "smart-graphic" -> stringResource(R.string.c_smart, n.count) to stringResource(R.string.c_smart_s)
        n.code == "tracked-changes" -> stringResource(R.string.c_tracked, n.count) to stringResource(R.string.c_tracked_s)
        n.code == "comments" -> stringResource(R.string.c_comments, n.count) to stringResource(R.string.c_comments_s)
        n.code == "chart" && kind.editor != FileKind.SHEET -> stringResource(R.string.c_chart, n.count) to stringResource(R.string.c_chart_s)
        n.code == "table" -> stringResource(R.string.c_table, n.count) to stringResource(R.string.c_table_s)
        else -> stringResource(R.string.c_other, n.count) + " · " + n.code to stringResource(R.string.c_other_s)
    }
    Column(Modifier.fillMaxWidth()) {
        Tag(stringResource(if (n.kind == CompatKind.APPROX) R.string.k_approx else R.string.k_hold), "compat")
        Txt(head, "compat/head", Modifier.padding(top = 4.dp), style = PicoTheme.typography.titleMedium, maxLines = 3)
        Txt(sub, "compat/sub", style = PicoTheme.typography.bodyMedium, color = PicoTheme.colorScheme.labelSecondary, maxLines = 4)
    }
}
