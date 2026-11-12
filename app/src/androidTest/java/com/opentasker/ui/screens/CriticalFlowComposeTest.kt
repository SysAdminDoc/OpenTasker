package com.opentasker.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.unit.dp
import androidx.test.espresso.Espresso
import com.opentasker.app.R
import com.opentasker.core.actions.ActionField
import com.opentasker.core.actions.ActionFieldOption
import com.opentasker.core.actions.ActionMetadata
import com.opentasker.core.actions.ActionNumberRule
import com.opentasker.core.actions.FieldType
import com.opentasker.core.apps.InstalledApp
import com.opentasker.core.capabilities.SetupRequirement
import com.opentasker.core.engine.ActionExecutionTrace
import com.opentasker.core.engine.ActionTraceStatus
import com.opentasker.core.engine.runLogMessage
import com.opentasker.core.model.ActionSpec
import com.opentasker.core.model.CollisionMode
import com.opentasker.core.model.ContextSpec
import com.opentasker.core.model.ContextType
import com.opentasker.core.model.Profile
import com.opentasker.core.model.RunLogEntry
import com.opentasker.core.model.Scene
import com.opentasker.core.model.SceneElement
import com.opentasker.core.model.SceneElementType
import com.opentasker.core.model.Task
import com.opentasker.core.references.AutomationReferenceIndex
import com.opentasker.core.storage.RunLogRetentionPolicy
import com.opentasker.core.templates.ProfileTemplateCatalog
import com.opentasker.core.transfer.BundleImportPlan
import com.opentasker.core.transfer.OpenTaskerBundle
import com.opentasker.core.transfer.VariableConflictAction
import com.opentasker.core.transfer.VariableConflictResolution
import com.opentasker.core.transfer.VariableImportConflict
import com.opentasker.ui.theme.OpenTaskerTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class CriticalFlowComposeTest(private val fontScale: Float) {
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

    /** Works on content a lazy list hasn't composed yet, which performScrollTo can't find. */
    private fun scrollToText(text: String) =
        composeTestRule.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText(text))

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "font {0}")
        fun fontScales() = TESTED_FONT_SCALES
    }

    @Test
    fun templateSlotDialogRendersPackagedAutomationFields() {
        val template = requireNotNull(ProfileTemplateCatalog.get("work-hours-focus"))

        composeTestRule.setContent {
            TestTheme {
                TemplateSlotDialog(
                    template = template,
                    onDismiss = {},
                    onInstall = {},
                )
            }
        }
        composeTestRule.performAccessibilityChecks()

        composeTestRule.onNodeWithText("Work-hours focus").assertIsDisplayed()
        scrollToText("Start time *")
        composeTestRule.onNodeWithText("Start time *").assertIsDisplayed()
        composeTestRule.onNodeWithText("Create for Review").assertIsEnabled()
    }

    @Test
    fun settingsOffersRunningOnboardingAgain() {
        var reopenedTemplatePicker = false
        composeTestRule.setContent {
            TestTheme {
                PermissionOnboardingScreen(
                    contentPadding = PaddingValues(0.dp),
                    onMessage = {},
                    onUndoableMessage = { _, _ -> },
                    backupState = BackupSetupState(busy = false),
                    onCreateBackup = {},
                    onExportBackup = {},
                    onImportBackup = {},
                    settingsOnly = true,
                    onRunOnboardingAgain = { reopenedTemplatePicker = true },
                )
            }
        }

        scrollToText("Run onboarding again")
        scrollToText("Show templates")
        composeTestRule.onNodeWithText("Show templates").performClick()
        assertTrue("Settings must be able to reopen the template picker", reopenedTemplatePicker)
    }

    @Test
    fun setupSurfacesAnInstalledTemplatesGrantWithoutHidingTheEngineRows() {
        composeTestRule.setContent {
            TestTheme {
                PermissionOnboardingScreen(
                    contentPadding = PaddingValues(0.dp),
                    onMessage = {},
                    onUndoableMessage = { _, _ -> },
                    backupState = BackupSetupState(busy = false),
                    onCreateBackup = {},
                    onExportBackup = {},
                    onImportBackup = {},
                    focusRequirements = setOf(SetupRequirement.WRITE_SETTINGS),
                )
            }
        }

        composeTestRule.onNodeWithTag(SETUP_FOCUS_BANNER_TAG).assertIsDisplayed()

        // The row the template is waiting on has to be reachable. An installed template is
        // disabled until its first enable, so this row is not in the resolved requirements of the
        // workspace and only appears because the focus adds it.
        composeTestRule.onAllNodes(hasScrollAction()).onFirst()
            .performScrollToNode(hasText("Modify system settings"))
        composeTestRule.onNodeWithText("Modify system settings").assertIsDisplayed()

        // Focus must not take the engine's own rows away. Battery optimization carries no
        // requirement of its own, so a filter that replaced the normal rule would drop it.
        composeTestRule.onAllNodes(hasScrollAction()).onFirst()
            .performScrollToNode(hasText("Battery optimization"))
        composeTestRule.onNodeWithText("Battery optimization").assertIsDisplayed()
    }

    @Test
    fun setupWithoutAFocusDoesNotShowTheTemplateBanner() {
        composeTestRule.setContent {
            TestTheme {
                PermissionOnboardingScreen(
                    contentPadding = PaddingValues(0.dp),
                    onMessage = {},
                    onUndoableMessage = { _, _ -> },
                    backupState = BackupSetupState(busy = false),
                    onCreateBackup = {},
                    onExportBackup = {},
                    onImportBackup = {},
                )
            }
        }

        composeTestRule.onNodeWithTag(SETUP_FOCUS_BANNER_TAG).assertDoesNotExist()
        // Without a focus the disabled-template row is not listed, which is what makes the
        // previous test's assertion meaningful rather than a row that was always there.
        composeTestRule.onAllNodesWithText("Modify system settings").assertCountEquals(0)
    }

    @Test
    fun settingsShowsThemeBackupAndLocaleEntryPoints() {
        composeTestRule.setContent {
            TestTheme {
                PermissionOnboardingScreen(
                    contentPadding = PaddingValues(0.dp),
                    onMessage = {},
                    onUndoableMessage = { _, _ -> },
                    backupState = BackupSetupState(
                        busy = false,
                        latestBackupName = null,
                        pendingRestore = false,
                    ),
                    onCreateBackup = {},
                    onExportBackup = {},
                    onImportBackup = {},
                    settingsOnly = true,
                )
            }
        }
        composeTestRule.performAccessibilityChecks()

        // settingsOnly drops the checklist summary card; the screen title itself belongs to the
        // app shell, not to this composable.
        composeTestRule.onNodeWithText("OpenTasker can run with missing access", substring = true)
            .assertDoesNotExist()
        composeTestRule.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText("Theme"))
        composeTestRule.onNodeWithText("Theme").assertIsDisplayed()
        // The card's second line is whatever mode this device has persisted, so assert the picker
        // offers the modes instead of pinning the test to device preference state.
        composeTestRule.onNodeWithContentDescription("Theme").performClick()
        // Only the menu entries are clickable; the card's own subtitle repeats whichever label
        // this device has persisted.
        composeTestRule.onNode(hasText("System") and hasClickAction()).assertIsDisplayed()
        composeTestRule.onNode(hasText("AMOLED black") and hasClickAction()).assertIsDisplayed()
        Espresso.pressBack()
        composeTestRule.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText("Backup and restore"))
        composeTestRule.onNodeWithText("Backup and restore").assertIsDisplayed()
        composeTestRule.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText("Locale execution grants"))
        composeTestRule.onNodeWithText("Locale execution grants").assertIsDisplayed()
        composeTestRule.onAllNodes(hasScrollAction()).onFirst()
            .performScrollToNode(hasText("No Locale execution grants are issued."))
        composeTestRule.onNodeWithText("No Locale execution grants are issued.").assertIsDisplayed()
    }

    @Test
    fun taskEditorRequiresValidTaskName() {
        var savedName: String? = null
        composeTestRule.setContent {
            TestTheme {
                TaskEditorDialog(
                    task = null,
                    onDismiss = {},
                    onSave = { name, _, mode -> savedName = "$name:$mode" },
                )
            }
        }
        composeTestRule.performAccessibilityChecks()

        composeTestRule.onNodeWithText("Create Task").assertIsDisplayed()
        composeTestRule.onNodeWithText("Save").assertIsNotEnabled()
        composeTestRule.onAllNodes(hasSetTextAction())[0].performTextInput("Morning focus")
        composeTestRule.onNodeWithText("Save").assertIsEnabled().performClick()

        assertEquals("Morning focus:${CollisionMode.ABORT_NEW}", savedName)
    }

    @Test
    fun taskEditorRestoresDraftAcrossSavedInstanceState() {
        val restorationTester = StateRestorationTester(composeTestRule)
        restorationTester.setContent {
            TestTheme {
                TaskEditorDialog(
                    task = null,
                    onDismiss = {},
                    onSave = { _, _, _ -> },
                )
            }
        }
        composeTestRule.performAccessibilityChecks()

        composeTestRule.onAllNodes(hasSetTextAction())[0].performTextInput("Remembered task")
        restorationTester.emulateSavedInstanceStateRestore()

        composeTestRule.onNodeWithText("Remembered task").assertIsDisplayed()
    }

    @Test
    fun profileEditorRequiresNameAndTaskSelection() {
        val task = Task(id = 42, name = "Morning focus")
        var savedName: String? = null
        composeTestRule.setContent {
            TestTheme {
                ProfileEditorDialog(
                    profile = null,
                    tasks = listOf(task),
                    onDismiss = {},
                    onSave = { name, _, enterTaskId, _, _, _, _, _, _, _, _, _, _, _, _ ->
                        savedName = "$name:$enterTaskId"
                    },
                )
            }
        }
        composeTestRule.performAccessibilityChecks()

        composeTestRule.onNodeWithText("Create Profile").assertIsDisplayed()
        composeTestRule.onNodeWithText("Save").assertIsNotEnabled()
        composeTestRule.onAllNodes(hasSetTextAction())[0].performTextInput("At work")
        composeTestRule.onNodeWithText("Morning focus").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("Save").assertIsEnabled().performClick()

        assertEquals("At work:42", savedName)
    }

    @Test
    fun profileEditorCanSelectAndClearAnExitTask() {
        val enter = Task(id = 42, name = "Enter")
        val cleanup = Task(id = 43, name = "Cleanup")
        var savedExitTaskId: Long? = null
        composeTestRule.setContent {
            TestTheme {
                ProfileEditorDialog(
                    profile = Profile(id = 7, name = "Work", enterTaskId = enter.id),
                    tasks = listOf(enter, cleanup),
                    onDismiss = {},
                    onSave = { _, _, _, exitTaskId, _, _, _, _, _, _, _, _, _, _, _ -> savedExitTaskId = exitTaskId },
                )
            }
        }
        composeTestRule.performAccessibilityChecks()

        composeTestRule.onNode(hasText("Exit task") and hasClickAction()).performScrollTo().performClick()
        composeTestRule.onNode(hasText("Cleanup (43)") and hasClickAction()).performClick()
        composeTestRule.onNodeWithText("Save").performClick()

        assertEquals(43L, savedExitTaskId)

        composeTestRule.onNode(hasText("Exit task") and hasClickAction()).performScrollTo().performClick()
        composeTestRule.onAllNodes(hasText("None") and hasClickAction()).onLast().performClick()
        composeTestRule.onNodeWithText("Save").performClick()
        assertNull(savedExitTaskId)
    }

    @Test
    fun aProfileWhoseTaskLivesInAnotherProjectStillShowsItsCard() {
        // A-329: under the "Home" scope the profile was there but its task lived in "Default", and
        // gating the first-run screen on tasks alone replaced the profile with "Build your first
        // automation". The card must also name the task rather than call it missing.
        val otherProjectTask = Task(id = 3, name = "Lights", projectId = 1)
        val profile = Profile(id = 4, name = "Evening lights", enterTaskId = otherProjectTask.id, projectId = 2)
        composeTestRule.setContent {
            TestTheme {
                ProfilesScreen(
                    profiles = listOf(profile),
                    tasks = emptyList(),
                    allTasks = listOf(otherProjectTask),
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

        composeTestRule.onNodeWithText("Evening lights").assertIsDisplayed()
        composeTestRule.onNodeWithText("Runs: Lights").assertIsDisplayed()
        composeTestRule.onAllNodesWithText("Build your first automation").assertCountEquals(0)
    }

    @Test
    fun contextEditorMarksTheFieldThatBlocksSave() {
        // A-328: 25:00 greyed out Save while every field looked fine.
        composeTestRule.setContent {
            TestTheme {
                ContextConfigDialog(
                    state = ContextEditState(
                        profile = Profile(id = 3, name = "Profile", enterTaskId = 7),
                        type = ContextType.TIME,
                        index = 0,
                        existing = ContextSpec(ContextType.TIME, mapOf("start" to "25:00", "end" to "09:00")),
                    ),
                    onDismiss = {},
                    onSave = {},
                )
            }
        }

        scrollToText("Use a 24-hour time such as 07:30.")
        composeTestRule.onNodeWithText("Use a 24-hour time such as 07:30.").assertIsDisplayed()
    }

    @Test
    fun taskListExposesAtomicActionReorderControls() {
        var moved: Pair<Int, Int>? = null
        val task = Task(
            id = 7,
            name = "Ordered",
            actions = listOf(
                ActionSpec(type = "test.first", label = "First"),
                ActionSpec(type = "test.second", label = "Second"),
            ),
        )
        composeTestRule.setContent {
            TestTheme {
                TasksScreen(
                    tasks = listOf(task),
                    storageDecodeIssues = emptyList(),
                    onCreateTask = {},
                    onEditTask = {},
                    onDeleteTask = {},
                    onRunTask = {},
                    onPreflightTask = {},
                    onPinTask = {},
                    onAddAction = {},
                    onEditAction = { _, _, _ -> },
                    onDeleteAction = { _, _ -> },
                    onRunAction = { _, _ -> },
                    onMoveAction = { _, fromIndex, toIndex -> moved = fromIndex to toIndex },
                    contentPadding = PaddingValues(0.dp),
                )
            }
        }
        composeTestRule.performAccessibilityChecks()

        // Reorder now lives behind each action row's overflow menu (one "More" button per action),
        // so open the second action's menu before asserting the labelled control.
        composeTestRule.onAllNodesWithContentDescription("More").onLast().performScrollTo().performClick()
        composeTestRule.onNodeWithContentDescription("Move action 2, Second, up").performClick()

        assertEquals(1 to 0, moved)
    }

    @Test
    fun actionEditorBlocksMissingRequiredFields() {
        var actionSaved = false
        val metadata = ActionMetadata(
            id = "test.required",
            nameRes = R.string.catalog_action_notify_show_name,
            descriptionRes = R.string.catalog_action_notify_show_description,
            categoryRes = R.string.catalog_category_notification,
            fields = listOf(ActionField("message", R.string.catalog_action_notify_show_field_text_label, required = true)),
        )
        composeTestRule.setContent {
            TestTheme {
                ActionConfigDialog(
                    state = ActionEditState(
                        task = Task(id = 7, name = "Task"),
                        metadata = metadata,
                    ),
                    onDismiss = {},
                    onSave = { actionSaved = true },
                )
            }
        }
        composeTestRule.performAccessibilityChecks()

        // "Show Notification" is the dialog title and appears again in the action summary.
        composeTestRule.onAllNodesWithText("Show Notification").onFirst().assertIsDisplayed()
        composeTestRule.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText("Required"))
        composeTestRule.onNodeWithText("Required").assertIsDisplayed()
        composeTestRule.onNodeWithText("Save").assertIsNotEnabled()
        assertTrue(!actionSaved)
    }

    @Test
    fun actionEditorRoundTripsConditionAndContinueOnError() {
        var saved: ActionSpec? = null
        val metadata = ActionMetadata(
            id = "flow.wait",
            nameRes = R.string.catalog_action_flow_wait_name,
            descriptionRes = R.string.catalog_action_flow_wait_description,
            categoryRes = R.string.catalog_category_flow,
            fields = listOf(
                ActionField("millis", R.string.catalog_action_flow_wait_field_millis_label, required = true),
            ),
        )
        composeTestRule.setContent {
            TestTheme {
                ActionConfigDialog(
                    state = ActionEditState(
                        task = Task(id = 7, name = "Task"),
                        metadata = metadata,
                        existing = ActionSpec(
                            id = 9,
                            type = "flow.wait",
                            args = mapOf("millis" to "1"),
                            condition = "%old == true",
                        ),
                    ),
                    onDismiss = {},
                    onSave = { saved = it },
                )
            }
        }
        composeTestRule.performAccessibilityChecks()

        composeTestRule.onAllNodes(hasSetTextAction())[1].performTextClearance()
        composeTestRule.onAllNodes(hasSetTextAction())[1].performTextInput("%armed == true")
        composeTestRule.onNodeWithTag(ACTION_CONTINUE_ON_ERROR_TAG).performScrollTo().performClick()
        composeTestRule.onNodeWithText("Save").performClick()

        assertEquals("%armed == true", saved?.condition)
        assertTrue(saved?.continueOnError == true)
        assertEquals(mapOf("millis" to "1"), saved?.args)
    }

    @Test
    fun actionEditorDropdownStoresTheStableOptionValue() {
        var saved: ActionSpec? = null
        val metadata = ActionMetadata(
            id = "flow.wait",
            nameRes = R.string.catalog_action_wifi_toggle_name,
            descriptionRes = R.string.catalog_action_wifi_toggle_description,
            categoryRes = R.string.catalog_category_settings,
            fields = listOf(
                ActionField(
                    key = "state",
                    labelRes = R.string.catalog_action_wifi_toggle_field_state_label,
                    fieldType = FieldType.DROPDOWN,
                    required = true,
                    options = listOf(
                        ActionFieldOption("enabled_wire_value", R.string.label_on),
                        ActionFieldOption("disabled_wire_value", R.string.label_off),
                    ),
                ),
            ),
        )
        composeTestRule.setContent {
            TestTheme {
                ActionConfigDialog(
                    state = ActionEditState(Task(id = 7, name = "Task"), metadata),
                    onDismiss = {},
                    onSave = { saved = it },
                )
            }
        }
        composeTestRule.performAccessibilityChecks()

        composeTestRule.onNodeWithText("Save").assertIsNotEnabled()
        composeTestRule.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText("State *"))
        composeTestRule.onNode(hasText("State *") and hasClickAction()).performClick()
        composeTestRule.onNodeWithText("On").performClick()
        composeTestRule.onNodeWithText("Save").assertIsEnabled().performClick()

        assertEquals("enabled_wire_value", saved?.args?.get("state"))
    }

    @Test
    fun actionEditorBlocksInvalidNumberSyntax() {
        val metadata = ActionMetadata(
            id = "flow.wait",
            nameRes = R.string.catalog_action_flow_wait_name,
            descriptionRes = R.string.catalog_action_flow_wait_description,
            categoryRes = R.string.catalog_category_flow,
            fields = listOf(
                ActionField(
                    key = "millis",
                    labelRes = R.string.catalog_action_flow_wait_field_millis_label,
                    fieldType = FieldType.NUMBER,
                    required = true,
                    numberRule = ActionNumberRule(minimum = 0.0, maximum = 10.0),
                ),
            ),
        )
        composeTestRule.setContent {
            TestTheme {
                ActionConfigDialog(
                    state = ActionEditState(Task(id = 7, name = "Task"), metadata),
                    onDismiss = {},
                    onSave = {},
                )
            }
        }
        composeTestRule.performAccessibilityChecks()

        composeTestRule.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText("Milliseconds *"))
        composeTestRule.onNode(hasText("Milliseconds *") and hasSetTextAction()).performTextInput("1e3")
        composeTestRule.onNodeWithText("Enter a valid number.").assertIsDisplayed()
        composeTestRule.onNodeWithText("Save").assertIsNotEnabled()
    }

    @Test
    fun actionEditorPreservesArgumentsUnknownToItsMetadata() {
        var saved: ActionSpec? = null
        val metadata = ActionMetadata(
            id = "log",
            nameRes = R.string.catalog_action_log_name,
            descriptionRes = R.string.catalog_action_log_description,
            categoryRes = R.string.catalog_category_system,
            fields = listOf(ActionField("message", R.string.catalog_action_log_field_message_label, required = true)),
        )
        val opaque = "  newer-format\u0000value\r\n  "
        composeTestRule.setContent {
            TestTheme {
                ActionConfigDialog(
                    state = ActionEditState(
                        task = Task(id = 7, name = "Task"),
                        metadata = metadata,
                        existing = ActionSpec(
                            id = 9,
                            type = metadata.id,
                            args = linkedMapOf("future.argument" to opaque, "message" to "hello"),
                        ),
                    ),
                    onDismiss = {},
                    onSave = { saved = it },
                )
            }
        }
        composeTestRule.performAccessibilityChecks()

        composeTestRule.onNodeWithText("Save").assertIsEnabled().performClick()

        assertEquals(opaque, saved?.args?.get("future.argument"))
        assertEquals("hello", saved?.args?.get("message"))
    }

    @Test
    fun contextEditorBlocksMissingRequiredFields() {
        var contextSaved = false
        composeTestRule.setContent {
            TestTheme {
                ContextConfigDialog(
                    state = ContextEditState(
                        profile = Profile(id = 3, name = "Profile", enterTaskId = 7),
                        type = ContextType.APPLICATION,
                    ),
                    onDismiss = {},
                    onSave = { contextSaved = true },
                )
            }
        }
        composeTestRule.performAccessibilityChecks()

        composeTestRule.onNodeWithText("Application").assertIsDisplayed()
        composeTestRule.onNodeWithText("Invert match").assertIsDisplayed()
        composeTestRule.onNodeWithText("Save").assertIsNotEnabled()
        assertTrue(!contextSaved)
    }

    @Test
    fun sceneCreationDialogValidatesNameBeforeSave() {
        var createdScene: String? = null
        composeTestRule.setContent {
            TestTheme {
                SceneLibraryScreen(
                    scenes = emptyList(),
                    tasks = emptyList(),
                    onCreateScene = { name, width, height, onSaved ->
                        createdScene = "$name:$width:$height"
                        onSaved()
                    },
                    onUpdateScene = { _, _, _ -> },
                    onRemoveElement = { _, _ -> },
                    onDeleteScene = {},
                    contentPadding = PaddingValues(0.dp),
                )
            }
        }
        composeTestRule.performAccessibilityChecks()

        composeTestRule.onNodeWithText("No scenes yet").assertIsDisplayed()
        composeTestRule.onNodeWithText("Create Scene").performClick()
        composeTestRule.onNodeWithText("Create").assertIsNotEnabled()
        composeTestRule.onAllNodes(hasSetTextAction())[0].performTextInput("HUD")
        composeTestRule.onNodeWithText("Create").assertIsEnabled().performClick()

        assertEquals("HUD:320:240", createdScene)
    }

    @Test
    fun incompatibleBundleReviewKeepsImportDisabled() {
        composeTestRule.setContent {
            TestTheme {
                OpenTaskerBundleReviewDialog(
                    state = OpenTaskerBundleReviewState(
                        bundle = OpenTaskerBundle(
                            schemaVersion = 999,
                            appVersion = "0.0.0",
                            exportedAtEpochMs = 0,
                        ),
                        plan = BundleImportPlan(
                            canImport = false,
                            warnings = listOf("Unsupported schema version 999."),
                        ),
                    ),
                    busy = false,
                    onDismiss = {},
                    onConfirm = {},
                )
            }
        }
        composeTestRule.performAccessibilityChecks()

        composeTestRule.onNodeWithText("Review OpenTasker bundle").assertIsDisplayed()
        scrollToText("Cannot import")
        composeTestRule.onNodeWithText("Cannot import").assertIsDisplayed()
        composeTestRule.onNodeWithText("Import Disabled").assertIsNotEnabled()
    }

    @Test
    fun bundleVariableConflictRequiresAnExplicitChoice() {
        val review = mutableStateOf(
            OpenTaskerBundleReviewState(
                bundle = OpenTaskerBundle(
                    appVersion = "0.2.79",
                    exportedAtEpochMs = 0,
                ),
                plan = BundleImportPlan(
                    canImport = true,
                    variableConflicts = listOf(
                        VariableImportConflict(
                            name = "API_TOKEN",
                            existingIsSecret = true,
                            suggestedRename = "API_TOKEN_imported",
                        ),
                    ),
                ),
            ),
        )
        composeTestRule.setContent {
            TestTheme {
                OpenTaskerBundleReviewDialog(
                    state = review.value,
                    busy = false,
                    onDismiss = {},
                    onVariableConflictResolution = { name, resolution ->
                        review.value = review.value.copy(
                            variableResolutions = review.value.variableResolutions + (name to resolution),
                        )
                    },
                    onConfirm = {},
                )
            }
        }
        composeTestRule.performAccessibilityChecks()

        composeTestRule.onNodeWithText("Import Disabled").assertIsNotEnabled()
        scrollToText("Keep existing")
        composeTestRule.onNodeWithText("Keep existing").performClick()
        scrollToText("Selected: Keep existing")
        composeTestRule.onNodeWithText("Selected: Keep existing").assertIsDisplayed()
        composeTestRule.onNodeWithText("Import for Review").assertIsEnabled()
        assertEquals(
            VariableConflictResolution(VariableConflictAction.PRESERVE_EXISTING),
            review.value.variableResolutions["API_TOKEN"],
        )
    }

    @Test
    fun installedAppPickerSearchesLabelsAndPackages() {
        var selected: InstalledApp? = null
        composeTestRule.setContent {
            TestTheme {
                InstalledAppPickerDialog(
                    appsOverride = listOf(
                        InstalledApp("com.android.chrome", "Chrome"),
                        InstalledApp("com.spotify.music", "Spotify"),
                    ),
                    onDismiss = {},
                    onSelect = { selected = it },
                )
            }
        }
        composeTestRule.performAccessibilityChecks()

        val search = composeTestRule.onNodeWithText("Search by app or package")
        search.performTextInput("spotify.music")
        // A short window at a large font leaves no room beside the keyboard, so the search key
        // has to put it away and bring the matches up. Nothing here scrolls for the user: the
        // match has to show on its own. The keyboard closes in its own process, hence the wait.
        search.performImeAction()
        search.assertIsNotFocused()
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule.onNodeWithText("Spotify").isDisplayed()
        }
        composeTestRule.onNodeWithText("Spotify").performClick()
        assertEquals(InstalledApp("com.spotify.music", "Spotify"), selected)
    }

    @Test
    fun installedAppPickerOffersLatestInspectorObservation() {
        var selected: InstalledApp? = null
        composeTestRule.setContent {
            TestTheme {
                InstalledAppPickerDialog(
                    suggestedPackage = "com.spotify.music",
                    appsOverride = listOf(InstalledApp("com.spotify.music", "Spotify")),
                    onDismiss = {},
                    onSelect = { selected = it },
                )
            }
        }
        composeTestRule.performAccessibilityChecks()

        scrollToText("Use latest observed: Spotify (com.spotify.music)")
        composeTestRule.onNodeWithText("Use latest observed: Spotify (com.spotify.music)").performClick()
        assertEquals(InstalledApp("com.spotify.music", "Spotify"), selected)
    }

    /**
     * A dialog's text slot clips what doesn't fit, so at a large font or in a short window the end of
     * the body is out of reach unless the body scrolls. A list inside the body doesn't count: it
     * leaves whatever sits above it stuck in place, which is why these look for the field too.
     */
    @Test
    fun dialogBodiesScrollToWhatDoesNotFit() {
        val aroundField = hasScrollAction() and hasAnyDescendant(hasSetTextAction())
        val preview = RunLogRetentionPreview(
            policy = RunLogRetentionPolicy(),
            storedCount = 10,
            prunableCount = 2,
            oldestTimestamp = null,
        )
        val dialogs = listOf(
            DialogCase("task editor", aroundField) {
                TaskEditorDialog(task = null, onDismiss = {}, onSave = { _, _, _ -> })
            },
            DialogCase("delete task still in use", hasScrollAction()) {
                val task = Task(id = 1, name = "Morning focus")
                val profile = Profile(id = 2, name = "Work hours", enterTaskId = task.id)
                val references = AutomationReferenceIndex.referencesTo(task, profiles = listOf(profile))
                TaskDeleteReferencesDialog(
                    preview = TaskDeletionPreview(task, references, requiresReassignment = references.any { it.isRequired }),
                    tasks = listOf(task, Task(id = 3, name = "Evening wind-down")),
                    onDismiss = {},
                    onConfirm = {},
                )
            },
            DialogCase("paste JSON", aroundField) {
                OpenTaskerBundleTextImportDialog(
                    text = "",
                    busy = false,
                    onTextChanged = {},
                    onDismiss = {},
                    onConfirm = {},
                )
            },
            DialogCase("MacroDroid password", aroundField) {
                MacroDroidPasswordDialog(
                    wrongPassword = false,
                    busy = false,
                    progress = null,
                    onSubmit = {},
                    onDismiss = {},
                    onCancel = {},
                )
            },
            DialogCase("create scene", aroundField) {
                SceneEditorDialog(onDismiss = {}, onSave = { _, _, _ -> })
            },
            DialogCase("global search", aroundField) {
                GlobalSearchDialog(
                    profiles = emptyList(),
                    tasks = emptyList(),
                    variables = emptyList(),
                    scenes = emptyList(),
                    onDismiss = {},
                    onSelect = {},
                )
            },
            DialogCase("action picker", aroundField) {
                ActionPickerDialog(onDismiss = {}, onSelect = {})
            },
            DialogCase("context picker", aroundField) {
                ContextTypePickerDialog(onDismiss = {}, onSelect = {})
            },
            DialogCase("app picker", aroundField) {
                InstalledAppPickerDialog(
                    suggestedPackage = null,
                    appsOverride = listOf(InstalledApp("com.spotify.music", "Spotify")),
                    onDismiss = {},
                    onSelect = {},
                )
            },
            DialogCase("run log retention", hasScrollAction()) {
                RunLogRetentionPreviewDialog(preview, onDismiss = {}, onExportJson = {}, onConfirm = {})
            },
        )
        val shown = mutableIntStateOf(0)
        composeTestRule.setContent {
            TestTheme { dialogs[shown.intValue].content() }
        }

        val stuck = dialogs.filterIndexed { index, dialog ->
            composeTestRule.runOnIdle { shown.intValue = index }
            composeTestRule.performAccessibilityChecks()
            composeTestRule.onAllNodes(dialog.scrollsWhenMatched).fetchSemanticsNodes().isEmpty()
        }
        assertEquals(emptyList<String>(), stuck.map { it.name })
    }

    @Test
    fun runLogTracesStayReadableWhenExpanded() {
        val traces = List(6) { index ->
            ActionExecutionTrace(
                index = index,
                actionType = "notify.show",
                label = "Show the evening summary notification, step $index",
                durationMs = 120L * index,
                status = if (index == 2) ActionTraceStatus.FAILURE else ActionTraceStatus.SUCCESS,
                message = if (index == 2) "Notification permission is off" else "Completed",
                expandedArgSummary = "title=Evening, text=Lights on at sunset",
            )
        }
        val entry = RunLogEntry(
            id = 1,
            taskId = 7,
            taskName = "Evening routine",
            durationMs = 900,
            success = false,
            message = runLogMessage(source = "Manual", traces = traces),
        )
        composeTestRule.setContent {
            TestTheme {
                RunLogScreenContent(
                    logs = listOf(entry),
                    tasks = listOf(Task(id = 7, name = "Evening routine")),
                    retentionPolicy = RunLogRetentionPolicy(),
                    onRetentionPolicyChange = {},
                    onShareDiagnostic = {},
                    contentPadding = PaddingValues(0.dp),
                )
            }
        }
        composeTestRule.performAccessibilityChecks()

        val list = composeTestRule.onNodeWithTag(RUN_LOG_LIST_TAG)
        list.performScrollToNode(hasText("Show all 6 actions"))
        composeTestRule.performAccessibilityChecks()
        composeTestRule.onNodeWithText("Show all 6 actions").performClick()
        list.performScrollToNode(hasText("Show fewer (6 total)"))
        composeTestRule.performAccessibilityChecks()
        composeTestRule.onNodeWithText("Show fewer (6 total)").assertIsDisplayed()
        composeTestRule.onNodeWithText("6. Show the evening summary notification, step 5").assertExists()
    }

    @Test
    fun flowGraphStaysUsableWithContextsAndSteps() {
        val task = Task(
            id = 7,
            name = "Evening routine",
            actions = listOf(
                ActionSpec(type = "notify.show", label = "Show the evening summary"),
                ActionSpec(type = "task.run", label = "Run the lights task", args = mapOf("task_id" to "8")),
                ActionSpec(type = "flow.wait", label = "Wait a minute", condition = "{{ event.x }} == 1"),
            ),
        )
        val profile = Profile(
            id = 3,
            name = "Evening lights",
            contexts = listOf(ContextSpec(ContextType.TIME, mapOf("start" to "18:00", "end" to "22:00"))),
            enterTaskId = task.id,
            exitTaskId = 99,
        )
        composeTestRule.setContent {
            TestTheme {
                AutomationFlowScreen(
                    profiles = listOf(profile),
                    tasks = listOf(task, Task(id = 8, name = "Lights")),
                    contentPadding = PaddingValues(0.dp),
                )
            }
        }
        composeTestRule.performAccessibilityChecks()

        scrollToText("Add Context")
        composeTestRule.performAccessibilityChecks()
        scrollToText("Add Step")
        composeTestRule.performAccessibilityChecks()
        composeTestRule.onNodeWithText("Add Step").assertIsDisplayed()
    }

    /** A check mark sat beside "1 issue(s)", and the icon read "Flow" to TalkBack. */
    @Test
    fun flowOverviewIconFollowsTheIssueCount() {
        val task = Task(id = 7, name = "Lights", actions = listOf(ActionSpec(type = "notify.show", label = "Notify")))
        var profiles by mutableStateOf(
            listOf(
                Profile(
                    id = 3,
                    name = "Evening lights",
                    contexts = listOf(ContextSpec(ContextType.TIME, mapOf("start" to "18:00", "end" to "22:00"))),
                    enterTaskId = task.id,
                    exitTaskId = 99,
                ),
            ),
        )
        composeTestRule.setContent {
            TestTheme {
                AutomationFlowScreen(profiles = profiles, tasks = listOf(task), contentPadding = PaddingValues(0.dp))
            }
        }

        composeTestRule.onNodeWithText("1 issue(s)").assertIsDisplayed()
        composeTestRule.onNodeWithTag(FLOW_OVERVIEW_ISSUES_ICON_TAG, useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithTag(FLOW_OVERVIEW_READY_ICON_TAG, useUnmergedTree = true).assertDoesNotExist()
        composeTestRule.onAllNodesWithContentDescription("Flow", useUnmergedTree = true).assertCountEquals(0)

        profiles = profiles.map { it.copy(exitTaskId = null) }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag(FLOW_OVERVIEW_READY_ICON_TAG, useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithTag(FLOW_OVERVIEW_ISSUES_ICON_TAG, useUnmergedTree = true).assertDoesNotExist()
    }

    /** The create button floats over the list, so only the list's end has to clear it. */
    @Test
    fun sceneListEndClearsTheCreateButton() {
        composeTestRule.setContent {
            TestTheme {
                SceneLibraryScreen(
                    scenes = listOf(sceneWithEveryElementType),
                    tasks = listOf(Task(id = 7, name = "Lights")),
                    onCreateScene = { _, _, _, _ -> },
                    onUpdateScene = { _, _, _ -> },
                    onRemoveElement = { _, _ -> },
                    onDeleteScene = {},
                    contentPadding = PaddingValues(0.dp),
                )
            }
        }
        composeTestRule.performAccessibilityChecks()

        scrollToText("Dashboard")
        composeTestRule.onNodeWithText("Dashboard").performClick()
        val list = composeTestRule.onAllNodes(hasScrollAction()).onFirst()
        list.performSemanticsAction(SemanticsActions.ScrollBy) { scroll -> scroll(0f, 100_000f) }
        composeTestRule.waitForIdle()
        composeTestRule.performAccessibilityChecks()

        // In a short window at a large font the scene's notes after the last element are taller
        // than the screen, so measure the list's real last line rather than a fixed control.
        // Items that scrolled away stay composed but unplaced, with stale bounds, so skip them.
        val contentBottom = generateSequence(list.fetchSemanticsNode().children) { level ->
            level.flatMap { it.children }.ifEmpty { null }
        }.flatten().filter { it.layoutInfo.isPlaced }.maxOf { it.boundsInRoot.bottom }
        // The button's label is only in the unmerged tree, so find the clickable node around it.
        val createTop = composeTestRule
            .onNode(hasClickAction() and hasAnyDescendant(hasText("Create Scene")), useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot.top
        assertTrue(
            "The list's last line ($contentBottom px) must end above the create button ($createTop px)",
            contentBottom <= createTop,
        )
        composeTestRule.onAllNodesWithContentDescription("Move element right 1 dp").onLast()
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun sceneCardStaysUsableWithEveryElementType() {
        composeTestRule.setContent {
            TestTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    SceneCard(
                        scene = sceneWithEveryElementType,
                        tasks = listOf(Task(id = 7, name = "Lights")),
                        onAddElement = {},
                        onEditElement = { _, _ -> },
                        onDeleteElement = { _, _ -> },
                        onUpdateScene = { _, _ -> },
                        canUndo = true,
                        canRedo = false,
                        onUndo = {},
                        onRedo = {},
                        onDelete = {},
                        onDuplicate = {},
                    )
                }
            }
        }
        composeTestRule.performAccessibilityChecks()

        composeTestRule.onNodeWithText("Dashboard").performClick()
        composeTestRule.performAccessibilityChecks()
        scrollToText("Add Element")
        composeTestRule.performAccessibilityChecks()
        composeTestRule.onAllNodesWithContentDescription("Move element right 1 dp").onLast().performScrollTo()
        composeTestRule.performAccessibilityChecks()
    }

    @Test
    fun sceneElementEditorScrollsToWhatDoesNotFit() {
        composeTestRule.setContent {
            TestTheme {
                SceneElementEditorDialog(
                    state = SceneElementEditorState(scene = sceneWithEveryElementType),
                    tasks = listOf(Task(id = 7, name = "Lights")),
                    onDismiss = {},
                    onSave = {},
                )
            }
        }
        composeTestRule.performAccessibilityChecks()

        val editorBody = hasScrollAction() and hasAnyDescendant(hasSetTextAction())
        assertTrue(
            "The element editor must scroll to what doesn't fit",
            composeTestRule.onAllNodes(editorBody).fetchSemanticsNodes().isNotEmpty(),
        )
    }

    private val sceneWithEveryElementType = Scene(
        id = 1,
        name = "Dashboard",
        widthDp = 320,
        heightDp = 240,
        elements = listOf(
            SceneElement(id = 1, type = SceneElementType.BUTTON, xDp = 16, yDp = 16, widthDp = 120, heightDp = 48, tapTaskId = 7),
            SceneElement(id = 2, type = SceneElementType.TEXT, xDp = 16, yDp = 72, widthDp = 200, heightDp = 32),
            SceneElement(id = 3, type = SceneElementType.SLIDER, xDp = 16, yDp = 112, widthDp = 200, heightDp = 48),
            SceneElement(id = 4, type = SceneElementType.IMAGE, xDp = 16, yDp = 168, widthDp = 64, heightDp = 64),
        ),
    )

    private class DialogCase(
        val name: String,
        val scrollsWhenMatched: SemanticsMatcher,
        val content: @Composable () -> Unit,
    )

    @Composable
    private fun TestTheme(content: @Composable () -> Unit) {
        OpenTaskerTheme(content = content)
    }
}
