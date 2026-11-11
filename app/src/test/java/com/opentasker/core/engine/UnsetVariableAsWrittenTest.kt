package com.opentasker.core.engine

import android.content.ContextWrapper
import com.opentasker.core.model.ActionSpec
import com.opentasker.core.model.Task
import com.opentasker.core.registerCoreRuntime
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tasker leaves a variable that has no value as its own name: "User-variables which have not had a
 * value assigned do not have replacements carried out." Imported tasks lean on that, as in
 * `%par1 ~ %*` to ask whether the caller passed anything. OpenTasker filled an unset `%name` in as
 * nothing. Its own `{{ }}` templates still give nothing for a missing value.
 */
class UnsetVariableAsWrittenTest {
    private val recorded = mutableListOf<String>()

    @Before
    fun setUp() {
        registerCoreRuntime()
        recorded.clear()
        listOf("test.unset.record", "plugin.locale.fire").forEach { type ->
            ActionRegistry.register(object : Action {
                override val id = type
                override val category = ActionCategory.FLOW
                override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
                    recorded += args["v"] ?: args.getValue("bundleJson")
                    return ActionResult.Success
                }
            })
        }
    }

    @After
    fun tearDown() {
        registerCoreRuntime()
    }

    private fun record(text: String, condition: String? = null) =
        ActionSpec(type = "test.unset.record", args = mapOf("v" to text), condition = condition)

    private fun store(vararg values: Pair<String, String>) = VariableStore().apply { values.forEach { (name, value) -> set(name, value) } }

    private fun run(vararg actions: ActionSpec, variables: VariableStore = VariableStore()): List<String> = runBlocking {
        recorded.clear()
        val report = TaskRunner(ActionContext(ContextWrapper(null), variables)).run(Task(name = "Unset", actions = actions.toList()))
        assertTrue(report.results.toString(), report.success)
        recorded.toList()
    }

    private fun runWithInner(outerArgs: Map<String, String>, vararg innerActions: ActionSpec): List<String> = runBlocking {
        recorded.clear()
        val inner = Task(id = 2, name = "Inner", actions = innerActions.toList())
        val outer = Task(id = 1, name = "Outer", actions = listOf(ActionSpec(type = "task.run", args = mapOf("task" to "Inner") + outerArgs)))
        val tasks = listOf(outer, inner).associateBy(Task::name)
        val report = TaskRunner(ActionContext(ContextWrapper(null), VariableStore()), resolveTask = { ref -> tasks[ref] }).run(outer)
        assertTrue(report.results.toString(), report.success)
        recorded.toList()
    }

    @Test
    fun anUnsetVariableStaysAsWrittenInARun() {
        assertEquals(listOf("Battery %BATT_LEVEL, left %todo"), run(record("Battery %BATT_LEVEL, left %todo")))
    }

    @Test
    fun aSetVariableStillFillsInNextToAnUnsetOne() {
        assertEquals(
            listOf("Battery %BATT_LEVEL, left 3"),
            run(record("Battery %BATT_LEVEL, left %todo"), variables = store("todo" to "3")),
        )
    }

    @Test
    fun theTextAfterAnUnsetVariableIsKept() {
        assertEquals(listOf("%count-1"), run(record("%count-1")))
        assertEquals(listOf("5-1"), run(record("%count-1"), variables = store("count" to "5")))
    }

    @Test
    fun percentEncodedTextKeepsItsEscapes() {
        assertEquals(listOf("https://example.com/caf%C3%A9?q=%E2%9C%93"), run(record("https://example.com/caf%C3%A9?q=%E2%9C%93")))
    }

    @Test
    fun aTemplateWithNoValueStillComesOutEmpty() {
        assertEquals(listOf("[] [%nothing]"), run(record("[{{ task.nothing }}] [%nothing]")))
    }

    @Test
    fun anElementPickedByAnUnsetIndexIsStillEmpty() {
        assertEquals(listOf("[]"), run(record("[%arr(%i)]"), variables = store("arr1" to "a", "arr2" to "b")))
    }

    @Test
    fun isSetAndIsntSetStillReadAnUnsetVariableAsUnset() {
        val checks = arrayOf(record("set", condition = "%x is_set"), record("not set", condition = "%x not_set"))

        assertEquals(listOf("not set"), run(*checks))
        assertEquals(listOf("set"), run(*checks, variables = store("x" to "1")))
        assertEquals(listOf("not set"), run(*checks, variables = store("x" to "")))
    }

    @Test
    fun anUnsetInputMatchesAPatternThatStartsWithAPercentSign() {
        val checks = arrayOf(
            record("nothing passed", condition = "%par1 ~ %*"),
            record("passed %par1", condition = "%par1 !~ %*"),
        )

        assertEquals(listOf("nothing passed"), runWithInner(emptyMap(), *checks))
        assertEquals(listOf("passed hi"), runWithInner(mapOf("par1" to "hi"), *checks))
    }

    @Test
    fun aCallerPassingAnUnsetVariableHandsItOnAsWritten() {
        assertEquals(
            listOf("got %where", "unset upstream"),
            runWithInner(
                mapOf("par1" to "%where"),
                record("got %par1"),
                record("unset upstream", condition = "%par1 ~ %*"),
            ),
        )
    }

    @Test
    fun aPluginBundleKeepsAnUnsetVariableAsWritten() {
        val fire = ActionSpec(type = "plugin.locale.fire", args = mapOf("package" to "org.kustom.widget", "bundleJson" to """{"n": %UNSET, "title": "%TITLE"}"""))

        assertEquals(
            Json.parseToJsonElement("""{"n": "%UNSET", "title": "%TITLE"}"""),
            Json.parseToJsonElement(run(fire).single()),
        )
    }

    @Test
    fun aJsonPathWithNoJsonStaysAsWritten() {
        assertEquals(listOf("see %json.example"), run(record("see %json.example")))
    }

    @Test
    fun twoUnsetVariablesCompareByTheirNames() {
        assertEquals(
            listOf("a != b", "a == a", "x ~ *"),
            run(
                record("a == b", condition = "%a == %b"),
                record("a != b", condition = "%a != %b"),
                record("a == a", condition = "%a == %a"),
                record("x ~ *", condition = "%x ~ *"),
            ),
        )
    }

    @Test
    fun aGotoWithAnUnsetLabelGoesToItsStepNumber() {
        assertEquals(
            listOf("landed"),
            run(
                ActionSpec(type = FlowControl.GOTO, args = mapOf("label" to "%where", "step" to "3")),
                record("passed over"),
                record("landed"),
            ),
        )
    }

    @Test
    fun thePreviewGivesACalledTaskItsCallersInputs() {
        val inner = Task(
            id = 2,
            name = "Inner",
            actions = listOf(
                ActionSpec(type = "log", args = mapOf("message" to "nothing passed"), condition = "%par1 ~ %*"),
                ActionSpec(type = "log", args = mapOf("message" to "passed %par1"), condition = "%par1 !~ %*"),
            ),
        )
        fun innerSteps(outerArgs: Map<String, String>): List<PreflightStep> {
            val outer = Task(id = 1, name = "Outer", actions = listOf(ActionSpec(type = "task.run", args = mapOf("task" to "Inner") + outerArgs)))
            return PreflightRunner.preflightTask(outer, listOf(outer, inner)).tasks.single { it.taskName == "Inner" }.steps
        }

        val passed = innerSteps(mapOf("par1" to "hi"))
        assertEquals(listOf(PreflightStepStatus.SKIPPED, PreflightStepStatus.SIMULATED), passed.map { it.status })
        assertEquals("passed hi", passed[1].expandedArguments["message"])
        assertEquals(listOf(PreflightStepStatus.SIMULATED, PreflightStepStatus.SKIPPED), innerSteps(emptyMap()).map { it.status })
    }

    @Test
    fun thePreviewShowsAnUnsetVariableAsWrittenAndSkipsOnIsSet() {
        val report = PreflightRunner.preflightTask(
            Task(
                name = "Unset",
                actions = listOf(
                    ActionSpec(type = "log", args = mapOf("message" to "left %todo")),
                    ActionSpec(type = "log", args = mapOf("message" to "set"), condition = "%todo is_set"),
                ),
            ),
        )
        val steps = report.tasks.single().steps

        assertEquals("left %todo", steps[0].expandedArguments["message"])
        assertEquals(PreflightStepStatus.SKIPPED, steps[1].status)
    }
}
