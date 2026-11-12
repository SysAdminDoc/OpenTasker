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

/** Stop's value handed back through Run Task's return variable, Tasker's Return and Perform Task. */
class TaskRunnerReturnValueTest {

    private val recorded = mutableListOf<String>()

    @Before
    fun setUp() {
        recorded.clear()
        registerActionMetadata()
        registerCoreRuntime()
        ActionRegistry.register(object : Action {
            override val id = "test.returnvalue.record"
            override val category = ActionCategory.FLOW
            override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
                val name = args.getValue("name")
                val value = ctx.variables.get(name)
                recorded += "$name=${value ?: "unset"}" + if (value != null && ctx.variables.isSensitive(name)) " (secret)" else ""
                return ActionResult.Success
            }
        })
    }

    private fun record(name: String) = ActionSpec(type = "test.returnvalue.record", args = mapOf("name" to name))

    private fun stop(value: String? = null) = ActionSpec(type = FlowControl.STOP, args = value?.let { mapOf("value" to it) }.orEmpty())

    private fun runTask(task: String, vararg args: Pair<String, String>) =
        ActionSpec(type = SUB_TASK_ACTION_ID, args = mapOf("task" to task, *args))

    private fun run(caller: List<ActionSpec>, vararg tasks: Task, variables: VariableStore = VariableStore()): TaskRunReport =
        runBlocking {
            val byName = tasks.associateBy { it.name }
            TaskRunner(ActionContext(ContextWrapper(null), variables), resolveTask = { byName[it] })
                .run(Task(id = 1, name = "Caller", actions = caller))
        }

    private fun TaskRunReport.failures(): List<String> = results.filterIsInstance<ActionResult.Failure>().map { it.message }

    @Test
    fun aReturnedValueLandsInTheCallersVariable() {
        val child = Task(id = 2, name = "Child", actions = listOf(stop("%par1 done"), record("never")))
        val report = run(
            listOf(runTask("Child", "par1" to "hello", SUB_TASK_RETURN_KEY to "%answer"), record("answer")),
            child,
        )

        assertTrue(report.failures().toString(), report.success)
        assertEquals(listOf("answer=hello done"), recorded)
    }

    @Test
    fun aTaskThatReturnsNothingLeavesTheVariableAlone() {
        val stops = Task(id = 2, name = "Stops", actions = listOf(stop()))
        val ends = Task(id = 3, name = "Ends", actions = listOf(record("par1")))
        val variables = VariableStore().apply { set("answer", "old") }
        val report = run(
            listOf(
                runTask("Stops", SUB_TASK_RETURN_KEY to "%answer"),
                record("answer"),
                runTask("Ends", SUB_TASK_RETURN_KEY to "answer"),
                record("answer"),
            ),
            stops,
            ends,
            variables = variables,
        )

        assertTrue(report.failures().toString(), report.success)
        assertEquals(listOf("answer=old", "par1=unset", "answer=old"), recorded)
    }

    @Test
    fun theReturnVariableIsNotAnInputOfTheCalledTask() {
        val child = Task(id = 2, name = "Child", actions = listOf(record(SUB_TASK_RETURN_KEY), stop("x")))
        val report = run(listOf(runTask("Child", SUB_TASK_RETURN_KEY to "%answer")), child)

        assertTrue(report.failures().toString(), report.success)
        assertEquals(listOf("$SUB_TASK_RETURN_KEY=unset"), recorded)
    }

    @Test
    fun aSecretValueStaysSecretInTheCaller() {
        val variables = VariableStore().apply { set("PIN", "1234", sensitive = true) }
        val child = Task(id = 2, name = "Child", actions = listOf(stop("%PIN")))
        val report = run(listOf(runTask("Child", SUB_TASK_RETURN_KEY to "%answer"), record("answer")), child, variables = variables)

        assertTrue(report.failures().toString(), report.success)
        assertEquals(listOf("answer=1234 (secret)"), recorded)
        assertFalse(report.traces.toString(), "1234" in report.traces.toString())
    }

    @Test
    fun onlyTheDirectCallerGetsTheValue() {
        val inner = Task(id = 3, name = "Inner", actions = listOf(stop("deep")))
        val middle = Task(
            id = 2,
            name = "Middle",
            actions = listOf(runTask("Inner", SUB_TASK_RETURN_KEY to "%mid"), stop("%mid up")),
        )
        val report = run(
            listOf(runTask("Middle", SUB_TASK_RETURN_KEY to "%top"), record("top"), record("mid")),
            middle,
            inner,
        )

        assertTrue(report.failures().toString(), report.success)
        assertEquals(listOf("top=deep up", "mid=unset"), recorded)
    }

    @Test
    fun aReturnVariableThatIsNotAVariableNameFailsBeforeTheTaskRuns() {
        val child = Task(id = 2, name = "Child", actions = listOf(record("ran"), stop("x")))
        val report = run(listOf(runTask("Child", SUB_TASK_RETURN_KEY to "1st place"), record("after")), child)

        assertFalse(report.success)
        assertTrue(report.failures().toString(), report.failures().single().contains("isn't a variable name"))
        assertEquals(emptyList<String>(), recorded)
    }

    @Test
    fun theStopTraceHidesASecretValueAndShortensALongOne() {
        val variables = VariableStore().apply { set("PIN", "1234", sensitive = true) }
        val secret = run(listOf(stop("%PIN")), variables = variables).traces.single()
        assertTrue(secret.message, secret.message.startsWith("stop, returning"))
        assertFalse(secret.toString(), "1234" in secret.toString())

        val long = "a".repeat(300)
        val shortened = run(listOf(stop(long))).traces.single()
        assertTrue(shortened.message, shortened.message.startsWith("stop, returning a"))
        assertFalse(shortened.message, long in shortened.message)
    }

    @Test
    fun aReturnVariableThatComesOutEmptyFailsBeforeTheTaskRuns() {
        val child = Task(id = 2, name = "Child", actions = listOf(record("ran"), stop("x")))
        val report = run(listOf(runTask("Child", SUB_TASK_RETURN_KEY to "{{ target }}"), record("after")), child)

        assertFalse(report.success)
        assertTrue(report.failures().toString(), report.failures().single().contains("came out empty"))
        assertEquals(emptyList<String>(), recorded)
    }

    @Test
    fun aFailureATryCatchesStillLetsTheValueComeBack() {
        val child = Task(
            id = 2,
            name = "Child",
            actions = listOf(
                ActionSpec(type = FlowControl.TRY),
                runTask("Missing"),
                ActionSpec(type = FlowControl.CATCH),
                ActionSpec(type = FlowControl.ENDTRY),
                stop("x"),
            ),
        )
        val report = run(listOf(runTask("Child", SUB_TASK_RETURN_KEY to "%answer"), record("answer")), child)

        assertTrue(report.failures().toString(), report.success)
        assertEquals(listOf("answer=x"), recorded)
    }

    @Test
    fun aValueOnStopInTheTopTaskJustStopsIt() {
        val report = run(listOf(stop("ignored"), record("never")))

        assertTrue(report.failures().toString(), report.success)
        assertEquals(emptyList<String>(), recorded)
    }

    @Test
    fun thePreviewHandsTheValueBackToo() {
        val child = Task(id = 2, name = "Child", actions = listOf(stop("%par1!")))
        val caller = Task(
            id = 1,
            name = "Caller",
            actions = listOf(
                runTask("Child", "par1" to "hello", SUB_TASK_RETURN_KEY to "%answer"),
                ActionSpec(type = "var.set", args = mapOf("name" to "seen", "value" to "%answer")),
            ),
        )

        val report = PreflightRunner.preflightTask(caller, tasks = listOf(caller, child))

        assertTrue(report.canPreflight)
        val callerSteps = report.tasks.first { it.taskId == 1L }.steps
        assertEquals("hello!", callerSteps[1].expandedArguments["value"])
    }

    @Test
    fun thePreviewHandsNothingBackWhenTheRunWouldFailTheCalledTask() {
        val child = Task(id = 2, name = "Child", actions = listOf(runTask("Missing"), stop("x")))
        val caller = Task(
            id = 1,
            name = "Caller",
            actions = listOf(
                runTask("Child", SUB_TASK_RETURN_KEY to "%answer"),
                ActionSpec(type = "var.set", args = mapOf("name" to "seen", "value" to "[%answer]")),
            ),
        )

        val callerSteps = PreflightRunner.preflightTask(caller, tasks = listOf(caller, child)).tasks.first { it.taskId == 1L }.steps

        assertFalse(callerSteps[1].expandedArguments.toString(), callerSteps[1].expandedArguments["value"] == "[x]")
    }

    @Test
    fun thePreviewOnlyWarnsAboutAReturnVariableThatIsEmptyThere() {
        // An earlier step's output could fill it in during a run, and the preview doesn't play those.
        val child = Task(id = 2, name = "Child", actions = listOf(stop("x")))
        val caller = Task(id = 1, name = "Caller", actions = listOf(runTask("Child", SUB_TASK_RETURN_KEY to "{{ target }}")))

        val report = PreflightRunner.preflightTask(caller, tasks = listOf(caller, child))
        val step = report.tasks.first { it.taskId == 1L }.steps.single()

        assertEquals(PreflightStepStatus.SIMULATED, step.status)
        assertTrue(step.warnings.toString(), step.warnings.any { "{{ target }}" in it && "is empty here" in it })
        assertTrue(report.tasks.toString(), report.tasks.any { it.taskId == 2L })
    }

    @Test
    fun thePreviewBlocksAReturnVariableThatIsNoName() {
        val child = Task(id = 2, name = "Child", actions = listOf(stop("x")))
        val caller = Task(id = 1, name = "Caller", actions = listOf(runTask("Child", SUB_TASK_RETURN_KEY to "1st place")))

        val step = PreflightRunner.preflightTask(caller, tasks = listOf(caller, child)).tasks.first { it.taskId == 1L }.steps.single()

        assertEquals(PreflightStepStatus.BLOCKED, step.status)
        assertTrue(step.warnings.toString(), step.warnings.any { "isn't a variable name" in it })
    }

    @Test
    fun thePreviewHandsNothingBackWhenAFailureDeeperDownFailsTheCalledTask() {
        val grand = Task(id = 3, name = "Grand", actions = listOf(runTask("Missing")))
        val child = Task(id = 2, name = "Child", actions = listOf(runTask("Grand"), stop("x")))
        val caller = Task(
            id = 1,
            name = "Caller",
            actions = listOf(
                runTask("Child", SUB_TASK_RETURN_KEY to "%answer"),
                ActionSpec(type = "var.set", args = mapOf("name" to "seen", "value" to "[%answer]")),
            ),
        )

        val callerSteps = PreflightRunner.preflightTask(caller, tasks = listOf(caller, child, grand)).tasks.first { it.taskId == 1L }.steps

        assertFalse(callerSteps[1].expandedArguments.toString(), callerSteps[1].expandedArguments["value"] == "[x]")
    }

    @Test
    fun thePreviewHandsTheValueBackWhenATryCatchesTheFailure() {
        val child = Task(
            id = 2,
            name = "Child",
            actions = listOf(
                ActionSpec(type = FlowControl.TRY),
                runTask("Missing"),
                ActionSpec(type = FlowControl.CATCH),
                ActionSpec(type = FlowControl.ENDTRY),
                stop("x"),
            ),
        )
        val caller = Task(
            id = 1,
            name = "Caller",
            actions = listOf(
                runTask("Child", SUB_TASK_RETURN_KEY to "%answer"),
                ActionSpec(type = "var.set", args = mapOf("name" to "seen", "value" to "[%answer]")),
            ),
        )

        val callerSteps = PreflightRunner.preflightTask(caller, tasks = listOf(caller, child)).tasks.first { it.taskId == 1L }.steps

        assertEquals("[x]", callerSteps[1].expandedArguments["value"])
    }

    @Test
    fun aSecondCallStoresInTheSameVariable() {
        val first = Task(id = 2, name = "First", actions = listOf(stop("one")))
        val second = Task(id = 3, name = "Second", actions = listOf(stop("two")))
        val report = run(
            listOf(
                runTask("First", SUB_TASK_RETURN_KEY to "%answer"),
                runTask("Second", SUB_TASK_RETURN_KEY to "%answer"),
                record("answer"),
                record("one"),
            ),
            first,
            second,
        )

        assertTrue(report.failures().toString(), report.success)
        assertEquals(listOf("answer=two", "one=unset"), recorded)
    }

    @Test
    fun aFailureTheCalledTaskCarriesOnPastStillSendsNothingBack() {
        val child = Task(id = 2, name = "Child", actions = listOf(runTask("Missing").copy(continueOnError = true), stop("x")))
        val report = run(
            listOf(runTask("Child", SUB_TASK_RETURN_KEY to "%answer").copy(continueOnError = true), record("answer")),
            child,
        )

        assertFalse(report.success)
        assertEquals(listOf("answer=unset"), recorded)
    }

    @Test
    fun thePreviewHandsNothingBackWhenTheCalledTaskCarriesOnPastAFailure() {
        val child = Task(id = 2, name = "Child", actions = listOf(runTask("Missing").copy(continueOnError = true), stop("x")))
        val caller = Task(
            id = 1,
            name = "Caller",
            actions = listOf(
                runTask("Child", SUB_TASK_RETURN_KEY to "%answer"),
                ActionSpec(type = "var.set", args = mapOf("name" to "seen", "value" to "[%answer]")),
            ),
        )

        val callerSteps = PreflightRunner.preflightTask(caller, tasks = listOf(caller, child)).tasks.first { it.taskId == 1L }.steps

        assertFalse(callerSteps[1].expandedArguments.toString(), callerSteps[1].expandedArguments["value"] == "[x]")
    }

    @Test
    fun aFailedGotoDeeperDownSendsNothingBack() {
        val grand = Task(id = 3, name = "Grand", actions = listOf(ActionSpec(type = FlowControl.GOTO, args = mapOf("to" to FlowControl.GOTO_LOOP_TOP))))
        val child = Task(id = 2, name = "Child", actions = listOf(runTask("Grand"), stop("x")))
        val report = run(
            listOf(runTask("Child", SUB_TASK_RETURN_KEY to "%answer").copy(continueOnError = true), record("answer")),
            child,
            grand,
        )

        assertFalse(report.success)
        assertEquals(listOf("answer=unset"), recorded)

        val caller = Task(
            id = 1,
            name = "Caller",
            actions = listOf(
                runTask("Child", SUB_TASK_RETURN_KEY to "%answer"),
                ActionSpec(type = "var.set", args = mapOf("name" to "seen", "value" to "[%answer]")),
            ),
        )
        val preview = PreflightRunner.preflightTask(caller, tasks = listOf(caller, child, grand))
        val gotoStep = preview.tasks.first { it.taskId == 3L }.steps.single()
        assertEquals(PreflightStepStatus.BLOCKED, gotoStep.status)
        val callerSteps = preview.tasks.first { it.taskId == 1L }.steps
        assertFalse(callerSteps[1].expandedArguments.toString(), callerSteps[1].expandedArguments["value"] == "[x]")
    }

    @Test
    fun thePreviewOnlyWarnsAboutAReturnVariableWithAPartThatIsEmptyThere() {
        // A step output could fill in the empty part during a run, and the preview doesn't play those.
        val child = Task(id = 2, name = "Child", actions = listOf(stop("x")))
        val caller = Task(id = 1, name = "Caller", actions = listOf(runTask("Child", SUB_TASK_RETURN_KEY to "{{ prefix }}_result")))

        val report = PreflightRunner.preflightTask(caller, tasks = listOf(caller, child))
        val step = report.tasks.first { it.taskId == 1L }.steps.single()

        assertEquals(PreflightStepStatus.SIMULATED, step.status)
        assertTrue(step.warnings.toString(), step.warnings.any { "{{ prefix }}_result" in it && "has an empty part here" in it })
        assertTrue(report.tasks.toString(), report.tasks.any { it.taskId == 2L })
    }
}
