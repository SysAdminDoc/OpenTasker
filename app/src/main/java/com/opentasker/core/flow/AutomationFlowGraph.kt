package com.opentasker.core.flow

import com.opentasker.core.actions.ActionArgumentSensitivity
import com.opentasker.core.actions.ActionMetadataRegistry
import com.opentasker.core.capabilities.AutomationLint
import com.opentasker.core.capabilities.AutomationLintFinding
import com.opentasker.core.actions.ResolvedActionOutput
import com.opentasker.core.actions.resolveOutputs
import com.opentasker.core.capabilities.AutomationLintStrings
import com.opentasker.core.model.ActionSpec
import com.opentasker.core.model.AutomationInvariant
import com.opentasker.core.model.ContextSpec
import com.opentasker.core.model.ContextType
import com.opentasker.core.model.Profile
import com.opentasker.core.model.Task

data class AutomationFlowGraph(
    val profileId: Long,
    val title: String,
    val nodes: List<AutomationFlowNode>,
    val edges: List<AutomationFlowEdge>,
    val warnings: List<String> = emptyList(),
    val strings: AutomationFlowStrings = AutomationFlowStrings.English,
    val lintFindings: List<AutomationLintFinding> = emptyList(),
) {
    val contextNodes: List<AutomationFlowNode>
        get() = nodes.filter { it.kind == AutomationFlowNodeKind.CONTEXT }

    val enterTaskNode: AutomationFlowNode?
        get() = nodes.firstOrNull { it.kind == AutomationFlowNodeKind.ENTER_TASK }

    val exitTaskNode: AutomationFlowNode?
        get() = nodes.firstOrNull { it.kind == AutomationFlowNodeKind.EXIT_TASK }

    fun actionNodesFor(taskNodeId: String): List<AutomationFlowNode> {
        val orderedIds = buildList {
            var cursor = edges.firstOrNull { it.fromId == taskNodeId }?.toId
            while (cursor != null) {
                val node = nodes.firstOrNull { it.id == cursor } ?: break
                if (node.kind != AutomationFlowNodeKind.ACTION) break
                add(node.id)
                cursor = edges.firstOrNull { it.fromId == node.id }?.toId
            }
        }
        return orderedIds.mapNotNull { id -> nodes.firstOrNull { it.id == id } }
    }

    fun incomingEdgeLabel(nodeId: String): String? =
        edges.firstOrNull { it.toId == nodeId }?.label

    fun accessibilitySummary(): String {
        val actionCount = nodes.count { it.kind == AutomationFlowNodeKind.ACTION }
        val enterTask = enterTaskNode?.title ?: strings.missingTaskTitle("enter")
        val exitTask = exitTaskNode?.title ?: strings.noExitTask()
        val warningText = if (warnings.isEmpty()) "no warnings" else "${warnings.size} warning${plural(warnings.size)}"
        return strings.accessibilitySummary(title, contextNodes.size, actionCount, enterTask, exitTask, warningText)
    }
}

data class AutomationFlowNode(
    val id: String,
    val kind: AutomationFlowNodeKind,
    val title: String,
    val detail: String? = null,
    val muted: Boolean = false,
    val changed: Boolean = false,
    val target: AutomationFlowTarget? = null,
    val condition: String? = null,
    /** Structural flag so the UI can badge sub-task nodes without parsing the localized detail. */
    val isSubTask: Boolean = false,
    val strings: AutomationFlowStrings = AutomationFlowStrings.English,
    val outputs: List<ResolvedActionOutput> = emptyList(),
) {
    fun accessibilityLabel(): String {
        val kindName = kind.name.lowercase().replace('_', ' ')
        return strings.nodeAccessibility(
            kind = kindName,
            title = title,
            detail = detail,
            condition = condition,
            muted = muted,
            changed = changed,
            outputs = outputs.map { it.name },
        )
    }
}

enum class AutomationFlowNodeKind {
    PROFILE,
    CONTEXT,
    ENTER_TASK,
    EXIT_TASK,
    ACTION,
    MISSING,
}

sealed interface AutomationFlowTarget {
    data class Profile(val profileId: Long) : AutomationFlowTarget
    data class Context(val profileId: Long, val index: Int) : AutomationFlowTarget
    data class Task(val taskId: Long) : AutomationFlowTarget
    data class Action(val taskId: Long, val index: Int) : AutomationFlowTarget
}

data class AutomationFlowEdge(
    val fromId: String,
    val toId: String,
    val label: String,
)

object AutomationFlowGraphBuilder {
    fun build(
        profile: Profile,
        tasks: List<Task>,
        strings: AutomationFlowStrings = AutomationFlowStrings.English,
        changedNodeKeys: Set<String> = emptySet(),
        lintStrings: AutomationLintStrings = AutomationLintStrings.English,
        invariants: List<AutomationInvariant> = emptyList(),
    ): AutomationFlowGraph = build(
        profile = profile,
        tasksById = tasks.associateBy { it.id },
        strings = strings,
        changedNodeKeys = changedNodeKeys,
        lintStrings = lintStrings,
        invariants = invariants,
    )

    fun build(
        profile: Profile,
        tasksById: Map<Long, Task>,
        strings: AutomationFlowStrings = AutomationFlowStrings.English,
        changedNodeKeys: Set<String> = emptySet(),
        lintStrings: AutomationLintStrings = AutomationLintStrings.English,
        invariants: List<AutomationInvariant> = emptyList(),
    ): AutomationFlowGraph {
        val nodes = mutableListOf<AutomationFlowNode>()
        val edges = mutableListOf<AutomationFlowEdge>()
        val warnings = mutableListOf<String>()
        val profileNodeId = "profile:${profile.id}"

        nodes += AutomationFlowNode(
            id = profileNodeId,
            kind = AutomationFlowNodeKind.PROFILE,
            title = profile.name,
            detail = listOf(
                strings.profileDetail(profile.enabled, profile.automationMode.name.lowercase(), profile.cooldownSec),
            ).joinToString(FLOW_DETAIL_SEPARATOR),
            muted = !profile.enabled,
            changed = "profile:${profile.id}" in changedNodeKeys,
            target = AutomationFlowTarget.Profile(profile.id),
            strings = strings,
        )

        if (profile.contexts.isEmpty()) {
            warnings += strings.noContextsWarning()
        }

        profile.contexts.forEachIndexed { index, context ->
            val contextNodeId = "profile:${profile.id}:context:$index"
            nodes += context.toNode(contextNodeId, profile.id, index, strings, changedNodeKeys)
            edges += AutomationFlowEdge(
                fromId = contextNodeId,
                toId = profileNodeId,
                label = strings.contextEdge(context.invert),
            )
        }

        val enterTaskNodeId = addTaskLane(
            nodes = nodes,
            edges = edges,
            warnings = warnings,
            sourceNodeId = profileNodeId,
            profileId = profile.id,
            profileName = profile.name,
            taskId = profile.enterTaskId,
            task = tasksById[profile.enterTaskId],
            kind = AutomationFlowNodeKind.ENTER_TASK,
            edgeLabel = "enter",
            strings = strings,
            changedNodeKeys = changedNodeKeys,
        )

        if (enterTaskNodeId == null) {
            warnings += strings.missingTaskWarning("enter", profile.enterTaskId)
        }

        profile.exitTaskId?.let { exitTaskId ->
            val exitTaskNodeId = addTaskLane(
                nodes = nodes,
                edges = edges,
                warnings = warnings,
                sourceNodeId = profileNodeId,
                profileId = profile.id,
                profileName = profile.name,
                taskId = exitTaskId,
                task = tasksById[exitTaskId],
                kind = AutomationFlowNodeKind.EXIT_TASK,
                edgeLabel = "exit",
                strings = strings,
                changedNodeKeys = changedNodeKeys,
            )
            if (exitTaskNodeId == null) {
                warnings += strings.missingTaskWarning("exit", exitTaskId)
            }
        }

        return AutomationFlowGraph(
            profileId = profile.id,
            title = profile.name,
            nodes = nodes,
            edges = edges,
            warnings = warnings.distinct(),
            strings = strings,
            lintFindings = AutomationLint.analyze(
                profile,
                tasksById.values.toList(),
                strings = lintStrings,
                invariants = invariants,
            ).forProfile(profile.id),
        )
    }

    private fun addTaskLane(
        nodes: MutableList<AutomationFlowNode>,
        edges: MutableList<AutomationFlowEdge>,
        warnings: MutableList<String>,
        sourceNodeId: String,
        profileId: Long,
        profileName: String,
        taskId: Long,
        task: Task?,
        kind: AutomationFlowNodeKind,
        edgeLabel: String,
        strings: AutomationFlowStrings,
        changedNodeKeys: Set<String>,
    ): String? {
        val taskNodeId = "${edgeLabel}-task:$taskId"
        if (task == null) {
            nodes += AutomationFlowNode(
                id = taskNodeId,
                kind = AutomationFlowNodeKind.MISSING,
                title = "Missing ${edgeLabel} task",
                detail = strings.missingTaskDetail(taskId, profileName),
                muted = true,
                changed = "task:$taskId" in changedNodeKeys || "profile:$profileId" in changedNodeKeys,
                target = AutomationFlowTarget.Profile(profileId),
                strings = strings,
            )
            edges += AutomationFlowEdge(sourceNodeId, taskNodeId, edgeLabel)
            return null
        }

        nodes += AutomationFlowNode(
            id = taskNodeId,
            kind = kind,
            title = task.name,
            detail = strings.taskDetail(task.actions.size, task.priority),
            changed = "task:$taskId" in changedNodeKeys,
            target = AutomationFlowTarget.Task(taskId),
            strings = strings,
        )
        edges += AutomationFlowEdge(sourceNodeId, taskNodeId, edgeLabel)

        if (task.actions.isEmpty()) {
            warnings += strings.noActionsWarning(task.name)
        }

        var previousNodeId = taskNodeId
        task.actions.forEachIndexed { index, action ->
            val actionNodeId = "$taskNodeId:action:$index"
            nodes += action.toNode(actionNodeId, taskId, index, strings, changedNodeKeys)
            edges += AutomationFlowEdge(
                fromId = previousNodeId,
                toId = actionNodeId,
                label = action.edgeLabel(index, strings),
            )
            previousNodeId = actionNodeId
        }
        return taskNodeId
    }
}

private fun ContextSpec.toNode(
    id: String,
    profileId: Long,
    index: Int,
    strings: AutomationFlowStrings,
    changedNodeKeys: Set<String>,
): AutomationFlowNode =
    AutomationFlowNode(
        id = id,
        kind = AutomationFlowNodeKind.CONTEXT,
        title = strings.contextTitle(index + 1, type.name.lowercase().replaceFirstChar { it.uppercase() }),
        detail = strings.contextDetail(
            invert,
            config.summaryOrNull(actionType = null, structuralKeys = STRUCTURAL_CONTEXT_KEYS[type].orEmpty()),
        ),
        muted = invert,
        changed = "profile:$profileId:context:$index" in changedNodeKeys,
        target = AutomationFlowTarget.Context(profileId, index),
        strings = strings,
    )

private fun ActionSpec.toNode(
    id: String,
    taskId: Long,
    index: Int,
    strings: AutomationFlowStrings,
    changedNodeKeys: Set<String>,
): AutomationFlowNode {
    val subTaskRef = if (type == "task.run") {
        listOf("task", "name", "id").firstNotNullOfOrNull { args[it]?.trim()?.takeUnless(String::isBlank) }
    } else {
        null
    }
    val title = strings.actionTitle(index + 1, subTaskRef, type, label)
    return AutomationFlowNode(
        id = id,
        kind = AutomationFlowNodeKind.ACTION,
        title = title,
        detail = strings.actionDetail(subTaskRef, type, strings.actionSummary(type, args), continueOnError),
        changed = "task:$taskId:action:$index" in changedNodeKeys || "task:$taskId" in changedNodeKeys,
        target = AutomationFlowTarget.Action(taskId, index),
        condition = condition?.trim()?.takeUnless { it.isBlank() },
        isSubTask = subTaskRef != null,
        // A step turned off is drawn like a disabled profile.
        muted = !enabled,
        strings = strings,
        outputs = ActionMetadataRegistry.get(type)?.resolveOutputs(this@toNode, actionIndex = index).orEmpty(),
    )
}

private fun ActionSpec.edgeLabel(index: Int, strings: AutomationFlowStrings): String {
    val trimmedCondition = condition?.trim()?.takeUnless { it.isBlank() }
    return strings.conditionalEdge(trimmedCondition?.safePreview().orEmpty(), index)
}

/**
 * Renders an argument/config map for a flow node. Values resolve through
 * [ActionArgumentSensitivity] so a credential stored in an action argument is masked here exactly
 * as it is in the task list and the run log. Pass a null [actionType] for context configs, which
 * have no registered field metadata and fall back to the shared name heuristic.
 */
private fun Map<String, String>.summaryOrNull(
    actionType: String?,
    limit: Int = 3,
    structuralKeys: Set<String> = emptySet(),
): String? {
    if (isEmpty()) return null
    return ActionArgumentSensitivity.summarize(
        actionType = actionType,
        args = this,
        limit = limit,
        maxValueLength = ARG_PREVIEW_LENGTH,
        structuralKeys = structuralKeys,
    ).takeUnless(String::isBlank)
}

/**
 * Built-in context fields that name what the context watches, never a secret, but trip the
 * credential heuristic by name: a State context's `key` is the state itself (`battery_level`), and
 * a broadcast trigger's `extraKey` is an intent extra's name. Masking them left every State
 * context in Flow reading `key=<redacted>`. Plugin configs get no exemption.
 */
private val STRUCTURAL_CONTEXT_KEYS: Map<ContextType, Set<String>> = mapOf(
    ContextType.STATE to setOf("key"),
    ContextType.EVENT to setOf("extraKey"),
)

private const val ARG_PREVIEW_LENGTH = 36

private fun String.safePreview(maxLength: Int = ARG_PREVIEW_LENGTH): String =
    replace(Regex("\\s+"), " ")
        .let { value -> if (value.length <= maxLength) value else value.take(maxLength - 1) + "..." }

private fun plural(count: Int): String = if (count == 1) "" else "s"
