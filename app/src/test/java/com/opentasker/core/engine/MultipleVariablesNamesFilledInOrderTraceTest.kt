package com.opentasker.core.engine

import android.content.ContextWrapper
import com.opentasker.core.actions.registerActionMetadata
import com.opentasker.core.model.ActionSpec
import com.opentasker.core.model.Task
import com.opentasker.core.registerCoreRuntime
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Multiple Variables Set's names are filled in the way the step fills them in, one after another,
 * without the check itself changing a variable; and a hidden template keeps all of its text off the
 * step's trace.
 */
class MultipleVariablesNamesFilledInOrderTraceTest {
    private val logs = mutableListOf<String>()

    @Before
    fun setUp() {
        registerCoreRuntime()
        registerActionMetadata()
    }

    private fun run(variables: VariableStore, spec: ActionSpec): Pair<String, ActionExecutionTrace> {
        val report = runBlocking {
            TaskRunner(ActionContext(ContextWrapper(null), variables, logger = { logs += it }))
                .run(Task(name = "T", actions = listOf(spec)))
        }
        return runLogMessage(source = "manual", traces = report.traces) to report.traces.single()
    }

    private fun changes(serialized: String) =
        serialized.toRunLogDiagnostics().traces.flatMap { it.variableChanges }.associate { it.name to it.value }

    private fun setMultiple(names: String, values: String) =
        ActionSpec(type = "var.set_multiple", args = mapOf("names" to names, "values" to values))

    @Test
    fun aNameAnEarlierOneInTheListFillsInAsASecretHidesItsTemplateValue() {
        val (serialized, _) = run(
            VariableStore().apply { set("Prefix", "sk-abc") },
            setMultiple("%slot\n%cache_%slot", "api_key\n{{ global.Prefix }}"),
        )
        assertEquals("<redacted>", changes(serialized)["cache_api_key"])
        assertFalse(serialized, "sk-abc" in serialized)
        assertTrue(logs.toString(), logs.none { "sk-abc" in it })
    }

    @Test
    fun aNameAnEarlierOneInTheListFillsInAsAnOrdinaryOneShowsItsTemplateValue() {
        val (serialized, _) = run(
            VariableStore().apply { set("Prefix", "sk-abc") },
            setMultiple("%slot\n%cache_%slot", "color\n{{ global.Prefix }}"),
        )
        assertEquals("sk-abc", changes(serialized)["cache_color"])
    }

    @Test
    fun anArrayANameStoresAsItIsFilledInShowsAsAChange() {
        val (_, trace) = run(
            VariableStore().apply {
                set("Prefix", "sk-abc")
                set("x", "a;b")
            },
            // Not a comma: a one-line names field is split on its commas first.
            setMultiple("%x(split:;)", "{{ global.Prefix }}"),
        )
        val arrays = trace.variableChanges.filter { it.scope == VariableChangeScope.ARRAY }
        assertEquals(listOf("x_split" to "a, b"), arrays.map { it.name to it.value })
    }

    @Test
    fun aHiddenTemplateLeavesNoTypedTextOnTheTrace() {
        val (_, trace) = run(
            VariableStore(),
            ActionSpec(type = "var.set", args = mapOf("name" to "%api_key", "value" to "{{ hunter2 }}")),
        )
        val expressions = trace.argumentExpansions.flatMap { it.expressions }
        assertTrue(expressions.isNotEmpty())
        assertTrue(expressions.toString(), expressions.none { "hunter2" in it.toString() })
    }
}
