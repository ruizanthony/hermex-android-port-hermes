package com.uzairansar.hermex.data.repository

import com.uzairansar.hermex.core.model.ChatMessage
import com.uzairansar.hermex.core.network.*
import com.uzairansar.hermex.data.db.*
import kotlinx.coroutines.*
import mockwebserver3.*
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test

class TranscriptSaveOrderingTest {
    @Test fun missingSessionPurgesAndRejectsOldExitSave() = runBlocking {
        val owner = ServerCacheOwnership(); val dao = RecordingCacheDao()
        MockWebServer().use { server ->
            server.enqueue(MockResponse.Builder().code(404).body("{}").build()); server.start()
            val http = OkHttpClient()
            val repo = ChatRepository(HermesApiClient(server.url("/"),http),dao,owner,SseStreamClient(server.url("/"),http){emptyList()})
            val token = owner.transcriptToken(server.url("/").toString(),"s")
            repo.loadSessionSnapshot("s")
            repo.enqueueTranscriptCache("s",listOf(ChatMessage(id="old",role="assistant")),token)!!.join()
            assertTrue(dao.replacedMessageBatches.isEmpty())
        }
    }

    @Test fun oldScreenCannotOverwriteNewSnapshotFromAnotherRepository() = runBlocking {
        val dao = RecordingCacheDao()
        val owner = ServerCacheOwnership()
        MockWebServer().use { server ->
            server.enqueue(MockResponse.Builder().code(200).body("""{"session":{"session_id":"s","messages":[{"id":"new","role":"assistant","content":"New answer"}]}}""").build())
            server.start()
            val http = OkHttpClient()
            fun repo() = ChatRepository(HermesApiClient(server.url("/"),http),dao,owner,SseStreamClient(server.url("/"),http){emptyList()})
            val old = repo()
            val token = owner.transcriptToken(server.url("/").toString(),"s")
            repo().loadSessionSnapshot("s")
            old.enqueueTranscriptCache("s",listOf(ChatMessage(id="old",role="assistant")),token)!!.join()
            assertEquals(1,dao.replacedMessageBatches.size)
            assertEquals("new",dao.replacedMessageBatches.single().single().toMessage()!!.id)
        }
    }

    @Test fun invalidationRejectsExitSaveWithoutResurrectingClearedData() = runBlocking {
        val owner=ServerCacheOwnership(); val dao=RecordingCacheDao()
        MockWebServer().use { server ->
            server.start(); val http=OkHttpClient(); val url=server.url("/").toString()
            val repo=ChatRepository(HermesApiClient(server.url("/"),http),dao,owner,SseStreamClient(server.url("/"),http){emptyList()})
            val token=owner.transcriptToken(url,"s")
            owner.invalidateAndClear(url) { dao.clearServer(url) }
            repo.enqueueTranscriptCache("s",listOf(ChatMessage(id="old",role="assistant")),token)!!.join()
            assertTrue(dao.replacedMessageBatches.isEmpty())
        }
    }

    @Test fun latestReservationWinsAndServersRemainIsolated() = runBlocking {
        val owner=ServerCacheOwnership()
        val token=owner.transcriptToken("server","s")
        val old=owner.reserveTranscriptSave("server","s")
        val latest=owner.reserveTranscriptSave("server","s")
        val writes=mutableListOf<String>()
        owner.writeTranscriptIfCurrent("server","s",token,latest){writes.add("latest")}
        owner.writeTranscriptIfCurrent("server","s",token,old){writes.add("old")}
        val other=owner.transcriptToken("other","s")
        val order=owner.reserveTranscriptSave("other","s")
        owner.invalidateAndClear("server"){}
        owner.writeTranscriptIfCurrent("other","s",other,order){writes.add("other")}
        assertEquals(listOf("latest","other"),writes)
    }

    @Test fun emptyAuthoritativeSnapshotAlsoFencesOldSave() = runBlocking {
        val owner=ServerCacheOwnership(); val old=owner.transcriptToken("server","s")
        val order=owner.reserveTranscriptSave("server","s")
        owner.writeTranscriptSnapshot("server","s",old.generation){}
        var wrote=false
        owner.writeTranscriptIfCurrent("server","s",old,order){wrote=true}
        assertFalse(wrote)
    }
}
