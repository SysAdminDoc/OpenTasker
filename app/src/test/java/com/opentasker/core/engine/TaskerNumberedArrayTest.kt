package com.opentasker.core.engine

import android.content.ContextWrapper
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
 * Tasker keeps an array as numbered variables (%arr1, %arr2, ...). Imported tasks split, join,
 * push and pop them and read them through the (#), (<) and (a:b) forms, so these follow Tasker.
 */
class TaskerNumberedArrayTest {

    private val recorded = mutableListOf<String>()

    @Before
    fun setUp() {
        registerCoreRuntime()
        recorded.clear()
        ActionRegistry.register(object : Action {
            override val id = "test.array.record"
            override val category = ActionCategory.FLOW
            override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
                recorded += args["v"].orEmpty()
                return ActionResult.Success
            }
        })
    }

    private fun act(variables: VariableStore, id: String, vararg args: Pair<String, String>): ActionResult =
        runBlocking { ActionRegistry.get(id)!!.run(ActionContext(ContextWrapper(null), variables), mapOf(*args)) }

    private fun store(vararg values: Pair<String, String>) =
        VariableStore().apply { values.forEach { (name, value) -> set(name, value) } }

    private fun VariableStore.elements(base: String): Map<Int, String> = numberedItems(base).toMap()

    @Test
    fun numberedItemsAreTheBaseNameFollowedByAnIndex() {
        val variables = store(
            "arr1" to "a", "arr2" to "b", "arr10" to "j",
            "arr" to "base", "arr01" to "zero-padded", "arrx" to "x", "array1" to "other array",
        )

        assertEquals(mapOf(1 to "a", 2 to "b", 10 to "j"), variables.elements("arr"))
        assertEquals(mapOf(1 to "a", 2 to "b", 10 to "j"), variables.elements("%arr"))
    }

    @Test
    fun aSubTaskChangingItsCallersArrayChangesItInTheCallersScope() {
        val variables = store("arr1" to "a", "arr2" to "b", "arr3" to "c")
        variables.pushScope()

        variables.replaceNumberedItems("arr", mapOf(1 to ("x" to false), 2 to ("y" to false)))
        variables.popScope()

        assertEquals(mapOf(1 to "x", 2 to "y"), variables.elements("arr"))
    }

    @Test
    fun aSubTaskThatShadowedOneElementStillChangesTheWholeArray() {
        fun shadowed() = store("arr1" to "a", "arr2" to "b", "arr3" to "c").apply {
            pushScope()
            set("arr1", "h")
        }
        val cleared = shadowed()
        val popped = shadowed()
        val split = shadowed().apply { set("arr", "x,y") }

        act(cleared, "array.clear", "array" to "arr")
        act(popped, "array.pop", "array" to "arr", "position" to "1", "to_var" to "got")
        act(split, "var.split", "name" to "arr", "splitter" to ",")

        assertEquals("0", cleared.expand("%arr(#)"))
        assertEquals("h", popped.get("got"))
        assertEquals(mapOf(1 to "b", 2 to "c"), popped.elements("arr"))
        listOf(cleared, popped, split).forEach(VariableStore::popScope)
        assertTrue(cleared.elements("arr").isEmpty())
        assertEquals(mapOf(1 to "b", 2 to "c"), popped.elements("arr"))
        assertEquals(mapOf(1 to "x", 2 to "y"), split.elements("arr"))
    }

    @Test
    fun aPositionVariableThatIsUnsetStopsTheStepInsteadOfUsingTheFirstElement() {
        fun pop(position: String) = Task(
            name = "T",
            actions = listOf(ActionSpec(type = "array.pop", args = mapOf("array" to "%arr", "position" to position, "to_var" to "got"))),
        )
        fun run(task: Task, variables: VariableStore) =
            runBlocking { TaskRunner(ActionContext(ContextWrapper(null), variables)).run(task) }
        fun preflightWarnings(task: Task, variables: Map<String, String>) =
            PreflightRunner.preflightTask(task = task, inputs = PreflightInputs(globalVariables = variables))
                .tasks.single().steps.single().let { it.status to it.warnings }
        val unset = store("arr1" to "a", "arr2" to "b")
        val given = store("arr1" to "a", "arr2" to "b", "pos" to "2")
        val blank = store("arr1" to "a", "arr2" to "b")

        val stopped = run(pop("%pos"), unset)
        assertFalse(stopped.success)
        assertTrue((stopped.results.single() as ActionResult.Failure).message.contains("%pos came out empty"))
        assertEquals(mapOf(1 to "a", 2 to "b"), unset.elements("arr"))
        assertTrue(run(pop("%pos"), given).success)
        assertEquals(mapOf(1 to "a"), given.elements("arr"))
        assertTrue(run(pop(""), blank).success)
        assertEquals(mapOf(1 to "b"), blank.elements("arr"))

        val (status, warnings) = preflightWarnings(pop("%POS"), emptyMap())
        assertEquals(PreflightStepStatus.BLOCKED, status)
        assertTrue(warnings.toString(), warnings.any { "%POS came out empty" in it })
        assertFalse(preflightWarnings(pop("%POS"), mapOf("POS" to "2")).second.any { "came out empty" in it })
    }

    @Test
    fun arrayFormsReadTheNumberedElements() {
        val variables = store("arr1" to "a", "arr2" to "b", "arr3" to "c", "i" to "2")

        val expected = mapOf(
            "%arr(#)" to "3",
            "%arr(#>)" to "1",
            "%arr(#<)" to "3",
            "%arr(>)" to "a",
            "%arr(<)" to "c",
            "%arr(2)" to "b",
            "%arr(9)" to "",
            "%arr()" to "a,b,c",
            "%arr(:)" to "a,b,c",
            "%arr(2:)" to "b,c",
            "%arr(:2)" to "a,b",
            "%arr(3:1)" to "c,b,a",
            "%arr(%i)" to "b",
            "[%arr(1)-%arr(3)]" to "[a-c]",
        )
        expected.forEach { (text, value) -> assertEquals(text, value, variables.expand(text)) }
    }

    @Test
    fun countAndLastIndexDifferWhenAnElementIsUnset() {
        val variables = store("arr1" to "a", "arr2" to "b", "arr5" to "e")

        assertEquals("3", variables.expand("%arr(#)"))
        assertEquals("5", variables.expand("%arr(#<)"))
        assertEquals("a,b,e", variables.expand("%arr()"))
    }

    @Test
    fun aPlainVariableKeepsItsOwnOperators() {
        val variables = store("n" to "5", "word" to "Hi")

        assertEquals("6", variables.expand("%n(+1)"))
        assertEquals("HI", variables.expand("%word(upper)"))
        assertEquals("0", variables.expand("%missing(#)"))
    }

    @Test
    fun splitBreaksOnWhiteSpaceByDefaultAndClearsTheOldElements() {
        val variables = store("words" to "one  two\tthree", "words4" to "stale")

        assertEquals(ActionResult.Success, act(variables, "var.split", "name" to "%words"))

        assertEquals(mapOf(1 to "one", 2 to "two", 3 to "three"), variables.elements("words"))
        assertEquals("one  two\tthree", variables.get("words"))
    }

    @Test
    fun splitTakesTheSplitterAsTextUnlessRegexIsOn() {
        val variables = store("TIME" to "12.30", "mixed" to "a,b;c")

        act(variables, "var.split", "name" to "TIME", "splitter" to ".")
        act(variables, "var.split", "name" to "mixed", "splitter" to "[,;]", "regex" to "true")

        assertEquals(mapOf(1 to "12", 2 to "30"), variables.elements("TIME"))
        assertEquals(mapOf(1 to "a", 2 to "b", 3 to "c"), variables.elements("mixed"))
    }

    @Test
    fun splitDropsTrailingEmptyPartsAndCanUnsetTheSource() {
        val variables = store("csv" to "a,,b,,")

        act(variables, "var.split", "name" to "csv", "splitter" to ",", "delete_base" to "true")

        assertEquals(mapOf(1 to "a", 2 to "", 3 to "b"), variables.elements("csv"))
        assertNull(variables.get("csv"))
    }

    @Test
    fun splittingAnUnsetVariableFails() {
        val variables = store("other1" to "kept")

        assertTrue(act(variables, "var.split", "name" to "other", "splitter" to ",") is ActionResult.Failure)
        assertEquals(mapOf(1 to "kept"), variables.elements("other"))
    }

    @Test
    fun joinPutsTheJoinerOnlyAfterSomethingHasBeenAdded() {
        val variables = store("p1" to "", "p2" to "a", "p3" to "b")

        act(variables, "var.join", "name" to "%p", "joiner" to "|||")

        assertEquals("a|||b", variables.get("p"))
        assertEquals(3, variables.elements("p").size)
    }

    @Test
    fun joinCanUnsetTheParts() {
        val variables = store("p1" to "x", "p2" to "y")

        act(variables, "var.join", "name" to "p", "joiner" to "", "delete_parts" to "true")

        assertEquals("xy", variables.get("p"))
        assertTrue(variables.elements("p").isEmpty())
    }

    @Test
    fun pushInsertsAndMovesTheLaterElementsUp() {
        val variables = store("arr1" to "a", "arr2" to "b", "arr3" to "c")

        act(variables, "array.push", "array" to "%arr", "position" to "2", "value" to "x")

        assertEquals(mapOf(1 to "a", 2 to "x", 3 to "b", 4 to "c"), variables.elements("arr"))
    }

    @Test
    fun pushPastTheEndAppends() {
        val variables = store("arr1" to "a", "arr2" to "b")
        val empty = VariableStore()

        act(variables, "array.push", "array" to "arr", "position" to "99", "value" to "z")
        act(empty, "array.push", "array" to "arr", "position" to "5", "value" to "first")

        assertEquals(mapOf(1 to "a", 2 to "b", 3 to "z"), variables.elements("arr"))
        assertEquals(mapOf(1 to "first"), empty.elements("arr"))
    }

    @Test
    fun fillSpacesMovesOnlyTheElementsBeforeTheFirstGap() {
        val filled = store("arr1" to "a", "arr2" to "b", "arr4" to "d")
        val shifted = store("arr1" to "a", "arr2" to "b", "arr4" to "d")

        act(filled, "array.push", "array" to "arr", "position" to "1", "value" to "x", "fill_spaces" to "true")
        act(shifted, "array.push", "array" to "arr", "position" to "1", "value" to "x")

        assertEquals(mapOf(1 to "x", 2 to "a", 3 to "b", 4 to "d"), filled.elements("arr"))
        assertEquals(mapOf(1 to "x", 2 to "a", 3 to "b", 5 to "d"), shifted.elements("arr"))
    }

    @Test
    fun pushRejectsAPositionBelowOne() {
        val variables = store("arr1" to "a")

        assertTrue(act(variables, "array.push", "array" to "arr", "position" to "0", "value" to "x") is ActionResult.Failure)
        assertEquals(mapOf(1 to "a"), variables.elements("arr"))
    }

    @Test
    fun popRemovesTheElementMovesTheRestDownAndStoresIt() {
        val variables = store("arr1" to "a", "arr2" to "b", "arr3" to "c")

        act(variables, "array.pop", "array" to "arr", "position" to "2", "to_var" to "%got")

        assertEquals(mapOf(1 to "a", 2 to "c"), variables.elements("arr"))
        assertEquals("b", variables.get("got"))
    }

    @Test
    fun popPastTheEndTakesTheLastElement() {
        val variables = store("arr1" to "a", "arr2" to "b", "arr3" to "c")

        act(variables, "array.pop", "array" to "arr", "position" to "9", "to_var" to "got")

        assertEquals(mapOf(1 to "a", 2 to "b"), variables.elements("arr"))
        assertEquals("c", variables.get("got"))
    }

    @Test
    fun poppingAnEmptyArrayLeavesEverythingAlone() {
        val variables = store("got" to "before")

        assertEquals(ActionResult.Success, act(variables, "array.pop", "array" to "arr", "to_var" to "got"))
        assertEquals("before", variables.get("got"))
    }

    @Test
    fun clearUnsetsEveryElementButNotTheBase() {
        val variables = store("arr" to "base", "arr1" to "a", "arr2" to "b", "arr7" to "g")

        act(variables, "array.clear", "array" to "arr")

        assertTrue(variables.elements("arr").isEmpty())
        assertEquals("base", variables.get("arr"))
    }

    @Test
    fun aPoppedGlobalElementIsReportedAsRemovedSoItIsDeletedFromStorage() {
        val variables = store("LOG1" to "a", "LOG2" to "b")
        val before = variables.globalSnapshot()

        act(variables, "array.pop", "array" to "LOG", "position" to "1")
        val after = variables.globalSnapshot()

        assertEquals(mapOf("LOG1" to "b"), after)
        assertEquals(listOf("LOG2"), removedGlobals(before, after))
        assertEquals(listOf("LOG1"), changedGlobals(before, after).map { it.name })
    }

    @Test
    fun aSecretElementKeepsItsFlagWhenItMoves() {
        val variables = VariableStore().apply {
            set("arr1", "secret", sensitive = true)
            set("arr2", "plain")
        }

        act(variables, "array.push", "array" to "arr", "position" to "1", "value" to "new")

        assertFalse(variables.isSensitive("arr1"))
        assertTrue(variables.isSensitive("arr2"))
        assertFalse(variables.isSensitive("arr3"))
        act(variables, "var.join", "name" to "arr", "joiner" to ",")
        assertTrue(variables.isSensitive("arr"))
    }

    @Test
    fun anImportedSplitAndLoopRunsInATask() {
        val variables = store("csv" to "a,b,c")

        val report = runBlocking {
            TaskRunner(ActionContext(ContextWrapper(null), variables)).run(
                Task(
                    name = "T",
                    actions = listOf(
                        ActionSpec(type = "var.split", args = mapOf("name" to "%csv", "splitter" to ",")),
                        ActionSpec(type = FlowControl.FOREACH, args = mapOf("list" to "csv", "var" to "item")),
                        ActionSpec(type = "test.array.record", args = mapOf("v" to "%item")),
                        ActionSpec(type = FlowControl.ENDFOR),
                        ActionSpec(type = "array.pop", args = mapOf("array" to "%csv", "to_var" to "%first")),
                        ActionSpec(type = "test.array.record", args = mapOf("v" to "%csv(#) %first %csv(<)")),
                    ),
                ),
            )
        }

        assertTrue(report.success)
        assertEquals(listOf("a", "b", "c", "2 a c"), recorded)
        assertEquals("a,b,c", variables.get("csv"))
    }
}
