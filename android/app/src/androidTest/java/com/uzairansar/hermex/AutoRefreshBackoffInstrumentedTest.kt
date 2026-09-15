package com.uzairansar.hermex

import android.app.Application
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.uzairansar.hermex.data.repository.AuthState
import com.uzairansar.hermex.data.secure.ServerAccount
import com.uzairansar.hermex.ui.sessions.SessionListRoute
import com.uzairansar.hermex.ui.theme.HermexTheme
import mockwebserver3.*
import org.junit.*
import org.junit.Assert.assertTrue
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * Visible auto-refresh backs off when the server fails, and returns to the base
 * cadence after the first success. Observed through the request log cadence of
 * the real list route + view model + repository stack against a MockWebServer.
 *
 * Expected timeline with base=5s: requests at ~0s, 5s, 15s, 35s (gaps 5, 10, 20),
 * then after recovery the cadence returns to ~5s.
 */
@RunWith(AndroidJUnit4::class)
class AutoRefreshBackoffInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private val server = MockWebServer()
    @After fun close() { server.close() }

    @Test fun serverFailuresStretchThenRecoverTheRefreshCadence() {
        val requests = CopyOnWriteArrayList<Long>()
        val state = AtomicInteger(0) // 0 = fail, 1 = ok
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.url.encodedPath == "/api/sessions") requests.add(System.currentTimeMillis())
                return if (state.get() == 0) MockResponse.Builder().code(503).body("").build()
                else MockResponse.Builder().code(200).body("{}").build()
            }
        }
        server.start()
        val app = ApplicationProvider.getApplicationContext<Application>()
        val container = AppContainer(app)
        val url = server.url("/")
        val account = ServerAccount(id=url.toString(),urlString=url.toString(),displayName="Fixture",initials="FX")
        compose.setContent { HermexTheme {
            SessionListRoute(authState=AuthState.LoggedIn(url,account),container=container,
                onOpenChat={},onOpenVoiceChat={},onOpenSharedDraft={},onOpenPanels={},onOpenKanban={},onOpenSettings={},onNeedsOnboarding={})
        } }
        // Four failed passes land within ~35s (0, 5, 15, 35).
        compose.waitUntil(60_000) { requests.size >= 4 }
        val head = requests.take(4)
        val gaps = head.zipWithNext().map { (a, b) -> b - a }
        assertTrue("expected growing gaps, saw ${gaps.joinToString()}", gaps.zipWithNext().any { (prev, next) -> next > prev + 1_000 })
        // Server recovers: after one success the cadence resets to ~5s. The next
        // pass arrives after the last stretched delay (<=40s), then ~5s each.
        state.set(1)
        compose.waitUntil(120_000) { requests.size >= head.size + 3 }
        val tail = requests.takeLast(3)
        val tailGaps = tail.zipWithNext().map { (a, b) -> b - a }
        assertTrue("expected recovered ~5s cadence, saw ${tailGaps.joinToString()}", tailGaps.all { it < 15_000 })
    }
}
