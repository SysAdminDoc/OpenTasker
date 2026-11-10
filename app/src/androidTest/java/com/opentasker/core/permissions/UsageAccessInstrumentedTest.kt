package com.opentasker.core.permissions

import android.Manifest
import android.app.usage.UsageStatsManager
import android.content.pm.PackageManager
import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The usage-access read against the phone's own app-op and permission. Granting
 * PACKAGE_USAGE_STATS over adb leaves the op at default, and UsageStatsManager then answers from
 * the grant, so the check has to agree or app triggers pause while access works. Each test first
 * asks the platform itself, so a pass can't come from a phone that ignores the setup.
 */
class UsageAccessInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val pkg = context.packageName
    private var originalMode = "default"
    private var originallyGranted = false

    @Before
    fun remember() {
        originalMode = shell("appops get $pkg GET_USAGE_STATS").lineSequence()
            // A "Uid mode:" line can come first, and appops set below changes the package mode.
            .filterNot { it.trimStart().startsWith("Uid mode:") }
            .firstNotNullOfOrNull { Regex("GET_USAGE_STATS: (\\w+)").find(it)?.groupValues?.get(1) } ?: "default"
        originallyGranted = context.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED
    }

    @After
    fun restore() {
        shell("pm ${if (originallyGranted) "grant" else "revoke"} $pkg $PERMISSION")
        shell("appops set $pkg GET_USAGE_STATS $originalMode")
    }

    @Test
    fun anAdbGrantWithTheOpAtDefaultCountsAsAccess() {
        shell("appops set $pkg GET_USAGE_STATS default")
        shell("pm grant $pkg $PERMISSION")

        assertTrue("the platform should serve usage stats for an adb grant", platformServesUsageStats())
        assertTrue(UsageAccess.hasUsageStatsAccess(context))
    }

    @Test
    fun switchingTheOpOffWinsOverTheGrant() {
        shell("pm grant $pkg $PERMISSION")
        shell("appops set $pkg GET_USAGE_STATS ignore")

        assertFalse("the platform should refuse usage stats with the op off", platformServesUsageStats())
        assertFalse(UsageAccess.hasUsageStatsAccess(context))
    }

    // Any phone in use today has daily stats; without access the query comes back empty.
    private fun platformServesUsageStats(): Boolean {
        val now = System.currentTimeMillis()
        return context.getSystemService(UsageStatsManager::class.java)
            .queryUsageStats(UsageStatsManager.INTERVAL_DAILY, now - DAY_MS, now)
            .isNotEmpty()
    }

    private fun shell(command: String): String {
        val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        // The command completes only once its output reaches EOF, so drain it before reading state.
        return ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes().decodeToString() }
    }

    private companion object {
        const val PERMISSION = Manifest.permission.PACKAGE_USAGE_STATS
        const val DAY_MS = 24L * 60 * 60 * 1000
    }
}
