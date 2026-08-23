package com.opentasker.ui.screens

import com.opentasker.core.actions.NotificationTaskResolution
import com.opentasker.core.model.Task
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationTaskEditorMigrationTest {
    @Test
    fun uniqueLegacyNameIsWrittenIntoCurrentIdField() {
        val tasks = listOf(Task(id = 7, name = "Morning"))

        val value = existingActionArgValue(
            actionId = "notify.show",
            key = "button1_task_id",
            args = mapOf("button1_task" to "Morning"),
            tasks = tasks,
        )

        assertEquals("7", value)
        assertTrue(unresolvedNotificationTaskBindings("notify.show", mapOf("button1_task" to "Morning"), tasks).isEmpty())
    }

    @Test
    fun duplicateLegacyNameRequiresExplicitReselection() {
        val tasks = listOf(Task(id = 7, name = "Duplicate"), Task(id = 8, name = "Duplicate"))
        val args = mapOf("button2_task" to "Duplicate")

        val value = existingActionArgValue("notify.show", "button2_task_id", args, tasks)
        val issue = unresolvedNotificationTaskBindings("notify.show", args, tasks).getValue("button2_task_id")

        assertEquals("", value)
        assertEquals(NotificationTaskResolution.Ambiguous("Duplicate", 2), issue)
    }

    @Test
    fun importedFlowIfConditionPopulatesItsOwnRequiredEditorField() {
        // flow.if's "condition" catalog field is a required text field the editor prefills from
        // args["condition"] (see the `args[key] ?: ...` lookup at the top of
        // existingActionArgValue). TaskerXmlImport writes the parsed Tasker <ConditionList> into
        // both action.condition and args["condition"] for this action type specifically, so the
        // field must come back non-blank -- a blank read here would mean an imported "if" opens
        // in the editor with its required condition field looking empty despite already working.
        val value = existingActionArgValue(
            actionId = "flow.if",
            key = "condition",
            args = mapOf("condition" to "%text is_set"),
        )

        assertEquals("%text is_set", value)
    }
}
