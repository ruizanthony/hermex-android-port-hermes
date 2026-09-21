package com.uzairansar.hermex.data.repository

import com.uzairansar.hermex.core.model.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Bounded read-only proof lookup. Cache only verdicts, never downloaded transcripts. */
internal class DelegationEnricher(private val now: () -> Long = System::currentTimeMillis) {
    private companion object { const val NEGATIVE_VERDICT_TTL_MS = 300_000L }
    private data class Key(val id: String?, val parent: String?, val profile: String?, val created: Double?)
    private data class Verdict(val confirmed: Boolean, val expires: Long)
    private val verdicts = linkedMapOf<Key, Verdict>()
    private val mutex = Mutex()
    private fun key(row: SessionSummary) = Key(row.sessionId, row.parentSessionId, row.profile, row.createdAt)
    private fun eligible(row: SessionSummary) = !row.sessionId.isNullOrBlank() && !row.parentSessionId.isNullOrBlank() &&
        row.relationshipType == "child_session" && row.rawSource in setOf("desktop", "cli") &&
        row.sessionSource?.trim()?.lowercase().orEmpty() in setOf("", "other") && row.preCompressionSnapshot != true &&
        row.continuationSessionId.isNullOrBlank() && row.lineageRootId.isNullOrBlank()

    suspend fun restore(rows: List<SessionSummary>) = mutex.withLock {
        rows.filter { eligible(it) && it.confirmedDelegationParentId == it.parentSessionId }.forEach {
            verdicts[key(it)] = Verdict(true, Long.MAX_VALUE)
        }
    }

    suspend fun enrich(rows: List<SessionSummary>, allowLookup: Boolean = true, fetch: suspend (String) -> SessionDetail?): List<SessionSummary> = mutex.withLock {
        val parents = mutableMapOf<String, SessionDetail?>()
        withTimeoutOrNull(6_000) {
            var checked = 0
            for (row in rows) {
                if (!allowLookup || !eligible(row)) continue
                val key = key(row)
                if (verdicts[key]?.expires?.let { it > now() } == true) continue
                if (checked++ >= 8) break
                val confirmed = try {
                    withTimeoutOrNull(4_000) read@ {
                        val child = fetch(row.sessionId!!) ?: return@read false
                        val parentId = row.parentSessionId!!
                        if (!parents.containsKey(parentId)) parents[parentId] = fetch(parentId)
                        val parent = parents[parentId] ?: return@read false
                        confirmsDelegation(row, child, parent)
                    }
                } catch (error: CancellationException) { throw error }
                catch (_: Exception) { null }
                verdicts[key] = Verdict(confirmed == true, when (confirmed) { true -> Long.MAX_VALUE; false -> now() + NEGATIVE_VERDICT_TTL_MS; null -> now() + 5_000 })
            }
        }
        while (verdicts.size > 512) verdicts.remove(verdicts.keys.first())
        rows.map { row -> row.copy(confirmedDelegationParentId = row.parentSessionId.takeIf {
            eligible(row) && verdicts[key(row)]?.confirmed == true
        }) }
    }
}
