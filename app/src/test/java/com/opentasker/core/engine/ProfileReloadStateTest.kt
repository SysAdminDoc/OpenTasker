package com.opentasker.core.engine

import com.opentasker.core.model.ContextBooleanOperator
import com.opentasker.core.model.ContextExpressionNode
import com.opentasker.core.model.ContextSpec
import com.opentasker.core.model.ContextType
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A reload builds a profile's matcher again when its contexts were edited, and the new one starts
 * with nothing read. A profile that was on before it has to come out of that still on: its
 * contexts arriving one at a time, or all still matching, must not run its enter task again, and
 * contexts that no longer match must switch it off so its exit task runs.
 */
class ProfileReloadStateTest {
    private val wifi = ContextSpec(ContextType.STATE, mapOf("key" to "wifi", "value" to "Home"))
    private val charging = ContextSpec(ContextType.STATE, mapOf("key" to "charging", "value" to "true"))
    private val bothRequired = listOf(wifi, charging)
    private val eitherOne = listOf(wifi.copy(orGroup = "a"), charging.copy(orGroup = "a"))

    private val unread = ContextMatchUpdate.initial(pulseContext = false)
    private fun read(matched: Boolean) = ContextMatchUpdate(matched, pulseContext = false, pulseSequence = 0)

    /** What the matcher builds from one set of readings. */
    private fun snapshot(
        vararg updates: ContextMatchUpdate,
        specs: List<ContextSpec> = bothRequired,
        expression: ContextExpressionNode? = null,
    ): ProfileMatchSnapshot = profileMatchSnapshot(arrayOf(*updates), specs, expression)

    private fun changes(initiallyActive: Boolean, vararg snapshots: ProfileMatchSnapshot) = runBlocking {
        profileStateChangesFromSnapshots(snapshots.asList().asFlow(), hasPulseContexts = false, initiallyActive = initiallyActive).toList()
    }

    @Test
    fun aProfileThatStillMatchesAfterAReloadRunsNothing() {
        val reload = arrayOf(
            snapshot(unread, unread),
            snapshot(read(true), unread),
            snapshot(read(true), read(true)),
        )

        assertEquals(emptyList<ProfileStateChange>(), changes(initiallyActive = true, *reload))
        // The same readings still turn on a profile that wasn't on.
        assertEquals(listOf(ProfileStateChange.Activated(null)), changes(initiallyActive = false, *reload))
    }

    @Test
    fun aProfileThatNoLongerMatchesAfterAReloadSwitchesOffOnce() {
        assertEquals(
            listOf(ProfileStateChange.Deactivated),
            changes(
                initiallyActive = true,
                snapshot(unread, unread),
                snapshot(read(true), unread),
                snapshot(read(true), read(false)),
                snapshot(read(false), read(false)),
            ),
        )
    }

    @Test
    fun oneReadingThatSettlesItSwitchesItOffWithoutWaitingForTheRest() {
        assertEquals(
            listOf(ProfileStateChange.Deactivated),
            changes(initiallyActive = true, snapshot(unread, unread), snapshot(read(false), unread)),
        )
    }

    @Test
    fun itStaysOnWhileTheAnswerWaitsOnAReading() {
        assertEquals(
            emptyList<ProfileStateChange>(),
            changes(initiallyActive = true, snapshot(unread, unread), snapshot(read(true), unread)),
        )
    }

    @Test
    fun aProfileThatWasOnCanStillGoOffAndBackOnLater() {
        assertEquals(
            listOf(ProfileStateChange.Deactivated, ProfileStateChange.Activated(null)),
            changes(
                initiallyActive = true,
                snapshot(read(true), read(true)),
                snapshot(read(true), read(false)),
                snapshot(read(true), read(true)),
            ),
        )
    }

    @Test
    fun aValueThatWasReadButIsUnknownDoesNotHoldItOn() {
        val refused = ContextMatchUpdate(matched = false, pulseContext = false, pulseSequence = 0, unknown = true)

        assertEquals(listOf(ProfileStateChange.Deactivated), changes(initiallyActive = true, snapshot(read(true), refused)))
    }

    @Test
    fun anOrGroupIsSettledByOneMatchingMember() {
        assertFalse(awaitingFirstReadings(arrayOf(unread, read(true)), eitherOne, null))
        assertTrue(awaitingFirstReadings(arrayOf(unread, read(false)), eitherOne, null))
        assertFalse(awaitingFirstReadings(arrayOf(read(false), read(false)), eitherOne, null))
    }

    @Test
    fun anExpressionIsSettledWhenItsUnreadPartsCannotChangeIt() {
        val notWifiAndCharging = ContextExpressionNode.group(
            ContextBooleanOperator.AND,
            listOf(
                ContextExpressionNode.group(ContextBooleanOperator.AND, listOf(ContextExpressionNode.leaf(0)), invert = true),
                ContextExpressionNode.leaf(1),
            ),
        )

        assertFalse(awaitingFirstReadings(arrayOf(unread, read(false)), bothRequired, notWifiAndCharging))
        assertTrue(awaitingFirstReadings(arrayOf(unread, read(true)), bothRequired, notWifiAndCharging))
        assertEquals(
            listOf(ProfileStateChange.Deactivated),
            changes(
                initiallyActive = true,
                snapshot(unread, read(true), expression = notWifiAndCharging),
                snapshot(read(true), read(true), expression = notWifiAndCharging),
            ),
        )
    }

    @Test
    fun onlyALevelContextStartsUnread() {
        assertTrue(ContextMatchUpdate.initial(pulseContext = false).unread)
        assertFalse(ContextMatchUpdate.initial(pulseContext = true).unread)
        assertFalse(awaitingFirstReadings(arrayOf(read(true), read(true)), bothRequired, null))
    }

    @Test
    fun aProfileThatWasOnAndNowWaitsForAnEventSwitchesOffFirst() = runBlocking {
        val changes = profileStateChangesFromSnapshots(
            snapshots = flowOf(
                ProfileMatchSnapshot(allMatched = false, pulseSequence = 0),
                ProfileMatchSnapshot(allMatched = true, pulseSequence = 1),
            ),
            hasPulseContexts = true,
            initiallyActive = true,
        ).toList()

        assertEquals(listOf(ProfileStateChange.Deactivated, ProfileStateChange.Activated(null)), changes)
    }
}
