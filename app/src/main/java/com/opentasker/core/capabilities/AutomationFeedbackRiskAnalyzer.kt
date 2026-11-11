package com.opentasker.core.capabilities

import com.opentasker.core.model.ActionSpec
import com.opentasker.core.model.Profile
import com.opentasker.core.model.Task

/** A high-confidence task-to-task cycle that can retrigger itself indefinitely. */
data class FeedbackLoopRisk(
    val taskPath: List<String>,
)

/**
 * Finds only explainable, statically provable feedback loops. Dynamic task references are ignored
 * because warning on an unknown target would make ordinary imported or variable-driven tasks
 * noisy; runtime admission still bounds those executions if they do loop.
 */
object AutomationFeedbackRiskAnalyzer {
    fun analyze(profile: Profile, tasks: List<Task>): List<FeedbackLoopRisk> {
        val byId = tasks.associateBy(Task::id)
        val byName = tasks.groupBy { it.name.trim().lowercase() }
        val risks = linkedSetOf<FeedbackLoopRisk>()

        fun references(action: ActionSpec): List<Task> {
            if (!action.enabled || action.type != TASK_RUN_ACTION) return emptyList()
            val reference = listOf("task", "name", "id")
                .firstNotNullOfOrNull { key -> action.args[key]?.trim()?.takeIf(String::isNotBlank) }
                ?: return emptyList()
            if (reference.contains('%') || reference.contains("{{")) return emptyList()
            return reference.toLongOrNull()?.let { id -> listOfNotNull(byId[id]) }
                ?: byName[reference.lowercase()].orEmpty()
        }

        val reachable = linkedSetOf<Task>()
        val pending = java.util.ArrayDeque<Task>()
        listOfNotNull(profile.enterTaskId, profile.exitTaskId).forEach { id -> byId[id]?.let(pending::addLast) }
        while (pending.isNotEmpty()) {
            val task = pending.removeFirst()
            if (reachable.add(task)) task.actions.flatMap(::references).forEach(pending::addLast)
        }
        val reachableIds = reachable.mapTo(hashSetOf(), Task::id)

        fun walk(task: Task, path: List<Task>) {
            task.actions.flatMap(::references).forEach { next ->
                if (next.id !in reachableIds) return@forEach
                val cycleStart = path.indexOfFirst { it.id == next.id }
                if (cycleStart >= 0) {
                    risks += FeedbackLoopRisk(canonicalCycle(path.drop(cycleStart) + next))
                } else if (path.none { it.id == next.id }) {
                    walk(next, path + next)
                }
            }
        }

        reachable.forEach { task -> walk(task, listOf(task)) }
        return risks.toList()
    }

    private fun canonicalCycle(cycle: List<Task>): List<String> {
        val nodes = cycle.dropLast(1)
        val rotations = nodes.indices.map { index ->
            nodes.drop(index) + nodes.take(index)
        }
        val canonical = rotations.minBy { rotation -> rotation.joinToString("\u0000") { it.id.toString() } }
        return canonical.map(Task::name) + canonical.first().name
    }

    private const val TASK_RUN_ACTION = "task.run"
}
