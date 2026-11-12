package com.opentasker.core.engine

import android.content.ContextWrapper
import com.opentasker.core.actions.registerActionMetadata
import com.opentasker.core.model.ActionSpec
import com.opentasker.core.model.Task
import com.opentasker.core.registerCoreRuntime
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Join Text's array field written as `%parts` names the array, the way an output name does. Filled
 * in first, it was the first part's text (or the text a Variable Split split), so nothing joined.
 */
class TextJoinArrayNameTest {
    private val recorded = mutableListOf<String>()

    @Before
    fun setUp() {
        registerActionMetadata()
        registerCoreRuntime()
        ActionRegistry.register(object : Action {
            override val id = "test.textjoin.record"
            override val category = ActionCategory.FLOW
            override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
                recorded += args.getValue("v")
                return ActionResult.Success
            }
        })
    }

    private fun record(text: String) = ActionSpec(type = "test.textjoin.record", args = mapOf("v" to text))

    private fun join(array: String) =
        ActionSpec(type = "text.join", args = mapOf("array" to array, "delimiter" to "|", "var" to "%joined"))

    private fun run(vararg actions: ActionSpec): List<String> = runBlocking {
        recorded.clear()
        val report = TaskRunner(ActionContext(ContextWrapper(null), VariableStore())).run(Task(id = 1, name = "Join", actions = actions.toList()))
        assertTrue(report.results.toString(), report.success)
        recorded.toList()
    }

    @Test
    fun aSplitTextArrayJoinsByItsPercentName() {
        val split = ActionSpec(type = "text.split", args = mapOf("source" to "a,b,c", "delimiter" to ",", "var" to "%parts"))

        assertEquals(listOf("a|b|c"), run(split, join("%parts"), record("%joined")))
        assertEquals(listOf("a|b|c"), run(split, join("parts"), record("%joined")))
        assertEquals(listOf("a|b|c"), run(split, join("{{ array.parts }}"), record("%joined")))
    }

    @Test
    fun aVariableSplitArrayJoinsByItsPercentName() {
        assertEquals(
            listOf("x|y"),
            run(
                ActionSpec(type = "var.set", args = mapOf("name" to "%text", "value" to "x y")),
                ActionSpec(type = "var.split", args = mapOf("name" to "text", "splitter" to " ")),
                join("%text"),
                record("%joined"),
            ),
        )
    }

    @Test
    fun aNameBuiltFromPartsStillFillsIn() {
        assertEquals(
            listOf("a|b|c"),
            run(
                ActionSpec(type = "text.split", args = mapOf("source" to "a,b,c", "delimiter" to ",", "var" to "%parts")),
                ActionSpec(type = "var.set", args = mapOf("name" to "%rest", "value" to "arts")),
                join("p%rest"),
                record("%joined"),
            ),
        )
    }
}
