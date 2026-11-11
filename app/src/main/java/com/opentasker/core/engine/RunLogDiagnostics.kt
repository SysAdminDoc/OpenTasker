package com.opentasker.core.engine

import com.opentasker.core.model.RunLogEntry

enum class RunLogOutcome(val label: String) {
    Succeeded("Succeeded"),
    Failed("Failed"),
    Skipped("Skipped"),
    Held("Held"),

    /** The run started and was stopped on purpose; distinct from a run that never started. */
    Cancelled("Cancelled"),

    /** The process ended before the admitted execution could write a terminal record. */
    Interrupted("Interrupted"),
}

data class RunLogDiagnostics(
    val source: String? = null,
    val executionId: String? = null,
    val replayOf: String? = null,
    val parentExecutionId: String? = null,
    val causalDepth: Int? = null,
    val causalProfileChain: List<String> = emptyList(),
    val producer: String? = null,
    val terminalReason: String? = null,
    val decision: String? = null,
    val reason: String? = null,
    val traces: List<RunLogActionDiagnostic> = emptyList(),
    val detailLines: List<String> = emptyList(),
) {
    val isSkipped: Boolean
        get() = decision.equals(SKIPPED_DECISION, ignoreCase = true)

    val isHeld: Boolean
        get() = decision.equals(HELD_DECISION, ignoreCase = true)

    val isCancelled: Boolean
        get() = decision.equals(CANCELLED_DECISION, ignoreCase = true)

    val isInterrupted: Boolean
        get() = decision.equals(INTERRUPTED_DECISION, ignoreCase = true)
}

data class RunLogActionDiagnostic(
    val index: Int,
    val status: ActionTraceStatus,
    val label: String,
    val actionType: String,
    val durationMs: Long,
    val message: String,
    val argumentSummary: String? = null,
    val templateWarningCount: Int = 0,
    val templateExpressions: List<RunLogTemplateDiagnostic> = emptyList(),
    val variableChanges: List<RunLogVariableChange> = emptyList(),
)

/** A variable this step added or modified, as recovered from the stored run log. */
data class RunLogVariableChange(
    val scope: String,
    val name: String,
    val value: String,
    val added: Boolean,
)

data class RunLogTemplateDiagnostic(
    val argName: String,
    val source: String,
    val expression: String,
    val value: String,
    val warning: String? = null,
)

fun RunLogEntry.outcome(): RunLogOutcome {
    val diagnostics = message.toRunLogDiagnostics()
    return when {
        diagnostics.isInterrupted -> RunLogOutcome.Interrupted
        diagnostics.isCancelled -> RunLogOutcome.Cancelled
        held || diagnostics.isHeld -> RunLogOutcome.Held
        diagnostics.isSkipped -> RunLogOutcome.Skipped
        success -> RunLogOutcome.Succeeded
        else -> RunLogOutcome.Failed
    }
}

fun runLogMessage(
    source: String,
    execution: ExecutionEnvelope? = null,
    terminalReason: ExecutionTerminalReason? = null,
    metadata: List<String> = emptyList(),
    traces: List<ActionExecutionTrace> = emptyList(),
): String = buildList {
    add("Source: ${(execution?.source ?: source).trim()}")
    execution?.metadataLines().orEmpty()
        .plus(terminalReason?.let { listOf("Terminal reason: ${it.render()}") }.orEmpty())
        .plus(metadata)
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .forEach(::add)
    addAll(traces.toRunLogMessage().lines())
}.joinToString("\n")

fun skippedRunLogMessage(
    source: String,
    reason: String,
    execution: ExecutionEnvelope? = null,
    terminalReason: ExecutionTerminalReason? = null,
    metadata: List<String> = emptyList(),
): String = buildList {
    add("Source: ${(execution?.source ?: source).trim()}")
    add("Decision: $SKIPPED_DECISION")
    add("Reason: ${reason.trim()}")
    execution?.metadataLines().orEmpty()
        .plus(terminalReason?.let { listOf("Terminal reason: ${it.render()}") }.orEmpty())
        .plus(metadata)
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .forEach(::add)
}.joinToString("\n")

fun heldRunLogMessage(
    source: String,
    reason: String,
    execution: ExecutionEnvelope? = null,
    terminalReason: ExecutionTerminalReason? = null,
    metadata: List<String> = emptyList(),
): String = buildList {
    add("Source: ${(execution?.source ?: source).trim()}")
    add("Decision: $HELD_DECISION")
    add("Reason: ${reason.trim()}")
    execution?.metadataLines().orEmpty()
        .plus(terminalReason?.let { listOf("Terminal reason: ${it.render()}") }.orEmpty())
        .plus(metadata)
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .forEach(::add)
}.joinToString("\n")

/**
 * A run that started and was stopped on purpose. Kept distinct from the skipped message so the
 * Run Log can tell "never ran" apart from "was running and I stopped it".
 */
fun cancelledRunLogMessage(
    source: String,
    reason: String,
    execution: ExecutionEnvelope? = null,
    terminalReason: ExecutionTerminalReason? = null,
    metadata: List<String> = emptyList(),
): String = buildList {
    add("Source: ${(execution?.source ?: source).trim()}")
    add("Decision: $CANCELLED_DECISION")
    add("Reason: ${reason.trim()}")
    execution?.metadataLines().orEmpty()
        .plus(terminalReason?.let { listOf("Terminal reason: ${it.render()}") }.orEmpty())
        .plus(metadata)
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .forEach(::add)
}.joinToString("\n")

fun String.toRunLogDiagnostics(): RunLogDiagnostics {
    if (isBlank()) return RunLogDiagnostics()

    var source: String? = null
    var executionId: String? = null
    var replayOf: String? = null
    var parentExecutionId: String? = null
    var causalDepth: Int? = null
    var causalProfileChain = emptyList<String>()
    var producer: String? = null
    var terminalReason: String? = null
    var decision: String? = null
    var reason: String? = null
    val traces = mutableListOf<RunLogActionDiagnostic>()
    val details = mutableListOf<String>()

    lineSequence()
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .forEach { line ->
            when {
                line == LEGACY_EXTERNAL_SOURCE -> source = LEGACY_EXTERNAL_SOURCE
                line.startsWith(SOURCE_PREFIX, ignoreCase = true) -> source = line.valueAfterPrefix(SOURCE_PREFIX)
                line.startsWith(EXECUTION_ID_PREFIX, ignoreCase = true) -> executionId = line.valueAfterPrefix(EXECUTION_ID_PREFIX)
                line.startsWith(REPLAY_OF_PREFIX, ignoreCase = true) -> replayOf = line.valueAfterPrefix(REPLAY_OF_PREFIX)
                line.startsWith(PARENT_EXECUTION_ID_PREFIX, ignoreCase = true) -> parentExecutionId = line.valueAfterPrefix(PARENT_EXECUTION_ID_PREFIX)
                line.startsWith(CAUSAL_DEPTH_PREFIX, ignoreCase = true) -> {
                    causalDepth = line.valueAfterPrefix(CAUSAL_DEPTH_PREFIX).toIntOrNull()
                    details.add(line)
                }
                line.startsWith(CAUSAL_CHAIN_PREFIX, ignoreCase = true) -> {
                    causalProfileChain = line.valueAfterPrefix(CAUSAL_CHAIN_PREFIX)
                        .split(" -> ")
                        .map(String::trim)
                        .filter(String::isNotBlank)
                    details.add(line)
                }
                line.startsWith(PRODUCER_PREFIX, ignoreCase = true) -> producer = line.valueAfterPrefix(PRODUCER_PREFIX)
                line.startsWith(TERMINAL_REASON_PREFIX, ignoreCase = true) -> terminalReason = line.valueAfterPrefix(TERMINAL_REASON_PREFIX)
                line.startsWith(DECISION_PREFIX, ignoreCase = true) -> decision = line.valueAfterPrefix(DECISION_PREFIX)
                line.startsWith(REASON_PREFIX, ignoreCase = true) -> reason = line.valueAfterPrefix(REASON_PREFIX)
                line.startsWith(VARIABLE_CHANGE_PREFIX, ignoreCase = true) -> {
                    val change = parseVariableChangeLine(line)
                    if (change != null && traces.isNotEmpty()) {
                        val previous = traces.removeAt(traces.lastIndex)
                        traces += previous.copy(variableChanges = previous.variableChanges + change)
                    } else {
                        details.add(line)
                    }
                }
                line.startsWith(TEMPLATE_TRACE_PREFIX, ignoreCase = true) -> {
                    val template = parseTemplateTraceLine(line)
                    if (template != null && traces.isNotEmpty()) {
                        val previous = traces.removeAt(traces.lastIndex)
                        traces += previous.copy(templateExpressions = previous.templateExpressions + template)
                    } else {
                        details.add(line)
                    }
                }
                else -> parseTraceLine(line)?.let(traces::add) ?: details.add(line)
            }
        }

    return RunLogDiagnostics(
        source = source?.takeIf { it.isNotBlank() },
        executionId = executionId?.takeIf { it.isNotBlank() },
        replayOf = replayOf?.takeIf { it.isNotBlank() },
        parentExecutionId = parentExecutionId?.takeIf { it.isNotBlank() },
        causalDepth = causalDepth,
        causalProfileChain = causalProfileChain,
        producer = producer?.takeIf { it.isNotBlank() },
        terminalReason = terminalReason?.takeIf { it.isNotBlank() },
        decision = decision?.takeIf { it.isNotBlank() },
        reason = reason?.takeIf { it.isNotBlank() },
        traces = traces,
        detailLines = details,
    )
}

private fun String.valueAfterPrefix(prefix: String): String =
    substring(prefix.length).trim()

private fun parseTraceLine(line: String): RunLogActionDiagnostic? {
    val match = tracePattern.matchEntire(line) ?: return null
    val status = runCatching { ActionTraceStatus.valueOf(match.groupValues[2].uppercase()) }.getOrNull()
        ?: return null
    val parsedMessage = parseTraceMessage(match.groupValues[6])
    return RunLogActionDiagnostic(
        index = match.groupValues[1].toIntOrNull()?.minus(1) ?: return null,
        status = status,
        label = match.groupValues[3],
        actionType = match.groupValues[4],
        durationMs = match.groupValues[5].toLongOrNull() ?: return null,
        message = parsedMessage.message,
        argumentSummary = parsedMessage.argumentSummary,
        templateWarningCount = parsedMessage.templateWarningCount,
    )
}

private fun parseTraceMessage(message: String): ParsedTraceMessage {
    val detailStart = message.lastIndexOf(" (")
    if (detailStart == -1 || !message.endsWith(")")) {
        return ParsedTraceMessage(message)
    }

    val details = message.substring(detailStart + 2, message.length - 1)
    val segments = details.split(";").map { it.trim() }.filter { it.isNotBlank() }
    if (segments.none { it.startsWith(ARGUMENTS_DETAIL_PREFIX) || it.startsWith(TEMPLATE_WARNINGS_DETAIL_PREFIX) }) {
        return ParsedTraceMessage(message)
    }

    val argumentSummary = segments
        .firstOrNull { it.startsWith(ARGUMENTS_DETAIL_PREFIX) }
        ?.substring(ARGUMENTS_DETAIL_PREFIX.length)
        ?.trim()
        ?.takeIf { it.isNotBlank() }
    val warningCount = segments
        .firstOrNull { it.startsWith(TEMPLATE_WARNINGS_DETAIL_PREFIX) }
        ?.substring(TEMPLATE_WARNINGS_DETAIL_PREFIX.length)
        ?.trim()
        ?.toIntOrNull()
        ?: 0

    return ParsedTraceMessage(
        message = message.substring(0, detailStart),
        argumentSummary = argumentSummary,
        templateWarningCount = warningCount,
    )
}

private fun parseTemplateTraceLine(line: String): RunLogTemplateDiagnostic? {
    val parts = line.split('\t', limit = TEMPLATE_TRACE_SPLIT_LIMIT)
    if (parts.size < TEMPLATE_TRACE_MIN_FIELD_COUNT || !parts.first().equals(TEMPLATE_TRACE_PREFIX, ignoreCase = true)) {
        return null
    }
    return RunLogTemplateDiagnostic(
        argName = parts[1].trim().takeIf { it.isNotBlank() } ?: return null,
        source = parts[2].trim().takeIf { it.isNotBlank() } ?: return null,
        expression = parts[3].trim(),
        // A blank value with no warning leaves the line ending in tabs, which the line trim removes.
        value = parts.getOrNull(4)?.trim().orEmpty(),
        warning = parts.getOrNull(5)?.trim()?.takeIf { it.isNotBlank() },
    )
}

private fun parseVariableChangeLine(line: String): RunLogVariableChange? {
    val parts = line.split('	', limit = VARIABLE_CHANGE_SPLIT_LIMIT)
    if (parts.size < VARIABLE_CHANGE_FIELD_COUNT || !parts.first().equals(VARIABLE_CHANGE_PREFIX, ignoreCase = true)) {
        return null
    }
    // With the value optional, a line missing a column would otherwise read shifted by one.
    val change = parts[3].trim()
    if (!change.equals(VARIABLE_CHANGE_ADDED, ignoreCase = true) && !change.equals(VARIABLE_CHANGE_UPDATED, ignoreCase = true)) {
        return null
    }
    return RunLogVariableChange(
        scope = parts[1].trim().takeIf { it.isNotBlank() } ?: return null,
        name = parts[2].trim().takeIf { it.isNotBlank() } ?: return null,
        added = change.equals(VARIABLE_CHANGE_ADDED, ignoreCase = true),
        // A blank value leaves the line ending in a tab, which the line trim above removes.
        value = parts.getOrNull(4)?.trim().orEmpty(),
    )
}

private data class ParsedTraceMessage(
    val message: String,
    val argumentSummary: String? = null,
    val templateWarningCount: Int = 0,
)

private val tracePattern = Regex("""^(\d+)\. ([a-z]+): (.*?) \[([^]]+)] (\d+)ms - (.*)$""")
private const val SOURCE_PREFIX = "Source:"
private const val EXECUTION_ID_PREFIX = "Execution ID:"
private const val REPLAY_OF_PREFIX = "Replay of:"
private const val PARENT_EXECUTION_ID_PREFIX = "Parent execution ID:"
private const val CAUSAL_DEPTH_PREFIX = "Causal depth:"
private const val CAUSAL_CHAIN_PREFIX = "Causal profile chain:"
private const val PRODUCER_PREFIX = "Producer:"
private const val TERMINAL_REASON_PREFIX = "Terminal reason:"
private const val DECISION_PREFIX = "Decision:"
private const val REASON_PREFIX = "Reason:"
private const val ARGUMENTS_DETAIL_PREFIX = "args:"
private const val TEMPLATE_WARNINGS_DETAIL_PREFIX = "template warnings:"
private const val TEMPLATE_TRACE_PREFIX = "Template:"
private const val VARIABLE_CHANGE_PREFIX = "Var:"
private const val VARIABLE_CHANGE_ADDED = "added"
private const val VARIABLE_CHANGE_UPDATED = "updated"
private const val VARIABLE_CHANGE_FIELD_COUNT = 4
private const val VARIABLE_CHANGE_SPLIT_LIMIT = 5
private const val TEMPLATE_TRACE_MIN_FIELD_COUNT = 4
private const val TEMPLATE_TRACE_SPLIT_LIMIT = 6
private const val SKIPPED_DECISION = "Skipped"
private const val HELD_DECISION = "Held"
private const val CANCELLED_DECISION = "Cancelled"
private const val INTERRUPTED_DECISION = "Interrupted"
private const val LEGACY_EXTERNAL_SOURCE = "External intent"
