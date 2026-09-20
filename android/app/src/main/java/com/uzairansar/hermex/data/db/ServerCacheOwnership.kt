package com.uzairansar.hermex.data.db

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

data class TranscriptCacheToken(val generation: Long, val revision: Long)

class ServerCacheOwnership {
    private val transcriptRevisions = ConcurrentHashMap<Pair<String, String>, AtomicLong>()
    private val localOrders = ConcurrentHashMap<Pair<String, String>, AtomicLong>()
    private fun revision(server: String, session: String) = transcriptRevisions.getOrPut(server to session) { AtomicLong() }
    fun transcriptToken(server: String, session: String) = TranscriptCacheToken(generation(server), revision(server, session).get())
    fun reserveTranscriptSave(server: String, session: String): Long =
        localOrders.getOrPut(server to session) { AtomicLong() }.incrementAndGet()

    suspend fun writeTranscriptSnapshot(server: String, session: String, generation: Long, write: suspend () -> Unit): TranscriptCacheToken? {
        var token: TranscriptCacheToken? = null
        writeIfCurrent(server, generation) {
            val next = revision(server, session).incrementAndGet()
            write()
            token = TranscriptCacheToken(generation, next)
        }
        return token
    }

    suspend fun writeTranscriptIfCurrent(server: String, session: String, token: TranscriptCacheToken, order: Long, write: suspend () -> Unit) {
        writeIfCurrent(server, token.generation) {
            if (revision(server, session).get() == token.revision && localOrders[server to session]?.get() == order) write()
        }
    }

    private data class ServerState(
        val generation: AtomicLong = AtomicLong(0),
        val mutex: Mutex = Mutex(),
    )

    private val states = ConcurrentHashMap<String, ServerState>()

    fun generation(serverUrl: String): Long = state(serverUrl).generation.get()

    suspend fun writeIfCurrent(
        serverUrl: String,
        generation: Long,
        write: suspend () -> Unit,
    ): Boolean {
        val state = state(serverUrl)
        return state.mutex.withLock {
            if (state.generation.get() != generation) return@withLock false
            write()
            true
        }
    }

    suspend fun <T> readIfCurrent(
        serverUrl: String,
        generation: Long,
        read: suspend () -> T,
    ): T? {
        val state = state(serverUrl)
        return state.mutex.withLock {
            if (state.generation.get() != generation) return@withLock null
            read()
        }
    }

    suspend fun invalidateAndClear(
        serverUrl: String,
        clear: suspend () -> Unit,
    ) {
        val state = state(serverUrl)
        state.mutex.withLock {
            state.generation.incrementAndGet()
            clear()
        }
    }

    private fun state(serverUrl: String): ServerState = states.getOrPut(serverUrl, ::ServerState)
}
