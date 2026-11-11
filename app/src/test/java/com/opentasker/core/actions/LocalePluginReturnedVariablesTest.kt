package com.opentasker.core.actions

import com.opentasker.core.engine.VariableStore
import com.opentasker.core.plugins.locale.LocalePluginContract
import com.opentasker.core.plugins.locale.LocalePluginResult
import com.opentasker.core.plugins.locale.LocalePluginReturnedVariables
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What a plugin step leaves behind in the task once its plugin answers, or doesn't. */
class LocalePluginReturnedVariablesTest {

    @Test
    fun aFinishedPluginsVariablesBecomeTaskLocals() {
        val variables = VariableStore()
        val result = LocalePluginResult(
            success = true,
            message = "Plugin p/.R finished.",
            resultCode = LocalePluginContract.RESULT_CODE_OK,
            variables = LocalePluginReturnedVariables(
                values = mapOf("stdout" to "hi", "result" to "0"),
                arrays = mapOf("lines" to listOf("a", "b")),
            ),
        )

        assertNull(LocalePluginActionPolicy.storeReturned(result, variables))

        assertEquals("hi", variables.get("stdout"))
        assertEquals("0", variables.get("result"))
        assertEquals(listOf("a", "b"), variables.getArrayItems("lines"))
        assertNull(variables.get("err"))
    }

    @Test
    fun aFailureThePluginReportedSetsErrToItsCode() {
        val variables = VariableStore()
        val result = LocalePluginResult(
            success = false,
            message = "Plugin p/.R failed (result code 12): nope.",
            resultCode = 12,
            variables = LocalePluginReturnedVariables(values = mapOf("errmsg" to "nope")),
        )

        LocalePluginActionPolicy.storeReturned(result, variables)

        assertEquals("12", variables.get("err"))
        assertEquals("nope", variables.get("errmsg"))
    }

    @Test
    fun thePluginsOwnErrWins() {
        val variables = VariableStore()
        val result = LocalePluginResult(
            success = false,
            message = "failed",
            resultCode = LocalePluginContract.RESULT_CODE_FAILED,
            variables = LocalePluginReturnedVariables(values = mapOf("err" to "127")),
        )

        LocalePluginActionPolicy.storeReturned(result, variables)

        assertEquals("127", variables.get("err"))
    }

    @Test
    fun aTimeoutOrAStepThatDidntWaitLeavesErrAlone() {
        val variables = VariableStore()
        LocalePluginActionPolicy.storeReturned(
            LocalePluginResult(false, "didn't finish", LocalePluginContract.RESULT_CODE_PENDING),
            variables,
        )
        LocalePluginActionPolicy.storeReturned(LocalePluginResult(false, "not deliverable"), variables)

        assertNull(variables.get("err"))
    }

    @Test
    fun leftOutVariablesAreNamedInTheRunLog() {
        val line = LocalePluginActionPolicy.storeReturned(
            LocalePluginResult(
                success = true,
                message = "finished",
                resultCode = LocalePluginContract.RESULT_CODE_OK,
                variables = LocalePluginReturnedVariables(refused = listOf("%Battery (not a local variable name)")),
            ),
            VariableStore(),
        )

        assertEquals("Left out plugin variables: %Battery (not a local variable name).", line)
    }

    @Test
    fun onlyAnUnansweredSettingKeepsTheUnconfirmedNote() {
        val sent = LocalePluginActionPolicy.settingTrace(LocalePluginResult(true, "Locale plugin setting dispatched to p/.R."))
        val plain = LocalePluginActionPolicy.settingTrace(
            LocalePluginResult(true, "Locale plugin setting dispatched to p/.R.", LocalePluginContract.RESULT_CODE_UNKNOWN),
        )
        val finished = LocalePluginActionPolicy.settingTrace(
            LocalePluginResult(true, "Plugin p/.R finished.", LocalePluginContract.RESULT_CODE_OK),
        )

        assertTrue(sent, "unconfirmed" in sent)
        assertTrue(plain, "unconfirmed" in plain)
        assertEquals("Plugin p/.R finished.", finished)
    }
}
