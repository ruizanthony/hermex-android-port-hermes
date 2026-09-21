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

    fun update(accountIdentity: String, ids: List<String>) {
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

    fun clear() { projection = null; rows = emptyList(); membershipIdentity = null }
}
