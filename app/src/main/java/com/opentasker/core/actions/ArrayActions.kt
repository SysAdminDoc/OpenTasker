package com.opentasker.core.actions

import com.opentasker.core.data.TextOps
import com.opentasker.core.engine.ActionContext
import com.opentasker.core.engine.ActionResult
import com.opentasker.core.engine.VariableStore
import com.opentasker.core.model.VariableNamePolicy

// Tasker's arrays are numbered variables: %arr1, %arr2, ... in the scope %arr belongs to. These
// actions work on those elements, not on the arrays text.split and text.match store.

private const val MAX_ELEMENTS = 10_000

private fun arrayBase(args: Map<String, String>, key: String): String? =
    args[key]?.let(VariableNamePolicy::normalize)

/** The 1-based `position` argument, 1 when it is left blank, or null when it isn't a whole number from 1. */
private fun position(args: Map<String, String>): Int? {
    val raw = args["position"]?.trim().orEmpty()
    return if (raw.isEmpty()) 1 else raw.toIntOrNull()?.takeIf { it >= 1 }
}

private fun elementName(base: String, index: Int): String? =
    "$base$index".takeIf { it.length <= VariableNamePolicy.MAX_LENGTH }

/** [base]'s elements by index, each with its own secret flag so moving it keeps the flag. */
private fun VariableStore.elementsWithFlags(base: String): java.util.TreeMap<Int, Pair<String, Boolean>> =
    numberedItems(base).mapValuesTo(java.util.TreeMap()) { (index, value) -> value to isSensitive("$base$index") }

/**
 * Tasker's Variable Split. Args: `name`, `splitter`, `regex`, `delete_base`. Splits %name into
 * %name1, %name2, ... after clearing the old ones. A blank splitter splits on white space, and
 * trailing empty parts are dropped the way Tasker drops them.
 */
class VarSplitAction : DeclaredAction(ActionCatalog.require("var.split")) {

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val base = arrayBase(args, "name") ?: return ActionResult.Failure("invalid variable name")
        val source = ctx.variables.get(base) ?: return ActionResult.Failure("%$base is not set")
        val splitter = args["splitter"].orEmpty()
        val regex = splitter.isEmpty() || args["regex"]?.toBooleanStrictOrNull() == true
        val parts = TextOps.split(source, splitter.ifEmpty { "\\s+" }, isRegex = regex)
            ?.dropLastWhile(String::isEmpty)
            ?: return ActionResult.Failure("invalid or oversized split")
        if (parts.size > MAX_ELEMENTS) return ActionResult.Failure("split gives more than $MAX_ELEMENTS elements")
        if (parts.isNotEmpty() && elementName(base, parts.size) == null) {
            return ActionResult.Failure("%$base${parts.size} is longer than ${VariableNamePolicy.MAX_LENGTH} characters")
        }
        val sensitive = ctx.variables.isSensitive(base)
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
