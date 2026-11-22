# Spatial Office (空间办公)

A keyboard-and-mouse-first office app for PICO (Spatial SDK 6.2.8 + SpatialUI). It opens, edits and saves standard
`.docx` / `.xlsx` / `.pptx` files directly (also `.txt` / `.md` / `.csv`; PDF, images and web pages read-only) and
presents them spatially. There is no app-specific file format; spatial layout lives outside the document.

Package `io.embodit.spatialoffice`. Design: `.scratch/prd_v07_body.html` (PRD v0.7), `.scratch/preview.html`.

## Build and test

```
export JAVA_HOME=~/.pico/primer-cli/jdk/jdk-21.0.13+9/Contents/Home
./gradlew :core:test            # file formats, formulas, layout rules, naming (pure JVM)
./gradlew :app:assembleDebug    # app/build/outputs/apk/debug/app-debug.apk
python3 -I tools/gen_strings.py # after editing tools/strings.tsv (zh + en string resources)
tools/.venv/bin/python tools/verify/verify_docx_pptx.py   # independent check of saved files (python-docx / python-pptx)
tools/.venv/bin/python tools/verify/verify_xlsx.py        # same with openpyxl
tools/.venv/bin/python tools/verify/verify_v025.py        # v0.2.5: moved / resized objects, slide tables, resized doc pictures and tables
tools/.venv/bin/python tools/fixtures/make_slide_proportions.py <dir>   # v0.2.6: decks of four page proportions for the slides window
```

## Layout

| Path | What |
|---|---|
| `core/` | Pure Kotlin/JVM, no Android. `pkg/` OPC package + XML range scanner; `docx/`, `xlsx/`, `pptx/`, `csv/` read / edit / patch-only save; `text/` rich-text spans, plain text / Markdown, web-page text; `layout/` spread rules, layout record, seat; `files/` naming rules. |
| `app/…/platform/` | `Graph` (process-wide state and every action), `Sessions` (one open file each), `Storage`, `Stores` (settings, recents, layout records), `WindowCtl`, `Exporter` (save as), `Head`, `DebugReceiver`. |
| `app/…/ui/` | `home/` (recent, browse, new, accounts, settings), `doc/`, `sheet/`, `slides/` editors, `chart/` 3D chart volume, `space/` Stage (spread layout, rehearsal), `Overlays.kt` (save sheets, dialogs). |
| `tools/` | `strings.tsv` + `gen_strings.py`, `svg2vd.py`, test-fixture generators and verifiers. |

Containers (`Main.kt`): home window (default, 1120 × 660 dp), one Planar window per open file (v0.2.5; one container id per kind, the file's tag tells them apart), a Volumetric window for the
chart (1.76 × 0.26 × 0.32 m since v0.2.3, `chartPlacement()` = `Placement.top(sheet)` since v0.2.2: in front it covered the grid,
below / beside it left the view, `build/v0.2.2/placement-trials.md` in the factory), one Mixed Stage.
v0.2.3: the chart's two panels have a fixed physical size (`core/layout/ChartPanels`: 0.0019 m per dp, text ≥ 15 sp,
50 dp buttons; the app scales each attachment by that over the volume's own m/dp, logged as `CHART panels base=…`);
`ChartPanelSizeTest` checks text ≥ 0.5° and buttons ≥ 48 dp of a window at the viewing distance. The volume clips its
attachments, so the panels stay inside it. The chart opens only after the sheet window is up (`core/layout/ChartOpenGate`,
`WindowCtl.openChartWhenSheetIsUp`, log `CHART gate …`; `ChartReopenTest` replays the rehearsal and spread timelines),
opens turned −35° (`CHART_DEFAULT_YAW`), and its "click a bar" hint is in the sheet window's footer. The compatibility
panel is a Sheet over its editor (v0.2.2; the Subwindow hung 36°–48° to the right). Every Sheet uses `SheetClose`
(close button labelled in the app language). Since v0.2.1 every editor and the chart open with `editorPlacement()` = the system's spot in
front of the user (`Placement.right(home)` put them 22°–60° to the right, QA #1 q1-1; `core/layout/WindowFit` +
`WindowFitTest` check all 8 corners). Nothing is placed from the head pose.

v0.2.4 (QA #4): **q4-1** the chart is no longer built on the main thread. `core/layout/ChartPlan` lists the boxes;
`ChartResources` (ChartVolume.kt) makes one material per colour, one mesh per size and the hit shapes on a background
thread, keeps them for the process (`toGlobal()`: otherwise the SDK releases them with the volume and the next opening
crashes with "closed MeshResource"), and prewarms them when the sheet window comes up and again when the chart is asked
for. Entities must be made on the main thread (the SDK throws off it), so they are made in ≤ 24 ms batches with a frame
between, into a group hung in at once. Logs: `CHART prepared …`, `CHART built … entities=… (longest … ms) open=… ms
(asked → built)`; `MAINWATCH chart-open` logs every main-thread gap > 100 ms with a stack sample of what held it.
**q4-2** the placement list keeps destroyed windows under the same name (`isOnstage=true` too), so the chart anchors to
the live sheet by container instance id: every window reports its id on ON_CREATE / ON_DESTROY (`platform/ContainerIds`,
log `CONTAINER …`), `chartPlacement()` picks with `core/layout/LiveAnchor` (log `PLACE SpatialOfficeChart … anchor=
SpatialOfficeSheet#<id> liveSheet=#<id> dead=[…] among …#<id>(destroyed)`). The SDK getters are internal, read by
reflection. Tests: `ChartPlanTest`, `LiveAnchorTest` (3 rehearsals + 3 spreads replayed against a list that keeps
destroyed entries; the v0.2.3 first-by-name rule picks a destroyed sheet in all 6).

v0.2.5 (headset feedback 2026-11-17, three items):

**1. Move / resize what was inserted.** On the editing canvas every slide object (text box, picture, shape, table,
and objects whose content is not drawn) is picked with a click, moved by a press-and-drag on it and resized by the
dots on its four corners; a round tab above it moves it too (what is left to hold while its text is being typed).
`ui/ObjectDrag.kt`: one `dragTotal` modifier for all of it — Compose `detectDragGestures` inside the Planar window
(`gate:allow compose-drag`), every event's `dragAmount` summed by `core/layout/DragTotal` into the travel since the
press (profile D53), which goes to `DeckSession.beginBoxDrag / moveBoxBy / resizeBoxBy / endBoxDrag`
(`core/layout/BoxDrag`: opposite corner fixed, pictures keep their proportions, half of the object stays on the
slide). While the pointer is down the object is drawn from `dragBox`; the file is written once on release (one undo
step). Text is read until clicked (`TapEdit`), so a drag on a text box is the object's, not a text selection.
Hand, controller ray and gaze + pinch all arrive in the window as that one pointer. `PptxDeck.setShapeBox` now also
moves graphic frames (a table's columns and rows scale with it) and groups (child space kept); slides have
Insert ▸ Table (`addTable`, `setTableCell`). Documents: a clicked picture shows corner dots (proportions kept, at
most the text width, `DocxDocument.setImageSize`); a table with the caret in it shows two on its free side
(20–100 % of the text width, `setTableWidth` → `w:tblW pct`). Log lines: `OBJDRAG start / end … events=… total=…`,
and `DRAGAT <target> x,y` = where each drag target is in window pixels (for `adb shell input swipe`, never logged
during a drag). Debug: `objdrag`, `slide-table`, `slide-cell`, `doc-pick`, `doc-resize`.

**2. Several files of one kind.** One window per open file: same container id, the file's tag (`f1`, `f2`, …) as
the container tag and in the bundle (`Containers.ARG_TAG`); `WinRef(win, tag)` names a window everywhere.
`core/layout/OpenFiles` decides (new window / already open → front / five of a kind is the limit); `Graph.sessions`
holds them, `Graph.doc / sheet / deck` are the ones whose window was used last (`activate`: any press or key in an
editor window, or its taking the focus). Each window is given its own session (`ui/Editor.kt EditorWindow`);
overlays and toasts carry the tag of the window they belong to. Opening an open file → `Graph.front`.
**Placement** (`build/v0.2.5/placement-trials.md` in the factory): the system offers "in front" or "beside an anchor
with a gap ≥ 0"; a negative gap (staggered, overlapping) leaves the window unshown, and a window opened on the spot
of another takes that one off stage. So every window opens in front (`editorPlacement`, inside ±39°), and the title
bar's **Windows n** menu (`WindowsMenu`) switches: Home, each open file, and Side by side (`WindowCtl.arrange`:
the others are reopened right / left of this one). A hidden window is "fronted" by closing and reopening it
(`restoreWindowContainer` answers false for it). After the Stage, windows come back in an order that leaves the
file that was spread out on top, or the chart's sheet when the chart was up. The 3D chart belongs to one sheet
(`Graph.chartTag`) and is put away when another sheet comes to the front. Debug: `front <tag>`, `activate <tag>`,
`close-tag <tag>`, `front-home`, `arrange [tag]`, `win-place front|gap:<dp>`, `front-mode auto|restore|reopen`;
`DUMP … open=[f1:DOC:name:ON_RESUME,…] chartOf=…`, `windows=DOC/f1=ON_RESUME,…`.

**3. Accounts.** A service is offered only when its client id was put in at build time (`Accounts.shown` =
`core/cloud/CloudCatalog`): otherwise no Accounts row, no Browse location, no How-to line, and with none at all no
Accounts page. [Connect] goes straight to the system browser (`core/cloud/BrowserSignIn` → `Accounts.launchBrowser`,
ACTION_VIEW + BROWSABLE; log `CLOUD … browser intent`); only without a browser does a service with scan sign-in
fall back to the code dialog. No user-facing string mentions registration or build files (gate dev-jargon-ui).
Debug builds: `cloud-id <service> <client id>` sets a client id for this run to walk the path (`cloud-id <service>`
clears it).

v0.2.6 (QA #6 q6-1): **slides of any page proportion.** v0.2.5 drew the editing canvas 960 dp wide whatever the page,
so a 4:3 file's canvas was 720 dp high (the whole column) and the notes label, the notes field and the picked-object
hint got no height. `core/layout/SlideFit` now sizes every slide drawing: `fit()` = the largest page-shaped rectangle
inside a space (the smaller of the width and height ratios decides); `editor()` = the slides window's column: the
canvas may take the column's height less `RESERVED_H` (gap 12 + label row 28 + gap 6 + notes field ≥ 108 dp) and at
most 960 dp of width, the label row and the notes field are the column's 960 dp wide whatever the canvas (a tall page
does not squeeze the label and the hint), and the notes field fills down to the bottom. A 16:9 file comes out as
before (960 × 540, notes 126 dp in the 712 dp column the window opens with). `SlidesWindow` measures the column with `BoxWithConstraints`, so a resized window
follows. Thumbnails: `SlideFit.thumb` (170 dp wide, at most 128 dp high), and the reorder drag steps by the real row
height (`dropIndex`; it stepped by the 16:9 height for every file). Storyline pieces (700 × 394), the rehearsal screen
(960 × 540) and the next-slide preview use `fit()` too: a 4:3 page was cut at the bottom there. Log:
`SLIDEFIT page=<EMU> column=<dp> canvas=<dp> … px: canvas=… label=… notes=… window=… inside=true|false` whenever the
layout changes (window px; `inside` = all three inside the window and none over another). Tests: `SlideFitTest`
(6 page proportions × 3 window sizes; the v0.2.5 rule is kept as `editorV025` and shown to leave 4:3 a notes field of
≤ 0 dp). Sample decks (4:3, 16:9, 16:10, 3:4, each with notes and a mark in every corner of the page):
`tools/.venv/bin/python tools/fixtures/make_slide_proportions.py <out dir>`. The emulator's `--spatial --size
2560x1440` screenshots are stretched 1.33× sideways; judge proportions on the plain `pico-cli capture screenshot`.

v0.2.7 (QA #7 q7-1): **no dead cloud row in Save as.** The dialog of all three editors drew, whatever the build, a
grey row "Save to a cloud drive · connect one under Accounts & drives first" that could not be clicked, and this
release has no Accounts page. The dialog now draws exactly `Exporter.rows(session, accounts.saveAsDrive)`: the local
targets, plus the cloud copy only when `core/cloud/CloudCatalog.saveAsDrive(shown, connected)` names a drive (the first
service this build offers that is signed in). Offered but not signed in → no row either: every row drawn can be picked.
The strings `sa_cloud`, `sa_cloud_sub`, `cloud_none_connected` are gone; `Accounts.saveCopy` takes the drive the row
named; `Graph.saveAs(CLOUD)` without a drive does nothing. Recent lists a cloud file only while its service is offered
(`CloudCatalog.listsRecent`). Log when the dialog opens: `SAVEAS rows=COPY,PDF,TXT drive=none offered=[]`. Tests
(`app` `SaveAsRowsTest`): no client ids → none of the three editors has a cloud row; offered but signed out → none;
signed in → one, last; the removed strings are in no language; only strings drawn with a drive offered
(`nav_account`, `cloud_none`, `how_to_cloud_keys`) name the Accounts page.
**Rule for anything new that touches accounts or drives:** gate it on `accounts.shown` (offered in this build) or
`accounts.saveAsDrive` / `connected` (signed in); never draw a disabled row that tells the user to go to a page.

v0.3.0 (M1, design-package roadmap[0]; "Spreadsheet basics, find, never lose work"; no new panels, the toolbars and
dialogs that were there):

**Spreadsheet.** s17 the selection is a range (`SheetSession.anchorRow/anchorCol` + the active cell; `selection` =
`core/xlsx/CellRect`): a press-and-drag across cells selects (SheetGrid, pointer events at the Initial pass, so a drag on
the cells selects and a drag on the row numbers scrolls; log `RANGE drag B2:D5`), a click on a row number / column letter
selects the row / column (cells in use), the corner selects all; Shift+arrows extend, Ctrl+arrows jump to the data edge
(`core/xlsx/DataEdge`), Ctrl+Shift+arrows both, Ctrl+A all. Blue 2 dp box + light blue fill; under the grid on the
sheet row "Sum · Average · Count" (`core/xlsx/RangeStats`; count = non-empty cells). Bold, Fill, number formats,
alignment, wrap and decimals act on the whole range. s18 [Edit ▾] copy / cut / paste / find (Ctrl+C / X / V / F):
cells go to the system clipboard as tab-separated text (`core/xlsx/Tsv`) and to `SheetSession.clip` with inputs and
style indexes; pasting our own clip shifts relative references by the offset (`XlsxWorkbook.pasteRange`,
`Formula.offset`, absolute parts stay, off-sheet → #REF!), a cut moves the cells unchanged and empties the source,
any other clipboard text is split at tabs / line breaks; the pasted range is selected (log `CLIP …`). s20 [Rows &
columns] wider / narrower (±2 characters) / fit to content (`autoFitColumn`) / taller / shorter (±6 pt) for the selected
columns / rows; the grid draws the file's widths (40–720 dp) and row heights (pt × 2.4). Dragging a header edge is not
in this version. s21 [⋯] beside the sheet tabs: new / rename (sheet with a name field; Excel's name rules,
`SheetSession.sheetNameProblem`) / copy / delete (confirm dialog; references to it become #REF!); all sheets are in
the picker, scrolled sideways. s22 [Format ▾] also has left / centre / right, wrap text, more / fewer decimals.

**Find (d41 / s19 / p21).** One find sheet for the three kinds (`Overlays.FindSheet`, model `Session.findHits /
showHit / replaceHit / replaceAllHits`, `FindHit`): [Previous] [Next] jump and mark the hit (document: yellow marker on
the paragraph, scrolled into view; spreadsheet: the cell is selected, other sheets with "All sheets"; slides: the slide
is shown and the text box picked, a hit in the notes is underlined on the notes label row and the notes field gets a
blue frame); the sheet also shows the hit with the text around it (it stands in front of the page). [Replace] changes
only the current hit; [Replace all] is one undo step (snackbar with [Undo]). Match case. Log `FIND n/m FindHit(…)`.
Search text survives closing the sheet (`FindMemo`).

**Drafts (d27).** Every 3 s each open file with unsaved changes that changed since its last draft is written whole to
`files/drafts/<sha1 of the file key>.draft` (`platform/Drafts`, `Graph.autosave`; log `DRAFT saved …`). Opening a file
that has a draft shows the confirm dialog "Recover unsaved changes?" [Recover] [No thanks] (`Overlay.Recover`,
`Graph.answerRecover`): Recover = `Session.recover(bytes)` (one undo step back to the file on disk, title bar shows
unsaved); No = draft deleted. Saving, discarding or closing without changes deletes it; a crash / force-stop leaves it.

**Slides (p19 / p20).** [Object ▾] copy / duplicate (Ctrl+C / Ctrl+D) and bring to front / forward / backward / send to
back (only the moves that can still happen are listed); [Insert ▾] paste (Ctrl+V) puts the copy on the current slide,
1/40 of the slide right and down, picked at once (`PptxDeck.copyShape / pasteShape / duplicateShape / reorderShape`;
fresh ids for every element of the copy, pictures share the media part). Charts / diagrams are refused with a
message. While a text field has the keyboard (`DeckSession.textEditing`) Ctrl+C / V stay the text's.

**Platform.** BACK / controller B (profile D60): `platform/BackKeys` catches them at every window's callback, on every
root view of the process and in an OnBackPressedCallback; `BackPolicy`: a dialog / sheet open → close it, the home
window closed → bring it back, else nothing; never exit (log `BACK <where> -> <act>`). Windows open at once, there is no
level-head gate any more (profile D9 / D10; `core/layout/LevelGate` removed, `Seat` kept in its own file).
**Controller / keyboard keys never press a button (v0.4.2, QA #11 q11-1, profile D60).** Two layers: (1) every Button /
IconButton / SegmentItem / MenuItem / `clickable` / `toggleable` carries `Modifier.noKeyFocus()` (`ui/Common.kt`,
`focusProperties { canFocus = false }`), so a tapped button never keeps key focus; `KeyFocusTest` scans the sources and
fails on a control without it. (2) the same window callback that catches BACK (`BackKeys.Wrapped`, plus the unhandled-key
listener) runs `platform/KeyGuard`: every gamepad button except B and D-pad centre are eaten; Enter / Space are eaten
unless a typing target of that window has focus (`Modifier.typingTarget()` on paragraphs, cells, slide text boxes, the
formula bar, find fields, the sheet grid). Ctrl / Alt shortcuts, Tab, arrows and letters pass untouched. Log
`KEYGUARD ate <key> at <window> typing=<bool>`. New dialogs / menus / windows are hooked by `BackKeys.sweepSoon` (overlay
set, menu opened, window event). New controls: add `.noKeyFocus()`; new text inputs: add `.typingTarget()`.
`ui/SafeSound`: SpatialUI 6.2.8 plays its click through `SpaceView.playSoundEffect`, which system images before that
method lack (the 2026-09-01 PICO 6.1.0 emulator image): there the buttons are silent instead of the app dying on the
first press (log `SOUND system click …`).

v0.4.0 (M2, design-package roadmap[1]; "Text formatting everywhere + version history"; no new panels, two new toolbar
menus per editor and second pages inside menus):

**Text model.** `core/text/Span` carries `color` (0xRRGGBB, null = automatic), `highlight` (Word name, documents only) and
`size` (half-points, null = the paragraph's); `RichText.restyle / setColor / setHighlight / setSize / stepSize / clearFormat /
common`; `FontSizes.LADDER` (Word's 8…72 pt list) and `PRESETS_PT` (12 14 18 24). Each format writer compares the model with
the run's own formatting and touches only what differs, so untouched runs stay byte-identical (null = remove the element).
**Documents** (`DocxDocument`): `Run.color / highlight / size`, `Paragraph.level / indentTw / lineSpacing / spaceBefore /
spaceAfter / baseSizeHp` (size of text without its own: style chain, then docDefaults, else 20); `setListLevel` (own list
definitions now have 9 levels), `setIndent`, `setLineSpacing`, `setParaSpacing` rewrite only the paragraph's `w:pPr`.
`DocSession.formatRange(id, a, b, f)` is what [Text ▾] / [Colour ▾] (and `docfmt`) call; with only a caret they act on the
whole paragraph, like B / I / U. Tab / Shift+Tab in a list item change its level (`ParaEditor` key handler →
`setListLevel`); [Paragraph ▾] indent (`DocSession.indent`: list → level, other → 420 twips) and [Line spacing ›]. The paper
draws levels (28 dp each, markers • ◦ ▪ / 1. a. i. counted per level), indent, spacing (`paraPadding`, `paraTextStyle`),
colours, highlights and sizes (relative to `baseSizeHp`). **Spreadsheets** (`XlsxWorkbook`): `setItalic / setUnderline /
setStrike / setFontColor / setFontSize / clearFont / defaultFontPt`, `Cell.italic / underline / strike / colorRgb / fontPt`;
[Text ▾] replaced the B button (groups stay 8), [Colour ▾] = text colours + the fills that were [Fill ▾]; Ctrl+I / Ctrl+U.
**Slides** (`PptxDeck`): `TextPara.runs` (effective formatting of each `a:r`, `src` = index among all runs of the box as
stored now) and `TextPara.align`; `setShapeText` keeps a paragraph whose text, level, bullet, runs and align are unchanged
verbatim. `DeckSession.spansOf / parasFrom` turn a box into one span list ("\n" between paragraphs, `SOFT_BREAK`
U+2028 for a line break inside one, `a:br`; v0.4.1 q10-2) and back; typing
(`setText`), find / replace and the menus all go through it, so mixed formats survive edits. `textSel` (the text field
reports its selection) + `formatText` / `setParaFormat` / `textCommon`; with no selection they act on the whole box.
The text field shows bullets through `BulletMarks` (a VisualTransformation, not text): it draws `SOFT_BREAK` as a new
line without a bullet and lays each paragraph style back so it ends before the next paragraph's bullet
(`BulletMarks.paragraphRanges`, sorted and clipped: Compose refuses overlapping paragraphs, v0.4.1 q10-1; `M2FixTest`).
Find hits on slides count offsets in the same text (`shapeText` uses `SOFT_BREAK` too). `setShapeFill / setShapeLine /
setShapeRotation`, `Shape.lineRgb / lineWidthEmu / rotation`; [Object ▾] › Fill ›, Line ›, rotate ±90°, straighten; the
rotate handle beside the move tab (`core/layout/Rotation`: angle about the centre, 15° steps; written once on release).
**Version history** (`platform/History`): every successful save keeps the saved bytes in
`files/history/<sha1 of the key>/<millis>.save.v` (an unchanged save is not kept twice; newest 10); [Restore] keeps what
the window holds as `.restore.v` first, then `Session.recover` (one undo step, unsaved). A first save that renames the
file moves its history. Overlay `History` (doc More ▾, sheet Edit ▾, slides Page ▾). Logs: `DOCTEXT`, `PARA level|indent|
line|spacing`, `SLIDETEXT`, `OBJSTYLE`, `HISTORY kept|restore|list`. Menus: `MenuEntry.sub` / `subMenu()` opens a second
page of the same menu ([‹ back] first). Tests: `M2Test` (app), `DocxM2Test`, `PptxM2Test`, `XlsxM2Test`, `RotationTest`.

v0.5.0 (M3, design-package roadmap[2]; "Spreadsheet depth, inserts, zoom"; 13 items, no new panels, entries in the
menus that were there plus a zoom menu in the title bar):

**Spreadsheets.** s24 `XlsxWorkbook.fill(sheet, rect, down)`: the first row (down) / column (right) of the rectangle goes
into the rest through `copyRange` / `pasteRange` (formulas move, styles copied); a rectangle one row high / one column
wide takes the row above / column to the left (Ctrl+D / Ctrl+R, [Edit ▾ ▸ Fill ›]). `fillSeries`: per column / row the
leading filled cells are the seed: numbers (dates included) continue their step (one seed: +1, a day), text ending in a
number ("Q1") counts that number up, anything else (formulas too) is copied; the seed's style goes with it. Log `FILL`.
s25 `mergeCells / unmergeCells / merges / mergeAt`: `Sheet.merges` (null = the file's `<mergeCells>` after this
session's row / column ops) and `mergesChanged`; a merge takes in every merge it touches and empties all but the
top-left cell; saving rewrites the `<mergeCells>` element only (`mergeEdits`, put after `sheetData` before the
`AFTER_MERGE` elements when the sheet had none, removed when empty); inserted / deleted rows and columns move it.
[Rows & columns ▾ ▸ Merge cells] (selection ≥ 2 cells; the merged cell is then selected) / [Unmerge cells] (shown on a
merged cell). The grid draws a merge as one box across its columns at its first column in view; its top row shows the
top-left value, the rows below continue the box without inner lines; a click selects the whole merge. Log `MERGE`. The
compatibility list no longer names merged cells. s26 `Styles.withBorders / borderMask` (thin, automatic colour,
`Borders.LEFT|RIGHT|TOP|BOTTOM` bits; one `<border>` + `<xf>` appended only when no equal one exists), `XlsxWorkbook.setBorders /
borderMask`, `SheetSession.setBorders("all"|"outline"|"bottom"|"none")` from [Colour ▾ ▸ Borders ›]; the grid draws the
file's lines dark 1.5 dp (a merged box only on its outer edge). Log `BORDER`. s27 `Formula.KNOWN` = 108 functions
(SUMIFS COUNTIFS AVERAGEIFS MAXIFS MINIFS, INDEX MATCH XLOOKUP CHOOSE ROW COLUMN ROWS COLUMNS, TODAY NOW DATE YEAR MONTH
DAY WEEKDAY DATEDIF EDATE EOMONTH HOUR MINUTE SECOND TIME DAYS, TEXT FIND SEARCH SUBSTITUTE VALUE TEXTJOIN REPLACE PROPER
CHAR CODE, STDEV(.S/.P) VAR(.S/.P) RANK(.EQ) SUMPRODUCT, IFS SWITCH XOR IFNA ISBLANK ISNUMBER ISTEXT ISERROR ISNA NA, PI
EXP LN LOG LOG10 TRUNC; `Evaluator.fn3`). `Formula.FUTURE`: functions newer than the 2007 format are stored as
`_xlfn.NAME(` (what Excel writes; without it Excel shows #NAME?) and `Formula.display` drops the prefix for the formula
bar; the parser reads `_xlfn.` / `_xlws.` names as the plain function. TEXT's date codes are rendered by
`core/xlsx/TextFormat` (yyyy mmm dddd hh:mm AM/PM, quoted literals). The hand-worked table of 63 formulas is
`XlsxM3Test.EXPECTED`, copied for QA to `apps/spatial-office/build/m3-expected.md` in the factory.

**Documents and slides** (d20 d21 d22 d42, p26 p40): see the two paragraphs below.

**Zoom (d43 / s41 / p41).** `Session.zoom` (50–200 %, `ZOOM_STEPS`), `zoomBy(±1)` / `zoomReset()` / `setZoomPct`; the
title bar's [n % ▾] (`ui/Editor.kt ZoomMenu`: zoom in / out / fit width · 100 % · fit window) and Ctrl+= / Ctrl+- /
Ctrl+0 in `commonKeys`. Display only (the file and `dirty` are untouched; `M3SheetTest`). Document: the paper is drawn
under `Zoomed` (a `LocalDensity` scaled by the zoom, so text, widths and hit-testing follow) and scrolls sideways when it
gets wider than the window; sheet: the grid under `Zoomed` (headers and frozen rows scale with it); slides: the canvas is
drawn `zoom` × the fitted width inside the fitted frame, which scrolls both ways above 100 %. Log `ZOOM <KIND> -> n%`;
`DUMP … zoom=DOC:100,SHEET:125,… merges=B7:C8`. The editor status line names the folder only (the name is the title),
so a long file name no longer overflows it.

v0.5.0 documents (d20 / d21 / d22 / d42). **Links (d20).** `Run.link` / `Span.link` hold the URL of an external
`w:hyperlink` (`r:id` to a TargetMode="External" relationship of type `…/hyperlink`). A paragraph whose links hold only
plain runs stays editable. `runsXml` wraps neighbouring runs with the same link in one `<w:hyperlink r:id w:history="1">`
and reuses an existing relationship for the same URL (`linkRid`, `Rels.add(external = true)`). `RichText.setLink /
linkAt / links`: a new link is underlined in 0563C1; removing it takes both away. `DocSession.setLink / insertLink` are
one undo step each; `normalizeUrl` adds `https://` or `mailto:`. [Insert ▾] › Link… / Ctrl+K opens `Overlay.Link`
(address, text to show when nothing is selected, [Remove link] for an existing link). With the caret in a link, a row
under the paragraph shows the address with [Open] and [Edit]; a click on a link in a read-only paragraph opens it.
Opening goes through `Graph.openLink` → `Accounts.launchBrowser` (ACTION_VIEW + BROWSABLE). **Horizontal line / page
break (d21).** `DocxDocument.insertRuleAfter` writes an empty paragraph with `w:pBdr/w:bottom single` (drawn as a line);
`insertPageBreakAfter` writes `<w:p><w:r><w:br w:type="page"/></w:r></w:p>` (a labelled line with [Remove]).
**Superscript / subscript (d22).** `w:vertAlign` on runs: `Run/Span.vertAlign`, `RichText.setVertAlign`, [Text ▾] ›
Superscript / Subscript (the same again returns to the baseline); drawn at 70 % with a baseline shift. **Table rows &
columns (d42).** `TableBlock.merges` (`CellMerge`, read from `w:gridSpan` and `w:vMerge`) are drawn spanning. With the
caret in a table cell, [Insert ▾] shows "Table rows & columns ›": rows above / below, columns left / right, merge with
right / below, delete row / column (`insertTableRow / deleteTableRow / insertTableColumn / deleteTableColumn /
mergeTableRight / mergeTableDown`); the grid keeps the table's total width; deleting the last row or column removes the
table; impossible merges return false with a short message. `DocSession.tableOp` is one undo step. Only the table's own
XML range is rewritten. Logs `DOCLINK`, `DOCINSERT`, `DOCTABLE`. Tests: `DocxM3Test` (core), `M3DocTest` (app).

v0.5.0 slides (p26 / p40). **Background (p26).** [Page ▾] › Background › eight colours (the Object › Fill swatches),
Picture… (the picture picker), Layout's background, and Apply to all slides › (the same for every slide).
`PptxDeck.setSlideBackground(slide, rgb|null)` writes `<p:bg><p:bgPr><a:solidFill><a:srgbClr/></a:solidFill><a:effectLst/>
</p:bgPr></p:bg>` as the first child of `p:cSld`; null removes the slide's own `p:bg`. `setSlideBackgroundPicture` stores
the picture once as a media part; `setSlideBackgroundPicturePart` points further slides at the same part (`a:blipFill`,
stretch); a replaced or removed picture background drops its relationship, and the part once nothing uses it.
`Slide.backgroundImage` / `ownBackground`; the canvas, thumbnails, storyline, rehearsal and the PDF export draw it.
`DeckSession.setBackground(rgb, all)` / `setBackgroundPicture(bytes, ext, all)`, one undo step each. **Tables (p40).**
`core/pptx/TableEdit` reads an `a:tbl` into a grid of owning cells, edits it and writes it back (cells whose merge
attributes are unchanged keep their exact text; new cells copy their neighbour's `tcPr` / body / run properties; column
widths refit to the frame's `cx`, the frame's `cy` follows rows): `tableInsertRow / tableDeleteRow / tableInsertCol /
tableDeleteCol / tableMerge(slide, id, row, col, down) / tableArea`; a merge writes `gridSpan` + `hMerge` or `rowSpan` +
`vMerge` and appends the second area's text to the first. A click on a cell of the picked table makes it the current cell
(blue frame; `DeckSession.tableCell / pickCell / currentCell`); [Object ▾] › Table rows & columns (row n, col m) › lists
what can happen now (`tableAbilities`). Merged tables are typeable (covered cells refuse text). Logs `SLIDEBG`,
`SLIDETABLE`. Tests: `PptxM3Test` (core), `M3SlidesTest` (app).

## v0.6.0 (M4, design-package roadmap[3]; "Organising, layout tools, templates"; gateB=false, no new panels)

**Documents.** d23 a selection across paragraphs: every editable paragraph is its own text field, so the paper
(`Paper`, DocWindow.kt) takes a press-and-drag at the Initial pass after the touch slop and works out the paragraph and
offset under the pointer from `ParaGeo` (each paragraph field reports its coordinates and text layout; log
`DOCGEO para=<id> x,y wxh` in window px for `adb shell input swipe`). Inside one paragraph it sets that field's
selection (`DocFocus.dragSel`); across paragraphs it sets `DocSession.crossSel` (`DocSel`, drawn as a light blue
ground by `crossMarked`; log `DOCSEL drag …`); near the top / bottom edge the page scrolls on. A click lets go of it,
Esc too; Delete / Backspace remove it (`deleteCross`, the two ends joined). Ctrl+C / Ctrl+X (and [More ▾ ▸ Copy /
Cut / Paste], the menu entries of the shortcuts) keep `DocSession.docClip` (`DocClip`: spans with their formats, style
and list level per paragraph; `src = -1`, so the formats are written from the spans) and put the plain text on the
system clipboard; Ctrl+V pastes the clip with its formats when the clipboard still holds its text (Word's way: the
first part joins the text before the caret, middle parts become paragraphs of their own, the last one is followed by
what was after the caret; one undo step, `pasteClip`), other clipboard text as plain paragraphs. Log `DOCCLIP …`.
d25 [More ▾ ▸ Outline] (`Overlay.Outline`, `OutlineSheet`): title and headings 1–3 (`DocSession.outline()`), a click
calls `jumpTo` (the paper scrolls there, caret in the heading; log `OUTLINE jump to …`).

**Templates (d26 / p27).** `core/templates/Templates.kt`: `DocTemplate` (meeting notes, weekly report, proposal,
résumé) and `DeckTemplate` (work report, product pitch, minimal dark), built with the editors' own calls (styles,
lists, tables; theme colours + accent bands + placeholders), in the app language, ordinary parts only. Home ▸ New has
a row of them under the document and presentation cards (`TemplateButton`); `Graph.newFile(kind, template)` names the
file after the template (log `NEW from template …`). `core/build/m4-fixtures/templates/` (M4FixturesTest) holds all 14
for checking with python-docx / python-pptx.

**Sheets.** s28 `core/xlsx/FormulaHints`: every function the evaluator knows with its arguments; while a formula is
typed, `FormulaHint` (over the grid: at the bottom when the cell is in the top rows, else at the top) lists the names
that start with what is typed (click one, or Tab = `completeFunction`), then inside the brackets the arguments with
the current one in bold (log `FNHINT …`). s29 [Data ▾ ▸ Filter column X…] (`Overlay.FilterData`, `FilterSheet`):
pick values, greater than, less than, contains (`core/xlsx/FilterRule`; a view of the data as before, not saved; log
`FILTER …`); the one-value [Only “…”] stays. s31 [Data ▾ ▸ Sort…] (`Overlay.SortData`, `SortSheet`): the selected
range or the block around the cell, up to three columns each ascending / descending, [My data has headers] guessed by
`SortPlan.looksLikeHeader` (`XlsxWorkbook.sortRowsBy`; text compares by `Collator(Locale.CHINA)`: Chinese by pinyin,
Latin ignoring case; log `SORT …`). s30 [Data ▾ ▸ Freeze at B2] (`freezeHere`) / [Unfreeze]: `setFreeze(rows, cols)`
writes `<pane xSplit ySplit topLeftCell activePane="bottomRight" state="frozen"/>`; the grid keeps frozen columns
left of a blue line while the rest scrolls. s42 [Rows & columns ▾ ▸ Hide ›] rows / columns / Unhide: `hidden="1"` on
`<row>` / on the column's own `<col>` (split out of a wider span, with a width); the grid leaves them out and marks the
header after them with a blue edge (log `HIDE` / `UNHIDE`).

**Slides.** p29 [Object ▾ ▸ Add to selection (n picked)] (pick an object, add it, pick the next…; Shift+click is not
needed), then [Align ›] left / centre / right / top / middle / bottom, [Distribute ›] across / down (three or more),
[Group (n)], [Ungroup] on a group, [Clear selection] (`DeckSession.multi`, `multiSet()`, `core/layout/Arrange`;
`PptxDeck.groupShapes / ungroupShape`: a `p:grpSp` whose `chOff / chExt` equal its box, so the objects keep their own
`a:off`; placeholders are made plain shapes first). A group is drawn with its objects (`Shape.children`) and is
picked, dragged and resized as one. p25 a dragged object snaps to the edges / centres of the others and of the slide
within 1/120 of the slide width (`Arrange.snap`, `DeckSession.guides`, thin pink lines; log `SNAP …`). Logs `MULTI`,
`ARRANGE`, `GROUP`. p28 the rehearsal bar has [Go to…] (a row of slide numbers) and [Black screen] / [Show slide]; the
bar is a typing target, so a number + Enter jumps and B / . toggles black (KeyGuard lets Enter through); [Page ▾ ▸
Rehearse from slide 1] (the title bar's [Rehearse] starts at the current slide). Logs `REHEARSAL go / black / typed`.

**K1 (QA #15).** After the Stage, `ArcRing.replan` brings the windows back on the side of the home window they stood
on, and the file just rehearsed / spread out takes the place right next to it (QA #15: the slides came back ~126°
away and the sheet and document changed sides). Nothing is closed and reopened beyond what the Stage already does and
no window is hidden. The home window shows `BackHintBar` ("… is to the right of this window") until that window gets
the focus, [Got it], or 30 s (log `STAGE closed … row-before=… plan=…`, `BACKHINT`). The ~63° neighbour angle is
unchanged (waiting for the user).

Debug commands (v0.6.0): `new-tpl meeting|weekly|proposal|resume|report|pitch|dark`, `filter-dialog`, `sort-dialog`,
`filter-rule <col> <values|gt|lt|ge|le|contains> <text | v1|v2>`, `sort-by A:asc,C:desc [1|0]`, `freeze-here`,
`unfreeze`, `hide rows|cols`, `unhide`, `outline`, `outline-go <text>`, `doc-xsel <i>:<off>-<j>:<off>`, `doc-copy`,
`doc-cut`, `doc-paste <i|last> <offset|end>`, `multi-add [id]`, `multi-clear`, `align left|center|right|top|middle|bottom`,
`distribute h|v`, `group`, `ungroup`, `rehearse-first`, `rh-go <n>`, `rh-black`, `rh-picker 1|0`, `rh-type <digits>`.
Sample files: `./gradlew :core:test --tests "*M4FixturesTest*"` → `core/build/m4-fixtures/` (outline-sample.docx with
chapters one to five, cross-select.docx, sales-m4.xlsx with 部门 / 姓名 / 金额). Tests: `M4CoreTest`, `M4AppTest`.

## The save rule

Opening never changes a file. Saving rewrites only the parts that hold an edit, and inside such a part only the
edited element's character range; everything else is byte-identical (`core` tests assert this for every fixture).
The first save of an existing file backs the original up beside it (`name（原件）.ext`).

## Debug broadcast (debug builds only)

```
adb -s <serial> shell am broadcast -a io.embodit.spatialoffice.DEBUG --es cmd <cmd> [--es arg <arg>]
```
Each command calls the same function as the control it stands for; the app logs `DUMP …` after every command (tag `SpatialOffice`).

| cmd | arg | Does |
|---|---|---|
| `page` | `recent` `browse` `new` `account` `settings` | home page |
| `lang` | `zh` `en` | language |
| `open` | absolute path | open a file |
| `new` | `doc` `sheet` `slides` | new file |
| `save` / `saveas` / `savefirst` / `confirm-save` / `close` / `compat` / `shortcuts` | `doc` `sheet` `slides` | per editor window |
| `unsaved` | `save` `discard` `cancel` | answer the unsaved-changes dialog |
| `howto`, `exit-confirm`, `exit`, `dismiss` | — | settings sheets / exit / close the open overlay |
| `type` | text | set the first editable paragraph of the open document |
| `cell` | `B2=123` | set a cell; `select` `B2`; `sheet` index |
| `slide` | index | current slide |
| `chart`, `chart-type` (`columns` `scatter`), `chart-yaw` degrees | | 3D chart volume |
| `copybeside` | `doc` `sheet` `slides` | show the notice the Feishu fallback shows (original kept, edits saved as "<name> (空间办公 副本)"); screenshot only |
| `chart-place` | `top:0` (default) `bottom:<dp>` `left:<dp>` `right:<dp>` `none` | placement the next chart opening uses (emulator trials only) |
| `spread` | `doc` `sheet` `slides` | enter the spread layout; `arr` `row` `arc` `layers`; `focus` index; `recenter`; `collapse` |
| `rehearse` | — | slide rehearsal (`collapse` ends it) |
| `radius` `0..2`, `openmode` `window` `spread` | | settings |
| `slide-add` | `title` `body` `two` `blank` | new slide after the current one (same as New slide ▾) |
| `slide-dup` / `slide-del` / `slide-move` | — / — / `from:to` | duplicate, delete, move (as the thumbnail drag) |
| `textbox`, `shape` | text / `rect` `roundRect` `ellipse` `triangle` `rightArrow` | insert on the current slide (the new object is picked) |
| `sel`, `nudge`, `obj-del` | shape id / `dx,dy[,scale]` / — | pick an object, move or scale it (1/20 of the slide per step), delete it |
| `theme` | `0`–`3` | light / dark / warm / forest colour scheme |
| `img` | `doc <path>` or `slides <path>` | insert a picture file (as Insert ▸ Picture…) |
| `img-pick` | `doc` `slides` | show the picture picker |
| `table`, `table-cell` | `rows,cols` / `r,c=text` | insert a table after the caret paragraph / set a cell of the last table |
| `para` | `<i> style <body|h1|h2|h3|bullet|numbered>`, `<i> align <0-3>`, `<i> strike <0|1>`, `<i> bold <0|1>` | format the i-th editable paragraph |
| `addpara` | `<style> <text>` | append a paragraph |
| `replace`, `wordcount`, `inserttable` | `old=new` / — / — | replace all in the document, word count, insert-table dialog |
| `fill`, `fmt`, `bold` | `RRGGBB`/`none` · `general` `other` `currency` `percent` `date` · `1`/`0` | selected cell |
| `freeze`, `filter`, `filter-clear`, `sort` | — / — / — / `asc` `desc` | Data ▸ … on the selected cell's column |
| `row-ins`, `row-del`, `col-ins`, `col-del` | — | Rows & columns ▸ … at the selection |
| `chart-add`, `chartview` | `bar` `line` `pie` / index | make a chart from the data around the selection / show the flat chart sheet |
| `browse` | `0`–`4` | Browse page at Device / Feishu / Google Drive / OneDrive / Dropbox |
| `connect` | `feishu` `gdrive` `onedrive` `dropbox` | same as [Connect]: the system browser on the service's sign-in page (nothing when the build has no client id for it) |
| `saveas-cloud` | `doc` `sheet` `slides` | Save as ▸ a copy to the signed-in drive (`Accounts.saveAsDrive`); with none the dialog has no such row and this does nothing (log `SAVEAS cloud ignored`) |
| `browse-dir` | absolute folder path | Browse page inside that folder |
| `scan` | `feishu` `onedrive` | scan-to-sign-in for real (needs a registration) |
| `scan-preview` | `feishu` `onedrive` | the scan dialog filled with a **sample** code `SAMP-LE42` (no request sent; for screenshots only) |
| `connected-preview` | provider | the "connected" dialog (sample state) |
| `conflict` | `doc` `sheet` `slides` | the "changed in the cloud" dialog of that window (what a cloud save shows on a version mismatch) |
| `conflict-answer` | `<win> overwrite|copy|cancel` | answer it (same function as the buttons) |
| `inbox` | — | check the Feishu inbox now |
| `range` | `B2:D5` or `B2` | select a range (as a drag from the first to the last cell); `range-row` n, `range-col` letter, `extend` up/down/left/right, `jump` dir[`+shift`] |
| `copy` `cut` `paste` | — | Edit ▸ Copy / Cut / Paste on the selection |
| `clip-set` | text (`\t` `\n`) | put text on the system clipboard (to prepare an outside paste only) |
| `align` `wrap` `decimals` | `left` `center` `right` · `1`/`0` · `1`/`-1` | Format ▸ … on the selection |
| `col-wider` `col-narrower` `col-autofit` `row-taller` | — | Rows & columns ▸ … on the selected columns / rows |
| `sheet-new` `sheet-copy` `sheet-rename` `sheet-rename-ask` `sheet-delete` `sheet-delete-now` | name for `sheet-rename` | the [⋯] menu (`-ask` / `sheet-delete` show the dialogs) |
| `find` | `doc` `sheet` `slides` | the find sheet of that window; `find-q` text [`case`], `find-with` text, `find-scope` `sheet`/`all` fill it |
| `recover` | `yes` `no` | answer "Recover unsaved changes?"; `draft-now` runs one autosave round |
| `obj-copy` `obj-paste` `obj-dup` `z` | `front` `back` `forward` `backward` for `z` | Object ▸ … / Insert ▸ Paste on the picked slide object |
| `notes` | text | the current slide's speaker notes |
| `back` | — | what BACK / controller B does (BackPolicy) |
| `docfmt` | `<i> <a>-<b>|all <op> [v]` | v0.4.0: [Text ▾] / [Colour ▾] on characters a..b of the i-th editable paragraph; op `color RRGGBB|none`, `highlight yellow|green|cyan|none`, `size-up`, `size-down`, `size <pt>`, `clear`, `strike|bold|italic|underline 1|0` (log `DOCTEXT debug … spans=…`) |
| `doclevel`, `docindent`, `docline`, `docspace` | `<i> tab|shift-tab` / `<i> more|less` / `<i> 240|276|360|480|none` / `<i> <before>,<after>|none` | list level (what Tab / Shift+Tab do), indent, line spacing, space before / after |
| `history`, `hist-restore` | `doc|sheet|slides` / `<win> <n>` | the version list; [Restore] on the n-th version counted from the oldest |
| `font`, `font-color`, `font-size`, `font-clear` | `italic|underline|strike|bold 1|0` / `RRGGBB|none` / `<pt>|up|down` / — | sheet [Text ▾] / [Colour ▾] on the selection |
| `stext-sel`, `stext` | `<a>,<b>` / `bold|italic|underline|strike`, `color RRGGBB|none`, `size up|down|<pt>`, `align 0|1|2`, `bullet 1|0`, `clear` | select characters of the picked slide text box, then the [Text ▾] / [Colour ▾] entries (log `SLIDETEXT debug … runs=…`) |
| `obj-fill`, `obj-line`, `obj-rot`, `obj-rotby`, `rotdrag` | `RRGGBB|none` / `RRGGBB|none [pt]` / degrees / degrees / degrees | picked slide object: fill, line, rotation (absolute / by), the rotate handle (press, drag to that angle snapped to 15°, release) |
| `dump` | — | log state only (v0.2.1 adds `tableCaret`, `cols`; v0.2.5 `open=[…]`, `chartOf`, `objBox`) |
| `objdrag` | `move|top_left|top_right|bottom_left|bottom_right <n>x<dxEmu>,<dyEmu>` | n pointer steps on the picked slide object through the session calls the gesture makes |
| `slide-table`, `slide-cell` | `rows,cols` / `r,c=text` | insert a table on the slide (picked) / set a cell of the picked table |
| `doc-pick`, `doc-resize` | `<n>` / `<corner> <dx,dy>` | pick the n-th picture or table of the document / resize it (picture: EMU, table: percent in dx) |
| `front`, `activate`, `close-tag` | window tag (`f1` …) | open-again behaviour / make it the active one of its kind / its title-bar close |
| `front-home`, `arrange` | — / tag | Windows ▸ Home / Windows ▸ Side by side around that window |
| `win-place`, `front-mode` | `arc` (default) `front` `gap:<dp>` / `auto` `restore` `reopen` | v0.5.1: `arc` = new editor windows beside the ones up (ArcRing, log `ARC`); `front` = the old in-front rule; placement trials |
| `cloud-id` | `<service> [client id]` | debug-only client id for this run (shows the service, [Connect] opens the browser) |
| `mainwatch` | seconds (default 10) | log the longest main-thread frame gap over that time (`MAINWATCH baseline …`), the emulator's own load for comparing with `MAINWATCH chart-open` |
| `fill-cells` | `down` `right` | v0.5.0 s24: Ctrl+D / Ctrl+R on the selection (`fill` stays the fill colour) |
| `series` | — / `down` / `right` | Edit ▸ Fill › Fill series (no arg: down when the selection is taller than wide) |
| `merge`, `unmerge` | — | Rows & columns ▸ Merge cells / Unmerge cells on the selection (log `MERGE`) |
| `border` | `all` `outline` `bottom` `none` | Colour ▸ Borders › on the selection (log `BORDER`) |
| `zoom` | `<doc|sheet|slides> in|out|reset|<pct>` | the title bar's zoom menu / Ctrl+= / Ctrl+- / Ctrl+0 (log `ZOOM`) |
| `doc-link` | `<i> <a>-<b>|all <url>` | v0.5.0 d20: link characters a..b of the i-th editable paragraph; `doc-link-open <i>` opens its first link (as a click), `doc-link-edit <i> <url>`, `doc-unlink <i>`, `doc-link-sheet <i> [<a>-<b>]` opens the link dialog (as Ctrl+K) |
| `doc-rule`, `doc-pagebreak` | `[<i>]` | d21: Insert ▸ Horizontal line / Page break after the caret paragraph (or paragraph i) |
| `docfmt` … `sup 1|0`, `sub 1|0` | | d22: superscript / subscript on characters of a paragraph |
| `doc-tcell`, `doc-trow`, `doc-tcol`, `doc-tmerge` | `<n> <r>,<c>` / `above|below|delete` / `left|right|delete` / `right|down` | d42: caret into a cell of the n-th table, then Insert ▸ Table rows & columns › … (log `DOCTABLE`) |
| `slide-bg` | `<RRGGBB|none|img:<abs path>> [all]` | p26: Page ▸ Background › on this slide (or all) (log `SLIDEBG`) |
| `stable-cell`, `stable-row`, `stable-col`, `stable-merge` | `<r>,<c>` / `above|below|delete` / `left|right|delete` / `right|down` | p40: current cell of the picked table, then Object ▸ Table rows & columns › … (log `SLIDETABLE`) |

v0.5.0 screenshot states (both languages, `m3-<lang>-*.png` in the factory's `build/v0.5.0/`): sheet-m3 (four formulas,
fill down, series, merge, borders), zoom-sheet / zoom-doc / zoom-slides (125 %), doc-m3 (sub / sup, link, line, page
break, table with a right and a down merge), doc-link-dialog, slides-m3 (background on all + this slide, table with a
new row and a right merge), unsaved-confirm.

v0.2.0 screenshot states (both languages): doc toolbar menus (Paragraph, Insert, More), find & replace, word count,
insert table, picture picker, a doc with numbered list + centred heading + strikethrough + picture + table; slides with
New slide ▾, two-column and blank slides, a picked shape, a picture, dark theme; sheet with fill colours, frozen top row,
a filter, Data / Rows & columns / Chart menus, flat line and pie charts; Accounts page and Browse › Feishu (v0.2.5: only with a client id; `cloud-id feishu <id>` in debug builds).

Screenshot states: home (5 pages × 2 languages), new-doc / new-sheet / new-slides, doc, sheet, slides, compat,
save-first, save-as, unsaved, shortcuts, how-to, exit-confirm, chart columns / scatter, spread row / arc / layers
for each kind, rehearsal.

## v0.5.1 (M3fix)

- Cells: `CellEdit.generation` renews the formula bar's text field (and input connection) after each commit; `CellKeyboard`
  (no capital, no autocorrect); a keyboard re-sending the last word within 800 ms is dropped (log `CELLEDIT dropped echo`). `M3FixTest`.
- Links: `UrlKeyboard` (URI type, no capital) on the address; `DocSession.normalizeUrl` lower-cases the scheme; `Graph.openLink`
  toasts `link_cant_open` when nothing opens it.
- Windows: `core/layout/ArcRing` + `WindowCtl.ring`: the first editor window opens in front, every next one right / left of the
  outer window (`Placement.right/left`, 56 dp). `ArcRingTest`.
- Browser: `BrowserPick` sends http(s) to PICO Browser (`com.picoxr.browser`) by package (sign-in pages and links), manifest `<queries>`.
- BACK: `OpenMenus` — an open toolbar / title-bar menu is closed first (`BackAct.CLOSE_MENU`).
- Emulator 6.2.1 (`Pico_Emulator_6_2_a`): menus, Sheets, Stage (rehearsal / storyline) and the 3D chart all work; real clicks
  through `npx -y @picoxr/pico-cli@0.7.0 interact query|perform` (query is capped at 100 nodes: filter with `--text`). Start it
  muted: `ANDROID_AVD_HOME` + `ANDROID_SDK_ROOT=<sdk>/6.2/emulator/system-images` + `ANDROID_SDK_HOME=~/.pico`, then
  `emulator.exe -avd Pico_Emulator_6_2_a -port 5566 -gpu angle_indirect -sysdir <…>/system-images -no-audio`.

## v0.5.2 (M3fix2, QA #14 q14-1)

- Windows: the home window is the hub of the row (`ArcRing.place(key, hub = Containers.HOME)`): with Home up and no file
  window in the row, Home becomes the row's first window and the first file goes `Placement.right(home)`; next ones
  alternate left / right as before. `frontHome` / `front` only reopen a window the system took off stage (`onstage=false`),
  never one that is on stage but out of sight (reopening it in front put it on the spot of the window being looked at and
  the system then hid that one). `ArcRingTest` (home + 3 files, home closed, all files closed). Log `ARC … row=[…]`,
  `WINDOW front HOME … row=[…]`.

## Cloud accounts

`platform/Accounts.kt` (sign-in, tokens in EncryptedSharedPreferences, file calls) on top of the pure
`core/cloud/` (requests, PKCE, answer parsing, Feishu online doc / sheet diffs). A service is offered
only when its client id was put in at build time (`-Pcloud.<service>.…` or `local.properties`; README "连接账号");
the others do not appear in the app at all.

## Not in this version

Fonts (typeface), comments and tracked changes (read-only), charts inside documents and slides,
animations, conditional formats (kept, not editable; merged cells are editable since v0.5.0), web-page editing, pinned cards, named layouts,
3D objects on slides. (v0.2.1 added scan-to-sign-in, the cloud conflict check and the Feishu inbox; none of them has
run against a real account yet.)

## Toolbar keyboard focus (v0.2.1)

The system Toolbar is a window of its own. After its menus the keyboard stays there and the app cannot take the window
focus back (`restoreWindowContainer` returns false). So the doc and sheet toolbars take keys themselves: doc → the
table cell with the caret (`DocSession.typeInCell` / `moveCell`), sheet → the selected cell (like typing on the grid).
