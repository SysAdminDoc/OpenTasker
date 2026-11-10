package com.opentasker.core.diagnostics

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The redaction patterns on Android's ICU regex engine.
 *
 * The production regex corpus can't hold these: they're assembled from a shared quoted-value
 * fragment and string concatenation, so the build skips them. The JVM suite runs them on the
 * desktop engine only. Here every pattern compiles in the object initializer, where an ICU
 * rejection would make the whole policy unusable, and then has to redact the same inputs the JVM
 * suite checks.
 */
@RunWith(AndroidJUnit4::class)
class ExportRedactionPolicyIcuTest {

    @Test
    fun compoundAndJsonCredentialsAreRedacted() {
        val result = ExportRedactionPolicy.redactText(
            "HTTP 401 api_key=AAA111 access_token: BBB222 client_secret=CCC333 apikey=DDD444 " +
                "authToken=EEE555 X-Api-Key: FFF666 refresh.token=GGG777 " +
                """reply {"user":"sam","password":"hunter2 with spaces","refresh_token": "r-1"} and \"token\":\"t-9\"""",
        )

        listOf("AAA111", "BBB222", "CCC333", "DDD444", "EEE555", "FFF666", "GGG777", "hunter2", "r-1", "t-9")
            .forEach { value -> assertFalse("$value survived in: $result", result.contains(value)) }
        assertTrue(result, result.contains("api_key=[REDACTED]"))
        assertTrue(result, result.contains("\"user\":\"sam\""))
    }

    @Test
    fun quotedValuesKeepTheirJsonShape() {
        assertEquals("""{"password":"[REDACTED]"}""", ExportRedactionPolicy.redactText("""{"password":"hun\"ter2 secret"}"""))
        assertEquals(
            """headers {"Authorization":"[REDACTED]","Accept":"json"}""",
            ExportRedactionPolicy.redactText("""headers {"Authorization":"Basic dXNlcjpwYXNz","Accept":"json"}"""),
        )
        assertEquals("""{"key":"[REDACTED]"}""", ExportRedactionPolicy.redactText("""{"key":"abc"}"""))
    }

    @Test
    fun bearerUrlAndCardValuesAreRedacted() {
        val result = ExportRedactionPolicy.redactText(
            "Bearer abc.def-123 at https://example.test/path?api_key=sentinel card 4111 1111 1111 1111",
        )

        assertFalse(result, result.contains("abc.def-123"))
        assertFalse(result, result.contains("sentinel"))
        assertFalse(result, result.contains("4111"))
        assertTrue(result, result.contains("https://example.test/path?[REDACTED]"))
        assertTrue(result, result.contains("[REDACTED-CARD]"))
    }

    @Test
    fun ordinaryTextIsLeftAlone() {
        listOf(
            "hotkey=F5 monkey=banana max_tokens=100 author: Bob keyboard: shown tokens: 12",
            "sort_key=5 primary_key=1",
            "%access_token == x",
        ).forEach { text -> assertEquals(text, ExportRedactionPolicy.redactText(text)) }
    }

    @Test
    fun longHostileInputsFinish() {
        assertEquals("password=[REDACTED]", ExportRedactionPolicy.redactText("password=\"" + "\\a".repeat(40_000)))
        val separators = "a_".repeat(20_000) + " b-".repeat(20_000)
        assertEquals(separators, ExportRedactionPolicy.redactText(separators))
    }

    @Test
    fun aSecretTemplateReferenceFailsClosed() {
        val result = ExportRedactionPolicy.sanitizeActionArguments(
            actionType = "log",
            args = mapOf("message" to "Token is {{ global.API_TOKEN }}"),
            context = ExportRedactionPolicy.Context(secretNames = setOf("API_TOKEN")),
        )

        assertEquals(ExportRedactionPolicy.REDACTED, result.args.getValue("message"))
    }
}
