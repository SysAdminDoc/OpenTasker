package com.opentasker.core.platform

import android.app.Activity
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * From Android 10 the clipboard reads as empty to an app that's on screen without the focus, so
 * the clipboard read checks focus, not just whether an activity is started.
 */
class AppWindowFocusTest {
    private class TestActivity : Activity() {
        override fun isFinishing() = false
    }

    private val main = TestActivity()
    private val picker = TestActivity()

    @Before
    fun useTestClock() {
        AppVisibilityTracker.clockForTests = { 0L }
    }

    @After
    fun forgetActivities() {
        AppVisibilityTracker.onActivityDestroyed(main)
        AppVisibilityTracker.onActivityDestroyed(picker)
        AppVisibilityTracker.clockForTests = null
    }

    @Test
    fun anActivityOnScreenWithoutTheFocusDoesntCountAsFocused() {
        AppVisibilityTracker.onActivityStarted(main)
        try {
            assertTrue(AppVisibilityTracker.isAppVisible)
            assertFalse(AppVisibilityTracker.hasWindowFocus)

            AppVisibilityTracker.noteWindowFocus(main, true)
            assertTrue(AppVisibilityTracker.hasWindowFocus)

            // The notification shade or the other half of split screen takes the focus.
            AppVisibilityTracker.noteWindowFocus(main, false)
            assertTrue(AppVisibilityTracker.isAppVisible)
            assertFalse(AppVisibilityTracker.hasWindowFocus)
        } finally {
            AppVisibilityTracker.onActivityStopped(main)
        }
    }

    @Test
    fun focusMovingBetweenTheAppsOwnActivitiesStaysFocused() {
        AppVisibilityTracker.noteWindowFocus(main, true)
        AppVisibilityTracker.noteWindowFocus(picker, true)
        AppVisibilityTracker.noteWindowFocus(main, false)

        assertTrue(AppVisibilityTracker.hasWindowFocus)

        AppVisibilityTracker.noteWindowFocus(picker, false)
        assertFalse(AppVisibilityTracker.hasWindowFocus)
    }

    @Test
    fun aDestroyedActivityNoLongerHoldsTheFocus() {
        AppVisibilityTracker.noteWindowFocus(main, true)

        AppVisibilityTracker.onActivityDestroyed(main)

        assertFalse(AppVisibilityTracker.hasWindowFocus)
    }
}
