package com.opentasker.core.engine

import com.opentasker.core.engine.variables.ArrayStore
import com.opentasker.core.engine.variables.VariableExpander
import com.opentasker.core.expressions.InertTemplateText
import com.opentasker.core.expressions.TemplateExpressionEngine
import com.opentasker.core.expressions.TemplateScope
import com.opentasker.core.model.VariableNamePolicy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

data class TrackedExpansion(
    /** Ready for the template engine: a `{{` that came out of a variable's value stays inert. */
    val value: String,
    val isSecretDerived: Boolean,
) {
    /** The finished text, when no template stage follows. */
    val text: String get() = InertTemplateText.restore(value)
}

/**
 * In-memory variable store with a global scope and stack of local scopes.
 * Thread-safe for concurrent read/write access.
 *
 * Naming convention (matches Tasker):
 *   - a name containing any uppercase letter → global, persistent
 *   - an all-lowercase name → local to the current task invocation
 *
 * Enhanced with operator support:
 *   - Math: %VAR(+5), %VAR(*2), %VAR(//), %VAR(/round)
 *   - Strings: %VAR(upper), %VAR(lower), %VAR(trim), %VAR(substring:0:5)
 *   - Linear-time regex: %VAR(regex:pattern:group), %VAR(replace:pattern:replacement)
 *   - Arrays: %list(#), %list(1), %list()
 *   - JSON: %json.path.to.field
 */
class VariableStore {
    private val globals = ConcurrentHashMap<String, String>()
    private val rootLocals = ConcurrentHashMap<String, String>()
    private val localStack = java.util.Collections.synchronizedList(mutableListOf<MutableMap<String, String>>())
    private val globalSensitiveNames = ConcurrentHashMap.newKeySet<String>()
    private val declaredSecretGlobals = ConcurrentHashMap.newKeySet<String>()
    private val rootLocalSensitiveNames = ConcurrentHashMap.newKeySet<String>()
    private val localSensitiveStack = java.util.Collections.synchronizedList(mutableListOf<MutableSet<String>>())
    private val sensitiveArrayNames = ConcurrentHashMap.newKeySet<String>()
    private val sensitiveWriteDepth = AtomicInteger(0)
    private val expander = VariableExpander()
    private val arrayStore = ArrayStore()

    fun pushScope() {
        localStack.add(java.util.concurrent.ConcurrentHashMap())
        localSensitiveStack.add(ConcurrentHashMap.newKeySet())
    }
    fun popScope() {
        synchronized(localStack) {
            if (localStack.isNotEmpty()) {
                localStack.removeAt(localStack.size - 1)
                localSensitiveStack.removeAt(localSensitiveStack.size - 1)
            }
        }
    }

    fun set(name: String, value: String, sensitive: Boolean = false) {
        val normalizedName = VariableNamePolicy.normalize(name) ?: return
        val shouldRemainSensitive = sensitive || sensitiveWriteDepth.get() > 0 || isSensitive(normalizedName)
        if (VariableNamePolicy.isGlobal(normalizedName)) {
            // Global taint is monotonic: once a global is declared-secret or tainted it stays
            // sensitive for the life of the store. The flag is set BEFORE the value is published and
            // is never cleared here, so a concurrent plain write from another parallel run on the
            // same global cannot race the flag off and leak the value — the previous read-then-clear
            // could drop a sensitive flag another thread had just set.
            if (shouldRemainSensitive || normalizedName in declaredSecretGlobals) {
                globalSensitiveNames += normalizedName
            }
            globals[normalizedName] = value
        } else {
            synchronized(localStack) {
                val target = localStack.lastOrNull()
                if (target == null) {
                    rootLocals[normalizedName] = value
                    updateSensitivity(rootLocalSensitiveNames, normalizedName, shouldRemainSensitive)
                } else {
                    target[normalizedName] = value
                    updateSensitivity(localSensitiveStack.last(), normalizedName, shouldRemainSensitive)
                }
            }
        }
    }

    fun get(name: String): String? {
        val normalizedName = VariableNamePolicy.normalize(name) ?: return null
        synchronized(localStack) {
            for (i in localStack.indices.reversed()) {
                localStack[i][normalizedName]?.let { return it }
            }
        }
        return rootLocals[normalizedName] ?: globals[normalizedName]
    }

    fun isSensitive(name: String): Boolean {
        val normalizedName = VariableNamePolicy.normalize(name) ?: return false
        synchronized(localStack) {
            for (index in localStack.indices.reversed()) {
                if (normalizedName in localStack[index]) return normalizedName in localSensitiveStack[index]
            }
        }
        if (rootLocals.containsKey(normalizedName)) return normalizedName in rootLocalSensitiveNames
        return normalizedName in globalSensitiveNames
    }

    /**
     * Seed the global scope with previously persisted values before a run starts. Only affects the
     * global namespace; local task scopes are untouched.
     */
    fun seedGlobals(values: Map<String, String>, secretNames: Set<String> = emptySet()) {
        values.forEach { (rawName, value) ->
            VariableNamePolicy.promoteToGlobal(rawName)?.let { name -> globals[name] = value }
        }
        secretNames.mapNotNullTo(declaredSecretGlobals, VariableNamePolicy::promoteToGlobal)
        globalSensitiveNames += declaredSecretGlobals
    }

    /** Snapshot of the current global scope, used to persist durable globals after a run. */
    fun globalSnapshot(): Map<String, String> = globals.toMap()

    /** Secret/taint metadata paired with [globalSnapshot] for encrypted durable persistence. */
    fun globalSensitiveSnapshot(): Set<String> = globalSensitiveNames.toSet()

    /**
     * Store an array in the array storage.
     * Arrays can be accessed via %arrayName(#) for length, %arrayName(0) for index, etc.
     */
    fun setArray(name: String, values: List<String>, sensitive: Boolean = false) {
        // Array taint is monotonic for the same reason global taint is (see [set]): mark before
        // publishing and never clear, so a later plain write can't race a secret array's flag off.
        if (sensitive || sensitiveWriteDepth.get() > 0 || name in sensitiveArrayNames) {
            sensitiveArrayNames += name
        }
        arrayStore.put(name, values)
    }

    /**
     * Returns the elements of a stored array by name, or null if no array with that name exists.
     * Used by the `flow.foreach` control action to iterate over array variables.
     */
    fun getArrayItems(name: String): List<String>? =
        arrayStore.snapshot()[name]

    fun isArraySensitive(name: String): Boolean = name in sensitiveArrayNames

    /** Expand all variable references in [s] using the current scope chain. */
    fun expand(s: String): String {
        return expander.expand(s, this, arrayStore)
    }

    /** Expands a legacy expression while retaining whether any referenced input was secret. */
    fun expandTracked(s: String): TrackedExpansion = TrackedExpansion(
        value = expander.expand(s, this, arrayStore, InertTemplateText::hide),
        isSecretDerived = variableReference.findAll(s).any { match ->
            val name = match.groupValues[1]
            isSensitive(name) || name in sensitiveArrayNames
        },
    )

    /**
     * Expand with operator support. Examples:
     * - "%VAR(+5)" → parse VAR as number, add 5
     * - "%VAR(upper)" → uppercase VAR
     * - "%VAR(regex:(\d+):1)" → extract first digit group
     * - "(x > 5) ? yes : no" → conditional
     */
    fun expandWithOperators(expr: String): String {
        return expand(expr)
    }

    fun evaluateCondition(expr: String): Boolean {
        return expander.evaluateCondition(expr, this, arrayStore)
    }

    /**
     * Evaluates a condition the way a run does: its structure is read from [expr] as typed, then
     * each operand goes through legacy `%var` expansion and the bounded `{{ ... }}` stage once.
     * Pass [expr] unexpanded, because a value that has already been substituted would be parsed
     * as part of the condition.
     */
    fun evaluateCondition(
        expr: String,
        templates: TemplateExpressionEngine,
        eventVariables: Map<String, String> = emptyMap(),
    ): Boolean {
        val scope by lazy(LazyThreadSafetyMode.NONE) { toTemplateScope(eventVariables) }
        // A template the engine can't read fails the whole condition, whichever branch holds it.
        if (expr.contains("{{") && templates.expand(expandTracked(expr).value, scope).warnings.isNotEmpty()) {
            return false
        }
        return expander.evaluateCondition(expr, this, arrayStore) { operand ->
            val legacy = expandTracked(operand)
            if (!legacy.value.contains("{{")) {
                legacy.text
            } else {
                InertTemplateText.restore(templates.expand(legacy.value, scope).value)
            }
        }
    }

    fun toTemplateScope(event: Map<String, String> = emptyMap()): TemplateScope {
        val taskValues = rootLocals.toMutableMap()
        synchronized(localStack) {
            localStack.forEach { scope -> taskValues += scope }
        }
        return TemplateScope(
            global = globals.toMap(),
            task = taskValues.toMap(),
            event = event.toMap(),
            arrays = arrayStore.snapshot(),
            sensitiveGlobal = globalSensitiveNames.toSet(),
            sensitiveTask = synchronized(localStack) {
                localSensitiveStack.flatMapTo(rootLocalSensitiveNames.toMutableSet()) { it }
            },
            sensitiveArrays = sensitiveArrayNames.toSet(),
            sensitiveEvent = event.keys.filterTo(mutableSetOf(), EventTaskVariables::isSensitive),
        )
    }

    suspend fun <T> withSensitiveWrites(sensitive: Boolean, block: suspend () -> T): T {
        if (!sensitive) return block()
        sensitiveWriteDepth.incrementAndGet()
        return try {
            block()
        } finally {
            sensitiveWriteDepth.decrementAndGet()
        }
    }

    /**
     * Set a value at a nested JSON path within an existing variable.
     *
     * `fullPath` is parsed as `base.key1.key2` or `base[0]` or `base.key[0].nested`.
     * If no selectors are found, this falls through to a flat [set].
     * If the base variable does not exist or is not valid JSON, a new JSON structure is created.
     *
     * Returns true if the write succeeded, false if the path is unparseable.
     */
    fun setAtPath(fullPath: String, value: String): Boolean {
        val parsed = parsePathSelectors(fullPath) ?: return false
        if (parsed.selectors.isEmpty()) {
            set(parsed.base, value)
            return true
        }

        val current = get(parsed.base)
        val root: JsonElement = if (current != null) {
            try { jsonCodec.parseToJsonElement(current) } catch (_: Exception) { JsonObject(emptyMap()) }
        } else {
            JsonObject(emptyMap())
        }

        val updated = setInJson(root, parsed.selectors, JsonPrimitive(value)) ?: return false
        set(parsed.base, updated.toString())
        return true
    }

    /**
     * Set a value at a nested path within an array variable.
     *
     * `fullPath` is `arrayName[index]`. Sets the element at the given index,
     * growing the array with empty strings if needed.
     *
     * Returns true if the write succeeded.
     */
    fun setArrayAtIndex(name: String, index: Int, value: String): Boolean {
        if (index < 0 || index > MAX_ARRAY_INDEX) return false
        val items = arrayStore.snapshot()[name]?.toMutableList() ?: mutableListOf()
        while (items.size <= index) items.add("")
        items[index] = value
        arrayStore.put(name, items)
        if (sensitiveWriteDepth.get() > 0) sensitiveArrayNames += name
        return true
    }

    private fun setInJson(
        element: JsonElement,
        selectors: List<PathSelector>,
        value: JsonElement,
    ): JsonElement? {
        if (selectors.isEmpty()) return value
        val head = selectors.first()
        val tail = selectors.drop(1)

        return when (head) {
            is PathSelector.Property -> {
                val obj = (element as? JsonObject) ?: JsonObject(emptyMap())
                val child = obj[head.name] ?: JsonObject(emptyMap())
                val updated = setInJson(child, tail, value) ?: return null
                buildJsonObject {
                    obj.forEach { (k, v) -> put(k, v) }
                    put(head.name, updated)
                }
            }
            is PathSelector.Index -> {
                val arr = (element as? JsonArray) ?: JsonArray(emptyList())
                val items = arr.toMutableList()
                while (items.size <= head.index) items.add(JsonPrimitive(""))
                val child = items[head.index]
                val updated = setInJson(child, tail, value) ?: return null
                items[head.index] = updated
                buildJsonArray { items.forEach(::add) }
            }
        }
    }

    private fun parsePathSelectors(fullPath: String): ParsedPath? {
        if (fullPath.isBlank()) return null
        var cursor = 0
        while (cursor < fullPath.length && isPathBaseChar(fullPath[cursor])) cursor++
        if (cursor == 0) return null
        val base = fullPath.substring(0, cursor)
        val selectors = mutableListOf<PathSelector>()

        while (cursor < fullPath.length) {
            when (fullPath[cursor]) {
                '.' -> {
                    cursor++
                    val start = cursor
                    while (cursor < fullPath.length && isPathBaseChar(fullPath[cursor])) cursor++
                    if (cursor == start) return null
                    selectors += PathSelector.Property(fullPath.substring(start, cursor))
                }
                '[' -> {
                    val close = fullPath.indexOf(']', startIndex = cursor + 1)
                    if (close == -1) return null
                    val body = fullPath.substring(cursor + 1, close).trim()
                    val index = body.toIntOrNull() ?: return null
                    if (index < 0 || index > MAX_ARRAY_INDEX) return null
                    selectors += PathSelector.Index(index)
                    cursor = close + 1
                }
                else -> return null
            }
        }
        return ParsedPath(base, selectors)
    }

    private fun isPathBaseChar(char: Char): Boolean =
        char.isLetterOrDigit() || char == '_' || char == '-'

    private fun updateSensitivity(target: MutableSet<String>, name: String, sensitive: Boolean) {
        if (sensitive) target += name else target -= name
    }

    private sealed interface PathSelector {
        data class Property(val name: String) : PathSelector
        data class Index(val index: Int) : PathSelector
    }

    private data class ParsedPath(
        val base: String,
        val selectors: List<PathSelector>,
    )

    companion object {
        private val jsonCodec = Json { ignoreUnknownKeys = true }
        private val variableReference = Regex("%([A-Za-z][A-Za-z0-9_-]*)")

        /**
         * Upper bound for a nested/array write index. A `var.set` name such as `X[2000000000]`
         * (reachable from an imported/shared profile) would otherwise grow a list ~2 billion
         * entries, hanging the task thread and OOM-ing the process. Writes above this fail closed.
         */
        internal const val MAX_ARRAY_INDEX = 100_000
    }
}
