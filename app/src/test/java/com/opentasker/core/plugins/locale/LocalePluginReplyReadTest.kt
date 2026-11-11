package com.opentasker.core.plugins.locale

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A plugin's answer is read in two parts. A result code that can't be read fails the step, but
 * variables that can't be read keep the code the plugin sent, and one value that can't be read is
 * refused by key while the rest are kept.
 */
class LocalePluginReplyReadTest {
    @Test
    fun variablesThatCantBeReadKeepThePluginsCode() {
        val reply = LocalePluginCompletion.reply({ LocalePluginContract.RESULT_CODE_OK }, { throw IllegalStateException("bad parcel") })

        assertEquals(LocalePluginContract.RESULT_CODE_OK, reply.resultCode)
        assertEquals(emptyMap<String, String>(), reply.variables.values)
        assertEquals(listOf("the plugin's variables couldn't be read (IllegalStateException)"), reply.variables.refused)
    }

    @Test
    fun variablesNestedDeepEnoughToRunOutOfStackKeepThePluginsCode() {
        val reply = LocalePluginCompletion.reply({ LocalePluginContract.RESULT_CODE_OK }, { throw StackOverflowError() })

        assertEquals(LocalePluginContract.RESULT_CODE_OK, reply.resultCode)
        assertEquals(listOf("the plugin's variables couldn't be read (StackOverflowError)"), reply.variables.refused)
    }

    @Test
    fun aCodeThatRunsOutOfStackFailsTheStepWithoutACrash() {
        val reply = LocalePluginCompletion.reply({ throw StackOverflowError() }, { null })

        assertEquals(LocalePluginContract.RESULT_CODE_FAILED, reply.resultCode)
        assertEquals(listOf("the plugin's answer couldn't be read (StackOverflowError)"), reply.variables.refused)
    }

    @Test
    fun oneValueThatCantBeReadIsRefusedByKey() {
        val variables = LocalePluginReturnedVariables.from(
            linkedMapOf(
                "%out" to "hello",
                "%planted" to LocalePluginReturnedVariables.Unreadable,
                "%list" to arrayListOf("a"),
            ),
        )

        assertEquals(mapOf("out" to "hello"), variables.values)
        assertEquals(mapOf("list" to listOf("a")), variables.arrays)
        assertEquals(listOf("%planted (couldn't be read)"), variables.refused)
    }
}
