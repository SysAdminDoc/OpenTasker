package com.opentasker.core.actions

import android.content.ContextWrapper
import com.opentasker.core.engine.ActionContext
import com.opentasker.core.engine.ActionResult
import com.opentasker.core.engine.VariableStore
import com.opentasker.core.registerCoreRuntime
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test

/** How a Multiple Variables Set fails, and the splitters Tasker lets carry a value after a name. */
class SetMultipleVariablesFailureTest {
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
    fun aSecretInALineThatIsNoNameStaysOutOfTheFailure() {
        val message = (run("names" to "%api_token,s3cr3t!") as ActionResult.Failure).message
        assertEquals("invalid variable name(s): <redacted>", message)
        assertFalse(message, "s3cr3t" in message)
    }

    @Test
    fun aSecretReadAsABareNameStaysOutOfTheLog() {
        // One line splits on its comma, so abc is a second name and is unset, as in Tasker.
        assertEquals(ActionResult.Success, run("names" to "%api_token,abc"))
        assertEquals(listOf("var.set_multiple -> unset 2 variable(s)"), logs)
    }

    @Test
    fun plainNamesStayInTheLog() {
        run("names" to "%token\n%user", "values" to "1\n2")
        assertEquals(listOf("var.set_multiple -> set %token, %user"), logs)
    }

    @Test
    fun noNamesFails() {
        assertEquals("no variable names given", (run("names" to " ") as ActionResult.Failure).message)
    }

    @Test
    fun aDashSplitsAValueOffItsNameAsInTasker() {
        assertEquals(ActionResult.Success, run("names" to "%a-1\n%b-2", "values_splitter" to "-"))
        assertEquals("1", variables.get("a"))
        assertEquals("2", variables.get("b"))
    }
}
