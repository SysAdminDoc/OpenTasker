package com.opentasker.core.engine

import android.content.ContextWrapper
import com.opentasker.core.actions.registerActionMetadata
import com.opentasker.core.engine.variables.TaskerBuiltIns
import com.opentasker.core.model.ActionSpec
import com.opentasker.core.model.Task
import com.opentasker.core.registerCoreRuntime
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tasker's Variable Clear unsets one variable, every variable whose name matches a pattern (globals
 * too unless Local Variables Only), or every local, plus every global with Clear All Variables. A
 * cleared variable reads as never set, so `%name` stays as written.
 */
class VariableClearTest {
    private val recorded = mutableListOf<String>()

    @Before
    fun setUp() {
        registerActionMetadata()
        registerCoreRuntime()
        ActionRegistry.register(object : Action {
            override val id = "test.varclear.record"
            override val category = ActionCategory.FLOW
            override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
                recorded += args.getValue("v")
                return ActionResult.Success
            }
        })
    }

    @After
    fun tearDown() {
        TaskerBuiltIns.resetForTests()
    }

    private fun record(text: String) = ActionSpec(type = "test.varclear.record", args = mapOf("v" to text))

    private fun set(name: String, value: String) = ActionSpec(type = "var.set", args = mapOf("name" to name, "value" to value))

    private fun clear(name: String, vararg switches: String) =
        ActionSpec(type = "var.clear", args = mapOf("name" to name) + switches.associateWith { "true" })

    private fun runTask(task: String) = ActionSpec(type = SUB_TASK_ACTION_ID, args = mapOf("task" to task))

    private fun run(vararg tasks: Task, store: VariableStore = VariableStore()): List<String> = runBlocking {
        recorded.clear()
        val byName = tasks.associateBy { it.name }
        val report = TaskRunner(ActionContext(ContextWrapper(null), store), resolveTask = { byName[it] }).run(tasks.first())
        assertTrue(report.results.toString(), report.success)
        recorded.toList()
    }

    private fun run(vararg actions: ActionSpec, store: VariableStore = VariableStore()) =
        run(Task(id = 1, name = "Clear", actions = actions.toList()), store = store)

    @Test
    fun oneVariableGoesAndTheOthersStay() {
        val store = VariableStore().apply { seedGlobals(mapOf("Kept" to "g", "Gone" to "g")) }

        assertEquals(
            listOf("%a 2 %Gone g"),
            run(set("%a", "1"), set("%b", "2"), clear("%a"), clear("%Gone"), record("%a %b %Gone %Kept"), store = store),
        )
    }

    @Test
    fun aPatternClearsEveryMatchingLocalAndGlobal() {
        val store = VariableStore().apply { seedGlobals(mapOf("ItemCount" to "2", "Other" to "o")) }

        assertEquals(
            listOf("%item1 %item2 x %ItemCount o"),
            run(
                set("%item1", "a"), set("%item2", "b"), set("%other", "x"),
                clear("%item*", "pattern"),
                record("%item1 %item2 %other %ItemCount %Other"),
                store = store,
            ),
        )
    }

    @Test
    fun localVariablesOnlyLeavesTheGlobalsAlone() {
        val store = VariableStore().apply { seedGlobals(mapOf("ItemCount" to "2")) }

        assertEquals(
            listOf("%item1 2"),
            run(set("%item1", "a"), clear("%item*", "pattern", "local_only"), record("%item1 %ItemCount"), store = store),
        )
    }

    @Test
    fun aPatternCanListTheNamesToClear() {
        val store = VariableStore().apply { seedGlobals(mapOf("Errmsg" to "e")) }

        assertEquals(
            listOf("%stdout %stderr %Errmsg kept"),
            run(
                set("%stdout", "out"), set("%stderr", "err"), set("%result", "kept"),
                clear("%stdout/%stderr/%errmsg", "pattern"),
                record("%stdout %stderr %Errmsg %result"),
                store = store,
            ),
        )
    }

    @Test
    fun noNameClearsEveryLocalAndClearAllTakesTheGlobalsToo() {
        fun store() = VariableStore().apply { seedGlobals(mapOf("Kept" to "g")) }

        assertEquals(listOf("%a %b g"), run(set("%a", "1"), set("%b", "2"), clear(""), record("%a %b %Kept"), store = store()))
        assertEquals(listOf("%a %Kept"), run(set("%a", "1"), clear("", "clear_all"), record("%a %Kept"), store = store()))
    }

    @Test
    fun theSwitchesOnlyCountWhereTaskerReadsThem() {
        val store = VariableStore().apply { seedGlobals(mapOf("Kept" to "g", "Other" to "o")) }

        // Local Variables Only needs Pattern Matching, and Clear All Variables needs no name.
        assertEquals(
            listOf("%Kept o 2"),
            run(set("%b", "2"), clear("%Kept", "local_only", "clear_all"), record("%Kept %Other %b"), store = store),
        )
    }

    @Test
    fun theNameIsReadAsWrittenNeverFilledIn() {
        assertEquals(
            listOf("%which 1", "%pa %pab"),
            run(
                set("%a", "1"), set("%which", "%a"), clear("%which"), record("%which %a"),
                set("%pa", "1"), set("%pab", "2"), clear("%pa*", "pattern"), record("%pa %pab"),
            ),
        )
    }

    @Test
    fun aSubTaskClearLeavesTheCallersValues() {
        val caller = Task(id = 1, name = "A", actions = listOf(set("%a", "old"), set("%b", "old"), runTask("B"), record("A:%a %b")))
        val called = Task(
            id = 2,
            name = "B",
            actions = listOf(set("%a", "mine"), clear("%a*", "pattern"), clear(""), record("B:%a %b")),
        )

        assertEquals(listOf("B:%a %b", "A:old old"), run(caller, called))
    }

    @Test
    fun aClearedGlobalIsReportedAsRemovedSoItIsDeletedFromStorage() {
        val store = VariableStore().apply { seedGlobals(mapOf("Kept" to "g", "Gone1" to "a", "Gone2" to "b")) }
        val before = store.globalSnapshot()

        run(clear("%Gone*", "pattern"), store = store)

        assertEquals(listOf("Gone1", "Gone2"), removedGlobals(before, store.globalSnapshot()))
    }

    @Test
    fun aBuiltInFillsInAgainOnceItsStoredValueIsCleared() {
        TaskerBuiltIns.installDevice { name -> mapOf("BATT" to "15")[name] }

        assertEquals(listOf("full", "15"), run(set("%BATT", "full"), record("%BATT"), clear("%BATT"), record("%BATT")))
    }

    @Test
    fun aClearedVariableCountsTheCharactersItIsWrittenWith() {
        val length = ActionSpec(type = "text.length", args = mapOf("source" to "%name", "var" to "%len"))

        assertEquals(listOf("5"), run(set("%name", "Al"), clear("%name"), length, record("%len")))
    }

    @Test
    fun aNameThatIsntAVariableFailsTheStep() {
        val report = runBlocking {
            TaskRunner(ActionContext(ContextWrapper(null), VariableStore()))
                .run(Task(id = 1, name = "Clear", actions = listOf(clear("%1x"))))
        }

        assertFalse(report.success)
    }

    @Test
    fun thePreviewClearsTheSameVariables() {
        val task = Task(
            id = 1,
            name = "Preview",
            actions = listOf(
                set("%item", "base"), set("%item1", "a"), set("%other", "x"),
                clear("%item*", "pattern"),
                set("%seen", "[%item][%item1][%other]"),
            ),
        )
        val steps = PreflightRunner.preflightTask(task).tasks.single().steps

        assertEquals("%item*", steps[3].expandedArguments["name"])
        assertEquals("[%item][%item1][x]", steps[4].expandedArguments["value"])
    }
}
