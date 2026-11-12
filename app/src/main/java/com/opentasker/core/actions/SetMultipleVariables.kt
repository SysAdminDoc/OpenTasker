package com.opentasker.core.actions

import com.opentasker.core.engine.ActionContext
import com.opentasker.core.engine.ActionResult
import com.opentasker.core.engine.VariableStore
import com.opentasker.core.expressions.InertTemplateText
import com.opentasker.core.expressions.TemplateExpressionEngine
import com.opentasker.core.model.VariableNamePolicy

/**
 * Tasker's Multiple Variables Set. Args: `names` and `values`, each split on its own splitter
 * (`names_splitter`, `values_splitter`), and `keep_existing`. A blank splitter means a new line when
 * the text has one, else a comma, as in Tasker. The first name gets the first value and so on, and a
 * name past the last value is unset, the way Tasker unsets it. With no values at all, a name can
 * carry its own after the Values splitter, so a line `%a,1` among names on their own lines sets %a
 * to 1. Names are read as written, so `%Sunrise` stays the name once
 * it's set; one built from other variables (`%item%n`) is filled in first. Keep Existing leaves a
 * variable that already has a value alone.
 */
class SetMultipleVariablesAction : DeclaredAction(ActionCatalog.require("var.set_multiple")) {

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val outcome = ctx.variables.setMultipleVariables(args, ctx.eventVariables)
        if (outcome.isEmpty()) return ActionResult.Failure(multipleVariablesFailure(args, outcome)!!)
        // Names only: a value could be a secret that only its name gives away. A names list that may
        // hold a secret, like a typed value read as a bare name or a name built from one, is only counted.
        val hidden = outcome.builtFromSecret || ActionArgumentSensitivity.isSensitive("var.set_multiple", "names", args)
        fun shown(names: List<String>) = if (hidden) "${names.size} variable(s)" else names.joinToString { "%$it" }
        ctx.logger(
            buildList {
                if (outcome.set.isNotEmpty()) add("set " + shown(outcome.set))
                if (outcome.cleared.isNotEmpty()) add("unset " + shown(outcome.cleared))
                if (outcome.kept.isNotEmpty()) add("kept " + shown(outcome.kept))
            }.joinToString("; ", prefix = "var.set_multiple -> "),
        )
        return multipleVariablesFailure(args, outcome)?.let(ActionResult::Failure) ?: ActionResult.Success
    }
}

/**
 * Why a run of [args] fails the step, or null when it doesn't. Names that aren't variables are
 * listed unless the names list may hold a secret, the way a single line `%api_token,abc` does.
 */
internal fun multipleVariablesFailure(args: Map<String, String>, outcome: MultipleVariablesOutcome): String? = when {
    outcome.isEmpty() -> "no variable names given"
    outcome.invalid.isEmpty() -> null
    ActionArgumentSensitivity.isSensitive("var.set_multiple", "names", args) ->
        "invalid variable name(s): ${ActionArgumentSensitivity.REDACTED}"
    else -> "invalid variable name(s): ${outcome.invalid.joinToString()}"
}

/**
 * What [setMultipleVariables] did with each name, by canonical name; [invalid] keeps the text as
 * written. [builtFromSecret] says a name was filled in from a secret, like `%cache_%Pin`.
 */
internal data class MultipleVariablesOutcome(
    val set: List<String>,
    val cleared: List<String>,
    val kept: List<String>,
    val invalid: List<String>,
    val builtFromSecret: Boolean = false,
) {
    fun isEmpty(): Boolean = set.isEmpty() && cleared.isEmpty() && kept.isEmpty() && invalid.isEmpty()
}

/**
 * What [SetMultipleVariablesAction] writes, shared with the preview. Each name is settled in turn, so
 * a name listed twice with Keep Existing keeps the first value it got.
 */
internal fun VariableStore.setMultipleVariables(
    args: Map<String, String>,
    eventVariables: Map<String, String> = emptyMap(),
): MultipleVariablesOutcome {
    val valuesText = args["values"].orEmpty()
    val values = if (valuesText.isEmpty()) emptyList() else valuesText.split(multipleVariablesSplitter(args["values_splitter"], valuesText))
    val keepExisting = args["keep_existing"]?.toBooleanStrictOrNull() == true

    val assigned = mutableListOf<String>()
    val cleared = mutableListOf<String>()
    val kept = mutableListOf<String>()
    val invalid = mutableListOf<String>()
    var builtFromSecret = false
    // A blank entry still takes its place, so the names after it keep lining up with their values.
    multipleVariablesEntries(args).forEachIndexed { index, entry ->
        val (written, inlineValue) = entry ?: return@forEachIndexed
        // The names field is never filled in by the engine, so a name built from a secret is tracked here.
        val direct = VariableNamePolicy.normalize(written)
        val built = if (direct == null) expandTracked(written) else null
        val name = direct ?: built?.text?.let(VariableNamePolicy::normalize)
        if (name == null) {
            invalid += written.ifEmpty { "(blank name)" }
            return@forEachIndexed
        }
        val secretName = built?.isSecretDerived == true
        builtFromSecret = builtFromSecret || secretName
        when {
            keepExisting && !get(name).isNullOrEmpty() -> kept += name
            // Filled in here for the same reason.
            inlineValue != null -> expandInlineValue(inlineValue, eventVariables).let { (text, secret) -> set(name, text, sensitive = secret || secretName) }.also { assigned += name }
            index < values.size -> set(name, values[index], sensitive = secretName).also { assigned += name }
            else -> clearForTask(name).also { cleared += name }
        }
    }
    return MultipleVariablesOutcome(assigned, cleared, kept, invalid, builtFromSecret)
}

/**
 * The two stages a run gives any other argument: `%var` first, then `{{ ... }}`, with a `{{` that a
 * variable's own value holds kept as text. The pair's second half says a secret fed into it.
 */
private fun VariableStore.expandInlineValue(raw: String, eventVariables: Map<String, String>): Pair<String, Boolean> {
    val legacy = expandTracked(raw)
    if (!legacy.value.contains("{{")) return legacy.text to legacy.isSecretDerived
    val result = TemplateExpressionEngine().expand(legacy.value, toTemplateScope(eventVariables))
    return InertTemplateText.restore(result.value) to (legacy.isSecretDerived || result.traces.any { it.isSecretDerived })
}

/**
 * What ends a name and starts its value in the names field, which Tasker reads only while Values is
 * empty: the Values splitter, a comma unless one is written, so `%count,0` on a line of its own sets
 * `%count` to 0. A single line is split into names on its commas first. Null when Values has text,
 * or when the splitter is only letters, digits and `_`, which Tasker reads as part of the name.
 */
internal fun multipleVariablesInlineSplitter(args: Map<String, String>): String? {
    if (!args["values"].isNullOrEmpty()) return null
    return multipleVariablesSplitter(args["values_splitter"], "").takeIf { !TASKER_NAME_PATTERN.matches("%aaa${it}aaa") }
}

/** The name pattern Tasker checks a splitter against, so `-` splits a value off where OpenTasker would keep it in a name. */
private val TASKER_NAME_PATTERN = Regex("%+[A-Za-z0-9][A-Za-z0-9_]+[A-Za-z0-9]")

/**
 * Each entry of the names field: the name as written and the value it carries, if any, or null for
 * a blank entry. The value keeps its spaces, as in Tasker; only the name is trimmed. It also keeps a
 * later splitter, where Tasker stops at it and cuts a link like `?q=1` short.
 */
private fun multipleVariablesEntries(args: Map<String, String>): List<Pair<String, String?>?> {
    val names = args["names"].orEmpty()
    val inline = multipleVariablesInlineSplitter(args)
    return names.split(multipleVariablesSplitter(args["names_splitter"], names)).map { raw ->
        when {
            raw.isBlank() -> null
            inline != null && inline in raw -> raw.substringBefore(inline).trim() to raw.substringAfter(inline)
            else -> raw.trim() to null
        }
    }
}

/** True when a name in [args] carries its own value after [multipleVariablesInlineSplitter]. */
internal fun multipleVariablesNamesCarryValues(args: Map<String, String>): Boolean =
    multipleVariablesEntries(args).any { it?.second != null }

/**
 * True when every entry of the names field is a `%name` as written, with no value of its own. A bare
 * word is a name here too, but in `%api_token,abc` on one line it's more likely a value typed there.
 */
internal fun multipleVariablesNamesArePlain(args: Map<String, String>): Boolean =
    multipleVariablesEntries(args).all {
        it == null || (it.second == null && it.first.startsWith('%') && VariableNamePolicy.normalize(it.first) != null)
    }

/**
 * The variables [args] gives a value, by canonical name; a name past the last value is only unset.
 * A name built from others, like `%item%n`, isn't known until the step runs, so it's left out. Values
 * filled in from a variable, like `%line`, can split into any number, so then every name counts.
 */
internal fun multipleVariablesTargets(args: Map<String, String>): List<String> {
    val valuesText = args["values"].orEmpty()
    val valueCount = when {
        valuesText.isEmpty() -> 0
        VALUE_REFERENCE.containsMatchIn(valuesText) || "{{" in valuesText -> Int.MAX_VALUE
        else -> valuesText.split(multipleVariablesSplitter(args["values_splitter"], valuesText)).size
    }
    return multipleVariablesEntries(args).mapIndexedNotNull { index, entry ->
        entry?.takeIf { it.second != null || index < valueCount }?.first?.let(VariableNamePolicy::normalize)
    }.distinct()
}

private val VALUE_REFERENCE = Regex("%[A-Za-z]")

/** The splitter as written, or Tasker's default: a new line when [text] has one, else a comma. */
private fun multipleVariablesSplitter(written: String?, text: String): String =
    written?.takeIf(String::isNotEmpty) ?: if ('\n' in text) "\n" else ","
