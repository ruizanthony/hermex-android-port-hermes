package com.uzairansar.hermex.data.repository

import com.uzairansar.hermex.core.model.SessionSummary
import com.uzairansar.hermex.ui.sessions.chainIdsFor
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable

@Serializable
data class ArchiveIdentity(val server: String, val account: String, val profile: String)

@Serializable
data class ArchiveRequest(
    val identity: ArchiveIdentity,
    val sessionId: String,
    val members: List<String> = listOf(sessionId),
    val error: String? = null,
    val confirmedMembers: List<String> = emptyList(),
) {
    val key: String get() = "${identity.server}\u0000${identity.account}\u0000${identity.profile}\u0000$sessionId"
}

interface ArchiveJournal {
    fun read(): List<ArchiveRequest>
    /** Must return only after durable storage; called exclusively on application IO. */
    fun write(records: List<ArchiveRequest>)
}

interface ArchiveBackend {
    /** Authoritative, enriched metadata, never a cached fallback. */
    suspend fun read(identity: ArchiveIdentity): List<SessionSummary>
    suspend fun archive(identity: ArchiveIdentity, sessionId: String): String?
}

data class ArchiveProjection(
    val requests: List<ArchiveRequest> = emptyList(),
    val confirmed: Map<ArchiveIdentity, Set<String>> = emptyMap(),
    val revision: Long = 0,
) {
    fun hidden(identity: ArchiveIdentity): Set<String> = confirmed[identity].orEmpty() +
        requests.filter { it.identity == identity && it.error == null }.flatMap { it.members }
    fun errors(server: String, account: String) = requests.filter {
        it.identity.server == server && it.identity.account == account && it.error != null
    }
}

/** Projection only: never delete cached rows, transcripts, drafts or compression evidence. */
fun ArchiveProjection.project(identity: ArchiveIdentity, rows: List<SessionSummary>, showArchived: Boolean): List<SessionSummary> {
    val hidden = hidden(identity)
    val failed = requests.filter { it.identity == identity && it.error != null }.flatMap { it.members }.toSet()
    return rows.mapNotNull { row ->
        val id = row.sessionId
        when {
            !showArchived && id in hidden -> null
            id in confirmed[identity].orEmpty() -> row.copy(archived = true)
            id in failed -> row.copy(archived = false, compressionTipArchived = null)
            else -> row
        }
    }
}

/** One application-owned sequential worker. UI acceptance never waits for disk/network.
 * A process death before the first journal commit can lose a newly accepted gesture.
 * Persisted pending work is reconciled before replay; failures require an explicit retry.
 */
class DurableArchiveCoordinator(
    private val journal: ArchiveJournal,
    private val backend: ArchiveBackend,
    private val scope: CoroutineScope,
    private val isAuthorized: (ArchiveIdentity) -> Boolean,
) {
    private val lock = Any()
    private val journalLock = Mutex()
    private val initialized = CompletableDeferred<Unit>()
    private val records = linkedMapOf<String, ArchiveRequest>()
    private val confirmed = mutableMapOf<ArchiveIdentity, Set<String>>()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val _state = MutableStateFlow(ArchiveProjection())
    val state: StateFlow<ArchiveProjection> = _state
    private var storageFailure: String? = null

    init {
        scope.launch {
            try {
                val recovered = journal.read()
                synchronized(lock) {
                    recovered.forEach {
                        records.putIfAbsent(it.key, it)
                        confirmed[it.identity] = confirmed[it.identity].orEmpty() + it.confirmedMembers
                    }
                    publish()
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { storageFailure = "Archive journal unavailable. Retry after restarting the app." }
            initialized.complete(Unit)
            wake.trySend(Unit)
            for (ignored in wake) {
                while (true) {
                    val request = synchronized(lock) {
                        records.values.firstOrNull { it.error == null && isAuthorized(it.identity) }
                    } ?: break
                    process(request)
                }
            }
        }
    }

    /** knownMembers must come only from the existing proven-chain projection. */
    fun enqueue(identity: ArchiveIdentity, sessionId: String, knownMembers: List<String> = listOf(sessionId)): Boolean {
        if (sessionId.isBlank() || !isAuthorized(identity)) return false
        synchronized(lock) {
            if (sessionId in _state.value.hidden(identity)) return false
            val request = ArchiveRequest(identity, sessionId, (knownMembers + sessionId).distinct())
            records[request.key] = request
            publish()
        }
        persistAcceptance(ArchiveRequest(identity, sessionId).key)
        return true
    }

    fun resume() { wake.trySend(Unit) }

    fun retry(key: String) {
        synchronized(lock) {
            val old = records[key] ?: return
            if (!isAuthorized(old.identity)) return
            records[key] = old.copy(error = null)
            publish()
        }
        persistAcceptance(key)
    }

    /** Only explicit unarchive may remove a confirmed anti-stale tombstone. */
    fun restored(identity: ArchiveIdentity, ids: List<String>) = synchronized(lock) {
        confirmed[identity] = confirmed[identity].orEmpty() - ids.toSet()
        publish()
    }

    private fun publish() {
        _state.value = ArchiveProjection(records.values.toList(), confirmed.toMap(), _state.value.revision + 1)
    }

    private suspend fun persist() {
        initialized.await()
        journalLock.withLock {
            storageFailure?.let { error(it) }
            journal.write(synchronized(lock) { records.values.toList() })
        }
    }

    private fun persistAcceptance(key: String) {
        // Disk work must not queue behind a held network response for another conversation.
        scope.launch {
            try {
                persist()
                wake.trySend(Unit)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                synchronized(lock) {
                    records[key]?.let { records[key] = it.copy(error = e.message ?: "Could not save archive request. Retry.") }
                    publish()
                }
            }
        }
    }

    private fun checkAuthority(identity: ArchiveIdentity) {
        if (!isAuthorized(identity)) throw ArchiveSuspended()
    }

    private suspend fun process(original: ArchiveRequest) {
        var request = original
        try {
            // Persist even if logout races acceptance; no mutation before this commit.
            persist()
            checkAuthority(request.identity)
            val rows = backend.read(request.identity)
            checkAuthority(request.identity)
            val selected = rows.firstOrNull { it.sessionId == request.sessionId }
                ?: error("Conversation unavailable. Refresh and retry.")
            val ids = rows.chainIdsFor(selected.stableId).ifEmpty { listOf(request.sessionId) }
            val members = rows.filter { it.sessionId in ids }
            require(members.all { (it.profile?.takeIf(String::isNotBlank) ?: "default") == request.identity.profile }) {
                "Conversation profile changed. Refresh and retry."
            }
            require(members.none { it.isSessionReadOnly }) { "This session is read-only." }
            request = request.copy(members = ids)
            synchronized(lock) { records[request.key] = request; publish() }
            persist() // Proven membership survives a partial mutation/process death.
            for (id in ids) {
                checkAuthority(request.identity)
                // Never replay a confirmed archive after an interrupted response.
                if (members.firstOrNull { it.sessionId == id }?.archived != true) {
                    backend.archive(request.identity, id)?.let { error(it) }
                }
                request = request.copy(confirmedMembers = (request.confirmedMembers + id).distinct())
                synchronized(lock) {
                    records[request.key] = request
                    confirmed[request.identity] = confirmed[request.identity].orEmpty() + id
                    publish()
                }
                persist() // Preserve partial acknowledgement even if reconciliation goes offline.
            }
            checkAuthority(request.identity)
            val truth = backend.read(request.identity)
            checkAuthority(request.identity)
            require(ids.all { id -> truth.any { it.sessionId == id && it.archived == true } }) {
                "Archive not fully confirmed. Refresh and retry."
            }
            synchronized(lock) {
                confirmed[request.identity] = confirmed[request.identity].orEmpty() + ids
                records.remove(request.key)
                publish()
            }
            persist()
        } catch (e: CancellationException) { throw e }
        catch (_: ArchiveSuspended) {
            // Remains pending and durable, but not runnable until the identity returns.
        } catch (e: Exception) {
            // Read after refusal/partial success. Never assert a server rollback.
            val truth = try {
                checkAuthority(request.identity)
                backend.read(request.identity).also { checkAuthority(request.identity) }
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { emptyList() }
            synchronized(lock) {
                val archived = truth.filter { it.sessionId in request.members && it.archived == true }.mapNotNull { it.sessionId }
                val observed = truth.mapNotNull { it.sessionId }.filter { it in request.members }.toSet()
                val known = (confirmed[request.identity].orEmpty() - observed) + archived
                confirmed[request.identity] = known
                records[request.key] = request.copy(error = e.message ?: "Archive failed. Retry.",
                    confirmedMembers = request.members.filter { it in known })
                publish()
            }
            try { persist() } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { /* Keep the visible retry and never send network work without a durable write. */ }
        }
    }

    private class ArchiveSuspended : Exception()
}
