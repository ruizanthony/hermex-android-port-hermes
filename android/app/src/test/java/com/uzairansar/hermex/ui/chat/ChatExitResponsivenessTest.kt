package com.uzairansar.hermex.ui.chat

import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import com.uzairansar.hermex.MainDispatcherRule
import com.uzairansar.hermex.core.network.*
import com.uzairansar.hermex.data.db.*
import com.uzairansar.hermex.data.repository.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import mockwebserver3.*
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean

@OptIn(ExperimentalCoroutinesApi::class)
class ChatExitResponsivenessTest {
    @get:Rule val main = MainDispatcherRule(UnconfinedTestDispatcher())
    @Test fun leavingReturnsWhileStorageIsSuspended() = exerciseExit(false)
    @Test fun completedRefreshKeepsExitCacheWritable() = exerciseExit(true)
    private fun exerciseExit(refreshCompleted: Boolean) = runTest {
        val block = AtomicBoolean(false)
        val entered = CountDownLatch(1)
        val released = CompletableDeferred<Unit>()
        val saved = CountDownLatch(1)
        val recording = RecordingCacheDao()
        val dao = object : CacheDao by recording {
            override suspend fun replaceMessages(serverUrl: String, sessionId: String, messages: List<CachedMessageEntity>, now: Long) {
                if (block.get()) { entered.countDown(); released.await() }
                recording.replaceMessages(serverUrl, sessionId, messages, now)
                if (block.get()) saved.countDown()
            }
        }
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse.Builder().code(200).body(
                if(request.url.encodedPath == "/api/session") """{"session":{"session_id":"s","messages":[{"id":"u","role":"user","content":"Question"},{"id":"m","role":"assistant","content":"Preserve this"}]}}""" else "{}"
            ).build()
        }
        server.start()
        val http = OkHttpClient()
        val vm = ChatViewModel("s", ChatRepository(HermesApiClient(server.url("/"), http), dao, ServerCacheOwnership(), SseStreamClient(server.url("/"), http) { emptyList() }))
        val store = ViewModelStore().also { it.put("vm",vm) }
        val executor = Executors.newSingleThreadExecutor()
        try {
            withContext(Dispatchers.Default) { withTimeout(5000) { vm.state.first { !it.isLoading } } }
            if(refreshCompleted) {
                val mutable = vm.state as kotlinx.coroutines.flow.MutableStateFlow<ChatUiState>
                mutable.value = mutable.value.copy(responseCompletionNeedsTranscriptRefresh = true, responseCompletionTrigger = 1)
                vm.refreshCompletedTranscriptIfNeeded()
                withContext(Dispatchers.Default) { withTimeout(5000) { vm.state.first { !it.responseCompletionNeedsTranscriptRefresh } } }
            }
            block.set(true)
            val close = executor.submit { store.clear() }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val returned = try { close.get(300, TimeUnit.MILLISECONDS); true } catch (_: TimeoutException) { false }
            released.complete(Unit)
            close.get(5, TimeUnit.SECONDS)
            assertTrue("Closing the screen must not wait for the DAO", returned)
            assertTrue("Exit save survives the VM", saved.await(5, TimeUnit.SECONDS))
            assertEquals("m", recording.replacedMessageBatches.last().last().toMessage()!!.id)
        } finally { released.complete(Unit); withContext(Dispatchers.Default) { vm.viewModelScope.coroutineContext[Job]?.join() }; executor.shutdownNow(); server.close() }
    }
}
