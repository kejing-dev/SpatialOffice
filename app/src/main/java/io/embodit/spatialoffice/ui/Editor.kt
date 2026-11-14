package io.embodit.spatialoffice.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.Icon
import com.pico.spatial.ui.design.IconButton
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.TitleBar
import com.pico.spatial.ui.design.menu.Menu
import com.pico.spatial.ui.design.menu.MenuItem
import com.pico.spatial.ui.design.menu.rememberMenuPositionProvider
import com.pico.spatial.ui.design.windows.popup.HorizontalPlacement
import com.pico.spatial.ui.design.windows.popup.VerticalPlacement
import io.embodit.spatialoffice.R
import io.embodit.spatialoffice.platform.Graph
import io.embodit.spatialoffice.platform.LayoutStoreMode
import io.embodit.spatialoffice.platform.Overlay
import io.embodit.spatialoffice.platform.Session
import io.embodit.spatialoffice.platform.Win
import io.embodit.spatialoffice.platform.approx
import io.embodit.spatialoffice.platform.held

/** One line under the file name: saved / unsaved / new / read-only, where the file is, and where its layout is kept. */
@Composable
fun statusLine(graph: Graph, s: Session): String {
    s.version
    val path = graph.storage.display(s.ref)
    val head = when {
        s.isNew -> tr(R.string.status_new, path, s.ref.name.substringAfterLast('.'))
        s.readOnly -> tr(R.string.status_readonly, path)
        s.dirty -> tr(R.string.status_dirty, path)
        else -> tr(R.string.status_saved, path)
    }
    if (s.isNew) return head
    val side = graph.filesVersion.let { graph.storage.sidecar(s.ref)?.exists() == true } || graph.settings.value.layoutStore == LayoutStoreMode.FILE
    return head + " · " + tr(if (side) R.string.status_layout_file else R.string.status_layout_device)
}

/**
 * Title bar shared by the three editors: close, file name with status line, then the compatibility button (when the
 * file has anything shown approximately or read-only), editor-specific actions, and the "more" menu.
 */
@Composable
fun EditorTitleBar(graph: Graph, win: Win, s: Session, extra: (@Composable () -> Unit)? = null) {
    var menu by remember { mutableStateOf(false) }
    val notes = remember(s.version) { s.compat() }
    TitleBar(
        modifier = Modifier.fillMaxWidth(),
        leadingActions = {
            IconButton(onClick = { graph.requestClose(win) }, modifier = Modifier.size(48.dp).semantics { contentDescription = "close-file" }) {
                Icon(painter = painterResource(R.drawable.ic_cross), contentDescription = tr(R.string.close_file), modifier = Modifier.size(20.dp))
            }
        },
        title = {
            Column(Modifier.padding(horizontal = 8.dp)) {
                // gate:allow ellipsis-no-fits file name in a title bar; Txt logs when it is cut
                Txt(s.ref.name, "editor/name", style = PicoTheme.typography.titleLarge, weight = FontWeight.Bold)
                Txt(statusLine(graph, s), "editor/status", style = PicoTheme.typography.bodyMedium, color = PicoTheme.colorScheme.labelSecondary)
            }
        },
        trailingActions = {
            ToolRow {
                if (notes.isNotEmpty()) ToolButton(tr(R.string.compat_btn, notes.approx() + notes.held()), "editor/compat", icon = R.drawable.ic_info_circle, onClick = {
                    graph.overlay = if (graph.overlay is Overlay.Compat) null else Overlay.Compat(win)
                })
                extra?.invoke()
                Box {
                    ToolIcon(R.drawable.ic_ellipsis, tr(R.string.more), onClick = { menu = !menu })
                    if (menu) {
                        Menu(
                            onDismissRequest = { menu = false },
                            positionProvider = rememberMenuPositionProvider(horizontalPlacement = HorizontalPlacement.alignEnd(), verticalPlacement = VerticalPlacement.below(offset = 8.dp)),
                        ) {
                            MenuItem(title = { Txt(tr(R.string.menu_layout_file), "editor/menu") }, onClick = { menu = false; graph.layoutWithFile(win) })
                            MenuItem(title = { Txt(tr(R.string.menu_open_backup), "editor/menu") }, onClick = { menu = false; graph.openBackup(win) })
                            MenuItem(title = { Txt(tr(R.string.menu_shortcuts), "editor/menu") }, onClick = { menu = false; graph.overlay = Overlay.Shortcuts(win) })
                        }
                    }
                }
            }
        },
    )
}

/** The right-hand group of every editor toolbar: spread out, save as, save. */
@Composable
fun FileTools(graph: Graph, win: Win, s: Session, spreadLabel: Int = R.string.spread) {
    ToolRow {
        ToolButton(tr(spreadLabel), "tools/spread", icon = R.drawable.ic_spread, onClick = { graph.spread(s.kind) })
        ToolButton(tr(R.string.save_as), "tools/saveas", icon = R.drawable.ic_folder_arrow_right, onClick = { graph.overlay = Overlay.SaveAs(win) })
        if (!s.readOnly) ToolButton(tr(R.string.save), "tools/save", primary = true, onClick = { graph.save(win) })
    }
}

/** Shortcuts every editor shares. Returns true when the event was used. */
fun commonKeys(graph: Graph, win: Win, e: KeyEvent): Boolean {
    if (e.type != KeyEventType.KeyDown || !e.isCtrlPressed || e.isAltPressed) return false
    val s = graph.session(win) ?: return false
    when (e.key) {
        Key.S -> graph.save(win)
        Key.Z -> s.undo()
        Key.Y -> s.redo()
        else -> return false
    }
    return true
}

@Composable
fun UndoRedo(s: Session) {
    s.version
    ToolIcon(R.drawable.ic_undo, tr(R.string.undo), onClick = { s.undo() }, enabled = s.canUndo)
    ToolIcon(R.drawable.ic_redo, tr(R.string.redo), onClick = { s.redo() }, enabled = s.canRedo)
}

/** Shown in an editor window whose file was closed (for example after "Don't save"). */
@Composable
fun NoFile(graph: Graph, win: Win) {
    Row(Modifier.fillMaxWidth().padding(32.dp), verticalAlignment = Alignment.CenterVertically) {
        Txt(tr(R.string.recent_empty), "editor/empty", style = PicoTheme.typography.bodyLarge, maxLines = 3)
    }
}
