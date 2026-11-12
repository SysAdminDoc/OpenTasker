package com.opentasker.core.engine

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
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * A sensor that starts over after its context was read, as when Bluetooth comes back on or the
 * location providers change, publishes its stand-in again. The profile keeps its state until a
 * measured value comes.
 */
class SensorStandInRestartTest {
    private val seed = mapOf("screen" to "on", "charging" to "false")
    private val on = ProfileStateChange.Activated(null)
    private val off = ProfileStateChange.Deactivated

    @Before
    fun setUp() {
        AutomationLiveConditionState.clear()
    }

    @After
    fun tearDown() {
        AutomationLiveConditionState.clear()
    }

    @Test
    fun aRestartedActivitySensorDoesNotTurnAWalkingProfileOff() {
        val walking = state("activity", "=", "walking")

        assertEquals(listOf(on), changes(walking, activity("walking"), activity("stationary", standIn = true)))
        assertEquals(
            listOf(on, off),
            changes(walking, activity("walking"), activity("stationary", standIn = true), activity("stationary")),
        )
    }

    @Test
    fun aRestartedSpeedSourceDoesNotTurnAStoppedProfileOffOrADrivingOneOn() {
        val driving = state("speed", ">", "5")
        val stopped = state("speed", "<", "1")

        assertEquals(listOf(on), changes(driving, speed("12"), speed("0", standIn = true)))
        assertEquals(emptyList<ProfileStateChange>(), changes(stopped, speed("12"), speed("0", standIn = true)))
        assertEquals(listOf(on), changes(stopped, speed("12"), speed("0", standIn = true), speed("0")))
    }

    @Test
    fun aFreshBluetoothScannerKeepsANearProfileOnUntilAbsenceSettles() {
        val nearCar = state(BT_NEAR_STATE_KEY, "=", "Car")
        val notNearCar = nearCar.copy(invert = true)
        val car = "Car\tAA:BB:CC:DD:EE:FF"

        assertEquals(listOf(on), changes(nearCar, btNear(car), btNear("", standIn = true)))
        assertEquals(listOf(on, off), changes(nearCar, btNear(car), btNear("", standIn = true), btNear("")))
        assertEquals(emptyList<ProfileStateChange>(), changes(notNearCar, btNear(car), btNear("", standIn = true)))
        // A device on a fresh list was seen, so that one counts at once.
        assertEquals(listOf(on, off), changes(notNearCar, btNear(""), btNear("", standIn = true), btNear(car, standIn = true)))
    }

    @Test
    fun aStandInWithTheSourceUnableToMeasureIsNotHeldOver() {
        val walking = state("activity", "=", "walking")
        val revoked = activity("stationary", standIn = true, setup = "Open Setup and grant Physical activity permission.")

        assertEquals(listOf(on, off), changes(walking, activity("walking"), revoked))
    }

    @Test
    fun aLocaleHostKeepsHearingTheMeasuredValueOverARestart() {
        val walking = state("activity", "=", "walking")
        var update = nextContextMatchUpdate(ContextMatchUpdate.initial(pulseContext = false), walking, activity("walking"))
        update = nextContextMatchUpdate(update, walking, activity("stationary", standIn = true))

        publishLiveContextState(7, 0, update)

        assertEquals(LocalePluginContract.RESULT_CONDITION_SATISFIED, hostHears(7, 0))
    }

    private fun state(key: String, operator: String, value: String) =
        ContextSpec(ContextType.STATE, mapOf("key" to key, "operator" to operator, "value" to value))

    /** What the State source sends once a sensor patch is merged into its snapshot. */
    private fun stateEvent(key: String, values: Map<String, String>, standIn: Boolean, setup: String = "") = ContextEvent(
        "state",
        true,
        seed + values + mapOf(standInMarkerKey(key) to if (standIn) "1" else "", "_setup_$key" to setup),
    )

    private fun activity(value: String, standIn: Boolean = false, setup: String = "") =
        stateEvent("activity", mapOf("activity" to value, "activity_steps_per_minute" to "0"), standIn, setup)

    private fun speed(value: String, standIn: Boolean = false) =
        stateEvent("speed", mapOf("speed" to value, "speed_kmh" to value, "speed_provider" to "gps"), standIn)

    private fun btNear(devices: String, standIn: Boolean = false) =
        stateEvent(BT_NEAR_STATE_KEY, mapOf(BT_NEAR_STATE_KEY to devices), standIn)

    /** The profile changes [events] make for a new profile built for [spec] alone, through the matcher's own step. */
    private fun changes(spec: ContextSpec, vararg events: ContextEvent) = runBlocking {
        var previous = ContextMatchUpdate.initial(pulseContext = false)
        val updates = listOf(previous) + events.map { event ->
            nextContextMatchUpdate(previous, spec, event).also { previous = it }
        }
        profileStateChangesFromSnapshots(
            updates.map { profileMatchSnapshot(arrayOf(it), listOf(spec), null) }.asFlow(),
            hasPulseContexts = false,
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
