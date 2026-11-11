package com.opentasker.core.engine

import com.opentasker.core.contexts.ContextEvent
import com.opentasker.core.contexts.DeviceStateEvents
import com.opentasker.core.model.ContextBooleanOperator
import com.opentasker.core.model.ContextExpressionNode
import com.opentasker.core.model.ContextSpec
import com.opentasker.core.model.ContextType
import com.opentasker.core.model.Profile
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a reload's first readings can and can't settle for a profile that was on: a source's first
 * event that doesn't carry a context's value, a reading that never comes, a value that was read and
 * is still unknown inside an expression, and a profile that matched but never ran.
 */
class ProfileReloadReadingsTest {
    private val wifiHome = ContextSpec(ContextType.STATE, mapOf("key" to "wifi", "value" to "Home"))
    private val charging = ContextSpec(ContextType.STATE, mapOf("key" to "charging", "value" to "true"))
    private val nearHome = ContextSpec(
        ContextType.LOCATION,
        mapOf("latitude" to "40.0", "longitude" to "-75.0", "radius" to "200"),
    )
    private val bothRequired = listOf(wifiHome, charging)

    /** What the State source sends first: what it reads at once, without Wi-Fi. */
    private val seed = ContextEvent("state", true, mapOf("screen" to "on", "charging" to "true", "airplane" to "false"))
    private val seedAndWifi = seed.copy(metadata = seed.metadata + DeviceStateEvents.wifiPatch("Home", connected = true))

    private val unread = ContextMatchUpdate.initial(pulseContext = false)
    private val refused = ContextMatchUpdate(matched = false, pulseContext = false, pulseSequence = 0, unknown = true)
    private fun read(matched: Boolean) = ContextMatchUpdate(matched, pulseContext = false, pulseSequence = 0)

    private val leaf0 = ContextExpressionNode.leaf(0)
    private val leaf1 = ContextExpressionNode.leaf(1)
    private val both = ContextExpressionNode.group(ContextBooleanOperator.AND, listOf(leaf0, leaf1))
    private val either = ContextExpressionNode.group(ContextBooleanOperator.OR, listOf(leaf0, leaf1))
    private val notFirstAndSecond = ContextExpressionNode.group(
        ContextBooleanOperator.AND,
        listOf(ContextExpressionNode.group(ContextBooleanOperator.AND, listOf(leaf0), invert = true), leaf1),
    )

    private fun snapshot(
        vararg updates: ContextMatchUpdate,
        specs: List<ContextSpec> = bothRequired,
        expression: ContextExpressionNode? = null,
    ): ProfileMatchSnapshot = profileMatchSnapshot(arrayOf(*updates), specs, expression)

    private fun changesAfterReload(vararg snapshots: ProfileMatchSnapshot) = runBlocking {
        profileStateChangesFromSnapshots(snapshots.asList().asFlow(), hasPulseContexts = false, initiallyActive = true).toList()
    }

    @Test
    fun aStateEventWithoutTheContextsValueLeavesItUnread() {
        val afterSeed = contextMatchUpdate(wifiHome, seed, previouslyUnread = true)
        assertTrue(afterSeed.unread)
        assertFalse(afterSeed.matched)

        val afterWifi = contextMatchUpdate(wifiHome, seedAndWifi, previouslyUnread = afterSeed.unread)
        assertFalse(afterWifi.unread)
        assertTrue(afterWifi.matched)

        // A value the first event does carry is read by it, and a read context stays read.
        assertFalse(contextMatchUpdate(charging, seed, previouslyUnread = true).unread)
        assertFalse(contextMatchUpdate(wifiHome, seed, previouslyUnread = false).unread)
    }

    @Test
    fun aWifiProfileThatWasOnStaysOnThroughTheStateSourcesFirstEvent() {
        val specs = listOf(wifiHome)
        val afterSeed = contextMatchUpdate(wifiHome, seed, previouslyUnread = true)
        val afterWifi = contextMatchUpdate(wifiHome, seedAndWifi, previouslyUnread = afterSeed.unread)

        assertEquals(
            emptyList<ProfileStateChange>(),
            changesAfterReload(
                snapshot(unread, specs = specs),
                snapshot(afterSeed, specs = specs),
                snapshot(afterWifi, specs = specs),
            ),
        )
    }

    @Test
    fun anExpressionWaitsOnAStateValueTheFirstEventDidNotCarry() {
        val wifiFromSeed = contextMatchUpdate(wifiHome, seed, previouslyUnread = true)
        val chargingFromSeed = contextMatchUpdate(charging, seed, previouslyUnread = true)

        assertTrue(awaitingFirstReadings(arrayOf(wifiFromSeed, chargingFromSeed), bothRequired, both))
    }

    @Test
    fun aLocationContextIsUnreadUntilTheFirstFix() {
        val waiting = ContextEvent("location", false, mapOf("setup" to "waiting_for_location"))
        val fix = ContextEvent("location", true, mapOf("latitude" to "40.0001", "longitude" to "-75.0001"))

        assertTrue(contextMatchUpdate(nearHome, waiting, previouslyUnread = true).unread)
        assertFalse(contextMatchUpdate(nearHome, fix, previouslyUnread = true).unread)
    }

    @Test
    fun aReadingThatNeverComesStopsHoldingItOnAfterTheWait() = runBlocking {
        val waiting = snapshot(read(true), unread)
        assertTrue(waiting.awaitingFirstReadings)

        val change = withTimeout(10_000) {
            profileStateChangesFromSnapshots(
                flow { emit(waiting); awaitCancellation() },
                hasPulseContexts = false,
                initiallyActive = true,
                firstReadingsWaitMs = 50,
            ).first()
        }

        assertEquals(ProfileStateChange.Deactivated, change)
    }

    @Test
    fun itIsHeldOnWhileTheWaitRuns() = runBlocking {
        val change = withTimeoutOrNull(500) {
            profileStateChangesFromSnapshots(
                flow { emit(snapshot(read(true), unread)); awaitCancellation() },
                hasPulseContexts = false,
                initiallyActive = true,
                firstReadingsWaitMs = 60_000,
            ).first()
        }

        assertNull(change)
    }

    @Test
    fun aReadingInsideTheWaitSettlesItAndLaterOnesGoThrough() = runBlocking {
        val changes = withTimeout(10_000) {
            profileStateChangesFromSnapshots(
                flow {
                    emit(snapshot(read(true), unread))
                    delay(20)
                    emit(snapshot(read(true), read(false)))
                    delay(20)
                    emit(snapshot(read(true), read(true)))
                    awaitCancellation()
                },
                hasPulseContexts = false,
                initiallyActive = true,
                firstReadingsWaitMs = 60_000,
            ).take(2).toList()
        }

        assertEquals(listOf(ProfileStateChange.Deactivated, ProfileStateChange.Activated(null)), changes)
    }

    @Test
    fun snapshotsThatEndDuringTheWaitSendNothingForIt() = runBlocking {
        val changes = withTimeout(10_000) {
            profileStateChangesFromSnapshots(
                listOf(snapshot(read(true), unread)).asFlow(),
                hasPulseContexts = false,
                initiallyActive = true,
                firstReadingsWaitMs = 50,
            ).toList()
        }

        assertEquals(emptyList<ProfileStateChange>(), changes)
    }

    @Test
    fun aValueThatWasReadAndIsStillUnknownDoesNotWaitOnTheRest() {
        // Unknown AND anything can't be true, so the unread context can't turn it on.
        assertFalse(awaitingFirstReadings(arrayOf(refused, unread), bothRequired, both))
        assertFalse(awaitingFirstReadings(arrayOf(refused, unread), bothRequired, notFirstAndSecond))
        // Unknown OR the unread one is true once that one is.
        assertTrue(awaitingFirstReadings(arrayOf(refused, unread), bothRequired, either))
        assertTrue(awaitingFirstReadings(arrayOf(read(true), unread), bothRequired, both))

        assertEquals(listOf(ProfileStateChange.Deactivated), changesAfterReload(snapshot(refused, unread, expression = both)))
    }

    @Test
    fun theAppSourcesEmptyFirstEventLeavesAnAppContextUnread() {
        val maps = ContextSpec(ContextType.APPLICATION, mapOf("package" to "com.example.maps"))
        val notMaps = maps.copy(invert = true)
        val placeholder = ContextEvent("app", false, mapOf("foreground" to "", "component" to "", "component_status" to "unavailable"))
        val browser = ContextEvent("app", true, mapOf("foreground" to "com.example.browser", "component" to "", "component_status" to "unavailable"))

        assertTrue(contextMatchUpdate(maps, placeholder, previouslyUnread = true).unread)
        // Inverted, the placeholder matches, and still can't turn a profile that was on off.
        val notMapsFromPlaceholder = contextMatchUpdate(notMaps, placeholder, previouslyUnread = true)
        assertTrue(notMapsFromPlaceholder.unread)
        assertTrue(snapshot(notMapsFromPlaceholder, specs = listOf(notMaps)).awaitingFirstReadings)
        assertFalse(contextMatchUpdate(maps, browser, previouslyUnread = true).unread)
    }

    @Test
    fun aContextStillUnreadAfterTheWaitCannotKeepItOnOrTurnItBackOn() = runBlocking {
        val notMaps = ContextSpec(ContextType.APPLICATION, mapOf("package" to "com.example.maps"), invert = true)
        val specs = listOf(notMaps, charging)
        val placeholder = ContextEvent("app", false, mapOf("foreground" to "", "component" to "", "component_status" to "unavailable"))
        val notMapsFromPlaceholder = contextMatchUpdate(notMaps, placeholder, previouslyUnread = true)
        val standIn = snapshot(notMapsFromPlaceholder, read(true), specs = specs)
        // Inverted, the placeholder matches, so only its being unread keeps it from counting.
        assertTrue(standIn.allMatched)

        val changes = mutableListOf<ProfileStateChange>()
        withTimeoutOrNull(1_000) {
            profileStateChangesFromSnapshots(
                flow {
                    emit(standIn)
                    delay(200)
                    emit(snapshot(notMapsFromPlaceholder, read(false), specs = specs))
                    emit(snapshot(notMapsFromPlaceholder, read(true), specs = specs))
                    awaitCancellation()
                },
                hasPulseContexts = false,
                initiallyActive = true,
                firstReadingsWaitMs = 50,
            ).toList(changes)
        }

        assertEquals(listOf(ProfileStateChange.Deactivated), changes)
    }

    @Test
    fun anUnreadContextCountsAsUnknownInsideANotGroup() {
        val standInNo = ContextMatchUpdate(matched = false, pulseContext = false, pulseSequence = 0, unread = true)

        val fromStandIn = snapshot(standInNo, read(true), expression = notFirstAndSecond)

        assertTrue(fromStandIn.allMatched)
        assertTrue(fromStandIn.awaitingFirstReadings)
        assertFalse(fromStandIn.readMatched)
        // Once it's read, the two agree.
        assertTrue(snapshot(read(false), read(true), expression = notFirstAndSecond).readMatched)
    }
}
