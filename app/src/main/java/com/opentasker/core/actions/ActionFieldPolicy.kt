package com.opentasker.core.actions

import com.opentasker.core.apps.PackageNamePolicy
import java.util.LinkedHashMap

/** Shared rendering and validation contract for metadata-driven action fields. */
object ActionFieldPolicy {
    enum class Renderer { TEXT, NUMBER, DROPDOWN, CHECKBOX, MULTILINE, TASK, APP, FILE }

    enum class Error {
        REQUIRED,
        INVALID_NUMBER,
        BELOW_MINIMUM,
        ABOVE_MAXIMUM,
        INVALID_OPTION,
        INVALID_BOOLEAN,
        INVALID_TASK,
        INVALID_APP,
        INVALID_FILE,
        CONFLICTING_VALUE,
        BODY_NOT_ALLOWED,
        INVALID_DEFINITION,
        INVALID_TIME,
        TIME_VARIABLE_NOT_GLOBAL,
        INVALID_TIME_VARIABLE,
        INVALID_COMPONENT,
    }

    data class Issue(
        val error: Error,
        val limit: Double? = null,
    )

    fun rendererFor(field: ActionField): Renderer? = when (field.fieldType) {
        FieldType.TEXT -> Renderer.TEXT
        FieldType.MULTILINE -> Renderer.MULTILINE
        FieldType.CHECKBOX -> Renderer.CHECKBOX
        FieldType.TASK -> Renderer.TASK
        FieldType.APP -> Renderer.APP
        FieldType.NUMBER -> if (validNumberRule(field.numberRule)) Renderer.NUMBER else null
        FieldType.DROPDOWN -> if (validOptions(field.options)) Renderer.DROPDOWN else null
        FieldType.FILE -> if (field.fileRule != null) Renderer.FILE else null
    }

    fun validate(
        field: ActionField,
        rawValue: String,
        availableTaskIds: Set<Long> = emptySet(),
    ): Issue? {
        if (rawValue.isBlank()) return if (field.required) Issue(Error.REQUIRED) else null
        return when (field.fieldType) {
            FieldType.TEXT,
            FieldType.MULTILINE -> null

            FieldType.CHECKBOX -> if (
                rawValue.equals("true", ignoreCase = true) || rawValue.equals("false", ignoreCase = true)
            ) null else Issue(Error.INVALID_BOOLEAN)

            FieldType.NUMBER -> validateNumber(field.numberRule, rawValue)
            FieldType.DROPDOWN -> if (validOptions(field.options) && field.options.any { it.value == rawValue }) {
                null
            } else {
                Issue(if (validOptions(field.options)) Error.INVALID_OPTION else Error.INVALID_DEFINITION)
            }
            FieldType.TASK -> {
                val taskId = rawValue.toLongOrNull()
                if (taskId != null && taskId in availableTaskIds) null else Issue(Error.INVALID_TASK)
            }
            FieldType.APP -> if (PackageNamePolicy.isValid(rawValue.trim())) null else Issue(Error.INVALID_APP)
            FieldType.FILE -> when (field.fileRule?.scope) {
                ActionFileScope.OPENTASKER -> if (isValidOpenTaskerPath(rawValue)) null else Issue(Error.INVALID_FILE)
                ActionFileScope.DEVICE_OR_URI -> if (isValidDevicePathOrUri(rawValue)) null else Issue(Error.INVALID_FILE)
                null -> Issue(Error.INVALID_DEFINITION)
            }
        }
    }

    fun validateForm(
        metadata: ActionMetadata,
        values: Map<String, String>,
        availableTaskIds: Set<Long> = emptySet(),
    ): Map<String, Issue> {
        val issues = LinkedHashMap<String, Issue>()
        metadata.fields.forEach { field ->
            validate(field, values[field.key].orEmpty(), availableTaskIds)?.let { issues[field.key] = it }
        }
        if (metadata.id == "http.request") {
            val hasBody = values["body"].isPresent()
            val hasBodyFile = values["body_file"].isPresent()
            if (hasBody && hasBodyFile) {
                issues.putIfAbsent("body", Issue(Error.CONFLICTING_VALUE))
                issues.putIfAbsent("body_file", Issue(Error.CONFLICTING_VALUE))
            }
            val hasResponseVariable = values["response_var"].isPresent()
            val hasOutputFile = values["output_file"].isPresent()
            if (hasResponseVariable && hasOutputFile) {
                issues.putIfAbsent("response_var", Issue(Error.CONFLICTING_VALUE))
                issues.putIfAbsent("output_file", Issue(Error.CONFLICTING_VALUE))
            }
            val method = values["method"].orEmpty().ifBlank { "GET" }.uppercase()
            if (method == "GET" || method == "HEAD") {
                if (hasBody) issues.putIfAbsent("body", Issue(Error.BODY_NOT_ALLOWED))
                if (hasBodyFile) issues.putIfAbsent("body_file", Issue(Error.BODY_NOT_ALLOWED))
            }
            if (!hasOutputFile) {
                values["max_response_bytes"]?.trim()?.toLongOrNull()?.let { maximum ->
                    if (maximum > MAX_VARIABLE_RESPONSE_BYTES) {
                        issues["max_response_bytes"] = Issue(Error.ABOVE_MAXIMUM, MAX_VARIABLE_RESPONSE_BYTES.toDouble())
                    }
                }
            }
        }
        // For Each loops over an array or over written-out values, and needs one of them.
        if (metadata.id == "flow.foreach" && listOf("list", "in", "array", "items", "values").none { values[it].isPresent() }) {
            issues.putIfAbsent("list", Issue(Error.REQUIRED))
            issues.putIfAbsent("values", Issue(Error.REQUIRED))
        }
        return issues
    }

    private fun validateNumber(rule: ActionNumberRule?, rawValue: String): Issue? {
        if (!validNumberRule(rule)) return Issue(Error.INVALID_DEFINITION)
        requireNotNull(rule)
        val value = rawValue.trim()
        if (rule.allowedLiterals.any { it.equals(value, ignoreCase = true) }) return null
        if (VARIABLE_REFERENCE.matches(value) || TEMPLATE_REFERENCE.matches(value)) return null
        val parsed = when (rule.kind) {
            ActionNumberKind.INTEGER -> value.toLongOrNull()?.toDouble()
            ActionNumberKind.DECIMAL -> value.toDoubleOrNull()?.takeIf(Double::isFinite)
        } ?: return Issue(Error.INVALID_NUMBER)
        rule.minimum?.let { if (parsed < it) return Issue(Error.BELOW_MINIMUM, it) }
        rule.maximum?.let { if (parsed > it) return Issue(Error.ABOVE_MAXIMUM, it) }
        return null
    }

    private fun validOptions(options: List<ActionFieldOption>): Boolean =
        options.isNotEmpty() && options.all { it.value.isNotBlank() } &&
            options.map { it.value }.distinct().size == options.size

    private fun validNumberRule(rule: ActionNumberRule?): Boolean = rule != null &&
        rule.minimum?.isFinite() != false && rule.maximum?.isFinite() != false &&
        (rule.minimum == null || rule.maximum == null || rule.minimum <= rule.maximum) &&
        rule.allowedLiterals.none(String::isBlank)

    private fun isValidOpenTaskerPath(rawValue: String): Boolean {
        if (rawValue.isBlank() || rawValue.length > MAX_FILE_PATH_CHARS || rawValue.any { it.code < 0x20 }) return false
        var depth = 0
        rawValue.trimStart('/', '\\').replace('\\', '/').split('/').forEach { segment ->
            when (segment) {
                "", "." -> Unit
                ".." -> {
                    if (depth == 0) return false
                    depth--
                }
                else -> depth++
            }
        }
        return depth > 0
    }

    private fun isValidDevicePathOrUri(rawValue: String): Boolean {
        if (rawValue.isBlank() || rawValue.length > MAX_FILE_PATH_CHARS || rawValue.any { it.code < 0x20 }) return false
        if (rawValue.contains("://")) {
            val scheme = rawValue.substringBefore("://")
            return scheme.matches(URI_SCHEME)
        }
        return true
    }

    private val VARIABLE_REFERENCE = Regex("%[A-Za-z][A-Za-z0-9_-]*")
    private val TEMPLATE_REFERENCE = Regex("\\{\\{\\s*(?:%?(?:task|event|global|array)\\.)?%?[A-Za-z][A-Za-z0-9_-]*(?:\\s*\\|[^{}]+)?\\s*\\}\\}")
    private val URI_SCHEME = Regex("[A-Za-z][A-Za-z0-9+.-]*")
    private fun String?.isPresent(): Boolean = !this.isNullOrBlank()
    private const val MAX_VARIABLE_RESPONSE_BYTES = 1_048_576L
    private const val MAX_FILE_PATH_CHARS = 512
}

/**
 * Applies edits to known fields without reconstructing the argument map. Unknown keys and their
 * values survive byte-for-byte, which keeps newer/imported action arguments forward compatible.
 */
fun mergeActionArguments(
    existing: Map<String, String>,
    fields: List<ActionField>,
    editedValues: Map<String, String>,
): Map<String, String> {
    val merged = LinkedHashMap(existing)
    fields.forEach { field ->
        val value = editedValues[field.key].orEmpty()
        if (value.isBlank()) {
            merged.remove(field.key)
        } else {
            merged[field.key] = if (field.fieldType == FieldType.APP) value.trim() else value
        }
    }
    return merged
}
