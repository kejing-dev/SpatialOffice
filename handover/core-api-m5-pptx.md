# core API — M5 "Review", presentations (`core/pptx/`)

Branch `m5-pptx`. Code: `PptxDeck.kt` (public API, footer), `SlideComments.kt` (new, internal: comment reading / writing),
`Model.kt`, `PptxTemplates.kt`. Tests: `PptxM5Test` (18). Independent check: `tools/verify/m5_pptx_verify.py`.
Shared model used as it is: `core/review/Review.kt` (`ReviewComment`, `ReviewReply`, `CommentKind`); nothing in it had to change.

## Signatures added

```kotlin
// PptxDeck — p33 comments
fun comments(): List<ReviewComment>                    // whole deck, slide order; within a slide the file's order
fun comments(slide: Int): List<ReviewComment>          // IndexOutOfBoundsException for a slide that is not there
fun addComment(slide: Int, x: Float, y: Float, author: String, text: String, dateIso: String): String   // returns the thread id
fun replyComment(id: String, author: String, text: String, dateIso: String): String                     // returns the reply id
fun setCommentResolved(id: String, resolved: Boolean)
fun editComment(id: String, text: String)              // thread, reply or old-style comment
fun deleteComment(id: String)                          // reply / thread with its replies / old-style comment

// PptxDeck — p32 slide number and footer
fun footerInfo(slide: Int): SlideFooter
fun setFooter(slides: List<Int>?, number: Boolean, footer: Boolean, text: String, hideOnTitle: Boolean): Int   // slides changed

// Model.kt
data class SlideFooter(val number: Boolean, val footer: Boolean, val text: String)
enum class FooterKind { NUMBER, FOOTER, DATE }
data class Shape(…, val footerKind: FooterKind? = null)   // new last field, default null: existing call sites compile
```

Changed behaviour of existing members: `duplicateSlide` (comments are not copied, see below), `firstTitle()` (never
returns a footer / slide-number text), `slides` (a slide-number field reads as the number). Nothing else changed.

## Comments: what the UI must know

- **Fields filled** in `ReviewComment`: `id`, `kind`, `author`, `date`, `text`, `resolved`, `replies`, `slide` (index),
  `x` / `y` (share of the slide, 0–1, clamped), `quote` = "". `THREAD` = threaded comment, `NOTE` = old-style comment.
- **Ids** are the ids the file keeps: a thread / reply id is its GUID with braces (`{D397D5F7-…}`), an old-style comment is
  `note:<authorId>:<idx>`. A duplicate inside one deck (broken file) gets `~2`, `~3` … appended. They are the same after
  save, after reopening and after an undo (the session reopens the deck), so the UI can keep a selected id across those.
- **Old-style comments** (`NOTE`): `editComment` and `deleteComment` work; `replyComment` and `setCommentResolved` throw
  `IllegalStateException` — hide those two controls for `kind == NOTE`. Unknown id: `IllegalArgumentException`.
- `replyComment` / `setCommentResolved` accept a reply's id too and act on its thread. Replying does not reopen a
  resolved thread. `editComment` with the text it already has, and `setCommentResolved` to the state it already has,
  change nothing (`dirty` stays false).
- **Text**: `\n` = a new paragraph. An edit keeps the first run's formatting and makes the comment one plain text
  (a mention inside it becomes plain text).
- **Dates**: pass ISO-8601 with a zone (`2026-11-22T10:00:00Z` or `…+08:00`); stored as UTC `2026-11-22T10:00:00.000`
  (the form desktop files use); read back as `2026-11-22T10:00:00Z` (fraction kept only when not zero). A stored
  date without a zone is taken as UTC. Text that is no date-time is stored as the time of the call.
- **Authors**: matched by exact name in the deck's authors part; a new name is added (`initials` from
  `ReviewText.initials`, `userId` = the name, `providerId="None"`). Authors are never removed.
- **Position**: `addComment` pins the thread to the slide at (x, y). Comments from other programs that are anchored to
  an object are read as that object's top-left corner plus the stored offset (object not found: offset from the
  slide's corner). There is no "move comment" call yet.
- **What a first comment on a slide writes**: the comment part `ppt/comments/modernComment_<slide id hex>_<n>.xml`, the
  slide's relationship to it, its content type, and one entry in the slide's own extension list (`p:extLst` /
  `p188:commentRel`, appended at the very end of the slide part — so the slide part changes once, by that entry
  only). First comment of the deck: also `ppt/authors.xml`, its relationship from the presentation, its content type.
  Later comments on that slide touch only the comment part (and the authors part for a new author).
- **Deleting the last comment of a part** removes the part, the relationship, the content type and (threads) the
  slide's extension entry: a slide that had no comments before is byte-identical again.
- **Slide operations**: `deleteSlide` removes the slide's comment parts (both kinds), their relationships and content
  types. `moveSlide` leaves them alone; `comments()` reports the new index. `duplicateSlide` does **not** copy
  comments: the copy has no relationship to the original's comment parts and no extension entry (a comment part
  belongs to exactly one slide); a comment added on the copy gets its own part.
- `comments()` is cached; every comment edit and every slide add / delete / move drops the cache.
- Sample decks for the UI (written by the tests into `core/build/m5-pptx/`): `acceptance-comments-before.pptx`
  (old-style only), `comments-threads-untouched.pptx` (two authors, replies, one resolved, one anchored to an object),
  `comments-both-kinds.pptx`, `acceptance-footer.pptx`, `footer-fallback.pptx`.

## Slide number and footer: what the UI must know

- `footerInfo(i)` describes the slide's **own** placeholders (`p:ph type="sldNum"` / `"ftr"`); a layout's footer that
  the slide does not carry counts as off (it is not drawn either, as before).
- `setFooter`: `slides == null` = all. `hideOnTitle` = slides whose layout has `type="title"` get neither, and ones they
  already have are removed. `text` is ignored when `footer` is false. Slides already as asked are not rewritten and
  not counted; a call that changes nothing leaves `dirty` false.
- On: a placeholder shape is appended to the slide's shape tree (footer first, then number). When the slide's layout
  has that placeholder the new shape inherits box, size and alignment from it (same `idx`, empty `p:spPr`). When it
  has none, the shape carries its own box — the slide master's footer / number box, else bottom centre / bottom right
  computed from the slide size — plus 12 pt and centre / right alignment. Off: the shapes are deleted; a slide that had
  none before is byte-identical again. Only the text differs: only the footer's paragraphs are rewritten.
- **Drawing model**: the two placeholders are ordinary entries of `Slide.shapes` with `kind == ShapeKind.TEXT` (never
  TITLE / BODY) and `footerKind == NUMBER / FOOTER` (`DATE` for a date placeholder a file brings along). Suggested UI
  rule: `footerKind != null` → draw, do not offer for dragging. The number shape has `editable == false` and its
  `paras` already hold the number (`firstSlideNum` of the presentation, default 1, plus the slide index — so a deck
  that starts counting at 5 shows 5, not 1); it follows moves / deletes without any call. The footer shape has
  `editable == true`: typing into it through `setShapeText` is the same as `setFooter` for that slide.
- A slide-number field in any other text box also reads as the number now (it read as the stored text before).
- Find: the number text ("3") is part of the shape's text, so a search for a digit can hit it; it is not editable.
- `compat()` counts each added slide-number field under the existing read-only code `field`.

## Known gaps

- No API to move a comment, to change a comment's author / date, or to convert old-style comments to threads.
- Old-style comments that answer another old-style comment (2013-era files) are listed as separate `NOTE`s, not nested;
  deleting the one they answer deletes them too.
- Mentions, task assignment, likes and reactions inside threads are kept in the file but not modelled.
- `setFooter` does not write the presentation-level "show on title slide" flag and does not touch layouts or masters.
- No date placeholder on / off (only number and footer were asked for).
- The stored text of a new number field is `‹#›`; programs recompute it when they open the file.

## Format details not verified against a file made by a desktop presentation program

There is no desktop suite on this machine and python-pptx has no comment support, so these follow the published
schema and its SDK sample, not a reference file:

1. Threaded comment element order (anchor, `pos`, `replyLst`, `txBody`), the slide anchor `pc:sldMkLst` / `pc:docMk` /
   `pc:sldMk cId sldId`, the slide extension `{6950BFC3-D8DA-4A85-94F7-54DA5524770B}` + `p188:commentRel r:id`, both
   relationship types and both content types: taken from the published schema page and SDK sample — **not opened in a
   desktop program**.
2. `pc:sldMk cId`: written as the slide's creation id when the slide carries one, else `0`. Whether a desktop program
   accepts `0` for a slide without a creation id is unverified.
3. Part name `modernComment_<slide id hex>_<creation id hex>.xml`: from memory of real files; the name is not
   significant to readers (the relationship is).
4. `p188:pos` units (EMU) and its origin (top-left of the anchored object; the slide for a slide anchor): the schema
   text says "relative to the top-left corner of the first object the comment is anchored to"; the unit is assumed.
5. Old-style `p:pos` units = 576 per inch: from memory of how other software converts them; unverified.
6. `created` without a zone letter being UTC: assumed.
7. New comment runs are written without `a:rPr` (as the SDK sample does); a desktop program writes `lang` there.
8. A slide carrying both an old-style and a threaded comment part: allowed by the package rules; whether a desktop
   program shows both or upgrades the old ones is unverified.
9. A slide-number / footer placeholder on a slide whose layout has no such placeholder (the fallback box): valid by
   schema and read by python-pptx; desktop rendering unverified.

## Evidence

- `./gradlew :core:test --tests 'io.embodit.spatialoffice.core.pptx.*'`: 71 tests, 0 failures (53 existing + 18 new).
- `./gradlew :core:test`: 331 tests, 0 failures.
- `tools/.venv/bin/python tools/verify/m5_pptx_verify.py`: 3985 checks, 0 failed (13 decks: python-pptx opens each;
  every relationship target exists; content types cover every part; XML well-formed; thread / reply ids unique GUIDs;
  author ids resolve; child order of comments, slides and shape trees; each comment part belongs to one slide and is
  named by that slide's extension entry; both acceptances re-checked, the old-style parts compared byte for byte).
- `tools/verify/verify_docx_pptx.py` (503 checks) and `verify_v025.py` (21 checks) still pass.
