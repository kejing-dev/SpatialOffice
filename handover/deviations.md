# Deviations from the approved design (v0.1.0)

| # | What | Why | Evidence | Inside tolerance |
|---|---|---|---|---|
| 1 | Cloud accounts (Feishu, Google Drive, OneDrive, Dropbox) are not connected. The pages say "not available in this version"; nothing simulates a sign-in. | No app identity / client ID is registered with any provider. | handover/development-context.md, decision 4 | no (scope: PRD "v1 · 第四步" is not in this build) |
| 2 | Document toolbar has no insert image / insert table / pin; slides have no shape / image / 3D object. Tables, pictures and charts inside documents are shown read-only. | Not built yet. Everything stays untouched in the file. | core tests (untouched round trip) | no (scope) |
| 3 | Opening shows a snackbar with [View] instead of an in-window banner with a progress bar. | Opening takes well under a second; the snackbar carries the same text. | emulator log `TOAST … 已打开` | yes |
| 4 | The 3D chart is turned with toolbar buttons (left / right / reset), not by dragging. | Entities cannot be dragged reliably in Shared Space (factory D53). | factory feedback, shared-drag gate | yes |
| 5 | "How to use" is a sheet with one block per action. | The system Sheet has a fixed width; a four-column table did not fit. | build/…/13-howto screenshot | yes |
| 6 | New files are named in the UI language (`Untitled document.docx` in English). | The sample showed the Chinese name in the English UI, which was an oversight in the sample. | core RulesTest | yes |
| 7 | Entering the spread layout closes the app's windows and leaving reopens them. | `minimizeWindowContainer` returns false for them on the 6.3 emulator, and open windows stand in the middle of the layout. | emulator log `WINDOW minimize … ok=false` | yes |
| 8 | Named layouts, pinned cards, storyline chapters, chart on the desk when spread, chart popping out in rehearsal: not built. | Not built yet. | — | no (scope: part of PRD "第二步 / 第三步") |
| 9 | Save as: no "save to Feishu / cloud"; images only for slides. | See 1. | — | no (scope) |
| 10 | Web pages (.html) are read-only. | Editing text while leaving markup untouched is not built. | — | no (scope) |
