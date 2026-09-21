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
            assertEquals(listOf("a"), journal.rows.single().attemptedMembers)
            assertEquals(1, journal.rows.single().attemptTrackingVersion)
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
    @Test fun freshRefreshCanReleaseRestoredTombstoneButStaleRefreshCannot() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val backend = Backend().apply { release.complete(Unit) }
        val queue = DurableArchiveCoordinator(Journal(), backend, scope) { true }
        try {
            val stale = queue.beginRefresh(identity)
            queue.enqueue(identity, "a")
            withTimeout(5000) { queue.state.first { it.requests.isEmpty() && "a" in it.hidden(identity) } }
            val restored = listOf(SessionSummary(sessionId = "a", profile = "default", archived = false))
            assertEquals(false, queue.reconcileRefresh(stale, restored))
            assertEquals(setOf("a"), queue.state.value.hidden(identity))
            val fresh = queue.beginRefresh(identity)
            assertEquals(true, queue.reconcileRefresh(fresh, restored))
            assertTrue(queue.state.value.hidden(identity).isEmpty())
            assertEquals(false, queue.reconcileRefresh(stale, restored))
        } finally { scope.cancel() }
    }

    @Test fun recoveredAcknowledgementNeverRearchivesAnExternallyRestoredMember() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val journal = Journal().apply { rows = listOf(ArchiveRequest(identity, "a", confirmedMembers = listOf("a"))) }
        val backend = Backend().apply { release.complete(Unit) }
        val queue = DurableArchiveCoordinator(journal, backend, scope) { true }
        try {
            withTimeout(5000) { queue.state.first { it.requests.any { row -> row.error != null } } }
            assertTrue("Restoration is not permission to archive again", backend.calls.isEmpty())
            assertTrue(queue.state.value.hidden(identity).isEmpty())
            assertNotNull(queue.state.value.requests.single().error)
        } finally { scope.cancel() }
    }

    @Test fun recoveredUncertainPostRequiresManualRetryButUnsentRequestCanResume() = runBlocking {
        for (attempted in listOf(emptyList<String>(), listOf("a"))) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val journal = Journal().apply { rows = listOf(ArchiveRequest(identity, "a", attemptedMembers = attempted, attemptTrackingVersion = 1)) }
            val backend = Backend().apply { release.complete(Unit) }
            val queue = DurableArchiveCoordinator(journal, backend, scope) { true }
            try {
                withTimeout(5000) { while (journal.rows.isNotEmpty() && journal.rows.none { it.error != null }) delay(5) }
                assertEquals(if (attempted.isEmpty()) listOf("a") else emptyList<String>(), backend.calls.toList())
            } finally { scope.cancel() }
        }
    }

    @Test fun refreshCannotReleasePendingUnknownOrForeignProfileRowsAndRejectsOutOfOrderRead() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val backend = Backend()
        val queue = DurableArchiveCoordinator(Journal(), backend, scope) { true }
        try {
            queue.enqueue(identity, "a")
            withTimeout(5000) { backend.entered.await() }
            assertTrue(queue.reconcileRefresh(queue.beginRefresh(identity), listOf(SessionSummary(sessionId = "a", archived = false))))
            assertEquals(setOf("a"), queue.state.value.hidden(identity))
            backend.release.complete(Unit)
            withTimeout(5000) { queue.state.first { it.requests.isEmpty() } }
            val older = queue.beginRefresh(identity)
            val newer = queue.beginRefresh(identity)
            assertTrue(queue.reconcileRefresh(newer, listOf(SessionSummary(sessionId = "a", archived = null),
                SessionSummary(sessionId = "a", profile = "foreign", archived = false))))
            assertEquals(setOf("a"), queue.state.value.hidden(identity))
            assertFalse(queue.reconcileRefresh(older, listOf(SessionSummary(sessionId = "a", archived = false))))
            assertEquals(setOf("a"), queue.state.value.hidden(identity))
        } finally { scope.cancel() }
    }

    @Test fun legacyJournalWithoutSendTrackingCannotProveThatAnActiveRowWasNeverSent() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val journal = Journal().apply { rows = listOf(ArchiveRequest(identity, "a")) }
        val backend = Backend().apply { release.complete(Unit) }
        val queue = DurableArchiveCoordinator(journal, backend, scope) { true }
        try {
            withTimeout(5000) { while (journal.rows.isNotEmpty() && journal.rows.none { it.error != null }) delay(5) }
            assertTrue("Legacy journal cannot exclude a lost response followed by restoration", backend.calls.isEmpty())
        } finally { scope.cancel() }
    }

    @Test fun freshRestorationOfFailedPartialArchiveIsAlsoRemovedFromDurableAcknowledgements() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val journal = Journal().apply { rows = listOf(ArchiveRequest(identity, "a", error = "Partial failure", confirmedMembers = listOf("a"))) }
        val queue = DurableArchiveCoordinator(journal, Backend(), scope) { true }
        try {
            withTimeout(5000) { queue.state.first { "a" in it.hidden(identity) } }
            assertTrue(queue.reconcileRefresh(queue.beginRefresh(identity), listOf(SessionSummary(sessionId = "a", archived = false))))
            assertTrue(queue.state.value.hidden(identity).isEmpty())
            withTimeout(1000) { while (journal.rows.single().confirmedMembers.isNotEmpty()) delay(5) }
            assertNotNull(journal.rows.single().error)
        } finally { scope.cancel() }
    }

    @Test fun freshArchiveEvidenceAfterLostResponseSurvivesProjectionAndJournalRecovery() = runBlocking {
        val journal = Journal().apply { rows = listOf(ArchiveRequest(identity, "a", error = "Lost response")) }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val backend = Backend()
        val queue = DurableArchiveCoordinator(journal, backend, scope) { true }
        val truth = listOf(SessionSummary(sessionId = "a", profile = "default", archived = true))
        try {
            withTimeout(5000) { queue.state.first { it.requests.isNotEmpty() } }
            val older = queue.beginRefresh(identity)
            assertTrue(queue.reconcileRefresh(queue.beginRefresh(identity), truth))
            fun assertVisible(projection: ArchiveProjection) {
                val state = com.uzairansar.hermex.ui.sessions.SessionListUiState(
                    sessions = projection.project(identity, truth, true), showArchived = true)
                assertEquals(listOf("a"), state.visibleSessions.map { it.sessionId })
                assertNotNull(projection.requests.single().error)
            }
            assertVisible(queue.state.value)
            assertFalse(queue.reconcileRefresh(older, truth.map { it.copy(archived = false) }))
            withTimeout(1000) { while (journal.rows.single().confirmedMembers != listOf("a")) delay(5) }
            val recoveredScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                val recovered = DurableArchiveCoordinator(journal, backend, recoveredScope) { true }
                assertVisible(withTimeout(5000) { recovered.state.first { it.requests.isNotEmpty() } })
                assertTrue(backend.calls.isEmpty())
            } finally { recoveredScope.cancel() }
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
