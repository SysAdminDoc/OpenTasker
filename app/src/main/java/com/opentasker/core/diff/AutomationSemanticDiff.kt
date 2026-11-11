package com.opentasker.core.diff

import com.opentasker.core.actions.ActionArgumentSensitivity
import com.opentasker.core.model.ActionSpec
import com.opentasker.core.model.ContextExpressionNode
import com.opentasker.core.model.ContextSpec
import com.opentasker.core.model.Profile
import com.opentasker.core.model.Scene
import com.opentasker.core.model.SceneElement
import com.opentasker.core.model.Task
import com.opentasker.core.model.Variable
import com.opentasker.core.transfer.OpenTaskerBundle

enum class SemanticDiffEntity {
    PROFILE,
    TASK,
    SCENE,
    VARIABLE,
}

enum class SemanticDiffKind {
    ADDED,
    REMOVED,
    CHANGED,
}

data class SemanticDiffChange(
    val kind: SemanticDiffKind,
    val path: String,
    val before: String? = null,
    val after: String? = null,
)

data class SemanticDiffEntry(
    val entity: SemanticDiffEntity,
    val name: String,
    val changes: List<SemanticDiffChange>,
    val flowNodeKeys: Set<String> = emptySet(),
) {
    val isChanged: Boolean get() = changes.isNotEmpty()
}

data class SemanticDiffDocument(
    val entries: List<SemanticDiffEntry> = emptyList(),
) {
    val changes: List<SemanticDiffChange> get() = entries.flatMap { it.changes }
    val changeCount: Int get() = changes.size
    val flowNodeKeys: Set<String> get() = entries.flatMapTo(linkedSetOf()) { it.flowNodeKeys }
    val isEmpty: Boolean get() = entries.none(SemanticDiffEntry::isChanged)

    /**
     * How many records of each kind changed, in declaration order, omitting kinds with none. The
     * summary used to count every entry as a profile, so a bundle of three profiles and three tasks
     * read "across 6 profiles".
     */
    val changedEntityCounts: List<Pair<SemanticDiffEntity, Int>>
        get() = SemanticDiffEntity.entries.mapNotNull { entity ->
            entries.count { it.entity == entity && it.isChanged }
                .takeIf { it > 0 }
                ?.let { entity to it }
        }
}

/**
 * Typed, display-safe diffs for automation records. IDs and storage JSON are deliberately not
 * part of the comparison: an import can remap them while preserving the user's automation.
 */
object AutomationSemanticDiff {
    fun compareTask(
        before: Task?,
        after: Task?,
        beforeTaskNames: Map<Long, String> = emptyMap(),
        afterTaskNames: Map<Long, String> = beforeTaskNames,
    ): SemanticDiffEntry? {
        val task = after ?: before ?: return null
        val changes = mutableListOf<SemanticDiffChange>()
        when {
            before == null -> {
                changes += change(SemanticDiffKind.ADDED, "Task", after!!.name)
                appendTaskFields(changes, after, SemanticDiffKind.ADDED, afterTaskNames)
                compareActions(changes, emptyList(), after.actions, afterTaskNames, afterTaskNames, SemanticDiffKind.ADDED)
            }
            after == null -> {
                changes += change(SemanticDiffKind.REMOVED, "Task", before.name)
                appendTaskFields(changes, before, SemanticDiffKind.REMOVED, beforeTaskNames)
                compareActions(changes, before.actions, emptyList(), beforeTaskNames, beforeTaskNames, SemanticDiffKind.REMOVED)
            }
            else -> {
                compare(changes, "Name", before.name, after.name)
                compare(changes, "Priority", before.priority, after.priority)
                compare(changes, "Collision mode", before.collisionMode, after.collisionMode)
                compareActions(changes, before.actions, after.actions, beforeTaskNames, afterTaskNames)
            }
        }
        return entry(
            entity = SemanticDiffEntity.TASK,
            name = task.name,
            changes = changes,
            flowNodeKeys = flowKeysForTask(before, after, changes),
        )
    }

    fun compareProfile(
        before: Profile?,
        after: Profile?,
        beforeTaskNames: Map<Long, String> = emptyMap(),
        afterTaskNames: Map<Long, String> = beforeTaskNames,
    ): SemanticDiffEntry? {
        val profile = after ?: before ?: return null
        val changes = mutableListOf<SemanticDiffChange>()
        when {
            before == null -> {
                changes += change(SemanticDiffKind.ADDED, "Profile", after!!.name)
                appendProfileFields(changes, after, SemanticDiffKind.ADDED, afterTaskNames)
                compareContexts(changes, emptyList(), after.contexts, emptyMap(), afterTaskNames, SemanticDiffKind.ADDED)
                compareExpression(changes, null, after.contextExpression, SemanticDiffKind.ADDED)
            }
            after == null -> {
                changes += change(SemanticDiffKind.REMOVED, "Profile", before.name)
                appendProfileFields(changes, before, SemanticDiffKind.REMOVED, beforeTaskNames)
                compareContexts(changes, before.contexts, emptyList(), beforeTaskNames, emptyMap(), SemanticDiffKind.REMOVED)
                compareExpression(changes, before.contextExpression, null, SemanticDiffKind.REMOVED)
            }
            else -> {
                compare(changes, "Name", before.name, after.name)
                compare(changes, "Enabled", before.enabled, after.enabled)
                compareReference(changes, "Enter task", before.enterTaskId, after.enterTaskId, beforeTaskNames, afterTaskNames)
                compareReference(changes, "Exit task", before.exitTaskId, after.exitTaskId, beforeTaskNames, afterTaskNames)
                compare(changes, "Cooldown", before.cooldownSec, after.cooldownSec)
                compare(changes, "Automation mode", before.automationMode, after.automationMode)
                compare(changes, "Group", before.group, after.group)
                compare(changes, "Risk acknowledgement", before.requiresRiskAcknowledgement, after.requiresRiskAcknowledgement)
                compare(changes, "Priority", before.priority, after.priority)
                compare(changes, "Grace period", before.gracePeriodSec, after.gracePeriodSec)
                compare(changes, "Lifetime", before.lifetime, after.lifetime)
                compare(changes, "Expiry", before.expiresAtMs, after.expiresAtMs)
                compare(changes, "Maximum active executions", before.maxActiveExecutions, after.maxActiveExecutions)
                compare(changes, "Burst limit", before.burstLimit, after.burstLimit)
                compare(changes, "Overflow policy", before.overflowPolicy, after.overflowPolicy)
                compareReference(changes, "Fallback task", before.fallbackTaskId, after.fallbackTaskId, beforeTaskNames, afterTaskNames)
                compareContexts(changes, before.contexts, after.contexts, beforeTaskNames, afterTaskNames)
                compareExpression(changes, before.contextExpression, after.contextExpression)
            }
        }
        return entry(
            entity = SemanticDiffEntity.PROFILE,
            name = profile.name,
            changes = changes,
            flowNodeKeys = flowKeysForProfile(before, after, changes),
        )
    }

    fun compareScene(
        before: Scene?,
        after: Scene?,
        beforeTaskNames: Map<Long, String> = emptyMap(),
        afterTaskNames: Map<Long, String> = beforeTaskNames,
    ): SemanticDiffEntry? {
        val scene = after ?: before ?: return null
        val changes = mutableListOf<SemanticDiffChange>()
        when {
            before == null -> {
                changes += change(SemanticDiffKind.ADDED, "Scene", after!!.name)
                appendSceneFields(changes, after, SemanticDiffKind.ADDED)
                compareElements(changes, emptyList(), after.elements, emptyMap(), afterTaskNames, SemanticDiffKind.ADDED)
            }
            after == null -> {
                changes += change(SemanticDiffKind.REMOVED, "Scene", before.name)
                appendSceneFields(changes, before, SemanticDiffKind.REMOVED)
                compareElements(changes, before.elements, emptyList(), beforeTaskNames, emptyMap(), SemanticDiffKind.REMOVED)
            }
            else -> {
                compare(changes, "Name", before.name, after.name)
                compare(changes, "Width", before.widthDp, after.widthDp)
                compare(changes, "Height", before.heightDp, after.heightDp)
                compareElements(changes, before.elements, after.elements, beforeTaskNames, afterTaskNames)
            }
        }
        return entry(SemanticDiffEntity.SCENE, scene.name, changes)
    }

    fun compareVariable(before: Variable?, after: Variable?): SemanticDiffEntry? {
        val variable = after ?: before ?: return null
        val changes = mutableListOf<SemanticDiffChange>()
        when {
            before == null -> {
                changes += change(SemanticDiffKind.ADDED, "Variable", after!!.name)
                appendVariableFields(changes, after, SemanticDiffKind.ADDED)
            }
            after == null -> {
                changes += change(SemanticDiffKind.REMOVED, "Variable", before.name)
                appendVariableFields(changes, before, SemanticDiffKind.REMOVED)
            }
            else -> {
                compare(changes, "Name", before.name, after.name)
                compare(changes, "Global", before.isGlobal, after.isGlobal)
                compare(changes, "Secret", before.isSecret, after.isSecret)
                compare(
                    changes,
                    "Value",
                    maskVariable(before),
                    maskVariable(after),
                    rawBefore = before.value,
                    rawAfter = after.value,
                )
            }
        }
        return entry(SemanticDiffEntity.VARIABLE, variable.name, changes)
    }

    /** Compares an import against decoded records already in the workspace by stable name/id. */
    fun compareBundle(
        bundle: OpenTaskerBundle,
        existingTasks: List<Task>,
        existingProfiles: List<Profile>,
        existingVariables: List<Variable>,
        existingScenes: List<Scene>,
        projectIdMap: Map<Long, Long> = emptyMap(),
    ): SemanticDiffDocument {
        val existingTaskNames = existingTasks.associate { it.id to it.name }
        val importedTaskNames = bundle.tasks.associate { it.id to it.name }
        val entries = buildList {
            bundle.tasks.forEach { imported ->
                val existingProjectId = projectIdMap[imported.projectId] ?: imported.projectId
                val existing = match(existingTasks, imported.id, existingProjectId, imported.name) { Triple(it.id, it.projectId, it.name) }
                compareTask(existing, imported, existingTaskNames, importedTaskNames)?.let(::add)
            }
            bundle.profiles.forEach { imported ->
                val existingProjectId = projectIdMap[imported.projectId] ?: imported.projectId
                val existing = match(existingProfiles, imported.id, existingProjectId, imported.name) { Triple(it.id, it.projectId, it.name) }
                val normalizedImported = imported.copy(enabled = false, requiresRiskAcknowledgement = true, lifetimeConsumed = false)
                compareProfile(existing, normalizedImported, existingTaskNames, importedTaskNames)?.let(::add)
            }
            bundle.scenes.forEach { imported ->
                val existingProjectId = projectIdMap[imported.projectId] ?: imported.projectId
                val existing = match(existingScenes, imported.id, existingProjectId, imported.name) { Triple(it.id, it.projectId, it.name) }
                compareScene(existing, imported, existingTaskNames, importedTaskNames)?.let(::add)
            }
            bundle.variables.forEach { imported ->
                val existingProjectId = projectIdMap[imported.projectId] ?: imported.projectId
                val existing = existingVariables.firstOrNull {
                    it.projectId == existingProjectId && it.name.equals(imported.name, ignoreCase = true)
                }
                compareVariable(existing, imported)?.let(::add)
            }
        }
        return SemanticDiffDocument(entries)
    }

    private fun appendTaskFields(
        changes: MutableList<SemanticDiffChange>,
        task: Task,
        kind: SemanticDiffKind,
        taskNames: Map<Long, String>,
    ) {
        changes += change(kind, "Name", task.name)
        changes += change(kind, "Priority", task.priority)
        changes += change(kind, "Collision mode", task.collisionMode)
    }

    private fun appendProfileFields(
        changes: MutableList<SemanticDiffChange>,
        profile: Profile,
        kind: SemanticDiffKind,
        taskNames: Map<Long, String>,
    ) {
        changes += change(kind, "Name", profile.name)
        changes += change(kind, "Enabled", profile.enabled)
        changes += change(kind, "Enter task", reference(profile.enterTaskId, taskNames))
        changes += change(kind, "Exit task", profile.exitTaskId?.let { reference(it, taskNames) })
        changes += change(kind, "Cooldown", profile.cooldownSec)
        changes += change(kind, "Automation mode", profile.automationMode)
        changes += change(kind, "Group", profile.group)
        changes += change(kind, "Risk acknowledgement", profile.requiresRiskAcknowledgement)
        changes += change(kind, "Priority", profile.priority)
        changes += change(kind, "Grace period", profile.gracePeriodSec)
        changes += change(kind, "Lifetime", profile.lifetime)
        changes += change(kind, "Expiry", profile.expiresAtMs)
        changes += change(kind, "Maximum active executions", profile.maxActiveExecutions)
        changes += change(kind, "Burst limit", profile.burstLimit)
        changes += change(kind, "Overflow policy", profile.overflowPolicy)
        changes += change(kind, "Fallback task", profile.fallbackTaskId?.let { reference(it, taskNames) })
    }

    private fun appendSceneFields(changes: MutableList<SemanticDiffChange>, scene: Scene, kind: SemanticDiffKind) {
        changes += change(kind, "Name", scene.name)
        changes += change(kind, "Width", scene.widthDp)
        changes += change(kind, "Height", scene.heightDp)
    }

    private fun appendVariableFields(changes: MutableList<SemanticDiffChange>, variable: Variable, kind: SemanticDiffKind) {
        changes += change(kind, "Name", variable.name)
        changes += change(kind, "Global", variable.isGlobal)
        changes += change(kind, "Value", maskVariable(variable))
    }

    private fun compareActions(
        changes: MutableList<SemanticDiffChange>,
        before: List<ActionSpec>,
        after: List<ActionSpec>,
        beforeTaskNames: Map<Long, String>,
        afterTaskNames: Map<Long, String>,
        forcedKind: SemanticDiffKind? = null,
    ) {
        val count = maxOf(before.size, after.size)
        for (index in 0 until count) {
            compareAction(
                changes,
                before.getOrNull(index),
                after.getOrNull(index),
                index,
                beforeTaskNames,
                afterTaskNames,
                forcedKind,
            )
        }
    }

    private fun compareAction(
        changes: MutableList<SemanticDiffChange>,
        before: ActionSpec?,
        after: ActionSpec?,
        index: Int,
        beforeTaskNames: Map<Long, String>,
        afterTaskNames: Map<Long, String>,
        forcedKind: SemanticDiffKind?,
    ) {
        val label = "Action ${index + 1}"
        when {
            before == null && after != null -> {
                changes += change(forcedKind ?: SemanticDiffKind.ADDED, label, actionDescription(after))
                appendActionFields(changes, after, forcedKind ?: SemanticDiffKind.ADDED, afterTaskNames)
            }
            before != null && after == null -> {
                changes += change(forcedKind ?: SemanticDiffKind.REMOVED, label, actionDescription(before))
                appendActionFields(changes, before, forcedKind ?: SemanticDiffKind.REMOVED, beforeTaskNames)
            }
            before != null && after != null -> {
                compare(changes, "$label / Type", before.type, after.type)
                compare(changes, "$label / Label", before.label, after.label)
                compare(changes, "$label / Continue on error", before.continueOnError, after.continueOnError)
                compare(changes, "$label / On", before.enabled, after.enabled)
                compare(
                    changes,
                    "$label / Condition",
                    before.condition,
                    after.condition,
                    rawBefore = before.condition,
                    rawAfter = after.condition,
                )
                compareArguments(
                    changes,
                    label,
                    before,
                    after,
                    beforeTaskNames,
                    afterTaskNames,
                )
            }
        }
    }

    private fun appendActionFields(
        changes: MutableList<SemanticDiffChange>,
        action: ActionSpec,
        kind: SemanticDiffKind,
        taskNames: Map<Long, String>,
    ) {
        changes += change(kind, "Action / Type", action.type)
        changes += change(kind, "Action / Label", action.label)
        changes += change(kind, "Action / Continue on error", action.continueOnError)
        if (!action.enabled) changes += change(kind, "Action / On", false)
        changes += change(kind, "Action / Condition", action.condition)
        action.args.toSortedMap().forEach { (key, value) ->
            changes += change(kind, "Action / Argument $key", maskActionArgument(action, key, value, taskNames))
        }
    }

    private fun compareArguments(
        changes: MutableList<SemanticDiffChange>,
        label: String,
        before: ActionSpec,
        after: ActionSpec,
        beforeTaskNames: Map<Long, String>,
        afterTaskNames: Map<Long, String>,
    ) {
        val beforeArgs = normalizeTaskReferences(before.args, beforeTaskNames)
        val afterArgs = normalizeTaskReferences(after.args, afterTaskNames)
        (beforeArgs.keys + afterArgs.keys).toSortedSet().forEach { key ->
            val old = beforeArgs[key]
            val new = afterArgs[key]
            if (old == new) return@forEach
            val oldDisplay = old?.let { ActionArgumentSensitivity.maskValue(before.type, key, it, beforeArgs) }
            val newDisplay = new?.let { ActionArgumentSensitivity.maskValue(after.type, key, it, afterArgs) }
            val kind = when {
                old == null -> SemanticDiffKind.ADDED
                new == null -> SemanticDiffKind.REMOVED
                else -> SemanticDiffKind.CHANGED
            }
            changes += SemanticDiffChange(kind, "$label / Argument $key", oldDisplay, newDisplay)
        }
    }

    private fun compareContexts(
        changes: MutableList<SemanticDiffChange>,
        before: List<ContextSpec>,
        after: List<ContextSpec>,
        beforeTaskNames: Map<Long, String>,
        afterTaskNames: Map<Long, String>,
        forcedKind: SemanticDiffKind? = null,
    ) {
        val count = maxOf(before.size, after.size)
        for (index in 0 until count) {
            val old = before.getOrNull(index)
            val new = after.getOrNull(index)
            val label = "Context ${index + 1}"
            when {
                old == null && new != null -> {
                    changes += change(forcedKind ?: SemanticDiffKind.ADDED, label, new.type)
                    appendContextFields(changes, label, new, forcedKind ?: SemanticDiffKind.ADDED)
                }
                old != null && new == null -> {
                    changes += change(forcedKind ?: SemanticDiffKind.REMOVED, label, old.type)
                    appendContextFields(changes, label, old, forcedKind ?: SemanticDiffKind.REMOVED)
                }
                old != null && new != null -> {
                    compare(changes, "$label / Type", old.type, new.type)
                    compare(changes, "$label / Inverted", old.invert, new.invert)
                    compare(changes, "$label / OR group", old.orGroup, new.orGroup)
                    compareMap(changes, label, old.config, new.config, null)
                }
            }
        }
    }

    private fun appendContextFields(
        changes: MutableList<SemanticDiffChange>,
        label: String,
        context: ContextSpec,
        kind: SemanticDiffKind,
    ) {
        changes += change(kind, "$label / Type", context.type)
        changes += change(kind, "$label / Inverted", context.invert)
        changes += change(kind, "$label / OR group", context.orGroup)
        context.config.toSortedMap().forEach { (key, value) ->
            changes += change(kind, "$label / Parameter $key", maskConfig(key, value))
        }
    }

    private fun compareElements(
        changes: MutableList<SemanticDiffChange>,
        before: List<SceneElement>,
        after: List<SceneElement>,
        beforeTaskNames: Map<Long, String>,
        afterTaskNames: Map<Long, String>,
        forcedKind: SemanticDiffKind? = null,
    ) {
        val count = maxOf(before.size, after.size)
        for (index in 0 until count) {
            val old = before.getOrNull(index)
            val new = after.getOrNull(index)
            val label = "Element ${index + 1}"
            when {
                old == null && new != null -> {
                    changes += change(forcedKind ?: SemanticDiffKind.ADDED, label, new.type)
                    appendElementFields(changes, label, new, forcedKind ?: SemanticDiffKind.ADDED, afterTaskNames)
                }
                old != null && new == null -> {
                    changes += change(forcedKind ?: SemanticDiffKind.REMOVED, label, old.type)
                    appendElementFields(changes, label, old, forcedKind ?: SemanticDiffKind.REMOVED, beforeTaskNames)
                }
                old != null && new != null -> {
                    compare(changes, "$label / Type", old.type, new.type)
                    compare(changes, "$label / X", old.xDp, new.xDp)
                    compare(changes, "$label / Y", old.yDp, new.yDp)
                    compare(changes, "$label / Width", old.widthDp, new.widthDp)
                    compare(changes, "$label / Height", old.heightDp, new.heightDp)
                    compareReference(changes, "$label / Tap task", old.tapTaskId, new.tapTaskId, beforeTaskNames, afterTaskNames)
                    compareReference(changes, "$label / Long-press task", old.longPressTaskId, new.longPressTaskId, beforeTaskNames, afterTaskNames)
                    compareMap(changes, label, old.config, new.config, null)
                }
            }
        }
    }

    private fun appendElementFields(
        changes: MutableList<SemanticDiffChange>,
        label: String,
        element: SceneElement,
        kind: SemanticDiffKind,
        taskNames: Map<Long, String>,
    ) {
        changes += change(kind, "$label / Type", element.type)
        changes += change(kind, "$label / X", element.xDp)
        changes += change(kind, "$label / Y", element.yDp)
        changes += change(kind, "$label / Width", element.widthDp)
        changes += change(kind, "$label / Height", element.heightDp)
        changes += change(kind, "$label / Tap task", element.tapTaskId?.let { reference(it, taskNames) })
        changes += change(kind, "$label / Long-press task", element.longPressTaskId?.let { reference(it, taskNames) })
        element.config.toSortedMap().forEach { (key, value) ->
            changes += change(kind, "$label / Parameter $key", maskConfig(key, value))
        }
    }

    private fun compareMap(
        changes: MutableList<SemanticDiffChange>,
        label: String,
        before: Map<String, String>,
        after: Map<String, String>,
        actionType: String?,
    ) {
        (before.keys + after.keys).toSortedSet().forEach { key ->
            val old = before[key]
            val new = after[key]
            if (old == new) return@forEach
            val oldDisplay = old?.let { maskValue(actionType, key, it, before) }
            val newDisplay = new?.let { maskValue(actionType, key, it, after) }
            val kind = when {
                old == null -> SemanticDiffKind.ADDED
                new == null -> SemanticDiffKind.REMOVED
                else -> SemanticDiffKind.CHANGED
            }
            changes += SemanticDiffChange(kind, "$label / Parameter $key", oldDisplay, newDisplay)
        }
    }

    private fun compareExpression(
        changes: MutableList<SemanticDiffChange>,
        before: ContextExpressionNode?,
        after: ContextExpressionNode?,
        forcedKind: SemanticDiffKind? = null,
    ) {
        val old = before?.semanticKey()
        val new = after?.semanticKey()
        if (old != new) {
            if (forcedKind == null) compare(changes, "Context logic", old, new)
            else changes += change(forcedKind, "Context logic", if (forcedKind == SemanticDiffKind.REMOVED) old else new)
        }
    }

    private fun compareReference(
        changes: MutableList<SemanticDiffChange>,
        path: String,
        before: Long?,
        after: Long?,
        beforeTaskNames: Map<Long, String>,
        afterTaskNames: Map<Long, String>,
    ) = compare(changes, path, before?.let { reference(it, beforeTaskNames) }, after?.let { reference(it, afterTaskNames) })

    private fun compare(
        changes: MutableList<SemanticDiffChange>,
        path: String,
        before: Any?,
        after: Any?,
        rawBefore: Any? = before,
        rawAfter: Any? = after,
    ) {
        if (rawBefore != rawAfter) changes += change(SemanticDiffKind.CHANGED, path, before, after)
    }

    private fun change(kind: SemanticDiffKind, path: String, value: Any?): SemanticDiffChange =
        if (kind == SemanticDiffKind.REMOVED) SemanticDiffChange(kind, path, before = value.asDisplay())
        else SemanticDiffChange(kind, path, after = value.asDisplay())

    private fun change(kind: SemanticDiffKind, path: String, before: Any?, after: Any?): SemanticDiffChange =
        SemanticDiffChange(kind, path, before.asDisplay(), after.asDisplay())

    private fun Any?.asDisplay(): String? = when (this) {
        null -> null
        else -> toString()
    }

    private fun entry(
        entity: SemanticDiffEntity,
        name: String,
        changes: List<SemanticDiffChange>,
        flowNodeKeys: Set<String> = emptySet(),
    ): SemanticDiffEntry? = changes.takeIf(List<SemanticDiffChange>::isNotEmpty)?.let {
        SemanticDiffEntry(entity, name, it, flowNodeKeys)
    }

    private fun actionDescription(action: ActionSpec): String =
        action.label?.takeUnless(String::isBlank) ?: action.type

    private fun appendMapValue(value: String, key: String, actionType: String?, args: Map<String, String>): String =
        maskValue(actionType, key, value, args)

    private fun maskActionArgument(action: ActionSpec, key: String, value: String, taskNames: Map<Long, String>): String =
        appendMapValue(value, key, action.type, normalizeTaskReferences(action.args, taskNames))

    private fun maskConfig(key: String, value: String): String = maskValue(null, key, value, emptyMap())

    private fun maskValue(actionType: String?, key: String, value: String, args: Map<String, String>): String =
        ActionArgumentSensitivity.maskValue(actionType, key, value, args, maxLength = 64)

    private fun maskVariable(variable: Variable): String =
        if (variable.isSecret || ActionArgumentSensitivity.isSensitiveArgumentName(variable.name)) {
            ActionArgumentSensitivity.REDACTED
        } else {
            maskValue(null, "value", variable.value, emptyMap())
        }

    private fun reference(id: Long, taskNames: Map<Long, String>): String = taskNames[id]?.let { "\"$it\"" } ?: "#$id"

    private fun normalizeTaskReferences(args: Map<String, String>, taskNames: Map<Long, String>): Map<String, String> =
        args.mapValues { (key, value) ->
            if (!key.contains("task", ignoreCase = true)) value
            else value.toLongOrNull()?.let { taskNames[it] ?: "#$value" } ?: value
        }

    private fun flowKeysForTask(before: Task?, after: Task?, changes: List<SemanticDiffChange>): Set<String> {
        val task = before ?: after ?: return emptySet()
        if (changes.isEmpty()) return emptySet()
        val keys = linkedSetOf("task:${task.id}")
        changes.mapNotNullTo(keys) { change ->
            Regex("Action (\\d+)").find(change.path)?.groupValues?.getOrNull(1)?.let { index ->
                "task:${task.id}:action:${index.toInt() - 1}"
            }
        }
        return keys
    }

    private fun flowKeysForProfile(before: Profile?, after: Profile?, changes: List<SemanticDiffChange>): Set<String> {
        val profile = before ?: after ?: return emptySet()
        if (changes.isEmpty()) return emptySet()
        val keys = linkedSetOf("profile:${profile.id}")
        if (before != null && after != null) {
            val referenceChanged = before.enterTaskId != after.enterTaskId ||
                before.exitTaskId != after.exitTaskId ||
                before.fallbackTaskId != after.fallbackTaskId
            if (referenceChanged) {
                listOfNotNull(before.enterTaskId, before.exitTaskId, before.fallbackTaskId, after.enterTaskId, after.exitTaskId, after.fallbackTaskId)
                    .forEach { keys += "task:$it" }
            }
        }
        changes.mapNotNullTo(keys) { change ->
            Regex("Context (\\d+)").find(change.path)?.groupValues?.getOrNull(1)?.let { index ->
                "profile:${profile.id}:context:${index.toInt() - 1}"
            }
        }
        return keys
    }

    private fun ContextExpressionNode.semanticKey(): String = buildString {
        append(if (invert) "!" else "")
        if (isLeaf()) append("leaf:").append(contextIndex)
        else append(operator).append('(').append(children.joinToString(",") { it.semanticKey() }).append(')')
    }

    private fun <T> match(
        values: List<T>,
        id: Long,
        projectId: Long,
        name: String,
        key: (T) -> Triple<Long, Long, String>,
    ): T? = values.firstOrNull { value ->
        val candidate = key(value)
        candidate.first == id && candidate.second == projectId && id > 0L
    } ?: values.firstOrNull { value ->
        val candidate = key(value)
        candidate.second == projectId && candidate.third.equals(name, ignoreCase = true)
    }
}
