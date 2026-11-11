package com.opentasker.core.data

import com.opentasker.core.actions.HomeAssistantWebhookProtocol
import com.opentasker.core.actions.PluginBundleArguments
import com.opentasker.core.contexts.PushContextEvents
import com.opentasker.core.engine.VariableStore
import com.opentasker.core.expressions.TemplateExpressionEngine
import com.opentasker.core.expressions.TemplateScope
import com.opentasker.core.expressions.TemplateValueSource
import com.opentasker.core.plugins.locale.LocalePluginContract
import com.opentasker.core.transfer.ImportBudgetExceededException
import com.opentasker.core.transfer.ImportResourceGuard
import com.opentasker.core.transfer.MacroDroidImporter
import com.opentasker.core.updates.UpdateCheckProtocol
import com.opentasker.core.updates.UpdateCheckResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.nio.charset.StandardCharsets

/**
 * kotlinx reads nested arrays recursively, so about 5000 `[` overflow the stack inside
 * `parseToJsonElement`. Each site that parses text a server, a push or a variable supplies refuses
 * that before parsing, while JSON nested the way real data is still reads. Every site also gets a
 * case just past its own limit, since some of them catch the overflow and would pass the deep case
 * without their guard.
 */
class DeepJsonRefusalTest {
    private fun nested(levels: Int, inner: String = "") = "[".repeat(levels) + inner + "]".repeat(levels)

    /** kotlinx reads a value after `]` as more of the closed array, so this nests while the brackets balance. */
    private fun chained(links: Int) = "[0]".repeat(links)

    @Test
    fun theDepthCountSeesBothShapes() {
        assertEquals(5_000, JsonNesting.depth(nested(5_000)))
        assertEquals(JsonNesting.NOT_JSON, JsonNesting.depth(chained(2)))
        assertEquals(2, JsonNesting.depth("""{"a":[0,1],"b":{"c":"[[[["}}"""))
        // kotlinx reads a no-break space as the start of a value.
        assertEquals(JsonNesting.NOT_JSON, JsonNesting.depth("[0]${Char(0xA0)},[1]"))
        // kotlinx reads down unclosed brackets before it fails, so text that doesn't balance isn't JSON.
        for (unbalanced in listOf("[".repeat(300), "[]]", "][", """{"a":"open}""")) {
            assertEquals(unbalanced, JsonNesting.NOT_JSON, JsonNesting.depth(unbalanced))
        }
    }

    @Test
    fun theLenientCountFollowsOrgJsonsTokenizer() {
        assertEquals(5_000, JsonNesting.lenientDepth(nested(5_000)))
        // Each of these hides a `]` from a count that only knows double-quoted strings.
        assertEquals(300, JsonNesting.lenientDepth("""[ '"]', """.repeat(300)))
        assertEquals(300, JsonNesting.lenientDepth("""[ a", """.repeat(300)))
        assertEquals(600, JsonNesting.lenientDepth("""{"k"=>"]", "j": [""".repeat(300)))
        assertEquals(301, JsonNesting.lenientDepth("""{"a":>"]","b":""" + "[".repeat(300)))
        assertEquals(300, JsonNesting.lenientDepth("""[ /* " */ """.repeat(300)))
        assertEquals(300, JsonNesting.lenientDepth("[ # \"\n".repeat(300)))
        assertEquals(300, JsonNesting.lenientDepth("""["\"]", """.repeat(300)))
        // org.json stops after the first value, so lines after it don't count.
        assertEquals(1, JsonNesting.lenientDepth("{\"a\":1}\n" + nested(300)))
        assertEquals(2, JsonNesting.lenientDepth("""{'a':['[[[[' /* [[ */]}"""))
    }

    @Test
    fun aTemplateJsonPathOverDeepJsonIsMissingInsteadOfOverflowing() {
        val engine = TemplateExpressionEngine()
        for (payload in listOf("""{"a":${nested(5_000)}}""", """{"a":${chained(5_000)}}""", """{"a":${nested(JsonNesting.MAX_DATA_DEPTH)}}""")) {
            val result = engine.expand("{{ payload.a }}", TemplateScope(event = mapOf("payload" to payload)))
            assertEquals("", result.value)
            assertEquals(TemplateValueSource.MISSING, result.traces.single().source)
        }

        val real = engine.expand("{{ payload.a[#] }}", TemplateScope(event = mapOf("payload" to """{"a":${nested(100, "1")}}""")))
        assertEquals("1", real.value)
    }

    @Test
    fun aPathWriteIntoDeepJsonFailsAndLeavesTheVariableAlone() {
        val store = VariableStore()
        for (deep in listOf("""{"a":${nested(5_000)}}""", """{"a":${nested(JsonNesting.MAX_DATA_DEPTH)}}""")) {
            store.set("Data", deep)
            assertFalse(store.setAtPath("Data.b", "x"))
            assertEquals(deep, store.get("Data"))
        }

        store.set("Shallow", """{"a":${nested(100)}}""")
        assertTrue(store.setAtPath("Shallow.b", "x"))
        assertTrue(store.get("Shallow")!!.endsWith(""","b":"x"}"""))
    }

    @Test
    fun aPathWriteIntoTextThatIsntJsonStillReplacesIt() {
        val store = VariableStore()
        for (text in listOf("[ok] done", chained(5_000), "[".repeat(300))) {
            store.set("Status", text)
            assertTrue(store.setAtPath("Status.b", "x"))
            assertEquals("""{"b":"x"}""", store.get("Status"))
        }
    }

    @Test
    fun readingDeepJsonDataFailsClosed() {
        assertNull(StructuredDataReader.read("json", """{"a":${nested(5_000)}}""", "a"))
        assertNull(StructuredDataReader.read("json", """{"a":${chained(5_000)}}""", "a"))
        assertNull(StructuredDataReader.read("json", """{"a":${nested(JsonNesting.MAX_DATA_DEPTH, "1")}}""", "a"))
        // a[0] is 99 levels deep, and an array's values are its items, so the one value is 98 deep.
        assertEquals(listOf(nested(98, "1")), StructuredDataReader.read("json", """{"a":${nested(100, "1")}}""", "a[0]")?.values)
    }

    @Test
    fun aHomeAssistantPayloadOrDataThatNestsTooDeepIsRefusedWithAReason() {
        val base = mapOf("url" to "https://homeassistant.example/api/webhook/abc")
        val depth = HomeAssistantWebhookProtocol.MAX_PAYLOAD_DEPTH
        for ((args, reason) in listOf(
            base + ("payload" to """{"a":${nested(5_000)}}""") to "Home Assistant webhook payload nests deeper than $depth levels",
            base + ("payload" to """{"a":${nested(depth)}}""") to "Home Assistant webhook payload nests deeper than $depth levels",
            base + ("message" to "hi") + ("data" to """{"a":${nested(5_000)}}""") to "Home Assistant data nests deeper than ${depth - 1} levels",
            base + ("message" to "hi") + ("data" to """{"a":${nested(depth - 1)}}""") to "Home Assistant data nests deeper than ${depth - 1} levels",
            base + ("message" to "hi") + ("data" to """{"a":${chained(3_000)}}""") to "Home Assistant data isn't valid JSON",
        )) {
            val refused = HomeAssistantWebhookProtocol.parse(args).exceptionOrNull()
            assertTrue(refused.toString(), refused is IllegalArgumentException && refused.message == reason)
        }

        assertTrue(HomeAssistantWebhookProtocol.parse(base + ("message" to "hi") + ("data" to """{"actions":[{"action":"OPEN"}]}""")).isSuccess)
        assertTrue(HomeAssistantWebhookProtocol.parse(base + ("message" to "hi") + ("data" to """{"a":${nested(depth - 2)}}""")).isSuccess)
    }

    @Test
    fun aPushMessageThatNestsTooDeepIsDropped() {
        // Unclosed, so it fits the 4096 byte limit.
        val deep = """{"topic":"tasks","id":"evt-1","a":""" + "[".repeat(4_000)
        assertNull(PushContextEvents.parseUnifiedPushMessage(deep.toByteArray(StandardCharsets.UTF_8)))
        val closed = """{"topic":"tasks","id":"evt-1","a":${nested(PushContextEvents.MAX_UNIFIED_PUSH_MESSAGE_DEPTH)}}"""
        assertNull(PushContextEvents.parseUnifiedPushMessage(closed.toByteArray(StandardCharsets.UTF_8)))

        val real = """{"topic":"tasks","id":"evt-2","actions":[{"action":"http","headers":{"X":"1"}}]}"""
        assertEquals("evt-2", PushContextEvents.parseUnifiedPushMessage(real.toByteArray(StandardCharsets.UTF_8))?.metadata?.get("eventId"))
    }

    @Test
    fun anUpdateResponseThatNestsTooDeepIsInvalidForThatReason() {
        for (assets in listOf(nested(5_000), nested(UpdateCheckProtocol.MAX_RESPONSE_DEPTH))) {
            val deep = """{"tag_name":"v9.9.9","assets":$assets}"""
            assertEquals(UpdateCheckResult.Invalid("release response nests too deep"), UpdateCheckProtocol.parseLatestRelease(deep, "0.2.95"))
        }
        for (broken in listOf("""{"tag_name":"v9.9.9"} x""", """{"tag_name":"v9.9.9"""")) {
            assertEquals(UpdateCheckResult.Invalid("release response is malformed"), UpdateCheckProtocol.parseLatestRelease(broken, "0.2.95"))
        }
    }

    @Test
    fun pluginSettingsThatNestTooDeepGetNoVariablesFilledIn() {
        val fill = { text: String -> text.replace("%x", "1") }
        assertNull(PluginBundleArguments.expand("""{"a":${chained(5_000)}}""", fill))
        assertNull(PluginBundleArguments.expand("""{"a":${nested(LocalePluginContract.MAX_BUNDLE_DEPTH, "\"%x\"")}}""", fill))
        assertEquals(
            """{"a":${nested(LocalePluginContract.MAX_BUNDLE_DEPTH - 1, "\"1\"")}}""",
            PluginBundleArguments.expand("""{"a":${nested(LocalePluginContract.MAX_BUNDLE_DEPTH - 1, "\"%x\"")}}""", fill),
        )
    }

    @Test
    fun anImportWithAValueRightAfterAClosingBracketIsRefusedBeforeParsing() {
        val raw = """{"macroList":[{"m_name":"Deep","m_GUID":1,"extra":${chained(5_000)},"m_actionList":[],"m_triggerList":[]}]}"""
        try {
            MacroDroidImporter.parse(rawJson = raw, appVersion = "test", importedAtEpochMs = 0)
            fail("Expected a refusal")
        } catch (refused: IllegalArgumentException) {
            assertEquals("Import JSON has a value right after a closing bracket.", refused.message)
        }

        ImportResourceGuard.requireJsonPreflight("""[[0] /* note */ ,[1]]""")
        for (bad in listOf("""[[0] /* note */ [1]]""", "[[0]${Char(0xA0)},[1]]", """{"a":{}"b"}""")) {
            try {
                ImportResourceGuard.requireJsonPreflight(bad)
                fail("Expected a refusal for $bad")
            } catch (refused: IllegalArgumentException) {
                assertEquals("Import JSON has a value right after a closing bracket.", refused.message)
            }
        }
    }

    @Test
    fun aMacroDroidImportCantHideDepthBehindAnUncommentedSlashPair() {
        // MacroDroid's parser has no comments, so `//` is a bare word and the brackets after it nest.
        val raw = """{"macroList":[{"m_name":"Deep","m_GUID":1,"extra":[//,""" + "[".repeat(5_000) + "\n]}]}"
        try {
            MacroDroidImporter.parse(rawJson = raw, appVersion = "test", importedAtEpochMs = 0)
            fail("Expected a refusal")
        } catch (refused: ImportBudgetExceededException) {
            assertEquals("nesting depth", refused.budgetName)
        }
    }
}
