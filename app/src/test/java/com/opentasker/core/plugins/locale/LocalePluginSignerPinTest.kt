package com.opentasker.core.plugins.locale

import android.content.ComponentName
import android.content.Intent
import com.opentasker.core.apps.InstalledSigners
import com.opentasker.core.apps.PackageSignatureSource
import com.opentasker.core.apps.TargetSignerGuard
import com.opentasker.core.apps.TargetSignerPin
import com.opentasker.core.apps.TargetSignerPinSource
import com.opentasker.core.apps.TargetSignerPins
import com.opentasker.core.apps.signerMismatchMessage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Locale dispatch sites refuse a plugin whose signing certificate no longer matches its pin,
 * before any bundle, intent or broadcast exists, so the plugin receives nothing.
 */
class LocalePluginSignerPinTest {
    private val pkg = "com.example.plugin"
    private val transport = RecordingTransport()
    private val resolver = RecordingResolver()
    private val host = LocalePluginHost(
        componentResolver = resolver,
        transport = transport,
        signerGuard = mismatchedGuard(),
    )

    @Test
    fun fireSettingSendsNothingToAMismatchedPlugin() = runBlocking {
        val result = host.fireSetting(LocalePluginRequest(packageName = pkg, bundleJson = """{"k":"v"}"""))

        assertFalse(result.success)
        assertEquals(signerMismatchMessage(pkg), result.message)
        assertTrue(result.message, result.message.contains(pkg))
        assertEquals("No FIRE_SETTING may leave", 0, transport.settingsSent)
        assertEquals("Not even a receiver lookup", 0, resolver.lookups)
    }

    @Test
    fun queryConditionSendsNothingToAMismatchedPlugin() = runBlocking {
        val result = host.queryCondition(LocalePluginRequest(packageName = pkg))

        // Unknown, not the cache's "treat unknown as unsatisfied", which would hide the refusal.
        assertEquals(LocalePluginConditionState.Unknown, result.state)
        assertEquals(signerMismatchMessage(pkg), result.message)
        assertEquals("No QUERY_CONDITION may leave", 0, transport.queriesSent)
        assertEquals("Not even a receiver lookup", 0, resolver.lookups)
    }

    private fun mismatchedGuard(): TargetSignerGuard {
        val pins = object : TargetSignerPins {
            private val pin = TargetSignerPin(pkg, listOf("a".repeat(64)), TargetSignerPinSource.APPROVED, 1L)
            override fun get(packageName: String): TargetSignerPin? = pin.takeIf { packageName == pkg }
            override fun put(pin: TargetSignerPin) = error("A refusal must not change the pin")
            override fun remove(packageName: String) = error("A refusal must not remove the pin")
            override fun all(): List<TargetSignerPin> = listOf(pin)
        }
        val installed = PackageSignatureSource { packageName ->
            InstalledSigners(current = listOf("b".repeat(64))).takeIf { packageName == pkg }
        }
        return TargetSignerGuard(installed, pins)
    }

    private class RecordingTransport : LocalePluginTransport {
        var settingsSent = 0
        var queriesSent = 0

        override suspend fun sendSetting(intent: Intent) {
            settingsSent++
        }

        override suspend fun queryCondition(intent: Intent): Int {
            queriesSent++
            return LocalePluginContract.RESULT_CONDITION_SATISFIED
        }
    }

    private class RecordingResolver : LocalePluginComponentResolver {
        var lookups = 0

        override fun editActivities(packageName: String, action: String): List<ComponentName> {
            lookups++
            return emptyList()
        }

        override fun broadcastReceivers(packageName: String, action: String): List<ComponentName> {
            lookups++
            return emptyList()
        }

        override fun activityPackages(action: String): Set<String> = emptySet()

        override fun broadcastReceiverPermissions(action: String): Map<String, List<String>> = emptyMap()
    }
}
