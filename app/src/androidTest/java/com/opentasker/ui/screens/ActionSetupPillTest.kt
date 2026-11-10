package com.opentasker.ui.screens

import android.content.Context
import android.content.pm.PackageManager
import android.os.ParcelFileDescriptor
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.platform.app.InstrumentationRegistry
import com.opentasker.core.capabilities.SetupRequirement
import com.opentasker.core.model.ActionSpec
import com.opentasker.ui.theme.OpenTaskerTheme
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * A-392: the pill on an action row follows the live grant, not the action's static tier.
 *
 * The device tests flip grants that don't restart the app: Write Settings is an app-op and secure
 * settings is a development permission. Revoking a runtime permission would kill the process
 * under test.
 */
class ActionSetupPillTest {
    @get:Rule
    val composeTestRule = createAccessibilityComposeRule()

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private var hadSecureSettings = false
    private var writeSettingsMode = "default"

    @Before
    fun rememberGrants() {
        hadSecureSettings = context.checkSelfPermission(SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED
        // "No operations." when the op was never set, which is the default mode.
        writeSettingsMode = Regex("WRITE_SETTINGS: (\\w+)")
            .find(shell("appops get ${context.packageName} WRITE_SETTINGS"))
            ?.groupValues?.get(1) ?: "default"
    }

    @After
    fun restoreGrants() {
        shell("appops set ${context.packageName} WRITE_SETTINGS $writeSettingsMode")
        shell("pm ${if (hadSecureSettings) "grant" else "revoke"} ${context.packageName} $SECURE_SETTINGS")
    }

    private fun showAction(action: ActionSpec, pendingSetup: Set<SetupRequirement>?) {
        composeTestRule.setContent {
            OpenTaskerTheme {
                ActionRow(
                    index = 0,
                    action = action,
                    canMoveUp = false,
                    canMoveDown = false,
                    onMoveUp = {},
                    onMoveDown = {},
                    onEdit = {},
                    onDelete = {},
                    onRun = {},
                    runBusy = false,
                    pendingSetup = pendingSetup,
                )
            }
        }
    }

    @Test
    fun aGrantedRequirementShowsNoPill() {
        showAction(BRIGHTNESS, pendingSetup = setOf(SetupRequirement.DEVICE_ADMIN))

        composeTestRule.onAllNodesWithText("Needs setup").assertCountEquals(0)
    }

    @Test
    fun anUngrantedRequirementShowsThePill() {
        showAction(BRIGHTNESS, pendingSetup = setOf(SetupRequirement.WRITE_SETTINGS))

        composeTestRule.onNodeWithText("Needs setup").assertIsDisplayed()
    }

    @Test
    fun beforeTheFirstReadThePillStays() {
        showAction(BRIGHTNESS, pendingSetup = null)

        composeTestRule.onNodeWithText("Needs setup").assertIsDisplayed()
    }

    @Test
    fun allowingWriteSettingsOnTheDeviceClearsThePill() {
        shell("appops set ${context.packageName} WRITE_SETTINGS allow")

        showAction(BRIGHTNESS, pendingSetupRequirements(context))

        composeTestRule.onAllNodesWithText("Needs setup").assertCountEquals(0)
    }

    @Test
    fun denyingWriteSettingsOnTheDeviceKeepsThePill() {
        shell("appops set ${context.packageName} WRITE_SETTINGS deny")

        showAction(BRIGHTNESS, pendingSetupRequirements(context))

        composeTestRule.onNodeWithText("Needs setup").assertIsDisplayed()
    }

    @Test
    fun grantingSecureSettingsClearsASecureTableWritesPill() {
        shell("pm grant ${context.packageName} $SECURE_SETTINGS")

        showAction(SECURE_WRITE, pendingSetupRequirements(context))

        composeTestRule.onAllNodesWithText("Needs setup").assertCountEquals(0)
    }

    @Test
    fun withoutSecureSettingsASecureTableWriteKeepsItsPill() {
        shell("pm revoke ${context.packageName} $SECURE_SETTINGS")

        showAction(SECURE_WRITE, pendingSetupRequirements(context))

        composeTestRule.onNodeWithText("Needs setup").assertIsDisplayed()
    }

    @Test
    fun aResumeReadsTheGrantsAgainAndClearsAFixedPill() {
        val owner = composeTestRule.runOnUiThread { ManualLifecycleOwner() }
        val pending = AtomicReference(setOf(SetupRequirement.WRITE_SETTINGS))
        val reads = AtomicInteger()
        composeTestRule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                OpenTaskerTheme {
                    ActionRow(
                        index = 0,
                        action = BRIGHTNESS,
                        canMoveUp = false,
                        canMoveDown = false,
                        onMoveUp = {},
                        onMoveDown = {},
                        onEdit = {},
                        onDelete = {},
                        onRun = {},
                        runBusy = false,
                        pendingSetup = rememberPendingSetup(refreshKey = Unit) {
                            pending.get().also { reads.incrementAndGet() }
                        },
                    )
                }
            }
        }
        // Wait for the first read, or flipping the grant below could clear the pill without a resume.
        composeTestRule.waitUntil(5_000) { reads.get() == 1 }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Needs setup").assertIsDisplayed()

        // The grant lands in a settings page, and the app comes back to the front.
        pending.set(emptySet())
        composeTestRule.runOnUiThread { owner.registry.currentState = Lifecycle.State.RESUMED }

        composeTestRule.waitUntil(5_000) {
            composeTestRule.onAllNodesWithText("Needs setup").fetchSemanticsNodes().isEmpty()
        }
    }

    private class ManualLifecycleOwner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }

    private fun shell(command: String): String {
        val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        // The command completes only once its output reaches EOF, so drain it before reading grants.
        return ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes().decodeToString() }
    }

    private companion object {
        const val SECURE_SETTINGS = "android.permission.WRITE_SECURE_SETTINGS"
        val BRIGHTNESS = ActionSpec(type = "brightness.set", args = mapOf("level" to "128"))
        val SECURE_WRITE = ActionSpec(
            type = "settings.write",
            args = mapOf("table" to "secure", "key" to "location_mode", "value" to "3"),
        )
    }
}
