package com.opentasker.core.engine

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
 * A task with no id skips the collision slots, but the stop button still holds the Job its block
 * runs in, so that Job has to be the run's own and not the caller's that records the result.
 */
class TaskCollisionNoIdLateStopTest {

    private val task = Task(id = 0, name = "Unsaved")

    @Test
    fun aStopAfterTheRunFinishedLeavesTheCallerAlone() = runBlocking {
        val coordinator = TaskCollisionCoordinator()
        val caller = async {
            var registered: Job? = null
            val outcome = coordinator.execute(task) {
                registered = currentCoroutineContext()[Job]
                "done"
            }
            // What the stop button does with a run it saw just before the run finished.
            registered?.cancel(CancellationException("Stopped too late"))
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
        val registered = CompletableDeferred<Job>()
        val caller = async {
            coordinator.execute(task) {
                registered.complete(checkNotNull(currentCoroutineContext()[Job]))
                awaitCancellation()
            }
        }

        registered.await().cancel(CancellationException("Stopped"))
        val stopped = runCatching { withTimeout(5_000) { caller.await() } }.exceptionOrNull()

        assertTrue("the caller must be cancelled, got $stopped", caller.isCancelled)
        assertEquals("Stopped", stopped?.message)
    }
}
