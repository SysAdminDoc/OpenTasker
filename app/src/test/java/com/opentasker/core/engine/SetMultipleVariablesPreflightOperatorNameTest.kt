package com.opentasker.core.engine

import com.opentasker.core.model.ActionSpec
import com.opentasker.core.model.Task
import com.opentasker.core.registerCoreRuntime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * A name built from a variable with an operator after it, like `%Title(lower)`, only warns when what
 * the variable holds today doesn't make a name, the same as one without the operator.
 */
class SetMultipleVariablesPreflightOperatorNameTest {

    @Before
    fun setUp() {
        registerCoreRuntime()
    }

    private fun step(names: String, globals: Map<String, String>) = PreflightRunner.preflightTask(
        Task(id = 1, name = "T", actions = listOf(ActionSpec(type = "var.set_multiple", args = mapOf("names" to names, "values" to "x")))),
        inputs = PreflightInputs(globalVariables = globals),
    ).tasks.single().steps.single()

    @Test
    fun aNameBuiltWithACaseOperatorOnlyWarns() {
        val step = step("%slot_%Title(lower)", mapOf("Title" to "My Song"))
        assertNotEquals(step.warnings.toString(), PreflightStepStatus.BLOCKED, step.status)
        assertTrue(step.warnings.toString(), step.warnings.any { "A name built from other variables" in it })
    }

    @Test
    fun aNameBuiltFromTwoOperatorsOnlyWarns() {
        val step = step("%row_%Label(upper)_%Title(trim)", mapOf("Label" to "a b", "Title" to "c"))
        assertNotEquals(step.warnings.toString(), PreflightStepStatus.BLOCKED, step.status)
    }

    @Test
    fun aNameBuiltFromAHyphenatedVariableOnlyWarns() {
        val step = step("%cache_%Pin-code", mapOf("Pin-code" to "12 34"))
        assertNotEquals(step.warnings.toString(), PreflightStepStatus.BLOCKED, step.status)
    }

    @Test
    fun anOperatorDoesNotRescueALineWithASpace() {
        val step = step("Total %Title(lower)", mapOf("Title" to "My Song"))
        assertEquals(PreflightStepStatus.BLOCKED, step.status)
        assertTrue(step.warnings.toString(), step.warnings.any { "invalid variable name(s): Total %Title(lower)" in it })
    }

    @Test
    fun aNameWithOnlyAnUnsetOperatorPassesUnwarned() {
        val step = step("%slot_%list(#)", emptyMap())
        assertNotEquals(step.warnings.toString(), PreflightStepStatus.BLOCKED, step.status)
    }
}
