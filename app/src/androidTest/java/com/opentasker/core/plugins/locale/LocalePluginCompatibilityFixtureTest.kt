package com.opentasker.core.plugins.locale

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.opentasker.core.actions.ActionArgumentSensitivity
import com.opentasker.core.actions.registerActionMetadata
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A deterministic synthetic setting/condition plugin. The fixture never installs or contacts a
 * third-party package; it drives the same host protocol through injected resolver/transport seams.
 */
@RunWith(AndroidJUnit4::class)
class LocalePluginCompatibilityFixtureTest {
    private val packageName = "com.example.locale.fixture"
    private val settingActivity = ComponentName(packageName, "$packageName.SettingActivity")
    private val conditionActivity = ComponentName(packageName, "$packageName.ConditionActivity")
    private val settingReceiver = ComponentName(packageName, "$packageName.SettingReceiver")
    private val conditionReceiver = ComponentName(packageName, "$packageName.ConditionReceiver")

    private lateinit var transport: SyntheticLocalePlugin
    private lateinit var host: LocalePluginHost

    @Before
    fun setUp() {
        registerActionMetadata()
        transport = SyntheticLocalePlugin()
        host = LocalePluginHost(
            componentResolver = SyntheticLocalePluginResolver(
                packageName = packageName,
                settingActivity = settingActivity,
                conditionActivity = conditionActivity,
                settingReceiver = settingReceiver,
                conditionReceiver = conditionReceiver,
            ),
            transport = transport,
        )
    }

    @Test
    fun syntheticPluginCoversDiscoveryConfigurationFireQueryRequestAndRedaction() = runBlocking {
        val discovery = LocalePluginDiscovery(
            componentResolver = SyntheticLocalePluginResolver(
                packageName = packageName,
                settingActivity = settingActivity,
                conditionActivity = conditionActivity,
                settingReceiver = settingReceiver,
                conditionReceiver = conditionReceiver,
            ),
            packageMetadata = object : LocalePluginPackageMetadata {
                override fun label(packageName: String) = "Synthetic Locale Plugin"
                override fun requestedPermissions(packageName: String) = listOf("com.example.PERMISSION")
            },
        ).discover()
        assertEquals(1, discovery.size)
        assertEquals(packageName, discovery.single().packageName)
        assertTrue(discovery.single().supportsSettings)
        assertTrue(discovery.single().supportsConditions)
        assertEquals(listOf("com.example.PERMISSION"), discovery.single().requestedPermissions)

        val configResult = LocalePluginConfigurationResultParser.parse(
            Activity.RESULT_OK,
            Intent()
                .putExtra(
                    LocalePluginContract.EXTRA_BUNDLE,
                    Bundle().apply {
                        putString("mode", "quiet")
                        putString("token", "fixture-secret")
                    },
                )
                .putExtra(LocalePluginContract.EXTRA_BLURB, "Quiet mode"),
        )
        assertEquals("{\"mode\":\"quiet\",\"token\":\"fixture-secret\"}", configResult.bundleJson)
        assertEquals("Quiet mode", configResult.blurb)

        val edit = host.buildEditSettingIntent(packageName)
        assertTrue(edit.success)
        assertEquals(settingActivity, edit.intent?.component)

        val request = LocalePluginRequest(
            packageName = packageName,
            bundleJson = "{\"enabled\":true,\"token\":\"fixture-secret\"}",
            blurb = "Enable fixture",
        )
        assertTrue(host.fireSetting(request).success)
        assertEquals(LocalePluginContract.ACTION_FIRE_SETTING, transport.lastSettingIntent?.action)
        assertEquals("fixture-secret", transport.lastSettingValues["token"])

        val satisfied = host.queryCondition(request)
        assertEquals(LocalePluginConditionState.Satisfied, satisfied.state)
        assertEquals(LocalePluginContract.RESULT_CONDITION_SATISFIED, transport.lastQueryResultCode)
        assertEquals("satisfied", satisfied.state.serializedName)

        val requestQuery = Intent(LocalePluginContract.ACTION_REQUEST_QUERY)
            .putExtra(LocalePluginContract.EXTRA_STRING_ACTIVITY_CLASS_NAME, "$packageName.ConditionActivity")
            .putExtra(
                LocalePluginContract.EXTRA_BUNDLE,
                LocalePluginBundleCodec.toBundle(mapOf("token" to "fixture-secret")),
            )
        val event = LocalePluginRequestQueryEvents.buildEventFromIntent(requestQuery)
        assertEquals("locale_request_query", event?.metadata?.get("event"))
        assertEquals("$packageName.ConditionActivity", event?.metadata?.get("activityClass"))
        assertEquals("{\"token\":\"fixture-secret\"}", event?.metadata?.get("bundleJson"))

        assertEquals(
            ActionArgumentSensitivity.REDACTED,
            ActionArgumentSensitivity.maskValue(
                actionType = "plugin.locale.fire",
                argName = "bundleJson",
                value = request.bundleJson,
            ),
        )
        assertEquals(LocalePluginConditionState.Unsatisfied, LocalePluginConditionResultParser.parse(17, packageName).state)
        assertEquals(LocalePluginConditionState.Unknown, LocalePluginConditionResultParser.parse(18, packageName).state)
        assertEquals(LocalePluginConditionState.Unknown, LocalePluginConditionResultParser.parse(999, packageName).state)
    }

    @Test
    fun typedPluginSettingsSurviveConfigurationEditAndFire() = runBlocking {
        // The shape Termux:Tasker saves: an int version code, a boolean and a null argument string.
        val configResult = LocalePluginConfigurationResultParser.parse(
            Activity.RESULT_OK,
            Intent()
                .putExtra(
                    LocalePluginContract.EXTRA_BUNDLE,
                    Bundle().apply {
                        putInt("version", 1002)
                        putString("executable", "hello.sh")
                        putString("arguments", null)
                        putBoolean("terminal", false)
                    },
                )
                .putExtra(LocalePluginContract.EXTRA_BLURB, "hello.sh"),
        )
        assertEquals(
            "{\"arguments\":{\"null\":true},\"executable\":\"hello.sh\",\"terminal\":{\"boolean\":false},\"version\":{\"int\":1002}}",
            configResult.bundleJson,
        )

        val edit = host.configurationIntent(packageName, condition = false, configResult.bundleJson, configResult.blurb)
        assertTrue(edit.success)
        assertEquals(settingActivity, edit.intent?.component)
        val reopened = edit.intent?.getBundleExtra(LocalePluginContract.EXTRA_BUNDLE)
        assertEquals(1002, reopened?.getInt("version", -1))
        assertEquals("hello.sh", edit.intent?.getStringExtra(LocalePluginContract.EXTRA_BLURB))

        val recorder = RecordingLocalePlugin()
        val recordingHost = LocalePluginHost(
            componentResolver = SyntheticLocalePluginResolver(
                packageName = packageName,
                settingActivity = settingActivity,
                conditionActivity = conditionActivity,
                settingReceiver = settingReceiver,
                conditionReceiver = conditionReceiver,
            ),
            transport = recorder,
        )
        assertTrue(recordingHost.fireSetting(LocalePluginRequest(packageName, configResult.bundleJson, configResult.blurb)).success)
        val fired = recorder.lastSettingIntent?.getBundleExtra(LocalePluginContract.EXTRA_BUNDLE)
        assertEquals(1002, fired?.getInt("version", -1))
        assertEquals(false, fired?.getBoolean("terminal", true))
        assertTrue(fired?.containsKey("arguments") == true)
        assertEquals(null, fired?.getString("arguments"))
        assertEquals("hello.sh", fired?.getString("executable"))
    }

    private class SyntheticLocalePluginResolver(
        private val packageName: String,
        private val settingActivity: ComponentName,
        private val conditionActivity: ComponentName,
        private val settingReceiver: ComponentName,
        private val conditionReceiver: ComponentName,
    ) : LocalePluginComponentResolver {
        override fun editActivities(packageName: String, action: String): List<ComponentName> = when (action) {
            LocalePluginContract.ACTION_EDIT_SETTING -> listOf(settingActivity)
            LocalePluginContract.ACTION_EDIT_CONDITION -> listOf(conditionActivity)
            else -> emptyList()
        }

        override fun broadcastReceivers(packageName: String, action: String): List<ComponentName> = when (action) {
            LocalePluginContract.ACTION_FIRE_SETTING -> listOf(settingReceiver)
            LocalePluginContract.ACTION_QUERY_CONDITION -> listOf(conditionReceiver)
            else -> emptyList()
        }

        override fun activityPackages(action: String): Set<String> = when (action) {
            LocalePluginContract.ACTION_EDIT_SETTING,
            LocalePluginContract.ACTION_EDIT_CONDITION -> setOf(packageName)
            else -> emptySet()
        }

        override fun broadcastReceiverPermissions(action: String): Map<String, List<String>> = when (action) {
            LocalePluginContract.ACTION_FIRE_SETTING,
            LocalePluginContract.ACTION_QUERY_CONDITION -> mapOf(packageName to listOf("com.example.PERMISSION"))
            else -> emptyMap()
        }
    }

    private class SyntheticLocalePlugin : LocalePluginTransport {
        var lastSettingIntent: Intent? = null
        var lastSettingValues: Map<String, String> = emptyMap()
        var lastQueryResultCode: Int = Activity.RESULT_CANCELED

        override suspend fun sendSetting(intent: Intent) {
            lastSettingIntent = intent
            lastSettingValues = intent.getBundleExtra(LocalePluginContract.EXTRA_BUNDLE)
                ?.let(LocalePluginBundleCodec::fromBundle)
                .orEmpty()
        }

        override suspend fun queryCondition(intent: Intent): Int {
            val values = intent.getBundleExtra(LocalePluginContract.EXTRA_BUNDLE)
                ?.let(LocalePluginBundleCodec::fromBundle)
                .orEmpty()
            lastQueryResultCode = if (values["enabled"] == "true") {
                LocalePluginContract.RESULT_CONDITION_SATISFIED
            } else {
                LocalePluginContract.RESULT_CONDITION_UNSATISFIED
            }
            return lastQueryResultCode
        }
    }

    /** Keeps the raw intent, so a test can read the exact types a plugin would receive. */
    private class RecordingLocalePlugin : LocalePluginTransport {
        var lastSettingIntent: Intent? = null

        override suspend fun sendSetting(intent: Intent) {
            lastSettingIntent = intent
        }

        override suspend fun queryCondition(intent: Intent): Int = LocalePluginContract.RESULT_CONDITION_UNKNOWN
    }
}
