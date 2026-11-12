package com.opentasker.core.engine

import android.content.ContextWrapper
import com.opentasker.core.actions.registerActionMetadata
import com.opentasker.core.engine.variables.TaskerBuiltIns
import com.opentasker.core.model.ActionSpec
import com.opentasker.core.model.CollisionMode
import com.opentasker.core.model.Task
import com.opentasker.core.registerCoreRuntime
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * A sub-task shows in `%TRUN` from when its collision rule lets it run, not while it's still
 * waiting its turn, and it drops off however its run ends, cancelled or thrown out included.
 */
class TaskerSubTaskAdmissionTest {
    private val started = CompletableDeferred<Unit>()
    private val release = CompletableDeferred<Unit>()

    @Before
    fun setUp() {
        registerActionMetadata()
        registerCoreRuntime()
        TaskerBuiltIns.resetForTests()
        ActiveExecutionRegistry.reset()
        ActionRegistry.register(object : Action {
            override val id = "test.trun.gate"
            override val category = ActionCategory.FLOW
            override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
                started.complete(Unit)
                release.await()
                return ActionResult.Success
            }
        })
        ActionRegistry.register(object : Action {
            override val id = "test.trun.hang"
            override val category = ActionCategory.FLOW
            override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
                started.complete(Unit)
                awaitCancellation()
            }
        })
        ActionRegistry.register(object : Action {
            override val id = "test.trun.throw"
            override val category = ActionCategory.FLOW
            override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult =
                throw IllegalStateException("broken on purpose")
        })
    }

    @After
    fun tearDown() {
        TaskerBuiltIns.resetForTests()
        ActiveExecutionRegistry.reset()
    }

    private fun step(type: String) = ActionSpec(type = type)

    private fun runTask(task: String) = ActionSpec(type = SUB_TASK_ACTION_ID, args = mapOf("task" to task), continueOnError = true)

    private fun runner(store: VariableStore, coordinator: TaskCollisionCoordinator, vararg tasks: Task) = TaskRunner(
        ActionContext(ContextWrapper(null), store),
        resolveTask = { name -> tasks.firstOrNull { it.name == name } },
        collisionCoordinator = coordinator,
    )

    @Test
    fun aSubTaskWaitingItsTurnIsNotListed() = runBlocking {
        val coordinator = TaskCollisionCoordinator()
        val busy = Task(
            id = 2,
            name = "Busy",
            collisionMode = CollisionMode.WAIT,
            actions = listOf(step("test.trun.gate"), ActionSpec(type = "var.set", args = mapOf("name" to "SEEN", "value" to "%TRUN"))),
        )
        val caller = Task(id = 1, name = "Caller", actions = listOf(runTask("Busy")))
        val callerStore = VariableStore()

        val holder = async { coordinator.execute(busy) { runner(VariableStore(), coordinator, busy).run(busy) } }
        started.await()
        val queued = async { coordinator.execute(caller) { runner(callerStore, coordinator, busy, caller).run(caller) } }
        // Caller reaches its Run Task step and queues behind the run holding Busy.
        delay(300)

        assertNull(TaskerBuiltIns.value("TRUN"))

        release.complete(Unit)
        withTimeout(10_000) {
            holder.await()
            queued.await()
        }
        assertEquals(",Busy,", callerStore.get("SEEN"))
        assertNull(TaskerBuiltIns.value("TRUN"))
    }

    @Test
    fun aCancelledSubTaskDropsOff() = runBlocking {
        val coordinator = TaskCollisionCoordinator()
        val inner = Task(id = 3, name = "Inner", actions = listOf(step("test.trun.hang")))
        val outer = Task(id = 4, name = "Outer", actions = listOf(runTask("Inner")))

        val run = async { coordinator.execute(outer) { runner(VariableStore(), coordinator, inner, outer).run(outer) } }
        started.await()
        assertEquals(",Inner,", TaskerBuiltIns.value("TRUN"))

        run.cancel()
        withTimeout(10_000) { run.join() }
        assertNull(TaskerBuiltIns.value("TRUN"))
    }

    @Test
    fun aSubTaskThatThrowsDropsOff() = runBlocking {
        val coordinator = TaskCollisionCoordinator()
        val inner = Task(id = 5, name = "Thrower", actions = listOf(step("test.trun.throw")))
        val outer = Task(id = 6, name = "Outer", actions = listOf(runTask("Thrower")))

        runCatching { coordinator.execute(outer) { runner(VariableStore(), coordinator, inner, outer).run(outer) } }

        assertNull(TaskerBuiltIns.value("TRUN"))
    }
}
