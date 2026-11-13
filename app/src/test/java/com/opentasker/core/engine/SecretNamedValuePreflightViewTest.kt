package com.opentasker.core.engine

import com.opentasker.core.actions.ActionArgumentSensitivity
import com.opentasker.core.actions.registerActionMetadata
import com.opentasker.core.model.ActionSpec
import com.opentasker.core.model.Task
import com.opentasker.core.registerCoreRuntime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Preflight's view of a step hides a value given to a secret name the way the run log does. */
class SecretNamedValuePreflightViewTest {

    @Before
    fun setUp() {
        registerCoreRuntime()
        registerActionMetadata()
    }

    private fun steps(vararg actions: ActionSpec) = PreflightRunner.preflightTask(
        Task(id = 1, name = "T", actions = actions.toList()),
        inputs = PreflightInputs(globalVariables = mapOf("Prefix" to "sk-abc")),
    ).tasks.single().steps

    private fun valuesForSlot(slot: String) = steps(
        ActionSpec(type = "var.set", args = mapOf("name" to "%slot", "value" to slot)),
        ActionSpec(type = "var.set_multiple", args = mapOf("names" to "%cache_%slot", "values" to "{{ global.Prefix }}")),
    ).last()

    @Test
    fun aTemplateValueForANameFilledInAsASecretIsHidden() {
        val step = valuesForSlot("token")
        assertEquals(ActionArgumentSensitivity.REDACTED, step.expandedArguments["values"])
        assertTrue(step.warnings.toString(), step.warnings.none { "sk-abc" in it })
    }

    @Test
    fun aTemplateValueForANameFilledInAsAnOrdinaryOneShows() {
        assertEquals("sk-abc", valuesForSlot("color").expandedArguments["values"])
    }

    @Test
    fun aTemplateWarningForASecretNameIsHidden() {
        val step = steps(ActionSpec(type = "var.set", args = mapOf("name" to "%api_key", "value" to "{{ hunter2 }}"))).single()
        assertTrue(step.warnings.toString(), step.warnings.none { "hunter2" in it })
        assertTrue(step.warnings.toString(), "value: ${ActionArgumentSensitivity.REDACTED}" in step.warnings)
    }

    @Test
    fun aTemplateWarningForAnOrdinaryNameStillShows() {
        val step = steps(ActionSpec(type = "var.set", args = mapOf("name" to "%color", "value" to "{{ hunter2 }}"))).single()
        assertTrue(step.warnings.toString(), step.warnings.any { "hunter2" in it })
    }

    @Test
    fun aTemplateWarningForALongValueShownShortenedStillShows() {
        // Text after the template, since the shown value is trimmed and a missing one fills in empty.
        val value = "x".repeat(ActionArgumentSensitivity.DEFAULT_MAX_VALUE_LENGTH) + " {{ hunter2 }} tail"
        val step = steps(ActionSpec(type = "var.set", args = mapOf("name" to "%color", "value" to value))).single()
        assertTrue(step.expandedArguments["value"].orEmpty().endsWith("..."))
        assertTrue(step.warnings.toString(), step.warnings.any { "hunter2" in it })
    }

    @Test
    fun aTemplateWarningForAValueThatReadsASecretStillShows() {
        val step = PreflightRunner.preflightTask(
            Task(id = 1, name = "T", actions = listOf(ActionSpec(type = "var.set", args = mapOf("name" to "%color", "value" to "%Token {{ hunter2 }}")))),
            inputs = PreflightInputs(globalVariables = mapOf("Token" to "sk-abc"), secretGlobalNames = setOf("Token")),
        ).tasks.single().steps.single()
        assertEquals(ActionArgumentSensitivity.REDACTED, step.expandedArguments["value"])
        assertTrue(step.warnings.toString(), step.warnings.any { "hunter2" in it })
    }
}
