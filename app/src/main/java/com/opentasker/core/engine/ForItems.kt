package com.opentasker.core.engine

import com.opentasker.core.model.VariableNamePolicy

/**
 * Tasker's For items: a comma-separated mix of values, `%var`, array parts like `%arr()` or
 * `%arr(1:2)`, and number ranges `a:b` or `a:b:jump` (`4:0` counts down, `8:2:-2` is 8, 6, 4, 2,
 * `2:%end:%skip` and `1:%arr(#)` read their ends from variables).
 *
 * A whole array (`%arr()` or `{{ array.arr }}`) gives its elements as they are, and nothing when
 * there's no such array, the way a For Each naming it does. Any other piece has its variables and
 * `{{ }}` references filled in by [resolve]'s `expand` and is then split on commas, as Tasker does,
 * so a variable holding `a,b` gives two items. A range's direction comes from its ends and the jump
 * only sets the size of each step. The items are worked out once, when the loop starts; Tasker works
 * them out again on each pass, which only differs when the body changes what they read.
 */
internal object ForItems {

    data class Resolved(val items: List<String>, val sensitive: Boolean, val problem: String? = null)

    /** One piece with its references filled in, and whether a secret went into it. */
    class Expanded(val text: String, val secret: Boolean)

    private const val NAME = "[A-Za-z][A-Za-z0-9_-]*"
    private val WHOLE_ARRAY = Regex("""%($NAME)\(\)|\{\{\s*array\.($NAME)\s*\}\}""")
    private val RANGE = Regex("""([+-]?\d+)\s*:\s*([+-]?\d+)(?:\s*:\s*([+-]?\d+))?""")

    // 15 digits a side keeps the arithmetic below inside a Long.
    private const val MAX_RANGE_NUMBER = 999_999_999_999_999L

    fun resolve(raw: String, variables: VariableStore, expand: (String) -> Expanded): Resolved {
        val parts = mutableListOf<List<String>>()
        var sensitive = false
        var size = 0L
        fun problem(message: String) = Resolved(emptyList(), sensitive, "For \"$raw\": $message")

        for (piece in topLevelPieces(raw)) {
            val arrayName = WHOLE_ARRAY.matchEntire(piece)?.groupValues?.drop(1)?.first(String::isNotEmpty)
            val found = if (arrayName != null) {
                sensitive = sensitive || variables.isArraySensitive(arrayName)
                listOf(variables.getArrayItems(arrayName).orEmpty())
            } else {
                val expanded = expand(piece)
                sensitive = sensitive || expanded.secret
                expanded.text.split(',').map(String::trim).filter(String::isNotEmpty).map { item ->
                    val match = RANGE.matchEntire(item) ?: return@map listOf(item)
                    val numbers = match.groupValues.drop(1).filter(String::isNotEmpty).map { it.toLongOrNull() }
                    if (numbers.any { it == null || it !in -MAX_RANGE_NUMBER..MAX_RANGE_NUMBER }) {
                        return problem("a range's numbers can't be longer than 15 digits")
                    }
                    val from = numbers[0]!!
                    val to = numbers[1]!!
                    val jump = Math.abs(numbers.getOrNull(2) ?: 1L)
                    if (jump == 0L) return problem("a range can't count in steps of 0")
                    val count = Math.abs(to - from) / jump + 1
                    if (count > Int.MAX_VALUE) return problem("a range has more items than a loop can count")
                    RangeItems(from, if (to < from) -jump else jump, count.toInt())
                }
            }
            for (part in found) {
                size += part.size
                if (size > Int.MAX_VALUE) return problem("there are more items than a loop can count")
                // Values next to each other share one list, so finding an item doesn't walk every value.
                if (part is RangeItems) parts += part else (parts.lastOrNull() as? Values ?: Values().also(parts::add)).addAll(part)
            }
        }
        return Resolved(JoinedItems(parts, size.toInt()), sensitive)
    }

    /** The array a For Each's list field names: `{{ array.x }}` the way its chip writes it, or the text as typed. */
    fun listName(raw: String): String =
        TEMPLATE_ARRAY.matchEntire(raw.trim())?.groupValues?.get(1)?.let(VariableNamePolicy::normalize) ?: raw

    private val TEMPLATE_ARRAY = Regex("""\{\{\s*array\.($NAME)\s*\}\}""")

    /**
     * [raw] split on the commas outside an array's `%v( )` and a closed `{{ }}`, so `%v(split:,)` and
     * `{{ x | default:a,b }}` each stay one piece. A `(` or `{{` that's only text, like `sad :(`,
     * doesn't hold the commas after it.
     */
    private fun topLevelPieces(raw: String): List<String> {
        val pieces = mutableListOf<String>()
        var start = 0
        var index = 0
        while (index < raw.length) {
            val past = when {
                raw.startsWith("{{", index) -> raw.indexOf("}}", index + 2).takeIf { it >= 0 }?.plus(2)
                raw[index] == '%' -> arrayCallEnd(raw, index)
                else -> null
            }
            if (past != null) {
                index = past
                continue
            }
            if (raw[index] == ',') {
                pieces += raw.substring(start, index)
                start = index + 1
            }
            index++
        }
        pieces += raw.substring(start)
        return pieces.map(String::trim).filter(String::isNotEmpty)
    }

    /** Where `%name(...)` starting at [at] ends, counting nested parentheses, or null when it isn't one or never closes. */
    private fun arrayCallEnd(raw: String, at: Int): Int? {
        fun isNameChar(c: Char) = c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '_' || c == '-'
        var index = at + 1
        if (index >= raw.length || !(raw[index] in 'A'..'Z' || raw[index] in 'a'..'z')) return null
        while (index < raw.length && isNameChar(raw[index])) index++
        if (index >= raw.length || raw[index] != '(') return null
        var depth = 0
        while (index < raw.length) {
            when (raw[index]) {
                '(' -> depth++
                ')' -> if (--depth == 0) return index + 1
            }
            index++
        }
        return null
    }

    private class Values : ArrayList<String>()

    /** A range's numbers, made one at a time so `1:1000000` doesn't hold a million strings. */
    private class RangeItems(private val from: Long, private val step: Long, override val size: Int) : AbstractList<String>() {
        override fun get(index: Int): String {
            if (index !in 0 until size) throw IndexOutOfBoundsException("$index of $size")
            return (from + step * index).toString()
        }
    }

    private class JoinedItems(private val parts: List<List<String>>, override val size: Int) : AbstractList<String>() {
        override fun get(index: Int): String {
            if (index !in 0 until size) throw IndexOutOfBoundsException("$index of $size")
            var rest = index
            for (part in parts) {
                if (rest < part.size) return part[rest]
                rest -= part.size
            }
            throw IndexOutOfBoundsException("$index of $size")
        }
    }
}
