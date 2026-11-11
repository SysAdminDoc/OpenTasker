package com.opentasker.core.engine

import android.content.ContextWrapper
import com.opentasker.core.actions.LocalePluginConditionQueryAction
import com.opentasker.core.actions.LocalePluginSettingAction
import com.opentasker.core.actions.registerActionMetadata
import com.opentasker.core.model.ActionSpec
import com.opentasker.core.model.Task
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * A Locale plugin's bundle is JSON. A variable holding a quote must stay inside the value it was
 * written in, not end the string and add a key the step never had.
 */
class PluginBundleExpansionTest {
    private val received = mutableListOf<Map<String, String>>()

    @Before
    fun setUp() {
        registerActionMetadata()
        received.clear()
        listOf("plugin.locale.fire", "plugin.locale.query").forEach { type ->
            ActionRegistry.register(object : Action {
                override val id = type
                override val category = ActionCategory.APP
                override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
                    received += args
                    return ActionResult.Success
                }
            })
        }
    }

    @After
    fun tearDown() {
        ActionRegistry.register(LocalePluginSettingAction())
        ActionRegistry.register(LocalePluginConditionQueryAction())
    }

    private fun fire(bundleJson: String, type: String = "plugin.locale.fire") = Task(
        name = "Set widget text",
        actions = listOf(
            ActionSpec(type = type, args = mapOf("package" to "org.kustom.widget", "bundleJson" to bundleJson)),
        ),
    )

    private fun run(bundleJson: String, variables: Map<String, String>, type: String = "plugin.locale.fire"): String = runBlocking {
        received.clear()
        val store = VariableStore().apply { variables.forEach { (name, value) -> set(name, value) } }
        val report = TaskRunner(ActionContext(ContextWrapper(null), store)).run(fire(bundleJson, type))
        assertTrue(report.results.toString(), report.success)
        received.single().getValue("bundleJson")
    }

    private fun json(text: String): JsonElement = Json.parseToJsonElement(text)

    @Test
    fun aQuoteInAVariableStaysInsideTheValueItWasWrittenIn() {
        val sent = run("""{"title":"%TITLE"}""", mapOf("TITLE" to """hi","admin":"yes"""))

        assertEquals(json("""{"title":"hi\",\"admin\":\"yes"}"""), json(sent))
    }

    @Test
    fun onlyTheKeysTaskerNamedForReplacementAreFilledIn() {
        val sent = run(
            """{"name":"%TITLE","pattern":"100%TITLE","net.dinglisch.android.tasker.extras.VARIABLE_REPLACE_KEYS":"name "}""",
            mapOf("TITLE" to "Hello"),
        )

        assertEquals(
            json("""{"name":"Hello","pattern":"100%TITLE","net.dinglisch.android.tasker.extras.VARIABLE_REPLACE_KEYS":"name "}"""),
            json(sent),
        )
    }

    @Test
    fun typedSlotsAndArrayItemsAreFilledInAndKeepTheirTypes() {
        val sent = run(
            """{"count":{"int":"%COUNT"},"lines":{"stringArray":["%TITLE","plain"]},"on":{"boolean":true}}""",
            mapOf("COUNT" to "5", "TITLE" to "Hello"),
        )

        assertEquals(
            json("""{"count":{"int":"5"},"lines":{"stringArray":["Hello","plain"]},"on":{"boolean":true}}"""),
            json(sent),
        )
    }

    @Test
    fun aValueThatStartsLikeAConditionalIsSentAsWritten() {
        val sent = run("""{"pattern":"(https?://)?example.com:8080","host":"%HOST"}""", mapOf("HOST" to "example.com"))

        assertEquals(json("""{"pattern":"(https?://)?example.com:8080","host":"example.com"}"""), json(sent))
    }

    @Test
    fun theHostsOwnKeysAreNotFilledIn() {
        val relevant = """"net.dinglisch.android.tasker.RELEVANT_VARIABLES":{"stringArray":["%SCREEN\nScreen text\nWhat's on it"]}"""
        val sent = run("""{"text":"%SCREEN",$relevant}""", mapOf("SCREEN" to "Home"))

        assertEquals(json("""{"text":"Home",$relevant}"""), json(sent))
    }

    @Test
    fun aConditionQueryFillsInItsBundleOneValueAtATime() {
        val sent = run("""{"title":"%TITLE"}""", mapOf("TITLE" to """hi","admin":"yes"""), type = "plugin.locale.query")

        assertEquals(json("""{"title":"hi\",\"admin\":\"yes"}"""), json(sent))
    }

    @Test
    fun aSecretFilledIntoOneValueKeepsTheBundleOutOfTheRunLog() {
        val report = runBlocking {
            val store = VariableStore().apply { set("TOKEN", "s3cr3t-value", sensitive = true) }
            TaskRunner(ActionContext(ContextWrapper(null), store)).run(fire("""{"auth":"%TOKEN","title":"plain"}"""))
        }

        assertTrue(report.results.toString(), report.success)
        assertEquals("<redacted>", report.traces.single().argumentExpansions.single().expandedValue)
        assertFalse(report.traces.toRunLogMessage().contains("s3cr3t-value"))
    }

    @Test
    fun anUnquotedVariableFillsInAsANumberOrAsTextButNeverAsMoreJson() {
        val bundle = """{"count": %COUNT, "on": %ON, "title":"%TITLE"}"""

        assertEquals(
            json("""{"count": 5, "on": true, "title":"hi\",\"admin\":\"yes"}"""),
            json(run(bundle, mapOf("COUNT" to "5", "ON" to "true", "TITLE" to """hi","admin":"yes"""))),
        )
        assertEquals(
            json("""{"count": "5, \"admin\": \"yes\"", "on": "{\"int\":1}", "title":"plain"}"""),
            json(run(bundle, mapOf("COUNT" to """5, "admin": "yes"""", "ON" to """{"int":1}""", "TITLE" to "plain"))),
        )
    }

    @Test
    fun aKeyNamedForReplacementIsFilledInEvenUnderTheLocalePrefix() {
        val key = "com.twofortyfouram.locale.intent.extra.MESSAGE"
        val named = run(
            """{"$key":"%MSG","net.dinglisch.android.tasker.extras.VARIABLE_REPLACE_KEYS":"$key"}""",
            mapOf("MSG" to "Hello"),
        )
        val unnamed = run("""{"$key":"%MSG"}""", mapOf("MSG" to "Hello"))

        assertEquals(json("""{"$key":"Hello","net.dinglisch.android.tasker.extras.VARIABLE_REPLACE_KEYS":"$key"}"""), json(named))
        assertEquals(json("""{"$key":"%MSG"}"""), json(unnamed))
    }

    @Test
    fun aDeeplyNestedBundleIsSentAsWrittenInsteadOfEndingTheRun() {
        val nested = listOf(100_000, 5_000).map { levels -> """{"a":""" + "[".repeat(levels) + "]".repeat(levels) + "}" }
        val chained = """{"a":""" + "[0]".repeat(5_400) + "}"
        val spaced = """{"a":""" + ("[0]" + Char(0xA0) + ",").repeat(2_700) + "0}"

        (nested + chained + spaced).forEach { deep -> assertEquals(deep, run(deep, emptyMap())) }
    }

    @Test
    fun settingsThatOnlyBecomeJsonOnceAVariableIsInAreSentAsWritten() {
        val variables = mapOf("PAYLOAD" to """{"title":"hi"}""", "COUNT" to """1, "admin": "yes"""")

        listOf("%PAYLOAD", """{"count": {{ global.COUNT }}}""").forEach { bundle ->
            assertEquals(bundle, run(bundle, variables))
        }
    }

    @Test
    fun preflightSaysWhenItCantReadTheSettings() {
        val step = PreflightRunner.preflightTask(task = fire("%PAYLOAD"), inputs = PreflightInputs()).tasks.single().steps.single()

        assertTrue(step.warnings.toString(), step.warnings.any { "aren't a JSON object" in it })
    }

    @Test
    fun preflightWarnsAboutTheKeysItFillsInAndNotTheOthers() {
        val step = PreflightRunner.preflightTask(
            task = fire(
                """{"name":"{{ global.MISSINGNAME }}","other":"{{ global.MISSINGOTHER }}",""" +
                    """"net.dinglisch.android.tasker.extras.VARIABLE_REPLACE_KEYS":"name"}""",
            ),
            inputs = PreflightInputs(),
        ).tasks.single().steps.single()

        assertTrue(step.warnings.toString(), step.warnings.any { "MISSINGNAME" in it })
        assertFalse(step.warnings.toString(), step.warnings.any { "MISSINGOTHER" in it })
    }
}
