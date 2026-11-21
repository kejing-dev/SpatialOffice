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
`ui/SafeSound`: SpatialUI 6.2.8 plays its click through `SpaceView.playSoundEffect`, which system images before that
method lack (the 2026-09-01 PICO 6.1.0 emulator image): there the buttons are silent instead of the app dying on the
first press (log `SOUND system click …`).

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
| `dump` | — | log state only (v0.2.1 adds `tableCaret`, `cols`; v0.2.5 `open=[…]`, `chartOf`, `objBox`) |
| `objdrag` | `move|top_left|top_right|bottom_left|bottom_right <n>x<dxEmu>,<dyEmu>` | n pointer steps on the picked slide object through the session calls the gesture makes |
| `slide-table`, `slide-cell` | `rows,cols` / `r,c=text` | insert a table on the slide (picked) / set a cell of the picked table |
| `doc-pick`, `doc-resize` | `<n>` / `<corner> <dx,dy>` | pick the n-th picture or table of the document / resize it (picture: EMU, table: percent in dx) |
| `front`, `activate`, `close-tag` | window tag (`f1` …) | open-again behaviour / make it the active one of its kind / its title-bar close |
| `front-home`, `arrange` | — / tag | Windows ▸ Home / Windows ▸ Side by side around that window |
| `win-place`, `front-mode` | `front` `gap:<dp>` / `auto` `restore` `reopen` | placement trials (emulator only) |
| `cloud-id` | `<service> [client id]` | debug-only client id for this run (shows the service, [Connect] opens the browser) |
| `mainwatch` | seconds (default 10) | log the longest main-thread frame gap over that time (`MAINWATCH baseline …`), the emulator's own load for comparing with `MAINWATCH chart-open` |

v0.2.0 screenshot states (both languages): doc toolbar menus (Paragraph, Insert, More), find & replace, word count,
insert table, picture picker, a doc with numbered list + centred heading + strikethrough + picture + table; slides with
New slide ▾, two-column and blank slides, a picked shape, a picture, dark theme; sheet with fill colours, frozen top row,
a filter, Data / Rows & columns / Chart menus, flat line and pie charts; Accounts page and Browse › Feishu (v0.2.5: only with a client id; `cloud-id feishu <id>` in debug builds).

Screenshot states: home (5 pages × 2 languages), new-doc / new-sheet / new-slides, doc, sheet, slides, compat,
save-first, save-as, unsaved, shortcuts, how-to, exit-confirm, chart columns / scatter, spread row / arc / layers
for each kind, rehearsal.

## Cloud accounts

`platform/Accounts.kt` (sign-in, tokens in EncryptedSharedPreferences, file calls) on top of the pure
`core/cloud/` (requests, PKCE, answer parsing, Feishu online doc / sheet diffs). A service is offered
only when its client id was put in at build time (`-Pcloud.<service>.…` or `local.properties`; README "连接账号");
the others do not appear in the app at all.

## Not in this version

Font / size / colour of text, comments and tracked changes (read-only), charts inside documents and slides,
animations, merged cells / conditional formats (kept, not editable), web-page editing, pinned cards, named layouts,
3D objects on slides. (v0.2.1 added scan-to-sign-in, the cloud conflict check and the Feishu inbox; none of them has
run against a real account yet.)

## Toolbar keyboard focus (v0.2.1)

The system Toolbar is a window of its own. After its menus the keyboard stays there and the app cannot take the window
focus back (`restoreWindowContainer` returns false). So the doc and sheet toolbars take keys themselves: doc → the
table cell with the caret (`DocSession.typeInCell` / `moveCell`), sheet → the selected cell (like typing on the grid).
