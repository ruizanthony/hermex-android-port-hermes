package com.uzairansar.hermex.core.model

import com.uzairansar.hermex.core.network.HermesJson
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class RuntimeAttributionTest {
    @Test fun legacyAndMalformedMessagesStayUnattributed() {
        for (source in listOf("{}", "{\"role\":\"assistant\",\"_usedModel\":{},\"_usedProvider\":false,\"_requestedModel\":42,\"_requestedProvider\":[]}")) {
            val message = HermesJson.decodeFromString<ChatMessage>(source)
            assertEquals(null, message.usedModel)
            assertEquals(null, message.usedProvider)
            assertEquals(null, message.requestedModel)
            assertEquals(null, message.requestedProvider)
            assertEquals(message, HermesJson.decodeFromString<ChatMessage>(HermesJson.encodeToString(message)))
        }
    }
    @Test fun messageAttributionSurvivesPersistedCacheRoundtrip() {
        val source = """{"role":"assistant","content":"Answer","_usedModel":"route-b","_usedProvider":"provider-b","_requestedModel":"route-a","_requestedProvider":"provider-a"}"""
        val message = HermesJson.decodeFromString<ChatMessage>(source)
        val persisted = HermesJson.encodeToString(message)
        val restored = HermesJson.decodeFromString<ChatMessage>(persisted)
        val encoded = HermesJson.parseToJsonElement(HermesJson.encodeToString(restored)).jsonObject
        for ((key, expected) in mapOf("_usedModel" to "route-b", "_usedProvider" to "provider-b", "_requestedModel" to "route-a", "_requestedProvider" to "provider-a")) {
            assertEquals("Persisted attribution $key", expected, encoded[key]?.jsonPrimitive?.content)
        }
    }
}
