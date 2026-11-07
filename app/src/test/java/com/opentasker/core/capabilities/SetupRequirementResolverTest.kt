package com.opentasker.core.capabilities

import com.opentasker.core.model.ActionSpec
import com.opentasker.core.model.ContextSpec
import com.opentasker.core.model.ContextType
import com.opentasker.core.model.Profile
import com.opentasker.core.model.Task
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SetupRequirementResolverTest {
    @Test
    fun emptyWorkspaceHasNoAutomationRequirements() {
        assertTrue(SetupRequirementResolver.resolve(emptyList(), emptyList()).isEmpty())
    }

    @Test
    fun anIncomingCallTriggerAsksForEverythingItsNumberAndContactCheckNeed() {
        val required = SetupRequirementResolver.resolveForTemplate(
            actionTypes = emptyList(),
            contexts = listOf(
                ContextSpec(ContextType.EVENT, mapOf("event" to "incoming_call", "knownContact" to "false")),
            ),
        )

        assertEquals(
            setOf(SetupRequirement.PHONE_STATE, SetupRequirement.CALL_LOG, SetupRequirement.CONTACTS),
            required,
        )
    }

    @Test
    fun aNearbyDeviceContextAsksForBluetoothAndNotForWhatItsNameSoundsLike() {
        fun requiredFor(name: String) = SetupRequirementResolver.resolveForTemplate(
            actionTypes = emptyList(),
            contexts = listOf(ContextSpec(ContextType.STATE, mapOf("key" to "bt_near", "operator" to "=", "value" to name))),
        )

        val bluetoothOnly = setOf(SetupRequirement.BLUETOOTH, SetupRequirement.BLUETOOTH_SCAN)
        assertEquals(bluetoothOnly, requiredFor("Car"))
        assertEquals(bluetoothOnly, requiredFor("Sam's Phone"))
        assertEquals(bluetoothOnly, requiredFor("Home Network Speed Motion"))
    }

    @Test
    fun openingAScreenFromAProfileNeedsDisplayOverOtherApps() {
        listOf("url.open", "app.launch", "home.go", "intent.launch").forEach { type ->
            assertEquals(
                type,
                setOf(SetupRequirement.OVERLAY),
                SetupRequirementResolver.resolveForTemplate(listOf(type), emptyList()),
            )
        }
    }

    @Test
    fun enabledProfileCombinesContextAndReachableTaskRequirements() {
        val child = Task(
            id = 2,
            name = "Notify",
            actions = listOf(ActionSpec(type = "brightness.set")),
        )
        val root = Task(
            id = 1,
            name = "Start",
            actions = listOf(ActionSpec(type = "task.run", args = mapOf("task" to "Notify"))),
        )
        val profile = Profile(
            id = 4,
            name = "Work",
            contexts = listOf(
                ContextSpec(ContextType.APPLICATION, mapOf("package" to "com.example")),
                ContextSpec(ContextType.EVENT, mapOf("event" to "calendar")),
            ),
            enterTaskId = root.id,
        )

        assertEquals(
            setOf(SetupRequirement.USAGE_ACCESS, SetupRequirement.CALENDAR, SetupRequirement.WRITE_SETTINGS),
            SetupRequirementResolver.resolve(listOf(profile), listOf(root, child)),
        )
    }

    @Test
    fun disabledOrUnacknowledgedProfilesDoNotCreateBlockers() {
        val task = Task(id = 1, name = "Send", actions = listOf(ActionSpec(type = "sms.send")))
        val disabled = Profile(
            id = 1,
            name = "Disabled",
            enabled = false,
            enterTaskId = task.id,
        )
        val unacknowledged = Profile(
            id = 2,
            name = "Review",
            requiresRiskAcknowledgement = true,
            enterTaskId = task.id,
        )

        assertTrue(SetupRequirementResolver.resolve(listOf(disabled, unacknowledged), listOf(task)).isEmpty())
    }

    @Test
    fun contactsLookupAddsContactsPermissionRequirement() {
        val task = Task(id = 1, name = "Find", actions = listOf(ActionSpec(type = "contacts.lookup")))
        val profile = Profile(id = 1, name = "Find contact", enterTaskId = task.id)

        assertEquals(
            setOf(SetupRequirement.CONTACTS),
            SetupRequirementResolver.resolve(listOf(profile), listOf(task)),
        )
    }

    @Test
    fun screenRecordingEventAddsScreenRecordingRequirement() {
        val profile = Profile(
            id = 1,
            name = "Recording",
            contexts = listOf(ContextSpec(ContextType.EVENT, mapOf("event" to "screen_recording"))),
            enterTaskId = 1,
        )

        assertEquals(
            setOf(SetupRequirement.SCREEN_RECORDING),
            SetupRequirementResolver.resolve(listOf(profile), emptyList()),
        )
    }

    @Test
    fun physicalStateContextsAddMatchingSensorSetupRequirements() {
        val profile = Profile(
            id = 8,
            name = "Physical state",
            contexts = listOf(
                ContextSpec(ContextType.STATE, mapOf("key" to "activity", "value" to "walking")),
                ContextSpec(ContextType.STATE, mapOf("predicate" to "speed>=10")),
                ContextSpec(ContextType.STATE, mapOf("key" to "roaming", "value" to "true")),
                ContextSpec(ContextType.STATE, mapOf("key" to "phone_call", "value" to "ringing")),
            ),
            enterTaskId = 1,
        )

        assertEquals(
            setOf(
                SetupRequirement.PHYSICAL_ACTIVITY,
                SetupRequirement.FOREGROUND_LOCATION,
                SetupRequirement.PHONE_STATE,
            ),
            SetupRequirementResolver.resolve(listOf(profile), emptyList()),
        )
    }

    @Test
    fun aWifiNameContextNeedsPreciseAndBackgroundLocationButConnectedDoesNot() {
        fun profile(value: String) = Profile(
            id = 9,
            name = "Wi-Fi",
            contexts = listOf(ContextSpec(ContextType.STATE, mapOf("key" to "wifi", "value" to value))),
            enterTaskId = 1,
        )

        // Without these grants Android withholds the name and the profile can never match (issue #17).
        assertEquals(
            setOf(
                SetupRequirement.NEARBY_WIFI,
                SetupRequirement.FOREGROUND_LOCATION,
                SetupRequirement.BACKGROUND_LOCATION,
            ),
            SetupRequirementResolver.resolve(listOf(profile("Home")), emptyList()),
        )
        assertEquals(
            setOf(SetupRequirement.NEARBY_WIFI),
            SetupRequirementResolver.resolve(listOf(profile("connected")), emptyList()),
        )
    }

    @Test
    fun templateActionsResolveBeforeAnythingIsStored() {
        // Onboarding points Setup at a freshly installed template, which has no profile or task
        // rows yet, so the requirements have to come from the template itself.
        assertEquals(
            setOf(SetupRequirement.WRITE_SETTINGS, SetupRequirement.CONTACTS),
            SetupRequirementResolver.resolveForTemplate(
                actionTypes = listOf("brightness.set", "contacts.lookup", "var.set"),
                contexts = emptyList(),
            ),
        )
    }

    @Test
    fun aTemplateTriggerCarriesItsOwnGrantEvenWhenNoActionNeedsOne() {
        // The calendar template only turns the volume down, so reading actions alone produced a
        // checklist that omitted the one grant its trigger depends on and then reported it ready.
        assertEquals(
            setOf(SetupRequirement.CALENDAR, SetupRequirement.DND),
            SetupRequirementResolver.resolveForTemplate(
                actionTypes = listOf("volume.set"),
                contexts = listOf(ContextSpec(ContextType.EVENT, mapOf("event" to "calendar"))),
            ),
        )
    }

    @Test
    fun anAppUsageTriggerNeedsUsageAccessThoughItsActionsDoNot() {
        assertEquals(
            setOf(SetupRequirement.USAGE_ACCESS),
            SetupRequirementResolver.resolveForTemplate(
                actionTypes = listOf("flow.wait", "notify.show"),
                contexts = listOf(ContextSpec(ContextType.APPLICATION, mapOf("package" to "com.example"))),
            ),
        )
    }

    @Test
    fun aTemplateThatNeedsNothingSpecialResolvesEmpty() {
        assertEquals(
            emptySet<SetupRequirement>(),
            SetupRequirementResolver.resolveForTemplate(
                actionTypes = listOf("var.set", "flow.wait"),
                contexts = emptyList(),
            ),
        )
    }
}
