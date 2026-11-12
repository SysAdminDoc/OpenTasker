package com.opentasker.core.actions

/**
 * Single source of truth for which action arguments are sensitive and how an action's stored
 * arguments are rendered for display.
 *
 * Runtime traces already redact secret-derived values, but the task list, flow graph, and
 * import/share previews render the *stored* arguments, so an HTTP `authorization` header or a
 * request body typed by the user could otherwise appear on screen, in a screenshot, and in
 * accessibility semantics. Every one of those surfaces resolves through this object.
 *
 * Resolution order for a single argument:
 *  1. explicit [ActionField.sensitive] on the action's registered metadata (true or false), then
 *  2. the shared argument-name heuristic in [SENSITIVE_NAME_TOKENS].
 *
 * Unknown action types and unknown argument keys therefore fall back to the heuristic and are
 * masked rather than printed — new or forward-compatible sensitive keys fail closed.
 */
object ActionArgumentSensitivity {

    const val REDACTED = "<redacted>"

    const val DEFAULT_MAX_VALUE_LENGTH = 80
    const val DEFAULT_SUMMARY_LIMIT = 4

    /**
     * Argument-name substrings treated as sensitive for any action, registered or not. Kept
     * deliberately broad: over-masking a structural field is recoverable, printing a credential
     * is not.
     */
    val SENSITIVE_NAME_TOKENS: List<String> = listOf(
        "authorization",
        "body",
        "cookie",
        "credential",
        "headers",
        "key",
        "passphrase",
        "password",
        "query",
        "secret",
        "token",
    )

    /**
     * Actions whose value argument only becomes sensitive because of what a sibling argument
     * names. `var.set name=api_token value=...` stores a credential under an innocuous key.
     */
    private val NAME_KEYED_VALUE_ARGS: Map<String, Pair<String, String>> = mapOf(
        "var.set" to ("name" to "value"),
        "var.set_multiple" to ("names" to "values"),
    )

    /** True when [argName] itself reads as a credential-bearing key. */
    fun isSensitiveArgumentName(argName: String): Boolean =
        SENSITIVE_NAME_TOKENS.any { token -> argName.contains(token, ignoreCase = true) }

    /**
     * True when [argName] must be masked for [actionType]. Pass a null [actionType] for
     * non-action argument maps (context configs, plugin bundles) to use the heuristic alone.
     */
    fun isSensitive(actionType: String?, argName: String, args: Map<String, String> = emptyMap()): Boolean {
        declaredSensitivity(actionType, argName)?.let { return it }
        if (isSensitiveArgumentName(argName)) return true
        if (actionType == "var.set_multiple" && argName.equals("names", ignoreCase = true)) {
            // A name can carry its own value, a typed value can read as a bare name, and a failed step
            // names an entry that isn't a name as written, so only a list of plain %names stays
            // readable once it mentions a secret.
            return isSensitiveArgumentName(args["names"].orEmpty()) && !multipleVariablesNamesArePlain(args)
        }
        val keyed = actionType?.let { NAME_KEYED_VALUE_ARGS[it] } ?: return false
        val (nameArg, valueArg) = keyed
        if (!argName.equals(valueArg, ignoreCase = true)) return false
        val referencedName = args[nameArg].orEmpty()
        return referencedName.isNotBlank() && isSensitiveArgumentName(referencedName)
    }

    /**
     * True when the value [actionType] wrote to [variableName] stays hidden in a run's change list,
     * the way the editor hides the value typed for it: `%api_token` set by `var.set`, `api_key`
     * written into `%config` by a `config.api_key` path, a `%api_token` among several names, the
     * global a Persist copies `%api_token` into, or `%api_token` named by any other step that writes
     * it, like Variable Convert, numbered pieces like the `%api_token1` Variable Split makes included.
     * An output the step doesn't name, like a response's headers, shows.
     */
    fun hidesWrittenValue(actionType: String?, args: Map<String, String>, variableName: String): Boolean {
        if (actionType == "var.persist") return isSensitiveArgumentName(variableName) || isSensitiveArgumentName(args["name"].orEmpty())
        val base = variableName.trimEnd(Char::isDigit)
        val (nameArg, _) = actionType?.let { NAME_KEYED_VALUE_ARGS[it] }
            ?: return isSensitiveArgumentName(variableName) &&
                args.values.any { it.namesVariable(variableName) || it.namesVariable(base) }
        return isSensitiveArgumentName(variableName) ||
            (actionType == "var.set" && isSensitiveArgumentName(args[nameArg].orEmpty()))
    }

    /** True when this is [name] as a bare word, or holds `%name` with no more of a name after it. */
    private fun String.namesVariable(name: String): Boolean {
        if (trim() == name) return true
        val reference = "%$name"
        var at = indexOf(reference)
        while (at != -1) {
            val next = getOrNull(at + reference.length)
            if (next == null || !(next.isLetterOrDigit() || next == '_' || next == '-')) return true
            at = indexOf(reference, at + 1)
        }
        return false
    }

    /** The explicit metadata declaration for [argName], or null when the field is not declared. */
    fun declaredSensitivity(actionType: String?, argName: String): Boolean? = actionType
        ?.let(ActionMetadataRegistry::get)
        ?.fields
        ?.firstOrNull { it.key.equals(argName, ignoreCase = true) }
        ?.sensitive

    /** Masks or shortens a single stored argument value for display. */
    fun maskValue(
        actionType: String?,
        argName: String,
        value: String,
        args: Map<String, String> = emptyMap(),
        maxLength: Int = DEFAULT_MAX_VALUE_LENGTH,
    ): String {
        if (isSensitive(actionType, argName, args)) return REDACTED
        return value.collapseWhitespace().ellipsize(maxLength)
    }

    /**
     * Deterministic, redacted, single-line summary of [args] suitable for list rows, flow nodes,
     * and previews. Returns an empty string when there is nothing to show so callers can fall
     * back to their own placeholder copy. [structuralKeys] names fields the caller knows are
     * structure rather than data, shown as written even when the name heuristic would mask them.
     */
    fun summarize(
        actionType: String?,
        args: Map<String, String>,
        limit: Int = DEFAULT_SUMMARY_LIMIT,
        maxValueLength: Int = DEFAULT_MAX_VALUE_LENGTH,
        structuralKeys: Set<String> = emptySet(),
    ): String {
        if (args.isEmpty()) return ""
        val ordered = args.entries.sortedBy { it.key }
        val visible = ordered.take(limit).joinToString(", ") { (key, value) ->
            val shown = if (key in structuralKeys) {
                value.collapseWhitespace().ellipsize(maxValueLength)
            } else {
                maskValue(actionType, key, value, args, maxValueLength)
            }
            "$key=$shown"
        }
        val hidden = ordered.size - limit
        return if (hidden > 0) "$visible, +$hidden more" else visible
    }

    private fun String.collapseWhitespace(): String = replace(WHITESPACE, " ").trim()

    private fun String.ellipsize(maxLength: Int): String =
        if (length <= maxLength) this else take(maxLength) + "..."

    private val WHITESPACE = Regex("""\s+""")
}
