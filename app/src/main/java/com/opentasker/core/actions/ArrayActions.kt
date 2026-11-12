package com.opentasker.core.actions

import com.opentasker.core.data.TextOps
import com.opentasker.core.engine.ActionContext
import com.opentasker.core.engine.ActionResult
import com.opentasker.core.engine.VariableStore
import com.opentasker.core.engine.isArgumentSensitive
import com.opentasker.core.model.VariableNamePolicy

// Tasker's arrays are numbered variables: %arr1, %arr2, ... in the scope %arr belongs to. These
// actions work on those elements, not on the arrays text.split and text.match store.

internal const val MAX_ELEMENTS = 10_000

private fun arrayBase(args: Map<String, String>, key: String): String? =
    args[key]?.let(VariableNamePolicy::normalize)

/** The 1-based `position` argument, 1 when it is left blank, or null when it isn't a whole number from 1. */
private fun position(args: Map<String, String>): Int? {
    val raw = args["position"]?.trim().orEmpty()
    return if (raw.isEmpty()) 1 else raw.toIntOrNull()?.takeIf { it >= 1 }
}

internal fun elementName(base: String, index: Int): String? =
    "$base$index".takeIf { it.length <= VariableNamePolicy.MAX_LENGTH }

/** [base]'s elements by index, each with its own secret flag so moving it keeps the flag. */
private fun VariableStore.elementsWithFlags(base: String): java.util.TreeMap<Int, Pair<String, Boolean>> =
    numberedItems(base).mapValuesTo(java.util.TreeMap()) { (index, value) -> value to isSensitive("$base$index") }

/**
 * Tasker's Variable Split. Args: `name`, `splitter`, `regex`, `delete_base`, `values`. Splits %name
 * into %name1, %name2, ... after clearing the old ones. A blank splitter splits on white space, and
 * trailing empty parts are dropped the way Tasker drops them. With `values` it splits that text
 * instead of %name's own value, which is Tasker's Array Set.
 */
class VarSplitAction : DeclaredAction(ActionCatalog.require("var.split")) {

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val base = arrayBase(args, "name") ?: return ActionResult.Failure("invalid variable name")
        val values = args["values"]
        val source = values ?: ctx.variables.get(base) ?: return ActionResult.Failure("%$base is not set")
        val splitter = args["splitter"].orEmpty()
        val regex = splitter.isEmpty() || args["regex"]?.toBooleanStrictOrNull() == true
        val parts = TextOps.split(source, splitter.ifEmpty { "\\s+" }, isRegex = regex)
            ?.dropLastWhile(String::isEmpty)
            ?: return ActionResult.Failure("invalid or oversized split")
        if (parts.size > MAX_ELEMENTS) return ActionResult.Failure("split gives more than $MAX_ELEMENTS elements")
        if (parts.isNotEmpty() && elementName(base, parts.size) == null) {
            return ActionResult.Failure("%$base${parts.size} is longer than ${VariableNamePolicy.MAX_LENGTH} characters")
        }
        val sensitive = if (values != null) ctx.isArgumentSensitive("values") else ctx.variables.isSensitive(base)
        ctx.variables.replaceNumberedItems(base, parts.withIndex().associate { (index, part) -> index + 1 to (part to sensitive) })
        if (args["delete_base"]?.toBooleanStrictOrNull() == true) ctx.variables.remove(base)
        ctx.logger("var.split -> %$base (${parts.size} element(s))")
        return ActionResult.Success
    }
}

/**
 * Tasker's Variable Join. Args: `name`, `joiner`, `delete_parts`. Joins %name1, %name2, ... in
 * index order into %name, and unsets the elements when asked. Unset elements are skipped.
 */
class VarJoinAction : DeclaredAction(ActionCatalog.require("var.join")) {

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val base = arrayBase(args, "name") ?: return ActionResult.Failure("invalid variable name")
        val items = ctx.variables.numberedItems(base)
        val joiner = args["joiner"].orEmpty()
        // Tasker adds the joiner only once the result has something in it, so empty leading
        // elements leave no joiner behind.
        val joined = buildString {
            items.values.forEach { value ->
                if (isNotEmpty()) append(joiner)
                append(value)
            }
        }
        ctx.variables.set(base, joined, sensitive = ctx.variables.anyNumberedItemSensitive(base))
        if (args["delete_parts"]?.toBooleanStrictOrNull() == true) ctx.variables.replaceNumberedItems(base, emptyMap())
        ctx.logger("var.join -> %$base (${items.size} element(s))")
        return ActionResult.Success
    }
}

/**
 * Tasker's Array Push. Args: `array`, `position` (from 1), `value`, `fill_spaces`. Inserts the
 * value and moves the elements from that position up one. A position past the end appends. With
 * `fill_spaces`, only the elements up to the first unset one move, and the value fills that gap.
 */
class ArrayPushAction : DeclaredAction(ActionCatalog.require("array.push")) {

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val base = arrayBase(args, "array") ?: return ActionResult.Failure("invalid array name")
        val requested = position(args) ?: return ActionResult.Failure("position must be a whole number from 1")
        val items = ctx.variables.elementsWithFlags(base)
        if (items.size >= MAX_ELEMENTS) return ActionResult.Failure("%$base already has $MAX_ELEMENTS elements")
        val at = requested.coerceAtMost((items.lastEntry()?.key ?: 0) + 1)
        val gap = if (args["fill_spaces"]?.toBooleanStrictOrNull() == true) {
            generateSequence(at) { it + 1 }.first { it !in items }
        } else {
            Int.MAX_VALUE
        }
        val moved = java.util.TreeMap<Int, Pair<String, Boolean>>()
        items.forEach { (index, item) -> moved[if (index in at until gap) index + 1 else index] = item }
        if (elementName(base, moved.lastEntry()?.key ?: at) == null || elementName(base, at) == null) {
            return ActionResult.Failure("%$base element names would pass ${VariableNamePolicy.MAX_LENGTH} characters")
        }
        moved[at] = args["value"].orEmpty() to false
        ctx.variables.replaceNumberedItems(base, moved)
        ctx.logger("array.push -> %$base($at)")
        return ActionResult.Success
    }
}

/**
 * Tasker's Array Pop. Args: `array`, `position` (from 1), `to_var`. Removes the element there and
 * moves the later ones down one, storing it in `to_var` when one is named. A position past the end
 * pops the last element, and an empty array is left as it is.
 */
class ArrayPopAction : DeclaredAction(ActionCatalog.require("array.pop")) {

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val base = arrayBase(args, "array") ?: return ActionResult.Failure("invalid array name")
        val requested = position(args) ?: return ActionResult.Failure("position must be a whole number from 1")
        val toVar = args["to_var"]?.trim()?.ifEmpty { null }
            ?.let { VariableNamePolicy.normalize(it) ?: return ActionResult.Failure("invalid To Var name") }
        val items = ctx.variables.elementsWithFlags(base)
        val last = items.lastEntry()?.key ?: run {
            ctx.logger("array.pop: %$base is empty")
            return ActionResult.Success
        }
        val at = requested.coerceAtMost(last)
        val popped = items[at]
        val moved = java.util.TreeMap<Int, Pair<String, Boolean>>()
        items.forEach { (index, item) -> if (index != at) moved[if (index > at) index - 1 else index] = item }
        ctx.variables.replaceNumberedItems(base, moved)
        if (toVar != null) {
            if (popped == null) ctx.variables.remove(toVar) else ctx.variables.set(toVar, popped.first, sensitive = popped.second)
        }
        ctx.logger("array.pop -> %$base($at)")
        return ActionResult.Success
    }
}

/** Tasker's Array Clear. Args: `array`. Unsets every element of the array. */
class ArrayClearAction : DeclaredAction(ActionCatalog.require("array.clear")) {

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val base = arrayBase(args, "array") ?: return ActionResult.Failure("invalid array name")
        val count = ctx.variables.numberedItems(base).size
        ctx.variables.replaceNumberedItems(base, emptyMap())
        ctx.logger("array.clear -> %$base ($count element(s))")
        return ActionResult.Success
    }
}

/**
 * Tasker's Array Process. Args: `array`, `type`. Reorders the values over the indices the elements
 * already have, so gaps stay where they are. Remove Duplicates unsets every later copy of a value,
 * and Squash renumbers the elements from 1, dropping empty ones. Equal values keep index order.
 */
class ArrayProcessAction : DeclaredAction(ActionCatalog.require("array.process")) {

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val base = arrayBase(args, "array") ?: return ActionResult.Failure("invalid array name")
        val type = args["type"]?.trim().orEmpty()
        if (type !in TYPES) return ActionResult.Failure("unknown array process type '$type'")
        val items = ctx.variables.elementsWithFlags(base)
        val values = items.values.toList()
        val processed: Map<Int, Pair<String, Boolean>> = when (type) {
            "remove_duplicates" -> {
                val seen = HashSet<String>()
                items.filterTo(java.util.TreeMap<Int, Pair<String, Boolean>>()) { (_, item) -> seen.add(item.first) }
            }
            "squash" -> values.filter { it.first.isNotEmpty() }.withIndex().associate { (index, item) -> index + 1 to item }
            else -> items.keys.zip(reorder(type, values)).toMap()
        }
        ctx.variables.replaceNumberedItems(base, processed)
        ctx.logger("array.process -> %$base ($type, ${processed.size} element(s))")
        return ActionResult.Success
    }

    private fun reorder(type: String, values: List<Pair<String, Boolean>>): List<Pair<String, Boolean>> = when (type) {
        "reverse" -> values.asReversed()
        "rotate_left" -> values.drop(1) + values.take(1)
        "rotate_right" -> values.takeLast(1) + values.dropLast(1)
        "shuffle" -> values.shuffled()
        "sort_alpha" -> values.sortedWith { a, b -> a.first.compareTo(b.first) }
        "sort_alpha_reverse" -> values.sortedWith { a, b -> a.first.compareTo(b.first) }.asReversed()
        "sort_alpha_caseless" -> values.sortedWith { a, b -> a.first.compareTo(b.first, ignoreCase = true) }
        // Tasker sorts with the comparison turned round here, not sort-then-reverse, so ties keep their order.
        "sort_alpha_caseless_reverse" -> values.sortedWith { a, b -> b.first.compareTo(a.first, ignoreCase = true) }
        "sort_shortest_first" -> values.sortedWith { a, b -> a.first.length.compareTo(b.first.length) }
        "sort_longest_first" -> values.sortedWith { a, b -> b.first.length.compareTo(a.first.length) }
        "sort_numeric_integer" -> sortedByNumber(values) { it.toLongOrNull() ?: FIRST_INTEGER.find(it)?.value?.toLongOrNull() }
        "sort_numeric_float" -> sortedByNumber(values) { it.toDoubleOrNull() ?: FIRST_DECIMAL.find(it)?.value?.toDoubleOrNull() }
        else -> throw IllegalStateException("array.process has no reorder for $type")
    }

    /** Numbers in order, then the values with no number in them, A to Z ignoring case. */
    private fun <N : Comparable<N>> sortedByNumber(
        values: List<Pair<String, Boolean>>,
        number: (String) -> N?,
    ): List<Pair<String, Boolean>> = values
        .map { it to number(it.first) }
        .sortedWith { (a, aNumber), (b, bNumber) ->
            when {
                aNumber == null && bNumber == null -> a.first.compareTo(b.first, ignoreCase = true)
                aNumber == null -> 1
                bNumber == null -> -1
                else -> aNumber.compareTo(bNumber)
            }
        }
        .map { it.first }

    companion object {
        /** The types in Tasker's order, so a type's index is the number Tasker stores for it. */
        val TYPES = listOf(
            "remove_duplicates", "reverse", "rotate_left", "rotate_right", "shuffle",
            "sort_alpha", "sort_alpha_reverse", "sort_alpha_caseless", "sort_alpha_caseless_reverse",
            "sort_shortest_first", "sort_longest_first", "sort_numeric_integer", "sort_numeric_float", "squash",
        )

        // A value that isn't a number as a whole sorts by the first number in it, as Tasker's do.
        private val FIRST_INTEGER = Regex("""-?\d+""")
        private val FIRST_DECIMAL = Regex("""-?\d+(?:\.\d+)?""")
    }
}
