package com.uzairansar.hermex

import android.app.Application
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.uzairansar.hermex.data.repository.*
import com.uzairansar.hermex.ui.chat.ActiveChatContent
import com.uzairansar.hermex.ui.chat.ChatRoute
import com.uzairansar.hermex.ui.theme.HermexTheme
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import mockwebserver3.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Synthetic server; compiling this test is not evidence of on-device execution. */
@RunWith(AndroidJUnit4::class)
class DurableArchiveInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private val server = MockWebServer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val release = CountDownLatch(1)
    @After fun close() { release.countDown(); scope.cancel(); server.close() }

    @Test fun nextArchiveAndSwipeWorkWhileFirstResponseIsHeldAndAcknowledgementDoesNotNavigate() {
        val archived = CopyOnWriteArrayList<String>()
        val calls = CopyOnWriteArrayList<String>()
        val entered = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                fun row(id: String) = """{"session_id":"$id","profile":"default","source":"webui","title":"Conversation $id","archived":${id in archived},"messages":[{"role":"user","content":"Transcript $id"}]}"""
                if (request.url.encodedPath == "/api/session/archive") {
                    val body = request.body!!.utf8()
                    val id = listOf("a", "b", "c", "d").single { body.contains("\"session_id\":\"$it\"") }
                    calls += id; entered.countDown()
                    check(release.await(60, TimeUnit.SECONDS))
                    archived += id
                    return MockResponse.Builder().body("{\"ok\":true}").build()
                }
                return MockResponse.Builder().body(when (request.url.encodedPath) {
                    "/api/session" -> """{"session":${row(request.url.queryParameter("session_id") ?: "a")}}"""
                    "/api/sessions" -> """{"sessions":[${listOf("a", "b", "c", "d").joinToString(",") { row(it) }}],"archived_count":${archived.size}}"""
                    "/api/profiles" -> """{"active":"default"}"""
                    else -> "{}"
                }).build()
            }
        }
        server.start()
        val app = ApplicationProvider.getApplicationContext<Application>()
        val container = AppContainer(app)
        val identity = ArchiveIdentity(server.url("/").toString(), "synthetic", "default")
        val journal = PreferencesArchiveJournal(app, "archive_test_${System.nanoTime()}")
        val queue = DurableArchiveCoordinator(journal, RepositoryArchiveBackend(
            { container.apiClient(server.url("/")) }, { container.sessionRepository(server.url("/")) },
            Mutex(), { 0L }, { true }), scope) { true }
        val config = android.content.res.Configuration(app.resources.configuration).apply { setLocale(java.util.Locale.FRANCE) }
        val resources = app.createConfigurationContext(config).resources
        var selected = "a"
        var backs = 0
        compose.setContent {
            var selection by remember { mutableStateOf("a") }
            val projection by queue.state.collectAsState()
            val ids = listOf("a", "b", "c", "d").filterNot { it in projection.hidden(identity) }
            CompositionLocalProvider(androidx.compose.ui.platform.LocalResources provides resources,
                androidx.compose.ui.platform.LocalConfiguration provides config) {
                HermexTheme {
                    ActiveChatContent("${identity.server}:$selection") {
                        ChatRoute(
                            sessionId = selection, serverId = identity.server,
                            repository = container.chatRepository(server.url("/")),
                            activeConversationIds = ids,
                            archiveCoordinator = queue, archiveIdentity = { identity.copy(profile = it) },
                            onNavigateConversation = { selection = it; selected = it },
                            onBack = { backs++ }, onOpenWorkspace = {}, onOpenGit = {},
                        )
                    }
                }
            }
        }
        fun awaitTranscript(id: String) {
            compose.waitUntil(20_000) { compose.onAllNodesWithText("Transcript $id").fetchSemanticsNodes().isNotEmpty() }
        }
        fun archive() {
            compose.onNodeWithContentDescription("Session actions").performClick()
            compose.onNodeWithText("Archiver").assertIsEnabled().performClick()
        }
        awaitTranscript("a")
        compose.onNode(hasSetTextAction()).performTextInput("Preserve this draft")
        archive()
        awaitTranscript("b")
        assertTrue(entered.await(10, TimeUnit.SECONDS))
        assertEquals(listOf("a"), calls.toList())
        assertEquals("a", journal.read().single().sessionId)
        archive() // Source a is destroyed; another request must be accepted, not globally locked.
        awaitTranscript("c")
        assertEquals(setOf("a", "b"), queue.state.value.hidden(identity))
        compose.onNodeWithTag("active_navigation").performTouchInput {
            swipe(Offset(width * 0.75f, center.y), Offset(width * 0.25f, center.y), 400)
        }
        awaitTranscript("d")
        assertEquals(listOf("a"), calls.toList())
        release.countDown()
        compose.waitUntil(20_000) { queue.state.value.requests.isEmpty() }
        compose.waitForIdle()
        assertEquals("d", selected)
        assertEquals(0, backs)
        assertEquals(listOf("a", "b"), calls.toList())
        compose.onNodeWithText("Transcript d").assertExists()
        compose.onAllNodesWithTag("chat_transcript").assertCountEquals(1)
        assertTrue(journal.read().isEmpty())
    }
}
