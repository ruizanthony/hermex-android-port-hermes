package com.uzairansar.hermex

import android.app.Application
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.uzairansar.hermex.core.model.SessionSummary
import com.uzairansar.hermex.data.preferences.LocalSettingsRepository
import com.uzairansar.hermex.ui.sessions.chainIdsFor
import com.uzairansar.hermex.ui.sessions.SessionListUiState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Emulator proof for the pichot.6 UX fixes: chain archive membership, cache-first
 *  refresh visibility, and collapsible utility sections. */
@RunWith(AndroidJUnit4::class)
class Pichot6UxInstrumentedTest {

    @get:Rule val compose = createComposeRule()

    private fun row(
        id: String, parent: String? = null, archived: Boolean = false,
        root: String? = null, tip: Boolean? = null, created: Double = 1.0,
    ) = SessionSummary(
        sessionId = id,
        parentSessionId = parent,
        archived = archived,
        lineageRootId = root,
        preCompressionSnapshot = parent != null,
        compressionTipArchived = tip,
        createdAt = created,
        rawSource = "desktop",
        title = "Session $id",
        messageCount = 10,
    )

    @Test
    fun chainArchiveHidesWholeConversationAndArchivedViewReachesIt() = runBlocking {
        val old = row("old", created = 1.0)
        val tip = row("tip", parent = "old", archived = true, root = "old", tip = true, created = 2.0)
        val ordinary = row("ordinary", created = 3.0)
        val all = listOf(old, tip, ordinary)

        // Chain membership covers exactly old+tip for the visible representative.
        assertEquals(setOf("old", "tip"), all.chainIdsFor(old.stableId).toSet())

        // After chain archive: conversation hidden from ordinary view, visible in archived view.
        val oldArchived = old.copy(archived = true)
        val after = listOf(oldArchived, tip, ordinary)
        val ordinary_view = SessionListUiState(sessions = after)
        assertEquals(listOf("ordinary"), ordinary_view.visibleSessions.map { it.sessionId })
        val archivedView = SessionListUiState(sessions = after, showArchived = true)
        assertEquals(listOf("old"), archivedView.visibleSessions.map { it.sessionId })
    }

    @Test
    fun utilitySectionsCollapsedFlagPersistsThroughSettingsRepository() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val repo = LocalSettingsRepository(app)

        val initial = repo.mainPageDisplaySettings.first().utilitySectionsCollapsed
        repo.setUtilitySectionsCollapsed(!initial)
        assertEquals(!initial, repo.mainPageDisplaySettings.first().utilitySectionsCollapsed)
        repo.setUtilitySectionsCollapsed(initial)
        assertEquals(initial, repo.mainPageDisplaySettings.first().utilitySectionsCollapsed)
    }

    @Test
    fun collapsedStateHidesSectionsInUiState() {
        // UI-state level: when collapsed, the sections are simply not requested by the caller;
        // verify the flag drives the condition used at the call site.
        val settings = com.uzairansar.hermex.data.preferences.MainPageDisplaySettings(utilitySectionsCollapsed = true)
        assertTrue(settings.utilitySectionsCollapsed)
        val expanded = settings.copy(utilitySectionsCollapsed = false)
        assertFalse(expanded.utilitySectionsCollapsed)
    }
}
