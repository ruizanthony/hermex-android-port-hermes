package com.uzairansar.hermex.ui.sessions

import com.uzairansar.hermex.core.model.SessionSummary
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Superseded lineage segments: default-titled Desktop rows whose conversation
 * lives on under a titled continuation, and untitled one-shot delegation leaves.
 * Hidden only on in-payload linkage proof; active/pinned/pending rows and solo
 * default-titled rows stay visible.
 */
class SupersededSegmentFilterTest {
    private fun row(id: String, title: String?, parent: String? = null, source: String = "desktop") = SessionSummary(
        sessionId = id, title = title, parentSessionId = parent, rawSource = source, sourceTag = source,
    )

    @Test
    fun untitledChainSegmentsHideBehindTitledTip() {
        val state = SessionListUiState(
            sessions = listOf(
                row("root", "Desktop Session"),
                row("mid", "Desktop Session", parent = "root"),
                row("tip", "Mise en service des tablettes", parent = "mid"),
            ),
        )
        assertEquals(listOf("tip"), state.visibleSessions.map { it.sessionId })
    }

    @Test
    fun untitledDelegationLeafHidesBehindVisibleParent() {
        val state = SessionListUiState(
            sessions = listOf(
                row("parent", "Ciel watcher: articles"),
                row("leaf", "Desktop Session", parent = "parent"),
            ),
        )
        assertEquals(listOf("parent"), state.visibleSessions.map { it.sessionId })
    }

    @Test
    fun soloUntitledRowStaysVisible() {
        val state = SessionListUiState(sessions = listOf(row("solo", "Desktop Session")))
        assertEquals(listOf("solo"), state.visibleSessions.map { it.sessionId })
    }

    @Test
    fun activeUntitledRowStaysVisible() {
        val state = SessionListUiState(
            sessions = listOf(
                row("parent", "Mission"),
                row("live", "Desktop Session", parent = "parent").copy(isStreaming = true),
                row("pinned", "Desktop Session", parent = "parent").copy(pinned = true),
            ),
        )
        assertEquals(setOf("parent", "live", "pinned"), state.visibleSessions.mapNotNull { it.sessionId }.toSet())
    }

    @Test
    fun titledMiddleSegmentsStayVisible() {
        val state = SessionListUiState(
            sessions = listOf(
                row("root", "Nettoyer articles"),
                row("mid", "Nettoyer articles", parent = "root"),
                row("leaf", "Desktop Session", parent = "mid"),
            ),
        )
        assertEquals(setOf("root", "mid"), state.visibleSessions.mapNotNull { it.sessionId }.toSet())
    }

    @Test
    fun untitledRowsWithoutDesktopSourceStayVisible() {
        // Only the Desktop fallback shape is hidden; a WebUI "Untitled" row keeps
        // the Hermex empty-placeholder semantics.
        val state = SessionListUiState(
            sessions = listOf(
                row("parent", "Mission", source = "webui"),
                row("child", "Untitled", parent = "parent", source = "webui"),
            ),
        )
        assertEquals(setOf("parent", "child"), state.visibleSessions.mapNotNull { it.sessionId }.toSet())
    }
}
