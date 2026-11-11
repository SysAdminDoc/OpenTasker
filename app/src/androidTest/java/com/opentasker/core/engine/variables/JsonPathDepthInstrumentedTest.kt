package com.opentasker.core.engine.variables

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.opentasker.core.data.JsonNesting
import com.opentasker.core.engine.VariableStore
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `%json.path` reads through Android's own org.json, which the JVM tests only see as stubs. That
 * reader recurses once per level, so JSON nested tens of thousands deep overflowed the stack, and a
 * StackOverflowError gets past the expander's catch. Past the data depth limit the path now reads as
 * empty, while lenient JSON nested the way real data is still reads.
 */
@RunWith(AndroidJUnit4::class)
class JsonPathDepthInstrumentedTest {
    private fun read(json: String, expr: String = "%json.a"): String =
        VariableExpander().expand(expr, VariableStore().apply { set("json", json) }, ArrayStore())

    @Test
    fun deepJsonReadsAsEmpty() {
        val levels = 50_000
        assertEquals("", read("""{"a":1,"b":${"[".repeat(levels)}${"]".repeat(levels)}}"""))
        // Single-quoted strings hide a `]` from a plain bracket count, never from this one.
        assertEquals("", read("""{'a':1,'b':""" + """[ '"]', """.repeat(levels)))
        // org.json skips a `>` after `:` too, so the quote after it opens a string, not a word.
        assertEquals("", read("""{"a":>"]","b":${"[".repeat(levels)}${"]".repeat(levels)}}"""))
    }

    @Test
    fun theLimitCountsTheObjectAroundThePath() {
        val limit = JsonNesting.MAX_DATA_DEPTH
        assertEquals("", read("""{"a":1,"b":${"[".repeat(limit)}${"]".repeat(limit)}}"""))
        assertEquals("1", read("""{"a":1,"b":${"[".repeat(limit - 1)}${"]".repeat(limit - 1)}}"""))
        assertEquals("x", read("""{"a":>"x"}"""))
    }

    @Test
    fun jsonThatReallyNestsStillReads() {
        assertEquals("1", read("""{"a":1,"b":${"[".repeat(200)}${"]".repeat(200)}}"""))
        assertEquals("[1,2]", read("""{"a":{"b":[1,2]}}""", "%json.a.b"))
        assertEquals("x", read("""{'a':'x' /* note */}"""))
        // org.json stops after the first value, so what follows it is never read.
        assertEquals("x", read("{\"a\":\"x\"}\n" + "[".repeat(50_000)))
    }
}
