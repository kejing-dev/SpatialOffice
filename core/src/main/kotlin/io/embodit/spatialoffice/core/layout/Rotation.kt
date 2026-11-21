package io.embodit.spatialoffice.core.layout

import kotlin.math.atan2
import kotlin.math.roundToInt

/**
 * p24 (M2): the rotation handle above a slide object. The angle is that of the pointer around the object's centre,
 * measured clockwise from straight up (screen y grows downward), snapped to [STEP] degrees, 0..359.
 */
object Rotation {
    const val STEP = 15

    /** Degrees clockwise from "up" of the point ([px], [py]) seen from the centre ([cx], [cy]). */
    fun angle(cx: Float, cy: Float, px: Float, py: Float): Float {
        val deg = Math.toDegrees(atan2((px - cx).toDouble(), (cy - py).toDouble())).toFloat()
        return if (deg < 0) deg + 360f else deg
    }

    fun snap(deg: Float, step: Int = STEP): Int = norm(((deg / step).roundToInt()) * step)

    fun norm(deg: Int): Int = ((deg % 360) + 360) % 360

    /**
     * The handle starts at [start] (offset from the centre, in the object's unrotated frame turned by [startDeg]) and has
     * travelled ([dx], [dy]) since the press: the snapped angle the object takes.
     */
    fun dragged(startDeg: Int, handleDist: Float, dx: Float, dy: Float): Int {
        val r = Math.toRadians(startDeg.toDouble())
        val hx = (handleDist * kotlin.math.sin(r)).toFloat()
        val hy = (-handleDist * kotlin.math.cos(r)).toFloat()
        return snap(angle(0f, 0f, hx + dx, hy + dy))
    }
}
