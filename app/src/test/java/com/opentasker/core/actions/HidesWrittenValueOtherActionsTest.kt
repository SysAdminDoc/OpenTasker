package com.opentasker.core.actions

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A step other than the variable setters hides the value it writes to a secret-looking variable it
 * names, and leaves alone a secret-looking output it doesn't name.
 */
class HidesWrittenValueOtherActionsTest {

    @Test
    fun aSecretNamedVariableTheStepNamesIsHidden() {
        assertTrue(ActionArgumentSensitivity.hidesWrittenValue("var.convert", mapOf("name" to "%api_token", "function" to "lower_case"), "api_token"))
        assertTrue(ActionArgumentSensitivity.hidesWrittenValue("var.convert", mapOf("name" to "api_token", "function" to "lower_case"), "api_token"))
        assertTrue(ActionArgumentSensitivity.hidesWrittenValue("var.convert", mapOf("name" to "%raw", "var" to "%API_TOKEN"), "API_TOKEN"))
    }

    @Test
    fun aLongerNameIsNotTheSameVariable() {
        assertFalse(ActionArgumentSensitivity.hidesWrittenValue("var.convert", mapOf("name" to "%api_token2"), "api_token"))
    }

    @Test
    fun aSecretNamedOutputTheStepDoesNotNameStaysVisible() {
        assertFalse(ActionArgumentSensitivity.hidesWrittenValue("http.request", mapOf("url" to "https://example.com"), "http_headers"))
    }

    @Test
    fun anOrdinaryVariableTheStepNamesStaysVisible() {
        assertFalse(ActionArgumentSensitivity.hidesWrittenValue("var.convert", mapOf("name" to "%greeting"), "greeting"))
    }
}
