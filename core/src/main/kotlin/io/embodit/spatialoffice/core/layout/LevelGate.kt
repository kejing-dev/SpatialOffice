package io.embodit.spatialoffice.core.layout

import kotlin.math.abs

/**
 * A window that is opened while the head is pitched can come up tilted. Requests to open one wait here until the
 * head has been level (|pitch| ≤ [maxPitchDeg]) for [dwellMs]. Without a usable head pose the request goes through
 * after [noHeadMs]; a head that never levels goes through after [maxWaitMs]. Pure logic: the caller feeds time and pitch.
 */
class LevelGate(val maxPitchDeg: Float = 10f, val dwellMs: Long = 300L, val noHeadMs: Long = 600L, val maxWaitMs: Long = 3000L) {
    var pending: String? = null
        private set
    private var askedMs = 0L
    private var levelSinceMs = -1L
    private var noHeadSinceMs = -1L

    val waiting: Boolean get() = pending != null

    fun ask(why: String, nowMs: Long) {
        if (pending == null) askedMs = nowMs
        pending = why; levelSinceMs = -1L; noHeadSinceMs = -1L
    }

    fun cancel() { pending = null; levelSinceMs = -1L; noHeadSinceMs = -1L }

    /** Returns the reason to open now ("<why>", "<why>/nohead", "<why>/timeout"), or null to keep waiting. */
    fun step(nowMs: Long, pitchDeg: Float?): String? {
        val why = pending ?: return null
        if (nowMs - askedMs >= maxWaitMs) { cancel(); return "$why/timeout" }
        if (pitchDeg == null || !pitchDeg.isFinite()) {
            levelSinceMs = -1L
            if (noHeadSinceMs < 0L) noHeadSinceMs = nowMs
            if (nowMs - noHeadSinceMs < noHeadMs) return null
            cancel(); return "$why/nohead"
        }
        noHeadSinceMs = -1L
        if (abs(pitchDeg) > maxPitchDeg) { levelSinceMs = -1L; return null }
        if (levelSinceMs < 0L) levelSinceMs = nowMs
        if (nowMs - levelSinceMs < dwellMs) return null
        cancel(); return why
    }
}

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
