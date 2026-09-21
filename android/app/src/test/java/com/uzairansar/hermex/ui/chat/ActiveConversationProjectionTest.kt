package com.uzairansar.hermex.ui.chat

import com.uzairansar.hermex.core.model.SessionSummary
import com.uzairansar.hermex.ui.sessions.SessionListUiState
import org.junit.Assert.*
import org.junit.Test

class ActiveConversationProjectionTest {
    @Test fun projectionUsesVisibleOrderAndExcludesArchivedView() {
        val state = SessionListUiState(sessions = listOf(
            SessionSummary(sessionId = "a", title = "A"),
            SessionSummary(sessionId = "pin", title = "B", pinned = true),
            SessionSummary(sessionId = "archived", title = "C", archived = true),
        ))
        assertEquals(listOf("pin", "a"), state.activeConversationIds)
        assertEquals(emptyList<String>(), state.copy(showArchived = true).activeConversationIds)
        assertEquals(listOf("a"), state.copy(searchQuery = "A").activeConversationIds)
        assertEquals(emptyList<String>(), state.copy(isSwitchingProfile = true).activeConversationIds)
    }

    @Test fun projectAndHiddenSegmentFiltersAreNotReimplementedByNavigation() {
        val state = SessionListUiState(selectedProjectId = "project", sessions = listOf(
            SessionSummary(sessionId = "root", title = "Desktop Session", rawSource = "desktop", projectId = "project"),
            SessionSummary(sessionId = "tip", title = "Work", parentSessionId = "root", rawSource = "desktop", projectId = "project"),
            SessionSummary(sessionId = "outside", title = "Elsewhere", projectId = "other"),
        ))
        assertEquals(listOf("tip"), state.activeConversationIds)
        assertEquals(state.visibleSessions.mapNotNull { it.sessionId }, state.activeConversationIds)
        assertTrue(state.copy(showCompressionSegments = true).activeConversationIds.contains("root"))
    }
}
