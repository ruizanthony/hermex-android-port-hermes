package com.uzairansar.hermex.ui.chat

import com.uzairansar.hermex.core.model.*
import com.uzairansar.hermex.core.network.*
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test

class RuntimeModelPresentationTest {
    private val observed = RuntimeModelSnapshot("s", "t", "b", "p", true, "observed_output")

    @Test fun exactIdentityAndKnownPhaseAreRequired() {
        assertTrue(observed.validFor("s", "t"))
        for (bad in listOf(observed.copy(sessionId = "other"), observed.copy(streamId = "old"), observed.copy(sessionId = " s"), observed.copy(phase = "attempt"), observed.copy(model = ""))) {
            assertFalse(bad.validFor("s", "t"))
            assertNull(RuntimeModelPresentation.fromRuntime(bad, "s", "t"))
        }
        assertFalse(RuntimeModelSnapshot().validFor(null, null))
    }
    @Test fun routeObservationIsNotOutputOrActiveFallbackProof() {
        val route = observed.copy(phase = "route_observed")
        assertTrue(route.validFor("s", "t"))
        assertFalse(route.hasObservedOutput)
        val presentation = RuntimeModelPresentation.fromRuntime(route, "s", "t")!!
        assertFalse(presentation.hasObservedOutput)
        assertFalse(presentation.fallbackActive)
    }
    @Test fun observedChainProjectsOnlyLatestMatchingEvidence() {
        val chain = listOf(observed.copy(model = "a", fallbackActive = false), observed.copy(model = "b"), observed.copy(model = "c"))
        assertEquals(listOf("a", "b", "c"), chain.map { RuntimeModelPresentation.fromRuntime(it, "s", "t")!!.model })
        assertTrue(RuntimeModelPresentation.fromRuntime(chain.last(), "s", "t")!!.fallbackActive)
    }
    @Test fun attributionRequiresUsedModelAndServerFallbackMarkerNotComparison() {
        assertNull(RuntimeModelPresentation.fromUsage(ContextWindowSnapshot(usedProvider = "p")))
        val providerFallback = RuntimeModelPresentation.fromUsage(ContextWindowSnapshot(usedModel = "same", usedProvider = "p2", requestedModel = "same", requestedProvider = "p1"))!!
        assertTrue(providerFallback.fallbackActive)
        assertEquals("p2", providerFallback.provider)
        assertEquals("p1", providerFallback.requestedProvider)
        assertNull(RuntimeModelPresentation.fromMessage(ChatMessage(role = "assistant")))
        assertNull(RuntimeModelPresentation.fromUsage(ContextWindowSnapshot(requestedModel = "a")))
        assertNull(RuntimeModelPresentation.fromMessage(ChatMessage(role = "user", usedModel = "b")))
        assertFalse(RuntimeModelPresentation.fromUsage(ContextWindowSnapshot(usedModel = "b"))!!.fallbackActive)
        assertTrue(RuntimeModelPresentation.fromUsage(ContextWindowSnapshot(usedModel = "same", requestedModel = "same"))!!.fallbackActive)
        assertTrue(RuntimeModelPresentation.fromMessage(ChatMessage(role = "assistant", usedModel = "b", requestedModel = "a"))!!.hasObservedOutput)
    }
    @Test fun journalAndTerminalUsageSurviveSerialization() {
        val detail = HermesJson.decodeFromString<SessionDetail>("""{"session_id":"s","runtime_journal_snapshot":{"runtime_model":{"session_id":"s","stream_id":"t","model":"b","provider":"p","fallback_active":true,"phase":"observed_output"},"future":{}}}""")
        assertEquals(observed, HermesJson.decodeFromString<SessionDetail>(HermesJson.encodeToString(detail)).runtimeJournalSnapshot!!.runtimeModel)
        val done = SseEventDecoder.decode("done", """{"usage":{"used_model":"b","used_provider":"p","requested_model":"a","requested_provider":"q"}}""") as SseEvent.Done
        assertEquals(done.usage, HermesJson.decodeFromString<ContextWindowSnapshot>(HermesJson.encodeToString(done.usage!!)))
        assertEquals("a", RuntimeModelPresentation.fromUsage(done.usage)!!.requestedModel)
    }
    @Test fun malformedOptionalEvidenceIsAbsentWithoutLosingTerminalEvent() {
        val done = SseEventDecoder.decode("done", """{"session":{"session_id":"s","runtime_journal_snapshot":{"runtime_model":[]}},"usage":{"used_model":{},"requested_model":false,"input_tokens":12}}""") as SseEvent.Done
        assertEquals("s", done.session!!.sessionId)
        assertNull(done.session.runtimeJournalSnapshot)
        assertNull(done.usage!!.usedModel)
        assertNull(done.usage.requestedModel)
        assertEquals(12, done.usage.inputTokens)
        assertNull(HermesJson.decodeFromString<SessionDetail>("{}").runtimeJournalSnapshot)
        assertNull(RuntimeModelPresentation.fromRuntime(null, "s", "t"))
    }
}
