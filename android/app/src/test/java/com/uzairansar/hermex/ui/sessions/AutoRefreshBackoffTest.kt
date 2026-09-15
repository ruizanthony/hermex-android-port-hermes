package com.uzairansar.hermex.ui.sessions

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Backoff policy for the auto-refresh loops (sessions list + chat).
 * Failure doubles the interval, capped at 60s; any success resets it to base.
 * Pure logic, no Android dependencies.
 */
class AutoRefreshBackoffTest {

    @Test
    fun failureDoublesIntervalUpToCap() {
        var policy = AutoRefreshBackoffPolicy(baseIntervalMillis = 5_000, maxIntervalMillis = 60_000)
        assertEquals(5_000L, policy.currentIntervalMillis)
        assertEquals(10_000L, policy.onFailure())
        assertEquals(20_000L, policy.onFailure())
        assertEquals(40_000L, policy.onFailure())
        assertEquals(60_000L, policy.onFailure())
        assertEquals(60_000L, policy.onFailure()) // capped
    }

    @Test
    fun successResetsToBase() {
        val policy = AutoRefreshBackoffPolicy(baseIntervalMillis = 5_000, maxIntervalMillis = 65_000)
        policy.onFailure(); policy.onFailure(); policy.onFailure()
        assertEquals(40_000L, policy.currentIntervalMillis)
        assertEquals(5_000L, policy.onSuccess())
    }
}
