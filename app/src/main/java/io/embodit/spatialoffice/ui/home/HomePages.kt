package io.embodit.spatialoffice.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.Icon
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Switch
import com.pico.spatial.ui.design.windows.AlertDialog
import com.pico.spatial.ui.design.windows.Sheet
import io.embodit.spatialoffice.BuildConfig
import io.embodit.spatialoffice.R
import io.embodit.spatialoffice.platform.FileRef
import io.embodit.spatialoffice.platform.Graph
import io.embodit.spatialoffice.platform.Lang
import io.embodit.spatialoffice.platform.LayoutStoreMode
import io.embodit.spatialoffice.platform.OpenMode
import io.embodit.spatialoffice.platform.Overlay
import io.embodit.spatialoffice.platform.Win
import io.embodit.spatialoffice.ui.PrimaryButton
import io.embodit.spatialoffice.ui.SecondaryButton
import io.embodit.spatialoffice.ui.Segments
import io.embodit.spatialoffice.ui.Txt

@Composable
internal fun BrowsePage(graph: Graph) {
    PageHeader(stringResource(R.string.nav_browse), stringResource(R.string.browse_sub), "browse")
    PageBody {
        val locs = listOf(R.string.loc_device, R.string.loc_feishu, R.string.loc_gdrive, R.string.loc_onedrive, R.string.loc_dropbox)
        // where to look, inside this one page (the page itself is chosen in the side navigation)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Segments(locs.mapIndexed { i, id -> i to stringResource(id) }, graph.browseLoc, "browse/loc", itemWidth = 136.dp) { graph.browseLoc = it; graph.browseDir = null }
        }
        if (graph.browseLoc != 0) {
            // no provider is connected in this version; say so instead of showing made-up folders
            Spacer(Modifier.height(24.dp))
            InfoRow(R.drawable.ic_cloud, stringResource(R.string.br_off_head, stringResource(locs[graph.browseLoc])), stringResource(R.string.br_off_sub), "browse/off")
            return@PageBody
        }
        val dir = graph.browseDir
        val version = graph.filesVersion
        Row(Modifier.fillMaxWidth().heightIn(min = 64.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(painter = painterResource(R.drawable.ic_folder), contentDescription = null, modifier = Modifier.size(20.dp), tint = PicoTheme.colorScheme.labelSecondary)
            Txt(if (dir == null) stringResource(R.string.loc_device) else graph.storage.display(dir), "browse/path", Modifier.weight(1f).padding(horizontal = 10.dp), style = PicoTheme.typography.bodyLarge, maxLines = 2)
            if (dir != null) SecondaryButton(stringResource(R.string.browse_up), "browse/up", icon = R.drawable.ic_arrow_up, onClick = {
                graph.browseDir = dir.parentFile?.takeIf { it.absolutePath.startsWith(graph.storage.shared.absolutePath) && it != graph.storage.shared }
            })
        }
        if (dir == null) {
            listOf(graph.storage.newDir, graph.storage.downloadDir, graph.storage.documentsDir).forEach { d ->
                FileRow(graph.storage.display(d), "", io.embodit.spatialoffice.core.files.FileKind.UNKNOWN, folder = true, tag = "browse/root") { graph.browseDir = d }
            }
        } else {
            val entries = remember(dir, version) { graph.storage.list(dir) }
            if (entries.isEmpty()) InfoRow(R.drawable.ic_info_circle, stringResource(R.string.browse_empty), null, "browse/empty")
            entries.take(40).forEach { e ->
                if (e.isDir) FileRow(e.file.name, "", e.kind, folder = true, tag = "browse/dir") { graph.browseDir = e.file }
                else FileRow(e.file.name, kindLabel(e.kind) + " · " + sizeLabel(e.size) + " · " + timeLabel(e.modified), e.kind, tag = "browse/file") { graph.open(FileRef.Path(e.file)) }
            }
        }
        if (!graph.storage.hasAllFiles) {
            Spacer(Modifier.height(4.dp))
            InfoRow(R.drawable.ic_lock, stringResource(R.string.browse_grant_head), stringResource(R.string.browse_grant_sub), "browse/grant") {
                SecondaryButton(stringResource(R.string.browse_grant_btn), "browse/grant", onClick = {
                    if (!graph.storage.requestAllFiles()) graph.say(Win.HOME, graph.str(R.string.picker_none))
                })
            }
        }
    }
}

internal fun sizeLabel(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${(bytes + 512) / 1024} KB"
    else -> String.format(java.util.Locale.ROOT, "%.1f MB", bytes / 1048576.0)
}

/** One settings row: label + explanation on the left, the control on the right. 64 dp min, so seven rows fit the first screen. */
@Composable
private fun SettingRow(label: String, sub: String?, tag: String, modifier: Modifier = Modifier, control: @Composable () -> Unit) {
    Row(modifier.fillMaxWidth().heightIn(min = 64.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 16.dp)) {
            Txt(label, "$tag/label", style = PicoTheme.typography.titleMedium)
            if (sub != null) Txt(sub, "$tag/sub", style = PicoTheme.typography.bodySmall, color = PicoTheme.colorScheme.labelSecondary, maxLines = 2)
        }
        control()
    }
}

@Composable
internal fun SettingsPage(graph: Graph) {
    val s = graph.settings.value
    PageHeader(stringResource(R.string.nav_settings), null, "settings")
    Column(Modifier.fillMaxWidth().padding(start = 32.dp, end = 32.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SettingRow(stringResource(R.string.set_language), null, "settings/lang") {
            Segments(listOf(Lang.ZH to stringResource(R.string.lang_zh), Lang.EN to stringResource(R.string.lang_en)), s.lang, "settings/lang") { v -> graph.settings.update { it.copy(lang = v) } }
        }
        SettingRow(stringResource(R.string.set_open), stringResource(R.string.set_open_sub), "settings/open") {
            Segments(listOf(OpenMode.WINDOW to stringResource(R.string.open_window), OpenMode.SPREAD to stringResource(R.string.open_spread)), s.openMode, "settings/open") { v -> graph.settings.update { it.copy(openMode = v) } }
        }
        SettingRow(stringResource(R.string.set_store), stringResource(R.string.set_store_sub), "settings/store") {
            Segments(listOf(LayoutStoreMode.DEVICE to stringResource(R.string.store_device), LayoutStoreMode.FILE to stringResource(R.string.store_file)), s.layoutStore, "settings/store") { v -> graph.settings.update { it.copy(layoutStore = v) } }
        }
        // the whole row toggles (the bare Switch is far smaller than 48 dp)
        SettingRow(
            stringResource(R.string.set_keep), stringResource(R.string.set_keep_sub), "settings/keep",
            Modifier.clip(RoundedCornerShape(16.dp)).toggleable(value = s.keepBackup, role = Role.Switch) { v -> graph.settings.update { it.copy(keepBackup = v) } },
        ) { Switch(checked = s.keepBackup, onCheckedChange = null) }
        SettingRow(stringResource(R.string.set_radius), stringResource(R.string.set_radius_sub), "settings/radius") {
            Segments(listOf(0 to "1.2 m", 1 to "1.5 m", 2 to "1.8 m"), s.radius, "settings/radius", itemWidth = 88.dp) { v -> graph.settings.update { it.copy(radius = v) } }
        }
        SettingRow(stringResource(R.string.set_howto), stringResource(R.string.shortcuts_sub), "settings/howto") {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                SecondaryButton(stringResource(R.string.set_howto), "settings/howto", icon = R.drawable.ic_question_fill, onClick = { graph.overlay = Overlay.HowTo() })
                SecondaryButton(stringResource(R.string.shortcuts), "settings/keys", icon = R.drawable.ic_keyboard_fill, onClick = { graph.overlay = Overlay.Shortcuts(Win.HOME) })
            }
        }
        SettingRow(stringResource(R.string.about), stringResource(R.string.about_sub, BuildConfig.VERSION_NAME), "settings/about") {
            SecondaryButton(stringResource(R.string.exit_app), "settings/exit", icon = R.drawable.ic_power, onClick = { graph.requestExit() })
        }
        Txt(stringResource(R.string.set_legal), "settings/legal", style = PicoTheme.typography.bodySmall, color = PicoTheme.colorScheme.labelSecondary, maxLines = 3)
    }
}

/** Dialogs and sheets that belong to the home window. */
@Composable
internal fun HomeOverlays(graph: Graph) {
    when (graph.overlay) {
        is Overlay.Exit -> {
            val dirty = graph.dirtyNames
            AlertDialog(
                onDismissRequest = { graph.overlay = null },
                title = { Txt(stringResource(R.string.exit_q), "exit/title", style = PicoTheme.typography.headlineSmall, weight = FontWeight.Bold) },
                content = {
                    Txt(if (dirty.isEmpty()) stringResource(R.string.exit_msg) else stringResource(R.string.exit_msg_dirty, dirty.joinToString("、")), "exit/msg", style = PicoTheme.typography.bodyLarge, maxLines = 4)
                },
                buttons = {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.End)) {
                        SecondaryButton(stringResource(R.string.cancel), "exit/cancel", onClick = { graph.overlay = null })
                        PrimaryButton(stringResource(R.string.exit), "exit/confirm", onClick = { graph.exit() })
                    }
                },
            )
        }
        is Overlay.HowTo -> HowToSheet(graph)
        else -> {}
    }
}

/** Settings → How to use: one row per main action, with the keyboard / hand / controller way (factory B19). */
@Composable
private fun HowToSheet(graph: Graph) {
    val rows = listOf(
        listOf(R.drawable.ic_folder, R.string.how_to_open, R.string.how_to_open_keys, R.string.how_to_open_hand, R.string.how_to_open_ctrl),
        listOf(R.drawable.ic_text, R.string.how_to_edit, R.string.how_to_edit_keys, R.string.how_to_edit_hand, R.string.how_to_edit_ctrl),
        listOf(R.drawable.ic_check_circle, R.string.how_to_save, R.string.how_to_save_keys, R.string.how_to_save_hand, R.string.how_to_save_ctrl),
        listOf(R.drawable.ic_spread, R.string.how_to_spread, R.string.how_to_spread_keys, R.string.how_to_spread_hand, R.string.how_to_spread_ctrl),
        listOf(R.drawable.ic_chart_3d, R.string.how_to_chart, R.string.how_to_chart_keys, R.string.how_to_chart_hand, R.string.how_to_chart_ctrl),
        listOf(R.drawable.ic_expand, R.string.how_to_move, R.string.how_to_move_keys, R.string.how_to_move_hand, R.string.how_to_move_ctrl),
    )
    Sheet(
        modifier = Modifier.width(920.dp),
        onDismissRequest = { graph.overlay = null },
        title = { Txt(stringResource(R.string.how_to_title), "howto/title", style = PicoTheme.typography.titleLarge, weight = FontWeight.Bold) },
    ) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.width(196.dp))
                HowToHead(R.drawable.ic_keyboard_fill, R.string.how_to_col_keys, Modifier.weight(1f))
                HowToHead(R.drawable.ic_hand, R.string.how_to_col_hand, Modifier.weight(1f))
                HowToHead(R.drawable.ic_controller, R.string.how_to_col_ctrl, Modifier.weight(1f))
            }
            rows.forEach { r ->
                Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                    Row(Modifier.width(196.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(painter = painterResource(r[0]), contentDescription = null, modifier = Modifier.size(22.dp))
                        Txt(stringResource(r[1]), "howto/action", Modifier.padding(start = 10.dp), style = PicoTheme.typography.titleMedium, maxLines = 2)
                    }
                    for (i in 2..4) Txt(stringResource(r[i]), "howto/cell", Modifier.weight(1f).padding(end = 12.dp), style = PicoTheme.typography.bodyMedium, maxLines = 4)
                }
            }
        }
    }
}

@Composable
private fun HowToHead(icon: Int, label: Int, modifier: Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(painter = painterResource(icon), contentDescription = null, modifier = Modifier.size(20.dp), tint = PicoTheme.colorScheme.labelSecondary)
        Txt(stringResource(label), "howto/head", Modifier.padding(start = 8.dp), style = PicoTheme.typography.labelLarge, color = PicoTheme.colorScheme.labelSecondary)
    }
}
