package com.opentasker.core.engine

import android.content.Context
import com.opentasker.core.capabilities.AutomationSensitivityRegistry
import com.opentasker.core.logging.AppLogger
import com.opentasker.core.model.RunLogEntry
import com.opentasker.core.model.ProfileOverflowPolicy
import com.opentasker.core.model.Task
import com.opentasker.core.platform.AudioForegroundServiceEligibility
import com.opentasker.core.platform.AudioRuntimeEligibility
import com.opentasker.core.storage.AppDatabase
import com.opentasker.core.storage.FallbackTaskSettings
import com.opentasker.core.storage.TaskEntity
import com.opentasker.core.storage.RuntimeVariableSeed
import com.opentasker.core.storage.RuntimeVariableValue
import com.opentasker.core.storage.VariableRepository
import com.opentasker.core.storage.toEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext

data class TaskExecutionResult(
    val report: TaskRunReport,
    val logInserted: Boolean,
    val skippedReason: String? = null,
    val held: Boolean = false,
    val execution: ExecutionEnvelope,
    val fallback: FallbackExecutionResult? = null,
)

data class FallbackExecutionResult(
    val taskId: Long,
    val taskName: String,
    val source: String,
    val success: Boolean,
    val reason: String? = null,
)

suspend fun executeAndLogTask(
    appContext: Context,
    db: AppDatabase,
    task: Task,
    source: String,
    metadata: List<String> = emptyList(),
    initialVariables: Map<String, String> = emptyMap(),
    visibleActivity: Boolean = false,
    audioForegroundService: AudioForegroundServiceEligibility = AudioForegroundServiceEligibility.NONE,
    logTag: String = TAG,
    admissionController: ExecutionAdmissionController = ExecutionAdmissionController.Default,
    profileId: Long? = null,
    profileLimits: ExecutionAdmissionProfileLimits? = null,
    overflowPolicy: ProfileOverflowPolicy = ProfileOverflowPolicy.LOG,
    profileName: String? = null,
    profileFallbackTaskId: Long? = null,
    allowFallback: Boolean = true,
    execution: ExecutionEnvelope = ExecutionEnvelope.create(task, source, profileId = profileId),
): TaskExecutionResult = withContext(Dispatchers.IO) {
    if (execution.mode == ExecutionMode.SIMULATION) {
        val reason = "Simulation mode is diagnostic only; no task or side effect was run."
        return@withContext TaskExecutionResult(
            report = collisionSkippedReport(task, reason),
            logInserted = false,
            skippedReason = reason,
            execution = execution,
        )
    }
    val accepted = ExecutionCommandLedger.accept(execution)
    if (!accepted.isNew) {
        val reason = ExecutionTerminalReason(
            ExecutionTerminalReasonCode.DUPLICATE_DELIVERY,
            "Duplicate execution delivery ignored.",
        ).render()
        return@withContext TaskExecutionResult(
            report = collisionSkippedReport(task, reason),
            logInserted = false,
            skippedReason = reason,
            execution = execution,
        )
    }
    val admission = admissionController.tryAcquire(
        profileId = profileId,
        profileLimits = profileLimits,
        strings = ExecutionAdmissionStrings.from(appContext.resources),
    )
    if (!admission.accepted) {
        val reason = admission.reason ?: "Execution admission rejected this run."
        val terminalReason = ExecutionTerminalReason(ExecutionTerminalReasonCode.ADMISSION_REJECTED, reason)
        val held = overflowPolicy == ProfileOverflowPolicy.LOG
        ExecutionCommandLedger.transition(
            execution.executionId,
            if (held) ExecutionLedgerState.HELD else ExecutionLedgerState.SKIPPED,
            terminalReason,
        )
        val inserted = if (held) {
            logHeldRun(
                db = db,
                task = task,
                source = execution.source,
                reason = reason,
                metadata = metadata,
                initialVariables = initialVariables,
                execution = execution,
                terminalReason = terminalReason,
            )
        } else {
            false
        }
        return@withContext TaskExecutionResult(
            report = collisionSkippedReport(task, reason),
            logInserted = inserted,
            skippedReason = reason,
            held = held,
            execution = execution,
        )
    }
    val admissionLease = requireNotNull(admission.lease)
    // Only admitted work becomes a causal parent. A capacity or collision skip must not make a
    // later unrelated profile appear to be a child of work that never ran.
    ExecutionCausality.remember(execution)
    try {
    ExecutionCommandLedger.transition(execution.executionId, ExecutionLedgerState.RUNNING)
    // Journal admission before hydrating variables or invoking any action. If the process dies
    // after this point, startup can prove that the command began and must not be retried blindly.
    if (!ExecutionJournal.start(db, execution)) {
        // The id is already journaled, so this is a re-delivery of a command that already ran.
        // Acknowledge it as a skip rather than running the task a second time.
        val duplicateReason = "Duplicate delivery: this command has already run."
        val terminalReason = ExecutionTerminalReason(
            ExecutionTerminalReasonCode.DUPLICATE_DELIVERY,
            duplicateReason,
        )
        ExecutionCommandLedger.transition(
            execution.executionId,
            ExecutionLedgerState.SKIPPED,
            terminalReason,
        )
        admissionLease.release()
        ExecutionCausality.forget(execution.executionId)
        return@withContext TaskExecutionResult(
            report = collisionSkippedReport(task, duplicateReason),
            logInserted = false,
            skippedReason = duplicateReason,
            execution = execution,
        )
    }
    // Run the whole task off the caller's thread. Manual runs (ViewModel), widget/shortcut, and
    // notification-action paths call this from the main thread; without this hop, blocking actions
    // (HTTP, file, ping) would throw NetworkOnMainThreadException and fail silently.
    val variables = VariableStore()
    val variableRepository = VariableRepository(db.variableDao())
    val persistedGlobals = runCatching {
        variableRepository.runtimeGlobals(task.projectId)
    }.getOrElse { error ->
        AppLogger.error(logTag, "Failed to hydrate global variables", error)
        RuntimeVariableSeed(emptyMap(), emptySet(), emptySet())
    }
    variables.seedGlobals(persistedGlobals.values, persistedGlobals.secretNames)
    val persistedBaselineValues = variables.globalSnapshot()
    val persistedBaselineSecretNames = variables.globalSensitiveSnapshot()
    val persistedBaseline = RuntimeVariableSeed(
        values = persistedBaselineValues,
        secretNames = persistedBaselineSecretNames,
        unavailableSecretNames = persistedBaselineSecretNames - persistedBaselineValues.keys,
    )
    if (persistedGlobals.unavailableSecretNames.isNotEmpty()) {
        AppLogger.warn(
            logTag,
            "Secret variables require re-entry: ${persistedGlobals.unavailableSecretNames.sorted().joinToString()}",
        )
    }
    initialVariables.forEach { (name, value) -> variables.set(name, value, EventTaskVariables.isSensitive(name)) }
    // Baseline after seeding + event vars, so only globals actually changed during the run persist.
    val baselineGlobals = variables.globalSnapshot()
    val baselineSensitiveGlobals = variables.globalSensitiveSnapshot()
    // Every way this run can end has to commit the globals it set. Only the report path did, so a
    // cancelled or crashed run silently discarded them, and RESTART mode cancels the in-flight run
    // on every re-trigger.
    suspend fun commitGlobals(): List<String> = persistChangedGlobals(
        variableRepository,
        persistedBaseline,
        baselineGlobals,
        variables.globalSnapshot(),
        baselineSensitiveGlobals,
        variables.globalSensitiveSnapshot(),
        logTag,
        projectId = task.projectId,
    )
    val audioEligibility = AudioRuntimeEligibility(
        appVisible = visibleActivity,
        foregroundService = audioForegroundService,
    )
    val ctx = ActionContext(
        app = appContext,
        variables = variables,
        eventVariables = initialVariables,
        audioEligibility = audioEligibility,
    ) { msg -> AppLogger.info(logTag, msg) }
    val collisionCoordinator = TaskCollisionCoordinator.Default
    var executionId: Long? = null
    val collisionOutcome = try {
        collisionCoordinator.execute(task) {
            // Publish only admitted work. A WAIT invocation is not shown as active until it owns
            // the task slot, and ABORT_NEW never flashes a run that will immediately be skipped.
            val admittedExecutionId = ActiveExecutionRegistry.register(
                taskId = task.id,
                taskName = task.name,
                source = execution.source,
                job = currentCoroutineContext()[Job],
                startedAtMs = System.currentTimeMillis(),
                executionId = execution.executionId,
                parentExecutionId = execution.parentExecutionId,
                producer = execution.producer.wireValue,
            )
            executionId = admittedExecutionId
            try {
                TaskRunner(
                    ctx,
                    resolveTask = dbSubTaskResolver(db),
                    onStep = {
                        index, label ->
                        ActiveExecutionRegistry.reportStep(admittedExecutionId, index, label)
                    },
                    onStepCompleted = { index, label ->
                        ExecutionJournal.recordStep(db, execution.executionId, index, label)
                    },
                    collisionCoordinator = collisionCoordinator,
                    executionChain = setOf(task.id).filterTo(linkedSetOf()) { it > 0L },
                    originatingProfileId = profileId,
                    originatingProfileName = profileName,
                ).run(task)
            } finally {
                ActiveExecutionRegistry.unregister(admittedExecutionId)
            }
        }
    } catch (cancellation: CancellationException) {
        // withContext(NonCancellable): the surrounding scope is already cancelled, so an ordinary
        // suspending write here would be dropped and the run would vanish without a trace.
        withContext(NonCancellable) {
            executionId?.let(ActiveExecutionRegistry::unregister)
            val commitMetadata = commitGlobals()
            val terminalReason = ExecutionTerminalReason(
                ExecutionTerminalReasonCode.CANCELLED,
                cancellation.message ?: ActiveExecutionRegistry.CANCELLED_BY_USER,
            )
            ExecutionCommandLedger.transition(
                execution.executionId,
                ExecutionLedgerState.CANCELLED,
                terminalReason,
            )
            val marked = ExecutionJournal.markTerminal(
                db = db,
                executionId = execution.executionId,
                state = ExecutionJournalState.CANCELLED,
                reason = terminalReason,
            )
            if (marked) {
                val inserted = insertRunLog(
                    db,
                    RunLogEntry(
                        taskId = task.id,
                        taskName = task.name,
                        timestamp = System.currentTimeMillis(),
                        durationMs = 0,
                        success = false,
                        message = cancelledRunLogMessage(
                            source = execution.source,
                            reason = cancellation.message ?: ActiveExecutionRegistry.CANCELLED_BY_USER,
                            execution = execution,
                            terminalReason = terminalReason,
                            metadata = metadata + commitMetadata,
                        ),
                        source = RunLogSource.classify(execution.source).key,
                        sourceLabel = RunLogSource.classify(execution.source).label,
                        executionId = execution.executionId,
                        replayOf = execution.replayOf,
                    ),
                )
                if (inserted) ExecutionJournal.markRunLogWritten(db, execution.executionId)
            }
        }
        throw cancellation
    } catch (error: Exception) {
        withContext(NonCancellable) {
            executionId?.let(ActiveExecutionRegistry::unregister)
            val commitMetadata = commitGlobals()
            val terminalReason = ExecutionTerminalReason(
                ExecutionTerminalReasonCode.TASK_FAILED,
                error.message ?: "Execution failed before a task report was produced.",
            )
            ExecutionCommandLedger.transition(
                execution.executionId,
                ExecutionLedgerState.FAILED,
                terminalReason,
            )
            val marked = ExecutionJournal.markTerminal(
                db = db,
                executionId = execution.executionId,
                state = ExecutionJournalState.FAILED,
                reason = terminalReason,
            )
            if (marked) {
                val inserted = insertRunLog(
                    db,
                    RunLogEntry(
                        taskId = task.id,
                        taskName = task.name,
                        timestamp = System.currentTimeMillis(),
                        durationMs = 0,
                        success = false,
                        message = runLogMessage(
                            source = execution.source,
                            execution = execution,
                            terminalReason = terminalReason,
                            metadata = listOf(
                                "Failure message: ${(error.message ?: "unknown error").take(256)}",
                            ) + metadata + commitMetadata,
                        ),
                        source = RunLogSource.classify(execution.source).key,
                        sourceLabel = RunLogSource.classify(execution.source).label,
                        executionId = execution.executionId,
                        replayOf = execution.replayOf,
                    ),
                )
                if (inserted) ExecutionJournal.markRunLogWritten(db, execution.executionId)
            }
        }
        throw error
    }
    val report = when (collisionOutcome) {
        is TaskCollisionOutcome.Executed -> collisionOutcome.value
        is TaskCollisionOutcome.Skipped -> {
            val terminalReason = ExecutionTerminalReason(
                ExecutionTerminalReasonCode.COLLISION_SKIPPED,
                collisionOutcome.reason,
            )
            ExecutionCommandLedger.transition(
                execution.executionId,
                ExecutionLedgerState.SKIPPED,
                terminalReason,
            )
            ExecutionJournal.markTerminal(
                db = db,
                executionId = execution.executionId,
                state = ExecutionJournalState.SKIPPED,
                reason = terminalReason,
            )
            val inserted = logSkippedRun(
                db = db,
                task = task,
                source = execution.source,
                reason = collisionOutcome.reason,
                metadata = metadata,
                execution = execution,
                terminalReason = terminalReason,
            )
            if (inserted) ExecutionJournal.markRunLogWritten(db, execution.executionId)
            return@withContext TaskExecutionResult(
                report = collisionSkippedReport(task, collisionOutcome.reason),
                logInserted = inserted,
                skippedReason = collisionOutcome.reason,
                execution = execution,
            )
        }
    }
    val globalCommitMetadata = commitGlobals()
    val fallback = if (allowFallback && !report.success && report.structuredError != null) {
        // The failed task no longer owns an active slot while its recovery task runs. Otherwise a
        // profile with maxActiveExecutions=1 would reject the very fallback intended to diagnose it.
        admissionLease.release()
        runFallbackTask(
            appContext = appContext,
            db = db,
            failedTask = task,
            failedReport = report,
            source = execution.source,
            profileName = profileName,
            profileFallbackTaskId = profileFallbackTaskId,
            admissionController = admissionController,
            profileId = profileId,
            profileLimits = profileLimits,
            overflowPolicy = overflowPolicy,
            visibleActivity = visibleActivity,
            audioForegroundService = audioForegroundService,
            logTag = logTag,
            parentExecution = execution,
        )
    } else {
        null
    }
    val failureMetadata = report.structuredError?.let(::structuredFailureMetadata).orEmpty()
    val fallbackMetadata = fallback?.let {
        listOf(
            "Fallback task: ${it.taskName} (${it.source})",
            "Fallback result: ${if (it.success) "succeeded" else "failed"}",
        ) + it.reason?.let { reason -> listOf("Fallback reason: ${reason.take(256)}") }.orEmpty()
    }.orEmpty()
    AppLogger.info(logTag, "Task ${report.taskName} completed: ${report.success} (${report.durationMs}ms)")
    val terminalReason = ExecutionTerminalReason(
        if (report.success) {
            ExecutionTerminalReasonCode.COMPLETED
        } else {
            ExecutionTerminalReasonCode.TASK_FAILED
        },
        detail = (failureMetadata + fallbackMetadata).joinToString("; ").takeIf { it.isNotBlank() },
    )
    ExecutionCommandLedger.transition(
        execution.executionId,
        if (report.success) ExecutionLedgerState.SUCCEEDED else ExecutionLedgerState.FAILED,
        terminalReason,
    )
    val journalState = if (report.success) ExecutionJournalState.SUCCEEDED else ExecutionJournalState.FAILED
    val journalTerminal = ExecutionJournal.markTerminal(
        db = db,
        executionId = execution.executionId,
        state = journalState,
        reason = terminalReason,
    )
    val classified = RunLogSource.classify(execution.source)
    val riskMetadata = taskPowerRunLogMetadata(task)
    val logEntry = RunLogEntry(
        taskId = task.id,
        taskName = task.name,
        timestamp = report.startedAt,
        durationMs = report.durationMs,
        success = report.success,
        message = runLogMessage(
            source = execution.source,
            execution = execution,
            terminalReason = terminalReason,
            metadata = riskMetadata + metadata + globalCommitMetadata + failureMetadata + fallbackMetadata,
            traces = report.traces,
        ),
        source = classified.key,
        sourceLabel = classified.label,
        executionId = execution.executionId,
        replayOf = execution.replayOf,
    )
    val inserted = insertRunLog(db, logEntry)
    if (journalTerminal && inserted) ExecutionJournal.markRunLogWritten(db, execution.executionId)
    TaskExecutionResult(report, inserted, execution = execution, fallback = fallback)
    } finally {
        admissionLease.release()
        // Finished work cannot have caused a later trigger. Releasing attribution here is what
        // keeps ordinary re-triggers and exit tasks from being read as causal cycles.
        ExecutionCausality.forget(execution.executionId)
    }
}

private fun collisionSkippedReport(task: Task, reason: String): TaskRunReport {
    val startedAt = System.currentTimeMillis()
    return TaskRunReport(
        taskId = task.id,
        taskName = task.name,
        startedAt = startedAt,
        durationMs = 0,
        results = listOf(ActionResult.Skip),
        traces = listOf(
            ActionExecutionTrace(
                index = 0,
                actionType = "admission",
                label = "collision policy",
                durationMs = 0,
                status = ActionTraceStatus.SKIPPED,
                message = reason,
            ),
        ),
        success = false,
    )
}

internal fun taskPowerRunLogMetadata(task: Task): List<String> {
    val riskPowers = AutomationSensitivityRegistry.summarize(task).powers
        .map { it.name.lowercase().replace('_', ' ') }
    return if (riskPowers.isEmpty()) emptyList() else listOf("Powers: ${riskPowers.joinToString()}")
}

/**
 * Globals whose value changed during a run (added or modified), relative to the run's baseline.
 * Deterministic and order-stable so parallel runs converge on a well-defined last-write-wins result
 * once each commits. Pure for testability.
 */
fun changedGlobals(
    before: Map<String, String>,
    after: Map<String, String>,
    beforeSensitive: Set<String> = emptySet(),
    afterSensitive: Set<String> = emptySet(),
    projectId: Long = com.opentasker.core.model.DEFAULT_PROJECT_ID,
): List<RuntimeVariableValue> =
    after.asSequence()
        .filter { (name, value) -> before[name] != value || (name in beforeSensitive) != (name in afterSensitive) }
        .sortedBy { it.key }
        .map { (name, value) -> RuntimeVariableValue(name, value, isSecret = name in afterSensitive, projectId = projectId) }
        .toList()

/**
 * Commits globals changed during the run to [com.opentasker.core.storage.VariableDao] before the
 * task's success is reported, so names containing uppercase letters and explicit `var.persist`
 * values survive across separate runs and process restarts. All-lowercase locals never reach this path.
 */
private suspend fun persistChangedGlobals(
    variableRepository: VariableRepository,
    persistedBaseline: RuntimeVariableSeed,
    before: Map<String, String>,
    after: Map<String, String>,
    beforeSensitive: Set<String>,
    afterSensitive: Set<String>,
    logTag: String,
    projectId: Long = com.opentasker.core.model.DEFAULT_PROJECT_ID,
): List<String> {
    val changed = changedGlobals(before, after, beforeSensitive, afterSensitive, projectId)
    if (changed.isEmpty()) return emptyList()
    val commit = runCatching {
        variableRepository.persistRuntimeAtomically(persistedBaseline, changed)
    }.getOrElse { error ->
        AppLogger.error(
            logTag,
            "Failed to persist ${changed.size} global variable change(s) atomically",
            error,
        )
        return listOf("Global commit failed: ${changed.map(RuntimeVariableValue::name).joinToString()}")
    }
    if (commit.conflictedNames.isEmpty()) return emptyList()
    val names = commit.conflictedNames.joinToString()
    AppLogger.warn(logTag, "Global commit preserved newer concurrent value(s): $names")
    return listOf("Global write conflict (newer value kept): $names")
}

suspend fun logSkippedRun(
    db: AppDatabase,
    task: Task,
    source: String,
    reason: String,
    metadata: List<String> = emptyList(),
    execution: ExecutionEnvelope? = null,
    terminalReason: ExecutionTerminalReason? = null,
): Boolean {
    val envelope = execution ?: ExecutionEnvelope.create(task, source)
    ExecutionCommandLedger.accept(envelope)
    val resolvedReason = terminalReason ?: ExecutionTerminalReason(
        ExecutionTerminalReasonCode.UNKNOWN,
        reason,
    )
    ExecutionCommandLedger.transition(envelope.executionId, ExecutionLedgerState.SKIPPED, resolvedReason)
    val classified = RunLogSource.classify(envelope.source)
    return insertRunLog(
        db,
        RunLogEntry(
            taskId = task.id,
            taskName = task.name,
            durationMs = 0,
            success = false,
            message = skippedRunLogMessage(
                source = envelope.source,
                reason = reason,
                execution = envelope,
                terminalReason = resolvedReason,
                metadata = metadata,
            ),
            source = classified.key,
            sourceLabel = classified.label,
            executionId = envelope.executionId,
            replayOf = envelope.replayOf,
        ),
    )
}

private data class FallbackTaskSelection(
    val task: Task,
    val source: String,
)

private suspend fun runFallbackTask(
    appContext: Context,
    db: AppDatabase,
    failedTask: Task,
    failedReport: TaskRunReport,
    source: String,
    profileName: String?,
    profileFallbackTaskId: Long?,
    admissionController: ExecutionAdmissionController,
    profileId: Long?,
    profileLimits: ExecutionAdmissionProfileLimits?,
    overflowPolicy: ProfileOverflowPolicy,
    visibleActivity: Boolean,
    audioForegroundService: AudioForegroundServiceEligibility,
    logTag: String,
    parentExecution: ExecutionEnvelope,
): FallbackExecutionResult? {
    val error = failedReport.structuredError ?: return null
    val selection = selectFallbackTask(
        db = db,
        failedTask = failedTask,
        profileFallbackTaskId = profileFallbackTaskId,
        globalFallbackTaskId = FallbackTaskSettings(appContext).loadTaskId(),
    ) ?: return null
    val fallbackSource = "Fallback: ${selection.task.name}"
    val fallbackExecution = ExecutionEnvelope.create(
        task = selection.task,
        source = fallbackSource,
        profileId = profileId,
        parentExecutionId = parentExecution.executionId,
        causalDepth = parentExecution.causalDepth + 1,
        causalProfileChain = parentExecution.causalProfileChain,
    )
    return try {
        val result = executeAndLogTask(
            appContext = appContext,
            db = db,
            task = selection.task,
            source = fallbackSource,
            metadata = listOf(
                "Fallback source: ${selection.source}",
                "Original task: ${failedTask.name} (${failedTask.id})",
                "Original execution: ${parentExecution.executionId}",
            ),
            initialVariables = error.toFailureVariables(),
            visibleActivity = visibleActivity,
            audioForegroundService = audioForegroundService,
            logTag = logTag,
            admissionController = admissionController,
            profileId = profileId,
            profileLimits = profileLimits,
            overflowPolicy = overflowPolicy,
            profileName = profileName,
            allowFallback = false,
            execution = fallbackExecution,
        )
        FallbackExecutionResult(
            taskId = selection.task.id,
            taskName = selection.task.name,
            source = selection.source,
            success = result.report.success,
            reason = result.skippedReason ?: result.report.structuredError?.message,
        )
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Exception) {
        FallbackExecutionResult(
            taskId = selection.task.id,
            taskName = selection.task.name,
            source = selection.source,
            success = false,
            reason = error.message ?: "Fallback task failed before it could run.",
        )
    }
}

private suspend fun selectFallbackTask(
    db: AppDatabase,
    failedTask: Task,
    profileFallbackTaskId: Long?,
    globalFallbackTaskId: Long?,
): FallbackTaskSelection? {
    val candidates = listOfNotNull(
        profileFallbackTaskId?.takeIf { it > 0L }?.let { it to "profile" },
        globalFallbackTaskId?.takeIf { it > 0L }?.let { it to "global" },
    ).distinctBy { it.first }
    for ((id, source) in candidates) {
        if (id == failedTask.id) continue
        val task = runCatching { db.taskDao().getById(id)?.toDomainDecodeResult() }
            .getOrNull()
            ?.takeIf { it.issue == null }
            ?.value
        if (task != null) return FallbackTaskSelection(task, source)
    }
    return null
}

private fun structuredFailureMetadata(error: StructuredTaskError): List<String> = listOf(
    "Failure task: ${error.taskName} (${error.taskId})",
    "Failure action: id=${error.actionId}, index=${error.actionIndex}, type=${error.actionType}",
    "Failure attempts: ${error.attemptCount}",
    "Failure message: ${error.message.take(256)}",
    "Originating profile: ${error.originatingProfileName.orEmpty()} (${error.originatingProfileId ?: "none"})",
)

suspend fun logHeldRun(
    db: AppDatabase,
    task: Task,
    source: String,
    reason: String,
    metadata: List<String> = emptyList(),
    initialVariables: Map<String, String> = emptyMap(),
    execution: ExecutionEnvelope? = null,
    terminalReason: ExecutionTerminalReason? = null,
): Boolean {
    val envelope = execution ?: ExecutionEnvelope.create(task, source)
    ExecutionCommandLedger.accept(envelope)
    val resolvedReason = terminalReason ?: ExecutionTerminalReason(
        ExecutionTerminalReasonCode.ADMISSION_REJECTED,
        reason,
    )
    ExecutionCommandLedger.transition(envelope.executionId, ExecutionLedgerState.HELD, resolvedReason)
    val classified = RunLogSource.classify(envelope.source)
    return insertRunLog(
        db,
        RunLogEntry(
            taskId = task.id,
            taskName = task.name,
            durationMs = 0,
            success = false,
            message = heldRunLogMessage(
                source = envelope.source,
                reason = reason,
                execution = envelope,
                terminalReason = resolvedReason,
                metadata = metadata,
            ),
            source = classified.key,
            sourceLabel = classified.label,
            executionId = envelope.executionId,
            replayOf = envelope.replayOf,
            held = true,
            heldPayload = HeldExecutionPayloadCodec.encode(task, envelope, metadata, initialVariables),
            heldPolicy = reason,
        ),
    )
}

/** Re-admits a held trigger against the current task definition and creates a new command id. */
suspend fun replayHeldExecution(
    appContext: Context,
    db: AppDatabase,
    heldEntry: RunLogEntry,
    visibleActivity: Boolean = false,
    audioForegroundService: AudioForegroundServiceEligibility = AudioForegroundServiceEligibility.NONE,
    admissionController: ExecutionAdmissionController = ExecutionAdmissionController.Default,
): TaskExecutionResult {
    require(heldEntry.held) { "Only held executions can be replayed." }
    val originalExecutionId = requireNotNull(heldEntry.executionId) { "Held execution has no command id." }
    val payload = requireNotNull(HeldExecutionPayloadCodec.decode(heldEntry.heldPayload)) {
        "Held execution payload is invalid or unavailable."
    }
    require(payload.taskId == heldEntry.taskId) { "Held execution task identity does not match its payload." }
    val task = db.taskDao().getById(payload.taskId)
        ?.toDomainDecodeResult()
        ?.takeIf { it.issue == null }
        ?.value
        ?: error("The task for this held execution no longer exists or is corrupt.")
    val profile = payload.profileId?.let { profileId ->
        db.profileDao().getById(profileId)?.toDomainDecodeResult()?.takeIf { it.issue == null }?.value
    }
    val replayEnvelope = ExecutionEnvelope.create(
        task = task,
        source = payload.source,
        profileId = payload.profileId,
        replayOf = originalExecutionId,
    )
    // Consume the held entry before running it. Nothing else cleared the marker, so the Replay
    // button stayed on the row forever and every tap ran the task again with real side effects.
    // Clearing it first also means a crash mid-replay cannot leave a row that replays twice.
    db.runLogDao().clearHeld(heldEntry.id)
    return executeAndLogTask(
        appContext = appContext,
        db = db,
        task = task,
        source = payload.source,
        metadata = payload.metadata,
        initialVariables = payload.initialVariables,
        visibleActivity = visibleActivity,
        audioForegroundService = audioForegroundService,
        admissionController = admissionController,
        profileId = payload.profileId,
        profileLimits = profile?.toExecutionAdmissionProfileLimits(),
        overflowPolicy = profile?.overflowPolicy ?: ProfileOverflowPolicy.LOG,
        profileName = profile?.name,
        profileFallbackTaskId = profile?.fallbackTaskId,
        execution = replayEnvelope,
    )
}

/**
 * Resolves a sub-task by numeric id first, then by exact name (case-insensitive), for `task.run`.
 * Corrupt tasks (whose stored payload fails to decode) resolve to `null` so `task.run` fails
 * closed instead of silently running an empty action list.
 */
fun dbSubTaskResolver(db: AppDatabase): SubTaskResolver = resolver@{ ref ->
    fun TaskEntity.decodedOrNull(): Task? {
        val result = toDomainDecodeResult()
        val issue = result.issue
        if (issue != null) {
            AppLogger.error(TAG, "Sub-task '$ref' (id=$id) is corrupt: ${issue.message}")
            return null
        }
        return result.value
    }
    val byId = ref.toLongOrNull()?.let { db.taskDao().getById(it) }
    if (byId != null) return@resolver byId.decodedOrNull()
    val exact = db.taskDao().getByName(ref)
    if (exact != null) return@resolver exact.decodedOrNull()
    db.taskDao().getAll().firstOrNull { it.name.equals(ref, ignoreCase = true) }?.decodedOrNull()
}

suspend fun insertRunLog(db: AppDatabase, entry: RunLogEntry): Boolean =
    runCatching { db.runLogDao().insert(entry.toEntity()) }
        .onFailure { e -> AppLogger.error(TAG, "Failed to write run log for task ${entry.taskId}", e) }
        .isSuccess

private const val TAG = "OpenTasker"
