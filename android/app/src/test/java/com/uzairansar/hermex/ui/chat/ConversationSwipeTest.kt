package com.uzairansar.hermex.ui.chat

import org.junit.Assert.*
import org.junit.Test

class ConversationSwipeTest {
    private fun gesture() = ConversationSwipe("previous", "next", 10f, 64f, 500L)
    @Test fun horizontalClaimsOnlyAfterSlopAndNavigatesOnlyOnRelease() {
        val swipe = gesture()
        assertFalse(swipe.update(4f, 1f, 10, false, 1, true))
        assertTrue(swipe.update(-70f, 4f, 100, false, 1, true))
        assertNull(swipe.target)
        swipe.update(-70f, 4f, 200, false, 1, false)
        assertEquals("next", swipe.target)
    }
    @Test fun verticalChildConsumptionMultitouchAndLongPressPermanentlyReject() {
        for (case in 0..3) {
            val swipe = gesture()
            swipe.update(if (case == 0) 2f else 15f, if (case == 0) 30f else 0f,
                if (case == 3) 501 else 50, case == 1, if (case == 2) 2 else 1, true)
            assertFalse(swipe.update(-100f, 0f, 600, false, 1, false))
            assertNull(swipe.target)
        }
    }
    @Test fun reversalBelowThresholdAndBoundariesDoNotNavigate() {
        val swipe = gesture()
        swipe.update(-70f, 0f, 80, false, 1, true)
        swipe.update(-20f, 0f, 100, false, 1, false)
        assertNull(swipe.target)
        val edge = ConversationSwipe(null, null, 10f, 64f, 500)
        edge.update(100f, 0f, 100, false, 1, false)
        assertNull(edge.target)
    }
}
