package com.opentasker.core.actions

import com.opentasker.core.data.TextOps
import com.opentasker.core.engine.ActionContext
import com.opentasker.core.engine.ActionResult
import com.opentasker.core.engine.isArgumentSensitive
import com.opentasker.core.model.VariableNamePolicy

private fun outputVar(args: Map<String, String>, default: String): String =
    (args["var"] ?: args["variable"])?.trim()?.ifBlank { null } ?: default

/**
 * Regex match. Args: `source`, `pattern`, `var`. Sets `%var` to the full match, stores
 * [full, group1, ...] as the `var` array (`%var(1)` = first group), and `%var_count` to the group
 * count (0 when there is no match).
 */
class TextMatchAction : DeclaredAction(ActionCatalog.require("text.match")) {

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val source = args["source"] ?: return ActionResult.Failure("missing source")
        val pattern = args["pattern"]?.ifBlank { null } ?: return ActionResult.Failure("missing pattern")
        val varName = outputVar(args, "match")
        val groups = TextOps.match(source, pattern) ?: return ActionResult.Failure("invalid or oversized pattern")
        ctx.variables.set(varName, groups.firstOrNull() ?: "")
        ctx.variables.setArray(varName, groups)
        ctx.variables.set("${varName}_count", (groups.size - 1).coerceAtLeast(0).toString())
        ctx.logger("text.match -> \$$varName (${groups.size} value(s))")
        return ActionResult.Success
    }
}

/**
 * Regex replace, which also runs Tasker's Search Replace. Args: `source`, `pattern`, `replacement`,
 * `var`, `first_only`, `matches`, `search_only`. Sets `%var` to the source with every match
 * replaced, or only the first with `first_only`. `matches` names an array that's emptied and then
 * holds each match as %found1, %found2, ..., only the first with `first_only`. With `search_only`
 * the step only collects the matches and leaves `%var` alone.
 */
class TextReplaceAction : DeclaredAction(ActionCatalog.require("text.replace")) {

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val source = args["source"] ?: return ActionResult.Failure("missing source")
        val firstOnly = args["first_only"]?.toBooleanStrictOrNull() == true
        val searchOnly = args["search_only"]?.toBooleanStrictOrNull() == true
        val array = args["matches"]?.trim()?.ifEmpty { null }?.let { name ->
            VariableNamePolicy.normalize(name) ?: return ActionResult.Failure("invalid array name '$name'")
        }
        // Tasker empties the array before it even compiles the pattern, so a step that fails leaves
        // it empty too. A list Split Text kept under the name goes as well, or it would hide the matches
        // from Join Text and For Each. The matches go in with one write, since emptying the array first
        // would lose the scope it lives in and a sub-task would fill its own copy.
        if (array != null) ctx.variables.removeArray(array)
        fun failed(reason: String): ActionResult {
            if (array != null) ctx.variables.replaceNumberedItems(array, emptyMap())
            return ActionResult.Failure(reason)
        }
        val pattern = args["pattern"]?.ifBlank { null } ?: return failed("missing pattern")
        if (array != null || searchOnly) {
            // With no array to fill, one match is enough to check the pattern.
            val found = TextOps.findAll(source, pattern, limit = if (firstOnly || array == null) 1 else MAX_ELEMENTS + 1)
                ?: return failed("invalid or oversized pattern")
            if (array != null) {
                if (found.size > MAX_ELEMENTS) return failed("more than $MAX_ELEMENTS matches")
                if (found.isNotEmpty() && elementName(array, found.size) == null) {
                    return failed("%$array${found.size} is longer than ${VariableNamePolicy.MAX_LENGTH} characters")
                }
                val sensitive = ctx.isArgumentSensitive("source")
                ctx.variables.replaceNumberedItems(array, found.withIndex().associate { (index, match) -> index + 1 to (match to sensitive) })
                ctx.logger("text.replace -> %$array (${found.size} match(es))")
            }
            if (searchOnly) return ActionResult.Success
        }
        val replacement = args["replacement"] ?: ""
        val varName = outputVar(args, "result")
        val replaced = (if (firstOnly) TextOps.replaceFirst(source, pattern, replacement) else TextOps.replaceAll(source, pattern, replacement))
            ?: return ActionResult.Failure("invalid or oversized pattern")
        ctx.variables.set(varName, replaced)
        ctx.logger("text.replace -> \$$varName")
        return ActionResult.Success
    }
}

/**
 * Split into an array. Args: `source`, `delimiter` (literal) or `pattern` (regex), `var`. Sets the
 * `var` array and `%var_count`, and `%var` to the first element.
 */
class TextSplitAction : DeclaredAction(ActionCatalog.require("text.split")) {

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val source = args["source"] ?: return ActionResult.Failure("missing source")
        val regex = args["pattern"]?.ifBlank { null }
        val literal = args["delimiter"]?.ifEmpty { null }
        val varName = outputVar(args, "parts")
        val parts = when {
            regex != null -> TextOps.split(source, regex, isRegex = true)
            literal != null -> TextOps.split(source, literal, isRegex = false)
            else -> return ActionResult.Failure("provide a delimiter or a regex pattern")
        } ?: return ActionResult.Failure("invalid or oversized split")
        ctx.variables.set(varName, parts.firstOrNull() ?: "")
        ctx.variables.setArray(varName, parts)
        ctx.variables.set("${varName}_count", parts.size.toString())
        ctx.logger("text.split -> \$$varName (${parts.size} part(s))")
        return ActionResult.Success
    }
}

/** Join an array variable into a string. Args: `array` (array var name), `delimiter`, `var`. */
class TextJoinAction : DeclaredAction(ActionCatalog.require("text.join")) {

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val arrayName = args["array"]?.trim()?.ifBlank { null } ?: return ActionResult.Failure("missing array name")
        val delimiter = args["delimiter"] ?: ","
        val varName = outputVar(args, "joined")
        val items = ctx.variables.getArrayItems(arrayName) ?: emptyList()
        ctx.variables.set(
            varName,
            TextOps.join(items, delimiter),
            sensitive = ctx.variables.isArraySensitive(arrayName),
        )
        ctx.logger("text.join -> \$$varName (${items.size} item(s))")
        return ActionResult.Success
    }
}

/** Substring. Args: `source`, `start`, `end` (optional), `var`. Bounds are clamped. */
class TextSubstringAction : DeclaredAction(ActionCatalog.require("text.substring")) {

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val source = args["source"] ?: return ActionResult.Failure("missing source")
        val start = args["start"]?.trim()?.toIntOrNull() ?: return ActionResult.Failure("invalid start index")
        val end = args["end"]?.trim()?.ifBlank { null }?.toIntOrNull()
        val varName = outputVar(args, "substring")
        ctx.variables.set(varName, TextOps.substring(source, start, end))
        ctx.logger("text.substring -> \$$varName")
        return ActionResult.Success
    }
}

/**
 * Character count. Args: `source`, `var`. Counts the text as it reads once variables are filled in,
 * so an unset `%name`, which stays as written, counts as its own characters, as in Tasker's Test
 * Variable. Empty text counts 0, and text that is only spaces counts its spaces, so `source` isn't
 * required.
 */
class TextLengthAction : DeclaredAction(ActionCatalog.require("text.length")) {

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val source = args["source"].orEmpty()
        val varName = outputVar(args, "length")
        ctx.variables.set(varName, source.length.toString())
        ctx.logger("text.length -> \$$varName")
        return ActionResult.Success
    }
}
