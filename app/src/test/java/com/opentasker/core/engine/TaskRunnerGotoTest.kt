package com.opentasker.core.engine

import android.content.ContextWrapper
import com.opentasker.core.model.ActionSpec
import com.opentasker.core.model.Task
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TaskRunnerGotoTest {

    private val recorded = mutableListOf<String>()

    @Before
    fun setUp() {
        recorded.clear()
        ActionRegistry.register(object : Action {
            override val id = "test.goto.record"
            override val category = ActionCategory.FLOW
            override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
                recorded += args["v"].orEmpty()
                return ActionResult.Success
            }
        })
        ActionRegistry.register(object : Action {
            override val id = "test.goto.count"
            override val category = ActionCategory.FLOW
            override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
                ctx.variables.set("n", ((ctx.variables.get("n")?.toIntOrNull() ?: 0) + 1).toString())
                return ActionResult.Success
            }
        })
    }

    private fun record(value: String, label: String? = null) =
        ActionSpec(type = "test.goto.record", label = label, args = mapOf("v" to value))

    private fun ctrl(type: String, args: Map<String, String> = emptyMap(), condition: String? = null, label: String? = null) =
        ActionSpec(type = type, label = label, args = args, condition = condition)

    private fun goto(step: String? = null, label: String? = null, condition: String? = null) =
        ctrl(FlowControl.GOTO, listOfNotNull(step?.let { "step" to it }, label?.let { "label" to it }).toMap(), condition)

    private fun run(variables: VariableStore = VariableStore(), vararg actions: ActionSpec): TaskRunReport =
        runBlocking {
            TaskRunner(ActionContext(ContextWrapper(null), variables)).run(Task(name = "T", actions = actions.toList()))
        }

    private fun TaskRunReport.failures(): List<String> = results.filterIsInstance<ActionResult.Failure>().map { it.message }

    @Test
    fun aGotoJumpsToTheStepNumberCountedFromOne() {
        val report = run(VariableStore(), record("one"), goto(step = "4"), record("skipped"), record("four"))

        assertTrue(report.failures().toString(), report.success)
        assertEquals(listOf("one", "four"), recorded)
    }

    @Test
    fun aLabelWinsOverTheNumberAndFindsTheFirstStepWithIt() {
        val report = run(
            VariableStore(),
            goto(step = "2", label = " here "),
            record("two"),
            ctrl(FlowControl.ANCHOR, label = "here"),
            record("after the anchor"),
            record("second here", label = "here"),
        )

        assertTrue(report.failures().toString(), report.success)
        assertEquals(listOf("after the anchor", "second here"), recorded)
    }

    @Test
    fun theStepNumberCanComeFromAVariableOrAWholeNumberSum() {
        run(VariableStore().apply { set("target", "3") }, goto(step = "%target"), record("skipped"), record("three"))
        assertEquals(listOf("three"), recorded)

        recorded.clear()
        run(VariableStore().apply { set("target", "3.0") }, goto(step = "%target"), record("skipped"), record("three"))
        assertEquals(listOf("three"), recorded)
    }

    @Test
    fun aGuardedGotoBackwardsMakesALoopAndAnUnmetGuardFallsThrough() {
        val report = run(
            VariableStore().apply { set("n", "0") },
            record("%n"),
            ActionSpec(type = "test.goto.count"),
            goto(step = "1", condition = "%n < 3"),
            record("done"),
        )

        assertTrue(report.failures().toString(), report.success)
        assertEquals(listOf("0", "1", "2", "done"), recorded)
    }

    @Test
    fun aGotoOutOfAForEachEndsTheLoop() {
        val report = run(
            VariableStore().apply { setArray("xs", listOf("a", "b", "c")); set("n", "0") },
            ctrl(FlowControl.FOREACH, mapOf("list" to "xs", "var" to "item")),
            record("%item"),
            goto(label = "out", condition = "%item == b"),
            ctrl(FlowControl.ENDFOR),
            ctrl(FlowControl.ANCHOR, label = "out"),
            // The loop that was left is over, so running it again starts from its first item.
            goto(label = "again", condition = "%n == 0"),
            record("done"),
            ctrl(FlowControl.STOP),
            ActionSpec(type = "test.goto.count", label = "again"),
            goto(step = "1"),
        )

        assertTrue(report.failures().toString(), report.success)
        assertEquals(listOf("a", "b", "a", "b", "done"), recorded)
    }

    @Test
    fun aGotoIntoTheMiddleOfAForEachOrTryThatIsntRunningFails() {
        val loop = run(
            VariableStore().apply { setArray("xs", listOf("a")) },
            goto(step = "3"),
            ctrl(FlowControl.FOREACH, mapOf("list" to "xs", "var" to "item")),
            record("%item"),
            ctrl(FlowControl.ENDFOR),
        )
        assertFalse(loop.success)
        assertTrue(loop.failures().toString(), loop.failures().any { "block that opens at step 2" in it })
        assertTrue(recorded.isEmpty())

        val attempt = run(VariableStore(), goto(step = "3"), ctrl(FlowControl.TRY), record("body"), ctrl(FlowControl.ENDTRY))
        assertFalse(attempt.success)
        assertTrue(recorded.isEmpty())
    }

    @Test
    fun aMissingLabelAStepPastTheEndOrANonNumberStopsTheTask() {
        listOf(
            goto(label = "nowhere") to "no step is labelled \"nowhere\"",
            goto(step = "9") to "has no step 9",
            goto(step = "0") to "has no step 0",
            goto(step = "%target") to "\"soon\" isn't a step number",
            goto(step = "%unset") to "%unset is empty, so there's no step to jump to",
            goto() to "needs a step number or a label",
        ).forEach { (jump, message) ->
            recorded.clear()
            val report = run(VariableStore().apply { set("target", "soon") }, jump, record("after"))
            assertFalse(report.success)
            assertTrue(report.failures().toString(), report.failures().any { message in it })
            assertTrue(recorded.isEmpty())
        }
    }

    @Test
    fun aGotoIntoAnIfBranchFromOutsideRunsOnIntoItsElseAsInTasker() {
        // Tasker skips an Else only when its If was found true, and a jump past the If doesn't test it.
        val report = run(
            VariableStore().apply { set("x", "1") },
            goto(label = "then"),
            ctrl(FlowControl.IF, mapOf("condition" to "%x == 1")),
            record("then", label = "then"),
            ctrl(FlowControl.ELSE),
            record("else"),
            ctrl(FlowControl.ENDIF),
        )

        assertTrue(report.failures().toString(), report.success)
        assertEquals(listOf("then", "else"), recorded)
    }

    @Test
    fun aJumpBackIntoAnIfBranchKeepsTheResultTheIfHadLastTime() {
        val report = run(
            VariableStore().apply { set("x", "1"); set("n", "0") },
            ctrl(FlowControl.IF, mapOf("condition" to "%x == 1")),
            record("then %n", label = "then"),
            ctrl(FlowControl.ELSE),
            record("else"),
            ctrl(FlowControl.ENDIF),
            ActionSpec(type = "test.goto.count"),
            goto(label = "then", condition = "%n < 2"),
        )

        assertTrue(report.failures().toString(), report.success)
        assertEquals(listOf("then 0", "then 1"), recorded)
    }
}
