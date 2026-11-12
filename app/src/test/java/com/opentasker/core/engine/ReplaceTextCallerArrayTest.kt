package com.opentasker.core.engine

import android.content.ContextWrapper
import com.opentasker.core.registerCoreRuntime
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * A sub-task's Replace Text fills its caller's array in the caller's scope, the way Variable Split
 * and Array Pop do, and a step that fails also empties it there. A Variable Split that fails leaves
 * a list Split Text kept alone.
 */
class ReplaceTextCallerArrayTest {

    @Before
    fun setUp() {
        registerCoreRuntime()
    }

    private fun act(variables: VariableStore, id: String, vararg args: Pair<String, String>): ActionResult =
        runBlocking { ActionRegistry.get(id)!!.run(ActionContext(ContextWrapper(null), variables), mapOf(*args)) }

    private fun replace(variables: VariableStore, pattern: String) =
        act(variables, "text.replace", "source" to "x1y2", "pattern" to pattern, "replacement" to "#", "var" to "out", "matches" to "found")

    @Test
    fun aSubTasksMatchesLandInItsCallersArray() {
        val variables = VariableStore().apply { set("found1", "old") }
        variables.pushScope()

        assertTrue(replace(variables, "\\d") is ActionResult.Success)
        variables.popScope()

        assertEquals(mapOf(1 to "1", 2 to "2"), variables.numberedItems("found").toMap())
    }

    @Test
    fun aSubTaskWhoseStepFailsEmptiesItsCallersArray() {
        val variables = VariableStore().apply { set("found1", "old") }
        variables.pushScope()

        assertTrue(replace(variables, "(") is ActionResult.Failure)
        variables.popScope()

        assertTrue(variables.numberedItems("found").isEmpty())
    }

    @Test
    fun aFailedVariableSplitKeepsAKeptList() {
        val variables = VariableStore()
        act(variables, "text.split", "source" to "a,b", "delimiter" to ",", "var" to "parts")

        assertTrue(act(variables, "var.split", "name" to "parts", "values" to "x(y", "splitter" to "(", "regex" to "true") is ActionResult.Failure)

        assertEquals(listOf("a", "b"), variables.getArrayItems("parts"))
    }
}
