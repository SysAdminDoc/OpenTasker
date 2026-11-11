package com.opentasker.core.actions

import com.opentasker.core.model.VariableNamePolicy

private const val MAX_PACKAGE_LENGTH = 255
private const val MAX_ACTION_LENGTH = 256
private const val MAX_CATEGORY_LENGTH = 256
private const val MAX_COMPONENT_LENGTH = 512
private const val MAX_URI_LENGTH = 4_096
private const val MAX_MIME_LENGTH = 127
private const val MAX_EXTRA_COUNT = 16
private const val MAX_EXTRA_KEY_LENGTH = 64
private const val MAX_EXTRA_VALUE_LENGTH = 512
private const val MAX_EXTRA_BYTES = 4_096

internal enum class IntentDispatchMode {
    ACTIVITY,
    BROADCAST,
    SERVICE,
}

internal enum class IntentDispatchFlag {
    ACTIVITY_NEW_TASK,
    ACTIVITY_CLEAR_TOP,
    ACTIVITY_SINGLE_TOP,
    ACTIVITY_CLEAR_TASK,
    GRANT_READ_URI,
    GRANT_WRITE_URI,
}

internal enum class IntentExtraType {
    STRING,
    INT,
    BOOL,
}

internal data class IntentDispatchExtra(
    val key: String,
    val type: IntentExtraType,
    val value: String,
)

internal data class IntentDispatchPlan(
    val mode: IntentDispatchMode,
    val packageName: String,
    val componentClassName: String? = null,
    val action: String? = null,
    val category: String? = null,
    val uri: String? = null,
    val mimeType: String? = null,
    val flags: Set<IntentDispatchFlag> = emptySet(),
    val extras: List<IntentDispatchExtra> = emptyList(),
    val resultVariable: String? = null,
)

internal sealed interface IntentDispatchParseResult {
    data class Valid(val plan: IntentDispatchPlan) : IntentDispatchParseResult
    data class Invalid(val message: String) : IntentDispatchParseResult
}

/** The extras field holds one key=type:value per line, so it expands as a [LineListArguments] field. */
internal object IntentExtrasLines {
    const val LINE_BREAK_FAILURE = "a variable in extras expanded to a line break, which would add an extra the step doesn't list"
}

/** Pure validation and bounded decoding for the intent action's primitive wire format. */
internal object IntentDispatchPolicy {
    private val packagePattern = Regex("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)*")
    private val componentPattern = Regex("[A-Za-z_$][A-Za-z0-9_$.]*")
    private val extraKeyPattern = Regex("[A-Za-z_][A-Za-z0-9_.-]*")
    private val allowedUriSchemes = setOf("http", "https", "content", "geo", "mailto", "tel", "package")
    private val allowedFlags = mapOf(
        "activity_new_task" to IntentDispatchFlag.ACTIVITY_NEW_TASK,
        "activity_clear_top" to IntentDispatchFlag.ACTIVITY_CLEAR_TOP,
        "activity_single_top" to IntentDispatchFlag.ACTIVITY_SINGLE_TOP,
        "activity_clear_task" to IntentDispatchFlag.ACTIVITY_CLEAR_TASK,
        "grant_read_uri" to IntentDispatchFlag.GRANT_READ_URI,
        "grant_write_uri" to IntentDispatchFlag.GRANT_WRITE_URI,
    )

    fun parse(args: Map<String, String>): IntentDispatchParseResult {
        val packageName = args["package"]?.trim().orEmpty()
        if (packageName.isBlank()) return invalid("missing package")
        if (packageName.length > MAX_PACKAGE_LENGTH || !packagePattern.matches(packageName)) {
            return invalid("invalid package name")
        }

        val mode = when (args["mode"]?.trim()?.lowercase().orEmpty().ifBlank { "activity" }) {
            "activity" -> IntentDispatchMode.ACTIVITY
            "broadcast" -> IntentDispatchMode.BROADCAST
            "service" -> IntentDispatchMode.SERVICE
            else -> return invalid("mode must be activity, broadcast, or service")
        }
        val component = parseComponent(args["component"], packageName)
            ?: if (args["component"].isNullOrBlank()) null else return invalid("invalid component class")
        val action = args["action"]?.trim()?.takeIf(String::isNotBlank)
        if (action != null && action.length > MAX_ACTION_LENGTH) return invalid("action exceeds $MAX_ACTION_LENGTH characters")
        val category = args["category"]?.trim()?.takeIf(String::isNotBlank)
        if (category != null && category.length > MAX_CATEGORY_LENGTH) return invalid("category exceeds $MAX_CATEGORY_LENGTH characters")
        val uri = parseUri(args["uri"])
        if (uri is UriResult.Invalid) return invalid(uri.message)
        val mime = parseMimeType(args["mime_type"])
        if (mime is MimeResult.Invalid) return invalid(mime.message)
        val flags = parseFlags(args["flags"])
        if (flags is FlagsResult.Invalid) return invalid(flags.message)
        val uriValue = (uri as UriResult.Valid).value
        val flagsValue = (flags as FlagsResult.Valid).value
        IntentUriGrantPolicy.violation(uriValue, flagsValue)?.let { return invalid(it) }
        val extras = parseExtras(args)
        if (extras is ExtrasResult.Invalid) return invalid(extras.message)
        // Output names reach the action unexpanded, so "%res" names res, as it does for every other
        // output. A bare "%" names nothing, and the name must be one the variable store keeps.
        val rawResultVariable = args["result_variable"]?.trim()?.takeIf(String::isNotEmpty)
        if (rawResultVariable != null && rawResultVariable.removePrefix("%").length > MAX_EXTRA_KEY_LENGTH) {
            return invalid("result variable exceeds $MAX_EXTRA_KEY_LENGTH characters")
        }
        val resultVariable = rawResultVariable?.let { VariableNamePolicy.normalize(it) ?: return invalid("invalid result variable") }
        if (resultVariable != null && mode != IntentDispatchMode.BROADCAST) {
            return invalid("result variable is supported only for broadcasts")
        }
        if (mode == IntentDispatchMode.SERVICE && component == null) {
            return invalid("service dispatch requires an explicit component")
        }
        if (mode == IntentDispatchMode.BROADCAST && component == null) {
            return invalid("broadcast dispatch requires an explicit component")
        }

        return IntentDispatchParseResult.Valid(
            IntentDispatchPlan(
                mode = mode,
                packageName = packageName,
                componentClassName = component,
                action = action,
                category = category,
                uri = uriValue,
                mimeType = (mime as MimeResult.Valid).value,
                flags = flagsValue,
                extras = (extras as ExtrasResult.Valid).value,
                resultVariable = resultVariable,
            ),
        )
    }

    private fun parseComponent(raw: String?, packageName: String): String? {
        val value = raw?.trim()?.takeIf(String::isNotBlank) ?: return null
        if (value.length > MAX_COMPONENT_LENGTH) return null
        val className = value.substringAfter('/', value)
            .let { if (it.startsWith('.')) packageName + it else it }
        if (!componentPattern.matches(className)) return null
        return className.takeIf { it == packageName || it.startsWith("$packageName.") }
    }

    private fun parseUri(raw: String?): UriResult {
        val value = raw?.trim()?.takeIf(String::isNotBlank) ?: return UriResult.Valid(null)
        if (value.length > MAX_URI_LENGTH || value.any(Char::isWhitespace)) {
            return UriResult.Invalid("uri is invalid or exceeds $MAX_URI_LENGTH characters")
        }
        val scheme = value.substringBefore(':', missingDelimiterValue = "").lowercase()
        if (scheme == "file") return UriResult.Invalid("file:// URIs are not allowed")
        if (scheme !in allowedUriSchemes) return UriResult.Invalid("uri scheme is not allowlisted")
        return UriResult.Valid(value)
    }

    private fun parseMimeType(raw: String?): MimeResult {
        val value = raw?.trim()?.takeIf(String::isNotBlank) ?: return MimeResult.Valid(null)
        val valid = value.length <= MAX_MIME_LENGTH &&
            Regex("[A-Za-z0-9!#$&^_.+*-]+/[A-Za-z0-9!#$&^_.+*-]+|\\*/[A-Za-z0-9!#$&^_.+*-]+|[A-Za-z0-9!#$&^_.+*-]+/\\*").matches(value)
        return if (valid) MimeResult.Valid(value) else MimeResult.Invalid("invalid MIME type")
    }

    private fun parseFlags(raw: String?): FlagsResult {
        val names = raw.orEmpty().split(',').map(String::trim).filter(String::isNotBlank)
        if (names.size > allowedFlags.size) return FlagsResult.Invalid("too many intent flags")
        val flags = mutableSetOf<IntentDispatchFlag>()
        names.forEach { name ->
            val flag = allowedFlags[name.lowercase()]
                ?: return FlagsResult.Invalid("intent flag is not allowlisted: $name")
            flags += flag
        }
        return FlagsResult.Valid(flags)
    }

    private fun parseExtras(args: Map<String, String>): ExtrasResult {
        val encoded = buildList {
            args["extras"].orEmpty().lineSequence().forEach { line ->
                if (line.trim().isNotEmpty()) add(line)
            }
            args.filterKeys { it.startsWith("extra.") }.forEach { (key, value) ->
                add("${key.removePrefix("extra.")}=$value")
            }
        }
        if (encoded.size > MAX_EXTRA_COUNT) return ExtrasResult.Invalid("too many extras")
        val seen = mutableSetOf<String>()
        var totalBytes = 0
        val extras = encoded.map { line ->
            val separator = line.indexOf('=')
            if (separator <= 0) return ExtrasResult.Invalid("extras must use key=type:value lines")
            val key = line.substring(0, separator).trim()
            if (key.length > MAX_EXTRA_KEY_LENGTH || !extraKeyPattern.matches(key) || !seen.add(key)) {
                return ExtrasResult.Invalid("invalid or duplicate extra key")
            }
            val encodedValue = line.substring(separator + 1).trim()
            val typeSeparator = encodedValue.indexOf(':')
            if (typeSeparator <= 0) return ExtrasResult.Invalid("extra $key must use string:, int:, or bool:")
            val type = when (encodedValue.substring(0, typeSeparator).lowercase()) {
                "string" -> IntentExtraType.STRING
                "int" -> IntentExtraType.INT
                "bool" -> IntentExtraType.BOOL
                else -> return ExtrasResult.Invalid("extra $key has an unsupported primitive type")
            }
            val value = encodedValue.substring(typeSeparator + 1)
            if (value.length > MAX_EXTRA_VALUE_LENGTH) return ExtrasResult.Invalid("extra $key exceeds $MAX_EXTRA_VALUE_LENGTH characters")
            when (type) {
                IntentExtraType.STRING -> Unit
                IntentExtraType.INT -> if (value.toIntOrNull() == null) return ExtrasResult.Invalid("extra $key is not a 32-bit integer")
                IntentExtraType.BOOL -> if (value.lowercase() !in setOf("true", "false")) return ExtrasResult.Invalid("extra $key is not boolean")
            }
            totalBytes += key.toByteArray().size + value.toByteArray().size
            if (totalBytes > MAX_EXTRA_BYTES) return ExtrasResult.Invalid("extras exceed $MAX_EXTRA_BYTES bytes")
            IntentDispatchExtra(key, type, value)
        }
        return ExtrasResult.Valid(extras)
    }

    private fun invalid(message: String) = IntentDispatchParseResult.Invalid(message)

    private sealed interface UriResult {
        data class Valid(val value: String?) : UriResult
        data class Invalid(val message: String) : UriResult
    }

    private sealed interface MimeResult {
        data class Valid(val value: String?) : MimeResult
        data class Invalid(val message: String) : MimeResult
    }

    private sealed interface FlagsResult {
        data class Valid(val value: Set<IntentDispatchFlag>) : FlagsResult
        data class Invalid(val message: String) : FlagsResult
    }

    private sealed interface ExtrasResult {
        data class Valid(val value: List<IntentDispatchExtra>) : ExtrasResult
        data class Invalid(val message: String) : ExtrasResult
    }
}

/**
 * Every URI in the configurable outbound-intent surface must carry an explicit permission
 * decision. This keeps the action fail-closed when Android 18 removes implicit URI grants.
 */
internal object IntentUriGrantPolicy {
    fun violation(uri: String?, flags: Set<IntentDispatchFlag>): String? {
        if (uri == null) return null
        if (flags.any { it == IntentDispatchFlag.GRANT_READ_URI || it == IntentDispatchFlag.GRANT_WRITE_URI }) {
            return null
        }
        return "URI-bearing intent requires explicit grant_read_uri or grant_write_uri"
    }
}
