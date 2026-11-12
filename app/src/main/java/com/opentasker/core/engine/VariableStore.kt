package com.opentasker.core.engine

import com.opentasker.core.data.JsonNesting
import com.opentasker.core.engine.variables.ArrayStore
import com.opentasker.core.engine.variables.TaskerBuiltIns
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
    /** Parallel to [localStack]: true for a scope that holds a sub-task's inputs. */
    private val inputScopes = java.util.Collections.synchronizedList(mutableListOf<Boolean>())
    /** Parallel to [localStack]: names [clearForTask] hid there, so a caller's value doesn't show through. */
    private val hiddenStack = java.util.Collections.synchronizedList(mutableListOf<MutableSet<String>>())
    private val sensitiveArrayNames = ConcurrentHashMap.newKeySet<String>()
    private val sensitiveWriteDepth = AtomicInteger(0)
    private val expander = VariableExpander()
    private val arrayStore = ArrayStore()

    /** [holdsInputs] marks the scope Perform Task puts a sub-task's inputs (`%par1`, ...) in. */
    fun pushScope(holdsInputs: Boolean = false) {
        synchronized(localStack) {
            localStack.add(java.util.concurrent.ConcurrentHashMap())
            localSensitiveStack.add(ConcurrentHashMap.newKeySet())
            inputScopes.add(holdsInputs)
            hiddenStack.add(ConcurrentHashMap.newKeySet())
        }
    }
    fun popScope() {
        synchronized(localStack) {
            if (localStack.isNotEmpty()) {
                localStack.removeAt(localStack.size - 1)
                localSensitiveStack.removeAt(localSensitiveStack.size - 1)
                inputScopes.removeAt(inputScopes.size - 1)
                hiddenStack.removeAt(hiddenStack.size - 1)
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
                if (normalizedName in hiddenStack[i]) return TaskerBuiltIns.value(normalizedName)
            }
        }
        // A Tasker built-in fills in when nothing is stored under its name, or only an empty value
        // is (an imported Variable Clear sets it to nothing).
        val stored = rootLocals[normalizedName] ?: globals[normalizedName]
        return if (stored.isNullOrEmpty()) TaskerBuiltIns.value(normalizedName) ?: stored else stored
    }

    /**
     * Unsets [name] in the scope [get] reads it from, so it reads as never set. Returns whether it
     * was set. A global's taint stays, as it does in [set].
     */
    fun remove(name: String): Boolean {
        val normalizedName = VariableNamePolicy.normalize(name) ?: return false
        if (VariableNamePolicy.isGlobal(normalizedName)) return globals.remove(normalizedName) != null
        synchronized(localStack) {
            for (index in localStack.indices.reversed()) {
                if (localStack[index].remove(normalizedName) != null) {
                    localSensitiveStack[index] -= normalizedName
                    return true
                }
                if (normalizedName in hiddenStack[index]) return false
            }
            if (rootLocals.remove(normalizedName) == null) return false
            rootLocalSensitiveNames -= normalizedName
            return true
        }
    }

    /**
     * Unsets [name] for the running task only, the way Tasker's Reset Return Variable clears it: a
     * local goes from that task's own scopes (the innermost input scope and every one above it, or
     * all of them in a task nothing called), and a caller's variable of that name stays as it is
     * but reads as unset here until the task ends. A global is unset everywhere, as in [remove].
     */
    fun clearForTask(name: String) {
        val normalizedName = VariableNamePolicy.normalize(name) ?: return
        if (VariableNamePolicy.isGlobal(normalizedName)) {
            globals.remove(normalizedName)
            return
        }
        synchronized(localStack) {
            val ownFrom = inputScopes.lastIndexOf(true)
            for (index in maxOf(ownFrom, 0) until localStack.size) {
                localStack[index].remove(normalizedName)
                localSensitiveStack[index] -= normalizedName
            }
            if (ownFrom < 0) {
                rootLocals.remove(normalizedName)
                rootLocalSensitiveNames -= normalizedName
            } else {
                hiddenStack[ownFrom] += normalizedName
            }
        }
    }

    /**
     * Tasker's array elements: the variables named [base] plus a whole number from 1 (`arr1`,
     * `arr2`, ...), in the scope [base] belongs to, by index. Each one reads the way [get] reads it.
     */
    fun numberedItems(base: String): java.util.NavigableMap<Int, String> {
        val items = java.util.TreeMap<Int, String>()
        val normalizedBase = VariableNamePolicy.normalize(base) ?: return items
        val names = if (VariableNamePolicy.isGlobal(normalizedBase)) {
            globals.keys.toSet()
        } else {
            synchronized(localStack) { arrayScopes(normalizedBase).flatMapTo(mutableSetOf()) { (values, _) -> values.keys } }
        }
        names.forEach { name ->
            val index = numberedIndex(normalizedBase, name) ?: return@forEach
            get(name)?.let { items[index] = it }
        }
        return items
    }

    /**
     * Replaces [base]'s numbered elements with [items] (index to value and secret flag). The old
     * elements are unset in every local scope [arrayScopes] covers, so none left in a caller's
     * scope shows through after a sub-task set one of its own, and the new ones go into the
     * outermost of those scopes that held an element, else the one [set] writes to. A sub-task
     * changing its caller's array changes it there, instead of writing the new elements into its
     * own scope, gone when it returns.
     */
    fun replaceNumberedItems(base: String, items: Map<Int, Pair<String, Boolean>>) {
        val normalizedBase = VariableNamePolicy.normalize(base) ?: return
        if (VariableNamePolicy.isGlobal(normalizedBase)) {
            globals.keys.filter { numberedIndex(normalizedBase, it) != null }.forEach(globals::remove)
            items.forEach { (index, item) -> set("$normalizedBase$index", item.first, sensitive = item.second) }
            return
        }
        val tainted = sensitiveWriteDepth.get() > 0
        synchronized(localStack) {
            val scopes = arrayScopes(normalizedBase)
            val (values, sensitiveNames) = scopes.firstOrNull { (values, _) ->
                values.keys.any { numberedIndex(normalizedBase, it) != null }
            } ?: scopes.last()
            scopes.forEach { (scopeValues, scopeSensitiveNames) ->
                scopeValues.keys.filter { numberedIndex(normalizedBase, it) != null }.forEach { name ->
                    scopeValues.remove(name)
                    scopeSensitiveNames -= name
                }
            }
            items.forEach { (index, item) ->
                val name = VariableNamePolicy.normalize("$normalizedBase$index") ?: return@forEach
                values[name] = item.first
                if (item.second || tainted) sensitiveNames += name
            }
        }
    }

    /** True when any of [base]'s numbered elements holds a secret or a value derived from one. */
    fun anyNumberedItemSensitive(base: String): Boolean {
        val normalizedBase = VariableNamePolicy.normalize(base) ?: return false
        if (VariableNamePolicy.isGlobal(normalizedBase)) {
            return globalSensitiveNames.any { numberedIndex(normalizedBase, it) != null }
        }
        return synchronized(localStack) {
            arrayScopes(normalizedBase).any { (_, sensitiveNames) -> sensitiveNames.any { numberedIndex(normalizedBase, it) != null } }
        }
    }

    /**
     * The local scopes, outermost first, that hold the local array [base]: every one, unless a
     * sub-task was handed elements of it as inputs. Then the array starts at the innermost such
     * input scope, so a sub-task's `%par` is its own and a Pop on it leaves its caller's `%par1`
     * and `%par2` alone. `%par` always starts at the innermost input scope, even one given no
     * inputs, so a sub-task run without any never sees or pops its caller's. Call it holding the
     * [localStack] lock.
     */
    private fun arrayScopes(base: String): List<Pair<MutableMap<String, String>, MutableSet<String>>> {
        val scopes = listOf<Pair<MutableMap<String, String>, MutableSet<String>>>(rootLocals to rootLocalSensitiveNames) +
            localStack.zip(localSensitiveStack)
        val inputs = localStack.indices.lastOrNull { index ->
            inputScopes[index] && (base == INPUT_ARRAY || localStack[index].keys.any { numberedIndex(base, it) != null })
        } ?: return scopes
        return scopes.drop(inputs + 1)
    }

    private fun numberedIndex(base: String, name: String): Int? {
        if (name.length <= base.length || !name.startsWith(base)) return null
        val digits = name.substring(base.length)
        if (digits.first() == '0' || !digits.all { it in '0'..'9' }) return null
        return digits.toIntOrNull()
    }

    fun isSensitive(name: String): Boolean {
        val normalizedName = VariableNamePolicy.normalize(name) ?: return false
        synchronized(localStack) {
            for (index in localStack.indices.reversed()) {
                if (normalizedName in localStack[index]) return normalizedName in localSensitiveStack[index]
                if (normalizedName in hiddenStack[index]) return false
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
        // Named the way [set] names it, so an output field written as %parts stores parts.
        val normalizedName = VariableNamePolicy.normalize(name) ?: return
        // Array taint is monotonic for the same reason global taint is (see [set]): mark before
        // publishing and never clear, so a later plain write can't race a secret array's flag off.
        if (sensitive || sensitiveWriteDepth.get() > 0 || normalizedName in sensitiveArrayNames) {
            sensitiveArrayNames += normalizedName
        }
        arrayStore.put(normalizedName, values)
    }

    /**
     * Returns the elements of a stored array by name, or else Tasker's numbered elements (`arr1`,
     * `arr2`, ...) in index order, or null when neither exists. Used by the `flow.foreach` control
     * action to iterate over array variables. [name] may carry its percent sign, as a For list does.
     */
    fun getArrayItems(name: String): List<String>? =
        arrayStore.snapshot()[storedArrayName(name)] ?: numberedItems(name).values.toList().ifEmpty { null }

    fun isArraySensitive(name: String): Boolean = storedArrayName(name) in sensitiveArrayNames || anyNumberedItemSensitive(name)

    /** The key [setArray] stores [name] under, so a lookup and its taint check find the same array. */
    private fun storedArrayName(name: String): String = VariableNamePolicy.normalize(name) ?: name

    /** Expand all variable references in [s] using the current scope chain. */
    fun expand(s: String): String {
        return expander.expand(s, this, arrayStore)
    }

    /**
     * Expands a legacy expression while retaining whether any referenced input was secret.
     * [conditionals] false keeps a leading `(cond) ? a : b` as text. [keepUnset] false fills in an
     * unset `%name` as nothing instead of leaving it as written.
     */
    fun expandTracked(s: String, conditionals: Boolean = true, keepUnset: Boolean = true): TrackedExpansion = TrackedExpansion(
        value = expander.expand(s, this, arrayStore, InertTemplateText::hide, conditionals, keepUnset),
        isSecretDerived = variableReference.findAll(s).any { match ->
            val name = match.groupValues[1]
            isSensitive(name) || name in sensitiveArrayNames ||
                (s.startsWith("(", match.range.last + 1) && anyNumberedItemSensitive(name))
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
        return expander.evaluateCondition(expr, this, arrayStore) { operand, keepUnset ->
            val legacy = expandTracked(operand, keepUnset = keepUnset)
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
            localStack.forEachIndexed { index, scope ->
                taskValues -= hiddenStack[index]
                taskValues += scope
            }
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
            builtIns = TaskerBuiltIns::value,
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
     * Returns true if the write succeeded, false if the path is unparseable or the variable holds
     * JSON nested deeper than [JsonNesting.MAX_DATA_DEPTH], which is left as it is.
     */
    fun setAtPath(fullPath: String, value: String): Boolean {
        val parsed = parsePathSelectors(fullPath) ?: return false
        if (parsed.selectors.isEmpty()) {
            set(parsed.base, value)
            return true
        }

        val current = get(parsed.base)
        val depth = current?.let(JsonNesting::depth)
        if (depth != null && depth != JsonNesting.NOT_JSON && depth > JsonNesting.MAX_DATA_DEPTH) return false
        val root: JsonElement = if (current != null && depth != JsonNesting.NOT_JSON) {
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

        /** The array Perform Task hands a sub-task its inputs in, as `%par1` and `%par2`. */
        private const val INPUT_ARRAY = "par"
    }
}
