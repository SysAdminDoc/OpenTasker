package com.opentasker.core.engine.variables

import com.opentasker.core.data.JsonNesting
import com.opentasker.core.engine.VariableStore
import org.json.JSONObject
import java.math.BigDecimal
import java.math.RoundingMode
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
    /**
     * [conditionals] false leaves a leading `(cond) ? a : b` as text, for a value that isn't a whole
     * expression. [unset] says how a `%name` with no value reads.
     */
    fun expand(
        expr: String,
        variableStore: VariableStore,
        arrayStore: ArrayStore,
        quoteValue: (String) -> String = { it },
        conditionals: Boolean = true,
        unset: Unset = Unset.AS_WRITTEN,
    ): String {
        return try {
            expandInternal(expr, variableStore, arrayStore, quoteValue, conditionals, unset)
        } catch (e: Exception) {
            // If evaluation fails, return original expression
            expr
        }
    }

    /** How a `%name` with no value reads. */
    enum class Unset {
        /** Left as written, as Tasker does: "User-variables which have not had a value assigned do not have replacements carried out." */
        AS_WRITTEN,

        /** Filled in as nothing, for Is Set and an array index. */
        BLANK,

        /**
         * Read as 0, empty or not, the way Tasker 6.7.8 fills in a plain variable in maths (decompiled
         * `dq.S`). An operator or array form, like `%x(+1)` or `%arr(#)`, reads as it always does.
         */
        ZERO,
    }

    private fun expandInternal(
        expr: String,
        variableStore: VariableStore,
        arrayStore: ArrayStore,
        quoteValue: (String) -> String = { it },
        conditionals: Boolean = true,
        unset: Unset = Unset.AS_WRITTEN,
    ): String {
        // Handle conditionals: (cond) ? true_val : false_val
        val ternary = if (conditionals) parseTernary(expr) else null
        if (ternary != null) {
            val result = evaluateConditionInternal(ternary.condition) { operand, mode ->
                expandInternal(operand, variableStore, arrayStore, unset = mode)
            }
            return expandText(if (result) ternary.trueValue else ternary.falseValue, variableStore, arrayStore, unset, quoteValue)
        }

        return expandText(expr, variableStore, arrayStore, unset, quoteValue)
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

    /** [unset] defaults to [Unset.BLANK] for an array index, where an unset `%i` names no element. */
    private fun expandText(
        expr: String,
        variableStore: VariableStore,
        arrayStore: ArrayStore,
        unset: Unset = Unset.BLANK,
        quoteValue: (String) -> String,
    ): String {
        if ('%' !in expr) return expr

        val out = StringBuilder(expr.length)
        var i = 0
        while (i < expr.length) {
            val c = expr[i]
            if (expr.startsWith("\\%", i) && startsVariable(expr, i + 2)) {
                // \%%name is a percent sign and the value, and Tasker reads an unset name as blank there.
                val token = readVariableToken(expr, i + 2, variableStore, arrayStore)
                out.append('%').append(if (token.unset) "" else quoteValue(token.value))
                i = token.nextIndex
            } else if (c == '\\') {
                i = appendEscape(expr, i, out)
            } else if (startsVariable(expr, i)) {
                val token = readVariableToken(expr, i, variableStore, arrayStore)
                out.append(
                    when {
                        unset == Unset.ZERO && token.value.isEmpty() && !token.operator -> "0"
                        // The text kept is the author's, not a value.
                        unset == Unset.AS_WRITTEN && token.unset -> expr.substring(i, token.nextIndex)
                        else -> quoteValue(token.value)
                    },
                )
                i = token.nextIndex
            } else {
                out.append(c)
                i++
            }
        }

        return out.toString()
    }

    private fun startsVariable(expr: String, index: Int): Boolean =
        index + 1 < expr.length && expr[index] == '%' && expr[index + 1].isLetter()

    /**
     * Tasker's backslash before a variable (decompiled 6.7.8, `dq.S`): `\%name` is the reference
     * as written without the backslash, and `\\%name` is one backslash and then the value, so a
     * longer run loses one backslash. [expandText] reads `\%%name`, which needs the value. A
     * backslash before anything else stays. Returns the index to carry on from.
     */
    private fun appendEscape(expr: String, start: Int, out: StringBuilder): Int {
        var run = start
        while (run < expr.length && expr[run] == '\\') run++
        val backslashes = run - start
        return when {
            backslashes == 1 && startsVariable(expr, run) -> {
                // Not read, because reading an operator form like split: stores an array.
                val end = variableTokenEnd(expr, run)
                out.append(expr, run, end)
                end
            }
            backslashes > 1 && startsVariable(expr, run) -> {
                repeat(backslashes - 1) { out.append('\\') }
                run
            }
            else -> {
                out.append(expr, start, run)
                run
            }
        }
    }

    /** Where the reference at [start] ends, as [readVariableToken] reads it, without reading its value. */
    private fun variableTokenEnd(expr: String, start: Int): Int {
        var cursor = start + 1
        while (cursor < expr.length && isVariableNameChar(expr[cursor])) cursor++
        if (expr.substring(start + 1, cursor) == "json" && cursor < expr.length && expr[cursor] == '.') {
            val path = readJsonPath(expr, cursor + 1)
            if (path.value.isNotEmpty()) return path.nextIndex
        }
        if (cursor < expr.length && expr[cursor] == '(') {
            val close = findOperatorClose(expr, cursor)
            if (close != -1) return close + 1
        }
        return cursor
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
                val json = variableStore.get("json") ?: return TokenExpansion("", path.nextIndex, unset = true)
                return TokenExpansion(evaluateJsonPath(json, path.value), path.nextIndex)
            }
        }

        if (cursor < expr.length && expr[cursor] == '(') {
            val close = findOperatorClose(expr, cursor)
            if (close != -1) {
                val op = expr.substring(cursor + 1, close)
                return TokenExpansion(
                    evaluateVarOp(name, op, variableStore, arrayStore),
                    close + 1,
                    operator = true,
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

        return TokenExpansion("", cursor, unset = true)
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

        if (arrays.contains(name)) storedArrayValue(name, op, baseValue, store, arrays)?.let { return it }

        taskerArrayValue(name, op, baseValue, store, arrays)?.let { return it }

        if (baseValue == null) return ""

        // Math operations
        val numValue = baseValue.toDoubleOrNull()
        if (numValue != null) {
            val result = when {
                op == "//" -> floor(numValue)
                // roundToLong throws on NaN, so a value that isn't finite comes out the way the
                // other operations leave it.
                op == "/round" -> if (numValue.isFinite()) numValue.roundToLong().toDouble() else numValue
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

    /**
     * The 0-based arrays text.split and text.match store: `#` counts the elements, a number is one
     * element and `a:b` joins a range with commas, backwards when a is past b. A variable inside
     * the parentheses is filled in first and trimmed, so `%parts(%i)` reads the element at `%i` and
     * gives empty while `%i` is, as does a range with an empty variable on either side. Any other
     * form joins the elements with it. Both actions also set [name] to the first element, so while
     * it holds a value the stored array answers only those array forms. It never answers once
     * [name] has numbered elements (`%parts1`, ...) of its own, whether [name] is set or not. Null
     * leaves [op] to them.
     */
    private fun storedArrayValue(
        name: String,
        op: String,
        baseValue: String?,
        store: VariableStore,
        arrays: ArrayStore,
    ): String? {
        val expanded = if ('%' in op) expandText(op, store, arrays) { it } else op
        val form = expanded.trim()
        val arrayForm = STORED_ARRAY_FORM.matches(form)
        if (store.numberedItems(name).isNotEmpty() || (baseValue != null && !arrayForm)) return null
        if (form.isEmpty() && '%' in op) return ""
        if (':' in op && op.split(':', limit = 2).any { side -> '%' in side && expandText(side, store, arrays) { it }.isBlank() }) return ""
        val size = arrays.length(name)
        return when {
            form == "#" -> size.toString()
            form.isEmpty() || !arrayForm -> arrays.join(name, expanded)
            ':' !in form -> form.toIntOrNull()?.let { arrays.get(name, it) }.orEmpty()
            else -> {
                val from = form.substringBefore(':').ifEmpty { "0" }.toIntOrNull() ?: return ""
                val to = form.substringAfter(':').ifEmpty { null }?.let { it.toIntOrNull() ?: return "" } ?: (size - 1)
                val range = if (from <= to) from..to else from downTo to
                range.filter { it in 0 until size }.joinToString(",") { arrays.get(name, it) }
            }
        }
    }

    /**
     * Tasker's array forms over numbered variables (`%arr1`, `%arr2`, ...): `#` counts the elements,
     * `#>` and `#<` give the first and last index, `>` and `<` the first and last value, a number
     * one element, and an empty, `:` or `a:b` form joins the elements set in that range with commas,
     * backwards when a is past b. A variable inside the parentheses is filled in first, so
     * `%arr(%i)` works as it does in Tasker, and gives empty when `%i` is, or when a variable on
     * either side of a range is. Returns null when [op]
     * is none of these, or when [name] is a plain variable with no elements, so its own operators
     * still apply.
     */
    private fun taskerArrayValue(
        name: String,
        op: String,
        baseValue: String?,
        store: VariableStore,
        arrays: ArrayStore,
    ): String? {
        val form = (if ('%' in op) expandText(op, store, arrays) { it } else op).trim()
        if (!TASKER_ARRAY_FORM.matches(form)) return null
        val items = store.numberedItems(name)
        if (items.isEmpty() && baseValue != null) return null
        // An index variable that came out empty names no element, so it isn't the empty form
        // that joins them all, nor the open side of a range (%arr(%a:%b) isn't %arr(:)).
        if (form.isEmpty() && '%' in op) return ""
        if (':' in op && op.split(':', limit = 2).any { side -> '%' in side && expandText(side, store, arrays) { it }.isBlank() }) return ""
        val lastIndex = if (items.isEmpty()) 0 else items.lastKey()
        return when (form) {
            "#" -> items.size.toString()
            "#>" -> (if (items.isEmpty()) 0 else items.firstKey()).toString()
            "#<" -> lastIndex.toString()
            ">" -> items.firstEntry()?.value.orEmpty()
            "<" -> items.lastEntry()?.value.orEmpty()
            else -> {
                if (':' !in form && form.isNotEmpty()) return form.toIntOrNull()?.let(items::get).orEmpty()
                val from = form.substringBefore(':').ifEmpty { "1" }.toIntOrNull() ?: return ""
                val to = form.substringAfter(':', "").ifEmpty { null }?.let { it.toIntOrNull() ?: return "" } ?: lastIndex
                val range = if (from <= to) items.subMap(from, true, to, true) else items.subMap(to, true, from, true).descendingMap()
                range.values.joinToString(",")
            }
        }
    }

    private fun parseRegexOp(body: String): Pair<String, Int> {
        val splitAt = body.lastIndexOf(':')
        if (splitAt <= 0) return body to 0
        val groupIdx = body.substring(splitAt + 1).toIntOrNull() ?: return body to 0
        return body.substring(0, splitAt) to groupIdx
    }

    /**
     * Evaluates [cond] as the author typed it. Its `||`, `&&`, parentheses and comparison operator
     * are read from that text first, and only then is each operand handed to [expandOperand], once.
     *
     * Callers must not expand [cond] themselves. A value is a stranger's words as often as not (an
     * SMS body, a notification title), and expanding before parsing let `true ||` in a message add
     * a branch to the condition, and let `%PIN` in a message be read back as the PIN's value when
     * the operand was expanded a second time.
     *
     * [expandOperand] defaults to legacy `%var` expansion. A caller with a template stage passes
     * its own, and a `{{ ... }}` block in [cond] is kept whole for it. Its second argument says how
     * an unset `%name` reads. It stays as written on either side of a text comparison, as in
     * Tasker, where `%par1 ~ %*` is how a task asks whether it was given anything. It's nothing for
     * Is Set and Isn't Set, which ask whether there's a value, and 0 where Tasker does maths: `<`,
     * `>`, `<=`, `>=`, Is Even and Is Odd.
     */
    fun evaluateCondition(
        cond: String,
        variableStore: VariableStore,
        arrayStore: ArrayStore,
        expandOperand: ((String, Unset) -> String)? = null,
    ): Boolean =
        try {
            evaluateConditionInternal(
                cond,
                expandOperand ?: { operand, unset -> expandInternal(operand, variableStore, arrayStore, unset = unset) },
            )
        } catch (e: Exception) {
            false
        }

    /**
     * How [evaluateCondition] reads [cond] at its top level, once its outer brackets come off: the
     * parts it ORs (`||`, the looser) or ANDs (`&&`), or no joiner and the one comparison left. The
     * Tasker exporter reads a guard through this so it writes the joiners the way the guard runs.
     */
    internal fun conditionParts(cond: String): Pair<String?, List<String>> {
        val normalized = stripOuterParens(cond.trim())
        splitTopLevel(normalized, "||")?.let { return "||" to it }
        splitTopLevel(normalized, "&&")?.let { return "&&" to it }
        return null to listOf(normalized)
    }

    /**
     * The one comparison [evaluateCondition] finds in [cond] as its left side, operator token and
     * right side, or null when there's none or more than one outside brackets. The Tasker exporter
     * reads a condition through this, so an operator inside brackets stays part of its side.
     */
    internal fun comparisonParts(cond: String): Triple<String, String, String>? =
        parseComparison(cond.trim())?.let { Triple(it.left, it.operator.token, it.right) }

    private fun evaluateConditionInternal(cond: String, expandOperand: (String, Unset) -> String): Boolean {
        val (joiner, parts) = conditionParts(cond)
        when (joiner) {
            "||" -> return parts.any { evaluateConditionInternal(it, expandOperand) }
            "&&" -> return parts.all { evaluateConditionInternal(it, expandOperand) }
        }
        val normalized = parts.single()
        if (normalized.isEmpty()) return false

        // A real binary comparison always wins over the unary is_set/not_set reading: e.g.
        // "%status == is_set", comparing a variable against the literal string "is_set", must
        // stay an equality check, not get hijacked into an existence check just because the text
        // happens to end with that word. Unary existence syntax is tried only once no binary
        // comparison matches at all -- which also means "is_set"/"not_set" can never appear as a
        // *value* on either side of a real ==, !=, ~, etc. comparison and be misread as the unary
        // operator, since parseComparison always gets first look.
        val comparison = parseComparison(normalized)
        if (comparison == null) {
            unaryExistenceResult(normalized, expandOperand)?.let { return it }
            unaryParityResult(normalized, expandOperand)?.let { return it }
            // A variable on its own, such as `%enabled`, is a boolean once it has its value.
            return expandOperand(normalized, Unset.AS_WRITTEN).trim().toBoolean()
        }
        // Trimmed after expansion: command output and file reads end with a newline, and a value
        // that came back empty matches nothing, so two empty sides aren't "equal". An unset side
        // stays as written, so `%a == %b` is false while both are unset, and `%a == %a` is true,
        // except in a numeric comparison, where it's 0.
        val unset = if (comparison.operator in NUMERIC_OPERATORS) Unset.ZERO else Unset.AS_WRITTEN
        val left = expandOperand(comparison.left, unset).trim()
        val right = expandOperand(comparison.right, unset).trim()

        return when (comparison.operator) {
            ComparisonOperator.EQ -> left.isNotEmpty() && left == right
            ComparisonOperator.NE -> left != right
            ComparisonOperator.LE -> compareNumbers(left, right) { l, r -> l <= r }
            ComparisonOperator.GE -> compareNumbers(left, right) { l, r -> l >= r }
            ComparisonOperator.LT -> compareNumbers(left, right) { l, r -> l < r }
            ComparisonOperator.GT -> compareNumbers(left, right) { l, r -> l > r }
            ComparisonOperator.MATCHES -> matchesPattern(left, right, emptyMatchesNothing = true)
            ComparisonOperator.NOT_MATCHES -> !matchesPattern(left, right, emptyMatchesNothing = false)
            ComparisonOperator.MATCHES_REGEX -> findsRegex(left, right)
            ComparisonOperator.NOT_MATCHES_REGEX -> !findsRegex(left, right)
        }
    }

    /**
     * Tasker's Matches. A pattern starting `~R` or `!~R` is a regular expression, and a leading `!`
     * turns the rest of the pattern around, so `%a ~ !x` is `%a !~ x`. Anything else goes to
     * [matchesGlob], where [emptyMatchesNothing] keeps `%a ~ *` false while `%a` is empty. An empty
     * pattern matches nothing, as in Tasker, which checks for one before it looks for the `!`.
     */
    private fun matchesPattern(value: String, pattern: String, emptyMatchesNothing: Boolean): Boolean = when {
        pattern.isEmpty() -> false
        pattern.startsWith("~R") -> findsRegex(value, pattern.substring(2))
        pattern.startsWith("!~R") -> !findsRegex(value, pattern.substring(3))
        pattern.startsWith("!") -> !matchesGlob(value, pattern.substring(1))
        else -> !(emptyMatchesNothing && value.isEmpty()) && matchesGlob(value, pattern)
    }

    /** [matchesPattern] for a value that is never empty, like the variable names Variable Clear matches. */
    fun matches(value: String, pattern: String): Boolean = matchesPattern(value, pattern, emptyMatchesNothing = false)

    /**
     * Tasker's Matches Regex (`~R`, and `!~R` for Doesn't Match Regex). The pattern only has to
     * match part of the value, and case counts unless the pattern says `(?i)`. An empty pattern, or
     * one RE2 can't run (backreferences, lookarounds), matches nothing, the way a bad pattern does
     * in Tasker.
     */
    private fun findsRegex(value: String, pattern: String): Boolean {
        if (pattern.isEmpty() || pattern.length > MAX_REGEX_LENGTH || value.length > MAX_REGEX_INPUT_LENGTH) return false
        val matcher = compileLinearRegex(pattern)?.matcher(value) ?: return false
        return try {
            matcher.find()
        } catch (e: RuntimeException) {
            false
        }
    }

    /**
     * Tasker's simple pattern matching: `/` separates alternatives, `*` is any number of any
     * character and `+` is one or more, and an alternative with no capital letter ignores case.
     * The whole value has to match. Used for imported "Matches"/"Doesn't Match" conditions (Tasker
     * op codes 2/3), e.g. `%pa_do ~ view_url` or `%SSID ~ home/work`. [matchesPattern] handles a
     * leading `!` before this.
     *
     * Goes through [compileLinearRegex] (RE2, linear-time) rather than Kotlin's backtracking
     * `Regex`: `pattern` here comes from imported Tasker condition data,
     * not a fixed literal, so several `*` wildcards is exactly the shape that causes catastrophic
     * backtracking in a standard engine. `com.google.re2j.Pattern.quote` is RE2J's per-character
     * `quoteMeta` escaper, not `kotlin.text.Regex.escape`'s `\Q...\E` -- RE2 doesn't implement
     * `\Q...\E` as a syntax construct at all, so the latter would either fail to compile or (worse)
     * be silently misinterpreted.
     *
     * The [MAX_REGEX_LENGTH] cap this file puts on every regex built from imported or variable
     * text applies per alternative here, so a long list of short choices still works. An
     * alternative over it, or a value or whole pattern over [MAX_REGEX_INPUT_LENGTH], matches
     * nothing, which makes `!~` true for it.
     *
     * `\A`/`\z` anchor for a full-string match (RE2J's `Matcher` is not confirmed to expose a
     * `.matches()` convenience the way `java.util.regex.Matcher` does, so this uses the same
     * `.find()` call already proven out at the other [compileLinearRegex] call sites).
     */
    private fun matchesGlob(value: String, pattern: String): Boolean {
        if (pattern.length > MAX_REGEX_INPUT_LENGTH || value.length > MAX_REGEX_INPUT_LENGTH) return false
        return pattern.trim('/').split('/').any {
            it.length <= MAX_REGEX_LENGTH && matchesPatternAlternative(value, it)
        }
    }

    /**
     * One alternative, built the way Tasker's matcher builds it from the literals between
     * wildcards: between two literals every `*` and `+` counts, but at either end only the
     * outermost one does, so `*+a` is `*a` and `a+*` is `a*`.
     */
    private fun matchesPatternAlternative(value: String, alternative: String): Boolean {
        val literals = alternative.split(PATTERN_WILDCARD).dropLastWhile { it.isEmpty() }
        val wildcards = alternative.filter { it == '*' || it == '+' }
        val regex = buildString {
            append(if (alternative.lowercase() == alternative) "(?si)\\A" else "(?s)\\A")
            alternative.firstOrNull()?.let { if (it == '*') append(".*") else if (it == '+') append(".+") }
            var literalsSoFar = 0
            literals.forEachIndexed { index, literal ->
                if (literalsSoFar > 0) append(if (wildcards[index - 1] == '*') ".*" else ".+")
                if (literal.isNotEmpty()) {
                    literalsSoFar++
                    append(Re2Pattern.quote(literal))
                }
            }
            // The last character counts only if it isn't the leading one already used.
            if (alternative.length > 1) {
                when (alternative.last()) {
                    '*' -> append(".*")
                    '+' -> append(".+")
                }
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

    /**
     * The index just past a `{{ ... }}` block opening at [index], or -1 when none opens there. The
     * structure scans step over these: a template's pipes, quoted defaults and brackets belong to
     * the template stage, never to the condition around it.
     */
    private fun templateBlockEnd(expr: String, index: Int): Int {
        if (!expr.startsWith("{{", index)) return -1
        val close = expr.indexOf("}}", index + 2)
        return if (close == -1) -1 else close + 2
    }

    private fun splitTopLevel(expr: String, delimiter: String): List<String>? {
        val parts = mutableListOf<String>()
        var depth = 0
        var partStart = 0
        var index = 0
        while (index < expr.length) {
            val templateEnd = templateBlockEnd(expr, index)
            if (templateEnd != -1) {
                index = templateEnd
                continue
            }
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
     * `normalized` is the author's text, so the operand still holds its `%variable` and is expanded
     * here, once, with an unset variable filled in as nothing. A bare "is_set"/"not_set" with
     * nothing before it means "the value is empty".
     */
    private fun unaryExistenceResult(normalized: String, expandOperand: (String, Unset) -> String): Boolean? {
        for ((suffix, wantsNonEmpty) in UNARY_EXISTENCE_SUFFIXES) {
            val bareSuffix = suffix.trim()
            if (normalized.equals(bareSuffix, ignoreCase = true)) {
                return !wantsNonEmpty
            }
            if (normalized.endsWith(suffix, ignoreCase = true)) {
                val operand = expandOperand(normalized.dropLast(suffix.length).trim(), Unset.BLANK).trim()
                return if (wantsNonEmpty) operand.isNotEmpty() else operand.isEmpty()
            }
        }
        return null
    }

    /**
     * Tasker's Is Even and Is Odd (condition ops 10 and 11), which take no right-hand operand
     * either. As in Tasker 6.7.8 (decompiled Expr), a value that isn't a finite number is neither,
     * and otherwise Even is `value % 2 == 0` and Odd is anything else, so 2.5 is odd. Tasker fills
     * the variable in as maths, so an unset or empty one is 0 and even. Null when neither suffix is
     * there.
     */
    private fun unaryParityResult(normalized: String, expandOperand: (String, Unset) -> String): Boolean? {
        for ((suffix, wantsEven) in UNARY_PARITY_SUFFIXES) {
            if (!normalized.endsWith(suffix, ignoreCase = true)) continue
            val value = taskerNumber(expandOperand(normalized.dropLast(suffix.length).trim(), Unset.ZERO).trim())
                ?: return false
            val remainder = value % 2.0
            return if (wantsEven) remainder == 0.0 else remainder != 0.0
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
        var index = openIndex
        while (index < expr.length) {
            val templateEnd = templateBlockEnd(expr, index)
            if (templateEnd != -1) {
                index = templateEnd
                continue
            }
            when (expr[index]) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) return index
                }
            }
            index++
        }
        return -1
    }

    private fun parseComparison(expr: String): ConditionComparison? {
        val matches = mutableListOf<ConditionComparison>()
        var depth = 0
        var index = 0
        while (index < expr.length) {
            val templateEnd = templateBlockEnd(expr, index)
            if (templateEnd != -1) {
                index = templateEnd
                continue
            }
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
     * [ComparisonOperator.NOT_MATCHES] and their `~R` / `!~R` regex forms only -- is bounded by
     * whitespace or a string edge on both sides. `~` and `!~` are ordinary characters in real
     * values in a way `==`/`<`/etc. are not (paths like `~/backups`, version strings like
     * `1.2~rc1`, approximations like `~100`), so
     * without this a literal tilde inside an otherwise unambiguous comparison's left/right text
     * makes parseComparison find two operator matches instead of one, `matches.singleOrNull()`
     * returns null, and the whole comparison silently falls back to `normalized.toBoolean()`
     * (false) -- turning a working condition into one that's always false. The other operators
     * don't get this treatment: it would be a larger, non-additive behavior change for tokens
     * this project already shipped with, out of scope for this fix.
     */
    private fun String.matchesOperatorAt(operator: ComparisonOperator, index: Int): Boolean {
        if (!startsWith(operator.token, index)) return false
        if (operator !in TILDE_OPERATORS) return true
        val before = index == 0 || this[index - 1].isWhitespace()
        val afterIndex = index + operator.token.length
        val after = afterIndex >= length || this[afterIndex].isWhitespace()
        return before && after
    }

    private fun compareNumbers(left: String, right: String, predicate: (Double, Double) -> Boolean): Boolean {
        val l = taskerNumber(left) ?: return false
        val r = taskerNumber(right) ?: return false
        return predicate(l, r)
    }

    /**
     * [text] as a number the way Tasker's maths reads one value (decompiled 6.7.8, `Expr.d` and
     * `yd`). It drops every space first, reads `0x` as a hexadecimal whole number, and otherwise
     * takes Java's decimal form, `4f` included, with a sign-free exponent, since a `+` or `-` there
     * is an operator to Tasker. The result has to be finite, and it's rounded half up to 10 decimal
     * places, so `0.30000000000000004` equals `0.3`. Kotlin's parser alone also takes `0x1p3`,
     * `NaN` and `Infinity`, which Tasker doesn't.
     */
    private fun taskerNumber(text: String): Double? {
        // Java's \s, which Tasker strips: space, tab, line feed, vertical tab, form feed, return.
        val compact = text.filterNot { it == ' ' || it in '\t'..'\r' }
        val value = when {
            compact.startsWith("0x", ignoreCase = true) -> compact.substring(2).toLongOrNull(16)?.toDouble()
            TASKER_NUMBER.matches(compact) -> compact.toDouble()
            else -> null
        }
        return value?.takeIf { it.isFinite() }
            ?.let { BigDecimal(it.toString()).setScale(TASKER_DECIMAL_PLACES, RoundingMode.HALF_UP).toDouble() }
    }

    private fun evaluateJsonPath(json: String, path: String): String {
        // org.json recurses per level and a StackOverflowError gets past the catch below.
        if (JsonNesting.lenientDepth(json) > JsonNesting.MAX_DATA_DEPTH) return ""
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

    private fun isJsonPathSegmentChar(c: Char): Boolean = c.isLetterOrDigit() || c == '_' || c == '-'

    /**
     * [unset] marks a plain `%name` that has no value, which [value] fills in as nothing, and
     * [operator] a `%name(...)` form.
     */
    private data class TokenExpansion(
        val value: String,
        val nextIndex: Int,
        val unset: Boolean = false,
        val operator: Boolean = false,
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
        MATCHES_REGEX("~R"),
        NOT_MATCHES_REGEX("!~R"),
    }

    companion object {
        // (suffix, isSetWhenTrue) — order matters: "not_set"/"!is_set" style negatives must be
        // checked before a shorter positive suffix could partially match, though with these two
        // literal strings neither is a suffix of the other, so this is just future-proofing.
        private val UNARY_EXISTENCE_SUFFIXES = listOf(
            " is_set" to true,
            " not_set" to false,
        )
        // (suffix, isEvenWhenTrue)
        private val UNARY_PARITY_SUFFIXES = listOf(
            " is_even" to true,
            " is_odd" to false,
        )
        private val COMPARISON_OPERATORS = listOf(
            ComparisonOperator.EQ,
            ComparisonOperator.NE,
            ComparisonOperator.LE,
            ComparisonOperator.GE,
            ComparisonOperator.LT,
            ComparisonOperator.NOT_MATCHES_REGEX,
            ComparisonOperator.NOT_MATCHES,
            ComparisonOperator.MATCHES_REGEX,
            ComparisonOperator.MATCHES,
            ComparisonOperator.GT,
        )
        private val TILDE_OPERATORS = setOf(
            ComparisonOperator.MATCHES,
            ComparisonOperator.NOT_MATCHES,
            ComparisonOperator.MATCHES_REGEX,
            ComparisonOperator.NOT_MATCHES_REGEX,
        )

        /** The comparisons Tasker works out as maths (its condition ops 6 and 7, and their or-equal forms). */
        private val NUMERIC_OPERATORS = setOf(
            ComparisonOperator.LT,
            ComparisonOperator.GT,
            ComparisonOperator.LE,
            ComparisonOperator.GE,
        )
        // [0-9], not \d, which Android's ICU regex matches against every script's digits.
        private val TASKER_NUMBER = Regex("""[+-]?(?:[0-9]+(?:\.[0-9]*)?|\.[0-9]+)(?:[eE][0-9]+)?[fFdD]?""")
        private const val TASKER_DECIMAL_PLACES = 10
        private const val MAX_REGEX_LENGTH = 256
        private const val MAX_REGEX_INPUT_LENGTH = 10_000
        private val PATTERN_WILDCARD = Regex("[*+]")
        private val TASKER_ARRAY_FORM = Regex("""#[<>]?|[<>]|\d*(?::\d*)?""")
        private val STORED_ARRAY_FORM = Regex("""#|\d*(?::\d*)?""")

        private fun isVariableNameChar(c: Char): Boolean = c.isLetterOrDigit() || c == '_' || c == '-'

        /**
         * True when [written] is a lone `%name` and [expanded] is that same text, which is how an
         * unset variable comes back. A caller that needs a value, such as an array position or a
         * goto target, treats it as empty. A variable holding its own name reads the same.
         */
        fun keptUnset(written: String?, expanded: String?): Boolean {
            val text = written?.trim().orEmpty()
            return text.length > 1 && text[0] == '%' && text[1].isLetter() &&
                text.drop(1).all(::isVariableNameChar) && expanded?.trim() == text
        }

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

    /** Drops the array [name], returning whether there was one. */
    fun remove(name: String): Boolean = synchronized(lock) { arrays.remove(name) != null }

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
