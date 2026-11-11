package com.opentasker.core.actions

import com.opentasker.core.capabilities.ActionCapabilityRegistry
import com.opentasker.core.capabilities.AutomationPower
import com.opentasker.core.capabilities.AutomationSensitivityRegistry
import com.opentasker.core.capabilities.CapabilityLevel
import com.opentasker.core.engine.ActionRetrySafety
import com.opentasker.core.storage.ProfileEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** How Profile Status picks its profile and what it turns it to, short of the database write. */
class ProfileSwitchTest {

    private var nextId = 1L

    private fun profile(name: String) = ProfileEntity(
        id = nextId++,
        name = name,
        enabled = false,
        enterTaskId = 1,
        exitTaskId = null,
        cooldownSec = 0,
        contextsJson = "[]",
    )

    private fun ProfileSwitch.Found.name(): String = (this as ProfileSwitch.Found.One).profile.name

    private fun ProfileSwitch.Found.message(): String = (this as ProfileSwitch.Found.Refused).message

    @Test
    fun onAndOffSetTheProfileAndToggleFlipsIt() {
        listOf(false, true).forEach { current ->
            assertEquals(true, ProfileSwitch.next("on", current))
            assertEquals(false, ProfileSwitch.next("off", current))
            assertEquals(!current, ProfileSwitch.next("toggle", current))
        }
        assertEquals(setOf("on", "off", "toggle"), ProfileSwitch.STATES)
    }

    @Test
    fun anExactNameWinsAndOtherwiseCaseDoesntMatter() {
        val profiles = listOf(profile("Night"), profile("night"), profile("Work"))

        assertEquals("night", ProfileSwitch.find("night", profiles).name())
        assertEquals("Night", ProfileSwitch.find(" Night ", profiles).name())
        assertEquals("Work", ProfileSwitch.find("WORK", profiles).name())
    }

    @Test
    fun aNameNothingOrSeveralProfilesShareIsRefused() {
        assertTrue(ProfileSwitch.find("Gym", listOf(profile("Work"))).message().contains("no profile is named 'Gym'"))

        val twins = listOf(profile("Car"), profile("Car"))
        assertTrue(ProfileSwitch.find("Car", twins).message().contains("2 profiles are named 'Car'"))

        val caseOnly = listOf(profile("Car"), profile("CAR"))
        assertTrue(ProfileSwitch.find("car", caseOnly).message().contains("2 profiles"))
    }

    @Test
    fun itRunsWithNoSetupIsCalledOutOnImportsAndIsNeverRetried() {
        assertEquals(CapabilityLevel.Supported, ActionCapabilityRegistry.get("profile.set").level)
        assertEquals(setOf(AutomationPower.DEVICE_CONTROL), AutomationSensitivityRegistry.classify("profile.set").powers)
        assertEquals(ActionRetrySafety.NEVER, ActionCatalog.require("profile.set").retrySafety)
    }
}
