package com.uzairansar.hermex.ui.chat

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import com.uzairansar.hermex.MainDispatcherRule
import com.uzairansar.hermex.core.network.HermesApiClient
import com.uzairansar.hermex.core.network.SseStreamClient
import com.uzairansar.hermex.data.db.ServerCacheOwnership
import com.uzairansar.hermex.data.repository.ChatRepository
import com.uzairansar.hermex.data.repository.RecordingCacheDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Incremental transcript caching: milestones seen live (tools, reasoning) must land in
 * the local cache before stream completion, so a reopen renders instantly from Room.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class IncrementalCacheTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(UnconfinedTestDispatcher())

    private fun json(body: String): MockResponse = MockResponse.Builder()
        .code(200)
        .setHeader("Content-Type", "application/json")
        .body(body)
        .build()

    private fun sse(events: String): MockResponse = MockResponse.Builder()
        .code(200)
        .addHeader("Content-Type", "text/event-stream")
        .body(events)
        .build()

    @Test
    fun leavingMidStreamPersistsWatchedTranscriptToCache() = runTest {
        val server = MockWebServer()
        val dao = RecordingCacheDao()
        val store = androidx.lifecycle.ViewModelStore()
        var ownedVm: ChatViewModel? = null
        try {
            val initialSession = """{"session":{"session_id":"session-1","messages":[
                {"id":"u1","role":"user","content":"run the audit"},
                {"id":"a1","role":"assistant","content":"working on it"}]}}"""
            val streamBody = "event: token\ndata: {\"text\":\" partial answer\"}\n\n" +
                "event: tool\ndata: {\"name\":\"read_file\"}\n\n" +
                "event: tool_complete\ndata: {\"name\":\"read_file\"}\n\n"
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.url.encodedPath) {
                    "/api/chat/start" -> json("""{"stream_id":"stream-1"}""")
                    "/api/chat/stream" -> sse(streamBody)
                    "/api/models" -> json("""{"models":[]}""")
                    "/api/profiles" -> json("""{"profiles":[]}""")
                    "/api/workspaces" -> json("""{"workspaces":[]}""")
                    "/api/reasoning" -> json("""{"supported_efforts":[]}""")
                    "/api/commands" -> json("""{"commands":[]}""")
                    "/api/skills" -> json("""{"skills":[]}""")
                    "/api/session/yolo" -> json("""{"yolo_enabled":false}""")
                    else -> json(initialSession)
                }
            }
            server.start()
            val client = HermesApiClient(server.url("/"), OkHttpClient())
            val repository = ChatRepository(
                client = client,
                cacheDao = dao,
                cacheOwnership = ServerCacheOwnership(),
                sse = SseStreamClient(server.url("/"), OkHttpClient()) { emptyList() },
            )
            val viewModel = ChatViewModel("session-1", repository)
            ownedVm = viewModel
            store.put("fixture", viewModel)

            withContext(Dispatchers.Default) {
                withTimeout(5_000) { viewModel.state.first { !it.isLoading } }
            }
            assertEquals(1, dao.replacedMessageBatches.size)

            viewModel.updateDraft("run the audit now")
            viewModel.send()
            withContext(Dispatchers.Default) {
                withTimeout(5_000) { viewModel.state.first { it.isStreaming } }
            }

            store.clear()
            viewModel.viewModelScope.coroutineContext[Job]?.join()

            assertTrue(
                "watched transcript must be persisted on screen exit even without stream completion",
                dao.replacedMessageBatches.size > 1,
            )
        } finally {
            ownedVm?.viewModelScope?.coroutineContext?.get(Job)?.join()
            server.close()
        }
    }
}
