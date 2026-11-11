package com.opentasker.core.engine

import com.opentasker.core.registerCoreRuntime
import com.opentasker.core.actions.registerActionMetadata
import com.opentasker.core.capabilities.CapabilityLevel
import com.opentasker.core.capabilities.SetupRequirement
import com.opentasker.core.actions.ActiveTransport
import com.opentasker.core.model.ActionSpec
import com.opentasker.core.model.ContextSpec
import com.opentasker.core.model.ContextType
import com.opentasker.core.model.Profile
import com.opentasker.core.model.Task
import com.opentasker.ui.screens.grantedSetupRequirements
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PreflightRunnerTest {
    @Before
    fun setUp() {
        registerActionMetadata()
        registerCoreRuntime()
    }

    @Test
    fun preflightBlocksAConnectionRestrictedRequestTheCurrentNetworkCannotSatisfy() {
        val task = Task(
            id = 9,
            name = "Sync over Wi-Fi",
            actions = listOf(
                ActionSpec(
                    type = "http.request",
                    args = mapOf("url" to "https://example.test/sync", "network" to "wifi"),
                ),
            ),
        )

        val onCellular = PreflightRunner.preflightTask(
            task = task,
            inputs = PreflightInputs(
                activeTransport = ActiveTransport(connected = true, cellular = true),
            ),
        )
        val blocked = onCellular.tasks.single().steps.single()
        assertEquals(PreflightStepStatus.BLOCKED, blocked.status)
        assertTrue(blocked.warnings.toString(), blocked.warnings.any { "limited to wifi" in it })
        assertFalse(onCellular.canPreflight)

        val onWifi = PreflightRunner.preflightTask(
            task = task,
            inputs = PreflightInputs(
                activeTransport = ActiveTransport(connected = true, wifi = true, unmetered = true),
            ),
        )
        assertEquals(PreflightStepStatus.SIMULATED, onWifi.tasks.single().steps.single().status)

        // An unknown transport is the default, and a preview must not invent a network failure.
        val unknown = PreflightRunner.preflightTask(task = task, inputs = PreflightInputs())
        assertEquals(PreflightStepStatus.SIMULATED, unknown.tasks.single().steps.single().status)
    }

    @Test
    fun aGrantSetupShowsAsDoneIsNotListedAsMissing() {
        val report = PreflightRunner.preflightTask(
            task = dimTask,
            inputs = PreflightInputs(grantedSetupRequirements = grantedSetupRequirements(pending = emptySet())),
        )

        assertEquals(setOf(SetupRequirement.WRITE_SETTINGS), report.setupRequirements)
        assertTrue(report.missingSetupRequirements.toString(), report.missingSetupRequirements.isEmpty())
    }

    @Test
    fun aGrantSetupStillListsIsNamedAsMissing() {
        val report = PreflightRunner.preflightTask(
            task = dimTask,
            inputs = PreflightInputs(
                grantedSetupRequirements = grantedSetupRequirements(pending = setOf(SetupRequirement.WRITE_SETTINGS)),
            ),
        )

        assertEquals(setOf(SetupRequirement.WRITE_SETTINGS), report.missingSetupRequirements)
    }

    @Test
    fun aTaskTheListLacksOrHasAnOlderCopyOfStillListsItsOwnSetup() {
        val olderCopy = dimTask.copy(actions = listOf(ActionSpec(type = "var.set", args = mapOf("name" to "x", "value" to "1"))))

        listOf(emptyList(), listOf(olderCopy)).forEach { stored ->
            val report = PreflightRunner.preflightTask(task = dimTask, tasks = stored)

            assertEquals(stored.toString(), setOf(SetupRequirement.WRITE_SETTINGS), report.setupRequirements)
        }
    }

    private val dimTask = Task(id = 4, name = "Dim", actions = listOf(ActionSpec(type = "brightness.set", args = mapOf("level" to "40"))))

    @Test
    fun aPersistedVariableGetsTheGlobalNameTheRunGivesIt() {
        val task = Task(
            id = 5,
            name = "Keep mode",
            actions = listOf(
                ActionSpec(type = "var.set", args = mapOf("name" to "mode", "value" to "night")),
                ActionSpec(type = "var.persist", args = mapOf("name" to "mode")),
                ActionSpec(type = "var.set", args = mapOf("name" to "check", "value" to "%Mode")),
            ),
        )

        val steps = PreflightRunner.preflightTask(task = task).tasks.single().steps

        assertEquals("night", steps[2].expandedArguments["value"])
    }

    @Test
    fun aPersistedVariableWhoseGlobalNameComesOutBlankSetsNothingAsTheRunDoes() {
        fun checkAfter(globalName: String, read: String): String? {
            val task = Task(
                id = 6,
                name = "Keep mode",
                actions = listOf(
                    ActionSpec(type = "var.set", args = mapOf("name" to "mode", "value" to "night")),
                    ActionSpec(type = "var.persist", args = mapOf("name" to "mode", "global_name" to globalName)),
                    ActionSpec(type = "var.set", args = mapOf("name" to "check", "value" to read)),
                ),
            )
            val inputs = PreflightInputs(eventVariables = mapOf("blank" to ""))
            return PreflightRunner.preflightTask(task = task, inputs = inputs).tasks.single().steps[2].expandedArguments["value"]
        }

        assertEquals("night", checkAfter("saved", "%Saved"))
        // The run fails with an invalid global name here, so Mode is never set.
        assertNotEquals("night", checkAfter("{{ event.blank }}", "%Mode"))
    }

    @Test
    fun preflightExpandsSyntheticEventsAndReportsSelectedBranchWithoutRunningActions() {
        val task = Task(
            id = 1,
            name = "Synthetic branch",
            actions = listOf(
                ActionSpec(type = FlowControl.IF, args = mapOf("condition" to "{{ event.mode }} == on")),
                ActionSpec(type = "var.set", args = mapOf("name" to "message", "value" to "{{ event.payload }}")),
                ActionSpec(type = FlowControl.ELSE),
                ActionSpec(type = "var.set", args = mapOf("name" to "message", "value" to "fallback")),
                ActionSpec(type = FlowControl.ENDIF),
            ),
        )

        val report = PreflightRunner.preflightTask(
            task = task,
            inputs = PreflightInputs(eventVariables = mapOf("mode" to "off", "payload" to "private")),
        )
        val steps = report.tasks.single().steps

        assertTrue(report.sideEffectsSuppressed)
        assertTrue(report.canPreflight)
        assertEquals("{{ event.mode }} == on", steps.first().condition)
        assertEquals("{{ event.mode }} == on -> false", steps.first().branchDecision)
        assertEquals(PreflightStepStatus.SKIPPED, steps[1].status)
        assertEquals("fallback", steps[3].expandedArguments["value"])
        assertTrue(steps[3].status == PreflightStepStatus.SIMULATED)
        assertFalse(steps.any { it.status == PreflightStepStatus.BLOCKED })
    }

    @Test
    fun preflightBlocksUnknownActionsAndSurfacesSetupRequirements() {
        val task = Task(
            id = 2,
            name = "Guarded side effects",
            actions = listOf(
                ActionSpec(type = "brightness.set", args = mapOf("brightness" to "20")),
                ActionSpec(type = "future.unclassified", args = mapOf("token" to "secret")),
            ),
        )

        val report = PreflightRunner.preflightTask(task)

        assertTrue(SetupRequirement.WRITE_SETTINGS in report.setupRequirements)
        assertTrue(SetupRequirement.WRITE_SETTINGS in report.missingSetupRequirements)
        assertEquals(CapabilityLevel.RequiresSetup, report.tasks.single().steps[0].capability)
        assertTrue(report.tasks.single().steps[1].status == PreflightStepStatus.BLOCKED)
        assertFalse(report.canPreflight)
        assertTrue(report.tasks.single().steps[1].warnings.any { it.contains("unknown action") })
    }

    @Test
    fun profilePreflightReportsContextSummaryAndMissingTaskBeforeAnyWrite() {
        val profile = Profile(
            id = 8,
            name = "Broken profile",
            contexts = listOf(ContextSpec(ContextType.EVENT, mapOf("event" to "push"))),
            enterTaskId = 404,
        )

        val report = PreflightRunner.preflightProfile(profile, emptyList())

        assertEquals(1, report.contexts.size)
        assertTrue(report.tasks.isEmpty())
        assertTrue(report.warnings.single().contains("missing task 404"))
        assertTrue(report.sideEffectsSuppressed)
    }

    @Test
    fun profilePreflightIncludesReferencedEntryAndExitTasks() {
        val profile = Profile(
            id = 9,
            name = "Complete profile",
            enterTaskId = 10,
            exitTaskId = 11,
        )
        val tasks = listOf(
            Task(id = 10, name = "Enter", actions = listOf(ActionSpec(type = "var.set", args = mapOf("name" to "phase", "value" to "enter")))),
            Task(id = 11, name = "Exit", actions = listOf(ActionSpec(type = "var.set", args = mapOf("name" to "phase", "value" to "exit")))),
        )

        val report = PreflightRunner.preflightProfile(profile, tasks)

        assertEquals(listOf("Complete profile / Enter", "Complete profile / Exit"), report.tasks.map { it.taskPath })
        assertTrue(report.canPreflight)
    }

    @Test
    fun everyRegisteredRuntimeActionHasAnExplicitNoSideEffectPreviewImplementation() {
        assertTrue(PreflightActionRegistry.missingRegisteredImplementations().isEmpty())
    }
}
