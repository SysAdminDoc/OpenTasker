package com.opentasker.ui.screens

import android.content.Context
import android.content.pm.PackageManager
import android.os.ParcelFileDescriptor
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.platform.app.InstrumentationRegistry
import com.opentasker.core.capabilities.CapabilityLevel
import com.opentasker.core.capabilities.SetupRequirement
import com.opentasker.core.model.ActionSpec
import com.opentasker.core.transfer.BundleImportPlan
import com.opentasker.core.transfer.CapabilityRequirement
import com.opentasker.core.transfer.OpenTaskerBundle
import com.opentasker.ui.theme.OpenTaskerTheme
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Assert.assertEquals
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

    private fun showAction(
        action: ActionSpec,
        pendingSetup: Set<SetupRequirement>?,
        onOpenSetup: (Set<SetupRequirement>) -> Unit = {},
    ) {
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
                    onOpenSetup = onOpenSetup,
                )
            }
        }
    }

    /**
     * A-402: Setup lists a row only for enabled profiles, so with none enabled the pill is the way
     * in. Setup focused on Write Settings showing its row is CriticalFlowComposeTest's half.
     */
    @Test
    fun tappingThePillOpensSetupOnTheRowTheActionNeeds() {
        val opened = AtomicReference<Set<SetupRequirement>?>(null)
        showAction(
            BRIGHTNESS,
            pendingSetup = setOf(SetupRequirement.WRITE_SETTINGS, SetupRequirement.DEVICE_ADMIN),
            onOpenSetup = opened::set,
        )

        composeTestRule.onNodeWithText("Needs setup").performClick()

        assertEquals(setOf(SetupRequirement.WRITE_SETTINGS), opened.get())
    }

    @Test
    fun beforeTheFirstReadThePillIsNotATap() {
        showAction(BRIGHTNESS, pendingSetup = null)

        composeTestRule.onNodeWithText("Needs setup").assertHasNoClickAction()
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

    @Test
    fun theImportReviewSaysReadyForANotificationActionOnceItsGrantIsIn() {
        showImportReview(pendingSetup = emptySet())

        composeTestRule.onNodeWithText("· Ready:", substring = true).assertIsDisplayed()
        composeTestRule.onAllNodesWithText("· Needs setup:", substring = true).assertCountEquals(0)
    }

    @Test
    fun theImportReviewSaysNeedsSetupWhileTheGrantIsMissing() {
        showImportReview(pendingSetup = setOf(SetupRequirement.POST_NOTIFICATIONS))

        composeTestRule.onNodeWithText("· Needs setup:", substring = true).assertIsDisplayed()
    }

    private fun showImportReview(pendingSetup: Set<SetupRequirement>?) {
        composeTestRule.setContent {
            OpenTaskerTheme {
                OpenTaskerBundleReviewDialog(
                    state = OpenTaskerBundleReviewState(
                        bundle = OpenTaskerBundle(appVersion = "0.2.96", exportedAtEpochMs = 0),
                        plan = BundleImportPlan(
                            canImport = true,
                            capabilityRequirements = listOf(
                                CapabilityRequirement(
                                    actionId = "notify.show",
                                    level = CapabilityLevel.RequiresSetup,
                                    reason = "Requires notification permission on Android 13+.",
                                ),
                            ),
                        ),
                    ),
                    busy = false,
                    onDismiss = {},
                    pendingSetup = pendingSetup,
                    onConfirm = {},
                )
            }
        }
        composeTestRule.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText("Capability review"))
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
