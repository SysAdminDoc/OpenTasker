package com.opentasker.core.platform

import android.content.Context
import android.os.Build
import android.provider.Settings

/**
 * Android 10 and later drop an activity start from an app the user can't see without saying so:
 * startActivity returns normally and the screen never opens, so these actions used to log success
 * for nothing. No call reports the verdict, so this repeats the rules an app can see for itself: it
 * is on screen, it may draw over other apps, or it's inside the ten seconds Android allows after the
 * app's last activity. Widget and shortcut taps pass through an activity, so they get that window.
 * Quick Settings taps don't: despite the tile's service being bound by the system, Android refused
 * a start 0.3 s after a real tile tap on API 37.
 */
internal object BackgroundActivityStart {
    private const val FIRST_RESTRICTED_API = 29
    private const val GRACE_PERIOD_MS = 10_000L

    @Volatile
    internal var likelyBlockedOverrideForTests: Boolean? = null

    fun likelyBlocked(
        sdkInt: Int,
        appVisible: Boolean,
        millisSinceUserPresence: Long?,
        canDrawOverlays: Boolean,
    ): Boolean {
        if (sdkInt < FIRST_RESTRICTED_API || appVisible || canDrawOverlays) return false
        return millisSinceUserPresence == null || millisSinceUserPresence >= GRACE_PERIOD_MS
    }

    fun likelyBlocked(context: Context): Boolean = likelyBlockedOverrideForTests ?: likelyBlocked(
        sdkInt = Build.VERSION.SDK_INT,
        appVisible = AppVisibilityTracker.isAppVisible,
        millisSinceUserPresence = AppVisibilityTracker.millisSinceUserPresence(),
        canDrawOverlays = Settings.canDrawOverlays(context),
    )

    fun failureMessage(what: String): String =
        "Android most likely blocked opening $what because OpenTasker is in the background. " +
            "Turn on Overlay access in Setup (Android calls it Display over other apps) so triggers can open it."
}
