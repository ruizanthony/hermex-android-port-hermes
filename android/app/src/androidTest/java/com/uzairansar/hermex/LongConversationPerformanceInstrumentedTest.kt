package com.uzairansar.hermex

import android.app.Application
import android.os.Debug
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.uzairansar.hermex.ui.chat.ChatRoute
import com.uzairansar.hermex.ui.theme.HermexTheme
import kotlinx.serialization.json.*
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Real chat route, synthetic history, no account and no user data. */
@RunWith(AndroidJUnit4::class)
class LongConversationPerformanceInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val server = MockWebServer()
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val sampler = Executors.newSingleThreadScheduledExecutor()
    private val peakRss = AtomicLong()
    private val peakNative = AtomicLong()
    private val loadingHeartbeat = AtomicLong()
    private val heartbeatPending = AtomicBoolean()

    @After fun close() {
        sampler.shutdownNow()
        compose.runOnUiThread { compose.activity.setContent {} }
        server.close()
    }

    @Test fun longConversationBoundsVisibleTextAndKeepsMainThreadResponsive() {
        val arguments = InstrumentationRegistry.getArguments()
        val characters = (arguments.getString("characters")?.toInt() ?: 12000).coerceIn(8000, 2000000)
        val historyCount = (arguments.getString("historyCount")?.toInt() ?: 100).coerceIn(1, 2000)
        val shape = arguments.getString("shape") ?: "prose"
        val role = arguments.getString("role") ?: "user"
        val body = if (shape == "unbroken") "W".repeat(characters)
            else "Texte de validation industrielle sans information privee. ".repeat(characters / 56 + 1).take(characters)
        val document = "LONG_FIXTURE_BEGIN " + body + " LONG_FIXTURE_END"
        val messages = buildJsonArray {
            repeat(historyCount) { index -> add(buildJsonObject {
                put("id", "short-$index"); put("role", if (index % 2 == 0) "user" else "assistant")
                put("content", "Message synthetique $index : controle du suivi de production.")
            }) }
            add(buildJsonObject { put("id", "long"); put("role", role); put("content", document) })
        }
        val row = buildJsonObject {
            put("session_id", "fixture-long-conversation"); put("title", "Validation longue conversation")
            put("source", "webui"); put("messages", messages)
        }
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = MockResponse.Builder().code(200).body(
                if (request.url.encodedPath == "/api/session") buildJsonObject { put("session", row) }.toString() else "{}"
            ).build()
        }
        server.start()
        val container = AppContainer(app)
        val start = SystemClock.elapsedRealtime()
        sampler.scheduleAtFixedRate({
            readRss()?.let { value -> peakRss.updateAndGet { maxOf(it, value) } }
            peakNative.updateAndGet { maxOf(it, Debug.getNativeHeapAllocatedSize()) }
            if (heartbeatPending.compareAndSet(false, true)) {
                val posted = SystemClock.elapsedRealtime()
                Handler(Looper.getMainLooper()).post {
                    loadingHeartbeat.updateAndGet { maxOf(it, SystemClock.elapsedRealtime() - posted) }
                    heartbeatPending.set(false)
                }
            }
        }, 0, 50, TimeUnit.MILLISECONDS)
        compose.runOnUiThread { compose.activity.setContent { HermexTheme {
            ChatRoute(sessionId = "fixture-long-conversation", serverId = server.url("/").toString(),
                repository = container.chatRepository(server.url("/")), onBack = {}, onOpenWorkspace = {}, onOpenGit = {})
        } } }
        compose.waitUntil(30000) {
            compose.onAllNodes(hasText("LONG_FIXTURE_BEGIN", substring = true), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitForIdle()
        val readyMillis = SystemClock.elapsedRealtime() - start
        val textLengths = compose.onAllNodes(isRoot(), useUnmergedTree = true).fetchSemanticsNodes()
            .flatMap { root -> flatten(root) }.flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty() }.map { it.text.length }
        val largestText = textLengths.maxOrNull() ?: 0
        var readerEndMillis: Long? = null
        // Baseline still writes its measured RED evidence. Candidate also proves full access.
        if (largestText <= 5000) {
            val readerStart = SystemClock.elapsedRealtime()
            compose.onNodeWithTag("transcript_read_full").performScrollTo().performClick()
            compose.onNodeWithTag("transcript_reader_end").performClick()
            compose.onNodeWithText("LONG_FIXTURE_END", substring = true).assertExists()
            readerEndMillis = SystemClock.elapsedRealtime() - readerStart
            val chunks = compose.onAllNodes(SemanticsMatcher("reader chunks") {
                it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("transcript_chunk_") == true
            }, useUnmergedTree = true).fetchSemanticsNodes()
            assertTrue("Reader must compose visible chunks, not the entire document", chunks.size in 1..20)
            assertTrue(chunks.flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty() }.all { it.text.length <= 2000 })
            compose.onNodeWithTag("transcript_reader_close").performClick()
        }
        val delays = mutableListOf<Long>()
        repeat(25) {
            val latch = CountDownLatch(1)
            val sent = SystemClock.elapsedRealtime()
            Handler(Looper.getMainLooper()).post { delays += SystemClock.elapsedRealtime() - sent; latch.countDown() }
            assertTrue("Main thread did not answer within two seconds", latch.await(2, TimeUnit.SECONDS))
        }
        val memory = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }
        val rss = readRss()
        val report = buildJsonObject {
            put("characters", document.length); put("historyCount", historyCount); put("readyMillis", readyMillis)
            put("shape", shape); put("role", role); put("readerEndMillis", readerEndMillis)
            put("largestSemanticsText", largestText); put("totalPssKiB", memory.totalPss)
            put("nativeAllocatedBytes", Debug.getNativeHeapAllocatedSize()); put("rssKiB", rss)
            put("mainHeartbeatMaxMillis", delays.maxOrNull()); put("mainHeartbeatSamples", delays.size)
            put("sampledPeakRssKiB", peakRss.get()); put("sampledPeakNativeAllocatedBytes", peakNative.get())
            put("loadingHeartbeatMaxMillis", loadingHeartbeat.get()); put("memorySamplingIntervalMillis", 50)
        }
        File(app.getExternalFilesDir(null), "long-conversation-$characters.json").writeText(report.toString())
        android.util.Log.i("HermexLongTextMeasure", report.toString())
        val image = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(app.getExternalFilesDir(null), "long-conversation-$characters.png").outputStream().use {
            image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        image.recycle()
        assertTrue("A single visible text still lays out $largestText characters", largestText <= 5000)
        assertTrue("The long document must have a visible bounded preview", largestText > 0)
    }

    private fun readRss(): Long? = File("/proc/self/status").useLines { lines ->
        lines.firstOrNull { it.startsWith("VmRSS:") }?.split(Regex("\\s+"))?.getOrNull(1)?.toLongOrNull()
    }

    private fun flatten(node: androidx.compose.ui.semantics.SemanticsNode): List<androidx.compose.ui.semantics.SemanticsNode> =
        listOf(node) + node.children.flatMap(::flatten)
}
