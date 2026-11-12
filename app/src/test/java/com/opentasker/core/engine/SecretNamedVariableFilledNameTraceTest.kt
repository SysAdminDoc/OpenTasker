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
 * A value hidden for the name it's given stays hidden when that name is filled in from another
 * variable, when its template warns, and when a step other than Set Variable writes it.
 */
class SecretNamedVariableFilledNameTraceTest {
    private val logs = mutableListOf<String>()

    @Before
    fun setUp() {
        registerCoreRuntime()
        registerActionMetadata()
    }

    private fun run(variables: VariableStore, vararg actions: ActionSpec): Pair<String, Map<String, String>> {
        val report = runBlocking {
            TaskRunner(ActionContext(ContextWrapper(null), variables, logger = { logs += it }))
                .run(Task(name = "T", actions = actions.toList()))
        }
        val serialized = runLogMessage(source = "manual", traces = report.traces)
        val changes = serialized.toRunLogDiagnostics().traces.flatMap { it.variableChanges }.associate { it.name to it.value }
        return serialized to changes
    }

    private fun withSlot(slot: String) = VariableStore().apply {
        set("Prefix", "sk-abc")
        set("slot", slot)
    }

    @Test
    fun aTemplateValueForANameFilledInAsASecretIsHidden() {
        val (serialized, changes) = run(
            withSlot("api_key"),
            ActionSpec(type = "var.set", args = mapOf("name" to "item_%slot", "value" to "{{ global.Prefix }}")),
        )
        assertEquals("<redacted>", changes["item_api_key"])
        assertFalse(serialized, "sk-abc" in serialized)
    }

    @Test
    fun aTemplateValueForSeveralNamesFilledInAsASecretIsHidden() {
        val (serialized, changes) = run(
            withSlot("token"),
            ActionSpec(type = "var.set_multiple", args = mapOf("names" to "%cache_%slot", "values" to "{{ global.Prefix }}")),
        )
        assertEquals("<redacted>", changes["cache_token"])
        assertFalse(serialized, "sk-abc" in serialized)
    }

    @Test
    fun aNameFilledInAsAnOrdinaryOneStillShowsItsTemplateValue() {
        val (serialized, changes) = run(
            withSlot("color"),
            ActionSpec(type = "var.set_multiple", args = mapOf("names" to "%cache_%slot", "values" to "{{ global.Prefix }}")),
        )
        assertEquals("sk-abc", changes["cache_color"])
        assertTrue(serialized, "values=sk-abc" in serialized)
    }

    @Test
    fun aTemplateWarningForASecretNameIsHidden() {
        val (serialized, _) = run(
            VariableStore(),
            ActionSpec(type = "var.set", args = mapOf("name" to "%api_key", "value" to "{{ hunter2 }}")),
        )
        assertFalse(serialized, "hunter2" in serialized)
    }

    @Test
    fun aTemplateWarningForASecretNameIsHiddenOnTheStepTrace() {
        val traces = runBlocking {
            TaskRunner(ActionContext(ContextWrapper(null), VariableStore(), logger = { logs += it }))
                .run(Task(name = "T", actions = listOf(ActionSpec(type = "var.set", args = mapOf("name" to "%api_key", "value" to "{{ hunter2 }}")))))
        }.traces
        val warnings = traces.flatMap { trace -> trace.templateWarnings + trace.argumentExpansions.flatMap { it.warnings } }
        assertEquals(listOf("value: <redacted>", "<redacted>"), warnings)
    }

    @Test
    fun aConvertedSecretNamedVariableKeepsItsValueHidden() {
        val (serialized, changes) = run(
            VariableStore(),
            ActionSpec(type = "var.set", args = mapOf("name" to "%api_token", "value" to "HUNTER2")),
            ActionSpec(type = "var.convert", args = mapOf("name" to "%api_token", "function" to "lower_case")),
        )
        assertEquals("<redacted>", changes["api_token"])
        assertFalse(serialized, "hunter2" in serialized)
    }

    @Test
    fun aConvertedVariableNamedOnlyOnceFilledInKeepsItsValueHidden() {
        val (serialized, changes) = run(
            VariableStore().apply { set("kind", "token") },
            ActionSpec(type = "var.set", args = mapOf("name" to "%api_token", "value" to "HUNTER2")),
            ActionSpec(type = "var.convert", args = mapOf("name" to "%api_%kind", "function" to "lower_case")),
        )
        assertEquals("<redacted>", changes["api_token"])
        assertFalse(serialized, "hunter2" in serialized)
    }

    @Test
    fun aConvertedOrdinaryVariableStillShowsItsValue() {
        val (_, changes) = run(
            VariableStore(),
            ActionSpec(type = "var.set", args = mapOf("name" to "%greeting", "value" to "HELLO")),
            ActionSpec(type = "var.convert", args = mapOf("name" to "%greeting", "function" to "lower_case")),
        )
        assertEquals("hello", changes["greeting"])
    }
}
