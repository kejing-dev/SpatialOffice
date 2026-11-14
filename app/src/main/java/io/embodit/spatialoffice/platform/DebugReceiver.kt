package io.embodit.spatialoffice.platform

import android.content.BroadcastReceiver
import android.content.Context
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
            "dump" -> {}
            else -> Log.w(TAG, "DEBUG unknown command $cmd")
        }
    }
}
