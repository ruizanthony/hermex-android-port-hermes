package com.uzairansar.hermex.ui.chat

/** Metadata-only snapshot: preserve the list's order, never sort transcripts here. */
class ActiveConversationNavigation(ids: List<String>) {
    val ids: List<String> = ids.filter { it.isNotBlank() }.distinct()

    fun neighbor(currentId: String, next: Boolean): String? {
        val index = ids.indexOf(currentId)
        if (index < 0) return null
        return ids.getOrNull(index + if (next) 1 else -1)
    }
}
