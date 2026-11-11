package com.opentasker.core.engine

import android.content.ContextWrapper
import com.opentasker.core.actions.SetVariableAction
import com.opentasker.core.actions.TextReplaceAction
import com.opentasker.core.model.ActionSpec
import com.opentasker.core.model.Task
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * An output name written as `%Mode` names Mode, the way the editor's step chips read it. Expanded
 * like any other text, it named whatever Mode already held, so the second run of "Set %Mode to
 * Loud" wrote a variable called Quiet. Global names keep the values readable after the run.
 */
class OutputNameArgumentTest {

    @Before
    fun setUp() {
        ActionRegistry.register(SetVariableAction())
        ActionRegistry.register(TextReplaceAction())
    }

    private fun run(variables: VariableStore, vararg actions: ActionSpec): TaskRunReport = runBlocking {
        TaskRunner(ActionContext(ContextWrapper(null), variables)).run(Task(name = "T", actions = actions.toList()))
    }

    @Test
    fun aVariableThatAlreadyHasAValueIsTheOneThatGetsSet() {
        val variables = VariableStore().apply { set("Mode", "Quiet") }

        val report = run(variables, ActionSpec(type = "var.set", args = mapOf("name" to "%Mode", "value" to "Loud")))

        assertTrue(report.success)
        assertEquals("Loud", variables.get("Mode"))
        assertNull(variables.get("Quiet"))
    }

    @Test
    fun aReplaceWritesBackToTheVariableItRead() {
        val variables = VariableStore().apply { set("Html", "a<b") }

        run(
            variables,
            ActionSpec(
                type = "text.replace",
                args = mapOf("source" to "%Html", "pattern" to "<", "replacement" to "~lt~", "var" to "%Html"),
            ),
        )

        assertEquals("a~lt~b", variables.get("Html"))
    }

    @Test
    fun aNameBuiltFromPartsStillExpands() {
        val variables = VariableStore().apply { set("Slot", "Two") }

        run(variables, ActionSpec(type = "var.set", args = mapOf("name" to "Item_%Slot", "value" to "x")))

        assertEquals("x", variables.get("Item_Two"))
    }

    @Test
    fun preflightSetsTheSameVariableARunWould() {
        val task = Task(
            id = 1,
            name = "Twice",
            actions = listOf(
                ActionSpec(type = "var.set", args = mapOf("name" to "%Mode", "value" to "Quiet")),
                ActionSpec(type = "var.set", args = mapOf("name" to "%Mode", "value" to "Loud")),
                ActionSpec(type = "var.set", args = mapOf("name" to "Seen", "value" to "%Mode")),
            ),
        )

        val steps = PreflightRunner.preflightTask(task = task).tasks.single().steps

        assertEquals("%Mode", steps[1].expandedArguments["name"])
        assertEquals("Loud", steps[2].expandedArguments["value"])
    }
}
