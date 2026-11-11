package com.opentasker.core.capabilities

import com.opentasker.core.model.Profile
import com.opentasker.core.model.Task

/**
 * The checks the app runs when a profile is turned on from its list, for the routes that turn one
 * on from outside it, so they can't turn on what the app wouldn't. In the app a task loop sends the
 * profile to review, and a blocking lint finding (a rule that can never match, or a same-priority
 * profile writing the same setting) or a missing Modify system settings grant stops it.
 */
object ProfileTurnOnChecks {

    /** Why [profile] can't be turned on, or null. [otherProfiles] may include [profile] itself. */
    fun refusal(
        profile: Profile,
        tasks: List<Task>,
        otherProfiles: List<Profile>,
        canWriteSettings: Boolean,
    ): String? {
        if (profile.requiresRiskAcknowledgement) {
            return "profile '${profile.name}' needs its review in the app before anything turns it on"
        }
        val on = profile.copy(enabled = true)
        AutomationFeedbackRiskAnalyzer.analyze(on, tasks).firstOrNull()?.let { loop ->
            return "profile '${profile.name}' runs its tasks in a loop (${loop.taskPath.joinToString(" -> ")}), " +
                "so it has to be turned on in the app, where the loop gets reviewed"
        }
        val blockers = AutomationLint.analyze(on, tasks, otherProfiles).blockingFor(profile.id)
        if (blockers.isNotEmpty()) {
            return "profile '${profile.name}' wasn't turned on. " +
                blockers.joinToString(" ") { finding -> "${finding.title}: ${finding.detail} ${finding.suggestedFix}" }
        }
        val taskById = tasks.associateBy(Task::id)
        val actions = listOfNotNull(on.enterTaskId, on.exitTaskId, on.fallbackTaskId).distinct()
            .flatMap { id -> taskById[id]?.actions.orEmpty() }
        return "profile '${profile.name}' changes system settings, which OpenTasker isn't allowed to do yet, so it wasn't turned on"
            .takeIf { WriteSettingsAdmission.blocked(actions, canWriteSettings) }
    }
}
