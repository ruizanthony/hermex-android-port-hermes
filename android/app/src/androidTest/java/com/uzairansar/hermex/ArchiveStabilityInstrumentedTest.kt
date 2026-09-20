package com.uzairansar.hermex

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.activity.compose.setContent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.lifecycle.ViewModelStore
import com.uzairansar.hermex.ui.chat.ChatRoute
import com.uzairansar.hermex.ui.sessions.SessionListViewModel
import com.uzairansar.hermex.ui.theme.HermexTheme
import com.uzairansar.hermex.core.model.ProfileSummary
import mockwebserver3.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class ArchiveStabilityInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val server = MockWebServer()
    private val archived = AtomicBoolean(false)
    private val mutations = AtomicInteger()
    private val refuse = AtomicBoolean(false)
    private var gate = CountDownLatch(0)
    private val entered = CountDownLatch(1)
    private val store = ViewModelStore()
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    @Before fun start() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.url.encodedPath == "/api/session/archive") {
                    mutations.incrementAndGet(); entered.countDown()
                    check(gate.await(15, TimeUnit.SECONDS))
                    if (refuse.get()) return MockResponse.Builder().code(503).body("""{"error":"Archive unavailable"}""").build()
                    archived.set(request.body!!.utf8().contains("\"archived\":true"))
                    return MockResponse.Builder().code(200).body("""{"ok":true}""").build()
                }
                val row = """{"session_id":"fixture-archive","title":"Conversation atelier","source":"webui","archived":${archived.get()},"messages":[{"id":"m","role":"user","content":"Contrôle archivage"}]}"""
                return MockResponse.Builder().code(200).body(when(request.url.encodedPath) {
                    "/api/session" -> """{"session":$row}"""
                    "/api/sessions" -> """{"sessions":[$row],"archived_count":${if(archived.get()) 1 else 0}}"""
                    else -> "{}"
                }).build()
            }
        }
        server.start()
    }
    @After fun stop() { gate.countDown(); compose.runOnIdle { store.clear() }; server.close() }

    @Test fun menuArchivesAndReturnsOnlyAfterAcknowledgement() {
        gate = CountDownLatch(1)
        val returned = AtomicBoolean(false)
        val container = AppContainer(app)
        val config = android.content.res.Configuration(app.resources.configuration).apply { setLocale(java.util.Locale.FRANCE) }
        val resources = app.createConfigurationContext(config).resources
        compose.runOnUiThread { compose.activity.setContent {
            androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalResources provides resources,
                androidx.compose.ui.platform.LocalConfiguration provides config) {
                HermexTheme { ChatRoute(sessionId = "fixture-archive", serverId = server.url("/").toString(), repository = container.chatRepository(server.url("/")),
                    onBack = { returned.set(true) }, onOpenWorkspace = {}, onOpenGit = {}) }
            }
        } }
        compose.waitUntil(20000) { compose.onAllNodesWithText("Contrôle archivage").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Session actions").performClick()
        compose.onNodeWithText("Archiver").assertIsDisplayed().assertIsEnabled()
        compose.waitForIdle()
        val image = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(app.getExternalFilesDir(null), "archive-menu.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG,100,it) }
        image.recycle()
        compose.onNodeWithText("Archiver").performClick()
        assertTrue(entered.await(10,TimeUnit.SECONDS))
        assertFalse(returned.get())
        gate.countDown()
        compose.waitUntil(10000) { returned.get() }
        assertTrue(archived.get()); assertEquals(1,mutations.get())
    }

    @Test fun listFailureDoesNotCrashAndRestoresVisibility() {
        refuse.set(true)
        val vm = listVm()
        compose.runOnIdle { vm.toggleArchive(vm.state.value.sessions.single()) }
        compose.waitUntil(10000) { vm.state.value.error != null && !vm.state.value.isMutating }
        assertFalse(vm.state.value.visibleSessions.isEmpty())
        assertFalse(vm.state.value.error.isNullOrBlank())
        assertEquals(1,mutations.get())
    }

    @Test fun repeatedActionAndProfileSwitchAreRejectedUntilArchiveCompletes() {
        gate = CountDownLatch(1)
        val vm = listVm()
        val row = vm.state.value.sessions.single()
        compose.runOnIdle { vm.toggleArchive(row) }
        assertTrue(entered.await(10,TimeUnit.SECONDS))
        compose.runOnIdle { vm.toggleArchive(row); vm.switchProfile(ProfileSummary(name="other")) }
        assertTrue(vm.state.value.isMutating)
        assertFalse(vm.state.value.error.isNullOrBlank())
        assertEquals(1,mutations.get())
        gate.countDown()
        compose.waitUntil(10000) { !vm.state.value.isMutating }
        assertTrue(archived.get())
    }

    @Test fun archiveThenRestoreReturnsToOrdinaryList() {
        val vm = listVm()
        compose.runOnIdle { vm.toggleArchive(vm.state.value.sessions.single()) }
        compose.waitUntil(10000) { archived.get() && !vm.state.value.isMutating }
        compose.runOnIdle { vm.toggleArchived() }
        compose.waitUntil(10000) { vm.state.value.sessions.any { it.archived == true } }
        compose.runOnIdle { vm.toggleArchive(vm.state.value.sessions.single()) }
        compose.waitUntil(10000) { !archived.get() && !vm.state.value.isMutating }
        compose.runOnIdle { vm.toggleArchived() }
        compose.waitUntil(10000) { vm.state.value.visibleSessions.size == 1 }
        assertEquals(1, vm.state.value.visibleSessions.size)
        assertEquals(2, mutations.get())
    }

    @Test fun repeatedNavigationKeepsImmediateDraftAndUiResponsiveDuringRoomWait() {
        val db = androidx.room.Room.inMemoryDatabaseBuilder(app, com.uzairansar.hermex.data.db.HermexDatabase::class.java).build()
        val enteredWrite = CountDownLatch(1)
        val completedWrite = CountDownLatch(1)
        val releaseWrite = kotlinx.coroutines.CompletableDeferred<Unit>()
        val suspendWrite = AtomicBoolean(false)
        val dao = object : com.uzairansar.hermex.data.db.CacheDao by db.cacheDao() {
            override suspend fun replaceMessages(serverUrl: String, sessionId: String, messages: List<com.uzairansar.hermex.data.db.CachedMessageEntity>, now: Long) {
                if(suspendWrite.get()) { enteredWrite.countDown(); releaseWrite.await() }
                db.cacheDao().replaceMessages(serverUrl,sessionId,messages,now)
                if(suspendWrite.get()) completedWrite.countDown()
            }
        }
        val http = okhttp3.OkHttpClient()
        val repo = com.uzairansar.hermex.data.repository.ChatRepository(
            com.uzairansar.hermex.core.network.HermesApiClient(server.url("/"),http),dao,
            com.uzairansar.hermex.data.db.ServerCacheOwnership(),
            com.uzairansar.hermex.core.network.SseStreamClient(server.url("/"),http){emptyList()})
        val pending = com.uzairansar.hermex.ui.chat.ChatPendingStateStore(app, server.url("/").toString()+"fixture-archive")
        try {
            repeat(3) { iteration ->
                lateinit var vm: com.uzairansar.hermex.ui.chat.ChatViewModel
                compose.runOnIdle {
                    vm = com.uzairansar.hermex.ui.chat.ChatViewModel("fixture-archive",repo,pending)
                    store.put("navigation",vm)
                }
                compose.waitUntil(10000) { !vm.state.value.isLoading }
                if(iteration > 0) assertEquals("Immediate draft", vm.state.value.draft)
                compose.runOnIdle { vm.updateDraft("Immediate draft") }
                if(iteration == 2) suspendWrite.set(true)
                compose.runOnIdle { store.clear() }
            }
            assertTrue(enteredWrite.await(5,TimeUnit.SECONDS))
            var heartbeat = false
            compose.runOnIdle { heartbeat = true }
            assertTrue(heartbeat)
            assertEquals(1L, completedWrite.count)
            releaseWrite.complete(Unit)
            assertTrue(completedWrite.await(5,TimeUnit.SECONDS))
        } finally { releaseWrite.complete(Unit); db.close() }
    }

    @Test fun deepListProjectionOnMainThreadIsBounded() {
        val rows = (0..8000).map { i -> com.uzairansar.hermex.core.model.SessionSummary(
            sessionId="s$i",title=if(i==8000) "Tip" else "Desktop Session",rawSource="desktop",sourceTag="desktop",parentSessionId=if(i==0)null else "s${i-1}") }
        var elapsed = 0L
        compose.runOnIdle {
            val start = android.os.SystemClock.elapsedRealtime()
            val visible = com.uzairansar.hermex.ui.sessions.SessionListUiState(sessions=rows).visibleSessions
            elapsed = android.os.SystemClock.elapsedRealtime()-start
            assertEquals(1,visible.size)
        }
        android.util.Log.i("HermexStabilityTest","8001-row projection on main: $elapsed ms")
        assertTrue("Projection took $elapsed ms",elapsed < 2000)
    }

    private fun listVm(): SessionListViewModel {
        val container = AppContainer(app)
        lateinit var vm: SessionListViewModel
        compose.runOnIdle { vm = SessionListViewModel(container.sessionRepository(server.url("/")),container.panelsRepository(server.url("/")),container.localSettingsRepository,server.url("/").toString()); store.put("list",vm) }
        compose.waitUntil(20000) { !vm.state.value.isLoading && vm.state.value.sessions.isNotEmpty() }
        return vm
    }
}
