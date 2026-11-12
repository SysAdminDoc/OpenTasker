package com.opentasker.core.engine

import android.content.ContextWrapper
import com.opentasker.core.actions.registerActionMetadata
import com.opentasker.core.model.ActionSpec
import com.opentasker.core.model.Task
import com.opentasker.core.registerCoreRuntime
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** A failure nothing catches leaves its Try open for a step that carries on past it. */
class TaskRunnerTryWithoutCatchTest {

    private val recorded = mutableListOf<String>()

    @Before
    fun setUp() {
        recorded.clear()
        registerActionMetadata()
        registerCoreRuntime()
        ActionRegistry.register(object : Action {
            override val id = "test.trynocatch.record"
            override val category = ActionCategory.FLOW
            override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
                recorded += args.getValue("name")
                return ActionResult.Success
            }
        })
    }

    private fun ctrl(type: String) = ActionSpec(type = type)

    private fun record(name: String) = ActionSpec(type = "test.trynocatch.record", args = mapOf("name" to name))

    private fun missing() = ActionSpec(type = SUB_TASK_ACTION_ID, args = mapOf("task" to "Missing"))

    private fun run(vararg actions: ActionSpec): TaskRunReport = runBlocking {
        TaskRunner(ActionContext(ContextWrapper(null), VariableStore()), resolveTask = { null })
            .run(Task(id = 1, name = "T", actions = actions.toList()))
    }

    @Test
    fun theEndTryIsReachedAfterAStepCarriesOnPastAFailure() {
        val report = run(ctrl(FlowControl.TRY), missing().copy(continueOnError = true), ctrl(FlowControl.ENDTRY), record("after"))

        assertEquals(listOf("after"), recorded)
        assertEquals(ActionTraceStatus.SUCCESS, report.traces[2].status)
        assertFalse(report.success)
    }

    @Test
    fun aHandlerStepThatCarriesOnLeavesBothTriesOpen() {
        val report = run(
            ctrl(FlowControl.TRY),
            ctrl(FlowControl.TRY),
            missing(),
            ctrl(FlowControl.CATCH),
            missing().copy(continueOnError = true),
            ctrl(FlowControl.ENDTRY),
            record("inner done"),
            ctrl(FlowControl.ENDTRY),
            record("after"),
        )

        assertEquals(listOf("inner done", "after"), recorded)
        assertFalse(report.success)
    }

    @Test
    fun anOuterCatchStillTakesAFailureFromAnInnerTryWithoutOne() {
        val report = run(
            ctrl(FlowControl.TRY),
            ctrl(FlowControl.TRY),
            missing().copy(continueOnError = true),
            ctrl(FlowControl.ENDTRY),
            record("rest"),
            ctrl(FlowControl.CATCH),
            record("handled"),
            ctrl(FlowControl.ENDTRY),
            record("after"),
        )

        assertEquals(listOf("handled", "after"), recorded)
        assertTrue(report.success)
    }
}
