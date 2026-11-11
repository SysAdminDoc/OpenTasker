package com.opentasker.core.flow

import android.content.res.Resources
import com.opentasker.app.R
import com.opentasker.core.actions.ActionArgumentSensitivity
import com.opentasker.core.actions.ActionSummaryFormatter

/** Joins the parts of a node's detail line. The Flow screen splits on it to find the action type. */
internal const val FLOW_DETAIL_SEPARATOR = " · "

/** Presentation copy for the flow graph; production UI supplies the current locale's resources. */
interface AutomationFlowStrings {
    companion object {
        fun from(resources: Resources): AutomationFlowStrings = ResourceAutomationFlowStrings(resources)
        val English: AutomationFlowStrings get() = EnglishAutomationFlowStrings
    }

    fun accessibilitySummary(title: String, contextCount: Int, actionCount: Int, enterTask: String, exitTask: String, warningText: String): String
    fun noExitTask(): String
    fun noEnterTask(): String
    fun nodeAccessibility(
        kind: String,
        title: String,
        detail: String?,
        condition: String?,
        muted: Boolean,
        changed: Boolean,
        outputs: List<String>,
    ): String
    fun profileDetail(enabled: Boolean, mode: String, cooldownSeconds: Int): String
    fun noContextsWarning(): String
    fun contextEdge(inverted: Boolean): String
    fun taskEdge(label: String): String
    fun missingTaskTitle(label: String): String
    fun missingTaskWarning(label: String, taskId: Long): String
    fun missingTaskDetail(taskId: Long, profileName: String): String
    fun taskDetail(actionCount: Int, priority: Int): String
    fun noActionsWarning(taskName: String): String
    fun contextTitle(index: Int, type: String): String
    fun contextDetail(inverted: Boolean, summary: String?): String
    fun actionTitle(index: Int, subTaskRef: String?, type: String, customLabel: String?): String
    fun actionSummary(type: String, args: Map<String, String>): String
    fun actionDetail(subTaskRef: String?, type: String, summary: String?, continuesAfterError: Boolean): String
    fun conditionalEdge(condition: String, index: Int): String
}

private class ResourceAutomationFlowStrings(
    private val resources: Resources,
) : AutomationFlowStrings {
    override fun accessibilitySummary(title: String, contextCount: Int, actionCount: Int, enterTask: String, exitTask: String, warningText: String): String =
        resources.getString(R.string.flow_accessibility_summary, title, contextCount, actionCount, enterTask, exitTask, warningText)
    override fun noExitTask(): String = resources.getString(R.string.flow_no_exit_task)
    override fun noEnterTask(): String = resources.getString(R.string.flow_no_enter_task)

    override fun nodeAccessibility(
        kind: String,
        title: String,
        detail: String?,
        condition: String?,
        muted: Boolean,
        changed: Boolean,
        outputs: List<String>,
    ): String =
        resources.getString(
            R.string.flow_node_accessibility,
            listOfNotNull(
                kind,
                title,
                detail.orEmpty(),
                condition?.let { resources.getString(R.string.flow_if_condition, it) }.orEmpty(),
                muted.toString(),
                resources.getString(R.string.flow_changed_node).takeIf { changed },
                outputs.takeIf { it.isNotEmpty() }
                    ?.let { resources.getString(R.string.flow_outputs_accessibility, it.joinToString(", ")) },
            ).joinToString(". "),
        )

    override fun profileDetail(enabled: Boolean, mode: String, cooldownSeconds: Int): String =
        resources.getString(
            R.string.flow_profile_detail,
            resources.getString(if (enabled) R.string.label_enabled else R.string.status_disabled),
            mode,
            cooldownSeconds,
        )

    override fun noContextsWarning(): String = resources.getString(R.string.flow_warning_no_contexts)
    override fun contextEdge(inverted: Boolean): String = resources.getString(if (inverted) R.string.flow_edge_not_match else R.string.flow_edge_match)
    override fun taskEdge(label: String): String = label
    override fun missingTaskTitle(label: String): String = resources.getString(R.string.flow_missing_task_title, label)
    override fun missingTaskWarning(label: String, taskId: Long): String = resources.getString(R.string.flow_missing_task_warning, label, taskId)
    override fun missingTaskDetail(taskId: Long, profileName: String): String = resources.getString(R.string.flow_missing_task_detail, taskId, profileName)
    override fun taskDetail(actionCount: Int, priority: Int): String = resources.getString(R.string.flow_task_detail, actionCount, priority)
    override fun noActionsWarning(taskName: String): String = resources.getString(R.string.flow_warning_no_actions, taskName)
    override fun contextTitle(index: Int, type: String): String = resources.getString(R.string.flow_context_title, index, type)
    override fun contextDetail(inverted: Boolean, summary: String?): String =
        listOfNotNull(if (inverted) resources.getString(R.string.label_inverted) else null, summary).joinToString(FLOW_DETAIL_SEPARATOR)
            .ifBlank { resources.getString(R.string.workspace_no_arguments) }

    override fun actionTitle(index: Int, subTaskRef: String?, type: String, customLabel: String?): String = when {
        !customLabel.isNullOrBlank() -> customLabel
        subTaskRef != null -> resources.getString(R.string.flow_action_subtask_title, index, subTaskRef)
        else -> resources.getString(R.string.flow_action_title, index, type)
    }

    override fun actionSummary(type: String, args: Map<String, String>): String =
        ActionSummaryFormatter.format(resources, type, args)

    override fun actionDetail(subTaskRef: String?, type: String, summary: String?, continuesAfterError: Boolean): String =
        listOfNotNull(
            if (subTaskRef != null) resources.getString(R.string.flow_action_subtask_detail, subTaskRef) else null,
            summary?.takeUnless(String::isBlank) ?: type,
            if (continuesAfterError) resources.getString(R.string.flow_continues_after_error) else null,
        ).joinToString(FLOW_DETAIL_SEPARATOR)

    override fun conditionalEdge(condition: String, index: Int): String = when {
        condition.isNotBlank() -> resources.getString(R.string.flow_if_condition, condition)
        index == 0 -> resources.getString(R.string.flow_step_label, index + 1)
        else -> resources.getString(R.string.flow_then)
    }
}

/** English fallback retained for pure JVM graph tests and non-UI callers. */
private object EnglishAutomationFlowStrings : AutomationFlowStrings {
    override fun accessibilitySummary(title: String, contextCount: Int, actionCount: Int, enterTask: String, exitTask: String, warningText: String) =
        "$title: $contextCount context${plural(contextCount)}, $actionCount action${plural(actionCount)}, enter task $enterTask, exit task $exitTask, $warningText."
    override fun noExitTask() = "no exit task"
    override fun noEnterTask() = "no enter task"
    override fun nodeAccessibility(
        kind: String,
        title: String,
        detail: String?,
        condition: String?,
        muted: Boolean,
        changed: Boolean,
        outputs: List<String>,
    ) = listOfNotNull(
        kind,
        title,
        detail?.takeUnless(String::isBlank),
        condition?.let { "condition if $it" },
        "inactive".takeIf { muted },
        "changed".takeIf { changed },
        outputs.takeIf { it.isNotEmpty() }?.let { "outputs ${it.joinToString(", ")}" },
    ).joinToString(". ")
    override fun profileDetail(enabled: Boolean, mode: String, cooldownSeconds: Int) =
        listOf(if (enabled) "Enabled" else "Disabled", "Mode $mode", "Cooldown ${cooldownSeconds}s").joinToString(FLOW_DETAIL_SEPARATOR)
    override fun noContextsWarning() = "Profile has no contexts."
    override fun contextEdge(inverted: Boolean) = if (inverted) "must not match" else "must match"
    override fun taskEdge(label: String) = label
    override fun missingTaskTitle(label: String) = "Missing $label task"
    override fun missingTaskWarning(label: String, taskId: Long) = "${label.replaceFirstChar { it.uppercase() }} task $taskId is missing."
    override fun missingTaskDetail(taskId: Long, profileName: String) = "Task id $taskId is referenced by $profileName"
    override fun taskDetail(actionCount: Int, priority: Int) = "$actionCount action${plural(actionCount)}${FLOW_DETAIL_SEPARATOR}priority $priority"
    override fun noActionsWarning(taskName: String) = "$taskName has no actions."
    override fun contextTitle(index: Int, type: String) = "Context $index: $type"
    override fun contextDetail(inverted: Boolean, summary: String?) = listOfNotNull("Inverted".takeIf { inverted }, summary).joinToString(FLOW_DETAIL_SEPARATOR).ifBlank { "No parameters" }
    override fun actionTitle(index: Int, subTaskRef: String?, type: String, customLabel: String?) = when {
        !customLabel.isNullOrBlank() -> customLabel
        subTaskRef != null -> "Step $index: run sub-task \"$subTaskRef\""
        else -> "Step $index: $type"
    }

    override fun actionSummary(type: String, args: Map<String, String>): String {
        val parameters = ActionArgumentSensitivity.summarize(
            actionType = type,
            args = args,
            limit = ActionSummaryFormatter.MAX_ARGUMENTS,
            maxValueLength = ActionSummaryFormatter.MAX_VALUE_LENGTH,
        ).ifBlank { "the configured values" }
        return "$type with $parameters"
    }

    override fun actionDetail(subTaskRef: String?, type: String, summary: String?, continuesAfterError: Boolean) =
        listOfNotNull(
            "sub-task -> $subTaskRef".takeIf { subTaskRef != null },
            summary?.takeUnless(String::isBlank) ?: type,
            "continues after error".takeIf { continuesAfterError },
        ).joinToString(FLOW_DETAIL_SEPARATOR)
    override fun conditionalEdge(condition: String, index: Int) = condition.takeIf { it.isNotBlank() }?.let { "if $it" } ?: if (index == 0) "step 1" else "then"

    private fun plural(count: Int): String = if (count == 1) "" else "s"
}

private fun plural(count: Int): String = if (count == 1) "" else "s"
