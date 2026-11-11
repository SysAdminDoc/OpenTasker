package com.opentasker.core.plugins.locale

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The real ordered-broadcast wait, against [SyncSettingFixtureReceiver] in this test package, which
 * the package manager finds and starts like any installed plugin.
 */
@RunWith(AndroidJUnit4::class)
class LocalePluginSyncTransportTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val host = LocalePluginHost(instrumentation.targetContext)
    private val plugin = instrumentation.context.packageName

    private fun fire(mode: String, timeoutMs: Long = 10_000L) = runBlocking {
        host.fireSetting(LocalePluginRequest(packageName = plugin, bundleJson = """{"mode":"$mode"}""", timeoutMs = timeoutMs))
    }

    @Test
    fun aPluginThatAnswersAtOnceReturnsItsVariables() {
        val result = fire("sync")

        assertTrue(result.message, result.success)
        assertEquals(LocalePluginContract.RESULT_CODE_OK, result.resultCode)
        assertEquals("sync", result.variables.values["out"])
    }

    @Test
    fun aPendingPluginIsWaitedForAndItsCompletionRead() {
        val result = fire("pending")

        assertTrue(result.message, result.success)
        assertEquals(LocalePluginContract.RESULT_CODE_OK, result.resultCode)
        assertEquals("later", result.variables.values["out"])
        assertEquals(listOf("a", "b"), result.variables.arrays["list"])
    }

    @Test
    fun aFailedPluginFailsTheStepWithItsMessage() {
        val result = fire("failed")

        assertFalse(result.success)
        assertEquals(LocalePluginContract.RESULT_CODE_FAILED, result.resultCode)
        assertTrue(result.message, "disk full" in result.message)
    }

    @Test
    fun aCompletionSentBeforeTheBroadcastIsReleasedStillCounts() {
        val result = fire("early")

        assertTrue(result.message, result.success)
        assertEquals("early", result.variables.values["out"])
    }

    @Test
    fun aPluginThatNeverFinishesTimesOutOnTime() {
        val started = SystemClock.elapsedRealtime()
        val result = fire("silent", timeoutMs = 1_500L)
        val waited = SystemClock.elapsedRealtime() - started

        assertFalse(result.success)
        assertEquals(LocalePluginContract.RESULT_CODE_PENDING, result.resultCode)
        assertTrue("waited $waited ms", waited in 1_400L..8_000L)
    }

    @Test
    fun noWaitSendsAndGoesOn() {
        val result = fire("silent", timeoutMs = 0L)

        assertTrue(result.message, result.success)
        assertNull(result.resultCode)
    }
}
