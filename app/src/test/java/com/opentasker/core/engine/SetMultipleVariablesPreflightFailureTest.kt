package com.opentasker.core.engine

import com.opentasker.core.model.ActionSpec
import com.opentasker.core.model.Task
import com.opentasker.core.registerCoreRuntime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Preflight stops a Multiple Variables Set where the run fails it, and reads which names it gives a value. */
class SetMultipleVariablesPreflightFailureTest {

    @Before
    fun setUp() {
        registerCoreRuntime()
    }

    private fun pop(position: String) =
        ActionSpec(type = "array.pop", args = mapOf("array" to "%arr", "position" to position, "to_var" to "got"))

    private fun setMultiple(vararg args: Pair<String, String>) = ActionSpec(type = "var.set_multiple", args = mapOf(*args))

    private fun steps(vararg actions: ActionSpec) =
        PreflightRunner.preflightTask(Task(id = 1, name = "T", actions = actions.toList())).tasks.single().steps

    @Test
    fun aLineThatIsNoNameStopsTheStep() {
        val step = steps(setMultiple("names" to "%a\nnot a name", "values" to "1\n2")).single()
        assertEquals(PreflightStepStatus.BLOCKED, step.status)
        assertTrue(step.warnings.toString(), step.warnings.any { "The run stops this step: invalid variable name(s): not a name." in it })
    }

    @Test
    fun noNamesStopsTheStep() {
        assertEquals(PreflightStepStatus.BLOCKED, steps(setMultiple("names" to "", "values" to "1")).single().status)
    }

    @Test
    fun aNameBuiltFromAnUnsetVariableOnlyWarns() {
        val step = steps(setMultiple("names" to "%item%n", "values" to "x")).single()
        assertNotEquals(PreflightStepStatus.BLOCKED, step.status)
        assertTrue(step.warnings.toString(), step.warnings.any { "A name built from other variables" in it })
    }

    @Test
    fun aSecretInALineThatIsNoNameIsNotShown() {
        val step = steps(setMultiple("names" to "%api_token,s3cr3t!")).single()
        assertEquals(PreflightStepStatus.BLOCKED, step.status)
        assertTrue(step.warnings.toString(), step.warnings.any { "invalid variable name(s): <redacted>" in it })
        assertFalse(step.warnings.toString(), step.warnings.any { "s3cr3t" in it })
        assertFalse(step.expandedArguments.toString(), step.expandedArguments.values.any { "s3cr3t" in it })
    }

    @Test
    fun valuesFromAVariableMayReachEveryName() {
        val first = steps(pop("%Pos"), setMultiple("names" to "%a,%Pos", "values" to "%line")).first()
        assertEquals(PreflightStepStatus.SIMULATED, first.status)
        assertTrue(first.warnings.toString(), first.warnings.any { "%Pos is empty here" in it })
    }

    @Test
    fun aNameListedOnlyToBeUnsetStillStopsAPop() {
        val first = steps(pop("%Pos"), setMultiple("names" to "%Pos")).first()
        assertEquals(PreflightStepStatus.BLOCKED, first.status)
        assertTrue(first.warnings.toString(), first.warnings.any { "The run stops this step" in it })
    }
}
