package io.embodit.spatialoffice.platform

import android.content.Intent
import android.os.Bundle
import com.pico.spatial.ui.platform.stub.SpatialLaunchActivity

/** Launcher entry, and the target of "Open with" / Share for the file types listed in the manifest. */
class LaunchActivity : SpatialLaunchActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Graph.from(this).openIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        Graph.from(this).openIntent(intent)
    }
}
