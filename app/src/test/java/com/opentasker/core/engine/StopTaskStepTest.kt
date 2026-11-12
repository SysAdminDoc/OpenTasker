package com.opentasker.core.engine

import android.content.ContextWrapper
import com.opentasker.core.model.ActionSpec
import com.opentasker.core.model.CollisionMode
import com.opentasker.core.model.Task
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tasker's Stop with a task named stops that task, wherever it's running. `task.stop` does the same
 * through the collision coordinator, which tracks every running copy of a task, sub-task runs
 * included.
 */
class StopTaskStepTest {

    private val blockStarted = CompletableDeferred<Unit>()

    @Before
    fun setUp() {
        ActionRegistry.register(object : Action {
            override val id = "test.stoptask.record"
            override val category = ActionCategory.FLOW
            override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
                ctx.variables.set(args.getValue("key"), "true")
                return ActionResult.Success
            }
        })
        ActionRegistry.register(object : Action {
            override val id = "test.stoptask.block"
            override val category = ActionCategory.FLOW
            override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
                blockStarted.complete(Unit)
                awaitCancellation()
            }
        })
    }

    private fun record(key: String) = ActionSpec(type = "test.stoptask.record", args = mapOf("key" to key))

    private fun stop(name: String) = ActionSpec(type = STOP_TASK_ACTION_ID, args = mapOf("task" to name))

    private fun runner(
        variables: VariableStore,
        coordinator: TaskCollisionCoordinator?,
        tasks: List<Task> = emptyList(),
        logs: MutableList<String> = mutableListOf(),
    ) = TaskRunner(
        ActionContext(ContextWrapper(null), variables, logger = { logs += it }),
        resolveTask = { ref -> tasks.firstOrNull { it.name == ref || it.id.toString() == ref } },
        collisionCoordinator = coordinator,
    )

    @Test
    fun stopsAnotherTaskThatIsRunning() = runBlocking {
        val coordinator = TaskCollisionCoordinator()
        val background = Task(id = 7, name = "Background", actions = listOf(ActionSpec(type = "test.stoptask.block")))
        val running = async {
            coordinator.execute(background) { runner(VariableStore(), coordinator).run(background) }
        }
        blockStarted.await()

        val logs = mutableListOf<String>()
        val report = runner(VariableStore(), coordinator, listOf(background), logs)
            .run(Task(id = 8, name = "Stopper", actions = listOf(stop("Background"))))

        assertTrue(report.success)
        assertEquals(listOf("Stopped 'Background'"), logs.filter { "Background" in it })
        val stopped = runCatching { withTimeout(5_000) { running.await() } }.exceptionOrNull()
        assertTrue("the running task must be cancelled, got $stopped", stopped is CancellationException)
        assertEquals("Stopped by a Stop Task step in 'Stopper'", stopped?.message)
    }

    @Test
    fun namingItsOwnTaskEndsTheRunLikeStop() = runBlocking {
        val self = Task(id = 5, name = "Self", actions = listOf(record("BEFORE"), stop("Self"), record("AFTER")))
        val variables = VariableStore()

        val report = runner(variables, TaskCollisionCoordinator(), tasks = listOf(self)).run(self)

        assertTrue(report.success)
        assertEquals("true", variables.get("BEFORE"))
        assertNull("steps after the Stop Task must not run", variables.get("AFTER"))
        assertEquals(2, report.results.size)
    }

    @Test
    fun aTaskThatIsNotRunningIsNotAnError() = runBlocking {
        val idle = Task(id = 7, name = "Idle")
        val logs = mutableListOf<String>()
        val variables = VariableStore()

        val report = runner(variables, TaskCollisionCoordinator(), listOf(idle), logs)
            .run(Task(id = 8, name = "Stopper", actions = listOf(stop("Idle"), record("AFTER"))))

        assertTrue(report.success)
        assertEquals("true", variables.get("AFTER"))
        assertTrue(logs.toString(), "'Idle' wasn't running" in logs)
    }

    @Test
    fun aTaskThatDoesNotExistFailsTheStep() = runBlocking {
        val report = runner(VariableStore(), TaskCollisionCoordinator())
            .run(Task(id = 8, name = "Stopper", actions = listOf(stop("Nope"))))

        assertFalse(report.success)
        assertEquals("task not found: Nope", (report.results.single() as ActionResult.Failure).message)
    }

    @Test
    fun withoutAResolverTheStepFails() = runBlocking {
        val report = TaskRunner(ActionContext(ContextWrapper(null), VariableStore()))
            .run(Task(id = 8, name = "Stopper", actions = listOf(stop("Anything"))))

        assertFalse(report.success)
        assertTrue(report.results.single() is ActionResult.Failure)
    }

    @Test
    fun theTaskNameCanComeFromAVariable() = runBlocking {
        val self = Task(id = 5, name = "Self", actions = listOf(stop("%target"), record("AFTER")))
        val variables = VariableStore().apply { set("target", "Self") }

        val report = runner(variables, TaskCollisionCoordinator(), tasks = listOf(self)).run(self)

        assertTrue(report.success)
        assertNull(variables.get("AFTER"))
    }

    @Test
    fun stoppingTheTaskThatCalledThisOneStopsBoth() = runBlocking {
        val coordinator = TaskCollisionCoordinator()
        val child = Task(id = 2, name = "Child", actions = listOf(stop("Parent"), record("CHILD_AFTER")))
        val parent = Task(
            id = 1,
            name = "Parent",
            actions = listOf(ActionSpec(type = SUB_TASK_ACTION_ID, args = mapOf("task" to "Child")), record("PARENT_AFTER")),
        )
        val variables = VariableStore()
        val logs = mutableListOf<String>()

        val caller = async {
            coordinator.execute(parent) { runner(variables, coordinator, tasks = listOf(parent, child), logs = logs).run(parent) }
        }
        val outcome = runCatching { withTimeout(5_000) { caller.await() } }.exceptionOrNull()

        assertTrue("the caller must be cancelled, got $outcome", caller.isCancelled)
        assertEquals("Stopped by a Stop Task step in 'Child'", outcome?.message)
        assertNull(variables.get("CHILD_AFTER"))
        assertNull(variables.get("PARENT_AFTER"))
        assertTrue("the log says what was stopped, got $logs", "Stopped 'Parent'" in logs)
    }

    @Test
    fun stoppingASubTaskFailsTheStepThatRanIt() = runBlocking {
        val coordinator = TaskCollisionCoordinator()
        val child = Task(id = 2, name = "Child", actions = listOf(ActionSpec(type = "test.stoptask.block")))
        val parent = Task(
            id = 1,
            name = "Parent",
            actions = listOf(
                ActionSpec(type = SUB_TASK_ACTION_ID, args = mapOf("task" to "Child"), continueOnError = true),
                record("PARENT_AFTER"),
            ),
        )
        val variables = VariableStore()
        val caller = async {
            coordinator.execute(parent) { runner(variables, coordinator, tasks = listOf(parent, child)).run(parent) }
        }
        blockStarted.await()

        val stopper = runner(VariableStore(), coordinator, tasks = listOf(child))
            .run(Task(id = 3, name = "Stopper", actions = listOf(stop("Child"))))
        val parentOutcome = withTimeout(5_000) { caller.await() }

        assertTrue(stopper.success)
        assertFalse("the caller itself must keep running", caller.isCancelled)
        val report = (parentOutcome as TaskCollisionOutcome.Executed).value
        val subTaskStep = report.results.first() as ActionResult.Failure
        assertTrue(subTaskStep.message, "Stopped by a Stop Task step in 'Stopper'" in subTaskStep.message)
        assertEquals("true", variables.get("PARENT_AFTER"))
    }

    @Test
    fun namingItsOwnTaskAlsoStopsItsOtherRuns() = runBlocking {
        val coordinator = TaskCollisionCoordinator()
        val first = Task(id = 9, name = "Loop", collisionMode = CollisionMode.RUN_BOTH, actions = listOf(ActionSpec(type = "test.stoptask.block")))
        val firstRun = async { coordinator.execute(first) { runner(VariableStore(), coordinator).run(first) } }
        blockStarted.await()

        val second = first.copy(actions = listOf(stop("Loop"), record("AFTER")))
        val variables = VariableStore()
        val secondRun = async { coordinator.execute(second) { runner(variables, coordinator, tasks = listOf(second)).run(second) } }
        val outcome = withTimeout(5_000) { secondRun.await() }

        val report = (outcome as TaskCollisionOutcome.Executed).value
        assertTrue("the run that named itself ends like Stop", report.success)
        assertNull(variables.get("AFTER"))
        val stopped = runCatching { withTimeout(5_000) { firstRun.await() } }.exceptionOrNull()
        assertEquals("Stopped by a Stop Task step in 'Loop'", stopped?.message)
    }

    @Test
    fun aRunWaitingForItsTurnIsNotStopped() = runBlocking {
        val coordinator = TaskCollisionCoordinator()
        val busy = Task(id = 11, name = "Queue", collisionMode = CollisionMode.WAIT, actions = listOf(ActionSpec(type = "test.stoptask.block")))
        val firstRun = async { coordinator.execute(busy) { runner(VariableStore(), coordinator).run(busy) } }
        blockStarted.await()
        val queued = busy.copy(actions = listOf(record("QUEUED_RAN")))
        val variables = VariableStore()
        // Undispatched, so it's already waiting on the slot when the Stop Task runs.
        val queuedRun = async(start = CoroutineStart.UNDISPATCHED) {
            coordinator.execute(queued) { runner(variables, coordinator).run(queued) }
        }

        runner(VariableStore(), coordinator, listOf(busy))
            .run(Task(id = 12, name = "Stopper", actions = listOf(stop("Queue"))))

        val stopped = runCatching { withTimeout(5_000) { firstRun.await() } }.exceptionOrNull()
        assertEquals("Stopped by a Stop Task step in 'Stopper'", stopped?.message)
        val outcome = withTimeout(5_000) { queuedRun.await() }
        assertTrue("the queued run starts once the slot frees up, got $outcome", (outcome as TaskCollisionOutcome.Executed).value.success)
        assertEquals("true", variables.get("QUEUED_RAN"))
    }

    @Test
    fun aStepThatNamesNoTaskFails() = runBlocking {
        val blank = ActionSpec(type = STOP_TASK_ACTION_ID, args = mapOf("task" to " "))
        val report = runner(VariableStore(), TaskCollisionCoordinator())
            .run(Task(id = 8, name = "Stopper", actions = listOf(blank)))

        assertFalse(report.success)
        assertEquals("task.stop requires a 'task' (id or name)", (report.results.single() as ActionResult.Failure).message)
    }
}
