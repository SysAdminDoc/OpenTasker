package com.opentasker.core.platform

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.SystemClock
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicInteger

/**
 * Process-local activity visibility, for Android 17 audio eligibility decisions and for monitors
 * that need to re-check something when the user comes back to the app or closes a prompt over it.
 */
object AppVisibilityTracker : Application.ActivityLifecycleCallbacks {
    private val startedActivityCount = AtomicInteger(0)
    private val resumeListeners = CopyOnWriteArraySet<() -> Unit>()

    @Volatile
    private var lastUserPresenceAtMs: Long? = null

    @Volatile
    internal var clockForTests: (() -> Long)? = null

    val isAppVisible: Boolean
        get() = startedActivityCount.get() > 0

    /**
     * Milliseconds since an activity last opened or finished itself or the user tapped a notification
     * button before Android 12, or null when none of that has happened since the user last left.
     * A Quick Settings tile tap doesn't count: Android refused a start 0.3 s after one on API 37.
     */
    fun millisSinceUserPresence(): Long? = lastUserPresenceAtMs?.let { now() - it }

    fun noteUserPresence() {
        lastUserPresenceAtMs = now()
    }

    private fun now(): Long = clockForTests?.invoke() ?: SystemClock.elapsedRealtime()

    fun register(application: Application) {
        application.registerActivityLifecycleCallbacks(this)
    }

    /**
     * Called on the main thread each time one of the app's activities resumes: on the way back
     * from the background, and when a permission prompt drawn over the app closes. The prompt
     * pauses the activity without stopping it, so the app never stops being visible and a
     * return-to-the-app signal would miss the grant.
     */
    fun addResumeListener(listener: () -> Unit) {
        resumeListeners += listener
    }

    fun removeResumeListener(listener: () -> Unit) {
        resumeListeners -= listener
    }

    override fun onActivityStarted(activity: Activity) {
        startedActivityCount.incrementAndGet()
        noteUserPresence()
    }

    override fun onActivityStopped(activity: Activity) {
        startedActivityCount.updateAndGet { count -> (count - 1).coerceAtLeast(0) }
        // Home and the recents gesture end Android's grace period on the spot: a start right after
        // Home was refused on API 37. An activity that finishes itself, as a widget's does, keeps it.
        if (activity.isFinishing) noteUserPresence() else lastUserPresenceAtMs = null
    }

    override fun onActivityResumed(activity: Activity) {
        resumeListeners.forEach { listener -> runCatching(listener) }
    }

    // The widget and shortcut activity finishes inside onCreate, so it never starts or stops.
    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = noteUserPresence()
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}
