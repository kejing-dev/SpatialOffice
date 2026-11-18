package io.embodit.spatialoffice

import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.foundation.dsl.ContainerProxy
import com.pico.spatial.ui.foundation.dsl.DefaultWindowContainer
import com.pico.spatial.ui.foundation.dsl.Form
import com.pico.spatial.ui.foundation.dsl.Placement
import com.pico.spatial.ui.foundation.dsl.SpatialAppScope
import com.pico.spatial.ui.foundation.dsl.Stage
import com.pico.spatial.ui.foundation.dsl.WindowContainer
import com.pico.spatial.ui.foundation.dsl.WindowContainerSize
import com.pico.spatial.ui.platform.LengthUnit
import io.embodit.spatialoffice.platform.ContainerIds
import io.embodit.spatialoffice.platform.Containers
import io.embodit.spatialoffice.platform.Placing
import io.embodit.spatialoffice.core.layout.LiveAnchor
import io.embodit.spatialoffice.core.layout.WindowFit
import io.embodit.spatialoffice.ui.chart.ChartVolume
import io.embodit.spatialoffice.ui.doc.DocWindow
import io.embodit.spatialoffice.ui.home.HomeWindow
import io.embodit.spatialoffice.ui.sheet.SheetWindow
import io.embodit.spatialoffice.ui.slides.SlidesWindow
import io.embodit.spatialoffice.ui.space.StageScreen

/**
 * The one placement rule for every editor window, on every path that opens it (new file, open, reopen after the
 * Stage, called to the front): the system's own spot in front of the user (v0.2.1; `Placement.right(home)` put a
 * 1040–1360 dp editor 22°–60° to the right, QA #1 q1-1).
 *
 * v0.2.5 (headset 2026-11-17 "word 文档也不能多开"): every open file has its own window, and each new one still opens
 * in front. Emulator trials (build/v0.2.5/placement-trials.md): the system has no staggered placement — a negative
 * gap leaves the window unshown, a gap ≥ 0 puts it beside the anchor on the circle around the user, mostly outside
 * ±39° (WindowFit.besideYaw) — and it takes the window that was in front off stage. So the windows are switched with
 * the title bar's [Windows] menu; only its [Side by side] (WindowCtl.arrange) asks for a place beside another window.
 */
internal fun editorPlacement(containers: List<ContainerProxy>, id: String): Placement {
    val next = Placing.next[id]
    val listed = containers.map { LiveAnchor.Listed(Containers.key(it.id, it.tag), ContainerIds.of(it)) }
    val dead = ContainerIds.dead.toSet()
    var placement = Placement.none()
    var how = "front"
    val key = next?.besideKey
    if (key != null) {
        // beside a live window (the list keeps destroyed ones: ContainerIds / LiveAnchor)
        val i = LiveAnchor.pick(listed, key, ContainerIds.live(key), dead)
        if (i != null) {
            val gap = if (Placing.mode.startsWith("gap:")) Placing.mode.substringAfter(':').toDoubleOrNull() ?: Placing.BESIDE_GAP_DP else Placing.BESIDE_GAP_DP
            placement = if (next.side == "left") Placement.left(containers[i], gap.dp) else Placement.right(containers[i], gap.dp)
            how = "${next.side} of $key#${listed[i].instance} gap=${gap.toInt()}dp"
        } else how = "front ($key is not up)"
    }
    android.util.Log.i("SpatialOffice", "PLACE $id/${next?.tag} -> $how among " +
        containers.withIndex().joinToString { (k, c) -> "${listed[k].name}#${listed[k].instance}${if (listed[k].instance in dead) "(destroyed)" else ""}/${c.state}" })
    return placement
}

/**
 * The 3D chart volume (QA #2 q2-2): over the sheet window, so it never covers the grid (v0.2.1 opened it in front,
 * on top of columns D–L, and the system faded the sheet). Beside the sheet is out of view (the sheet alone spans
 * ±30°); below it the system sets the volume down on the bed / floor (34°+ down); a negative offset hides it;
 * right / left put it about 4 m away at 55° (emulator trials, build/v0.2.2/placement-trials.md). Above, with the
 * volume 0.24 m tall, all of it is 27°–35° up and clear of the sheet: WindowFitV022Test checks the 8 corners.
 */
internal fun chartPlacement(containers: List<ContainerProxy>): Placement {
    val mode = ChartPlace.mode
    // v0.2.4 (QA #4 q4-2): the list keeps destroyed windows under the same name (after the 2nd rehearsal two
    // SpatialOfficeSheet entries; v0.2.3 took the first, a destroyed one, and the chart fell back to the front).
    // Anchor only to the live instance (ContainerIds), else another live window; never a destroyed one.
    // v0.2.5: several sheet windows can be up; the chart stands on the one it was asked from (ChartPlace.sheetKey)
    val listed = containers.map { LiveAnchor.Listed(Containers.key(it.id, it.tag), ContainerIds.of(it)) }
    val dead = ContainerIds.dead.toSet()
    val sheetKey = ChartPlace.sheetKey
    val others = listed.map { it.name }.filter { it != sheetKey && !it.startsWith(Containers.CHART) && !it.startsWith(Containers.STAGE) }.distinct()
        .sortedBy { if (it.startsWith(Containers.SHEET)) 0 else if (it.startsWith(Containers.HOME)) 2 else 1 }
    val i = LiveAnchor.anchor(listed, sheetKey, others, ContainerIds::live, dead)
    val anchor = i?.let { containers[it] }
    android.util.Log.i("SpatialOffice", "PLACE ${Containers.CHART} mode=$mode anchor=${i?.let { listed[it].name }}#${i?.let { listed[it].instance }} liveSheet=#${ContainerIds.live(sheetKey)} dead=$dead among " +
        containers.withIndex().joinToString { (k, c) -> "${listed[k].name}#${listed[k].instance}${if (listed[k].instance in dead) "(destroyed)" else ""}/${c.state}" })
    // v0.2.3 (q3-2): WindowCtl opens the chart only once the sheet is up, so the sheet is here; should it not be,
    // the chart still goes on top of a live window (never Placement.none(), which is in front of the user, over the grid)
    if (anchor?.id != Containers.SHEET) android.util.Log.w("SpatialOffice", "PLACE ${Containers.CHART}: no live sheet window, above ${anchor?.id} instead")
    if (anchor == null) return Placement.none()
    val (side, gap) = mode.split(':').let { it[0] to (it.getOrNull(1)?.toFloatOrNull() ?: 0f) }
    return when (side) {
        "top" -> Placement.top(anchor, gap.dp)
        "left" -> Placement.left(anchor, gap.dp)
        "right" -> Placement.right(anchor, gap.dp)
        "none" -> Placement.none()
        else -> Placement.bottom(anchor, gap.dp)
    }
}

/** Where the chart volume goes (side:gapDp); the debug command `chart-place` can try others on the emulator. */
object ChartPlace {
    @Volatile var mode: String = "top:0"
    /** Window key ([Containers.key]) of the sheet window the chart stands on; WindowCtl sets it before opening the volume. */
    @Volatile var sheetKey: String = Containers.SHEET
}

/** Which open file an editor window shows: the tag it was opened with, else the oldest window of its kind still waiting. */
@androidx.compose.runtime.Composable
private fun fileTag(bundle: android.os.Bundle?, win: io.embodit.spatialoffice.platform.Win): String? {
    val context = androidx.compose.ui.platform.LocalContext.current
    return androidx.compose.runtime.remember {
        val w = io.embodit.spatialoffice.platform.Graph.from(context).windows
        val tag = bundle?.getString(Containers.ARG_TAG)
        if (tag != null) { w.claimed(win, tag); tag } else w.claim(win).also { android.util.Log.w("SpatialOffice", "WINDOW $win opened without a tag, claimed $it") }
    }
}

/**
 * Containers (design 2.1). Shared Space: the home window (default container, 1120 × 660 dp from the manifest), one
 * window per open file (same container id, the file's tag; [editorPlacement]), and the chart volume over its sheet; nothing is placed from
 * the head pose. Full Space: one Mixed Stage for the spread layout and rehearsal.
 */
fun mainApp(scope: SpatialAppScope) =
    with(scope) {
        DefaultWindowContainer {
            Box(Modifier.windowConstraints(minWidth = 1040.dp, minHeight = 620.dp)) { HomeWindow() }
        }
        WindowContainer(
            Containers.DOC, form = Form.Planar,
            defaultSize = WindowContainerSize(width = 1160.dp, height = 860.dp),
            placement = { editorPlacement(containers, Containers.DOC) },
        ) { val tag = fileTag(bundle, io.embodit.spatialoffice.platform.Win.DOC); Box(Modifier.windowConstraints(minWidth = 960.dp, minHeight = 720.dp)) { DocWindow(tag) } }
        WindowContainer(
            Containers.SHEET, form = Form.Planar,
            defaultSize = WindowContainerSize(width = 1280.dp, height = 800.dp),
            placement = { editorPlacement(containers, Containers.SHEET) },
        ) { val tag = fileTag(bundle, io.embodit.spatialoffice.platform.Win.SHEET); Box(Modifier.windowConstraints(minWidth = 1100.dp, minHeight = 700.dp)) { SheetWindow(tag) } }
        WindowContainer(
            Containers.SLIDES, form = Form.Planar,
            defaultSize = WindowContainerSize(width = 1360.dp, height = 860.dp),
            placement = { editorPlacement(containers, Containers.SLIDES) },
        ) { val tag = fileTag(bundle, io.embodit.spatialoffice.platform.Win.SLIDES); Box(Modifier.windowConstraints(minWidth = 1300.dp, minHeight = 820.dp)) { SlidesWindow(tag) } }
        WindowContainer(
            Containers.CHART, form = Form.Volumetric,
            defaultSize = WindowContainerSize(WindowFit.CHART_W.toFloat(), WindowFit.CHART_H.toFloat(), WindowFit.CHART_D.toFloat(), LengthUnit.Meters),
            enableMaterialBackground = false,
            placement = { chartPlacement(containers) },
        ) { ChartVolume() }
        Stage(Containers.STAGE) { StageScreen() }
    }
