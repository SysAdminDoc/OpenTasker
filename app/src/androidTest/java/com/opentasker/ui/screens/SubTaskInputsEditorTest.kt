package com.opentasker.ui.screens

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import com.opentasker.core.actions.ActionMetadataRegistry
import com.opentasker.core.model.ActionSpec
import com.opentasker.core.model.Task
import com.opentasker.ui.theme.OpenTaskerTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

/** The Run Task editor shows, edits and checks the inputs it passes to the sub-task (A-354). */
class SubTaskInputsEditorTest {
    @get:Rule
    val composeTestRule = createAccessibilityComposeRule()

    private var saved: ActionSpec? = null

    private fun openRunTask(args: Map<String, String>) {
        val weather = Task(id = 2, name = "Weather")
        composeTestRule.setContent {
            OpenTaskerTheme {
                ActionConfigDialog(
                    state = ActionEditState(
                        task = Task(id = 1, name = "Morning"),
                        metadata = requireNotNull(ActionMetadataRegistry.get("task.run")),
                        existing = ActionSpec(id = 5, type = "task.run", args = args),
                    ),
                    tasks = listOf(Task(id = 1, name = "Morning"), weather),
                    onDismiss = {},
                    onSave = { saved = it },
                )
            }
        }
    }

    private fun scrollTo(text: String) {
        composeTestRule.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText(text))
    }

    @Test
    fun anAddedInputIsSavedAsAnArgument() {
        openRunTask(mapOf("task" to "2"))

        scrollTo("Add input")
        composeTestRule.onNodeWithText("Add input").performClick()
        scrollTo("Name")
        composeTestRule.onNodeWithText("Name").performTextInput("city")
        composeTestRule.onNodeWithText("Value").performTextInput("Boston")
        composeTestRule.onNodeWithText("Save").performClick()

        assertEquals(mapOf("task" to "2", "city" to "Boston"), saved?.args)
    }

    @Test
    fun aSavedInputIsShownAndABadNameBlocksSave() {
        openRunTask(mapOf("task" to "2", "city" to "Boston"))

        scrollTo("Boston")
        composeTestRule.onNodeWithText("city").performTextClearance()
        composeTestRule.onAllNodesWithText("Name").onLast().performTextInput("id")

        scrollTo("task, name and id choose the task to run. Pick another name.")
        composeTestRule.onNodeWithText("Save").assertIsNotEnabled().performClick()
        assertNull(saved)

        composeTestRule.onNodeWithText("id").performTextClearance()
        composeTestRule.onAllNodesWithText("Name").onLast().performTextInput("town")
        composeTestRule.onNodeWithText("Save").assertIsEnabled().performClick()
        assertEquals(mapOf("task" to "2", "town" to "Boston"), saved?.args)
    }
}
