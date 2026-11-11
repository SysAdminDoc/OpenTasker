package com.opentasker.core.capabilities

import com.opentasker.core.model.ActionSpec
import com.opentasker.core.model.ContextSpec
import com.opentasker.core.model.ContextType
import com.opentasker.core.model.Profile
import com.opentasker.core.model.Task
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A step turned off never runs, so what a profile can do, what it needs granted and what lint warns
 * about all leave it out. Each check also runs with the step on, so a pass means the flag did it.
 */
class TurnedOffStepAnalysisTest {

    private fun ActionSpec.off() = copy(enabled = false)

    private fun profile(enterTaskId: Long, requiresReview: Boolean = false) = Profile(
        id = 1,
        name = "Reader",
        enabled = !requiresReview,
        contexts = listOf(ContextSpec(ContextType.APPLICATION, mapOf("package" to "com.example.reader"))),
        enterTaskId = enterTaskId,
        requiresRiskAcknowledgement = requiresReview,
    )

    @Test
    fun anImportedProfileWhosePlaceholderIsTurnedOffCanBeTurnedOn() {
        fun review(step: ActionSpec) = ImportedProfileEnablePolicy.review(
            profile(enterTaskId = 1, requiresReview = true),
            listOf(Task(id = 1, name = "Imported", actions = listOf(step, ActionSpec(type = "var.set", args = mapOf("name" to "%a", "value" to "1"))))),
        )

        val off = review(ActionSpec(type = "tasker.unsupported").off())
        assertTrue(off.unsupportedActionIds.isEmpty())
        assertTrue(off.canAcknowledge)

        val on = review(ActionSpec(type = "tasker.unsupported"))
        assertEquals(setOf("tasker.unsupported"), on.unsupportedActionIds)
        assertFalse(on.canAcknowledge)
    }

    @Test
    fun aStepTurnedOffLendsTheTaskNoPowers() {
        fun summary(step: ActionSpec) = AutomationSensitivityRegistry.summarize(Task(id = 1, name = "Post", actions = listOf(step)))

        assertTrue(summary(ActionSpec(type = "http.post").off()).powers.isEmpty())
        assertTrue(AutomationPower.EXTERNAL_TRANSMISSION in summary(ActionSpec(type = "http.post")).powers)
    }

    @Test
    fun aTaskRunTurnedOffReachesNothing() {
        val child = Task(id = 2, name = "Child", actions = listOf(ActionSpec(type = "var.set")))
        val call = ActionSpec(type = "task.run", args = mapOf("task" to "2"))
        fun reached(step: ActionSpec) = AutomationSensitivityRegistry
            .reachableTasks(profile(enterTaskId = 1), listOf(Task(id = 1, name = "Parent", actions = listOf(step)), child))
            .map(Task::id)

        assertEquals(listOf(1L), reached(call.off()))
        assertEquals(listOf(1L, 2L), reached(call))
    }

    @Test
    fun setupDoesntAskForWhatOnlyAStepTurnedOffNeeds() {
        fun required(step: ActionSpec) =
            SetupRequirementResolver.resolve(listOf(Profile(id = 1, name = "Send", enterTaskId = 1)), listOf(Task(id = 1, name = "Send", actions = listOf(step))))

        assertTrue(required(ActionSpec(type = "sms.send").off()).isEmpty())
        assertTrue(required(ActionSpec(type = "sms.send")).isNotEmpty())
    }

    @Test
    fun aTaskRunTurnedOffMakesNoLoop() {
        fun risks(step: ActionSpec) = AutomationFeedbackRiskAnalyzer.analyze(
            Profile(name = "Loop", enterTaskId = 1),
            listOf(Task(id = 1, name = "Loop", actions = listOf(step))),
        )
        val self = ActionSpec(type = "task.run", args = mapOf("task" to "1"))

        assertTrue(risks(self.off()).isEmpty())
        assertEquals(1, risks(self).size)
    }

    @Test
    fun lintIgnoresASettingWriteTurnedOff() {
        fun codes(step: ActionSpec) =
            AutomationLint.analyze(profile(enterTaskId = 10), listOf(Task(id = 10, name = "Dim", actions = listOf(step)))).findings.map { it.code }
        val dim = ActionSpec(type = "brightness.set", args = mapOf("value" to "20"))

        assertFalse(AutomationLintCode.MISSING_REVERSAL in codes(dim.off()))
        assertTrue(AutomationLintCode.MISSING_REVERSAL in codes(dim))
    }
}
