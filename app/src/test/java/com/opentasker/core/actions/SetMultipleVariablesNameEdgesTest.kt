package com.opentasker.core.actions

import android.content.ContextWrapper
import com.opentasker.core.engine.ActionContext
import com.opentasker.core.engine.ActionResult
import com.opentasker.core.engine.VariableStore
import com.opentasker.core.registerCoreRuntime
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Names a Multiple Variables Set can only read when it runs: one left blank, and one built from a secret. */
class SetMultipleVariablesNameEdgesTest {
    private val variables = VariableStore()
    private val logs = mutableListOf<String>()

    @Before
    fun setUp() {
        registerCoreRuntime()
        registerActionMetadata()
    }

    private fun run(vararg args: Pair<String, String>): ActionResult = runBlocking {
        SetMultipleVariablesAction().run(ActionContext(ContextWrapper(null), variables, logger = { logs += it }), mapOf(*args))
    }

    @Test
    fun aValueWithNoNameBeforeItIsReportedAsABlankName() {
        val result = run("names" to "%a,1\n,2")
        assertEquals("invalid variable name(s): (blank name)", (result as ActionResult.Failure).message)
        assertEquals("1", variables.get("a"))
    }

    @Test
    fun aNameBuiltFromASecretIsCountedAndItsValueTreatedAsSecret() {
        variables.set("Pin", "1234", sensitive = true)
        assertEquals(ActionResult.Success, run("names" to "%cache_%Pin", "values" to "warm"))
        assertEquals("warm", variables.get("cache_1234"))
        assertTrue(variables.isSensitive("cache_1234"))
        assertEquals(listOf("var.set_multiple -> set 1 variable(s)"), logs)
    }
}
