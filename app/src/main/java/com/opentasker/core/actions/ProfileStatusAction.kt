package com.opentasker.core.actions

import android.provider.Settings
import com.opentasker.app.OpenTaskerApp_NoHilt
import com.opentasker.core.capabilities.ProfileTurnOnChecks
import com.opentasker.core.engine.ActionContext
import com.opentasker.core.engine.ActionResult
import com.opentasker.core.storage.ProfileEntity

/**
 * Tasker's Profile Status: turns a profile on or off, or flips it, picked by name.
 *
 * It won't turn on a profile the app wouldn't turn on from its list ([ProfileTurnOnChecks]), since
 * those checks are where the user sees what a profile can do before it runs. Turning one off is
 * never refused.
 */
class ProfileStatusAction : DeclaredAction(ActionCatalog.require("profile.set")) {

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val name = args["profile"]?.trim().orEmpty()
        if (name.isEmpty()) return ActionResult.Failure("missing profile argument")
        val state = args["state"]?.trim()?.lowercase().orEmpty()
        if (state !in ProfileSwitch.STATES) return ActionResult.Failure("invalid state: $state (use on, off or toggle)")
        val db = OpenTaskerApp_NoHilt.db
        val dao = db.profileDao()
        val saved = dao.getAll()
        val profile = when (val found = ProfileSwitch.find(name, saved)) {
            is ProfileSwitch.Found.One -> found.profile
            is ProfileSwitch.Found.Refused -> return ActionResult.Failure(found.message)
        }
        val enabled = ProfileSwitch.next(state, profile.enabled)
        if (enabled && !profile.enabled) {
            val profiles = saved.map { it.toDomainDecodeResult() }
            val tasks = db.taskDao().getAll().map { it.toDomainDecodeResult() }
            if (profiles.any { it.issue != null } || tasks.any { it.issue != null }) {
                return ActionResult.Failure("profile '${profile.name}' wasn't turned on, since a saved profile or task couldn't be read to check it")
            }
            val allProfiles = profiles.map { it.value }
            ProfileTurnOnChecks.refusal(
                profile = allProfiles.first { it.id == profile.id },
                tasks = tasks.map { it.value },
                otherProfiles = allProfiles,
                canWriteSettings = Settings.System.canWrite(ctx.app),
            )?.let { return ActionResult.Failure(it) }
        }
        if (dao.setEnabled(profile.id, enabled) == 0) {
            return ActionResult.Failure("profile '${profile.name}' was removed or now needs its review, so it wasn't switched")
        }
        ctx.logger("Profile '${profile.name}' turned ${if (enabled) "on" else "off"}")
        return ActionResult.Success
    }
}

/** The parts of [ProfileStatusAction] that don't touch the database. */
internal object ProfileSwitch {

    sealed interface Found {
        data class One(val profile: ProfileEntity) : Found
        data class Refused(val message: String) : Found
    }

    val STATES = setOf("on", "off", "toggle")

    /** Whether the profile ends up on. [state] is one of [STATES]. */
    fun next(state: String, current: Boolean): Boolean = when (state) {
        "on" -> true
        "off" -> false
        else -> !current
    }

    /**
     * An exact name wins, then one that differs only in case. Two profiles sharing the name the
     * step gives are refused rather than guessed between.
     */
    fun find(name: String, profiles: List<ProfileEntity>): Found {
        val wanted = name.trim()
        val exact = profiles.filter { it.name.trim() == wanted }
        val matches = exact.ifEmpty { profiles.filter { it.name.trim().equals(wanted, ignoreCase = true) } }
        return when (matches.size) {
            0 -> Found.Refused("no profile is named '$wanted'")
            1 -> Found.One(matches.single())
            else -> Found.Refused("${matches.size} profiles are named '$wanted', so it isn't clear which one to switch")
        }
    }
}
