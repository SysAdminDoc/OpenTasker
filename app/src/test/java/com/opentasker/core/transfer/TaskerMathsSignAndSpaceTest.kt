package com.opentasker.core.transfer

import com.opentasker.core.engine.VariableStore
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Two more corners of how Tasker 6.7.8's maths reads one value (decompiled `Expr.d` and `yd`): a
 * sign right after `0x` is an operator, so `0x-1` isn't a number, and Tasker strips Android's `\s`,
 * which takes a no-break space off a scraped value but leaves a zero width space on, which keeps
 * it from being one.
 */
class TaskerMathsSignAndSpaceTest {
    private fun store(value: String) = VariableStore().apply { set("x", value) }

    @Test
    fun aSignAfterHexIsNotANumber() {
        listOf("0x-1", "0x+1", "0X-f").forEach { value ->
            val variables = store(value)
            assertFalse(value, variables.evaluateCondition("%x < 100"))
            assertFalse(value, variables.evaluateCondition("%x > -100"))
            assertFalse(value, variables.evaluateCondition("%x is_odd"))
        }
        assertTrue(store("0x1").evaluateCondition("%x is_odd"))
    }

    @Test
    fun aNoBreakSpaceOnANumberIsStrippedButAZeroWidthSpaceIsNot() {
        val padded = store("42" + Char(NO_BREAK_SPACE))
        assertTrue(padded.evaluateCondition("%x > 41"))
        assertTrue(padded.evaluateCondition("%x is_even"))

        val zeroWidth = store("42" + Char(ZERO_WIDTH_SPACE))
        assertFalse(zeroWidth.evaluateCondition("%x > 41"))
        assertFalse(zeroWidth.evaluateCondition("%x is_even"))

        // A trailing newline from command output is Java's \s, which Tasker strips too.
        val newline = store("42\n")
        assertTrue(newline.evaluateCondition("%x > 41"))
        assertTrue(newline.evaluateCondition("%x is_even"))
    }

    @Test
    fun aTextComparisonStillTrimsTheValue() {
        assertTrue(store("42" + Char(NO_BREAK_SPACE)).evaluateCondition("%x == 42"))
    }

    private companion object {
        const val NO_BREAK_SPACE = 0x00A0
        const val ZERO_WIDTH_SPACE = 0x200B
    }
}
