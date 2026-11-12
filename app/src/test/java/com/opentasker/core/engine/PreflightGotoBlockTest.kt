package com.opentasker.core.engine

import com.opentasker.core.actions.registerActionMetadata
import com.opentasker.core.model.ActionSpec
import com.opentasker.core.model.Task
import com.opentasker.core.registerCoreRuntime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The preview follows a goto to the top or out of its For Each, or past its If, as a run does. */
class PreflightGotoBlockTest {
    @Before
    fun setUp() {
        registerActionMetadata()
        registerCoreRuntime()
    }

    private val notReached = "not reached by selected branch"

    private fun set(name: String) = ActionSpec(type = "var.set", args = mapOf("name" to name, "value" to "1"))

    private fun ctrl(type: String, args: Map<String, String> = emptyMap()) = ActionSpec(type = type, args = args)

    private fun goto(to: String) = ctrl(FlowControl.GOTO, mapOf("to" to to))

    private fun preview(vararg actions: ActionSpec) = PreflightRunner.preflightTask(
        Task(id = 5, name = "Jumps", actions = actions.toList()),
        inputs = PreflightInputs(globalVariables = mapOf("XS1" to "a", "XS2" to "b")),
    )

    @Test
    fun topOfLoopRunsEveryItemAndNeverReachesTheRestOfTheBody() {
        val report = preview(
            ctrl(FlowControl.FOREACH, mapOf("list" to "XS", "var" to "item")),
            goto(FlowControl.GOTO_LOOP_TOP),
            set("skipped"),
            ctrl(FlowControl.ENDFOR),
            set("after"),
        )

        assertTrue(report.canPreflight)
        val steps = report.tasks.single().steps
        assertEquals(2, steps[1].executionCount)
        assertEquals("goto top of loop -> loop done", steps[1].branchDecision)
        assertEquals(notReached, steps[2].branchDecision)
        assertEquals(notReached, steps[3].branchDecision)
        assertEquals(PreflightStepStatus.SIMULATED, steps[4].status)
    }

    @Test
    fun outOfLoopAndEndOfIfLandAfterTheirBlocks() {
        val out = preview(
            ctrl(FlowControl.FOREACH, mapOf("list" to "XS", "var" to "item")),
            goto(FlowControl.GOTO_LOOP_END),
            set("skipped"),
            ctrl(FlowControl.ENDFOR),
            set("after"),
        ).tasks.single().steps
        assertEquals(1, out[1].executionCount)
        assertEquals("goto out of loop -> after step 4", out[1].branchDecision)
        assertEquals(notReached, out[2].branchDecision)
        assertEquals(PreflightStepStatus.SIMULATED, out[4].status)

        val endIf = preview(ctrl(FlowControl.IF), goto(FlowControl.GOTO_IF_END), set("skipped"), ctrl(FlowControl.ENDIF), set("after"))
            .tasks.single().steps
        assertEquals("goto end of If -> step 4", endIf[1].branchDecision)
        assertEquals(notReached, endIf[2].branchDecision)
        assertEquals(PreflightStepStatus.SIMULATED, endIf[4].status)
    }

    @Test
    fun aLoopJumpOutsideAnyLoopBlocksTheRun() {
        val report = preview(goto(FlowControl.GOTO_LOOP_TOP), set("a"))
        val (blocked, after) = report.tasks.single().steps

        assertFalse(report.canPreflight)
        assertEquals(PreflightStepStatus.BLOCKED, blocked.status)
        assertTrue(blocked.warnings.toString(), blocked.warnings.any { "isn't inside a For Each" in it })
        assertEquals(PreflightStepStatus.SKIPPED, after.status)
    }
}
