package com.opentasker.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import android.os.SystemClock
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.opentasker.core.model.ContextSpec
import com.opentasker.core.model.ContextType
import com.opentasker.core.model.Profile
import com.opentasker.core.model.Task
import com.opentasker.core.plugins.locale.LocaleGrant
import com.opentasker.ui.theme.OpenTaskerTheme
import com.opentasker.ui.utils.SINGLE_TAP_WINDOW_MS
import com.opentasker.ui.utils.rememberSingleTap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * A double tap on a Delete or Revoke that runs on the tap takes one row, not the one that slides
 * up under the finger, and a deliberate tap after the window still goes through. The gate reads
 * the real uptime clock, so these run on a phone rather than against the JVM's TapGate alone.
 */
class DoubleTapDeleteInstrumentedTest {
    @get:Rule
    val composeTestRule = createAccessibilityComposeRule()

    /** The control: without a gate the same double tap is two clicks, so one delete below is the gate's doing. */
    @Test
    fun aDoubleTapIsTwoClicksOnAnUngatedButton() {
        var clicks by mutableIntStateOf(0)
        composeTestRule.setContent {
            OpenTaskerTheme {
                IconButton(onClick = { clicks++ }) {
                    Icon(Icons.Filled.Delete, contentDescription = "Ungated delete")
                }
            }
        }

        tapTwiceAt(composeTestRule.onNodeWithContentDescription("Ungated delete").fetchSemanticsNode().boundsInRoot.center)
        assertEquals(2, clicks)
    }

    /**
     * The other control: with a gate in each row, as these lists had before, the double tap's
     * second tap lands on the row that slid up and deletes it too. So the list lays out again
     * between the two taps, and the gated lists below aren't just catching a repeat on one row.
     */
    @Test
    fun aGateInEachRowLetsTheRowThatSlidUpGo() {
        var rows by mutableStateOf(listOf("First", "Second"))
        val deleted = mutableListOf<String>()
        composeTestRule.setContent {
            OpenTaskerTheme {
                Column {
                    rows.forEach { row ->
                        key(row) {
                            val singleTap = rememberSingleTap()
                            IconButton(onClick = { singleTap { deleted += row; rows = rows - row } }) {
                                Icon(Icons.Filled.Delete, contentDescription = "Delete $row")
                            }
                        }
                    }
                }
            }
        }

        tapTwiceAt(composeTestRule.onNodeWithContentDescription("Delete First").fetchSemanticsNode().boundsInRoot.center)
        assertEquals(listOf("First", "Second"), deleted)
    }

    @Test
    fun aDoubleTapOnAContextDeleteRemovesOneContext() {
        val task = Task(id = 3, name = "Mute")
        var profile by mutableStateOf(
            Profile(
                id = 4,
                name = "Work hours",
                enterTaskId = task.id,
                contexts = listOf(ContextSpec(ContextType.TIME), ContextSpec(ContextType.DAY)),
            ),
        )
        val deleted = mutableListOf<Int>()
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
                    onDeleteContext = { _, index ->
                        deleted += index
                        profile = profile.copy(contexts = profile.contexts.filterIndexed { i, _ -> i != index })
                    },
                    contentPadding = PaddingValues(0.dp),
                )
            }
        }
        composeTestRule.onNodeWithContentDescription("Expand", useUnmergedTree = true).performClick()

        val firstDelete = composeTestRule.onNodeWithContentDescription("Delete context 1, Time").performScrollTo()
        val tapPoint = firstDelete.fetchSemanticsNode().boundsInRoot.center
        tapTwiceAt(tapPoint)
        assertEquals(listOf(0), deleted)
        assertEquals(listOf(ContextSpec(ContextType.DAY)), profile.contexts)
        // The Day row's Delete slid up under the spot both taps landed on.
        assertTrue(composeTestRule.onNodeWithContentDescription("Delete context 1, Day").fetchSemanticsNode().boundsInRoot.contains(tapPoint))

        Thread.sleep(PAST_THE_WINDOW_MS)
        composeTestRule.onNodeWithContentDescription("Delete context 1, Day").performScrollTo().performClick()
        composeTestRule.waitForIdle()
        assertEquals(listOf(0, 0), deleted)
        assertEquals(emptyList<ContextSpec>(), profile.contexts)
    }

    @Test
    fun aDoubleTapOnRevokeRevokesOneGrant() {
        val first = LocaleGrant(token = "first-token", taskId = 42L)
        val second = LocaleGrant(token = "second-token", taskId = 43L)
        var grants by mutableStateOf(listOf(first, second))
        val revoked = mutableListOf<LocaleGrant>()
        composeTestRule.setContent {
            OpenTaskerTheme {
                LocaleGrantManagementCard(
                    tasks = listOf(Task(id = 42L, name = "Porch lights"), Task(id = 43L, name = "Garage door")),
                    grants = grants,
                    onRevoke = { revoked += it; grants = grants - it },
                    onRestore = { grants = grants + it },
                    onUndoableMessage = { _, _ -> },
                )
            }
        }

        val tapPoint = composeTestRule.onAllNodesWithText("Revoke")[0].fetchSemanticsNode().boundsInRoot.center
        tapTwiceAt(tapPoint)
        assertEquals(listOf(first), revoked)
        assertEquals(listOf(second), grants)
        // The second grant's Revoke slid up under the spot both taps landed on.
        assertTrue(composeTestRule.onAllNodesWithText("Revoke")[0].fetchSemanticsNode().boundsInRoot.contains(tapPoint))

        Thread.sleep(PAST_THE_WINDOW_MS)
        composeTestRule.onAllNodesWithText("Revoke")[0].performClick()
        composeTestRule.waitForIdle()
        assertEquals(listOf(first, second), revoked)
    }

    /**
     * A finger's double tap: two taps on one spot of the screen with a frame between them, so a row
     * the first tap removed is gone before the second lands. `doubleClick()` sends both taps before
     * the list lays out again, and its second tap never reaches the row that slid up.
     */
    private fun tapTwiceAt(point: Offset) {
        val start = SystemClock.uptimeMillis()
        composeTestRule.onRoot().performTouchInput { click(point) }
        composeTestRule.waitForIdle()
        composeTestRule.onRoot().performTouchInput { click(point) }
        val apart = SystemClock.uptimeMillis() - start
        composeTestRule.waitForIdle()
        // Slower than the window and the second tap is a deliberate one, which the gate lets through.
        assertTrue("the taps were $apart ms apart", apart < SINGLE_TAP_WINDOW_MS)
    }

    private companion object {
        const val PAST_THE_WINDOW_MS = SINGLE_TAP_WINDOW_MS + 200L
    }
}
