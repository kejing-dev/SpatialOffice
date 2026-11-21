package io.embodit.spatialoffice.core.layout

import kotlin.math.abs

/** Seat frame: where the layout's origin is in Stage space. Position = head when the layout was (re)centred, yaw only. */
data class Seat(val x: Float, val y: Float, val z: Float, val yawRad: Float) {
    /** Seat-local point → Stage space. */
    fun toStage(lx: Float, ly: Float, lz: Float): Triple<Float, Float, Float> {
        val c = kotlin.math.cos(yawRad); val s = kotlin.math.sin(yawRad)
        return Triple(x + lx * c + lz * s, y + ly, z - lx * s + lz * c)
    }

    companion object {
        /** Seat from a head position and its forward vector (pitch and roll are ignored). */
        fun fromHead(px: Float, py: Float, pz: Float, fx: Float, fz: Float): Seat {
            // forward (0,0,−1) = yaw 0; turning left (towards −x) is positive yaw
            val yaw = if (abs(fx) < 1e-5f && abs(fz) < 1e-5f) 0f else kotlin.math.atan2(-fx, -fz)
            return Seat(px, py, pz, yaw)
        }
    }
}
