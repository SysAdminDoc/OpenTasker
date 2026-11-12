package com.opentasker.core.actions

import android.content.ContextWrapper
import com.opentasker.core.engine.ActionContext
import com.opentasker.core.engine.ActionResult
import com.opentasker.core.engine.VariableStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class SetVariableLogLineTest {

    private fun logLine(name: String, value: String, variables: VariableStore = VariableStore()): String = runBlocking {
        val logs = mutableListOf<String>()
        val context = ActionContext(ContextWrapper(null), variables, logger = logs::add)
        assertEquals(ActionResult.Success, SetVariableAction().run(context, mapOf("name" to name, "value" to value)))
        logs.single()
    }

    @Test
    fun aNameTypedWithItsPercentSignLogsLikeABareOne() {
        assertEquals("Set \$color = red", logLine("%color", "red"))
        assertEquals("Set \$color = red", logLine("color", "red"))
    }

    // A path only runs written bare; `%config.theme` fails as an invalid path before anything is logged.
    @Test
    fun aPathLogsWithoutAPercentSign() {
        assertEquals("Set path \$config.theme = dark", logLine("config.theme", "dark", VariableStore().apply { set("config", "{}") }))
    }

    @Test
    fun aSecretNamedValueStaysHidden() {
        val hidden = ActionArgumentSensitivity.REDACTED
        assertEquals("Set \$api_token = $hidden", logLine("%api_token", "hunter2"))
        assertEquals("Set \$api_token = $hidden", logLine("api_token", "hunter2"))
        assertEquals("Set path \$config.api_key = $hidden", logLine("config.api_key", "hunter2", VariableStore().apply { set("config", "{}") }))
    }
}
