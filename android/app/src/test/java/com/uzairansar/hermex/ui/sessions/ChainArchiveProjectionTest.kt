package com.uzairansar.hermex.ui.sessions

import com.uzairansar.hermex.core.model.SessionSummary
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * One visible row stands for a proven compression chain: the chain id set used by
 * chain-wide archive must contain every proven member, and nothing else.
 */
class ChainArchiveProjectionTest {

    private fun row(
        id: String,
        parent: String? = null,
        archived: Boolean = false,
        root: String? = null,
        tip: Boolean? = null,
        createdAt: Double = 1.0,
    ) = SessionSummary(
        sessionId = id,
        parentSessionId = parent,
        archived = archived,
        lineageRootId = root,
        preCompressionSnapshot = parent != null,
        compressionTipArchived = tip,
        createdAt = createdAt,
        rawSource = "desktop",
    )

    @Test
    fun `archiving the visible old segment hides the whole proven chain from the ordinary view`() {
        val old = row("old", parent = null, root = null, tip = null)
        val tip = row("tip", parent = "old", archived = true, root = "old", tip = true)

        val state = SessionListUiState(sessions = listOf(old, tip))
        assertEquals(listOf("old"), state.visibleSessions.map { it.sessionId })

        val oldArchived = old.copy(archived = true)
        val stateAfter = SessionListUiState(sessions = listOf(oldArchived, tip))
        assertEquals(emptyList<String>(), stateAfter.visibleSessions.map { it.sessionId })

        val archivedView = SessionListUiState(sessions = listOf(oldArchived, tip), showArchived = true)
        assertEquals(listOf("old"), archivedView.visibleSessions.map { it.sessionId })
    }

    @Test
    fun `chain ids for the visible representative cover every proven member and nothing else`() {
        val old = row("old", parent = null, root = null, tip = null)
        val tip = row("tip", parent = "old", archived = true, root = "old", tip = true)
        val stranger = row("stranger", parent = null, root = null, tip = null)

        val ids = listOf(old, tip, stranger).chainIdsFor(old.stableId)
        assertEquals(setOf("old", "tip"), ids.toSet())

        // A lone conversation archives only itself.
        assertEquals(listOf("stranger"), listOf(old, tip, stranger).chainIdsFor(stranger.stableId))
    }
}
