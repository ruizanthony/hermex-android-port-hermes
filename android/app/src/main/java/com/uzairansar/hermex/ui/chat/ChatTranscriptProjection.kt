package com.uzairansar.hermex.ui.chat

import com.uzairansar.hermex.core.model.ChatMessage

/** Reversible display projection. Ambiguous or unfinished human turns stay visible. */
internal fun List<ChatMessage>.visibleTranscriptIndices(showTechnical: Boolean): Set<Int> {
    if (showTechnical) return indices.toSet()
    val visible = linkedSetOf<Int>()
    var technicalOnly = false
    var humanPending = false
    forEachIndexed { index, message ->
        val event = message.role == "user" &&
            (message.displayKind == "process_wakeup" || message.source == "process_wakeup")
        when {
            event -> technicalOnly = !humanPending
            message.role == "user" -> {
                technicalOnly = false
                humanPending = true
                visible.add(index)
            }
            else -> {
                if (!technicalOnly) visible.add(index)
                if (!technicalOnly && message.role == "assistant" &&
                    message.displayText.isNotBlank() && message.toolCalls.isNullOrEmpty()) humanPending = false
                if (message.role == "tool" && !technicalOnly) humanPending = true
            }
        }
    }
    return visible
}
