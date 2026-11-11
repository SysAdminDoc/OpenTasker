package com.opentasker.core.plugins.locale

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tasker's synchronous setting protocol, as TaskerPlugin.java defines it: the result codes a plugin
 * answers with, the `%name` variables it sends back, and the wait it asks for on its settings screen.
 */
class LocalePluginSyncProtocolTest {

    @Test
    fun textAndListsComeBackAsLocalsAndArrays() {
        val returned = LocalePluginReturnedVariables.from(
            linkedMapOf(
                "%stdout" to "hello",
                "%errmsg" to "",
                "%lines" to arrayOf("a", null, "c"),
                "%words" to listOf("x", "y"),
            ),
        )

        assertEquals(mapOf("stdout" to "hello", "errmsg" to ""), returned.values)
        assertEquals(mapOf("lines" to listOf("a", "", "c"), "words" to listOf("x", "y")), returned.arrays)
        assertTrue(returned.refused.isEmpty())
    }

    @Test
    fun aGlobalANameWithoutItsPercentAndOtherTypesAreLeftOutAndNamed() {
        val returned = LocalePluginReturnedVariables.from(
            linkedMapOf(
                "%Battery" to "50",
                "stdout" to "no percent",
                "%count" to 3,
                "%mixed" to listOf("a", 1),
                "%ints" to arrayOf(1, 2),
                "%ok" to "kept",
            ),
        )

        assertEquals(mapOf("ok" to "kept"), returned.values)
        assertTrue(returned.arrays.isEmpty())
        assertEquals(
            listOf(
                "%Battery (not a local variable name)",
                "stdout (not a local variable name)",
                "%count (not text or a list of text)",
                "%mixed (not text or a list of text)",
                "%ints (not text or a list of text)",
            ),
            returned.refused,
        )
    }

    @Test
    fun aPluginCantSendMoreThanTheCaps() {
        val many = (0..LocalePluginContract.MAX_RETURNED_VARIABLES).associate { "%v$it" to "x" }
        val tooMany = LocalePluginReturnedVariables.from(many)
        assertEquals(LocalePluginContract.MAX_RETURNED_VARIABLES, tooMany.values.size)
        assertEquals(listOf("%v${LocalePluginContract.MAX_RETURNED_VARIABLES} (too many variables)"), tooMany.refused)

        val half = "y".repeat(LocalePluginContract.MAX_RETURNED_TEXT_CHARS / 2)
        val tooLong = LocalePluginReturnedVariables.from(linkedMapOf("%a" to half, "%b" to half, "%c" to "z"))
        assertEquals(setOf("a", "b"), tooLong.values.keys)
        assertEquals(listOf("%c (too much text)"), tooLong.refused)
    }

    @Test
    fun aRefusedNameIsShortenedInTheRunLog() {
        val returned = LocalePluginReturnedVariables.from(mapOf("x".repeat(500) to "v"))
        assertEquals("${"x".repeat(64)} (not a local variable name)", returned.refused.single())
    }

    @Test
    fun theRequestedWaitStaysWithinWhatAStepAllows() {
        assertEquals(0L, LocalePluginContract.waitForRequestedTimeout(0))
        assertEquals(12_000L, LocalePluginContract.waitForRequestedTimeout(12_000))
        assertEquals(
            LocalePluginContract.MAX_SETTING_WAIT_MS,
            LocalePluginContract.waitForRequestedTimeout(LocalePluginContract.REQUESTED_TIMEOUT_NEVER),
        )
        assertNull(LocalePluginContract.waitForRequestedTimeout(-1))
    }

    @Test
    fun theHostSaysItReturnsVariablesReplacesThemAndWaits() {
        val flags = LocalePluginContract.HOST_CAPABILITIES
        assertEquals(2, flags and 2)
        assertEquals(8, flags and 8)
        assertEquals(32, flags and 32)
        // Condition variables and relevant-variable hints aren't offered.
        assertEquals(0, flags and 4)
        assertEquals(0, flags and 16)
    }

    @Test
    fun resultCodesMapToTheStepOutcome() {
        val target = "com.example/.Fire"
        val ok = LocalePluginSettingOutcome.of(LocalePluginSettingReply(LocalePluginContract.RESULT_CODE_OK), target, 5_000)
        assertTrue(ok.success)
        assertEquals("Plugin $target finished.", ok.message)
        assertEquals(LocalePluginContract.RESULT_CODE_OK, ok.resultCode)

        val minor = LocalePluginSettingOutcome.of(LocalePluginSettingReply(LocalePluginContract.RESULT_CODE_OK_MINOR_FAILURES), target, 5_000)
        assertTrue(minor.success)
        assertTrue(minor.message, "minor failures" in minor.message)

        val plain = LocalePluginSettingOutcome.of(LocalePluginSettingReply(LocalePluginContract.RESULT_CODE_UNKNOWN), target, 5_000)
        assertTrue(plain.success)
        assertEquals("Locale plugin setting dispatched to $target.", plain.message)

        val failed = LocalePluginSettingOutcome.of(LocalePluginSettingReply(LocalePluginContract.RESULT_CODE_FAILED), target, 5_000)
        assertFalse(failed.success)
        assertEquals("Plugin $target failed (result code 2).", failed.message)

        val own = LocalePluginSettingOutcome.of(LocalePluginSettingReply(10), target, 5_000)
        assertFalse(own.success)
        assertEquals(10, own.resultCode)
    }

    @Test
    fun aFailureCarriesThePluginsErrorMessageAndVariables() {
        val variables = LocalePluginReturnedVariables(values = mapOf("errmsg" to "No such file", "stderr" to "boom"))
        val failed = LocalePluginSettingOutcome.of(
            LocalePluginSettingReply(LocalePluginContract.RESULT_CODE_FAILED, variables),
            "com.termux.tasker/.FireReceiver",
            5_000,
        )

        assertFalse(failed.success)
        assertEquals("Plugin com.termux.tasker/.FireReceiver failed (result code 2): No such file.", failed.message)
        assertEquals(variables, failed.variables)

        val long = LocalePluginSettingOutcome.of(
            LocalePluginSettingReply(LocalePluginContract.RESULT_CODE_FAILED, LocalePluginReturnedVariables(values = mapOf("errmsg" to "e".repeat(2_000)))),
            "p/.R",
            5_000,
        )
        assertTrue(long.message.length < 600)
    }

    @Test
    fun noAnswerWithinTheWaitIsATimeout() {
        val timedOut = LocalePluginSettingOutcome.of(null, "com.example/.Fire", 7_000)

        assertFalse(timedOut.success)
        assertEquals("Plugin com.example/.Fire didn't finish within 7000 ms.", timedOut.message)
        assertEquals(LocalePluginContract.RESULT_CODE_PENDING, timedOut.resultCode)
        assertTrue(timedOut.variables.values.isEmpty())
    }

    @Test
    fun anAnswerThatCantBeReadFailsWithNothingReturned() {
        val reply = LocalePluginCompletion.reply({ throw IllegalStateException("bad parcel") }, { null })

        assertEquals(LocalePluginContract.RESULT_CODE_FAILED, reply.resultCode)
        assertTrue(reply.variables.values.isEmpty())
        assertTrue(reply.variables.refused.single(), "couldn't be read (IllegalStateException)" in reply.variables.refused.single())

        val bare = LocalePluginCompletion.reply({ LocalePluginContract.RESULT_CODE_OK }, { null })
        assertEquals(LocalePluginSettingReply(LocalePluginContract.RESULT_CODE_OK), bare)
    }

    @Test
    fun eachWaitGetsItsOwnUnguessableToken() {
        val first = LocalePluginCompletion.newToken()
        val second = LocalePluginCompletion.newToken()

        assertTrue(first, Regex("[0-9a-f]{32}").matches(first))
        assertNotEquals(first, second)
    }
}
