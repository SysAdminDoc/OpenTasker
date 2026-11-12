package com.opentasker.core.engine

import com.opentasker.core.contexts.BT_NEAR_STATE_KEY
import com.opentasker.core.contexts.ContextEvent
import com.opentasker.core.contexts.standInMarkerKey
import com.opentasker.core.model.ContextSpec
import com.opentasker.core.model.ContextType
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicLong

/**
 * Once a hold over a sensor restart's stand-in runs out, the context is unknown rather than what
 * the stand-in says, since a stand-in isn't a reading. So it can end a profile that was on but can't
 * start one, an inverted context included.
 */
class SensorStandInExpiredUnknownTest {
    private val nearCar = ContextSpec(ContextType.STATE, mapOf("key" to BT_NEAR_STATE_KEY, "operator" to "=", "value" to "Car"))
    private val notNearCar = nearCar.copy(invert = true)
    private val car = "Car\tAA:BB:CC:DD:EE:FF"
    private val on = ProfileStateChange.Activated(null)
    private val off = ProfileStateChange.Deactivated

    @Test
    fun anExpiredStandInReadsAsUnknown() {
        var update = next(nearCar, ContextMatchUpdate.initial(pulseContext = false), btNear(car), nowMs = 0)
        update = next(nearCar, update, btNear("", standIn = true), nowMs = 0)
        update = next(nearCar, update, btNear("", standIn = true), nowMs = LIMIT_MS)

        assertFalse(update.matched)
        assertTrue(update.unknown)
    }

    @Test
    fun anExpiredStandInEndsAnInvertedProfileThatWasOn() {
        assertEquals(listOf(on, off), changes(notNearCar, btNear("") to 0, btNear("", standIn = true) to 0, btNear("", standIn = true) to LIMIT_MS))
    }

    @Test
    fun anExpiredStandInNeverStartsAnInvertedProfile() {
        assertEquals(emptyList<ProfileStateChange>(), changes(notNearCar, btNear(car) to 0, btNear("", standIn = true) to 0, btNear("", standIn = true) to LIMIT_MS))
    }

    @Test
    fun aHoldEndingOnItsOwnSendsUnknown() = runBlocking {
        val clock = AtomicLong(0)
        val measured = next(notNearCar, ContextMatchUpdate.initial(pulseContext = false), btNear(car), nowMs = 0)
        val held = next(notNearCar, measured, btNear("", standIn = true), nowMs = 0)
        val seen = mutableListOf<ContextMatchUpdate>()

        val collecting = launch {
            flow { emit(held); awaitCancellation() }.endingStandInHolds(TIMER_MS, clock::get).collect { seen += it }
        }
        delay(TIMER_MS / 2)
        clock.set(TIMER_MS)
        delay(4 * TIMER_MS)
        collecting.cancel()

        assertEquals(2, seen.size)
        assertFalse(seen.last().matched)
        assertTrue(seen.last().unknown)
    }

    private fun next(spec: ContextSpec, previous: ContextMatchUpdate, event: ContextEvent, nowMs: Long) =
        nextContextMatchUpdate(previous, spec, event, nowMs = nowMs, holdLimitMs = LIMIT_MS)

    private fun changes(spec: ContextSpec, vararg events: Pair<ContextEvent, Long>) = runBlocking {
        var previous = ContextMatchUpdate.initial(pulseContext = false)
        val updates = listOf(previous) + events.map { (event, at) -> next(spec, previous, event, at).also { previous = it } }
        profileStateChangesFromSnapshots(
            updates.map { profileMatchSnapshot(arrayOf(it), listOf(spec), null) }.asFlow(),
            hasPulseContexts = false,
        ).toList()
    }

    private fun btNear(devices: String, standIn: Boolean = false) = ContextEvent(
        "state",
        true,
        mapOf(BT_NEAR_STATE_KEY to devices, standInMarkerKey(BT_NEAR_STATE_KEY) to if (standIn) "1" else "", "_setup_$BT_NEAR_STATE_KEY" to ""),
    )

    private companion object {
        const val LIMIT_MS = 1_000L
        // Real time the timer test waits, kept short.
        const val TIMER_MS = 100L
    }
}
