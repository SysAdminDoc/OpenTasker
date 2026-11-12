package com.opentasker.core.engine

import android.content.ContextWrapper
import com.opentasker.core.actions.ActionArgumentSensitivity
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

/** For Each over Tasker-style values and ranges, in a run and in the preview. */
class TaskRunnerForValuesTest {

    private val recorded = mutableListOf<String>()

    @Before
    fun setUp() {
        recorded.clear()
        registerActionMetadata()
        registerCoreRuntime()
        ActionRegistry.register(object : Action {
            override val id = "test.forvalues.record"
            override val category = ActionCategory.FLOW
            override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
                recorded += args["v"].orEmpty() + if (ctx.variables.isSensitive("i")) " (secret)" else ""
                return ActionResult.Success
            }
        })
    }

    private fun record(value: String = "%i") = ActionSpec(type = "test.forvalues.record", args = mapOf("v" to value))

    private fun forEach(vararg args: Pair<String, String>) = ActionSpec(type = FlowControl.FOREACH, args = mapOf("var" to "i", *args))

    private val endFor = ActionSpec(type = FlowControl.ENDFOR)

    private fun run(vararg actions: ActionSpec, variables: VariableStore = VariableStore()): TaskRunReport =
        runBlocking {
            TaskRunner(ActionContext(ContextWrapper(null), variables)).run(Task(name = "T", actions = actions.toList()))
        }

    private fun TaskRunReport.failures(): List<String> = results.filterIsInstance<ActionResult.Failure>().map { it.message }

    @Test
    fun aRangeAndWrittenOutValuesEachRunTheBody() {
        val report = run(forEach("values" to "3:1, x, 10:20:5"), record(), endFor, record("done"))

        assertTrue(report.failures().toString(), report.success)
        assertEquals(listOf("3", "2", "1", "x", "10", "15", "20", "done"), recorded)
    }

    @Test
    fun valuesReadVariablesWhenTheLoopStarts() {
        val variables = VariableStore().apply {
            set("last", "3")
            set("arr1", "a")
            set("arr2", "b")
        }
        val report = run(forEach("values" to "1:%last, %arr()"), record(), endFor, variables = variables)

        assertTrue(report.failures().toString(), report.success)
        assertEquals(listOf("1", "2", "3", "a", "b"), recorded)
    }

    @Test
    fun theBodyChangingWhatTheValuesReadDoesNotChangeTheLoop() {
        ActionRegistry.register(object : Action {
            override val id = "test.forvalues.shrink"
            override val category = ActionCategory.FLOW
            override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
                ctx.variables.set("last", "1")
                return ActionResult.Success
            }
        })
        val variables = VariableStore().apply { set("last", "3") }
        val report = run(
            forEach("values" to "1:%last"),
            record(),
            ActionSpec(type = "test.forvalues.shrink"),
            endFor,
            variables = variables,
        )

        assertTrue(report.failures().toString(), report.success)
        assertEquals(listOf("1", "2", "3"), recorded)
    }

    @Test
    fun aNamedArrayWinsOverValues() {
        val variables = VariableStore().apply { setArray("xs", listOf("a", "b")) }
        val report = run(forEach("list" to "xs", "values" to "1:3"), record(), endFor, variables = variables)

        assertTrue(report.failures().toString(), report.success)
        assertEquals(listOf("a", "b"), recorded)
    }

    @Test
    fun noValuesSkipTheLoop() {
        val report = run(forEach("values" to " , "), record(), endFor, record("done"))

        assertTrue(report.failures().toString(), report.success)
        assertEquals(listOf("done"), recorded)
    }

    @Test
    fun aSecretValueKeepsTheLoopVariableSecret() {
        val variables = VariableStore().apply { set("pin", "1234", sensitive = true) }
        val report = run(forEach("values" to "%pin"), record(), endFor, variables = variables)

        assertTrue(report.failures().toString(), report.success)
        assertEquals(listOf("1234 (secret)"), recorded)
    }

    @Test
    fun aSecretStaysSecretOnEveryPass() {
        val variables = VariableStore().apply { set("pins", "12,34", sensitive = true) }
        val report = run(forEach("values" to "%pins, 1:2"), record(), endFor, variables = variables)

        assertTrue(report.failures().toString(), report.success)
        assertEquals(listOf("12 (secret)", "34 (secret)", "1 (secret)", "2 (secret)"), recorded)
    }

    @Test
    fun aNamedSecretArrayKeepsTheLoopVariableSecret() {
        val variables = VariableStore().apply { setArray("keys", listOf("k1", "k2"), sensitive = true) }
        val report = run(forEach("list" to "{{ array.keys }}"), record(), endFor, variables = variables)

        assertTrue(report.failures().toString(), report.success)
        assertEquals(listOf("k1 (secret)", "k2 (secret)"), recorded)
    }

    @Test
    fun templateReferencesAreFilledInLikeTheChipsWriteThem() {
        val variables = VariableStore().apply {
            set("LAST", "2")
            setArray("xs", listOf("a, b", "c"))
            setArray("my-list", listOf("d"))
        }
        val report = run(forEach("values" to "1:{{ global.LAST }}, {{ array.xs }}, %my-list()"), record(), endFor, variables = variables)

        assertTrue(report.failures().toString(), report.success)
        assertEquals(listOf("1", "2", "a, b", "c", "d"), recorded)
    }

    @Test
    fun aSecretTemplateReferenceKeepsTheLoopVariableSecret() {
        val variables = VariableStore().apply { set("PIN", "1234", sensitive = true) }
        val report = run(forEach("values" to "{{ global.PIN }}"), record(), endFor, variables = variables)

        assertTrue(report.failures().toString(), report.success)
        assertEquals(listOf("1234 (secret)"), recorded)
    }

    @Test
    fun aRangeThatCantCountStopsTheTask() {
        val report = run(forEach("values" to "1:5:0"), record(), endFor, record("never"))

        assertFalse(report.success)
        assertTrue(report.failures().toString(), report.failures().single().contains("steps of 0"))
        assertEquals(emptyList<String>(), recorded)
    }

    @Test
    fun thePreviewLoopsOverValuesAndBlocksOnARangeThatCantCount() {
        fun preview(vararg actions: ActionSpec) = PreflightRunner.preflightTask(
            Task(id = 5, name = "For", actions = actions.toList()),
            inputs = PreflightInputs(globalVariables = mapOf("LAST" to "3")),
        )
        val set = ActionSpec(type = "var.set", args = mapOf("name" to "seen", "value" to "%i"))

        val loops = preview(forEach("values" to "1:%LAST"), set, endFor)
        assertTrue(loops.canPreflight)
        val steps = loops.tasks.single().steps
        assertEquals("foreach 1:%LAST -> 3 items (1/3)", steps[0].branchDecision)
        assertEquals(3, steps[1].executionCount)

        val blocked = preview(forEach("values" to "1:5:0"), set, endFor)
        assertFalse(blocked.canPreflight)
        val first = blocked.tasks.single().steps.first()
        assertEquals(PreflightStepStatus.BLOCKED, first.status)
        assertTrue(first.warnings.toString(), first.warnings.any { "steps of 0" in it })
    }

    @Test
    fun thePreviewHidesALoopVariableMadeFromASecret() {
        val set = ActionSpec(type = "var.set", args = mapOf("name" to "seen", "value" to "%i"))
        val report = PreflightRunner.preflightTask(
            Task(id = 5, name = "For", actions = listOf(forEach("values" to "%PIN, {{ global.PIN }}"), set, endFor)),
            inputs = PreflightInputs(globalVariables = mapOf("PIN" to "1234"), secretGlobalNames = setOf("PIN")),
        )

        assertTrue(report.canPreflight)
        val steps = report.tasks.single().steps
        assertEquals(2, steps[1].executionCount)
        assertEquals(ActionArgumentSensitivity.REDACTED, steps[1].expandedArguments["value"])
        assertFalse(report.toString(), "1234" in report.toString())
    }

    @Test
    fun thePreviewHidesALoopOverASecretArray() {
        val set = ActionSpec(type = "var.set", args = mapOf("name" to "seen", "value" to "%i"))
        val inputs = PreflightInputs(
            globalVariables = mapOf("KEYS1" to "k-one", "KEYS2" to "k-two"),
            secretGlobalNames = setOf("KEYS1", "KEYS2"),
        )
        listOf(forEach("list" to "KEYS"), forEach("values" to "%KEYS(), x")).forEach { loop ->
            val report = PreflightRunner.preflightTask(Task(id = 5, name = "For", actions = listOf(loop, set, endFor)), inputs = inputs)

            assertTrue(report.canPreflight)
            val step = report.tasks.single().steps[1]
            assertTrue(loop.args.toString(), step.executionCount >= 2)
            assertEquals(loop.args.toString(), ActionArgumentSensitivity.REDACTED, step.expandedArguments["value"])
            assertFalse(report.toString(), "k-one" in report.toString() || "k-two" in report.toString())
        }
    }

    @Test
    fun thePreviewReadsAnArrayChipInTheListField() {
        val set = ActionSpec(type = "var.set", args = mapOf("name" to "seen", "value" to "%i"))
        val report = PreflightRunner.preflightTask(
            Task(id = 5, name = "For", actions = listOf(forEach("list" to "{{ array.XS }}"), set, endFor)),
            inputs = PreflightInputs(globalVariables = mapOf("XS1" to "a", "XS2" to "b")),
        )

        assertTrue(report.canPreflight)
        assertEquals(2, report.tasks.single().steps[1].executionCount)
    }
}
