package com.opentasker.core.contexts

import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A listener that came off can still be handed a fix Android queued before removeUpdates, so the
 * Speed source asks whether a listener is the current one before it reads the fix.
 */
class LocationListenerCurrentTest {
    @Test
    fun onlyTheListenerRegisteredNowIsCurrent() {
        val state = LocationListenerRegistrationState { }
        val first = testListener()
        val second = testListener()

        state.replace(setOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER), first) { }
        assertTrue(state.isCurrent(first))

        state.replace(setOf(LocationManager.GPS_PROVIDER), second) { }
        assertFalse(state.isCurrent(first))
        assertTrue(state.isCurrent(second))

        state.stop()
        assertFalse(state.isCurrent(second))
    }

    @Test
    fun aListenerWhoseRegistrationFailedIsNotCurrent() {
        val state = LocationListenerRegistrationState { }
        val listener = testListener()

        runCatching { state.replace(setOf(LocationManager.GPS_PROVIDER), listener) { throw SecurityException("revoked") } }

        assertFalse(state.isCurrent(listener))
    }

    private fun testListener(): LocationListener =
        object : LocationListener {
            override fun onLocationChanged(location: Location) = Unit
        }
}
