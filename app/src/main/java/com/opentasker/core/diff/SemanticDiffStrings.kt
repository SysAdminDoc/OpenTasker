package com.opentasker.core.diff

import android.content.res.Resources
import com.opentasker.app.R

/** Resolves semantic-diff field labels and stable enum values at the localized UI boundary. */
interface SemanticDiffStrings {
    fun path(path: String): String

    fun value(path: String, value: String?): String

    companion object {
        fun from(resources: Resources): SemanticDiffStrings = ResourceSemanticDiffStrings(resources)

        val English: SemanticDiffStrings = EnglishSemanticDiffStrings
    }
}

private class ResourceSemanticDiffStrings(
    private val resources: Resources,
) : SemanticDiffStrings {
    override fun path(path: String): String = path
        .split(" / ")
        .joinToString(" / ") { segment -> localizeSegment(segment) }

    override fun value(path: String, value: String?): String = when (value) {
        null -> resources.getString(R.string.semantic_diff_value_missing)
        "true" -> resources.getString(R.string.semantic_diff_value_true)
        "false" -> resources.getString(R.string.semantic_diff_value_false)
        "NEVER" -> resources.getString(R.string.semantic_diff_value_never)
        "UNTIL_DATE" -> resources.getString(R.string.semantic_diff_value_until_date)
        "ONCE" -> resources.getString(R.string.semantic_diff_value_once)
        "LOG" -> resources.getString(R.string.semantic_diff_value_log)
        "SILENT" -> resources.getString(R.string.semantic_diff_value_silent)
        "SINGLE" -> resources.getString(R.string.semantic_diff_value_single)
        "RESTART" -> resources.getString(R.string.semantic_diff_value_restart)
        "QUEUED" -> resources.getString(R.string.semantic_diff_value_queued)
        "PARALLEL" -> resources.getString(R.string.semantic_diff_value_parallel)
        "ABORT_NEW" -> resources.getString(R.string.semantic_diff_value_abort_new)
        "ABORT_EXISTING" -> resources.getString(R.string.semantic_diff_value_abort_existing)
        "RUN_BOTH" -> resources.getString(R.string.semantic_diff_value_run_both)
        "WAIT" -> resources.getString(R.string.semantic_diff_value_wait)
        else -> value
    }

    private fun localizeSegment(segment: String): String {
        val indexed = Regex("^(Action|Context|Element) (\\d+)$").matchEntire(segment)
        if (indexed != null) {
            val index = indexed.groupValues[2].toInt()
            return when (indexed.groupValues[1]) {
                "Action" -> resources.getString(R.string.semantic_diff_path_action_index, index)
                "Context" -> resources.getString(R.string.semantic_diff_path_context_index, index)
                else -> resources.getString(R.string.semantic_diff_path_element_index, index)
            }
        }
        when {
            segment.startsWith("Argument ") -> return resources.getString(
                R.string.semantic_diff_path_argument,
                segment.removePrefix("Argument "),
            )
            segment.startsWith("Parameter ") -> return resources.getString(
                R.string.semantic_diff_path_parameter,
                segment.removePrefix("Parameter "),
            )
        }
        val resource = PATH_RESOURCES[segment] ?: return segment
        return resources.getString(resource)
    }

    private companion object {
        val PATH_RESOURCES = mapOf(
            "Task" to R.string.semantic_diff_path_task,
            "Profile" to R.string.semantic_diff_path_profile,
            "Scene" to R.string.semantic_diff_path_scene,
            "Variable" to R.string.semantic_diff_path_variable,
            "Name" to R.string.semantic_diff_path_name,
            "Priority" to R.string.semantic_diff_path_priority,
            "Collision mode" to R.string.semantic_diff_path_collision_mode,
            "Enabled" to R.string.semantic_diff_path_enabled,
            "Enter task" to R.string.semantic_diff_path_enter_task,
            "Exit task" to R.string.semantic_diff_path_exit_task,
            "Cooldown" to R.string.semantic_diff_path_cooldown,
            "Automation mode" to R.string.semantic_diff_path_automation_mode,
            "Group" to R.string.semantic_diff_path_group,
            "Risk acknowledgement" to R.string.semantic_diff_path_risk_acknowledgement,
            "Grace period" to R.string.semantic_diff_path_grace_period,
            "Lifetime" to R.string.semantic_diff_path_lifetime,
            "Expiry" to R.string.semantic_diff_path_expiry,
            "Maximum active executions" to R.string.semantic_diff_path_maximum_active,
            "Burst limit" to R.string.semantic_diff_path_burst_limit,
            "Overflow policy" to R.string.semantic_diff_path_overflow_policy,
            "Fallback task" to R.string.semantic_diff_path_fallback_task,
            "Action" to R.string.semantic_diff_path_action,
            "Type" to R.string.semantic_diff_path_type,
            "Label" to R.string.semantic_diff_path_label,
            "Continue on error" to R.string.semantic_diff_path_continue_on_error,
            "Condition" to R.string.semantic_diff_path_condition,
            "Context" to R.string.semantic_diff_path_context,
            "Inverted" to R.string.semantic_diff_path_inverted,
            "OR group" to R.string.semantic_diff_path_or_group,
            "Element" to R.string.semantic_diff_path_element,
            "X" to R.string.semantic_diff_path_x,
            "Y" to R.string.semantic_diff_path_y,
            "Width" to R.string.semantic_diff_path_width,
            "Height" to R.string.semantic_diff_path_height,
            "Tap task" to R.string.semantic_diff_path_tap_task,
            "Long-press task" to R.string.semantic_diff_path_long_press_task,
            "Value" to R.string.semantic_diff_path_value,
            "Context logic" to R.string.semantic_diff_path_context_logic,
        )
    }
}

private object EnglishSemanticDiffStrings : SemanticDiffStrings {
    override fun path(path: String): String = path

    override fun value(path: String, value: String?): String = value ?: "none"
}
