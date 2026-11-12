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

/** Goto to the top or out of the For Each it sits in (Tasker's continue and break), or past its If. */
class TaskRunnerGotoBlockTest {

    private val recorded = mutableListOf<String>()

    @Before
    fun setUp() {
        recorded.clear()
        ActionRegistry.register(object : Action {
            override val id = "test.gotoblock.record"
            override val category = ActionCategory.FLOW
            override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
                recorded += args["v"].orEmpty()
                return ActionResult.Success
            }
        })
        ActionRegistry.register(object : Action {
            override val id = "test.gotoblock.fail"
            override val category = ActionCategory.FLOW
            override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult =
                ActionResult.Failure("boom")
        })
    }

    private fun record(value: String) = ActionSpec(type = "test.gotoblock.record", args = mapOf("v" to value))

    private fun ctrl(type: String, args: Map<String, String> = emptyMap(), condition: String? = null) =
        ActionSpec(type = type, args = args, condition = condition)

    private fun goto(to: String, condition: String? = null, step: String? = null) =
        ctrl(FlowControl.GOTO, listOfNotNull("to" to to, step?.let { "step" to it }).toMap(), condition)

    private fun forEach(list: String, variable: String) = ctrl(FlowControl.FOREACH, mapOf("list" to list, "var" to variable))

    private fun variables() = VariableStore().apply {
        setArray("xs", listOf("a", "b", "c"))
        setArray("ys", listOf("1", "2"))
    }

    private fun run(vararg actions: ActionSpec): TaskRunReport =
        runBlocking {
            TaskRunner(ActionContext(ContextWrapper(null), variables())).run(Task(name = "T", actions = actions.toList()))
        }

    private fun TaskRunReport.failures(): List<String> = results.filterIsInstance<ActionResult.Failure>().map { it.message }

    @Test
    fun topOfLoopSkipsTheRestOfTheBodyAndTakesTheNextItem() {
        val report = run(
            forEach("xs", "item"),
            record("%item"),
            goto(FlowControl.GOTO_LOOP_TOP, condition = "%item == b"),
            record("after %item"),
            ctrl(FlowControl.ENDFOR),
            record("done"),
        )

        assertTrue(report.failures().toString(), report.success)
        assertEquals(listOf("a", "after a", "b", "c", "after c", "done"), recorded)
    }

    @Test
    fun topOfLoopOnTheLastItemLeavesTheLoopWithoutAskingTheEndFor() {
        // The End For's own condition would end the loop after the first item, but a continue
        // never reaches that marker.
        val report = run(
            forEach("xs", "item"),
            record("%item"),
            goto(FlowControl.GOTO_LOOP_TOP),
            record("never"),
            ctrl(FlowControl.ENDFOR, condition = "%item == nothing"),
            record("done"),
        )

        assertTrue(report.failures().toString(), report.success)
        assertEquals(listOf("a", "b", "c", "done"), recorded)
    }

    @Test
    fun outOfLoopEndsTheLoopAndCarriesOnAfterItsEndFor() {
        val report = run(
            forEach("xs", "item"),
            record("%item"),
            goto(FlowControl.GOTO_LOOP_END, condition = "%item == b"),
            record("after %item"),
            ctrl(FlowControl.ENDFOR),
            record("done"),
        )

        assertTrue(report.failures().toString(), report.success)
        assertEquals(listOf("a", "after a", "b", "done"), recorded)
    }

    @Test
    fun aLoopJumpActsOnTheInnermostLoop() {
        val report = run(
            forEach("xs", "x"),
            forEach("ys", "y"),
            record("%x%y"),
            goto(FlowControl.GOTO_LOOP_END, condition = "%y == 1"),
            record("never"),
            ctrl(FlowControl.ENDFOR),
            record("end %x"),
            goto(FlowControl.GOTO_LOOP_TOP, condition = "%x == b"),
            record("after %x"),
            ctrl(FlowControl.ENDFOR),
            record("done"),
        )

        assertTrue(report.failures().toString(), report.success)
        assertEquals(listOf("a1", "end a", "after a", "b1", "end b", "c1", "end c", "after c", "done"), recorded)
    }

    @Test
    fun outOfTheLastLoopStepEndsTheTaskCleanly() {
        val report = run(
            forEach("xs", "item"),
            record("%item"),
            goto(FlowControl.GOTO_LOOP_END),
            ctrl(FlowControl.ENDFOR),
        )

        assertTrue(report.failures().toString(), report.success)
        assertEquals(listOf("a"), recorded)
    }

    @Test
    fun aContinueEndsATryOpenedInsideTheLoop() {
        // Left running, the first item's try would catch the failure in the second item's catch
        // handler and run that handler again.
        val report = run(
            forEach("xs", "item"),
            ctrl(FlowControl.TRY),
            record("%item"),
            goto(FlowControl.GOTO_LOOP_TOP, condition = "%item == a"),
            ActionSpec(type = "test.gotoblock.fail"),
            ctrl(FlowControl.CATCH),
            record("caught"),
            ActionSpec(type = "test.gotoblock.fail"),
            ctrl(FlowControl.ENDTRY),
            ctrl(FlowControl.ENDFOR),
            record("never"),
        )

        assertFalse(report.success)
        assertTrue(report.failures().toString(), report.failures().any { "boom" in it })
        assertEquals(listOf("a", "b", "caught"), recorded)
    }

    @Test
    fun endOfIfSkipsTheRestOfTheBranchAndTheElse() {
        val report = run(
            ctrl(FlowControl.IF),
            record("one"),
            goto(FlowControl.GOTO_IF_END),
            record("never"),
            ctrl(FlowControl.ELSE),
            record("else"),
            ctrl(FlowControl.ENDIF),
            record("after"),
        )

        assertTrue(report.failures().toString(), report.success)
        assertEquals(listOf("one", "after"), recorded)
    }

    @Test
    fun endOfIfActsOnTheInnermostIfAndWorksFromTheElse() {
        val nested = run(
            ctrl(FlowControl.IF),
            ctrl(FlowControl.IF),
            goto(FlowControl.GOTO_IF_END),
            record("never"),
            ctrl(FlowControl.ENDIF),
            record("outer body"),
            ctrl(FlowControl.ENDIF),
        )
        assertTrue(nested.failures().toString(), nested.success)
        assertEquals(listOf("outer body"), recorded)

        recorded.clear()
        val fromElse = run(
            ctrl(FlowControl.IF, mapOf("condition" to "1 == 2")),
            record("never"),
            ctrl(FlowControl.ELSE),
            record("else"),
            goto(FlowControl.GOTO_IF_END),
            record("never"),
            ctrl(FlowControl.ENDIF),
            record("after"),
        )
        assertTrue(fromElse.failures().toString(), fromElse.success)
        assertEquals(listOf("else", "after"), recorded)
    }

    @Test
    fun endOfIfLeavesALoopInsideThatIf() {
        // The outer End For fails on a loop left running inside the If, so this proves it ended.
        val report = run(
            forEach("ys", "outer"),
            ctrl(FlowControl.IF),
            forEach("xs", "item"),
            record("%item"),
            goto(FlowControl.GOTO_IF_END),
            ctrl(FlowControl.ENDFOR),
            record("never"),
            ctrl(FlowControl.ENDIF),
            record("after %outer"),
            ctrl(FlowControl.ENDFOR),
        )

        assertTrue(report.failures().toString(), report.success)
        assertEquals(listOf("a", "after 1", "a", "after 2"), recorded)
    }

    @Test
    fun theKindOfJumpWinsOverAStepNumber() {
        val report = run(
            record("start"),
            forEach("xs", "item"),
            record("%item"),
            goto(FlowControl.GOTO_LOOP_END, step = "1"),
            ctrl(FlowControl.ENDFOR),
            record("done"),
        )

        assertTrue(report.failures().toString(), report.success)
        assertEquals(listOf("start", "a", "done"), recorded)
    }

    @Test
    fun aJumpWithNoBlockAroundItOrAnUnknownKindStopsTheTask() {
        fun failure(vararg actions: ActionSpec): String {
            recorded.clear()
            val report = run(*actions)
            assertFalse(report.success)
            assertEquals(emptyList<String>(), recorded)
            return report.failures().single()
        }

        val top = failure(goto(FlowControl.GOTO_LOOP_TOP), record("never"))
        assertTrue(top, "top of loop" in top && "isn't inside a For Each" in top)
        val out = failure(ctrl(FlowControl.IF), goto(FlowControl.GOTO_LOOP_END), ctrl(FlowControl.ENDIF), record("never"))
        assertTrue(out, "out of loop" in out && "isn't inside a For Each" in out)
        val endIf = failure(forEach("xs", "item"), goto(FlowControl.GOTO_IF_END), ctrl(FlowControl.ENDFOR), record("never"))
        assertTrue(endIf, "isn't inside an If" in endIf)
        val unknown = failure(goto("sideways"), record("never"))
        assertTrue(unknown, "\"sideways\" isn't a place to jump to" in unknown)
    }
}
