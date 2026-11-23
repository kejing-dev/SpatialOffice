package io.embodit.spatialoffice.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pico.spatial.ui.design.Icon
import com.pico.spatial.ui.design.IconButton
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Switch
import com.pico.spatial.ui.design.Text
import com.pico.spatial.ui.design.menu.Menu
import com.pico.spatial.ui.design.menu.MenuItem
import com.pico.spatial.ui.design.menu.rememberMenuPositionProvider
import com.pico.spatial.ui.design.windows.popup.HorizontalPlacement
import com.pico.spatial.ui.design.windows.popup.VerticalPlacement
import io.embodit.spatialoffice.R
import io.embodit.spatialoffice.core.files.FileKind
import io.embodit.spatialoffice.core.review.CommentKind
import io.embodit.spatialoffice.core.review.ReviewComment
import io.embodit.spatialoffice.core.review.ReviewText
import io.embodit.spatialoffice.core.review.Revision
import io.embodit.spatialoffice.core.review.RevisionKind
import io.embodit.spatialoffice.platform.DeckSession
import io.embodit.spatialoffice.platform.Graph
import io.embodit.spatialoffice.platform.OpenMenus
import io.embodit.spatialoffice.platform.Session
import io.embodit.spatialoffice.platform.TAG
import io.embodit.spatialoffice.platform.typingTarget

/**
 * M5 (v0.7.0) review pane, one component for documents, sheets and slides (design: `reviewPane` in the design package).
 * It sits INSIDE the editor window, at its right edge, as high as the content area: the window keeps its size and its
 * place, so the row of windows around the user does not move. Width 336 dp, padding 16, cards 304.
 * Clickable controls that are always there: close, the two tabs (documents), the text field, Post = 5; a picked card
 * adds Reply / Resolve / More (B31: ≤ 7; a card itself is content, picking another one just moves the pick, D52).
 */
object ReviewLook {
    val Width: Dp = 336.dp
    val Pad: Dp = 16.dp
    val Pane = Color(0x66FFFFFF)
    val Card = Color(0xA6FFFFFF)
    val CardOn = Color(0xFFFFFFFF)
    val Field = Color(0xD9FFFFFF)
    /** Inserted text (someone else's): purple, underlined. */
    val Ins = Color(0xFF5B4CFF)
    /** Deleted text: red, struck through. */
    val Del = Color(0xFFC62F2F)
    /** My own tracked edits: blue. */
    val Mine = Color(0xFF1F5FD0)
    /** Marker on commented text / the quote bar. */
    val Mark = Color(0xFFFFBF00)
    val MarkFill = Color(0x59FFD24D)
    val MarkFillOn = Color(0xB3FFBF00)
    /** Corner marker of a cell: comment / old-style note. */
    val CellThread = Color(0xFF2A6FDB)
    val CellNote = Color(0xFF7A808C)
    /** Avatar colours, picked by the author's name; white initial on each is ≥ 4.5 : 1. */
    val Avatars = listOf(Color(0xFF2A5FD0), Color(0xFF5B4CE0), Color(0xFF0E7C61), Color(0xFFB8430F), Color(0xFFA23A8C), Color(0xFF4A6572))

    /** The colour of [author] among [authors] (the file's people in the order they first appear): everyone their own while there are colours left. */
    fun avatar(author: String, authors: List<String> = emptyList()): Color {
        val i = authors.indexOf(author)
        return Avatars[if (i >= 0) i % Avatars.size else Math.floorMod(author.trim().lowercase().hashCode(), Avatars.size)]
    }

    /** Everyone who wrote a comment, a reply or a tracked change, in order of first appearance. */
    fun authors(comments: List<ReviewComment>, revisions: List<Revision>): List<String> =
        (comments.flatMap { c -> listOf(c.author) + c.replies.map { it.author } } + revisions.map { it.author }).distinct()

    /** `2026-11-22T10:05:00Z` → `11-22 10:05` in the headset's time zone; "" when the file gives no usable time. */
    fun time(iso: String): String = runCatching {
        if (iso.isBlank()) return ""
        val t = runCatching { java.time.Instant.parse(iso) }.getOrElse { java.time.LocalDateTime.parse(iso.take(19)).atZone(java.time.ZoneId.systemDefault()).toInstant() }
        java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(java.time.ZoneId.systemDefault()).format(t)
    }.getOrDefault("")

    fun isRecent(iso: String): Boolean = runCatching { java.time.Duration.between(java.time.Instant.parse(iso), java.time.Instant.now()).seconds in 0..90 }.getOrDefault(false)
}

/** User content inside the pane (a comment, a quote, a name): cut by design, so it does not count as a layout overflow. */
@Composable
private fun Said(text: String, modifier: Modifier = Modifier, maxLines: Int = 2, style: TextStyle = PicoTheme.typography.bodyMedium, color: Color = PicoTheme.colorScheme.labelPrimary, weight: FontWeight? = null) {
    // gate:allow ellipsis-no-fits what a person wrote (comment text, quoted words, author name): shown in full on the picked card
    Text(text = text, modifier = modifier, maxLines = maxLines, overflow = TextOverflow.Ellipsis, style = style, color = color, fontWeight = weight)
}

/** The file's authors, so each keeps one avatar colour all over the pane. */
val LocalReviewAuthors = androidx.compose.runtime.staticCompositionLocalOf<List<String>> { emptyList() }

@Composable
private fun Avatar(author: String, size: Dp = 28.dp) {
    Box(Modifier.size(size).clip(CircleShape).background(ReviewLook.avatar(author, LocalReviewAuthors.current)), contentAlignment = Alignment.Center) {
        Text(ReviewText.initial(author), style = PicoTheme.typography.labelMedium, color = Color.White, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

@Composable
private fun WhoRow(author: String, date: String, tag: String? = null, height: Dp = 28.dp, trailing: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().heightIn(min = height), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Avatar(author, if (height < 28.dp) 24.dp else 28.dp)
        Said(author.ifBlank { "?" }, Modifier.weight(1f, fill = false), maxLines = 1, style = PicoTheme.typography.labelLarge, weight = FontWeight.SemiBold)
        val t = if (ReviewLook.isRecent(date)) tr(R.string.rv_just_now) else ReviewLook.time(date)
        if (t.isNotEmpty()) Txt(t, "review/time", style = PicoTheme.typography.bodySmall, color = PicoTheme.colorScheme.labelSecondary)
        Spacer(Modifier.weight(1f))
        if (tag != null) Tag(tag, "review/kind")
        trailing?.invoke()
    }
}

/** A text box of the pane; [onSend] also runs on Ctrl+Enter. */
@Composable
private fun PaneField(value: String, onChange: (String) -> Unit, hint: String, tag: String, minHeight: Dp = 72.dp, singleLine: Boolean = false, focus: FocusRequester? = null, onSend: (() -> Unit)? = null) {
    Box(
        Modifier.fillMaxWidth().heightIn(min = minHeight, max = 160.dp).clip(RoundedCornerShape(16.dp)).background(ReviewLook.Field).padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        if (value.isEmpty()) Txt(hint, "$tag/hint", style = PicoTheme.typography.bodyMedium, color = PicoTheme.colorScheme.labelSecondary, maxLines = 2)
        BasicTextField(
            value = value, onValueChange = onChange, singleLine = singleLine,
            textStyle = TextStyle(fontSize = 15.sp, lineHeight = 21.sp, color = PicoTheme.colorScheme.labelPrimary),
            cursorBrush = SolidColor(PicoTheme.colorScheme.interaction),
            modifier = Modifier.typingTarget().fillMaxWidth().then(if (focus != null) Modifier.focusRequester(focus) else Modifier)
                .keyShortcut { ctrl, enter -> if (ctrl && enter && onSend != null) { onSend(); true } else false }
                .semantics { contentDescription = tag },
        )
    }
}

private fun Modifier.keyShortcut(f: (ctrl: Boolean, enter: Boolean) -> Boolean): Modifier = this.onPreviewKeyEvent { e ->
    e.type == KeyEventType.KeyDown && f(e.isCtrlPressed, e.key == Key.Enter || e.key == Key.NumPadEnter)
}

/** The pane. [modifier] gives it its place in the window; the width is fixed. */
@Composable
fun ReviewPane(graph: Graph, s: Session, modifier: Modifier = Modifier) {
    val comments = remember(s.version) { runCatching { s.comments() }.onFailure { android.util.Log.w(TAG, "REVIEW comments() failed: $it") }.getOrDefault(emptyList()) }
    val revisions = remember(s.version) { if (s.hasRevisions) runCatching { s.revisions() }.getOrDefault(emptyList()) else emptyList() }
    val open = comments.filter { !it.resolved }
    val me = graph.settings.value.myName
    LaunchedEffect(s.tag, comments.size, revisions.size) {
        android.util.Log.i(TAG, "REVIEW pane shown ${s.kind} ${s.tag} tab=${s.reviewTab} comments=${comments.size} open=${open.size} revisions=${revisions.size} sel=${s.selComment}")
    }
    Column(
        modifier.width(ReviewLook.Width).fillMaxHeight().clip(RoundedCornerShape(28.dp)).background(ReviewLook.Pane).padding(ReviewLook.Pad)
            .semantics { contentDescription = "review-pane" },
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
            Txt(tr(R.string.review), "review/title", Modifier.weight(1f), style = PicoTheme.typography.titleMedium, weight = FontWeight.SemiBold)
            IconButton(onClick = { graph.setReview(s, false) }, modifier = Modifier.noKeyFocus().size(48.dp).semantics { contentDescription = "review-close" }) {
                Icon(painter = painterResource(R.drawable.ic_cross), contentDescription = tr(R.string.review_close), modifier = Modifier.size(20.dp))
            }
        }
        if (s.hasRevisions) {
            // gate:allow tabs-segment two lists inside the review pane (comments / tracked changes), approved at design gate B (M5); the window's pages do not change
            Segments(
                listOf(0 to tr(R.string.rv_comments_n, open.size), 1 to tr(R.string.rv_changes_n, revisions.count { it.canResolve })),
                s.reviewTab, "review/tab", itemWidth = 146.dp,
            ) { s.reviewTab = it }
        }
        val people = remember(comments, revisions) { ReviewLook.authors(comments, revisions) }
        androidx.compose.runtime.CompositionLocalProvider(LocalReviewAuthors provides people) {
            if (s.hasRevisions && s.reviewTab == 1) RevisionList(graph, s, revisions, me)
            else CommentList(graph, s, comments, me)
        }
    }
}

// ------------------------------------------------------------------------------------------------ comments

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.CommentList(graph: Graph, s: Session, all: List<ReviewComment>, me: String) {
    val open = all.filter { !it.resolved }
    val done = all.filter { it.resolved }
    var showDone by remember(s.tag) { mutableStateOf(false) }
    var replying by remember(s.tag) { mutableStateOf<String?>(null) }
    var editing by remember(s.tag) { mutableStateOf<String?>(null) }
    val list = rememberLazyListState()
    // slides: this slide's comments first, then the others under their own heading
    val here = (s as? DeckSession)?.current
    val first = if (here != null) open.filter { it.slide == here } else open
    val rest = if (here != null) open.filter { it.slide != here } else emptyList()
    // a card picked from the content (a tap on marked text, a cell, a bubble) scrolls into view
    LaunchedEffect(s.selComment) {
        val id = s.selComment ?: return@LaunchedEffect
        if (replying != id) replying = null
        if (editing != null && editing != id && all.none { c -> c.id == id && c.replies.any { it.id == editing } }) editing = null
        val idx = (first + rest).indexOfFirst { it.id == id }
        if (idx >= 0) runCatching { list.animateScrollToItem(idx + (if (here != null) 1 else 0) + (if (here != null && idx >= first.size) 1 else 0)) }
    }
    // the reply / edit box opens at the bottom of its card: the list moves up until the whole card (its Post button) shows
    LaunchedEffect(replying, editing) {
        val id = replying ?: s.selComment?.takeIf { editing != null } ?: return@LaunchedEffect
        kotlinx.coroutines.delay(180)
        val info = list.layoutInfo
        val item = info.visibleItemsInfo.firstOrNull { it.key == id } ?: return@LaunchedEffect
        val over = item.offset + item.size - info.viewportEndOffset
        val room = item.offset - info.viewportStartOffset
        if (over > 0) runCatching { list.animateScrollBy(minOf(over, room).toFloat()) }
    }
    LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (open.isEmpty() && done.isEmpty()) item("empty") {
            val msg = when (s.editor) { FileKind.SHEET -> R.string.rv_empty_sheet; FileKind.SLIDES -> R.string.rv_empty_slides; else -> R.string.rv_empty_doc }
            Box(Modifier.fillParentMaxSize().padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
                Txt(tr(msg), "review/empty", style = PicoTheme.typography.bodyMedium, color = PicoTheme.colorScheme.labelSecondary, maxLines = 4, align = TextAlign.Center)
            }
        }
        if (here != null && (first.isNotEmpty() || rest.isNotEmpty())) item("h-here") { GroupLabel(tr(R.string.rv_slide_n, here + 1)) }
        items(first.size, key = { first[it].id }) { i ->
            CommentCard(graph, s, first[i], me, replying == first[i].id, editing, { replying = it }, { editing = it })
        }
        if (rest.isNotEmpty()) item("h-rest") { GroupLabel(tr(R.string.rv_other_slides)) }
        items(rest.size, key = { rest[it].id }) { i ->
            CommentCard(graph, s, rest[i], me, replying == rest[i].id, editing, { replying = it }, { editing = it })
        }
        if (done.isNotEmpty()) item("fold") {
            Row(
                Modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(14.dp)).noKeyFocus().clickable { showDone = !showDone }.padding(horizontal = 6.dp)
                    .semantics { contentDescription = "review-resolved-fold" },
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(painter = painterResource(if (showDone) R.drawable.ic_arrow_down else R.drawable.ic_arrow_right), contentDescription = null, modifier = Modifier.size(18.dp), tint = PicoTheme.colorScheme.labelSecondary)
                Txt(tr(R.string.rv_resolved_n, done.size), "review/resolved", style = PicoTheme.typography.bodyMedium, color = PicoTheme.colorScheme.labelSecondary)
            }
        }
        if (showDone) items(done.size, key = { "done-" + done[it].id }) { i -> ResolvedCard(graph, s, done[i]) }
    }
    if (!s.readOnly) Composer(graph, s, me)
}

@Composable
private fun GroupLabel(text: String) {
    Txt(text, "review/group", Modifier.padding(start = 6.dp, top = 2.dp), style = PicoTheme.typography.labelLarge, color = PicoTheme.colorScheme.labelSecondary)
}

/** Where the comment sits, as the card's quote line. */
@Composable
private fun placeOf(s: Session, c: ReviewComment): String = when (s.editor) {
    FileKind.SHEET -> s.commentPlace(c)
    FileKind.SLIDES -> if (c.slide >= 0 && c.slide != (s as? DeckSession)?.current) tr(R.string.rv_slide_n, c.slide + 1) else ""
    else -> c.quote.replace('\n', ' ').trim()
}

@Composable
private fun CommentCard(
    graph: Graph, s: Session, c: ReviewComment, me: String, replying: Boolean, editing: String?,
    setReplying: (String?) -> Unit, setEditing: (String?) -> Unit,
) {
    val on = s.selComment == c.id
    val note = c.kind == CommentKind.NOTE
    val shape = RoundedCornerShape(18.dp)
    Column(
        Modifier.fillMaxWidth().clip(shape).background(if (on) ReviewLook.CardOn else ReviewLook.Card)
            .then(if (on) Modifier.border(2.dp, PicoTheme.colorScheme.interaction, shape) else Modifier)
            .noKeyFocus().clickable { s.pickComment(c.id); s.showComment(c) }
            .padding(horizontal = 14.dp, vertical = 12.dp)
            .semantics { contentDescription = "review-card" },
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        WhoRow(c.author, c.date, tag = if (note) tr(if (s.editor == FileKind.SLIDES) R.string.rv_legacy else R.string.rv_note) else null)
        val place = placeOf(s, c)
        if (place.isNotEmpty()) Row(Modifier.fillMaxWidth().height(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(3.dp).fillMaxHeight().background(ReviewLook.Mark))
            Said(place, Modifier.padding(start = 8.dp), maxLines = 1, style = PicoTheme.typography.bodySmall, color = PicoTheme.colorScheme.labelSecondary)
        }
        if (editing == c.id) EditBox(c.text, onDone = { t -> setEditing(null); if (t != null && t.isNotBlank() && t != c.text) s.editComment(c.id, t.trim()) })
        else Said(c.text, maxLines = if (on) 6 else 2)
        if (note && on) Txt(tr(if (s.editor == FileKind.SLIDES) R.string.rv_legacy_sub else R.string.rv_note_sub), "review/note-sub", style = PicoTheme.typography.bodySmall, color = PicoTheme.colorScheme.labelSecondary, maxLines = 2)
        c.replies.forEach { r ->
            Column(Modifier.fillMaxWidth().padding(start = 36.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0x0F000000)))
                Spacer(Modifier.height(4.dp))
                WhoRow(r.author, r.date, height = 24.dp)
                if (editing == r.id) EditBox(r.text, onDone = { t -> setEditing(null); if (t != null && t.isNotBlank() && t != r.text) s.editComment(r.id, t.trim()) })
                else Said(r.text, maxLines = if (on) 6 else 2)
            }
        }
        if (!on || s.readOnly) return@Column
        if (replying && !note) ReplyBox(graph, s, c, me) { setReplying(null) }
        // gate:allow button-gap the picked card's three actions in a 276 dp row: 48 dp targets 8 dp apart, as the editor toolbar (design M5 reviewPane.card)
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!note) {
                ToolButton(tr(R.string.rv_reply), "review/reply", icon = R.drawable.ic_reply, name = "card-reply", onClick = { setReplying(if (replying) null else c.id) })
                ToolButton(tr(R.string.rv_resolve), "review/resolve", icon = R.drawable.ic_check, primary = true, name = "card-resolve", onClick = {
                    if (s.resolveComment(c.id, true)) { s.selComment = null; graph.say(s, graph.str(R.string.rv_resolved_snack)) }
                })
            } else {
                ToolButton(tr(R.string.rv_edit), "review/edit", name = "card-edit", onClick = { setEditing(c.id) })
                if (s.editor == FileKind.SLIDES) ToolButton(tr(R.string.rv_write_new), "review/write-new", onClick = { s.selComment = null })
            }
            Spacer(Modifier.weight(1f))
            CardMenu(
                edit = if (note) null else ({ setEditing(c.replies.lastOrNull { it.author == me && me.isNotBlank() }?.id ?: c.id) }),
                delete = { if (s.deleteComment(c.id)) { s.selComment = null; graph.say(s, graph.str(R.string.rv_deleted_snack), graph.str(R.string.undo_short)) { s.undo() } } },
            )
        }
    }
}

@Composable
private fun CardMenu(edit: (() -> Unit)?, delete: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        ToolIcon(R.drawable.ic_ellipsis, tr(R.string.rv_more), onClick = { open = !open })
        val key = remember { Any() }
        DisposableEffect(open) {
            if (open) OpenMenus.opened(key) { open = false }
            onDispose { OpenMenus.closed(key) }
        }
        if (open) Menu(
            onDismissRequest = { open = false },
            positionProvider = rememberMenuPositionProvider(horizontalPlacement = HorizontalPlacement.alignEnd(), verticalPlacement = VerticalPlacement.below(offset = 8.dp)),
        ) {
            if (edit != null) MenuItem(title = { Txt(tr(R.string.rv_edit), "review/menu") }, onClick = { open = false; edit() }, modifier = Modifier.noKeyFocus())
            MenuItem(title = { Txt(tr(R.string.rv_delete), "review/menu") }, onClick = { open = false; delete() }, modifier = Modifier.noKeyFocus())
        }
    }
}

/** Changing the text of a comment, reply or note in place. [onDone] gets the new text, or null when cancelled. */
@Composable
private fun EditBox(text: String, onDone: (String?) -> Unit) {
    var v by remember { mutableStateOf(text) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        PaneField(v, { v = it }, "", "review-edit-field", minHeight = 48.dp, focus = focus, onSend = { onDone(v) })
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.End)) {
            ToolButton(tr(R.string.cancel), "review/edit-cancel", onClick = { onDone(null) })
            ToolButton(tr(R.string.rv_save_edit), "review/edit-save", primary = true, name = "card-edit-save", enabled = v.isNotBlank(), onClick = { onDone(v) })
        }
    }
}

@Composable
private fun ReplyBox(graph: Graph, s: Session, c: ReviewComment, me: String, close: () -> Unit) {
    var v by remember(c.id) { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(c.id) { runCatching { focus.requestFocus() } }
    val send = {
        if (v.isNotBlank() && me.isNotBlank() && s.replyComment(c.id, me, v.trim())) { v = ""; close() }
    }
    Column(Modifier.fillMaxWidth().padding(start = 36.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        PaneField(v, { v = it }, tr(R.string.rv_reply_ph), "review-reply-field", minHeight = 48.dp, focus = focus, onSend = send)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Said(if (me.isBlank()) tr(R.string.my_name_ask) else tr(R.string.rv_as_me, me), Modifier.weight(1f), maxLines = 1, style = PicoTheme.typography.bodySmall, color = PicoTheme.colorScheme.labelSecondary)
            ToolButton(tr(R.string.rv_post), "review/reply-post", icon = R.drawable.ic_send, primary = true, name = "post-reply", enabled = v.isNotBlank() && me.isNotBlank(), onClick = send)
        }
    }
}

@Composable
private fun ResolvedCard(graph: Graph, s: Session, c: ReviewComment) {
    Column(
        Modifier.fillMaxWidth().alpha(0.78f).clip(RoundedCornerShape(18.dp)).background(ReviewLook.Card).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        WhoRow(c.author, "", height = 48.dp) {
            if (!s.readOnly) ToolButton(tr(R.string.rv_reopen), "review/reopen", name = "card-reopen", onClick = { if (s.resolveComment(c.id, false)) s.pickComment(c.id) })
        }
        Said(c.text, maxLines = 2)
    }
}

/** Bottom of the comment list: the text box and Post; the first time, the one question about the name. */
@Composable
private fun Composer(graph: Graph, s: Session, me: String) {
    val text = s.draft
    var name by remember(s.tag) { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    // Ctrl+Alt+M / Insert › Comment put the caret here
    LaunchedEffect(s.composeTick) { if (s.composeTick > 0) runCatching { focus.requestFocus() } }
    val send: () -> Unit = { postDraft(graph, s) }
    Column(Modifier.fillMaxWidth().semantics { contentDescription = "review-composer" }, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (me.isBlank()) {
            Txt(tr(R.string.my_name_ask), "review/ask-name", style = PicoTheme.typography.labelLarge, weight = FontWeight.SemiBold, maxLines = 2)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Box(Modifier.weight(1f)) { PaneField(name, { name = it.take(40) }, tr(R.string.my_name_ph), "review-name-field", minHeight = 48.dp, singleLine = true, onSend = { if (name.isNotBlank()) graph.setMyName(name) }) }
                ToolButton(tr(R.string.my_name_use), "review/use-name", primary = true, enabled = name.isNotBlank(), onClick = { graph.setMyName(name) })
            }
            Txt(tr(R.string.my_name_ask_sub), "review/ask-name-sub", style = PicoTheme.typography.bodySmall, color = PicoTheme.colorScheme.labelSecondary, maxLines = 2)
        }
        val hint = when {
            s.editor == FileKind.SHEET -> tr(R.string.rv_compose_cell, s.commentTargetName())
            s.editor == FileKind.SLIDES -> tr(if (s.commentOnSelection) R.string.rv_compose_obj else R.string.rv_compose_slide)
            else -> tr(if (s.commentOnSelection) R.string.rv_compose_sel else R.string.rv_compose_para)
        }
        PaneField(text, { s.draft = it }, hint, "review-field", focus = focus, onSend = send)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Said(tr(R.string.rv_as_me, me.ifBlank { "—" }), Modifier.weight(1f), maxLines = 1, style = PicoTheme.typography.bodySmall, color = PicoTheme.colorScheme.labelSecondary)
            ToolButton(tr(R.string.rv_post), "review/post", icon = R.drawable.ic_send, primary = true, name = "post-comment", enabled = text.isNotBlank() && me.isNotBlank(), onClick = send)
        }
    }
}

/** [Post] / Ctrl+Enter: the draft becomes a comment at the current place (needs a name and some text). */
fun postDraft(graph: Graph, s: Session): Boolean {
    val me = graph.settings.value.myName
    if (s.draft.isBlank() || me.isBlank()) return false
    val id = s.addComment(me, s.draft.trim())
    if (id == null) { graph.say(s, graph.str(s.reviewHint ?: R.string.rv_cant_here)); return false }
    s.draft = ""; s.composing = false; s.pickComment(id)
    return true
}

/**
 * Keys that arrive at an editor's toolbar while a comment is being written ([Session.composing]): characters go to the
 * draft, Backspace removes one, Ctrl+Enter posts, Esc stops (the draft stays). True when the key was used.
 */
fun composeKeys(graph: Graph, s: Session, e: androidx.compose.ui.input.key.KeyEvent): Boolean {
    if (!s.composing || !s.reviewOpen || e.type != KeyEventType.KeyDown) return false
    val enter = e.key == Key.Enter || e.key == Key.NumPadEnter
    if (e.isCtrlPressed) return if (enter) { postDraft(graph, s); true } else false
    if (e.nativeKeyEvent.isAltPressed) return false
    return when {
        e.key == Key.Escape -> { s.composing = false; true }
        e.key == Key.Backspace -> { if (s.draft.isNotEmpty()) s.draft = s.draft.substring(0, s.draft.offsetByCodePoints(s.draft.length, -1)); true }
        enter -> { s.draft += "\n"; true }
        else -> { val cp = e.nativeKeyEvent.unicodeChar; if (cp >= 32) { s.draft += String(Character.toChars(cp)); true } else false }
    }
}

// ------------------------------------------------------------------------------------------------ tracked changes

/** The text of a revision the way the paper draws it: inserted = underlined, deleted = struck through. */
fun revisionText(r: Revision, mine: Boolean): AnnotatedString = buildAnnotatedString {
    val shown = r.text.replace('\n', ' ').ifBlank { "¶" }
    when (r.kind) {
        RevisionKind.INSERT -> withStyle(SpanStyle(color = if (mine) ReviewLook.Mine else ReviewLook.Ins, textDecoration = TextDecoration.Underline, fontWeight = FontWeight.SemiBold)) { append(shown) }
        RevisionKind.DELETE -> withStyle(SpanStyle(color = ReviewLook.Del, textDecoration = TextDecoration.LineThrough, fontWeight = FontWeight.SemiBold)) { append(shown) }
        else -> append(shown)
    }
}

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.RevisionList(graph: Graph, s: Session, all: List<Revision>, me: String) {
    var sel by remember(s.tag) { mutableStateOf<String?>(null) }
    // the whole row toggles (the bare switch is far smaller than 48 dp)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).clip(RoundedCornerShape(18.dp)).background(ReviewLook.Card)
            .noKeyFocus().toggleable(value = s.tracking, enabled = !s.readOnly, role = Role.Switch) { on ->
                if (graph.setTracking(s, on)) graph.say(s, graph.str(if (on) R.string.rv_track_on else R.string.rv_track_off))
            }
            .padding(horizontal = 14.dp, vertical = 10.dp).semantics { contentDescription = "review-track" },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Txt(tr(R.string.rv_track), "review/track", style = PicoTheme.typography.labelLarge, weight = FontWeight.SemiBold)
            if (me.isBlank()) Txt(tr(R.string.rv_track_sub_noname), "review/track-sub", style = PicoTheme.typography.bodySmall, color = PicoTheme.colorScheme.labelSecondary, maxLines = 2)
            else Said(tr(R.string.rv_track_sub, me), maxLines = 2, style = PicoTheme.typography.bodySmall, color = PicoTheme.colorScheme.labelSecondary)
        }
        Switch(checked = s.tracking, onCheckedChange = null)
    }
    if (me.isBlank() && !s.readOnly) NameAsk(graph)
    LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (all.isEmpty()) item("empty") {
            Box(Modifier.fillParentMaxSize().padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
                Txt(tr(R.string.rv_empty_changes), "review/empty-changes", style = PicoTheme.typography.bodyMedium, color = PicoTheme.colorScheme.labelSecondary, maxLines = 4, align = TextAlign.Center)
            }
        }
        items(all.size, key = { all[it].id }) { i ->
            val r = all[i]
            val on = sel == r.id
            val shape = RoundedCornerShape(18.dp)
            Column(
                Modifier.fillMaxWidth().clip(shape).background(if (on) ReviewLook.CardOn else ReviewLook.Card)
                    .then(if (on) Modifier.border(2.dp, PicoTheme.colorScheme.interaction, shape) else Modifier)
                    .noKeyFocus().clickable { sel = r.id; s.showRevision(r) }
                    .padding(horizontal = 14.dp, vertical = 12.dp).semantics { contentDescription = "review-revision" },
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                val kind = when (r.kind) { RevisionKind.INSERT -> tr(R.string.rv_added); RevisionKind.DELETE -> tr(R.string.rv_removed); else -> null }
                WhoRow(r.author, r.date, tag = kind)
                when (r.kind) {
                    RevisionKind.FORMAT -> Txt(tr(R.string.rv_fmt_changed), "review/fmt", style = PicoTheme.typography.bodyMedium, color = PicoTheme.colorScheme.labelSecondary, maxLines = 2)
                    RevisionKind.MOVE -> Txt(tr(R.string.rv_moved), "review/moved", style = PicoTheme.typography.bodyMedium, color = PicoTheme.colorScheme.labelSecondary, maxLines = 2)
                    // gate:allow ellipsis-no-fits the changed words of the document: two lines on the card, all of them on the picked card and on the paper
                    else -> Text(revisionText(r, r.author == me && me.isNotBlank()), maxLines = if (on) 8 else 2, overflow = TextOverflow.Ellipsis, style = PicoTheme.typography.bodyMedium, color = PicoTheme.colorScheme.labelPrimary)
                }
                if (on && r.canResolve && !s.readOnly) Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    ToolButton(tr(R.string.rv_accept), "review/accept", icon = R.drawable.ic_check, primary = true, name = "change-accept", onClick = { if (s.acceptRevision(r.id)) sel = null })
                    ToolButton(tr(R.string.rv_reject), "review/reject", icon = R.drawable.ic_undo, name = "change-reject", onClick = { if (s.rejectRevision(r.id)) sel = null })
                }
            }
        }
    }
    if (!s.readOnly) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        val any = all.any { it.canResolve }
        ToolButton(tr(R.string.rv_accept_all), "review/accept-all", icon = R.drawable.ic_check, enabled = any, width = 144.dp, name = "all-accept", onClick = {
            val n = s.acceptAllRevisions()
            if (n > 0) graph.say(s, graph.str(R.string.rv_accepted_n, n), graph.str(R.string.undo_short)) { s.undo() }
        })
        ToolButton(tr(R.string.rv_reject_all), "review/reject-all", icon = R.drawable.ic_undo, enabled = any, width = 144.dp, name = "all-reject", onClick = {
            val n = s.rejectAllRevisions()
            if (n > 0) graph.say(s, graph.str(R.string.rv_rejected_n, n), graph.str(R.string.undo_short)) { s.undo() }
        })
    }
}

/** The one-time question about the name, on the changes list (the comment list has it in its composer). */
@Composable
private fun NameAsk(graph: Graph) {
    var name by remember { mutableStateOf("") }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Txt(tr(R.string.my_name_ask), "review/ask-name", style = PicoTheme.typography.labelLarge, weight = FontWeight.SemiBold, maxLines = 2)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Box(Modifier.weight(1f)) { PaneField(name, { name = it.take(40) }, tr(R.string.my_name_ph), "review-name-field", minHeight = 48.dp, singleLine = true, onSend = { if (name.isNotBlank()) graph.setMyName(name) }) }
            ToolButton(tr(R.string.my_name_use), "review/use-name", primary = true, enabled = name.isNotBlank(), onClick = { graph.setMyName(name) })
        }
    }
}

/** Title bar [Review n]: opens / closes the pane; lit while it is open. */
@Composable
fun ReviewButton(graph: Graph, s: Session) {
    if (!s.canReview) return
    val n = remember(s.version) { s.reviewCount() }
    ToolButton(if (n > 0) tr(R.string.review_n, n) else tr(R.string.review), "editor/review", icon = R.drawable.ic_review, primary = s.reviewOpen, onClick = { graph.toggleReview(s) })
}
