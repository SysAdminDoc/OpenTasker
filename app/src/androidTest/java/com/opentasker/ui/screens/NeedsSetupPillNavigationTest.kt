package com.opentasker.ui.screens

import android.os.ParcelFileDescriptor
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.opentasker.app.R
import com.opentasker.core.model.ActionSpec
import com.opentasker.core.model.Task
import com.opentasker.core.storage.AppDatabase
import com.opentasker.core.storage.toEntity
import com.opentasker.ui.theme.OpenTaskerTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * A-402 through the whole screen: the pill on a task's action row opens Setup with that row in
 * focus. ActionSetupPillTest covers the pill on its own; this one covers the screen's wiring.
 */
class NeedsSetupPillNavigationTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var db: AppDatabase
    private var writeSettingsMode = "default"

    @Before
    fun setUp() {
        writeSettingsMode = shell("appops get ${context.packageName} WRITE_SETTINGS").lineSequence()
            // A "Uid mode:" line can come first, and appops set below changes the package mode.
            .filterNot { it.trimStart().startsWith("Uid mode:") }
            .firstNotNullOfOrNull { Regex("WRITE_SETTINGS: (\\w+)").find(it)?.groupValues?.get(1) } ?: "default"
        shell("appops set ${context.packageName} WRITE_SETTINGS deny")
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        runBlocking {
            // The first-run flow would cover the screen.
            OnboardingPreference.markCompleted(context)
            db.taskDao().insert(
                Task(name = "Dim", actions = listOf(ActionSpec(type = "brightness.set", args = mapOf("level" to "40")))).toEntity(),
            )
        }
    }

    @After
    fun tearDown() {
        db.close()
        shell("appops set ${context.packageName} WRITE_SETTINGS $writeSettingsMode")
    }

    @Test
    fun tappingAPillOnTheTasksScreenOpensSetupOnItsRow() {
        composeTestRule.setContent { OpenTaskerTheme { ActiveAutomationUi(db) } }
        composeTestRule.onNode(hasText("Tasks") and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab))
            .performClick()
        // The pill waits for the first grant read, and the task list for the database. Plain
        // "Needs setup" text can show before the tappable pill does, so wait for the pill itself.
        val pill = hasText("Needs setup") and hasClickAction()
        composeTestRule.waitUntil(TIMEOUT_MS) {
            composeTestRule.onAllNodes(pill).fetchSemanticsNodes().isNotEmpty()
        }

        composeTestRule.onAllNodes(pill).onFirst().performClick()

        val bannerTitle = context.getString(R.string.setup_focus_title)
        composeTestRule.waitUntil(TIMEOUT_MS) {
            composeTestRule.onAllNodes(hasText(bannerTitle)).fetchSemanticsNodes().isNotEmpty()
        }
        // The banner shows before the rows load, so it can't stand for the row. With no enabled
        // profile only the pill's focus lists Write Settings, so finding it proves the right focus.
        val writeSettingsRow = context.getString(R.string.setup_write_settings_title)
        // By tag: the banner scrolls out of composition before a row far down the list comes in.
        composeTestRule.waitUntil(TIMEOUT_MS) {
            runCatching {
                composeTestRule.onNode(hasTestTag(SETUP_LIST_TAG)).performScrollToNode(hasText(writeSettingsRow))
            }.isSuccess
        }
        composeTestRule.onNodeWithText(writeSettingsRow).assertIsDisplayed()
    }

    private fun shell(command: String): String {
        val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        // The command completes only once its output reaches EOF, so drain it before reading grants.
        return ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes().decodeToString() }
    }

    private companion object {
        const val TIMEOUT_MS = 10_000L
    }
}
