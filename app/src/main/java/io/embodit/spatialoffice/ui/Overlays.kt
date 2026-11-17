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
import io.embodit.spatialoffice.platform.DocSession
import io.embodit.spatialoffice.platform.SheetSession
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
        is Overlay.Find -> (s as? DocSession)?.let { FindSheet(graph, it) }
        is Overlay.WordCount -> (s as? DocSession)?.let { WordCountDialog(graph, it) }
        is Overlay.InsertTable -> (s as? DocSession)?.let { InsertTableDialog(graph, it) }
        is Overlay.ImagePick -> ImagePickSheet(graph, win)
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
            targets.forEach { t ->
                val (head, sub) = when (t) {
                    SaveAsTarget.COPY -> tr(R.string.sa_copy, s.ref.name.substringAfterLast('.')) to tr(R.string.sa_copy_sub)
                    SaveAsTarget.PDF -> tr(R.string.sa_pdf) to tr(R.string.sa_pdf_sub)
                    SaveAsTarget.CSV -> tr(R.string.sa_csv) to tr(R.string.sa_csv_sub)
                    SaveAsTarget.TXT -> tr(R.string.sa_txt) to tr(R.string.sa_txt_sub)
                    SaveAsTarget.PNG -> tr(R.string.sa_png) to tr(R.string.sa_png_sub)
                    SaveAsTarget.CLOUD -> "" to ""
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
            // a copy to the first connected cloud drive; without a connection the row says where to connect
            val cloud = io.embodit.spatialoffice.core.cloud.Provider.values().firstOrNull { graph.accounts.connected(it) }
            val onCloud = picked == SaveAsTarget.CLOUD
            Row(
                Modifier.fillMaxWidth().heightIn(min = 64.dp).clip(RoundedCornerShape(16.dp))
                    .background(if (onCloud) PicoTheme.colorScheme.fillSecondary else PicoTheme.colorScheme.fillLight)
                    .clickable(enabled = cloud != null) { picked = SaveAsTarget.CLOUD }.padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(painter = painterResource(R.drawable.ic_cloud), contentDescription = null, modifier = Modifier.size(20.dp), tint = if (cloud != null) PicoTheme.colorScheme.labelPrimary else PicoTheme.colorScheme.labelSecondary)
                Column(Modifier.weight(1f).padding(start = 14.dp)) {
                    Txt(if (cloud != null) tr(R.string.sa_cloud_to, tr(io.embodit.spatialoffice.platform.Accounts.nameRes(cloud))) else tr(R.string.sa_cloud), "saveas/cloud", style = PicoTheme.typography.titleMedium, maxLines = 2)
                    Txt(tr(if (cloud != null) R.string.sa_cloud_to_sub else R.string.sa_cloud_sub), "saveas/cloud-sub", style = PicoTheme.typography.bodyMedium, color = PicoTheme.colorScheme.labelSecondary, maxLines = 2)
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
        R.string.key_slide to "PageUp / PageDown",
        R.string.key_focus to "Ctrl+Alt+← / →",
        R.string.key_arr to "Ctrl+Alt+1 / 2 / 3",
        R.string.key_collapse to "Esc",
    )
    Sheet(
        modifier = Modifier.width(560.dp),
        onDismissRequest = { graph.overlay = null },
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

/** "Compatibility": what is shown approximately or read-only here. Nothing in the file is ever dropped. */
@Composable
private fun CompatPanel(graph: Graph, s: Session) {
    val notes = remember(s.version) { s.compat() }
    Subwindow {
        Column(Modifier.fillMaxSize().padding(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 24.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { graph.overlay = null }, modifier = Modifier.size(48.dp)) {
                    Icon(painter = painterResource(R.drawable.ic_cross), contentDescription = tr(R.string.close), modifier = Modifier.size(20.dp))
                }
                Txt(tr(R.string.compat_title), "compat/title", Modifier.padding(start = 8.dp), style = PicoTheme.typography.titleLarge, weight = FontWeight.Bold)
            }
            Spacer(Modifier.height(8.dp))
            Txt(tr(R.string.compat_src, s.ref.name), "compat/src", style = PicoTheme.typography.bodyMedium, color = PicoTheme.colorScheme.labelSecondary, maxLines = 3)
            Txt(
                if (notes.isEmpty()) tr(R.string.compat_none) else tr(R.string.compat_sum, notes.approx(), notes.held()),
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

@Composable
private fun FindSheet(graph: Graph, s: DocSession) {
    var find by remember { mutableStateOf("") }
    var repl by remember { mutableStateOf("") }
    val hits = remember(find, s.version) { if (find.isEmpty()) 0 else s.find(find).size }
    Sheet(
        modifier = Modifier.width(680.dp),
        onDismissRequest = { graph.overlay = null },
        title = { Txt(tr(R.string.find_replace), "find/title", style = PicoTheme.typography.titleLarge, weight = FontWeight.Bold) },
        bottom = {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Txt(if (find.isEmpty()) "" else tr(R.string.find_hits, hits), "find/hits", Modifier.weight(1f), style = PicoTheme.typography.bodyLarge, color = PicoTheme.colorScheme.labelSecondary)
                SecondaryButton(tr(R.string.close), "find/close", onClick = { graph.overlay = null })
                PrimaryButton(tr(R.string.replace_all), "find/replace", enabled = hits > 0, onClick = {
                    val n = s.replaceAll(find, repl)
                    graph.say(Win.DOC, graph.str(R.string.replaced_n, n))
                })
            }
        },
    ) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            LabeledField(tr(R.string.find_label), find, "find/find") { find = it }
            LabeledField(tr(R.string.replace_label), repl, "find/replace-with") { repl = it }
        }
    }
}

@Composable
fun LabeledField(label: String, value: String, tag: String, onChange: (String) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Txt(label, "$tag/label", style = PicoTheme.typography.labelLarge, color = PicoTheme.colorScheme.labelSecondary)
        Spacer(Modifier.height(6.dp))
        androidx.compose.foundation.text.BasicTextField(
            value = value, onValueChange = onChange, singleLine = true,
            textStyle = androidx.compose.ui.text.TextStyle(fontSize = androidx.compose.ui.unit.TextUnit(18f, androidx.compose.ui.unit.TextUnitType.Sp), color = PicoTheme.colorScheme.labelPrimary),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(PicoTheme.colorScheme.interaction),
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(RoundedCornerShape(14.dp)).background(PicoTheme.colorScheme.fillSecondary)
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
        title = { Txt(tr(R.string.ins_table), "table/title", style = PicoTheme.typography.headlineSmall, weight = FontWeight.Bold) },
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
                        .clickable { graph.imageChosen(win, io.embodit.spatialoffice.platform.FileRef.Path(f)) }.padding(horizontal = 16.dp, vertical = 8.dp)
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
