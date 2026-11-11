package com.opentasker.core.engine

import com.opentasker.core.contexts.ContextEvent
import com.opentasker.core.contexts.TimeContextBounds
import com.opentasker.core.model.ContextSpec
import com.opentasker.core.model.ContextType
import com.opentasker.core.model.ContextBooleanOperator
import com.opentasker.core.model.ContextExpressionNode
import com.opentasker.core.model.Profile
import java.io.IOException
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileMatcherTest {
    @Test
    fun repeatedMatchingEventPulsesActivateEachTime() = runBlocking {
        val changes = profileStateChangesFromSnapshots(
            snapshots = flowOf(
                ProfileMatchSnapshot(allMatched = false, pulseSequence = 0),
                ProfileMatchSnapshot(allMatched = true, pulseSequence = 1),
                ProfileMatchSnapshot(allMatched = true, pulseSequence = 2),
            ),
            hasPulseContexts = true,
        ).toList()

        assertEquals(
            listOf(ProfileStateChange.Activated(null), ProfileStateChange.Activated(null)),
            changes,
        )
    }

    @Test
    fun anEventThatDoesNotMatchTheSpecLeavesThePulseSequenceAlone() {
        // Every EVENT context receives every bridge's traffic, so a notification reaches an NFC
        // context. Advancing on arrival is what let `EVENT(nfc) OR STATE(...)` fire on unrelated
        // traffic while the STATE leaf was already true.
        assertEquals(4L, pulseSequenceAfterObservation(matched = false, observedSequence = 5L, previousSequence = 4L))
        assertEquals(6L, pulseSequenceAfterObservation(matched = true, observedSequence = 6L, previousSequence = 4L))
    }

    @Test
    fun unrelatedPulsesDoNotActivateAProfileWhoseOtherBranchIsAlreadyTrue() = runBlocking {
        // `EVENT(nfc) OR STATE(wifi=Home)` on the home network: allMatched stays true throughout,
        // so only a real sequence advance may activate. Two unrelated events arrive, then a tag.
        var sequence = 4L
        val afterFirstUnrelated = pulseSequenceAfterObservation(false, observedSequence = 5L, previousSequence = sequence)
        val afterSecondUnrelated = pulseSequenceAfterObservation(false, observedSequence = 6L, previousSequence = afterFirstUnrelated)
        val afterTag = pulseSequenceAfterObservation(true, observedSequence = 7L, previousSequence = afterSecondUnrelated)

        val changes = profileStateChangesFromSnapshots(
            snapshots = flowOf(
                ProfileMatchSnapshot(allMatched = true, pulseSequence = sequence),
                ProfileMatchSnapshot(allMatched = true, pulseSequence = afterFirstUnrelated),
                ProfileMatchSnapshot(allMatched = true, pulseSequence = afterSecondUnrelated),
                ProfileMatchSnapshot(allMatched = true, pulseSequence = afterTag),
            ),
            hasPulseContexts = true,
            initialPulseSequence = sequence,
        ).toList()

        assertEquals(listOf(ProfileStateChange.Activated(null)), changes)
    }

    @Test
    fun inheritedPulseBaselineDoesNotRefireThePulseThatStartedDuringReconcile() = runBlocking {
        val changes = profileStateChangesFromSnapshots(
            snapshots = flowOf(
                ProfileMatchSnapshot(allMatched = true, pulseSequence = 7),
                ProfileMatchSnapshot(allMatched = true, pulseSequence = 8),
            ),
            hasPulseContexts = true,
            initialPulseSequence = 7,
        ).toList()

        assertEquals(1, changes.size)
        assertTrue(changes.single() is ProfileStateChange.Activated)
    }

    @Test
    fun eventPulseDoesNotActivateRetroactivelyWhenLevelContextMatchesLater() = runBlocking {
        val changes = profileStateChangesFromSnapshots(
            snapshots = flowOf(
                ProfileMatchSnapshot(allMatched = false, pulseSequence = 0),
                ProfileMatchSnapshot(allMatched = false, pulseSequence = 1),
                ProfileMatchSnapshot(allMatched = true, pulseSequence = 1),
                ProfileMatchSnapshot(allMatched = true, pulseSequence = 2),
            ),
            hasPulseContexts = true,
        ).toList()

        assertEquals(listOf(ProfileStateChange.Activated(null)), changes)
    }

    @Test
    fun matchingPulseCarriesSourceEventForTaskVariables() = runBlocking {
        val event = ContextEvent(
            type = "event",
            matched = true,
            metadata = mapOf("event" to "share", "text" to "hello"),
        )
        val changes = profileStateChangesFromSnapshots(
            snapshots = flowOf(
                ProfileMatchSnapshot(allMatched = false, pulseSequence = 0),
                ProfileMatchSnapshot(allMatched = true, pulseSequence = 1, event = event),
            ),
            hasPulseContexts = true,
        ).toList()

        assertEquals(event, (changes.single() as ProfileStateChange.Activated).event)
    }

    @Test
    fun levelContextsKeepActivationAndDeactivationTransitions() = runBlocking {
        val changes = profileStateChangesFromSnapshots(
            snapshots = flowOf(
                ProfileMatchSnapshot(allMatched = false, pulseSequence = 0),
                ProfileMatchSnapshot(allMatched = true, pulseSequence = 0),
                ProfileMatchSnapshot(allMatched = true, pulseSequence = 0),
                ProfileMatchSnapshot(allMatched = false, pulseSequence = 0),
            ),
            hasPulseContexts = false,
        ).toList()

        assertEquals(
            listOf(ProfileStateChange.Activated(null), ProfileStateChange.Deactivated),
            changes,
        )
    }

    @Test
    fun lifecycleSuppressionPreventsACurrentlyMatchingProfileFromActivating() = runBlocking {
        val profile = com.opentasker.core.model.Profile(
            id = 11,
            name = "Expired",
            enterTaskId = 1,
            lifetime = com.opentasker.core.model.ProfileLifetime.UNTIL_DATE,
            expiresAtMs = 100L,
        )
        val changes = profileStateChangesFromSnapshots(
            snapshots = stabilizeProfileSnapshots(
                snapshots = flowOf(ProfileMatchSnapshot(allMatched = true, pulseSequence = 0)),
                lifecycleTicks = flowOf(Unit),
                profile = profile,
                clock = { 100L },
                hasPulseContexts = false,
            ),
            hasPulseContexts = false,
        ).toList()

        assertTrue(changes.isEmpty())
    }

    @Test
    fun gracePeriodSuppressesATransientLevelMatch() = runBlocking {
        val profile = com.opentasker.core.model.Profile(
            id = 12,
            name = "Stable",
            enterTaskId = 1,
            gracePeriodSec = 1,
        )
        val snapshots = flow {
            emit(ProfileMatchSnapshot(allMatched = false, pulseSequence = 0))
            emit(ProfileMatchSnapshot(allMatched = true, pulseSequence = 0))
            emit(ProfileMatchSnapshot(allMatched = false, pulseSequence = 0))
        }
        val changes = profileStateChangesFromSnapshots(
            snapshots = stabilizeProfileSnapshots(
                snapshots = snapshots,
                lifecycleTicks = flowOf(Unit),
                profile = profile,
                clock = { 0L },
                hasPulseContexts = false,
            ),
            hasPulseContexts = false,
        ).toList()

        assertTrue(changes.isEmpty())
    }

    private fun match(matched: Boolean) = ContextMatchUpdate(matched, pulseContext = false, pulseSequence = 0)
    private fun spec(orGroup: String? = null) = ContextSpec(ContextType.STATE, orGroup = orGroup)

    @Test
    fun andOnlyAllMatchedReturnsTrue() {
        val matches = arrayOf(match(true), match(true))
        val specs = listOf(spec(), spec())
        assertTrue(evaluateWithOrGroups(matches, specs))
    }

    @Test
    fun andOnlyOneFailsReturnsFalse() {
        val matches = arrayOf(match(true), match(false))
        val specs = listOf(spec(), spec())
        assertFalse(evaluateWithOrGroups(matches, specs))
    }

    @Test
    fun orGroupMatchesWhenEitherIsTrue() {
        val matches = arrayOf(match(false), match(true))
        val specs = listOf(spec(orGroup = "wifi"), spec(orGroup = "wifi"))
        assertTrue(evaluateWithOrGroups(matches, specs))
    }

    @Test
    fun orGroupFailsWhenNoneMatch() {
        val matches = arrayOf(match(false), match(false))
        val specs = listOf(spec(orGroup = "wifi"), spec(orGroup = "wifi"))
        assertFalse(evaluateWithOrGroups(matches, specs))
    }

    @Test
    fun mixedAndOrGroupsRequireBoth() {
        val matches = arrayOf(match(true), match(false), match(true))
        val specs = listOf(spec(), spec(orGroup = "net"), spec(orGroup = "net"))
        assertTrue(evaluateWithOrGroups(matches, specs))
    }

    @Test
    fun mixedAndOrFailsWhenAndTermFails() {
        val matches = arrayOf(match(false), match(false), match(true))
        val specs = listOf(spec(), spec(orGroup = "net"), spec(orGroup = "net"))
        assertFalse(evaluateWithOrGroups(matches, specs))
    }

    @Test
    fun emptyContextMatchesReturnsFalse() {
        assertFalse(evaluateWithOrGroups(emptyArray(), emptyList()))
    }

    @Test
    fun nestedExpressionSupportsAndOrAndNotWithoutChangingLegacyEvaluation() {
        val expression = ContextExpressionNode.group(
            ContextBooleanOperator.OR,
            listOf(
                ContextExpressionNode.group(
                    ContextBooleanOperator.AND,
                    listOf(ContextExpressionNode.leaf(0), ContextExpressionNode.leaf(1)),
                ),
                ContextExpressionNode(contextIndex = 2, invert = true),
            ),
        )
        val specs = listOf(
            ContextSpec(ContextType.STATE),
            ContextSpec(ContextType.STATE),
            ContextSpec(ContextType.STATE),
        )

        assertTrue(evaluateContextExpression(arrayOf(match(true), match(true), match(true)), specs, expression))
        assertTrue(evaluateContextExpression(arrayOf(match(false), match(false), match(false)), specs, expression))
        assertFalse(evaluateContextExpression(arrayOf(match(false), match(false), match(true)), specs, expression))
        assertFalse(evaluateWithOrGroups(arrayOf(match(true), match(false)), specs.take(2)))
    }

    @Test
    fun aTimeWindowReadsTheGlobalsOfItsOwnProject() = runBlocking {
        val globalsByProject = mapOf(1L to mapOf("Sunrise" to "5.00"), 7L to mapOf("Sunrise" to "6.42"))
        val sun = ContextSpec(ContextType.TIME, mapOf("start" to "%Sunrise", "end" to "18:00"))
        val tick = ContextEvent("time", true, mapOf("time" to "12:00"))
        val read = mutableListOf<Long>()

        val events = timeBoundedEvents(Profile(id = 3, name = "Daylight", enterTaskId = 1, projectId = 7L), sun, flowOf(tick)) { projectId ->
            read += projectId
            flowOf(globalsByProject[projectId].orEmpty())
        }.toList()

        assertEquals(listOf(7L), read)
        assertEquals("06:42", events.single().metadata[TimeContextBounds.START_VALUE])
        val clockOnly = ContextSpec(ContextType.TIME, mapOf("start" to "06:00", "end" to "18:00"))
        assertEquals(listOf(tick), timeBoundedEvents(Profile(id = 4, name = "Day", enterTaskId = 1), clockOnly, flowOf(tick)) { error("read globals") }.toList())
    }

    @Test
    fun aFailedGlobalsReadIsRetriedWithoutEmittingAnEmptyMap() = runBlocking {
        var reads = 0
        val values = retryingGlobalValues(pauseMs = { 1L }) {
            reads++
            when (reads) {
                1 -> throw IllegalStateException("database not open")
                2 -> flow {
                    emit(mapOf("Sunrise" to "6.42"))
                    throw IOException("cursor closed")
                }
                else -> flowOf(mapOf("Sunrise" to "7.00"))
            }
        }.toList()

        assertEquals(listOf(mapOf("Sunrise" to "6.42"), mapOf("Sunrise" to "7.00")), values)
        assertEquals(3, reads)
    }
}
