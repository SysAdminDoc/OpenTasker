package com.opentasker.core.diagnostics

import com.opentasker.core.actions.ActionArgumentSensitivity

/**
 * The single serialization-boundary policy for user-controlled diagnostics and exports.
 *
 * Action metadata is authoritative for known fields, while unknown fields continue to fail
 * closed through [ActionArgumentSensitivity]. Secret names and values are supplied by callers
 * that have provenance for them; this keeps a secret-derived template or an echoed runtime value
 * safe even when the containing action field is otherwise ordinary.
 */
object ExportRedactionPolicy {
    const val REDACTED = "[REDACTED]"
    private const val REDACTED_CARD = "[REDACTED-CARD]"
    const val SENSITIVE_ACTION_WARNING =
        "Sensitive action value(s) were omitted and must be re-entered after import."

    data class Context(
        val secretNames: Set<String> = emptySet(),
        val secretValues: Set<String> = emptySet(),
        val secretDerivedFields: Set<String> = emptySet(),
    )

    data class RedactedField(
        val value: String,
        val redacted: Boolean,
    )

    data class SanitizedActionArguments(
        val args: Map<String, String>,
        val redactedFields: Set<String>,
    )

    fun sanitizeActionArguments(
        actionType: String,
        args: Map<String, String>,
        context: Context = Context(),
    ): SanitizedActionArguments {
        val sanitized = linkedMapOf<String, String>()
        val redactedFields = linkedSetOf<String>()
        args.forEach { (name, value) ->
            val field = redactField(actionType, name, value, args, context)
            sanitized[name] = field.value
            if (field.redacted) redactedFields += name
        }
        return SanitizedActionArguments(sanitized, redactedFields)
    }

    fun redactField(
        actionType: String?,
        fieldName: String,
        value: String,
        args: Map<String, String> = emptyMap(),
        context: Context = Context(),
        placeholder: String = REDACTED,
    ): RedactedField {
        if (
            fieldName in context.secretDerivedFields ||
            ActionArgumentSensitivity.isSensitive(actionType, fieldName, args) ||
            referencesSecret(value, context.secretNames)
        ) {
            return RedactedField(placeholder, redacted = true)
        }

        val redacted = redactText(value, context.secretValues, placeholder)
        return RedactedField(redacted, redacted != value)
    }

    /** Redacts known secret values and structured credential-bearing text without throwing. */
    fun redactText(
        text: String,
        secretValues: Collection<String> = emptySet(),
        placeholder: String = REDACTED,
    ): String {
        var redacted = text
        secretValues
            .asSequence()
            .filter { it.isNotEmpty() }
            .distinct()
            .sortedByDescending(String::length)
            // ignoreCase, because a secret retyped with different capitalisation is the same
            // credential. Kotlin's two-argument replace defaults to case-sensitive, so a stored
            // `sk-live-abc123` left a hand-typed `sk-Live-ABC123` in the clear in every export.
            // This can over-redact when a secret's value is an ordinary word, which is the right
            // way round to be wrong: a redacted word is recoverable, a leaked credential is not.
            .forEach { value -> redacted = redacted.replace(value, placeholder, ignoreCase = true) }

        redacted = URL_PATTERN.replace(redacted) { match -> redactUrl(match.value, placeholder) }
        redacted = AUTHORIZATION_PATTERN.replace(redacted) { match ->
            "${match.groupValues[1]}$placeholder"
        }
        redacted = BEARER_PATTERN.replace(redacted) { match ->
            "${match.groupValues[1]}$placeholder"
        }
        redacted = KEYED_VALUE_PATTERN.replace(redacted) { match ->
            "${match.groupValues[1]}=$placeholder"
        }
        return CARD_PATTERN.replace(redacted, REDACTED_CARD)
    }

    fun referencesSecret(value: String, secretNames: Set<String>): Boolean {
        if (secretNames.isEmpty()) return false
        val normalized = secretNames.mapTo(hashSetOf<String>()) { normalizeName(it) }
        return TEMPLATE_REFERENCE_PATTERN.findAll(value).any { match ->
            normalizeName(match.groupValues[1]) in normalized
        }
    }

    private fun redactUrl(raw: String, placeholder: String): String {
        val fragmentStart = raw.indexOf('#')
        val withoutFragment = if (fragmentStart >= 0) raw.substring(0, fragmentStart) else raw
        val queryStart = withoutFragment.indexOf('?')
        val withoutQuery = if (queryStart >= 0) withoutFragment.substring(0, queryStart) else withoutFragment
        val authorityStart = withoutQuery.indexOf("://") + 3
        if (authorityStart <= 2 || authorityStart >= withoutQuery.length) return placeholder

        val pathStart = withoutQuery.indexOf('/', authorityStart).takeIf { it >= 0 } ?: withoutQuery.length
        val authority = withoutQuery.substring(authorityStart, pathStart)
        val sanitizedAuthority = authority.substringAfterLast('@', authority).let { host ->
            if (authority.contains('@')) "$placeholder@$host" else host
        }
        val prefix = withoutQuery.substring(0, authorityStart)
        val path = withoutQuery.substring(pathStart)
        return buildString {
            append(prefix)
            append(sanitizedAuthority)
            append(path)
            if (queryStart >= 0 || fragmentStart >= 0) append("?").append(placeholder)
        }
    }

    private fun normalizeName(value: String): String = value
        .substringAfterLast('.')
        .removePrefix("%")
        .trim()
        .lowercase()

    private val TEMPLATE_REFERENCE_PATTERN = Regex(
        """(?i)(?:\{\{\s*(?:global\.|task\.|event\.|array\.)?|%)\s*([A-Za-z][A-Za-z0-9_-]*)""",
    )
    private val URL_PATTERN = Regex("""(?i)\b(?:https?|ftp)://[^\s<>"']+""")
    private val AUTHORIZATION_PATTERN = Regex("""(?i)(\bauthorization\s*:\s*)[^\r\n]+""")
    private val BEARER_PATTERN = Regex("""(?i)(\bbearer\s+)[A-Za-z0-9._~+/=-]+""")
    private val KEYED_VALUE_PATTERN = Regex(
        """(?i)(\b(?:password|secret|token|key|auth))\s*[:=]\s*[^\s,;]+""",
    )
    private val CARD_PATTERN = Regex("""\b\d{4}[- ]?\d{4}[- ]?\d{4}[- ]?\d{4}\b""")
}
