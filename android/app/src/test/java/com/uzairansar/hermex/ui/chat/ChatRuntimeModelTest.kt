package com.uzairansar.hermex.ui.chat

import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import com.uzairansar.hermex.MainDispatcherRule
import com.uzairansar.hermex.core.network.*
import com.uzairansar.hermex.data.db.ServerCacheOwnership
import com.uzairansar.hermex.data.repository.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import mockwebserver3.*
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatRuntimeModelTest {
    @get:Rule val main = MainDispatcherRule(UnconfinedTestDispatcher())

    @Test fun serverTerminalAttributionDoesNotChangeRequestedSelection() = runTest {
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.url.encodedPath == "/api/chat/stream") {
                    return MockResponse.Builder().code(200).addHeader("Content-Type", "text/event-stream").body(
                        "event: warning\ndata: {\"type\":\"fallback\"}\n\n" +
                        "event: runtime_model\ndata: {\"session_id\":\"s\",\"stream_id\":\"turn\",\"model\":\"backup\",\"provider\":\"second\",\"fallback_active\":true,\"phase\":\"observed_output\"}\n\n" +
                        "event: token\ndata: {\"text\":\"Generic answer\"}\n\n" +
                        "event: done\ndata: {\"session_id\":\"s\",\"usage\":{\"used_model\":\"backup\",\"used_provider\":\"second\",\"requested_model\":\"primary\",\"requested_provider\":\"first\"}}\n\n" +
                        "event: stream_end\ndata: {}\n\n",
                    ).build()
                }
                val body = if (request.url.encodedPath == "/api/session")
                    """{"session":{"session_id":"s","model":"primary","model_provider":"first","active_stream_id":"turn","messages":[{"role":"user","content":"Question"}]}}"""
                    else "{}"
                return MockResponse.Builder().code(200).body(body).build()
            }
        }
        server.start()
        val http = OkHttpClient()
        val repository = ChatRepository(HermesApiClient(server.url("/"), http), RecordingCacheDao(), ServerCacheOwnership(), SseStreamClient(server.url("/"), http) { emptyList() })
        val vm = ChatViewModel("s", repository)
        val store = ViewModelStore().also { it.put("fixture", vm) }
        try {
            withContext(Dispatchers.Default) {
                withTimeout(15000) { vm.state.first { it.responseCompletionTrigger > 0 } }
            }
            val state = vm.state.value
            assertEquals("primary", state.selectedModel?.id)
            assertEquals("primary", state.sessionModel)
            assertFalse(state.isStreaming)
            val answer = state.messages.last { it.role == "assistant" }
            assertEquals("backup", answer.usedModel)
            assertEquals("second", answer.usedProvider)
            assertEquals("primary", answer.requestedModel)
        } finally {
            store.clear()
            vm.viewModelScope.coroutineContext[Job]?.join()
            server.close()
        }
    }

    @Test fun noCurrentAnswerMeansTerminalMetadataCannotRewriteHistory() {
        val old = com.uzairansar.hermex.core.model.ChatMessage(role="assistant", content="Old", usedModel="old")
        val messages = listOf(old, com.uzairansar.hermex.core.model.ChatMessage(role="user", content="New"))
        val usage = com.uzairansar.hermex.core.model.ContextWindowSnapshot(usedModel="backup")
        assertEquals(messages, messages.withRuntimeAttribution(usage))
    }
}
