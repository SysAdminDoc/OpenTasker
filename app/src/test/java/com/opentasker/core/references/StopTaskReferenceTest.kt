package com.opentasker.core.references

import com.opentasker.core.model.ActionSpec
import com.opentasker.core.model.Task
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A Stop Task step names a task the same way Perform Task does, so renames and deletes have to follow it too. */
class StopTaskReferenceTest {

    private val target = Task(id = 10, name = "Morning")
    private val other = Task(id = 20, name = "Evening")

    private fun stopper(vararg actions: ActionSpec) = Task(id = 30, name = "Stopper", actions = actions.toList())

    @Test
    fun theIndexFindsStopTaskStepsByIdAndByName() {
        val stopper = stopper(
            ActionSpec(type = "task.stop", args = mapOf("task" to "10")),
            ActionSpec(type = "task.stop", args = mapOf("task" to "Morning")),
            ActionSpec(type = "task.stop", args = mapOf("task" to "20")),
        )

        val references = AutomationReferenceIndex.referencesTo(task = target, tasks = listOf(stopper))

        assertEquals(listOf("StopTaskStep", "StopTaskStep"), references.map { it.site::class.simpleName })
        assertEquals(listOf(0, 1), references.map { (it.site as TaskReferenceSite.StopTaskStep).actionIndex })
        assertTrue(references.none { it.isRequired })
    }

    @Test
    fun deletingWithReassignPointsTheStepAtTheOtherTask() {
        val rewrite = AutomationReferenceRewriter.retarget(
            target = target,
            resolution = ReferenceResolution.Reassign(other),
            tasks = listOf(stopper(ActionSpec(type = "task.stop", args = mapOf("task" to "Morning")))),
        )

        assertTrue(rewrite.canCommit)
        assertEquals("20", rewrite.tasks.single().actions.single().args["task"])
    }

    @Test
    fun deletingWithClearDropsTheReference() {
        val rewrite = AutomationReferenceRewriter.retarget(
            target = target,
            resolution = ReferenceResolution.Clear,
            tasks = listOf(stopper(ActionSpec(type = "task.stop", args = mapOf("task" to "10")))),
        )

        assertTrue(rewrite.canCommit)
        assertNull(rewrite.tasks.single().actions.single().args["task"])
    }

    @Test
    fun renamingPinsANameReferenceToTheId() {
        val rewrite = AutomationReferenceRewriter.stabilizeNameReferences(
            target = target,
            tasks = listOf(stopper(ActionSpec(type = "task.stop", args = mapOf("task" to "morning")))),
        )

        assertEquals("10", rewrite.tasks.single().actions.single().args["task"])
    }

    @Test
    fun renamingATaskPinsItsOwnStopTaskStepToItsId() {
        val before = Task(
            id = 10,
            name = "Morning",
            actions = listOf(
                ActionSpec(type = "task.stop", args = mapOf("task" to "morning")),
                ActionSpec(type = "task.stop", args = mapOf("task" to "Evening")),
            ),
        )

        val renamed = AutomationReferenceRewriter.stabilizeOwnNameReferences(before, before.copy(name = "Dawn"))

        assertEquals("Dawn", renamed.name)
        assertEquals(listOf("10", "Evening"), renamed.actions.map { it.args["task"] })
    }

    @Test
    fun importedIdsAreRemapped() {
        val rewrite = AutomationReferenceRewriter.remapIds(
            idMap = mapOf(7L to 700L),
            tasks = listOf(stopper(ActionSpec(type = "task.stop", args = mapOf("task" to "7")))),
        )

        assertEquals("700", rewrite.tasks.single().actions.single().args["task"])
    }
}
