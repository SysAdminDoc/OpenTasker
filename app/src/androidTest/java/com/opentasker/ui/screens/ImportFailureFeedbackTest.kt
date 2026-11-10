package com.opentasker.ui.screens

import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.opentasker.app.R
import com.opentasker.ui.theme.OpenTaskerTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** An import failure's reason stays readable without holding anything else back (A-383). */
class ImportFailureFeedbackTest {
    @get:Rule
    val composeTestRule = createAccessibilityComposeRule()

    private fun string(resId: Int): String =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(resId)

    @Test
    fun aFailureReasonStaysUpUntilTheNextMessageTakesItsPlace() {
        val messages = Channel<UiMessage>(Channel.UNLIMITED)
        val host = SnackbarHostState()
        composeTestRule.setContent {
            val context = LocalContext.current
            LaunchedEffect(Unit) { host.showUiMessages(messages.receiveAsFlow(), context) {} }
            OpenTaskerTheme { SnackbarHost(host) }
        }

        messages.trySend(UiMessage(R.string.ui_error_import_damaged, stayUntilDismissed = true))
        composeTestRule.onNodeWithText(string(R.string.ui_error_import_damaged)).assertIsDisplayed()
        // Past the 10 seconds a long snackbar gets.
        composeTestRule.mainClock.advanceTimeBy(15_000)
        composeTestRule.onNodeWithText(string(R.string.ui_error_import_damaged)).assertIsDisplayed()

        messages.trySend(UiMessage(R.string.ui_message_tasker_xml_ready))
        composeTestRule.onNodeWithText(string(R.string.ui_message_tasker_xml_ready)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.ui_error_import_damaged)).assertDoesNotExist()
    }

    @Test
    fun aFailureReasonWithAMessageRightBehindItIsStillShownFirst() {
        val messages = Channel<UiMessage>(Channel.UNLIMITED)
        val host = SnackbarHostState()
        // Both queued before the collector starts, as when two import lanes fail back to back.
        messages.trySend(UiMessage(R.string.ui_error_import_damaged, stayUntilDismissed = true))
        messages.trySend(UiMessage(R.string.ui_message_tasker_xml_ready))
        composeTestRule.setContent {
            val context = LocalContext.current
            LaunchedEffect(Unit) { host.showUiMessages(messages.receiveAsFlow(), context) {} }
            OpenTaskerTheme { SnackbarHost(host) }
        }

        composeTestRule.onNodeWithText(string(R.string.ui_error_import_damaged)).assertIsDisplayed()

        composeTestRule.mainClock.advanceTimeBy(STICKY_MESSAGE_MINIMUM_MS + 1_000)
        composeTestRule.onNodeWithText(string(R.string.ui_message_tasker_xml_ready)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.ui_error_import_damaged)).assertDoesNotExist()
    }

    @Test
    fun aDeletedTasksUndoShowsAtOnceOverAReason() {
        val messages = Channel<UiMessage>(Channel.UNLIMITED)
        val host = SnackbarHostState()
        lateinit var scope: CoroutineScope
        composeTestRule.setContent {
            val context = LocalContext.current
            scope = rememberCoroutineScope()
            LaunchedEffect(Unit) { host.showUiMessages(messages.receiveAsFlow(), context) {} }
            OpenTaskerTheme { SnackbarHost(host) }
        }
        val reason = string(R.string.ui_error_import_damaged)
        val undo = string(R.string.action_undo)
        var undone = false

        messages.trySend(UiMessage(R.string.ui_error_import_damaged, stayUntilDismissed = true))
        composeTestRule.onNodeWithText(reason).assertIsDisplayed()
        composeTestRule.runOnIdle { undoableMessages(scope, host, undo)("Deleted Morning routine") { undone = true } }
        composeTestRule.onNodeWithText("Deleted Morning routine").assertIsDisplayed()
        composeTestRule.onNodeWithText(reason).assertDoesNotExist()

        composeTestRule.onNodeWithText(undo).performClick()
        composeTestRule.runOnIdle { assertTrue(undone) }
        // The queue isn't left waiting on the reason that went away.
        messages.trySend(UiMessage(R.string.ui_message_tasker_xml_ready))
        composeTestRule.onNodeWithText(string(R.string.ui_message_tasker_xml_ready)).assertIsDisplayed()
    }

    @Test
    fun aDeletedTasksUndoFromTheViewModelSkipsTheReasonsMinimum() {
        val messages = Channel<UiMessage>(Channel.UNLIMITED)
        val host = SnackbarHostState()
        var undoneWith: UiMessageAction.Undo? = null
        composeTestRule.setContent {
            val context = LocalContext.current
            LaunchedEffect(Unit) { host.showUiMessages(messages.receiveAsFlow(), context) { undoneWith = it } }
            OpenTaskerTheme { SnackbarHost(host) }
        }
        val reason = string(R.string.ui_error_import_damaged)
        val deleted = string(R.string.ui_message_task_deleted)
        val undoAction = UiMessageAction.Undo("task", 7L)

        messages.trySend(UiMessage(R.string.ui_error_import_damaged, stayUntilDismissed = true))
        composeTestRule.onNodeWithText(reason).assertIsDisplayed()
        // Task delete sends its Undo through the view model's queue, the same one as the reason.
        // The clock only moves when told, so one second passes: well inside the reason's minimum.
        composeTestRule.mainClock.autoAdvance = false
        messages.trySend(UiMessage(R.string.ui_message_task_deleted, action = undoAction))
        composeTestRule.mainClock.advanceTimeBy(1_000)
        composeTestRule.onNodeWithText(deleted).assertIsDisplayed()
        composeTestRule.onNodeWithText(reason).assertDoesNotExist()

        composeTestRule.mainClock.autoAdvance = true
        composeTestRule.onNodeWithText(string(R.string.action_undo)).performClick()
        composeTestRule.runOnIdle { assertTrue(undoneWith == undoAction) }
    }

    @Test
    fun aReasonAnUndoJumpsBeforeItWasSeenComesBackAfterTheUndo() {
        val messages = Channel<UiMessage>(Channel.UNLIMITED)
        val host = SnackbarHostState()
        lateinit var scope: CoroutineScope
        var undoneWith: UiMessageAction.Undo? = null
        composeTestRule.setContent {
            val context = LocalContext.current
            scope = rememberCoroutineScope()
            LaunchedEffect(Unit) { host.showUiMessages(messages.receiveAsFlow(), context) { undoneWith = it } }
            OpenTaskerTheme { SnackbarHost(host) }
        }
        val reason = string(R.string.ui_error_import_damaged)
        val direct = string(R.string.ui_flow_target_missing)
        val deleted = string(R.string.ui_message_task_deleted)
        val undoAction = UiMessageAction.Undo("task", 7L)

        composeTestRule.runOnIdle { scope.launch { host.showSnackbarOverReason(direct) } }
        composeTestRule.onNodeWithText(direct).assertIsDisplayed()
        // The reason waits behind the direct message, and a task delete comes in before it's seen.
        messages.trySend(UiMessage(R.string.ui_error_import_damaged, stayUntilDismissed = true))
        messages.trySend(UiMessage(R.string.ui_message_task_deleted, action = undoAction))

        composeTestRule.mainClock.advanceTimeBy(4_500)
        composeTestRule.onNodeWithText(deleted).assertIsDisplayed()
        composeTestRule.onNodeWithText(reason).assertDoesNotExist()

        composeTestRule.onNodeWithText(string(R.string.action_undo)).performClick()
        composeTestRule.runOnIdle { assertTrue(undoneWith == undoAction) }
        composeTestRule.onNodeWithText(reason).assertIsDisplayed()
    }

    @Test
    fun aReasonTheScreensOwnUndoJumpsBeforeItWasSeenComesBackAfterTheUndo() {
        val messages = Channel<UiMessage>(Channel.UNLIMITED)
        val host = SnackbarHostState()
        lateinit var scope: CoroutineScope
        composeTestRule.setContent {
            val context = LocalContext.current
            scope = rememberCoroutineScope()
            LaunchedEffect(Unit) { host.showUiMessages(messages.receiveAsFlow(), context) {} }
            OpenTaskerTheme { SnackbarHost(host) }
        }
        val reason = string(R.string.ui_error_import_damaged)
        val direct = string(R.string.ui_flow_target_missing)
        val undo = string(R.string.action_undo)
        var undone = false

        composeTestRule.runOnIdle { scope.launch { host.showSnackbarOverReason(direct) } }
        composeTestRule.onNodeWithText(direct).assertIsDisplayed()
        // The reason gets in line behind the direct message first, then a list delete's Undo.
        messages.trySend(UiMessage(R.string.ui_error_import_damaged, stayUntilDismissed = true))
        composeTestRule.waitForIdle()
        composeTestRule.runOnIdle { undoableMessages(scope, host, undo)("Deleted Morning routine") { undone = true } }

        composeTestRule.mainClock.advanceTimeBy(4_500)
        composeTestRule.onNodeWithText("Deleted Morning routine").assertIsDisplayed()
        composeTestRule.onNodeWithText(reason).assertDoesNotExist()

        composeTestRule.onNodeWithText(undo).performClick()
        composeTestRule.runOnIdle { assertTrue(undone) }
        composeTestRule.onNodeWithText(reason).assertIsDisplayed()
    }

    @Test
    fun aReasonTheScreensOwnUndoJumpsStillComesBeforeTheMessageQueuedBehindIt() {
        val messages = Channel<UiMessage>(Channel.UNLIMITED)
        val host = SnackbarHostState()
        lateinit var scope: CoroutineScope
        composeTestRule.setContent {
            val context = LocalContext.current
            scope = rememberCoroutineScope()
            LaunchedEffect(Unit) { host.showUiMessages(messages.receiveAsFlow(), context) {} }
            OpenTaskerTheme { SnackbarHost(host) }
        }
        val reason = string(R.string.ui_error_import_damaged)
        val direct = string(R.string.ui_flow_target_missing)
        val next = string(R.string.ui_message_tasker_xml_ready)
        val undo = string(R.string.action_undo)
        var undone = false

        composeTestRule.runOnIdle { scope.launch { host.showSnackbarOverReason(direct) } }
        composeTestRule.onNodeWithText(direct).assertIsDisplayed()
        // The queue waits on the reason's time on screen while the reason waits behind the direct message.
        messages.trySend(UiMessage(R.string.ui_error_import_damaged, stayUntilDismissed = true))
        messages.trySend(UiMessage(R.string.ui_message_tasker_xml_ready))
        composeTestRule.waitForIdle()
        composeTestRule.runOnIdle { undoableMessages(scope, host, undo)("Deleted Morning routine") { undone = true } }

        composeTestRule.mainClock.advanceTimeBy(4_500)
        composeTestRule.onNodeWithText("Deleted Morning routine").assertIsDisplayed()
        composeTestRule.onNodeWithText(reason).assertDoesNotExist()

        composeTestRule.onNodeWithText(undo).performClick()
        composeTestRule.runOnIdle { assertTrue(undone) }
        composeTestRule.onNodeWithText(reason).assertIsDisplayed()
        composeTestRule.onNodeWithText(next).assertDoesNotExist()
        // Still the reason's turn just short of its minimum, not merely the moment the Undo ends.
        composeTestRule.mainClock.advanceTimeBy(STICKY_MESSAGE_MINIMUM_MS - 500)
        composeTestRule.onNodeWithText(reason).assertIsDisplayed()
        composeTestRule.onNodeWithText(next).assertDoesNotExist()

        // A second past the minimum, so inside the next message's own short time.
        composeTestRule.mainClock.advanceTimeBy(1_500)
        composeTestRule.onNodeWithText(next).assertIsDisplayed()
        composeTestRule.onNodeWithText(reason).assertDoesNotExist()
    }

    @Test
    fun aReasonQueuedBehindAnotherSnackbarGetsItsFullTimeOnceShown() {
        val messages = Channel<UiMessage>(Channel.UNLIMITED)
        val host = SnackbarHostState()
        lateinit var scope: CoroutineScope
        composeTestRule.setContent {
            val context = LocalContext.current
            scope = rememberCoroutineScope()
            LaunchedEffect(Unit) { host.showUiMessages(messages.receiveAsFlow(), context) {} }
            OpenTaskerTheme { SnackbarHost(host) }
        }
        val reason = string(R.string.ui_error_import_damaged)
        val direct = string(R.string.ui_flow_target_missing)
        val next = string(R.string.ui_message_tasker_xml_ready)

        composeTestRule.runOnIdle { scope.launch { host.showSnackbarOverReason(direct) } }
        composeTestRule.onNodeWithText(direct).assertIsDisplayed()
        messages.trySend(UiMessage(R.string.ui_error_import_damaged, stayUntilDismissed = true))
        messages.trySend(UiMessage(R.string.ui_message_tasker_xml_ready))

        // The direct message's 4 seconds run out and the reason comes up.
        composeTestRule.mainClock.advanceTimeBy(4_500)
        composeTestRule.onNodeWithText(reason).assertIsDisplayed()
        // Over 6 seconds since it was queued, but only 2 on screen.
        composeTestRule.mainClock.advanceTimeBy(2_000)
        composeTestRule.onNodeWithText(reason).assertIsDisplayed()
        composeTestRule.onNodeWithText(next).assertDoesNotExist()

        composeTestRule.mainClock.advanceTimeBy(3_000)
        composeTestRule.onNodeWithText(next).assertIsDisplayed()
        composeTestRule.onNodeWithText(reason).assertDoesNotExist()
    }

    @Test
    fun aPasteCutAtTheCapSaysSoAndWontGoToReview() {
        var text by mutableStateOf("<TaskerData sr=\"\">")
        composeTestRule.setContent {
            OpenTaskerTheme {
                OpenTaskerBundleTextImportDialog(
                    text = text,
                    busy = false,
                    onTextChanged = { text = it },
                    onDismiss = {},
                    onConfirm = {},
                )
            }
        }
        val review = string(R.string.import_paste_json_review)
        composeTestRule.onNodeWithText(review).assertIsEnabled()
        composeTestRule.onNodeWithText(string(R.string.import_paste_too_long), useUnmergedTree = true).assertDoesNotExist()

        text = "x".repeat(MAX_PASTED_BUNDLE_CHARS)
        composeTestRule.onNodeWithText(string(R.string.import_paste_too_long), useUnmergedTree = true).assertIsDisplayed()
        composeTestRule.onNodeWithText(review).assertIsNotEnabled()
    }
}
