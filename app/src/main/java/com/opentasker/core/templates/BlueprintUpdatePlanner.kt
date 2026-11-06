package com.opentasker.core.templates

import com.opentasker.core.diff.AutomationSemanticDiff
import com.opentasker.core.diff.SemanticDiffDocument
import com.opentasker.core.model.Profile
import com.opentasker.core.model.Task

/** A newer definition compared with one concrete, already-installed instance. */
data class BlueprintUpdateReview(
    val blueprintId: String,
    val blueprintTitle: String,
    val installedVersion: Int,
    val incomingVersion: Int,
    val profileId: Long,
    val taskId: Long,
    val document: SemanticDiffDocument = SemanticDiffDocument(),
    val error: String? = null,
) {
    val canReview: Boolean get() = error == null
    val hasChanges: Boolean get() = !document.isEmpty
}

/**
 * Pure update planning for blueprint instances. It creates a candidate in memory and compares it
 * with the user's records; it deliberately has no write operation, so importing a definition can
 * never overwrite an instantiated profile or task.
 */
object BlueprintUpdatePlanner {
    fun plan(
        blueprint: AutomationBlueprint,
        installation: BlueprintInstallation,
        currentProfile: Profile?,
        currentTask: Task?,
        currentExitTask: Task? = null,
    ): BlueprintUpdateReview? {
        if (blueprint.version <= installation.blueprintVersion) return null

        val base = BlueprintUpdateReview(
            blueprintId = blueprint.id,
            blueprintTitle = blueprint.title,
            installedVersion = installation.blueprintVersion,
            incomingVersion = blueprint.version,
            profileId = installation.profileId,
            taskId = installation.taskId,
        )
        if (currentProfile == null || currentTask == null) {
            return base.copy(error = "The installed blueprint profile or task is no longer available.")
        }

        val candidate = runCatching { blueprint.instantiate(installation.inputValues) }
            .getOrElse { error ->
                return base.copy(error = error.message ?: "The newer blueprint values are invalid.")
            }
        val candidateTask = candidate.task.copy(
            id = currentTask.id,
            projectId = currentTask.projectId,
        )
        val candidateProfile = candidate.profile.copy(
            id = currentProfile.id,
            enterTaskId = currentTask.id,
            // The candidate has no stored exit task of its own, and the task diff below is what
            // reports a change to one. Comparing the link too would show a removal that isn't real.
            exitTaskId = currentProfile.exitTaskId,
            projectId = currentProfile.projectId,
        )
        val taskNames = mapOf(currentTask.id to currentTask.name)
        val profileDiff = AutomationSemanticDiff.compareProfile(
            before = currentProfile,
            after = candidateProfile,
            beforeTaskNames = taskNames,
            afterTaskNames = mapOf(candidateTask.id to candidateTask.name),
        )
        val taskDiff = AutomationSemanticDiff.compareTask(
            before = currentTask,
            after = candidateTask,
            beforeTaskNames = taskNames,
            afterTaskNames = mapOf(candidateTask.id to candidateTask.name),
        )
        val candidateExitTask = candidate.exitTask?.copy(
            id = currentExitTask?.id ?: 0L,
            projectId = currentTask.projectId,
        )
        val exitTaskDiff = AutomationSemanticDiff.compareTask(before = currentExitTask, after = candidateExitTask)
        return base.copy(
            document = SemanticDiffDocument(
                entries = listOfNotNull(profileDiff, taskDiff, exitTaskDiff),
            ),
        )
    }
}
