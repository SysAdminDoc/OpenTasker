package com.opentasker.core.actions

import com.opentasker.core.engine.SUB_TASK_REF_KEYS
import com.opentasker.core.engine.SUB_TASK_RETURN_KEY
import com.opentasker.core.model.VariableNamePolicy
import java.io.Serializable

/** One named value a Run Task action hands to the task it runs, as a variable there (A-354). */
data class SubTaskInput(val name: String, val value: String) : Serializable

enum class SubTaskInputIssue {
    /** Not a variable name: it has to start with a letter and use letters, digits, `_` or `-`. */
    InvalidName,

    /** `task`, `name` and `id` say which task to run, so the runner never passes them on. */
    PicksTheTask,

    /** A second row with the same name would silently replace the first. */
    Duplicate,

    /** `returnVariable` names where the task's returned value goes, so it's never passed on. */
    HoldsTheReturnVariable,
}

/**
 * The inputs half of a Run Task action. The engine already passes every argument that isn't the
 * task reference into the sub-task as a variable; until the editor showed them, the only way to
 * add one was to hand-edit an exported bundle.
 */
object SubTaskInputs {
    /** The arguments on a saved action that are inputs, not the task reference or a form field. */
    fun from(args: Map<String, String>, fieldKeys: Set<String>): List<SubTaskInput> =
        args.filterKeys { it !in SUB_TASK_REF_KEYS && it != SUB_TASK_RETURN_KEY && it !in fieldKeys }
            .map { (name, value) -> SubTaskInput(name, value) }

    /** Problems by row. A row with neither a name nor a value is an unused blank and is fine. */
    fun validate(inputs: List<SubTaskInput>): Map<Int, SubTaskInputIssue> {
        val seen = mutableSetOf<String>()
        val issues = linkedMapOf<Int, SubTaskInputIssue>()
        inputs.forEachIndexed { index, input ->
            if (input.name.isBlank() && input.value.isBlank()) return@forEachIndexed
            val name = VariableNamePolicy.normalize(input.name)
            when {
                name == null -> issues[index] = SubTaskInputIssue.InvalidName
                name in SUB_TASK_REF_KEYS -> issues[index] = SubTaskInputIssue.PicksTheTask
                name == SUB_TASK_RETURN_KEY -> issues[index] = SubTaskInputIssue.HoldsTheReturnVariable
                !seen.add(name) -> issues[index] = SubTaskInputIssue.Duplicate
            }
        }
        return issues
    }

    /**
     * [args] with the inputs it was opened with replaced by [edited]. Unrelated arguments survive,
     * since the editor's field merge keeps keys it doesn't know. Names are stored the way the
     * engine reads them, so `%city` saves as `city`.
     */
    fun applyTo(args: Map<String, String>, opened: List<SubTaskInput>, edited: List<SubTaskInput>): Map<String, String> {
        val result = LinkedHashMap(args)
        opened.forEach { result.remove(it.name) }
        edited.forEach { input ->
            val name = VariableNamePolicy.normalize(input.name) ?: return@forEach
            if (name !in SUB_TASK_REF_KEYS && name != SUB_TASK_RETURN_KEY) result[name] = input.value
        }
        return result
    }
}
