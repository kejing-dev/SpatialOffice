package io.embodit.spatialoffice.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.pointerInput
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
    // v0.5.0: the file name is the title right above; the status line names the folder, so a long name does not push it out of the bar
    val full = graph.storage.display(s.ref)
    val path = if (full.endsWith("/" + s.ref.name)) full.substringBeforeLast('/') else full
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
            IconButton(onClick = { graph.requestClose(s) }, modifier = Modifier.noKeyFocus().size(48.dp).semantics { contentDescription = "close-file" }) {
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
                    if (graph.overlay is Overlay.Compat) graph.overlay = null else graph.show(s, Overlay.Compat(win))
                })
                extra?.invoke()
                ZoomMenu(s)
                WindowsMenu(graph, s)
                Box {
                    ToolIcon(R.drawable.ic_ellipsis, tr(R.string.more), onClick = { menu = !menu })
                    val menuKey = androidx.compose.runtime.remember { Any() }
                    androidx.compose.runtime.DisposableEffect(menu) {
                        if (menu) io.embodit.spatialoffice.platform.OpenMenus.opened(menuKey) { menu = false } // v0.5.1: BACK closes it
                        onDispose { io.embodit.spatialoffice.platform.OpenMenus.closed(menuKey) }
                    }
                    if (menu) {
                        Menu(
                            onDismissRequest = { menu = false },
                            positionProvider = rememberMenuPositionProvider(horizontalPlacement = HorizontalPlacement.alignEnd(), verticalPlacement = VerticalPlacement.below(offset = 8.dp)),
                        ) {
                            MenuItem(title = { Txt(tr(R.string.menu_layout_file), "editor/menu") }, onClick = { menu = false; graph.layoutWithFile(s) }, modifier = Modifier.noKeyFocus())
                            MenuItem(title = { Txt(tr(R.string.menu_open_backup), "editor/menu") }, onClick = { menu = false; graph.openBackup(s) }, modifier = Modifier.noKeyFocus())
                            MenuItem(title = { Txt(tr(R.string.menu_shortcuts), "editor/menu") }, onClick = { menu = false; graph.show(s, Overlay.Shortcuts(win)) }, modifier = Modifier.noKeyFocus())
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
        ToolButton(tr(spreadLabel), "tools/spread", icon = R.drawable.ic_spread, onClick = { graph.spread(s) })
        ToolButton(tr(R.string.save_as), "tools/saveas", icon = R.drawable.ic_folder_arrow_right, onClick = { graph.show(s, Overlay.SaveAs(win)) })
        if (!s.readOnly) ToolButton(tr(R.string.save), "tools/save", primary = true, onClick = { graph.save(s) })
    }
}

/** Shortcuts every editor shares. Returns true when the event was used. */
fun commonKeys(graph: Graph, s: Session, e: KeyEvent): Boolean {
    if (e.type != KeyEventType.KeyDown || !e.isCtrlPressed || e.isAltPressed) return false
    when (e.key) {
        Key.S -> graph.save(s)
        Key.Z -> s.undo()
        Key.Y -> s.redo()
        // v0.5.0 (d43 / s41 / p41): view zoom, display only
        Key.Equals, Key.Plus, Key.NumPadAdd -> s.zoomBy(1)
        Key.Minus, Key.NumPadSubtract -> s.zoomBy(-1)
        Key.Zero, Key.NumPad0 -> s.zoomReset()
        else -> return false
    }
    return true
}

/**
 * v0.5.0: title bar [100 % ▾]: the zoom of this window's view (Ctrl+= / Ctrl+- / Ctrl+0 have their entries here).
 * Display only; the file is not touched.
 */
@Composable
fun ZoomMenu(s: Session) {
    val resetLabel = when (s.editor) { io.embodit.spatialoffice.core.files.FileKind.SHEET -> R.string.zoom_100; io.embodit.spatialoffice.core.files.FileKind.SLIDES -> R.string.zoom_fit_window; else -> R.string.zoom_fit_width }
    ToolMenu("${s.zoom} %", "editor/zoom", above = false, width = 104.dp, entries = listOf(
        MenuEntry(tr(R.string.zoom_in), "editor/zoom-in") { s.zoomBy(1) },
        MenuEntry(tr(R.string.zoom_out), "editor/zoom-out") { s.zoomBy(-1) },
        MenuEntry(tr(resetLabel), "editor/zoom-reset", checked = s.zoom == 100) { s.zoomReset() },
    ))
}

/** Content drawn at the session's zoom: dp and sp both scale, so layout, hit-testing and text follow (display only). */
@Composable
fun Zoomed(s: Session, content: @Composable () -> Unit) {
    val d = androidx.compose.ui.platform.LocalDensity.current
    val z = s.zoom / 100f
    androidx.compose.runtime.CompositionLocalProvider(
        androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(d.density * z, d.fontScale),
    ) { content() }
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

/**
 * Root of an editor window (v0.2.5): the window shows the open file with [tag], not "the" document / sheet / deck, so
 * several windows of one kind can be up. Any press or key in the window, and the window taking the system focus,
 * makes its file the active one of its kind (what window-less commands and the Stage act on).
 */
@Composable
fun EditorWindow(win: Win, tag: String?, content: @Composable (Graph, Session) -> Unit) {
    WindowFrame(win, tag) { graph ->
        val s = graph.sessionByTag(tag)
        if (s == null) { NoFile(graph, win); return@WindowFrame }
        val focused = com.pico.spatial.ui.platform.LocalSpatialContainerStateManager.current.isFocused.value
        androidx.compose.runtime.LaunchedEffect(focused, s) { if (focused) graph.activate(s) }
        Box(
            Modifier.fillMaxSize()
                .pointerInput(s) {
                    awaitPointerEventScope {
                        while (true) {
                            val e = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                            if (e.type == androidx.compose.ui.input.pointer.PointerEventType.Press) graph.activate(s)
                        }
                    }
                }
                .onPreviewKeyEvent { graph.activate(s); false },
        ) { content(graph, s) }
    }
}

/**
 * Title bar › [Windows n] (v0.2.5): every open file has a window of its own and a new one opens in front, over the
 * others (the system has no staggered placement and hides the window underneath). This menu is how to get from one
 * to another: the home window, each open file (✓ = this one, • = unsaved changes), and [Side by side], which sets
 * the windows out to the right and left of this one.
 */
@Composable
fun WindowsMenu(graph: Graph, s: Session) {
    val open = graph.sessions
    val entries = ArrayList<MenuEntry>()
    entries.add(MenuEntry(tr(R.string.win_home), "windows/home") { graph.windows.frontHome() })
    open.sortedBy { it.tag.drop(1).toIntOrNull() ?: 0 }.forEach { o ->
        entries.add(MenuEntry((if (o.dirty) "• " else "") + o.ref.name, "windows/file", checked = o === s) { if (o !== s) graph.front(o, quiet = true) })
    }
    if (open.size > 1) entries.add(MenuEntry(tr(R.string.win_side_by_side), "windows/arrange") { graph.windows.arrange(graph.refOf(s)) })
    ToolMenu(tr(R.string.win_menu, open.size), "editor/windows", entries, icon = R.drawable.ic_stack, above = false)
}
