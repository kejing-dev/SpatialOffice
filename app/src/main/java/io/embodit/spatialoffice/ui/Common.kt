package io.embodit.spatialoffice.ui

import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.content.res.Resources
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.pico.spatial.ui.design.Button
import com.pico.spatial.ui.design.ButtonDefaults
import com.pico.spatial.ui.design.Icon
import com.pico.spatial.ui.design.IconButton
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.SegmentControl
import com.pico.spatial.ui.design.SegmentItem
import com.pico.spatial.ui.design.Text
import com.pico.spatial.ui.design.ToggleIconButton
import com.pico.spatial.ui.design.windows.LocalSnackbarHostState
import com.pico.spatial.ui.design.windows.SnackbarDuration
import com.pico.spatial.ui.design.windows.SnackbarHost
import com.pico.spatial.ui.design.windows.SnackbarResult
import com.pico.spatial.ui.platform.containers.LocalSpatialNavigator
import io.embodit.spatialoffice.platform.Graph
import io.embodit.spatialoffice.platform.Lang
import io.embodit.spatialoffice.platform.TAG
import io.embodit.spatialoffice.platform.Win
import java.util.Locale

/** Colours used on the app's own content surfaces (paper, grid, slide canvas). Window chrome uses PicoTheme. */
object Ink {
    val Paper = Color(0xFFFFFFFF)
    val Text = Color(0xFF17181C)
    val TextSoft = Color(0xFF4A4E57)
    val Hint = Color(0xFF8A8F99)
    val Line = Color(0xFFD9DCE3)
    val Header = Color(0xFFF1F3F7)
    val Select = Color(0xFF2A6FDB)
    val SelectFill = Color(0x1F2A6FDB)
    val Range = Color(0xFFE6F0FF)
    val Doc = Color(0xFF3F6FD9)
    val Sheet = Color(0xFF2F9A5F)
    val Slides = Color(0xFFE0742C)
    val Opaque = Color(0xFFF4F5F8)
    val Series = listOf(Color(0xFF2A78D6), Color(0xFFEB6834), Color(0xFF1BAF7A), Color(0xFFEDA100), Color(0xFF8A5BD6), Color(0xFFD6457A))
}

/** A context whose resources follow the in-app language; only getResources is overridden (a bare configuration context breaks SpatialView). */
fun Context.localized(lang: Lang): Context {
    val config = Configuration(resources.configuration)
    config.setLocale(if (lang == Lang.ZH) Locale.SIMPLIFIED_CHINESE else Locale.ENGLISH)
    val res = createConfigurationContext(config).resources
    return object : ContextWrapper(this) {
        override fun getResources(): Resources = res
    }
}

/**
 * The app-language resources. Dialogs, sheets and attachment panels are composed in their own windows, where
 * LocalContext is the system's again; this local travels into them, so their text follows the in-app language too.
 */
val LocalRes = staticCompositionLocalOf<Resources?> { null }

/** A string in the app language (use instead of stringResource everywhere). */
@Composable
fun tr(id: Int, vararg args: Any): String {
    val r = LocalRes.current ?: LocalContext.current.resources
    return if (args.isEmpty()) r.getString(id) else r.getString(id, *args)
}

/**
 * Root of every container's content: registers the navigator and lifecycle with the window controller, applies the
 * in-app language and theme, and hosts this window's snackbars and shared dialogs.
 */
@Composable
fun WindowFrame(win: Win, content: @Composable (Graph) -> Unit) {
    val base = LocalContext.current
    val graph = remember(base) { Graph.from(base) }
    val nav = LocalSpatialNavigator.current
    DisposableEffect(nav) {
        graph.windows.attach(win, nav)
        onDispose { graph.windows.detach(win, nav) }
    }
    val owner = LocalLifecycleOwner.current
    // v0.2.4 (q4-2): this window's container instance, so placements anchor to the live window, not a destroyed one
    val instance = remember(base) { io.embodit.spatialoffice.platform.ContainerIds.of(base) }
    DisposableEffect(owner) {
        val name = io.embodit.spatialoffice.platform.Containers.id(win)
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                androidx.lifecycle.Lifecycle.Event.ON_CREATE -> io.embodit.spatialoffice.platform.ContainerIds.created(name, instance)
                androidx.lifecycle.Lifecycle.Event.ON_DESTROY -> io.embodit.spatialoffice.platform.ContainerIds.destroyed(name, instance)
                else -> {}
            }
            graph.windows.onLifecycle(win, e.name)
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    val lang = graph.settings.value.lang
    val localized = remember(base, lang) { base.localized(lang) }
    CompositionLocalProvider(LocalContext provides localized, LocalRes provides localized.resources) {
        PicoTheme {
            SnackbarHost {
                ToastPump(graph, win)
                content(graph)
                SharedOverlays(graph, win)
                CloudOverlays(graph, win)
            }
        }
    }
}

/** Shows this window's pending message as a snackbar. */
@Composable
private fun ToastPump(graph: Graph, win: Win) {
    val host = LocalSnackbarHostState.current
    val t = graph.toast
    LaunchedEffect(t?.id) {
        if (t == null || t.win != win) return@LaunchedEffect
        graph.toastShown(t.id)
        val r = if (t.action != null) host.show(message = t.text, action = t.action, duration = SnackbarDuration.custom(6_000L))
        else host.show(message = t.text, duration = SnackbarDuration.custom(4_000L))
        if (r == SnackbarResult.ActionPerformed) t.onAction?.invoke()
    }
}

/** Text that logs `OVERFLOW <tag>` whenever it is cut, so truncation shows up in the audit run. */
@Composable
fun Txt(
    text: String,
    tag: String,
    modifier: Modifier = Modifier,
    style: TextStyle = PicoTheme.typography.bodyLarge,
    color: Color = PicoTheme.colorScheme.labelPrimary,
    maxLines: Int = 1,
    align: TextAlign = TextAlign.Start,
    weight: FontWeight? = null,
) {
    Text(
        text = text, style = style, color = color, modifier = modifier, maxLines = maxLines, softWrap = maxLines > 1, textAlign = align, fontWeight = weight,
        onTextLayout = { if (it.hasVisualOverflow) Log.w(TAG, "OVERFLOW $tag \"$text\"") },
    )
}

/** Main action, 64 dp high. */
@Composable
fun PrimaryButton(text: String, tag: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, icon: Int? = null) {
    Button(
        onClick = onClick, modifier = modifier, enabled = enabled,
        size = ButtonDefaults.Regular.copy(height = 64.dp, minWidth = 144.dp),
        shape = RoundedCornerShape(32.dp),
        colors = ButtonDefaults.buttonColors(containerColor = PicoTheme.colorScheme.interaction, contentColor = PicoTheme.colorScheme.labelPrimaryLight),
    ) {
        if (icon != null) {
            Icon(painter = painterResource(icon), contentDescription = null, modifier = Modifier.size(22.dp), tint = PicoTheme.colorScheme.labelPrimaryLight)
            Box(Modifier.width(8.dp))
        }
        Txt(text, "$tag/primary", style = PicoTheme.typography.titleMedium, color = PicoTheme.colorScheme.labelPrimaryLight)
    }
}

@Composable
fun SecondaryButton(text: String, tag: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, icon: Int? = null, danger: Boolean = false) {
    val fg = if (danger) PicoTheme.colorScheme.labelPrimaryLight else PicoTheme.colorScheme.labelPrimary
    Button(
        onClick = onClick, modifier = modifier, enabled = enabled,
        size = ButtonDefaults.Regular.copy(height = 64.dp, minWidth = 112.dp),
        shape = RoundedCornerShape(32.dp),
        colors = ButtonDefaults.buttonColors(containerColor = if (danger) PicoTheme.colorScheme.error else PicoTheme.colorScheme.fillSecondary, contentColor = fg),
    ) {
        if (icon != null) {
            Icon(painter = painterResource(icon), contentDescription = null, modifier = Modifier.size(22.dp), tint = fg)
            Box(Modifier.width(8.dp))
        }
        Txt(text, "$tag/secondary", style = PicoTheme.typography.titleMedium, color = fg)
    }
}

/**
 * Editor toolbar button with a label. Toolbars are dense by design (14–16 tools, PRD §11): 48 dp targets, 8 dp
 * apart inside the system Toolbar ornament.
 */
@Composable
fun ToolButton(text: String, tag: String, onClick: () -> Unit, enabled: Boolean = true, icon: Int? = null, primary: Boolean = false, width: Dp? = null) {
    val fg = if (primary) PicoTheme.colorScheme.labelPrimaryLight else PicoTheme.colorScheme.labelPrimary
    Button(
        onClick = onClick, enabled = enabled,
        // a fixed width for buttons whose label follows the state (paragraph style), so the row never reflows
        modifier = if (width != null) Modifier.width(width) else Modifier,
        // gate:allow button-height editor toolbar: 14–16 tools in one system Toolbar (PRD §11), 48 dp targets
        size = ButtonDefaults.Small.copy(height = 48.dp, minWidth = 64.dp),
        shape = RoundedCornerShape(24.dp),
        colors = ButtonDefaults.buttonColors(containerColor = if (primary) PicoTheme.colorScheme.interaction else PicoTheme.colorScheme.fillSecondary, contentColor = fg),
    ) {
        if (icon != null) {
            Icon(painter = painterResource(icon), contentDescription = null, modifier = Modifier.size(20.dp), tint = fg)
            Box(Modifier.width(6.dp))
        }
        Txt(text, "$tag/tool", style = PicoTheme.typography.labelLarge, color = fg)
    }
}

/** Icon-only toolbar button, 48 dp, with an accessibility name. */
@Composable
fun ToolIcon(icon: Int, label: String, onClick: () -> Unit, enabled: Boolean = true, size: Dp = 48.dp) {
    IconButton(
        onClick = onClick, enabled = enabled,
        modifier = Modifier.size(size).semantics { contentDescription = label },
    ) {
        Icon(painter = painterResource(icon), contentDescription = null, modifier = Modifier.size(size * 22 / 48))
    }
}

@Composable
fun ToolToggle(icon: Int, label: String, checked: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean = true) {
    ToggleIconButton(
        checked = checked, onCheckedChange = onChange, enabled = enabled,
        modifier = Modifier.size(48.dp).semantics { contentDescription = label },
    ) {
        Icon(painter = painterResource(icon), contentDescription = null, modifier = Modifier.size(22.dp))
    }
}

/** Row of toolbar items: 48 dp targets, 8 dp apart (see [ToolButton]). */
@Composable
fun ToolRow(content: @Composable RowScope.() -> Unit) {
    // gate:allow button-gap editor toolbar inside the system Toolbar ornament: 48 dp targets, 8 dp apart (PRD §11)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), content = content)
}

@Composable
fun ToolDivider() {
    Box(Modifier.padding(horizontal = 4.dp).width(1.dp).height(28.dp).background(PicoTheme.colorScheme.dividerLine))
}

/** Options of one setting, inside a labelled row (never used to change pages). 52 dp high. */
@Composable
fun <T> Segments(options: List<Pair<T, String>>, selected: T, tag: String, modifier: Modifier = Modifier, itemWidth: Dp = 120.dp, labelStyle: TextStyle? = null, onSelect: (T) -> Unit) {
    // an explicit width: left alone, the control takes all the width its row offers (or none inside a scroller)
    SegmentControl(modifier = modifier.width(itemWidth * options.size + 12.dp).height(52.dp)) {
        options.forEach { (value, label) ->
            SegmentItem(
                selected = value == selected,
                onClick = { onSelect(value) },
                modifier = Modifier.width(itemWidth).fillMaxHeight(),
                title = { Txt(label, "$tag/segment", style = labelStyle ?: PicoTheme.typography.labelLarge) },
            )
        }
    }
}

/** Small rounded tag (file type, compatibility kind). */
@Composable
fun Tag(text: String, tag: String, color: Color = PicoTheme.colorScheme.fillSecondary, ink: Color = PicoTheme.colorScheme.labelPrimary) {
    Box(Modifier.clip(RoundedCornerShape(10.dp)).background(color).padding(horizontal = 10.dp, vertical = 4.dp)) {
        Txt(text, "$tag/tag", style = PicoTheme.typography.labelMedium, color = ink)
    }
}

/** One entry of a toolbar drop-down. [checked] adds a tick in front; disabled entries are left out by the caller. */
data class MenuEntry(val label: String, val tag: String, val checked: Boolean = false, val onClick: () -> Unit)

/**
 * Toolbar button that opens a menu of related tools (B31: keep a toolbar to a handful of groups, the rest behind
 * "More"). The menu opens above the bottom toolbar.
 */
@Composable
fun ToolMenu(label: String, tag: String, entries: List<MenuEntry>, enabled: Boolean = true, icon: Int? = null, above: Boolean = true, width: Dp? = null) {
    var open by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    Box {
        ToolButton(label, tag, onClick = { open = !open }, enabled = enabled && entries.isNotEmpty(), icon = icon, width = width)
        if (open) {
            com.pico.spatial.ui.design.menu.Menu(
                onDismissRequest = { open = false },
                positionProvider = com.pico.spatial.ui.design.menu.rememberMenuPositionProvider(
                    horizontalPlacement = com.pico.spatial.ui.design.windows.popup.HorizontalPlacement.alignStart(),
                    verticalPlacement = if (above) com.pico.spatial.ui.design.windows.popup.VerticalPlacement.above(offset = (-12).dp)
                    else com.pico.spatial.ui.design.windows.popup.VerticalPlacement.below(offset = 8.dp),
                ),
            ) {
                entries.forEach { e ->
                    com.pico.spatial.ui.design.menu.MenuItem(
                        title = { Txt((if (e.checked) "✓ " else "") + e.label, "$tag/item") },
                        onClick = { open = false; e.onClick() },
                    )
                }
            }
        }
    }
}
