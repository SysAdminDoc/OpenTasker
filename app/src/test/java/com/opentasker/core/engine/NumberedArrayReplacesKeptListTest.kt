package com.opentasker.core.engine

import android.content.ContextWrapper
import com.opentasker.core.actions.registerActionMetadata
import com.opentasker.core.model.ActionSpec
import com.opentasker.core.model.Task
import com.opentasker.core.registerCoreRuntime
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * A step that sets a whole numbered array (Replace Text's matches, Variable Split, Array Clear)
 * drops the list Split Text kept under that name. Left in place, it hid the new elements, since
 * Join Text and For Each read a kept list first. Replace Text also empties its array on
 * every failure after the name is read, as Tasker does. Globals keep the elements past the run.
 */
class NumberedArrayReplacesKeptListTest {

    @Before
    fun setUp() {
        registerActionMetadata()
        registerCoreRuntime()
    }

    private fun set(name: String, value: String) = ActionSpec(type = "var.set", args = mapOf("name" to name, "value" to value))

    private fun splitText(source: String, name: String) =
        ActionSpec(type = "text.split", args = mapOf("source" to source, "delimiter" to ",", "var" to name))

    private fun replace(pattern: String, array: String = "%Found") = ActionSpec(
        type = "text.replace",
        args = mapOf("source" to "%Text", "pattern" to pattern, "replacement" to "#", "var" to "%Text", "matches" to array),
    )

    private fun run(store: VariableStore, vararg actions: ActionSpec): Boolean = runBlocking {
        TaskRunner(ActionContext(ContextWrapper(null), store)).run(Task(id = 1, name = "Arrays", actions = actions.toList())).success
    }

    @Test
    fun replaceTextMatchesReplaceAKeptList() {
        val store = VariableStore()

        assertTrue(run(store, splitText("a,b,c", "%Found"), set("%Text", "x1y2"), replace("\\d")))

        assertEquals(listOf("1", "2"), store.getArrayItems("Found"))
    }

    @Test
    fun aFailedReplaceTextDropsAKeptList() {
        val store = VariableStore()

        assertFalse(run(store, splitText("a,b", "%Found"), set("%Text", "x1"), replace("(")))

        assertNull(store.getArrayItems("Found"))
    }

    @Test
    fun aBlankPatternStillEmptiesTheArray() {
        val store = VariableStore()

        assertFalse(run(store, set("%Found1", "old"), set("%Text", "x1"), replace("")))

        assertNull(store.globalSnapshot()["Found1"])
    }

    @Test
    fun tooManyMatchesEmptiesTheArray() {
        val store = VariableStore()

        assertFalse(run(store, set("%Found1", "old"), set("%Text", "1".repeat(10_001)), replace("\\d")))

        assertNull(store.globalSnapshot()["Found1"])
    }

    @Test
    fun anElementNameTooLongEmptiesTheArray() {
        // 63 characters, so the first element's name fits in 64 and the tenth's doesn't.
        val base = "F" + "x".repeat(62)
        val store = VariableStore()

        assertFalse(run(store, set("%${base}1", "old"), set("%Text", "0123456789"), replace("\\d", "%$base")))

        assertNull(store.globalSnapshot()["${base}1"])
    }

    @Test
    fun variableSplitReplacesAKeptList() {
        val store = VariableStore()

        assertTrue(
            run(
                store,
                splitText("a,b,c", "%Parts"),
                ActionSpec(type = "var.split", args = mapOf("name" to "%Parts", "values" to "x y", "splitter" to " ")),
            ),
        )

        assertEquals(listOf("x", "y"), store.getArrayItems("Parts"))
    }

    @Test
    fun arrayClearDropsAKeptList() {
        val store = VariableStore()

        assertTrue(run(store, splitText("a,b", "%Parts"), ActionSpec(type = "array.clear", args = mapOf("array" to "%Parts"))))

        assertNull(store.getArrayItems("Parts"))
    }
}
