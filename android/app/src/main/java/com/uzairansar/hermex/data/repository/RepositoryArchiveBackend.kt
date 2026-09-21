package com.uzairansar.hermex.data.repository

import com.uzairansar.hermex.core.model.SessionSummary
import com.uzairansar.hermex.core.network.HermesApiClient
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Profile switches and archive calls share this gate; no POST can cross a local switch. */
class RepositoryArchiveBackend(
    private val client: (ArchiveIdentity) -> HermesApiClient,
    private val repository: (ArchiveIdentity) -> SessionRepository,
    private val gate: Mutex,
    private val authVersion: (ArchiveIdentity) -> Long,
    private val authorized: (ArchiveIdentity) -> Boolean,
) : ArchiveBackend {
    private suspend fun checkProfile(identity: ArchiveIdentity, generation: Long) {
        check(authorized(identity)) { "Account changed. Retry when signed in." }
        check((client(identity).profiles().active?.takeIf(String::isNotBlank) ?: "default") == identity.profile) {
            "Active profile changed. Return to the original profile and retry."
        }
        check(authorized(identity) && authVersion(identity) == generation) { "Account changed. Retry when signed in." }
    }

    override suspend fun read(identity: ArchiveIdentity): List<SessionSummary> = gate.withLock {
        val generation = authVersion(identity)
        checkProfile(identity, generation)
        val result = repository(identity).loadSessions(includeArchived = true)
        check(authorized(identity) && authVersion(identity) == generation) { "Account changed. Retry when signed in." }
        check(result is ResultState.Data && !result.fromCache) { "Reconnect to archive this conversation." }
        result.value.sessions
    }

    override suspend fun archive(identity: ArchiveIdentity, sessionId: String): String? = gate.withLock {
        val generation = authVersion(identity)
        checkProfile(identity, generation)
        repository(identity).archiveChain(listOf(sessionId), true)
    }
}
