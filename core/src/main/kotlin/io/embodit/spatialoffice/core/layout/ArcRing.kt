package io.embodit.spatialoffice.core.layout

/**
 * v0.5.1 (user 2026-11-21, profile D "多窗口默认环绕摆开": 「直接摆在旁边环绕多窗口这样排布，发挥空间计算平台多窗口的优势」):
 * the editor windows stand side by side on the circle around the user, left to right in [order]. The first one opens
 * in front; every next one goes beside an outer window, alternately to the right and to the left so the row stays
 * centred on the first, and never in front of (over) the windows already up. No button press is needed.
 *
 * Pure bookkeeping of the row; the system does the actual placement (`Placement.right / left(anchor, gap)`), which
 * keeps both windows facing the user at the same height.
 */
class ArcRing {
    /** Window keys, left to right. */
    private val row = ArrayList<String>()
    /** The first window of the row (the one the others stand around) while it is in the row. */
    private var center: String? = null

    val order: List<String> get() = row.toList()

    /** Where window [key] goes: null = in front (row empty), else (anchor key, "right" | "left"). Adds it to the row. */
    fun place(key: String, live: (String) -> Boolean = { true }): Pair<String, String>? {
        row.removeAll { it == key || !live(it) }
        if (center != null && center !in row) center = row.getOrNull(row.size / 2)
        if (row.isEmpty()) { row.add(key); center = key; return null }
        val c = row.indexOf(center).coerceAtLeast(0)
        val rightCount = row.size - 1 - c
        val leftCount = c
        return if (rightCount <= leftCount) {
            val anchor = row.last(); row.add(key); anchor to "right"
        } else {
            val anchor = row.first(); row.add(0, key); anchor to "left"
        }
    }

    /** A window closed: it leaves the row (its neighbours keep their places). */
    fun remove(key: String) {
        row.remove(key)
        if (center == key) center = row.getOrNull(row.size / 2)
    }

    fun clear() { row.clear(); center = null }

    /** Slot of [key] counted from the first window: 0 centre, +1 first to the right, −1 first to the left, … */
    fun slot(key: String): Int? {
        val i = row.indexOf(key); if (i < 0) return null
        return i - row.indexOf(center).coerceAtLeast(0)
    }

    companion object {
        /**
         * Horizontal angle (degrees) between the centres of two neighbouring windows of widths [wDp] and [nextDp] with
         * [gapDp] between them on a circle of [radiusM], at [mPerDp] metres per dp (emulator ≈ 1.5–1.75 mm / dp).
         */
        fun neighbourAngle(wDp: Double, nextDp: Double, gapDp: Double, radiusM: Double, mPerDp: Double): Double {
            val arc = (wDp / 2 + gapDp + nextDp / 2) * mPerDp
            return Math.toDegrees(arc / radiusM)
        }
    }
}
