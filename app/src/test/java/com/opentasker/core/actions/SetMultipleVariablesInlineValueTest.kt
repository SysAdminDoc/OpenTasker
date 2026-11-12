package com.opentasker.core.actions

import android.content.ContextWrapper
import com.opentasker.core.engine.ActionContext
import com.opentasker.core.engine.ActionResult
import com.opentasker.core.engine.VariableStore
import com.opentasker.core.registerCoreRuntime
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * A value written in the names field, the way Tasker 6.7.8 reads it: after the Values splitter, only
 * while Values is empty, and filled in the same two stages as any other argument.
 */
class SetMultipleVariablesInlineValueTest {
    private val variables = VariableStore()

    @Before
    fun setUp() {
        registerCoreRuntime()
    }

    private fun run(vararg args: Pair<String, String>): ActionResult = runBlocking {
        SetMultipleVariablesAction().run(ActionContext(ContextWrapper(null), variables), mapOf(*args))
    }

    @Test
    fun aTypedValuesSplitterEndsTheNameAndTheValueKeepsItsSpaces() {
        run("names" to "%a=1\n%b= two ", "values_splitter" to "=")
        assertEquals("1", variables.get("a"))
        assertEquals(" two ", variables.get("b"))
    }

    @Test
    fun theValueKeepsALaterSplitter() {
        run("names" to "%url=https://example.com/?q=1", "values_splitter" to "=")
        assertEquals("https://example.com/?q=1", variables.get("url"))
    }

    @Test
    fun aSpaceIsNotTheSplitterSoTheLineIsOneBadName() {
        val result = run("names" to "%count 0")
        assertTrue(result is ActionResult.Failure)
        assertNull(variables.get("count"))
    }

    @Test
    fun aSplitterThatCanBePartOfANameIsReadAsPartOfIt() {
        variables.set("a_b", "old")
        run("names" to "%a_b", "values_splitter" to "_")
        assertNull(variables.get("a_b"))
        assertNull(variables.get("a"))
    }

    @Test
    fun withValuesASplitterInANameIsNotReadAsAValue() {
        val result = run("names" to "%a=1\n%b", "values" to "x=y", "values_splitter" to "=")
        assertTrue(result is ActionResult.Failure)
        assertNull(variables.get("a"))
        assertEquals("y", variables.get("b"))
    }

    @Test
    fun bracesAVariableHoldsStayAsText() {
        variables.set("raw", "{{ x }}")
        variables.set("x", "9")
        run("names" to "%copy,%raw\n%n,1")
        assertEquals("{{ x }}", variables.get("copy"))
    }

    @Test
    fun aTemplateIsWorkedOutLikeAnyOtherArgument() {
        variables.set("name", "sam")
        run("names" to "%shout,{{ name | upper }}\n%n,1")
        assertEquals("SAM", variables.get("shout"))
    }

    @Test
    fun aTemplateThatReadsASecretStaysSecret() {
        variables.set("api", "s3cr3t", sensitive = true)
        run("names" to "%copy,{{ api | upper }}\n%n,1")
        assertEquals("S3CR3T", variables.get("copy"))
        assertTrue(variables.isSensitive("copy"))
    }
}
