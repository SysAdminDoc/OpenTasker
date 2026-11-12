package com.opentasker.core.engine

import android.content.ContextWrapper
import com.opentasker.core.actions.registerActionMetadata
import com.opentasker.core.model.ActionSpec
import com.opentasker.core.model.Task
import com.opentasker.core.registerCoreRuntime
import com.opentasker.core.transfer.TaskerXmlExporter
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Reset Return Variable clears the calling task's own variable only. In Tasker a called task's
 * locals are its own, so clearing one never touches the task that called it.
 */
class TaskRunnerResetReturnVariableScopeTest {

    private val recorded = mutableListOf<String>()

    @Before
    fun setUp() {
        recorded.clear()
        registerActionMetadata()
        registerCoreRuntime()
        ActionRegistry.register(object : Action {
            override val id = "test.resetscope.record"
            override val category = ActionCategory.FLOW
            override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
                val name = args.getValue("name")
                recorded += "${args["who"]}:$name=${ctx.variables.get(name) ?: "unset"}"
                return ActionResult.Success
            }
        })
    }

    private fun record(who: String, name: String = "answer") =
        ActionSpec(type = "test.resetscope.record", args = mapOf("who" to who, "name" to name))

    private fun stop(value: String? = null) = ActionSpec(type = FlowControl.STOP, args = value?.let { mapOf("value" to it) }.orEmpty())

    private fun set(name: String, value: String) = ActionSpec(type = "var.set", args = mapOf("name" to name, "value" to value))

    private fun runTask(task: String, vararg args: Pair<String, String>) =
        ActionSpec(type = SUB_TASK_ACTION_ID, args = mapOf("task" to task, *args))

    private fun resetting(task: String) = runTask(task, SUB_TASK_RETURN_KEY to "%answer", SUB_TASK_RESET_RETURN_KEY to "true")

    private fun run(vararg tasks: Task): TaskRunReport = runBlocking {
        val byName = tasks.associateBy { it.name }
        TaskRunner(ActionContext(ContextWrapper(null), VariableStore()), resolveTask = { byName[it] }).run(tasks.first())
    }

    @Test
    fun aCallersVariableOfTheSameNameIsLeftAlone() {
        run(
            Task(id = 1, name = "A", actions = listOf(set("answer", "old"), runTask("B"), record("A"))),
            Task(id = 2, name = "B", actions = listOf(resetting("C"), record("B"))),
            Task(id = 3, name = "C", actions = listOf(stop())),
        )

        assertEquals(listOf("B:answer=unset", "A:answer=old"), recorded)
    }

    @Test
    fun aValueReturnedThereStaysWithTheTaskThatAskedForIt() {
        run(
            Task(id = 1, name = "A", actions = listOf(set("answer", "old"), runTask("B"), record("A"))),
            Task(id = 2, name = "B", actions = listOf(resetting("C"), record("B"))),
            Task(id = 3, name = "C", actions = listOf(stop("new"))),
        )

        assertEquals(listOf("B:answer=new", "A:answer=old"), recorded)
    }

    @Test
    fun theTasksOwnValueGoesWithoutTheCallersShowingThrough() {
        run(
            Task(id = 1, name = "A", actions = listOf(set("answer", "old"), runTask("B"), record("A"))),
            Task(id = 2, name = "B", actions = listOf(set("answer", "mine"), resetting("C"), record("B"), set("answer", "again"), record("B"))),
            Task(id = 3, name = "C", actions = listOf(record("C"), stop())),
        )

        assertEquals(listOf("C:answer=unset", "B:answer=unset", "B:answer=again", "A:answer=old"), recorded)
    }

    @Test
    fun thePreviewLeavesTheCallersVariableAloneToo() {
        val a = Task(id = 1, name = "A", actions = listOf(set("answer", "old"), runTask("B"), set("seen", "[%answer]")))
        val b = Task(id = 2, name = "B", actions = listOf(resetting("C"), set("seen", "[%answer]")))
        val c = Task(id = 3, name = "C", actions = listOf(stop()))
        val reports = PreflightRunner.preflightTask(a, tasks = listOf(a, b, c)).tasks

        assertEquals("[%answer]", reports.first { it.taskId == 2L }.steps[1].expandedArguments["value"])
        assertEquals("[old]", reports.first { it.taskId == 1L }.steps[2].expandedArguments["value"])
    }

    @Test
    fun pastTheDepthLimitNeitherTheRunNorThePreviewClearsIt() {
        // T1 runs at depth 0, so T9 is the first task whose Run Task step the depth limit stops.
        val chain = (1..8).map { level -> Task(id = level.toLong(), name = "T$level", actions = listOf(runTask("T${level + 1}"))) }
        val deepest = Task(
            id = 9,
            name = "T9",
            actions = listOf(set("answer", "old"), resetting("T10").copy(continueOnError = true), record("T9"), set("seen", "[%answer]")),
        )
        val last = Task(id = 10, name = "T10", actions = listOf(stop()))
        val tasks = chain + deepest + last

        // Had T10 run, the switch would have cleared it and the step would read unset.
        run(*tasks.toTypedArray())
        assertEquals(listOf("T9:answer=old"), recorded)

        val previewed = PreflightRunner.preflightTask(tasks.first(), tasks = tasks).tasks.first { it.taskId == 9L }
        assertEquals("[old]", previewed.steps[3].expandedArguments["value"])
    }

    @Test
    fun theStoreHidesTheCallersValueOnlyUntilTheTaskEnds() {
        val store = VariableStore().apply { set("answer", "old", sensitive = true) }
        store.pushScope()
        store.pushScope(holdsInputs = true)
        store.pushScope()
        store.clearForTask("answer")

        assertNull(store.get("answer"))
        assertFalse(store.isSensitive("answer"))
        assertFalse("answer" in store.toTemplateScope().task)
        assertFalse(store.remove("answer"))
        store.set("answer", "mine")
        assertEquals("mine", store.get("answer"))

        store.popScope()
        store.popScope()
        assertEquals("old", store.get("answer"))
        assertTrue(store.isSensitive("answer"))
    }

    @Test
    fun aTaskNothingCalledClearsEveryScopeItHas() {
        val store = VariableStore().apply { set("answer", "root") }
        store.pushScope()
        store.set("answer", "inner")
        store.clearForTask("answer")

        assertNull(store.get("answer"))
        store.popScope()
        assertNull(store.get("answer"))
    }

    @Test
    fun anExportReadsTheSwitchTheWayTheRunDoes() {
        fun exported(value: String) = TaskerXmlExporter.export(
            emptyList(),
            listOf(
                Task(id = 1, name = "Exported", actions = listOf(runTask("Restore", SUB_TASK_RETURN_KEY to "restored", SUB_TASK_RESET_RETURN_KEY to value))),
                Task(id = 2, name = "Restore", actions = emptyList()),
            ),
        )

        val spaced = exported("TRUE ")
        assertTrue(spaced.xml, """<Int sr="arg8" val="1"/>""" in spaced.xml)
        assertTrue(spaced.warnings.toString(), spaced.warnings.isEmpty())

        val variable = exported("%flag")
        assertTrue(variable.xml, """<Int sr="arg8" val="0"/>""" in variable.xml)
        assertTrue(variable.warnings.toString(), variable.warnings.any { "Reset Return Variable" in it && "%flag" in it })
    }
}
