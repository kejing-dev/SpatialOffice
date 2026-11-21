package io.embodit.spatialoffice.platform

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import com.pico.spatial.ui.foundation.dsl.launch
import io.embodit.spatialoffice.mainApp

class SpatialApplication : Application() {
    lateinit var graph: Graph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = Graph(this)
        DebugReceiver.register(this, graph)
        // profile D60: controller B = system BACK. It never exits or sends the app to the background (BackPolicy).
        // Caught at the earliest layer of every window (BackKeys); the OnBackPressedCallback is the last fallback.
        BackKeys.handler = { where -> graph.onBack(where) }
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityCreated(a: Activity, b: Bundle?) {
                BackKeys.wrap(a)
                val ca = a as? ComponentActivity ?: run { Log.w(TAG, "BACK guard: ${a.javaClass.simpleName} is not a ComponentActivity"); return }
                ca.onBackPressedDispatcher.addCallback(ca, object : OnBackPressedCallback(true) {
                    override fun handleOnBackPressed() { BackKeys.handler("dispatcher:${a.javaClass.simpleName}") }
                })
            }
            override fun onActivityPostCreated(a: Activity, b: Bundle?) { BackKeys.wrap(a); BackKeys.sweep("created") }
            override fun onActivityStarted(a: Activity) = BackKeys.sweep("started")
            override fun onActivityResumed(a: Activity) { BackKeys.wrap(a); BackKeys.sweep("resumed") }
            override fun onActivityPostResumed(a: Activity) { BackKeys.sweep("post-resumed"); BackKeys.sweepSoon("post-resumed") }
            override fun onActivityPaused(a: Activity) {}
            override fun onActivityStopped(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        })
        launch(::mainApp)
    }
}
