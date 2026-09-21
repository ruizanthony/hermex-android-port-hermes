package com.uzairansar.hermex.ui.chat

/** Serializes foreground promotion with its terminal refusal transition. */
internal class StreamForegroundGuard(
    private val isDenial: (Exception) -> Boolean,
    private val onDenied: () -> Unit,
) {
    @Volatile
    var isStopped: Boolean = false
        private set

    @Synchronized
    fun ensure(promote: () -> Unit): Boolean {
        if (isStopped) return false
        return try {
            promote()
            true
        } catch (error: Exception) {
            if (error is java.util.concurrent.CancellationException || !isDenial(error)) throw error
            isStopped = true
            onDenied()
            false
        }
    }
}
