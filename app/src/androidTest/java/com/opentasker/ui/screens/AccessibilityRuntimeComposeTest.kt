package com.opentasker.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import com.opentasker.core.model.ActionSpec
import com.opentasker.core.model.Profile
import com.opentasker.core.model.Task
import com.opentasker.core.references.AutomationReferenceIndex
import com.opentasker.ui.theme.OpenTaskerTheme
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class AccessibilityRuntimeComposeTest(private val fontScale: Float) {
    @get:Rule
    val composeTestRule = createAccessibilityComposeRule()

    private lateinit var restoreFontScale: () -> Unit

    @Before
    fun applyFontScale() {
        restoreFontScale = composeTestRule.activity.useFontScale(fontScale)
    }

    @After
    fun undoFontScale() {
        restoreFontScale()
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "font {0}")
        fun fontScales() = TESTED_FONT_SCALES
    }

    @Test
    fun profilesScreenPassesRuntimeAccessibilityChecks() {
        val task = Task(
            id = 7,
            name = "Morning focus",
            actions = listOf(ActionSpec(type = "log", label = "Record focus")),
        )
        val profile = Profile(id = 8, name = "Work hours", enterTaskId = task.id)

        composeTestRule.setContent {
            OpenTaskerTheme {
                ProfilesScreen(
                    profiles = listOf(profile),
                    tasks = listOf(task),
                    runLogs = emptyList(),
                    storageDecodeIssues = emptyList(),
                    onCreateTaskFirst = {},
                    onCreateProfile = {},
                    onBrowseTemplates = {},
                    onPreviewProfileShare = {},
                    onPreflightProfile = {},
                    onExportOpenTaskerBundle = {},
                    onImportOpenTaskerBundle = {},
                    onImportOpenTaskerBundleText = {},
                    openTaskerBundleBusy = false,
                    onImportTaskerXml = {},
                    onExportTaskerXml = {},
                    taskerImportBusy = false,
                    onEditProfile = {},
                    onDeleteProfile = {},
                    onToggleProfile = { _, _ -> },
                    onAddContext = {},
                    onEditContextLogic = {},
                    onEditContext = { _, _, _ -> },
                    onDeleteContext = { _, _ -> },
                    contentPadding = PaddingValues(0.dp),
                )
            }
        }

        composeTestRule.performAccessibilityChecks()
        composeTestRule.onNodeWithText("Work hours").assertIsDisplayed()
    }

    @Test
    fun destructiveDeleteDialogPassesRuntimeAccessibilityChecks() {
        val task = Task(id = 7, name = "Remove me")
        val profile = Profile(id = 8, name = "Work hours", enterTaskId = task.id)
        val references = AutomationReferenceIndex.referencesTo(task, profiles = listOf(profile))
        composeTestRule.setContent {
            OpenTaskerTheme {
                TaskDeleteReferencesDialog(
                    preview = TaskDeletionPreview(task, references, requiresReassignment = references.any { it.isRequired }),
                    tasks = listOf(task, Task(id = 9, name = "Keep me")),
                    onDismiss = {},
                    onConfirm = {},
                )
            }
        }

        composeTestRule.performAccessibilityChecks()
        composeTestRule.onNodeWithText("Remove me", substring = true).assertIsDisplayed()
    }

    @Test
    fun accessibilityChecksRejectUnlabelledIconButton() {
        composeTestRule.setContent {
            OpenTaskerTheme {
                IconButton(onClick = {}) {
                    Icon(Icons.Filled.Delete, contentDescription = null)
                }
            }
        }

        val failure = runCatching { composeTestRule.performAccessibilityChecks() }.exceptionOrNull()
        assertTrue("An unlabeled icon button must fail the runtime accessibility check", failure != null)
    }

    @Test
    fun bottomNavigationItemReadsItsLabelOnce() {
        composeTestRule.setContent {
            OpenTaskerTheme {
                Row {
                    OpenTaskerNavigationItem(
                        selected = true,
                        onClick = {},
                        icon = Icons.Filled.Delete,
                        label = "Tasks",
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        composeTestRule.performAccessibilityChecks()
        composeTestRule.onNodeWithText("Tasks").assertIsDisplayed()
    }

    @Test
    fun accessibilityChecksRejectARepeatedReading() {
        composeTestRule.setContent {
            OpenTaskerTheme {
                Row(Modifier.heightIn(min = 48.dp).clickable(onClick = {})) {
                    Icon(Icons.Filled.Delete, contentDescription = "Delete")
                    Text("Delete")
                }
            }
        }

        val failure = runCatching { composeTestRule.performAccessibilityChecks() }.exceptionOrNull()
        assertTrue(
            "An icon described the same as its label must fail the check, got $failure",
            failure?.message.orEmpty().contains("Read twice"),
        )
    }

    @Test
    fun accessibilityChecksRejectSmallClickableTarget() {
        composeTestRule.setContent {
            OpenTaskerTheme {
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clickable(onClick = {}),
                )
            }
        }

        val failure = runCatching { composeTestRule.performAccessibilityChecks() }.exceptionOrNull()
        assertTrue("A sub-48dp clickable target must fail the runtime accessibility check", failure != null)
    }
}
