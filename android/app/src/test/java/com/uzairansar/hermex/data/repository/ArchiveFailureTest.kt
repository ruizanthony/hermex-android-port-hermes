package com.uzairansar.hermex.data.repository

import com.uzairansar.hermex.core.network.HermesApiClient
import com.uzairansar.hermex.data.db.ServerCacheOwnership
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockWebServer
import mockwebserver3.MockResponse
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test

class ArchiveFailureTest {
    @Test fun partialSuccessStopsAtFirstFailureWithoutClaimingRollback() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse.Builder().code(200).body("{\"ok\":true}").build())
            server.enqueue(MockResponse.Builder().code(503).body("{}").build()); server.start()
            val repository = SessionRepository(HermesApiClient(server.url("/"), OkHttpClient()), RecordingCacheDao(), ServerCacheOwnership())
            val error = repository.archiveChain(listOf("first", "second", "third"), true)
            assertFalse(error.isNullOrBlank())
            assertEquals(2, server.requestCount)
            assertTrue(server.takeRequest().body!!.utf8().contains("first"))
            assertTrue(server.takeRequest().body!!.utf8().contains("second"))
        }
    }

    @Test fun httpFailureIsVisibleAndStopsTheChain() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse.Builder().code(503).body("""{"error":"Unavailable"}""").build())
            server.start()
            val repository = SessionRepository(HermesApiClient(server.url("/"), OkHttpClient()), RecordingCacheDao(), ServerCacheOwnership())
            val error = repository.archiveChain(listOf("first", "second"), true)
            assertFalse("Transport failure must become a visible action error", error.isNullOrBlank())
            assertEquals(1, server.requestCount)
        }
    }
}
