package io.embodit.spatialoffice.core.layout

/**
 * Which listed window a placement stands against (v0.2.4, QA #4 q4-2).
 *
 * The containers the system hands to a placement keep windows that were already destroyed: after the 2nd and 3rd
 * rehearsal the list held two `SpatialOfficeSheet` entries (and old Slides / Chart ones), all saying `isOnstage=true`.
 * v0.2.3 took the first entry with the sheet's name, which was the destroyed one, and the system put the chart in
 * front of the user, over the grid. Every window now reports its container instance id when it is created and when
 * it is destroyed; the placement takes the listed entry whose instance is the live one, never a dead one.
 */
object LiveAnchor {
    /** One entry of the placement list: container name and the system's instance id (-1 when unknown). */
    data class Listed(val name: String, val instance: Int)

    /**
     * Index in [listed] of the live window called [name]: the entry with instance [live] if listed; else, of the
     * entries with that name that are not in [dead], the newest (instance ids grow); null when there is none.
     */
    fun pick(listed: List<Listed>, name: String, live: Int?, dead: Set<Int>): Int? {
        val same = listed.withIndex().filter { it.value.name == name }
        if (live != null) same.firstOrNull { it.value.instance == live }?.let { return it.index }
        // not listed under its own id (or not known yet): any entry that was never reported destroyed, newest first
        return same.filter { it.value.instance !in dead }.maxByOrNull { it.value.instance }?.index
    }

    /** [pick] for [name], else for each of [fallbacks] in turn (each by its own live / dead instances). */
    fun anchor(listed: List<Listed>, name: String, fallbacks: List<String>, live: (String) -> Int?, dead: Set<Int>): Int? =
        (listOf(name) + fallbacks).firstNotNullOfOrNull { pick(listed, it, live(it), dead) }
}
