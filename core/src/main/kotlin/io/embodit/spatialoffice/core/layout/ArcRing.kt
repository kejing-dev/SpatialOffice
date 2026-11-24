package io.embodit.spatialoffice.core.layout

/**
 * v0.5.1 (user 2026-11-21, profile D "多窗口默认环绕摆开": 「直接摆在旁边环绕多窗口这样排布，发挥空间计算平台多窗口的优势」):
 * the editor windows stand side by side on the circle around the user, left to right in [order]. The first one opens
 * in front, the next at its right, the next at its left. No button press is needed.
 * 2026-11-24: the row ends there ([PER_SIDE]); a fourth window opens in front, over the row (the system shows it on
 * the centre's spot and takes the centre off stage until that window is moved or closed).
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

    /**
     * Where window [key] goes: null = in front (row empty), else (anchor key, "right" | "left"). Adds it to the row.
     *
     * v0.5.2 (QA #14 q14-1): [hub] is the home window. It takes a place of its own in the row: when the row is empty
     * and the home window is up, the home window is the row's first window (in front, where the system put it) and
     * the first file goes beside it, not on its spot. v0.5.1 left the home window out, so the first file opened on
     * the home window's spot, and [Window n] › [Home] then put the home window back there and the system took that
     * file off stage. A home window that came back while files are up (the system puts it in front of the user,
     * somewhere unknown) is left out of the bookkeeping.
     */
    fun place(key: String, hub: String? = null, live: (String) -> Boolean = { true }): Pair<String, String>? {
        // v0.6.0 (K1): a window coming back after the Stage takes the place [replan] gave it
        plan.remove(key)?.let { return it }
        row.removeAll { it == key || !live(it) }
        if (center != null && center !in row) center = row.getOrNull(row.size / 2)
        if (hub != null && hub != key && row.isEmpty() && live(hub)) { row.add(hub); center = hub }
        if (row.isEmpty()) { row.add(key); center = key; return null }
        val c = row.indexOf(center).coerceAtLeast(0)
        val rightCount = row.size - 1 - c
        val leftCount = c
        // the row is full (left · centre · right): the next window opens in front, over the row, and is not one of it
        if (rightCount >= PER_SIDE && leftCount >= PER_SIDE) return null
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

    fun clear() { row.clear(); center = null; plan.clear() }

    /** Places promised by [replan] to windows that are still to open again (key → anchor, side). */
    private val plan = LinkedHashMap<String, Pair<String, String>>()

    /**
     * v0.6.0 (K1, QA #15): the windows closed for the Stage come back around [hub] (the home window, which the system
     * puts in front of the user). Each keeps the side of the hub it stood on before (v0.5.2 put them back in the
     * order they opened, so the sheet and the document changed sides), and [first] (the file just rehearsed / spread
     * out) takes the place right next to the hub on its side, the nearest one to the front. Windows of [before] that
     * are not in [reopen] are left out. Returns the order to open them in (each one's anchor opens before it); the
     * places are given by [place] as they open.
     */
    fun replan(before: List<String>, hub: String, reopen: List<String>, first: String?): List<Pair<String, Pair<String, String>>> {
        val want = reopen.toSet()
        val h = before.indexOf(hub)
        val left = ArrayList<String>(); val right = ArrayList<String>()
        if (h >= 0) {
            for (i in h - 1 downTo 0) before[i].takeIf { it in want }?.let { left.add(it) }
            for (i in h + 1 until before.size) before[i].takeIf { it in want }?.let { right.add(it) }
        } else {
            // the hub was not in the row: alternate right / left in the row's order
            before.filter { it in want }.forEachIndexed { i, k -> if (i % 2 == 0) right.add(k) else left.add(k) }
        }
        // a window that was not in the row (it stood in front, over it) takes a free place at a side, else it opens in front again
        for (k in reopen) if (k !in left && k !in right) {
            if (right.size < PER_SIDE && right.size <= left.size) right.add(k) else if (left.size < PER_SIDE) left.add(k) else if (right.size < PER_SIDE) right.add(k)
        }
        if (first != null && (first in left || first in right)) {
            val onLeft = first in left
            left.remove(first); right.remove(first)
            if (onLeft) left.add(0, first) else right.add(0, first)
        }
        // no more than [PER_SIDE] at each side; the others open in front again ([place] gives them no place in the row)
        while (left.size > PER_SIDE) left.removeAt(left.size - 1)
        while (right.size > PER_SIDE) right.removeAt(right.size - 1)
        val out = ArrayList<Pair<String, Pair<String, String>>>()
        fun take(side: List<String>, i: Int, name: String) { side.getOrNull(i)?.let { k -> out.add(k to ((if (i == 0) hub else side[i - 1]) to name)) } }
        // the first window first, then outwards, right and left in turn
        if (first != null && (first in left || first in right)) { if (left.firstOrNull() == first) take(left, 0, "left") else take(right, 0, "right") }
        for (i in 0 until maxOf(left.size, right.size)) {
            if (right.getOrNull(i) != first) take(right, i, "right")
            if (left.getOrNull(i) != first) take(left, i, "left")
        }
        row.clear(); row.addAll(left.reversed()); row.add(hub); row.addAll(right); center = hub
        plan.clear(); for ((k, p) in out) plan[k] = p
        return out
    }

    /** Slot of [key] counted from the first window: 0 centre, +1 first to the right, −1 first to the left, … */
    fun slot(key: String): Int? {
        val i = row.indexOf(key); if (i < 0) return null
        return i - row.indexOf(center).coerceAtLeast(0)
    }

    companion object {
        /**
         * Windows at each side of the first one. One: the row is left · centre · right, which is what a person looks
         * at without turning round (user on the headset, 2026-11-24: "一般就是看到左中右 3 个窗口就行了…不要超过 180 度的视野").
         * v0.5.1 went on right, left, right, … all the way round.
         */
        const val PER_SIDE = 1

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
