package com.uzairansar.hermex.core.network

import org.junit.Assert.*
import org.junit.Test

class RuntimeModelEventTest {
    @Test fun runtimeMetadataIsRecognized() {
        assertEquals("RuntimeModel", SseEventDecoder.decode("runtime_model", """{"session_id":"s","stream_id":"t","model":"b","phase":"observed_output"}""").javaClass.simpleName)
    }
    @Test fun warningIsRecognizedWithoutTerminatingStream() {
        assertEquals("Warning", SseEventDecoder.decode("warning", """{"type":"fallback","message":"Trying another route"}""").javaClass.simpleName)
    }
    @Test fun malformedMetadataNeverBecomesTransportFailure() {
        for (type in listOf("runtime_model", "warning")) for (data in listOf("{", "[]", "null", "{\"model\":{},\"type\":[],\"message\":{}}")) {
            assertFalse(SseEventDecoder.decode(type, data) is SseEvent.TransportError)
        }
        assertEquals(SseEvent.Token("ok"), SseEventDecoder.decode("token", """{"text":"ok"}"""))
    }
}
