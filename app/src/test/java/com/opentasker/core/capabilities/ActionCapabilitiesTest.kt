package com.opentasker.core.capabilities

import com.opentasker.app.BuildConfig
import com.opentasker.core.power.ShizukuPowerBackend
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionCapabilitiesTest {
    @Test
    fun unsupportedActionsCannotBeAddedFromUi() {
        assertFalse(ActionCapabilityRegistry.get("app.kill").canAdd)
        assertFalse(ActionCapabilityRegistry.get("wifi.toggle").canAdd)
    }

    @Test
    fun elevatedActionsAreAddableButRequireShizukuSetup() {
        (ShizukuPowerBackend.elevatedActionIds - ShizukuPowerBackend.frameworkActionIds).forEach { actionId ->
            val capability = ActionCapabilityRegistry.get(actionId)
            assertEquals("$actionId must require setup", CapabilityLevel.RequiresSetup, capability.level)
            assertTrue(capability.canAdd)
            assertTrue(capability.reason.contains("Shizuku"))
        }
    }

    @Test
    fun theHotspotActionNeedsAndroidElevenAndThenShizukuSetup() {
        val current = ActionCapabilityRegistry.hotspotCapabilityForSdk(30)
        val old = ActionCapabilityRegistry.hotspotCapabilityForSdk(29)

        assertEquals(CapabilityLevel.RequiresSetup, current.level)
        assertTrue(current.canAdd)
        assertTrue(current.reason.contains("Shizuku"))
        assertEquals(CapabilityLevel.Unsupported, old.level)
        assertFalse(old.canAdd)
        assertTrue(old.reason.contains("Android 11"))
    }

    @Test
    fun termuxScriptActionRequiresSetup() {
        val capability = ActionCapabilityRegistry.get("script.termux.run")

        assertTrue(capability.canAdd)
        assertEquals(CapabilityLevel.RequiresSetup, capability.level)
        assertTrue("Termux" in capability.reason)
    }

    @Test
    fun smsCapabilityFollowsDistributionPolicy() {
        val capability = ActionCapabilityRegistry.get("sms.send")

        if (BuildConfig.SMS_ACTION_AVAILABLE) {
            assertTrue(capability.canAdd)
            assertTrue("SMS permission" in capability.reason)
        } else {
            assertFalse(capability.canAdd)
            assertTrue("Play policy" in capability.reason)
        }
    }

    @Test
    fun android17AudioCapabilitiesRemainAddableWithRuntimeEligibilityWarning() {
        val output = ActionCapabilityRegistry.audioOutputCapabilityForSdk(37, "Uses Android TTS.")
        val mediaKey = ActionCapabilityRegistry.mediaKeyCapabilityForSdk(37, "Dispatches a media key.")
        val volume = ActionCapabilityRegistry.volumeCapabilityForSdk(37, "Changes a media stream.")

        assertEquals(CapabilityLevel.RequiresSetup, output.level)
        assertEquals(CapabilityLevel.RequiresSetup, mediaKey.level)
        assertEquals(CapabilityLevel.RequiresSetup, volume.level)
        assertTrue(output.canAdd)
        assertTrue(output.reason.contains("while-in-use eligible foreground service"))
        assertTrue(mediaKey.reason.contains("media-key dispatch"))
        assertTrue(volume.reason.contains("volume changes"))
    }

    @Test
    fun preAndroid17AudioCapabilitiesRemainAvailable() {
        val output = ActionCapabilityRegistry.audioOutputCapabilityForSdk(36, "Uses Android TTS.")
        val mediaKey = ActionCapabilityRegistry.mediaKeyCapabilityForSdk(36, "Dispatches a media key.")
        val volume = ActionCapabilityRegistry.volumeCapabilityForSdk(36, "Changes a media stream.")

        assertEquals(CapabilityLevel.Supported, output.level)
        assertEquals(CapabilityLevel.Supported, mediaKey.level)
        assertEquals(CapabilityLevel.RequiresSetup, volume.level)
        assertTrue(output.canAdd)
        assertTrue(mediaKey.canAdd)
        assertTrue(volume.canAdd)
    }

    @Test
    fun unknownActionsFailClosedUntilClassified() {
        assertFalse(ActionCapabilityRegistry.get("plugin.example").canAdd)
        assertEquals(CapabilityLevel.Unsupported, ActionCapabilityRegistry.get("plugin.example").level)
    }
}
