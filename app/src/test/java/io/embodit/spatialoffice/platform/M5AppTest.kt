package io.embodit.spatialoffice.platform

import org.junit.Assert.assertEquals
import org.junit.Test

/** v0.7.0 (M5): rules of the review pane that do not need a device. */
class M5AppTest {

    // B / BACK closes the topmost layer: menu, dialog or sheet, a held format painter, the review pane, and only then the old rules
    @Test
    fun backClosesTheTopmostLayerFirst() {
        fun d(menu: Boolean, overlay: Boolean, painter: Boolean, review: Boolean, home: Boolean = true, stage: Boolean = false) =
            BackPolicy.decide(overlayOpen = overlay, stageUp = stage, homeOpen = home, menuOpen = menu, painterHeld = painter, reviewOpen = review)
        assertEquals(BackAct.CLOSE_MENU, d(menu = true, overlay = true, painter = true, review = true))
        assertEquals(BackAct.CLOSE_OVERLAY, d(menu = false, overlay = true, painter = true, review = true))
        assertEquals(BackAct.DROP_PAINTER, d(menu = false, overlay = false, painter = true, review = true))
        assertEquals(BackAct.CLOSE_REVIEW, d(menu = false, overlay = false, painter = false, review = true))
        assertEquals(BackAct.NOTHING, d(menu = false, overlay = false, painter = false, review = false))
        // the pane open and the home window closed: the pane goes first, the next press brings the home window back
        assertEquals(BackAct.CLOSE_REVIEW, d(menu = false, overlay = false, painter = false, review = true, home = false))
        assertEquals(BackAct.REOPEN_HOME, d(menu = false, overlay = false, painter = false, review = false, home = false))
        // in the Stage the pane is not on screen: B does nothing there, as before
        assertEquals(BackAct.NOTHING, d(menu = false, overlay = false, painter = false, review = true, stage = true))
    }
}
