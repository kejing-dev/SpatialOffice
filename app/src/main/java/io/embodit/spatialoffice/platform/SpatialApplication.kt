package io.embodit.spatialoffice.platform

import android.app.Application
import com.pico.spatial.ui.foundation.dsl.launch
import io.embodit.spatialoffice.mainApp

class SpatialApplication : Application() {
    lateinit var graph: Graph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = Graph(this)
        DebugReceiver.register(this, graph)
        launch(::mainApp)
    }
}
