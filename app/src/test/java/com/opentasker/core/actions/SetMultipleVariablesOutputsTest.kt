package com.opentasker.core.actions

import com.opentasker.core.flow.AutomationFlowGraphBuilder
import com.opentasker.core.flow.AutomationFlowNodeKind
import com.opentasker.core.model.ActionSpec
import com.opentasker.core.model.Profile
import com.opentasker.core.model.Task
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The names a Multiple Variables Set gives a value show up as variable chips and on its Flow card. */
class SetMultipleVariablesOutputsTest {

    @Before
    fun setUp() {
        registerActionMetadata()
    }

    private fun step(names: String, values: String) =
        ActionSpec(type = "var.set_multiple", args = mapOf("names" to names, "values" to values))

    private fun chips(step: ActionSpec) =
        typedVariableOptions(listOf(step), eventVariables = emptyList()).map { it.name to it.reference }

    private fun flowOutputs(step: ActionSpec): List<String> {
        val profile = Profile(id = 1, name = "Sun", contexts = emptyList(), enterTaskId = 10)
        val task = Task(id = 10, name = "Copy", actions = listOf(step))
        return AutomationFlowGraphBuilder.build(profile, listOf(task)).nodes
            .single { it.kind == AutomationFlowNodeKind.ACTION }.outputs.map { it.name }
    }

    @Test
    fun theNamesGivenAValueAreChipsAndFlowOutputs() {
        val step = step("%Sunrise\n%Sunset", "%ss_sunrise\n%ss_sunset")
        assertEquals(listOf("Sunrise" to "{{ global.Sunrise }}", "Sunset" to "{{ global.Sunset }}"), chips(step))
        assertEquals(listOf("Sunrise", "Sunset"), flowOutputs(step))
    }

    @Test
    fun aNameOnlyUnsetOrBuiltFromAnotherVariableIsLeftOut() {
        val step = step("%first\n%item%n\n%last", "a,b")
        assertEquals(listOf("first"), chips(step).map { it.first })
        assertEquals(listOf("first"), flowOutputs(step))
    }

    @Test
    fun aWordOnANameLineOfAHiddenNamesFieldIsLeftOut() {
        // The editor hides this names field, since hunter2 may be a value typed on a name line.
        val step = step("%api_token\nhunter2", "a\nb")
        assertTrue(ActionArgumentSensitivity.isSensitive("var.set_multiple", "names", step.args))
        assertEquals(listOf("api_token"), chips(step).map { it.first })
        assertEquals(listOf("api_token"), flowOutputs(step))
    }

    @Test
    fun aBareNameStillCountsWhenNothingIsHidden() {
        val step = step("%first\nsecond", "a\nb")
        assertEquals(listOf("first", "second"), chips(step).map { it.first })
        assertEquals(listOf("first", "second"), flowOutputs(step))
    }
}
