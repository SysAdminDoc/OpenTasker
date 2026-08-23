package com.opentasker.core.engine

import android.content.ContextWrapper
import com.opentasker.core.model.ActionSpec
import com.opentasker.core.model.Task
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskRunnerConditionTest {
    @Test
    fun skipsActionWhenConditionEvaluatesFalse() = runBlocking {
        var ran = false
        ActionRegistry.register(
            object : Action {
                override val id = "test.condition.skip"
                override val category = ActionCategory.FLOW

                override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
                    ran = true
                    return ActionResult.Success
                }
            }
        )

        val variables = VariableStore().apply { set("mode", "off") }
        val report = TaskRunner(ActionContext(ContextWrapper(null), variables)).run(
            Task(
                name = "Conditional",
                actions = listOf(
                    ActionSpec(
                        type = "test.condition.skip",
                        condition = "%mode == on",
                    )
                ),
            )
        )

        assertFalse(ran)
        assertTrue(report.success)
        assertTrue(report.results.single() is ActionResult.Skip)
    }

    @Test
    fun propagatesCoroutineCancellationFromActions() = runBlocking {
        ActionRegistry.register(
            object : Action {
                override val id = "test.condition.cancel"
                override val category = ActionCategory.FLOW

                override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
                    throw CancellationException("cancelled")
                }
            }
        )

        val result = runCatching {
            TaskRunner(ActionContext(ContextWrapper(null), VariableStore())).run(
                Task(
                    name = "Cancellation",
                    actions = listOf(ActionSpec(type = "test.condition.cancel")),
                )
            )
        }

        assertTrue(result.exceptionOrNull() is CancellationException)
    }

    @Test
    fun runsActionWhenTemplateConditionEvaluatesTrue() = runBlocking {
        var ran = false
        ActionRegistry.register(
            object : Action {
                override val id = "test.condition.template"
                override val category = ActionCategory.FLOW

                override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
                    ran = true
                    return ActionResult.Success
                }
            }
        )

        val variables = VariableStore().apply {
            set("mode", "on")
            set("payload", """{"status":"ready"}""")
        }
        val report = TaskRunner(ActionContext(ContextWrapper(null), variables)).run(
            Task(
                name = "Template condition",
                actions = listOf(
                    ActionSpec(
                        type = "test.condition.template",
                        condition = "{{ mode }} == on",
                    ),
                    ActionSpec(
                        type = "test.condition.template",
                        condition = "{{ payload.status }} == ready",
                    ),
                ),
            )
        )

        assertTrue(ran)
        assertTrue(report.success)
        assertEquals(2, report.results.size)
    }

    @Test
    fun skipsActionWhenTemplateConditionHasWarnings() = runBlocking {
        var ran = false
        ActionRegistry.register(
            object : Action {
                override val id = "test.condition.template.warning"
                override val category = ActionCategory.FLOW

                override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
                    ran = true
                    return ActionResult.Success
                }
            }
        )

        val variables = VariableStore().apply { set("mode", "on") }
        val report = TaskRunner(ActionContext(ContextWrapper(null), variables)).run(
            Task(
                name = "Template condition warning",
                actions = listOf(
                    ActionSpec(
                        type = "test.condition.template.warning",
                        condition = "{{ mode | shell:\"echo\" }} == on",
                    )
                ),
            )
        )

        assertFalse(ran)
        assertTrue(report.success)
        assertTrue(report.results.single() is ActionResult.Skip)
    }

    @Test
    fun expandsActionArgumentsWithOperators() = runBlocking {
        var capturedMessage: String? = null
        ActionRegistry.register(
            object : Action {
                override val id = "test.argument.expand"
                override val category = ActionCategory.FLOW

                override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
                    capturedMessage = args["message"]
                    return ActionResult.Success
                }
            }
        )

        val variables = VariableStore().apply {
            set("count", "2")
            setArray("items", listOf("alpha", "beta"))
        }

        val report = TaskRunner(ActionContext(ContextWrapper(null), variables)).run(
            Task(
                name = "Argument expansion",
                actions = listOf(
                    ActionSpec(
                        type = "test.argument.expand",
                        args = mapOf("message" to "Count %count(+3), second %items(1)"),
                    )
                ),
            )
        )

        assertTrue(report.success)
        assertEquals("Count 5, second beta", capturedMessage)
    }

    @Test
    fun expandsTemplateActionArgumentsAndRecordsTrace() = runBlocking {
        var capturedMessage: String? = null
        ActionRegistry.register(
            object : Action {
                override val id = "test.argument.template"
                override val category = ActionCategory.FLOW

                override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
                    capturedMessage = args["message"]
                    return ActionResult.Success
                }
            }
        )

        val variables = VariableStore().apply {
            set("name", "Ada")
            set("count", "2")
            set("payload", """{"user":{"city":"London"}}""")
            setArray("items", listOf("alpha", "beta"))
        }

        val report = TaskRunner(
            ActionContext(
                app = ContextWrapper(null),
                variables = variables,
                eventVariables = mapOf("source" to "calendar"),
            ),
        ).run(
            Task(
                name = "Template expansion",
                actions = listOf(
                    ActionSpec(
                        type = "test.argument.template",
                        args = mapOf(
                            "message" to "Hi {{ name | upper }} {{ count | add:3 }} {{ payload.user.city }} {{ items[1] }} {{ event.source }}",
                        ),
                    )
                ),
            )
        )

        assertTrue(report.success)
        assertEquals("Hi ADA 5 London beta calendar", capturedMessage)
        val expansion = report.traces.single().argumentExpansions.single()
        assertEquals("message", expansion.argName)
        assertEquals(5, expansion.expressions.size)
        assertEquals("message=Hi ADA 5 London beta calendar", report.traces.single().expandedArgSummary)
    }

    @Test
    fun templateWarningsAreCarriedIntoActionTraceSummary() = runBlocking {
        var capturedMessage: String? = null
        ActionRegistry.register(
            object : Action {
                override val id = "test.argument.template.warning"
                override val category = ActionCategory.FLOW

                override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
                    capturedMessage = args["message"]
                    return ActionResult.Success
                }
            }
        )

        val variables = VariableStore().apply { set("name", "Ada") }

        val report = TaskRunner(ActionContext(ContextWrapper(null), variables)).run(
            Task(
                name = "Template warning",
                actions = listOf(
                    ActionSpec(
                        type = "test.argument.template.warning",
                        args = mapOf(
                            "message" to "{{ name | shell:\"echo\" }}",
                            "token" to "{{ name }}",
                        ),
                    )
                ),
            )
        )

        val trace = report.traces.single()
        assertEquals("{{ name | shell:\"echo\" }}", capturedMessage)
        assertTrue(trace.templateWarnings.any { it.contains("Unknown template function") })
        assertTrue(trace.toSummaryLine().contains("message={{ name | shell:\"echo\" }}"))
        assertTrue(trace.toSummaryLine().contains("token=<redacted>"))
        assertTrue(trace.toSummaryLine().contains("template warnings: 1"))
    }

    // Covers the condition syntax now emitted by TaskerXmlImport for imported Tasker
    // <ConditionList> Is Set/Not Set/Matches conditions (op codes 12/13/2/3).

    @Test
    fun runsActionWhenIsSetConditionMatchesNonEmptyVariable() = runBlocking {
        var ran = false
        ActionRegistry.register(
            object : Action {
                override val id = "test.condition.isset.run"
                override val category = ActionCategory.FLOW
                override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
                    ran = true
                    return ActionResult.Success
                }
            }
        )

        val variables = VariableStore().apply { set("text", "set a timer") }
        val report = TaskRunner(ActionContext(ContextWrapper(null), variables)).run(
            Task(
                name = "IsSet",
                actions = listOf(ActionSpec(type = "test.condition.isset.run", condition = "%text is_set")),
            )
        )

        assertTrue(ran)
        assertTrue(report.results.single() is ActionResult.Success)
    }

    @Test
    fun skipsActionWhenIsSetConditionSeesUnsetVariable() = runBlocking {
        var ran = false
        ActionRegistry.register(
            object : Action {
                override val id = "test.condition.isset.skip"
                override val category = ActionCategory.FLOW
                override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
                    ran = true
                    return ActionResult.Success
                }
            }
        )

        val variables = VariableStore()
        val report = TaskRunner(ActionContext(ContextWrapper(null), variables)).run(
            Task(
                name = "IsSetUnset",
                actions = listOf(ActionSpec(type = "test.condition.isset.skip", condition = "%text is_set")),
            )
        )

        assertFalse(ran)
        assertTrue(report.results.single() is ActionResult.Skip)
    }

    @Test
    fun runsActionWhenNotSetConditionSeesUnsetVariable() = runBlocking {
        var ran = false
        ActionRegistry.register(
            object : Action {
                override val id = "test.condition.notset.run"
                override val category = ActionCategory.FLOW
                override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
                    ran = true
                    return ActionResult.Success
                }
            }
        )

        val variables = VariableStore()
        val report = TaskRunner(ActionContext(ContextWrapper(null), variables)).run(
            Task(
                name = "NotSet",
                actions = listOf(ActionSpec(type = "test.condition.notset.run", condition = "%text not_set")),
            )
        )

        assertTrue(ran)
        assertTrue(report.results.single() is ActionResult.Success)
    }

    @Test
    fun runsActionWhenMatchesConditionSatisfiesWildcard() = runBlocking {
        var ran = false
        ActionRegistry.register(
            object : Action {
                override val id = "test.condition.matches.run"
                override val category = ActionCategory.FLOW
                override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
                    ran = true
                    return ActionResult.Success
                }
            }
        )

        val variables = VariableStore().apply { set("do", "view_url") }
        val report = TaskRunner(ActionContext(ContextWrapper(null), variables)).run(
            Task(
                name = "Matches",
                actions = listOf(ActionSpec(type = "test.condition.matches.run", condition = "%do ~ view_url")),
            )
        )

        assertTrue(ran)
        assertTrue(report.results.single() is ActionResult.Success)
    }

    @Test
    fun skipsActionWhenDoesntMatchConditionFindsWildcardMatch() = runBlocking {
        var ran = false
        ActionRegistry.register(
            object : Action {
                override val id = "test.condition.notmatches.skip"
                override val category = ActionCategory.FLOW
                override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
                    ran = true
                    return ActionResult.Success
                }
            }
        )

        val variables = VariableStore().apply { set("json", "{\"say\":\"hello\"}") }
        val report = TaskRunner(ActionContext(ContextWrapper(null), variables)).run(
            Task(
                name = "NotMatches",
                actions = listOf(ActionSpec(type = "test.condition.notmatches.skip", condition = "%json !~ *\"say\":*")),
            )
        )

        assertFalse(ran)
        assertTrue(report.results.single() is ActionResult.Skip)
    }
}
