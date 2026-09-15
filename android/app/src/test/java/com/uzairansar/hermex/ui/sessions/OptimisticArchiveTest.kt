package com.uzairansar.hermex.ui.sessions

import com.uzairansar.hermex.core.model.SessionSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Optimistic chain archive: the gesture must update the projected list immediately
 * (row leaves the ordinary view), stay reversible, and cover exactly the proven chain.
 */
class OptimisticArchiveTest {

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
    fun `archive plan hides the visible chain row immediately through the projection`() {
        val old = row("old")
        val tip = row("tip", parent = "old", archived = true, root = "old", tip = true)
        val stranger = row("stranger", createdAt = 2.0)
        val sessions = listOf(old, tip, stranger)

        val plan = OptimisticArchive.plan(sessions, old.stableId)!!

        assertEquals(listOf("old", "tip"), plan.chainIds)
        assertTrue(plan.archive)

        val afterArchive = SessionListUiState(sessions = plan.updated)
        assertEquals(listOf("stranger"), afterArchive.visibleSessions.map { it.sessionId })
        // The archived view still offers the row for restore.
        val archivedView = SessionListUiState(sessions = plan.updated, showArchived = true)
        assertEquals(listOf("old"), archivedView.visibleSessions.map { it.sessionId })
    }

    @Test
    fun `rollback plan restores visibility without touching unrelated rows`() {
        val old = row("old")
        val tip = row("tip", parent = "old", archived = true, root = "old", tip = true)
        val stranger = row("stranger", createdAt = 2.0)
        val plan = OptimisticArchive.plan(listOf(old, tip, stranger), old.stableId)!!

        val rolled = OptimisticArchive.rollback(plan.updated, plan)

        val afterRollback = SessionListUiState(sessions = rolled)
        assertEquals(listOf("stranger", "old"), afterRollback.visibleSessions.map { it.sessionId })
        assertNull(rolled.first { it.sessionId == "old" }.compressionTipArchived)
    }

    @Test
    fun `restore direction clears archive on every chain member and unblocks the archived view`() {
        val old = row("old", archived = true)
        val tip = row("tip", parent = "old", archived = true, root = "old", tip = true)

        val plan = OptimisticArchive.plan(listOf(old, tip), old.stableId)!!

        assertEquals(false, plan.archive)
        val ordinary = SessionListUiState(sessions = plan.updated)
        assertEquals(listOf("old"), ordinary.visibleSessions.map { it.sessionId })
    }

    @Test
    fun `a lone conversation plans only itself`() {
        val lone = row("lone")
        val other = row("other", createdAt = 2.0)

        val plan = OptimisticArchive.plan(listOf(lone, other), lone.stableId)!!

        assertEquals(listOf("lone"), plan.chainIds)
        val after = SessionListUiState(sessions = plan.updated)
        assertEquals(listOf("other"), after.visibleSessions.map { it.sessionId })
    }
}
