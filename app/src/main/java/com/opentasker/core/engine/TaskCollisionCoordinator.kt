package com.opentasker.core.engine

import com.opentasker.core.model.CollisionMode
import com.opentasker.core.model.Task
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull

sealed interface TaskCollisionOutcome<out T> {
    data class Executed<T>(val value: T) : TaskCollisionOutcome<T>
    data class Skipped(val reason: String) : TaskCollisionOutcome<Nothing>
}

/**
 * Applies a task's collision policy at the common execution boundary used by every top-level
 * source and by nested `task.run` actions. Profile re-trigger modes decide whether a profile emits
 * another invocation; this coordinator then applies the referenced task's global collision rule.
 */
class TaskCollisionCoordinator(
    /**
     * How long a WAIT-mode invocation queues before giving up.
     *
     * WAIT holds a per-task mutex for the whole run, so two tasks that sub-run each other in WAIT
     * mode acquire them in opposite orders and deadlock (A holds 1 and wants 2 while B holds 2 and
     * wants 1). `executionChain` only guards recursion inside one chain, and `task.run` returns
     * before the per-action timeout wrapper, so nothing else bounded the wait: each stuck run held
     * an admission lease until the global cap wedged the engine.
     */
    private val waitAcquisitionTimeoutMs: Long = DEFAULT_WAIT_ACQUISITION_TIMEOUT_MS,
) {
    private class Slot {
        val activeJobs = linkedSetOf<Job>()
        val waitMutex = Mutex()
    }

    private val slots = ConcurrentHashMap<Long, Slot>()

    suspend fun <T> execute(task: Task, block: suspend () -> T): TaskCollisionOutcome<T> {
        val caller = currentCoroutineContext()[Job] ?: return TaskCollisionOutcome.Executed(block())
        // A task with no id shares no slot, but its run still gets a scope of its own: the stop
        // button holds whatever Job the block runs in.
        if (task.id <= 0L) return runRegistered(Slot(), caller, block = block)
        val slot = slots.computeIfAbsent(task.id) { Slot() }

        return when (task.collisionMode) {
            CollisionMode.RUN_BOTH -> runRegistered(slot, caller, block = block)
            CollisionMode.WAIT -> {
                val owner = Any()
                withTimeoutOrNull(waitAcquisitionTimeoutMs) { slot.waitMutex.lock(owner) }
                // holdsLock is the authoritative post-condition: it stays correct even when the
                // timeout fires in the window between acquiring the lock and returning.
                if (!slot.waitMutex.holdsLock(owner)) {
                    TaskCollisionOutcome.Skipped(
                        "Task was still running after ${waitAcquisitionTimeoutMs / 1_000} s; Wait gave up on this invocation.",
                    )
                } else {
                    try {
                        runRegistered(slot, caller, block = block)
                    } finally {
                        slot.waitMutex.unlock(owner)
                    }
                }
            }
            CollisionMode.ABORT_NEW -> runRegistered(
                slot,
                caller,
                admit = { run ->
                    slot.activeJobs.removeAll { !it.isActive }
                    if (slot.activeJobs.isNotEmpty()) {
                        "Task is already running; Abort new skipped this invocation."
                    } else {
                        slot.activeJobs += run
                        null
                    }
                },
                block = block,
            )
            CollisionMode.ABORT_EXISTING -> {
                var previous = emptyList<Job>()
                runRegistered(
                    slot,
                    caller,
                    admit = { run ->
                        slot.activeJobs.removeAll { !it.isActive }
                        // A copy this run is nested in stays, or replacing it would cancel this run too.
                        previous = slot.activeJobs.filterNot { it.isOrContains(caller) }
                        slot.activeJobs.removeAll(previous.toSet())
                        slot.activeJobs += run
                        null
                    },
                ) {
                    previous.forEach { activeJob ->
                        activeJob.cancel(CancellationException("Replaced by a newer run under Abort existing."))
                    }
                    block()
                }
            }
        }
    }

    /**
     * Cancels every running invocation of [taskId] for a Stop Task step and returns how many it
     * cancelled. A WAIT invocation still queued for its turn isn't running yet, so it starts once
     * the slot frees up. The invocation [sparing] runs in is left alone, for a step that names its
     * own task and ends its own run another way.
     */
    fun cancelRunning(taskId: Long, reason: String, sparing: Job? = null): Int {
        val slot = slots[taskId] ?: return 0
        val running = synchronized(slot) {
            slot.activeJobs.removeAll { !it.isActive }
            slot.activeJobs.filter { sparing == null || !it.isOrContains(sparing) }
        }
        running.forEach { job -> job.cancel(CancellationException(reason)) }
        return running.size
    }

    // A copy is registered by the Job of its own scope, so the code running in it has [job] somewhere
    // under that. Nothing runs a task inside its own run today (the execution chain refuses it), so
    // the copy that encloses [job] is always the one [job] belongs to.
    private fun Job.isOrContains(job: Job): Boolean = this === job || children.any { it.isOrContains(job) }

    /**
     * Runs [block] in a scope of its own and registers that scope's Job as the running copy, after
     * [admit] (under the slot lock) lets it in. [admit] returns why it refused, or null.
     *
     * The caller's Job used to be registered, so a stop that saw this copy running just before it
     * finished cancelled the caller while it recorded the result, and the finished run lost its run
     * log. Now that stop lands on the finished scope and the result stands. A stop that [block] sees,
     * at a step's limit or any other suspension, cancels the caller as well, as it always has, so
     * callers see the same thing. One that lands after [block]'s last suspension leaves the result.
     */
    private suspend fun <T> runRegistered(
        slot: Slot,
        caller: Job,
        admit: (run: Job) -> String? = { run -> slot.activeJobs += run; null },
        block: suspend () -> T,
    ): TaskCollisionOutcome<T> {
        var outcome: TaskCollisionOutcome<T>? = null
        var stopped = false
        try {
            coroutineScope {
                val run = coroutineContext.job
                synchronized(slot) { admit(run) }?.let { refusal ->
                    outcome = TaskCollisionOutcome.Skipped(refusal)
                    return@coroutineScope
                }
                try {
                    outcome = TaskCollisionOutcome.Executed(block())
                } catch (stop: CancellationException) {
                    // Still active means the block threw it itself, a timeout say, and nothing stopped it.
                    stopped = !run.isActive
                    throw stop
                } finally {
                    synchronized(slot) { slot.activeJobs.remove(run) }
                }
            }
        } catch (stop: CancellationException) {
            outcome?.takeIf { caller.isActive }?.let { return it }
            if (stopped) caller.cancel(stop)
            throw stop
        }
        return checkNotNull(outcome)
    }

    companion object {
        internal const val DEFAULT_WAIT_ACQUISITION_TIMEOUT_MS = 300_000L
        val Default = TaskCollisionCoordinator()
    }
}
