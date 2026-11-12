package com.opentasker.core.engine

import com.opentasker.core.contexts.BT_NEAR_CYCLE_MS
import com.opentasker.core.contexts.BT_NEAR_STATE_KEY
import com.opentasker.core.contexts.ContextEvent
import com.opentasker.core.contexts.standInMarkerKey
import com.opentasker.core.model.ContextSpec
import com.opentasker.core.model.ContextType
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A fresh Bluetooth Near scanner can't say yet who's away, but a device it lists was seen, and a
 * profile waits for absence long enough to cover a first scan that couldn't start.
 */
class BluetoothNearFirstReadingTest {
    private val nearCar = ContextSpec(ContextType.STATE, mapOf("key" to BT_NEAR_STATE_KEY, "operator" to "=", "value" to "Car"))

    @Test
    fun aNewNearProfileTurnsOnAsSoonAsAFreshScannerSeesTheDevice() {
        assertEquals(
            listOf(ProfileStateChange.Activated(null)),
            changes(nearCar, btNear("Car\tAA:BB:CC:DD:EE:FF", standIn = true)),
        )
        // A list without it is only "not seen yet".
        assertEquals(emptyList<ProfileStateChange>(), changes(nearCar, btNear("Phone\t11:22:33:44:55:66", standIn = true)))
    }

    @Test
    fun theWaitOutlastsAbsenceSettlingAfterAFailedFirstScan() {
        val forgetAfterMs = 6 * 60_000L
        val scanMs = 12_000L
        // The first scan fails, the next starts a cycle later, and its result is published when it ends.
        val settledAndPublishedMs = BT_NEAR_CYCLE_MS + forgetAfterMs + scanMs

        assertTrue(firstReadingsWaitMs(listOf(nearCar.copy(invert = true))) > settledAndPublishedMs)
    }

    private fun btNear(devices: String, standIn: Boolean) = ContextEvent(
        "state",
        true,
        mapOf(
            "screen" to "on",
            BT_NEAR_STATE_KEY to devices,
            standInMarkerKey(BT_NEAR_STATE_KEY) to if (standIn) "1" else "",
            "_setup_$BT_NEAR_STATE_KEY" to "",
        ),
    )

    /** The changes [events] make for a new profile built on [spec] alone. */
    private fun changes(spec: ContextSpec, vararg events: ContextEvent) = runBlocking {
        var previous = ContextMatchUpdate.initial(pulseContext = false)
        val updates = listOf(previous) + events.map { event ->
            contextMatchUpdate(spec, event, previouslyUnread = previous.unread).also { previous = it }
        }
        profileStateChangesFromSnapshots(
            updates.map { profileMatchSnapshot(arrayOf(it), listOf(spec), null) }.asFlow(),
            hasPulseContexts = false,
            initiallyActive = false,
        ).toList()
    }
}
