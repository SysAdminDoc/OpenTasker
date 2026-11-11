package com.opentasker.core.engine

import com.opentasker.core.actions.ActionArgumentSensitivity
import com.opentasker.core.actions.ActionValueType
import com.opentasker.core.actions.ActiveTransport
import com.opentasker.core.actions.ArrayPositionArguments
import com.opentasker.core.actions.HttpNetworkConstraint
import com.opentasker.core.actions.LineListArguments
import com.opentasker.core.actions.declaredActionOutputs
import com.opentasker.core.actions.httpNetworkDenial
import com.opentasker.core.capabilities.ActionCapabilityRegistry
import com.opentasker.core.capabilities.CapabilityLevel
import com.opentasker.core.capabilities.SetupRequirement
import com.opentasker.core.capabilities.SetupRequirementResolver
import com.opentasker.core.diagnostics.ExportRedactionPolicy
import com.opentasker.core.expressions.InertTemplateText
import com.opentasker.core.expressions.TemplateExpansionResult
import com.opentasker.core.expressions.TemplateExpressionEngine
import com.opentasker.core.expressions.TemplateExpansionTrace
import com.opentasker.core.expressions.TemplateValueSource
import com.opentasker.core.model.ActionSpec
import com.opentasker.core.model.ContextSpec
import com.opentasker.core.model.ContextType
import com.opentasker.core.model.Profile
import com.opentasker.core.model.Task
import com.opentasker.core.model.VariableNamePolicy

/** Synthetic inputs for a side-effect-free task/profile preflight. */
data class PreflightInputs(
    val eventVariables: Map<String, String> = emptyMap(),
    val globalVariables: Map<String, String> = emptyMap(),
    val secretGlobalNames: Set<String> = emptySet(),
    val grantedSetupRequirements: Set<SetupRequirement> = emptySet(),
    /**
     * What the device is connected through, when the caller knows. Null means unknown, and an
     * unknown transport never blocks: a preview must not invent a connection failure.
     */
    val activeTransport: ActiveTransport? = null,
)

enum class PreflightStepStatus {
    SIMULATED,
    SKIPPED,
    BLOCKED,
}

data class PreflightStep(
    val taskPath: String,
    val actionIndex: Int,
    val actionType: String,
    val label: String,
    val status: PreflightStepStatus,
    val expandedArguments: Map<String, String> = emptyMap(),
    val condition: String? = null,
    val branchDecision: String? = null,
    val capability: CapabilityLevel = CapabilityLevel.Supported,
    val intendedEffect: String,
    val warnings: List<String> = emptyList(),
    val executionCount: Int = 1,
)

data class PreflightTaskReport(
    val taskId: Long,
    val taskName: String,
    val taskPath: String,
    val steps: List<PreflightStep>,
)

data class PreflightContextReport(
    val index: Int,
    val type: ContextType,
    val configuration: Map<String, String>,
    val intendedEffect: String = "Context matching is described only; no context source is started.",
)

data class PreflightReport(
    val title: String,
    val profileId: Long? = null,
    val profileName: String? = null,
    val contexts: List<PreflightContextReport> = emptyList(),
    val tasks: List<PreflightTaskReport> = emptyList(),
    val setupRequirements: Set<SetupRequirement> = emptySet(),
    val missingSetupRequirements: Set<SetupRequirement> = emptySet(),
    val warnings: List<String> = emptyList(),
    val sideEffectsSuppressed: Boolean = true,
) {
    val blockedSteps: List<PreflightStep>
        get() = tasks.flatMap { it.steps }.filter { it.status == PreflightStepStatus.BLOCKED }

    val canPreflight: Boolean
        get() = sideEffectsSuppressed && blockedSteps.isEmpty()
}

/**
 * Static action descriptions used by the preflight runner. No Action.run implementation is ever
 * called here. Registered actions receive an explicit category-based simulation contract; an
 * unknown action has no preview implementation and is blocked instead of being guessed.
 */
object PreflightActionRegistry {
    fun hasImplementation(actionId: String): Boolean =
        actionId in FlowControl.ALL || actionId == SUB_TASK_ACTION_ID || ActionRegistry.get(actionId) != null

    fun intendedEffect(actionId: String): String = when (actionId) {
        FlowControl.IF, FlowControl.ELSE, FlowControl.ENDIF, FlowControl.FOREACH, FlowControl.ENDFOR,
        FlowControl.STOP, FlowControl.GOTO, FlowControl.ANCHOR -> "Evaluates flow control in memory; no external effect."
        SUB_TASK_ACTION_ID -> "Would invoke a referenced sub-task; nested action execution is suppressed."
        "var.set" -> "Updates the preflight variable snapshot only; durable variable writes are suppressed."
        "var.persist" -> "Would promote a variable to global scope; persistence is suppressed."
        else -> {
            val category = ActionRegistry.get(actionId)?.category?.name?.lowercase() ?: "unknown"
            "Would simulate $category action $actionId; the runtime action is not invoked."
        }
    }

    /** Registry completeness is a testable contract for every action registered at runtime. */
    internal fun missingRegisteredImplementations(): Set<String> =
        ActionRegistry.allIds().filterNot(::hasImplementation).toSet()
}

object PreflightRunner {
    private const val MAX_STEPS = 512
    private const val MAX_SUBTASK_DEPTH = 8
    private const val MAX_DISPLAY_VALUE_LENGTH = 256
    private val SINGLE_VARIABLE = Regex("%[A-Za-z][A-Za-z0-9_]*")

    // The actions that write Tasker's numbered elements (%arr1, %arr2, ...). text.split and
    // text.match keep their parts in a stored array instead, so they never set %Pos1.
    private val NUMBERED_ELEMENT_WRITERS = setOf("var.split", "array.push", "array.pop", "array.process")

    fun preflightTask(
        task: Task,
        tasks: List<Task> = listOf(task),
        inputs: PreflightInputs = PreflightInputs(),
    ): PreflightReport {
        val variables = VariableStore()
        seedVariables(variables, inputs)
        val required = setupRequirements(task, tasks)
        val taskReports = mutableListOf<PreflightTaskReport>()
        val warnings = mutableListOf<String>()
        taskReports += simulateTask(
            task = task,
            tasks = tasks,
            variables = variables,
            input = inputs,
            taskPath = task.name,
            depth = 0,
            activeTaskIds = setOf(task.id),
            reports = taskReports,
            warnings = warnings,
        )
        return PreflightReport(
            title = task.name,
            tasks = taskReports,
            setupRequirements = required,
            missingSetupRequirements = required - inputs.grantedSetupRequirements,
            warnings = warnings.distinct(),
        )
    }

    fun preflightProfile(
        profile: Profile,
        tasks: List<Task>,
        inputs: PreflightInputs = PreflightInputs(),
    ): PreflightReport {
        val byId = tasks.associateBy(Task::id)
        val required = setupRequirements(profile, tasks)
        val warnings = mutableListOf<String>()
        val taskReports = mutableListOf<PreflightTaskReport>()
        val variables = VariableStore()
        seedVariables(variables, inputs)
        val contexts = profile.contexts.mapIndexed { index, context ->
            PreflightContextReport(index, context.type, context.config)
        }
        val rootTaskIds = listOfNotNull(profile.enterTaskId, profile.exitTaskId).distinct()
        rootTaskIds.forEach { taskId ->
            val task = byId[taskId]
            if (task == null) {
                warnings += "Profile references missing task $taskId. Repair the profile before relying on this preflight."
            } else {
                taskReports += simulateTask(
                    task = task,
                    tasks = tasks,
                    variables = variables,
                    input = inputs,
                    taskPath = "${profile.name} / ${task.name}",
                    depth = 0,
                    activeTaskIds = setOf(task.id),
                    reports = taskReports,
                    warnings = warnings,
                )
            }
        }
        return PreflightReport(
            title = profile.name,
            profileId = profile.id,
            profileName = profile.name,
            contexts = contexts,
            tasks = taskReports,
            setupRequirements = required,
            missingSetupRequirements = required - inputs.grantedSetupRequirements,
            warnings = warnings.distinct(),
        )
    }

    private fun simulateTask(
        task: Task,
        tasks: List<Task>,
        variables: VariableStore,
        input: PreflightInputs,
        taskPath: String,
        depth: Int,
        activeTaskIds: Set<Long>,
        reports: MutableList<PreflightTaskReport>,
        warnings: MutableList<String>,
    ): PreflightTaskReport {
        variables.pushScope()
        return try {
            simulateTaskInScope(
                task = task,
                tasks = tasks,
                variables = variables,
                input = input,
                taskPath = taskPath,
                depth = depth,
                activeTaskIds = activeTaskIds,
                reports = reports,
                warnings = warnings,
            )
        } finally {
            variables.popScope()
        }
    }

    private fun simulateTaskInScope(
        task: Task,
        tasks: List<Task>,
        variables: VariableStore,
        input: PreflightInputs,
        taskPath: String,
        depth: Int,
        activeTaskIds: Set<Long>,
        reports: MutableList<PreflightTaskReport>,
        warnings: MutableList<String>,
    ): PreflightTaskReport {
        if (depth > MAX_SUBTASK_DEPTH) {
            val blocked = PreflightStep(
                taskPath = taskPath,
                actionIndex = 0,
                actionType = SUB_TASK_ACTION_ID,
                label = task.name,
                status = PreflightStepStatus.BLOCKED,
                intendedEffect = "Nested preflight depth is bounded; no sub-task is traversed.",
                warnings = listOf("Sub-task depth limit $MAX_SUBTASK_DEPTH exceeded."),
            )
            warnings += blocked.warnings
            return PreflightTaskReport(task.id, task.name, taskPath, listOf(blocked))
        }

        val structure = FlowStructure.analyze(task.actions)
        if (structure.error != null) {
            val blocked = PreflightStep(
                taskPath = taskPath,
                actionIndex = 0,
                actionType = "flow",
                label = "flow control",
                status = PreflightStepStatus.BLOCKED,
                intendedEffect = "Flow control is invalid; no action would be attempted.",
                warnings = listOf(structure.error),
            )
            warnings += blocked.warnings
            return PreflightTaskReport(task.id, task.name, taskPath, listOf(blocked))
        }

        val steps = linkedMapOf<Int, PreflightStep>()
        val loopStack = ArrayDeque<PreflightLoopFrame>()
        val tryStack = ArrayDeque<Int>()
        val armedElseIndices = mutableSetOf<Int>()
        val ifResults = mutableMapOf<Int, Boolean>()
        var pc = 0
        var interpretedSteps = 0
        var halted = false
        while (!halted && pc in task.actions.indices && interpretedSteps++ < MAX_STEPS) {
            val spec = task.actions[pc]
            if (!spec.enabled) {
                recordStep(steps, turnedOff(taskPath, pc, spec))
                pc++
                continue
            }
            if (FlowControl.isControl(spec.type)) {
                val control = simulateControl(
                    pc, spec, task.actions, structure, loopStack, tryStack, armedElseIndices, ifResults, variables, input,
                )
                recordStep(steps, control.step.copy(taskPath = taskPath))
                warnings += control.step.warnings
                pc = control.nextPc
                halted = control.halt
                continue
            }

            val expansion = expandArguments(spec, variables, input)
            val condition = spec.condition?.trim()?.takeIf(String::isNotBlank)
            val conditionResult = condition?.let { evaluate(it, variables, input) }
            if (conditionResult != null && !conditionResult.value) {
                recordStep(
                    steps,
                    PreflightStep(
                        taskPath = taskPath,
                        actionIndex = pc,
                        actionType = spec.type,
                        label = spec.label ?: spec.type,
                        status = PreflightStepStatus.SKIPPED,
                        expandedArguments = expansion.values,
                        condition = conditionResult.display,
                        branchDecision = "condition -> false",
                        capability = ActionCapabilityRegistry.get(spec.type).level,
                        intendedEffect = PreflightActionRegistry.intendedEffect(spec.type),
                        warnings = expansion.warnings + conditionResult.warnings,
                    ),
                )
                pc++
                continue
            }

            val capability = ActionCapabilityRegistry.get(spec.type)
            val actionKnown = PreflightActionRegistry.hasImplementation(spec.type)
            val stepWarnings = (expansion.warnings + conditionResult?.warnings.orEmpty()).toMutableList()
            val status = when {
                !actionKnown -> {
                    stepWarnings += "No preflight implementation exists for unknown action '${spec.type}'."
                    PreflightStepStatus.BLOCKED
                }
                capability.level == CapabilityLevel.Unsupported -> {
                    stepWarnings += capability.reason
                    PreflightStepStatus.BLOCKED
                }
                else -> PreflightStepStatus.SIMULATED
            }
            if (capability.level == CapabilityLevel.RequiresSetup) stepWarnings += capability.reason
            // A request restricted to Wi-Fi is not going to run on cellular, and the point of a
            // preview is to say so before the user waits for a failure.
            val networkBlock = httpNetworkBlock(spec.type, expansion.rawValues, input.activeTransport)
            if (networkBlock != null) stepWarnings += networkBlock
            expansion.failure?.let { stepWarnings += "The run stops this step: $it." }
            // Preflight plays Variable Set but not every write a run makes (other actions' outputs,
            // a caller's %par1, event values), so an unset position blocks only when nothing could set it.
            val positionStop = expansion.unsetPosition?.let { (position, reason) ->
                if (staysUnset(position, task, tasks)) {
                    stepWarnings += "The run stops this step: $reason."
                    reason
                } else {
                    stepWarnings += "$position is empty here. If nothing sets it before this step, the run stops the step."
                    null
                }
            }
            val effectiveStatus = if (networkBlock != null || expansion.failure != null || positionStop != null) {
                PreflightStepStatus.BLOCKED
            } else {
                status
            }
            val step = PreflightStep(
                taskPath = taskPath,
                actionIndex = pc,
                actionType = spec.type,
                label = spec.label ?: spec.type,
                status = effectiveStatus,
                expandedArguments = expansion.values,
                condition = conditionResult?.display,
                branchDecision = conditionResult?.let { "condition -> true" },
                capability = capability.level,
                intendedEffect = PreflightActionRegistry.intendedEffect(spec.type),
                warnings = stepWarnings.distinct(),
            )
            recordStep(steps, step)
            warnings += step.warnings

            if (effectiveStatus != PreflightStepStatus.BLOCKED) {
                simulateVariableWrite(spec, expansion.rawValues, variables)
                if (spec.type == SUB_TASK_ACTION_ID) {
                    val reference = SUB_TASK_REF_KEYS.firstNotNullOfOrNull { expansion.rawValues[it]?.trim()?.takeIf(String::isNotBlank) }
                    val target = reference?.let { resolveTask(it, tasks) }
                    when {
                        reference == null -> addWarning(steps, pc, "task.run requires a task id or name.")
                        target == null -> addWarning(steps, pc, "Sub-task not found: $reference")
                        target.id in activeTaskIds -> addWarning(steps, pc, "Sub-task recursion detected for ${target.name}.")
                        else -> {
                            val child = simulateTask(
                                task = target,
                                tasks = tasks,
                                variables = variables,
                                input = input,
                                taskPath = "$taskPath / ${target.name}",
                                depth = depth + 1,
                                activeTaskIds = activeTaskIds + target.id,
                                reports = reports,
                                warnings = warnings,
                            )
                            reports += child
                        }
                    }
                }
            }
            pc++
        }

        if (interpretedSteps >= MAX_STEPS) {
            warnings += "Preflight step budget $MAX_STEPS exceeded; remaining flow was not traversed."
        }
        task.actions.indices
            .filterNot(steps::containsKey)
            .forEach { index ->
                val spec = task.actions[index]
                if (!spec.enabled) {
                    steps[index] = turnedOff(taskPath, index, spec)
                    return@forEach
                }
                steps[index] = PreflightStep(
                    taskPath = taskPath,
                    actionIndex = index,
                    actionType = spec.type,
                    label = spec.label ?: spec.type,
                    status = PreflightStepStatus.SKIPPED,
                    expandedArguments = emptyMap(),
                    branchDecision = "not reached by selected branch",
                    capability = ActionCapabilityRegistry.get(spec.type).level,
                    intendedEffect = PreflightActionRegistry.intendedEffect(spec.type),
                )
            }
        return PreflightTaskReport(task.id, task.name, taskPath, steps.values.sortedBy { it.actionIndex })
    }

    private fun turnedOff(taskPath: String, index: Int, spec: ActionSpec) = PreflightStep(
        taskPath = taskPath,
        actionIndex = index,
        actionType = spec.type,
        label = spec.label ?: spec.type,
        status = PreflightStepStatus.SKIPPED,
        expandedArguments = emptyMap(),
        branchDecision = STEP_TURNED_OFF,
        capability = ActionCapabilityRegistry.get(spec.type).level,
        intendedEffect = PreflightActionRegistry.intendedEffect(spec.type),
    )

    private data class PreflightLoopFrame(
        val foreachIndex: Int,
        val items: List<String>,
        val itemVar: String,
        var index: Int,
    )

    private data class ControlSimulation(
        val step: PreflightStep,
        val nextPc: Int,
        val halt: Boolean = false,
    )

    private fun simulateControl(
        index: Int,
        spec: ActionSpec,
        actions: List<ActionSpec>,
        structure: FlowStructure,
        loops: ArrayDeque<PreflightLoopFrame>,
        tries: ArrayDeque<Int>,
        armedElseIndices: MutableSet<Int>,
        ifResults: MutableMap<Int, Boolean>,
        variables: VariableStore,
        input: PreflightInputs,
    ): ControlSimulation {
        fun step(decision: String, nextPc: Int, halt: Boolean = false, skipped: Boolean = false) = ControlSimulation(
            step = PreflightStep(
                taskPath = "",
                actionIndex = index,
                actionType = spec.type,
                label = spec.label ?: spec.type,
                status = if (skipped) PreflightStepStatus.SKIPPED else PreflightStepStatus.SIMULATED,
                condition = spec.args["condition"] ?: spec.condition,
                branchDecision = decision,
                intendedEffect = PreflightActionRegistry.intendedEffect(spec.type),
            ),
            nextPc = nextPc,
            halt = halt,
        )
        // Mirrors TaskRunner.stepControl: the generic "Run only if" guard applies to the control
        // markers with real behavior (stop, foreach, try, if, else-if, endfor) and is ignored on
        // the structural ones. Keep the two interpreters in step or the preview lies.
        val guard = spec.condition?.trim()?.takeIf(String::isNotBlank)
        fun guardMet(): Boolean = guard == null || evaluate(guard, variables, input).value
        return when (spec.type) {
            FlowControl.IF -> {
                val argCondition = spec.args["condition"]?.trim()?.takeIf(String::isNotBlank)
                val condition = argCondition ?: guard ?: "true"
                val distinctGuard = guard != null && argCondition != null && guard != argCondition
                val evaluated = evaluate(condition, variables, input).value && (!distinctGuard || guardMet())
                ifResults[index] = evaluated
                val next = if (evaluated) {
                    index + 1
                } else {
                    val elseIndex = structure.ifToElse[index]
                    if (elseIndex != null) armedElseIndices += elseIndex
                    elseIndex ?: structure.ifToEndif.getValue(index) + 1
                }
                step("$condition -> $evaluated", next)
            }
            FlowControl.ELSE -> if (armedElseIndices.remove(index) || structure.ifForElse(index)?.let(ifResults::get) != true) {
                if (guardMet()) {
                    step("else branch selected", index + 1)
                } else {
                    step("else if ($guard) -> false", structure.elseToEndif.getValue(index) + 1, skipped = true)
                }
            } else {
                step("else", structure.elseToEndif.getValue(index) + 1)
            }
            FlowControl.ENDIF -> step("endif", index + 1)
            FlowControl.FOREACH -> if (!guardMet()) {
                step("foreach skipped: condition ($guard) -> false", structure.foreachToEndfor.getValue(index) + 1, skipped = true)
            } else {
                val listName = listOf("list", "in", "array", "items")
                    .firstNotNullOfOrNull { spec.args[it]?.trim()?.takeIf(String::isNotBlank) }
                val itemVar = spec.args["var"]?.trim()?.takeIf(String::isNotBlank) ?: "item"
                val items = listName?.let(variables::getArrayItems).orEmpty()
                if (items.isEmpty()) {
                    step("foreach ${listName ?: "<missing list>"} -> 0 items", structure.foreachToEndfor.getValue(index) + 1)
                } else {
                    loops.addLast(PreflightLoopFrame(index, items, itemVar, 0))
                    variables.set(itemVar, items.first())
                    step("foreach $listName -> ${items.size} items (1/${items.size})", index + 1)
                }
            }
            FlowControl.ENDFOR -> {
                val frame = loops.lastOrNull()
                if (frame == null) {
                    step("endfor without loop", index + 1, halt = true)
                } else if (!guardMet()) {
                    loops.removeLast()
                    step("endfor skipped: condition ($guard) -> false; loop exited", index + 1, skipped = true)
                } else {
                    frame.index++
                    if (frame.index < frame.items.size) {
                        variables.set(frame.itemVar, frame.items[frame.index])
                        step("loop ${frame.index + 1}/${frame.items.size}", frame.foreachIndex + 1)
                    } else {
                        loops.removeLast()
                        step("endfor", index + 1)
                    }
                }
            }
            FlowControl.TRY -> if (!guardMet()) {
                step("try skipped: condition ($guard) -> false", structure.tryToEndtry.getValue(index) + 1, skipped = true)
            } else {
                tries.addLast(index)
                step(spec.type, index + 1)
            }
            FlowControl.ENDTRY -> {
                if (tries.lastOrNull()?.let(structure.tryToEndtry::get) == index) tries.removeLast()
                step(spec.type, index + 1)
            }
            FlowControl.STOP -> if (!guardMet()) {
                step("stop skipped: condition ($guard) -> false", index + 1, skipped = true)
            } else {
                step("stop", index + 1, halt = true)
            }
            FlowControl.GOTO -> if (!guardMet()) {
                step("goto skipped: condition ($guard) -> false", index + 1, skipped = true)
            } else {
                val expansion = expandArguments(spec, variables, input)
                val target = FlowControl.gotoTarget(actions, expansion.rawValues, spec.args)
                val unresolved = FlowControl.emptiedGotoArgument(expansion.rawValues, spec.args)
                // A label that is still empty here wins over the step number once a run sets it.
                val labelUnknown = !spec.args["label"].isNullOrBlank() && expansion.rawValues["label"].isNullOrBlank()
                val landing = target.index.takeUnless { labelUnknown }
                // A step number or label read from a secret stays out of the preview, like its value.
                fun hidden(text: String) = ExportRedactionPolicy.redactText(text, expansion.secretValues, ActionArgumentSensitivity.REDACTED)
                val problem = when {
                    // Preflight doesn't play every write a run makes, so a target that is still a
                    // variable here may well be set by then.
                    unresolved != null && landing == null -> null
                    landing == null -> target.error
                    else -> {
                        while (loops.lastOrNull()?.let { landing <= it.foreachIndex || landing > structure.foreachToEndfor.getValue(it.foreachIndex) } == true) {
                            loops.removeLast()
                        }
                        while (tries.lastOrNull()?.let { landing <= it || landing > structure.tryToEndtry.getValue(it) } == true) {
                            tries.removeLast()
                        }
                        structure.unopenedBlockAt(landing, loops.map { it.foreachIndex }, tries)?.let { open ->
                            "goto step ${landing + 1} lands inside the block that opens at step ${open + 1}, which isn't running"
                        }
                    }
                }
                when {
                    problem != null -> ControlSimulation(
                        step = PreflightStep(
                            taskPath = "",
                            actionIndex = index,
                            actionType = spec.type,
                            label = spec.label ?: spec.type,
                            status = PreflightStepStatus.BLOCKED,
                            expandedArguments = expansion.values,
                            branchDecision = "goto fails",
                            intendedEffect = PreflightActionRegistry.intendedEffect(spec.type),
                            warnings = expansion.warnings + hidden("The run stops here: $problem."),
                        ),
                        nextPc = index + 1,
                        halt = true,
                    )
                    landing == null -> ControlSimulation(
                        step = PreflightStep(
                            taskPath = "",
                            actionIndex = index,
                            actionType = spec.type,
                            label = spec.label ?: spec.type,
                            status = PreflightStepStatus.SIMULATED,
                            expandedArguments = expansion.values,
                            branchDecision = "goto $unresolved",
                            intendedEffect = PreflightActionRegistry.intendedEffect(spec.type),
                            warnings = expansion.warnings +
                                "$unresolved is empty here, so the preview can't follow this goto. The steps after it weren't checked.",
                        ),
                        nextPc = index + 1,
                        halt = true,
                    )
                    else -> step(hidden("goto -> step ${landing + 1}"), landing)
                }
            }
            else -> step(spec.type, index + 1)
        }
    }

    private fun recordStep(steps: MutableMap<Int, PreflightStep>, incoming: PreflightStep) {
        val existing = steps[incoming.actionIndex]
        steps[incoming.actionIndex] = if (existing == null) incoming else existing.copy(
            executionCount = existing.executionCount + 1,
            status = if (existing.status == PreflightStepStatus.BLOCKED) existing.status else incoming.status,
            branchDecision = incoming.branchDecision ?: existing.branchDecision,
            warnings = (existing.warnings + incoming.warnings).distinct(),
        )
    }

    private fun addWarning(steps: MutableMap<Int, PreflightStep>, index: Int, warning: String) {
        steps[index]?.let { step ->
            steps[index] = step.copy(
                status = PreflightStepStatus.BLOCKED,
                warnings = (step.warnings + warning).distinct(),
            )
        }
    }

    private data class Expansion(
        val values: Map<String, String>,
        val rawValues: Map<String, String>,
        val warnings: List<String>,
        /** Set when the run would stop the step before the action got its arguments. */
        val failure: String? = null,
        /** An Array Push or Pop position that came out empty, as written, with the run's reason. */
        val unsetPosition: Pair<String, String>? = null,
        /** Expanded values that came from a secret, which [values] shows redacted. */
        val secretValues: Set<String> = emptySet(),
    )

    private fun expandArguments(spec: ActionSpec, variables: VariableStore, input: PreflightInputs): Expansion {
        val outputNames = OutputNameArguments(spec.type)
        var failure: String? = null
        var unsetPosition: Pair<String, String>? = null
        val rawValues = spec.args.mapValues { (key, raw) ->
            when {
                outputNames.keepsAsWritten(key, raw) -> TemplateExpansionResult(raw, emptyList(), emptyList())
                LineListArguments.applies(spec.type, key) -> {
                    val lines = raw.lines().map { line -> expand(line, variables, input) }
                    LineListArguments.lineBreakFailure(spec.type, key, lines.map { it.value })?.let { failure = it }
                    TemplateExpansionResult(
                        value = lines.joinToString("\n") { it.value },
                        traces = lines.flatMap { it.traces },
                        warnings = lines.flatMap { it.warnings },
                    )
                }
                else -> expand(raw, variables, input).also { expansion ->
                    ArrayPositionArguments.emptyFailure(spec.type, key, raw, expansion.value)?.let { unsetPosition = raw.trim() to it }
                }
            }
        }
        val warnings = rawValues.values.flatMap { it.warnings }
        val display = rawValues.mapValues { (key, expansion) ->
            val secret = expansion.traces.any { it.isSecretDerived }
            if (secret) ActionArgumentSensitivity.REDACTED
            else ActionArgumentSensitivity.maskValue(spec.type, key, expansion.value, rawValues.mapValues { it.value.value })
        }
        val secretValues = rawValues.values.filter { expansion -> expansion.traces.any { it.isSecretDerived } }
            .mapNotNullTo(mutableSetOf()) { it.value.takeIf(String::isNotBlank) }
        return Expansion(display, rawValues.mapValues { it.value.value }, warnings.distinct(), failure, unsetPosition, secretValues)
    }

    /**
     * True when [position] is a single global that no step here could write: no step names it in an
     * argument (an output name, a Variable Set, anything but another Push or Pop position, which
     * only reads it) and no declared output resolves to it, or, for the array output of an action
     * that writes numbered elements, to an element of it.
     */
    private fun staysUnset(position: String, task: Task, tasks: List<Task>): Boolean {
        if (!SINGLE_VARIABLE.matches(position) || !VariableNamePolicy.isGlobal(position)) return false
        val name = VariableNamePolicy.normalize(position) ?: return false
        return (tasks.filterNot { it.id == task.id } + task).flatMap(Task::actions).filter(ActionSpec::enabled).none { action ->
            val named = action.args.any { (key, value) ->
                !ArrayPositionArguments.isPosition(action.type, key) && VariableNamePolicy.normalize(value) == name
            }
            named || declaredActionOutputs(action.type).any { output ->
                val written = output.resolveName(action.args) ?: return@any false
                written == name || (output.type == ActionValueType.ARRAY && action.type in NUMBERED_ELEMENT_WRITERS && name.startsWith(written) &&
                    name.drop(written.length).let { it.isNotEmpty() && it.all(Char::isDigit) })
            }
        }
    }

    private fun expand(raw: String, variables: VariableStore, input: PreflightInputs): TemplateExpansionResult {
        val legacy = variables.expandTracked(raw)
        val result = TemplateExpressionEngine().expand(
            legacy.value,
            variables.toTemplateScope(input.eventVariables),
        ).let { it.copy(value = InertTemplateText.restore(it.value)) }
        return if (legacy.isSecretDerived && result.traces.none { it.isSecretDerived }) {
            result.copy(
                traces = result.traces + TemplateExpansionTrace(
                    rawExpression = raw,
                    expression = raw,
                    value = ActionArgumentSensitivity.REDACTED,
                    source = TemplateValueSource.GLOBAL,
                    path = raw,
                    isSecretDerived = true,
                ),
            )
        } else {
            result
        }
    }

    private fun evaluate(raw: String, variables: VariableStore, input: PreflightInputs): ConditionResult {
        val expanded = expand(raw, variables, input)
        return ConditionResult(
            // The same evaluation a run does, on the raw text. The expansion above is only shown.
            value = variables.evaluateCondition(raw, TemplateExpressionEngine(), input.eventVariables),
            display = if (expanded.traces.any { it.isSecretDerived }) ActionArgumentSensitivity.REDACTED else expanded.value,
            warnings = expanded.warnings,
        )
    }

    private data class ConditionResult(
        val value: Boolean,
        val display: String,
        val warnings: List<String>,
    )

    private fun simulateVariableWrite(spec: ActionSpec, values: Map<String, String>, variables: VariableStore) {
        when (spec.type) {
            "var.set" -> {
                val name = values["name"]?.trim().orEmpty()
                if (name.isNotBlank()) variables.set(name, values["value"].orEmpty())
            }
            "var.persist" -> {
                val source = values["name"]?.trim().orEmpty()
                // The same promotion the run uses, so "mode" becomes "Mode" here too, not "MODE". A
                // global_name that comes out blank fails the run, so it sets nothing here either.
                val target = VariableNamePolicy.promoteToGlobal(values["global_name"] ?: source)
                val value = variables.get(source)
                if (value != null && target != null) variables.set(target, value, sensitive = variables.isSensitive(source))
            }
        }
    }

    private fun resolveTask(reference: String, tasks: List<Task>): Task? =
        reference.toLongOrNull()?.let { id -> tasks.firstOrNull { it.id == id } }
            ?: tasks.firstOrNull { it.name.equals(reference, ignoreCase = true) }

    private fun seedVariables(variables: VariableStore, input: PreflightInputs) {
        variables.seedGlobals(input.globalVariables, input.secretGlobalNames)
        variables.pushScope()
        input.eventVariables.forEach { (name, value) -> variables.set(name, value, EventTaskVariables.isSensitive(name)) }
    }

    private fun setupRequirements(task: Task, tasks: List<Task>): Set<SetupRequirement> =
        SetupRequirementResolver.resolve(
            profiles = listOf(Profile(id = -1, name = "Preflight", enabled = true, enterTaskId = task.id)),
            // The task that was simulated, even when the list has an older copy of it or none yet.
            tasks = tasks.filterNot { it.id == task.id } + task,
        )

    private fun setupRequirements(profile: Profile, tasks: List<Task>): Set<SetupRequirement> =
        SetupRequirementResolver.resolve(
            profiles = listOf(profile.copy(enabled = true, requiresRiskAcknowledgement = false)),
            tasks = tasks,
        )
}

/**
 * The blocker text for a connection-restricted HTTP Request that the live transport cannot satisfy,
 * or null when there is nothing to say. Shares [httpNetworkDenial] with the action itself, so the
 * preview cannot describe a rule the runner does not apply.
 */
private fun httpNetworkBlock(
    actionType: String,
    args: Map<String, String>,
    transport: ActiveTransport?,
): String? {
    if (actionType != "http.request" || transport == null) return null
    val constraint = HttpNetworkConstraint.parse(args["network"])
    val denial = httpNetworkDenial(constraint, transport) as? ActionResult.Failure ?: return null
    return denial.message
}
