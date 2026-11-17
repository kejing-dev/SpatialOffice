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
import io.embodit.spatialoffice.platform.Containers
import io.embodit.spatialoffice.core.layout.WindowFit
import io.embodit.spatialoffice.ui.chart.ChartVolume
import io.embodit.spatialoffice.ui.doc.DocWindow
import io.embodit.spatialoffice.ui.home.HomeWindow
import io.embodit.spatialoffice.ui.sheet.SheetWindow
import io.embodit.spatialoffice.ui.slides.SlidesWindow
import io.embodit.spatialoffice.ui.space.StageScreen

/** The first of [ids] that is open, as the window to stand beside. */
private fun beside(containers: List<ContainerProxy>, vararg ids: String): ContainerProxy? {
    android.util.Log.i("SpatialOffice", "PLACE among " + containers.joinToString { "${it.id}/${it.tag}/${it.state}" })
    return ids.firstNotNullOfOrNull { id -> containers.firstOrNull { it.id == id } } ?: containers.firstOrNull()
}

/**
 * The one placement rule for every editor window, on every path that opens it (new file, open, reopen after the
 * Stage): the system's own spot in front of the user. v0.2.0 used `Placement.right(home)`, which put a 1040–1360 dp
 * editor 22°–60° to the right, mostly out of view (QA #1 q1-1; WindowFit / WindowFitTest check all 8 corners).
 */
internal fun editorPlacement(containers: List<ContainerProxy>, id: String): Placement {
    android.util.Log.i("SpatialOffice", "PLACE $id ${io.embodit.spatialoffice.core.layout.WindowFit.editorSpot()} among " + containers.joinToString { "${it.id}/${it.state}" })
    return Placement.none()
}

/**
 * The 3D chart volume (QA #2 q2-2): over the sheet window, so it never covers the grid (v0.2.1 opened it in front,
 * on top of columns D–L, and the system faded the sheet). Beside the sheet is out of view (the sheet alone spans
 * ±30°); below it the system sets the volume down on the bed / floor (34°+ down); a negative offset hides it;
 * right / left put it about 4 m away at 55° (emulator trials, build/v0.2.2/placement-trials.md). Above, with the
 * volume 0.24 m tall, all of it is 27°–35° up and clear of the sheet: WindowFitV022Test checks the 8 corners.
 */
internal fun chartPlacement(containers: List<ContainerProxy>): Placement {
    val sheet = containers.firstOrNull { it.id == Containers.SHEET }
    val mode = ChartPlace.mode
    android.util.Log.i("SpatialOffice", "PLACE ${Containers.CHART} mode=$mode anchor=${sheet?.id} among " + containers.joinToString { "${it.id}/${it.state}" })
    if (sheet == null) return Placement.none()
    val (side, gap) = mode.split(':').let { it[0] to (it.getOrNull(1)?.toFloatOrNull() ?: 0f) }
    return when (side) {
        "top" -> Placement.top(sheet, gap.dp)
        "left" -> Placement.left(sheet, gap.dp)
        "right" -> Placement.right(sheet, gap.dp)
        "none" -> Placement.none()
        else -> Placement.bottom(sheet, gap.dp)
    }
}

/** Where the chart volume goes (side:gapDp); the debug command `chart-place` can try others on the emulator. */
object ChartPlace { @Volatile var mode: String = "top:0" }

/**
 * Containers (design 2.1). Shared Space: the home window (default container, 1120 × 660 dp from the manifest), one
 * window per editor (in front, [editorPlacement]), and the chart volume beside the sheet; nothing is placed from
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
        ) { Box(Modifier.windowConstraints(minWidth = 960.dp, minHeight = 720.dp)) { DocWindow() } }
        WindowContainer(
            Containers.SHEET, form = Form.Planar,
            defaultSize = WindowContainerSize(width = 1280.dp, height = 800.dp),
            placement = { editorPlacement(containers, Containers.SHEET) },
        ) { Box(Modifier.windowConstraints(minWidth = 1100.dp, minHeight = 700.dp)) { SheetWindow() } }
        WindowContainer(
            Containers.SLIDES, form = Form.Planar,
            defaultSize = WindowContainerSize(width = 1360.dp, height = 860.dp),
            placement = { editorPlacement(containers, Containers.SLIDES) },
        ) { Box(Modifier.windowConstraints(minWidth = 1300.dp, minHeight = 820.dp)) { SlidesWindow() } }
        WindowContainer(
            Containers.CHART, form = Form.Volumetric,
            defaultSize = WindowContainerSize(WindowFit.CHART_W.toFloat(), WindowFit.CHART_H.toFloat(), WindowFit.CHART_D.toFloat(), LengthUnit.Meters),
            enableMaterialBackground = false,
            placement = { chartPlacement(containers) },
        ) { ChartVolume() }
        Stage(Containers.STAGE) { StageScreen() }
    }
