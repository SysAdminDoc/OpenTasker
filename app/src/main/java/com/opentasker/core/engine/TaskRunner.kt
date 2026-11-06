package com.opentasker.core.engine

import com.opentasker.core.actions.ActionArgumentSensitivity
import com.opentasker.core.capabilities.AutomationSensitivityRegistry
import com.opentasker.core.capabilities.ActionCapabilityRegistry
import com.opentasker.core.diagnostics.ExportRedactionPolicy
import com.opentasker.core.expressions.InertTemplateText
import com.opentasker.core.expressions.TemplateExpansionTrace
import com.opentasker.core.expressions.TemplateScope
import com.opentasker.core.expressions.TemplateExpressionEngine
import com.opentasker.core.model.ActionSpec
import com.opentasker.core.model.Task
import com.opentasker.core.model.VariableNamePolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeout

/** Resolves a sub-task by id or name for the `task.run` action. */
typealias SubTaskResolver = suspend (ref: String) -> Task?

/**
 * Executes a Task's action list with flow control and variable expansion.
 *
 * When a [resolveTask] resolver is supplied, the `task.run` action can execute another task as a
 * reusable sub-task, sharing this task's variable store so inputs flow in and global outputs flow
 * out. Recursion is bounded by [MAX_SUBTASK_DEPTH] to prevent infinite call chains.
 */
class TaskRunner(
    private val ctx: ActionContext,
    private val templateExpressionEngine: TemplateExpressionEngine = TemplateExpressionEngine(),
    private val resolveTask: SubTaskResolver? = null,
    private val depth: Int = 0,
    /**
     * Reports the step about to run so an in-flight execution can say what it is doing. Nested
     * sub-task runners inherit it, so a run stuck inside a sub-task still names the real step.
    */
    private val onStep: (suspend (index: Int, label: String) -> Unit)? = null,
    /** Reports a non-control action only after it has returned a result. */
    private val onStepCompleted: (suspend (index: Int, label: String) -> Unit)? = null,
    private val collisionCoordinator: TaskCollisionCoordinator? = null,
    private val executionChain: Set<Long> = emptySet(),
    private val originatingProfileId: Long? = null,
    private val originatingProfileName: String? = null,
    /** Injectable clock for deterministic scenario tests; production uses wall-clock time. */
    private val now: () -> Long = System::currentTimeMillis,
) {
    /** A live `flow.foreach` iteration in progress. */
    private class LoopFrame(
        val foreachIndex: Int,
        val items: List<String>,
        val itemVar: String,
        val sensitive: Boolean,
        var index: Int,
    )

    private enum class TryPhase { BODY, CATCH }

    private class TryFrame(
        val tryIndex: Int,
        val catchIndex: Int?,
        val endIndex: Int,
        val config: FlowControl.TryConfig,
        val loopDepth: Int,
        var attempt: Int = 1,
        var phase: TryPhase = TryPhase.BODY,
    )

    suspend fun run(task: Task): TaskRunReport {
        ctx.variables.pushScope()
        val started = now()
        val results = mutableListOf<ActionResult>()
        val traces = mutableListOf<ActionExecutionTrace>()

        val unknownActionIds = task.actions
            .map(ActionSpec::type)
            .filter { actionId ->
                !AutomationSensitivityRegistry.isKnown(actionId) && ActionRegistry.get(actionId) == null
            }
            .distinct()
            .sorted()
        if (unknownActionIds.isNotEmpty()) {
            ctx.variables.popScope()
            val failure = ActionResult.Failure(
                "task contains unknown unclassified actions: ${unknownActionIds.joinToString()}",
            )
            return preflightFailureReport(task, started, failure, "preflight", "action classification")
        }

        val unsupportedActionIds = task.actions
            .map(ActionSpec::type)
            .filter { actionId ->
                AutomationSensitivityRegistry.isKnown(actionId) &&
                    !ActionCapabilityRegistry.get(actionId).canAdd
            }
            .distinct()
            .sorted()
        if (unsupportedActionIds.isNotEmpty()) {
            ctx.variables.popScope()
            val failure = ActionResult.Failure(
                "task contains unsupported actions: ${unsupportedActionIds.joinToString()}",
            )
            return preflightFailureReport(task, started, failure, "preflight", "action capability")
        }

        val structure = FlowStructure.analyze(task.actions)
        if (structure.error != null) {
            ctx.variables.popScope()
            val failure = ActionResult.Failure("flow control error: ${structure.error}")
            return preflightFailureReport(task, started, failure, "flow", "flow control")
        }

        val loopStack = ArrayDeque<LoopFrame>()
        val tryStack = ArrayDeque<TryFrame>()
        val armedElseIndices = mutableSetOf<Int>()
        val handledFailureIndices = mutableSetOf<Int>()
        var unhandledFailure = false
        var structuredFailure: StructuredTaskError? = null
        try {
            var pc = 0
            var steps = 0
            while (pc in task.actions.indices) {
                if (++steps > MAX_FLOW_STEPS) {
                    val failure = ActionResult.Failure("flow step budget ($MAX_FLOW_STEPS) exceeded")
                    results += failure
                    traces += markerTrace(pc, task.actions[pc], failure, ActionTraceStatus.FAILURE)
                    structuredFailure = setFailureVariables(
                        task = task,
                        pc = pc,
                        spec = task.actions[pc],
                        failure = failure,
                        attempt = 1,
                        retrying = false,
                        retryReason = null,
                    )
                    ctx.variables.set(FLOW_ERROR_CAUGHT, "false")
                    unhandledFailure = true
                    break
                }
                val spec = task.actions[pc]
                if (FlowControl.isControl(spec.type)) {
                    val outcome = stepControl(pc, spec, structure, loopStack, tryStack, armedElseIndices)
                    results += outcome.result
                    traces += outcome.trace
                    if (outcome.halt) {
                        if (outcome.result is ActionResult.Failure) {
                            structuredFailure = setFailureVariables(
                                task = task,
                                pc = pc,
                                spec = spec,
                                failure = outcome.result,
                                attempt = 1,
                                retrying = false,
                                retryReason = null,
                            )
                            ctx.variables.set(FLOW_ERROR_CAUGHT, "false")
                            unhandledFailure = true
                        }
                        break
                    }
                    pc = outcome.nextPc
                    continue
                }

                val stepLabel = spec.label ?: spec.type
                onStep?.invoke(pc, stepLabel)
                val (result, trace) = runOne(pc, spec)
                onStepCompleted?.invoke(pc, stepLabel)
                results += result
                traces += trace
                if (result !is ActionResult.Failure &&
                    tryStack.any { frame ->
                        frame.phase == TryPhase.BODY && pc > frame.tryIndex && pc < frame.endIndex
                    }
                ) {
                    clearRetryVariables()
                }
                if (result is ActionResult.Failure) {
                    val recovery = recoverFailure(task, pc, spec, result, structure, loopStack, tryStack)
                    recovery.reason?.let { reason ->
                        traces[traces.lastIndex] = traces.last().copy(
                            message = "${traces.last().message}; $reason",
                        )
                    }
                    if (recovery.nextPc != null) {
                        handledFailureIndices += results.lastIndex
                        pc = recovery.nextPc
                        continue
                    }
                    if (structuredFailure == null) {
                        structuredFailure = setFailureVariables(
                            task = task,
                            pc = pc,
                            spec = spec,
                            failure = result,
                            attempt = recovery.attemptCount,
                            retrying = false,
                            retryReason = recovery.reason,
                        )
                        ctx.variables.set(FLOW_ERROR_CAUGHT, "false")
                    }
                    if (!spec.continueOnError) {
                        unhandledFailure = true
                        break
                    }
                }
                pc++
            }
        } finally {
            ctx.variables.popScope()
        }
        return TaskRunReport(
            taskId = task.id,
            taskName = task.name,
            startedAt = started,
            durationMs = now() - started,
            results = results,
            traces = traces,
            success = !unhandledFailure && results.withIndex().all { (index, result) ->
                result !is ActionResult.Failure || index in handledFailureIndices
            },
            structuredError = structuredFailure,
        )
    }

    private fun preflightFailureReport(
        task: Task,
        started: Long,
        failure: ActionResult.Failure,
        actionType: String,
        label: String,
    ): TaskRunReport {
        return TaskRunReport(
            taskId = task.id,
            taskName = task.name,
            startedAt = started,
            durationMs = now() - started,
            results = listOf(failure),
            traces = listOf(
                ActionExecutionTrace(
                    index = 0,
                    actionType = actionType,
                    label = label,
                    durationMs = 0,
                    status = ActionTraceStatus.FAILURE,
                    message = failure.message,
                ),
            ),
            success = false,
            structuredError = StructuredTaskError(
                taskId = task.id,
                taskName = task.name,
                actionId = 0L,
                actionIndex = 0,
                actionType = actionType,
                message = failure.message,
                attemptCount = 1,
                originatingProfileId = originatingProfileId,
                originatingProfileName = originatingProfileName,
            ),
        )
    }

    private class ControlOutcome(
        val result: ActionResult,
        val trace: ActionExecutionTrace,
        val nextPc: Int,
        val halt: Boolean = false,
    )

    private data class FailureRecovery(
        val nextPc: Int?,
        val reason: String? = null,
        val attemptCount: Int = 1,
    )

    private fun stepControl(
        pc: Int,
        spec: ActionSpec,
        structure: FlowStructure,
        loopStack: ArrayDeque<LoopFrame>,
        tryStack: ArrayDeque<TryFrame>,
        armedElseIndices: MutableSet<Int>,
    ): ControlOutcome {
        fun outcome(message: String, nextPc: Int, halt: Boolean = false) = ControlOutcome(
            result = ActionResult.Success,
            trace = markerTrace(pc, spec, ActionResult.Success, ActionTraceStatus.SUCCESS, message),
            nextPc = nextPc,
            halt = halt,
        )

        fun skipped(message: String, nextPc: Int) = ControlOutcome(
            result = ActionResult.Skip,
            trace = markerTrace(pc, spec, ActionResult.Skip, ActionTraceStatus.SKIPPED, message),
            nextPc = nextPc,
        )

        // The generic "Run only if" guard (ActionSpec.condition — what a Tasker <ConditionList>
        // imports to). Non-control actions evaluate it in runOne via shouldRun, but control
        // actions are dispatched here before runOne is ever reached, so each branch with real
        // behavior applies it explicitly. Tasker semantics: an unmet guard skips the action, it
        // never aborts the task. For the block-opening markers (foreach, try) the whole block is
        // the action, so skipping the marker skips its block — falling through into the body
        // would execute it with no active frame and abort at the closing marker. flow.if handles
        // the guard inside its own branch because the importer copies the guard into
        // args["condition"] (both fields equal), and that one expression must not apply twice.
        // The remaining markers are structural or error-path bookkeeping (endif, catch, endtry)
        // and deliberately ignore the guard: skipping them would corrupt block state.
        val guard = spec.condition?.trim()?.takeIf { it.isNotBlank() }
        fun guardMet(): Boolean = guard == null || evaluateConditionString(guard)

        return when (spec.type) {
            FlowControl.IF -> {
                val argCondition = spec.args["condition"]?.trim()?.takeIf { it.isNotBlank() }
                val condition = argCondition ?: guard ?: "true"
                // A guard distinct from the if's own test gates the branch alongside it: both
                // must hold. When the guard IS the test (an imported ConditionList lands in both
                // fields with the same text) it is evaluated once.
                val distinctGuard = guard != null && argCondition != null && guard != argCondition
                val display = if (distinctGuard) "$condition, only if $guard" else condition
                val matched = evaluateConditionString(condition) && (!distinctGuard || guardMet())
                if (matched) {
                    outcome("if ($display) -> true", pc + 1)
                } else {
                    val elseIndex = structure.ifToElse[pc]
                    if (elseIndex != null) {
                        // Land on the else marker itself (not past it) so an "Else If" guard on
                        // that marker can decide whether its branch runs.
                        armedElseIndices += elseIndex
                        outcome("if ($display) -> false", elseIndex)
                    } else {
                        outcome("if ($display) -> false", structure.ifToEndif.getValue(pc) + 1)
                    }
                }
            }
            FlowControl.ELSE -> {
                if (armedElseIndices.remove(pc)) {
                    // Reached from a false if: this marker opens the else branch. A guard here is
                    // Tasker's "Else If" — unmet means the branch is skipped, not run.
                    if (guardMet()) {
                        outcome(if (guard == null) "else" else "else if ($guard) -> true", pc + 1)
                    } else {
                        skipped("else if ($guard) -> false", structure.elseToEndif.getValue(pc) + 1)
                    }
                } else {
                    // Fell in from the end of the taken if branch: exit the block.
                    outcome("else", structure.elseToEndif.getValue(pc) + 1)
                }
            }
            FlowControl.ENDIF -> outcome("endif", pc + 1)
            FlowControl.FOREACH -> if (!guardMet()) {
                skipped("foreach skipped: condition ($guard) -> false", structure.foreachToEndfor.getValue(pc) + 1)
            } else {
                val listName = listOf("list", "in", "array", "items")
                    .firstNotNullOfOrNull { key ->
                        spec.args[key]?.trim()?.takeIf(String::isNotBlank)?.let { raw ->
                            arrayReferenceName(spec.type, key, raw) ?: raw
                        }
                    }
                val itemVar = spec.args["var"]?.trim()?.takeIf { it.isNotBlank() } ?: "item"
                val items = listName?.let { ctx.variables.getArrayItems(it) }.orEmpty()
                val endfor = structure.foreachToEndfor.getValue(pc)
                if (items.isEmpty()) {
                    outcome("foreach $listName -> 0 items", endfor + 1)
                } else {
                    val sensitive = listName?.let(ctx.variables::isArraySensitive) == true
                    loopStack.addLast(LoopFrame(pc, items, itemVar, sensitive, 0))
                    ctx.variables.set(itemVar, items[0], sensitive = sensitive)
                    outcome("foreach $listName -> ${items.size} items (1/${items.size})", pc + 1)
                }
            }
            FlowControl.ENDFOR -> {
                val frame = loopStack.lastOrNull()
                if (frame == null || frame.foreachIndex != structure.endforToForeach[pc]) {
                    ControlOutcome(
                        result = ActionResult.Failure("flow.endfor without an active loop"),
                        trace = markerTrace(pc, spec, ActionResult.Failure("flow.endfor without an active loop"), ActionTraceStatus.FAILURE),
                        nextPc = pc + 1,
                        halt = true,
                    )
                } else if (!guardMet()) {
                    // Tasker semantics for a skipped End For: no jump back happens, so the loop
                    // exits and execution continues after the marker.
                    loopStack.removeLast()
                    skipped("endfor skipped: condition ($guard) -> false; loop exited", pc + 1)
                } else {
                    frame.index++
                    if (frame.index < frame.items.size) {
                        ctx.variables.set(frame.itemVar, frame.items[frame.index], sensitive = frame.sensitive)
                        outcome("loop ${frame.index + 1}/${frame.items.size}", frame.foreachIndex + 1)
                    } else {
                        loopStack.removeLast()
                        outcome("endfor", pc + 1)
                    }
                }
            }
            FlowControl.TRY -> if (!guardMet()) {
                skipped("try skipped: condition ($guard) -> false", structure.tryToEndtry.getValue(pc) + 1)
            } else {
                val config = FlowControl.parseTryConfig(spec.args)
                    ?: return ControlOutcome(
                        result = ActionResult.Failure("invalid flow.try retry bounds"),
                        trace = markerTrace(pc, spec, ActionResult.Failure("invalid flow.try retry bounds"), ActionTraceStatus.FAILURE),
                        nextPc = pc + 1,
                        halt = true,
                    )
                tryStack.addLast(
                    TryFrame(
                        tryIndex = pc,
                        catchIndex = structure.tryToCatch[pc],
                        endIndex = structure.tryToEndtry.getValue(pc),
                        config = config,
                        loopDepth = loopStack.size,
                    ),
                )
                outcome("try attempt 1/${config.maxAttempts}", pc + 1)
            }
            FlowControl.CATCH -> {
                val frame = tryStack.lastOrNull()
                if (frame == null || frame.catchIndex != pc) {
                    ControlOutcome(
                        result = ActionResult.Failure("flow.catch without an active try"),
                        trace = markerTrace(pc, spec, ActionResult.Failure("flow.catch without an active try"), ActionTraceStatus.FAILURE),
                        nextPc = pc + 1,
                        halt = true,
                    )
                } else if (frame.phase == TryPhase.BODY) {
                    // The try body completed normally; skip the handler and its end marker.
                    tryStack.removeLast()
                    outcome("catch skipped; try succeeded", frame.endIndex + 1)
                } else {
                    ctx.variables.set(FLOW_ERROR_CAUGHT, "true")
                    outcome("catch", pc + 1)
                }
            }
            FlowControl.ENDTRY -> {
                val frame = tryStack.removeLastOrNull()
                if (frame == null || frame.endIndex != pc) {
                    ControlOutcome(
                        result = ActionResult.Failure("flow.endtry without an active try"),
                        trace = markerTrace(pc, spec, ActionResult.Failure("flow.endtry without an active try"), ActionTraceStatus.FAILURE),
                        nextPc = pc + 1,
                        halt = true,
                    )
                } else {
                    outcome("endtry", pc + 1)
                }
            }
            FlowControl.STOP -> if (!guardMet()) {
                skipped("stop skipped: condition ($guard) -> false", pc + 1)
            } else {
                outcome("stop", pc + 1, halt = true)
            }
            else -> outcome(spec.type, pc + 1)
        }
    }

    /**
     * Whether replaying the whole try body is safe.
     *
     * A retry restarts at `tryIndex + 1`, so every action in the body runs again - not just the one
     * that failed. Checking only the failing action let a body like `[sms.send, http.get]` re-send
     * the message each time the HTTP call failed. Control markers carry no side effects and are
     * skipped; an unknown action id is treated as unsafe.
     */
    private fun tryBodyIsRetrySafe(task: Task, frame: TryFrame): Boolean {
        val bodyEnd = frame.catchIndex ?: frame.endIndex
        return ((frame.tryIndex + 1) until bodyEnd)
            .mapNotNull { index -> task.actions.getOrNull(index) }
            .filterNot { bodySpec -> FlowControl.isControl(bodySpec.type) }
            .all { bodySpec ->
                ActionRegistry.get(bodySpec.type)?.retrySafetyFor(bodySpec.args) == ActionRetrySafety.IDEMPOTENT
            }
    }

    private suspend fun recoverFailure(
        task: Task,
        pc: Int,
        spec: ActionSpec,
        failure: ActionResult.Failure,
        structure: FlowStructure,
        loopStack: ArrayDeque<LoopFrame>,
        tryStack: ArrayDeque<TryFrame>,
    ): FailureRecovery {
        var nonRetryReason: String? = null
        var lastAttempt = 1
        while (true) {
            val frame = tryStack.asReversed().firstOrNull { candidate ->
                candidate.phase == TryPhase.BODY && pc > candidate.tryIndex && pc < candidate.endIndex
            } ?: return FailureRecovery(nextPc = null, reason = nonRetryReason, attemptCount = lastAttempt)

            while (tryStack.lastOrNull() !== frame) tryStack.removeLast()
            lastAttempt = frame.attempt
            if (frame.attempt < frame.config.maxAttempts && tryBodyIsRetrySafe(task, frame)) {
                setFailureVariables(task, pc, spec, failure, frame.attempt, retrying = true, retryReason = null)
                frame.attempt++
                clearLoopsToDepth(loopStack, frame.loopDepth)
                val waitMs = retryBackoffMs(frame.config.backoffMs, frame.attempt - 1)
                if (waitMs > 0) delay(waitMs)
                return FailureRecovery(
                    nextPc = frame.tryIndex + 1,
                    reason = nonRetryReason,
                    attemptCount = frame.attempt - 1,
                )
            } else if (frame.attempt < frame.config.maxAttempts || frame.config.maxAttempts > 1) {
                nonRetryReason = retryReason(spec, ActionRegistry.get(spec.type)?.retrySafetyFor(spec.args))
            }

            frame.catchIndex?.let { catchIndex ->
                setFailureVariables(
                    task,
                    pc,
                    spec,
                    failure,
                    frame.attempt,
                    retrying = false,
                    retryReason = nonRetryReason,
                )
                frame.phase = TryPhase.CATCH
                clearLoopsToDepth(loopStack, frame.loopDepth)
                // Resume on the CATCH marker itself, not past it. Jumping to catchIndex + 1 skipped
                // the only place that records the failure as caught, so %FLOW_ERROR_CAUGHT read
                // "false" inside every flow.catch handler and the marker's branch was dead code.
                return FailureRecovery(
                    nextPc = catchIndex,
                    reason = nonRetryReason,
                    attemptCount = frame.attempt,
                )
            }

            // An uncaught nested failure propagates to the enclosing try block.
            tryStack.removeLast()
        }
    }

    private fun retryReason(spec: ActionSpec, safety: ActionRetrySafety?): String = when (safety) {
        ActionRetrySafety.NEVER ->
            "not retried: ${spec.type} is classified NEVER; flow.try retries only IDEMPOTENT actions"
        ActionRetrySafety.IDEMPOTENT ->
            "not retried: ${spec.type} exhausted the configured attempts"
        null ->
            "not retried: ${spec.type} has no runtime retry classification"
    }

    private fun setFailureVariables(
        task: Task,
        pc: Int,
        spec: ActionSpec,
        failure: ActionResult.Failure,
        attempt: Int,
        retrying: Boolean,
        retryReason: String?,
        structuredError: StructuredTaskError? = null,
    ): StructuredTaskError {
        val error = structuredError ?: failure.structuredError ?: StructuredTaskError(
            taskId = task.id,
            taskName = task.name,
            actionId = spec.id,
            actionIndex = pc + 1,
            actionType = spec.type,
            message = failure.message,
            attemptCount = attempt.coerceAtLeast(1),
            originatingProfileId = originatingProfileId,
            originatingProfileName = originatingProfileName,
        )
        ctx.variables.set(FLOW_ERROR_JSON, StructuredTaskErrorCodec.encode(error))
        ctx.variables.set(FLOW_ERROR_TASK_ID, error.taskId.toString())
        ctx.variables.set(FLOW_ERROR_TASK_NAME, error.taskName)
        ctx.variables.set(FLOW_ERROR_ACTION_ID, error.actionId.toString())
        ctx.variables.set(FLOW_ERROR_MESSAGE, error.message)
        ctx.variables.set(FLOW_ERROR_ACTION, error.actionType)
        ctx.variables.set(FLOW_ERROR_INDEX, error.actionIndex.toString())
        ctx.variables.set(FLOW_ERROR_TYPE, error.actionType)
        ctx.variables.set(FLOW_ERROR_ATTEMPT, error.attemptCount.toString())
        ctx.variables.set(FLOW_ERROR_RETRYING, retrying.toString())
        ctx.variables.set(FLOW_ERROR_RETRY_REASON, retryReason.orEmpty())
        ctx.variables.set(FLOW_ERROR_PROFILE_ID, error.originatingProfileId?.toString().orEmpty())
        ctx.variables.set(FLOW_ERROR_PROFILE_NAME, error.originatingProfileName.orEmpty())
        return error
    }

    private fun clearRetryVariables() {
        ctx.variables.set(FLOW_ERROR_RETRYING, "false")
        ctx.variables.set(FLOW_ERROR_RETRY_REASON, "")
    }

    private fun clearLoopsToDepth(loopStack: ArrayDeque<LoopFrame>, depth: Int) {
        while (loopStack.size > depth) loopStack.removeLast()
    }

    private fun markerTrace(
        index: Int,
        spec: ActionSpec,
        result: ActionResult,
        status: ActionTraceStatus,
        message: String = spec.type,
    ): ActionExecutionTrace = ActionExecutionTrace(
        index = index,
        actionType = spec.type,
        label = spec.label ?: spec.type,
        durationMs = 0,
        status = status,
        message = message,
    )

    private suspend fun runOne(index: Int, spec: ActionSpec): Pair<ActionResult, ActionExecutionTrace> {
        val started = now()
        if (!shouldRun(spec)) {
            val result = ActionResult.Skip
            return result to traceFor(index, spec, started, result, ActionArgumentExpansionReport.Empty)
        }

        if (spec.type == SUB_TASK_ACTION_ID) {
            return runSubTask(index, spec, started)
        }

        val action = ActionRegistry.get(spec.type)
            ?: ActionResult.Failure("unknown action: ${spec.type}").let { result ->
                return result to traceFor(index, spec, started, result, ActionArgumentExpansionReport.Empty)
            }
        val expansionReport = expandArgs(spec.type, spec.args)
        val timeoutMs = actionTimeoutMs(spec.type)
        val before = ctx.variables.toTemplateScope()
        val rawResult = try {
            withTimeout(timeoutMs) {
                ctx.variables.withSensitiveWrites(expansionReport.hasSecretDerivedValues()) {
                    action.run(ctx.forAction(expansionReport.sensitiveArgumentNames()), expansionReport.args)
                }
            }
        } catch (e: TimeoutCancellationException) {
            ActionResult.Failure("timed out after ${timeoutMs / 1000}s")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ActionResult.Failure("threw: ${e.message}", e)
        }
        val result = if (rawResult is ActionResult.Failure) {
            // Preserve useful failure context while applying the same field-aware policy used by
            // exports. Drop the cause whenever an action received a sensitive value because a
            // Throwable message/stack cannot be redacted in place.
            val redactedMessage = expansionReport.redactSensitiveValues(rawResult.message)
            if (expansionReport.hasSensitiveValues() || redactedMessage != rawResult.message) {
                ActionResult.Failure(
                    message = redactedMessage,
                    structuredError = rawResult.structuredError,
                )
            } else {
                rawResult
            }
        } else {
            rawResult
        }
        val changes = variableChangesBetween(before, ctx.variables.toTemplateScope())
        return result to traceFor(index, spec, started, result, expansionReport, changes)
    }

    private suspend fun runSubTask(
        index: Int,
        spec: ActionSpec,
        started: Long,
    ): Pair<ActionResult, ActionExecutionTrace> {
        val expansionReport = expandArgs(spec.type, spec.args)
        val args = expansionReport.args

        fun fail(message: String): Pair<ActionResult, ActionExecutionTrace> {
            val result = ActionResult.Failure(message)
            return result to traceFor(index, spec, started, result, expansionReport)
        }

        val resolver = resolveTask ?: return fail("sub-tasks are not available in this context")
        if (depth >= MAX_SUBTASK_DEPTH) {
            return fail("sub-task depth limit ($MAX_SUBTASK_DEPTH) exceeded; possible recursion")
        }

        val ref = SUB_TASK_REF_KEYS.firstNotNullOfOrNull { args[it]?.trim()?.takeIf(String::isNotBlank) }
            ?: return fail("task.run requires a 'task' (id or name)")
        val target = resolver(ref) ?: return fail("sub-task not found: $ref")
        if (target.id > 0L && target.id in executionChain) {
            return fail("sub-task '${target.name}' is already active in this execution chain")
        }

        // Pass any extra args as input variables scoped to the sub-task invocation: a dedicated
        // scope wraps the child run so local (lowercase) inputs are visible to the child through the
        // scope chain but are popped when it returns, never leaking into the parent's later actions.
        // Global (uppercase) outputs still flow back through the shared global namespace.
        val child = TaskRunner(
            ctx = ctx,
            templateExpressionEngine = templateExpressionEngine,
            resolveTask = resolveTask,
            depth = depth + 1,
            onStep = onStep,
            onStepCompleted = onStepCompleted,
            collisionCoordinator = collisionCoordinator,
            executionChain = executionChain + target.id,
            originatingProfileId = originatingProfileId,
            originatingProfileName = originatingProfileName,
            now = now,
        )
        ctx.variables.pushScope()
        val report = try {
            args.forEach { (key, value) ->
                if (key !in SUB_TASK_REF_KEYS) {
                    ctx.variables.set(key, value, sensitive = expansionReport.isArgumentSensitive(key))
                }
            }
            val coordinator = collisionCoordinator
            val outcome = if (coordinator == null) {
                TaskCollisionOutcome.Executed(child.run(target))
            } else {
                runNestedUnderItsOwnJob(coordinator, target, child)
            }
            when (outcome) {
                is TaskCollisionOutcome.Executed -> outcome.value
                is TaskCollisionOutcome.Skipped -> return fail(outcome.reason)
            }
        } finally {
            ctx.variables.popScope()
        }
        val result = if (report.success) {
            ActionResult.Success
        } else {
            ActionResult.Failure(
                "sub-task '${target.name}' failed",
                structuredError = report.structuredError,
            )
        }
        return result to traceFor(index, spec, started, result, expansionReport)
    }

    /**
     * Runs a sub-task in a coroutine of its own so the collision coordinator registers *it*.
     *
     * The coordinator identifies a running invocation by the current Job. A sub-task used to run
     * directly in the caller's coroutine, so the Job it registered for the sub-task was the
     * caller's whole execution: a later Abort existing run of the sub-task cancelled the caller,
     * which had no collision of its own and was logged as "Replaced by a newer run", and an Abort
     * new run of the sub-task was refused because the caller looked like a run of it.
     *
     * `supervisorScope` keeps that cancellation from reaching the caller, so the sub-task step
     * fails and the caller's own `continueOnError` decides what happens next. A cancellation of
     * the caller itself still propagates, which is what `ensureActive` distinguishes.
     */
    private suspend fun runNestedUnderItsOwnJob(
        coordinator: TaskCollisionCoordinator,
        target: Task,
        child: TaskRunner,
    ): TaskCollisionOutcome<TaskRunReport> = supervisorScope {
        val nested = async { coordinator.execute(target) { child.run(target) } }
        try {
            nested.await()
        } catch (cancelled: CancellationException) {
            coroutineContext.ensureActive()
            TaskCollisionOutcome.Skipped(
                cancelled.message ?: "The sub-task was replaced by a newer run.",
            )
        }
    }

    private fun shouldRun(spec: ActionSpec): Boolean {
        val condition = spec.condition?.trim()?.takeIf { it.isNotBlank() } ?: return true
        return evaluateConditionString(condition)
    }

    /** Evaluates a condition string with legacy `%var` then bounded `{{ ... }}` expansion. */
    private fun evaluateConditionString(condition: String): Boolean {
        val legacyExpanded = ctx.variables.expandTracked(condition)
        if (!legacyExpanded.value.contains("{{")) return ctx.variables.evaluateCondition(legacyExpanded.text)

        val expanded = templateExpressionEngine.expand(legacyExpanded.value, ctx.variables.toTemplateScope(ctx.eventVariables))
        if (expanded.warnings.isNotEmpty()) return false
        return ctx.variables.evaluateCondition(InertTemplateText.restore(expanded.value))
    }

    private fun traceFor(
        index: Int,
        spec: ActionSpec,
        started: Long,
        result: ActionResult,
        expansionReport: ActionArgumentExpansionReport,
        variableChanges: List<ActionVariableChange> = emptyList(),
    ): ActionExecutionTrace = ActionExecutionTrace(
        index = index,
        actionType = spec.type,
        label = spec.label ?: spec.type,
        durationMs = now() - started,
        status = when (result) {
            is ActionResult.Success -> ActionTraceStatus.SUCCESS
            is ActionResult.Failure -> if (result.message.startsWith("timed out")) ActionTraceStatus.TIMEOUT else ActionTraceStatus.FAILURE
            is ActionResult.Skip -> ActionTraceStatus.SKIPPED
        },
        message = when (result) {
            is ActionResult.Failure -> result.message
            is ActionResult.Skip -> "Skipped"
            is ActionResult.Success -> "Completed"
        },
        expandedArgSummary = expansionReport.summary(),
        templateWarnings = expansionReport.templateWarnings(),
        argumentExpansions = expansionReport.expansions,
        variableChanges = variableChanges,
    )

    private fun expandArgs(actionType: String, args: Map<String, String>): ActionArgumentExpansionReport {
        if (args.isEmpty()) return ActionArgumentExpansionReport.Empty

        val templateScope = ctx.variables.toTemplateScope(ctx.eventVariables)
        val expansions = mutableListOf<ActionArgumentExpansionTrace>()
        val expandedArgs = args.mapValues { (name, rawValue) ->
            arrayReferenceName(actionType, name, rawValue)?.let { arrayName ->
                return@mapValues arrayName
            }
            val legacy = ctx.variables.expandTracked(rawValue)
            if (!legacy.value.contains("{{")) {
                if (legacy.isSecretDerived) {
                    expansions += ActionArgumentExpansionTrace(
                        argName = name,
                        rawValue = rawValue,
                        expandedValue = REDACTED_VALUE,
                        expressions = emptyList(),
                        warnings = emptyList(),
                        isSecretDerived = true,
                    )
                } else if (actionType == SUB_TASK_ACTION_ID && name !in SUB_TASK_REF_KEYS) {
                    // Only expansions get a trace, so a sub-task input typed as plain text would
                    // otherwise never appear in the run log of the step that passed it.
                    val shown = if (ActionArgumentSensitivity.isSensitive(actionType, name)) REDACTED_VALUE else legacy.text
                    expansions += ActionArgumentExpansionTrace(
                        argName = name,
                        rawValue = shown,
                        expandedValue = shown,
                        expressions = emptyList(),
                        warnings = emptyList(),
                        isSecretDerived = false,
                    )
                }
                return@mapValues legacy.text
            }

            val result = templateExpressionEngine.expand(legacy.value, templateScope)
            val expanded = InertTemplateText.restore(result.value)
            val isSecretDerived = legacy.isSecretDerived || result.traces.any { it.isSecretDerived }
            if (result.traces.isNotEmpty() || result.warnings.isNotEmpty() || isSecretDerived) {
                expansions += ActionArgumentExpansionTrace(
                    argName = name,
                    rawValue = rawValue,
                    expandedValue = if (isSecretDerived) REDACTED_VALUE else expanded,
                    expressions = result.traces.map { trace ->
                        if (trace.isSecretDerived) trace.copy(value = REDACTED_VALUE) else trace
                    },
                    warnings = result.warnings,
                    isSecretDerived = isSecretDerived,
                )
            }
            expanded
        }

        return ActionArgumentExpansionReport(expandedArgs, expansions, actionType)
    }

    /**
     * Array inputs consume a variable name, not the array's rendered contents. A typed chip uses
     * `{{ array.parts }}` so the reference remains explicit in stored text; preserve that exact
     * name for the two actions whose contracts expect an array slot.
     */
    private fun arrayReferenceName(actionType: String, argumentName: String, rawValue: String): String? {
        val validArgument = when (actionType) {
            "text.join" -> argumentName == "array"
            FlowControl.FOREACH -> argumentName in setOf("list", "in", "array", "items")
            else -> false
        }
        if (!validArgument) return null
        val match = ARRAY_REFERENCE.matchEntire(rawValue.trim()) ?: return null
        return VariableNamePolicy.normalize(match.groupValues[1])
    }

    private companion object {
        // The closing braces must be escaped: Android's ICU regex engine rejects a bare "}}"
        // where desktop java.util.regex accepts it. Because this sits in a companion
        // initializer, the failure was an ExceptionInInitializerError that made TaskRunner
        // unusable on device - every task execution, not only ones using array references.
        val ARRAY_REFERENCE = Regex("\\{\\{\\s*array\\.([A-Za-z][A-Za-z0-9_-]*)\\s*\\}\\}")
    }
}

data class TaskRunReport(
    val taskId: Long,
    val taskName: String,
    val startedAt: Long,
    val durationMs: Long,
    val results: List<ActionResult>,
    val traces: List<ActionExecutionTrace>,
    val success: Boolean,
    /** Present only when the task completed with an unhandled failure. */
    val structuredError: StructuredTaskError? = null,
)

enum class ActionTraceStatus {
    SUCCESS,
    FAILURE,
    TIMEOUT,
    SKIPPED,
}

private fun actionTimeoutMs(actionType: String): Long = when {
    actionType == "flow.wait" -> MAX_WAIT_TIMEOUT_MS
    // The two other network actions belong in this bucket too. The HA webhook's own contract
    // allows timeout_sec=30 across four attempts plus backoff (~140 s) and MQTT allows a 30 s
    // connect plus three 30 s reads, so the 60 s default silently truncated retries the user
    // configured and reported a timeout instead.
    actionType.startsWith("http.") || actionType == "download" || actionType == "ping" ||
        actionType == "mqtt.publish" || actionType == "integration.home_assistant.webhook" -> 120_000L
    // Playback and speech suspend until completion; a long sound file or near-limit TTS
    // text legitimately outlives the default 60 s budget.
    actionType == "sound.play" || actionType == "tts.speak" -> MEDIA_ACTION_TIMEOUT_MS
    else -> DEFAULT_ACTION_TIMEOUT_MS
}

private const val DEFAULT_ACTION_TIMEOUT_MS = 60_000L
private const val MEDIA_ACTION_TIMEOUT_MS = 600_000L // 10 minutes

// The engine budget must exceed WaitAction.MAX_WAIT_MS (30 min): the timeout clock starts
// before the action parses its arguments, so an equal budget deterministically failed a
// wait at the documented maximum.
private const val MAX_WAIT_TIMEOUT_MS = 1_860_000L // 30 minutes + 60 s margin

const val SUB_TASK_ACTION_ID = "task.run"
const val MAX_SUBTASK_DEPTH = 8
/** Argument keys `task.run` accepts as its target, in precedence order. */
val SUB_TASK_REF_KEYS = listOf("task", "name", "id")

/** Safety cap on total interpreted steps to bound pathological flow.foreach loops. */
private const val MAX_FLOW_STEPS = 100_000
private const val FLOW_ERROR_JSON = TaskFailureVariables.JSON
private const val FLOW_ERROR_TASK_ID = TaskFailureVariables.TASK_ID
private const val FLOW_ERROR_TASK_NAME = TaskFailureVariables.TASK_NAME
private const val FLOW_ERROR_ACTION_ID = TaskFailureVariables.ACTION_ID
private const val FLOW_ERROR_MESSAGE = TaskFailureVariables.MESSAGE
private const val FLOW_ERROR_ACTION = TaskFailureVariables.ACTION
private const val FLOW_ERROR_INDEX = TaskFailureVariables.ACTION_INDEX
private const val FLOW_ERROR_TYPE = TaskFailureVariables.ACTION_TYPE
private const val FLOW_ERROR_ATTEMPT = TaskFailureVariables.ATTEMPT
private const val FLOW_ERROR_RETRYING = TaskFailureVariables.RETRYING
private const val FLOW_ERROR_RETRY_REASON = TaskFailureVariables.RETRY_REASON
private const val FLOW_ERROR_PROFILE_ID = TaskFailureVariables.ORIGINATING_PROFILE_ID
private const val FLOW_ERROR_PROFILE_NAME = TaskFailureVariables.ORIGINATING_PROFILE_NAME
private const val FLOW_ERROR_CAUGHT = TaskFailureVariables.CAUGHT

private fun retryBackoffMs(baseMs: Long, retryNumber: Int): Long {
    if (baseMs <= 0L) return 0L
    val multiplier = 1L shl retryNumber.coerceIn(0, 16)
    return (baseMs * multiplier).coerceAtMost(FlowControl.MAX_BACKOFF_MS)
}

data class ActionExecutionTrace(
    val index: Int,
    val actionType: String,
    val label: String,
    val durationMs: Long,
    val status: ActionTraceStatus,
    val message: String,
    val expandedArgSummary: String? = null,
    val templateWarnings: List<String> = emptyList(),
    val argumentExpansions: List<ActionArgumentExpansionTrace> = emptyList(),
    val variableChanges: List<ActionVariableChange> = emptyList(),
)

/**
 * One variable an action added or modified. Captured per step so a finished run answers "what did
 * this task actually set?" — the run log previously showed only what went *into* each action.
 */
data class ActionVariableChange(
    val scope: VariableChangeScope,
    val name: String,
    val value: String,
    val added: Boolean,
    val sensitive: Boolean = false,
)

enum class VariableChangeScope { TASK, GLOBAL, ARRAY }

/**
 * Variables an action added or modified, derived by diffing the store around the call.
 *
 * Deltas are used rather than a full snapshot because a run's interesting output is what changed,
 * and a snapshot per step would grow the run log with the same untouched globals over and over.
 * Sensitive names carry the flag so the value is redacted at the serialization boundary — the
 * value itself is never written to the log.
 */
internal fun variableChangesBetween(
    before: TemplateScope,
    after: TemplateScope,
): List<ActionVariableChange> = buildList {
    fun diff(
        scope: VariableChangeScope,
        beforeValues: Map<String, String>,
        afterValues: Map<String, String>,
        sensitiveNames: Set<String>,
    ) {
        afterValues.entries
            .sortedBy { it.key }
            .forEach { (name, value) ->
                if (!beforeValues.containsKey(name)) {
                    add(ActionVariableChange(scope, name, value, added = true, sensitive = name in sensitiveNames))
                } else if (beforeValues[name] != value) {
                    add(ActionVariableChange(scope, name, value, added = false, sensitive = name in sensitiveNames))
                }
            }
    }

    diff(VariableChangeScope.TASK, before.task, after.task, after.sensitiveTask)
    diff(VariableChangeScope.GLOBAL, before.global, after.global, after.sensitiveGlobal)
    diff(
        VariableChangeScope.ARRAY,
        before.arrays.mapValues { (_, items) -> items.joinToString(", ") },
        after.arrays.mapValues { (_, items) -> items.joinToString(", ") },
        after.sensitiveArrays,
    )
}

data class ActionArgumentExpansionTrace(
    val argName: String,
    val rawValue: String,
    val expandedValue: String,
    val expressions: List<TemplateExpansionTrace>,
    val warnings: List<String>,
    val isSecretDerived: Boolean = false,
)

fun ActionExecutionTrace.toSummaryLine(): String =
    "${index + 1}. ${status.name.lowercase()}: $label [$actionType] ${durationMs}ms - $message${traceDetailSuffix()}"

fun List<ActionExecutionTrace>.toRunLogMessage(maxLines: Int = 8): String {
    if (isEmpty()) return "No actions executed"
    val visible = take(maxLines).flatMap { it.toRunLogLines() }.joinToString("\n")
    val remaining = size - maxLines
    return if (remaining > 0) "$visible\n... $remaining more action(s)" else visible
}

private data class ActionArgumentExpansionReport(
    val args: Map<String, String>,
    val expansions: List<ActionArgumentExpansionTrace>,
    val actionType: String? = null,
) {
    fun templateWarnings(): List<String> =
        expansions.flatMap { expansion -> expansion.warnings.map { "${expansion.argName}: $it" } }.distinct()

    fun summary(): String? {
        if (expansions.isEmpty()) return null
        return expansions
            .take(MAX_SUMMARY_ARGS)
            .joinToString(", ") { expansion ->
                "${expansion.argName}=${summarizeArgValue(actionType, expansion.argName, expansion.expandedValue, expansion.isSecretDerived)}"
            }
            .let { summary ->
                val remaining = expansions.size - MAX_SUMMARY_ARGS
                if (remaining > 0) "$summary, +$remaining more" else summary
            }
    }

    fun hasSecretDerivedValues(): Boolean = expansions.any { it.isSecretDerived }

    /**
     * Removes the raw values of secret-derived arguments from [message]. The raw expanded values
     * live in [args] (what the action actually received); the per-expansion trace already stores a
     * redacted placeholder, so it cannot be used for scrubbing. Longest values are replaced first so
     * a secret that contains a shorter secret as a substring is fully removed.
     */
    fun redactSensitiveValues(message: String): String {
        val sensitiveValues = args
            .filter { (name, _) ->
                ActionArgumentSensitivity.isSensitive(actionType, name, args) ||
                    expansions.any { it.argName == name && it.isSecretDerived }
            }
            .values
            .mapNotNull { it.takeIf(String::isNotBlank) }
            .distinct()
            .sortedByDescending { it.length }
        return ExportRedactionPolicy.redactText(message, sensitiveValues, REDACTED_VALUE)
    }

    /** Compatibility name retained for source-level callers; the policy now covers all fields. */
    fun redactSecretDerivedValues(message: String): String = redactSensitiveValues(message)

    fun hasSensitiveValues(): Boolean = args.keys.any { name ->
        ActionArgumentSensitivity.isSensitive(actionType, name, args) ||
            expansions.any { it.argName == name && it.isSecretDerived }
    }

    fun sensitiveArgumentNames(): Set<String> = expansions
        .filter { it.isSecretDerived }
        .mapTo(linkedSetOf()) { it.argName }

    fun isArgumentSensitive(name: String): Boolean = expansions.any {
        it.argName == name && it.isSecretDerived
    }

    companion object {
        val Empty = ActionArgumentExpansionReport(emptyMap(), emptyList())
    }
}

private fun ActionExecutionTrace.traceDetailSuffix(): String {
    val details = buildList {
        expandedArgSummary?.takeIf { it.isNotBlank() }?.let { add("args: $it") }
        if (templateWarnings.isNotEmpty()) add("template warnings: ${templateWarnings.size}")
    }
    return if (details.isEmpty()) "" else " (${details.joinToString("; ")})"
}

private fun ActionExecutionTrace.toRunLogLines(): List<String> = buildList {
    add(toSummaryLine())
    argumentExpansions
        .flatMap { it.toTemplateDiagnosticLines(actionType) }
        .take(MAX_TEMPLATE_TRACE_LINES_PER_ACTION)
        .forEach(::add)
    variableChanges
        .take(MAX_VARIABLE_CHANGE_LINES_PER_ACTION)
        .map { it.toRunLogLine() }
        .forEach(::add)
}

private fun ActionVariableChange.toRunLogLine(): String = listOf(
    VARIABLE_CHANGE_PREFIX,
    scope.name.lowercase(),
    name.toLogField(),
    if (added) VARIABLE_CHANGE_ADDED else VARIABLE_CHANGE_UPDATED,
    if (sensitive) REDACTED_VALUE else value.toLogField(),
).joinToString("	")

private fun ActionArgumentExpansionTrace.toTemplateDiagnosticLines(actionType: String?): List<String> =
    expressions.map { expressionTrace ->
        val sensitive = isSecretDerived ||
            ActionArgumentSensitivity.isSensitive(actionType, argName) ||
            expressionTrace.isSecretDerived
        listOf(
            TEMPLATE_TRACE_PREFIX,
            argName.toLogField(),
            expressionTrace.source.name.lowercase().toLogField(),
            if (sensitive) REDACTED_VALUE else expressionTrace.expression.toLogField(),
            if (sensitive) REDACTED_VALUE else expressionTrace.value.toLogField(),
            expressionTrace.warning.orEmpty().toLogField(),
        ).joinToString("\t")
    }

private fun summarizeArgValue(
    actionType: String?,
    argName: String,
    value: String,
    forceRedact: Boolean = false,
): String {
    if (forceRedact || ActionArgumentSensitivity.isSensitive(actionType, argName)) {
        return REDACTED_VALUE
    }
    val singleLine = value.replace(Regex("""\s+"""), " ").trim()
    return if (singleLine.length <= MAX_SUMMARY_VALUE_LENGTH) {
        singleLine
    } else {
        singleLine.take(MAX_SUMMARY_VALUE_LENGTH) + "..."
    }
}

private fun String.toLogField(): String =
    replace('\t', ' ')
        .replace('\r', ' ')
        .replace('\n', ' ')
        .replace(Regex("""\s+"""), " ")
        .trim()
        .let { value ->
            if (value.length <= MAX_TEMPLATE_TRACE_FIELD_LENGTH) value else value.take(MAX_TEMPLATE_TRACE_FIELD_LENGTH) + "..."
        }

private const val TEMPLATE_TRACE_PREFIX = "Template:"
private const val REDACTED_VALUE = ActionArgumentSensitivity.REDACTED
private const val MAX_SUMMARY_ARGS = 4
private const val MAX_SUMMARY_VALUE_LENGTH = 80
private const val MAX_TEMPLATE_TRACE_LINES_PER_ACTION = 8
private const val MAX_VARIABLE_CHANGE_LINES_PER_ACTION = 8
private const val VARIABLE_CHANGE_PREFIX = "Var:"
private const val VARIABLE_CHANGE_ADDED = "added"
private const val VARIABLE_CHANGE_UPDATED = "updated"
private const val MAX_TEMPLATE_TRACE_FIELD_LENGTH = 120
