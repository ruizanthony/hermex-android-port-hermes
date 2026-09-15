package com.uzairansar.hermex.ui.sessions

/**
 * Backoff policy shared by the visible auto-refresh loops (sessions list, chat).
 *
 * Base cadence is 5s while the server answers; each failure doubles the interval
 * up to [maxIntervalMillis]; any success resets it to the base. The loops stay
 * silent on errors (list keeps cached content, chat keeps transcript) so a slow
 * or unreachable server degrades cadence instead of spamming the UI.
 */
class AutoRefreshBackoffPolicy(
    private val baseIntervalMillis: Long,
    private val maxIntervalMillis: Long,
) {
    private var currentIntervalMillisInternal: Long = baseIntervalMillis

    val currentIntervalMillis: Long get() = currentIntervalMillisInternal

    /** Registers a failed refresh and returns the new interval. */
    fun onFailure(): Long {
        currentIntervalMillisInternal = (currentIntervalMillisInternal * 2).coerceAtMost(maxIntervalMillis)
        return currentIntervalMillisInternal
    }

    /** Registers a successful refresh and returns the reset interval. */
    fun onSuccess(): Long {
        currentIntervalMillisInternal = baseIntervalMillis
        return currentIntervalMillisInternal
    }
}
