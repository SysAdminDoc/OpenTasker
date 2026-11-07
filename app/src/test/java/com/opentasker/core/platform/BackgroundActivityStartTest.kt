package com.opentasker.core.platform

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundActivityStartTest {
    private fun blocked(
        sdkInt: Int = 37,
        appVisible: Boolean = false,
        millisSinceUserPresence: Long? = null,
        canDrawOverlays: Boolean = false,
    ) = BackgroundActivityStart.likelyBlocked(sdkInt, appVisible, millisSinceUserPresence, canDrawOverlays)

    @Test
    fun aTriggerFiringWithTheAppHiddenIsBlockedFromAndroid10On() {
        assertTrue(blocked(sdkInt = 29))
        assertTrue(blocked(sdkInt = 37))
        assertTrue("an old visit to the app doesn't help", blocked(millisSinceUserPresence = 60_000L))
    }

    @Test
    fun androidBefore10NeverRestrictedIt() {
        assertFalse(blocked(sdkInt = 28))
        assertFalse(blocked(sdkInt = 26))
    }

    @Test
    fun beingOnScreenOrHoldingOverlayAccessAllowsIt() {
        assertFalse(blocked(appVisible = true))
        assertFalse(blocked(canDrawOverlays = true))
    }

    @Test
    fun aWidgetOrTileTapJustNowIsInsideAndroidsGracePeriod() {
        assertFalse(blocked(millisSinceUserPresence = 0L))
        assertFalse(blocked(millisSinceUserPresence = 9_999L))
        assertTrue(blocked(millisSinceUserPresence = 10_000L))
    }
}
