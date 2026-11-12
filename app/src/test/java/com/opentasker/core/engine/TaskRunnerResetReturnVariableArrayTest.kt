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

/**
 * Reset Return Variable on a name that is also an element of the caller's array. In Tasker the
 * called task's array is its own copy, so changing it after the reset leaves the caller's alone.
 */
class TaskRunnerResetReturnVariableArrayTest {

    private val recorded = mutableListOf<String>()

    @Before
    fun setUp() {
        recorded.clear()
        registerActionMetadata()
        registerCoreRuntime()
        ActionRegistry.register(object : Action {
            override val id = "test.resetarray.record"
            override val category = ActionCategory.FLOW
            override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
                val name = args.getValue("name")
                recorded += "${args["who"]}:$name=${ctx.variables.get(name) ?: "unset"}"
                return ActionResult.Success
            }
        })
    }

    private fun record(who: String, vararg names: String) =
        names.map { ActionSpec(type = "test.resetarray.record", args = mapOf("who" to who, "name" to it)) }

    private fun set(name: String, value: String) = ActionSpec(type = "var.set", args = mapOf("name" to name, "value" to value))

    private fun run(vararg tasks: Task): TaskRunReport = runBlocking {
        val byName = tasks.associateBy { it.name }
        TaskRunner(ActionContext(ContextWrapper(null), VariableStore()), resolveTask = { byName[it] }).run(tasks.first())
    }

    @Test
    fun anArrayChangedAfterTheResetIsTheTasksOwn() {
        val resetting = ActionSpec(
            type = SUB_TASK_ACTION_ID,
            args = mapOf("task" to "C", SUB_TASK_RETURN_KEY to "%item1", SUB_TASK_RESET_RETURN_KEY to "true"),
        )
        val push = ActionSpec(type = "array.push", args = mapOf("array" to "%item", "position" to "1", "value" to "x"))
        run(
            Task(
                id = 1,
                name = "A",
                actions = listOf(set("item1", "a"), set("item2", "b"), set("item3", "c"), ActionSpec(type = SUB_TASK_ACTION_ID, args = mapOf("task" to "B"))) +
                    record("A", "item1", "item2", "item3"),
            ),
            Task(id = 2, name = "B", actions = listOf(resetting, push) + record("B", "item1")),
            Task(id = 3, name = "C", actions = listOf(ActionSpec(type = FlowControl.STOP))),
        )

        assertEquals(listOf("B:item1=x", "A:item1=a", "A:item2=b", "A:item3=c"), recorded)
    }

    @Test
    fun theStoreKeepsTheCallersElementsWhenTheTaskWritesItsArray() {
        val store = VariableStore().apply {
            set("item1", "a")
            set("item2", "b")
        }
        store.pushScope()
        store.pushScope(holdsInputs = true)
        store.pushScope()
        store.clearForTask("item1")
        store.replaceNumberedItems("item", mapOf(1 to ("x" to false)))

        assertEquals(mapOf(1 to "x"), store.numberedItems("item"))
        store.popScope()
        store.popScope()
        assertEquals(mapOf(1 to "a", 2 to "b"), store.numberedItems("item"))
    }
}
