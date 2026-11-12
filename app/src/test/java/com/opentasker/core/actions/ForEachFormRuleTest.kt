package com.opentasker.core.actions

import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/** For Each saves with an array or with values, and asks for one when it has neither. */
class ForEachFormRuleTest {

    @Before
    fun setUp() {
        registerActionMetadata()
    }

    private fun issues(vararg values: Pair<String, String>) =
        ActionFieldPolicy.validateForm(ActionMetadataRegistry.get("flow.foreach")!!, mapOf(*values))

    @Test
    fun neitherAnArrayNorValuesMarksBothRequired() {
        val required = ActionFieldPolicy.Issue(ActionFieldPolicy.Error.REQUIRED)

        assertEquals(mapOf("list" to required, "values" to required), issues("var" to "i"))
        assertEquals(mapOf("list" to required, "values" to required), issues("list" to " ", "values" to ""))
    }

    @Test
    fun anArrayOrValuesIsEnough() {
        assertEquals(emptyMap<String, ActionFieldPolicy.Issue>(), issues("list" to "names"))
        assertEquals(emptyMap<String, ActionFieldPolicy.Issue>(), issues("values" to "1:5"))
        assertEquals(emptyMap<String, ActionFieldPolicy.Issue>(), issues("in" to "names"))
    }
}
