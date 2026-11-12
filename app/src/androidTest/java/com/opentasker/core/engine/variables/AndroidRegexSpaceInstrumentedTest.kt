package com.opentasker.core.engine.variables

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.opentasker.core.engine.VariableStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The set of spaces Tasker's maths strips is Android's regex `\s`, which the JVM tests can't reach
 * since the JVM's `\s` is ASCII only. This checks the spelled-out set against the device's own.
 */
@RunWith(AndroidJUnit4::class)
class AndroidRegexSpaceInstrumentedTest {

    @Test
    fun theSpelledOutSetIsExactlyAndroidsWhitespaceClass() {
        val space = Regex("\\s")
        val differing = (0..0xFFFF).map { Char(it) }.filter { space.matches(it.toString()) != VariableExpander.isAndroidRegexSpace(it) }

        assertEquals(differing.joinToString { "%04x".format(it.code) }, emptyList<Char>(), differing)
    }

    @Test
    fun aNextLineCharacterInsideANumberIsStripped() {
        val value = "4" + Char(NEXT_LINE) + "2"

        assertEquals("42", value.replace(Regex("\\s"), ""))
        assertTrue(VariableStore().apply { set("x", value) }.evaluateCondition("%x > 41"))
    }

    private companion object {
        const val NEXT_LINE = 0x0085
    }
}
