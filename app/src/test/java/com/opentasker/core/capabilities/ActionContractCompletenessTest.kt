package com.opentasker.core.capabilities

import com.opentasker.core.actions.ActionMetadataRegistry
import com.opentasker.core.actions.ActionCatalog
import com.opentasker.core.actions.registerActionMetadata
import com.opentasker.core.engine.ActionRegistry
import com.opentasker.core.engine.ActionRetrySafety
import com.opentasker.core.engine.FlowControl
import com.opentasker.core.engine.SUB_TASK_ACTION_ID
import com.opentasker.core.registerCoreRuntime
import com.opentasker.core.power.ShizukuPowerBackend
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText

/**
 * The action contract must be total: every action the app can run has exactly one metadata,
 * capability, and sensitivity classification, and the README's advertised counts are derived from
 * the registry rather than maintained by hand.
 *
 * The failure this locks out is a fail-open default — an action that is registered but never
 * reviewed used to report itself as Ready.
 */
class ActionContractCompletenessTest {

    private val engineHandledActions = setOf(SUB_TASK_ACTION_ID) + FlowControl.ALL

    private val repoRoot: Path = listOf(Path.of("."), Path.of(".."))
        .first { Files.exists(it.resolve("README.md")) && Files.exists(it.resolve("app/build.gradle.kts")) }

    /**
     * Setup is two files: the screen owns layout and the state machine, `SetupRows.kt` owns the row
     * catalogue. A grant path can be declared in either, so assertions about what Setup offers read
     * both rather than naming one and going quiet when a row moves.
     */
    private fun setupSurface(): String = listOf("PermissionOnboardingScreen.kt", "SetupRows.kt")
        .joinToString("\n") { repoRoot.resolve("app/src/main/java/com/opentasker/ui/screens/$it").readText() }

    @Before
    fun setUp() {
        registerActionMetadata()
        registerCoreRuntime()
    }

    private fun allActionIds(): Set<String> =
        ActionRegistry.allIds().toSet() + engineHandledActions + ActionMetadataRegistry.all().map { it.id }

    @Test
    fun everyActionHasAnExplicitCapabilityContract() {
        val uncontracted = allActionIds()
            .filterNot { it in ActionCapabilityRegistry.contractedActionIds() }
            .sorted()

        assertTrue(
            "Actions with no explicit capability contract (they now fail closed as unknown, which " +
                "is safe but wrong for a shipped action): $uncontracted",
            uncontracted.isEmpty(),
        )
    }

    @Test
    fun anUnreviewedActionFailsClosedInsteadOfReportingReady() {
        val capability = ActionCapabilityRegistry.get("some.brand.new.action")
        assertEquals(CapabilityLevel.Unsupported, capability.level)
        assertFalse(capability.canAdd)
    }

    @Test
    fun everyActionHasASensitivityClassification() {
        val unclassified = allActionIds()
            .filterNot(AutomationSensitivityRegistry::isKnown)
            .sorted()

        assertTrue("Actions with no sensitivity classification: $unclassified", unclassified.isEmpty())
    }

    @Test
    fun everyRegisteredActionHasAReviewedRetrySafetyClassification() {
        val metadataIds = ActionMetadataRegistry.all().map { it.id }.toSet()
        val registered = ActionRegistry.all().filter { it.id in metadataIds }
        val retryable = registered
            .filter { it.retrySafety == ActionRetrySafety.IDEMPOTENT }
            .map { it.id }
            .toSet()

        assertEquals("all built-in actions must remain registered", 78, registered.size)
        assertEquals(
            setOf(
                "app.archive", "app.unarchive", "brightness.set", "clipboard.get", "clipboard.set",
                "contacts.lookup", "data.read", "datetime.add", "datetime.format", "datetime.parse",
                "dnd.set", "download", "file.delete", "file.list", "file.read", "file.write",
                "http.get", "ime.info", "lock", "media.mute", "notify.cancel", "ping", "plugin.locale.query",
                "screen.off", "screen.timeout", "settings.write", "sound.pause", "sound.stop", "text.join", "text.match",
                "text.replace", "text.split", "text.substring", "tile.set", "var.persist", "var.set",
                "volume.set", "wake", "wifi.scan", "wol", "zen.rule.clear", "ringer.set",
            ),
            retryable,
        )
        assertTrue(registered.all { it.retrySafety in ActionRetrySafety.entries })
        assertEquals(ActionRetrySafety.IDEMPOTENT, ActionRegistry.get("http.request")?.retrySafetyFor(mapOf("method" to "GET")))
        assertEquals(ActionRetrySafety.NEVER, ActionRegistry.get("http.request")?.retrySafetyFor(mapOf("method" to "POST")))
    }

    @Test
    fun runtimeMetadataAndCapabilitiesResolveFromTheCanonicalDeclaration() {
        assertEquals(78, ActionCatalog.all.size)
        ActionCatalog.all.forEach { definition ->
            val action = definition.factory()
            assertSame(definition, action.definition)
            assertSame(definition, ActionRegistry.get(definition.id)?.definition)
            assertEquals(definition.metadata, ActionMetadataRegistry.get(definition.id))
            assertEquals(definition.capability(), ActionCapabilityRegistry.get(definition.id))
        }
    }

    @Test
    fun everyRegisteredBuiltInSourceDeclaresRetrySafety() {
        val catalog = repoRoot.resolve("app/src/main/java/com/opentasker/core/actions/ActionCatalog.kt").readText()
        val registeredCount = Regex("(?m)^\\s*define\\(\\\"").findAll(catalog).count()
        val classifiedCount = Regex("(?m)^\\s*define\\(\\\"[^\"]+\",\\s*ActionCategory\\.[A-Z]+,\\s*ActionRetrySafety\\.(?:NEVER|IDEMPOTENT)")
            .findAll(catalog)
            .count()

        assertEquals(
            "Adding a registered action without an explicit retry classification must fail the source guard",
            registeredCount,
            classifiedCount,
        )
    }

    @Test
    fun theContractHasNoEntriesForActionsThatDoNotExist() {
        val stale = ActionCapabilityRegistry.contractedActionIds()
            .filterNot { it in allActionIds() }
            .sorted()

        assertTrue("Capability contract names actions that are not registered: $stale", stale.isEmpty())
    }

    @Test
    fun permanentlyBlockedActionsStayUnsupportedAndShizukuActionsRequireSetup() {
        // "lock" used to be listed here. It is not permanently blocked: DevicePolicyManager.lockNow
        // works for any app with an active device admin, no root, Shizuku, or accessibility service
        // involved. It is now covered by the special-access test below, which is a stricter
        // assertion than this one was.
        listOf("app.kill", "wifi.toggle")
            .forEach { actionId ->
                assertEquals(
                    "$actionId must be Unsupported",
                    CapabilityLevel.Unsupported,
                    ActionCapabilityRegistry.get(actionId).level,
                )
            }
        ShizukuPowerBackend.elevatedActionIds.forEach { actionId ->
            assertEquals(
                "$actionId must be RequiresSetup",
                CapabilityLevel.RequiresSetup,
                ActionCapabilityRegistry.get(actionId).level,
            )
        }
    }

    @Test
    fun specialAccessActionsDeclareTheGrantTheyNeedAndTheAppRequestsIt() {
        listOf("brightness.set", "screen.timeout").forEach { actionId ->
            assertEquals(
                "$actionId must be gated on its special access, not silently Supported",
                CapabilityLevel.RequiresSetup,
                ActionCapabilityRegistry.get(actionId).level,
            )
        }

        // Settings.System.canWrite() can never become true without the manifest declaration, so an
        // action advertising "one grant away" would otherwise fail forever.
        val manifest = repoRoot.resolve("app/src/main/AndroidManifest.xml").readText()
        assertTrue(
            "WRITE_SETTINGS must be declared for the brightness/screen-timeout grant path to exist",
            "android.permission.WRITE_SETTINGS" in manifest,
        )
        val setup = setupSurface()
        assertTrue(
            "Setup must expose a working Modify system settings grant path",
            "Settings.ACTION_MANAGE_WRITE_SETTINGS" in setup && "Settings.System.canWrite(context)" in setup,
        )
    }

    /**
     * The lock action, end to end.
     *
     * It shipped for months as an unconditional failure with no receiver behind it, so this pins
     * every link in the chain rather than the capability level alone: the admin component, the
     * force-lock-only policy, the manifest registration, and the Setup row that turns it on and
     * back off again.
     */
    @Test
    fun lockDeclaresTheDeviceAdminItNeedsAndTheAppCanActivateAndRemoveIt() {
        assertEquals(
            "lock is one grant away, not permanently blocked",
            CapabilityLevel.RequiresSetup,
            ActionCapabilityRegistry.get("lock").level,
        )

        // Read the declared policies only. Scanning the whole file would also match a comment that
        // names a policy in order to say it is deliberately absent, which is a false failure.
        val policyFile = repoRoot.resolve("app/src/main/res/xml/device_admin.xml").readText()
        val open = policyFile.indexOf("<uses-policies>")
        assertTrue("device_admin.xml must declare its policies", open >= 0)
        val close = policyFile.indexOf("</uses-policies>", open)
        assertTrue("the policy block must be closed", close > open)
        val declared = policyFile.substring(open, close)

        assertTrue("the admin must ask for force-lock", "<force-lock />" in declared)
        listOf("wipe-data", "reset-password", "expire-password", "encrypted-storage", "disable-camera", "limit-password", "watch-login")
            .forEach { forbidden ->
                assertFalse("the lock admin must not ask for $forbidden", forbidden in declared)
            }

        val manifest = repoRoot.resolve("app/src/main/AndroidManifest.xml").readText()
        assertTrue(
            "the admin receiver must be registered or activation can never succeed",
            "com.opentasker.core.platform.LockDeviceAdminReceiver" in manifest,
        )
        assertTrue(
            "only the system may bind a device admin receiver",
            "android.permission.BIND_DEVICE_ADMIN" in manifest,
        )
        assertTrue("the policy resource must be attached", "@xml/device_admin" in manifest)

        val action = repoRoot.resolve("app/src/main/java/com/opentasker/core/actions/SystemActions.kt").readText()
        assertTrue("the action must actually lock", "manager.lockNow()" in action)
        assertTrue(
            "an inactive admin must be reported, not attempted",
            "LockDeviceAdminReceiver.isActive(ctx.app)" in action,
        )

        val setup = setupSurface()
        assertTrue(
            "Setup must offer activation",
            "DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN" in setup,
        )
        // Without removal the user cannot uninstall the app, because Android blocks uninstalling
        // while a device admin is active and offers no intent that lands on our admin's page.
        assertTrue("Setup must offer removal", "removeActiveAdmin" in setup)
    }

    @Test
    fun readmeActionCountsAreDerivedFromTheRegistry() {
        val registered = ActionRegistry.allIds().size
        val engineHandled = engineHandledActions.size
        val readme = repoRoot.resolve("README.md").readText()

        assertTrue(
            "README must advertise the registry-derived counts: expected " +
                "\"### Actions ($registered registered + $engineHandled engine-handled)\"",
            "### Actions ($registered registered + $engineHandled engine-handled)" in readme,
        )
        assertTrue(
            "README feature bullet must advertise the registry-derived count: expected " +
                "\"**$registered built-in actions**\"",
            "**$registered built-in actions**" in readme,
        )

        // The per-category table has to add up to the same number, so a new action cannot be
        // announced in the headline while its category row silently stays behind.
        val actionSection = readme
            .substringAfter("### Actions (")
            .substringBefore("\n#")
        val categoryTotal = Regex("""^\| [A-Za-z ]+ \| *(\d+)(?:\+\d+)? \|""", RegexOption.MULTILINE)
            .findAll(actionSection)
            .sumOf { it.groupValues[1].toInt() }
        assertEquals("README action category rows must sum to the registered count", registered, categoryTotal)
    }
}
