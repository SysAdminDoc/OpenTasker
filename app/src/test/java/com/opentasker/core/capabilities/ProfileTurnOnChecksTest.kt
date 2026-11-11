package com.opentasker.core.capabilities

import com.opentasker.core.model.ActionSpec
import com.opentasker.core.model.ContextSpec
import com.opentasker.core.model.ContextType
import com.opentasker.core.model.Profile
import com.opentasker.core.model.Task
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A task or broadcast can't turn on a profile the app wouldn't turn on from its list. */
class ProfileTurnOnChecksTest {

    private val plain = Task(id = 1, name = "Plain", actions = listOf(ActionSpec(type = "notify.show", args = mapOf("title" to "Hi"))))

    private fun settingTask(id: Long, name: String) = Task(
        id = id,
        name = name,
        priority = 5,
        actions = listOf(ActionSpec(type = "brightness.set", args = mapOf("value" to "20"))),
    )

    private fun profile(id: Long, name: String, enterTaskId: Long, enabled: Boolean = false) = Profile(
        id = id,
        name = name,
        enabled = enabled,
        contexts = listOf(ContextSpec(ContextType.APPLICATION, mapOf("package" to "com.example.reader"))),
        enterTaskId = enterTaskId,
    )

    private fun refusal(
        profile: Profile,
        tasks: List<Task>,
        otherProfiles: List<Profile> = listOf(profile),
        canWriteSettings: Boolean = true,
    ) = ProfileTurnOnChecks.refusal(profile, tasks, otherProfiles, canWriteSettings)

    @Test
    fun aProfileTheAppWouldTurnOnPasses() {
        assertNull(refusal(profile(5, "Reader", plain.id), listOf(plain)))
    }

    @Test
    fun oneStillWaitingForItsReviewStaysOff() {
        val waiting = profile(5, "Imported", plain.id).copy(requiresRiskAcknowledgement = true)

        val message = refusal(waiting, listOf(plain))
        assertTrue(message.toString(), message!!.contains("needs its review in the app"))
        assertNull(refusal(waiting.copy(requiresRiskAcknowledgement = false), listOf(plain)))
    }

    @Test
    fun oneWhoseTasksRunInALoopStaysOff() {
        val loop = Task(id = 2, name = "Loop", actions = listOf(ActionSpec(type = "task.run", args = mapOf("task" to "2"))))
        val looping = profile(5, "Looping", loop.id)

        val message = refusal(looping, listOf(loop))
        assertTrue(message.toString(), message!!.contains("loop (Loop -> Loop)"))
        // Pointed at a task that doesn't call itself, the same profile turns on.
        assertNull(refusal(looping.copy(enterTaskId = plain.id), listOf(loop, plain)))
    }

    @Test
    fun oneThatWouldFightAnActiveSamePriorityProfileOverASettingStaysOff() {
        val dim = settingTask(10, "Dim display")
        val bright = settingTask(11, "Bright display")
        val reader = profile(1, "Reader", dim.id, enabled = true)
        val focus = profile(2, "Reader focus", bright.id)

        val message = refusal(focus, listOf(dim, bright), listOf(reader, focus))
        assertTrue(message.toString(), message!!.contains("Priority conflict"))
        // With the other profile off there's nothing to fight over, so it turns on.
        assertNull(refusal(focus, listOf(dim, bright), listOf(reader.copy(enabled = false), focus)))
    }

    @Test
    fun oneThatChangesSystemSettingsNeedsTheGrant() {
        val dim = settingTask(10, "Dim display")
        val reader = profile(1, "Reader", dim.id)

        val message = refusal(reader, listOf(dim), canWriteSettings = false)
        assertTrue(message.toString(), message!!.contains("changes system settings"))
        assertNull(refusal(reader, listOf(dim), canWriteSettings = true))
    }
}
