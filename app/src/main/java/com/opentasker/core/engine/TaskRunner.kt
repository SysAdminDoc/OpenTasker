package com.opentasker.core.engine

import com.opentasker.core.actions.ActionArgumentSensitivity
import com.opentasker.core.actions.ArrayPositionArguments
import com.opentasker.core.actions.LineListArguments
import com.opentasker.core.actions.PluginBundleArguments
import com.opentasker.core.actions.declaredActionOutputs
import com.opentasker.core.capabilities.AutomationSensitivityRegistry
import com.opentasker.core.capabilities.ActionCapabilityRegistry
import com.opentasker.core.diagnostics.ExportRedactionPolicy
import com.opentasker.core.engine.variables.TaskerBuiltIns
import com.opentasker.core.expressions.InertTemplateText
import com.opentasker.core.expressions.TemplateExpansionTrace
import com.opentasker.core.expressions.TemplateScope
import com.opentasker.core.expressions.TemplateExpressionEngine
import com.opentasker.core.model.ActionSpec
import com.opentasker.core.model.Task
import com.opentasker.core.model.VariableNamePolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeoutOrNull

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

    /** The task [run] is running, for a Stop Task step that names it. */
    private var runningTask: Task? = null

    /** Set when a Stop Task step names [runningTask], which ends the run like `flow.stop`. */
    private var stopThisRun = false

    /** What a Stop with a value handed back, for the Run Task step that ran this task (Tasker's Return). */
    internal class ReturnedValue(val text: String, val sensitive: Boolean)

    internal var returnedValue: ReturnedValue? = null
        private set

    suspend fun run(task: Task): TaskRunReport {
        ctx.variables.pushScope()
        val started = now()
        runningTask = task
        stopThisRun = false
        returnedValue = null
        val results = mutableListOf<ActionResult>()
        val traces = mutableListOf<ActionExecutionTrace>()

        // A step turned off never runs, so it can't hold the rest of the task back.
        val unknownActionIds = task.actions
            .filter(ActionSpec::enabled)
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
            .filter(ActionSpec::enabled)
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
        val ifResults = mutableMapOf<Int, Boolean>()
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
                if (!spec.enabled) {
                    results += ActionResult.Skip
                    traces += markerTrace(pc, spec, ActionResult.Skip, ActionTraceStatus.SKIPPED, STEP_TURNED_OFF)
                    pc++
                    continue
                }
                if (FlowControl.isControl(spec.type)) {
                    val outcome = stepControl(pc, spec, task.actions, structure, loopStack, tryStack, armedElseIndices, ifResults)
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
                if (stopThisRun) break
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
        actions: List<ActionSpec>,
        structure: FlowStructure,
        loopStack: ArrayDeque<LoopFrame>,
        tryStack: ArrayDeque<TryFrame>,
        armedElseIndices: MutableSet<Int>,
        ifResults: MutableMap<Int, Boolean>,
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
                ifResults[pc] = matched
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
                val openedByIf = armedElseIndices.remove(pc)
                // Tasker skips an else only when its If was found true, so a goto from outside the
                // block into the if branch runs on into the else, as it does there.
                if (openedByIf || structure.ifForElse(pc)?.let(ifResults::get) != true) {
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
                // Values (Tasker's For items) apply only when no array is named.
                val values = spec.args["values"]?.trim()?.takeIf { listName == null && it.isNotBlank() }
                val loop = values?.let { raw ->
                    val templateScope = ctx.variables.toTemplateScope(ctx.eventVariables)
                    ForItems.resolve(raw, ctx.variables) { piece ->
                        val (text, trace) = expandValue(spec.type, "values", piece, templateScope, conditionals = false)
                        ForItems.Expanded(text, secret = trace?.isSecretDerived == true)
                    }
                } ?: ForItems.Resolved(
                    items = listName?.let { ctx.variables.getArrayItems(it) }.orEmpty(),
                    sensitive = listName?.let(ctx.variables::isArraySensitive) == true,
                )
                val source = values ?: listName
                val endfor = structure.foreachToEndfor.getValue(pc)
                if (loop.problem != null) {
                    val failure = ActionResult.Failure(loop.problem)
                    ControlOutcome(failure, markerTrace(pc, spec, failure, ActionTraceStatus.FAILURE), pc + 1, halt = true)
                } else if (loop.items.isEmpty()) {
                    outcome("foreach $source -> 0 items", endfor + 1)
                } else {
                    loopStack.addLast(LoopFrame(pc, loop.items, itemVar, loop.sensitive, 0))
                    ctx.variables.set(itemVar, loop.items[0], sensitive = loop.sensitive)
                    outcome("foreach $source -> ${loop.items.size} items (1/${loop.items.size})", pc + 1)
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
            } else if (!spec.args["value"].isNullOrBlank()) {
                // Tasker's Return: the value goes back to the Run Task step that called this task, if any.
                val expansion = expandArgs(spec.type, mapOf("value" to spec.args.getValue("value")))
                val value = expansion.args["value"].orEmpty()
                val sensitive = expansion.isArgumentSensitive("value")
                returnedValue = ReturnedValue(value, sensitive)
                val shown = summarizeArgValue(spec.type, "value", value, forceRedact = sensitive)
                outcome(expansion.redactSensitiveValues("stop, returning $shown"), pc + 1, halt = true)
            } else {
                outcome("stop", pc + 1, halt = true)
            }
            FlowControl.GOTO -> if (!guardMet()) {
                skipped("goto skipped: condition ($guard) -> false", pc + 1)
            } else if (!spec.args["to"].isNullOrBlank()) {
                gotoBlock(pc, spec, spec.args.getValue("to").trim(), structure, loopStack, tryStack)
            } else {
                val expansion = expandArgs(spec.type, spec.args)
                val target = FlowControl.gotoTarget(actions, expansion.args, spec.args)
                val index = target.index
                // A step number or label read from a secret stays out of the trace and the error.
                if (index == null) {
                    val failure = ActionResult.Failure(expansion.redactSensitiveValues(target.error ?: "goto has no target"))
                    ControlOutcome(failure, markerTrace(pc, spec, failure, ActionTraceStatus.FAILURE), pc + 1, halt = true)
                } else {
                    val unopened = leaveBlocksFor(index, structure, loopStack, tryStack)
                    if (unopened != null) {
                        val failure = ActionResult.Failure(
                            expansion.redactSensitiveValues("goto step ${index + 1} lands inside the block that opens at step ${unopened + 1}, which isn't running"),
                        )
                        ControlOutcome(failure, markerTrace(pc, spec, failure, ActionTraceStatus.FAILURE), pc + 1, halt = true)
                    } else {
                        outcome(expansion.redactSensitiveValues("goto -> step ${index + 1}"), index)
                    }
                }
            }
            else -> outcome(spec.type, pc + 1)
        }
    }

    /**
     * Ends the loops and tries a jump to [index] leaves, along with any block they hold (jumping
     * back onto a For Each restarts that loop). Returns the opening step of a block [index] sits
     * in that isn't running, which a goto can't land in, or null.
     */
    private fun leaveBlocksFor(
        index: Int,
        structure: FlowStructure,
        loopStack: ArrayDeque<LoopFrame>,
        tryStack: ArrayDeque<TryFrame>,
    ): Int? {
        while (loopStack.lastOrNull()?.let { index <= it.foreachIndex || index > structure.foreachToEndfor.getValue(it.foreachIndex) } == true) {
            loopStack.removeLast()
        }
        while (tryStack.lastOrNull()?.let { index <= it.tryIndex || index > it.endIndex } == true) {
            tryStack.removeLast()
        }
        return structure.unopenedBlockAt(index, loopStack.map { it.foreachIndex }, tryStack.map { it.tryIndex })
    }

    /** A goto to the top or out of the For Each it sits in, or to the End If of its If. */
    private fun gotoBlock(
        pc: Int,
        spec: ActionSpec,
        to: String,
        structure: FlowStructure,
        loopStack: ArrayDeque<LoopFrame>,
        tryStack: ArrayDeque<TryFrame>,
    ): ControlOutcome {
        fun fail(message: String): ControlOutcome {
            val failure = ActionResult.Failure(message)
            return ControlOutcome(failure, markerTrace(pc, spec, failure, ActionTraceStatus.FAILURE), pc + 1, halt = true)
        }
        fun go(message: String, nextPc: Int) =
            ControlOutcome(ActionResult.Success, markerTrace(pc, spec, ActionResult.Success, ActionTraceStatus.SUCCESS, message), nextPc)
        fun jump(message: String, index: Int): ControlOutcome {
            val unopened = leaveBlocksFor(index, structure, loopStack, tryStack)
                ?: return go(message, index)
            return fail("goto step ${index + 1} lands inside the block that opens at step ${unopened + 1}, which isn't running")
        }

        if (to == FlowControl.GOTO_IF_END) {
            val open = structure.enclosingIf(pc) ?: return fail("goto end of If: this step isn't inside an If")
            val endif = structure.ifToEndif.getValue(open)
            return jump("goto end of If -> step ${endif + 1}", endif)
        }
        val name = when (to) {
            FlowControl.GOTO_LOOP_TOP -> "top of loop"
            FlowControl.GOTO_LOOP_END -> "out of loop"
            else -> return fail("goto: \"$to\" isn't a place to jump to")
        }
        val open = structure.enclosingLoop(pc) ?: return fail("goto $name: this step isn't inside a For Each")
        val frameAt = loopStack.indexOfLast { it.foreachIndex == open }
        if (frameAt < 0) return fail("goto $name: the For Each at step ${open + 1} isn't running")
        val endfor = structure.foreachToEndfor.getValue(open)
        if (to == FlowControl.GOTO_LOOP_END) return jump("goto out of loop -> after step ${endfor + 1}", endfor + 1)

        // Tasker's "continue": end whatever the loop body opened, then take the next item the way
        // the End For would, without that marker's own condition.
        while (loopStack.size > frameAt + 1) loopStack.removeLast()
        while (tryStack.lastOrNull()?.let { it.tryIndex > open } == true) tryStack.removeLast()
        val frame = loopStack.last()
        frame.index++
        if (frame.index < frame.items.size) {
            ctx.variables.set(frame.itemVar, frame.items[frame.index], sensitive = frame.sensitive)
            return go("goto top of loop -> loop ${frame.index + 1}/${frame.items.size}", open + 1)
        }
        loopStack.removeLast()
        return go("goto top of loop -> loop done", endfor + 1)
    }

    /**
     * Whether replaying the whole try body is safe.
     *
     * A retry restarts at `tryIndex + 1`, so every action in the body runs again - not just the one
     * that failed. Checking only the failing action let a body like `[sms.send, http.get]` re-send
     * the message each time the HTTP call failed. Control markers carry no side effects and are
     * skipped, and so are steps turned off, which a replay passes over too; an unknown action id
     * is treated as unsafe.
     */
    private fun tryBodyIsRetrySafe(task: Task, frame: TryFrame): Boolean {
        val bodyEnd = frame.catchIndex ?: frame.endIndex
        return ((frame.tryIndex + 1) until bodyEnd)
            .mapNotNull { index -> task.actions.getOrNull(index) }
            .filterNot { bodySpec -> FlowControl.isControl(bodySpec.type) || !bodySpec.enabled }
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
        // Innermost first. Frames come off the stack only once one of them retries or catches, so a
        // failure nothing handles leaves every Try open for a step that carries on past it.
        val enclosing = tryStack.asReversed().filter { candidate ->
            candidate.phase == TryPhase.BODY && pc > candidate.tryIndex && pc < candidate.endIndex
        }
        for (frame in enclosing) {
            lastAttempt = frame.attempt
            if (frame.attempt < frame.config.maxAttempts && tryBodyIsRetrySafe(task, frame)) {
                while (tryStack.lastOrNull() !== frame) tryStack.removeLast()
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
                while (tryStack.lastOrNull() !== frame) tryStack.removeLast()
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
        }
        return FailureRecovery(nextPc = null, reason = nonRetryReason, attemptCount = lastAttempt)
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
        if (spec.type == STOP_TASK_ACTION_ID) {
            return stopTask(index, spec, started)
        }

        val action = ActionRegistry.get(spec.type)
            ?: ActionResult.Failure("unknown action: ${spec.type}").let { result ->
                return result to traceFor(index, spec, started, result, ActionArgumentExpansionReport.Empty)
            }
        val expansionReport = expandArgs(spec.type, spec.args)
        expansionReport.failure?.let { message ->
            val result = ActionResult.Failure(message)
            return result to traceFor(index, spec, started, result, expansionReport)
        }
        val timeoutMs = actionTimeoutMs(spec.type)
        val before = ctx.variables.toTemplateScope()
        val rawResult = try {
            runStepWithinLimit(timeoutMs) {
                ctx.variables.withSensitiveWrites(expansionReport.hasSecretDerivedValues()) {
                    action.run(ctx.forAction(expansionReport.sensitiveArgumentNames()), expansionReport.args)
                }
            }
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
        // Checked before the task runs, so a typo doesn't run the task and then lose what it returns.
        val returnVariable = spec.args[SUB_TASK_RETURN_KEY]?.takeIf(String::isNotBlank)?.let { written ->
            val expanded = args[SUB_TASK_RETURN_KEY].orEmpty()
            VariableNamePolicy.normalize(expanded) ?: return fail(
                expansionReport.redactSensitiveValues(
                    unusableReturnVariable(written, expanded, expansionReport.isArgumentSensitive(SUB_TASK_RETURN_KEY)),
                ),
            )
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
        // Like Tasker, cleared once the task is found and before it runs, so a task that returns
        // nothing leaves the variable unset instead of holding an earlier call's value.
        if (returnVariable != null && resetsReturnVariable(args)) ctx.variables.clearForTask(returnVariable)
        ctx.variables.pushScope(holdsInputs = true)
        val report = try {
            args.forEach { (key, value) ->
                if (key !in SUB_TASK_REF_KEYS && key !in SUB_TASK_SETTING_KEYS) {
                    ctx.variables.set(key, value, sensitive = expansionReport.isArgumentSensitive(key))
                }
            }
            val coordinator = collisionCoordinator
            val outcome = if (coordinator == null) {
                TaskCollisionOutcome.Executed(runListed(child, target))
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
        // Set after the inputs' scope is gone, so the value lands in this task's own variables.
        val returned = child.returnedValue
        if (report.success && returnVariable != null && returned != null) {
            ctx.variables.set(returnVariable, returned.text, sensitive = returned.sensitive)
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
     * Runs a sub-task in a coroutine of its own, so stopping the sub-task cancels *it*.
     *
     * A stopped run cancels the Job the coordinator was called from. A sub-task used to run
     * directly in the caller's coroutine, so that Job was the caller's whole execution: a later
     * Abort existing run of the sub-task cancelled the caller, which had no collision of its own
     * and was logged as "Replaced by a newer run", and an Abort new run of the sub-task was
     * refused because the caller looked like a run of it.
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
        val nested = async { coordinator.execute(target) { runListed(child, target) } }
        try {
            nested.await()
        } catch (cancelled: CancellationException) {
            coroutineContext.ensureActive()
            TaskCollisionOutcome.Skipped(
                cancelled.message ?: "The sub-task was replaced by a newer run.",
            )
        }
    }

    /**
     * Tasker's %TRUN lists a task run by another one too, from when its collision rule lets it run,
     * so one still waiting its turn or skipped isn't listed.
     */
    private suspend fun runListed(child: TaskRunner, target: Task): TaskRunReport {
        val running = TaskerBuiltIns.subTaskStarted(target.name)
        try {
            return child.run(target)
        } finally {
            TaskerBuiltIns.subTaskEnded(running)
        }
    }

    /**
     * Stops every running copy of the task the step names, Tasker's Stop with a task given.
     *
     * Naming the task this step runs in ends this run the way `flow.stop` does and cancels its other
     * runs. Naming a task that called this one, directly or further up, stops that caller and this
     * run with it. Another task's runs are cancelled, and a task running the stopped one as a
     * sub-task sees that step fail. A task that isn't running is not an error.
     */
    private suspend fun stopTask(
        index: Int,
        spec: ActionSpec,
        started: Long,
    ): Pair<ActionResult, ActionExecutionTrace> {
        val expansionReport = expandArgs(spec.type, spec.args)

        fun finish(result: ActionResult): Pair<ActionResult, ActionExecutionTrace> =
            result to traceFor(index, spec, started, result, expansionReport)

        expansionReport.failure?.let { return finish(ActionResult.Failure(it)) }
        val ref = SUB_TASK_REF_KEYS.firstNotNullOfOrNull { expansionReport.args[it]?.trim()?.takeIf(String::isNotBlank) }
            ?: return finish(ActionResult.Failure("task.stop requires a 'task' (id or name)"))
        val resolver = resolveTask ?: return finish(ActionResult.Failure("stopping a task is not available in this context"))
        val target = resolver(ref) ?: return finish(ActionResult.Failure("task not found: $ref"))
        val running = runningTask
        val reason = "Stopped by a Stop Task step in '${running?.name.orEmpty()}'"
        if (target.id > 0L && target.id == running?.id) {
            // Tasker stops every copy of the task, so the other runs of this one end as well.
            val others = collisionCoordinator?.cancelRunning(target.id, reason, sparing = currentCoroutineContext()[Job]) ?: 0
            if (others > 0) ctx.logger("Stopped '${target.name}'")
            stopThisRun = true
            return finish(ActionResult.Success)
        }
        val coordinator = collisionCoordinator
            ?: return finish(ActionResult.Failure("stopping another task is not available in this context"))
        val stopped = coordinator.cancelRunning(target.id, reason)
        ctx.logger(if (stopped == 0) "'${target.name}' wasn't running" else "Stopped '${target.name}'")
        // Stopping a task that called this one cancelled this run too, so end before another step.
        currentCoroutineContext().ensureActive()
        return finish(ActionResult.Success)
    }

    private fun shouldRun(spec: ActionSpec): Boolean {
        val condition = spec.condition?.trim()?.takeIf { it.isNotBlank() } ?: return true
        return evaluateConditionString(condition)
    }

    /**
     * Evaluates a condition as typed: structure first, then legacy `%var` and bounded `{{ ... }}`
     * expansion of each operand. The condition is never expanded as a whole, so a value can't
     * change what it asks.
     */
    private fun evaluateConditionString(condition: String): Boolean =
        ctx.variables.evaluateCondition(condition, templateExpressionEngine, ctx.eventVariables)

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
        val outputNames = OutputNameArguments(actionType)
        var failure: String? = null
        val expandedArgs = args.mapValues { (name, rawValue) ->
            arrayReferenceName(actionType, name, rawValue)?.let { arrayName ->
                return@mapValues arrayName
            }
            if (outputNames.keepsAsWritten(name, rawValue)) return@mapValues rawValue
            if (LineListArguments.applies(actionType, name)) {
                val lines = rawValue.lines().map { line -> expandValue(actionType, name, line, templateScope) }
                val expanded = lines.joinToString("\n") { it.first }
                LineListArguments.lineBreakFailure(actionType, name, lines.map { it.first })?.let { failure = it }
                combinedTrace(name, rawValue, expanded, lines.mapNotNull { it.second })?.let { expansions += it }
                return@mapValues expanded
            }
            if (PluginBundleArguments.applies(actionType, name)) {
                val traces = mutableListOf<ActionArgumentExpansionTrace>()
                val expanded = PluginBundleArguments.expand(rawValue) { text ->
                    val (value, trace) = expandValue(actionType, name, text, templateScope, conditionals = false)
                    trace?.let(traces::add)
                    value
                }
                combinedTrace(name, rawValue, expanded ?: rawValue, traces)?.let { expansions += it }
                return@mapValues expanded ?: rawValue
            }
            val (expanded, trace) = expandValue(actionType, name, rawValue, templateScope)
            trace?.let { expansions += it }
            ArrayPositionArguments.emptyFailure(actionType, name, rawValue, expanded)?.let { failure = it }
            expanded
        }

        return ActionArgumentExpansionReport(expandedArgs, expansions, actionType, failure)
    }

    /** One trace for an argument expanded in pieces, or null when no piece needed one. */
    private fun combinedTrace(
        name: String,
        rawValue: String,
        expanded: String,
        traces: List<ActionArgumentExpansionTrace>,
    ): ActionArgumentExpansionTrace? {
        if (traces.isEmpty()) return null
        val isSecretDerived = traces.any { it.isSecretDerived }
        return ActionArgumentExpansionTrace(
            argName = name,
            rawValue = rawValue,
            expandedValue = if (isSecretDerived) REDACTED_VALUE else expanded,
            expressions = traces.flatMap { it.expressions },
            warnings = traces.flatMap { it.warnings },
            isSecretDerived = isSecretDerived,
        )
    }

    private fun expandValue(
        actionType: String,
        name: String,
        rawValue: String,
        templateScope: TemplateScope,
        conditionals: Boolean = true,
    ): Pair<String, ActionArgumentExpansionTrace?> {
        val legacy = ctx.variables.expandTracked(rawValue, conditionals)
        if (!legacy.value.contains("{{")) {
            val trace = if (legacy.isSecretDerived) {
                ActionArgumentExpansionTrace(
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
                ActionArgumentExpansionTrace(
                    argName = name,
                    rawValue = shown,
                    expandedValue = shown,
                    expressions = emptyList(),
                    warnings = emptyList(),
                    isSecretDerived = false,
                )
            } else {
                null
            }
            return legacy.text to trace
        }

        val result = templateExpressionEngine.expand(legacy.value, templateScope)
        val expanded = InertTemplateText.restore(result.value)
        val isSecretDerived = legacy.isSecretDerived || result.traces.any { it.isSecretDerived }
        val trace = if (result.traces.isNotEmpty() || result.warnings.isNotEmpty() || isSecretDerived) {
            ActionArgumentExpansionTrace(
                argName = name,
                rawValue = rawValue,
                expandedValue = if (isSecretDerived) REDACTED_VALUE else expanded,
                expressions = result.traces.map { trace ->
                    if (trace.isSecretDerived) trace.copy(value = REDACTED_VALUE) else trace
                },
                warnings = result.warnings,
                isSecretDerived = isSecretDerived,
            )
        } else {
            null
        }
        return expanded to trace
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

/**
 * "%count" in an argument that names an action's output is the variable to write, the way the
 * editor reads it. Expanded like other text, it named whatever count held, so a second run wrote
 * to a different variable. A name built from parts, like "item_%slot", still expands. Variable
 * Clear's name is never filled in, since it can be a pattern like "%item*", as in Tasker. Join
 * Text's "%parts" names the array to join, the way an output name does: filled in, it was the
 * first part's text, so the step joined nothing.
 */
internal class OutputNameArguments(private val actionType: String) {
    private val keys = declaredActionOutputs(actionType).flatMapTo(hashSetOf()) { it.nameArgumentKeys }

    fun keepsAsWritten(argumentName: String, rawValue: String): Boolean =
        ((argumentName in keys || (actionType == "text.join" && argumentName == "array")) && SINGLE_VARIABLE.matches(rawValue.trim())) ||
            (actionType == "var.clear" && argumentName == "name")

    private companion object {
        val SINGLE_VARIABLE = Regex("%[A-Za-z][A-Za-z0-9_]*")
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

/**
 * Runs one step under its own limit. A limit set further out, by the `task.run` step above a
 * sub-task, has to keep unwinding: the step that set it owns the message, and this step's budget
 * would be the wrong number to report.
 */
internal suspend fun runStepWithinLimit(timeoutMs: Long, step: suspend () -> ActionResult): ActionResult =
    try {
        withTimeoutOrNull(timeoutMs) { step() } ?: ActionResult.Failure("timed out after ${timeoutMs / 1000}s")
    } catch (e: TimeoutCancellationException) {
        // Still active means this came from a limit the action set inside itself.
        currentCoroutineContext().ensureActive()
        ActionResult.Failure("timed out: ${e.message}")
    }

internal fun actionTimeoutMs(actionType: String): Long = when {
    actionType == "flow.wait" -> MAX_WAIT_TIMEOUT_MS
    // A plugin step waits for its plugin's answer up to LocalePluginContract.MAX_SETTING_WAIT_MS,
    // the same 30 minutes, and reports its own timeout.
    actionType == "plugin.locale.fire" -> MAX_WAIT_TIMEOUT_MS
    // MQTT allows a 30 s connect plus three 30 s reads, so the 60 s default silently truncated
    // work the user configured and reported a timeout instead.
    actionType.startsWith("http.") || actionType == "download" || actionType == "ping" ||
        actionType == "mqtt.publish" -> 120_000L
    // The webhook's own contract allows timeout_sec=30 across four attempts with 5 s, 10 s and
    // 10 s of backoff between them, which is 145 s.
    actionType == "integration.home_assistant.webhook" -> LONG_REQUEST_TIMEOUT_MS
    // A script may run for TermuxScriptPolicy.MAX_TIMEOUT_MS after a hash check of up to
    // HASH_TIMEOUT_MS, and the contact picker waits two minutes for a choice. Each reports its
    // own limit, which the engine must outlast for that message to reach the run log.
    actionType == "script.termux.run" || actionType == "script.shizuku.run" ||
        actionType == "contacts.lookup" -> LONG_REQUEST_TIMEOUT_MS
    // Playback and speech suspend until completion; a long sound file or near-limit TTS
    // text legitimately outlives the default 60 s budget.
    actionType == "sound.play" || actionType == "tts.speak" -> MEDIA_ACTION_TIMEOUT_MS
    else -> DEFAULT_ACTION_TIMEOUT_MS
}

private const val DEFAULT_ACTION_TIMEOUT_MS = 60_000L
private const val LONG_REQUEST_TIMEOUT_MS = 180_000L
private const val MEDIA_ACTION_TIMEOUT_MS = 600_000L // 10 minutes

// The engine budget must exceed WaitAction.MAX_WAIT_MS (30 min): the timeout clock starts
// before the action parses its arguments, so an equal budget deterministically failed a
// wait at the documented maximum.
private const val MAX_WAIT_TIMEOUT_MS = 1_860_000L // 30 minutes + 60 s margin

const val SUB_TASK_ACTION_ID = "task.run"
/** Stops another task's running copies; takes its target through [SUB_TASK_REF_KEYS] like `task.run`. */
const val STOP_TASK_ACTION_ID = "task.stop"
const val MAX_SUBTASK_DEPTH = 8
/** Argument keys `task.run` accepts as its target, in precedence order. */
val SUB_TASK_REF_KEYS = listOf("task", "name", "id")
/** The `task.run` argument naming the caller's variable for the value a Stop hands back, Tasker's Return Value Variable. */
const val SUB_TASK_RETURN_KEY = "returnVariable"
/** The `task.run` switch that clears that variable before the task runs, Tasker's Reset Return Variable. */
const val SUB_TASK_RESET_RETURN_KEY = "resetReturnVariable"
/** `task.run` arguments that set up the step itself, so they never reach the sub-task as inputs. */
val SUB_TASK_SETTING_KEYS = setOf(SUB_TASK_RETURN_KEY, SUB_TASK_RESET_RETURN_KEY)

/** Whether [args] turn on [SUB_TASK_RESET_RETURN_KEY]. The run, the preview and an export all read it this way. */
fun resetsReturnVariable(args: Map<String, String>): Boolean =
    args[SUB_TASK_RESET_RETURN_KEY]?.trim().equals("true", ignoreCase = true)

/** What the run log and Preflight say for a step turned off. */
const val STEP_TURNED_OFF = "step is turned off"

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
    /** Set when the expanded arguments can't be handed to the action at all. */
    val failure: String? = null,
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

/**
 * Why a Run Task step's return variable can't hold the returned value, from the name as written and
 * as it came out. The run and Preflight both say this.
 */
internal fun unusableReturnVariable(written: String, expanded: String, sensitive: Boolean): String = when {
    expanded.isBlank() -> "'${written.trim()}' came out empty, so the returned value has nowhere to go"
    sensitive -> "$REDACTED_VALUE isn't a variable name, so the returned value has nowhere to go"
    else -> "'${expanded.trim()}' isn't a variable name, so the returned value has nowhere to go"
}

internal fun summarizeArgValue(
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
