package com.opentasker.core.platform

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.SystemClock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicInteger

/**
 * Process-local activity visibility and window focus, for Android 17 audio eligibility decisions,
 * clipboard reads, and monitors that need to re-check something when the user comes back to the
 * app or closes a prompt over it.
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

    private val focusedActivities = ConcurrentHashMap.newKeySet<Activity>()
    private val focusWatchedActivities = ConcurrentHashMap.newKeySet<Activity>()

    /**
     * True while one of the app's activity windows has input focus. From Android 10 the clipboard
     * reads as empty to an app that's on screen but not focused, as in split screen or under the
     * notification shade, so [isAppVisible] isn't enough there.
     */
    val hasWindowFocus: Boolean
        get() = focusedActivities.isNotEmpty()

    internal fun noteWindowFocus(activity: Activity, focused: Boolean) {
        if (focused) focusedActivities += activity else focusedActivities -= activity
    }

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

    // Android 10 added this callback in the same release that tied clipboard reads to focus. The
    // window may not be attached yet, but a listener added before it attaches carries over.
    override fun onActivityPostResumed(activity: Activity) {
        val decor = activity.window?.peekDecorView() ?: return
        if (!focusWatchedActivities.add(activity)) return
        decor.viewTreeObserver.addOnWindowFocusChangeListener { focused -> noteWindowFocus(activity, focused) }
        noteWindowFocus(activity, decor.hasWindowFocus())
    }

    // The widget and shortcut activity finishes inside onCreate, so it never starts or stops.
    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = noteUserPresence()
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) {
        focusWatchedActivities -= activity
        focusedActivities -= activity
    }
}
