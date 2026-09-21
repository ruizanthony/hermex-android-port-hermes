package com.uzairansar.hermex

import android.app.ForegroundServiceStartNotAllowedException
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.uzairansar.hermex.ui.chat.LiveStreamOwnerRegistry
import com.uzairansar.hermex.ui.chat.StreamForegroundTestHooks
import com.uzairansar.hermex.ui.chat.StreamRecoveryService
import com.uzairansar.hermex.ui.chat.StreamStatusNotifier
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/** Run only on a disposable API 31+ emulator with the DEBUG target APK.
 * Real Android-managed service + framework exception injected at the promotion boundary.
 * This is not proof that Android's background-start quota was exhausted naturally.
 */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 31)
class StreamForegroundGuardInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val preferences get() = context.getSharedPreferences("hermex_stream_recovery", Context.MODE_PRIVATE)
    private val service = AtomicReference<StreamRecoveryService>()
    private val attempts = AtomicInteger()
    private val refusal = AtomicReference<RuntimeException>()
    private val owner = Any()
    private var activity: ActivityScenario<MainActivity>? = null
    private var seeded = false
    private val sessions = listOf("foreground-fixture-a", "foreground-fixture-b")
    private val server = "https://foreground-fixture.invalid/"

    @Before fun setUp() {
        assertTrue("Use a disposable debug emulator", BuildConfig.DEBUG)
        assertTrue("Do not overwrite existing recovery records",
            JSONArray(preferences.getString("records", "[]")).length() == 0)
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            instrumentation.uiAutomation.executeShellCommand(
                "pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS",
            ).use { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes() }
        }
        activity = ActivityScenario.launch(MainActivity::class.java)
        val records = JSONArray()
        sessions.forEach { session ->
            LiveStreamOwnerRegistry.acquire(server, session, "stream-$session", owner)
            records.put(JSONObject().put("serverId", server).put("sessionId", session)
                .put("streamId", "stream-$session").put("startedAtMillis", System.currentTimeMillis()))
        }
        assertTrue(preferences.edit().putString("records", records.toString()).commit())
        seeded = true
        StreamRecoveryService.testHooks = StreamForegroundTestHooks(
            onCreated = { service.set(it) },
            beforePromotion = {
                attempts.incrementAndGet()
                refusal.get()?.let { throw it }
            },
        )
    }

    @After fun tearDown() {
        if (!seeded) {
            activity?.close()
            return
        }
        instrumentation.runOnMainSync { context.stopService(Intent(context, StreamRecoveryService::class.java)) }
        instrumentation.waitForIdleSync()
        StreamRecoveryService.testHooks = null
        if (seeded) {
            sessions.forEach { session ->
                LiveStreamOwnerRegistry.release(server, session, "stream-$session", owner)
                StreamStatusNotifier(context).clear(server, session)
            }
            preferences.edit().remove("records").commit()
        }
        activity?.close()
    }

    @Test fun deniedNullStickyCallbackPreservesRecordsAndNotificationsThenFreshServiceResumes() {
        val current = startService()
        val original = preferences.getString("records", null)
        val notifier = StreamStatusNotifier(context)
        sessions.forEach { notifier.show(server, it, "stream-$it", null, null, null) }
        val notificationIds = sessions.map { notifier.notificationId(server, it) }.toSet()
        await { activeNotificationIds().containsAll(notificationIds) }
        val monitors = jobs(current).values.toList()
        refusal.set(ForegroundServiceStartNotAllowedException("fixture Android denial"))
        instrumentation.runOnMainSync {
            assertEquals(Service.START_NOT_STICKY, current.onStartCommand(null, 0, 99))
            assertEquals(Service.START_NOT_STICKY, current.onStartCommand(null, 0, 100))
        }
        instrumentation.waitForIdleSync()
        assertEquals(2, attempts.get())
        assertEquals(original, preferences.getString("records", null))
        assertTrue(monitors.all { it.isCancelled })
        assertTrue(jobs(current).isEmpty())
        assertTrue(activeNotificationIds().containsAll(notificationIds))
        refusal.set(null)
        instrumentation.runOnMainSync { assertTrue(StreamRecoveryService.resumePending(context)) }
        await { service.get() !== current && jobs(service.get()).size == 2 }
        assertEquals(3, attempts.get())
        assertEquals(original, preferences.getString("records", null))
    }

    @Test fun denialDuringFinishKeepsOtherDurableRecordAndStopsAllMonitors() {
        val current = startService()
        val monitors = jobs(current).values.toList()
        refusal.set(ForegroundServiceStartNotAllowedException("fixture completion promotion denial"))
        instrumentation.runOnMainSync {
            val storeField = current.javaClass.getDeclaredField("store").apply { isAccessible = true }
            val store = storeField.get(current)
            val records = store.javaClass.getDeclaredMethod("records").apply { isAccessible = true }.invoke(store) as List<*>
            val record = records.first()!!
            val finish = current.javaClass.getDeclaredMethod("finish", record.javaClass,
                Boolean::class.javaPrimitiveType, Continuation::class.java).apply { isAccessible = true }
            runBlocking {
                suspendCoroutine<Unit> { continuation ->
                    val result = finish.invoke(current, record, false, continuation)
                    if (result !== COROUTINE_SUSPENDED) continuation.resume(Unit)
                }
            }
        }
        instrumentation.waitForIdleSync()
        val remaining = JSONArray(preferences.getString("records", "[]"))
        assertEquals(1, remaining.length())
        assertEquals(sessions[1], remaining.getJSONObject(0).getString("sessionId"))
        assertEquals(2, attempts.get())
        assertTrue(monitors.all { it.isCancelled })
        assertTrue(jobs(current).isEmpty())
    }

    @Test fun denialFromCancelledMonitorFinallyDoesNotLoopOrDeleteRecords() {
        val current = startService()
        val original = preferences.getString("records", null)
        val monitors = jobs(current).values.toList()
        refusal.set(ForegroundServiceStartNotAllowedException("fixture finally promotion denial"))
        instrumentation.runOnMainSync { monitors.first().cancel() }
        instrumentation.waitForIdleSync()
        assertEquals(2, attempts.get())
        assertTrue(monitors.all { it.isCancelled })
        assertTrue(jobs(current).isEmpty())
        assertEquals(original, preferences.getString("records", null))
    }

    @Test fun permissionDenialAtInitialCallbackDoesNotStartAnyMonitor() {
        refusal.set(SecurityException("fixture missing foreground permission"))
        instrumentation.runOnMainSync { context.startService(Intent(context, StreamRecoveryService::class.java)) }
        await { service.get() != null && attempts.get() == 1 }
        instrumentation.waitForIdleSync()
        assertTrue(jobs(service.get()).isEmpty())
        assertEquals(2, JSONArray(preferences.getString("records", "[]")).length())
    }

    @Test fun destructionDoesNotRepromoteCancelledJobs() {
        val current = startService()
        val monitors = jobs(current).values.toList()
        instrumentation.runOnMainSync { context.stopService(Intent(context, StreamRecoveryService::class.java)) }
        await { monitors.all { it.isCancelled } }
        instrumentation.waitForIdleSync()
        assertEquals(1, attempts.get())
        assertTrue(monitors.all { it.isCancelled })
        assertEquals(2, JSONArray(preferences.getString("records", "[]")).length())
    }

    @Test fun timeoutSuspendsWithoutDestroyingRecoveryRecords() {
        val current = startService()
        val original = preferences.getString("records", null)
        val monitors = jobs(current).values.toList()
        instrumentation.runOnMainSync { current.onTimeout(1, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) }
        instrumentation.waitForIdleSync()
        assertEquals(1, attempts.get())
        assertTrue(monitors.all { it.isCancelled })
        assertEquals(original, preferences.getString("records", null))
    }

    private fun startService(): StreamRecoveryService {
        instrumentation.runOnMainSync { context.startService(Intent(context, StreamRecoveryService::class.java)) }
        await { service.get() != null && jobs(service.get()).size == 2 }
        assertEquals(1, attempts.get())
        return service.get()
    }

    @Suppress("UNCHECKED_CAST")
    private fun jobs(current: StreamRecoveryService): Map<String, Job> =
        current.javaClass.getDeclaredField("jobs").apply { isAccessible = true }.get(current) as Map<String, Job>

    private fun activeNotificationIds(): Set<Int> =
        context.getSystemService(NotificationManager::class.java).activeNotifications.map { it.id }.toSet()

    private fun await(condition: () -> Boolean) {
        val deadline = android.os.SystemClock.elapsedRealtime() + 5_000
        while (!condition() && android.os.SystemClock.elapsedRealtime() < deadline) Thread.sleep(20)
        assertTrue("Timed out waiting for the Android service", condition())
    }
}
