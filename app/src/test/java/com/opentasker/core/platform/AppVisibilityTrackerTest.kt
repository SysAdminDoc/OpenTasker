package com.opentasker.core.platform

import android.app.Activity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AppVisibilityTrackerTest {
    private var nowMs = 0L

    @Before
    fun useTestClock() {
        AppVisibilityTracker.clockForTests = { nowMs }
    }

    @After
    fun restoreClock() {
        AppVisibilityTracker.clockForTests = null
    }

    private class TestActivity(private val finishing: Boolean = false) : Activity() {
        override fun isFinishing() = finishing
    }

    @Test
    fun aWidgetsActivityFinishingItselfRestartsTheTimeSinceTheUserWasHere() {
        val widgetActivity = TestActivity(finishing = true)
        nowMs = 1_000L
        AppVisibilityTracker.onActivityStarted(widgetActivity)
        nowMs = 4_000L
        assertEquals(3_000L, AppVisibilityTracker.millisSinceUserPresence())
        AppVisibilityTracker.onActivityStopped(widgetActivity)
        nowMs = 4_500L
        assertEquals(500L, AppVisibilityTracker.millisSinceUserPresence())
    }

    @Test
    fun anActivityThatFinishesInsideOnCreateStillCounts() {
        nowMs = 2_000L
        AppVisibilityTracker.onActivityCreated(TestActivity(finishing = true), null)
        nowMs = 2_300L
        assertEquals(300L, AppVisibilityTracker.millisSinceUserPresence())
    }

    @Test
    fun pressingHomeForgetsTheUserWasHere() {
        val activity = TestActivity(finishing = false)
        nowMs = 1_000L
        AppVisibilityTracker.onActivityStarted(activity)
        AppVisibilityTracker.onActivityStopped(activity)
        nowMs = 1_100L
        assertEquals(null, AppVisibilityTracker.millisSinceUserPresence())

        AppVisibilityTracker.noteUserPresence()
        nowMs = 1_200L
        assertEquals("a noted tap counts", 100L, AppVisibilityTracker.millisSinceUserPresence())
    }

    @Test
    fun resumeListenersHearAPromptClosingOverTheApp() {
        var calls = 0
        val listener: () -> Unit = { calls++ }
        val activity = TestActivity()
        AppVisibilityTracker.addResumeListener(listener)
        try {
            AppVisibilityTracker.onActivityStarted(activity)
            AppVisibilityTracker.onActivityResumed(activity)
            assertEquals("coming back to the app is a resume", 1, calls)

            // Setup's permission prompt pauses the activity and resumes it with no stop between,
            // so the app stays visible the whole time and only the resume says the grant is in.
            AppVisibilityTracker.onActivityPaused(activity)
            assertTrue(AppVisibilityTracker.isAppVisible)
            AppVisibilityTracker.onActivityResumed(activity)
            assertEquals("a prompt closing over the app is a resume", 2, calls)

            AppVisibilityTracker.onActivityPaused(activity)
            AppVisibilityTracker.onActivityStopped(activity)
            assertFalse(AppVisibilityTracker.isAppVisible)

            AppVisibilityTracker.removeResumeListener(listener)
            AppVisibilityTracker.onActivityStarted(activity)
            AppVisibilityTracker.onActivityResumed(activity)
            AppVisibilityTracker.onActivityPaused(activity)
            AppVisibilityTracker.onActivityStopped(activity)
            assertEquals("a removed listener must not be called", 2, calls)
        } finally {
            AppVisibilityTracker.removeResumeListener(listener)
        }
    }
}
