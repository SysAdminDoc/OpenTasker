package com.opentasker.core.engine

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A profile that starts off says so once its first readings settle, so a Locale host asking whether
 * it's active hears "no" instead of unknown until the profile first goes on.
 */
class ProfileSettledOffTest {
    private val waiting = ProfileMatchSnapshot(allMatched = false, pulseSequence = 0L, awaitingFirstReadings = true, readMatched = false)
    private val off = ProfileMatchSnapshot(allMatched = false, pulseSequence = 0L)
    private val on = ProfileMatchSnapshot(allMatched = true, pulseSequence = 0L)

    private class Run(val changes: List<ProfileStateChange>, val settledOff: Int)

    private fun run(
        snapshots: Flow<ProfileMatchSnapshot>,
        initiallyActive: Boolean = false,
        hasPulseContexts: Boolean = false,
        waitMs: Long = FIRST_READINGS_WAIT_MS,
    ): Run = runBlocking {
        var settledOff = 0
        val changes = profileStateChangesFromSnapshots(
            snapshots,
            hasPulseContexts = hasPulseContexts,
            initiallyActive = initiallyActive,
            firstReadingsWaitMs = waitMs,
            onSettledOff = { settledOff++ },
        ).toList()
        Run(changes, settledOff)
    }

    @Test
    fun firstReadingsThatDontMatchSayTheProfileIsOff() {
        val result = run(listOf(off, off).asFlow())

        assertEquals(emptyList<ProfileStateChange>(), result.changes)
        assertEquals(1, result.settledOff)
    }

    @Test
    fun aProfileStillWaitingOnAReadingIsntCalledOffYet() {
        assertEquals(0, run(listOf(waiting, waiting).asFlow()).settledOff)
        assertEquals(1, run(listOf(waiting, off).asFlow()).settledOff)
    }

    @Test
    fun aReadingThatNeverComesLeavesTheProfileOffOnceTheWaitEnds() {
        val result = run(
            flow {
                emit(waiting)
                delay(400)
            },
            waitMs = 50,
        )

        assertEquals(emptyList<ProfileStateChange>(), result.changes)
        assertEquals(1, result.settledOff)
    }

    @Test
    fun aProfileThatGoesOnFirstReportsThatAndNotOff() {
        val result = run(listOf(waiting, on, off).asFlow())

        assertEquals(listOf(ProfileStateChange.Activated(null), ProfileStateChange.Deactivated), result.changes)
        assertEquals(0, result.settledOff)
    }

    @Test
    fun aProfileThatWasOnKeepsItsStateUntilItsReadingsSayOtherwise() {
        val result = run(listOf(waiting, off).asFlow(), initiallyActive = true)

        assertEquals(listOf(ProfileStateChange.Deactivated), result.changes)
        assertEquals(0, result.settledOff)
    }

    @Test
    fun anEventProfileIsOffUntilItsEventComes() {
        val result = run(listOf(off).asFlow(), hasPulseContexts = true)

        assertEquals(emptyList<ProfileStateChange>(), result.changes)
        assertEquals(1, result.settledOff)
    }
}
