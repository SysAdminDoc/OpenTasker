package com.opentasker.core.model

import kotlinx.serialization.Serializable

/**
 * A Profile binds one or more Contexts to a Task. Legacy profiles use implicit AND semantics;
 * profiles with [contextExpression] evaluate that explicit nested boolean tree instead.
 * Activation runs [enterTaskId]; deactivation runs [exitTaskId] (if set). An [enterTaskId] of 0
 * means the profile has no enter task, which is valid when it has an exit task: it goes on without
 * running anything and runs [exitTaskId] when its contexts stop matching.
 */
@Serializable
data class Profile(
    val id: Long = 0,
    val name: String,
    val enabled: Boolean = true,
    val contexts: List<ContextSpec> = emptyList(),
    val enterTaskId: Long,
    val exitTaskId: Long? = null,
    val cooldownSec: Int = 0,
    val automationMode: AutomationMode = AutomationMode.SINGLE,
    val group: String? = null,
    val requiresRiskAcknowledgement: Boolean = false,
    val projectId: Long = DEFAULT_PROJECT_ID,
    val contextExpression: ContextExpressionNode? = null,
    /** Higher-priority profiles win deterministic arbitration when multiple profiles match. */
    val priority: Int = 0,
    /** Stable time in seconds required before either an activation or deactivation is accepted. */
    val gracePeriodSec: Int = 0,
    val lifetime: ProfileLifetime = ProfileLifetime.NEVER,
    /** Absolute epoch-millis expiry for [ProfileLifetime.UNTIL_DATE]. */
    val expiresAtMs: Long? = null,
    /** Internal persisted state for one-shot profiles; it is never user-authored directly. */
    val lifetimeConsumed: Boolean = false,
    /** Optional override for the number of active executions admitted for this profile. */
    val maxActiveExecutions: Int? = null,
    /** Optional override for starts admitted during the engine burst window. */
    val burstLimit: Int? = null,
    /** Whether admission overflow should leave a replayable run-log entry. */
    val overflowPolicy: ProfileOverflowPolicy = ProfileOverflowPolicy.LOG,
    /** Optional task to run when this profile's task fails without a lexical flow.catch. */
    val fallbackTaskId: Long? = null,
)

/**
 * [Profile.enterTaskId], or null when the profile has no enter task. Only 0 means none: a negative
 * id stays a broken reference to a task that can't exist, as it was before 0 meant none.
 */
val Profile.enterTaskIdOrNull: Long?
    get() = enterTaskId.takeIf { it != 0L }

@Serializable
enum class ProfileLifetime {
    NEVER,
    UNTIL_DATE,
    ONCE,
}

@Serializable
enum class ProfileOverflowPolicy {
    LOG,
    SILENT,
}

@Serializable
enum class AutomationMode {
    SINGLE,
    RESTART,
    QUEUED,
    PARALLEL,
}
