package com.opentasker.core.engine

import android.content.ContextWrapper
import com.opentasker.core.actions.registerActionMetadata
import com.opentasker.core.model.ActionSpec
import com.opentasker.core.model.Task
import com.opentasker.core.registerCoreRuntime
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Variable Clear and the arrays steps like Split Text keep apart from plain variables: a cleared
 * name takes the array kept under it along, so `%parts(#)` reads 0 and a For loop over it runs none.
 */
class VariableClearStoredArrayTest {
    private val recorded = mutableListOf<String>()

    @Before
    fun setUp() {
        registerActionMetadata()
        registerCoreRuntime()
        ActionRegistry.register(object : Action {
            override val id = "test.clearstored.record"
            override val category = ActionCategory.FLOW
            override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
                recorded += args.getValue("v")
                return ActionResult.Success
            }
        })
    }

    private fun record(text: String) = ActionSpec(type = "test.clearstored.record", args = mapOf("v" to text))

    private fun split(name: String, source: String = "a,b,c") =
        ActionSpec(type = "text.split", args = mapOf("source" to source, "delimiter" to ",", "var" to name))

    private fun clear(name: String, vararg switches: String) =
        ActionSpec(type = "var.clear", args = mapOf("name" to name) + switches.associateWith { "true" })

    private fun run(vararg actions: ActionSpec, store: VariableStore = VariableStore()): List<String> = runBlocking {
        recorded.clear()
        val report = TaskRunner(ActionContext(ContextWrapper(null), store)).run(Task(id = 1, name = "Clear", actions = actions.toList()))
        assertTrue(report.results.toString(), report.success)
        recorded.toList()
    }

    @Test
    fun clearingTheNameDropsItsArray() {
        val store = VariableStore()

        assertEquals(
            listOf("3", "0"),
            run(split("%parts"), record("%parts(#)"), clear("%parts"), record("%parts(#)"), store = store),
        )
        assertNull(store.getArrayItems("parts"))
    }

    @Test
    fun aPatternDropsEveryArrayWhoseNameMatches() {
        assertEquals(
            listOf("0|2"),
            run(split("%parts"), split("%words", "x,y"), clear("%parts*", "pattern"), record("%parts(#)|%words(#)")),
        )
    }

    @Test
    fun clearingEveryLocalLeavesAGlobalArrayUnlessClearAll() {
        assertEquals(
            listOf("0|3", "0|0"),
            run(
                split("%parts"), split("%Parts"),
                clear(""), record("%parts(#)|%Parts(#)"),
                clear("", "clear_all"), record("%parts(#)|%Parts(#)"),
            ),
        )
    }

    @Test
    fun localVariablesOnlyLeavesAGlobalArray() {
        assertEquals(
            listOf("0|3"),
            run(split("%parts"), split("%Parts"), clear("%*arts", "pattern", "local_only"), record("%parts(#)|%Parts(#)")),
        )
    }

    @Test
    fun anArrayWhoseNameDoesntMatchStays() {
        assertEquals(
            listOf("3"),
            run(split("%parts"), clear("%part"), clear("%x*", "pattern"), record("%parts(#)")),
        )
    }
}
