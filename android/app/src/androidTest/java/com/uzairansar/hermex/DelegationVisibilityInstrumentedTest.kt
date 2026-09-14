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
class DelegationVisibilityInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private val server = MockWebServer()
    @After fun close() { server.close() }
    @Test fun hidesOnlyProvenDelegationAndRetainsCacheAndReveal() {
        val methods = CopyOnWriteArrayList<String>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                methods.add(request.method)
                val body = when(request.url.encodedPath) {
                    "/api/projects" -> """{"projects":[]}"""
                    "/api/profiles" -> """{"profiles":[],"single_profile_mode":true}"""
                    "/api/sessions" -> """{"sessions":[
                        {"session_id":"parent","title":"Workshop discussion","created_at":50,"raw_source":"desktop"},
                        {"session_id":"child","title":"Desktop Session","created_at":100.5,"raw_source":"desktop","parent_session_id":"parent","relationship_type":"child_session"},
                        {"session_id":"manual","title":"Desktop Session","created_at":90,"parent_session_id":"parent","relationship_type":"fork","raw_source":"desktop"}
                    ]}"""
                    "/api/session" -> when(request.url.queryParameter("session_id")) {
                        "child" -> """{"session":{"session_id":"child","created_at":100.5,"messages":[{"role":"user","content":"Check fixture","timestamp":100.4}]}}"""
                        "parent" -> """{"session":{"session_id":"parent","messages":[{"role":"assistant","timestamp":100,"tool_calls":[{"id":"call","function":{"name":"delegate_task","arguments":{"tasks":[{"goal":"Check fixture"}]}}}]}]}}"""
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
        compose.waitUntil(30_000) { compose.onAllNodesWithTag("session_row_parent").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("session_row_manual").performScrollTo().assertIsDisplayed()
        assertTrue(compose.onAllNodesWithTag("session_row_child").fetchSemanticsNodes().isEmpty())
        runBlocking {
            val cached = container.sessionRepository(url).loadCachedSessions()!!.sessions
            assertEquals(3,cached.size)
            assertEquals(2,SessionListUiState(sessions=cached).visibleSessions.size)
            assertEquals(3,SessionListUiState(sessions=cached,sessionRowDisplaySettings=SessionRowDisplaySettings(showSubagentSessions=true)).visibleSessions.size)
        }
        val bitmap = compose.onNodeWithTag("session_list").captureToImage().asAndroidBitmap()
        File(app.getExternalFilesDir(null),"subagents-hidden.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
        bitmap.recycle()
        assertTrue(methods.all { it=="GET" })
    }
}
