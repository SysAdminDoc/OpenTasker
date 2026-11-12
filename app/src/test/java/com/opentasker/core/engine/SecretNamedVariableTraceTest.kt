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
 * A value hidden for the name it's given stays out of the step's args and template lines and out of
 * a Persist, while the stored variable itself is left an ordinary one.
 */
class SecretNamedVariableTraceTest {
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

    private fun withPrefix() = VariableStore().apply { set("Prefix", "sk-abc") }

    @Test
    fun aTemplateValueForASecretNameIsHiddenInTheArgsAndTemplateLines() {
        val (serialized, _) = run(withPrefix(), ActionSpec(type = "var.set", args = mapOf("name" to "%api_key", "value" to "{{ global.Prefix }}")))
        assertFalse(serialized, "sk-abc" in serialized)
        assertTrue(serialized, "<redacted>" in serialized)
        assertFalse(logs.toString(), logs.any { "sk-abc" in it })
    }

    @Test
    fun aTemplateValueListedForSeveralNamesWithASecretIsHidden() {
        val (serialized, changes) = run(
            withPrefix(),
            ActionSpec(type = "var.set_multiple", args = mapOf("names" to "%user\n%api_token", "values" to "sam\n{{ global.Prefix }}")),
        )
        assertEquals(mapOf("user" to "sam", "api_token" to "<redacted>"), changes)
        assertFalse(serialized, "sk-abc" in serialized)
    }

    @Test
    fun anOrdinaryNameStillShowsItsTemplateValue() {
        val (serialized, _) = run(withPrefix(), ActionSpec(type = "var.set", args = mapOf("name" to "%greeting", "value" to "{{ global.Prefix }}")))
        assertTrue(serialized, "value=sk-abc" in serialized)
    }

    @Test
    fun persistingASecretNamedVariableKeepsItsValueHidden() {
        val (serialized, changes) = run(
            VariableStore(),
            ActionSpec(type = "var.set", args = mapOf("name" to "%api_token", "value" to "hunter2")),
            ActionSpec(type = "var.persist", args = mapOf("name" to "%api_token")),
        )
        assertEquals(mapOf("api_token" to "<redacted>", "Api_token" to "<redacted>"), changes)
        assertFalse(serialized, "hunter2" in serialized)
        assertFalse(logs.toString(), logs.any { "hunter2" in it })
    }

    @Test
    fun persistingUnderASecretNameKeepsItsValueHidden() {
        val (_, changes) = run(
            VariableStore(),
            ActionSpec(type = "var.set", args = mapOf("name" to "%mode", "value" to "night")),
            ActionSpec(type = "var.persist", args = mapOf("name" to "%mode", "global_name" to "Token")),
        )
        assertEquals(mapOf("mode" to "night", "Token" to "<redacted>"), changes)
        assertFalse(logs.toString(), logs.any { "Persist" in it && "night" in it })
    }

    @Test
    fun persistingAnOrdinaryVariableStillShowsItsValue() {
        val (_, changes) = run(
            VariableStore(),
            ActionSpec(type = "var.set", args = mapOf("name" to "%greeting", "value" to "hello")),
            ActionSpec(type = "var.persist", args = mapOf("name" to "%greeting")),
        )
        assertEquals(mapOf("greeting" to "hello", "Greeting" to "hello"), changes)
        assertTrue(logs.toString(), logs.any { "Persist" in it && "hello" in it })
    }

    @Test
    fun theStoredVariableIsNotMadeASecretByItsName() {
        val variables = VariableStore()
        run(
            variables,
            ActionSpec(type = "var.set", args = mapOf("name" to "%Api_token", "value" to "hunter2")),
            ActionSpec(type = "var.set", args = mapOf("name" to "%Keyboard", "value" to "qwerty")),
        )
        assertEquals("hunter2", variables.get("Api_token"))
        assertEquals("qwerty", variables.get("Keyboard"))
        assertFalse(variables.isSensitive("Api_token"))
        assertEquals(emptySet<String>(), variables.globalSensitiveSnapshot())
    }
}
