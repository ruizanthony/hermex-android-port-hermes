package com.uzairansar.hermex.data.repository

import com.uzairansar.hermex.core.model.*
import com.uzairansar.hermex.core.network.HermesJson
import org.junit.Assert.*
import org.junit.Test

class DelegationEvidenceTest {
    private val child = SessionSummary(sessionId="child", parentSessionId="parent", relationshipType="child_session", rawSource="desktop", profile="default", createdAt=100.5)
    private val parent = SessionDetail(sessionId="parent", profile="default", messages=listOf(
        HermesJson.decodeFromString<ChatMessage>("""{"role":"assistant","timestamp":100,"tool_calls":[{"id":"call","function":{"name":"delegate_task","arguments":{"tasks":[{"goal":"Check fixture"}]}}}]}""")
    ))
    private val detail = SessionDetail(sessionId="child", createdAt=100.5, messages=listOf(ChatMessage(role="user",content="Check fixture",timestamp=100.4)))

    @Test fun restoresOnlyMatchingCachedProofAndBoundsProgressiveReads() = kotlinx.coroutines.test.runTest {
        val enricher = DelegationEnricher()
        enricher.restore(listOf(child.copy(confirmedDelegationParentId="parent")))
        val restored=enricher.enrich(listOf(child)) { error("No network needed for proven immutable identity") }
        assertTrue(restored.single().isListSubagent)
        assertFalse(enricher.enrich(listOf(child.copy(parentSessionId="other"))) { null }.single().isListSubagent)
        val many=(1..20).map { child.copy(sessionId="child-$it") }
        var calls=0
        val bounded=DelegationEnricher()
        bounded.enrich(many) { calls++; null }
        assertEquals(8,calls)
        bounded.enrich(many) { calls++; null }
        assertEquals(16,calls)
    }

    @Test fun keepsForksMissingProofsProfilesAndCompressedHistoryVisible() {
        for (row in listOf(child.copy(parentSessionId=null), child.copy(relationshipType="fork"), child.copy(profile="other"), child.copy(preCompressionSnapshot=true), child.copy(continuationSessionId="next"), child.copy(createdAt=200.0))) {
            assertFalse(confirmsDelegation(row, detail, parent))
        }
        assertFalse(confirmsDelegation(child, detail.copy(messagesOffset=25), parent))
        assertFalse(confirmsDelegation(child, detail.copy(messages=listOf(ChatMessage(role="user",content="Check fixture",timestamp=50.0))), parent))
        assertFalse(confirmsDelegation(child, detail, parent.copy(messages=emptyList())))
    }

    @Test fun onlyConfirmedRowsAreHiddenAndCacheRetainsRawIdentity() = kotlinx.coroutines.test.runTest {
        val ordinary = child.copy(sessionId="ordinary", parentSessionId=null)
        val enricher = DelegationEnricher()
        val rows = enricher.enrich(listOf(child,ordinary)) { if(it=="child") detail else parent }
        val hidden = rows.first()
        assertEquals("desktop",hidden.rawSource)
        val cached = com.uzairansar.hermex.data.db.CachedSessionEntity.from("https://fixture/",hidden)!!.toSummary()
        assertEquals(listOf(ordinary),com.uzairansar.hermex.ui.sessions.SessionListUiState(sessions=listOf(cached,ordinary)).visibleSessions)
        val revealed = com.uzairansar.hermex.ui.sessions.SessionListUiState(sessions=listOf(cached,ordinary),sessionRowDisplaySettings=com.uzairansar.hermex.data.preferences.SessionRowDisplaySettings(showSubagentSessions=true))
        assertEquals(2,revealed.visibleSessions.size)
        assertFalse(cached.isSessionReadOnly) // hiding does not change capabilities
    }

    @Test fun matchesActualParentDelegationInsteadOfDesktopTitle() {
        assertTrue(confirmsDelegation(child, detail, parent))
        assertTrue(confirmsDelegation(child.copy(title="Any title"), detail, parent))
        assertFalse(confirmsDelegation(child, detail.copy(messages=listOf(ChatMessage(role="user",content="Unrelated",timestamp=100.4))), parent))
    }
}
