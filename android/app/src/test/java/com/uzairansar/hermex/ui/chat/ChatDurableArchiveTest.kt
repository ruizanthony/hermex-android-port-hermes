package com.uzairansar.hermex.ui.chat

import androidx.lifecycle.ViewModelStore
import com.uzairansar.hermex.MainDispatcherRule
import com.uzairansar.hermex.core.network.*
import com.uzairansar.hermex.data.db.ServerCacheOwnership
import com.uzairansar.hermex.data.repository.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
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
class ChatDurableArchiveTest {
    @get:Rule val main = MainDispatcherRule(UnconfinedTestDispatcher())

    @Test fun heldResponseDoesNotOwnVmOrBlockNextArchiveAndSwitchWaitsForPost() = runTest {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val archived = CopyOnWriteArrayList<String>(); val calls = CopyOnWriteArrayList<String>()
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.url.encodedPath
                if (path == "/api/session/archive") {
                    val id = if (request.body!!.utf8().contains("\"session_id\":\"a\"")) "a" else "b"
                    calls += id; entered.countDown(); check(release.await(10, TimeUnit.SECONDS)); archived += id
                    return MockResponse.Builder().body("{\"ok\":true}").build()
                }
                if (path.contains("switch")) calls += "switch"
                fun row(id: String) = """{"session_id":"$id","profile":"default","source":"webui","archived":${id in archived},"messages":[]}"""
                return MockResponse.Builder().body(when (path) {
                    "/api/session" -> """{"session":${row("a")}}"""
                    "/api/sessions" -> """{"sessions":[${row("a")},${row("b")}],"archived_count":${archived.size}}"""
                    "/api/profiles" -> """{"active":"default"}"""
                    else -> "{}"
                }).build()
            }
        }
        server.start()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val http = OkHttpClient(); val gate = Mutex()
        val api = HermesApiClient(server.url("/"), http, profileMutationGate = gate)
        val cache = RecordingCacheDao(); val ownership = ServerCacheOwnership()
        val sessions = SessionRepository(api, cache, ownership)
        val identity = ArchiveIdentity(server.url("/").toString(), "fixture", "default")
        val journal = object : ArchiveJournal {
            @Volatile var rows = emptyList<ArchiveRequest>()
            override fun read() = rows
            override fun write(records: List<ArchiveRequest>) { rows = records }
        }
        val queue = DurableArchiveCoordinator(journal,
            RepositoryArchiveBackend({ api }, { sessions }, gate, { 0L }, { true }), scope) { true }
        val vm = ChatViewModel("a", ChatRepository(api, cache, ownership, SseStreamClient(server.url("/"), http) { emptyList() }))
        val store = ViewModelStore().also { it.put("chat", vm) }
        try {
            withContext(Dispatchers.Default) { withTimeout(5000) { vm.state.first { !it.isLoading } } }
            vm.updateDraft("Kept draft")
            assertTrue(vm.enqueueArchive { queue.enqueue(identity, "a") })
            assertFalse(vm.state.value.isRunningSessionAction)
            assertFalse(vm.state.value.isArchived) // Late confirmation cannot navigate again.
            assertEquals("Kept draft", vm.state.value.draft)
            withContext(Dispatchers.IO) { assertTrue(entered.await(5, TimeUnit.SECONDS)) }
            assertTrue(journal.rows.any { it.sessionId == "a" })
            store.clear() // Real departing ViewModel; worker is not its child.
            assertTrue(queue.enqueue(identity, "b"))
            val switch = scope.launch { api.switchProfile("default") }
            withContext(Dispatchers.IO) { delay(80) }
            assertEquals(listOf("a"), calls.toList())
            release.countDown()
            withContext(Dispatchers.Default) { withTimeout(5000) { queue.state.first { it.requests.isEmpty() }; switch.join() } }
            assertEquals(setOf("a", "b"), archived.toSet())
            assertEquals(1, calls.count { it == "a" }); assertEquals(1, calls.count { it == "b" })
        } finally { release.countDown(); store.clear(); scope.cancel(); server.close() }
    }
}
