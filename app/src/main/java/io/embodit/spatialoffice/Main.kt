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
            defaultSize = WindowContainerSize(0.76f, 0.60f, 0.48f, LengthUnit.Meters),
            enableMaterialBackground = false,
            // in front, like the editors: beside the sheet (now in front itself) would be out of view, and
            // Placement.bottom(sheet) put it on the floor about 2 m away (emulator, v0.2.1)
            placement = { editorPlacement(containers, Containers.CHART) },
        ) { ChartVolume() }
        Stage(Containers.STAGE) { StageScreen() }
    }
