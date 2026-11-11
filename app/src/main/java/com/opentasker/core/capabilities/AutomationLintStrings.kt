package com.opentasker.core.capabilities

import android.content.res.Resources
import com.opentasker.app.R

data class AutomationLintCopy(
    val title: String,
    val detail: String,
    val suggestedFix: String,
)

/** Presentation copy for structural lint; callers that show findings supply app resources. */
interface AutomationLintStrings {
    fun missingReversal(profileName: String, writes: String): AutomationLintCopy
    fun repeatedTriggering(profileName: String): AutomationLintCopy

    /** The same finding for a profile with no enter task, where only the exit task can repeat. */
    fun repeatedExitTriggering(profileName: String): AutomationLintCopy = repeatedTriggering(profileName)
    fun priorityConflict(leftName: String, rightName: String, overlap: String, leftPriority: Int, rightPriority: Int, equalPriority: Boolean): AutomationLintCopy
    fun interProfileLoop(path: String): AutomationLintCopy
    fun shadowedRule(
        shadowingProfileName: String,
        shadowedProfileName: String,
        overlap: String,
        shadowingPriority: Int,
        shadowedPriority: Int,
    ): AutomationLintCopy = English.shadowedRule(
        shadowingProfileName,
        shadowedProfileName,
        overlap,
        shadowingPriority,
        shadowedPriority,
    )

    fun unreachableRule(profileName: String, reason: String): AutomationLintCopy =
        English.unreachableRule(profileName, reason)

    fun actionRevertPair(profileName: String, overlap: String): AutomationLintCopy =
        English.actionRevertPair(profileName, overlap)

    fun invariantViolation(
        invariantName: String,
        guard: String,
        profileNames: String,
        forbiddenWriteKey: String,
    ): AutomationLintCopy = English.invariantViolation(
        invariantName,
        guard,
        profileNames,
        forbiddenWriteKey,
    )

    companion object {
        fun from(resources: Resources): AutomationLintStrings = ResourceAutomationLintStrings(resources)
        val English: AutomationLintStrings get() = EnglishAutomationLintStrings
    }
}

private class ResourceAutomationLintStrings(
    private val resources: Resources,
) : AutomationLintStrings {
    override fun missingReversal(profileName: String, writes: String): AutomationLintCopy = AutomationLintCopy(
        title = resources.getString(R.string.automation_lint_missing_reversal_title),
        detail = resources.getString(R.string.automation_lint_missing_reversal_detail, profileName, writes),
        suggestedFix = resources.getString(R.string.automation_lint_missing_reversal_fix),
    )

    override fun repeatedTriggering(profileName: String): AutomationLintCopy = AutomationLintCopy(
        title = resources.getString(R.string.automation_lint_repeated_triggering_title),
        detail = resources.getString(R.string.automation_lint_repeated_triggering_detail, profileName),
        suggestedFix = resources.getString(R.string.automation_lint_repeated_triggering_fix),
    )

    override fun repeatedExitTriggering(profileName: String): AutomationLintCopy = AutomationLintCopy(
        title = resources.getString(R.string.automation_lint_repeated_triggering_title),
        detail = resources.getString(R.string.automation_lint_repeated_exit_triggering_detail, profileName),
        suggestedFix = resources.getString(R.string.automation_lint_repeated_exit_triggering_fix),
    )

    override fun priorityConflict(
        leftName: String,
        rightName: String,
        overlap: String,
        leftPriority: Int,
        rightPriority: Int,
        equalPriority: Boolean,
    ): AutomationLintCopy = AutomationLintCopy(
        title = resources.getString(R.string.automation_lint_priority_conflict_title),
        detail = resources.getString(
            R.string.automation_lint_priority_conflict_detail,
            leftName,
            rightName,
            overlap,
            leftPriority,
            rightPriority,
        ),
        suggestedFix = resources.getString(
            if (equalPriority) R.string.automation_lint_priority_conflict_equal_fix
            else R.string.automation_lint_priority_conflict_ordered_fix,
        ),
    )

    override fun interProfileLoop(path: String): AutomationLintCopy = AutomationLintCopy(
        title = resources.getString(R.string.automation_lint_inter_profile_loop_title),
        detail = resources.getString(R.string.automation_lint_inter_profile_loop_detail, path),
        suggestedFix = resources.getString(R.string.automation_lint_inter_profile_loop_fix),
    )

    override fun shadowedRule(
        shadowingProfileName: String,
        shadowedProfileName: String,
        overlap: String,
        shadowingPriority: Int,
        shadowedPriority: Int,
    ): AutomationLintCopy = AutomationLintCopy(
        title = resources.getString(R.string.automation_lint_shadowed_rule_title),
        detail = resources.getString(
            R.string.automation_lint_shadowed_rule_detail,
            shadowedProfileName,
            shadowingProfileName,
            overlap,
            shadowingPriority,
            shadowedPriority,
        ),
        suggestedFix = resources.getString(R.string.automation_lint_shadowed_rule_fix),
    )

    override fun unreachableRule(profileName: String, reason: String): AutomationLintCopy = AutomationLintCopy(
        title = resources.getString(R.string.automation_lint_unreachable_rule_title),
        detail = resources.getString(R.string.automation_lint_unreachable_rule_detail, profileName, reason),
        suggestedFix = resources.getString(R.string.automation_lint_unreachable_rule_fix),
    )

    override fun actionRevertPair(profileName: String, overlap: String): AutomationLintCopy = AutomationLintCopy(
        title = resources.getString(R.string.automation_lint_action_revert_pair_title),
        detail = resources.getString(R.string.automation_lint_action_revert_pair_detail, profileName, overlap),
        suggestedFix = resources.getString(R.string.automation_lint_action_revert_pair_fix),
    )

    override fun invariantViolation(
        invariantName: String,
        guard: String,
        profileNames: String,
        forbiddenWriteKey: String,
    ): AutomationLintCopy = AutomationLintCopy(
        title = resources.getString(R.string.automation_lint_invariant_violation_title),
        detail = resources.getString(
            R.string.automation_lint_invariant_violation_detail,
            invariantName,
            guard,
            profileNames,
            forbiddenWriteKey,
        ),
        suggestedFix = resources.getString(R.string.automation_lint_invariant_violation_fix),
    )
}

private object EnglishAutomationLintStrings : AutomationLintStrings {
    override fun missingReversal(profileName: String, writes: String): AutomationLintCopy = AutomationLintCopy(
        title = "Missing reversal",
        detail = "$profileName writes $writes when it enters but has no exit task.",
        suggestedFix = "Add an exit task that restores the setting, or use a bounded temporary-state action.",
    )

    override fun repeatedTriggering(profileName: String): AutomationLintCopy = AutomationLintCopy(
        title = "Repeated triggering",
        detail = "$profileName has a state context without a cooldown, dwell, or explicit idempotency guard.",
        suggestedFix = "Add a cooldown, a dwell requirement, or an explicit guard condition to the enter task.",
    )

    override fun repeatedExitTriggering(profileName: String): AutomationLintCopy = AutomationLintCopy(
        title = "Repeated triggering",
        detail = "$profileName has a state context without a grace period, dwell, or explicit idempotency guard.",
        suggestedFix = "Add a grace period, a dwell requirement, or an explicit guard condition to the exit task.",
    )

    override fun priorityConflict(
        leftName: String,
        rightName: String,
        overlap: String,
        leftPriority: Int,
        rightPriority: Int,
        equalPriority: Boolean,
    ): AutomationLintCopy = AutomationLintCopy(
        title = "Priority conflict",
        detail = "$leftName and $rightName can both write $overlap while their profile priorities are $leftPriority and $rightPriority.",
        suggestedFix = if (equalPriority) {
            "Raise one task priority or make the contexts mutually exclusive."
        } else {
            "Confirm that the higher-priority task should win, or make the contexts mutually exclusive."
        },
    )

    override fun interProfileLoop(path: String): AutomationLintCopy = AutomationLintCopy(
        title = "Inter-profile loop",
        detail = "Profiles form a direct task cycle: $path.",
        suggestedFix = "Remove one task.run edge or add an explicit state guard so the chain cannot retrigger itself.",
    )

    override fun shadowedRule(
        shadowingProfileName: String,
        shadowedProfileName: String,
        overlap: String,
        shadowingPriority: Int,
        shadowedPriority: Int,
    ): AutomationLintCopy = AutomationLintCopy(
        title = "Shadowed rule",
        detail = "$shadowedProfileName can be active with $shadowingProfileName, which writes $overlap at priority $shadowingPriority versus $shadowedPriority.",
        suggestedFix = "Make the contexts mutually exclusive, or confirm that the lower-priority rule should remain available.",
    )

    override fun unreachableRule(profileName: String, reason: String): AutomationLintCopy = AutomationLintCopy(
        title = "Unreachable rule",
        detail = "$profileName cannot run: $reason.",
        suggestedFix = "Restore the referenced task or adjust the context and lifetime constraints so the profile can run.",
    )

    override fun actionRevertPair(profileName: String, overlap: String): AutomationLintCopy = AutomationLintCopy(
        title = "Action/revert pair",
        detail = "$profileName writes $overlap in both its enter and exit tasks.",
        suggestedFix = "Verify that the exit task restores the intended previous state instead of repeating the enter action.",
    )

    override fun invariantViolation(
        invariantName: String,
        guard: String,
        profileNames: String,
        forbiddenWriteKey: String,
    ): AutomationLintCopy = AutomationLintCopy(
        // Kept in step with automation_lint_invariant_violation_* in strings.xml. Two copies of
        // the same user-facing copy drifted apart once already: the 2026-09-03 plain-language
        // pass rewrote the resources and left this default serving the old jargon.
        title = "A watched setting could be changed",
        detail = "Your rule $invariantName ($guard) watches a setting that $profileNames may change by writing $forbiddenWriteKey.",
        suggestedFix = "Turn that profile off or narrow when it runs. If the change is deliberate, edit the rule instead.",
    )
}
