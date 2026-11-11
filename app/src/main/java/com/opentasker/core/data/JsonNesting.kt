package com.opentasker.core.data

/**
 * kotlinx's `JsonTreeReader` reads arrays recursively (only objects past 200 levels switch to
 * `DeepRecursiveFunction`), so a few thousand nested `[` throw StackOverflowError inside
 * `parseToJsonElement`. Text a server, a plugin or a variable supplies gets its depth counted here
 * first, and anything deeper than the caller's limit is refused before parsing.
 */
object JsonNesting {
    /** Depth allowed for JSON a task reads (HTTP bodies, variables). Real data stays far below it. */
    const val MAX_DATA_DEPTH = 256

    /** What [depth] returns for text kotlinx refuses, which it can still recurse deep into first. */
    const val NOT_JSON = Int.MAX_VALUE

    /**
     * How deep `{` and `[` nest outside strings. kotlinx also keeps reading values after a `]` or
     * `}`, so `[0][0]` nests while the count stays flat: anything but `,`, `]` or `}` after a close
     * counts as [NOT_JSON]. Only JSON's own four whitespace characters are skipped there,
     * since kotlinx reads a Unicode space as the start of a value. Brackets that don't balance or a
     * string left open count as [NOT_JSON] too, since kotlinx reads down an unclosed `[[[` before
     * it fails.
     */
    fun depth(text: String): Int {
        var depth = 0
        var deepest = 0
        var inString = false
        var escaped = false
        var closed = false
        for (char in text) {
            if (closed && !inString && char !in " \t\n\r") {
                if (char != ',' && char != ']' && char != '}') return NOT_JSON
                closed = false
            }
            when {
                escaped -> escaped = false
                inString -> if (char == '\\') escaped = true else if (char == '"') inString = false
                char == '"' -> inString = true
                char == '{' || char == '[' -> deepest = maxOf(deepest, ++depth)
                char == '}' || char == ']' -> {
                    if (--depth < 0) return NOT_JSON
                    closed = true
                }
            }
        }
        return if (depth != 0 || inString) NOT_JSON else deepest
    }

    fun within(text: String, maxDepth: Int): Boolean = depth(text) <= maxDepth

    /**
     * How deep Android's `org.json` reader nests on [text], which recurses too. It's lenient, so the
     * count follows its tokenizer: `'` strings as well as `"`, `/* */`, `//` and `#` comments, and
     * a quote inside an unquoted word is part of the word. It stops after the first value, so text
     * after that value closes isn't counted. Past a syntax error the count can drift, but the
     * reader has already stopped there.
     */
    fun lenientDepth(text: String): Int {
        var depth = 0
        var deepest = 0
        var quote = NO_QUOTE
        var inWord = false
        var index = 0
        while (index < text.length) {
            val char = text[index]
            when {
                quote != NO_QUOTE -> if (char == '\\') index++ else if (char == quote) quote = NO_QUOTE
                char == '/' && text.startsWith("/*", index) -> {
                    val end = text.indexOf("*/", index + 2)
                    if (end < 0) return deepest
                    index = end + 1
                    inWord = false
                }
                char == '#' || (char == '/' && text.startsWith("//", index)) -> {
                    while (index < text.length && text[index] != '\n' && text[index] != '\r') index++
                    inWord = false
                }
                // org.json skips a `>` right after a key's `:` or `=`, so `=>` and `:>` both separate.
                char == '>' && index > 0 && (text[index - 1] == '=' || text[index - 1] == ':') -> inWord = false
                char in WORD_ENDS -> {
                    inWord = false
                    if (char == '{' || char == '[') {
                        deepest = maxOf(deepest, ++depth)
                    } else if ((char == '}' || char == ']') && --depth <= 0) {
                        return deepest
                    }
                }
                inWord -> Unit
                char == '"' || char == '\'' -> quote = char
                else -> inWord = true
            }
            index++
        }
        return deepest
    }

    private const val NO_QUOTE = ' '

    /** What ends an unquoted word in `org.json`: its delimiters plus its whitespace. */
    private const val WORD_ENDS = "{}[]/\\:,=;# \t\n\r\u000C"
}
