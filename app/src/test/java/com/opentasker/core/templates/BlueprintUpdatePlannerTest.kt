package com.opentasker.core.templates

import com.opentasker.core.model.ActionSpec
import com.opentasker.core.model.ContextSpec
import com.opentasker.core.model.ContextType
import com.opentasker.core.model.Profile
import com.opentasker.core.model.Task
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BlueprintUpdatePlannerTest {
    @Test
    fun newerBlueprintProducesDiffWithoutMutatingInstalledRecords() {
        val blueprint = blueprint(version = 2)
        val installedTask = Task(
            id = 22,
            name = "Installed task",
            actions = listOf(ActionSpec(type = "log", label = "Old label", args = mapOf("message" to "old"))),
        )
        val installedProfile = Profile(
            id = 11,
            name = "Installed profile",
            enabled = true,
            enterTaskId = installedTask.id,
            contexts = listOf(ContextSpec(ContextType.STATE, mapOf("mode" to "old"))),
        )
        val installation = BlueprintInstallation(
            blueprintId = blueprint.id,
            blueprintVersion = 1,
            profileId = installedProfile.id,
            taskId = installedTask.id,
            inputValues = blueprint.defaults(),
        )

        val review = BlueprintUpdatePlanner.plan(blueprint, installation, installedProfile, installedTask)

        assertNotNull(review)
        assertEquals(1, review!!.installedVersion)
        assertEquals(2, review.incomingVersion)
        assertTrue(review.hasChanges)
        assertTrue(review.document.changes.any { it.path.contains("Action") || it.path == "Enabled" })
        assertEquals("Old label", installedTask.actions.single().label)
        assertTrue(installedProfile.enabled)
    }

    @Test
    fun sameOrOlderDefinitionDoesNotCreateAnUpdateReview() {
        val blueprint = blueprint(version = 2)
        val installation = BlueprintInstallation(blueprint.id, 2, 11, 22, blueprint.defaults())

        assertNull(BlueprintUpdatePlanner.plan(blueprint, installation, Profile(id = 11, name = "p", enterTaskId = 22), Task(id = 22, name = "t")))
        assertNull(BlueprintUpdatePlanner.plan(blueprint.copy(version = 1), installation, Profile(id = 11, name = "p", enterTaskId = 22), Task(id = 22, name = "t")))
    }

    @Test
    fun missingInstanceIsReportedAndNeverConvertedIntoAWrite() {
        val blueprint = blueprint(version = 3)
        val installation = BlueprintInstallation(blueprint.id, 1, 11, 22, blueprint.defaults())

        val review = BlueprintUpdatePlanner.plan(blueprint, installation, currentProfile = null, currentTask = null)

        assertNotNull(review)
        assertTrue(review!!.error.orEmpty().contains("no longer available"))
        assertTrue(review.document.isEmpty)
    }

    @Test
    fun anUpdateReportsAChangedExitTaskWithoutClaimingTheProfileLostIt() {
        val updated = blueprint(version = 2).copy(
            exitActions = listOf(TemplateAction("log", "New goodbye", mapOf("message" to "bye"))),
        )
        val applied = updated.instantiate(emptyMap())
        val enterTask = applied.task.copy(id = 22)
        val exitTask = applied.exitTask!!.copy(
            id = 33,
            actions = listOf(ActionSpec(type = "log", label = "Old goodbye", args = mapOf("message" to "ciao"))),
        )
        val profile = applied.profile.copy(id = 11, enterTaskId = enterTask.id, exitTaskId = exitTask.id)
        val installation = BlueprintInstallation(updated.id, 1, profile.id, enterTask.id, updated.defaults(), exitTask.id)

        val review = BlueprintUpdatePlanner.plan(updated, installation, profile, enterTask, exitTask)!!

        assertTrue(review.hasChanges)
        assertTrue(review.document.changes.none { it.path == "Exit task" })
        assertEquals(1, review.document.entries.size)

        val unchanged = BlueprintUpdatePlanner.plan(updated, installation, profile, enterTask, applied.exitTask!!.copy(id = 33))!!
        assertTrue(unchanged.document.isEmpty)
    }

    private fun blueprint(version: Int) = AutomationBlueprint(
        id = "planner-test",
        version = version,
        title = "Planner test",
        summary = "Planner test blueprint",
        category = "Tests",
        availability = TemplateAvailability.Ready,
        safetyNote = "Test only",
        inputs = listOf(BlueprintInput("message", "Message", "new")),
        contexts = listOf(TemplateContext(ContextType.STATE, mapOf("mode" to "new"))),
        actions = listOf(TemplateAction("log", "New label", mapOf("message" to "{message}"))),
    )
}
