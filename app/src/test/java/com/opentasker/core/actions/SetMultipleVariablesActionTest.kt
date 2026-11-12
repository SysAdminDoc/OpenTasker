package com.opentasker.core.actions

import android.content.ContextWrapper
import com.opentasker.core.engine.ActionContext
import com.opentasker.core.engine.ActionResult
import com.opentasker.core.engine.VariableStore
import com.opentasker.core.registerCoreRuntime
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Tasker's Multiple Variables Set, run on its own with the args as the engine hands them over. */
class SetMultipleVariablesActionTest {
    private val variables = VariableStore()
    private val logs = mutableListOf<String>()

    @Before
    fun setUp() {
        registerCoreRuntime()
    }

    private fun run(vararg args: Pair<String, String>): ActionResult = runBlocking {
        SetMultipleVariablesAction().run(ActionContext(ContextWrapper(null), variables, logger = { logs += it }), mapOf(*args))
    }

    @Test
    fun eachNameGetsTheValueInTheSamePlaceSplitOnNewLines() {
        assertEquals(ActionResult.Success, run("names" to "%Sunrise\n%Sunset", "values" to "1750843560\n1750897860"))
        assertEquals("1750843560", variables.get("Sunrise"))
        assertEquals("1750897860", variables.get("Sunset"))
    }

    @Test
    fun oneLineSplitsOnCommasAndValuesKeepTheirSpaces() {
        run("names" to "%a,%b", "values" to "1, two")
        assertEquals("1", variables.get("a"))
        assertEquals(" two", variables.get("b"))
    }

    @Test
    fun writtenSplittersWin() {
        run("names" to "%a %b", "names_splitter" to " ", "values" to "x|y,z", "values_splitter" to "|")
        assertEquals("x", variables.get("a"))
        assertEquals("y,z", variables.get("b"))
    }

    @Test
    fun aNamePastTheLastValueIsUnset() {
        variables.set("c", "old")
        run("names" to "%a\n%b\n%c", "values" to "1\n2")
        assertEquals("2", variables.get("b"))
        assertNull(variables.get("c"))
    }

    @Test
    fun noValuesAtAllUnsetsEveryNameWithoutOne() {
        variables.set("a", "old")
        run("names" to "%a")
        assertNull(variables.get("a"))
    }

    @Test
    fun withNoValuesANameCarriesItsOwnAfterTheValuesSplitter() {
        variables.set("who", "Sam")
        run("names" to "%count,0\n%greeting,hello %who\n%gone")
        assertEquals("0", variables.get("count"))
        assertEquals("hello Sam", variables.get("greeting"))
        assertNull(variables.get("gone"))
    }

    @Test
    fun anInlineValueFromASecretStaysSecret() {
        variables.set("api", "s3cr3t", sensitive = true)
        run("names" to "%copy,%api\n%plain,1")
        assertEquals("s3cr3t", variables.get("copy"))
        assertTrue(variables.isSensitive("copy"))
        assertFalse(variables.isSensitive("plain"))
    }

    @Test
    fun keepExistingLeavesASetVariableAloneAndAnUnsetOneGetsItsValue() {
        variables.set("a", "kept")
        run("names" to "%a\n%b", "values" to "1\n2", "keep_existing" to "true")
        assertEquals("kept", variables.get("a"))
        assertEquals("2", variables.get("b"))
    }

    @Test
    fun keepExistingAlsoLeavesANameWithoutAValueSet() {
        variables.set("b", "kept")
        run("names" to "%a\n%b", "values" to "1", "keep_existing" to "true")
        assertEquals("kept", variables.get("b"))
    }

    @Test
    fun aNameListedTwiceWithKeepExistingKeepsItsFirstValue() {
        run("names" to "%a\n%a", "values" to "first\nsecond", "keep_existing" to "true")
        assertEquals("first", variables.get("a"))
    }

    @Test
    fun aBlankLineStillTakesItsPlace() {
        run("names" to "%a\n\n%c", "values" to "1\n2\n3")
        assertEquals("1", variables.get("a"))
        assertEquals("3", variables.get("c"))
    }

    @Test
    fun aNameBuiltFromAnotherVariableIsFilledInFirst() {
        variables.set("n", "2")
        run("names" to "%item%n", "values" to "two")
        assertEquals("two", variables.get("item2"))
    }

    @Test
    fun aNameThatIsntAVariableFailsTheStepAfterTheRestAreSet() {
        val result = run("names" to "%a\nnot a name\n%c", "values" to "1\n2\n3")
        assertEquals("invalid variable name(s): not a name", (result as ActionResult.Failure).message)
        assertEquals("1", variables.get("a"))
        assertEquals("3", variables.get("c"))
    }

    @Test
    fun noNamesFailsTheStep() {
        assertTrue(run("names" to " \n ", "values" to "1") is ActionResult.Failure)
    }

    @Test
    fun theLogNamesTheVariablesButNeverTheirValues() {
        run("names" to "%token\n%b\n%c", "values" to "hunter2\nplain")
        val line = logs.single()
        assertEquals("var.set_multiple -> set %token, %b; unset %c", line)
        assertFalse(line.contains("hunter2"))
    }
}
