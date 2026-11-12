package com.opentasker.core.capabilities

import com.opentasker.core.model.ActionSpec
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A sun.times step needs location access only for a coordinate it doesn't have, so the pill follows the args. */
class SunTimesSetupPillTest {
    private val location = setOf(SetupRequirement.FOREGROUND_LOCATION, SetupRequirement.BACKGROUND_LOCATION)

    @Test
    fun typedCoordinatesNeverKeepTheNeedsSetupPill() {
        val typed = ActionSpec(type = "sun.times", args = mapOf("latitude" to "40.69", "longitude" to "-74.14"))
        assertFalse(SetupRequirementResolver.stillNeedsSetup(typed, sdkInt = 36, pending = location))
        assertFalse(SetupRequirementResolver.stillNeedsSetup(typed, sdkInt = 36, pending = emptySet()))
        // Nothing to ask for, so the grants not being read yet doesn't matter either.
        assertFalse(SetupRequirementResolver.stillNeedsSetup(typed, sdkInt = 36, pending = null))
    }

    @Test
    fun aBlankCoordinateKeepsThePillUntilLocationIsGranted() {
        val blank = ActionSpec(type = "sun.times", args = mapOf("latitude" to "40.69"))
        assertTrue(SetupRequirementResolver.stillNeedsSetup(blank, sdkInt = 36, pending = location))
        assertTrue(SetupRequirementResolver.stillNeedsSetup(blank, sdkInt = 36, pending = null))
        assertFalse(SetupRequirementResolver.stillNeedsSetup(blank, sdkInt = 36, pending = emptySet()))
    }

    @Test
    fun anActionNoSetupRowCanSettleStillKeepsItsPill() {
        assertTrue(SetupRequirementResolver.stillNeedsSetup(ActionSpec(type = "vpn.tunnel"), sdkInt = 36, pending = emptySet()))
    }
}
