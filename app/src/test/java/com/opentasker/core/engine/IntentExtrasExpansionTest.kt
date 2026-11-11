package com.opentasker.core.engine

import android.content.ContextWrapper
import com.opentasker.core.actions.IntentDispatchParseResult
import com.opentasker.core.actions.IntentDispatchPolicy
import com.opentasker.core.actions.IntentExtrasLines
import com.opentasker.core.actions.LaunchIntentAction
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
 * Send Intent's extras are one key=type:value per line. A notification title that holds a line
 * break must not add an extra the step never listed, in a run or in Preflight.
 */
class IntentExtrasExpansionTest {
    private val received = mutableListOf<Map<String, String>>()

    @Before
    fun setUp() {
        registerActionMetadata()
        received.clear()
        ActionRegistry.register(object : Action {
            override val id = "intent.launch"
            override val category = ActionCategory.APP
            override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
                received += args
                return ActionResult.Success
            }
        })
    }

    @After
    fun tearDown() {
        ActionRegistry.register(LaunchIntentAction())
    }

    private fun sendIntent(extras: String) = Task(
        name = "Forward title",
        actions = listOf(
            ActionSpec(
                type = "intent.launch",
                args = mapOf("package" to "com.example.receiver", "action" to "com.example.TITLE", "extras" to extras),
            ),
        ),
    )

    private fun run(extras: String, variables: Map<String, String>): TaskRunReport = runBlocking {
        val store = VariableStore().apply { variables.forEach { (name, value) -> set(name, value) } }
        TaskRunner(ActionContext(ContextWrapper(null), store)).run(sendIntent(extras))
    }

    private fun preflight(extras: String, variables: Map<String, String>): PreflightStep =
        PreflightRunner.preflightTask(
            task = sendIntent(extras),
            inputs = PreflightInputs(globalVariables = variables),
        ).tasks.single().steps.single()

    private fun extraKeys(args: Map<String, String>): List<String> =
        (IntentDispatchPolicy.parse(args) as IntentDispatchParseResult.Valid).plan.extras.map { it.key }

    @Test
    fun aRunStopsTheStepWhenAVariableAddsALine() {
        val report = run("title=string:%NTITLE", mapOf("NTITLE" to "x\nevil=bool:true"))

        assertTrue(received.isEmpty())
        assertFalse(report.success)
        val failure = report.results.single() as ActionResult.Failure
        assertEquals(IntentExtrasLines.LINE_BREAK_FAILURE, failure.message)
    }

    @Test
    fun aRunStopsTheStepWhenATemplateAddsALine() {
        run("title=string:{{ global.NTITLE }}", mapOf("NTITLE" to "x\r\nevil=bool:true"))

        assertTrue(received.isEmpty())
    }

    @Test
    fun aRunExpandsEachWrittenLineOnItsOwn() {
        val report = run(
            "title=string:%NTITLE\ncount=int:%COUNT\r\n\nquiet=bool:{{ global.QUIET }}",
            mapOf("NTITLE" to "Hello there", "COUNT" to "3", "QUIET" to "true"),
        )

        assertTrue(report.success)
        val args = received.single()
        assertEquals("title=string:Hello there\ncount=int:3\n\nquiet=bool:true", args["extras"])
        assertEquals(listOf("title", "count", "quiet"), extraKeys(args))
    }

    @Test
    fun preflightBlocksTheStepWhenAVariableAddsALine() {
        val step = preflight("title=string:%NTITLE", mapOf("NTITLE" to "x\nevil=bool:true"))

        assertEquals(PreflightStepStatus.BLOCKED, step.status)
        assertTrue(step.warnings.toString(), step.warnings.any { IntentExtrasLines.LINE_BREAK_FAILURE in it })
    }

    @Test
    fun preflightSimulatesTheStepWhenEveryLineStaysOneLine() {
        val step = preflight("title=string:%NTITLE\ncount=int:%COUNT", mapOf("NTITLE" to "Hello there", "COUNT" to "3"))

        assertEquals(PreflightStepStatus.SIMULATED, step.status)
        assertFalse(step.warnings.toString(), step.warnings.any { IntentExtrasLines.LINE_BREAK_FAILURE in it })
    }
}
