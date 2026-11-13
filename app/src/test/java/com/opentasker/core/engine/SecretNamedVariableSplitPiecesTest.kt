package com.opentasker.core.engine

import android.content.ContextWrapper
import com.opentasker.core.actions.ActionArgumentSensitivity
import com.opentasker.core.actions.registerActionMetadata
import com.opentasker.core.model.ActionSpec
import com.opentasker.core.model.Task
import com.opentasker.core.registerCoreRuntime
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test

/** The numbered pieces a step makes of a secret-named variable keep their values hidden. */
class SecretNamedVariableSplitPiecesTest {

    @Before
    fun setUp() {
        registerCoreRuntime()
        registerActionMetadata()
    }

    private fun splitChanges(name: String): Pair<String, Map<String, String>> {
        val report = runBlocking {
            TaskRunner(ActionContext(ContextWrapper(null), VariableStore(), logger = {})).run(
                Task(
                    name = "T",
                    actions = listOf(
                        ActionSpec(type = "var.set", args = mapOf("name" to "%$name", "value" to "Bearer sk-live")),
                        ActionSpec(type = "var.split", args = mapOf("name" to "%$name", "splitter" to " ")),
                    ),
                ),
            )
        }
        val serialized = runLogMessage(source = "manual", traces = report.traces)
        val split = serialized.toRunLogDiagnostics().traces.last().variableChanges.associate { it.name to it.value }
        return serialized to split
    }

    @Test
    fun theSplitPiecesOfASecretNamedVariableAreHidden() {
        val (serialized, split) = splitChanges("api_token")
        assertEquals("<redacted>", split["api_token1"])
        assertEquals("<redacted>", split["api_token2"])
        assertFalse(serialized, "sk-live" in serialized)
    }

    @Test
    fun theSplitPiecesOfASecretNameEndingInADigitAreHidden() {
        val (serialized, split) = splitChanges("api_key2")
        assertEquals("<redacted>", split["api_key21"])
        assertEquals("<redacted>", split["api_key22"])
        assertFalse(serialized, "sk-live" in serialized)
    }

    @Test
    fun theSplitPiecesOfAnOrdinaryVariableShow() {
        val (_, split) = splitChanges("header")
        assertEquals("Bearer", split["header1"])
        assertEquals("sk-live", split["header2"])
    }

    @Test
    fun aNumberedNameCountsAsTheVariableItCameFrom() {
        assertEquals(true, ActionArgumentSensitivity.hidesWrittenValue("var.split", mapOf("name" to "%api_token"), "api_token12"))
        assertEquals(false, ActionArgumentSensitivity.hidesWrittenValue("var.split", mapOf("name" to "%list"), "list2"))
        assertEquals(false, ActionArgumentSensitivity.hidesWrittenValue("http.request", mapOf("url" to "%api"), "api_token1"))
    }
}
