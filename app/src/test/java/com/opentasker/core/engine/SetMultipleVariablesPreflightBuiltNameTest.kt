package com.opentasker.core.engine

import com.opentasker.core.model.ActionSpec
import com.opentasker.core.model.Task
import com.opentasker.core.registerCoreRuntime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Preflight only waits on a name built from other variables when filling them in could make it a name. */
class SetMultipleVariablesPreflightBuiltNameTest {

    @Before
    fun setUp() {
        registerCoreRuntime()
    }

    private fun step(names: String) = PreflightRunner.preflightTask(
        Task(id = 1, name = "T", actions = listOf(ActionSpec(type = "var.set_multiple", args = mapOf("names" to names, "values" to "x")))),
    ).tasks.single().steps.single()

    @Test
    fun aLineWithASpaceStopsTheStepWhateverItsVariableHolds() {
        val step = step("Total %count")
        assertEquals(PreflightStepStatus.BLOCKED, step.status)
        assertTrue(step.warnings.toString(), step.warnings.any { "invalid variable name(s): Total %count" in it })
    }

    @Test
    fun aNameWithAVariableInsideOnlyWarns() {
        val step = step("%cache_%Pin")
        assertNotEquals(PreflightStepStatus.BLOCKED, step.status)
        assertTrue(step.warnings.toString(), step.warnings.any { "A name built from other variables" in it })
    }
}
