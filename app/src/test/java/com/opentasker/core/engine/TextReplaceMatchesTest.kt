package com.opentasker.core.engine

import android.content.ContextWrapper
import com.opentasker.core.actions.registerActionMetadata
import com.opentasker.core.model.ActionSpec
import com.opentasker.core.model.Task
import com.opentasker.core.registerCoreRuntime
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Replace Text's First match only, Store matches in array and Only find matches, which run
 * Tasker's One Match Only, Store Matches In Array and Replace Matches off. The array is emptied and
 * then holds each whole match as %found1, %found2, ...
 */
class TextReplaceMatchesTest {
    private val recorded = mutableListOf<String>()

    @Before
    fun setUp() {
        registerActionMetadata()
        registerCoreRuntime()
        ActionRegistry.register(object : Action {
            override val id = "test.textreplace.record"
            override val category = ActionCategory.FLOW
            override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
                recorded += args.getValue("v")
                return ActionResult.Success
            }
        })
    }

    private fun record(text: String) = ActionSpec(type = "test.textreplace.record", args = mapOf("v" to text))

    private fun set(name: String, value: String) = ActionSpec(type = "var.set", args = mapOf("name" to name, "value" to value))

    private fun replace(pattern: String, vararg extra: Pair<String, String>, with: String = "#") = ActionSpec(
        type = "text.replace",
        args = mapOf("source" to "%text", "pattern" to pattern, "replacement" to with, "var" to "%text") + extra,
    )

    private fun run(vararg actions: ActionSpec, store: VariableStore = VariableStore()): List<String> = runBlocking {
        recorded.clear()
        val report = TaskRunner(ActionContext(ContextWrapper(null), store)).run(Task(id = 1, name = "Replace", actions = actions.toList()))
        assertTrue(report.results.toString(), report.success)
        recorded.toList()
    }

    private fun fails(vararg actions: ActionSpec): Boolean = runBlocking {
        !TaskRunner(ActionContext(ContextWrapper(null), VariableStore())).run(Task(id = 1, name = "Replace", actions = actions.toList())).success
    }

    @Test
    fun everyMatchIsStoredAndReplaced() {
        assertEquals(
            listOf("a# b# c#|1|22|333|%found4"),
            run(set("%text", "a1 b22 c333"), replace("\\d+", "matches" to "%found"), record("%text|%found1|%found2|%found3|%found4")),
        )
    }

    @Test
    fun firstMatchOnlyStoresAndReplacesJustTheFirst() {
        assertEquals(
            listOf("a# b22 c333|1|%found2"),
            run(set("%text", "a1 b22 c333"), replace("\\d+", "matches" to "%found", "first_only" to "true"), record("%text|%found1|%found2")),
        )
        assertEquals(
            listOf("a<1> b22"),
            run(set("%text", "a1 b22"), replace("(\\d+)", "first_only" to "true", with = "<\$1>"), record("%text")),
        )
    }

    @Test
    fun onlyFindingMatchesLeavesTheVariableAsItIs() {
        assertEquals(
            listOf("a1 b22|1|22"),
            run(set("%text", "a1 b22"), replace("\\d+", "matches" to "%found", "search_only" to "true"), record("%text|%found1|%found2")),
        )
    }

    @Test
    fun theOldElementsGoEvenWhenNothingMatches() {
        assertEquals(
            listOf("x|%found1 %found2 %found5"),
            run(
                set("%found1", "old"), set("%found2", "old"), set("%found5", "old"), set("%text", "x"),
                replace("\\d+", "matches" to "%found"),
                record("%text|%found1 %found2 %found5"),
            ),
        )
    }

    @Test
    fun aGlobalArrayHoldsItsMatchesAsGlobals() {
        val store = VariableStore()

        run(set("%text", "a1 b2"), replace("\\d", "matches" to "%Found", "search_only" to "true"), store = store)

        assertEquals(mapOf("Found1" to "1", "Found2" to "2"), store.globalSnapshot().filterKeys { it.startsWith("Found") })
    }

    @Test
    fun theArrayNameIsReadAsWrittenNeverFilledIn() {
        assertEquals(
            listOf("1|%other1"),
            run(
                set("%which", "%other"), set("%text", "a1"),
                replace("\\d", "matches" to "%which", "search_only" to "true"),
                record("%which1|%other1"),
            ),
        )
    }

    @Test
    fun aBadPatternOrArrayNameFailsTheStep() {
        assertTrue(fails(set("%text", "a"), replace("(", "search_only" to "true")))
        assertTrue(fails(set("%text", "a"), replace("a", "matches" to "%1x")))
        assertFalse(fails(set("%text", "a"), replace("a", "search_only" to "true")))
    }
}
