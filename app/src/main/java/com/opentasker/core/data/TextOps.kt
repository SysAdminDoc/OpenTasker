package com.opentasker.core.data

import com.google.re2j.Pattern as Re2Pattern

/**
 * Deterministic text transforms for the `text.*` actions. Regex uses linear-time RE2 (no
 * catastrophic backtracking) with bounded pattern/input sizes. Functions return null on invalid
 * input so the actions fail closed.
 */
object TextOps {
    const val MAX_PATTERN_CHARS = 256
    const val MAX_INPUT_CHARS = 100_000

    /**
     * Find the first match of [pattern] in [source]. Returns [full match, group1, group2, ...], an
     * empty list when there is no match, or null when the pattern/input is invalid.
     */
    fun match(source: String, pattern: String): List<String>? {
        if (source.length > MAX_INPUT_CHARS) return null
        val compiled = compile(pattern) ?: return null
        val matcher = compiled.matcher(source)
        if (!matcher.find()) return emptyList()
        return (0..matcher.groupCount()).map { matcher.group(it) ?: "" }
    }

    /** Replace every match of [pattern] with [replacement] (supports `$1` group refs). */
    fun replaceAll(source: String, pattern: String, replacement: String): String? {
        if (source.length > MAX_INPUT_CHARS) return null
        val compiled = compile(pattern) ?: return null
        return runCatching { compiled.matcher(source).replaceAll(replacement) }.getOrNull()
    }

    /** Replace only the first match of [pattern] with [replacement] (supports `$1` group refs). */
    fun replaceFirst(source: String, pattern: String, replacement: String): String? {
        if (source.length > MAX_INPUT_CHARS) return null
        val compiled = compile(pattern) ?: return null
        return runCatching { compiled.matcher(source).replaceFirst(replacement) }.getOrNull()
    }

    /**
     * The whole text of each match of [pattern] in [source], in order and at most [limit] of them,
     * or null when the pattern/input is invalid.
     */
    fun findAll(source: String, pattern: String, limit: Int): List<String>? {
        if (source.length > MAX_INPUT_CHARS) return null
        val compiled = compile(pattern) ?: return null
        val matcher = compiled.matcher(source)
        val found = mutableListOf<String>()
        while (found.size < limit && matcher.find()) found += matcher.group().orEmpty()
        return found
    }

    /** Split [source] by a literal [delimiter] or, when [isRegex], a regex delimiter. */
    fun split(source: String, delimiter: String, isRegex: Boolean): List<String>? {
        if (source.length > MAX_INPUT_CHARS) return null
        return if (isRegex) {
            compile(delimiter)?.split(source)?.toList()
        } else {
            if (delimiter.isEmpty()) null else source.split(delimiter)
        }
    }

    fun join(items: List<String>, delimiter: String): String = items.joinToString(delimiter)

    /** Substring with clamped, forgiving bounds; [end] null means to the end of the string. */
    fun substring(source: String, start: Int, end: Int?): String {
        val from = start.coerceIn(0, source.length)
        val to = (end ?: source.length).coerceIn(from, source.length)
        return source.substring(from, to)
    }

    private fun compile(pattern: String): Re2Pattern? {
        if (pattern.isEmpty() || pattern.length > MAX_PATTERN_CHARS) return null
        return runCatching { Re2Pattern.compile(pattern) }.getOrNull()
    }
}
