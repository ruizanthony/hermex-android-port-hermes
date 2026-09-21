package com.uzairansar.hermex.ui.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import com.uzairansar.hermex.core.model.SessionSummary
import com.uzairansar.hermex.ui.sessions.chainIdsFor

/** Survives rotation, never process death: stale eligibility is not saved into a Bundle. */
class ActiveConversationContext : ViewModel() {
    private var projection by mutableStateOf<Pair<String, List<String>>?>(null)

    private var generation = 0L
    private var template = com.uzairansar.hermex.ui.sessions.SessionListUiState()

    fun updateFromList(identity: String, state: com.uzairansar.hermex.ui.sessions.SessionListUiState) {
        updateMembership(identity, state.activeProfileName ?: "default", state.sessions)
        template = state.copy(sessions = emptyList())
        update(identity, state.activeConversationIds)
    }

    fun beginMetadataRefresh(): Long = generation

    fun suspendEligibility() { generation++; projection = null }

    fun reconcileMetadata(identity: String, token: Long, metadata: com.uzairansar.hermex.data.repository.SessionRepository.NavigationMetadata): Boolean {
        if (generation != token) return false
        if (membershipIdentity != null && (membershipIdentity != identity || profile != metadata.profile)) {
            clear()
            return false
        }
        updateMembership(identity, metadata.profile, metadata.sessions)
        val fresh = template.copy(sessions = metadata.sessions, activeProfileName = metadata.profile)
        update(identity, fresh.activeConversationIds)
        return true
    }

    fun update(accountIdentity: String, ids: List<String>) {
        generation++
        projection = accountIdentity to ids.toList()
    }

    fun idsFor(accountIdentity: String): List<String> =
        projection?.takeIf { it.first == accountIdentity }?.second.orEmpty()

    var profile by mutableStateOf("default")
        private set
    private var membershipIdentity: String? = null
    private var rows: List<SessionSummary> = emptyList()

    fun updateMembership(identity: String, profile: String, rows: List<SessionSummary>) {
        if (membershipIdentity != identity || this.profile != profile) projection = null
        membershipIdentity = identity
        this.profile = profile
        this.rows = rows
    }

    fun members(identity: String, sessionId: String): List<String> =
        if (membershipIdentity != identity) listOf(sessionId)
        else rows.firstOrNull { it.sessionId == sessionId }?.let { rows.chainIdsFor(it.stableId) }
            ?.ifEmpty { listOf(sessionId) } ?: listOf(sessionId)

    fun clear() {
        generation++
        projection = null; rows = emptyList(); membershipIdentity = null
        template = com.uzairansar.hermex.ui.sessions.SessionListUiState()
    }
}
