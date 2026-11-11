package com.opentasker.core.engine

import android.content.ContextWrapper
import com.opentasker.core.actions.HttpGetAction
import com.opentasker.core.actions.HttpRequestAction
import com.opentasker.core.actions.LineListArguments
import com.opentasker.core.actions.registerActionMetadata
import com.opentasker.core.model.ActionSpec
import com.opentasker.core.model.Task
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * An HTTP request's headers and query are one entry per line. A variable holding a line break must
 * not add a header like Authorization, or a query parameter, that the step never listed.
 */
class HttpLineListExpansionTest {
    private val received = mutableListOf<Pair<String, Map<String, String>>>()

    @Before
    fun setUp() {
        registerActionMetadata()
        received.clear()
        listOf(HttpRequestAction.ID, "http.get").forEach { actionId ->
            ActionRegistry.register(object : Action {
                override val id = actionId
                override val category = ActionCategory.NET
                override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
                    received += actionId to args
                    return ActionResult.Success
                }
            })
        }
    }

    @After
    fun tearDown() {
        ActionRegistry.register(HttpRequestAction())
        ActionRegistry.register(HttpGetAction())
    }

    private fun request(type: String, args: Map<String, String>) = Task(
        name = "Post status",
        actions = listOf(ActionSpec(type = type, args = mapOf("url" to "https://example.test/status") + args)),
    )

    private fun run(type: String, args: Map<String, String>, variables: Map<String, String>): TaskRunReport = runBlocking {
        val store = VariableStore().apply { variables.forEach { (name, value) -> set(name, value) } }
        TaskRunner(ActionContext(ContextWrapper(null), store)).run(request(type, args))
    }

    private fun preflight(type: String, args: Map<String, String>, variables: Map<String, String>): PreflightStep =
        PreflightRunner.preflightTask(
            task = request(type, args),
            inputs = PreflightInputs(globalVariables = variables),
        ).tasks.single().steps.single()

    @Test
    fun aRunStopsTheStepWhenAVariableAddsAHeader() {
        val report = run(
            HttpRequestAction.ID,
            mapOf("headers" to "X-Title: %NTITLE"),
            mapOf("NTITLE" to "hello\nAuthorization: Bearer stolen"),
        )

        assertTrue(received.isEmpty())
        assertFalse(report.success)
        assertEquals(LineListArguments.HEADER_LINE_BREAK_FAILURE, (report.results.single() as ActionResult.Failure).message)
    }

    @Test
    fun aRunStopsTheStepWhenATemplateAddsAQueryParameter() {
        val report = run("http.get", mapOf("query" to "q={{ global.SEARCH }}"), mapOf("SEARCH" to "cats\r\nadmin=true"))

        assertTrue(received.isEmpty())
        assertEquals(LineListArguments.QUERY_LINE_BREAK_FAILURE, (report.results.single() as ActionResult.Failure).message)
    }

    @Test
    fun aRunExpandsEachWrittenHeaderAndQueryLineOnItsOwn() {
        val report = run(
            HttpRequestAction.ID,
            mapOf(
                "headers" to "X-Title: %NTITLE\nAccept: application/json",
                "query" to "q=%SEARCH\r\npage=%PAGE",
            ),
            mapOf("NTITLE" to "Hello there", "SEARCH" to "cats", "PAGE" to "2"),
        )

        assertTrue(report.success)
        val (type, args) = received.single()
        assertEquals(HttpRequestAction.ID, type)
        assertEquals("X-Title: Hello there\nAccept: application/json", args["headers"])
        assertEquals("q=cats\npage=2", args["query"])
    }

    @Test
    fun preflightBlocksTheStepWhenAVariableAddsAHeader() {
        val step = preflight(
            HttpRequestAction.ID,
            mapOf("headers" to "X-Title: %NTITLE"),
            mapOf("NTITLE" to "hello\nAuthorization: Bearer stolen"),
        )

        assertEquals(PreflightStepStatus.BLOCKED, step.status)
        assertTrue(step.warnings.toString(), step.warnings.any { LineListArguments.HEADER_LINE_BREAK_FAILURE in it })
    }

    @Test
    fun preflightLeavesTheStepAloneWhenEveryLineStaysOneLine() {
        val step = preflight(
            "http.get",
            mapOf("headers" to "X-Title: %NTITLE", "query" to "q=%SEARCH"),
            mapOf("NTITLE" to "Hello there", "SEARCH" to "cats"),
        )

        assertEquals(PreflightStepStatus.SIMULATED, step.status)
        assertFalse(step.warnings.toString(), step.warnings.any { "expanded to a line break" in it })
    }
}
