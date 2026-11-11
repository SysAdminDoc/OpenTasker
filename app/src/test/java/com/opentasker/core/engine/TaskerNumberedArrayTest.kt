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
    fun anIndexVariableThatIsUnsetNamesNoElement() {
        val variables = store("arr1" to "a", "arr2" to "b", "blank" to "")

        assertEquals("", variables.expand("%arr(%i)"))
        assertEquals("", variables.expand("%arr(%blank)"))
        assertEquals("a,b", variables.expand("%arr()"))
    }

    @Test
    fun anUnsetVariableOnEitherSideOfARangeNamesNoElement() {
        val variables = store("arr1" to "a", "arr2" to "b", "arr3" to "c", "two" to "2")

        assertEquals("", variables.expand("%arr(%from:%to)"))
        assertEquals("", variables.expand("%arr(%from:)"))
        assertEquals("", variables.expand("%arr(:%to)"))
        assertEquals("", variables.expand("%arr(%two:%to)"))
        assertEquals("b,c", variables.expand("%arr(%two:)"))
        assertEquals("a,b", variables.expand("%arr(:%two)"))
        assertEquals("a,b,c", variables.expand("%arr(:)"))
    }

    @Test
    fun aStoredArrayReadsTheElementAnIndexVariableNames() {
        val variables = store("i" to "1").apply { setArray("parts", listOf("x", "y")) }

        // Whatever element %parts(1) names, %parts(%i) names the same one, not a join on "%i".
        assertEquals(variables.expand("%parts(1)"), variables.expand("%parts(%i)"))
        assertTrue(variables.expand("%parts(%i)") in listOf("x", "y"))
        assertEquals("", variables.expand("%parts(%unset)"))
        assertEquals("x;y", variables.expand("%parts(;)"))
    }

    @Test
    fun splitTextsPartsReadByIndexRangeAndCountThoughItSetsTheFirstPartToo() {
        val variables = store("i" to "2", "padded" to "1 ", "zero" to "0", "blank" to "")

        assertEquals(ActionResult.Success, act(variables, "text.split", "source" to "a,b,c", "delimiter" to ",", "var" to "parts"))

        assertEquals("a", variables.expand("%parts"))
        assertEquals("3", variables.expand("%parts(#)"))
        assertEquals("c", variables.expand("%parts(%i)"))
        assertEquals("b", variables.expand("%parts(%padded)"))
        assertEquals("a,b", variables.expand("%parts(%zero:1)"))
        assertEquals("b,c", variables.expand("%parts(1:)"))
        assertEquals("c,b,a", variables.expand("%parts(2:0)"))
        assertEquals("", variables.expand("%parts(%blank:1)"))
        assertEquals("", variables.expand("%parts(%unset)"))
        // Not an array form, so %parts's own operators still apply to the first part.
        assertEquals("A", variables.expand("%parts(upper)"))
    }

    @Test
    fun aSplitTextArrayNeverHidesATaskerArrayOfTheSameName() {
        val variables = VariableStore()

        act(variables, "text.split", "source" to "a,b,c", "delimiter" to ",", "var" to "words")
        variables.set("words", "x y")
        act(variables, "var.split", "name" to "words")

        assertEquals("2", variables.expand("%words(#)"))
        assertEquals("y", variables.expand("%words(2)"))
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
    fun aPopOnASubTasksParLeavesItsCallersParAlone() {
        fun record(text: String) = ActionSpec(type = "test.array.record", args = mapOf("v" to text))
        val inner = Task(
            id = 3,
            name = "Inner",
            actions = listOf(
                ActionSpec(type = "array.pop", args = mapOf("array" to "%par", "position" to "1", "to_var" to "got")),
                record("inner got %got, left %par(#) as %par1"),
            ),
        )
        val middle = Task(
            id = 2,
            name = "Middle",
            actions = listOf(
                ActionSpec(type = "task.run", args = mapOf("task" to "Inner", "par1" to "x", "par2" to "y")),
                record("middle still has %par1 and %par2, %par(#) in all"),
            ),
        )
        val outer = Task(id = 1, name = "Outer", actions = listOf(ActionSpec(type = "task.run", args = mapOf("task" to "Middle", "par1" to "p", "par2" to "q"))))
        val tasks = listOf(outer, middle, inner).associateBy(Task::name)

        val report = runBlocking {
            TaskRunner(ActionContext(ContextWrapper(null), VariableStore()), resolveTask = { ref -> tasks[ref] }).run(outer)
        }

        assertTrue(report.success)
        assertEquals(listOf("inner got x, left 1 as y", "middle still has p and q, 2 in all"), recorded)
    }

    @Test
    fun aSubTaskNeverReachesItsCallersParThroughItsOwn() {
        fun record(text: String) = ActionSpec(type = "test.array.record", args = mapOf("v" to text))
        fun pop() = ActionSpec(type = "array.pop", args = mapOf("array" to "%par", "position" to "1"))
        val givenNothing = Task(id = 3, name = "GivenNothing", actions = listOf(record("given nothing sees %par(#)"), pop()))
        val drains = Task(id = 4, name = "Drains", actions = listOf(pop(), pop(), pop(), record("drains left %par(#)")))
        val middle = Task(
            id = 2,
            name = "Middle",
            actions = listOf(
                ActionSpec(type = "task.run", args = mapOf("task" to "GivenNothing")),
                ActionSpec(type = "task.run", args = mapOf("task" to "Drains", "par1" to "x", "par2" to "y")),
                record("middle still has %par1 and %par2, %par(#) in all"),
            ),
        )
        val outer = Task(id = 1, name = "Outer", actions = listOf(ActionSpec(type = "task.run", args = mapOf("task" to "Middle", "par1" to "p", "par2" to "q"))))
        val tasks = listOf(outer, middle, givenNothing, drains).associateBy(Task::name)

        val report = runBlocking {
            TaskRunner(ActionContext(ContextWrapper(null), VariableStore()), resolveTask = { ref -> tasks[ref] }).run(outer)
        }

        assertTrue(report.toString(), report.success)
        assertEquals(listOf("given nothing sees 0", "drains left 0", "middle still has p and q, 2 in all"), recorded)
    }

    @Test
    fun preflightBlocksAnUnsetPositionOnlyWhenNothingCouldSetIt() {
        fun pop(position: String) = ActionSpec(type = "array.pop", args = mapOf("array" to "%arr", "position" to position, "to_var" to "got"))
        fun stepFor(report: PreflightReport, taskName: String) = report.tasks.single { it.taskName == taskName }.steps.last()
        val child = Task(id = 2, name = "Child", actions = listOf(pop("%par1")))
        val caller = Task(id = 1, name = "Caller", actions = listOf(ActionSpec(type = "task.run", args = mapOf("task" to "2", "par1" to "2"))))
        val fromCaller = stepFor(PreflightRunner.preflightTask(caller, listOf(caller, child)), "Child")
        val earlierOutput = Task(
            id = 3,
            name = "Split",
            actions = listOf(
                ActionSpec(type = "text.split", args = mapOf("text" to "2,1", "separator" to ",", "var" to "%Pos")),
                pop("%Pos1"),
            ),
        )
        val fromOutput = stepFor(PreflightRunner.preflightTask(earlierOutput), "Split")
        val noWriter = stepFor(PreflightRunner.preflightTask(Task(id = 4, name = "Alone", actions = listOf(pop("%Pos")))), "Alone")

        // %par1 comes from the caller, which Preflight doesn't pass on, so it warns and goes on.
        assertEquals(PreflightStepStatus.SIMULATED, fromCaller.status)
        assertTrue(fromCaller.warnings.toString(), fromCaller.warnings.any { "%par1 is empty here" in it })
        // A global written by an earlier step's output isn't one Preflight plays either.
        assertEquals(PreflightStepStatus.SIMULATED, fromOutput.status)
        assertTrue(fromOutput.warnings.toString(), fromOutput.warnings.any { "%Pos1 is empty here" in it })
        // A global nothing writes really does stop the run's step.
        assertEquals(PreflightStepStatus.BLOCKED, noWriter.status)
        assertTrue(noWriter.warnings.toString(), noWriter.warnings.any { "The run stops this step" in it })
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

    private fun processed(type: String, vararg elements: Pair<Int, String>): Map<Int, String> {
        val variables = store(*elements.map { (index, value) -> "arr$index" to value }.toTypedArray())
        assertEquals(ActionResult.Success, act(variables, "array.process", "array" to "arr", "type" to type))
        return variables.elements("arr")
    }

    @Test
    fun processReordersValuesOverTheIndicesTheArrayAlreadyHas() {
        val gapped = arrayOf(1 to "c", 2 to "a", 4 to "b")

        assertEquals(mapOf(1 to "a", 2 to "b", 4 to "c"), processed("sort_alpha", *gapped))
        assertEquals(mapOf(1 to "b", 2 to "a", 4 to "c"), processed("reverse", *gapped))
        assertEquals(mapOf(1 to "a", 2 to "b", 4 to "c"), processed("rotate_left", *gapped))
        assertEquals(mapOf(1 to "b", 2 to "c", 4 to "a"), processed("rotate_right", *gapped))
        assertEquals(mapOf(1 to "only"), processed("rotate_left", 1 to "only"))
        assertEquals(emptyMap<Int, String>(), processed("sort_alpha"))
    }

    @Test
    fun removeDuplicatesKeepsTheFirstCopyAndUnsetsTheLaterOnes() {
        assertEquals(
            mapOf(1 to "a", 2 to "b", 4 to "c"),
            processed("remove_duplicates", 1 to "a", 2 to "b", 3 to "a", 4 to "c", 5 to "b"),
        )
    }

    @Test
    fun squashRenumbersFromOneAndDropsEmptyElements() {
        assertEquals(
            mapOf(1 to "one", 2 to "three", 3 to "six"),
            processed("squash", 1 to "one", 3 to "three", 4 to "", 6 to "six"),
        )
    }

    @Test
    fun alphabeticalSortsFollowTaskersCaseRules() {
        val mixed = arrayOf(1 to "b", 2 to "B", 3 to "a", 4 to "A")

        assertEquals(listOf("A", "B", "a", "b"), processed("sort_alpha", *mixed).values.toList())
        assertEquals(listOf("b", "a", "B", "A"), processed("sort_alpha_reverse", *mixed).values.toList())
        assertEquals(listOf("a", "A", "b", "B"), processed("sort_alpha_caseless", *mixed).values.toList())
        // Tasker turns the comparison round instead of reversing the sorted list, so "b" stays before "B".
        assertEquals(listOf("b", "B", "a", "A"), processed("sort_alpha_caseless_reverse", *mixed).values.toList())
    }

    @Test
    fun lengthSortsKeepEqualLengthsInIndexOrder() {
        val words = arrayOf(1 to "ccc", 2 to "a", 3 to "bb", 4 to "d")

        assertEquals(listOf("a", "d", "bb", "ccc"), processed("sort_shortest_first", *words).values.toList())
        assertEquals(listOf("ccc", "bb", "a", "d"), processed("sort_longest_first", *words).values.toList())
    }

    @Test
    fun numericSortsUseTheFirstNumberAndPutTheRestLast() {
        val values = arrayOf(1 to "10", 2 to "v1.5", 3 to "1.25", 4 to "9", 5 to "zeta", 6 to "Alpha", 7 to "-3")

        assertEquals(
            listOf("-3", "v1.5", "1.25", "9", "10", "Alpha", "zeta"),
            processed("sort_numeric_integer", *values).values.toList(),
        )
        assertEquals(
            listOf("-3", "1.25", "v1.5", "9", "10", "Alpha", "zeta"),
            processed("sort_numeric_float", *values).values.toList(),
        )
    }

    @Test
    fun shuffleKeepsTheSameValuesOnTheSameIndices() {
        val result = processed("shuffle", 1 to "a", 2 to "b", 3 to "c", 4 to "d", 7 to "g")

        assertEquals(setOf(1, 2, 3, 4, 7), result.keys)
        assertEquals(listOf("a", "b", "c", "d", "g"), result.values.sorted())
    }

    @Test
    fun processMovesSecretFlagsWithTheirValuesAndRefusesAnUnknownType() {
        val variables = VariableStore().apply {
            set("arr1", "b", sensitive = true)
            set("arr2", "a")
        }

        assertTrue(act(variables, "array.process", "array" to "arr", "type" to "sideways") is ActionResult.Failure)
        assertEquals(mapOf(1 to "b", 2 to "a"), variables.elements("arr"))
        act(variables, "array.process", "array" to "arr", "type" to "sort_alpha")

        assertEquals(mapOf(1 to "a", 2 to "b"), variables.elements("arr"))
        assertFalse(variables.isSensitive("arr1"))
        assertTrue(variables.isSensitive("arr2"))
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
