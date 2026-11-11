package com.opentasker.core.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VariableStoreTest {
    @Test
    fun localScopeShadowsRootLocalUntilPopped() {
        val store = VariableStore()
        store.set("value", "root")
        store.pushScope()
        store.set("value", "local")

        assertEquals("local", store.expand("%value"))

        store.popScope()

        assertEquals("root", store.expand("%value"))
    }

    @Test
    fun allLowercaseRootValuesNeverLeakIntoGlobalSnapshot() {
        val store = VariableStore()
        store.set("event", "value")

        assertEquals("value", store.get("event"))
        assertFalse(store.globalSnapshot().containsKey("event"))
    }

    @Test
    fun mixedCaseNameIsGlobalAndSurvivesLocalScope() {
        val store = VariableStore()
        store.pushScope()
        store.set("myVar", "global")
        store.popScope()

        assertEquals("global", store.get("myVar"))
        assertEquals("global", store.globalSnapshot()["myVar"])
    }

    @Test
    fun missingVariablesStayAsWritten() {
        val store = VariableStore()

        assertEquals("before %missing after", store.expand("before %missing after"))
    }

    @Test
    fun legacyExpansionSupportsPolicyAllowedHyphenNames() {
        val store = VariableStore().apply { set("api-token", "present") }

        assertEquals("present", store.expand("%api-token"))
    }

    /**
     * Accepting `-` in variable names made `%count-1` scan as the single name "count-1", so text
     * that used to expand `%count` and keep `-1` silently collapsed to an empty string. A defined
     * hyphenated name still wins; only an undefined one falls back to its longest defined prefix.
     */
    @Test
    fun hyphenatedTextFallsBackToTheLongestDefinedPrefix() {
        val store = VariableStore().apply {
            set("count", "5")
            set("battery", "20")
        }

        // The hyphen and everything after it stay in the text, exactly as before.
        assertEquals("5-1", store.expand("%count-1"))
        assertEquals("20-20 > 0", store.expand("%battery-20 > 0"))
        assertEquals("%unknown-thing", store.expand("%unknown-thing"))
    }

    @Test
    fun aDefinedHyphenatedNameStillWinsOverItsPrefix() {
        val store = VariableStore().apply {
            set("api", "prefix")
            set("api-token", "full")
        }

        assertEquals("full", store.expand("%api-token"))
    }

    @Test
    fun expandsEmbeddedOperatorsInText() {
        val store = VariableStore().apply {
            set("name", "open tasker")
            set("count", "7")
            set("score", "7.8")
        }

        assertEquals("OPEN TASKER-8-7", store.expand("%name(upper)-%count(+1)-%score(//)"))
        assertEquals("8", store.expand("%score(/round)"))
    }

    @Test
    fun expandsArrayTokens() {
        val store = VariableStore().apply {
            setArray("items", listOf("one", "two", "three"))
        }

        assertEquals("3", store.expand("%items(#)"))
        assertEquals("two", store.expand("%items(1)"))
        assertEquals("one,two,three", store.expand("%items(,)"))
    }

    @Test
    fun regexOperatorsDoNotCreateWorkerThreads() {
        val store = VariableStore().apply {
            set("text", "OpenTasker 123")
        }
        val threadCountBefore = regexEvalThreadCount()

        repeat(5) {
            assertEquals("123", store.expand("%text(regex:(\\d+):1)"))
            assertEquals("OpenTasker #", store.expand("%text(replace:\\d+:#)"))
        }

        assertEquals(threadCountBefore, regexEvalThreadCount())
    }

    @Test
    fun unsupportedRegexSyntaxFailsClosed() {
        val store = VariableStore().apply {
            set("text", "ab")
        }

        assertEquals("", store.expand("%text(regex:(?<=a)b:0)"))
        assertEquals("ab", store.expand("%text(replace:(?<=a)b:x)"))
    }

    @Test
    fun conditionParserHandlesOrderedOperatorsAndLogic() {
        val store = VariableStore().apply {
            set("mode", "on")
            set("battery", "50")
            set("wifi", "off")
        }

        assertTrue(store.evaluateCondition("%battery <= 50"))
        assertTrue(store.evaluateCondition("%mode == on && %battery <= 50"))
        assertTrue(store.evaluateCondition("%mode == off || %battery < 60"))
        assertTrue(store.evaluateCondition("(%mode == on && %battery <= 50) || %wifi == on"))
        assertFalse(store.evaluateCondition("%mode == on && %battery > 60"))
    }

    @Test
    fun conditionParserFailsClosedForUnjoinedCompoundComparisons() {
        val store = VariableStore()

        assertFalse(store.evaluateCondition("1 <= 2 != false"))
        assertFalse(store.evaluateCondition("a == a == a"))
    }

    @Test
    fun setAtPathCreatesNestedJsonObject() {
        val store = VariableStore()
        assertTrue(store.setAtPath("Config.theme", "dark"))
        assertEquals("{\"theme\":\"dark\"}", store.get("Config"))
    }

    @Test
    fun setAtPathUpdatesExistingJsonProperty() {
        val store = VariableStore()
        store.set("Config", """{"theme":"light","lang":"en"}""")
        assertTrue(store.setAtPath("Config.theme", "dark"))
        val json = store.get("Config")!!
        assertTrue(json.contains("\"theme\":\"dark\""))
        assertTrue(json.contains("\"lang\":\"en\""))
    }

    @Test
    fun setAtPathCreatesDeepNestedPath() {
        val store = VariableStore()
        assertTrue(store.setAtPath("Data.user.profile.name", "test"))
        val json = store.get("Data")!!
        assertTrue(json.contains("\"name\":\"test\""))
    }

    @Test
    fun setAtPathWritesArrayIndex() {
        val store = VariableStore()
        store.set("Items", """["a","b","c"]""")
        assertTrue(store.setAtPath("Items[1]", "replaced"))
        val json = store.get("Items")!!
        assertTrue(json.contains("\"replaced\""))
        assertTrue(json.contains("\"a\""))
        assertTrue(json.contains("\"c\""))
    }

    @Test
    fun setAtPathGrowsArrayForOutOfBoundsIndex() {
        val store = VariableStore()
        store.set("Items", """["a"]""")
        assertTrue(store.setAtPath("Items[3]", "d"))
        val json = store.get("Items")!!
        assertTrue(json.contains("\"d\""))
    }

    @Test
    fun setAtPathFlatFallback() {
        val store = VariableStore()
        assertTrue(store.setAtPath("simple", "value"))
        assertEquals("value", store.get("simple"))
    }

    @Test
    fun setAtPathRejectsInvalidPath() {
        val store = VariableStore()
        assertFalse(store.setAtPath("", "value"))
        assertFalse(store.setAtPath("base[", "value"))
        assertFalse(store.setAtPath("base[-1]", "value"))
    }

    @Test
    fun setAtPathMixedObjectAndArrayPath() {
        val store = VariableStore()
        store.set("Config", """{"items":["x","y"]}""")
        assertTrue(store.setAtPath("Config.items[0]", "replaced"))
        val json = store.get("Config")!!
        assertTrue(json.contains("\"replaced\""))
        assertTrue(json.contains("\"y\""))
    }

    @Test
    fun globalSensitiveFlagIsMonotonicAcrossPlainRewrite() {
        val store = VariableStore()
        store.set("TOKEN", "secret", sensitive = true)
        assertTrue(store.isSensitive("TOKEN"))

        // A later plain (non-sensitive) write to the same global must not clear the flag; the value
        // stayed derived from a secret, so downgrading it would leak on the next log/trace.
        store.set("TOKEN", "still-secret", sensitive = false)
        assertTrue(store.isSensitive("TOKEN"))
    }

    @Test
    fun arraySensitiveFlagIsMonotonicAcrossPlainRewrite() {
        val store = VariableStore()
        store.setArray("SECRETS", listOf("a", "b"), sensitive = true)
        assertTrue(store.isArraySensitive("SECRETS"))

        store.setArray("SECRETS", listOf("c"), sensitive = false)
        assertTrue(store.isArraySensitive("SECRETS"))
    }

    @Test
    fun sensitiveGlobalFlagSurvivesConcurrentPlainWrites() {
        // Simulate two parallel profile runs racing the same global: one marks it secret while the
        // other repeatedly overwrites it plainly. The flag must never be observed cleared once set.
        repeat(50) {
            val store = VariableStore()
            val start = java.util.concurrent.CountDownLatch(1)
            val secretWriter = Thread {
                start.await()
                store.set("SHARED", "secret", sensitive = true)
            }
            val plainWriter = Thread {
                start.await()
                repeat(200) { i -> store.set("SHARED", "plain-$i", sensitive = false) }
            }
            secretWriter.start()
            plainWriter.start()
            start.countDown()
            secretWriter.join()
            plainWriter.join()
            assertTrue("secret flag was lost on iteration $it", store.isSensitive("SHARED"))
        }
    }

    private fun regexEvalThreadCount(): Int =
        Thread.getAllStackTraces().keys.count { it.name == "regex-eval" }
}
