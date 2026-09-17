package com.uzairansar.hermex.ui.chat

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ChatForegroundRefreshCoordinator contract:
 *
 * - Foreground transition triggers an immediate refresh attempt (fast reopen).
 * - Background + active conversation keeps refreshing at a bounded cadence.
 * - Background + inactive conversation never polls (battery contract).
 * - isRefreshing wraps coordinator attempts (top-bar spinner).
 *
 * Uses real short delays on a dedicated dispatcher (same pragmatic style as
 * the repository's coroutine tests, no virtual scheduler).
 */
class ChatForegroundRefreshCoordinatorTest {

    private fun newScope() = CoroutineScope(Dispatchers.Default + Job())

    @Test
    fun foregroundTransitionTriggersImmediateRefresh() {
        val scope = newScope()
        try {
            val coordinator = ChatForegroundRefreshCoordinator(scope = scope)
            var attempts = 0
            coordinator.refreshAttempt = { attempts += 1; true }
            coordinator.onAppForeground()
            waitFor({ attempts >= 1 })
            assertEquals(1, attempts)
        } finally { scope.cancel() }
    }

    @Test
    fun activeConversationKeepsRefreshingInBackground() {
        val scope = newScope()
        try {
            val coordinator = ChatForegroundRefreshCoordinator(
                scope = scope,
                backgroundIntervalMillis = 200L,
            )
            var attempts = 0
            coordinator.refreshAttempt = { attempts += 1; true }
            coordinator.onAppBackground()
            coordinator.onConversationActive(true)
            waitFor({ attempts >= 2 })
            assertTrue(attempts >= 2)
        } finally { scope.cancel() }
    }

    @Test
    fun inactiveConversationInBackgroundDoesNotRefresh() {
        val scope = newScope()
        try {
            val coordinator = ChatForegroundRefreshCoordinator(
                scope = scope,
                backgroundIntervalMillis = 200L,
            )
            var attempts = 0
            coordinator.refreshAttempt = { attempts += 1; true }
            coordinator.onAppBackground()
            coordinator.onConversationActive(false)
            Thread.sleep(700)
            assertEquals(0, attempts)
        } finally { scope.cancel() }
    }

    @Test
    fun isRefreshingFlagLifecycleAroundAttempt() {
        val scope = newScope()
        try {
            val coordinator = ChatForegroundRefreshCoordinator(scope = scope)
            val observed = mutableListOf<Boolean>()
            var release = kotlinx.coroutines.CompletableDeferred<Unit>()
            coordinator.refreshAttempt = {
                observed.add(coordinator.isRefreshing.value)
                release.await()
                true
            }
            coordinator.onAppForeground()
            waitFor({ observed.isNotEmpty() })
            assertTrue(observed.first())
            assertTrue(coordinator.isRefreshing.value)
            release.complete(Unit)
            waitFor({ !coordinator.isRefreshing.value })
            assertFalse(coordinator.isRefreshing.value)
        } finally { scope.cancel() }
    }

    @Test
    fun backgroundLoopStopsWhenConversationBecomesInactive() {
        val scope = newScope()
        try {
            val coordinator = ChatForegroundRefreshCoordinator(
                scope = scope,
                backgroundIntervalMillis = 200L,
            )
            var attempts = 0
            coordinator.refreshAttempt = { attempts += 1; true }
            coordinator.onAppBackground()
            coordinator.onConversationActive(true)
            waitFor({ attempts >= 1 })
            coordinator.onConversationActive(false)
            val before = attempts
            Thread.sleep(700)
            assertEquals(before, attempts)
        } finally { scope.cancel() }
    }

    @Test
    fun backgroundLoopStopsWhenAppReturnsToForeground() {
        val scope = newScope()
        try {
            val coordinator = ChatForegroundRefreshCoordinator(
                scope = scope,
                backgroundIntervalMillis = 200L,
            )
            var attempts = 0
            coordinator.refreshAttempt = { attempts += 1; true }
            coordinator.onAppBackground()
            coordinator.onConversationActive(true)
            waitFor({ attempts >= 1 })
            coordinator.onAppForeground()
            val before = attempts
            waitFor({ attempts == before + 1 })
            Thread.sleep(500)
            // Exactly the single foreground attempt: background cadence is stopped.
            assertEquals(before + 1, attempts)
        } finally { scope.cancel() }
    }

    private fun waitFor(condition: () -> Boolean, timeoutMillis: Long = 5000) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertTrue("condition not reached within ${timeoutMillis}ms", condition())
    }
}
