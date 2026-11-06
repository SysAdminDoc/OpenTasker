package com.opentasker.core.expressions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TemplateExpressionEngineTest {
    private val engine = TemplateExpressionEngine()

    @Test
    fun taskScopeOverridesEventAndGlobalScope() {
        val result = engine.expand(
            template = "Hello {{ name }} from {{ event.name }} and {{ global.name }}",
            scope = TemplateScope(
                global = mapOf("name" to "Global"),
                event = mapOf("name" to "Event"),
                task = mapOf("name" to "Task"),
            ),
        )

        assertEquals("Hello Task from Event and Global", result.value)
        assertEquals(TemplateValueSource.TASK, result.traces.first().source)
    }

    @Test
    fun urlEncodingKeepsANumbersPlusAndSpacesSafeInAnAddress() {
        val result = engine.expand(
            template = "https://example.com/?q={{ event.incoming_number | urlencode }}",
            scope = TemplateScope(
                event = mapOf("incoming_number" to "+1 555&x=y"),
                sensitiveEvent = setOf("incoming_number"),
            ),
        )

        assertEquals("https://example.com/?q=%2B1%20555%26x%3Dy", result.value)
        assertTrue(
            "Encoding a caller's number must not launder it out of the sensitive set",
            result.traces.single().isSecretDerived,
        )
    }

    @Test
    fun defaultFallbackFillsMissingValues() {
        val result = engine.expand(
            template = "Notify {{ recipient | default:\"Owner\" }}",
            scope = TemplateScope(),
        )

        assertEquals("Notify Owner", result.value)
        assertEquals(TemplateValueSource.DEFAULT, result.traces.single().source)
        assertTrue(result.warnings.isEmpty())
    }

    @Test
    fun stringAndMathFunctionsAreAppliedInOrder() {
        val result = engine.expand(
            template = "{{ label | trim | upper }} {{ count | add:5 | mul:2 }} {{ score | round }}",
            scope = TemplateScope(
                task = mapOf(
                    "label" to "  open tasker  ",
                    "count" to "7",
                    "score" to "7.6",
                ),
            ),
        )

        assertEquals("OPEN TASKER 24 8", result.value)
        assertEquals(listOf("trim", "upper"), result.traces[0].functions)
    }

    @Test
    fun jsonPathsReadNestedObjectFields() {
        val result = engine.expand(
            template = "User {{ payload.user.name }} count {{ payload.items[#] }} first {{ payload.items[0].label }}",
            scope = TemplateScope(
                event = mapOf(
                    "payload" to """{"user":{"name":"Ada"},"items":[{"label":"one"},{"label":"two"}]}""",
                ),
            ),
        )

        assertEquals("User Ada count 2 first one", result.value)
    }

    @Test
    fun arrayReferencesSupportIndexCountAndJoin() {
        val result = engine.expand(
            template = "{{ items[1] }} / {{ items[#] }} / {{ items | join:\";\" }}",
            scope = TemplateScope(
                arrays = mapOf("items" to listOf("one", "two", "three")),
            ),
        )

        assertEquals("two / 3 / one;two;three", result.value)
        assertEquals(TemplateValueSource.ARRAY, result.traces.first().source)
    }

    @Test
    fun unknownFunctionsFailClosedByPreservingToken() {
        val result = engine.expand(
            template = "Value {{ name | shell:\"rm -rf\" }}",
            scope = TemplateScope(task = mapOf("name" to "OpenTasker")),
        )

        assertEquals("Value {{ name | shell:\"rm -rf\" }}", result.value)
        assertTrue(result.warnings.any { it.contains("Unknown template function") })
        assertEquals(TemplateValueSource.MISSING, result.traces.single().source)
    }

    @Test
    fun regexFunctionsAreExplicitlyUnsupported() {
        val result = engine.expand(
            template = "{{ title | regex:\"(Open).*\" }}",
            scope = TemplateScope(task = mapOf("title" to "OpenTasker")),
        )

        assertEquals("{{ title | regex:\"(Open).*\" }}", result.value)
        assertTrue(result.warnings.any { it.contains("Regex template function 'regex' is intentionally unsupported") })
    }

    @Test
    fun dateFormatsEpochMillis() {
        // 2000-06-15 12:00:00 UTC — unambiguous in any timezone
        val result = engine.expand(
            template = "{{ ts | date:'yyyy' }}",
            scope = TemplateScope(task = mapOf("ts" to "961070400000")),
        )
        assertEquals("2000", result.value)
        assertTrue(result.warnings.isEmpty())
    }

    @Test
    fun dateDefaultPatternFormatsCurrentTime() {
        val result = engine.expand(
            template = "{{ ts | date }}",
            scope = TemplateScope(task = mapOf("ts" to System.currentTimeMillis().toString())),
        )
        assertTrue("formatted date should not be empty", result.value.isNotBlank())
        assertTrue(result.warnings.isEmpty())
    }

    @Test
    fun dateRejectsInvalidPattern() {
        val result = engine.expand(
            template = "{{ ts | date:'QQQQQ' }}",
            scope = TemplateScope(task = mapOf("ts" to "0")),
        )
        assertTrue(result.warnings.any { it.contains("Invalid date pattern") })
    }

    @Test
    fun dateRejectsNonNumericValue() {
        val result = engine.expand(
            template = "{{ ts | date:'yyyy' }}",
            scope = TemplateScope(task = mapOf("ts" to "not-a-number")),
        )
        assertTrue(result.warnings.any { it.contains("numeric epoch-millis") })
    }

    @Test
    fun resolvedValueLimitPreventsExpansionAbuse() {
        val limitedEngine = TemplateExpressionEngine(
            TemplateExpressionLimits(maxResolvedValueLength = 8),
        )
        val result = limitedEngine.expand(
            template = "Payload {{ payload }}",
            scope = TemplateScope(task = mapOf("payload" to "0123456789")),
        )

        assertEquals("Payload {{ payload }}", result.value)
        assertTrue(result.warnings.any { it.contains("Resolved value exceeds") })
    }
}
