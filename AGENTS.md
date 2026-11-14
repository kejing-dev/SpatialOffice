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

Containers (`Main.kt`): home window (default), one Planar window per editor, a Volumetric window for the chart, one
Mixed Stage. Editors open to the right of an existing window; nothing is placed from the head pose.

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
| `dump` | — | log state only |

Screenshot states: home (5 pages × 2 languages), new-doc / new-sheet / new-slides, doc, sheet, slides, compat,
save-first, save-as, unsaved, shortcuts, how-to, exit-confirm, chart columns / scatter, spread row / arc / layers
for each kind, rehearsal.

## Not in this version

Cloud accounts (no app identity registered with any provider: the screens say so), editing tables / images /
charts inside documents and slides, tracked-change paragraphs (read-only), web-page editing, pinned cards,
named layouts, 3D objects on slides.
