package io.embodit.spatialoffice.platform

import io.embodit.spatialoffice.core.xlsx.A1
import io.embodit.spatialoffice.core.pptx.SlideLayoutKind
import io.embodit.spatialoffice.core.text.Attr
import io.embodit.spatialoffice.core.text.RichText

import android.content.BroadcastReceiver
import android.content.Context
import kotlinx.coroutines.launch
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.util.Log
import io.embodit.spatialoffice.core.files.FileKind
import io.embodit.spatialoffice.core.layout.Arrangement
import java.io.File

/**
 * Debug builds only: `adb shell am broadcast -a io.embodit.spatialoffice.DEBUG --es cmd <cmd> [--es arg <arg>]`.
 * Every command calls the same Graph function as the UI control it stands for (AGENTS.md lists them).
 */
object DebugReceiver {
    const val ACTION = "io.embodit.spatialoffice.DEBUG"

    fun register(context: Context, graph: Graph) {
        if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                val cmd = i.getStringExtra("cmd") ?: return
                val arg = i.getStringExtra("arg") ?: ""
                Log.i(TAG, "DEBUG $cmd $arg")
                runCatching { run(graph, cmd, arg) }.onFailure { Log.e(TAG, "DEBUG failed", it) }
                Log.i(TAG, graph.dump())
            }
        }
        context.registerReceiver(r, IntentFilter(ACTION), Context.RECEIVER_EXPORTED)
    }

    private fun win(arg: String): Win = Win.values().firstOrNull { it.name.equals(arg, true) } ?: Win.DOC
    private fun kind(arg: String): FileKind = when (arg.lowercase()) { "sheet" -> FileKind.SHEET; "slides" -> FileKind.SLIDES; else -> FileKind.DOC }

    private fun run(g: Graph, cmd: String, arg: String) {
        when (cmd) {
            "page" -> { g.overlay = null; g.homePage = HomePage.values().first { it.name.equals(arg, true) } }
            "lang" -> g.settings.update { it.copy(lang = if (arg.equals("en", true)) Lang.EN else Lang.ZH) }
            "open" -> g.open(FileRef.Path(File(arg)))
            "new" -> g.newFile(kind(arg))
            "save" -> g.save(win(arg))
            "saveas" -> g.overlay = Overlay.SaveAs(win(arg))
            "savefirst" -> g.overlay = Overlay.SaveFirst(win(arg))
            "confirm-save" -> g.confirmFirstSave(win(arg))
            "close" -> g.requestClose(win(arg))
            "unsaved" -> g.answerUnsaved(arg)
            "compat" -> g.overlay = Overlay.Compat(win(arg))
            "chart-place" -> io.embodit.spatialoffice.ChartPlace.mode = arg
            "shortcuts" -> g.overlay = Overlay.Shortcuts(if (arg.isEmpty()) Win.HOME else win(arg))
            "howto" -> { g.homePage = HomePage.SETTINGS; g.overlay = Overlay.HowTo() }
            "exit-confirm" -> g.requestExit()
            "exit" -> g.exit()
            "dismiss" -> g.overlay = null
            "spread" -> g.spread(kind(arg))
            "rehearse" -> g.rehearse()
            "collapse" -> g.collapse()
            "arr" -> g.arrange(Arrangement.values().first { it.name.equals(arg, true) })
            "focus" -> g.focusOn(arg.toInt())
            "recenter" -> g.recenter()
            "chart" -> g.sheet?.let { io.embodit.spatialoffice.ui.sheet.openChart(g, it) }
            "chart-type" -> g.chartScatter = arg == "scatter"
            "chart-yaw" -> g.chartYaw = arg.toFloat()
            "cell" -> g.sheet?.let { s -> val (a, v) = arg.split("=", limit = 2); io.embodit.spatialoffice.core.xlsx.A1.parse(a)?.let { (r, c) -> s.select(r, c); s.setCell(r, c, v) } }
            "select" -> g.sheet?.let { s -> io.embodit.spatialoffice.core.xlsx.A1.parse(arg)?.let { (r, c) -> s.select(r, c) } }
            "sheet" -> g.sheet?.let { it.sheet = arg.toInt(); it.select(0, 0) }
            "slide" -> g.deck?.let { it.current = arg.toInt().coerceIn(0, it.deck.slides.size - 1) }
            "type" -> g.doc?.let { d -> val p = d.blocks().filterIsInstance<UiBlock.Para>().firstOrNull { it.editable }; if (p != null) d.setParagraph(p.id, listOf(io.embodit.spatialoffice.core.text.Span(arg)), p.style) else d.insertAfter(-1, listOf(io.embodit.spatialoffice.core.text.Span(arg)), io.embodit.spatialoffice.core.docx.ParaStyle.BODY) }
            "radius" -> g.settings.update { it.copy(radius = arg.toInt().coerceIn(0, 2)) }
            "openmode" -> g.settings.update { it.copy(openMode = if (arg == "spread") OpenMode.SPREAD else OpenMode.WINDOW) }
            // ---- v0.2: every command calls the same session function as its toolbar entry
            "slide-add" -> g.deck?.addSlide(when (arg) { "title" -> SlideLayoutKind.TITLE; "two" -> SlideLayoutKind.TWO_COLUMN; "blank" -> SlideLayoutKind.BLANK; else -> SlideLayoutKind.TITLE_BODY })
            "slide-dup" -> g.deck?.duplicateSlide()
            "slide-move" -> g.deck?.let { d -> val (a, b) = arg.split(":").map { it.toInt() }; d.moveSlideTo(a, b) }
            "slide-del" -> g.deck?.deleteSlide()
            "shape" -> g.deck?.addShape(arg.ifEmpty { "roundRect" }, io.embodit.spatialoffice.ui.slides.SHAPE_FILL)
            "textbox" -> g.deck?.addTextBox(arg)
            "sel" -> g.deck?.selected = arg.toInt()
            "nudge" -> g.deck?.let { d -> val p = arg.split(",").map { it.toFloat() }; d.nudge(p[0].toInt(), p[1].toInt(), p.getOrElse(2) { 1f }) }
            "obj-del" -> g.deck?.deleteSelected()
            "theme" -> g.deck?.setColors(io.embodit.spatialoffice.ui.slides.THEMES[arg.toInt().coerceIn(0, 3)].second)
            "img" -> { // img doc|slides <absolute path>
                val (w, path) = arg.split(" ", limit = 2)
                val win = if (w == "slides") Win.SLIDES else Win.DOC
                if (win == Win.SLIDES) g.deck?.let { d -> g.pickImage(Win.SLIDES) { b, e, x, y -> d.addPicture(b, e, x, y) } }
                else g.doc?.let { d -> val at = d.blocks().lastOrNull()?.id ?: -1; g.pickImage(Win.DOC) { b, e, x, y -> d.insertImage(at, b, e, x, y) } }
                g.imageChosen(win, FileRef.Path(File(path)))
            }
            "img-pick" -> g.pickImage(win(arg)) { _, _, _, _ -> }
            "table" -> g.doc?.let { d -> val (r, c) = arg.split(",").map { it.toInt() }; io.embodit.spatialoffice.ui.doc.docInsertTable(d, r, c) }
            "table-cell" -> g.doc?.let { d -> val (pos, v) = arg.split("=", limit = 2); val (r, c) = pos.split(",").map { it.toInt() }
                d.blocks().filterIsInstance<UiBlock.Table>().lastOrNull()?.let { d.setTableCell(it.id, r, c, v) } }
            "find" -> g.overlay = Overlay.Find(win(arg.ifEmpty { "doc" }))
            "replace" -> g.doc?.let { d -> val (a, b) = arg.split("=", limit = 2); Log.i(TAG, "REPLACED " + d.replaceAll(a, b)) }
            "wordcount" -> g.overlay = Overlay.WordCount(Win.DOC)
            "inserttable" -> g.overlay = Overlay.InsertTable(Win.DOC)
            "para" -> g.doc?.let { d -> // para <index> <style|align|strike> <value>
                val (i, what, v) = arg.split(" ", limit = 3)
                val p = d.blocks().filterIsInstance<UiBlock.Para>().filter { it.editable }[i.toInt()]
                when (what) {
                    "style" -> d.setParagraph(p.id, p.spans, io.embodit.spatialoffice.core.docx.ParaStyle.valueOf(v.uppercase()))
                    "align" -> d.setParagraph(p.id, p.spans, p.style, v.toInt())
                    "strike" -> d.setParagraph(p.id, io.embodit.spatialoffice.core.text.RichText.setAll(p.spans, io.embodit.spatialoffice.core.text.Attr.STRIKE, v == "1"), p.style)
                    "bold" -> d.setParagraph(p.id, io.embodit.spatialoffice.core.text.RichText.setAll(p.spans, io.embodit.spatialoffice.core.text.Attr.BOLD, v == "1"), p.style)
                }
            }
            "addpara" -> g.doc?.let { d -> val (st, text) = arg.split(" ", limit = 2); d.insertAfter(d.blocks().lastOrNull()?.id ?: -1, listOf(io.embodit.spatialoffice.core.text.Span(text)), io.embodit.spatialoffice.core.docx.ParaStyle.valueOf(st.uppercase())) }
            "fill" -> g.sheet?.setFill(arg.takeIf { it.isNotEmpty() && it != "none" }?.toInt(16))
            "fmt" -> g.sheet?.setFmt(io.embodit.spatialoffice.core.xlsx.NumFmt.valueOf(arg.uppercase()))
            "bold" -> g.sheet?.setBold(arg != "0")
            "freeze" -> g.sheet?.toggleFreeze()
            "filter" -> g.sheet?.let { io.embodit.spatialoffice.ui.sheet.sheetFilter(g, it) }
            "filter-clear" -> g.sheet?.let { it.filterCol = -1; it.filterValue = null }
            "sort" -> g.sheet?.sortByColumn(arg != "desc")
            "row-ins" -> g.sheet?.insertRowBelow()
            "row-del" -> g.sheet?.deleteRow()
            "col-ins" -> g.sheet?.insertColumn()
            "col-del" -> g.sheet?.deleteColumn()
            "chart-add" -> g.sheet?.let { io.embodit.spatialoffice.ui.sheet.makeChart(g, it, arg.ifEmpty { "bar" }) }
            "chartview" -> g.overlay = Overlay.ChartView(Win.SHEET, arg.toIntOrNull() ?: -1)
            "browse" -> { g.overlay = null; g.homePage = HomePage.BROWSE; g.browseLoc = arg.toInt().coerceIn(0, 4) }
            "browse-dir" -> { g.overlay = null; g.homePage = HomePage.BROWSE; g.browseLoc = 0; g.browseDir = File(arg) }
            "connect" -> io.embodit.spatialoffice.core.cloud.Provider.of(arg)?.let { g.accounts.connect(it) }
            "saveas-cloud" -> g.saveAs(win(arg), SaveAsTarget.CLOUD)
            // ---- v0.2.1 cloud: the dialogs are the real ones; scan-preview / connected-preview fill them with a sample
            // code (marked as such in AGENTS.md), because no service is registered on the test machines
            "scan" -> io.embodit.spatialoffice.core.cloud.Provider.of(arg)?.let { g.accounts.connectByScan(it) }
            "scan-preview" -> io.embodit.spatialoffice.core.cloud.Provider.of(arg)?.let { g.accounts.previewScan(it) }
            "connected-preview" -> io.embodit.spatialoffice.core.cloud.Provider.of(arg)?.let { g.overlay = Overlay.Connected(it) }
            "conflict" -> g.overlay = Overlay.Conflict(win(arg))
            // the notice shown after a Feishu in-place save was refused and a copy went beside the file (screenshot only)
            "copybeside" -> g.session(win(arg))?.let { s -> g.overlay = Overlay.CopyBeside(win(arg), s.ref.name, io.embodit.spatialoffice.core.files.Naming.appCopyName(s.ref.name, g.settings.value.zh)) }
            "conflict-answer" -> { val (w, c) = arg.split(" ", limit = 2); g.resolveConflict(win(w), c) }
            "inbox" -> g.scope.launch { runCatching { g.accounts.checkInbox() } }
            // ---- v0.2.5
            // objdrag <move|top_left|top_right|bottom_left|bottom_right> <n>x<dxEmu>,<dyEmu>: replays n pointer steps on the
            // picked slide object through the same session calls as the gesture (begin, travel since the press, end)
            "objdrag" -> g.deck?.let { d ->
                val (what, steps) = arg.split(" ", limit = 2)
                val (n, d2) = steps.split("x", limit = 2); val (dx, dy) = d2.split(",").map { it.toFloat() }
                val corner = io.embodit.spatialoffice.core.layout.Corner.values().firstOrNull { it.name.equals(what, true) }
                val total = io.embodit.spatialoffice.core.layout.DragTotal()
                if (d.beginBoxDrag(d.selected)) {
                    repeat(n.toInt()) { total.add(dx, dy); if (corner == null) d.moveBoxBy(total.x.toLong(), total.y.toLong()) else d.resizeBoxBy(corner, total.x.toLong(), total.y.toLong()) }
                    Log.i(TAG, "OBJDRAG debug ${d.selected} $what events=${total.events} total=${total.x},${total.y} emu changed=${d.endBoxDrag()}")
                }
            }
            "slide-table" -> g.deck?.let { d -> val p = arg.split(",").mapNotNull { it.toIntOrNull() }; d.addTable(p.getOrElse(0) { 3 }, p.getOrElse(1) { 3 }) }
            "slide-cell" -> g.deck?.let { d -> val (pos, v) = arg.split("=", limit = 2); val (r, c) = pos.split(",").map { it.toInt() }; d.setTableCell(d.current, d.selected, r, c, v) }
            // doc-pick <n>: picks the n-th picture / table of the document (shows its corner dots); doc-resize <corner> <dx,dy>
            // (picture: EMU; table: percent of the text width in dx)
            "doc-pick" -> g.doc?.let { d -> d.picked = d.blocks().filter { it is UiBlock.Image || it is UiBlock.Table }.getOrNull(arg.toIntOrNull() ?: 0)?.id ?: -1 }
            "doc-resize" -> g.doc?.let { d ->
                val (what, d2) = arg.split(" ", limit = 2); val (dx, dy) = d2.split(",").map { it.toLong() }
                val corner = io.embodit.spatialoffice.core.layout.Corner.values().first { it.name.equals(what, true) }
                if (d.beginResize(d.picked)) { d.resizeBy(corner, dx, dy); Log.i(TAG, "DOCRESIZE debug ${d.picked} $what $dx,$dy changed=${d.endResize()}") }
            }
            // front <tag>: what opening an open file again does; activate <tag>; close-tag <tag>; windows: list
            "front" -> g.sessionByTag(arg)?.let { g.front(it) }
            "activate" -> g.sessionByTag(arg)?.let { g.activate(it) }
            "close-tag" -> g.sessionByTag(arg)?.let { g.requestClose(it) }
            "win-place" -> Placing.mode = arg.ifEmpty { "front" }
            "front-home" -> g.windows.frontHome()
            "arrange" -> (g.sessionByTag(arg) ?: g.sessions.lastOrNull())?.let { g.windows.arrange(g.refOf(it)) }
            "front-mode" -> g.windows.frontMode = arg.ifEmpty { "auto" }
            // cloud-id <service> <client id>: a client id for this run only (debug builds), to walk [Connect] → browser
            // on a machine whose build has none; `cloud-id <service>` takes it away again
            "cloud-id" -> { val p = arg.split(" ", limit = 2); io.embodit.spatialoffice.core.cloud.Provider.of(p[0])?.let { g.accounts.debugClientId(it, p.getOrElse(1) { "" }) } }
            // ---- v0.3.0 (M1): each command calls the function its menu entry / key calls
            // range B2:D5 | B2 (s17: as a drag from the first to the last cell); range-row <n>, range-col <letter>, jump <up|down|left|right>[+shift]
            "range" -> g.sheet?.let { s -> val p = arg.split(":"); A1.parse(p[0])?.let { (r, c) -> s.select(r, c) }; p.getOrNull(1)?.let { A1.parse(it) }?.let { (r, c) -> s.extendTo(r, c) } }
            "range-row" -> g.sheet?.selectRow(arg.toInt() - 1)
            "range-col" -> g.sheet?.let { s -> A1.parse(arg + "1")?.let { s.selectColumn(it.second) } }
            "jump" -> g.sheet?.let { s -> val (dir, sh) = arg.split("+").let { it[0] to (it.getOrNull(1) == "shift") }
                when (dir) { "up" -> s.jump(-1, 0, sh); "down" -> s.jump(1, 0, sh); "left" -> s.jump(0, -1, sh); else -> s.jump(0, 1, sh) } }
            "extend" -> g.sheet?.let { s -> when (arg) { "up" -> s.extendTo(s.row - 1, s.col); "down" -> s.extendTo(s.row + 1, s.col); "left" -> s.extendTo(s.row, s.col - 1); else -> s.extendTo(s.row, s.col + 1) } }
            "copy" -> g.sheet?.let { io.embodit.spatialoffice.ui.sheet.copyCells(g, it, cut = false) }
            "cut" -> g.sheet?.let { io.embodit.spatialoffice.ui.sheet.copyCells(g, it, cut = true) }
            "paste" -> g.sheet?.let { io.embodit.spatialoffice.ui.sheet.pasteCells(g, it) }
            // clip-set <text>: puts text on the system clipboard (QA uses it only to prepare an outside paste); \t and \n are tab / line break
            "clip-set" -> runCatching {
                val text = arg.replace("\\t", "\t").replace("\\n", "\n")
                val cm = g.app.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("debug", text)); Log.i(TAG, "CLIP set ${text.length} chars")
            }
            "align" -> g.sheet?.setHAlign(arg.takeIf { it == "left" || it == "center" || it == "right" })
            "wrap" -> g.sheet?.setWrap(arg != "0")
            "decimals" -> g.sheet?.adjustDecimals(arg.toIntOrNull() ?: 1)
            "col-wider" -> g.sheet?.widen(2f)
            "col-narrower" -> g.sheet?.widen(-2f)
            "col-autofit" -> g.sheet?.autoFit()
            "row-taller" -> g.sheet?.taller(6f)
            "sheet-new" -> g.sheet?.addSheet()
            "sheet-rename" -> g.sheet?.let { s -> Log.i(TAG, "SHEET rename -> ${s.renameSheet(arg) ?: "ok"}") }
            "sheet-rename-ask" -> g.sheet?.let { g.show(it, Overlay.SheetRename(Win.SHEET)) }
            "sheet-copy" -> g.sheet?.copySheet()
            "sheet-delete" -> g.sheet?.let { g.show(it, Overlay.SheetDelete(Win.SHEET)) }
            "sheet-delete-now" -> g.sheet?.deleteSheet()
            // find-q <text> [case], find-with <text>, find-scope sheet|all: fill the find sheet (its buttons are pressed for real)
            "find-q" -> { val p = arg.split(" "); io.embodit.spatialoffice.ui.FindMemo.query = p[0]; io.embodit.spatialoffice.ui.FindMemo.matchCase = p.getOrNull(1) == "case" }
            "find-with" -> io.embodit.spatialoffice.ui.FindMemo.replacement = arg
            "find-scope" -> io.embodit.spatialoffice.ui.FindMemo.allSheets = arg == "all"
            // recover yes|no: answers "Recover unsaved changes?"; draft-now: one autosave round at once
            "recover" -> g.answerRecover(arg != "no")
            "draft-now" -> g.autosave()
            // obj-copy, obj-paste, obj-dup, z front|back|forward|backward on the picked slide object
            "obj-copy" -> g.deck?.let { io.embodit.spatialoffice.ui.slides.objCopy(g, it) }
            "obj-paste" -> g.deck?.let { io.embodit.spatialoffice.ui.slides.objPaste(g, it) }
            "obj-dup" -> g.deck?.duplicateSelected()
            "z" -> g.deck?.reorder(io.embodit.spatialoffice.core.pptx.ZOrder.valueOf(when (arg) { "front" -> "FRONT"; "back" -> "BACK"; "forward" -> "FORWARD"; else -> "BACKWARD" }))
            "back" -> g.onBack("debug")
            // notes <text>: the current slide's speaker notes, as typed into the notes field
            "notes" -> g.deck?.let { it.setNotes(it.current, arg) }
            // ---- v0.4.0 (M2): each command calls the function its menu entry / key calls
            // docfmt <i> <a>-<b>|all <op> [v]: on the i-th editable paragraph's characters a..b; op = color RRGGBB|none,
            // highlight yellow|green|cyan|none, size-up, size-down, size <pt>, clear, strike|bold|italic|underline 1|0
            "docfmt" -> g.doc?.let { d ->
                val p = arg.split(" ")
                val para = d.blocks().filterIsInstance<UiBlock.Para>().filter { it.editable }[p[0].toInt()]
                val len = RichText.text(para.spans).length
                val (a, b) = if (p[1] == "all") 0 to len else p[1].split("-").map { it.toInt().coerceIn(0, len) }.let { it[0] to it[1] }
                val v = p.getOrNull(3)
                fun attr(x: Attr) = d.formatRange(para.id, a, b) { sp, x0, x1, _ -> RichText.setAttr(sp, x0, x1, x, v != "0") }
                val ok = when (p[2]) {
                    "color" -> d.formatRange(para.id, a, b) { sp, x0, x1, _ -> RichText.setColor(sp, x0, x1, v?.takeIf { it != "none" }?.toInt(16)) }
                    "highlight" -> d.formatRange(para.id, a, b) { sp, x0, x1, _ -> RichText.setHighlight(sp, x0, x1, v?.takeIf { it != "none" }) }
                    "size-up" -> d.formatRange(para.id, a, b) { sp, x0, x1, pp -> RichText.stepSize(sp, x0, x1, 1, pp.baseSizeHp) }
                    "size-down" -> d.formatRange(para.id, a, b) { sp, x0, x1, pp -> RichText.stepSize(sp, x0, x1, -1, pp.baseSizeHp) }
                    "size" -> d.formatRange(para.id, a, b) { sp, x0, x1, _ -> RichText.setSize(sp, x0, x1, (v!!.toFloat() * 2).toInt()) }
                    "clear" -> d.formatRange(para.id, a, b) { sp, x0, x1, _ -> RichText.clearFormat(sp, x0, x1) }
                    "strike" -> attr(Attr.STRIKE)
                    "bold" -> attr(Attr.BOLD)
                    "italic" -> attr(Attr.ITALIC)
                    "underline" -> attr(Attr.UNDERLINE)
                    else -> false
                }
                val after = d.blocks().firstOrNull { it.id == para.id } as? UiBlock.Para
                Log.i(TAG, "DOCTEXT debug ${p[2]} ok=$ok spans=" + after?.spans?.joinToString("|") { sp -> sp.text.take(12) + ":c=" + (sp.color?.let { c -> String.format("%06X", c) }) + ":h=" + sp.highlight + ":sz=" + sp.size + ":b=" + sp.bold + ":s=" + sp.strike })
            }
            // doclevel <i> tab|shift-tab (what the keys do in a list item), docindent <i> more|less, docline <i> <240|276|360|480|none>, docspace <i> <before>,<after>|none
            "doclevel" -> g.doc?.let { d -> val (i, k) = arg.split(" "); val p = d.blocks().filterIsInstance<UiBlock.Para>().filter { it.editable }[i.toInt()]; d.setListLevel(p.id, p.level + if (k == "shift-tab") -1 else 1) }
            "docindent" -> g.doc?.let { d -> val (i, k) = arg.split(" "); val p = d.blocks().filterIsInstance<UiBlock.Para>().filter { it.editable }[i.toInt()]; d.indent(p.id, k != "less") }
            "docline" -> g.doc?.let { d -> val (i, k) = arg.split(" "); val p = d.blocks().filterIsInstance<UiBlock.Para>().filter { it.editable }[i.toInt()]; d.setLineSpacing(p.id, k.toIntOrNull()) }
            "docspace" -> g.doc?.let { d ->
                val (i, k) = arg.split(" "); val p = d.blocks().filterIsInstance<UiBlock.Para>().filter { it.editable }[i.toInt()]
                val v = k.split(",").map { it.toIntOrNull() }; d.setParaSpacing(p.id, v.getOrNull(0), v.getOrNull(1))
            }
            // history doc|sheet|slides: the version list; hist-restore <win> <n>: [Restore] on the n-th version counted from the oldest (1 = first)
            "history" -> g.session(win(arg))?.let { g.show(it, Overlay.History(win(arg))) }
            "hist-restore" -> { val (w, n) = arg.split(" "); g.session(win(w))?.let { s -> g.versions(s).reversed().getOrNull(n.toInt() - 1)?.let { v -> g.restoreVersion(s, v) } } }
            // font italic|underline|strike|bold 1|0, font-color RRGGBB|none, font-size <pt>|up|down, font-clear: Text / Colour menus on the selected cells
            "font" -> g.sheet?.let { s -> val (k, v) = arg.split(" "); val on = v != "0"; when (k) { "italic" -> s.setItalic(on); "underline" -> s.setUnderline(on); "strike" -> s.setStrike(on); else -> s.setBold(on) } }
            "font-color" -> g.sheet?.setFontColor(arg.takeIf { it.isNotEmpty() && it != "none" }?.toInt(16))
            "font-size" -> g.sheet?.let { s -> when (arg) { "up" -> s.stepFontSize(1); "down" -> s.stepFontSize(-1); else -> s.setFontSize(arg.toFloat()) } }
            "font-clear" -> g.sheet?.clearFont()
            // stext-sel <a>,<b>: select characters a..b of the picked slide text box (as the text field reports it)
            "stext-sel" -> g.deck?.let { d -> val (a, b) = arg.split(",").map { it.toInt() }; d.textSel = DeckSession.TextSel(d.current, d.selected, a, b) }
            // stext bold|italic|underline|strike | color RRGGBB|none | size up|down|<pt> | align 0|1|2 | bullet 1|0 | clear (Text / Colour menus on the picked text box)
            "stext" -> g.deck?.let { d ->
                val p = arg.split(" "); val v = p.getOrNull(1)
                val ok = when (p[0]) {
                    "bold" -> io.embodit.spatialoffice.ui.slides.slideToggle(d, Attr.BOLD)
                    "italic" -> io.embodit.spatialoffice.ui.slides.slideToggle(d, Attr.ITALIC)
                    "underline" -> io.embodit.spatialoffice.ui.slides.slideToggle(d, Attr.UNDERLINE)
                    "strike" -> io.embodit.spatialoffice.ui.slides.slideToggle(d, Attr.STRIKE)
                    "color" -> d.formatText { sp, a, b -> RichText.setColor(sp, a, b, v?.takeIf { it != "none" }?.toInt(16)) }
                    "size" -> when (v) {
                        "up", "down" -> { val base = d.textBaseSize(); d.formatText { sp, a, b -> RichText.stepSize(sp, a, b, if (v == "up") 1 else -1, base) } }
                        else -> d.formatText { sp, a, b -> RichText.setSize(sp, a, b, (v!!.toFloat() * 2).toInt()) }
                    }
                    "align" -> d.setParaFormat(align = v!!.toInt())
                    "bullet" -> d.setParaFormat(bullet = v != "0")
                    "clear" -> d.formatText { sp, a, b -> RichText.clearFormat(sp, a, b) }
                    else -> false
                }
                val sh = d.pickedText()
                Log.i(TAG, "SLIDETEXT debug ${p[0]} ok=$ok runs=" + sh?.let { d.spansOf(it).joinToString("|") { sp -> sp.text.take(12) + ":c=" + (sp.color?.let { c -> String.format("%06X", c) }) + ":sz=" + sp.size + ":b=" + sp.bold + ":src=" + sp.src } } + " paras=" + sh?.paras?.joinToString { "a=" + it.align + ",bu=" + it.bullet })
            }
            // obj-fill RRGGBB|none, obj-line RRGGBB|none [pt], obj-rot <deg> (absolute), obj-rotby <deg>, rotdrag <deg> (the handle: press, drag to the angle, release)
            // ---- M3 slides: p26 page background, p40 table rows / columns / merge (same session calls as the menus)
            "slide-bg" -> g.deck?.let { d ->
                val p = arg.trim().split(" ")
                val all = p.getOrNull(1) == "all"
                val v = p[0]
                when {
                    v.startsWith("img:") -> {
                        val f = File(v.removePrefix("img:"))
                        d.setBackgroundPicture(f.readBytes(), f.extension.lowercase().ifEmpty { "png" }, all)
                    }
                    v == "none" -> d.setBackground(null, all)
                    else -> d.setBackground(v.toInt(16), all)
                }
                val sl = d.deck.slides[d.current]
                Log.i(TAG, "SLIDEBG debug slide=${d.current} own=${sl.ownBackground} rgb=${sl.backgroundRgb?.let { "%06X".format(it) }} picture=${sl.backgroundImage}")
            }
            "stable-cell" -> g.deck?.let { d -> val (r, c) = arg.split(",").map { it.trim().toInt() }; d.pickCell(d.selected, r, c); Log.i(TAG, "SLIDETABLE cell ${d.currentCell()} of ${d.selected}") }
            "stable-row" -> g.deck?.tableRow(arg.trim())
            "stable-col" -> g.deck?.tableCol(arg.trim())
            "stable-merge" -> g.deck?.tableMerge(arg.trim() == "down")
            "obj-fill" ->g.deck?.setFill(arg.takeIf { it.isNotEmpty() && it != "none" }?.toInt(16))
            "obj-line" -> g.deck?.let { d -> val p = arg.split(" "); d.setLine(p[0].takeIf { it != "none" }?.toInt(16), p.getOrNull(1)?.toFloat()?.let { Math.round(it * 12700f).toLong() }) }
            "obj-rot" -> g.deck?.setRotation(arg.toInt())
            "obj-rotby" -> g.deck?.rotateBy(arg.toInt())
            "rotdrag" -> g.deck?.let { d -> if (d.beginRotate(d.selected)) { d.rotateTo(io.embodit.spatialoffice.core.layout.Rotation.snap(arg.toFloat())); Log.i(TAG, "ROTATE debug ${d.selected} -> ${d.dragRot} changed=${d.endRotate()}") } }
            "dump" -> {}
            // v0.2.4 (q4-1): longest main-thread frame gap over <arg> seconds (baseline of the emulator's own load)
            "mainwatch" -> { val w = io.embodit.spatialoffice.ui.chart.MainWatch("baseline"); w.start(); android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ w.stop() }, (arg?.toLongOrNull() ?: 10L) * 1000) }
            else -> Log.w(TAG, "DEBUG unknown command $cmd")
        }
    }
}
