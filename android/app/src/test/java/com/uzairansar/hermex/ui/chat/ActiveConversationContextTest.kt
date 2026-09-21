package com.uzairansar.hermex.ui.chat

import org.junit.Assert.*
import org.junit.Test

class ActiveConversationContextTest {
    @Test fun contextIsScopedToItsAccountAndClearedForExternalNavigation() {
        val context = ActiveConversationContext()
        assertEquals(emptyList<String>(), context.idsFor("one"))
        context.update("one", listOf("pin", "a"))
        assertEquals(listOf("pin", "a"), context.idsFor("one"))
        assertEquals(emptyList<String>(), context.idsFor("two"))
        context.clear()
        assertEquals(emptyList<String>(), context.idsFor("one"))
    }

    @Test fun laterProjectionReplacesRatherThanAppendsOldNeighbors() {
        val context = ActiveConversationContext()
        val source = mutableListOf("a", "b")
        context.update("one", source)
        source.clear()
        assertEquals(listOf("a", "b"), context.idsFor("one"))
        context.update("one", listOf("c"))
        assertEquals(listOf("c"), context.idsFor("one"))
    }
}
