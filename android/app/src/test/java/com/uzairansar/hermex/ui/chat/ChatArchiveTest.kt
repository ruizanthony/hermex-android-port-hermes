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
import java.util.concurrent.CopyOnWriteArrayList

@OptIn(ExperimentalCoroutinesApi::class)
class ChatArchiveTest {
    @get:Rule val main = MainDispatcherRule(UnconfinedTestDispatcher())
    @Test fun archiveUsesLoadedIdentityAndPreservesDraft() = exercise()
    @Test fun refusalKeepsConversationOpen() = exercise(refuse = true)
    @Test fun duplicateGestureSendsOnlyOneChain() = exercise(duplicate = true)
    @Test fun readOnlyConversationCannotBeArchived() = exercise(readOnly = true)

    private fun exercise(refuse: Boolean = false, duplicate: Boolean = false, readOnly: Boolean = false) = runTest {
        val calls = CopyOnWriteArrayList<String>()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(if (duplicate) 1 else 0)
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.url.encodedPath == "/api/session/archive") {
                    calls.add(request.body!!.utf8()); entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                    return MockResponse.Builder().code(if (refuse) 503 else 200)
                        .body(if (refuse) """{"error":"Archive unavailable"}""" else """{"ok":true}""").build()
                }
                val row = """{"session_id":"s","source":"webui","read_only":$readOnly,"messages":[]}"""
                return MockResponse.Builder().code(200).body(when(request.url.encodedPath) {
                    "/api/session" -> """{"session":$row}"""
                    "/api/sessions" -> """{"sessions":[$row]}"""
                    else -> "{}"
                }).build()
            }
        }
        server.start()
        val http = OkHttpClient()
        val vm = ChatViewModel("s", ChatRepository(HermesApiClient(server.url("/"), http), RecordingCacheDao(), ServerCacheOwnership(), SseStreamClient(server.url("/"), http) { emptyList() }))
        val store = ViewModelStore().also { it.put("fixture", vm) }
        try {
            withContext(Dispatchers.Default) { withTimeout(5000) { vm.state.first { !it.isLoading } } }
            vm.updateDraft("Keep my draft")
            val action = vm.javaClass.methods.firstOrNull { it.name == "archiveConversation" }
            assertNotNull("Chat must expose archiveConversation", action)
            action!!.invoke(vm)
            if (duplicate) {
                withContext(Dispatchers.IO) { assertTrue(entered.await(5, TimeUnit.SECONDS)) }
                action.invoke(vm); release.countDown()
            }
            withContext(Dispatchers.Default) { withTimeout(5000) { vm.state.first { !it.isRunningSessionAction } } }
            assertEquals("Keep my draft", vm.state.value.draft)
            assertEquals(if (readOnly) 0 else 1, calls.size)
            if (calls.isNotEmpty()) assertTrue(calls.single().contains("\"session_id\":\"s\""))
            assertEquals(!refuse && !readOnly, vm.state.value.isArchived)
            if (refuse || readOnly) assertFalse(vm.state.value.error.isNullOrBlank())
        } finally { release.countDown(); store.clear(); vm.viewModelScope.coroutineContext[Job]?.join(); server.close() }
    }
}
