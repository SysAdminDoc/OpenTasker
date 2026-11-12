package com.opentasker.core.engine

import android.content.ContextWrapper
import com.opentasker.core.actions.registerActionMetadata
import com.opentasker.core.model.ActionSpec
import com.opentasker.core.model.Task
import com.opentasker.core.registerCoreRuntime
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Replace Text's Store matches in array empties the array first, as Tasker's Search Replace does
 * before it even compiles the pattern, so a step that fails or matches nothing leaves no old
 * elements behind. Globals are used so the results outlive the task.
 */
class TextReplaceEmptiedArrayTest {

    @Before
    fun setUp() {
        registerActionMetadata()
        registerCoreRuntime()
    }

    private fun set(name: String, value: String) = ActionSpec(type = "var.set", args = mapOf("name" to name, "value" to value))

    private fun replace(pattern: String) = ActionSpec(
        type = "text.replace",
        args = mapOf("source" to "%Text", "pattern" to pattern, "replacement" to "#", "var" to "%Text", "matches" to "%Found"),
    )

    private fun run(store: VariableStore, vararg actions: ActionSpec): Boolean = runBlocking {
        TaskRunner(ActionContext(ContextWrapper(null), store)).run(Task(id = 1, name = "Replace", actions = actions.toList())).success
    }

    private fun found(store: VariableStore) = store.globalSnapshot().filterKeys { it.startsWith("Found") }

    @Test
    fun aBadPatternFailsTheStepAndStillEmptiesTheArray() {
        val store = VariableStore()

        assertFalse(run(store, set("%Found1", "old"), set("%Found2", "old"), set("%Text", "a1"), replace("(")))

        assertEquals(emptyMap<String, String>(), found(store))
        assertEquals("a1", store.globalSnapshot()["Text"])
    }

    @Test
    fun anArrayIsEmptiedWhenNothingMatches() {
        val store = VariableStore()

        assertTrue(run(store, set("%Found1", "old"), set("%Found3", "old"), set("%Text", "x"), replace("\\d")))

        assertEquals(emptyMap<String, String>(), found(store))
        assertEquals("x", store.globalSnapshot()["Text"])
    }
}
