package com.opentasker.core.actions

import com.opentasker.core.data.TextOps
import com.opentasker.core.engine.ActionContext
import com.opentasker.core.engine.ActionResult

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

/** Replace-all. Args: `source`, `pattern`, `replacement`, `var`. */
class TextReplaceAction : DeclaredAction(ActionCatalog.require("text.replace")) {

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val source = args["source"] ?: return ActionResult.Failure("missing source")
        val pattern = args["pattern"]?.ifBlank { null } ?: return ActionResult.Failure("missing pattern")
        val replacement = args["replacement"] ?: ""
        val varName = outputVar(args, "result")
        val replaced = TextOps.replaceAll(source, pattern, replacement)
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
        val literal = args["delimiter"]?.ifBlank { null }
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
