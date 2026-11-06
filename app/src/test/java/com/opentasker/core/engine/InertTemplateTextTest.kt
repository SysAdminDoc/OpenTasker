package com.opentasker.core.engine

import android.content.ContextWrapper
import com.opentasker.core.model.ActionSpec
import com.opentasker.core.model.Task
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * A stranger's text reaches tasks through variables, so what it says must stay words. Legacy `%var`
 * expansion runs before the template stage, which used to read `{{ ... }}` inside a value.
 */
class InertTemplateTextTest {
    private val received = mutableListOf<Map<String, String>>()
    private val attack = "{{ API_TOKEN }}"

    @Before
    fun setUp() {
        received.clear()
        ActionRegistry.register(object : Action {
            override val id = "test.inert.forward"
            override val category = ActionCategory.FLOW
            override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
                received += args
                return ActionResult.Success
            }
        })
    }

    private fun store() = VariableStore().apply {
        set("API_TOKEN", "hunter2", true)
        set("sms_body", attack, true)
        set("greeting", "hello")
    }

    private fun run(
        args: Map<String, String>,
        condition: String? = null,
        events: Map<String, String> = mapOf("sms_body" to attack, "sms_kind" to "sms"),
    ): TaskRunReport = runBlocking {
        TaskRunner(ActionContext(ContextWrapper(null), store(), eventVariables = events)).run(
            Task(name = "Forward", actions = listOf(ActionSpec(type = "test.inert.forward", args = args, condition = condition))),
        )
    }

    @Test
    fun aMessageForwardedThroughALegacyVariableIsNotRunAsATemplate() {
        val report = run(mapOf("text" to "%sms_body"))

        assertTrue(report.success)
        assertEquals(attack, received.single()["text"])
    }

    @Test
    fun aMessageNextToARealTemplateIsStillNotRun() {
        run(mapOf("text" to "{{ event.sms_kind }}: %sms_body"))

        assertEquals("sms: $attack", received.single()["text"])
    }

    @Test
    fun aMessageReadThroughTheTemplateStageIsNotRunAgain() {
        run(mapOf("text" to "{{ event.sms_body }}"))

        assertEquals(attack, received.single()["text"])
    }

    @Test
    fun whatTheTaskAuthorTypedStillExpands() {
        run(mapOf("text" to "%greeting {{ greeting | upper }} {{ API_TOKEN }}"))

        assertEquals("hello HELLO hunter2", received.single()["text"])
    }

    @Test
    fun aMessageCannotSteerAConditionThroughASecret() {
        run(mapOf("text" to "ran"), condition = "%sms_body == hunter2")

        assertTrue("The message was compared as the secret's value", received.isEmpty())
    }

    @Test
    fun theRunPreviewAgreesWithTheRun() {
        val report = PreflightRunner.preflightTask(
            task = Task(
                name = "Forward",
                actions = listOf(ActionSpec(type = "log", args = mapOf("message" to "%share_text"))),
            ),
            inputs = PreflightInputs(
                eventVariables = mapOf("share_text" to attack),
                globalVariables = mapOf("API_TOKEN" to "hunter2"),
                secretGlobalNames = setOf("API_TOKEN"),
            ),
        )

        val step = report.tasks.single().steps.single()
        assertEquals(attack, step.expandedArguments["message"])
        assertFalse(step.warnings.toString(), step.warnings.any { "API_TOKEN" in it })
    }
}
