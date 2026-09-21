package com.uzairansar.hermex.ui.chat

/** Pre-gesture order, skip every optimistically hidden proven member. Never wrap. */
internal fun archiveSuccessor(before: List<String>, current: String, hidden: Set<String>): String? {
    val ids = before.filter(String::isNotBlank).distinct()
    val index = ids.indexOf(current)
    if (index < 0) return null
    return ids.drop(index + 1).firstOrNull { it !in hidden }
        ?: ids.take(index).lastOrNull { it !in hidden }
}
