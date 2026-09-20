package com.uzairansar.hermex

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.uzairansar.hermex.ui.chat.ChatRoute
import com.uzairansar.hermex.ui.theme.HermexTheme
import mockwebserver3.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(AndroidJUnit4::class)
class ChatPinInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private val server = MockWebServer()
    @After fun close() { server.close() }

    @Test fun conversationMenuPinsAndUnpinsWithFrenchLabels() {
        val pinned = AtomicBoolean(false)
        val calls = CopyOnWriteArrayList<String>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val body = when (request.url.encodedPath) {
                    "/api/session" -> """{"session":{"session_id":"fixture-pin","title":"Épinglage atelier","pinned":${pinned.get()},"messages":[{"role":"user","content":"Conversation de contrôle"}]}}"""
                    "/api/session/pin" -> {
                        val payload = request.body?.utf8().orEmpty()
                        calls.add(payload)
                        pinned.set(payload.contains("\"pinned\":true"))
                        """{"ok":true,"session":{"session_id":"fixture-pin","pinned":${pinned.get()}}}"""
                    }
                    else -> "{}"
                }
                return MockResponse.Builder().code(200).body(body).build()
            }
        }
        server.start()
        val app = ApplicationProvider.getApplicationContext<Application>()
        val container = AppContainer(app)
        val frenchConfig = android.content.res.Configuration(app.resources.configuration).apply { setLocale(java.util.Locale.FRANCE) }
        val frenchContext = app.createConfigurationContext(frenchConfig)
        compose.setContent {
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.ui.platform.LocalResources provides frenchContext.resources,
                androidx.compose.ui.platform.LocalConfiguration provides frenchConfig,
            ) { HermexTheme {
                ChatRoute(sessionId = "fixture-pin", serverId = server.url("/").toString(),
                    repository = container.chatRepository(server.url("/")), onBack = {}, onOpenWorkspace = {}, onOpenGit = {})
            } }
        }
        compose.waitUntil(20_000) { compose.onAllNodesWithText("Conversation de contrôle").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Session actions").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Épingler").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Épingler").assertIsDisplayed().assertIsEnabled()
        capture(app, "pin-menu.png")
        compose.onNodeWithText("Épingler").performClick()
        compose.waitUntil(10_000) { pinned.get() }
        compose.onNodeWithContentDescription("Session actions").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Désépingler").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Désépingler").assertIsDisplayed().assertIsEnabled()
        kotlinx.coroutines.runBlocking {
            val reopened = container.chatRepository(server.url("/"))
            assertEquals(true, reopened.loadCachedSessionSnapshot("fixture-pin")?.pinned)
            assertEquals(true, (reopened.loadSessionSnapshot("fixture-pin") as com.uzairansar.hermex.data.repository.ResultState.Data).value.pinned)
        }
        capture(app, "unpin-menu.png")
        compose.onNodeWithText("Désépingler").performClick()
        compose.waitUntil(10_000) { !pinned.get() }
        assertEquals(2, calls.size)
        assertTrue(calls.all { it.contains("fixture-pin") })
        compose.onNodeWithContentDescription("Session actions").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Épingler").fetchSemanticsNodes().isNotEmpty() }
    }

    private fun capture(app: Application, name: String) {
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(300)
        compose.waitForIdle()
        val bitmap = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(app.getExternalFilesDir(null), name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
