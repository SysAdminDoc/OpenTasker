package com.opentasker.core.engine

import com.opentasker.core.actions.registerActionMetadata
import com.opentasker.core.model.ActionSpec
import com.opentasker.core.model.Task
import com.opentasker.core.registerCoreRuntime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** A preview of a Stop Task step says when it can't find its task, and ends where a run would. */
class PreflightStopTaskTest {

    @Before
    fun setUp() {
        registerActionMetadata()
        registerCoreRuntime()
    }

    private fun set(name: String) = ActionSpec(type = "var.set", args = mapOf("name" to name, "value" to "1"))

    private fun stop(name: String) = ActionSpec(type = STOP_TASK_ACTION_ID, args = mapOf("task" to name))

    @Test
    fun aTaskThatIsNowhereToBeFoundIsFlagged() {
        val task = Task(id = 5, name = "Stopper", actions = listOf(stop("Nope"), set("%after")))

        val steps = PreflightRunner.preflightTask(task).tasks.single().steps

        assertEquals(PreflightStepStatus.BLOCKED, steps[0].status)
        assertTrue(steps[0].warnings.toString(), "Task to stop not found: Nope" in steps[0].warnings)
        assertEquals(PreflightStepStatus.SIMULATED, steps[1].status)
    }

    @Test
    fun aStepThatNamesNoTaskIsFlagged() {
        val task = Task(id = 5, name = "Stopper", actions = listOf(stop(" ")))

        val step = PreflightRunner.preflightTask(task).tasks.single().steps.single()

        assertEquals(PreflightStepStatus.BLOCKED, step.status)
        assertTrue(step.warnings.toString(), "task.stop requires a task id or name." in step.warnings)
    }

    @Test
    fun namingItsOwnTaskEndsThePreviewThere() {
        val task = Task(id = 5, name = "Self", actions = listOf(set("%before"), stop("Self"), set("%after")))

        val steps = PreflightRunner.preflightTask(task).tasks.single().steps

        assertEquals(PreflightStepStatus.SIMULATED, steps[1].status)
        assertEquals(PreflightStepStatus.SKIPPED, steps[2].status)
    }

    private fun runSub(name: String) = ActionSpec(type = SUB_TASK_ACTION_ID, args = mapOf("task" to name))

    private fun PreflightReport.stepsOf(name: String) = tasks.single { it.taskName == name }.steps

    @Test
    fun aSubTaskThatStopsItsCallerEndsBoth() {
        val caller = Task(id = 1, name = "Caller", actions = listOf(runSub("Child"), set("%x")))
        val child = Task(id = 2, name = "Child", actions = listOf(stop("Caller"), set("%y")))

        val report = PreflightRunner.preflightTask(caller, tasks = listOf(caller, child))

        assertEquals(PreflightStepStatus.SKIPPED, report.stepsOf("Child")[1].status)
        assertEquals(PreflightStepStatus.SKIPPED, report.stepsOf("Caller")[1].status)
    }

    @Test
    fun stoppingACallerFurtherUpFailsTheStepThatRanIt() {
        val top = Task(id = 1, name = "Top", actions = listOf(runSub("Middle"), set("%a")))
        val middle = Task(id = 2, name = "Middle", actions = listOf(runSub("Bottom"), set("%b")))
        val bottom = Task(id = 3, name = "Bottom", actions = listOf(stop("Middle"), set("%c")))

        val report = PreflightRunner.preflightTask(top, tasks = listOf(top, middle, bottom))

        assertEquals(PreflightStepStatus.SKIPPED, report.stepsOf("Bottom")[1].status)
        assertEquals(PreflightStepStatus.SKIPPED, report.stepsOf("Middle")[1].status)
        val ranMiddle = report.stepsOf("Top")[0]
        assertEquals(PreflightStepStatus.BLOCKED, ranMiddle.status)
        assertTrue(ranMiddle.warnings.toString(), ranMiddle.warnings.any { "Stopped by a Stop Task step in 'Bottom'" in it })
        assertEquals(PreflightStepStatus.SIMULATED, report.stepsOf("Top")[1].status)
    }

    @Test
    fun aSubTaskThatStopsItselfLetsTheCallerCarryOn() {
        val caller = Task(id = 1, name = "Caller", actions = listOf(runSub("Child"), set("%x")))
        val child = Task(id = 2, name = "Child", actions = listOf(stop("Child"), set("%y")))

        val report = PreflightRunner.preflightTask(caller, tasks = listOf(caller, child))

        assertEquals(PreflightStepStatus.SKIPPED, report.stepsOf("Child")[1].status)
        assertEquals(PreflightStepStatus.SIMULATED, report.stepsOf("Caller")[0].status)
        assertEquals(PreflightStepStatus.SIMULATED, report.stepsOf("Caller")[1].status)
    }

    @Test
    fun anotherTaskDoesntEndThePreview() {
        val other = Task(id = 6, name = "Other")
        val task = Task(id = 5, name = "Stopper", actions = listOf(stop("Other"), set("%after")))

        val steps = PreflightRunner.preflightTask(task, tasks = listOf(task, other)).tasks.single().steps

        assertEquals(PreflightStepStatus.SIMULATED, steps[0].status)
        assertEquals(PreflightStepStatus.SIMULATED, steps[1].status)
    }
}
