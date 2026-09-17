package com.uzairansar.hermex.ui.sessions

import com.uzairansar.hermex.core.model.SessionSummary

/** Archive is a display property of a proven chain, never an update to raw rows. */
internal fun List<SessionSummary>.conversationArchiveStates(now:Long=System.currentTimeMillis()):Map<String,Boolean> {
    fun key(row:SessionSummary)= (row.profile?.trim().takeUnless { it.isNullOrEmpty() } ?: "default") to (row.lineageRootId ?: row.sessionId)
    val live=filter { it.isStreaming==true || !it.activeStreamId.isNullOrBlank() }.map(::key).toSet()
    return associate { row ->
        val age=row.compressionArchiveCheckedAt?.let { now-it }
        val proven=!row.lineageRootId.isNullOrBlank() && age!=null && age in 0..60_000
        row.stableId to (key(row) !in live && if(proven) row.compressionTipArchived ?: (row.archived==true) else row.archived==true)
    }
}

/** Groups rows into proven conversations using the same scoping as the visible projection. */
internal fun List<SessionSummary>.provenConversationGroups(): List<List<SessionSummary>> =
    collapseCompressionSegmentsGroups()

/**
 * Superseded lineage segments: Desktop rows still carrying the fallback title
 * ("Desktop Session"/"Untitled") whose conversation lives on under a titled
 * continuation, plus untitled one-shot delegation leaves behind a TITLED
 * parent. Hidden only on in-payload linkage proof; a solo default-titled row
 * and any active row (streaming/stream id/pinned) stay visible. Untitled
 * chains without a titled member stay visible (ambiguous, not proven
 * replaced). Display-only projection; raw rows are never mutated.
 */
internal fun List<SessionSummary>.filterNotSupersededSegments(): List<SessionSummary> {
    fun fallbackTitled(row: SessionSummary): Boolean {
        // Source is checked FIRST: a non-Desktop row with a null title is not
        // the Desktop fallback shape and must stay visible.
        val source = row.rawSource?.trim()?.lowercase() ?: row.sourceTag?.trim()?.lowercase() ?: ""
        if (source != "desktop" && source.isNotBlank()) return false
        val normalized = row.title?.trim()?.lowercase() ?: return true
        return normalized in setOf("", "untitled", "untitled session", "desktop session")
    }

    fun active(row: SessionSummary): Boolean = row.isStreaming == true ||
        !row.activeStreamId.isNullOrBlank() || row.pinned == true

    fun key(row: SessionSummary) = row.profile?.trim().takeUnless { it.isNullOrEmpty() } ?: "default"
    val scoped = groupBy(::key).values.flatMap { rows ->
        val byId = rows.filter { !it.sessionId.isNullOrBlank() }.associateBy { it.sessionId!! }
        val children = rows.filter { !it.parentSessionId.isNullOrBlank() }.groupBy { it.parentSessionId!! }

        fun titledDescendant(id: String, seen: MutableSet<String> = mutableSetOf()): Boolean {
            if (!seen.add(id)) return false
            return children[id].orEmpty().any { child ->
                (!fallbackTitled(child) && !active(child)) || titledDescendant(child.sessionId.orEmpty(), seen)
            }
        }

        var provenSuperseded = mutableSetOf<String>()
        var grew = true
        while (grew) {
            grew = false
            for (row in rows) {
                val sid = row.sessionId?.takeIf { it.isNotBlank() } ?: continue
                if (sid in provenSuperseded || !fallbackTitled(row) || active(row)) continue
                val parent = row.parentSessionId?.let { byId[it] }
                val titledParent = parent?.takeIf { !fallbackTitled(it) }
                // A parent already PROVEN superseded (titled descendant found in
                // a previous pass) makes this leaf part of a dead branch; plain
                // parenthood alone still never hides anything.
                val parentDead = parent != null && parent.sessionId in provenSuperseded
                if (titledDescendant(sid) || (titledParent != null && !active(titledParent)) ||
                    (parentDead && parent != null && !active(parent))
                ) {
                    provenSuperseded.add(sid)
                    grew = true
                }
            }
        }
        rows.filter { row -> row.sessionId?.takeIf { it.isNotBlank() }?.let { it !in provenSuperseded } ?: true }
    }
    return scoped
}

/** Session ids of the proven conversation containing this stableId (itself included). Structural only: ignores display archive state. */
internal fun List<SessionSummary>.chainIdsFor(stableId: String): List<String> =
    collapseCompressionSegmentsGroups(scopeByArchive = false)
        .firstOrNull { members -> members.any { it.stableId == stableId } }
        ?.mapNotNull { it.sessionId?.takeIf { id -> id.isNotBlank() } }
        ?: emptyList()

/** Display-only projection. Never uses titles or ordinary parenthood as identity. */
internal fun List<SessionSummary>.collapseCompressionSegments(matchingIds: Set<String>? = null): List<SessionSummary> =
    collapseCompressionSegmentsGroups(matchingIds).map { members ->
        members.maxWithOrNull(
            compareBy<SessionSummary> { it.isStreaming == true || !it.activeStreamId.isNullOrBlank() }
                .thenBy { candidate -> members.any { it.lineageRootId==candidate.sessionId } }
                .thenBy { it.preCompressionSnapshot != true }
                .thenBy { maxOf(it.lastMessageAt ?: 0.0, it.updatedAt ?: 0.0, it.createdAt ?: 0.0) }
                .thenBy { it.sessionId.orEmpty() },
        )!!
    }

private fun List<SessionSummary>.collapseCompressionSegmentsGroups(matchingIds: Set<String>? = null, scopeByArchive: Boolean = true): List<List<SessionSummary>> {
    val groups = mutableListOf<List<SessionSummary>>()
    val archiveStates=conversationArchiveStates()
    groupBy { (it.profile?.trim().takeUnless { p -> p.isNullOrEmpty() } ?: "default") to (if (scopeByArchive) archiveStates[it.stableId] else null) }.values.forEach { scoped ->
        val rows = scoped.filter { !it.sessionId.isNullOrBlank() }.associateBy { it.sessionId!! }
        val roots = rows.keys.associateWith { it }.toMutableMap()
        fun root(id: String): String {
            var current = id
            val seen = mutableSetOf<String>()
            while (roots[current] != null && roots[current] != current && seen.add(current)) {
                current = roots.getValue(current)
            }
            return current
        }
        fun protected(row: SessionSummary): Boolean = row.isListSubagent ||
            row.sessionSource.equals("fork", true) ||
            row.relationshipType?.lowercase() in setOf("fork", "branch", "subagent")
        fun join(a: String, b: String) {
            val first = rows[a] ?: return
            val second = rows[b] ?: return
            if (protected(first) || protected(second)) return
            if (!first.rawSource.isNullOrBlank() && !second.rawSource.isNullOrBlank() &&
                !first.rawSource.equals(second.rawSource, true) &&
                !(first.rawSource in setOf("desktop", "webui") && second.rawSource in setOf("desktop", "webui"))) return
            roots[root(a)] = root(b)
        }
        // A server-projected root is evidence; a plain parent_session_id is not.
        rows.values.filter { !protected(it) && !it.lineageRootId.isNullOrBlank() }
            .groupBy { it.lineageRootId }.values.forEach { linked ->
                linked.forEach { row ->
                    join(linked.first().sessionId!!, row.sessionId!!)
                    row.lineageRootId?.let { join(row.sessionId!!, it) }
                }
            }
        rows.values.forEach { old ->
            if (old.preCompressionSnapshot != true || protected(old)) return@forEach
            val target = rows[old.continuationSessionId] ?: return@forEach
            if (protected(target)) return@forEach
            // A confirmed snapshot redirect can span intermediate segments whose
            // generic relationship_type is child_session (older WebUI servers).
            val path = mutableListOf(target.sessionId!!)
            val seen = mutableSetOf(target.sessionId)
            var current = target
            while (current.sessionId != old.sessionId) {
                val parent = rows[current.parentSessionId] ?: break
                if (!seen.add(parent.sessionId!!) || protected(parent) || protected(current)) break
                path.add(parent.sessionId!!)
                current = parent
            }
            join(old.sessionId!!, target.sessionId!!)
            if (current.sessionId == old.sessionId) path.forEach { join(old.sessionId, it) }
        }
        groups.addAll(scoped.groupBy { row -> row.sessionId?.let(::root) ?: row.stableId }.values)
    }
    val matchOrder = matchingIds?.withIndex()?.associate { it.value to it.index }.orEmpty()
    return groups.filter { members -> matchingIds == null || members.any { it.stableId in matchingIds } }
        .sortedBy { members -> members.minOf { matchOrder[it.stableId] ?: Int.MAX_VALUE } }
}

