package io.embodit.spatialoffice.platform

import android.util.Log
import com.pico.spatial.tracking.hmd.HMDTrackingProvider
import io.embodit.spatialoffice.core.layout.Seat
import kotlin.math.asin

/** A head sample in Stage space: position and the forward vector. */
data class HeadSample(val x: Float, val y: Float, val z: Float, val fx: Float, val fy: Float, val fz: Float) {
    val pitchDeg: Float get() = Math.toDegrees(asin(fy.coerceIn(-1f, 1f).toDouble())).toFloat()
    fun seat(): Seat = Seat.fromHead(x, y, z, fx, fz)
}

/**
 * Head pose, used for exactly one thing (never to aim, select or place content by gaze, and since v0.3.0 never to hold
 * a window back): where the spread layout's seat is when the user enters or presses [Bring back to front] (position +
 * yaw only). The all-zero identity sample the tracker reports before it is ready is ignored.
 */
object Head {
    private var provider: HMDTrackingProvider? = null
    @Volatile var last: HeadSample? = null
        private set

    fun start() {
        if (provider != null) return
        val p = HMDTrackingProvider()
        val r = runCatching { p.start() }
        Log.i(TAG, "TRACK hmd start=${r.getOrNull() ?: r.exceptionOrNull()}")
        provider = p
    }

    fun stop() {
        runCatching { provider?.stop() }
        provider = null
    }

    fun sample(): HeadSample? {
        val p = provider ?: return last
        val pose = runCatching { p.latestData.hmdPose }.getOrNull() ?: return last
        val q = pose.rotation
        val pos = pose.position
        if (pos.x == 0f && pos.y == 0f && pos.z == 0f && q.w == 1f && q.x == 0f && q.y == 0f && q.z == 0f) return last
        if (!(pos.x.isFinite() && pos.y.isFinite() && pos.z.isFinite())) return last
        // forward = q * (0, 0, −1)
        val fx = -(2f * (q.x * q.z + q.w * q.y))
        val fy = -(2f * (q.y * q.z - q.w * q.x))
        val fz = -(1f - 2f * (q.x * q.x + q.y * q.y))
        val s = HeadSample(pos.x, pos.y, pos.z, fx, fy, fz)
        last = s
        return s
    }
}
