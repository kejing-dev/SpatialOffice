# `:core` xlsx — M5 (s35) comments and notes on cells

Code: `core/xlsx/Comments.kt` (new: `CommentStore`, `CommentDates`, both internal) and thin members on
`XlsxWorkbook`. Model: `core/review/Review.kt` (`ReviewComment`, `ReviewReply`, `CommentKind`), used as it is.
Tests: `XlsxM5Test` (22). Independent check: `tools/verify/m5_xlsx_verify.py` (reads `core/build/m5-xlsx/`).

## Added to `XlsxWorkbook`

```kotlin
fun comments(sheet: Int): List<ReviewComment>            // one sheet, by cell: top to bottom, left to right
fun comments(): List<ReviewComment>                      // every sheet, in sheet order
fun commentAt(sheet: Int, cell: String): ReviewComment?  // "C2" ("$c$2" is read too); null when the cell has none
fun comment(id: String): ReviewComment?                  // by id; a reply's id gives the thread that holds it
fun commentCells(sheet: Int): Map<String, CommentKind>   // "C2" -> THREAD | NOTE, for the corner marker
fun addComment(sheet: Int, cell: String, author: String, text: String, dateIso: String): String
fun replyComment(id: String, author: String, text: String, dateIso: String): String
fun setCommentResolved(id: String, resolved: Boolean)
fun editComment(id: String, text: String)
fun deleteComment(id: String)
```

`ReviewComment` for a sheet: `sheet` = sheet index, `cell` = `quote` = the A1 reference, `kind`, `author`, `date`,
`text`, `resolved`, `replies` (THREAD only; a NOTE has `resolved = false`, no replies, `date = ""`).

## What the UI needs to know

**Ids.** Unique in the workbook, the same for as long as this `XlsxWorkbook` object lives (across `save()`, row /
column changes, sorting, sheet add / rename / delete).
- Thread and reply: the GUID the file holds (`{XXXXXXXX-XXXX-…}`), so it is also the same after save + reopen and
  after undo (undo reopens the bytes).
- Note: `note:<sheet index>:<cell>` taken when the note is first read; it stays with the note when its cell moves
  (`note:0:B3` can sit on B4). After a reopen (undo included) it is made again from where the note is then, so a
  note that moved has a different id in the reopened object. Find it again with `commentAt`.
- `sheet` in a `ReviewComment` is the index now: it changes when a sheet in front is deleted; ids do not.

**`addComment`.**
- Makes a THREAD and returns its id.
- The cell already has a thread → the text is added as a reply and the **reply's** id comes back (use `comment(id)`
  to get the thread).
- The cell has a NOTE → `IllegalStateException("cell has a note")`, nothing changes. Route to `editComment(note.id, …)`.
- A cell inside a merged range → the comment goes on the range's first cell (`comment(id)!!.cell` says where).
- `cell` that is not an A1 reference → `IllegalArgumentException`; bad sheet index → `IndexOutOfBoundsException`.
- There is no call that creates a NOTE: notes are read, edited, moved and deleted, new comments are threads.

**Other calls.**
- Unknown id → `IllegalArgumentException` (all four). `replyComment` / `setCommentResolved` on a note →
  `IllegalStateException`.
- `replyComment(id, …)`: `id` may be the thread's id or the id of any of its replies. A resolved thread stays
  resolved (reopen it first if the UI wants that).
- `setCommentResolved(id, …)`: same ids. Nothing to do → no change, `dirty` stays as it was.
- `editComment(id, text)`: the message `id` names (first message, reply, or note). Same text → no change.
- `deleteComment(id)`: thread id → the whole thread; reply id → that reply only; note id → the note.
- `commentCells` / `comments` are cached between changes; calling them per frame is cheap. Reading never makes
  the workbook dirty and never changes a part.

**Dates.** `dateIso`: ISO-8601 with `Z`, with an offset (`+08:00`) or without a zone (taken as universal time);
blank = now. Read back always as `yyyy-MM-ddTHH:mm:ssZ`. In the file: `dT="2026-11-22T10:00:00.00"` (universal
time, no zone mark; see deviations).

**Authors.** Looked up by exact display name in the workbook's person list, added with a fresh GUID when new.
A thread whose person is not in the list reads with `author = ""`. A note's author heading (the name and a colon
in front of the text, as most programs write it) is not part of `text`; it is kept in the file when the note is
edited.

**What moves comments.** `insertRowBelow`, `insertColumnRight`, `deleteRow`, `deleteColumn`, `sortRows` /
`sortRowsBy`: thread items, the entry in the `comments` part and the shape (row, column, anchor) all follow the
cell. Deleting the row / column a comment is on deletes the comment (a thread with all its replies).
**What does not:** `copyRange` / `pasteRange` (copy, cut, paste), `fill`, `fillSeries`, `clearRange`, `setCell("")`,
`mergeCells` / `unmergeCells`: comments stay on their cell and are not copied. `copySheet` does not copy comments.

**Compat list.** `compat()` no longer reports `comments` (READ_ONLY) for spreadsheets, and row / column edits no
longer add `insert-row:comments` and the like. `ui/Overlays.kt` still maps the `comments` code (documents may use it).

## What is written (for QA unzipping the file)

- Thread: `xl/threadedComments/threadedComment<N>.xml` (one per sheet, relationship from the sheet) +
  `xl/persons/person.xml` (one per workbook, relationship from the workbook) + content-type overrides.
- For every thread also, on the same cell: an entry in the sheet's `xl/comments<N>.xml` (author `tc={thread id}`,
  text = each message under its author's name) and a hidden note shape in `xl/drawings/vmlDrawing<N>.vml`
  (`x:Row` / `x:Column` / `x:Anchor`), the `vml` Default in `[Content_Types].xml`, and `<legacyDrawing r:id>` in the
  sheet (after `<drawing>`, before `<legacyDrawingHF>` … `<tableParts>` / `<extLst>`).
- `<N>` = first free number, in the order parts are made (not the sheet number). A sheet that already has a
  comments part or a legacy drawing gets the new entry / shape in those.
- Save rule: only the element that changed is spliced; a sheet part changes only when its first legacy drawing is
  made (one element inserted).

## Known gaps

- Parts left without comments stay in the file (empty, valid, still referenced). `deleteSheet` leaves the deleted
  sheet's comment parts in the package unreferenced (as it does for that sheet's drawings).
- Editing a note replaces its text with one run (the first run's font is kept); other rich formatting inside the
  note is lost. Editing a thread message drops its `<mentions>` (they index into the old text).
- A resized / moved note box keeps its size when its cell moves; the shape's `style` margins are not recomputed
  (programs place hidden note boxes from the anchor).
- No call to create a NOTE, to convert a note into a thread, or to move a comment to another cell.
- A legacy drawing that also holds form controls: only note shapes are moved on row / column changes.
- The saved files were not opened in a desktop spreadsheet program in this work; they were checked with openpyxl
  and by reading the parts (`m5_xlsx_verify.py`).

## Deviations from the brief

- `dT` is not the given string verbatim: it is written as universal time without a zone mark and with hundredths
  (`2026-11-22T10:00:00Z` → `2026-11-22T10:00:00.00`), the form desktop programs write; `ReviewComment.date` gives
  it back as `2026-11-22T10:00:00Z`.
- `addComment` on a cell that has a thread returns the new reply's id, not the thread's.
- Sorting rows moves comments too (the brief asked to check; whole rows move, so their comments go with them).
- Added `comment(id)`; removed the `comments` READ_ONLY compat note and the `<op>:comments` notes.
