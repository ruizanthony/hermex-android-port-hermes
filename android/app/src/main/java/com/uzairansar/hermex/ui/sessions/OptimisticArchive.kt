package com.uzairansar.hermex.ui.sessions

import com.uzairansar.hermex.core.model.SessionSummary

/**
 * Optimistic chain archive planning: pure projection of the gesture, decoupled from
 * server confirmation. The row disappears immediately through the display projection;
 * server mutations continue in the background; a refusal restores the original rows
 * and the caller surfaces a visible error.
 */
internal object OptimisticArchive {

    data class Plan(
        val chainIds: List<String>,
        val archive: Boolean,
        val updated: List<SessionSummary>,
        /** Chain rows as they were before the optimistic flip, keyed by session id. */
        val originalsById: Map<String, SessionSummary>,
    )

    /**
     * Computes the optimistic flip for the visible row identified by [stableId].
     * Returns null when the row or its chain cannot be resolved.
     */
    fun plan(sessions: List<SessionSummary>, stableId: String): Plan? {
        val row = sessions.firstOrNull { it.stableId == stableId } ?: return null
        val archive = row.archived != true
        val chainIds = sessions.chainIdsFor(stableId).ifEmpty { listOfNotNull(row.sessionId) }
        if (chainIds.isEmpty()) return null
        val chain = chainIds.toSet()
        val updated = sessions.map { member ->
            val id = member.sessionId
            if (id != null && id in chain) {
                member.copy(
                    archived = archive,
                    compressionTipArchived = if (archive) member.compressionTipArchived else null,
                )
            } else {
                member
            }
        }
        return Plan(
            chainIds = chainIds,
            archive = archive,
            updated = updated,
            originalsById = sessions.filter { it.sessionId != null && it.sessionId in chain }.associateBy { it.sessionId!! },
        )
    }

    /**
     * Restores the pre-gesture rows after a server refusal. Rows changed elsewhere
     * since the flip (background refresh) are kept as-is unless they are chain members.
     */
    fun rollback(sessions: List<SessionSummary>, plan: Plan): List<SessionSummary> =
        sessions.map { member -> member.sessionId?.let { plan.originalsById[it] } ?: member }
}
