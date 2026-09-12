package com.uzairansar.hermex.ui.chat

import com.uzairansar.hermex.core.model.ChatMessage
import com.uzairansar.hermex.core.network.HermesJson
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test

class ChatTranscriptProjectionTest {
    @Test fun hidesStandaloneTechnicalReplyButPreservesUnfinishedHumanTurn() {
        val human=ChatMessage(role="user",content="question")
        val answer=ChatMessage(role="assistant",content="réponse")
        val event=ChatMessage(role="user",content="event",source="process_wakeup")
        val tool=ChatMessage(role="tool",content="result")
        assertEquals(setOf(0,1,4),listOf(human,answer,event,answer,human).visibleTranscriptIndices(false))
        assertEquals(setOf(0,1,3),listOf(human,tool,event,answer).visibleTranscriptIndices(false))
    }

    @Test fun hidesTechnicalNotificationButRetainsHumanQuoteAcrossSerialization() {
        val event = HermesJson.decodeFromString<ChatMessage>("""{"role":"user","content":"notification","display_kind":"process_wakeup"}""")
        val restored = HermesJson.decodeFromString<ChatMessage>(HermesJson.encodeToString(event))
        val human = ChatMessage(role="user",content="Explique [ASYNC DELEGATION BATCH COMPLETE] sans supprimer ma question")
        assertEquals(setOf(1), listOf(restored,human).visibleTranscriptIndices(false))
        assertEquals(setOf(0,1), listOf(restored,human).visibleTranscriptIndices(true))
    }
}
