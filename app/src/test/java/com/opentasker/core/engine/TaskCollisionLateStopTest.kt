package com.opentasker.core.engine

import com.opentasker.core.model.CollisionMode
import com.opentasker.core.model.Task
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A Stop Task step or an Abort existing run cancels whatever the coordinator registered for a
 * running copy. When that cancel lands after the copy finished its steps, the run has to keep its
 * result, because the caller goes on to record it (run log, journal, globals).
 */
class TaskCollisionLateStopTest {

    private val task = Task(id = 21, name = "Late", collisionMode = CollisionMode.RUN_BOTH)

    @Test
    fun aStopAfterTheRunFinishedLeavesTheCallerAlone() = runBlocking {
        val coordinator = TaskCollisionCoordinator()
        val caller = async {
            var registered: Job? = null
            val outcome = coordinator.execute(task) {
                registered = currentCoroutineContext()[Job]
                "done"
            }
            // What a stop does with a copy it saw running just before the copy finished.
            registered?.cancel(CancellationException("Stopped too late"))
            yield()
            outcome
        }

        val outcome = withTimeout(5_000) { caller.await() }

        assertFalse(caller.isCancelled)
        assertEquals("done", (outcome as TaskCollisionOutcome.Executed).value)
    }

    @Test
    fun aStopDuringTheLastStepKeepsTheFinishedResult() = runBlocking {
        val coordinator = TaskCollisionCoordinator()
        val caller = async {
            val outcome = coordinator.execute(task) {
                coordinator.cancelRunning(task.id, "Stopped too late")
                "done"
            }
            yield()
            outcome
        }

        val outcome = withTimeout(5_000) { caller.await() }

        assertFalse(caller.isCancelled)
        assertEquals("done", (outcome as TaskCollisionOutcome.Executed).value)
    }

    @Test
    fun aStopWhileTheRunIsWorkingStillEndsTheCaller() = runBlocking {
        val coordinator = TaskCollisionCoordinator()
        val started = CompletableDeferred<Unit>()
        val caller = async {
            coordinator.execute(task) {
                started.complete(Unit)
                awaitCancellation()
            }
        }
        started.await()

        assertEquals(1, coordinator.cancelRunning(task.id, "Stopped"))
        val stopped = runCatching { withTimeout(5_000) { caller.await() } }.exceptionOrNull()

        assertTrue("the caller must be cancelled, got $stopped", caller.isCancelled)
        assertEquals("Stopped", stopped?.message)
    }

    @Test
    fun aCancellationThrownByTheRunItselfDoesNotCancelTheCaller() = runBlocking {
        val coordinator = TaskCollisionCoordinator()
        val caller = async {
            val thrown = runCatching {
                coordinator.execute(task) { throw CancellationException("timed out inside") }
            }.exceptionOrNull()
            yield()
            thrown?.message
        }

        assertEquals("timed out inside", withTimeout(5_000) { caller.await() })
        assertFalse(caller.isCancelled)
    }
}
