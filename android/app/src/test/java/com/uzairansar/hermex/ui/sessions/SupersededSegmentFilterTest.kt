package com.uzairansar.hermex.ui.sessions

import com.uzairansar.hermex.core.model.SessionSummary
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Superseded lineage segments: default-titled Desktop rows whose conversation
 * lives on under a titled continuation, and untitled one-shot delegation leaves
 * behind a TITLED parent. Hidden only on in-payload linkage proof; active rows,
 * solo default-titled rows, untitled chains without a titled member, and all
 * rows reached through search or the compression-segment disclosure stay
 * visible.
 */
class SupersededSegmentFilterTest {
    @Test(timeout = 5000) fun deepHistoryDoesNotOverflowTheUiStack() {
        val rows = (0..8000).map { i -> row("s$i", if (i == 8000) "Visible tip" else "Desktop Session", if (i == 0) null else "s${i-1}") }
        assertEquals(listOf("s8000"), rows.filterNotSupersededSegments().map { it.sessionId })
    }

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
    fun untitledDelegationLeafHidesBehindTitledParent() {
        val state = SessionListUiState(
            sessions = listOf(
                row("parent", "Ciel watcher: articles"),
                row("leaf", "Desktop Session", parent = "parent"),
            ),
        )
        assertEquals(listOf("parent"), state.visibleSessions.map { it.sessionId })
    }

    @Test
    fun untitledLeafBehindUntitledParentStaysVisible() {
        // Plain parenthood is not proof: an untitled parent does not prove the
        // leaf was replaced. The ambiguous tip stays reachable.
        val state = SessionListUiState(
            sessions = listOf(
                row("root", "Desktop Session"),
                row("tip", "Desktop Session", parent = "root"),
            ),
        )
        assertEquals(setOf("root", "tip"), state.visibleSessions.mapNotNull { it.sessionId }.toSet())
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
    fun nullTitleOutsideDesktopSourceStaysVisible() {
        val state = SessionListUiState(
            sessions = listOf(
                row("parent", "Mission", source = "webui"),
                // Null title but WebUI source: not the Desktop fallback shape.
                row("child", null, parent = "parent", source = "webui"),
            ),
        )
        assertEquals(setOf("parent", "child"), state.visibleSessions.mapNotNull { it.sessionId }.toSet())
    }

    @Test
    fun nullTitleDesktopLeafHidesBehindTitledParent() {
        val state = SessionListUiState(
            sessions = listOf(
                row("parent", "Ciel watcher: articles"),
                row("leaf", null, parent = "parent"),
            ),
        )
        assertEquals(setOf("parent"), state.visibleSessions.mapNotNull { it.sessionId }.toSet())
    }

    @Test
    fun searchReachesSupersededSegments() {
        val sessions = listOf(
            row("parent", "Ciel watcher: articles"),
            row("leaf", "Desktop Session", parent = "parent"),
        )
        val browsing = SessionListUiState(sessions = sessions)
        assertEquals(setOf("parent"), browsing.visibleSessions.mapNotNull { it.sessionId }.toSet())
        // Any non-blank query bypasses the superseded filter.
        val searching = SessionListUiState(sessions = sessions, searchQuery = "desktop")
        assertEquals(setOf("leaf"), searching.visibleSessions.mapNotNull { it.sessionId }.toSet())
    }

    @Test
    fun compressionDisclosureShowsSupersededSegments() {
        val sessions = listOf(
            row("parent", "Ciel watcher: articles"),
            row("leaf", "Desktop Session", parent = "parent"),
        )
        val disclosed = SessionListUiState(sessions = sessions, showCompressionSegments = true)
        assertEquals(setOf("parent", "leaf"), disclosed.visibleSessions.mapNotNull { it.sessionId }.toSet())
    }

    @Test
    fun parentChildCycleTerminatesAndKeepsTitledRows() {
        // Defensive: a malformed A<->B parent cycle must not hide a titled row
        // and must terminate.
        val state = SessionListUiState(
            sessions = listOf(
                row("a", "Desktop Session", parent = "b"),
                row("b", "Desktop Session", parent = "a"),
                row("t", "Titre réel", parent = "a"),
            ),
        )
        assertEquals(setOf("t"), state.visibleSessions.mapNotNull { it.sessionId }.toSet())
    }

    @Test
    fun untitledLeafBehindProvenSupersededParentHides() {
        // The parent is itself proven superseded (titled sibling exists): the
        // leaf is part of a dead branch and hides. Plain parenthood alone never
        // hides anything — the proof comes from the parent's titled descendant.
        val state = SessionListUiState(
            sessions = listOf(
                row("dead", "Desktop Session"),
                row("leaf", "Desktop Session", parent = "dead"),
                row("alive", "Ciel watcher: articles", parent = "dead"),
            ),
        )
        assertEquals(setOf("alive"), state.visibleSessions.mapNotNull { it.sessionId }.toSet())
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
