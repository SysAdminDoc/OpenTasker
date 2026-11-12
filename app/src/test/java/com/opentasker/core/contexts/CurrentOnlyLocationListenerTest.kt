package com.opentasker.core.contexts

import android.location.Location
import android.location.LocationManager
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.IdentityHashMap

/**
 * The Speed source's own listener: a fix Android queued for a listener before removeUpdates can
 * still arrive after the swap, and it must not reach the source.
 */
class CurrentOnlyLocationListenerTest {
    private val names = IdentityHashMap<Location, String>()

    @Test
    fun aFixQueuedForAListenerThatCameOffNeverReachesTheSource() {
        val state = LocationListenerRegistrationState { }
        val heard = mutableListOf<String>()
        val lock = Any()
        val first = currentOnlyLocationListener(state, lock) { heard += "first ${names.getValue(it)}" }
        val second = currentOnlyLocationListener(state, lock) { heard += "second ${names.getValue(it)}" }

        state.replaceProviders(setOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER), first) { _, _ -> }
        first.onLocationChanged(fix("early"))
        state.replaceProviders(setOf(LocationManager.GPS_PROVIDER), second) { _, _ -> }
        first.onLocationChanged(fix("queued"))
        second.onLocationChanged(fix("live"))
        state.stop()
        second.onLocationChanged(fix("after stop"))

        assertEquals(listOf("first early", "second live"), heard)
    }

    /**
     * Location's constructor is a stub off the device, so the fix is made without running it.
     * Nothing here reads its fields.
     */
    private fun fix(name: String): Location {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val unsafe = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
        val location = unsafeClass.getMethod("allocateInstance", Class::class.java).invoke(unsafe, Location::class.java) as Location
        names[location] = name
        return location
    }
}
