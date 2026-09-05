package com.opentasker.core.power

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText

class ShizukuPowerBackendTest {
    @After
    fun resetKillSwitch() {
        ShizukuPowerBackend.killSwitchEnabled = true
    }

    @Test
    fun statusForReportsManagerPresence() {
        val installed = ShizukuPowerBackend.statusFor(managerInstalled = true)
        val missing = ShizukuPowerBackend.statusFor(managerInstalled = false)

        assertEquals(ShizukuPowerState.ManagerInstalled, installed.state)
        assertTrue(installed.managerInstalled)
        assertEquals(ShizukuPowerState.NotInstalled, missing.state)
        assertFalse(missing.managerInstalled)
    }

    @Test
    fun elevatedActionHintsOnlyCoverRestrictedCandidates() {
        assertNotNull(ShizukuPowerBackend.hintForAction("reboot"))
        assertNotNull(ShizukuPowerBackend.hintForAction("airplane.toggle"))
        assertNull(ShizukuPowerBackend.hintForAction("notify.show"))
    }

    @Test
    fun managerPackageIsStableForPackageVisibilityQueries() {
        assertEquals("moe.shizuku.privileged.api", ShizukuPowerBackend.MANAGER_PACKAGE)
    }

    @Test
    fun killSwitchDisablesBackend() {
        ShizukuPowerBackend.killSwitchEnabled = true
        assertFalse(ShizukuPowerBackend.isReady())
    }

    @Test
    fun shellRunnerRejectsUnknownAction() {
        val result = ShizukuShellRunner.execute("unknown.action")
        assertTrue(result is ShellResult.Failure)
        assertTrue((result as ShellResult.Failure).reason.contains("not in the Shizuku allowlist"))
    }

    @Test
    fun shellRunnerRejectsWhenKillSwitchActive() {
        ShizukuPowerBackend.killSwitchEnabled = true
        val result = ShizukuShellRunner.execute("reboot")
        assertTrue(result is ShellResult.Failure)
        assertTrue((result as ShellResult.Failure).reason.contains("kill switch"))
    }

    @Test
    fun allElevatedActionsAreInAllowlist() {
        ShizukuPowerBackend.elevatedActionIds.forEach { actionId ->
            assertTrue("$actionId should be in allowlist", ShizukuShellRunner.isAllowed(actionId))
            assertTrue("$actionId should have variants", ShizukuShellRunner.allowedVariantCount(actionId) > 0)
        }
    }

    @Test
    fun allowlistPinsEveryElevatedCommandVariant() {
        val expected = mapOf(
            "airplane.toggle" to listOf(
                listOf("settings", "put", "global", "airplane_mode_on", "1"),
                listOf("settings", "put", "global", "airplane_mode_on", "0"),
            ),
            "mobile.toggle" to listOf(
                listOf("svc", "data", "enable"),
                listOf("svc", "data", "disable"),
            ),
            "aod.set" to listOf(
                listOf("settings", "put", "secure", "doze_always_on", "1"),
                listOf("settings", "put", "secure", "doze_always_on", "0"),
            ),
            "screenshot.take" to listOf(listOf("screencap", "-p")),
            "reboot" to listOf(listOf("svc", "power", "reboot", "false")),
            "screen.off" to listOf(listOf("input", "keyevent", "223")),
            "wake" to listOf(listOf("input", "keyevent", "224")),
        )

        assertEquals(ShizukuPowerBackend.elevatedActionIds, expected.keys)
        expected.forEach { (actionId, variants) ->
            assertEquals("$actionId variant count", variants.size, ShizukuCommandPolicy.variantCount(actionId))
            variants.forEachIndexed { index, argv ->
                assertEquals("$actionId variant $index", argv, ShizukuCommandPolicy.command(actionId, index))
                assertTrue("$actionId should accept its pinned variant", ShizukuCommandPolicy.isExact(actionId, argv))
            }
        }

        assertFalse(
            ShizukuCommandPolicy.isExact(
                "reboot",
                listOf("svc", "power", "reboot", "true"),
            ),
        )
        assertFalse(ShizukuCommandPolicy.isExact("airplane.toggle", listOf("settings", "put", "global", "airplane_mode_on", "1", "--user", "0")))
        assertFalse(
            "The always-on display write must not accept another secure key",
            ShizukuCommandPolicy.isExact("aod.set", listOf("settings", "put", "secure", "doze_enabled", "1")),
        )
    }

    @Test
    fun statusForDisabledShowsKillSwitchState() {
        val status = ShizukuPowerBackend.statusFor(
            managerInstalled = true,
            killSwitchEnabled = true,
        )
        assertEquals(ShizukuPowerState.Disabled, status.state)
    }

    @Test
    fun permissionAloneCannotReportReadyWithoutPrivilegedTransport() {
        val unavailable = ShizukuPowerBackend.statusFor(
            managerInstalled = true,
            serviceRunning = true,
            permissionGranted = true,
            privilegedTransportAvailable = false,
        )
        val ready = ShizukuPowerBackend.statusFor(
            managerInstalled = true,
            serviceRunning = true,
            permissionGranted = true,
            privilegedTransportAvailable = true,
        )

        assertEquals(ShizukuPowerState.BackendUnavailable, unavailable.state)
        assertFalse(unavailable.isReady)
        assertEquals(ShizukuPowerState.Ready, ready.state)
        assertTrue(ready.isReady)
    }

    @Test
    fun runnerNeverFallsBackToOrdinaryAppProcess() {
        ShizukuPowerBackend.killSwitchEnabled = false

        val result = ShizukuShellRunner.execute("reboot")

        assertTrue(result is ShellResult.Failure)
        assertTrue((result as ShellResult.Failure).reason.contains("No privileged Shizuku user-service transport"))
        assertFalse(ShizukuShellRunner.hasPrivilegedTransport())
    }

    @Test
    fun productionRunnerContainsNoProcessBuilderExecution() {
        val root = listOf(
            Path.of("src/main/java"),
            Path.of("app/src/main/java"),
        ).first(Files::exists)
        val source = root.resolve("com/opentasker/core/power/ShizukuShellRunner.kt").readText()

        assertFalse(source.contains("ProcessBuilder("))

        val service = root.resolve("com/opentasker/core/power/ShizukuCommandUserService.kt").readText()
        assertTrue(service.contains("Runtime.getRuntime().exec"))
        assertFalse(service.contains("ProcessBuilder("))
        assertTrue(service.contains("ShizukuCommandPolicy.isExact"))
        assertTrue(service.contains("16777114"))
    }

    @Test
    fun runnerBindsAndUnbindsTheShizukuUserService() {
        val root = listOf(
            Path.of("src/main/java"),
            Path.of("app/src/main/java"),
        ).first(Files::exists)
        val source = root.resolve("com/opentasker/core/power/ShizukuShellRunner.kt").readText()
        val application = root.resolve("com/opentasker/app/OpenTaskerApp_NoHilt.kt").readText()

        assertTrue(source.contains("Shizuku.bindUserService"))
        assertTrue(source.contains("Shizuku.unbindUserService"))
        assertTrue(source.contains("onBindingDied"))
        assertTrue(application.contains("ShizukuPowerBackend.shutdown()"))
    }

    @Test
    fun killSwitchDefaultsOnAndIsBackedByPreferences() {
        val root = listOf(
            Path.of("src/main/java"),
            Path.of("app/src/main/java"),
        ).first(Files::exists)
        val source = root.resolve("com/opentasker/core/power/ShizukuPowerBackend.kt").readText()

        assertTrue(source.contains("var killSwitchEnabled: Boolean = true"))
        assertTrue(source.contains("KEY_KILL_SWITCH"))
        assertTrue(source.contains("getSharedPreferences"))
        assertTrue(source.contains("putBoolean(KEY_KILL_SWITCH, enabled)"))
    }

    @Test
    fun setupAndCapabilitiesKeepUnavailableTransportFailClosed() {
        val root = listOf(
            Path.of("src/main/java"),
            Path.of("app/src/main/java"),
        ).first(Files::exists)
        val setup = listOf("PermissionOnboardingScreen.kt", "SetupRows.kt")
            .joinToString("\n") { root.resolve("com/opentasker/ui/screens/$it").readText() }
        val capabilities = root.resolve("com/opentasker/core/capabilities/ActionCapabilities.kt").readText()
        val application = root.resolve("com/opentasker/app/OpenTaskerApp_NoHilt.kt").readText()

        assertTrue(setup.contains("PermissionAction.ShizukuPermission"))
        assertTrue(setup.contains("PermissionAction.ShizukuKillSwitch"))
        assertTrue(setup.contains("ShizukuPowerState.BackendUnavailable"))
        assertFalse(capabilities.contains("ShizukuPowerBackend.isReady()"))
        assertTrue(application.contains("ShizukuPowerBackend.initialize(this)"))
    }
}
