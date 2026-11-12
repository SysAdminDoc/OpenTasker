package com.opentasker.core.engine

import com.opentasker.core.contexts.BT_NEAR_STATE_KEY
import com.opentasker.core.contexts.ContextEvent
import com.opentasker.core.contexts.standInMarkerKey
import com.opentasker.core.model.ContextSpec
import com.opentasker.core.model.ContextType
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicLong

/**
 * A restart stand-in is held over for as long as a first reading gets, not for good: a Bluetooth
 * scan that keeps failing publishes stand-ins forever, and a Speed source with no live fix never
 * sends anything after its stale last-known one.
 */
class SensorStandInHoldLimitTest {
    private val nearCar = ContextSpec(ContextType.STATE, mapOf("key" to BT_NEAR_STATE_KEY, "operator" to "=", "value" to "Car"))
    private val car = "Car\tAA:BB:CC:DD:EE:FF"

    @Test
    fun aHoldRunsOutAfterItsLimitAndStaysOutUntilAMeasuredValue() {
        var update = next(ContextMatchUpdate.initial(pulseContext = false), btNear(car), nowMs = 0)
        assertTrue(update.matched)

        update = next(update, btNear("", standIn = true), nowMs = 100)
        assertTrue("held", update.matched)
        update = next(update, btNear("", standIn = true), nowMs = 1_099)
        assertTrue("still held, counted from the first stand-in", update.matched)
        update = next(update, btNear("", standIn = true), nowMs = 1_100)
        assertFalse("ran out", update.matched)
        update = next(update, btNear("", standIn = true), nowMs = 1_200)
        assertFalse("stays out", update.matched)

        update = next(update, btNear(car), nowMs = 1_300)
        assertTrue(update.matched)
        assertNull(update.holdingSinceMs)
        update = next(update, btNear("", standIn = true), nowMs = 1_400)
        assertTrue("a new restart gets its own hold", update.matched)
    }

    @Test
    fun aHoldEndsOnItsOwnOnceTheClockPassesItsLimit() = runBlocking {
        val clock = AtomicLong(0)
        val measured = next(ContextMatchUpdate.initial(pulseContext = false), btNear(car), nowMs = 0)
        val held = next(measured, btNear("", standIn = true), nowMs = 0)
        val seen = mutableListOf<Boolean>()

        val collecting = launch {
            flow { emit(measured); emit(held); awaitCancellation() }
                .endingStandInHolds(LIMIT_MS, clock::get)
                .collect { seen += it.matched }
        }
        // Real time passes but the matcher's clock doesn't, so the hold goes on.
        delay(3 * LIMIT_MS)
        assertEquals(listOf(true, true), seen)

        clock.set(LIMIT_MS)
        delay(4 * LIMIT_MS)
        assertEquals(listOf(true, true, false), seen)
        collecting.cancel()
    }

    @Test
    fun anUpdateAfterTheHoldKeepsItFromEndingLate() = runBlocking {
        val clock = AtomicLong(0)
        val updates = Channel<ContextMatchUpdate>(Channel.UNLIMITED)
        val measured = next(ContextMatchUpdate.initial(pulseContext = false), btNear(car), nowMs = 0)
        val held = next(measured, btNear("", standIn = true), nowMs = 0)
        val seen = mutableListOf<Boolean>()

        val collecting = launch {
            updates.receiveAsFlow().endingStandInHolds(LIMIT_MS, clock::get).collect { seen += it.matched }
        }
        updates.send(measured)
        updates.send(held)
        delay(LIMIT_MS / 2)
        updates.send(next(held, btNear(car), nowMs = 0))
        clock.set(10 * LIMIT_MS)
        delay(4 * LIMIT_MS)

        assertEquals(listOf(true, true, true), seen)
        collecting.cancel()
    }

    private fun next(previous: ContextMatchUpdate, event: ContextEvent, nowMs: Long) =
        nextContextMatchUpdate(previous, nearCar, event, nowMs = nowMs, holdLimitMs = 1_000)

    private fun btNear(devices: String, standIn: Boolean = false) = ContextEvent(
        "state",
        true,
        mapOf(BT_NEAR_STATE_KEY to devices, standInMarkerKey(BT_NEAR_STATE_KEY) to if (standIn) "1" else "", "_setup_$BT_NEAR_STATE_KEY" to ""),
    )

    private companion object {
        const val LIMIT_MS = 100L
    }
}
