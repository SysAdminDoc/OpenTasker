package com.opentasker.core.contexts

import com.opentasker.core.engine.SyntheticTriggerSimulator
import com.opentasker.core.model.ContextSpec
import com.opentasker.core.model.ContextType
import com.opentasker.core.model.Profile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locale and Tasker send the saved blurb with every condition query, and primitive FTPd picks its
 * condition by the blurb alone ("server running?"), so a profile's Plugin context has to send it
 * too, and a result is only this context's when it answered the same blurb.
 */
class PluginConditionBlurbTest {

    private val ftpRunning = ContextSpec(
        ContextType.PLUGIN,
        mapOf("package" to "org.primftpd", "bundleJson" to """{"org.primftpd.remotecontrol.extra.INT_VERSION_CODE":{"int":71}}""", "blurb" to "server running?"),
    )

    private fun result(blurb: String?, state: String = "satisfied") = ContextEvent(
        "plugin",
        true,
        mapOf("package" to "org.primftpd", "bundleJson" to ftpRunning.config.getValue("bundleJson"), "state" to state) +
            listOfNotNull(blurb?.let { "blurb" to it }),
    )

    @Test
    fun theProfilesQueryCarriesTheSavedBlurb() {
        val subs = pluginConditionSubscriptions(listOf(Profile(name = "FTP", contexts = listOf(ftpRunning), enterTaskId = 1)))

        assertEquals("server running?", subs.single().blurb)
        assertEquals("org.primftpd", subs.single().packageName)
    }

    @Test
    fun twoContextsThatDifferOnlyByBlurbAreTwoQueries() {
        val other = ftpRunning.copy(config = ftpRunning.config + ("blurb" to "server stopped?"))

        val subs = pluginConditionSubscriptions(listOf(Profile(name = "FTP", contexts = listOf(ftpRunning, other), enterTaskId = 1)))

        assertEquals(setOf("server running?", "server stopped?"), subs.map { it.blurb }.toSet())
    }

    @Test
    fun aResultForTheSameBlurbTurnsTheContextOn() {
        val satisfied = result("server running?")

        assertTrue(ContextMatchEvaluator.pluginEventAddressesSpec(ftpRunning, satisfied))
        assertTrue(ContextMatchEvaluator.matches(ftpRunning, satisfied))
        assertFalse(ContextMatchEvaluator.matches(ftpRunning, result("server running?", state = "unsatisfied")))
    }

    @Test
    fun aResultForAnotherBlurbIsSomeoneElsesAnswer() {
        val otherQuestion = result("server stopped?")

        assertFalse(ContextMatchEvaluator.pluginEventAddressesSpec(ftpRunning, otherQuestion))
        assertFalse(ContextMatchEvaluator.matches(ftpRunning, otherQuestion))
        assertFalse(ContextMatchEvaluator.pluginEventAddressesSpec(ftpRunning, result(blurb = null)))
    }

    @Test
    fun aContextWithoutABlurbStillTakesAResultWithoutOne() {
        val plain = ContextSpec(ContextType.PLUGIN, mapOf("package" to "org.primftpd", "bundleJson" to ftpRunning.config.getValue("bundleJson")))

        assertTrue(ContextMatchEvaluator.matches(plain, result(blurb = null)))
        assertEquals("", pluginConditionSubscriptions(listOf(Profile(name = "FTP", contexts = listOf(plain), enterTaskId = 1))).single().blurb)
    }

    @Test
    fun aLongBlurbIsSentAndMatchedAtTheLengthTheEditorKeeps() {
        val long = ftpRunning.copy(config = ftpRunning.config + ("blurb" to "x".repeat(130)))

        val sent = pluginConditionSubscriptions(listOf(Profile(name = "FTP", contexts = listOf(long), enterTaskId = 1))).single().blurb

        assertEquals(120, sent.length)
        assertTrue(ContextMatchEvaluator.matches(long, result(sent)))
    }

    @Test
    fun theSimulatedResultAnswersTheSameBlurb() {
        val simulated = SyntheticTriggerSimulator.template(ftpRunning).event

        assertEquals("server running?", simulated.metadata["blurb"])
        assertTrue(ContextMatchEvaluator.matches(ftpRunning, simulated))
    }

    @Test
    fun aRequestQueryReachesOnlyThePluginWhoseConditionScreenItNames() {
        val screens = mapOf(
            "org.primftpd" to listOf("org.primftpd.remotecontrol.TaskerEditConditionActivity"),
            "com.example.other" to listOf("com.example.other.EditCondition"),
        )

        val asking = packagesAskingForQuery(
            "org.primftpd.remotecontrol.TaskerEditConditionActivity",
            listOf("org.primftpd", "com.example.other", "com.example.gone"),
        ) { screens[it].orEmpty() }

        assertEquals(setOf("org.primftpd"), asking)
        assertEquals(emptySet<String>(), packagesAskingForQuery("org.primftpd.Unknown", screens.keys) { screens[it].orEmpty() })
    }
}
