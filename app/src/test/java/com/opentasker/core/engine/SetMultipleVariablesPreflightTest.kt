package com.opentasker.core.engine

import com.opentasker.core.model.ActionSpec
import com.opentasker.core.model.Task
import com.opentasker.core.registerCoreRuntime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Preflight reads the names a Multiple Variables Set lists as variables it writes. */
class SetMultipleVariablesPreflightTest {

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
    fun aPositionALaterListSetsOnlyWarns() {
        listOf(
            setMultiple("names" to "%a,%Pos", "values" to "1,2"),
            setMultiple("names" to "%a\n%Pos,2"),
        ).forEach { setter ->
            val pop = steps(pop("%Pos"), setter).first()
            assertEquals(setter.args.toString(), PreflightStepStatus.SIMULATED, pop.status)
            assertTrue(pop.warnings.toString(), pop.warnings.any { "%Pos is empty here" in it })
        }
    }

    @Test
    fun aPositionTheListOnlyUnsetsStillStopsTheStep() {
        val pop = steps(pop("%Pos"), setMultiple("names" to "%a,%Pos", "values" to "1")).first()
        assertEquals(PreflightStepStatus.BLOCKED, pop.status)
        assertTrue(pop.warnings.toString(), pop.warnings.any { "The run stops this step" in it })
    }

    @Test
    fun aBuiltInInTheListIsNamed() {
        val warnings = steps(setMultiple("names" to "%a,%TIME", "values" to "1,2")).single().warnings
        assertTrue(warnings.toString(), warnings.any { "%TIME is a Tasker built-in" in it })
    }

    @Test
    fun aBuiltInTheListOnlyUnsetsIsNotNamed() {
        val warnings = steps(setMultiple("names" to "%a,%TIME", "values" to "1")).single().warnings
        assertTrue(warnings.toString(), warnings.none { "Tasker built-in" in it })
    }
}
