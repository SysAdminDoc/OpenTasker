package com.opentasker.core

import com.opentasker.core.actions.ActionMetadataRegistry
import com.opentasker.core.actions.registerActionMetadata
import com.opentasker.core.contexts.ContextSourceRegistry
import com.opentasker.core.engine.ActionRegistry
import com.opentasker.core.engine.FlowControl
import com.opentasker.core.engine.STOP_TASK_ACTION_ID
import com.opentasker.core.engine.SUB_TASK_ACTION_ID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeRegistriesTest {
    // Actions handled directly by the TaskRunner (not via ActionRegistry).
    private val engineHandledActions = setOf(SUB_TASK_ACTION_ID, STOP_TASK_ACTION_ID) + FlowControl.ALL

    @Test
    fun everyUiMetadataActionHasRuntimeImplementation() {
        registerActionMetadata()
        registerCoreRuntime()

        val missing = ActionMetadataRegistry.all()
            .map { it.id }
            .filter { it !in engineHandledActions && ActionRegistry.get(it) == null }

        assertTrue("Missing runtime actions: $missing", missing.isEmpty())
    }

    @Test
    fun everyRuntimeActionHasUiMetadata() {
        registerActionMetadata()
        registerCoreRuntime()

        val metadataIds = ActionMetadataRegistry.all().map { it.id }.toSet()
        val runtimeIds = ActionRegistry.allIds()
        val missing = runtimeIds.filter { it !in metadataIds }

        assertTrue("Runtime actions missing metadata: $missing", missing.isEmpty())
    }

    @Test
    fun dynamicFormMetadataUsesRuntimeArgumentKeys() {
        registerActionMetadata()

        assertFieldKeys("brightness.set", "brightness")
        assertFieldKeys("screenshot.take", "path")
        assertFieldKeys("file.read", "path", "var")
        assertFieldKeys("file.write", "path", "text")
        assertFieldKeys("file.append", "path", "text")
        assertFieldKeys("file.list", "path", "var", "pattern")
        assertFieldKeys(
            "http.request",
            "method", "url", "query", "headers", "authorization", "body", "body_file", "content_type",
            "response_var", "status_var", "headers_var", "output_file", "max_response_bytes", "redirects",
            "network", "allow_http", "timeout_sec", "connect_timeout_sec", "read_timeout_sec", "write_timeout_sec",
            "call_timeout_sec",
        )
        assertFieldKeys("http.get", "url", "var", "allow_http")
        assertFieldKeys("http.post", "url", "data", "var", "allow_http")
    }

    @Test
    fun coreContextSourcesIncludeLiveLocationSource() {
        registerCoreRuntime()

        val registered = ContextSourceRegistry.all().map { it.type }.toSet()

        assertTrue("Location context source must be registered: $registered", "location" in registered)
    }

    private fun assertFieldKeys(actionId: String, vararg expected: String) {
        val metadata = ActionMetadataRegistry.get(actionId)
        assertTrue("Missing metadata for $actionId", metadata != null)
        assertEquals(
            "$actionId field keys",
            expected.toList(),
            metadata!!.fields.map { it.key },
        )
    }
}
