# `:core` docx — M5 "Review" API

Package `io.embodit.spatialoffice.core.docx` unless said otherwise. Everything below is new in M5 or changed by it.
Tests: `core/src/test/kotlin/…/docx/DocxM5Test.kt` (25 tests). Independent check: `tools/verify/m5_docx_verify.py`.

## Read this first: one thing the app must change

`Run` and `Span` both have a new last field `rev: RunRev?`. `Sessions.kt` builds spans and runs with 11 positional
arguments, so today it drops `rev`. **Until that mapping carries `rev`, the first keystroke in a paragraph that has
tracked changes writes the deleted text back as ordinary text and loses the insertion marks.** Use the two converters
(they carry every field, also future ones):

```kotlin
fun Run.toSpan(): Span          // core.docx (Tracking.kt)
fun Span.toRun(): Run
// in DocSession:  b.runs.map { it.toSpan() }   and   spans.map { it.toRun() }
```

## Model changes

```kotlin
// core.text (RichText.kt)
data class RunRev(val kind: RevisionKind, val author: String, val date: String = "")   // kind: INSERT or DELETE only
data class Span(…, val vertAlign: String? = null, val rev: RunRev? = null)

// core.docx (Model.kt)
data class Run(…, val vertAlign: String? = null, val rev: RunRev? = null)
data class Paragraph(…) {
    val text: String        // CHANGED: now includes the text of tracked deletions (it is on the paper, struck through)
    val cleanText: String   // NEW: text without tracked deletions = what the document says
}
```

- Draw `rev.kind == INSERT` underlined, `DELETE` struck through; `rev.author` / `rev.date` for the tooltip or colour.
- **Every character offset in this API counts in `Paragraph.text`** (deleted text included), which is also what the
  text field holds. Nothing is hidden from the text field, so offsets and caret positions need no translation.
- `firstLine()` and `plainText()` use `cleanText` (so word count, file-name suggestion and "save as text" leave
  deleted text out). Find / replace in the app runs over span text and therefore sees struck-through text too.
- `RichText`: `sameFormat` compares `rev` (a tracked span never merges with its neighbour); text produced by
  `applyEdit`, `replaceAll`, `replaceAt` gets `rev = null` (typed text never inherits a tracked mark). `clearFormat`,
  `setAttr`, `setLink`, … keep `rev`.
- A paragraph with tracked insertions / deletions of text, comment anchors, or both is now `editable = true`
  (before: `"tracked-changes"` / `"complex"`). Still read-only with `readOnlyWhy = "tracked-changes"`: a paragraph with
  a tracked move, a tracked format change (`w:rPrChange`, `w:pPrChange` …), a tracked paragraph mark, or an insertion
  nested in a deletion (or the reverse). Its `text` is as before (inserted text shown, deleted text not).
- `compat()`: the `"comments"` note is gone. `"tracked-changes"` is now the number of paragraphs that are still
  read-only for that reason (no note when there are none); before it counted insert / delete marks.

## A. Comments (d29)

```kotlin
fun comments(): List<ReviewComment>
fun addComment(blockId: Int, start: Int, end: Int, author: String, text: String, dateIso: String): String
fun replyComment(id: String, author: String, text: String, dateIso: String): String
fun setCommentResolved(id: String, resolved: Boolean)
fun editComment(id: String, text: String)
fun deleteComment(id: String)
```

- `comments()` — threads in the order the file lists them (not sorted by resolved; use `ReviewText.ordered`). Always
  `kind = THREAD`. `block` = id of the paragraph the range starts in, `start` / `end` = offsets in that paragraph's
  `text`; a range that runs on into later paragraphs has `end` = length of the first one and the whole commented
  text in `quote` (paragraphs joined with `\n`). `quote` leaves tracked deletions out. Replies are nested. Cached
  until the next change.
- `block = -1`: the comment is anchored in a table cell (quote still filled) or has no anchor in the text at all
  (quote ""). Reply / resolve / edit / delete work on those too. A comment in a read-only paragraph has that
  paragraph's id and offsets in its `text`.
- An empty range (`start == end`, quote "") is a real state, not "whole paragraph": the commented text was deleted
  and the comment stayed at that place. (`ReviewComment`'s doc reads `start == end` as the whole paragraph — see the
  last section.)
- `addComment` — `start == end` comments the whole paragraph; offsets are clamped and may come in either order.
  A read-only paragraph can be commented (always as a whole, offsets ignored). Tables, pictures and opaque blocks:
  `IllegalStateException`. Returns the id. `text` may hold `\n` (one paragraph per line).
- `replyComment` — `id` may be a thread's first comment or one of its replies; the reply always joins the thread.
  Unknown id: `IllegalArgumentException`. Same for `setCommentResolved` and `editComment`.
- `setCommentResolved` — works with any id of the thread; `comments()` reports the state on the thread.
- `editComment` — root or reply; author, date and place stay.
- `deleteComment` — a root id removes the thread and all its anchors; a reply id removes that reply. Unknown id: no-op.
- **Ids** are the file's own `w:id` as a decimal string ("0", "12"). They are stable across edits, save and reopen —
  so across undo too, which reopens saved bytes — and unique among comments and replies.
- **Editing text needs nothing from the caller.** `setParagraph(id, runs, style, align)` (signature unchanged)
  compares the paragraph's old and new text (shared start, shared end, one changed stretch) and moves every comment
  anchor and bookmark end with the characters around it:
  typing before a range shifts it; typing at either edge stays outside; typing inside lengthens it; replacing exactly
  the commented text keeps the comment on the replacement; deleting all of it leaves an empty range there.
- Split (Enter) as the app does it today (`setParagraph(left)` + `insertParagraphAfter(right)`): anchors behind the
  cut stay with the left paragraph, at its end — the comment survives, its range may shrink to what is left of it.
- `deleteBlock` of a block holding comment anchors (join with Backspace, `deleteCross`): the anchors move, as empty
  ranges, to the end of the editable paragraph before it, else to the start of the one after it. With no such
  neighbour the comments are deleted with the block.
- `setTableCell` keeps the comments anchored in that cell. Deleting a table row / column deletes the comments
  anchored only in it.
- Parts: the comments part and the extended part (replies, resolved) are created on the first `addComment`, with
  relationship and content type; a comment read from an older file gets its entry in the extended part the first
  time it is answered or resolved. When the file has the two newer id parts, every new comment gets its entry there
  too and a deleted one loses it; they are never created.

## B. Tracked changes (d30)

```kotlin
fun revisions(): List<Revision>
fun acceptRevision(id: String): Boolean
fun rejectRevision(id: String): Boolean
fun acceptAll(): Int
fun rejectAll(): Int
```

- `revisions()` — reading order. Neighbouring insertions (or deletions) by one author in one paragraph are one
  revision, also when the file stores them as several elements. `block` = id of the paragraph, or of the table for a
  change inside a cell; -1 for a tracked change of the page setup. `text` is "" for FORMAT.
  FORMAT = tracked format change, paragraph mark, table row / cell change; MOVE = moved text. Both are listed only.
- Accept INSERT → ordinary text; reject INSERT → text gone; accept DELETE → text gone; reject DELETE → ordinary text.
  Only that change's own elements are rewritten; changes inside table cells resolve too. False for unknown ids and
  for FORMAT / MOVE. A comment anchored on text that goes stays, as an empty range.
- `acceptAll` / `rejectAll` return the number of INSERT / DELETE revisions that are gone afterwards.
- **Revision ids** ("r" + the file's `w:id`, e.g. "r102") stay the same while their paragraph is not rewritten, and
  across save / reopen. Treat them as valid until the next change and read `revisions()` again after every change
  (it is cached).
- A paragraph that becomes free of read-only content after accept / reject turns editable at once.

## C. Recording my own changes (d36)

```kotlin
object Tracking {
    data class Result(val runs: List<Run>, val caret: Int, val deletedEnd: Int)
    fun merge(old: List<Run>, new: List<Run>, author: String, dateIso: String): List<Run>
    fun mergeAt(old: List<Run>, new: List<Run>, author: String, dateIso: String): Result
    fun mergeSpans(old: List<Span>, new: List<Span>, author: String, dateIso: String): List<Span>
    fun text(runs: List<Run>): String
    fun slice(runs: List<Run>, from: Int, to: Int): List<Run>
    fun runAt(runs: List<Run>, index: Int): Run?
    fun normalize(runs: List<Run>): List<Run>
    fun common(a: String, b: String): Pair<Int, Int>     // shared prefix / suffix lengths, same rule as RichText.applyEdit
}
```

The switch and the author name live in the app; nothing is written to the settings part. Use:

```kotlin
val old = paragraph.runs                                    // before the edit
val edited = RichText.applyEdit(spans, newText).map { it.toRun() }   // the ordinary editor
val runs = if (tracking) Tracking.merge(old, edited, author, nowIso) else edited
doc.setParagraph(id, runs, style)
```

- Removed text stays as a DELETE run by `author`; exceptions: the author's own INSERT is really removed, text that is
  already DELETE stays as it is. New text becomes an INSERT run by `author`; typed inside or next to the author's own
  insertion it joins that insertion (same mark, same date). A change of format only is returned as given (untracked).
- **The stored text is longer than what the text field reported** (the removed text is still there). After a
  tracked edit set the field from the paragraph again and put the caret at `mergeAt(…).caret`: behind the inserted
  text, or — when text was only removed — in front of the struck-through stretch (right for Backspace). After
  forward Delete use `deletedEnd`, otherwise the next Delete hits the same, already deleted character and nothing moves.
- The comparison is by characters: replacing "jumps" by "leaps" tracks "jum" → "lea" (the shared "ps" stays). Edits
  arriving key by key do not show this.
- Deleting another author's tracked insertion turns it into a deletion by `author` (the other author's mark is not
  kept underneath).
- `setParagraph` / `insertParagraphAfter` write runs with `rev` as `<w:ins w:id w:author w:date>` /
  `<w:del …>` with `w:delText`. Ids are new and above every `w:id` in the document; a change that was already in the
  paragraph keeps its id.
- **Split / join while tracking**: apply them untracked, exactly as today (`setParagraph` + `insertParagraphAfter`,
  `setParagraph` + `deleteBlock`); pass the runs through with their `rev` (use `Tracking.slice`) and do not call
  `merge` for them — `merge` would record the moved half as deleted. Tracked paragraph marks are not written.
  Covered by `newIdsAvoidEveryIdInTheDocumentAndSplittingATrackedParagraphWorks`.

## D. Header and footer (d31)

```kotlin
fun headerText(): String?
fun footerText(): String?
val headerEditable: Boolean
val footerEditable: Boolean
fun setHeaderFooter(header: String?, footer: String?): Boolean
```

- The default header / footer of the last section (the body-level section properties). null = none. Several
  paragraphs read as lines (`\n`), tabs as `\t`. First-page and even-page headers are neither read nor changed.
- `…Editable` is false when the existing part holds a field (page number), table, picture or anything but plain
  paragraphs; true when there is none yet.
- `setHeaderFooter` — null leaves that one alone; "" empties an existing one and creates nothing. A missing part is
  created (`header1.xml` / `footer1.xml` or the next free number) with relationship, content type and the reference
  placed first in the section properties (which are created when the body has none). An existing plain one keeps
  its paragraph properties and first run's formatting. **A non-editable one is skipped silently** (no exception).
  Returns true when something changed (the brief had `Unit`; the result can be ignored).

## E. Format painter (d24)

```kotlin
class RunFormat { val bold; val italic; val underline; val strike: Boolean; val color: Int?; val highlight: String?; val size: Int?; val vertAlign: String? }
fun runFormatAt(blockId: Int, offset: Int): RunFormat
fun paintFormat(format: RunFormat, toBlockId: Int, start: Int, end: Int): Boolean
fun paintFormat(fromBlockId: Int, fromOffset: Int, toBlockId: Int, start: Int, end: Int): Boolean
```

- `runFormatAt` copies the complete character formatting of the character at `offset` (past the end: the last
  character's; works on read-only paragraphs too). The token is a copy: it survives edits of its source, can be
  applied any number of times, and stays usable after undo / redo of the same file.
- `paintFormat` — `start == end` paints the whole paragraph; false when there is nothing to paint (empty paragraph).
  The target must be an editable paragraph (`IllegalStateException` otherwise). Links, comments and tracked marks of
  the target stay. Afterwards the model (`bold`, `color`, `size` …) says what the file says, also after reopen.
- Painting from a link never copies the link itself (and not the link's character style).

## Other things the UI should know

- Block ids are unchanged by all of the above (accept / reject, comments, paint never renumber blocks).
- `Run.src` values of a paragraph change after accept / reject, reply, delete-comment and paint; read `blocks` again
  after any of these instead of reusing spans held from before.
- Where an anchor sits between two characters of one run, the run is stored in two; after reopen the paragraph has
  more runs with the same formatting. Harmless, but do not compare run lists for equality across a save.
- Deleted text is written with `xml:space="preserve"` only where it needs it, like ordinary text, so that a
  paragraph rewritten without changes is byte-identical to what the desktop suites wrote.

## Known gaps

- FORMAT and MOVE revisions cannot be accepted or rejected, and their paragraphs stay read-only.
- A tracked insertion nested in a deletion (or the reverse) keeps its paragraph read-only; both changes are listed
  and can be resolved one by one, after which the paragraph is editable.
- Enter / Backspace at a paragraph edge is never recorded as a tracked change.
- `setTableCell` on a cell with tracked changes writes the visible text back as ordinary text (as before M5).
- Splitting a commented paragraph leaves the comment on the left half (see A); there is no `splitParagraph` that
  hands anchors to the right half.
- Comments cannot be added to a table cell, picture or opaque block.
- No file was opened in a desktop suite by a person. Checked: python-docx, lxml and the system's text converter read
  all 37 saved files.

## `review/Review.kt`

Nothing had to change. One wording to consider: `ReviewComment` says "`start == end`: the whole paragraph". That is
the convention for `addComment`'s arguments; in what `comments()` returns, `start == end` means an empty range (see A).
