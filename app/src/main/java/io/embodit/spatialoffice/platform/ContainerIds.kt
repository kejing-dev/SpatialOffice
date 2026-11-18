package io.embodit.spatialoffice.platform

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.util.Log
import com.pico.spatial.ui.foundation.dsl.ContainerProxy
import java.util.concurrent.ConcurrentHashMap

/**
 * Container instance ids (v0.2.4, QA #4 q4-2). The placement list the system hands to a WindowContainer keeps
 * windows that were already destroyed, under the same name. Each window reports its own instance id here when its
 * content is created and when it is destroyed, and the chart placement anchors only to the live instance.
 *
 * The SDK has the id on both sides but keeps the getters `internal` (`Activity.retrieveSpatialContainer()`, `SpatialContainer.containerId`,
 * `ContainerProxy.containerId`), so they are read by reflection; -1 when that fails (then the old name-only rule).
 */
object ContainerIds {
    /** Container name → live instance id. */
    private val live = ConcurrentHashMap<String, Int>()
    /** Instance ids that reported ON_DESTROY. */
    val dead: MutableSet<Int> = ConcurrentHashMap.newKeySet()

    fun live(name: String): Int? = live[name]

    fun created(name: String, instance: Int) {
        if (instance < 0) return
        live.put(name, instance)?.takeIf { it != instance }?.let { dead += it }
        dead.remove(instance)
        Log.i(TAG, "CONTAINER $name live=#$instance dead=$dead")
    }

    fun destroyed(name: String, instance: Int) {
        if (instance < 0) return
        dead += instance
        live.remove(name, instance)
        Log.i(TAG, "CONTAINER $name destroyed #$instance live=${live[name]?.let { "#$it" } ?: "-"}")
    }

    /** The instance id of the window whose content runs in [context]; -1 if unknown. */
    fun of(context: Context): Int {
        var c: Context? = context
        while (c != null && c !is Activity) c = (c as? ContextWrapper)?.baseContext
        val activity = c as? Activity ?: return -1
        // Activity.retrieveSpatialContainer() is internal to the SDK too
        val container = runCatching {
            Class.forName("com.pico.spatial.core.container.ContextExtKt").getMethod("retrieveSpatialContainer", Activity::class.java).invoke(null, activity)
        }.onFailure { Log.w(TAG, "CONTAINER retrieve: $it") }.getOrNull() ?: return -1
        return reflectId(container, "getContainerId\$spatialsdk_core_core")
    }

    /** The instance id of a placement list entry; -1 if unknown. */
    fun of(proxy: ContainerProxy): Int = reflectId(proxy, "getContainerId\$foundation_release")

    private val methods = ConcurrentHashMap<String, java.lang.reflect.Method>()

    private fun reflectId(o: Any, getter: String): Int = runCatching {
        val key = o.javaClass.name + "#" + getter
        val m = methods.getOrPut(key) {
            generateSequence<Class<*>>(o.javaClass) { it.superclass }.firstNotNullOf { k ->
                runCatching { k.getDeclaredMethod(getter) }.getOrNull()
            }.apply { isAccessible = true }
        }
        m.invoke(o) as Int
    }.getOrElse { Log.w(TAG, "CONTAINER id of ${o.javaClass.simpleName}: $it"); -1 }
}
