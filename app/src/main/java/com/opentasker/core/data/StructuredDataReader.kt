package com.opentasker.core.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.jsoup.Jsoup
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.xml.sax.InputSource
import com.opentasker.core.transfer.ImportResourceGuard
import com.opentasker.core.transfer.applyImportHardening

/**
 * Deterministic, on-device parser that turns a JSON / CSV / XML / HTML string into one or more variable
 * values, without any network or cloud dependency. Used by the `data.read` action to make raw HTTP
 * responses and file contents usable in automations.
 *
 * All inputs are bounded, and any parse failure or unresolved selector returns null so the action
 * fails closed.
 */
object StructuredDataReader {
    const val MAX_INPUT_CHARS = 1_000_000
    const val MAX_RESULT_VALUES = 5_000

    /** Result of a read: an ordered list of extracted string values (may be empty). */
    data class ReadResult(val values: List<String>)

    fun read(format: String, source: String, path: String): ReadResult? {
        if (source.length > MAX_INPUT_CHARS) return null
        val values = when (format.trim().lowercase()) {
            "json" -> readJson(source, path)
            "csv" -> readCsv(source, path)
            "xml" -> readXml(source, path)
            "html" -> readHtml(source, path)
            else -> null
        } ?: return null
        return ReadResult(values.take(MAX_RESULT_VALUES))
    }

    // ---- JSON ----

    private val json = Json { ignoreUnknownKeys = true }

    private fun readJson(source: String, path: String): List<String>? {
        val root = runCatching { json.parseToJsonElement(source) }.getOrNull() ?: return null
        val target = navigateJson(root, path) ?: return null
        return when (target) {
            is JsonArray -> target.map { it.asPlainString() }
            else -> listOf(target.asPlainString())
        }
    }

    private fun navigateJson(root: JsonElement, path: String): JsonElement? {
        val selectors = parseSelectors(path) ?: return null
        var current = root
        for (selector in selectors) {
            current = when (selector) {
                is Selector.Property -> (current as? JsonObject)?.get(selector.name) ?: return null
                is Selector.Index -> (current as? JsonArray)?.getOrNull(selector.index) ?: return null
            }
        }
        return current
    }

    private fun JsonElement.asPlainString(): String = when (this) {
        is JsonPrimitive -> content
        else -> toString()
    }

    // ---- CSV ----
    //
    // path "" or "*"  -> every cell, row-major
    // path "c"        -> column c across all rows
    // path "r,c"      -> single cell at row r, column c
    private fun readCsv(source: String, path: String): List<String>? {
        val rows = parseCsvRows(source)
        if (rows.isEmpty()) return emptyList()

        val trimmed = path.trim()
        if (trimmed.isEmpty() || trimmed == "*") return rows.flatten()

        val parts = trimmed.split(',').map { it.trim() }
        return when (parts.size) {
            1 -> {
                val col = parts[0].toIntOrNull() ?: return null
                if (col < 0) return null
                rows.mapNotNull { it.getOrNull(col) }
            }
            2 -> {
                val row = parts[0].toIntOrNull() ?: return null
                val col = parts[1].toIntOrNull() ?: return null
                val cell = rows.getOrNull(row)?.getOrNull(col) ?: return null
                listOf(cell)
            }
            else -> null
        }
    }

    /**
     * RFC 4180-style CSV row parser: double-quoted fields may contain commas, embedded
     * newlines, and doubled quotes (""). Unquoted fields are trimmed; quoted fields keep
     * their content verbatim. A naive split(',') returned wrong cells for any quoted CSV.
     */
    internal fun parseCsvRows(source: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        val fields = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false
        var fieldWasQuoted = false
        var index = 0

        fun endField() {
            fields.add(if (fieldWasQuoted) current.toString() else current.toString().trim())
            current.setLength(0)
            fieldWasQuoted = false
        }

        fun endRow() {
            endField()
            if (fields.size > 1 || fields.first().isNotEmpty()) rows.add(fields.toList())
            fields.clear()
        }

        while (index < source.length) {
            val ch = source[index]
            when {
                inQuotes -> when {
                    ch == '"' && index + 1 < source.length && source[index + 1] == '"' -> {
                        current.append('"')
                        index++
                    }
                    ch == '"' -> inQuotes = false
                    else -> current.append(ch)
                }
                ch == '"' && current.isBlank() -> {
                    inQuotes = true
                    fieldWasQuoted = true
                    current.setLength(0)
                }
                ch == ',' -> endField()
                ch == '\r' -> if (index + 1 >= source.length || source[index + 1] != '\n') endRow()
                ch == '\n' -> endRow()
                else -> current.append(ch)
            }
            index++
        }
        if (current.isNotEmpty() || fields.isNotEmpty()) endRow()
        return rows
    }

    // ---- XML ----
    //
    // path is a slash-separated element name path, e.g. "root/item/name". Returns the text content
    // of every element matching the full path.
    private fun readXml(source: String, path: String): List<String>? {
        val names = path.trim().trim('/').split('/').map { it.trim() }.filter { it.isNotEmpty() }
        if (names.isEmpty()) return null
        // DOCTYPE is rejected in text first, because the Apache feature below cannot be relied on:
        // Android's Harmony/Expat factory throws SAXNotRecognizedException for that URI, and since
        // the throw happened inside runCatching every XML read failed on device while desktop
        // Xerces kept the JVM tests green. This is the same defect that broke Tasker XML import
        // (issue #5); the sanitizer is the enforcement, the feature is belt and braces.
        val sanitized = runCatching { ImportResourceGuard.sanitizeTaskerXml(source) }.getOrNull()
            ?: return null
        val doc = runCatching {
            val factory = DocumentBuilderFactory.newInstance().apply {
                applyImportHardening()
                isExpandEntityReferences = false
                isNamespaceAware = false
            }
            factory.newDocumentBuilder().parse(InputSource(sanitized.reader()))
        }.getOrNull() ?: return null

        val rootEl = doc.documentElement ?: return null
        if (!rootEl.tagName.equals(names.first(), ignoreCase = false)) return emptyList()
        var current = listOf(rootEl)
        for (name in names.drop(1)) {
            current = current.flatMap { el -> el.childElements().filter { it.tagName == name } }
        }
        return current.map { it.textContent.trim() }
    }

    private fun Element.childElements(): List<Element> {
        val out = mutableListOf<Element>()
        val children = childNodes
        for (i in 0 until children.length) {
            (children.item(i) as? Element)?.let { out.add(it) }
        }
        return out
    }

    // ---- HTML ----
    //
    // path is a bounded CSS selector. Matching elements yield their normalized text content.
    // Jsoup parses only the supplied string; this action never follows links or performs I/O.
    private fun readHtml(source: String, path: String): List<String>? {
        val selector = path.trim()
        if (selector.isEmpty() || selector.length > MAX_SELECTOR_CHARS) return null
        if (unsupportedHtmlSelectorReason(selector) != null) return null
        val document = runCatching { Jsoup.parse(source) }.getOrNull() ?: return null
        return runCatching { document.select(selector).map { it.text() } }.getOrNull()
    }

    /**
     * Names the reason a CSS selector is refused, or null when it is fine to run.
     *
     * Jsoup compiles the regex in `:matches()`, `:matchesOwn()`, `:matchesWholeText()`,
     * `:matchesWholeOwnText()` and `[attr~=regex]` with `java.util.regex`, which backtracks. Against
     * a body of up to [MAX_INPUT_CHARS] a pattern like `:matches((a+)+$)` runs for effectively
     * forever inside a plain loop, and `TaskRunner`'s `withTimeout` cannot interrupt a
     * non-suspending call, so the action would hang the task rather than fail it.
     *
     * Pre-validating the embedded pattern through RE2 would not help: RE2 accepts `(a+)+$`
     * perfectly happily, and jsoup would still go on to match it with `java.util.regex`. The
     * pattern has to not reach jsoup at all, which is why these constructs are refused outright
     * rather than checked. Everything else in the selector grammar is bounded, so ordinary CSS is
     * unaffected. The same reasoning is why `TextOps` uses RE2 for user patterns it evaluates
     * itself.
     */
    fun unsupportedHtmlSelectorReason(selector: String): String? {
        // A backslash starts a CSS escape, and jsoup decodes those before it matches a
        // pseudo-selector name: `:mat\63 hes(...)` selects exactly what `:matches(...)` selects.
        // A substring check runs against the undecoded text and sees neither, so an escape was a
        // complete bypass of everything below. Rather than reimplement CSS unescaping and get it
        // subtly wrong, refuse the escape itself. Nothing in a bounded content selector needs one.
        if (selector.contains('\\')) {
            return "HTML selector contains a backslash escape, which cannot be checked safely; " +
                "write the selector without escapes"
        }

        val lowered = selector.lowercase()
        REGEX_PSEUDO_SELECTORS.firstOrNull { lowered.contains("$it(") }?.let {
            return "HTML selector uses $it(), which matches with a backtracking regular expression; " +
                "use a plain CSS selector instead"
        }
        // Quoted attribute values are data, not syntax: `[title="a~=b"]` is an ordinary exact match
        // on a value that happens to contain the operator's characters. Blanking quoted runs before
        // looking for the operator keeps that selector working.
        if (ATTRIBUTE_REGEX_OPERATOR.containsMatchIn(withoutQuotedValues(lowered))) {
            return "HTML selector uses the [attr~=regex] operator, which matches with a backtracking " +
                "regular expression; use [attr], [attr=value], [attr^=], [attr\$=] or [attr*=] instead"
        }
        return null
    }

    /** Replaces the contents of single- and double-quoted runs with `x`, preserving length. */
    private fun withoutQuotedValues(selector: String): String {
        val out = StringBuilder(selector.length)
        var quote: Char? = null
        selector.forEach { ch ->
            when {
                quote == null && (ch == '"' || ch == '\'') -> {
                    quote = ch
                    out.append(ch)
                }
                quote != null && ch == quote -> {
                    quote = null
                    out.append(ch)
                }
                quote != null -> out.append('x')
                else -> out.append(ch)
            }
        }
        return out.toString()
    }

    // ---- selectors ----

    private sealed interface Selector {
        data class Property(val name: String) : Selector
        data class Index(val index: Int) : Selector
    }

    /** Parses a JSON path like `items[0].name` (leading '.' optional) into selectors. */
    private fun parseSelectors(path: String): List<Selector>? {
        val trimmed = path.trim().removePrefix(".")
        if (trimmed.isEmpty()) return emptyList()
        val selectors = mutableListOf<Selector>()
        var cursor = 0
        // optional leading bare property
        cursor = readProperty(trimmed, cursor, selectors) ?: return null
        while (cursor < trimmed.length) {
            when (trimmed[cursor]) {
                '.' -> {
                    cursor = readProperty(trimmed, cursor + 1, selectors) ?: return null
                }
                '[' -> {
                    val close = trimmed.indexOf(']', cursor + 1)
                    if (close == -1) return null
                    val index = trimmed.substring(cursor + 1, close).trim().toIntOrNull() ?: return null
                    if (index < 0) return null
                    selectors += Selector.Index(index)
                    cursor = close + 1
                }
                else -> return null
            }
        }
        return selectors
    }

    private fun readProperty(path: String, start: Int, out: MutableList<Selector>): Int? {
        var cursor = start
        while (cursor < path.length && (path[cursor].isLetterOrDigit() || path[cursor] == '_' || path[cursor] == '-')) {
            cursor++
        }
        if (cursor == start) return if (start == 0) start else null // allow leading '[' with no property
        out += Selector.Property(path.substring(start, cursor))
        return cursor
    }

    private const val MAX_SELECTOR_CHARS = 512

    /**
     * The jsoup pseudo-selectors whose evaluator holds a `java.util.regex.Pattern`, read off
     * jsoup 1.23.2 itself (`Evaluator$Matches`, `$MatchesOwn`, `$MatchesWholeText`,
     * `$MatchesWholeOwnText`) rather than from documentation.
     */
    private val REGEX_PSEUDO_SELECTORS = listOf(
        ":matcheswholeowntext",
        ":matcheswholetext",
        ":matchesown",
        ":matches",
    )

    /**
     * `[attr~=regex]` (`Evaluator$AttributeWithValueMatching`). Written to match the operator only
     * inside an attribute selector, so a `~` used as a sibling combinator elsewhere is untouched.
     */
    private val ATTRIBUTE_REGEX_OPERATOR = Regex("""\[[^\]]*~=""")
}
