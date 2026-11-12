package com.opentasker.core.engine

import android.content.ContextWrapper
import com.opentasker.core.actions.registerActionMetadata
import com.opentasker.core.model.ActionSpec
import com.opentasker.core.model.Task
import com.opentasker.core.registerCoreRuntime
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Join Text's array field: `{{ which }}` still picks the array by another variable's value now that
 * `%which` names the array called which, and the preview shows `%parts` as written, like a run.
 */
class TextJoinArrayFieldTest {
    private val recorded = mutableListOf<String>()

    @Before
    fun setUp() {
        registerActionMetadata()
        registerCoreRuntime()
        ActionRegistry.register(object : Action {
            override val id = "test.textjoinfield.record"
            override val category = ActionCategory.FLOW
            override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
                recorded += args.getValue("v")
                return ActionResult.Success
            }
        })
    }

    private val split = ActionSpec(type = "text.split", args = mapOf("source" to "a,b,c", "delimiter" to ",", "var" to "%parts"))

    private fun join(array: String) =
        ActionSpec(type = "text.join", args = mapOf("array" to array, "delimiter" to "|", "var" to "%joined"))

    @Test
    fun aTemplateStillPicksTheArrayByAnotherVariable() {
        val actions = listOf(
            split,
            ActionSpec(type = "var.set", args = mapOf("name" to "%which", "value" to "parts")),
            join("{{ which }}"),
            ActionSpec(type = "test.textjoinfield.record", args = mapOf("v" to "%joined")),
        )

        val report = runBlocking { TaskRunner(ActionContext(ContextWrapper(null), VariableStore())).run(Task(id = 1, name = "Join", actions = actions)) }

        assertTrue(report.results.toString(), report.success)
        assertEquals(listOf("a|b|c"), recorded)
    }

    @Test
    fun thePreviewKeepsThePercentNameAsWritten() {
        // The preview sets variables but doesn't split, so %parts has a value it would fill in.
        val setParts = ActionSpec(type = "var.set", args = mapOf("name" to "%parts", "value" to "a b"))
        val steps = PreflightRunner.preflightTask(task = Task(id = 1, name = "Join", actions = listOf(setParts, join("%parts")))).tasks.single().steps

        assertEquals("%parts", steps[1].expandedArguments["array"])
    }
}
