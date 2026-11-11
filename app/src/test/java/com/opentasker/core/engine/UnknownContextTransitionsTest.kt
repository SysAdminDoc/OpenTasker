package com.opentasker.core.engine

import com.opentasker.core.contexts.ContextEvent
import com.opentasker.core.contexts.TimeContextBounds
import com.opentasker.core.model.ContextBooleanOperator
import com.opentasker.core.model.ContextExpressionNode
import com.opentasker.core.model.ContextSpec
import com.opentasker.core.model.ContextType
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The matcher's own chain for one level context: the scan seed, [contextMatchUpdate] per reading,
 * the expression, then [profileStateChangesFromSnapshots]. A NOT group over a value that isn't
 * there yet doesn't turn the profile on, and one that was on turns off while the value is gone.
 */
class UnknownContextTransitionsTest {
    private val daylight = ContextSpec(ContextType.TIME, mapOf("start" to "%Sunrise", "end" to "%Sunset"))
    private val notDaylight = ContextExpressionNode.group(ContextBooleanOperator.AND, listOf(ContextExpressionNode.leaf(0)), invert = true)

    private fun night(bounds: Boolean) = ContextEvent(
        "time",
        true,
        mapOf("time" to "22:00") +
            if (bounds) mapOf(TimeContextBounds.START_VALUE to "06:42", TimeContextBounds.END_VALUE to "16:51") else emptyMap(),
    )

    private fun changes(vararg readings: ContextEvent) = runBlocking {
        val updates = readings.asList().asFlow().scan(ContextMatchUpdate.initial(pulseContext = false)) { _, event ->
            contextMatchUpdate(daylight, event)
        }
        val snapshots = updates.map { update ->
            ProfileMatchSnapshot(allMatched = evaluateContextExpression(arrayOf(update), listOf(daylight), notDaylight), pulseSequence = 0)
        }
        profileStateChangesFromSnapshots(snapshots, hasPulseContexts = false).toList()
    }

    @Test
    fun aNotGroupStaysOffUntilItsValueIsRead() {
        assertEquals(emptyList<ProfileStateChange>(), changes())
        assertEquals(emptyList<ProfileStateChange>(), changes(night(bounds = false)))
        assertEquals(listOf(ProfileStateChange.Activated(null)), changes(night(bounds = false), night(bounds = true)))
    }

    @Test
    fun aNotGroupThatWasOnTurnsOffWhileItsValueIsGoneAndBackOnWhenItReturns() {
        assertEquals(
            listOf(ProfileStateChange.Activated(null), ProfileStateChange.Deactivated, ProfileStateChange.Activated(null)),
            changes(night(bounds = true), night(bounds = false), night(bounds = true)),
        )
    }
}
