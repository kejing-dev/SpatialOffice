# Factory constraints (checked by script after the run)

Project: `/Users/bytedance/Downloads/spatial-office` · app name `SpatialOffice` · package `io.embodit.spatialoffice` (fixed; the company word may appear only in the package name, never in UI text) · first version `0.1.0` (`versionName "0.1.0"`).

## Code rules (grep-checked in `app/src/main`, Kotlin and Gradle Kotlin files)

| id | Rule |
|---|---|
| material | No Jetpack Compose Material or Material 3 libraries, no Material theme object. SpatialUI (`com.pico.spatial.ui.*`) + `PicoTheme` only. |
| tabs / tabs-segment | Switch pages only with the native SpatialUI `TabBar`. No `TabRow`, `SegmentedButton`; `SegmentControl` only for options inside a labelled row. Tab labels must follow the in-app language (resolve the string first, then pass it in) and any text you add inside a tab item uses `LocalContentColor.current`. |
| drag-bar / compose-drag | No self-made panel drag bars or grips; windows move with the system handle. No `detectDragGestures` on world panels (inside a 2D window for a real 2D control, add `// gate:allow compose-drag <control>`). |
| geometry / primitive-hero | No visible objects built from `MeshResource.createBox/Sphere/Cylinder/Cone/Capsule`. Invisible colliders, floor guide lines, abstract indicators and **data marks of a chart (bars, points)** need `// gate:allow geometry <purpose>` on the same or previous line. |
| plane-colliders | No `PlaneColliders`. |
| window-tilt | Any `openWindowContainer` needs the level gate (window stays upright; see spatial-ui-ability window docs). |
| button-height / button-gap | Buttons ≥ 64 dp high; clickable things in a Row/Column spaced ≥ 16 dp. Dense editor toolbars and on-screen key grids may be smaller with `// gate:allow button-height <reason>` / `// gate:allow button-gap <reason>`. All click targets ≥ 48 dp. |
| slider | `Slider` gets `.height(48.dp)`, adjustable width ≥ 100 dp; stepped sliders use `steps`, not rounding in `onValueChange`. |
| ellipsis | No `TextOverflow.Ellipsis` to hide overflow unless the Text has an `onTextLayout` overflow log; file names in lists may ellipsize with `// gate:allow ellipsis-no-fits file name`. |
| recall-front | No `Placement.none(`; no copy saying 「叫回面前 / 在你面前 / in front of you」. |
| double-ray | In a Stage (Full Space) never draw your own controller ray / laser / beam. |
| shared-drag | Entity drag callbacks (`onDragStart` / `onDragMove`) only inside a Stage. |
| system-registered | Every `class X : System()` is registered with `registerSystem`. |
| signing / keystore-file | No `signingConfig`, `storeFile`, `storePassword` in the app Gradle file; no `*.jks` / `*.keystore` in the project. |
| company-name | No company name in any `strings.xml`. |
| how-to | Settings has a 「怎么操作 / How to use」 page: one row per main action (icon + keyboard/mouse way + hand/controller way). Strings named `how_to_*` in both `res/values` and the Chinese values folder. First launch shows one line saying what to do next. |
| bilingual | Chinese and English for every string (`values` + `values-zh*`), switchable in settings, and the whole UI follows it. |
| exit | Settings first screen has [退出应用] with a confirm; exit closes **every** WindowContainer first, then `finishAndRemoveTask`, so nothing stays in recents. |

## Behaviour rules

- ≤ 7 clickable controls per screen, except the three editor toolbars (14–16, approved in PRD §11) and the sheet grid itself.
- One main window per surface; do not open extra small windows for controls that belong to a surface.
- Settings and every required item fit in the first screen of the window without scrolling; nothing clipped by the window edge.
- Unsaved changes: closing a file, opening another in the same window, or exiting asks first.
- A debug broadcast `io.embodit.spatialoffice.DEBUG` (debuggable builds only) can switch the app to each named state used for screenshots (home, home-new, doc, doc-new, sheet, slides, spread layouts, chart, save-first, save-as, account, settings, how-to, exit-confirm, both languages). Document it in the project `AGENTS.md`.
- App icon: own adaptive icon (vector foreground, one distinctive flat background colour), no template default, no letters.
- Unit tests for file round-trip, layout rules, and naming rules; `./gradlew testDebugUnitTest` green.
- Remove internal-only Maven repositories from the Gradle settings file if dependency resolution hangs (keep the public Volcengine mirror).

## Machine rules (other apps share this Mac)

- Emulator: borrow one with `python3 -I "/Users/bytedance/Desktop/Spatial Apps/spatial-app-factory/bin/emu_borrow.py" borrow spatial-office` (prints the serial; retries until one is free) and return it with `… return spatial-office` when the test round is done. Never start a new emulator or AVD, never use emulator-5554 / 5556 / 5558.
- Always pass `-s <serial>` to adb. Never `adb kill-server`, `adb reconnect`, or kill adb / logcat by name.
- The headset `PB324XJGL2090081G` is off limits: do not install to it, launch on it, or run any adb command against it. The coordinator installs there.
- Do not write to `/Users/bytedance/Desktop/Spatial Apps/spatial-app-factory/` except `apps/spatial-office/build/` (evidence files below). Never edit its `state.json`.
- Do not create git remotes or push; the coordinator does that. A local `git init` + commits in the project are fine. `.gitignore` must cover `local.properties`, `*.jks`, `*.keystore`, `.env`, `build/`.
- No credentials, tokens or client secrets in the project.

## Evidence files to leave (all newer than the final APK, none a copy of another)

In `/Users/bytedance/Desktop/Spatial Apps/spatial-app-factory/apps/spatial-office/build/`:
- `first-screen.png` — cold start, home.
- `settings-firstscreen.png` — whole settings window, [退出应用] visible.
- `default-view.png` — cold start, camera untouched, whole app in view.
- `exit-recents.txt` — after really clicking [退出应用] → confirm: `adb -s <serial> shell dumpsys activity recents`; must not contain the package name.
- `newgame-confirm.png` — with unsaved edits in a document, click New (or open another file): the "save changes?" confirm.
- `ui-audit.md` — per screen: control count, overflow scan in both languages, what was verified with real pointer input (`pico-cli interact`) and what was not verified.
- Project root `handover/deviations.md` and `handover/build-report.md` (what works, what is stubbed, test counts, APK path).
