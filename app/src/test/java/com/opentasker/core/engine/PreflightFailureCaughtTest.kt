package com.opentasker.core.engine

import android.content.ContextWrapper
import com.opentasker.core.actions.registerActionMetadata
import com.opentasker.core.model.ActionSpec
import com.opentasker.core.model.Task
import com.opentasker.core.registerCoreRuntime
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/** The preview sets %FLOW_ERROR_CAUGHT where a run does. */
class PreflightFailureCaughtTest {

    private val recorded = mutableListOf<String>()

    @Before
    fun setUp() {
        recorded.clear()
        registerActionMetadata()
        registerCoreRuntime()
        ActionRegistry.register(object : Action {
            override val id = "test.caught.record"
            override val category = ActionCategory.FLOW
            override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
                recorded += ctx.variables.get(TaskFailureVariables.CAUGHT) ?: "unset"
                return ActionResult.Success
            }
        })
    }

    private fun ctrl(type: String) = ActionSpec(type = type)

    private fun missing() = ActionSpec(type = SUB_TASK_ACTION_ID, args = mapOf("task" to "Missing"))

    /** What %FLOW_ERROR_CAUGHT reads after [actions], in a run and then in the preview. */
    private fun assertCaught(expected: String?, actions: List<ActionSpec>) {
        runBlocking {
            TaskRunner(ActionContext(ContextWrapper(null), VariableStore()), resolveTask = { null })
                .run(Task(id = 1, name = "T", actions = actions + ActionSpec(type = "test.caught.record")))
        }
        assertEquals("run", listOf(expected ?: "unset"), recorded)

        val read = ActionSpec(type = "var.set", args = mapOf("name" to "seen", "value" to "[%${TaskFailureVariables.CAUGHT}]"))
        val steps = PreflightRunner.preflightTask(Task(id = 1, name = "T", actions = actions + read)).tasks.single().steps
        assertEquals("preview", "[${expected ?: "%${TaskFailureVariables.CAUGHT}"}]", steps.last().expandedArguments["value"])
    }

    @Test
    fun aCaughtFailureReadsTrue() {
        assertCaught("true", listOf(ctrl(FlowControl.TRY), missing(), ctrl(FlowControl.CATCH), ctrl(FlowControl.ENDTRY)))
    }

    @Test
    fun aFailureAStepCarriesOnPastReadsFalse() {
        assertCaught("false", listOf(missing().copy(continueOnError = true)))
    }

    @Test
    fun aLaterCaughtFailureReadsTrue() {
        assertCaught(
            "true",
            listOf(missing().copy(continueOnError = true), ctrl(FlowControl.TRY), missing(), ctrl(FlowControl.CATCH), ctrl(FlowControl.ENDTRY)),
        )
    }

    @Test
    fun aTryThatGetsThroughLeavesItUnset() {
        assertCaught(null, listOf(ctrl(FlowControl.TRY), ctrl(FlowControl.CATCH), ctrl(FlowControl.ENDTRY)))
    }
}
