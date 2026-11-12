package com.opentasker.core.actions

import android.content.ContextWrapper
import com.opentasker.core.engine.ActionContext
import com.opentasker.core.engine.VariableStore
import com.opentasker.core.registerCoreRuntime
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test

/** A space is a splitter or joiner someone means, so saving the step keeps it. */
class WhitespaceSplitterSaveTest {

    @Before
    fun setUp() {
        registerActionMetadata()
        registerCoreRuntime()
    }

    private fun save(actionId: String, existing: Map<String, String>, edited: Map<String, String>): Map<String, String> =
        mergeActionArguments(existing, ActionMetadataRegistry.get(actionId)!!.fields, edited)

    @Test
    fun aSpaceSplitterSurvivesASave() {
        val saved = save("var.set_multiple", emptyMap(), mapOf("names" to "%a %b", "values" to "1 2", "names_splitter" to " ", "values_splitter" to " "))
        assertEquals(" ", saved["names_splitter"])
        assertEquals(" ", saved["values_splitter"])
    }

    @Test
    fun reSavingAnImportedSpaceSplitterKeepsIt() {
        val imported = mapOf("names" to "%a %b", "values" to "1 2", "names_splitter" to " ", "values_splitter" to " ")
        assertEquals(imported, save("var.set_multiple", imported, imported))
    }

    @Test
    fun anEmptySplitterIsStillCleared() {
        val saved = save("var.set_multiple", mapOf("names" to "%a", "names_splitter" to ";"), mapOf("names" to "%a", "names_splitter" to ""))
        assertFalse("names_splitter" in saved)
    }

    @Test
    fun aBlankOrdinaryFieldIsStillCleared() {
        val saved = save("var.set_multiple", mapOf("names" to "%a", "values" to "1"), mapOf("names" to "%a", "values" to "  "))
        assertFalse("values" in saved)
    }

    @Test
    fun theOtherSplittersAndJoinersKeepASpaceToo() {
        assertEquals(" ", save("var.split", emptyMap(), mapOf("name" to "%parts", "splitter" to " "))["splitter"])
        assertEquals(" ", save("var.join", emptyMap(), mapOf("name" to "%parts", "joiner" to " "))["joiner"])
        assertEquals(" ", save("text.split", emptyMap(), mapOf("source" to "a b", "delimiter" to " "))["delimiter"])
        assertEquals(" ", save("text.join", emptyMap(), mapOf("array" to "parts", "delimiter" to " "))["delimiter"])
    }

    @Test
    fun theSavedSpaceSplitsAndJoinsWhenTheStepsRun() = runBlocking {
        val variables = VariableStore()
        val ctx = ActionContext(ContextWrapper(null), variables)
        SetMultipleVariablesAction().run(ctx, save("var.set_multiple", emptyMap(), mapOf("names" to "%a %b", "values" to "1 2", "names_splitter" to " ", "values_splitter" to " ")))
        assertEquals("1", variables.get("a"))
        assertEquals("2", variables.get("b"))

        TextSplitAction().run(ctx, save("text.split", emptyMap(), mapOf("source" to "x y z", "delimiter" to " ", "var" to "parts")))
        assertEquals("3", variables.get("parts_count"))
        TextJoinAction().run(ctx, save("text.join", emptyMap(), mapOf("array" to "parts", "delimiter" to " ", "var" to "joined")))
        assertEquals("x y z", variables.get("joined"))
    }
}
