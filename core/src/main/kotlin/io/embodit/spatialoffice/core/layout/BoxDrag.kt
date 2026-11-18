package io.embodit.spatialoffice.core.layout

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToLong

/**
 * Sums the per-event drag deltas of one press into the travel since the press (profile D53). SpatialUI and Compose
 * both report `dragAmount` as one event's delta; a handler that reads it as the total pins the dragged thing to where
 * it started (kinetica / render-lab on the headset, 2026-11-14). Unit-tested with the headset pattern: 60 events of
 * 2 units total 120.
 */
class DragTotal {
    var x = 0f; private set
    var y = 0f; private set
    var events = 0; private set

    fun reset() { x = 0f; y = 0f; events = 0 }

    fun add(dx: Float, dy: Float): DragTotal { x += dx; y += dy; events++; return this }
}

/** A rectangle in the file's own units (EMU on slides and for document pictures). */
data class UnitBox(val x: Long, val y: Long, val w: Long, val h: Long)

enum class Corner(val left: Boolean, val top: Boolean) {
    TOP_LEFT(true, true), TOP_RIGHT(false, true), BOTTOM_LEFT(true, false), BOTTOM_RIGHT(false, false)
}

/**
 * Moving and corner-resizing an object that was inserted on a slide or in a document (v0.2.5, headset feedback
 * 2026-11-17: "PPT 插入的东西移动不了"). Pure: the box at the press plus the travel since the press gives the box now,
 * so nothing drifts however many events arrive. Hand, controller ray and gaze + pinch all reach the window as the
 * same pointer drag; this is the one path behind them (and behind the debug command that replays deltas).
 */
object BoxDrag {
    /** Smallest side a resize leaves: 0.25 in on a slide. */
    const val MIN_SIDE = 228_600L

    /** Travel on screen (pixels) in file units, given how many pixels one unit takes. */
    fun units(px: Float, pxPerUnit: Float): Long = if (pxPerUnit <= 0f) 0L else (px / pxPerUnit).roundToLong()

    /** [start] moved by the travel since the press; at least half of the box stays on the [pageW] × [pageH] page. */
    fun move(start: UnitBox, dx: Long, dy: Long, pageW: Long, pageH: Long): UnitBox {
        val x = (start.x + dx).coerceIn(-start.w / 2, max(-start.w / 2, pageW - start.w / 2))
        val y = (start.y + dy).coerceIn(-start.h / 2, max(-start.h / 2, pageH - start.h / 2))
        return start.copy(x = x, y = y)
    }

    /**
     * [start] with [corner] dragged by the travel since the press; the opposite corner stays where it is. With
     * [keepAspect] (pictures) the box keeps its proportions and follows whichever axis asks for the larger change.
     * Neither side goes under [minSide] nor, when [maxW] is given (document text width), over it.
     */
    fun resize(start: UnitBox, corner: Corner, dx: Long, dy: Long, keepAspect: Boolean, minSide: Long = MIN_SIDE, maxW: Long? = null): UnitBox {
        val sw = start.w.coerceAtLeast(1); val sh = start.h.coerceAtLeast(1)
        var w = if (corner.left) sw - dx else sw + dx
        var h = if (corner.top) sh - dy else sh + dy
        if (keepAspect) {
            val fw = w.toDouble() / sw; val fh = h.toDouble() / sh
            var f = if (abs(fw - 1) >= abs(fh - 1)) fw else fh
            val lo = max(minSide.toDouble() / sw, minSide.toDouble() / sh).coerceAtMost(1.0)
            f = f.coerceAtLeast(lo)
            if (maxW != null) f = f.coerceAtMost(maxW.toDouble() / sw)
            w = (sw * f).roundToLong(); h = (sh * f).roundToLong()
        } else {
            w = w.coerceAtLeast(minOf(minSide, sw)); h = h.coerceAtLeast(minOf(minSide, sh))
            if (maxW != null) w = w.coerceAtMost(maxW)
        }
        val x = if (corner.left) start.x + sw - w else start.x
        val y = if (corner.top) start.y + sh - h else start.y
        return UnitBox(x, y, w, h)
    }
}
