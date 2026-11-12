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
 * A Multiple Variables Set name that only fails for what its variables hold today only warns,
 * whether the variable is the whole name, its operator has parentheses inside, or its name has a
 * hyphen.
 */
class SetMultipleVariablesPreflightWholeEntryTest {

    @Before
    fun setUp() {
        registerCoreRuntime()
    }

    private fun step(names: String, globals: Map<String, String>) = PreflightRunner.preflightTask(
        Task(id = 1, name = "T", actions = listOf(ActionSpec(type = "var.set_multiple", args = mapOf("names" to names, "values" to "x")))),
        inputs = PreflightInputs(globalVariables = globals),
    ).tasks.single().steps.single()

    @Test
    fun aNameThatIsOneVariableWithAnOperatorOnlyWarns() {
        val step = step("%Title(lower)", mapOf("Title" to "My Song"))
        assertNotEquals(step.warnings.toString(), PreflightStepStatus.BLOCKED, step.status)
        assertTrue(step.warnings.toString(), step.warnings.any { "A name built from other variables" in it })
    }

    @Test
    fun anOperatorWithParenthesesInsideOnlyWarns() {
        val step = step("%slot_%Title(replace:( ):_)", mapOf("Title" to "My Song!"))
        assertNotEquals(step.warnings.toString(), PreflightStepStatus.BLOCKED, step.status)
        assertTrue(step.warnings.toString(), step.warnings.any { "A name built from other variables" in it })
    }

    @Test
    fun aHyphenatedVariableWithAnOperatorOnlyWarns() {
        val step = step("%cache_%Pin-code(lower)", mapOf("Pin-code" to "A B"))
        assertNotEquals(step.warnings.toString(), PreflightStepStatus.BLOCKED, step.status)
        assertTrue(step.warnings.toString(), step.warnings.any { "A name built from other variables" in it })
    }

    @Test
    fun aWholeEntryVariableDoesNotRescueTextAfterIt() {
        val step = step("%Title(lower) total", mapOf("Title" to "My Song"))
        assertEquals(PreflightStepStatus.BLOCKED, step.status)
    }
}
