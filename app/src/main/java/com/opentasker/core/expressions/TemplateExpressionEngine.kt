package com.opentasker.core.expressions

import com.opentasker.core.data.JsonNesting
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.floor
import kotlin.math.roundToLong

data class TemplateScope(
    val global: Map<String, String> = emptyMap(),
    val task: Map<String, String> = emptyMap(),
    val event: Map<String, String> = emptyMap(),
    val arrays: Map<String, List<String>> = emptyMap(),
    val sensitiveGlobal: Set<String> = emptySet(),
    val sensitiveTask: Set<String> = emptySet(),
    val sensitiveArrays: Set<String> = emptySet(),
    val sensitiveEvent: Set<String> = emptySet(),
    /** Read last, and in place of an empty stored value: Tasker's built-ins like `TIMES`. */
    val builtIns: (String) -> String? = NoBuiltIns,
)

private val NoBuiltIns: (String) -> String? = { null }

data class TemplateExpressionLimits(
    val maxTemplateLength: Int = 20_000,
    val maxExpressionLength: Int = 512,
    val maxExpansions: Int = 128,
    val maxFunctionChain: Int = 8,
    val maxJsonDepth: Int = 16,
    val maxJsonNesting: Int = JsonNesting.MAX_DATA_DEPTH,
    val maxResolvedValueLength: Int = 4_096,
    val maxOutputLength: Int = 20_000,
)

data class TemplateExpansionResult(
    val value: String,
    val traces: List<TemplateExpansionTrace>,
    val warnings: List<String>,
)

data class TemplateExpansionTrace(
    val rawExpression: String,
    val expression: String,
    val value: String,
    val source: TemplateValueSource,
    val path: String,
    val functions: List<String> = emptyList(),
    val warning: String? = null,
    val isSecretDerived: Boolean = false,
)

enum class TemplateValueSource {
    TASK,
    EVENT,
    GLOBAL,
    ARRAY,
    LITERAL,
    DEFAULT,
    MISSING,
}

/**
 * Bounded template evaluator for user-authored action arguments.
 *
 * Supported syntax is intentionally small:
 * - `{{ name }}` resolves task, event, then global variables.
 * - `{{ task.name }}`, `{{ event.name }}`, and `{{ global.name }}` pin a scope.
 * - `{{ payload.user.name }}` reads a dot path from a JSON object variable.
 * - `{{ items[0] }}` and `{{ items[#] }}` read arrays supplied by the caller.
 * - Pipe functions include `default`, `upper`, `lower`, `trim`, `add`, `sub`,
 *   `mul`, `div`, `round`, `floor`, `count`, `join`, and `date`.
 */
/**
 * A `{{` that arrives inside a variable's value is data. Legacy `%var` expansion runs first, so
 * without this a text message saying `{{ API_TOKEN }}`, forwarded through `%sms_body`, would be
 * read as an expression and the secret sent along with it. The same goes for a web response or
 * a broadcast extra.
 */
internal object InertTemplateText {
    private val MARK = "${Char(0xE000)}${Char(0xE001)}"

    fun hide(value: String): String = value.replace("{{", MARK)

    fun restore(text: String): String = text.replace(MARK, "{{")
}

class TemplateExpressionEngine(
    private val limits: TemplateExpressionLimits = TemplateExpressionLimits(),
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = false
    }

    fun expand(template: String, scope: TemplateScope): TemplateExpansionResult {
        if (template.length > limits.maxTemplateLength) {
            return TemplateExpansionResult(
                value = template,
                traces = emptyList(),
                warnings = listOf("Template length exceeds ${limits.maxTemplateLength} characters."),
            )
        }

        val out = StringBuilder(template.length)
        val traces = mutableListOf<TemplateExpansionTrace>()
        val warnings = mutableListOf<String>()
        var cursor = 0
        var expansionCount = 0

        while (cursor < template.length) {
            val open = template.indexOf("{{", startIndex = cursor)
            if (open == -1) {
                appendStatic(out, template.substring(cursor), warnings)
                break
            }

            appendStatic(out, template.substring(cursor, open), warnings)

            val close = template.indexOf("}}", startIndex = open + 2)
            if (close == -1) {
                appendStatic(out, template.substring(open), warnings)
                warnings += "Unclosed template expression at character $open."
                break
            }

            val rawToken = template.substring(open, close + 2)
            val expression = template.substring(open + 2, close).trim()
            expansionCount += 1
            if (expansionCount > limits.maxExpansions) {
                appendExpression(out, rawToken, rawToken, warnings)
                warnings += "Expansion limit of ${limits.maxExpansions} expressions reached."
                cursor = close + 2
                continue
            }

            val evaluated = evaluateExpression(rawToken, expression, scope)
            warnings += evaluated.warnings
            traces += evaluated.trace
            appendExpression(out, evaluated.value, rawToken, warnings)
            cursor = close + 2
        }

        return TemplateExpansionResult(
            value = out.toString(),
            traces = traces,
            warnings = warnings.distinct(),
        )
    }

    private fun evaluateExpression(
        rawToken: String,
        expression: String,
        scope: TemplateScope,
    ): EvaluatedExpression {
        if (expression.isBlank()) {
            return preserve(rawToken, expression, "Empty template expression.")
        }
        if (expression.length > limits.maxExpressionLength) {
            return preserve(rawToken, expression, "Expression length exceeds ${limits.maxExpressionLength} characters.")
        }

        val parts = splitPipeline(expression)
        if (parts.isEmpty()) {
            return preserve(rawToken, expression, "Empty template expression.")
        }
        if (parts.size - 1 > limits.maxFunctionChain) {
            return preserve(rawToken, expression, "Function chain exceeds ${limits.maxFunctionChain} steps.")
        }

        val functionNames = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        var current = resolveReference(parts.first(), scope)
        for (functionText in parts.drop(1)) {
            val function = parseFunction(functionText)
            if (function.name.isBlank()) {
                return preserve(rawToken, expression, "Blank template function.")
            }
            functionNames += function.name
            val applied = applyFunction(current, function)
            if (applied.preserveReason != null) {
                return preserve(rawToken, expression, applied.preserveReason, functionNames)
            }
            warnings += applied.warnings
            current = applied.value
        }

        if (current.missing && functionNames.none { it == "default" }) {
            warnings += "Missing template value for '${parts.first()}'."
        }
        if (current.value.length > limits.maxResolvedValueLength) {
            return preserve(
                rawToken = rawToken,
                expression = expression,
                warning = "Resolved value exceeds ${limits.maxResolvedValueLength} characters.",
                functions = functionNames,
            )
        }

        return EvaluatedExpression(
            value = current.value,
            trace = TemplateExpansionTrace(
                rawExpression = rawToken,
                expression = expression,
                value = current.value,
                source = current.source,
                path = current.path,
                functions = functionNames,
                warning = warnings.firstOrNull(),
                isSecretDerived = current.isSecretDerived,
            ),
            warnings = warnings,
        )
    }

    private fun resolveReference(referenceText: String, scope: TemplateScope): ResolvedValue {
        val reference = referenceText.trim()
        if (reference.isEmpty()) {
            return ResolvedValue("", TemplateValueSource.MISSING, reference, missing = true)
        }
        parseLiteral(reference)?.let { return it }

        val normalized = reference.removePrefix("%")
        return when {
            normalized.startsWith("task.") -> resolveInMap(
                normalized.removePrefix("task."),
                scope.task,
                TemplateValueSource.TASK,
                scope.sensitiveTask,
            )
            normalized.startsWith("event.") -> resolveInMap(
                normalized.removePrefix("event."),
                scope.event,
                TemplateValueSource.EVENT,
                scope.sensitiveEvent,
            )
            normalized.startsWith("global.") -> normalized.removePrefix("global.").let { name ->
                resolveInMap(name, scope.global, TemplateValueSource.GLOBAL, scope.sensitiveGlobal)
                    .orBuiltIn(name, scope)
            }
            normalized.startsWith("array.") -> resolveArray(
                normalized.removePrefix("array."),
                scope.arrays,
                scope.sensitiveArrays,
            )
            else -> (
                resolveInMap(normalized, scope.task, TemplateValueSource.TASK, scope.sensitiveTask).takeIfResolved()
                    ?: resolveInMap(normalized, scope.event, TemplateValueSource.EVENT, scope.sensitiveEvent).takeIfResolved()
                    ?: resolveInMap(normalized, scope.global, TemplateValueSource.GLOBAL, scope.sensitiveGlobal).takeIfResolved()
                    ?: resolveArray(normalized, scope.arrays, scope.sensitiveArrays).takeIfResolved()
                    ?: ResolvedValue("", TemplateValueSource.MISSING, normalized, missing = true)
                ).orBuiltIn(normalized, scope)
        }
    }

    /**
     * A Tasker built-in fills in when nothing is stored under its name, or only an empty value is
     * (a Variable Set to nothing).
     */
    private fun ResolvedValue.orBuiltIn(name: String, scope: TemplateScope): ResolvedValue {
        if (value.isNotEmpty()) return this
        val builtIn = scope.builtIns(name) ?: return this
        return ResolvedValue(builtIn, TemplateValueSource.GLOBAL, name)
    }

    private fun parseLiteral(reference: String): ResolvedValue? {
        val quoted = unquote(reference)
        if (quoted != reference) {
            return ResolvedValue(quoted, TemplateValueSource.LITERAL, "literal")
        }
        if (reference.toDoubleOrNull() != null || reference == "true" || reference == "false") {
            return ResolvedValue(reference, TemplateValueSource.LITERAL, "literal")
        }
        return null
    }

    private fun resolveInMap(
        referenceText: String,
        values: Map<String, String>,
        source: TemplateValueSource,
        sensitiveNames: Set<String>,
    ): ResolvedValue {
        val exact = values[referenceText]
        if (exact != null) return ResolvedValue(
            exact,
            source,
            referenceText,
            isSecretDerived = referenceText in sensitiveNames,
        )

        val reference = parseReference(referenceText) ?: return ResolvedValue(
            value = "",
            source = TemplateValueSource.MISSING,
            path = referenceText,
            missing = true,
        )
        val base = values[reference.base] ?: return ResolvedValue(
            value = "",
            source = TemplateValueSource.MISSING,
            path = referenceText,
            missing = true,
        )
        if (reference.selectors.isEmpty()) {
            return ResolvedValue(
                base,
                source,
                reference.base,
                isSecretDerived = reference.base in sensitiveNames,
            )
        }
        return resolveJsonSelectors(
            base,
            reference.selectors,
            source,
            referenceText,
            isSecretDerived = reference.base in sensitiveNames,
        )
    }

    private fun resolveArray(
        referenceText: String,
        arrays: Map<String, List<String>>,
        sensitiveNames: Set<String>,
    ): ResolvedValue {
        val reference = parseReference(referenceText) ?: return ResolvedValue(
            value = "",
            source = TemplateValueSource.MISSING,
            path = referenceText,
            missing = true,
        )
        val values = arrays[reference.base] ?: return ResolvedValue(
            value = "",
            source = TemplateValueSource.MISSING,
            path = referenceText,
            missing = true,
        )
        if (reference.selectors.isEmpty()) {
            return ResolvedValue(
                value = values.joinToString(","),
                source = TemplateValueSource.ARRAY,
                path = reference.base,
                array = values,
                isSecretDerived = reference.base in sensitiveNames,
            )
        }

        val first = reference.selectors.first()
        val item = when (first) {
            Selector.Count -> values.size.toString()
            is Selector.Index -> values.getOrNull(first.index) ?: ""
            is Selector.Property -> return ResolvedValue(
                value = "",
                source = TemplateValueSource.MISSING,
                path = referenceText,
                missing = true,
            )
        }
        if (first is Selector.Count || reference.selectors.size == 1) {
            return ResolvedValue(
                item,
                TemplateValueSource.ARRAY,
                referenceText,
                array = values,
                isSecretDerived = reference.base in sensitiveNames,
            )
        }
        return resolveJsonSelectors(
            jsonText = item,
            selectors = reference.selectors.drop(1),
            source = TemplateValueSource.ARRAY,
            path = referenceText,
            isSecretDerived = reference.base in sensitiveNames,
        )
    }

    private fun resolveJsonSelectors(
        jsonText: String,
        selectors: List<Selector>,
        source: TemplateValueSource,
        path: String,
        isSecretDerived: Boolean,
    ): ResolvedValue {
        if (selectors.size > limits.maxJsonDepth || !JsonNesting.within(jsonText, limits.maxJsonNesting)) {
            return ResolvedValue("", TemplateValueSource.MISSING, path, missing = true)
        }

        val root = try {
            json.parseToJsonElement(jsonText)
        } catch (_: Exception) {
            return ResolvedValue("", TemplateValueSource.MISSING, path, missing = true)
        }

        var current: JsonElement = root
        for (selector in selectors) {
            current = when (selector) {
                Selector.Count -> when (current) {
                    is JsonArray -> JsonPrimitive(current.size)
                    is JsonObject -> JsonPrimitive(current.size)
                    else -> return ResolvedValue("", TemplateValueSource.MISSING, path, missing = true)
                }
                is Selector.Index -> {
                    val array = current as? JsonArray ?: return ResolvedValue(
                        "",
                        TemplateValueSource.MISSING,
                        path,
                        missing = true,
                    )
                    array.getOrNull(selector.index) ?: return ResolvedValue(
                        "",
                        TemplateValueSource.MISSING,
                        path,
                        missing = true,
                    )
                }
                is Selector.Property -> {
                    val obj = current as? JsonObject ?: return ResolvedValue(
                        "",
                        TemplateValueSource.MISSING,
                        path,
                        missing = true,
                    )
                    obj[selector.name] ?: return ResolvedValue(
                        "",
                        TemplateValueSource.MISSING,
                        path,
                        missing = true,
                    )
                }
            }
        }

        return ResolvedValue(
            jsonElementToString(current),
            source,
            path,
            isSecretDerived = isSecretDerived,
        )
    }

    private fun applyFunction(current: ResolvedValue, function: TemplateFunction): FunctionApplication {
        val name = function.name.lowercase()
        val arg = function.argument
        val value = current.value

        return when (name) {
            "default" -> {
                val fallback = arg ?: return FunctionApplication(
                    current,
                    warnings = listOf("default requires an argument."),
                )
                if (current.missing || value.isEmpty()) {
                    FunctionApplication(
                        current.copy(
                            value = fallback,
                            source = TemplateValueSource.DEFAULT,
                            missing = false,
                            path = "default",
                        ),
                    )
                } else {
                    FunctionApplication(current)
                }
            }
            "upper" -> FunctionApplication(current.copy(value = value.uppercase(), missing = false))
            "lower" -> FunctionApplication(current.copy(value = value.lowercase(), missing = false))
            "trim" -> FunctionApplication(current.copy(value = value.trim(), missing = false))
            // Form encoding writes a space as +, which a phone number's own + would be confused
            // with once decoded, so spaces become %20 and the result is safe anywhere in a URL.
            "urlencode" -> FunctionApplication(
                current.copy(value = URLEncoder.encode(value, "UTF-8").replace("+", "%20"), missing = false),
            )
            // roundToLong throws on NaN, so a value that isn't finite passes through and comes
            // out empty, the way the other numeric functions leave one.
            "round" -> applyUnaryNumber(current) { if (it.isFinite()) it.roundToLong().toDouble() else it }
            "floor" -> applyUnaryNumber(current) { floor(it) }
            "add" -> applyBinaryNumber(current, arg, name) { left, right -> left + right }
            "sub" -> applyBinaryNumber(current, arg, name) { left, right -> left - right }
            "mul" -> applyBinaryNumber(current, arg, name) { left, right -> left * right }
            "div" -> {
                val divisor = arg?.toDoubleOrNull()
                if (divisor == null) {
                    FunctionApplication(current, warnings = listOf("div requires a numeric argument."))
                } else if (divisor == 0.0) {
                    FunctionApplication(current, warnings = listOf("div cannot use zero as the divisor."))
                } else {
                    applyBinaryNumber(current, arg, name) { left, right -> left / right }
                }
            }
            "count" -> FunctionApplication(current.copy(value = (current.array?.size ?: value.length).toString()))
            "join" -> {
                val joined = current.array?.joinToString(arg ?: ",")
                if (joined == null) {
                    FunctionApplication(current, warnings = listOf("join requires an array value."))
                } else {
                    FunctionApplication(current.copy(value = joined, missing = false))
                }
            }
            "date" -> {
                val pattern = arg ?: "yyyy-MM-dd HH:mm:ss"
                if (pattern.length > MAX_DATE_PATTERN_LENGTH) {
                    FunctionApplication(current, warnings = listOf("date pattern exceeds $MAX_DATE_PATTERN_LENGTH characters."))
                } else {
                    try {
                        val millis = if (current.missing || value.isEmpty()) {
                            System.currentTimeMillis()
                        } else {
                            value.toLongOrNull() ?: return FunctionApplication(
                                current,
                                warnings = listOf("date requires a numeric epoch-millis value or empty input for current time."),
                            )
                        }
                        val formatted = SimpleDateFormat(pattern, Locale.ROOT).format(Date(millis))
                        FunctionApplication(current.copy(value = formatted, missing = false))
                    } catch (_: IllegalArgumentException) {
                        FunctionApplication(current, warnings = listOf("Invalid date pattern: $pattern"))
                    }
                }
            }
            "match", "matches", "regex", "replace" -> FunctionApplication(
                current,
                preserveReason = "Regex template function '$name' is intentionally unsupported.",
            )
            else -> FunctionApplication(current, preserveReason = "Unknown template function '$name'.")
        }
    }

    private fun applyUnaryNumber(
        current: ResolvedValue,
        transform: (Double) -> Double,
    ): FunctionApplication {
        val number = current.value.toDoubleOrNull() ?: return FunctionApplication(
            current,
            warnings = listOf("Numeric function requires a numeric value."),
        )
        return FunctionApplication(current.copy(value = formatNumber(transform(number)), missing = false))
    }

    private fun applyBinaryNumber(
        current: ResolvedValue,
        arg: String?,
        functionName: String,
        transform: (Double, Double) -> Double,
    ): FunctionApplication {
        val left = current.value.toDoubleOrNull()
        val right = arg?.toDoubleOrNull()
        if (left == null || right == null) {
            return FunctionApplication(
                current,
                warnings = listOf("$functionName requires numeric input and argument."),
            )
        }
        return FunctionApplication(current.copy(value = formatNumber(transform(left, right)), missing = false))
    }

    private fun formatNumber(value: Double): String {
        if (!value.isFinite()) return ""
        return if (floor(value) == value) value.toLong().toString() else value.toString()
    }

    private fun splitPipeline(expression: String): List<String> {
        val parts = mutableListOf<String>()
        val current = StringBuilder()
        var quote: Char? = null
        var escaped = false

        for (char in expression) {
            if (escaped) {
                current.append(char)
                escaped = false
                continue
            }
            if (char == '\\') {
                escaped = true
                current.append(char)
                continue
            }
            if (quote != null) {
                current.append(char)
                if (char == quote) quote = null
                continue
            }
            when (char) {
                '\'', '"' -> {
                    quote = char
                    current.append(char)
                }
                '|' -> {
                    parts += current.toString().trim()
                    current.clear()
                }
                else -> current.append(char)
            }
        }
        parts += current.toString().trim()
        return parts.filter { it.isNotEmpty() }
    }

    private fun parseFunction(functionText: String): TemplateFunction {
        val splitAt = functionText.indexOf(':')
        if (splitAt == -1) {
            return TemplateFunction(functionText.trim(), null)
        }
        return TemplateFunction(
            name = functionText.substring(0, splitAt).trim(),
            argument = unquote(functionText.substring(splitAt + 1).trim()),
        )
    }

    private fun parseReference(text: String): TemplateReference? {
        if (text.isBlank()) return null
        var cursor = 0
        val baseStart = cursor
        while (cursor < text.length && isReferenceChar(text[cursor])) cursor++
        if (cursor == baseStart) return null
        val base = text.substring(baseStart, cursor)
        val selectors = mutableListOf<Selector>()

        while (cursor < text.length) {
            when (text[cursor]) {
                '.' -> {
                    cursor += 1
                    val start = cursor
                    while (cursor < text.length && isReferenceChar(text[cursor])) cursor++
                    if (cursor == start) return null
                    selectors += Selector.Property(text.substring(start, cursor))
                }
                '[' -> {
                    val close = text.indexOf(']', startIndex = cursor + 1)
                    if (close == -1) return null
                    val body = text.substring(cursor + 1, close).trim()
                    selectors += when {
                        body == "#" -> Selector.Count
                        body.toIntOrNull() != null -> Selector.Index(body.toInt())
                        else -> return null
                    }
                    cursor = close + 1
                }
                else -> return null
            }
        }

        return TemplateReference(base, selectors)
    }

    private fun jsonElementToString(element: JsonElement): String =
        when (element) {
            JsonNull -> ""
            is JsonPrimitive -> element.contentOrNull ?: element.toString()
            else -> element.toString()
        }

    private fun unquote(value: String): String {
        if (value.length >= 2) {
            val first = value.first()
            val last = value.last()
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                return value.substring(1, value.length - 1)
                    .replace("\\$first", first.toString())
                    .replace("\\\\", "\\")
            }
        }
        return value
    }

    private fun ResolvedValue.takeIfResolved(): ResolvedValue? = if (missing) null else this

    private fun appendStatic(out: StringBuilder, text: String, warnings: MutableList<String>) {
        if (text.isEmpty()) return
        val available = limits.maxOutputLength - out.length
        if (available <= 0) {
            warnings += "Expanded output exceeds ${limits.maxOutputLength} characters."
            return
        }
        out.append(text.take(available))
        if (text.length > available) {
            warnings += "Expanded output exceeds ${limits.maxOutputLength} characters."
        }
    }

    private fun appendExpression(
        out: StringBuilder,
        value: String,
        rawToken: String,
        warnings: MutableList<String>,
    ) {
        val available = limits.maxOutputLength - out.length
        if (value.length <= available) {
            out.append(value)
            return
        }
        val fallbackAvailable = limits.maxOutputLength - out.length
        if (rawToken.length <= fallbackAvailable) {
            out.append(rawToken)
        }
        warnings += "Expansion for '$rawToken' omitted because output would exceed ${limits.maxOutputLength} characters."
    }

    private fun preserve(
        rawToken: String,
        expression: String,
        warning: String,
        functions: List<String> = emptyList(),
    ): EvaluatedExpression = EvaluatedExpression(
        value = rawToken,
        trace = TemplateExpansionTrace(
            rawExpression = rawToken,
            expression = expression,
            value = rawToken,
            source = TemplateValueSource.MISSING,
            path = expression,
            functions = functions,
            warning = warning,
        ),
        warnings = listOf(warning),
    )

    private fun isReferenceChar(char: Char): Boolean =
        char.isLetterOrDigit() || char == '_' || char == '-'

    private data class EvaluatedExpression(
        val value: String,
        val trace: TemplateExpansionTrace,
        val warnings: List<String> = emptyList(),
    )

    private data class ResolvedValue(
        val value: String,
        val source: TemplateValueSource,
        val path: String,
        val missing: Boolean = false,
        val array: List<String>? = null,
        val isSecretDerived: Boolean = false,
    )

    private data class FunctionApplication(
        val value: ResolvedValue,
        val warnings: List<String> = emptyList(),
        val preserveReason: String? = null,
    )

    private val MAX_DATE_PATTERN_LENGTH = 64

    private data class TemplateFunction(
        val name: String,
        val argument: String?,
    )

    private data class TemplateReference(
        val base: String,
        val selectors: List<Selector>,
    )

    private sealed interface Selector {
        data class Property(val name: String) : Selector
        data class Index(val index: Int) : Selector
        data object Count : Selector
    }
}
