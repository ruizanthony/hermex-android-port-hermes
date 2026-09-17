package com.uzairansar.hermex

import android.app.Activity
import android.app.Application
import android.os.Bundle
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

object AppVisibilityTracker : Application.ActivityLifecycleCallbacks {
    private val resumedActivities = AtomicInteger(0)
    private val isForegroundInternal = MutableStateFlow(false)

    /** Observable foreground state: true while at least one activity is RESUMED. */
    val isForeground: StateFlow<Boolean> = isForegroundInternal

    fun register(application: Application) {
        application.registerActivityLifecycleCallbacks(this)
    }

    fun isActive(): Boolean = resumedActivities.get() > 0

    override fun onActivityResumed(activity: Activity) {
        resumedActivities.incrementAndGet()
        isForegroundInternal.value = isActive()
    }

    override fun onActivityPaused(activity: Activity) {
        resumedActivities.updateAndGet { current -> (current - 1).coerceAtLeast(0) }
        isForegroundInternal.value = isActive()
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}
