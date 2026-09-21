package com.uzairansar.hermex.ui.chat

import org.junit.Assert.*
import org.junit.Test

class ActiveConversationNavigationTest {
    @Test fun nextAndPreviousRespectProjectionOrderWithoutWrapping() {
        val navigation = ActiveConversationNavigation(listOf("pinned", "b", "c"))
        assertEquals("b", navigation.neighbor("pinned", true))
        assertEquals("pinned", navigation.neighbor("b", false))
        assertNull(navigation.neighbor("pinned", false))
        assertNull(navigation.neighbor("c", true))
        assertNull(navigation.neighbor("unknown", true))
    }

    @Test fun emptyOrSingleConversationHasNoNeighbor() {
        assertNull(ActiveConversationNavigation(emptyList()).neighbor("a", true))
        val navigation = ActiveConversationNavigation(listOf("a"))
        assertNull(navigation.neighbor("a", true))
        assertNull(navigation.neighbor("a", false))
    }

    @Test fun duplicateAndBlankIdentifiersDoNotCreatePhantomNeighbors() {
        val navigation = ActiveConversationNavigation(listOf("pin", "", "pin", "b", " "))
        assertEquals(listOf("pin", "b"), navigation.ids)
        assertEquals("b", navigation.neighbor("pin", true))
    }
}
