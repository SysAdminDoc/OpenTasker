package com.opentasker.core.engine

import android.content.ContextWrapper
import com.opentasker.core.actions.registerActionMetadata
import com.opentasker.core.engine.variables.TaskerBuiltIns
import com.opentasker.core.model.ActionSpec
import com.opentasker.core.model.Task
import com.opentasker.core.registerCoreRuntime
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * Tasker's `%TRUN` lists a task another task runs (decompiled `ExecuteService.M4` walks every task
 * in its queue), so a sub-task shows there while it's going and drops off when it ends.
 */
class TaskerSubTaskRunningListTest {
    @Before
    fun setUp() {
        registerActionMetadata()
        registerCoreRuntime()
        TaskerBuiltIns.resetForTests()
        ActiveExecutionRegistry.reset()
    }

    @After
    fun tearDown() {
        TaskerBuiltIns.resetForTests()
        ActiveExecutionRegistry.reset()
    }

    private fun set(name: String, value: String) = ActionSpec(type = "var.set", args = mapOf("name" to name, "value" to value))

    private fun runTask(task: String) = ActionSpec(type = SUB_TASK_ACTION_ID, args = mapOf("task" to task))

    private fun run(vararg tasks: Task): VariableStore = VariableStore().also { store ->
        val byName = tasks.associateBy { it.name }
        runBlocking { TaskRunner(ActionContext(ContextWrapper(null), store), resolveTask = { byName[it] }).run(tasks.first()) }
    }

    @Test
    fun aSubTaskIsListedWhileItRuns() {
        val store = run(
            Task(id = 1, name = "Outer", actions = listOf(runTask("Middle"), set("AFTER", "[%TRUN]"))),
            Task(id = 2, name = "Middle", actions = listOf(runTask("Inner"))),
            Task(id = 3, name = "Inner", actions = listOf(set("SEEN", "%TRUN"))),
        )

        assertEquals(",Middle,Inner,", store.get("SEEN"))
        assertEquals("[%TRUN]", store.get("AFTER"))
        assertNull(TaskerBuiltIns.value("TRUN"))
    }

    @Test
    fun aSubTaskThatFailsStillDropsOff() {
        run(
            Task(id = 1, name = "Outer", actions = listOf(runTask("Broken").copy(continueOnError = true))),
            Task(id = 2, name = "Broken", actions = listOf(ActionSpec(type = "no.such.action"))),
        )

        assertNull(TaskerBuiltIns.value("TRUN"))
    }
}
