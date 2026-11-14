# Build report — Spatial Office v0.1.0 (2026-11-14)

APK: `app/build/outputs/apk/debug/app-debug.apk` (debug, package `io.embodit.spatialoffice`, versionName 0.1.0).
Source: https://github.com/kejing-dev/SpatialOffice (private).

## Works (verified on the emulator, see the factory's `apps/spatial-office/build/ui-audit.md`)
- Open, edit, save `.docx` / `.xlsx` / `.pptx` with patch-only save and first-save backup; new blank files named on first save; `.txt` / `.md` / `.csv` edit; PDF, images, web pages read-only.
- Home (recent, browse, new, accounts, settings), how-to, shortcuts, bilingual UI, unsaved-changes dialog, exit.
- Save as: copy, PDF, CSV, TXT, PNG per slide.
- 3D chart volume from a standard chart, live with the grid; create a standard bar chart from the data around the selection.
- Spread layout (row / arc / layers) and rehearsal in a Mixed Stage; layout record in app data or a sidecar `.spatial.json`.
- Open with / Share for the supported file types.

## Not built
See `deviations.md`.

## Tests
`./gradlew :core:test`: 127 tests, 0 failures. Independent verifiers: `tools/verify/verify_docx_pptx.py` 503 checks, `tools/verify/verify_xlsx.py` 869 checks, 0 failed.

## How it was built
The managed `pico-cli workflow` run could not start an agent ("Authentication required": the ACP bridge needs the user's own `claude` CLI login), so the work followed the installed skills directly.
