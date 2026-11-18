package io.embodit.spatialoffice.platform

import io.embodit.spatialoffice.core.pptx.SlideLayoutKind

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
            "find" -> g.overlay = Overlay.Find(Win.DOC)
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
            "dump" -> {}
            else -> Log.w(TAG, "DEBUG unknown command $cmd")
        }
    }
}
