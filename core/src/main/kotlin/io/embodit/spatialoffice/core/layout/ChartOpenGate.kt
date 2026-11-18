package io.embodit.spatialoffice.core.layout

/**
 * When the 3D chart volume may open (v0.2.3, QA #3 q3-2).
 *
 * The volume is placed `Placement.top(sheet)`, and the system only lists the sheet window among the containers a
 * placement can stand against once that window is up. After a rehearsal the sheet and the chart were reopened 90 ms
 * apart: the chart's placement found no sheet (`anchor=null`), fell back to the spot in front of the user and covered
 * the grid. So the chart now waits: it opens only when the sheet window has reported ON_RESUME and [settleMs] have
 * passed since; if the sheet window is not open it asks for it first; if the sheet never comes up (or the Full Space Stage closed less than [STAGE_QUIET_MS] ago) within
 * [timeoutMs] the chart is not opened at all (it never opens in front of the user without a sheet to stand on).
 *
 * Pure: the app calls [step] every 100 ms with what it knows about the sheet window and acts on the answer.
 */
class ChartOpenGate(private val settleMs: Long = SETTLE_MS, private val timeoutMs: Long = TIMEOUT_MS) {
    enum class Act { WAIT, OPEN_SHEET, OPEN_CHART, DROP }

    private var askedAt = -1L
    private var sheetAsked = false
    val waiting: Boolean get() = askedAt >= 0

    fun ask(now: Long) { if (askedAt < 0) { askedAt = now; sheetAsked = false } }

    /**
     * [hasSheetFile]: a spreadsheet is open (the chart's data). [sheetOpen]: its window exists or is opening.
     * [sheetResumedAt]: when the sheet window last reported ON_RESUME since it was created, or null.
     */
    fun step(now: Long, hasSheetFile: Boolean, sheetOpen: Boolean, sheetResumedAt: Long?, stageClosedAt: Long? = null): Act {
        if (askedAt < 0) return Act.WAIT
        // v0.2.3 emulator: a chart placed 0.6 s after the Full Space Stage went away, while the system was still
        // bringing the Shared Space windows back, landed in front of the sheet although its anchor was the sheet
        val stageQuiet = stageClosedAt == null || now - stageClosedAt >= STAGE_QUIET_MS
        val act = when {
            !hasSheetFile -> Act.DROP
            sheetResumedAt != null && now - sheetResumedAt >= settleMs && stageQuiet -> Act.OPEN_CHART
            now - askedAt > timeoutMs -> Act.DROP
            !sheetOpen && !sheetAsked -> { sheetAsked = true; Act.OPEN_SHEET }
            else -> Act.WAIT
        }
        if (act == Act.OPEN_CHART || act == Act.DROP) askedAt = -1L
        return act
    }

    companion object {
        /** ON_RESUME to listed among the placement containers: QA #3 log, sheet ON_RESUME → listed within one frame; 800 ms margin. */
        const val SETTLE_MS = 800L
        /** After the Stage (spread / rehearsal) closes, Shared Space windows settle for this long. */
        const val STAGE_QUIET_MS = 2500L
        const val TIMEOUT_MS = 8000L

        /**
         * What the chart's placement stands against: the sheet window, else (never expected after [ChartOpenGate])
         * any open window, the chart going above it; null only when no window is open at all.
         */
        fun anchor(openIds: List<String>, sheetId: String, others: List<String>): String? =
            openIds.firstOrNull { it == sheetId } ?: others.firstOrNull { it in openIds } ?: openIds.firstOrNull()
    }
}
