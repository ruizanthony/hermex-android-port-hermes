package com.uzairansar.hermex

import android.app.Application
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.uzairansar.hermex.ui.chat.ChatRoute
import com.uzairansar.hermex.ui.chat.ActiveChatContent
import com.uzairansar.hermex.ui.theme.HermexTheme
import mockwebserver3.*
import org.junit.*
import org.junit.runner.RunWith

/** Synthetic server only; run on device separately from assembleDebugAndroidTest. */
@RunWith(AndroidJUnit4::class)
class ActiveConversationNavigationInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private val server = MockWebServer()
    @After fun close() { server.close() }

    @Test fun buttonsReplaceTheOnlyChatAndRestoreItsDraft() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val id = request.url.queryParameter("session_id") ?: "a"
                return MockResponse.Builder().code(200).body(when (request.url.encodedPath) {
                    "/api/session" -> """{"session":{"session_id":"$id","title":"Conversation $id","messages":[{"role":"user","content":"Transcript $id"}]}}"""
                    else -> "{}"
                }).build()
            }
        }
        server.start()
        val app = ApplicationProvider.getApplicationContext<Application>()
        val container = AppContainer(app)
        compose.setContent {
            var selected by remember { mutableStateOf("a") }
            HermexTheme {
                ActiveChatContent("${server.url("/")}:$selected") {
                    ChatRoute(
                        sessionId = selected,
                        serverId = server.url("/").toString(),
                        repository = container.chatRepository(server.url("/")),
                        activeConversationIds = listOf("a", "b", "c"),
                        onNavigateConversation = { selected = it },
                        onBack = {}, onOpenWorkspace = {}, onOpenGit = {},
                    )
                }
            }
        }
        compose.waitUntil(20_000) { compose.onAllNodesWithText("Transcript a").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("active_previous").assertIsNotEnabled()
        compose.onNode(hasSetTextAction()).performTextInput("Draft to preserve")
        compose.onNodeWithTag("active_next").performClick()
        compose.waitUntil(20_000) { compose.onAllNodesWithText("Transcript b").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Transcript a").assertDoesNotExist()
        compose.onAllNodesWithTag("chat_transcript").assertCountEquals(1)
        compose.onNodeWithTag("active_previous").performClick()
        compose.waitUntil(20_000) { compose.onAllNodesWithText("Draft to preserve").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("active_navigation").performTouchInput { swipeLeft() }
        compose.waitUntil(20_000) { compose.onAllNodesWithText("Transcript b").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("active_navigation").performTouchInput { swipeLeft() }
        compose.waitUntil(20_000) { compose.onAllNodesWithText("Transcript c").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("active_next").assertIsNotEnabled()
        compose.onNodeWithTag("active_navigation").performTouchInput { swipeUp() }
        compose.onNodeWithText("Transcript c").assertExists()
    }
}
