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

/** The preview follows a Try's Catch the way a run does. */
class PreflightTryCatchTest {

    private val recorded = mutableListOf<String>()

    @Before
    fun setUp() {
        recorded.clear()
        registerActionMetadata()
        registerCoreRuntime()
        ActionRegistry.register(object : Action {
            override val id = "test.trycatch.record"
            override val category = ActionCategory.FLOW
            override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
                val name = args.getValue("name")
                recorded += "$name=${ctx.variables.get(name) ?: "unset"}"
                return ActionResult.Success
            }
        })
    }

    private fun ctrl(type: String) = ActionSpec(type = type)

    private fun set(name: String, value: String = "1") = ActionSpec(type = "var.set", args = mapOf("name" to name, "value" to value))

    private fun stop(value: String) = ActionSpec(type = FlowControl.STOP, args = mapOf("value" to value))

    private fun runTask(task: String, vararg args: Pair<String, String>) =
        ActionSpec(type = SUB_TASK_ACTION_ID, args = mapOf("task" to task, *args))

    private fun missing() = runTask("Missing")

    /** What a Run Task step gets back from a task made of [child], in a run and then in the preview. */
    private fun assertReturned(expected: String?, child: List<ActionSpec>, vararg others: Task) {
        val called = Task(id = 2, name = "Child", actions = child)
        val tasks = listOf(called) + others
        runBlocking {
            TaskRunner(ActionContext(ContextWrapper(null), VariableStore()), resolveTask = { name -> tasks.firstOrNull { it.name == name } })
                .run(
                    Task(
                        id = 1,
                        name = "Caller",
                        actions = listOf(
                            runTask("Child", SUB_TASK_RETURN_KEY to "%answer").copy(continueOnError = true),
                            ActionSpec(type = "test.trycatch.record", args = mapOf("name" to "answer")),
                        ),
                    ),
                )
        }
        assertEquals("run", listOf("answer=${expected ?: "unset"}"), recorded)

        val caller = Task(id = 1, name = "Caller", actions = listOf(runTask("Child", SUB_TASK_RETURN_KEY to "%answer"), set("seen", "[%answer]")))
        val steps = PreflightRunner.preflightTask(caller, tasks = listOf(caller) + tasks).tasks.first { it.taskId == 1L }.steps
        assertEquals("preview", "[${expected ?: "%answer"}]", steps[1].expandedArguments["value"])
    }

    @Test
    fun theHandlerIsSkippedWhenNothingInTheTryFails() {
        val steps = PreflightRunner.preflightTask(
            Task(
                id = 1,
                name = "T",
                actions = listOf(ctrl(FlowControl.TRY), set("a"), ctrl(FlowControl.CATCH), set("b"), ctrl(FlowControl.ENDTRY), set("c")),
            ),
        ).tasks.single().steps

        assertEquals("catch skipped; try succeeded", steps[2].branchDecision)
        assertEquals(PreflightStepStatus.SKIPPED, steps[3].status)
        assertEquals("not reached by selected branch", steps[3].branchDecision)
        assertEquals(PreflightStepStatus.SIMULATED, steps[5].status)
    }

    @Test
    fun aStepThatWouldFailGoesStraightToTheHandler() {
        val steps = PreflightRunner.preflightTask(
            Task(
                id = 1,
                name = "T",
                actions = listOf(ctrl(FlowControl.TRY), missing(), set("rest"), ctrl(FlowControl.CATCH), set("handled"), ctrl(FlowControl.ENDTRY)),
            ),
        ).tasks.single().steps

        assertEquals(PreflightStepStatus.BLOCKED, steps[1].status)
        assertEquals(PreflightStepStatus.SKIPPED, steps[2].status)
        assertEquals("catch", steps[3].branchDecision)
        assertEquals(PreflightStepStatus.SIMULATED, steps[4].status)
    }

    @Test
    fun theHandlerDoesNotRunWhenTheTrySucceeds() {
        assertReturned("ok", listOf(ctrl(FlowControl.TRY), set("a"), ctrl(FlowControl.CATCH), stop("err"), ctrl(FlowControl.ENDTRY), stop("ok")))
    }

    @Test
    fun theHandlerRunsInsteadOfTheRestOfTheTry() {
        assertReturned("x", listOf(ctrl(FlowControl.TRY), missing(), stop("y"), ctrl(FlowControl.CATCH), stop("x"), ctrl(FlowControl.ENDTRY)))
    }

    @Test
    fun aFailureInTheHandlerIsNotCaughtByItsOwnTry() {
        assertReturned(
            null,
            listOf(ctrl(FlowControl.TRY), missing(), ctrl(FlowControl.CATCH), runTask("Missing2"), ctrl(FlowControl.ENDTRY), stop("x")),
        )
    }

    @Test
    fun aTryWithoutACatchDoesNotCatch() {
        assertReturned(null, listOf(ctrl(FlowControl.TRY), missing(), ctrl(FlowControl.ENDTRY), stop("x")))
    }

    @Test
    fun anOuterCatchCatchesAFailureInAnInnerTryWithoutOne() {
        assertReturned(
            "x",
            listOf(
                ctrl(FlowControl.TRY),
                ctrl(FlowControl.TRY),
                missing(),
                ctrl(FlowControl.ENDTRY),
                stop("y"),
                ctrl(FlowControl.CATCH),
                stop("x"),
                ctrl(FlowControl.ENDTRY),
            ),
        )
    }

    @Test
    fun aStepThatFailedOnAnEarlierPassOfALoopIsNotCaughtAgain() {
        assertReturned(
            "Missing",
            listOf(
                ActionSpec(type = FlowControl.FOREACH, args = mapOf("var" to "i", "values" to "Missing, Other")),
                ctrl(FlowControl.TRY),
                runTask("%i"),
                ctrl(FlowControl.CATCH),
                set("last", "%i"),
                ctrl(FlowControl.ENDTRY),
                ctrl(FlowControl.ENDFOR),
                stop("%last"),
            ),
            Task(id = 3, name = "Other", actions = emptyList()),
        )
    }
}
