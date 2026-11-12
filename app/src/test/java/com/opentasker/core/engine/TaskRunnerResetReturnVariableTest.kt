package com.opentasker.core.engine

import android.content.ContextWrapper
import com.opentasker.core.actions.SubTaskInput
import com.opentasker.core.actions.SubTaskInputIssue
import com.opentasker.core.actions.SubTaskInputs
import com.opentasker.core.actions.registerActionMetadata
import com.opentasker.core.model.ActionSpec
import com.opentasker.core.model.Task
import com.opentasker.core.registerCoreRuntime
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Run Task's switch that clears the return variable before the task runs, Tasker's Reset Return Variable. */
class TaskRunnerResetReturnVariableTest {

    private val recorded = mutableListOf<String>()

    @Before
    fun setUp() {
        recorded.clear()
        registerActionMetadata()
        registerCoreRuntime()
        ActionRegistry.register(object : Action {
            override val id = "test.resetreturn.record"
            override val category = ActionCategory.FLOW
            override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
                val name = args.getValue("name")
                recorded += "$name=${ctx.variables.get(name) ?: "unset"}"
                return ActionResult.Success
            }
        })
    }

    private fun record(name: String) = ActionSpec(type = "test.resetreturn.record", args = mapOf("name" to name))

    private fun stop(value: String? = null) = ActionSpec(type = FlowControl.STOP, args = value?.let { mapOf("value" to it) }.orEmpty())

    private fun set(name: String, value: String) = ActionSpec(type = "var.set", args = mapOf("name" to name, "value" to value))

    private fun runTask(task: String, vararg args: Pair<String, String>) =
        ActionSpec(type = SUB_TASK_ACTION_ID, args = mapOf("task" to task, *args))

    private fun run(caller: List<ActionSpec>, vararg tasks: Task, variables: VariableStore = VariableStore()): TaskRunReport =
        runBlocking {
            val byName = tasks.associateBy { it.name }
            TaskRunner(ActionContext(ContextWrapper(null), variables), resolveTask = { byName[it] })
                .run(Task(id = 1, name = "Caller", actions = caller))
        }

    private fun TaskRunReport.failures(): List<String> = results.filterIsInstance<ActionResult.Failure>().map { it.message }

    /** What a step after the call reads for %answer in the Preview, with the value as written when it's unset. */
    private fun previewed(call: ActionSpec, vararg tasks: Task): String? {
        val caller = Task(id = 1, name = "Caller", actions = listOf(set("answer", "old"), call, set("seen", "[%answer]")))
        val steps = PreflightRunner.preflightTask(caller, tasks = listOf(caller) + tasks).tasks.first { it.taskId == 1L }.steps
        return steps[2].expandedArguments["value"]
    }

    @Test
    fun theVariableIsClearedBeforeTheTaskRunsSoNothingReturnedLeavesItUnset() {
        val child = Task(id = 2, name = "Child", actions = listOf(record("answer"), stop()))
        val variables = VariableStore().apply { set("answer", "old") }
        val report = run(
            listOf(runTask("Child", SUB_TASK_RETURN_KEY to "%answer", SUB_TASK_RESET_RETURN_KEY to "true"), record("answer")),
            child,
            variables = variables,
        )

        assertTrue(report.failures().toString(), report.success)
        assertEquals(listOf("answer=unset", "answer=unset"), recorded)
    }

    @Test
    fun aReturnedValueStillLandsThere() {
        val child = Task(id = 2, name = "Child", actions = listOf(stop("new")))
        val variables = VariableStore().apply { set("answer", "old") }
        run(
            listOf(runTask("Child", SUB_TASK_RETURN_KEY to "answer", SUB_TASK_RESET_RETURN_KEY to "TRUE "), record("answer")),
            child,
            variables = variables,
        )

        assertEquals(listOf("answer=new"), recorded)
    }

    @Test
    fun turnedOffTheOldValueStays() {
        val child = Task(id = 2, name = "Child", actions = listOf(stop()))
        val variables = VariableStore().apply { set("answer", "old") }
        run(
            listOf(runTask("Child", SUB_TASK_RETURN_KEY to "%answer", SUB_TASK_RESET_RETURN_KEY to "false"), record("answer")),
            child,
            variables = variables,
        )

        assertEquals(listOf("answer=old"), recorded)
    }

    @Test
    fun aGlobalReturnVariableIsClearedToo() {
        val child = Task(id = 2, name = "Child", actions = listOf(stop()))
        val variables = VariableStore().apply { set("ANSWER", "old") }
        run(listOf(runTask("Child", SUB_TASK_RETURN_KEY to "%ANSWER", SUB_TASK_RESET_RETURN_KEY to "true")), child, variables = variables)

        assertNull(variables.get("ANSWER"))
    }

    @Test
    fun aTaskThatIsNotFoundClearsNothing() {
        val variables = VariableStore().apply { set("answer", "old") }
        val report = run(
            listOf(
                runTask("Missing", SUB_TASK_RETURN_KEY to "%answer", SUB_TASK_RESET_RETURN_KEY to "true").copy(continueOnError = true),
                record("answer"),
            ),
            variables = variables,
        )

        assertEquals(listOf("sub-task not found: Missing"), report.failures())
        assertEquals(listOf("answer=old"), recorded)
    }

    @Test
    fun theSwitchIsNotAnInputOfTheCalledTask() {
        val child = Task(id = 2, name = "Child", actions = listOf(record(SUB_TASK_RESET_RETURN_KEY), stop()))
        val variables = VariableStore()
        run(listOf(runTask("Child", SUB_TASK_RETURN_KEY to "%answer", SUB_TASK_RESET_RETURN_KEY to "true")), child, variables = variables)

        assertEquals(listOf("$SUB_TASK_RESET_RETURN_KEY=unset"), recorded)
        assertNull(variables.get(SUB_TASK_RESET_RETURN_KEY))
    }

    @Test
    fun thePreviewClearsItTheSameWay() {
        val returnsNothing = Task(id = 2, name = "Nothing", actions = listOf(stop()))
        val returnsNew = Task(id = 3, name = "New", actions = listOf(stop("new")))

        assertEquals("[%answer]", previewed(runTask("Nothing", SUB_TASK_RETURN_KEY to "%answer", SUB_TASK_RESET_RETURN_KEY to "true"), returnsNothing))
        assertEquals("[new]", previewed(runTask("New", SUB_TASK_RETURN_KEY to "%answer", SUB_TASK_RESET_RETURN_KEY to "true"), returnsNew))
        assertEquals("[old]", previewed(runTask("Nothing", SUB_TASK_RETURN_KEY to "%answer"), returnsNothing))
    }

    @Test
    fun theInputsEditorLeavesTheSwitchAlone() {
        val args = mapOf("task" to "Child", SUB_TASK_RESET_RETURN_KEY to "true", "city" to "Oslo")

        assertEquals(listOf(SubTaskInput("city", "Oslo")), SubTaskInputs.from(args, fieldKeys = emptySet()))
        assertEquals(
            mapOf(0 to SubTaskInputIssue.HoldsTheResetSwitch),
            SubTaskInputs.validate(listOf(SubTaskInput(SUB_TASK_RESET_RETURN_KEY, "false"))),
        )
        val saved = SubTaskInputs.applyTo(args, opened = emptyList(), edited = listOf(SubTaskInput(SUB_TASK_RESET_RETURN_KEY, "false")))
        assertEquals("true", saved[SUB_TASK_RESET_RETURN_KEY])
    }

    // The import notes for Tasker's Limit Passthrough To and Allow Overwrite Variables rest on this.
    @Test
    fun aCalledTaskReadsTheCallersLocalsButCantChangeThem() {
        val child = Task(id = 2, name = "Child", actions = listOf(record("city"), set("city", "Bergen"), record("city")))
        val report = run(listOf(set("city", "Oslo"), runTask("Child"), record("city")), child)

        assertTrue(report.failures().toString(), report.success)
        assertEquals(listOf("city=Oslo", "city=Bergen", "city=Oslo"), recorded)
    }
}
