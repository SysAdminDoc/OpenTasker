package com.opentasker.core.flow

import com.opentasker.core.model.ContextSpec
import com.opentasker.core.model.ContextType
import com.opentasker.core.model.Profile
import com.opentasker.core.model.Task
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A context card's line stays one short line, and a state it can't read still names its state. */
class AutomationFlowContextOneLineTest {

    private fun detail(context: ContextSpec): String {
        val profile = Profile(id = 1, name = "One line", contexts = listOf(context), enterTaskId = 10)
        val task = Task(id = 10, name = "Run", actions = emptyList())
        return AutomationFlowGraphBuilder.build(profile, listOf(task)).contextNodes.single().detail.orEmpty()
    }

    @Test
    fun aStateNameWithALineBreakStaysOnOneLine() {
        assertEquals("custom state = 1", detail(ContextSpec(ContextType.STATE, mapOf("key" to "custom\nstate", "value" to "1"))))
    }

    @Test
    fun aLongStateNameIsShortenedLikeAValue() {
        val key = "a_state_name_that_keeps_going_and_going_and_going"
        val line = detail(ContextSpec(ContextType.STATE, mapOf("key" to key, "value" to "1")))
        assertFalse(line, key.replace('_', ' ') in line)
        assertTrue(line, line.startsWith("a state name that keeps going"))
        assertTrue(line, line.endsWith("... = 1"))
    }

    @Test
    fun anUnreadablePredicateShowsTheStateNameAsWritten() {
        val line = detail(ContextSpec(ContextType.STATE, mapOf("key" to "battery_level", "predicate" to "battery_level ?? 20")))
        assertTrue(line, "key=battery_level" in line)
        assertFalse(line, "<redacted>" in line)
    }
}
