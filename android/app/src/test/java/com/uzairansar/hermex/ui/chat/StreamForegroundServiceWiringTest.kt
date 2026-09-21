package com.uzairansar.hermex.ui.chat

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** JVM wiring contracts complement device tests; these do not emulate Android. */
class StreamForegroundServiceWiringTest {
    private val source = File("src/main/java/com/uzairansar/hermex/ui/chat/StreamRecoveryService.kt").readText()

    @Test fun startCallbackDoesNotLaunchMonitorsAfterRefusal() {
        assertTrue("Deferred callback must check foreground admission before launching monitors",
            source.contains("if (!ensureForeground(records.first())) return START_NOT_STICKY"))
    }

    @Test fun refusalFromAnImmediatelyFinishingMonitorCannotReturnSticky() {
        assertTrue("Immediate monitor cleanup can deny promotion before onStartCommand returns",
            source.contains("return if (stopping) START_NOT_STICKY else START_STICKY"))
    }

    @Test fun everyPromotionUsesTheGuardAndShutdownPreservesDurableState() {
        assertTrue("Promotion must be centrally guarded", source.contains("foregroundGuard.ensure {"))
        val shutdown = source.substringAfter("private fun suspendRecovery() {").substringBefore("private fun ensureForeground")
        assertTrue("Refusal must detach, not delete the shared business notification",
            shutdown.contains("STOP_FOREGROUND_DETACH"))
        assertFalse(shutdown.contains("store.remove"))
        assertFalse(shutdown.contains("notifier.clear"))
        assertTrue(shutdown.indexOf("stopping = true") < shutdown.indexOf("scope.cancel()"))
    }
}
