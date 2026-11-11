package com.opentasker.core.plugins.locale

import android.content.ComponentName
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A plugin step goes by package and action, and its dispatch refuses a package with no receiver for
 * that action or more than one. The "Needs setup" pill asks the same question, so a package whose
 * update left two setting receivers gets the pill instead of passing until the step runs.
 */
@RunWith(AndroidJUnit4::class)
class LocalePluginReceiverPillTest {
    private val pkg = "com.example.locale.pill"

    private fun receiver(name: String) = ComponentName(pkg, "$pkg.$name")

    private fun resolver(settings: List<ComponentName>, conditions: List<ComponentName>) = object : LocalePluginComponentResolver {
        override fun editActivities(packageName: String, action: String) = emptyList<ComponentName>()
        override fun broadcastReceivers(packageName: String, action: String) = when (action) {
            LocalePluginContract.ACTION_FIRE_SETTING -> settings
            LocalePluginContract.ACTION_QUERY_CONDITION -> conditions
            else -> emptyList()
        }
        override fun activityPackages(action: String) = emptySet<String>()
        override fun broadcastReceiverPermissions(action: String) = emptyMap<String, List<String>>()
    }

    @Test
    fun aSettingAndAConditionReceiverInOnePackageBothResolve() {
        val both = resolver(listOf(receiver("Fire")), listOf(receiver("Query")))

        assertTrue(localePluginReceiverResolves(both, pkg, condition = false))
        assertTrue(localePluginReceiverResolves(both, pkg, condition = true))
    }

    @Test
    fun twoReceiversForTheStepsActionGetThePill() {
        val twoSettings = resolver(listOf(receiver("Fire"), receiver("FireOld")), listOf(receiver("Query")))

        assertFalse(localePluginReceiverResolves(twoSettings, pkg, condition = false))
        assertTrue(localePluginReceiverResolves(twoSettings, pkg, condition = true))
    }

    @Test
    fun noReceiverOrABadPackageNameGetsThePill() {
        val none = resolver(emptyList(), emptyList())

        assertFalse(localePluginReceiverResolves(none, pkg, condition = false))
        assertFalse(localePluginReceiverResolves(none, pkg, condition = true))
        assertFalse(localePluginReceiverResolves(resolver(listOf(receiver("Fire")), emptyList()), "not a package", condition = false))
    }

    @Test
    fun theStepRefusesWhatThePillFlags() = runBlocking {
        var sent = 0
        val host = LocalePluginHost(
            componentResolver = resolver(listOf(receiver("Fire"), receiver("FireOld")), emptyList()),
            transport = object : LocalePluginTransport {
                override suspend fun sendSetting(intent: Intent) {
                    sent++
                }

                override suspend fun queryCondition(intent: Intent) = LocalePluginContract.RESULT_CONDITION_UNKNOWN
            },
        )

        val result = host.fireSetting(LocalePluginRequest(packageName = pkg, bundleJson = "{}"))

        assertFalse(result.success)
        assertEquals(0, sent)
    }
}
