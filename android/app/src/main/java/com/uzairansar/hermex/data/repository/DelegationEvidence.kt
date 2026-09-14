package com.uzairansar.hermex.data.repository

import com.uzairansar.hermex.core.model.*
import com.uzairansar.hermex.core.network.HermesJson
import kotlinx.serialization.json.*
import kotlin.math.abs

/** Legacy Desktop children need a real parent tool call, never a title heuristic. */
internal fun confirmsDelegation(row: SessionSummary, child: SessionDetail, parent: SessionDetail): Boolean {
    if (row.sessionId.isNullOrBlank() || row.sessionId != child.sessionId ||
        row.parentSessionId.isNullOrBlank() || row.parentSessionId != parent.sessionId ||
        row.sessionId == parent.sessionId || row.relationshipType != "child_session" ||
        row.preCompressionSnapshot == true || !row.continuationSessionId.isNullOrBlank() ||
        !row.lineageRootId.isNullOrBlank() || row.sessionSource.equals("fork", true)) return false
    fun profile(value: String?) = value?.trim().takeUnless { it.isNullOrEmpty() } ?: "default"
    if (profile(row.profile) != profile(child.profile) || profile(row.profile) != profile(parent.profile)) return false
    val created = row.createdAt?.takeIf { it.isFinite() } ?: return false
    if (child.createdAt != created || child.resolvedMessagesOffset(child.messages.orEmpty().size) != 0) return false
    val first = child.messages?.firstOrNull() ?: return false
    val text = first.content?.takeIf { it.isNotBlank() } ?: return false
    val time = first.timestamp?.takeIf { it.isFinite() } ?: return false
    if (first.role != "user" || abs(time - created) > 5) return false
    val matching = parent.messages.orEmpty().filter { message ->
        val sent = message.timestamp ?: return@filter false
        if (message.role != "assistant" || !sent.isFinite() || created - sent !in 0.0..5.0) return@filter false
        message.toolCalls.orEmpty().any { call ->
            val function = call.function ?: return@any false
            if (function.name != "delegate_task") return@any false
            val args = runCatching { HermesJson.parseToJsonElement(function.arguments.orEmpty()) as? JsonObject }.getOrNull() ?: return@any false
            val action = (args["action"] as? JsonPrimitive)?.contentOrNull
            if (action != null && action != "spawn") return@any false
            (args["tasks"] as? JsonArray).orEmpty().any { task ->
                ((task as? JsonObject)?.get("goal") as? JsonPrimitive)?.contentOrNull == text
            }
        }
    }
    return matching.size == 1
}
