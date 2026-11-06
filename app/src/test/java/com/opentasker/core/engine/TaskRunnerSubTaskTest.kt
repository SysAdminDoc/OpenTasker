package com.opentasker.core.engine

import android.content.ContextWrapper
import com.opentasker.core.model.ActionSpec
import com.opentasker.core.model.CollisionMode
import com.opentasker.core.model.Task
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskRunnerSubTaskTest {

    /** Records into a shared variable so we can observe whether a sub-task ran. */
    private fun registerRecorderAction(id: String) {
        ActionRegistry.register(object : Action {
            override val id = id
            override val category = ActionCategory.FLOW
            override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
                val key = args["key"] ?: "RAN"
                ctx.variables.set(key, args["value"] ?: "true")
                return ActionResult.Success
            }
        })
    }

    private fun runner(variables: VariableStore, resolve: SubTaskResolver?) =
        TaskRunner(ActionContext(ContextWrapper(null), variables), resolveTask = resolve)

    /**
     * The collision coordinator identifies a run by its Job, and a sub-task used to run in the
     * caller's own coroutine. Aborting the sub-task therefore aborted the entire caller, which had
     * no collision of its own and was logged as "Replaced by a newer run".
     */
    @Test
    fun abortExistingOnANestedSubTaskLeavesTheCallerRunning() = runBlocking {
        registerRecorderAction("test.sub.recorder")
        val started = CompletableDeferred<Unit>()
        ActionRegistry.register(object : Action {
            override val id = "test.sub.blocks"
            override val category = ActionCategory.FLOW
            override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
                started.complete(Unit)
                awaitCancellation()
            }
        })
        val coordinator = TaskCollisionCoordinator()
        val nested = Task(
            id = 7,
            name = "Nested",
            collisionMode = CollisionMode.ABORT_EXISTING,
            actions = listOf(ActionSpec(type = "test.sub.blocks")),
        )
        val variables = VariableStore()
        val caller = async {
            TaskRunner(
                ActionContext(ContextWrapper(null), variables),
                resolveTask = { ref -> nested.takeIf { ref == "Nested" } },
                collisionCoordinator = coordinator,
            ).run(
                Task(
                    name = "Parent",
                    actions = listOf(
                        ActionSpec(
                            type = "task.run",
                            args = mapOf("task" to "Nested"),
                            continueOnError = true,
                        ),
                        ActionSpec(type = "test.sub.recorder", args = mapOf("key" to "PARENT_CONTINUED")),
                    ),
                ),
            )
        }
        started.await()

        val replacement = coordinator.execute(nested) { "replacement" }

        withTimeout(5_000) { caller.await() }
        assertTrue("the replacement must be admitted", replacement is TaskCollisionOutcome.Executed)
        assertFalse("the caller must not be cancelled with its sub-task", caller.isCancelled)
        assertEquals(
            "the caller must carry on past the sub-task that was replaced",
            "true",
            variables.get("PARENT_CONTINUED"),
        )
    }

    /**
     * The other half of the same contract: isolating the sub-task's job must not detach it. When
     * the caller is cancelled, from the Live screen or by its own profile, the sub-task still has
     * to stop with it.
     */
    @Test
    fun cancellingTheCallerStillStopsItsSubTask() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val nestedStopped = CompletableDeferred<Unit>()
        ActionRegistry.register(object : Action {
            override val id = "test.sub.observed"
            override val category = ActionCategory.FLOW
            override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
                try {
                    started.complete(Unit)
                    awaitCancellation()
                } finally {
                    nestedStopped.complete(Unit)
                }
            }
        })
        val nested = Task(
            id = 9,
            name = "Observed",
            collisionMode = CollisionMode.ABORT_EXISTING,
            actions = listOf(ActionSpec(type = "test.sub.observed")),
        )
        val caller = async {
            TaskRunner(
                ActionContext(ContextWrapper(null), VariableStore()),
                resolveTask = { ref -> nested.takeIf { ref == "Observed" } },
                collisionCoordinator = TaskCollisionCoordinator(),
            ).run(Task(name = "Parent", actions = listOf(ActionSpec(type = "task.run", args = mapOf("task" to "Observed")))))
        }
        started.await()

        caller.cancelAndJoin()

        withTimeout(5_000) { nestedStopped.await() }
        assertTrue("the caller's cancellation must reach its sub-task", caller.isCancelled)
    }

    @Test
    fun runsResolvedSubTask() = runBlocking {
        registerRecorderAction("test.sub.recorder")
        val subTask = Task(
            id = 42,
            name = "Toggle WiFi",
            actions = listOf(ActionSpec(type = "test.sub.recorder", args = mapOf("key" to "SUB_RAN"))),
        )
        val variables = VariableStore()
        val report = runner(variables) { ref -> subTask.takeIf { ref == "42" || ref == "Toggle WiFi" } }.run(
            Task(name = "Parent", actions = listOf(ActionSpec(type = "task.run", args = mapOf("task" to "Toggle WiFi")))),
        )
        assertTrue(report.success)
        assertEquals("true", variables.get("SUB_RAN"))
    }

    @Test
    fun passesInputVariablesAndReceivesGlobalOutput() = runBlocking {
        // Sub-task echoes an input variable into a global output variable.
        ActionRegistry.register(object : Action {
            override val id = "test.sub.echo"
            override val category = ActionCategory.FLOW
            override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
                ctx.variables.set("RESULT", ctx.variables.get("input").orEmpty())
                return ActionResult.Success
            }
        })
        val subTask = Task(name = "Echo", actions = listOf(ActionSpec(type = "test.sub.echo")))
        val variables = VariableStore()
        val report = runner(variables) { ref -> subTask.takeIf { ref == "Echo" } }.run(
            Task(
                name = "Parent",
                actions = listOf(
                    ActionSpec(type = "task.run", args = mapOf("task" to "Echo", "input" to "hello")),
                ),
            ),
        )
        assertTrue(report.success)
        assertEquals("hello", variables.get("RESULT"))
    }

    @Test
    fun aSubTaskReadsAnInputThroughATemplateAndHandsBackAGlobal() = runBlocking {
        // The editor's Inputs section saves `city = Boston` as a plain argument (A-354).
        registerRecorderAction("test.sub.template")
        val subTask = Task(
            name = "Weather",
            actions = listOf(ActionSpec(type = "test.sub.template", args = mapOf("key" to "FORECAST", "value" to "Rain in {{ city }}"))),
        )
        val variables = VariableStore()
        val report = runner(variables) { ref -> subTask.takeIf { ref == "Weather" } }.run(
            Task(
                name = "Morning",
                actions = listOf(
                    ActionSpec(type = "task.run", args = mapOf("task" to "Weather", "city" to "Boston")),
                    ActionSpec(type = "test.sub.template", args = mapOf("key" to "SEEN", "value" to "{{ global.FORECAST }}")),
                ),
            ),
        )

        assertTrue(report.success)
        assertEquals("Rain in Boston", variables.get("FORECAST"))
        assertEquals("Rain in Boston", variables.get("SEEN"))
        assertEquals(null, variables.get("city"))
        // The run log shows what was passed on the Run Task step itself.
        assertTrue(report.traces.first { it.actionType == "task.run" }.expandedArgSummary.orEmpty().contains("city=Boston"))
    }

    @Test
    fun subTaskInputVariablesDoNotLeakIntoParentScope() = runBlocking {
        registerRecorderAction("test.sub.recorder")
        // The parent records whatever it currently sees for the sub-task input name `input`
        // into a global AFTER the sub-task returns; a leak would surface the child's value.
        ActionRegistry.register(object : Action {
            override val id = "test.parent.capture"
            override val category = ActionCategory.FLOW
            override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
                ctx.variables.set("PARENT_SAW", ctx.variables.get("input").orEmpty())
                return ActionResult.Success
            }
        })
        val subTask = Task(name = "Echo", actions = listOf(ActionSpec(type = "test.sub.recorder", args = mapOf("key" to "SUB_RAN"))))
        val variables = VariableStore()
        val report = runner(variables) { ref -> subTask.takeIf { ref == "Echo" } }.run(
            Task(
                name = "Parent",
                actions = listOf(
                    ActionSpec(type = "task.run", args = mapOf("task" to "Echo", "input" to "child-only")),
                    ActionSpec(type = "test.parent.capture"),
                ),
            ),
        )
        assertTrue(report.success)
        assertEquals("true", variables.get("SUB_RAN"))
        // The child's lowercase input must not survive into the parent's later actions.
        assertEquals("", variables.get("PARENT_SAW"))
    }

    @Test
    fun failsWhenSubTaskNotFound() = runBlocking {
        val report = runner(VariableStore()) { null }.run(
            Task(name = "Parent", actions = listOf(ActionSpec(type = "task.run", args = mapOf("task" to "ghost")))),
        )
        assertFalse(report.success)
        assertTrue((report.results.single() as ActionResult.Failure).message.contains("not found"))
    }

    @Test
    fun failsWhenNoResolverAvailable() = runBlocking {
        val report = runner(VariableStore(), resolve = null).run(
            Task(name = "Parent", actions = listOf(ActionSpec(type = "task.run", args = mapOf("task" to "x")))),
        )
        assertFalse(report.success)
    }

    @Test
    fun failsWhenTaskReferenceMissing() = runBlocking {
        val report = runner(VariableStore()) { Task(name = "x", actions = emptyList()) }.run(
            Task(name = "Parent", actions = listOf(ActionSpec(type = "task.run"))),
        )
        assertFalse(report.success)
    }

    @Test
    fun boundsRecursionAtDepthLimitWithoutStackOverflow() = runBlocking {
        // A task that calls itself; the resolver always returns the same recursive task.
        val recursive = Task(
            id = 1,
            name = "Recursive",
            actions = listOf(ActionSpec(type = "task.run", args = mapOf("task" to "Recursive"))),
        )
        val report = runner(VariableStore()) { ref -> recursive.takeIf { ref == "Recursive" } }.run(recursive)
        assertFalse(report.success)
        // The top-level action fails because somewhere below the depth limit was hit.
        assertTrue(report.results.any { it is ActionResult.Failure })
    }

    @Test
    fun nestedTaskRunUsesTheTargetCollisionPolicy() = runBlocking {
        registerRecorderAction("test.sub.collision.recorder")
        val coordinator = TaskCollisionCoordinator()
        val child = Task(
            id = 42,
            name = "Child",
            collisionMode = CollisionMode.ABORT_NEW,
            actions = listOf(ActionSpec(type = "test.sub.collision.recorder", args = mapOf("key" to "CHILD_RAN"))),
        )
        val activeStarted = CompletableDeferred<Unit>()
        val releaseActive = CompletableDeferred<Unit>()
        val active = async {
            coordinator.execute(child) {
                activeStarted.complete(Unit)
                releaseActive.await()
            }
        }
        activeStarted.await()
        val variables = VariableStore()
        val report = TaskRunner(
            ctx = ActionContext(ContextWrapper(null), variables),
            resolveTask = { child },
            collisionCoordinator = coordinator,
            executionChain = setOf(7L),
        ).run(
            Task(
                id = 7,
                name = "Parent",
                actions = listOf(ActionSpec(type = "task.run", args = mapOf("task" to "42"))),
            ),
        )

        assertFalse(report.success)
        assertEquals(null, variables.get("CHILD_RAN"))
        assertTrue((report.results.single() as ActionResult.Failure).message.contains("Abort new"))
        releaseActive.complete(Unit)
        active.await()
        Unit
    }
}
