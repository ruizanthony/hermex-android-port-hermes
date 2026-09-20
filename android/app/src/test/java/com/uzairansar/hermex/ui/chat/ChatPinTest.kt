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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
class ChatPinTest {
    @get:Rule val main = MainDispatcherRule(UnconfinedTestDispatcher())

    @Test fun loadedConversationExposesServerPinState() = exercise(initial = true)
    @Test fun pinAndUnpinPreserveDraftAndTranscript() = exercise(toggle = true)
    @Test fun serverRefusalKeepsOldPinAndShowsError() = exercise(toggle = true, refuse = true)
    @Test fun negativeAcknowledgementKeepsOldStateAndDraft() = exercise(toggle = true, negativeAcknowledgement = true)
    @Test fun leavingConversationCancelsPendingPin() = exercise(toggle = true, leave = true)
    @Test fun concurrentTapSendsOneMutation() = exercise(toggle = true, duplicate = true)
    @Test fun missingSessionIdentityCannotBePinned() = exercise(toggle = true, missingId = true)

    @Test fun staleRefreshCannotUndoSuccessfulPin() = runTest {
        val captured = CountDownLatch(1)
        val release = CountDownLatch(1)
        val reads = AtomicInteger()
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.url.encodedPath == "/api/session/pin")
                    return MockResponse.Builder().code(200).body("""{"ok":true,"session":{"session_id":"s","pinned":true}}""").build()
                if (request.url.encodedPath == "/api/session") {
                    if (reads.incrementAndGet() > 1) {
                        captured.countDown()
                        check(release.await(10, TimeUnit.SECONDS))
                    }
                    return MockResponse.Builder().code(200).body("""{"session":{"session_id":"s","pinned":false,"messages":[]}}""").build()
                }
                return MockResponse.Builder().code(200).body("{}").build()
            }
        }
        server.start()
        val http = OkHttpClient()
        val vm = ChatViewModel("s", ChatRepository(HermesApiClient(server.url("/"), http), RecordingCacheDao(), ServerCacheOwnership(), SseStreamClient(server.url("/"), http) { emptyList() }))
        val store = ViewModelStore().also { it.put("fixture", vm) }
        try {
            withContext(Dispatchers.Default) { withTimeout(5000) { vm.state.first { !it.isLoading } } }
            val refresh = launch(UnconfinedTestDispatcher(testScheduler)) { vm.refreshVisibleConversation() }
            withContext(Dispatchers.IO) { assertTrue(captured.await(5, TimeUnit.SECONDS)) }
            vm.togglePin()
            withContext(Dispatchers.Default) { withTimeout(5000) { vm.state.first { !it.isPinning } } }
            assertTrue(vm.state.value.isPinned)
            release.countDown()
            refresh.join()
            assertTrue("Old GET must not undo a newer successful pin", vm.state.value.isPinned)
        } finally { release.countDown(); store.clear(); vm.viewModelScope.coroutineContext[Job]?.join(); server.close() }
    }

    private fun exercise(initial: Boolean = false, toggle: Boolean = false, refuse: Boolean = false,
                         duplicate: Boolean = false, missingId: Boolean = false, negativeAcknowledgement: Boolean = false, leave: Boolean = false) = runTest {
        val pinned = AtomicBoolean(initial)
        val mutations = AtomicInteger()
        val requested = CountDownLatch(1)
        val release = CountDownLatch(if (duplicate || leave) 1 else 0)
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.url.encodedPath == "/api/session/pin") {
                    mutations.incrementAndGet()
                    val body = request.body!!.utf8()
                    assertTrue(body.contains("\"session_id\":\"s\""))
                    requested.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                    if (refuse) return MockResponse.Builder().code(400).body("""{"error":"Pin rejected"}""").build()
                    if (negativeAcknowledgement) return MockResponse.Builder().code(200).body("""{"ok":false,"error":"Pin rejected"}""").build()
                    pinned.set(body.contains("\"pinned\":true"))
                    return MockResponse.Builder().code(200).body("""{"ok":true,"session":{"session_id":"s","pinned":${pinned.get()}}}""").build()
                }
                val identity = if (missingId) "" else "\"session_id\":\"s\","
                return MockResponse.Builder().code(200).body(
                    if (request.url.encodedPath == "/api/session")
                        """{"session":{$identity"pinned":${pinned.get()},"messages":[{"role":"assistant","content":"Saved message"}]}}"""
                    else "{}"
                ).build()
            }
        }
        server.start()
        val http = OkHttpClient()
        val vm = ChatViewModel("s", ChatRepository(HermesApiClient(server.url("/"), http), RecordingCacheDao(), ServerCacheOwnership(), SseStreamClient(server.url("/"), http) { emptyList() }))
        val store = ViewModelStore().also { it.put("fixture", vm) }
        try {
            withContext(Dispatchers.Default) { withTimeout(5000) { vm.state.first { !it.isLoading } } }
            assertEquals(initial, vm.state.value.isPinned)
            assertEquals(!missingId, vm.state.value.canPinConversation)
            if (toggle) {
                vm.updateDraft("Unsent draft")
                val messages = vm.state.value.messages
                val action = vm.javaClass.methods.firstOrNull { it.name == "togglePin" }
                assertNotNull("The chat must provide a pin action", action)
                action!!.invoke(vm)
                if (missingId) {
                    assertEquals(0, mutations.get())
                    return@runTest
                }
                if (duplicate || leave) {
                    withContext(Dispatchers.IO) { assertTrue(requested.await(5, TimeUnit.SECONDS)) }
                    if (leave) {
                        store.clear()
                        release.countDown()
                        vm.viewModelScope.coroutineContext[Job]?.join()
                        assertEquals(initial, vm.state.value.isPinned)
                        return@runTest
                    }
                    action.invoke(vm)
                    release.countDown()
                }
                withContext(Dispatchers.Default) { withTimeout(5000) { vm.state.first { !it.isPinning } } }
                assertEquals(1, mutations.get())
                assertEquals(!(refuse || negativeAcknowledgement), vm.state.value.isPinned)
                if (refuse || negativeAcknowledgement) assertTrue(vm.state.value.error!!.contains("Pin rejected"))
                assertEquals("Unsent draft", vm.state.value.draft)
                assertEquals(messages, vm.state.value.messages)
                if (!refuse && !negativeAcknowledgement) {
                    action.invoke(vm)
                    withContext(Dispatchers.Default) { withTimeout(5000) { vm.state.first { !it.isPinning } } }
                    assertFalse(vm.state.value.isPinned)
                    assertEquals(2, mutations.get())
                }
            }
        } finally { release.countDown(); store.clear(); vm.viewModelScope.coroutineContext[Job]?.join(); server.close() }
    }
}
