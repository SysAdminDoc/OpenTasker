package com.opentasker.core.actions

import com.opentasker.core.model.ActionSpec
import com.opentasker.core.model.Task
import com.opentasker.core.transfer.OpenTaskerBundleCodec
import org.junit.Assert.assertEquals
import org.junit.Test

/** Run Task inputs can be edited without hand-editing a bundle (A-354). */
class SubTaskInputsTest {
    @Test
    fun theTaskReferenceAndFormFieldsAreNotInputs() {
        val args = mapOf("task" to "2", "name" to "Old ref", "id" to "3", "city" to "Boston", "Mode" to "fast")

        assertEquals(
            listOf(SubTaskInput("city", "Boston"), SubTaskInput("Mode", "fast")),
            SubTaskInputs.from(args, fieldKeys = setOf("task")),
        )
    }

    @Test
    fun badNamesAreRefusedRowByRow() {
        val issues = SubTaskInputs.validate(
            listOf(
                SubTaskInput("city", "Boston"),
                SubTaskInput("9lives", "x"),
                SubTaskInput("name", "x"),
                SubTaskInput("%city", "Denver"),
                SubTaskInput("", ""),
                SubTaskInput("", "orphan value"),
            ),
        )

        assertEquals(
            mapOf(
                1 to SubTaskInputIssue.InvalidName,
                2 to SubTaskInputIssue.PicksTheTask,
                // %city is city once the sigil comes off, so it collides with the first row.
                3 to SubTaskInputIssue.Duplicate,
                5 to SubTaskInputIssue.InvalidName,
            ),
            issues,
        )
    }

    @Test
    fun savingReplacesTheInputsItOpenedWithAndKeepsEverythingElse() {
        val opened = listOf(SubTaskInput("city", "Boston"), SubTaskInput("units", "imperial"))
        val merged = mapOf("task" to "2", "city" to "Boston", "units" to "imperial", "legacy" to "kept")

        val saved = SubTaskInputs.applyTo(
            merged,
            opened = opened,
            edited = listOf(SubTaskInput("%town", "Salem"), SubTaskInput("", "")),
        )

        assertEquals(mapOf("task" to "2", "legacy" to "kept", "town" to "Salem"), saved)
    }

    @Test
    fun inputsSurviveExportAndReimport() {
        val task = Task(
            id = 1,
            name = "Weather",
            actions = listOf(ActionSpec(type = "task.run", args = mapOf("task" to "2", "city" to "Boston"))),
        )
        val bundle = OpenTaskerBundleCodec.build(appVersion = "test", exportedAtEpochMs = 1L, profiles = emptyList(), tasks = listOf(task))

        val reimported = OpenTaskerBundleCodec.decode(OpenTaskerBundleCodec.encode(bundle)).tasks.single().actions.single()

        assertEquals(listOf(SubTaskInput("city", "Boston")), SubTaskInputs.from(reimported.args, fieldKeys = setOf("task")))
    }
}
