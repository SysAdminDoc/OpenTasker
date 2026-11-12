package com.opentasker.core.engine

import com.opentasker.core.model.CollisionMode
import com.opentasker.core.model.Task
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
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
        if (task.id <= 0L) return TaskCollisionOutcome.Executed(block())
        val job = currentCoroutineContext()[Job] ?: return TaskCollisionOutcome.Executed(block())
        val slot = slots.computeIfAbsent(task.id) { Slot() }

        return when (task.collisionMode) {
            CollisionMode.RUN_BOTH -> runRegistered(slot, job, block = block)
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
                        runRegistered(slot, job, block = block)
                    } finally {
                        slot.waitMutex.unlock(owner)
                    }
                }
            }
            CollisionMode.ABORT_NEW -> {
                val admitted = synchronized(slot) {
                    slot.activeJobs.removeAll { !it.isActive }
                    if (slot.activeJobs.isNotEmpty()) {
                        false
                    } else {
                        slot.activeJobs += job
                        true
                    }
                }
                if (!admitted) {
                    TaskCollisionOutcome.Skipped("Task is already running; Abort new skipped this invocation.")
                } else {
                    runRegistered(slot, job, alreadyRegistered = true, block = block)
                }
            }
            CollisionMode.ABORT_EXISTING -> {
                val previous = synchronized(slot) {
                    slot.activeJobs.removeAll { !it.isActive }
                    val active = slot.activeJobs.filterNot { it === job }
                    slot.activeJobs.clear()
                    slot.activeJobs += job
                    active
                }
                previous.forEach { activeJob ->
                    activeJob.cancel(CancellationException("Replaced by a newer run under Abort existing."))
                }
                runRegistered(slot, job, alreadyRegistered = true, block = block)
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

    // A registered copy that encloses [job] is spared too. Nothing runs a task inside its own run
    // today (the execution chain refuses it), so that copy is always [job] itself.
    private fun Job.isOrContains(job: Job): Boolean = this === job || children.any { it.isOrContains(job) }

    private suspend fun <T> runRegistered(
        slot: Slot,
        job: Job,
        alreadyRegistered: Boolean = false,
        block: suspend () -> T,
    ): TaskCollisionOutcome.Executed<T> {
        if (!alreadyRegistered) synchronized(slot) { slot.activeJobs += job }
        return try {
            TaskCollisionOutcome.Executed(block())
        } finally {
            synchronized(slot) { slot.activeJobs.remove(job) }
        }
    }

    companion object {
        internal const val DEFAULT_WAIT_ACQUISITION_TIMEOUT_MS = 300_000L
        val Default = TaskCollisionCoordinator()
    }
}
