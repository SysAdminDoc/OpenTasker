package com.opentasker.core.engine.variables

import com.opentasker.core.engine.VariableStore
import org.json.JSONObject
import com.google.re2j.Pattern as Re2Pattern
import kotlin.math.floor
import kotlin.math.roundToLong

/**
 * Enhanced variable expression evaluator supporting:
 * - Math operators: %var(+5), %var(*2), %var(//) floor, %var(/round)
 * - String operations: %var(upper), %var(lower), %var(substring:0:5), %var(trim), %var(split:,), %var(join:-)
 * - Linear-time regex: %var(regex:pattern:group) extract, %var(replace:pattern:replacement)
 * - Conditionals: %var = (expr) ? true_val : false_val
 * - Arrays: %list(#) count, %list(1) indexed, %list() join
 * - JSON: %json.path.to.field parse nested JSON
 */
class VariableExpander {

    /**
     * Expand an expression with operators. Examples:
     * - "5" -> "5"
     * - "%VAR" -> value of VAR
     * - "%VAR(+10)" -> parse VAR as number, add 10
     * - "%VAR(upper)" -> uppercase VAR
     * - "%VAR(regex:(\d+):1)" -> extract first digit group from VAR
     * - "%VAR(split:,)" -> split VAR by comma, return array
     */
    fun expand(expr: String, variableStore: VariableStore, arrayStore: ArrayStore): String {
        return try {
            expandInternal(expr, variableStore, arrayStore)
        } catch (e: Exception) {
            // If evaluation fails, return original expression
            expr
        }
    }

    private fun expandInternal(expr: String, variableStore: VariableStore, arrayStore: ArrayStore): String {
        // Handle conditionals: (cond) ? true_val : false_val
        val ternary = parseTernary(expr)
        if (ternary != null) {
            val result = evaluateConditionInternal(ternary.condition, variableStore, arrayStore)
            return expandText(if (result) ternary.trueValue else ternary.falseValue, variableStore, arrayStore)
        }

        return expandText(expr, variableStore, arrayStore)
    }

    private data class Ternary(val condition: String, val trueValue: String, val falseValue: String)

    /**
     * Parse a leading `(condition) ? trueValue : falseValue` expression. The condition is matched by
     * balanced parentheses so operator expressions that contain parens (e.g. `(%A(+1) > 5)`) are
     * handled, unlike the previous `[^)]+` regex which stopped at the first `)`.
     */
    private fun parseTernary(expr: String): Ternary? {
        if (expr.isEmpty() || expr[0] != '(') return null
        var depth = 0
        var conditionEnd = -1
        for (i in expr.indices) {
            when (expr[i]) {
                '(' -> depth++
                ')' -> if (--depth == 0) { conditionEnd = i; break }
            }
        }
        if (conditionEnd == -1) return null
        var cursor = conditionEnd + 1
        while (cursor < expr.length && expr[cursor].isWhitespace()) cursor++
        if (cursor >= expr.length || expr[cursor] != '?') return null
        val rest = expr.substring(cursor + 1)
        val colon = rest.indexOf(':')
        if (colon == -1) return null
        return Ternary(
            condition = expr.substring(1, conditionEnd),
            trueValue = rest.substring(0, colon).trim(),
            falseValue = rest.substring(colon + 1).trim(),
        )
    }

    private fun expandText(expr: String, variableStore: VariableStore, arrayStore: ArrayStore): String {
        if ('%' !in expr) return expr

        val out = StringBuilder(expr.length)
        var i = 0
        while (i < expr.length) {
            val c = expr[i]
            if (c == '%' && i + 1 < expr.length && expr[i + 1].isLetter()) {
                val token = readVariableToken(expr, i, variableStore, arrayStore)
                out.append(token.value)
                i = token.nextIndex
            } else {
                out.append(c)
                i++
            }
        }

        return out.toString()
    }

    private fun readVariableToken(
        expr: String,
        start: Int,
        variableStore: VariableStore,
        arrayStore: ArrayStore,
    ): TokenExpansion {
        var cursor = start + 1
        while (cursor < expr.length && isVariableNameChar(expr[cursor])) cursor++
        val name = expr.substring(start + 1, cursor)

        if (name == "json" && cursor < expr.length && expr[cursor] == '.') {
            val path = readJsonPath(expr, cursor + 1)
            if (path.value.isNotEmpty()) {
                return TokenExpansion(
                    evaluateJsonPath(variableStore.get("json") ?: "{}", path.value),
                    path.nextIndex,
                )
            }
        }

        if (cursor < expr.length && expr[cursor] == '(') {
            val close = findOperatorClose(expr, cursor)
            if (close != -1) {
                val op = expr.substring(cursor + 1, close)
                return TokenExpansion(
                    evaluateVarOp(name, op, variableStore, arrayStore),
                    close + 1,
                )
            }
        }

        variableStore.get(name)?.let { return TokenExpansion(it, cursor) }

        // A hyphen is a legal name character, so `%count-1` scans greedily as the name "count-1".
        // When no such variable exists, fall back to the longest defined prefix so ordinary text
        // keeps expanding `%count` and leaving `-1` behind, the way it did before hyphens were
        // accepted in names. Tasker does not allow hyphens either, so imported tasks rely on this.
        var separator = name.lastIndexOf('-')
        while (separator > 0) {
            val prefix = name.substring(0, separator)
            variableStore.get(prefix)?.let { value ->
                return TokenExpansion(value, start + 1 + separator)
            }
            separator = name.lastIndexOf('-', separator - 1)
        }

        return TokenExpansion("", cursor)
    }

    private fun findOperatorClose(expr: String, openIndex: Int): Int {
        var depth = 0
        for (index in openIndex until expr.length) {
            when (expr[index]) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) return index
                }
            }
        }
        return -1
    }

    private fun evaluateVarOp(name: String, op: String, store: VariableStore, arrays: ArrayStore): String {
        val baseValue = store.get(name)

        if (arrays.contains(name) && baseValue == null) {
            return when {
                op == "#" -> arrays.length(name).toString()
                op.toIntOrNull() != null -> arrays.get(name, op.toInt())
                op.isEmpty() -> arrays.join(name, "")
                else -> arrays.join(name, op)
            }
        }

        if (baseValue == null) return ""

        // Math operations
        val numValue = baseValue.toDoubleOrNull()
        if (numValue != null) {
            val result = when {
                op == "//" -> floor(numValue)
                op == "/round" -> numValue.roundToLong().toDouble()
                op.startsWith("+") -> numValue + (op.substring(1).toDoubleOrNull() ?: 0.0)
                op.startsWith("-") -> numValue - (op.substring(1).toDoubleOrNull() ?: 0.0)
                op.startsWith("*") -> numValue * (op.substring(1).toDoubleOrNull() ?: 1.0)
                op.startsWith("/") -> {
                    val divisor = op.substring(1).toDoubleOrNull() ?: 1.0
                    if (divisor != 0.0) numValue / divisor else 0.0
                }
                else -> numValue
            }
            return when {
                result == floor(result) -> result.toLong().toString()
                else -> result.toString()
            }
        }

        // String operations
        return when {
            op == "upper" -> baseValue.uppercase()
            op == "lower" -> baseValue.lowercase()
            op == "trim" -> baseValue.trim()
            op.startsWith("substring:") -> {
                val parts = op.substring(10).split(":")
                val start = parts.getOrNull(0)?.toIntOrNull() ?: 0
                val end = parts.getOrNull(1)?.toIntOrNull() ?: baseValue.length
                val boundedStart = start.coerceIn(0, baseValue.length)
                val boundedEnd = end.coerceIn(0, baseValue.length)
                if (boundedEnd < boundedStart) "" else baseValue.substring(boundedStart, boundedEnd)
            }
            op.startsWith("split:") -> {
                val delimiter = op.substring(6)
                val parts = baseValue.split(delimiter)
                arrays.put(name + "_split", parts)
                "${name}_split"
            }
            op.startsWith("join:") -> {
                val delimiter = op.substring(5)
                arrays.join(name, delimiter)
            }
            op.startsWith("regex:") -> {
                val (pattern, groupIdx) = parseRegexOp(op.substring(6))
                if (pattern.length > MAX_REGEX_LENGTH || baseValue.length > MAX_REGEX_INPUT_LENGTH) return ""
                extractRegexGroup(pattern, baseValue, groupIdx)
            }
            op.startsWith("replace:") -> {
                val parts = op.substring(8).split(":", limit = 2)
                val pattern = parts.getOrNull(0) ?: ""
                val replacement = parts.getOrNull(1) ?: ""
                if (pattern.length > MAX_REGEX_LENGTH || baseValue.length > MAX_REGEX_INPUT_LENGTH) return baseValue
                replaceRegex(pattern, baseValue, replacement) ?: baseValue
            }
            else -> baseValue
        }
    }

    private fun parseRegexOp(body: String): Pair<String, Int> {
        val splitAt = body.lastIndexOf(':')
        if (splitAt <= 0) return body to 0
        val groupIdx = body.substring(splitAt + 1).toIntOrNull() ?: return body to 0
        return body.substring(0, splitAt) to groupIdx
    }

    fun evaluateCondition(cond: String, variableStore: VariableStore, arrayStore: ArrayStore): Boolean =
        try {
            evaluateConditionInternal(cond, variableStore, arrayStore)
        } catch (e: Exception) {
            false
        }

    private fun evaluateConditionInternal(cond: String, store: VariableStore, arrays: ArrayStore): Boolean {
        val normalized = stripOuterParens(cond.trim())
        if (normalized.isEmpty()) return false

        splitTopLevel(normalized, "||")?.let { parts ->
            return parts.any { evaluateConditionInternal(it, store, arrays) }
        }
        splitTopLevel(normalized, "&&")?.let { parts ->
            return parts.all { evaluateConditionInternal(it, store, arrays) }
        }

        // A real binary comparison always wins over the unary is_set/not_set reading: e.g.
        // "%status == is_set", comparing a variable against the literal string "is_set", must
        // stay an equality check, not get hijacked into an existence check just because the text
        // happens to end with that word. Unary existence syntax is tried only once no binary
        // comparison matches at all -- which also means "is_set"/"not_set" can never appear as a
        // *value* on either side of a real ==, !=, ~, etc. comparison and be misread as the unary
        // operator, since parseComparison always gets first look.
        val comparison = parseComparison(normalized)
        if (comparison == null) {
            unaryExistenceResult(normalized, store, arrays)?.let { return it }
            return normalized.toBoolean()
        }
        val left = expandInternal(comparison.left, store, arrays)
        val right = expandInternal(comparison.right, store, arrays)

        return when (comparison.operator) {
            ComparisonOperator.EQ -> left == right
            ComparisonOperator.NE -> left != right
            ComparisonOperator.LE -> compareNumbers(left, right) { l, r -> l <= r }
            ComparisonOperator.GE -> compareNumbers(left, right) { l, r -> l >= r }
            ComparisonOperator.LT -> compareNumbers(left, right) { l, r -> l < r }
            ComparisonOperator.GT -> compareNumbers(left, right) { l, r -> l > r }
            ComparisonOperator.MATCHES -> matchesGlob(left, right)
            ComparisonOperator.NOT_MATCHES -> !matchesGlob(left, right)
        }
    }

    /**
     * Tasker's wildcard match: `*` is the only special character, everything else is literal.
     * Used for imported "Matches"/"Doesn't Match" conditions (Tasker op codes 2/3), e.g.
     * `%pa_do ~ view_url` or `%pa_json ~ *"say":*`. Regex metacharacters other than `*` are
     * escaped so a pattern like `%pa_x1.example` matches a literal dot, not "any character".
     */
    /**
     * Tasker's wildcard match: `*` is the only special character, everything else is literal.
     * Used for imported "Matches"/"Doesn't Match" conditions (Tasker op codes 2/3), e.g.
     * `%pa_do ~ view_url` or `%pa_json ~ *"say":*`.
     *
     * Goes through [compileLinearRegex] (RE2, linear-time) with the same [MAX_REGEX_LENGTH] /
     * [MAX_REGEX_INPUT_LENGTH] guards this file already applies to every other regex built from
     * import- or variable-derived text (see the `regex:`/`replace:` var-ops above), rather than
     * Kotlin's backtracking `Regex`: `pattern` here comes from imported Tasker condition data,
     * not a fixed literal, so several `*` wildcards is exactly the shape that causes catastrophic
     * backtracking in a standard engine. `com.google.re2j.Pattern.quote` is RE2J's per-character
     * `quoteMeta` escaper, not `kotlin.text.Regex.escape`'s `\Q...\E` -- RE2 doesn't implement
     * `\Q...\E` as a syntax construct at all, so the latter would either fail to compile or (worse)
     * be silently misinterpreted.
     *
     * `\A`/`\z` anchor for a full-string match (RE2J's `Matcher` is not confirmed to expose a
     * `.matches()` convenience the way `java.util.regex.Matcher` does, so this uses the same
     * `.find()` call already proven out at the other [compileLinearRegex] call sites).
     */
    private fun matchesGlob(value: String, pattern: String): Boolean {
        if (pattern.length > MAX_REGEX_LENGTH || value.length > MAX_REGEX_INPUT_LENGTH) return false
        val regex = buildString {
            append("(?s)\\A")
            pattern.split("*").forEachIndexed { index, literal ->
                if (index > 0) append(".*")
                append(Re2Pattern.quote(literal))
            }
            append("\\z")
        }
        val matcher = compileLinearRegex(regex)?.matcher(value) ?: return false
        return try {
            matcher.find()
        } catch (e: RuntimeException) {
            false
        }
    }

    private fun splitTopLevel(expr: String, delimiter: String): List<String>? {
        val parts = mutableListOf<String>()
        var depth = 0
        var partStart = 0
        var index = 0
        while (index < expr.length) {
            when (expr[index]) {
                '(' -> depth++
                ')' -> if (depth > 0) depth--
            }
            if (depth == 0 && expr.startsWith(delimiter, index)) {
                parts += expr.substring(partStart, index).trim()
                index += delimiter.length
                partStart = index
                continue
            }
            index++
        }
        if (parts.isEmpty()) return null
        parts += expr.substring(partStart).trim()
        return parts
    }

    /**
     * Unary existence checks (imported from Tasker's "Is Set"/"Not Set" condition ops, which have
     * no right-hand operand). Suffix-anchored, not scanned like the binary operators, so a
     * variable value that happens to contain "is_set"/"not_set" mid-string can't false-match.
     * Returns null when `normalized` doesn't end with either suffix at all, so the caller can
     * fall through to its own default (`normalized.toBoolean()`).
     *
     * This evaluator has two call paths with different pre-expansion behavior:
     * TaskRunner.evaluateConditionString expands the whole condition once before calling in, but
     * VariableStore.evaluateCondition (used directly by callers/tests that don't go through
     * TaskRunner) does not expand at all -- `normalized` can arrive as raw `%variable` text. The
     * binary comparison branch stays correct under both by calling expandInternal on its operands
     * regardless of whether the caller already did; this needs the same self-sufficiency, so it
     * must not assume expansion already happened.
     *
     * Empty (post-expansion) operand is a real, meaningful case, not a malformed condition: when
     * the source variable is unset and the caller pre-expanded (the TaskRunner path), e.g.
     * "%text not_set" arrives as just " not_set" (empty value + the literal suffix), and
     * `cond.trim()` at the top of [evaluateConditionInternal] then eats that boundary space -- so
     * an empty-operand match and a bare "not_set"/"is_set" with nothing before it are
     * indistinguishable by the time we see them, and both correctly mean "the value is empty".
     */
    private fun unaryExistenceResult(normalized: String, store: VariableStore, arrays: ArrayStore): Boolean? {
        for ((suffix, wantsNonEmpty) in UNARY_EXISTENCE_SUFFIXES) {
            val bareSuffix = suffix.trim()
            if (normalized.equals(bareSuffix, ignoreCase = true)) {
                return !wantsNonEmpty
            }
            if (normalized.endsWith(suffix, ignoreCase = true)) {
                val operand = expandInternal(normalized.removeSuffix(suffix).trim(), store, arrays)
                return if (wantsNonEmpty) operand.isNotEmpty() else operand.isEmpty()
            }
        }
        return null
    }

    private fun stripOuterParens(expr: String): String {
        var result = expr
        while (result.length >= 2 && result.first() == '(' && matchingCloseParen(result, 0) == result.lastIndex) {
            result = result.substring(1, result.lastIndex).trim()
        }
        return result
    }

    private fun matchingCloseParen(expr: String, openIndex: Int): Int {
        var depth = 0
        for (index in openIndex until expr.length) {
            when (expr[index]) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) return index
                }
            }
        }
        return -1
    }

    private fun parseComparison(expr: String): ConditionComparison? {
        val matches = mutableListOf<ConditionComparison>()
        var depth = 0
        var index = 0
        while (index < expr.length) {
            when (expr[index]) {
                '(' -> depth++
                ')' -> if (depth > 0) depth--
            }
            if (depth == 0) {
                val operator = COMPARISON_OPERATORS.firstOrNull { expr.matchesOperatorAt(it, index) }
                if (operator != null) {
                    val left = expr.substring(0, index).trim()
                    val right = expr.substring(index + operator.token.length).trim()
                    if (left.isEmpty() || right.isEmpty()) return null
                    matches += ConditionComparison(left, operator, right)
                    index += operator.token.length
                    continue
                }
            }
            index++
        }
        return matches.singleOrNull()
    }

    /**
     * True if `operator`'s token occurs at `index`, and -- for [ComparisonOperator.MATCHES] /
     * [ComparisonOperator.NOT_MATCHES] only -- is bounded by whitespace or a string edge on both
     * sides. `~` and `!~` are ordinary characters in real values in a way `==`/`<`/etc. are not
     * (paths like `~/backups`, version strings like `1.2~rc1`, approximations like `~100`), so
     * without this a literal tilde inside an otherwise unambiguous comparison's left/right text
     * makes parseComparison find two operator matches instead of one, `matches.singleOrNull()`
     * returns null, and the whole comparison silently falls back to `normalized.toBoolean()`
     * (false) -- turning a working condition into one that's always false. The other operators
     * don't get this treatment: it would be a larger, non-additive behavior change for tokens
     * this project already shipped with, out of scope for this fix.
     */
    private fun String.matchesOperatorAt(operator: ComparisonOperator, index: Int): Boolean {
        if (!startsWith(operator.token, index)) return false
        if (operator != ComparisonOperator.MATCHES && operator != ComparisonOperator.NOT_MATCHES) return true
        val before = index == 0 || this[index - 1].isWhitespace()
        val afterIndex = index + operator.token.length
        val after = afterIndex >= length || this[afterIndex].isWhitespace()
        return before && after
    }

    private fun compareNumbers(left: String, right: String, predicate: (Double, Double) -> Boolean): Boolean {
        val l = left.toDoubleOrNull() ?: return false
        val r = right.toDoubleOrNull() ?: return false
        return predicate(l, r)
    }

    private fun evaluateJsonPath(json: String, path: String): String {
        try {
            var current: Any? = JSONObject(json)
            val keys = path.split(".")
            for (key in keys) {
                current = if (current is JSONObject) {
                    current.opt(key)
                } else {
                    return ""
                }
            }
            return current?.toString() ?: ""
        } catch (e: Exception) {
            return ""
        }
    }

    private fun readJsonPath(expr: String, start: Int): TokenExpansion {
        val path = StringBuilder()
        var cursor = start
        while (cursor < expr.length) {
            val segmentStart = cursor
            while (cursor < expr.length && isJsonPathSegmentChar(expr[cursor])) cursor++
            if (cursor == segmentStart) break
            if (path.isNotEmpty()) path.append('.')
            path.append(expr, segmentStart, cursor)
            if (cursor < expr.length && expr[cursor] == '.' &&
                cursor + 1 < expr.length && isJsonPathSegmentChar(expr[cursor + 1])
            ) {
                cursor++
            } else {
                break
            }
        }
        return TokenExpansion(path.toString(), cursor)
    }

    private fun extractRegexGroup(pattern: String, input: String, groupIdx: Int): String {
        val matcher = compileLinearRegex(pattern)?.matcher(input) ?: return ""
        return try {
            if (!matcher.find()) ""
            else matcher.group(groupIdx) ?: ""
        } catch (_: RuntimeException) {
            ""
        }
    }

    private fun replaceRegex(pattern: String, input: String, replacement: String): String? {
        val matcher = compileLinearRegex(pattern)?.matcher(input) ?: return null
        return try {
            matcher.replaceAll(replacement)
        } catch (_: RuntimeException) {
            null
        }
    }

    private fun isVariableNameChar(c: Char): Boolean = c.isLetterOrDigit() || c == '_' || c == '-'

    private fun isJsonPathSegmentChar(c: Char): Boolean = c.isLetterOrDigit() || c == '_' || c == '-'

    private data class TokenExpansion(
        val value: String,
        val nextIndex: Int,
    )

    private data class ConditionComparison(
        val left: String,
        val operator: ComparisonOperator,
        val right: String,
    )

    private enum class ComparisonOperator(val token: String) {
        EQ("=="),
        NE("!="),
        LE("<="),
        GE(">="),
        LT("<"),
        GT(">"),
        MATCHES("~"),
        NOT_MATCHES("!~"),
    }

    companion object {
        // (suffix, isSetWhenTrue) — order matters: "not_set"/"!is_set" style negatives must be
        // checked before a shorter positive suffix could partially match, though with these two
        // literal strings neither is a suffix of the other, so this is just future-proofing.
        private val UNARY_EXISTENCE_SUFFIXES = listOf(
            " is_set" to true,
            " not_set" to false,
        )
        private val COMPARISON_OPERATORS = listOf(
            ComparisonOperator.EQ,
            ComparisonOperator.NE,
            ComparisonOperator.LE,
            ComparisonOperator.GE,
            ComparisonOperator.LT,
            ComparisonOperator.NOT_MATCHES,
            ComparisonOperator.MATCHES,
            ComparisonOperator.GT,
        )
        private const val MAX_REGEX_LENGTH = 256
        private const val MAX_REGEX_INPUT_LENGTH = 10_000

        private fun compileLinearRegex(pattern: String): Re2Pattern? {
            return try {
                Re2Pattern.compile(pattern)
            } catch (_: RuntimeException) {
                null
            }
        }
    }
}

/**
 * Array storage for list variables.
 * Arrays are accessed as %list(#) for count, %list(1) for index, %list() for join.
 */
class ArrayStore {
    // Access-ordered LRU: the least-recently-used array is evicted at the cap, not an
    // arbitrary entry. All access is synchronized on [lock] because the store is shared
    // across concurrently running tasks.
    private val lock = Any()
    private val arrays = object : LinkedHashMap<String, List<String>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<String>>): Boolean =
            size > MAX_ARRAYS
    }

    fun put(name: String, values: List<String>) {
        synchronized(lock) { arrays[name] = values }
    }

    fun clear() {
        synchronized(lock) { arrays.clear() }
    }

    companion object {
        private const val MAX_ARRAYS = 500
    }

    fun get(name: String, index: Int): String {
        return synchronized(lock) { arrays[name]?.getOrNull(index) } ?: ""
    }

    fun length(name: String): Int {
        return synchronized(lock) { arrays[name]?.size } ?: 0
    }

    fun contains(name: String): Boolean {
        return synchronized(lock) { arrays.containsKey(name) }
    }

    fun join(name: String, delimiter: String): String {
        return synchronized(lock) { arrays[name]?.joinToString(delimiter) } ?: ""
    }

    fun snapshot(): Map<String, List<String>> =
        synchronized(lock) { LinkedHashMap(arrays).mapValues { (_, values) -> values.toList() } }
}
