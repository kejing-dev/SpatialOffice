# Spatial Office (空间办公) — development handover

The design passed the user's design gate (factory "gate B") on 2026-11-14. **Do not redesign.** Implement it.

## Where the approved design is

| What | Path | Note |
|---|---|---|
| PRD v0.7 (text, 91 KB) | `.scratch/prd_v07_body.html` | Read this one. The delivered `prd.html` in the factory is the same text with 23 inlined screenshots (4 MB) — do not load it into context. |
| Interactive preview (source) | `.scratch/src/01-head.html`, `02-body.html`, `03-app.html`; assembled `.scratch/preview.html` | SpatialUI web runtime (`sui-*`). All copy is in the `I18N` / `OV*` dictionaries in `03-app.html`, Chinese and English. Use these strings as written. |
| Design notes | `.scratch/design-doc.md` (§2d and §2e are the current direction; earlier sections are history) | Containers, coordinates, layout rules. |
| Review screenshots | `.scratch/review/01…43-*.png` | What each screen should look like. |
| Icons / assets drawn for the design | `.scratch/assets/` | Original shapes; no third-party logos. |

Not part of the app: the review chrome in the preview — top tab strip (`#rv-tabs`), yellow "this round" banner (`#rv-changed`), orange `.chg-hl` frames, the caption bar (`#rv-caption`), language toggle in the header.

There is no `design-spec.json`. The preview and PRD are the source of truth.

## Decisions the user confirmed at the gate

1. Opening a file shows the normal editing window first; one button spreads it into space; each file remembers its last state.
2. "Curved" layout in v1 = segmented flat panels arranged on an arc (no true curved surface).
3. [Save] writes back to the original file, changing only the parts that were edited; before the first save of a file the original is backed up.
4. Feishu connection uses a self-built enterprise app first (store app before public release). **No app identity / client ID is registered yet** for Feishu, Google Drive, OneDrive or Dropbox.
5. Formats: standard `.docx` / `.xlsx` / `.pptx` only (ECMA-376 / ISO/IEC 29500). No app-specific file format. New files are blank standard files in `Documents/SpatialOffice`, named 未命名文档 / 未命名表格 / 未命名演示; the first save takes the first line / first slide title as the name; an untouched new file is deleted on close.
6. Spatial layout is a presentation layer: stored in app data by default (optional sidecar `<file>.spatial.json`, schema `spatial-layout/1.0`), never inside the document.

## Wording and trademark rules (hard)

- UI, strings, icons, README, store text: never use the names of other vendors' office products or that vendor's name, in any language, or their icons. Say only 「兼容 .docx / .xlsx / .pptx」 / "compatible with .docx / .xlsx / .pptx". (The exact banned word list is in PRD §3.5.)
- Cloud service names (飞书, Google Drive, OneDrive, Dropbox) appear as plain text only, no logos.
- File icons are our own shapes (see `.scratch/assets/`), no letter-on-colour tiles.
- Keep the settings footer notice and the About line exactly as in the preview.
- No company name anywhere in UI text.

## What to build first (v0.1.0)

Order of value. Each step must really work before moving on; do not fake a feature with canned data.

1. **Local standard files** (PRD §4, §5, §7, §13 "v1 · 第一步"): home (recent / browse / new), open `.docx` / `.xlsx` / `.pptx` / `.md` / `.txt` / `.csv`, read-only PDF and images, edit, save back changing only edited parts with first-save backup, new blank files, save-as copy. Keyboard and mouse are the main input. Round-trip unit tests on real sample files (create fixtures in tests) are the core evidence: open → edit one paragraph / cell / slide text → save → reopen → everything else byte- or structure-identical.
2. **Spatial presentation** (PRD §6, "第二步"): spread / collect; side-by-side, arc (segmented), layered; focus; layout record and restore.
3. **Spatial charts** (PRD §6, "第三步"): column and scatter in 3D from a standard chart, linked with the sheet.
4. **Cloud and browser** (PRD §9, §10, "第四步"): without registered client IDs the sign-in cannot work. Build the "账号与云盘" screen and the receiving of file-open intents (ACTION_VIEW / ACTION_SEND for the supported types — this part works offline and should be real). For each cloud service show an honest "not available in this version" state instead of a fake sign-in. Record this as a deviation in `handover/deviations.md`.

If something in the design cannot be done with the SDK, do not silently change the design: write it in `handover/deviations.md` (what, why, evidence) and pick the closest behaviour inside the design's rules.

## Factory rules the result is checked against

Read `handover/factory-constraints.md` before writing code. A script checks most of them and a failing check blocks hand-off.
