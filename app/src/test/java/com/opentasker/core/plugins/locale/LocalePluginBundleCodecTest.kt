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
