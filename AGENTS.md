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
```

## Layout

| Path | What |
|---|---|
| `core/` | Pure Kotlin/JVM, no Android. `pkg/` OPC package + XML range scanner; `docx/`, `xlsx/`, `pptx/`, `csv/` read / edit / patch-only save; `text/` rich-text spans, plain text / Markdown, web-page text; `layout/` spread rules, layout record, level gate; `files/` naming rules. |
| `app/…/platform/` | `Graph` (process-wide state and every action), `Sessions` (one open file each), `Storage`, `Stores` (settings, recents, layout records), `WindowCtl`, `Exporter` (save as), `Head`, `DebugReceiver`. |
| `app/…/ui/` | `home/` (recent, browse, new, accounts, settings), `doc/`, `sheet/`, `slides/` editors, `chart/` 3D chart volume, `space/` Stage (spread layout, rehearsal), `Overlays.kt` (save sheets, dialogs). |
| `tools/` | `strings.tsv` + `gen_strings.py`, `svg2vd.py`, test-fixture generators and verifiers. |

Containers (`Main.kt`): home window (default, 1120 × 660 dp), one Planar window per editor, a Volumetric window for the
chart, one Mixed Stage. Since v0.2.1 every editor and the chart open with `editorPlacement()` = the system's spot in
front of the user (`Placement.right(home)` put them 22°–60° to the right, QA #1 q1-1; `core/layout/WindowFit` +
`WindowFitTest` check all 8 corners). Nothing is placed from the head pose.

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
| `find`, `replace`, `wordcount`, `inserttable` | — / `old=new` / — / — | find-and-replace sheet, replace all, word count, insert-table dialog |
| `fill`, `fmt`, `bold` | `RRGGBB`/`none` · `general` `other` `currency` `percent` `date` · `1`/`0` | selected cell |
| `freeze`, `filter`, `filter-clear`, `sort` | — / — / — / `asc` `desc` | Data ▸ … on the selected cell's column |
| `row-ins`, `row-del`, `col-ins`, `col-del` | — | Rows & columns ▸ … at the selection |
| `chart-add`, `chartview` | `bar` `line` `pie` / index | make a chart from the data around the selection / show the flat chart sheet |
| `browse` | `0`–`4` | Browse page at Device / Feishu / Google Drive / OneDrive / Dropbox |
| `connect` | `feishu` `gdrive` `onedrive` `dropbox` | same as [Connect] (opens the system browser when the app is registered) |
| `saveas-cloud` | `doc` `sheet` `slides` | Save as ▸ a copy to the first connected drive |
| `browse-dir` | absolute folder path | Browse page inside that folder |
| `scan` | `feishu` `onedrive` | scan-to-sign-in for real (needs a registration) |
| `scan-preview` | `feishu` `onedrive` | the scan dialog filled with a **sample** code `SAMP-LE42` (no request sent; for screenshots only) |
| `connected-preview` | provider | the "connected" dialog (sample state) |
| `conflict` | `doc` `sheet` `slides` | the "changed in the cloud" dialog of that window (what a cloud save shows on a version mismatch) |
| `conflict-answer` | `<win> overwrite|copy|cancel` | answer it (same function as the buttons) |
| `inbox` | — | check the Feishu inbox now |
| `dump` | — | log state only (v0.2.1 adds `tableCaret`, `cols`) |

v0.2.0 screenshot states (both languages): doc toolbar menus (Paragraph, Insert, More), find & replace, word count,
insert table, picture picker, a doc with numbered list + centred heading + strikethrough + picture + table; slides with
New slide ▾, two-column and blank slides, a picked shape, a picture, dark theme; sheet with fill colours, frozen top row,
a filter, Data / Rows & columns / Chart menus, flat line and pie charts; Accounts page (unregistered rows) and
Browse › Feishu.

Screenshot states: home (5 pages × 2 languages), new-doc / new-sheet / new-slides, doc, sheet, slides, compat,
save-first, save-as, unsaved, shortcuts, how-to, exit-confirm, chart columns / scatter, spread row / arc / layers
for each kind, rehearsal.

## Cloud accounts

`platform/Accounts.kt` (sign-in, tokens in EncryptedSharedPreferences, file calls) on top of the pure
`core/cloud/` (requests, PKCE, answer parsing, Feishu online doc / sheet diffs). Each service needs an app
registration in `local.properties`; see README "连接账号". Unregistered services say so and send nothing.

## Not in this version

Font / size / colour of text, comments and tracked changes (read-only), charts inside documents and slides,
animations, merged cells / conditional formats (kept, not editable), web-page editing, pinned cards, named layouts,
3D objects on slides. (v0.2.1 added scan-to-sign-in, the cloud conflict check and the Feishu inbox; none of them has
run against a real account yet.)

## Toolbar keyboard focus (v0.2.1)

The system Toolbar is a window of its own. After its menus the keyboard stays there and the app cannot take the window
focus back (`restoreWindowContainer` returns false). So the doc and sheet toolbars take keys themselves: doc → the
table cell with the caret (`DocSession.typeInCell` / `moveCell`), sheet → the selected cell (like typing on the grid).
