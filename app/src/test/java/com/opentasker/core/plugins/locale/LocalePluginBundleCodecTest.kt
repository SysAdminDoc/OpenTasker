package com.opentasker.core.plugins.locale

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalePluginBundleCodecTest {
    @Test
    fun decodesPrimitiveJsonObjectAsStringMap() {
        val values = LocalePluginBundleCodec.decodeStringBundle(
            """{"text":"hello","enabled":true,"count":3,"ratio":1.5}"""
        )

        assertEquals("hello", values["text"])
        assertEquals("true", values["enabled"])
        assertEquals("3", values["count"])
        assertEquals("1.5", values["ratio"])
    }

    @Test
    fun rejectsNestedBundleValues() {
        val error = runCatching {
            LocalePluginBundleCodec.decodeStringBundle("""{"nested":{"unsafe":"value"}}""")
        }.exceptionOrNull()

        assertTrue(error is IllegalStateException || error is IllegalArgumentException)
    }

    @Test
    fun rejectsInvalidPackageNames() {
        val error = runCatching {
            LocalePluginBundleCodec.validatePackageName("not a package")
        }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
    }

    @Test
    fun rejectsOversizedBundleJson() {
        val oversized = buildString {
            append("{\"payload\":\"")
            repeat(LocalePluginContract.MAX_BUNDLE_JSON_BYTES) { append('a') }
            append("\"}")
        }

        val error = runCatching {
            LocalePluginBundleCodec.decodeStringBundle(oversized)
        }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
    }

    @Test
    fun encodesBundleJsonDeterministically() {
        val json = LocalePluginBundleCodec.encodeStringBundle(
            mapOf(
                "zeta" to "last",
                "alpha" to "first",
            )
        )

        assertEquals("""{"alpha":"first","zeta":"last"}""", json)
    }

    @Test
    fun sanitizesPrimitiveConfigurationResultValues() {
        val values = LocalePluginBundleCodec.sanitizeBundleValues(
            mapOf(
                "text" to "hello",
                "enabled" to true,
                "count" to 3,
                "ratio" to 1.5,
            )
        )

        assertEquals("hello", values["text"])
        assertEquals("true", values["enabled"])
        assertEquals("3", values["count"])
        assertEquals("1.5", values["ratio"])
    }

    @Test
    fun rejectsNestedConfigurationResultValues() {
        val error = runCatching {
            LocalePluginBundleCodec.sanitizeBundleValues(
                mapOf("nested" to mapOf("unsafe" to "value"))
            )
        }.exceptionOrNull()

        assertTrue(error is IllegalStateException || error is IllegalArgumentException)
    }

    @Test
    fun decodesEveryTypedValue() {
        val values = LocalePluginBundleCodec.decodeBundle(
            """{"text":"hi","tagged":{"string":"there"},"on":{"boolean":true},"version":{"int":1002},
               "big":{"long":4000000000},"half":{"float":0.5},"third":{"double":0.25},
               "args":{"stringArray":["-l","/sdcard"]},"list":{"stringList":["a"]},"missing":{"null":true}}"""
        )

        assertEquals(LocaleBundleValue.Text("hi"), values["text"])
        assertEquals(LocaleBundleValue.Text("there"), values["tagged"])
        assertEquals(LocaleBundleValue.Flag(true), values["on"])
        assertEquals(LocaleBundleValue.IntNumber(1002), values["version"])
        assertEquals(LocaleBundleValue.LongNumber(4_000_000_000L), values["big"])
        assertEquals(LocaleBundleValue.FloatNumber(0.5f), values["half"])
        assertEquals(LocaleBundleValue.DoubleNumber(0.25), values["third"])
        assertEquals(LocaleBundleValue.TextArray(listOf("-l", "/sdcard")), values["args"])
        assertEquals(LocaleBundleValue.TextList(listOf("a")), values["list"])
        assertEquals(LocaleBundleValue.Null, values["missing"])
    }

    @Test
    fun plainJsonValuesStillTravelAsStrings() {
        val values = LocalePluginBundleCodec.decodeBundle("""{"enabled":true,"count":3}""")

        assertEquals(LocaleBundleValue.Text("true"), values["enabled"])
        assertEquals(LocaleBundleValue.Text("3"), values["count"])
    }

    @Test
    fun typedNumbersAcceptQuotedValuesFromVariables() {
        val values = LocalePluginBundleCodec.decodeBundle("""{"version":{"int":" 1002 "},"on":{"boolean":"false"}}""")

        assertEquals(LocaleBundleValue.IntNumber(1002), values["version"])
        assertEquals(LocaleBundleValue.Flag(false), values["on"])
    }

    @Test
    fun rejectsTypedValuesThatDoNotFitTheirType() {
        listOf(
            """{"version":{"int":"twelve"}}""",
            """{"version":{"int":4000000000}}""",
            """{"on":{"boolean":"yes"}}""",
            """{"ratio":{"double":"NaN"}}""",
            """{"ratio":{"float":"Infinity"}}""",
            """{"args":{"stringArray":"not a list"}}""",
            """{"args":{"stringArray":[1,null]}}""",
            """{"two":{"int":1,"long":2}}""",
            """{"odd":{"bytes":"AA=="}}""",
            """{"plain":null}""",
            """{"list":["a","b"]}""",
        ).forEach { json ->
            val error = runCatching { LocalePluginBundleCodec.decodeBundle(json) }.exceptionOrNull()
            assertTrue("$json should be refused", error is IllegalStateException || error is IllegalArgumentException)
        }
    }

    @Test
    fun typedBundlesEncodeBackToTheSameJson() {
        val json = """{"args":{"stringArray":["a","b"]},"missing":{"null":true},"name":"x","on":{"boolean":true},"version":{"int":3}}"""

        val values = LocalePluginBundleCodec.decodeBundle(json)

        assertEquals(json, LocalePluginBundleCodec.encodeBundle(values))
        assertEquals("""{"mode":"quiet"}""", LocalePluginBundleCodec.encodeBundle(mapOf("mode" to LocaleBundleValue.Text("quiet"))))
    }

    @Test
    fun canonicalValuesKeepAStringApartFromANumber() {
        val text = LocalePluginBundleCodec.canonicalValues(LocalePluginBundleCodec.decodeBundle("""{"count":"3"}"""))
        val number = LocalePluginBundleCodec.canonicalValues(LocalePluginBundleCodec.decodeBundle("""{"count":{"int":3}}"""))

        assertTrue(text != number)
    }

    @Test
    fun canonicalValuesSpellOutTheType() {
        assertEquals(mapOf("count" to "\"3\""), LocalePluginBundleCodec.canonicalValues(LocalePluginBundleCodec.decodeBundle("""{"count":"3"}""")))
        assertEquals(mapOf("count" to """{"int":3}"""), LocalePluginBundleCodec.canonicalValues(LocalePluginBundleCodec.decodeBundle("""{"count":{"int":3}}""")))
    }

    @Test
    fun thePluginsSettingsScreenStillGetsABundleHoldingAVariable() {
        val values = LocalePluginBundleCodec.decodeBundleForEditing(
            """{"count":{"int":"%count"},"name":"x","on":{"boolean":true},"bad":{"stringArray":[[1]]},"gone":null}"""
        )

        // The variable goes as its text; only the entries that can't be read at all are left out.
        assertEquals(
            mapOf(
                "count" to LocaleBundleValue.Text("%count"),
                "name" to LocaleBundleValue.Text("x"),
                "on" to LocaleBundleValue.Flag(true),
            ),
            values,
        )
        val typed = """{"version":{"int":3}}"""
        assertEquals(LocalePluginBundleCodec.decodeBundle(typed), LocalePluginBundleCodec.decodeBundleForEditing(typed))
        assertEquals(emptyMap<String, LocaleBundleValue>(), LocalePluginBundleCodec.decodeBundleForEditing("not json"))
    }

    @Test
    fun onlyAVariableInATypedSlotGoesAsText() {
        val longKey = "k".repeat(LocalePluginContract.MAX_BUNDLE_KEY_BYTES + 1)
        val values = LocalePluginBundleCodec.decodeBundleForEditing(
            """{"count":{"long":"{{ n }}"},"":{"int":"%x"},"$longKey":{"int":"%x"},""" +
                """"word":{"int":"abc"},"half":{"int":1.5},"odd":{"bogus":1},"list":{"stringArray":"%x"}}"""
        )

        // A blank or oversized key, a value that isn't a variable, and an unknown or list type all stay out.
        assertEquals(mapOf("count" to LocaleBundleValue.Text("{{ n }}")), values)
    }

    @Test
    fun ofSeveralSettingsScreensTheDefaultOneIsPicked() {
        assertEquals(listOf("b"), preferSingle(listOf("a", "b"), listOf("b")))
        // Nothing to go on, or still more than one, stays ambiguous.
        assertEquals(listOf("a", "b"), preferSingle(listOf("a", "b"), emptyList()))
        assertEquals(listOf("a", "b"), preferSingle(listOf("a", "b"), listOf("a", "b")))
        assertEquals(listOf("a", "b"), preferSingle(listOf("a", "b"), listOf("c")))
    }

    @Test
    fun configurationResultsKeepTheirTypes() {
        val values = LocalePluginBundleCodec.sanitizeTypedValues(
            mapOf(
                "com.termux.tasker.extra.VERSION_CODE" to 1002,
                "com.termux.tasker.extra.EXECUTABLE" to "hello.sh",
                "com.termux.tasker.extra.ARGUMENTS" to null,
                "com.termux.tasker.extra.TERMINAL" to false,
                "since" to 7L,
                "level" to 0.5f,
                "ratio" to 0.25,
                "array" to arrayOf("a", "b"),
                "list" to arrayListOf("c"),
            )
        )

        assertEquals(LocaleBundleValue.IntNumber(1002), values["com.termux.tasker.extra.VERSION_CODE"])
        assertEquals(LocaleBundleValue.Text("hello.sh"), values["com.termux.tasker.extra.EXECUTABLE"])
        assertEquals(LocaleBundleValue.Null, values["com.termux.tasker.extra.ARGUMENTS"])
        assertEquals(LocaleBundleValue.Flag(false), values["com.termux.tasker.extra.TERMINAL"])
        assertEquals(LocaleBundleValue.LongNumber(7L), values["since"])
        assertEquals(LocaleBundleValue.FloatNumber(0.5f), values["level"])
        assertEquals(LocaleBundleValue.DoubleNumber(0.25), values["ratio"])
        assertEquals(LocaleBundleValue.TextArray(listOf("a", "b")), values["array"])
        assertEquals(LocaleBundleValue.TextList(listOf("c")), values["list"])
        assertEquals(values, LocalePluginBundleCodec.decodeBundle(LocalePluginBundleCodec.encodeBundle(values)))
    }

    @Test
    fun configurationResultsRefuseValuesTheyCannotStoreByName() {
        val nested = runCatching {
            LocalePluginBundleCodec.sanitizeTypedValues(mapOf("nested" to mapOf("unsafe" to "value")))
        }.exceptionOrNull()
        val mixedList = runCatching {
            LocalePluginBundleCodec.sanitizeTypedValues(mapOf("items" to arrayListOf("a", 1)))
        }.exceptionOrNull()

        assertTrue(nested is IllegalStateException && nested.message.orEmpty().contains("nested"))
        assertTrue(mixedList is IllegalStateException && mixedList.message.orEmpty().contains("items"))
    }

    @Test
    fun aDeeplyNestedBundleIsRefusedBeforeItIsParsed() {
        val nested = """{"a":""" + "[".repeat(5_000) + "]".repeat(5_000) + "}"
        // kotlinx reads on after a `]`, so this nests 5400 deep while the brackets balance at one.
        val chained = """{"a":""" + "[0]".repeat(5_400) + "}"
        // kotlinx reads a no-break space as a value, so the array goes on after `]` and the comma.
        val spaced = """{"a":""" + ("[0]" + Char(0xA0) + ",").repeat(2_700) + "0}"
        listOf(nested, chained, spaced).forEach { deep ->
            val refused = runCatching { LocalePluginBundleCodec.decodeBundle(deep) }.exceptionOrNull()

            assertTrue(refused.toString(), refused is IllegalArgumentException && refused.message.orEmpty().contains("nests deeper"))
            assertEquals(emptyMap<String, LocaleBundleValue>(), LocalePluginBundleCodec.decodeBundleForEditing(deep))
        }

        val brackets = "[".repeat(5_000)
        assertEquals(
            mapOf("text" to LocaleBundleValue.Text(brackets), "lines" to LocaleBundleValue.TextArray(listOf("a\"]", "b"))),
            LocalePluginBundleCodec.decodeBundle("""{"text":"$brackets","lines":{"stringArray":["a\"]","b"]}}"""),
        )
    }

    @Test
    fun parsesConditionResultCodes() {
        val satisfied = LocalePluginConditionResultParser.parse(
            LocalePluginContract.RESULT_CONDITION_SATISFIED,
            "com.example.plugin",
        )
        val unsatisfied = LocalePluginConditionResultParser.parse(
            LocalePluginContract.RESULT_CONDITION_UNSATISFIED,
            "com.example.plugin",
        )
        val unknown = LocalePluginConditionResultParser.parse(
            LocalePluginContract.RESULT_CONDITION_UNKNOWN,
            "com.example.plugin",
        )
        val unrecognized = LocalePluginConditionResultParser.parse(999, "com.example.plugin")

        assertEquals(LocalePluginConditionState.Satisfied, satisfied.state)
        assertEquals(LocalePluginConditionState.Unsatisfied, unsatisfied.state)
        assertEquals(LocalePluginConditionState.Unknown, unknown.state)
        assertEquals(LocalePluginConditionState.Unknown, unrecognized.state)
        assertTrue(satisfied.satisfied)
        assertTrue(unrecognized.message.contains("unrecognized result code"))
    }

    @Test
    fun conditionStateCacheFallsBackToLastKnownStateForUnknown() {
        val cache = LocalePluginConditionStateCache()
        val key = LocalePluginConditionCacheKey("com.example.plugin", mapOf("mode" to "work"))

        cache.resolve(
            key,
            LocalePluginConditionResultParser.parse(
                LocalePluginContract.RESULT_CONDITION_SATISFIED,
                "com.example.plugin",
            ),
        )
        val resolved = cache.resolve(
            key,
            LocalePluginConditionResultParser.parse(
                LocalePluginContract.RESULT_CONDITION_UNKNOWN,
                "com.example.plugin",
            ),
        )

        assertEquals(LocalePluginConditionState.Satisfied, resolved.state)
        assertTrue(resolved.message.contains("last known satisfied"))
    }

    @Test
    fun conditionStateCacheTreatsUnknownWithoutHistoryAsUnsatisfied() {
        val cache = LocalePluginConditionStateCache()
        val key = LocalePluginConditionCacheKey("com.example.plugin", mapOf("mode" to "work"))

        val resolved = cache.resolve(
            key,
            LocalePluginConditionResultParser.parse(
                LocalePluginContract.RESULT_CONDITION_UNKNOWN,
                "com.example.plugin",
            ),
        )

        assertEquals(LocalePluginConditionState.Unsatisfied, resolved.state)
        assertTrue(resolved.message.contains("No last known result"))
    }

    @Test
    fun conditionStateCacheScopesHistoryByBundle() {
        val cache = LocalePluginConditionStateCache()
        val work = LocalePluginConditionCacheKey("com.example.plugin", mapOf("mode" to "work"))
        val home = LocalePluginConditionCacheKey("com.example.plugin", mapOf("mode" to "home"))

        cache.resolve(
            work,
            LocalePluginConditionResultParser.parse(
                LocalePluginContract.RESULT_CONDITION_SATISFIED,
                "com.example.plugin",
            ),
        )
        val resolved = cache.resolve(
            home,
            LocalePluginConditionResultParser.parse(
                LocalePluginContract.RESULT_CONDITION_UNKNOWN,
                "com.example.plugin",
            ),
        )

        assertEquals(LocalePluginConditionState.Unsatisfied, resolved.state)
    }
}
