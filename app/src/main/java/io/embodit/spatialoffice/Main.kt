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
 * Containers (design 2.1). Shared Space: the home window (default container, 1120 × 800 dp from the manifest), one
 * window per editor, and the chart volume; each editor opens to the right of a window that is already there, so
 * no window is ever placed from the head pose. Full Space: one Mixed Stage for the spread layout and rehearsal.
 */
fun mainApp(scope: SpatialAppScope) =
    with(scope) {
        DefaultWindowContainer {
            Box(Modifier.windowConstraints(minWidth = 1040.dp, minHeight = 760.dp)) { HomeWindow() }
        }
        WindowContainer(
            Containers.DOC, form = Form.Planar,
            defaultSize = WindowContainerSize(width = 1040.dp, height = 860.dp),
            placement = { beside(containers, Containers.HOME)?.let { Placement.right(it) } ?: Placement.none() },
        ) { Box(Modifier.windowConstraints(minWidth = 960.dp, minHeight = 720.dp)) { DocWindow() } }
        WindowContainer(
            Containers.SHEET, form = Form.Planar,
            defaultSize = WindowContainerSize(width = 1280.dp, height = 800.dp),
            placement = { beside(containers, Containers.DOC, Containers.HOME)?.let { Placement.right(it) } ?: Placement.none() },
        ) { Box(Modifier.windowConstraints(minWidth = 1100.dp, minHeight = 700.dp)) { SheetWindow() } }
        WindowContainer(
            Containers.SLIDES, form = Form.Planar,
            defaultSize = WindowContainerSize(width = 1360.dp, height = 860.dp),
            placement = { beside(containers, Containers.SHEET, Containers.DOC, Containers.HOME)?.let { Placement.right(it) } ?: Placement.none() },
        ) { Box(Modifier.windowConstraints(minWidth = 1300.dp, minHeight = 820.dp)) { SlidesWindow() } }
        WindowContainer(
            Containers.CHART, form = Form.Volumetric,
            defaultSize = WindowContainerSize(0.76f, 0.60f, 0.48f, LengthUnit.Meters),
            enableMaterialBackground = false,
            placement = { beside(containers, Containers.SHEET, Containers.HOME)?.let { Placement.right(it) } ?: Placement.none() },
        ) { ChartVolume() }
        Stage(Containers.STAGE) { StageScreen() }
    }
