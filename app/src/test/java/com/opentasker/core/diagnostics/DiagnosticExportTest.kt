package com.opentasker.core.diagnostics

import com.opentasker.core.apps.TargetSignerPin
import com.opentasker.core.apps.TargetSignerPinSource
import com.opentasker.core.apps.TargetSignerState
import com.opentasker.core.apps.TargetSignerStatus
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticExportTest {
    @Test
    fun redactSensitiveRemovesPasswordValues() {
        val input = "password=secret123 other=visible"
        val result = DiagnosticExport.redactSensitive(input)
        assertFalse(result.contains("secret123"))
        assertTrue(result.contains("password=[REDACTED]"))
        assertTrue(result.contains("visible"))
    }

    @Test
    fun redactSensitiveRemovesTokenValues() {
        val input = "token: abc-xyz-123"
        val result = DiagnosticExport.redactSensitive(input)
        assertFalse(result.contains("abc-xyz-123"))
        assertTrue(result.contains("token=[REDACTED]"))
    }

    @Test
    fun redactSensitiveRemovesCardNumbers() {
        val input = "Card is 4111 1111 1111 1111 for purchase"
        val result = DiagnosticExport.redactSensitive(input)
        assertFalse(result.contains("4111"))
        assertTrue(result.contains("[REDACTED-CARD]"))
    }

    @Test
    fun redactSensitivePreservesNonSensitiveText() {
        val input = "Task completed successfully in 250ms"
        assertEquals(input, DiagnosticExport.redactSensitive(input))
    }

    @Test
    fun redactSensitiveCaseInsensitive() {
        val input = "SECRET=mysecret AUTH=mytoken"
        val result = DiagnosticExport.redactSensitive(input)
        assertFalse(result.contains("mysecret"))
        assertFalse(result.contains("mytoken"))
    }

    @Test
    fun redactSensitiveRemovesAuthorizationAndBearerValues() {
        val result = DiagnosticExport.redactSensitive(
            "Authorization: Bearer abc.def.ghi\nretry Bearer secondary-token",
        )

        assertFalse(result.contains("abc.def.ghi"))
        assertFalse(result.contains("secondary-token"))
        assertTrue(result.contains("Authorization: [REDACTED]"))
        assertTrue(result.contains("Bearer [REDACTED]"))
    }

    /** Issue #30's report listed exact alarms as denied while its own log showed exact ticks being scheduled. */
    @Test
    fun specialAccessPermissionsReportWhatSettingsAllowsNotTheInstallFlag() {
        assertEquals(
            "  SCHEDULE_EXACT_ALARM: granted (special access)",
            DiagnosticExport.permissionLine("android.permission.SCHEDULE_EXACT_ALARM", grantFlag = false, definedOnDevice = true) { true },
        )
        assertEquals(
            "  ACCESS_NOTIFICATION_POLICY: denied (special access)",
            DiagnosticExport.permissionLine("android.permission.ACCESS_NOTIFICATION_POLICY", grantFlag = true, definedOnDevice = true) { false },
        )
        assertEquals(
            "  SYSTEM_ALERT_WINDOW: special access, couldn't check",
            DiagnosticExport.permissionLine("android.permission.SYSTEM_ALERT_WINDOW", grantFlag = false, definedOnDevice = true) {
                throw SecurityException("blocked")
            },
        )
    }

    @Test
    fun aPermissionThisDeviceDoesNotDefineIsNotReportedAsDenied() {
        assertEquals(
            "  QUERY_ADVANCED_PROTECTION_MODE: not defined on this device",
            DiagnosticExport.permissionLine("android.permission.QUERY_ADVANCED_PROTECTION_MODE", grantFlag = false, definedOnDevice = false, specialAccess = null),
        )
        assertEquals(
            "  com.termux.permission.RUN_COMMAND: not defined on this device",
            DiagnosticExport.permissionLine("com.termux.permission.RUN_COMMAND", grantFlag = false, definedOnDevice = false, specialAccess = null),
        )
    }

    @Test
    fun ordinaryPermissionsKeepTheirGrantFlag() {
        assertEquals(
            "  READ_CONTACTS: denied",
            DiagnosticExport.permissionLine("android.permission.READ_CONTACTS", grantFlag = false, definedOnDevice = true, specialAccess = null),
        )
        assertEquals(
            "  moe.shizuku.manager.permission.API_V23: granted",
            DiagnosticExport.permissionLine("moe.shizuku.manager.permission.API_V23", grantFlag = true, definedOnDevice = true, specialAccess = null),
        )
    }

    @Test
    fun pinnedTargetLineShowsPackagePrefixSourceAndStateOnly() {
        val digest = "0123456789abcdef".repeat(4)
        val firstUse = TargetSignerPin("com.termux", listOf(digest), TargetSignerPinSource.FIRST_USE, 5L)
        val approved = TargetSignerPin("com.example.plugin", listOf("f".repeat(64), digest), TargetSignerPinSource.APPROVED, 6L)

        assertEquals(
            "  com.termux: sha256 0123456789ab, pinned on first use, match",
            DiagnosticExport.pinnedTargetLine(TargetSignerStatus(firstUse, TargetSignerState.MATCH)),
        )
        assertEquals(
            "  com.example.plugin: sha256 ffffffffffff+0123456789ab, pinned at approval, mismatch",
            DiagnosticExport.pinnedTargetLine(TargetSignerStatus(approved, TargetSignerState.MISMATCH)),
        )
        val gone = DiagnosticExport.pinnedTargetLine(TargetSignerStatus(firstUse, TargetSignerState.NOT_INSTALLED))
        assertEquals("  com.termux: sha256 0123456789ab, pinned on first use, not installed", gone)
        assertFalse("The export carries a prefix, never the whole digest", gone.contains(digest))
    }

    @Test
    fun reportIncludesHealthRingAndCrashSources() {
        val root = listOf(Path.of("src/main/java"), Path.of("app/src/main/java")).first(Files::exists)
        val source = Files.readString(root.resolve("com/opentasker/core/diagnostics/DiagnosticExport.kt"))

        assertTrue(source.contains("EngineHealthReader.read"))
        assertTrue(source.contains("AppLogger.snapshot"))
        assertTrue(source.contains("CrashLogHandler.listCrashLogs"))
        assertTrue(source.contains("redactSensitive(entry.message)"))
    }
}
