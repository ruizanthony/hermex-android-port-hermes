package com.uzairansar.hermex.ui.chat

import com.uzairansar.hermex.core.network.ApiError
import com.uzairansar.hermex.core.network.HermesJson
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A compressed (sealed) session no longer accepts turns: the server answers
 * `/api/chat/start` with HTTP 409 `session_rotated` and exposes the live tip as
 * `continuation_session_id` on the session detail. The client must follow the
 * tip instead of repeatedly reading the frozen segment.
 */
object SealedContinuation {
    const val REDIRECT_NOTICE = "Conversation compressée : ouverture de la suite."
    const val AVAILABLE_NOTICE = "Segment compressé : la conversation continue dans un segment plus récent."
    const val NO_TIP_ERROR = "Cette conversation a été compressée et ne peut plus recevoir de message. Ouvrez sa suite depuis la liste."

    /** Returns a usable continuation distinct from [currentSessionId], or null. */
    fun target(candidate: String?, currentSessionId: String): String? =
        candidate?.trim()?.takeIf { it.isNotEmpty() && it != currentSessionId }

    /** Parses a 409 `session_rotated` refusal. Null when the error is not a rotation refusal. */
    fun rotation(error: Throwable): Rotation? {
        val http = error as? ApiError.Http ?: return null
        if (http.statusCode != 409) return null
        val payload = http.body
            ?.let { runCatching { HermesJson.decodeFromString<Payload>(it) }.getOrNull() }
            ?: return null
        if (payload.code != "session_rotated") return null
        return Rotation(payload.continuationSessionId?.trim()?.takeIf { it.isNotEmpty() })
    }

    data class Rotation(val continuationSessionId: String?)

    @Serializable
    private data class Payload(
        val code: String? = null,
        @SerialName("continuation_session_id") val continuationSessionId: String? = null,
    )
}
