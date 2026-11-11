package com.opentasker.core.capabilities

import com.opentasker.core.model.Profile
import com.opentasker.core.model.Task
import androidx.room.withTransaction
import com.opentasker.core.storage.AppDatabase

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

    /**
     * Turns the saved profile [profileId] on or off, running [refusalForSaved] first when it goes
     * from off to on. The check and the write share one transaction, so an edit saved in between
     * can't slip past the check, and only the enabled column is written. Returns why it wasn't
     * switched, or null.
     */
    suspend fun switchSaved(db: AppDatabase, profileId: Long, enabled: Boolean, canWriteSettings: Boolean): String? =
        db.withTransaction {
            val current = db.profileDao().getById(profileId)
                ?: return@withTransaction "the profile was removed, so it wasn't switched"
            if (enabled && !current.enabled) {
                refusalForSaved(db, profileId, canWriteSettings)?.let { return@withTransaction it }
            }
            "profile '${current.name}' needs its review in the app before anything turns it on"
                .takeIf { db.profileDao().setEnabled(profileId, enabled) == 0 }
        }

    /**
     * [refusal] for the saved profile [profileId], checked against every saved profile and task. A
     * profile that's gone, or any saved record that can't be read, is refused too.
     */
    suspend fun refusalForSaved(db: AppDatabase, profileId: Long, canWriteSettings: Boolean): String? {
        val profiles = db.profileDao().getAll().map { it.toDomainDecodeResult() }
        val tasks = db.taskDao().getAll().map { it.toDomainDecodeResult() }
        val profile = profiles.firstOrNull { it.value.id == profileId }?.value
            ?: return "the profile was removed, so it wasn't turned on"
        if (profiles.any { it.issue != null } || tasks.any { it.issue != null }) {
            return "profile '${profile.name}' wasn't turned on, since a saved profile or task couldn't be read to check it"
        }
        return refusal(profile, tasks.map { it.value }, profiles.map { it.value }, canWriteSettings)
    }
}
