package com.opentasker.core.engine

import com.opentasker.core.model.ActionSpec
import com.opentasker.core.model.Task

/**
 * Running one action of a task on its own, to tune it without re-running everything.
 *
 * The action is executed as a one-action copy of its own task, so it keeps the task's identity,
 * collision policy and project. That matters: a single-action run and a full run of the same task
 * touch the same task-scoped variables, and sharing the id is what makes the collision policy
 * apply to both. It also means the run-log row already carries the right task.
 *
 * Flow-control markers are excluded. An `if` without its `end if` is not a smaller program, it is
 * a broken one, and running a `for each` alone would either do nothing or abort at a closing
 * marker that is no longer there.
 */
object SingleActionRun {

    /**
     * Must stay in step with [RunLogSource.SINGLE_ACTION] and its prefix match. The key lives
     * there rather than here because `:core:engine` cannot see this file: `FlowControl` is still
     * in `:app`, so this object has to be too.
     */
    private const val SOURCE_PREFIX = "Single action"

    /** Flow control only means anything in sequence, so it is never offered on its own. */
    fun isRunnableAlone(action: ActionSpec): Boolean = !FlowControl.isControl(action.type)

    /**
     * The one-action task to execute, or null when [index] is out of range or names a flow-control
     * marker. Returning null rather than throwing keeps a stale UI index from crashing the editor.
     */
    fun taskFor(task: Task, index: Int): Task? {
        val action = task.actions.getOrNull(index) ?: return null
        if (!isRunnableAlone(action)) return null
        // Asked for by name, so a step turned off in the task still runs here.
        return task.copy(actions = listOf(action.copy(enabled = true)))
    }

    /**
     * The run-log source string. [RunLogSource.classify] splits this on the colon, so the label
     * half becomes the action's own name and the row reads as that action rather than as an
     * anonymous manual run.
     */
    fun sourceFor(label: String): String {
        val trimmed = label.trim()
        return if (trimmed.isEmpty()) SOURCE_PREFIX else "$SOURCE_PREFIX: $trimmed"
    }
}
