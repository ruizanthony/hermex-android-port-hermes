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

    @Test fun metadataReconcilesDirectLinksPreservesFiltersAndFencesOldResponses() {
        val context = ActiveConversationContext()
        val a = com.uzairansar.hermex.core.model.SessionSummary(sessionId = "a", projectId = "project", pinned = true)
        val b = a.copy(sessionId = "b", projectId = "elsewhere", pinned = false)
        fun page(vararg rows: com.uzairansar.hermex.core.model.SessionSummary) =
            com.uzairansar.hermex.data.repository.SessionRepository.NavigationMetadata("default", rows.toList())
        assertTrue(context.reconcileMetadata("one", context.beginMetadataRefresh(), page(a, b)))
        assertEquals(listOf("a", "b"), context.idsFor("one"))
        context.updateFromList("one", com.uzairansar.hermex.ui.sessions.SessionListUiState(
            sessions = listOf(a, b), selectedProjectId = "project"))
        assertTrue(context.reconcileMetadata("one", context.beginMetadataRefresh(), page(a, b)))
        assertEquals(listOf("a"), context.idsFor("one"))
        val old = context.beginMetadataRefresh()
        context.clear()
        assertFalse(context.reconcileMetadata("one", old, page(a, b)))
        assertTrue(context.idsFor("one").isEmpty())
    }

    @Test fun profileInvalidationMustSuspendNeighborsWithoutDroppingListFilters() {
        val context = ActiveConversationContext()
        val a = com.uzairansar.hermex.core.model.SessionSummary(sessionId = "a", projectId = "project")
        val b = a.copy(sessionId = "b", projectId = "other")
        context.updateFromList("one", com.uzairansar.hermex.ui.sessions.SessionListUiState(
            sessions = listOf(a, b), selectedProjectId = "project"))
        val old = context.beginMetadataRefresh()
        context.suspendEligibility()
        assertTrue(context.idsFor("one").isEmpty())
        val metadata = com.uzairansar.hermex.data.repository.SessionRepository.NavigationMetadata("default", listOf(a, b))
        assertFalse(context.reconcileMetadata("one", old, metadata))
        assertTrue(context.reconcileMetadata("one", context.beginMetadataRefresh(), metadata))
        assertEquals(listOf("a"), context.idsFor("one"))
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
