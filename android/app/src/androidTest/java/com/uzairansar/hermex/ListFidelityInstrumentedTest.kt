package com.uzairansar.hermex

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.uzairansar.hermex.data.repository.AuthState
import com.uzairansar.hermex.data.secure.ServerAccount
import com.uzairansar.hermex.ui.sessions.SessionListRoute
import com.uzairansar.hermex.ui.sessions.SessionListUiState
import com.uzairansar.hermex.data.preferences.SessionRowDisplaySettings
import com.uzairansar.hermex.ui.theme.HermexTheme
import kotlinx.coroutines.runBlocking
import mockwebserver3.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(AndroidJUnit4::class)
class ListFidelityInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private val server = MockWebServer()
    @After fun close() { server.close() }

    // Cold repository -> viewmodel -> UI path with the three real categories mixed:
    // a delegation review child, a compression segment whose hidden tip is archived,
    // and an independent human conversation sharing the generic title.
    @Test fun coldListFidelityHidesProofRowsAndKeepsHumanConversations() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val body = when(request.url.encodedPath) {
                    "/api/projects" -> """{"projects":[]}"""
                    "/api/profiles" -> """{"profiles":[],"single_profile_mode":true}"""
                    "/api/sessions" -> """{"sessions":[
                        {"session_id":"parent","title":"Workshop discussion","created_at":50,"raw_source":"desktop"},
                        {"session_id":"review","title":"Desktop Session","created_at":100.5,"raw_source":"desktop","session_source":"other","parent_session_id":"parent","relationship_type":"child_session"},
                        {"session_id":"segment","title":"Desktop Session","created_at":200,"raw_source":"desktop","session_source":"other","parent_session_id":null,"relationship_type":null,"end_reason":"compression"},
                        {"session_id":"human","title":"Desktop Session","created_at":300,"raw_source":"desktop","session_source":"other"}
                    ]}"""
                    "/api/session/lineage/report" -> when(request.url.queryParameter("session_id")) {
                        "segment" -> """{"mutation":false,"found":true,"session_id":"segment","manual_review":false,"total_segments":1,
                            "segments":[{"session_id":"segment","source":"desktop","end_reason":"compression","active":false,"updated_at":250.2}],
                            "children":[{"session_id":"tip","source":"desktop","role":"child_session","started_at":250.0}]}"""
                        else -> """{"mutation":false,"found":false}"""
                    }
                    "/api/session" -> when(request.url.queryParameter("session_id")) {
                        "review" -> """{"session":{"session_id":"review","created_at":100.5,"messages":[{"role":"user","content":"Check fixture","timestamp":100.4}]}}"""
                        "parent" -> """{"session":{"session_id":"parent","messages":[{"role":"assistant","timestamp":100,"tool_calls":[{"id":"call","function":{"name":"delegate_task","arguments":{"tasks":[{"goal":"Check fixture"}]}}}]}]}}"""
                        "tip" -> """{"session":{"session_id":"tip","archived":true,"raw_source":"desktop"}}"""
                        else -> "{}"
                    }
                    else -> "{}"
                }
                return MockResponse.Builder().code(200).body(body).build()
            }
        }
        server.start()
        val app = ApplicationProvider.getApplicationContext<Application>()
        val container = AppContainer(app)
        val url = server.url("/")
        val account = ServerAccount(id=url.toString(),urlString=url.toString(),displayName="Fixture",initials="FX")
        compose.setContent { HermexTheme {
            SessionListRoute(authState=AuthState.LoggedIn(url,account),container=container,
                onOpenChat={},onOpenVoiceChat={},onOpenSharedDraft={},onOpenPanels={},onOpenKanban={},onOpenSettings={},onNeedsOnboarding={})
        } }
        compose.waitUntil(30_000) { compose.onAllNodesWithTag("session_row_human").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("session_row_parent").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("session_row_human").assertIsDisplayed()
        assertTrue(compose.onAllNodesWithTag("session_row_review").fetchSemanticsNodes().isEmpty())
        assertTrue(compose.onAllNodesWithTag("session_row_segment").fetchSemanticsNodes().isEmpty())
        runBlocking {
            val cached = container.sessionRepository(url).loadCachedSessions()!!.sessions
            assertEquals(setOf("parent","human"),SessionListUiState(sessions=cached).visibleSessions.map { it.sessionId }.toSet())
            val revealed=SessionListUiState(sessions=cached,sessionRowDisplaySettings=SessionRowDisplaySettings(showSubagentSessions=true))
            assertEquals(setOf("parent","review","human"),revealed.visibleSessions.map { it.sessionId }.toSet())
        }
        val bitmap = compose.onNodeWithTag("session_list").captureToImage().asAndroidBitmap()
        File(app.getExternalFilesDir(null),"list-fidelity.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
        bitmap.recycle()
    }
}
