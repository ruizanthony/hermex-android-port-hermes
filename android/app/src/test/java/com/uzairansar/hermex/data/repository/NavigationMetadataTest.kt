package com.uzairansar.hermex.data.repository

import com.uzairansar.hermex.core.network.HermesApiClient
import com.uzairansar.hermex.data.db.ServerCacheOwnership
import kotlinx.coroutines.runBlocking
import mockwebserver3.*
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

class NavigationMetadataTest {
    @Test fun missingDetailForeignProfileAndProfileSwitchDuringReadNeverCreateNeighbors() = runBlocking {
        for (scenario in listOf("missing", "foreign", "switch")) {
            val server = MockWebServer()
            var profileReads = 0
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = MockResponse.Builder().body(
                    when (request.url.encodedPath) {
                        "/api/session" -> if (scenario == "missing") "{}" else
                            """{"session":{"session_id":"a","profile":"work"}}"""
                        "/api/profiles" -> {
                            profileReads++
                            val profile = if (scenario == "foreign" || scenario == "switch" && profileReads > 1) "other" else "work"
                            """{"active":"$profile"}"""
                        }
                        "/api/sessions" -> """{"sessions":[{"session_id":"a","profile":"work"}]}"""
                        else -> "{}"
                    }).build()
            }
            server.start()
            try {
                val repository = SessionRepository(HermesApiClient(server.url("/"), OkHttpClient()), RecordingCacheDao(), ServerCacheOwnership())
                val result = runCatching { repository.loadNavigationMetadata("a") }
                assertTrue("$scenario must not publish neighbors", result.isFailure)
                if (scenario != "missing") assertTrue(result.exceptionOrNull() is SessionRepository.NavigationProfileChanged)
            } finally { server.close() }
        }
    }

    @Test fun directLinkReadsOnlyMetadataAndKeepsItsProfileBoundary() = runBlocking {
        val requests = CopyOnWriteArrayList<RecordedRequest>()
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                return MockResponse.Builder().body(when (request.url.encodedPath) {
                    "/api/profiles" -> """{"active":"work"}"""
                    "/api/session" -> """{"session":{"session_id":"a","profile":"work"}}"""
                    "/api/sessions" -> """{"sessions":[{"session_id":"a","profile":"work"},{"session_id":"legacy","profile":"work","raw_source":"desktop","session_source":"other","relationship_type":"child_session","parent_session_id":"a"},{"session_id":"other","profile":"personal"},{"session_id":"unknown"}]}"""
                    else -> "{}"
                }).build()
            }
        }
        server.start()
        try {
            val repository = SessionRepository(HermesApiClient(server.url("/"), OkHttpClient()), RecordingCacheDao(), ServerCacheOwnership())
            val page = repository.loadNavigationMetadata("a")
            assertEquals("work", page.profile)
            assertEquals(setOf("a", "legacy"), page.sessions.map { it.sessionId }.toSet())
            assertTrue(requests.none { it.url.encodedPath == "/api/session" &&
                (it.url.queryParameter("messages") != "0" || it.url.queryParameter("resolve_model") != "0") })
            assertFalse(requests.any { it.method != "GET" })
        } finally { server.close() }
    }
}
