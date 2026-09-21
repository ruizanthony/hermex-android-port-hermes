package com.uzairansar.hermex.ui.chat

import org.junit.Assert.*
import org.junit.Test

class StreamForegroundGuardTest {
    @Test fun deniedPromotionStopsOnceInsteadOfEscaping() {
        var stops = 0
        val guard = StreamForegroundGuard({ it is SecurityException }, { stops++ })
        val result = try {
            guard.ensure { throw SecurityException("fixture denial") }
        } catch (error: SecurityException) {
            fail("Foreground refusal escaped: ${error.javaClass.simpleName}")
        }
        assertEquals(false, result)
        assertEquals(1, stops)
    }

    @Test fun cancellationCannotBeMistakenForPlatformDenial() {
        val guard = StreamForegroundGuard({ true }, { fail("Cancellation was swallowed") })
        val cancellation = java.util.concurrent.CancellationException("cancelled")
        assertSame(cancellation, assertThrows(java.util.concurrent.CancellationException::class.java) {
            guard.ensure { throw cancellation }
        })
        assertFalse(guard.isStopped)
    }

    @Test fun unrelatedExceptionsAndFatalErrorsEscapeUnchanged() {
        val guard = StreamForegroundGuard({ it is SecurityException }, { fail("Unexpected shutdown") })
        val bug = IllegalArgumentException("invalid notification")
        assertSame(bug, assertThrows(IllegalArgumentException::class.java) { guard.ensure { throw bug } })
        val fatal = OutOfMemoryError("fixture only")
        assertSame(fatal, assertThrows(OutOfMemoryError::class.java) { guard.ensure { throw fatal } })
        assertTrue(guard.ensure {})
    }

    @Test fun aFreshServiceCanResumeAfterAnEarlierInstanceWasDenied() {
        val first = StreamForegroundGuard({ it is SecurityException }, {})
        assertFalse(first.ensure { throw SecurityException() })
        val fresh = StreamForegroundGuard({ it is SecurityException }, {})
        assertTrue(fresh.ensure {})
        assertFalse(fresh.isStopped)
    }

    @Test fun refusalIsLatchedBeforeCancelledJobsFinallyReenter() {
        var stops = 0
        lateinit var guard: StreamForegroundGuard
        guard = StreamForegroundGuard({ it is SecurityException }) {
            stops++
            assertFalse(guard.ensure { fail("Cancelled job finally promoted again") })
        }
        assertFalse(guard.ensure { throw SecurityException("fixture denial") })
        repeat(3) { assertFalse(guard.ensure { fail("Stopped service promoted again") }) }
        assertEquals(1, stops)
    }
}
