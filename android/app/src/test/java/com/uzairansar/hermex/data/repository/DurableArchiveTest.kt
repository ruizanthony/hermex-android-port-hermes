package com.uzairansar.hermex.data.repository

import com.uzairansar.hermex.core.model.SessionSummary
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

class DurableArchiveTest {
    private val identity = ArchiveIdentity("https://fixture.invalid/", "account", "default")
    private class Journal : ArchiveJournal {
        @Volatile var rows = emptyList<ArchiveRequest>()
        var fail = false
        override fun read() = rows
        override fun write(records: List<ArchiveRequest>) { check(!fail) { "Disk full" }; rows = records }
    }
    private class Backend : ArchiveBackend {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val calls = CopyOnWriteArrayList<String>()
        val archived = CopyOnWriteArrayList<String>()
        var refuse: String? = null
        override suspend fun read(identity: ArchiveIdentity) = listOf("a", "b", "c").map {
            SessionSummary(sessionId = it, profile = "default", archived = it in archived)
        }
        override suspend fun archive(identity: ArchiveIdentity, sessionId: String): String? {
            calls += sessionId; entered.complete(Unit); release.await()
            if (sessionId == refuse) return "Refused"
            archived += sessionId
            return null
        }
    }
    @Test fun immediateProjectionDurableBeforeNetworkAndSequentialBeyondScreenLifetime() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val journal = Journal(); val backend = Backend()
        val queue = DurableArchiveCoordinator(journal, backend, scope) { true }
        try {
            assertTrue(queue.enqueue(identity, "a"))
            assertEquals(setOf("a"), queue.state.value.hidden(identity))
            assertFalse(queue.enqueue(identity, "a"))
            withTimeout(5000) { backend.entered.await() }
            assertEquals("a", journal.rows.single().sessionId)
            assertTrue(queue.enqueue(identity, "b"))
            assertEquals(setOf("a", "b"), queue.state.value.hidden(identity))
            assertEquals(listOf("a"), backend.calls.toList())
            withTimeout(1000) { while (journal.rows.none { it.sessionId == "b" }) delay(5) }
            backend.release.complete(Unit)
            withTimeout(5000) { queue.state.first { it.requests.isEmpty() } }
            assertEquals(listOf("a", "b"), backend.calls.toList())
            assertEquals(setOf("a", "b"), queue.state.value.hidden(identity))
        } finally { scope.cancel() }
    }
    @Test fun refusalRestoresProjectionAndRequiresExplicitRetry() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val journal = Journal(); val backend = Backend().apply { refuse = "a"; release.complete(Unit) }
        val queue = DurableArchiveCoordinator(journal, backend, scope) { true }
        try {
            queue.enqueue(identity, "a")
            val failed = withTimeout(5000) { queue.state.first { it.requests.any { r -> r.error != null } } }
            assertTrue(failed.hidden(identity).isEmpty())
            queue.resume(); delay(50)
            assertEquals(listOf("a"), backend.calls.toList())
            backend.refuse = null; queue.retry(failed.requests.single().key)
            withTimeout(5000) { queue.state.first { it.requests.isEmpty() } }
            assertEquals(setOf("a"), queue.state.value.hidden(identity))
        } finally { scope.cancel() }
    }
    @Test fun recoveryReconcilesBeforeReplayAndSuspendsOtherAccounts() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val journal = Journal().apply { rows = listOf(ArchiveRequest(identity, "a")) }
        val backend = Backend().apply { archived += "a"; release.complete(Unit) }
        var authorized = false
        val queue = DurableArchiveCoordinator(journal, backend, scope) { authorized }
        try {
            withTimeout(5000) { queue.state.first { it.requests.isNotEmpty() } }
            assertTrue(backend.calls.isEmpty())
            authorized = true; queue.resume()
            withTimeout(5000) { queue.state.first { it.requests.isEmpty() } }
            assertTrue(backend.calls.isEmpty())
        } finally { scope.cancel() }
    }
    @Test fun provenChainPartialFailureRetainsConfirmedAndReinsertsOnlyActiveMembers() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val journal = Journal()
        val archived = mutableSetOf<String>()
        val calls = mutableListOf<String>()
        val backend = object : ArchiveBackend {
            override suspend fun read(identity: ArchiveIdentity) = listOf(
                SessionSummary(sessionId = "root", profile = "default", archived = "root" in archived),
                SessionSummary(sessionId = "tip", profile = "default", archived = "tip" in archived, lineageRootId = "root"),
                SessionSummary(sessionId = "fork", profile = "default", parentSessionId = "root"),
            )
            override suspend fun archive(identity: ArchiveIdentity, sessionId: String): String? {
                calls += sessionId
                if (sessionId == "tip") return "Denied"
                archived += sessionId
                return null
            }
        }
        val queue = DurableArchiveCoordinator(journal, backend, scope) { true }
        try {
            queue.enqueue(identity, "root", listOf("root", "tip"))
            val failed = withTimeout(5000) { queue.state.first { it.requests.any { r -> r.error != null } } }
            assertEquals(listOf("root", "tip"), calls)
            assertEquals(setOf("root"), failed.hidden(identity))
            val projection = failed.project(identity, backend.read(identity), false)
            assertEquals(listOf("tip", "fork"), projection.map { it.sessionId })
        } finally { scope.cancel() }
    }
    @Test fun acknowledgedMemberIsNotRolledBackWhenPartialReconciliationIsOffline() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        var reads = 0
        val backend = object : ArchiveBackend {
            override suspend fun read(identity: ArchiveIdentity): List<SessionSummary> {
                check(++reads == 1) { "Offline" }
                return listOf(SessionSummary(sessionId = "root"), SessionSummary(sessionId = "tip", lineageRootId = "root"))
            }
            override suspend fun archive(identity: ArchiveIdentity, sessionId: String) = if (sessionId == "tip") "Refused" else null
        }
        val queue = DurableArchiveCoordinator(Journal(), backend, scope) { true }
        try {
            queue.enqueue(identity, "root")
            val failed = withTimeout(5000) { queue.state.first { it.requests.any { r -> r.error != null } } }
            assertEquals(setOf("root"), failed.hidden(identity))
        } finally { scope.cancel() }
    }
    @Test fun diskFailureNeverSendsMutationAndRestoresTheRow() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val backend = Backend()
        val queue = DurableArchiveCoordinator(Journal().apply { fail = true }, backend, scope) { true }
        try {
            queue.enqueue(identity, "a")
            val failed = withTimeout(5000) { queue.state.first { it.requests.any { r -> r.error != null } } }
            assertTrue(failed.hidden(identity).isEmpty())
            assertTrue(backend.calls.isEmpty())
        } finally { scope.cancel() }
    }
}
