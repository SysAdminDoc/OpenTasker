package com.opentasker.core.diagnostics

import com.opentasker.core.actions.registerActionMetadata
import com.opentasker.core.model.ActionSpec
import com.opentasker.core.model.Task
import com.opentasker.core.transfer.OpenTaskerBundle
import com.opentasker.core.transfer.OpenTaskerBundleCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ExportRedactionPolicyTest {
    @Before
    fun setUp() {
        registerActionMetadata()
    }

    @Test
    fun actionFieldsAndKnownSecretsAreRedactedAcrossStructuredValues() {
        val result = ExportRedactionPolicy.sanitizeActionArguments(
            actionType = "http.request",
            args = linkedMapOf(
                "url" to "https://user:pass@example.test/api?token=sentinel",
                "headers" to "Authorization: Bearer sentinel",
                "body_file" to "/sdcard/sentinel.json",
                "response_var" to "result",
            ),
            context = ExportRedactionPolicy.Context(secretValues = setOf("sentinel")),
        )

        assertEquals(ExportRedactionPolicy.REDACTED, result.args.getValue("headers"))
        assertEquals("result", result.args.getValue("response_var"))
        assertTrue(result.redactedFields.containsAll(setOf("url", "headers", "body_file")))
        assertFalse(result.args.values.any { it.contains("sentinel") })
        assertFalse(result.args.getValue("url").contains("pass@"))
        assertFalse(result.args.getValue("url").contains("?token="))
    }

    @Test
    fun secretDerivedTemplateReferencesFailClosedEvenInOrdinaryFields() {
        val result = ExportRedactionPolicy.sanitizeActionArguments(
            actionType = "log",
            args = mapOf("message" to "Token is {{ global.API_TOKEN }}"),
            context = ExportRedactionPolicy.Context(secretNames = setOf("API_TOKEN")),
        )

        assertEquals(ExportRedactionPolicy.REDACTED, result.args.getValue("message"))
        assertEquals(setOf("message"), result.redactedFields)
    }

    @Test
    fun freeTextRedactionRemovesCredentialsAndUrlQueries() {
        val result = ExportRedactionPolicy.redactText(
            "failure password=sentinel at https://example.test/path?api_key=sentinel",
            secretValues = setOf("sentinel"),
        )

        assertFalse(result.contains("sentinel"))
        assertFalse(result.contains("api_key=sentinel"))
        assertTrue(result.contains("password=[REDACTED]"))
        assertTrue(result.contains("https://example.test/path?[REDACTED]"))
    }

    @Test
    fun aCredentialUnderACompoundNameIsRedacted() {
        val result = ExportRedactionPolicy.redactText(
            "HTTP 401 api_key=AAA111 access_token: BBB222 client_secret=CCC333 apikey=DDD444 " +
                "authToken=EEE555 X-Api-Key: FFF666 refresh.token=GGG777",
        )

        listOf("AAA111", "BBB222", "CCC333", "DDD444", "EEE555", "FFF666", "GGG777").forEach { value ->
            assertFalse("$value survived in: $result", result.contains(value))
        }
        assertTrue(result.contains("api_key=[REDACTED]"))
    }

    @Test
    fun aCredentialInsideAJsonBodyIsRedacted() {
        val result = ExportRedactionPolicy.redactText(
            """reply {"user":"sam","password":"hunter2 with spaces","refresh_token": "r-1"} and \"token\":\"t-9\"""",
        )

        assertFalse(result, result.contains("hunter2"))
        assertFalse(result, result.contains("with spaces"))
        assertFalse(result, result.contains("r-1"))
        assertFalse(result, result.contains("t-9"))
        assertTrue(result, result.contains("\"user\":\"sam\""))
    }

    @Test
    fun ordinaryWordsThatOnlyContainACredentialWordAreLeftAlone() {
        val text = "hotkey=F5 monkey=banana max_tokens=100 author: Bob keyboard: shown tokens: 12"

        assertEquals(text, ExportRedactionPolicy.redactText(text))
    }

    @Test
    fun anEscapedQuoteDoesNotEndTheRedaction() {
        assertEquals(
            """{"password":"[REDACTED]"}""",
            ExportRedactionPolicy.redactText("""{"password":"hun\"ter2 secret"}"""),
        )
    }

    @Test
    fun aQuotedAuthorizationHeaderIsRedacted() {
        assertEquals(
            """headers {"Authorization":"[REDACTED]","Accept":"json"}""",
            ExportRedactionPolicy.redactText("""headers {"Authorization":"Basic dXNlcjpwYXNz","Accept":"json"}"""),
        )
    }

    @Test
    fun aRedactedJsonBodyKeepsItsShape() {
        assertEquals("""{"key":"[REDACTED]"}""", ExportRedactionPolicy.redactText("""{"key":"abc"}"""))
    }

    @Test
    fun namesThatOnlyEndInKeyAreLeftAlone() {
        val text = "sort_key=5 primary_key=1"

        assertEquals(text, ExportRedactionPolicy.redactText(text))
    }

    @Test
    fun aConditionOnACredentialNameIsLeftAlone() {
        val text = "%access_token == x"

        assertEquals(text, ExportRedactionPolicy.redactText(text))
    }

    @Test
    fun basicAuthUnderACompoundNameIsRedacted() {
        val result = ExportRedactionPolicy.redactText("basic_auth=dXNlcjpwYXNz")

        assertEquals("basic_auth=[REDACTED]", result)
    }

    @Test
    fun aLongUnterminatedQuotedValueIsStillRedacted() {
        val result = ExportRedactionPolicy.redactText("password=\"" + "\\a".repeat(40_000))

        assertEquals("password=[REDACTED]", result)
    }

    @Test
    fun aLongRunOfSeparatorsIsReadOnce() {
        val text = "a_".repeat(20_000) + " b-".repeat(20_000)

        assertEquals(text, ExportRedactionPolicy.redactText(text))
    }

    @Test
    fun openTaskerBundleEncodingUsesTheSamePolicy() {
        val bundle = OpenTaskerBundle(
            appVersion = "test",
            exportedAtEpochMs = 1L,
            tasks = listOf(
                Task(
                    id = 1,
                    name = "Export",
                    actions = listOf(
                        ActionSpec(
                            type = "var.set",
                            args = mapOf("name" to "API_TOKEN", "value" to "sentinel"),
                        ),
                        ActionSpec(
                            type = "url.open",
                            args = mapOf("url" to "https://example.test/?token=sentinel"),
                        ),
                    ),
                ),
            ),
        )

        val encoded = OpenTaskerBundleCodec.encode(bundle)
        val decoded = OpenTaskerBundleCodec.decode(encoded)

        assertFalse(encoded.contains("sentinel"))
        assertEquals(ExportRedactionPolicy.REDACTED, decoded.tasks.single().actions.first().args["value"])
        assertTrue(decoded.metadata.warnings.any { it.contains("must be re-entered") })
    }
}
