package com.uzairansar.hermex.ui.chat

import com.uzairansar.hermex.core.model.ChatMessage
import com.uzairansar.hermex.core.model.ContextWindowSnapshot
import com.uzairansar.hermex.core.model.RuntimeModelSnapshot

/** Pure server-evidence projection. Never consult the configured/requested model as a substitute. */
data class RuntimeModelPresentation(
    val model: String,
    val provider: String?,
    val requestedModel: String?,
    val requestedProvider: String?,
    val hasObservedOutput: Boolean,
    val fallbackActive: Boolean,
) {
    companion object {
        fun fromRuntime(snapshot: RuntimeModelSnapshot?, sessionId: String?, streamId: String?): RuntimeModelPresentation? {
            if (snapshot == null || !snapshot.validFor(sessionId, streamId)) return null
            return RuntimeModelPresentation(
                model = snapshot.model!!,
                provider = snapshot.provider.nonBlankEvidence(),
                requestedModel = null,
                requestedProvider = null,
                hasObservedOutput = snapshot.hasObservedOutput,
                fallbackActive = snapshot.hasObservedOutput && snapshot.fallbackActive == true,
            )
        }

        fun fromMessage(message: ChatMessage): RuntimeModelPresentation? =
            if (message.role != "assistant") null else attribution(
                message.usedModel, message.usedProvider, message.requestedModel, message.requestedProvider,
            )

        fun fromUsage(usage: ContextWindowSnapshot): RuntimeModelPresentation? = attribution(
            usage.usedModel, usage.usedProvider, usage.requestedModel, usage.requestedProvider,
        )

        private fun attribution(model: String?, provider: String?, requestedModel: String?, requestedProvider: String?): RuntimeModelPresentation? {
            return RuntimeModelPresentation(
                model = model.nonBlankEvidence() ?: return null,
                provider = provider.nonBlankEvidence(),
                requestedModel = requestedModel.nonBlankEvidence(),
                requestedProvider = requestedProvider.nonBlankEvidence(),
                hasObservedOutput = true,
                // The server emits requested_model only on confirmed fallback; equality is irrelevant.
                fallbackActive = requestedModel.nonBlankEvidence() != null,
            )
        }
    }
}

private fun String?.nonBlankEvidence(): String? = this?.takeIf { it.isNotBlank() }
