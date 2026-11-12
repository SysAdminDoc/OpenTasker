package com.opentasker.core.references

import com.opentasker.core.actions.NotificationTaskBindings
import com.opentasker.core.engine.STOP_TASK_ACTION_ID
import com.opentasker.core.engine.SUB_TASK_ACTION_ID
import com.opentasker.core.engine.SUB_TASK_REF_KEYS
import com.opentasker.core.model.ActionSpec
import com.opentasker.core.model.Profile
import com.opentasker.core.model.Scene
import com.opentasker.core.model.Task
import com.opentasker.core.model.Variable
import com.opentasker.core.model.VariableNamePolicy

/** What to do with the references that point at a task being removed or re-identified. */
sealed interface ReferenceResolution {
    /** Refuse the operation while any dependent object still points at the task. */
    data object Block : ReferenceResolution

    /** Point every dependent object at [replacement] instead. */
    data class Reassign(val replacement: Task) : ReferenceResolution

    /**
     * Drop optional references (profile exit task, scene gestures, notification buttons,
     * `task.run` arguments). Profile *enter* tasks cannot be cleared and are reported instead.
     */
    data object Clear : ReferenceResolution
}

/** The objects a rewrite actually changed, plus anything it could not legally resolve. */
data class ReferenceRewrite(
    val profiles: List<Profile> = emptyList(),
    val tasks: List<Task> = emptyList(),
    val scenes: List<Scene> = emptyList(),
    /** References the chosen resolution cannot satisfy; a non-empty list means "do not commit". */
    val blocked: List<TaskReference> = emptyList(),
    /** New global fallback id when the per-install setting was changed by this rewrite. */
    val globalFallbackTaskId: Long? = null,
    val globalFallbackChanged: Boolean = false,
) {
    val isEmpty: Boolean get() = profiles.isEmpty() && tasks.isEmpty() && scenes.isEmpty() && !globalFallbackChanged
    val canCommit: Boolean get() = blocked.isEmpty()
}

/** Pure result of a variable rename or deletion guard. */
data class VariableReferenceRewrite(
    val profiles: List<Profile> = emptyList(),
    val tasks: List<Task> = emptyList(),
    val scenes: List<Scene> = emptyList(),
    val blocked: List<VariableReference> = emptyList(),
) {
    val isEmpty: Boolean get() = profiles.isEmpty() && tasks.isEmpty() && scenes.isEmpty()
    val canCommit: Boolean get() = blocked.isEmpty()
}

/**
 * The single rewriter for task references. Deletion, reassignment, and bundle-import id remapping
 * all funnel through [retarget]/[remapIds] so no surface can invent a rule the others don't share
 * — that divergence is exactly what left `task.run`, notification buttons, and scene gestures
 * dangling or silently retargeted while profile columns were checked.
 *
 * Every function is pure: it returns only the changed objects so the caller can persist them in
 * one transaction.
 */
object AutomationReferenceRewriter {

    /** Rebinds references to [original] inside a copied task to that copy's stable id. */
    fun remapDuplicateSelfReferences(original: Task, duplicate: Task): Task = duplicate.copy(
        actions = duplicate.actions.map { action ->
            action.retargetTaskArgs(original, duplicate.id)
        },
    )

    /** Returns every dependent site that would break if [target] were deleted. */
    fun guardVariableDeletion(
        target: Variable,
        profiles: List<Profile> = emptyList(),
        tasks: List<Task> = emptyList(),
        scenes: List<Scene> = emptyList(),
    ): VariableReferenceRewrite = VariableReferenceRewrite(
        blocked = AutomationReferenceIndex.referencesTo(
            target,
            profiles = profiles,
            tasks = tasks,
            scenes = scenes,
        ),
    )

    /**
     * Rewrites every reference to [target] while retaining local/global scope semantics. The
     * returned objects are the only records that need to be persisted by the caller.
     */
    fun renameVariable(
        target: Variable,
        replacementName: String,
        profiles: List<Profile> = emptyList(),
        tasks: List<Task> = emptyList(),
        scenes: List<Scene> = emptyList(),
    ): VariableReferenceRewrite {
        val oldName = requireNotNull(VariableNamePolicy.normalizeForScope(target.name, target.isGlobal)) {
            "Invalid variable name '${target.name}'."
        }
        val newName = requireNotNull(VariableNamePolicy.normalizeForScope(replacementName, target.isGlobal)) {
            "Invalid replacement variable name '$replacementName'."
        }
        if (oldName == newName) return VariableReferenceRewrite()

        val references = AutomationReferenceIndex.referencesTo(
            target,
            profiles = profiles,
            tasks = tasks,
            scenes = scenes,
        )
        if (references.isEmpty()) return VariableReferenceRewrite()

        val changedProfiles = profiles.mapNotNull { profile ->
            if (profile.projectId != target.projectId) return@mapNotNull null
            val contexts = profile.contexts.map { context ->
                context.copy(
                    config = context.config.mapValues { (_, value) ->
                        VariableReferenceScanner.rewrite(value, target, newName)
                    },
                )
            }
            profile.copy(contexts = contexts).takeIf { it != profile }
        }
        val changedTasks = tasks.mapNotNull { task ->
            if (task.projectId != target.projectId) return@mapNotNull null
            val actions = task.actions.map { action ->
                action.copy(
                    args = action.args.mapValues { (_, value) ->
                        VariableReferenceScanner.rewrite(value, target, newName)
                    },
                    condition = action.condition?.let { condition ->
                        VariableReferenceScanner.rewrite(condition, target, newName)
                    },
                )
            }
            task.copy(actions = actions).takeIf { it != task }
        }
        val changedScenes = scenes.mapNotNull { scene ->
            if (scene.projectId != target.projectId) return@mapNotNull null
            val elements = scene.elements.map { element ->
                element.copy(
                    config = element.config.mapValues { (_, value) ->
                        VariableReferenceScanner.rewrite(value, target, newName)
                    },
                )
            }
            scene.copy(elements = elements).takeIf { it != scene }
        }
        return VariableReferenceRewrite(changedProfiles, changedTasks, changedScenes)
    }

    /**
     * Applies [resolution] to every reference pointing at [target].
     *
     * The task being deleted is excluded from the scan of [tasks] — its own actions go away with
     * it, and a self-reference should not block its own removal.
     */
    fun retarget(
        target: Task,
        resolution: ReferenceResolution,
        profiles: List<Profile> = emptyList(),
        tasks: List<Task> = emptyList(),
        scenes: List<Scene> = emptyList(),
        globalFallbackTaskId: Long? = null,
    ): ReferenceRewrite {
        val others = tasks.filterNot { it.id == target.id }
        val references = AutomationReferenceIndex.referencesTo(
            AutomationReferenceIndex.build(profiles, others, scenes, globalFallbackTaskId),
            target,
        )
        if (references.isEmpty()) return ReferenceRewrite()

        return when (resolution) {
            ReferenceResolution.Block -> ReferenceRewrite(blocked = references)

            is ReferenceResolution.Reassign -> {
                if (resolution.replacement.id == target.id) {
                    return ReferenceRewrite(blocked = references)
                }
                rewrite(target, profiles, others, scenes, globalFallbackTaskId) { resolution.replacement.id }
            }

            ReferenceResolution.Clear -> {
                val required = references.filter { it.isRequired }
                if (required.isNotEmpty()) return ReferenceRewrite(blocked = required)
                rewrite(target, profiles, others, scenes, globalFallbackTaskId) { null }
            }
        }
    }

    /**
     * Converts every *name*-based reference to [target] into its stable id.
     *
     * Call this before renaming a task: `task.run` targets and legacy notification bindings match
     * on the task's name, so a rename silently breaks them (or, worse, retargets them at whatever
     * task later takes the old name). Pass the task with its **old** name — the scan has to match
     * what the references still say.
     */
    fun stabilizeNameReferences(
        target: Task,
        profiles: List<Profile> = emptyList(),
        tasks: List<Task> = emptyList(),
        scenes: List<Scene> = emptyList(),
    ): ReferenceRewrite {
        val others = tasks.filterNot { it.id == target.id }
        val nameReferences = AutomationReferenceIndex
            .referencesTo(AutomationReferenceIndex.build(profiles, others, scenes), target)
            .filter { it.ref is TaskRef.ByName }
        if (nameReferences.isEmpty()) return ReferenceRewrite()
        return rewrite(target, profiles, others, scenes, null) { target.id }
    }

    /**
     * Pins the references [renamed] makes to its own old name to its id. [stabilizeNameReferences]
     * leaves the renamed task out, but a Stop Task step naming the task it's in is a real self
     * reference, and it would otherwise go on naming a task that no longer exists.
     */
    fun stabilizeOwnNameReferences(previous: Task, renamed: Task): Task =
        renamed.copy(actions = renamed.actions.map { action -> action.retargetTaskArgs(previous, previous.id) })

    /**
     * Rewrites every task reference through [idMap], used when a bundle's tasks are inserted under
     * fresh ids. References that [idMap] does not cover are left untouched so an import can report
     * them rather than silently retargeting a stranger's task.
     */
    fun remapIds(
        idMap: Map<Long, Long>,
        profiles: List<Profile> = emptyList(),
        tasks: List<Task> = emptyList(),
        scenes: List<Scene> = emptyList(),
        globalFallbackTaskId: Long? = null,
    ): ReferenceRewrite {
        if (idMap.isEmpty()) return ReferenceRewrite()

        val changedProfiles = profiles.mapNotNull { profile ->
            val enter = idMap[profile.enterTaskId] ?: profile.enterTaskId
            val exit = profile.exitTaskId?.let { idMap[it] ?: it }
            val fallback = profile.fallbackTaskId?.let { idMap[it] ?: it }
            profile.copy(enterTaskId = enter, exitTaskId = exit, fallbackTaskId = fallback)
                .takeIf { it != profile }
        }
        val changedTasks = tasks.mapNotNull { task ->
            val actions = task.actions.map { action -> action.mapTaskArgs { id -> idMap[id] ?: id } }
            task.copy(actions = actions).takeIf { it != task }
        }
        val changedScenes = scenes.mapNotNull { scene ->
            val elements = scene.elements.map { element ->
                element.copy(
                    tapTaskId = element.tapTaskId?.let { idMap[it] ?: it },
                    longPressTaskId = element.longPressTaskId?.let { idMap[it] ?: it },
                )
            }
            scene.copy(elements = elements).takeIf { it != scene }
        }
        val remappedGlobal = globalFallbackTaskId?.let { idMap[it] ?: it }
        return ReferenceRewrite(
            profiles = changedProfiles,
            tasks = changedTasks,
            scenes = changedScenes,
            globalFallbackTaskId = remappedGlobal,
            globalFallbackChanged = remappedGlobal != globalFallbackTaskId,
        )
    }

    /** [newId] `null` clears the reference; a value retargets it. */
    private fun rewrite(
        target: Task,
        profiles: List<Profile>,
        tasks: List<Task>,
        scenes: List<Scene>,
        globalFallbackTaskId: Long?,
        newId: () -> Long?,
    ): ReferenceRewrite {
        val replacement = newId()

        val changedProfiles = profiles.mapNotNull { profile ->
            val enterMatches = profile.enterTaskId == target.id
            val exitMatches = profile.exitTaskId == target.id
            val fallbackMatches = profile.fallbackTaskId == target.id
            if (!enterMatches && !exitMatches && !fallbackMatches) return@mapNotNull null
            profile.copy(
                enterTaskId = if (enterMatches) replacement ?: profile.enterTaskId else profile.enterTaskId,
                exitTaskId = if (exitMatches) replacement else profile.exitTaskId,
                fallbackTaskId = if (fallbackMatches) replacement else profile.fallbackTaskId,
            ).takeIf { it != profile }
        }

        val changedTasks = tasks.mapNotNull { task ->
            val actions = task.actions.map { action -> action.retargetTaskArgs(target, replacement) }
            task.copy(actions = actions).takeIf { it != task }
        }

        val changedScenes = scenes.mapNotNull { scene ->
            val elements = scene.elements.map { element ->
                element.copy(
                    tapTaskId = if (element.tapTaskId == target.id) replacement else element.tapTaskId,
                    longPressTaskId = if (element.longPressTaskId == target.id) replacement else element.longPressTaskId,
                )
            }
            scene.copy(elements = elements).takeIf { it != scene }
        }

        val globalMatches = globalFallbackTaskId == target.id
        return ReferenceRewrite(
            profiles = changedProfiles,
            tasks = changedTasks,
            scenes = changedScenes,
            globalFallbackTaskId = if (globalMatches) replacement else globalFallbackTaskId,
            globalFallbackChanged = globalMatches,
        )
    }

    /** Applies [mapId] to every numeric task id an action stores; name references are left alone. */
    private fun ActionSpec.mapTaskArgs(mapId: (Long) -> Long): ActionSpec {
        val updated = args.toMutableMap()
        var changed = false

        if (type == SUB_TASK_ACTION_ID || type == STOP_TASK_ACTION_ID) {
            SUB_TASK_REF_KEYS.forEach { key ->
                val id = updated[key]?.trim()?.toLongOrNull()?.takeIf { it > 0 } ?: return@forEach
                val mapped = mapId(id)
                if (mapped != id) {
                    updated[key] = mapped.toString()
                    changed = true
                }
            }
        }

        for (button in 1..NotificationTaskBindings.BUTTON_COUNT) {
            val key = NotificationTaskBindings.taskIdKey(button)
            val id = updated[key]?.trim()?.toLongOrNull()?.takeIf { it > 0 } ?: continue
            val mapped = mapId(id)
            if (mapped != id) {
                updated[key] = mapped.toString()
                changed = true
            }
        }

        return if (changed) copy(args = updated) else this
    }

    /**
     * Retargets or clears the references this action holds to [target]. Clearing a notification
     * button drops its label too, so a button cannot survive with nothing to run; clearing a
     * `task.run` or `task.stop` target leaves the action present but unbound, where the runner
     * already fails closed with "task.run requires a 'task'" (or task.stop's same message).
     */
    private fun ActionSpec.retargetTaskArgs(target: Task, replacement: Long?): ActionSpec {
        val updated = args.toMutableMap()
        var changed = false

        if (type == SUB_TASK_ACTION_ID || type == STOP_TASK_ACTION_ID) {
            SUB_TASK_REF_KEYS.forEach { key ->
                val raw = updated[key]?.trim().orEmpty()
                if (raw.isBlank() || !raw.refersTo(target)) return@forEach
                if (replacement == null) updated.remove(key) else updated[key] = replacement.toString()
                changed = true
            }
        }

        for (button in 1..NotificationTaskBindings.BUTTON_COUNT) {
            val idKey = NotificationTaskBindings.taskIdKey(button)
            val nameKey = NotificationTaskBindings.legacyTaskNameKey(button)
            val labelKey = "button${button}_label"
            val bound = listOf(idKey, nameKey).any { key ->
                updated[key]?.trim()?.takeIf(String::isNotBlank)?.refersTo(target) == true
            }
            if (!bound) continue
            updated.remove(nameKey)
            if (replacement == null) {
                updated.remove(idKey)
                updated.remove(labelKey)
            } else {
                updated[idKey] = replacement.toString()
            }
            changed = true
        }

        return if (changed) copy(args = updated) else this
    }

    private fun String.refersTo(target: Task): Boolean {
        val id = toLongOrNull()
        return if (id != null) id == target.id else equals(target.name, ignoreCase = true)
    }
}

/** Convenience for previews: a short human-readable location for a reference. */
fun TaskReference.describe(): String = when (val site = site) {
    is TaskReferenceSite.ProfileEnterTask -> "Profile \"${site.ownerName}\" enter task"
    is TaskReferenceSite.ProfileExitTask -> "Profile \"${site.ownerName}\" exit task"
    is TaskReferenceSite.ProfileFallbackTask -> "Profile \"${site.ownerName}\" fallback task"
    TaskReferenceSite.GlobalFallbackTask -> "Global settings fallback task"
    is TaskReferenceSite.SubTaskRun -> "Task \"${site.ownerName}\" step ${site.actionIndex + 1} (run sub-task)"
    is TaskReferenceSite.StopTaskStep -> "Task \"${site.ownerName}\" step ${site.actionIndex + 1} (stop task)"
    is TaskReferenceSite.NotificationButton ->
        "Task \"${site.ownerName}\" step ${site.actionIndex + 1} (notification button ${site.buttonIndex})"
    is TaskReferenceSite.SceneTap -> "Scene \"${site.ownerName}\" element ${site.elementIndex + 1} (tap)"
    is TaskReferenceSite.SceneLongPress -> "Scene \"${site.ownerName}\" element ${site.elementIndex + 1} (long press)"
}

fun VariableReference.describe(): String = when (val site = site) {
    is VariableReferenceSite.ProfileContextBinding ->
        "Profile \"${site.ownerName}\" context ${site.contextIndex + 1} binding \"${site.configKey}\""
    is VariableReferenceSite.TaskActionArgument ->
        "Task \"${site.ownerName}\" step ${site.actionIndex + 1} argument \"${site.argKey}\""
    is VariableReferenceSite.TaskCondition ->
        "Task \"${site.ownerName}\" step ${site.actionIndex + 1} condition"
    is VariableReferenceSite.SceneBinding ->
        "Scene \"${site.ownerName}\" element ${site.elementIndex + 1} binding \"${site.configKey}\""
}
