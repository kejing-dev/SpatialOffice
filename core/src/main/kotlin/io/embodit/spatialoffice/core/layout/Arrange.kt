package io.embodit.spatialoffice.core.layout

import kotlin.math.abs

/**
 * v0.6.0 (M4 p25): lining up slide objects. [align] and [distribute] work on the boxes of several picked objects (in
 * EMU) and return the new boxes in the same order; [snap] is what a dragged object does near the edges and centres of
 * the other objects and of the slide: it moves onto them and the guide lines to draw are returned with it.
 */
object Arrange {
    enum class Align { LEFT, CENTER, RIGHT, TOP, MIDDLE, BOTTOM }

    /** Lines the boxes up on one side / centre of their common bounding box (PowerPoint's "align selected objects"). */
    fun align(boxes: List<UnitBox>, how: Align): List<UnitBox> {
        if (boxes.size < 2) return boxes
        val x0 = boxes.minOf { it.x }; val x1 = boxes.maxOf { it.x + it.w }
        val y0 = boxes.minOf { it.y }; val y1 = boxes.maxOf { it.y + it.h }
        return boxes.map { b ->
            when (how) {
                Align.LEFT -> b.copy(x = x0)
                Align.RIGHT -> b.copy(x = x1 - b.w)
                Align.CENTER -> b.copy(x = (x0 + x1) / 2 - b.w / 2)
                Align.TOP -> b.copy(y = y0)
                Align.BOTTOM -> b.copy(y = y1 - b.h)
                Align.MIDDLE -> b.copy(y = (y0 + y1) / 2 - b.h / 2)
            }
        }
    }

    /**
     * Equal gaps between neighbours across the boxes' span, sideways ([horizontal]) or up and down. The outermost two
     * stay where they are; the order is the boxes' order along that axis (ties keep their order). Fewer than three
     * boxes come back unchanged. Boxes that already share one left edge and width stay put.
     */
    fun distribute(boxes: List<UnitBox>, horizontal: Boolean): List<UnitBox> {
        if (boxes.size < 3) return boxes
        fun pos(b: UnitBox) = if (horizontal) b.x else b.y
        fun len(b: UnitBox) = if (horizontal) b.w else b.h
        val order = boxes.indices.sortedWith(compareBy({ pos(boxes[it]) }, { pos(boxes[it]) + len(boxes[it]) }))
        val first = pos(boxes[order.first()])
        val end = boxes.maxOf { pos(it) + len(it) }
        val total = boxes.sumOf { len(it) }
        val gap = (end - first - total).toDouble() / (boxes.size - 1)
        val out = boxes.toMutableList()
        var at = first.toDouble()
        for (i in order) {
            val b = boxes[i]
            val p = Math.round(at)
            out[i] = if (horizontal) b.copy(x = p) else b.copy(y = p)
            at += len(b) + gap
        }
        // the last one keeps its far edge exactly (rounding)
        val last = order.last()
        val lb = boxes[last]
        if (pos(lb) + len(lb) == end) out[last] = if (horizontal) lb.copy(x = end - lb.w) else lb.copy(y = end - lb.h)
        return out
    }

    /** A guide line: vertical at [at] (x) when [vertical], else horizontal at [at] (y); EMU. */
    data class Guide(val vertical: Boolean, val at: Long)

    data class Snapped(val box: UnitBox, val guides: List<Guide>)

    /**
     * [moving] snapped to the nearest line within [tolerance]: its left / centre / right against every other box's
     * left / centre / right and the slide's left / centre / right (the same up and down), each axis on its own.
     * Guides are the lines it snapped to.
     */
    fun snap(moving: UnitBox, others: List<UnitBox>, pageW: Long, pageH: Long, tolerance: Long): Snapped {
        val xs = ArrayList<Long>(); val ys = ArrayList<Long>()
        xs += listOf(0L, pageW / 2, pageW); ys += listOf(0L, pageH / 2, pageH)
        for (o in others) { xs += listOf(o.x, o.x + o.w / 2, o.x + o.w); ys += listOf(o.y, o.y + o.h / 2, o.y + o.h) }
        fun best(edges: List<Long>, lines: List<Long>): Pair<Long, Long>? {
            var bestD = Long.MAX_VALUE; var shift = 0L; var line = 0L
            for (e in edges) for (l in lines) { val d = abs(l - e); if (d < bestD) { bestD = d; shift = l - e; line = l } }
            return if (bestD <= tolerance) shift to line else null
        }
        val bx = best(listOf(moving.x, moving.x + moving.w / 2, moving.x + moving.w), xs)
        val by = best(listOf(moving.y, moving.y + moving.h / 2, moving.y + moving.h), ys)
        val box = moving.copy(x = moving.x + (bx?.first ?: 0L), y = moving.y + (by?.first ?: 0L))
        val guides = listOfNotNull(bx?.let { Guide(true, it.second) }, by?.let { Guide(false, it.second) })
        return Snapped(box, guides)
    }
}
