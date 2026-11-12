package com.opentasker.core.engine

import com.opentasker.core.engine.variables.VariableExpander
import com.opentasker.core.model.ActionSpec

/** Flow-control action type ids, handled directly by the [TaskRunner] rather than the registry. */
object FlowControl {
    const val IF = "flow.if"
    const val ELSE = "flow.else"
    const val ENDIF = "flow.endif"
    const val FOREACH = "flow.foreach"
    const val ENDFOR = "flow.endfor"
    const val TRY = "flow.try"
    const val CATCH = "flow.catch"
    const val ENDTRY = "flow.endtry"
    const val STOP = "flow.stop"
    const val GOTO = "flow.goto"

    /** Does nothing. Tasker's Anchor, a step that only exists to carry a label a goto can jump to. */
    const val ANCHOR = "flow.anchor"

    val ALL = setOf(IF, ELSE, ENDIF, FOREACH, ENDFOR, TRY, CATCH, ENDTRY, STOP, GOTO, ANCHOR)

    /**
     * A goto's `to` values, Tasker's Top of Loop ("continue"), End of Loop ("break") and End of If.
     * Each acts on the innermost For Each or If holding the goto, and wins over a step or label.
     */
    const val GOTO_LOOP_TOP = "loop_top"
    const val GOTO_LOOP_END = "loop_end"
    const val GOTO_IF_END = "if_end"

    const val DEFAULT_MAX_ATTEMPTS = 1
    const val MAX_ATTEMPTS = 5
    const val MAX_BACKOFF_MS = 60_000L

    data class TryConfig(val maxAttempts: Int, val backoffMs: Long)

    fun parseTryConfig(args: Map<String, String>): TryConfig? {
        val attempts = args["max_attempts"]?.trim()?.takeIf(String::isNotBlank)?.toIntOrNull()
            ?: DEFAULT_MAX_ATTEMPTS
        val backoff = args["backoff_ms"]?.trim()?.takeIf(String::isNotBlank)?.toLongOrNull() ?: 0L
        if (attempts !in 1..MAX_ATTEMPTS || backoff !in 0..MAX_BACKOFF_MS) return null
        return TryConfig(attempts, backoff)
    }

    fun isControl(type: String): Boolean = type in ALL

    /** Where a goto lands, or why it can't jump. */
    data class GotoTarget(val index: Int?, val error: String? = null)

    /**
     * The step a goto jumps to: the first step labelled [label], or else step [step] counted from
     * 1. Both arrive expanded, so a step number can come from a variable.
     */
    fun gotoTarget(actions: List<ActionSpec>, label: String?, step: String?): GotoTarget {
        val name = label?.trim().orEmpty()
        if (name.isNotEmpty()) {
            val index = actions.indexOfFirst { it.label?.trim() == name }
            return if (index >= 0) GotoTarget(index) else GotoTarget(null, "goto: no step is labelled \"$name\"")
        }
        val raw = step?.trim().orEmpty()
        if (raw.isEmpty()) return GotoTarget(null, "goto needs a step number or a label")
        // A sum stored by a maths step can read 3.0.
        val number = raw.toIntOrNull()
            ?: raw.toDoubleOrNull()?.takeIf { it % 1.0 == 0.0 && it in Int.MIN_VALUE.toDouble()..Int.MAX_VALUE.toDouble() }?.toInt()
            ?: return GotoTarget(null, "goto: \"$raw\" isn't a step number")
        if (number !in 1..actions.size) return GotoTarget(null, "goto: the task has no step $number (it has ${actions.size})")
        return GotoTarget(number - 1)
    }

    /**
     * [gotoTarget] for a goto step's arguments, [expanded] as they read now and [written] as the
     * step has them. A variable that is empty or unset (an unset one stays as written) names no
     * target, so when that left the goto with none the error names the variable.
     */
    fun gotoTarget(actions: List<ActionSpec>, expanded: Map<String, String>, written: Map<String, String>): GotoTarget {
        val values = valuesRead(expanded, written)
        val target = gotoTarget(actions, values["label"], values["step"])
        if (target.index != null || GOTO_KEYS.any { !values[it].isNullOrBlank() }) return target
        val emptied = emptiedGotoArgument(expanded, written) ?: return target
        return GotoTarget(null, "goto: $emptied is empty, so there's no step to jump to")
    }

    /** The label or step a goto step was written with, like %later, that came out empty or unset. */
    fun emptiedGotoArgument(expanded: Map<String, String>, written: Map<String, String>): String? {
        val values = valuesRead(expanded, written)
        return GOTO_KEYS.firstNotNullOfOrNull { key ->
            written[key]?.trim()?.takeIf { it.isNotEmpty() && values[key].isNullOrBlank() }
        }
    }

    /** The goto's label and step, with one that's an unset variable kept as written read as empty. */
    fun valuesRead(expanded: Map<String, String>, written: Map<String, String>): Map<String, String?> =
        GOTO_KEYS.associateWith { key -> expanded[key]?.takeUnless { VariableExpander.keptUnset(written[key], it) } }

    private val GOTO_KEYS = listOf("label", "step")
}

data class TryRetryPlan(
    val retryableActionIds: List<String>,
    val nonRetryableActionIds: List<String>,
)

/**
 * Describes which actions in a try body can be repeated after a transient failure. Control
 * markers are omitted because the runner retries the body action that failed, not the markers.
 * Unknown and engine-handled actions are deliberately non-retryable.
 */
fun tryRetryPlan(
    actions: List<ActionSpec>,
    tryIndex: Int,
    safetyFor: (ActionSpec) -> ActionRetrySafety? = { spec ->
        ActionRegistry.get(spec.type)?.retrySafetyFor(spec.args)
    },
): TryRetryPlan {
    val structure = FlowStructure.analyze(actions)
    val endIndex = structure.tryToEndtry[tryIndex] ?: return TryRetryPlan(emptyList(), emptyList())
    val bodyEnd = structure.tryToCatch[tryIndex] ?: endIndex
    val retryable = linkedSetOf<String>()
    val nonRetryable = linkedSetOf<String>()
    for (index in (tryIndex + 1) until bodyEnd) {
        val action = actions.getOrNull(index) ?: continue
        if (FlowControl.isControl(action.type) || !action.enabled) continue
        if (safetyFor(action) == ActionRetrySafety.IDEMPOTENT) {
            retryable += action.type
        } else {
            nonRetryable += action.type
        }
    }
    return TryRetryPlan(retryable.toList(), nonRetryable.toList())
}

/**
 * Resolved jump targets for block-structured flow control within a single task's action list.
 *
 * All maps are keyed by the action index of the opening/closing marker so the interpreter can jump
 * in O(1). [analyze] validates that every `flow.if`/`flow.foreach` is correctly paired and nested;
 * an unbalanced list produces an [error] string and the task fails honestly instead of misbehaving.
 */
data class FlowStructure(
    /** flow.if index -> matching flow.else index (or null when there is no else). */
    val ifToElse: Map<Int, Int>,
    /** flow.if index -> matching flow.endif index. */
    val ifToEndif: Map<Int, Int>,
    /** flow.else index -> matching flow.endif index. */
    val elseToEndif: Map<Int, Int>,
    /** flow.foreach index -> matching flow.endfor index. */
    val foreachToEndfor: Map<Int, Int>,
    /** flow.endfor index -> matching flow.foreach index. */
    val endforToForeach: Map<Int, Int>,
    /** flow.try index -> matching flow.catch index, when a catch block is present. */
    val tryToCatch: Map<Int, Int>,
    /** flow.try index -> matching flow.endtry index. */
    val tryToEndtry: Map<Int, Int>,
    /** flow.catch index -> matching flow.endtry index. */
    val catchToEndtry: Map<Int, Int>,
    val error: String? = null,
) {
    /** flow.else index -> its flow.if index. */
    fun ifForElse(elseIndex: Int): Int? = ifToElse.entries.firstOrNull { it.value == elseIndex }?.key

    /**
     * The opening step of a For Each or Try whose body holds [target] while that block isn't
     * running, or null. A goto can leave a block, but it can't start one halfway through.
     */
    fun unopenedBlockAt(target: Int, runningLoops: Collection<Int>, runningTries: Collection<Int>): Int? =
        foreachToEndfor.entries.firstOrNull { (open, end) -> target in open + 1..end && open !in runningLoops }?.key
            ?: tryToEndtry.entries.firstOrNull { (open, end) -> target in open + 1..end && open !in runningTries }?.key

    /** The flow.foreach index of the innermost loop whose body holds [index], or null. */
    fun enclosingLoop(index: Int): Int? =
        foreachToEndfor.entries.filter { (open, end) -> index in open + 1 until end }.maxOfOrNull { it.key }

    /** The flow.if index of the innermost If whose branches hold [index], or null. */
    fun enclosingIf(index: Int): Int? =
        ifToEndif.entries.filter { (open, end) -> index in open + 1 until end }.maxOfOrNull { it.key }

    companion object {
        fun analyze(actions: List<ActionSpec>): FlowStructure {
            val ifToElse = mutableMapOf<Int, Int>()
            val ifToEndif = mutableMapOf<Int, Int>()
            val elseToEndif = mutableMapOf<Int, Int>()
            val foreachToEndfor = mutableMapOf<Int, Int>()
            val endforToForeach = mutableMapOf<Int, Int>()
            val tryToCatch = mutableMapOf<Int, Int>()
            val tryToEndtry = mutableMapOf<Int, Int>()
            val catchToEndtry = mutableMapOf<Int, Int>()

            // Stack entries: marker type + opening index (+ optional else index for if-blocks).
            data class Frame(
                val type: String,
                val openIndex: Int,
                var elseIndex: Int? = null,
                var catchIndex: Int? = null,
            )
            val stack = ArrayDeque<Frame>()

            actions.forEachIndexed { index, spec ->
                // A marker turned off isn't there, so the body it held runs as part of the block around it.
                if (!spec.enabled) return@forEachIndexed
                when (spec.type) {
                    FlowControl.IF -> stack.addLast(Frame(FlowControl.IF, index))
                    FlowControl.FOREACH -> stack.addLast(Frame(FlowControl.FOREACH, index))
                    FlowControl.TRY -> {
                        if (FlowControl.parseTryConfig(spec.args) == null) {
                            return error("invalid flow.try retry bounds at step ${index + 1}")
                        }
                        stack.addLast(Frame(FlowControl.TRY, index))
                    }
                    FlowControl.ELSE -> {
                        val frame = stack.lastOrNull()
                            ?: return error("flow.else without matching flow.if at step ${index + 1}")
                        if (frame.type != FlowControl.IF) {
                            return error("flow.else inside a ${frame.type} block at step ${index + 1}")
                        }
                        if (frame.elseIndex != null) {
                            return error("duplicate flow.else at step ${index + 1}")
                        }
                        frame.elseIndex = index
                    }
                    FlowControl.ENDIF -> {
                        val frame = stack.removeLastOrNull()
                            ?: return error("flow.endif without matching flow.if at step ${index + 1}")
                        if (frame.type != FlowControl.IF) {
                            return error("flow.endif closing a ${frame.type} block at step ${index + 1}")
                        }
                        frame.elseIndex?.let { elseIndex ->
                            ifToElse[frame.openIndex] = elseIndex
                            elseToEndif[elseIndex] = index
                        }
                        ifToEndif[frame.openIndex] = index
                    }
                    FlowControl.ENDFOR -> {
                        val frame = stack.removeLastOrNull()
                            ?: return error("flow.endfor without matching flow.foreach at step ${index + 1}")
                        if (frame.type != FlowControl.FOREACH) {
                            return error("flow.endfor closing a ${frame.type} block at step ${index + 1}")
                        }
                        foreachToEndfor[frame.openIndex] = index
                        endforToForeach[index] = frame.openIndex
                    }
                    FlowControl.CATCH -> {
                        val frame = stack.lastOrNull()
                            ?: return error("flow.catch without matching flow.try at step ${index + 1}")
                        if (frame.type != FlowControl.TRY) {
                            return error("flow.catch inside a ${frame.type} block at step ${index + 1}")
                        }
                        if (frame.catchIndex != null) {
                            return error("duplicate flow.catch at step ${index + 1}")
                        }
                        frame.catchIndex = index
                    }
                    FlowControl.ENDTRY -> {
                        val frame = stack.removeLastOrNull()
                            ?: return error("flow.endtry without matching flow.try at step ${index + 1}")
                        if (frame.type != FlowControl.TRY) {
                            return error("flow.endtry closing a ${frame.type} block at step ${index + 1}")
                        }
                        frame.catchIndex?.let { catchIndex ->
                            tryToCatch[frame.openIndex] = catchIndex
                            catchToEndtry[catchIndex] = index
                        }
                        tryToEndtry[frame.openIndex] = index
                    }
                }
            }

            if (stack.isNotEmpty()) {
                val unclosed = stack.last()
                val marker = unclosed.type
                return error("unclosed $marker block opened at step ${unclosed.openIndex + 1}")
            }

            return FlowStructure(
                ifToElse,
                ifToEndif,
                elseToEndif,
                foreachToEndfor,
                endforToForeach,
                tryToCatch,
                tryToEndtry,
                catchToEndtry,
            )
        }

        private fun error(message: String) = FlowStructure(
            emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyMap(), error = message,
        )
    }
}
