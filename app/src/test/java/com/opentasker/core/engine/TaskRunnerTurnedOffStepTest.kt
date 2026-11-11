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

/** A step the user turned off stays in the task and keeps its number, and nothing runs it. */
class TaskRunnerTurnedOffStepTest {

    private val recorded = mutableListOf<String>()
    private var flakyCalls = 0

    @Before
    fun setUp() {
        registerActionMetadata()
        registerCoreRuntime()
        recorded.clear()
        flakyCalls = 0
        ActionRegistry.register(object : Action {
            override val id = "test.off.record"
            override val category = ActionCategory.FLOW
            override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
                recorded += args["v"].orEmpty()
                return ActionResult.Success
            }
        })
        ActionRegistry.register(object : Action {
            override val id = "test.off.sideeffect"
            override val category = ActionCategory.FLOW
            override val retrySafety = ActionRetrySafety.NEVER
            override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult = ActionResult.Success
        })
        ActionRegistry.register(object : Action {
            override val id = "test.off.flaky"
            override val category = ActionCategory.FLOW
            override val retrySafety = ActionRetrySafety.IDEMPOTENT
            override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
                flakyCalls++
                return ActionResult.Failure("transient failure")
            }
        })
    }

    private fun record(value: String) = ActionSpec(type = "test.off.record", args = mapOf("v" to value))

    private fun ctrl(type: String, args: Map<String, String> = emptyMap()) = ActionSpec(type = type, args = args)

    private fun ActionSpec.off() = copy(enabled = false)

    private fun run(variables: VariableStore = VariableStore(), vararg actions: ActionSpec): TaskRunReport =
        runBlocking {
            TaskRunner(ActionContext(ContextWrapper(null), variables)).run(Task(name = "T", actions = actions.toList()))
        }

    private fun TaskRunReport.failures(): List<String> = results.filterIsInstance<ActionResult.Failure>().map { it.message }

    @Test
    fun aStepTurnedOffIsPassedOverAndTheLogSaysWhy() {
        val report = run(VariableStore(), record("one"), record("two").off(), record("three"))

        assertTrue(report.failures().toString(), report.success)
        assertEquals(listOf("one", "three"), recorded)
        assertEquals(ActionTraceStatus.SKIPPED, report.traces[1].status)
        assertEquals(STEP_TURNED_OFF, report.traces[1].message)
    }

    @Test
    fun aStopTurnedOffDoesntEndTheTask() {
        val report = run(VariableStore(), record("one"), ctrl(FlowControl.STOP).off(), record("two"))

        assertTrue(report.failures().toString(), report.success)
        assertEquals(listOf("one", "two"), recorded)
    }

    @Test
    fun markersTurnedOffLeaveTheirBodyToRunEveryTime() {
        val x = VariableStore().apply { set("x", "1") }
        val paired = run(
            x,
            ctrl(FlowControl.IF, mapOf("condition" to "%x == 2")).off(),
            record("body"),
            ctrl(FlowControl.ENDIF).off(),
        )
        assertTrue(paired.failures().toString(), paired.success)
        assertEquals(listOf("body"), recorded)

        // An If turned off on its own no longer needs an End If.
        recorded.clear()
        val lone = run(x, ctrl(FlowControl.IF, mapOf("condition" to "%x == 2")).off(), record("body"))
        assertTrue(lone.failures().toString(), lone.success)
        assertEquals(listOf("body"), recorded)

        // The same If switched on still needs one, so the check above means something.
        recorded.clear()
        val unclosed = run(x, ctrl(FlowControl.IF, mapOf("condition" to "%x == 2")), record("body"))
        assertFalse(unclosed.success)
        assertTrue(unclosed.failures().toString(), unclosed.failures().any { "unclosed flow.if" in it })
    }

    @Test
    fun aGotoCountsStepsTurnedOffAndLandingOnOneMovesOn() {
        run(VariableStore(), ctrl(FlowControl.GOTO, mapOf("step" to "3")), record("off").off(), record("three"))
        assertEquals(listOf("three"), recorded)

        recorded.clear()
        val landed = run(VariableStore(), ctrl(FlowControl.GOTO, mapOf("step" to "2")), record("off").off(), record("after"))
        assertTrue(landed.failures().toString(), landed.success)
        assertEquals(listOf("after"), recorded)
    }

    @Test
    fun aStepTurnedOffThatCantRunHereDoesntHoldTheTaskBack() {
        listOf("tasker.unsupported", "no.such.action").forEach { type ->
            recorded.clear()
            val off = run(VariableStore(), ActionSpec(type = type).off(), record("ran"))
            assertTrue(off.failures().toString(), off.success)
            assertEquals(listOf("ran"), recorded)

            recorded.clear()
            val on = run(VariableStore(), ActionSpec(type = type), record("ran"))
            assertFalse(type, on.success)
            assertTrue(recorded.isEmpty())
        }
    }

    @Test
    fun aStepTurnedOffDoesntStopATryFromRetrying() {
        fun body(sideEffect: ActionSpec) = run(
            VariableStore(),
            ctrl(FlowControl.TRY, mapOf("max_attempts" to "3")),
            sideEffect,
            ActionSpec(type = "test.off.flaky"),
            ctrl(FlowControl.CATCH),
            ctrl(FlowControl.ENDTRY),
        )

        body(ActionSpec(type = "test.off.sideeffect").off())
        assertEquals(3, flakyCalls)

        // Switched on, the step that mustn't run twice keeps the body from being replayed.
        flakyCalls = 0
        body(ActionSpec(type = "test.off.sideeffect"))
        assertEquals(1, flakyCalls)
    }

    @Test
    fun runningAStepTurnedOffOnItsOwnRunsIt() {
        val task = Task(id = 3, name = "T", actions = listOf(record("first"), record("alone").off()))

        val single = SingleActionRun.taskFor(task, index = 1)!!
        assertEquals(listOf(task.actions[1].copy(enabled = true)), single.actions)

        val report = runBlocking { TaskRunner(ActionContext(ContextWrapper(null), VariableStore())).run(single) }
        assertTrue(report.failures().toString(), report.success)
        assertEquals(listOf("alone"), recorded)
    }

    @Test
    fun preflightShowsAStepTurnedOffAsSkippedWhetherOrNotItIsReached() {
        fun set(name: String) = ActionSpec(type = "var.set", args = mapOf("name" to name, "value" to "1"))
        val steps = PreflightRunner.preflightTask(
            Task(
                id = 5,
                name = "Partly off",
                actions = listOf(
                    set("%a"),
                    ActionSpec(type = "tasker.unsupported").off(),
                    ctrl(FlowControl.STOP).off(),
                    set("%b"),
                    ctrl(FlowControl.STOP),
                    set("%c").off(),
                ),
            ),
        ).tasks.single().steps

        assertEquals(PreflightStepStatus.SIMULATED, steps[0].status)
        listOf(1, 2, 5).forEach { index ->
            assertEquals("step ${index + 1}", PreflightStepStatus.SKIPPED, steps[index].status)
            assertEquals("step ${index + 1}", STEP_TURNED_OFF, steps[index].branchDecision)
        }
        assertEquals(PreflightStepStatus.SIMULATED, steps[3].status)
    }
}
