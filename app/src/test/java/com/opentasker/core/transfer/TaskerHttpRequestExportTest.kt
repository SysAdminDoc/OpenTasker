package com.opentasker.core.transfer

import com.opentasker.core.actions.registerActionMetadata
import com.opentasker.core.diagnostics.ExportRedactionPolicy
import com.opentasker.core.model.ActionSpec
import com.opentasker.core.model.Task
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * An HTTP request exported to Tasker's HTTP Request (339) reads back through the Tasker import as
 * the same request. Headers, query and body are redacted on every export, so only their place
 * survives the trip.
 */
class TaskerHttpRequestExportTest {

    @Before
    fun declareSensitiveFields() {
        registerActionMetadata()
    }

    private fun export(action: ActionSpec) =
        TaskerXmlExporter.export(profiles = emptyList(), tasks = listOf(Task(id = 1L, name = "Request", actions = listOf(action))))

    private fun readBack(xml: String): ActionSpec =
        TaskerXmlImporter.parse(rawXml = xml, appVersion = "test", importedAtEpochMs = 0L).bundle.tasks.single().actions.single()

    @Test
    fun aRequestReadsBackWithItsMethodAddressAndSettings() {
        listOf("GET", "POST", "HEAD", "PUT", "PATCH", "DELETE", "OPTIONS").forEach { method ->
            val result = export(ActionSpec(type = "http.request", args = mapOf("method" to method.lowercase(), "url" to "https://example.test/items/%id")))
            assertTrue(result.skippedActions.toString(), result.skippedActions.isEmpty())
            val back = readBack(result.xml)
            assertEquals("http.request", back.type)
            assertEquals(method, back.args["method"])
            assertEquals("https://example.test/items/%id", back.args["url"])
        }

        val result = export(
            ActionSpec(
                type = "http.request",
                args = mapOf(
                    "method" to "POST",
                    "url" to "https://example.test/",
                    "content_type" to "application/json",
                    "timeout_sec" to "12",
                    "redirects" to "same_origin",
                    "response_var" to "%http_data",
                    "status_var" to "http_response_code",
                ),
            ),
        )
        val back = readBack(result.xml)

        assertEquals("Content-Type: application/json", back.args["headers"])
        assertEquals("12", back.args["timeout_sec"])
        assertEquals("same_origin", back.args["redirects"])
        assertTrue(result.warnings.toString(), result.warnings.any { "any site" in it })
        assertFalse("the results already land where Tasker puts them", result.warnings.any { "own variables" in it })
    }

    @Test
    fun headersQueryAndBodyNeverLeaveInTheExport() {
        val result = export(
            ActionSpec(
                type = "http.request",
                args = mapOf(
                    "method" to "POST",
                    "url" to "https://example.test/",
                    "headers" to "X-Api-Key: header-secret",
                    "query" to "token=query-secret",
                    "body" to "body-secret",
                    "authorization" to "Bearer auth-secret",
                ),
            ),
        )

        listOf("header-secret", "query-secret", "body-secret", "auth-secret").forEach { assertFalse(it, it in result.xml) }
        assertTrue(result.warnings.toString(), ExportRedactionPolicy.SENSITIVE_ACTION_WARNING in result.warnings)
        assertEquals("POST", readBack(result.xml).args["method"])
    }

    @Test
    fun theOlderGetAndPostActionsExportAsTheRequestTheyRun() {
        val get = export(ActionSpec(type = "http.get", args = mapOf("url" to "https://example.test/a", "var" to "page")))
        assertEquals("GET", readBack(get.xml).args["method"])
        assertEquals("https://example.test/a", readBack(get.xml).args["url"])
        assertTrue(get.warnings.toString(), get.warnings.any { "the response from %page to %http_data" in it })

        val post = export(ActionSpec(type = "http.post", args = mapOf("url" to "https://example.test/b")))
        assertEquals("POST", readBack(post.xml).args["method"])
        assertTrue(post.warnings.toString(), post.warnings.any { "the response from %result to %http_data" in it })
    }

    @Test
    fun whatTaskerHasNoPlaceForIsNamed() {
        val result = export(
            ActionSpec(
                type = "http.request",
                args = mapOf(
                    "url" to "https://example.test/",
                    "output_file" to "downloads/a.bin",
                    "status_var" to "code",
                    "headers_var" to "hdrs",
                    "network" to "unmetered",
                ),
            ),
        )
        val all = result.warnings.joinToString("\n")

        assertTrue(all, "output_file" in all)
        assertTrue(all, "the status from %code to %http_response_code" in all)
        assertTrue(all, "the headers from %hdrs to %http_headers()" in all)
        assertTrue(all, "only over an unmetered network" in all)
        assertFalse("a response saved to a file sets no variable", "%result" in all)
    }
}
