package com.uzairansar.hermex

import android.app.Application
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
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
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val server = MockWebServer()
    @After fun close() {
        compose.runOnUiThread { compose.activity.setContent {} }
        server.close()
    }

    @Test fun olderReadingPositionSurvivesReplacementAndSavedStateRecreation() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val id = request.url.queryParameter("session_id") ?: "a"
                val messages = (0 until 80).joinToString(",") {
                    """{"role":"user","content":"Message $id $it : synthetic reading position fixture."}"""
                }
                return MockResponse.Builder().body(if (request.url.encodedPath == "/api/session")
                    """{"session":{"session_id":"$id","title":"Conversation $id","messages":[$messages]}}""" else "{}").build()
            }
        }
        server.start()
        val container = AppContainer(ApplicationProvider.getApplicationContext<Application>())
        var registry = androidx.compose.runtime.saveable.SaveableStateRegistry(null) { true }
        var showContent by mutableStateOf(true)
        compose.runOnUiThread { compose.activity.setContent {
            if (showContent) {
                CompositionLocalProvider(androidx.compose.runtime.saveable.LocalSaveableStateRegistry provides registry) {
                    var selected by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("a") }
                    HermexTheme {
                        ActiveChatContent("${server.url("/")}:$selected") {
                            ChatRoute(sessionId = selected, serverId = server.url("/").toString(),
                                repository = container.chatRepository(server.url("/")), activeConversationIds = listOf("a", "b"),
                                onNavigateConversation = { selected = it }, onBack = {}, onOpenWorkspace = {}, onOpenGit = {})
                        }
                    }
                }
            }
        } }
        fun position(): Float = compose.onNodeWithTag("chat_transcript").fetchSemanticsNode()
            .config[androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange].value()
        fun awaitLoadedTranscript() {
            // The title may already be visible from the provisional cache window.
            compose.waitUntil(20_000) {
                compose.onAllNodesWithTag("chat_transcript").fetchSemanticsNodes().singleOrNull()
                    ?.config?.get(com.uzairansar.hermex.ui.chat.TranscriptLoadComplete) == true
            }
            compose.waitForIdle()
        }
        awaitLoadedTranscript()
        compose.waitUntil(20_000) { position() > 50f }
        repeat(3) {
            compose.onNodeWithTag("chat_transcript").performTouchInput {
                swipeDown(startY = height * 0.35f, endY = height * 0.70f, durationMillis = 450)
            }
        }
        compose.waitForIdle()
        fun anchor() = compose.onNodeWithTag("chat_transcript").fetchSemanticsNode()
            .config[com.uzairansar.hermex.ui.chat.TranscriptReadingAnchor]
        fun visibleMessages() = compose.onAllNodes(hasText("Message a", substring = true), useUnmergedTree = true)
            .fetchSemanticsNodes().filter { it.boundsInRoot.height > 0f }
            .map { it.config[androidx.compose.ui.semantics.SemanticsProperties.Text].toString() to it.boundsInRoot }
        val before = anchor()
        val estimatedBefore = position()
        val visibleBefore = visibleMessages()
        org.junit.Assert.assertTrue("Must read an older message, not the bottom", before.first in 1..75)
        org.junit.Assert.assertTrue("Must have visible message evidence", visibleBefore.isNotEmpty())
        android.util.Log.i("ReadingAnchorProof", "before anchor=$before estimated=${position()} visible=$visibleBefore")
        compose.onNodeWithTag("active_next").performClick()
        compose.waitUntil(20_000) { compose.onAllNodesWithText("Conversation b").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("active_previous").performClick()
        compose.waitUntil(20_000) { compose.onAllNodesWithText("Conversation a").fetchSemanticsNodes().isNotEmpty() }
        awaitLoadedTranscript()
        android.util.Log.i("ReadingAnchorProof", "restored anchor=${anchor()} estimated=${position()} visible=${visibleMessages()}")
        org.junit.Assert.assertEquals(before, anchor())
        org.junit.Assert.assertEquals(estimatedBefore, position(), 0.1f)
        org.junit.Assert.assertEquals(visibleBefore, visibleMessages())
        // Recreate the composition at the SAME call-site key, like an Activity restart.
        // Changing an outer key changes every rememberSaveable key and cannot restore them.
        val saved = compose.runOnIdle { registry.performSave().also { showContent = false } }
        compose.waitForIdle()
        compose.onAllNodesWithTag("chat_transcript").assertCountEquals(0)
        compose.runOnIdle {
            registry = androidx.compose.runtime.saveable.SaveableStateRegistry(saved) { true }
            showContent = true
        }
        compose.waitUntil(20_000) { compose.onAllNodesWithText("Conversation a").fetchSemanticsNodes().isNotEmpty() }
        awaitLoadedTranscript()
        android.util.Log.i("ReadingAnchorProof", "restored anchor=${anchor()} estimated=${position()} visible=${visibleMessages()}")
        org.junit.Assert.assertEquals(before, anchor())
        org.junit.Assert.assertEquals(estimatedBefore, position(), 0.1f)
        org.junit.Assert.assertEquals(visibleBefore, visibleMessages())
        compose.onAllNodesWithTag("chat_transcript").assertCountEquals(1)
    }

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
        compose.runOnUiThread { compose.activity.setContent {
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
        // Start inside the content, not the Android back-gesture edge exclusion.
        compose.onNodeWithTag("chat_transcript").performTouchInput { swipeLeft(startX = width * 0.8f, endX = width * 0.2f) }
        compose.waitUntil(20_000) { compose.onAllNodesWithText("Transcript b").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("chat_transcript").performTouchInput { swipeLeft(startX = width * 0.8f, endX = width * 0.2f) }
        compose.waitUntil(20_000) { compose.onAllNodesWithText("Transcript c").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("active_next").assertIsNotEnabled()
        compose.onNodeWithTag("active_navigation").performTouchInput { swipeUp() }
        compose.onNodeWithText("Transcript c").assertExists()
    }
}
