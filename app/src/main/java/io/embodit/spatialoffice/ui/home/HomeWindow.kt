package io.embodit.spatialoffice.ui.home

import android.content.Intent
import android.util.Log
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.Icon
import com.pico.spatial.ui.design.ListItem
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.SideNavigation
import com.pico.spatial.ui.design.SideNavigationItem
import com.pico.spatial.ui.design.TitleBar
import io.embodit.spatialoffice.R
import io.embodit.spatialoffice.core.files.FileKind
import io.embodit.spatialoffice.platform.Graph
import io.embodit.spatialoffice.platform.HomePage
import io.embodit.spatialoffice.platform.PickerActivity
import io.embodit.spatialoffice.platform.TAG
import io.embodit.spatialoffice.platform.Win
import io.embodit.spatialoffice.ui.SecondaryButton
import io.embodit.spatialoffice.ui.Txt
import io.embodit.spatialoffice.ui.WindowFrame
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** W0 home (Planar 1120 × 800 dp): side navigation with five destinations and the page of the selected one. */
@Composable
fun HomeWindow() {
    WindowFrame(Win.HOME) { graph ->
        Row(Modifier.fillMaxSize()) {
            SideNavigation(
                modifier = Modifier.width(316.dp).fillMaxHeight(),
                header = {
                    Box(Modifier.fillMaxWidth().height(96.dp).padding(start = 8.dp, top = 31.dp, bottom = 31.dp), contentAlignment = Alignment.CenterStart) {
                        Txt(stringResource(R.string.app_name), "home/nav-title", style = PicoTheme.typography.titleLarge, weight = FontWeight.Bold)
                    }
                },
            ) {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    NavItem(graph, HomePage.RECENT, R.drawable.ic_history, R.string.nav_recent)
                    NavItem(graph, HomePage.BROWSE, R.drawable.ic_folder, R.string.nav_browse)
                    NavItem(graph, HomePage.NEW, R.drawable.ic_plus, R.string.nav_new)
                    NavItem(graph, HomePage.ACCOUNT, R.drawable.ic_cloud, R.string.nav_account)
                    NavItem(graph, HomePage.SETTINGS, R.drawable.ic_pico_settings_fill, R.string.nav_settings)
                }
            }
            Column(Modifier.weight(1f).fillMaxHeight()) {
                when (graph.homePage) {
                    HomePage.RECENT -> RecentPage(graph)
                    HomePage.BROWSE -> BrowsePage(graph)
                    HomePage.NEW -> NewPage(graph)
                    HomePage.ACCOUNT -> AccountPage(graph)
                    HomePage.SETTINGS -> SettingsPage(graph)
                }
            }
        }
        HomeOverlays(graph)
    }
}

@Composable
private fun NavItem(graph: Graph, page: HomePage, icon: Int, label: Int) {
    SideNavigationItem(
        selected = graph.homePage == page,
        contentPadding = PaddingValues(start = 16.dp, top = 8.dp, end = 8.dp, bottom = 8.dp),
        modifier = Modifier.heightIn(min = 56.dp).clickable { graph.homePage = page; Log.i(TAG, "PAGE $page") },
        leading = { Icon(painter = painterResource(icon), contentDescription = null, modifier = Modifier.size(22.dp)) },
    ) {
        Txt(stringResource(label), "home/nav", style = PicoTheme.typography.titleMedium)
    }
}

/** Title bar of a home page: bold title, optional one-line subtitle under it, optional trailing action. */
@Composable
internal fun PageHeader(title: String, subtitle: String? = null, tag: String, trailing: (@Composable () -> Unit)? = null) {
    TitleBar(
        modifier = Modifier.fillMaxWidth(),
        title = {
            Column(Modifier.padding(end = 8.dp)) {
                Txt(title, "$tag/title", style = PicoTheme.typography.titleLarge, weight = FontWeight.Bold)
                if (subtitle != null) Txt(subtitle, "$tag/subtitle", style = PicoTheme.typography.bodyMedium, color = PicoTheme.colorScheme.labelSecondary, maxLines = 2)
            }
        },
        trailingActions = { trailing?.invoke() },
    )
}

@Composable
internal fun PageBody(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(start = 32.dp, end = 32.dp, bottom = 24.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) { content() }
}

@Composable
internal fun GroupLabel(text: String, tag: String) {
    Txt(text, "$tag/group", style = PicoTheme.typography.labelLarge, color = PicoTheme.colorScheme.labelSecondary, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
}

/** 48 dp tile for a file kind: the app's own shapes (lines / grid / frame), never a letter on a colour. */
@Composable
internal fun FileTile(kind: FileKind, folder: Boolean = false) {
    val drawable = when {
        folder -> null
        kind == FileKind.DOC -> R.drawable.file_doc
        kind.editor == FileKind.SHEET -> R.drawable.file_sheet
        kind == FileKind.SLIDES -> R.drawable.file_slides
        else -> null
    }
    if (drawable != null) Image(painter = painterResource(drawable), contentDescription = null, modifier = Modifier.size(48.dp))
    else Box(Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)).background(PicoTheme.colorScheme.fillSecondary), contentAlignment = Alignment.Center) {
        Icon(painter = painterResource(if (folder) R.drawable.ic_folder else R.drawable.ic_article), contentDescription = null, modifier = Modifier.size(24.dp))
    }
}

@Composable
internal fun kindLabel(kind: FileKind): String = stringResource(
    when (kind) {
        FileKind.DOC -> R.string.meta_doc
        FileKind.SHEET, FileKind.CSV -> R.string.meta_sheet
        FileKind.SLIDES -> R.string.meta_slides
        FileKind.PDF -> R.string.meta_pdf
        FileKind.IMAGE -> R.string.meta_image
        FileKind.HTML -> R.string.meta_html
        else -> R.string.meta_text
    },
)

@Composable
internal fun timeLabel(time: Long): String {
    val now = Calendar.getInstance()
    val then = Calendar.getInstance().apply { timeInMillis = time }
    val hm = SimpleDateFormat("HH:mm", Locale.ROOT).format(Date(time))
    val sameDay = now.get(Calendar.YEAR) == then.get(Calendar.YEAR) && now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR)
    now.add(Calendar.DAY_OF_YEAR, -1)
    val yesterday = now.get(Calendar.YEAR) == then.get(Calendar.YEAR) && now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR)
    return when {
        sameDay -> stringResource(R.string.today) + " " + hm
        yesterday -> stringResource(R.string.yesterday) + " " + hm
        else -> SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date(time))
    }
}

/** One file or folder row. File names may be long: they wrap to two lines and are logged if still cut. */
@Composable
internal fun FileRow(name: String, meta: String, kind: FileKind, folder: Boolean = false, tag: String, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Txt(name, "$tag/name", style = PicoTheme.typography.titleMedium, maxLines = 2) },
        supportingContent = { Txt(meta, "$tag/meta", style = PicoTheme.typography.bodyMedium, color = PicoTheme.colorScheme.labelSecondary, maxLines = 2) },
        leadingContent = { FileTile(kind, folder) },
        modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp).clickable(onClick = onClick),
    )
}

@Composable
private fun RecentPage(graph: Graph) {
    val context = LocalContext.current
    LaunchedEffect(graph.filesVersion) { graph.recents.prune() }
    PageHeader(stringResource(R.string.nav_recent), stringResource(R.string.recent_sub), "recent") {
        SecondaryButton(stringResource(R.string.open_file), "recent/open", icon = R.drawable.ic_folder, onClick = {
            runCatching { context.startActivity(Intent(context, PickerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                .onFailure { graph.say(Win.HOME, graph.str(R.string.picker_none)) }
        })
    }
    PageBody {
        val items = graph.recents.items
        GroupLabel(stringResource(R.string.group_device), "recent")
        if (items.isEmpty()) {
            InfoRow(R.drawable.ic_info_circle, stringResource(R.string.recent_empty), stringResource(R.string.first_hint), "recent/empty")
        } else {
            items.take(6).forEach { r ->
                FileRow(r.name, kindLabel(r.kind) + " · " + r.display.substringBeforeLast('/', "").ifEmpty { r.display } + " · " + timeLabel(r.time), r.kind, tag = "recent/row") { graph.open(r.ref()) }
            }
        }
        if (!graph.settings.value.hintSeen && items.isNotEmpty()) LaunchedEffect(Unit) { graph.settings.update { it.copy(hintSeen = true) } }
        GroupLabel(stringResource(R.string.group_cloud), "recent")
        InfoRow(R.drawable.ic_cloud, stringResource(R.string.cloud_none), null, "recent/cloud")
        Spacer(Modifier.height(4.dp))
        InfoRow(R.drawable.ic_info_circle, stringResource(R.string.entry_head), stringResource(R.string.entry_sub), "recent/entry")
    }
}

/** A non-clickable note row: icon, one bold line, optional explanation. */
@Composable
internal fun InfoRow(icon: Int, head: String, sub: String?, tag: String, trailing: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(painter = painterResource(icon), contentDescription = null, modifier = Modifier.size(22.dp), tint = PicoTheme.colorScheme.labelSecondary)
        Column(Modifier.weight(1f).padding(start = 12.dp, end = 12.dp)) {
            Txt(head, "$tag/head", style = PicoTheme.typography.bodyLarge, maxLines = 3)
            if (sub != null) Txt(sub, "$tag/sub", style = PicoTheme.typography.bodyMedium, color = PicoTheme.colorScheme.labelSecondary, maxLines = 4)
        }
        trailing?.invoke()
    }
}

@Composable
private fun NewPage(graph: Graph) {
    PageHeader(stringResource(R.string.nav_new), stringResource(R.string.new_sub), "new")
    PageBody {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            NewCard(R.drawable.cover_doc, stringResource(R.string.new_doc), ".docx", "new/doc") { graph.newFile(FileKind.DOC) }
            NewCard(R.drawable.cover_sheet, stringResource(R.string.new_sheet), ".xlsx", "new/sheet") { graph.newFile(FileKind.SHEET) }
            NewCard(R.drawable.cover_slides, stringResource(R.string.new_slides), ".pptx", "new/slides") { graph.newFile(FileKind.SLIDES) }
        }
        Spacer(Modifier.height(12.dp))
        Txt(stringResource(R.string.new_note), "new/note", style = PicoTheme.typography.bodyMedium, color = PicoTheme.colorScheme.labelSecondary, maxLines = 3)
    }
}

@Composable
private fun NewCard(cover: Int, label: String, ext: String, tag: String, onClick: () -> Unit) {
    Column(
        Modifier.width(224.dp).clip(RoundedCornerShape(20.dp)).background(PicoTheme.colorScheme.fillSecondary).clickable(onClick = onClick).padding(12.dp),
    ) {
        Image(painter = painterResource(cover), contentDescription = null, modifier = Modifier.fillMaxWidth().height(125.dp).clip(RoundedCornerShape(12.dp)))
        Spacer(Modifier.height(10.dp))
        Txt(label, "$tag/label", style = PicoTheme.typography.titleMedium, weight = FontWeight.Bold)
        Txt(ext, "$tag/ext", style = PicoTheme.typography.bodyMedium, color = PicoTheme.colorScheme.labelSecondary)
    }
}

@Composable
private fun AccountPage(graph: Graph) {
    PageHeader(stringResource(R.string.nav_account), stringResource(R.string.account_sub), "account")
    PageBody {
        // No app identity is registered with any provider yet, so nothing here pretends to sign in.
        listOf(R.string.loc_feishu, R.string.loc_gdrive, R.string.loc_onedrive, R.string.loc_dropbox).forEach { name ->
            ListItem(
                headlineContent = { Txt(stringResource(name), "account/name", style = PicoTheme.typography.titleMedium) },
                supportingContent = { Txt(stringResource(R.string.acct_off), "account/state", style = PicoTheme.typography.bodyMedium, color = PicoTheme.colorScheme.labelSecondary, maxLines = 2) },
                leadingContent = {
                    Box(Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)).background(PicoTheme.colorScheme.fillSecondary), contentAlignment = Alignment.Center) {
                        Icon(painter = painterResource(R.drawable.ic_cloud), contentDescription = null, modifier = Modifier.size(24.dp))
                    }
                },
                trailingContent = { io.embodit.spatialoffice.ui.Tag(stringResource(R.string.acct_soon), "account") },
                modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp),
            )
        }
        Spacer(Modifier.height(4.dp))
        InfoRow(R.drawable.ic_lock, stringResource(R.string.scope_head), stringResource(R.string.scope_sub), "account/scope")
    }
}
