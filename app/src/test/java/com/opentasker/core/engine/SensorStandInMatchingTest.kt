package com.opentasker.core.engine

import com.opentasker.core.contexts.BT_NEAR_ABSENCE_SETTLES_MS
import com.opentasker.core.contexts.BT_NEAR_STATE_KEY
import com.opentasker.core.contexts.ContextEvent
import com.opentasker.core.contexts.standInMarkerKey
import com.opentasker.core.model.ContextSpec
import com.opentasker.core.model.ContextType
import com.opentasker.core.plugins.locale.LocaleConditionEvaluator
import com.opentasker.core.plugins.locale.LocaleConditionKind
import com.opentasker.core.plugins.locale.LocaleConditionSnapshot
import com.opentasker.core.plugins.locale.LocaleConditionSpec
import com.opentasker.core.plugins.locale.LocalePluginContract
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * A sensor's stand-in first value, like activity's "stationary" before anything was sensed, can't
 * turn an edited profile that was on off and back on, and can't run a new profile's enter task.
 */
class SensorStandInMatchingTest {
    private val seed = mapOf("screen" to "on", "charging" to "false")

    @Before
    fun setUp() {
        AutomationLiveConditionState.clear()
    }

    @After
    fun tearDown() {
        AutomationLiveConditionState.clear()
    }

    @Test
    fun anEditedActivityProfileThatWasOnIsNotFlippedByTheStandIn() {
        val walking = state("activity", "=", "walking")

        assertEquals(
            emptyList<ProfileStateChange>(),
            changes(walking, initiallyActive = true, activity("stationary", standIn = true), activity("walking")),
        )
    }

    @Test
    fun anEditedSpeedProfileThatWasOnIsNotFlippedByTheStandIn() {
        val driving = state("speed", ">", "5")

        assertEquals(
            emptyList<ProfileStateChange>(),
            changes(driving, initiallyActive = true, speed("0", standIn = true), speed("12")),
        )
    }

    @Test
    fun anEditedTetheringProfileThatWasOnIsNotFlippedByTheStandIn() {
        val sharing = state("tethering", "=", "true")

        assertEquals(
            emptyList<ProfileStateChange>(),
            changes(sharing, initiallyActive = true, tethering("false", standIn = true), tethering("true")),
        )
    }

    @Test
    fun anEditedBluetoothNearProfileThatWasOnIsNotFlippedByAFreshScanner() {
        val nearCar = state(BT_NEAR_STATE_KEY, "=", "Car")
        val notNearCar = nearCar.copy(invert = true)

        // A device on the list was seen, even before the rest of the list can say who's away.
        assertEquals(
            emptyList<ProfileStateChange>(),
            changes(nearCar, initiallyActive = true, btNear("", standIn = true), btNear("Car\tAA:BB:CC:DD:EE:FF", standIn = true)),
        )
        assertEquals(
            emptyList<ProfileStateChange>(),
            changes(notNearCar, initiallyActive = true, btNear("", standIn = true), btNear("")),
        )
    }

    @Test
    fun aNewProfileDoesNotRunItsEnterTaskFromAStandIn() {
        val notWalking = state("activity", "=", "walking").copy(invert = true)
        val stopped = state("speed", "<", "1")
        val notSharing = state("tethering", "=", "false")
        val notNearCar = state(BT_NEAR_STATE_KEY, "=", "Car").copy(invert = true)

        assertEquals(emptyList<ProfileStateChange>(), changes(notWalking, initiallyActive = false, activity("stationary", standIn = true)))
        assertEquals(emptyList<ProfileStateChange>(), changes(stopped, initiallyActive = false, speed("0", standIn = true)))
        assertEquals(emptyList<ProfileStateChange>(), changes(notSharing, initiallyActive = false, tethering("false", standIn = true)))
        assertEquals(emptyList<ProfileStateChange>(), changes(notNearCar, initiallyActive = false, btNear("", standIn = true)))

        // The same values once measured do turn it on.
        val on = listOf(ProfileStateChange.Activated(null))
        assertEquals(on, changes(notWalking, initiallyActive = false, activity("stationary", standIn = true), activity("stationary")))
        assertEquals(on, changes(stopped, initiallyActive = false, speed("0", standIn = true), speed("0")))
        assertEquals(on, changes(notSharing, initiallyActive = false, tethering("false", standIn = true), tethering("false")))
        assertEquals(on, changes(notNearCar, initiallyActive = false, btNear("", standIn = true), btNear("")))
    }

    @Test
    fun aNewInvertedAppProfileWaitsForAnAppToBeSeen() {
        val notMaps = ContextSpec(ContextType.APPLICATION, mapOf("package" to "com.example.maps"), invert = true)
        val placeholder = ContextEvent("app", false, mapOf("foreground" to "", "component" to "", "component_status" to "unavailable"))
        val browser = ContextEvent("app", true, mapOf("foreground" to "com.example.browser", "component" to "", "component_status" to "unavailable"))

        assertEquals(emptyList<ProfileStateChange>(), changes(notMaps, initiallyActive = false, placeholder))
        assertEquals(listOf(ProfileStateChange.Activated(null)), changes(notMaps, initiallyActive = false, placeholder, browser))
    }

    @Test
    fun anEventDoesNotFireAProfileWhoseOtherContextIsOnlyAStandIn() = runBlocking {
        val nfc = ContextSpec(ContextType.EVENT, mapOf("event" to "nfc"))
        val notWalking = state("activity", "=", "walking").copy(invert = true)
        val specs = listOf(nfc, notWalking)
        val tapped = ContextMatchUpdate(matched = true, pulseContext = true, pulseSequence = 1L)
        val fromStandIn = contextMatchUpdate(notWalking, activity("stationary", standIn = true), previouslyUnread = true)
        val fromSensor = contextMatchUpdate(notWalking, activity("stationary"), previouslyUnread = true)

        val standInSnapshot = profileMatchSnapshot(arrayOf(tapped, fromStandIn), specs, null)
        assertTrue(standInSnapshot.allMatched)
        assertEquals(
            emptyList<ProfileStateChange>(),
            profileStateChangesFromSnapshots(flowOf(standInSnapshot), hasPulseContexts = true).toList(),
        )
        assertEquals(
            listOf(ProfileStateChange.Activated(null)),
            profileStateChangesFromSnapshots(flowOf(profileMatchSnapshot(arrayOf(tapped, fromSensor), specs, null)), hasPulseContexts = true).toList(),
        )
    }

    @Test
    fun aLocaleHostHearsUnknownForAStandIn() {
        val notWalking = state("activity", "=", "walking").copy(invert = true)
        val fromStandIn = contextMatchUpdate(notWalking, activity("stationary", standIn = true), previouslyUnread = true)
        // Inverted, the stand-in matches.
        assertTrue(fromStandIn.matched)

        publishLiveContextState(7, 0, fromStandIn)

        assertEquals(LocalePluginContract.RESULT_CONDITION_UNKNOWN, hostHears(7, 0))
    }

    @Test
    fun aBluetoothNearProfileWaitsLongEnoughForAbsenceToSettle() {
        val wifiHome = state("wifi", "=", "Home")
        val notNearCar = state(BT_NEAR_STATE_KEY, "=", "Car").copy(invert = true)

        assertEquals(FIRST_READINGS_WAIT_MS, firstReadingsWaitMs(listOf(wifiHome)))
        assertTrue(firstReadingsWaitMs(listOf(wifiHome, notNearCar)) >= BT_NEAR_ABSENCE_SETTLES_MS)
    }

    private fun state(key: String, operator: String, value: String) =
        ContextSpec(ContextType.STATE, mapOf("key" to key, "operator" to operator, "value" to value))

    /** What the State source sends once a sensor patch is merged into its first event. */
    private fun stateEvent(key: String, values: Map<String, String>, standIn: Boolean) = ContextEvent(
        "state",
        true,
        seed + values + mapOf(standInMarkerKey(key) to if (standIn) "1" else "", "_setup_$key" to ""),
    )

    private fun activity(value: String, standIn: Boolean = false) =
        stateEvent("activity", mapOf("activity" to value, "activity_steps_per_minute" to "0"), standIn)

    private fun speed(value: String, standIn: Boolean = false) =
        stateEvent("speed", mapOf("speed" to value, "speed_kmh" to value, "speed_provider" to "gps"), standIn)

    private fun tethering(value: String, standIn: Boolean = false) =
        stateEvent("tethering", mapOf("tethering" to value), standIn)

    private fun btNear(devices: String, standIn: Boolean = false) =
        stateEvent(BT_NEAR_STATE_KEY, mapOf(BT_NEAR_STATE_KEY to devices), standIn)

    /** The profile changes [events] make for a matcher built for [spec] alone. */
    private fun changes(spec: ContextSpec, initiallyActive: Boolean, vararg events: ContextEvent) = runBlocking {
        var previous = ContextMatchUpdate.initial(pulseContext = false)
        val updates = listOf(previous) + events.map { event ->
            contextMatchUpdate(spec, event, previouslyUnread = previous.unread).also { previous = it }
        }
        profileStateChangesFromSnapshots(
            updates.map { profileMatchSnapshot(arrayOf(it), listOf(spec), null) }.asFlow(),
            hasPulseContexts = false,
            initiallyActive = initiallyActive,
        ).toList()
    }

    /** What [LocaleConditionQueryReceiver] answers for an enabled profile's context. */
    private fun hostHears(profileId: Long, contextIndex: Int): Int = LocaleConditionEvaluator.evaluate(
        LocaleConditionSpec(LocaleConditionKind.CONTEXT_SATISFIED, profileId = profileId, contextIndex = contextIndex),
        LocaleConditionSnapshot(
            profileExists = true,
            profileEnabled = true,
            contextMatched = AutomationLiveConditionState.contextState(profileId, contextIndex),
        ),
    ).resultCode
}
