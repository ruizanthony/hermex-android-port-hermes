package com.uzairansar.hermex.ui.chat

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
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

@OptIn(ExperimentalCoroutinesApi::class)
class SealedContinuationTest {
    @get:Rule val main = MainDispatcherRule(UnconfinedTestDispatcher())

    @Test fun parsesOnlySessionRotatedRefusals() {
        val rotated = ApiError.Http(409, """{"error":"This session was compressed.","code":"session_rotated","continuation_session_id":" tip "}""")
        assertEquals("tip", SealedContinuation.rotation(rotated)?.continuationSessionId)
        val noTip = ApiError.Http(409, """{"code":"session_rotated","continuation_session_id":null}""")
        assertNotNull(SealedContinuation.rotation(noTip))
        assertNull(SealedContinuation.rotation(noTip)?.continuationSessionId)
        assertNull(SealedContinuation.rotation(ApiError.Http(409, """{"code":"session_profile_mismatch"}""")))
        assertNull(SealedContinuation.rotation(ApiError.Http(500, """{"code":"session_rotated"}""")))
        assertNull(SealedContinuation.rotation(ApiError.Http(409, "not json")))
        assertNull(SealedContinuation.rotation(IllegalStateException("x")))
        assertNull(SealedContinuation.target("s", "s"))
        assertNull(SealedContinuation.target("  ", "s"))
        assertEquals("tip", SealedContinuation.target(" tip ", "s"))
    }

    /** Segment compressed while displayed: the idle refresh follows the tip and keeps the draft. */
    @Test fun idleRefreshFollowsContinuationDiscoveredAfterOpening() = withServer { sealed, _, vm ->
        vm.updateDraft("keep this")
        sealed.set(true)
        vm.refreshVisibleConversation()
        assertEquals("tip", vm.state.value.openSessionId)
        assertEquals("tip", vm.state.value.sealedContinuationId)
        assertEquals("keep this", vm.state.value.draft)
    }

    /** Explicit historical access: an already sealed segment stays readable, no automatic redirect. */
    @Test fun alreadySealedSegmentIsNotRedirectedButSendOpensContinuation() = withServer(initiallySealed = true) { _, posts, vm ->
        assertEquals("tip", vm.state.value.sealedContinuationId)
        vm.refreshVisibleConversation()
        assertNull(vm.state.value.openSessionId)
        vm.updateDraft("question")
        vm.send()
        assertEquals("tip", vm.state.value.openSessionId)
        assertEquals("question", vm.state.value.draft)
        assertTrue("no doomed POST to a sealed segment", posts.isEmpty())
    }

    /** Race: sealed between the last read and the send. The 409 restores the draft and opens the tip. */
    @Test fun rotatedSendRestoresDraftAndOpensContinuation() = withServer { sealed, posts, vm ->
        sealed.set(true)
        vm.updateDraft("question")
        vm.send()
        withContext(Dispatchers.Default) { withTimeout(5000) { vm.state.first { it.openSessionId != null } } }
        assertEquals(1, posts.size)
        assertEquals("tip", vm.state.value.openSessionId)
        assertEquals("question", vm.state.value.draft)
        assertNull(vm.state.value.error)
        assertFalse(vm.state.value.isStreaming)
        assertTrue(vm.state.value.messages.none { it.id?.startsWith("optimistic-") == true })
    }

    private fun withServer(
        initiallySealed: Boolean = false,
        body: suspend (AtomicBoolean, MutableList<String>, ChatViewModel) -> Unit,
    ) = runTest {
        val sealed = AtomicBoolean(initiallySealed)
        val posts = CopyOnWriteArrayList<String>()
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.url.encodedPath == "/api/chat/start") {
                    posts += request.url.encodedPath
                    return MockResponse.Builder().code(409).body(
                        """{"error":"This session was compressed. Open its continuation before sending.","code":"session_rotated","continuation_session_id":"tip"}""",
                    ).build()
                }
                val continuation = if (sealed.get()) ",\"continuation_session_id\":\"tip\"" else ""
                val json = when (request.url.encodedPath) {
                    "/api/session" -> """{"session":{"session_id":"s"$continuation,"messages":[{"id":"m","role":"assistant","content":"old"}]}}"""
                    else -> "{}"
                }
                return MockResponse.Builder().code(200).body(json).build()
            }
        }
        server.start()
        val http = OkHttpClient()
        val vm = ChatViewModel("s", ChatRepository(HermesApiClient(server.url("/"), http), RecordingCacheDao(), ServerCacheOwnership(), SseStreamClient(server.url("/"), http) { emptyList() }))
        val store = androidx.lifecycle.ViewModelStore().also { it.put("fixture", vm) }
        try {
            withContext(Dispatchers.Default) { withTimeout(5000) { vm.state.first { !it.isLoading } } }
            body(sealed, posts, vm)
        } finally { store.clear(); vm.viewModelScope.coroutineContext[Job]?.join(); server.close() }
    }
}
