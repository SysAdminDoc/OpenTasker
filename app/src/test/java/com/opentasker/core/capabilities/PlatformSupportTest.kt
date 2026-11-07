package com.opentasker.core.capabilities

import com.opentasker.core.capabilities.PlatformSupport.BlockedFamily
import com.opentasker.core.capabilities.PlatformSupport.BlockedTrigger
import com.opentasker.core.model.ContextSpec
import com.opentasker.core.model.ContextType
import com.opentasker.core.model.Profile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlatformSupportTest {
    private fun event(name: String, vararg extra: Pair<String, String>) =
        ContextSpec(ContextType.EVENT, mapOf("event" to name, *extra))

    @Test
    fun aScreenRecordingProfileIsBlockedUntilAndroid15() {
        val profile = Profile(id = 7, name = "Hide chats while recording", contexts = listOf(event("screen_recording")), enterTaskId = 1)

        assertEquals(
            listOf(BlockedTrigger(7, "Hide chats while recording", BlockedFamily.SCREEN_RECORDING)),
            PlatformSupport.blockedTriggers(listOf(profile), sdkInt = 34),
        )
        assertTrue(PlatformSupport.blockedTriggers(listOf(profile), sdkInt = 35).isEmpty())
    }

    @Test
    fun eachFamilyHasTheReleaseItsPlatformApiArrivedIn() {
        assertEquals(30, PlatformSupport.familyOf(event("camera"))?.minimumApi)
        assertEquals(30, PlatformSupport.familyOf(event(" Mic "))?.minimumApi)
        assertEquals(36, PlatformSupport.familyOf(event("advanced_protection"))?.minimumApi)
        assertEquals(36, PlatformSupport.familyOf(event("bluetooth_key_missing"))?.minimumApi)
        assertEquals(36, PlatformSupport.familyOf(event("bluetooth_encryption_change"))?.minimumApi)
        assertEquals("15", PlatformSupport.androidRelease(BlockedFamily.SCREEN_RECORDING.minimumApi))
        assertEquals("11", PlatformSupport.androidRelease(BlockedFamily.CAMERA_MIC.minimumApi))
    }

    @Test
    fun aBroadcastIsBlockedOnlyWhenItFiltersOnTheSender() {
        val anySender = event("broadcast", "action" to "com.example.PING")
        val oneSender = event("broadcast", "action" to "com.example.PING", "sender" to "com.example")

        assertNull(PlatformSupport.familyOf(anySender))
        assertEquals(BlockedFamily.BROADCAST_SENDER, PlatformSupport.familyOf(oneSender))
        assertEquals(34, BlockedFamily.BROADCAST_SENDER.minimumApi)
    }

    @Test
    fun triggersEveryReleaseHasAreNeverBlocked() {
        val profile = Profile(
            id = 1,
            name = "Everyday",
            contexts = listOf(
                event("bluetooth"),
                event("shake"),
                ContextSpec(ContextType.STATE, mapOf("key" to "camera")),
                ContextSpec(ContextType.TIME, mapOf("from" to "08:00", "to" to "09:00")),
            ),
            enterTaskId = 1,
        )

        assertTrue(PlatformSupport.blockedTriggers(listOf(profile), sdkInt = 26).isEmpty())
    }

    @Test
    fun disabledProfilesAndRepeatedFamiliesAreLeftOut() {
        val disabled = Profile(id = 1, name = "Off", enabled = false, contexts = listOf(event("camera")), enterTaskId = 1)
        val twice = Profile(id = 2, name = "Camera or mic", contexts = listOf(event("camera"), event("mic")), enterTaskId = 1)

        assertEquals(
            listOf(BlockedTrigger(2, "Camera or mic", BlockedFamily.CAMERA_MIC)),
            PlatformSupport.blockedTriggers(listOf(disabled, twice), sdkInt = 29),
        )
    }
}
