package com.uzairansar.hermex.ui.chat

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex

/**
 * Coordinates chat conversation refreshes across foreground/background so that
 * reopening a conversation is always fast:
 *
 * - Foreground transition (app resumed from background): triggers ONE immediate
 *   refresh attempt, without waiting for the resumed screen loop's backoff.
 * - Background + active conversation (streaming): keeps refreshing at a bounded
 *   background cadence so the cache stays warm and reopening renders fresh
 *   content instantly.
 * - Background + inactive conversation: no background polling at all (existing
 *   battery contract unchanged).
 * - [isRefreshing] drives the top-bar spinner around coordinator attempts.
 *
 * The coordinator performs read-only snapshot refreshes; it never resets the
 * composer, never issues mutations and never fights the resumed screen loop
 * (attempts are serialized by the coordinator mutex and the ViewModel's
 * visibleRefreshMutex).
 */
class ChatForegroundRefreshCoordinator(
    private val scope: CoroutineScope,
    private val backgroundIntervalMillis: Long = DEFAULT_BACKGROUND_INTERVAL_MILLIS,
) {
    /** Performs one refresh attempt; returns true when the attempt ran. */
    var refreshAttempt: suspend () -> Boolean = { false }

    private val isRefreshingInternal = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = isRefreshingInternal

    private var foreground = false
    private var conversationActive = false
    private var backgroundLoopJob: Job? = null
    private val attemptMutex = Mutex()

    /** App came back to the foreground: one immediate attempt, then idle (the resumed screen loop drives cadence). */
    fun onAppForeground() {
        foreground = true
        backgroundLoopJob?.cancel()
        backgroundLoopJob = null
        scope.launch { runOneAttempt() }
    }

    /** App moved to the background: start the bounded loop only if the conversation is active. */
    fun onAppBackground() {
        foreground = false
        maybeStartBackgroundLoop()
    }

    /** Streaming state of the currently open conversation changed. */
    fun onConversationActive(active: Boolean) {
        conversationActive = active
        maybeStartBackgroundLoop()
    }

    private fun maybeStartBackgroundLoop() {
        if (foreground || !conversationActive) {
            backgroundLoopJob?.cancel()
            backgroundLoopJob = null
            return
        }
        if (backgroundLoopJob?.isActive == true) return
        backgroundLoopJob = scope.launch {
            while (true) {
                var slept = 0L
                while (slept < backgroundIntervalMillis) {
                    delay(BACKGROUND_POLL_STEP_MILLIS)
                    slept += BACKGROUND_POLL_STEP_MILLIS
                    if (foreground || !conversationActive) return@launch
                }
                runOneAttempt()
            }
        }
    }

    private suspend fun runOneAttempt() {
        if (!attemptMutex.tryLock()) return
        try {
            isRefreshingInternal.value = true
            try {
                refreshAttempt()
            } finally {
                isRefreshingInternal.value = false
            }
        } finally {
            attemptMutex.unlock()
        }
    }

    private companion object {
        const val DEFAULT_BACKGROUND_INTERVAL_MILLIS = 15_000L
        const val BACKGROUND_POLL_STEP_MILLIS = 500L
    }
}
